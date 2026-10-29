package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSendStatsTest {
    @Test fun separatesLockWaitFromSocketWriteAndResetsEachWindow() {
        var now = 0L
        val stats = EventSendStats { now }
        stats.record(EventSendStats.Stage.LOCK_WAIT, 100_000_000)
        stats.record(EventSendStats.Stage.WRITE, 2_000_000)
        stats.record(EventSendStats.Stage.WRITE, 4_000_000)
        assertNull(stats.takeIfDue())
        now = 5_000_000_000
        val line = stats.takeIfDue()!!
        assertTrue(line.contains("LOCK_WAITUs=100000/100000 n=1"))
        assertTrue(line.contains("WRITEUs=3000/4000 n=2"))
        now *= 2
        assertNull(stats.takeIfDue())
    }
}
