package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WiredMediaStartupTest {
    @Test
    fun lateOutputReannouncesOnceAfterReplyGrace() {
        val startup = WiredMediaStartup()
        startup.startSent(1_000, outputReady = false)
        assertEquals(WiredMediaStartup.Action.NONE, startup.poll(13_000, false, false))
        assertEquals(WiredMediaStartup.Action.NONE, startup.poll(14_000, true, false))
        assertEquals(WiredMediaStartup.Action.NONE, startup.poll(14_499, true, false))
        assertEquals(WiredMediaStartup.Action.REANNOUNCE, startup.poll(14_500, true, false))
        assertEquals(WiredMediaStartup.Action.NONE, startup.poll(20_000, true, false))
    }

    @Test
    fun tcpDuringReplyGracePreventsReannouncementAndTimeout() {
        val startup = WiredMediaStartup()
        startup.startSent(0, false)
        startup.poll(10_000, true, false)
        assertEquals(WiredMediaStartup.Action.NONE, startup.poll(10_500, true, true))
        // The attachment latch remains disarmed even if its first session has since closed.
        assertEquals(WiredMediaStartup.Action.NONE, startup.poll(90_000, true, false))
        assertFalse(startup.isWatching)
    }

    @Test
    fun outputAlreadyReadyDoesNotReannounce() {
        val startup = WiredMediaStartup()
        startup.startSent(0, true)
        assertEquals(WiredMediaStartup.Action.NONE, startup.poll(5_000, true, false))
        assertEquals(WiredMediaStartup.Action.TIMED_OUT, startup.poll(25_000, true, false))
    }

    @Test
    fun repeatedAvailabilityDoesNotExtendFirstTcpDeadline() {
        val startup = WiredMediaStartup()
        startup.startSent(1_000, false)
        startup.startSent(24_000, true)
        assertEquals(WiredMediaStartup.Action.NONE, startup.poll(25_999, false, false))
        assertEquals(WiredMediaStartup.Action.TIMED_OUT, startup.poll(26_000, false, false))
    }

    @Test
    fun beforeStartCannotTimeoutOrReannounce() {
        val startup = WiredMediaStartup()
        assertEquals(WiredMediaStartup.Action.NONE, startup.poll(90_000, true, false))
    }

    @Test
    fun tcpAtDeadlineTakesPrecedenceOverTimeout() {
        val startup = WiredMediaStartup()
        startup.startSent(0, false)
        assertEquals(WiredMediaStartup.Action.NONE, startup.poll(25_000, true, true))
    }
}
