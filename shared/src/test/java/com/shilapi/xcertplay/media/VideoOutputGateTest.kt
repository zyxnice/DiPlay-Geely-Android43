package com.shilapi.xcertplay.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class VideoOutputGateTest {
    @Test fun closePreventsLateOutputFromRenderingButStillReleasesItsBuffer() {
        val gate = VideoOutputGate()
        assertTrue(gate.release(true) { assertTrue(it) })
        gate.close()
        var released = false
        assertFalse(gate.release(true) { allowed ->
            assertFalse(allowed)
            released = true
        })
        assertTrue(released)
    }

    @Test fun closeWaitsForInFlightReleaseBeforeGivingTheSurfaceToANewStream() {
        val gate = VideoOutputGate()
        val insideRelease = CountDownLatch(1)
        val finishRelease = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeReturned = AtomicBoolean(false)
        val releaseCompletedBeforeClose = AtomicBoolean(false)
        val release = Thread {
            gate.release(true) {
                insideRelease.countDown()
                finishRelease.await(2, TimeUnit.SECONDS)
                releaseCompletedBeforeClose.set(!closeReturned.get())
            }
        }
        val close = Thread {
            closeStarted.countDown()
            gate.close()
            closeReturned.set(true)
        }
        try {
            release.start()
            assertTrue(insideRelease.await(2, TimeUnit.SECONDS))
            close.start()
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            assertFalse(closeReturned.get())
            finishRelease.countDown()
            release.join(2_000)
            close.join(2_000)
            assertFalse(release.isAlive)
            assertFalse(close.isAlive)
            assertTrue(releaseCompletedBeforeClose.get())
            assertTrue(closeReturned.get())
            assertFalse(gate.release(true) { assertFalse(it) })
        } finally {
            finishRelease.countDown()
            release.join(2_000)
            close.join(2_000)
        }
    }

    @Test fun outputFailureReleasesTheFenceSoShutdownCanComplete() {
        val gate = VideoOutputGate()
        try {
            gate.release(true) { throw IllegalStateException("codec failure") }
            fail("Expected simulated codec failure")
        } catch (_: IllegalStateException) { }
        gate.close()
        assertFalse(gate.release(true) { assertFalse(it) })
    }
}
