package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the real JNI module when a host-built xcertplay_chacha is on java.library.path.
 * Skips on ordinary JVM runs without that module; host success is not API-18 device acceptance.
 */
class NativeChaChaHostTest {
    @Test
    fun nativeMatchesBouncyCastleAcrossBlockBoundariesAndLargeFrames() {
        assumeTrue("host JNI module not provisioned", NativeChaCha.available)
        val random = Random(8439)
        val key = ByteArray(32).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        for (length in listOf(0, 1, 15, 16, 17, 63, 64, 65, 127, 128, 129, 1400, 18200, 65537, 1024 * 1024)) {
            for (aadLength in listOf(0, 8, 128, 1024)) {
                val plain = ByteArray(length).also(random::nextBytes)
                val aad = ByteArray(aadLength).also(random::nextBytes)
                val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
                val original = sealed.copyOf()
                assertArrayEquals(plain, NativeChaCha.open(key, nonce, sealed, aad))
                assertArrayEquals(original, sealed)
                sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
                val rejected = sealed.copyOf()
                try {
                    NativeChaCha.open(key, nonce, sealed, aad)
                    fail("changed tag accepted length=$length aad=$aadLength")
                } catch (_: InvalidCipherTextException) {
                    assertArrayEquals(rejected, sealed)
                }
            }
        }
    }

    @Test
    fun unsupportedNativeInputsReturnFallbackWithoutTouchingSource() {
        assumeTrue("host JNI module not provisioned", NativeChaCha.available)
        val key = ByteArray(32)
        val nonce = ByteArray(12)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, byteArrayOf(1))
        val original = sealed.copyOf()
        assertNull(NativeChaCha.open(key.copyOf(31), nonce, sealed, ByteArray(0)))
        assertNull(NativeChaCha.open(key, nonce.copyOf(11), sealed, ByteArray(0)))
        assertNull(NativeChaCha.open(key, nonce, sealed, ByteArray(1025)))
        assertNull(NativeChaCha.open(key, nonce, ByteArray(15), ByteArray(0)))
        assertArrayEquals(original, sealed)
    }

    @Test
    fun mediaWrapperUsesNativeConcurrentlyWithoutStateSharing() {
        assumeTrue("host JNI module not provisioned", NativeChaCha.available)
        val key = ByteArray(32) { it.toByte() }
        val crypto = MediaFrameCrypto(true)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 128).map { frame ->
                Callable {
                    val nonce = AirPlayCrypto.nonce64(frame.toLong())
                    val aad = ByteArray(128) { (it + frame).toByte() }
                    val plain = ByteArray(frame * 31) { it.toByte() }
                    val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
                    assertArrayEquals(plain, crypto.open(key, nonce, sealed, aad))
                }
            }
            pool.invokeAll(tasks).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(128L, crypto.snapshot().nativeOpens)
        assertEquals(0L, crypto.snapshot().nativeFallbacks)
    }
}
