package com.shilapi.xcertplay.airplay

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class BytesHexCompatibilityTest {
    @Test fun fastHexPreservesTracePayloadForEveryByteAndEmptyInput() {
        val allBytes = ByteArray(256) { it.toByte() }
        val legacy = allBytes.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
        assertEquals(legacy, allBytes.toHexString())
        assertEquals("", ByteArray(0).toHexString())
        assertEquals("00010f107f80feff", byteArrayOf(0, 1, 15, 16, 127, -128, -2, -1).toHexString())
    }
}
