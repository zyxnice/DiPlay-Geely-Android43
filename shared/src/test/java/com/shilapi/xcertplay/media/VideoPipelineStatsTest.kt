package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPipelineStatsTest {
    @Test fun windowReportsIndependentStagesAndDoesNotCallSubmissionPhysicalDisplay() {
        var now = 0L
        val lines = mutableListOf<String>()
        val stats = VideoPipelineStats(lines::add, { now })
        stats.record(VideoPipelineStats.Stage.INPUT_WAIT, 30_000_000)
        stats.record(VideoPipelineStats.Stage.INPUT_WAIT, 10_000_000)
        stats.record(VideoPipelineStats.Stage.OUTPUT_RELEASE, 2_000_000)
        stats.output(false)
        stats.superseded()
        stats.output(true)
        stats.flush()
        assertTrue(lines.isEmpty())
        now = 5_000_000_000
        stats.flush()
        assertTrue(lines.single().contains("INPUT_WAITUs=20000/30000"))
        assertTrue(lines.single().contains("OUTPUT_RELEASEUs=2000/2000"))
        assertTrue(lines.single().contains("decoded=2 rendered=1 superseded=1"))
        stats.flush(true)
        assertEquals(1, lines.size)
        stats.record(VideoPipelineStats.Stage.QUEUE_AGE, 1_000_000)
        stats.flush(true)
        assertTrue(lines.last().contains("INPUT_WAITUs=0/0"))
        assertTrue(lines.last().contains("QUEUE_AGEUs=1000/1000"))
    }
}
