package com.shilapi.xcertplay.media

/**
 * Owns at most one already-decoded output index during a nonblocking dequeue pass. Superseded
 * decoded pictures can be returned without rendering; encoded reference frames are never dropped.
 * The decoder worker must finish each pass, including on shutdown. No buffer crosses passes.
 */
internal class VideoOutputBatch(private val release: (Int, Long, Int, Int, Boolean) -> Unit) {
    private var index = -1
    private var presentationTimeUs = 0L
    private var flags = 0
    private var bytes = 0

    /** Returns true when an older decoded output was released without rendering. */
    fun offer(nextIndex: Int, nextPresentationTimeUs: Long, nextFlags: Int, nextBytes: Int): Boolean {
        val replaced = index >= 0
        finish(false)
        index = nextIndex
        presentationTimeUs = nextPresentationTimeUs
        flags = nextFlags
        bytes = nextBytes
        return replaced
    }

    fun finish(render: Boolean) {
        if (index < 0) return
        val releasing = index
        index = -1 // A failed release belongs to codec teardown, never retry an ambiguous release.
        release(releasing, presentationTimeUs, flags, bytes, render)
    }
}
