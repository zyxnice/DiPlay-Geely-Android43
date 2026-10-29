package com.shilapi.xcertplay.media

/**
 * C's short output/close fence. Closing waits only for an in-flight releaseOutputBuffer call,
 * never for codec dequeue/configuration or a worker join. Once close returns, this decoder cannot
 * submit another buffer for rendering onto a surface now owned by the next stream generation.
 */
internal class VideoOutputGate {
    private val lock = Any()
    private var closed = false

    fun release(requestRender: Boolean, releaseBuffer: (Boolean) -> Unit): Boolean = synchronized(lock) {
        val render = requestRender && !closed
        releaseBuffer(render)
        render
    }

    fun close() = synchronized(lock) { closed = true }
}
