package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOutputBatchTest {
    @Test fun failedReplacementRetiresOldIndexAndLeavesCodecTeardownToReclaimNewIndex() {
        val released = mutableListOf<Int>()
        val batch = VideoOutputBatch { index, _, _, _, _ ->
            released.add(index)
            throw IllegalStateException("release failed; owner must tear down codec")
        }
        batch.offer(2, 100, 0, 10)
        try { batch.offer(4, 200, 0, 10) } catch (_: IllegalStateException) { }
        batch.finish(false)
        assertEquals(listOf(2), released)
    }

    @Test fun burstReleasesOldDecodedPicturesAndRendersOnlyNewestWithItsMetadata() {
        val released = mutableListOf<String>()
        val batch = VideoOutputBatch { index, pts, flags, bytes, render ->
            released.add("$index/$pts/$flags/$bytes/$render")
        }
        assertFalse(batch.offer(2, 100L, 0, 200))
        assertTrue(batch.offer(4, 200L, 1, 400))
        assertTrue(batch.offer(6, 300L, 0, 600))
        batch.finish(true)
        batch.finish(true)
        assertEquals(listOf("2/100/0/200/false", "4/200/1/400/false", "6/300/0/600/true"), released)
    }

    @Test fun isolatedFrameIsRenderedAtEndOfSamePassAndNeverRetainedForNextPass() {
        val released = mutableListOf<Pair<Int, Boolean>>()
        val batch = VideoOutputBatch { index, _, _, _, render -> released.add(index to render) }
        batch.offer(0, 1, 0, 10)
        batch.finish(true)
        batch.offer(0, 2, 0, 20)
        batch.finish(true)
        assertEquals(listOf(0 to true, 0 to true), released)
    }

    @Test fun shutdownReleasesHeldBufferWithoutRenderingAndFailureIsNotRetried() {
        val released = mutableListOf<Boolean>()
        val batch = VideoOutputBatch { _, _, _, _, render -> released.add(render) }
        batch.offer(0, 1, 0, 10)
        batch.finish(false)
        assertEquals(listOf(false), released)
        var calls = 0
        val failing = VideoOutputBatch { _, _, _, _, _ -> calls++; throw IllegalStateException("codec closed") }
        failing.offer(1, 2, 0, 10)
        try { failing.finish(true) } catch (_: IllegalStateException) { }
        failing.finish(false)
        assertEquals(1, calls)
    }
}
