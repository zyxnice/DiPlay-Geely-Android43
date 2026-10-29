package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.InvalidCipherTextException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One audio/video stream's authenticated decryptor and cumulative diagnostics.
 * Its native choice is fixed at construction; pairing/control retain AirPlayCrypto.
 * Neither implementation modifies the caller's key, nonce, AAD or ciphertext.
 */
class MediaFrameCrypto internal constructor(
    private val nativeEnabled: Boolean,
    private val onDiagnostic: (String) -> Unit,
    private val nativeOpen: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray?,
) {
    constructor(nativeEnabled: Boolean, onDiagnostic: (String) -> Unit = {}) : this(
        nativeEnabled,
        onDiagnostic,
        NativeChaCha::open,
    )

    /** Counts are cumulative per stream; durations cover this decryptor, not socket receive or delivery. */
    data class Snapshot(
        val attempts: Long,
        val nativeOpens: Long,
        val javaOpens: Long,
        val nativeFallbacks: Long,
        val authFailures: Long,
        val totalNanos: Long,
        val maxNanos: Long,
    )

    private val attempts = AtomicLong()
    private val nativeOpens = AtomicLong()
    private val javaOpens = AtomicLong()
    private val nativeFallbacks = AtomicLong()
    private val authFailures = AtomicLong()
    private val totalNanos = AtomicLong()
    private val maxNanos = AtomicLong()
    private val reportedNative = AtomicBoolean()
    private val reportedJava = AtomicBoolean()

    /** Authenticate before returning plaintext; rejection is propagated to the existing stream handler. */
    fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        // The first native attempt includes one-time loading/self-test; compare warmed-up windows.
        val started = System.nanoTime()
        try {
            if (nativeEnabled) {
                val nativeResult = nativeOpen(key, nonce, ciphertextAndTag, aad)
                if (nativeResult != null) {
                    nativeOpens.incrementAndGet()
                    if (reportedNative.compareAndSet(false, true)) report("media crypto path=native selfTest=passed")
                    return nativeResult
                }
                nativeFallbacks.incrementAndGet()
            }
            val output = AirPlayCrypto.chachaOpen(key, nonce, ciphertextAndTag, aad)
            javaOpens.incrementAndGet()
            if (reportedJava.compareAndSet(false, true)) {
                val reason = if (nativeEnabled) NativeChaCha.failureReason ?: "input not handled by native" else "disabled"
                report("media crypto path=java nativeRequested=$nativeEnabled reason=$reason")
            }
            return output
        } catch (error: InvalidCipherTextException) {
            authFailures.incrementAndGet()
            throw error
        } finally {
            val elapsed = System.nanoTime() - started
            attempts.incrementAndGet()
            totalNanos.addAndGet(elapsed)
            var previous = maxNanos.get()
            while (elapsed > previous && !maxNanos.compareAndSet(previous, elapsed)) previous = maxNanos.get()
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        attempts.get(), nativeOpens.get(), javaOpens.get(), nativeFallbacks.get(),
        authFailures.get(), totalNanos.get(), maxNanos.get(),
    )

    private fun report(message: String) {
        // Diagnostic output cannot turn successful authentication into a media failure.
        runCatching { onDiagnostic(message) }
    }
}
