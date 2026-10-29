package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MediaFrameCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { (it + 10).toByte() }
    private val aad = ByteArray(128) { (it * 3).toByte() }
    private val plaintext = ByteArray(129) { (it * 7).toByte() }

    @Test
    fun disabledNativeNeverInvokesBackendAndPreservesCiphertext() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        val original = sealed.copyOf()
        val crypto = MediaFrameCrypto(false, {}, { _, _, _, _ -> error("native must not load") })

        assertArrayEquals(plaintext, crypto.open(key, nonce, sealed, aad))
        assertArrayEquals(original, sealed)
        assertEquals(1L, crypto.snapshot().javaOpens)
        assertEquals(0L, crypto.snapshot().nativeFallbacks)
    }

    @Test
    fun unavailableNativeFallsBackWithoutChangingCiphertext() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        val original = sealed.copyOf()
        val crypto = MediaFrameCrypto(true, {}, { _, _, _, _ -> null })

        assertArrayEquals(plaintext, crypto.open(key, nonce, sealed, aad))
        assertArrayEquals(original, sealed)
        assertEquals(1L, crypto.snapshot().nativeFallbacks)
        assertEquals(1L, crypto.snapshot().javaOpens)
    }

    @Test
    fun nativeAuthenticationRejectionNeverFallsBackEvenForValidJavaCiphertext() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        val original = sealed.copyOf()
        val crypto = MediaFrameCrypto(true, {}, { _, _, _, _ ->
            throw InvalidCipherTextException("native rejection")
        })

        expectRejected { crypto.open(key, nonce, sealed, aad) }
        assertArrayEquals(original, sealed)
        assertEquals(1L, crypto.snapshot().authFailures)
        assertEquals(0L, crypto.snapshot().nativeFallbacks)
        assertEquals(0L, crypto.snapshot().javaOpens)
    }

    @Test
    fun fallbackRejectsChangedKeyNonceAadCiphertextAndTag() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        val crypto = MediaFrameCrypto(true, {}, { _, _, _, _ -> null })
        expectRejected { crypto.open(changed(key), nonce, sealed, aad) }
        expectRejected { crypto.open(key, changed(nonce), sealed, aad) }
        expectRejected { crypto.open(key, nonce, sealed, changed(aad)) }
        expectRejected { crypto.open(key, nonce, changed(sealed), aad) }
        expectRejected { crypto.open(key, nonce, changed(sealed, sealed.lastIndex), aad) }
        assertEquals(5L, crypto.snapshot().authFailures)
    }

    @Test
    fun emptyPlaintextIsAuthenticatedAndTruncatedTagRejected() {
        val crypto = MediaFrameCrypto(true, {}, { _, _, _, _ -> null })
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, ByteArray(0), aad)
        assertArrayEquals(ByteArray(0), crypto.open(key, nonce, sealed, aad))
        expectRejected { crypto.open(key, nonce, sealed.copyOf(15), aad) }
    }

    @Test
    fun nativeResultIsCountedAndDiagnosticFailureDoesNotBreakPlayback() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        val crypto = MediaFrameCrypto(true, { error("diagnostic unavailable") }, { k, n, data, associated ->
            AirPlayCrypto.chachaOpen(k, n, data, associated)
        })
        assertArrayEquals(plaintext, crypto.open(key, nonce, sealed, aad))
        val stats = crypto.snapshot()
        assertEquals(1L, stats.nativeOpens)
        assertEquals(0L, stats.javaOpens)
        assertEquals(1L, stats.attempts)
        assertTrue(stats.totalNanos >= 0)
        assertEquals(stats.totalNanos, stats.maxNanos)
    }

    @Test
    fun concurrentStreamsDoNotShareCryptoStateOrLoseCounters() {
        val reported = AtomicInteger()
        val crypto = MediaFrameCrypto(true, { reported.incrementAndGet() }, { _, _, _, _ -> null })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 100).map { sequence ->
                Callable {
                    val frameNonce = AirPlayCrypto.nonce64(sequence.toLong())
                    val body = ByteArray(sequence * 17) { (it + sequence).toByte() }
                    val sealed = AirPlayCrypto.chachaSeal(key, frameNonce, body, aad)
                    val original = sealed.copyOf()
                    assertArrayEquals(body, crypto.open(key, frameNonce, sealed, aad))
                    assertArrayEquals(original, sealed)
                }
            }
            executor.invokeAll(tasks).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(100L, crypto.snapshot().attempts)
        assertEquals(100L, crypto.snapshot().javaOpens)
        assertEquals(100L, crypto.snapshot().nativeFallbacks)
        assertEquals(1, reported.get())
    }

    private fun changed(bytes: ByteArray, index: Int = 0): ByteArray = bytes.copyOf().also {
        it[index] = (it[index].toInt() xor 1).toByte()
    }

    private fun expectRejected(action: () -> Unit) {
        try {
            action()
            fail("unauthenticated frame was accepted")
        } catch (_: InvalidCipherTextException) {
            // The public error contract is the same on native and Java paths.
        }
    }
}
