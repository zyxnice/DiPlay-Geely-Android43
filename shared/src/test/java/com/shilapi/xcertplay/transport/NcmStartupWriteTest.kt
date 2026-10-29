package com.shilapi.xcertplay.transport

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NcmStartupWriteTest {
    @Test fun retainsStartupBlockAcrossEndpointNotReadyAndDoesNotExtendNormalWrites() {
        var now = 0L
        var waits = 0
        var acceptedAttempts = 0
        var calls = 0
        val writer = NcmStartupWrite(512, { now }, { now += it })
        val result = writer.write(114, 2_000, { waits++ }, { _, attempts -> acceptedAttempts = attempts }) { timeout ->
            calls++
            now += timeout
            if (calls < 6) 0 else 114
        }
        assertEquals(114, result)
        assertEquals(6, acceptedAttempts)
        assertEquals(1, waits)
        calls = 0
        assertEquals(0, writer.write(80, 200, { fail("Already ready") }, { _, _ -> fail("Already ready") }) {
            calls++
            assertEquals(200, it)
            0
        })
        assertEquals(1, calls)
    }

    @Test fun aPartialNtbIsReturnedOnceAndNeverReplayed() {
        var calls = 0
        val writer = NcmStartupWrite(512, { 0L }, { fail("Partial data must not retry") })
        assertEquals(23, writer.write(114, 100, { fail("Not a zero write") }, { _, _ -> fail("Not complete") }) {
            calls++
            23
        })
        assertEquals(1, calls)
    }

    @Test fun permanentlyUnreadyEndpointHasAnOverallDeadline() {
        var now = 0L
        val writer = NcmStartupWrite(512, { now }, { now += it }, 150)
        try {
            writer.write(114, 100, {}, { _, _ -> fail("Not ready") }) { timeout ->
                assertTrue(timeout in 1..100)
                now += timeout
                0
            }
            fail("Expected bounded startup failure")
        } catch (expected: IOException) {
            assertEquals(150L, now)
        }
    }

    @Test fun immediateFailureBacksOffAndCancellationStopsBeforeAnotherWrite() {
        var now = 0L
        var calls = 0
        val writer = NcmStartupWrite(512, { now }, { now += it })
        val cancelled = IOException("closed")
        try {
            writer.write(114, 100, {}, { _, _ -> fail("Not ready") }) {
                calls++
                if (calls == 2) throw cancelled
                -1
            }
            fail("Expected cancellation")
        } catch (actual: IOException) {
            assertTrue(actual === cancelled)
            assertEquals(20L, now)
            assertEquals(2, calls)
        }
    }

    @Test fun opaqueErrorsNeverReplayMultiPacketOrExactBoundaryNtb() {
        for (blockBytes in listOf(512, 513, 16_384, 20_000)) {
            var calls = 0
            val writer = NcmStartupWrite(512, { 0L }, { fail("Unsafe replay") })
            assertEquals(-1, writer.write(blockBytes, 2_000, {}, { _, _ -> fail("Not complete") }) {
                calls++
                -1
            })
            assertEquals(1, calls)
        }
    }
}
