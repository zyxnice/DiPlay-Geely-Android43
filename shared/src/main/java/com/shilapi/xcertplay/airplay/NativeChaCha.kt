package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.InvalidCipherTextException

/** Optional media-only JNI implementation. Loading and RFC checks happen once, on first use. */
internal object NativeChaCha {
    private const val LIBRARY = "xcertplay_chacha"
    private const val TAG_SIZE = 16
    private const val AUTH_FAILED = -1

    @Volatile
    var failureReason: String? = null
        private set

    val available: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY)
            selfTest().also { valid ->
                if (!valid) failureReason = "RFC 8439 authentication self-test failed"
            }
        } catch (error: LinkageError) {
            failureReason = "native library unavailable: ${error.javaClass.simpleName}"
            false
        } catch (error: SecurityException) {
            failureReason = "native library blocked: ${error.javaClass.simpleName}"
            false
        }
    }

    /**
     * Returns null only when the native path is unavailable or cannot handle the input.
     * A tag mismatch throws and must never be retried as a successful fallback.
     * Unlike the reference implementation, ciphertext is unchanged on every path.
     */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (!available || key.size != 32 || nonce.size != 12 || sealed.size < TAG_SIZE || aad.size > 1024) {
            return null
        }
        val output = ByteArray(sealed.size - TAG_SIZE)
        val result = nativeOpen(key, nonce, aad, sealed, output)
        if (result == AUTH_FAILED) throw InvalidCipherTextException("mac check in ChaCha20Poly1305 failed")
        return if (result == output.size) output else null
    }

    /** RFC 8439 section 2.8.2 pins both the plaintext and rejection of a changed tag. */
    private fun selfTest(): Boolean {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val sealed = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691",
        )
        val expected = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
            "sunscreen would be it.").toByteArray(Charsets.US_ASCII)
        val original = sealed.copyOf()
        val output = ByteArray(expected.size)
        if (nativeOpen(key, nonce, aad, sealed, output) != expected.size ||
            !output.contentEquals(expected) || !sealed.contentEquals(original)
        ) return false
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        val beforeRejected = sealed.copyOf()
        output.fill(0x55.toByte())
        return nativeOpen(key, nonce, aad, sealed, output) == AUTH_FAILED &&
            output.all { it == 0x55.toByte() } && sealed.contentEquals(beforeRejected)
    }

    private external fun nativeOpen(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        sealed: ByteArray,
        output: ByteArray,
    ): Int

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
