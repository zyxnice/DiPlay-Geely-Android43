package com.shilapi.xcertplay.transport

import java.io.IOException

/**
 * Keeps an initial single-short-packet NTB until bulk OUT accepts it. A negative Android bulk
 * result can hide partial progress across USB packets, so a larger or exact-boundary NTB must
 * never be replayed here. The caller serializes writes and checks cancellation before each attempt.
 * Positive short writes also return to the transport owner. Ready writes keep their normal deadline.
 */
internal class NcmStartupWrite(
    private val maxPacketBytes: Int,
    private val nowMillis: () -> Long,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val startupTimeoutMillis: Long = 15_000L,
) {
    init { require(maxPacketBytes > 0) }
    @Volatile
    var ready = false
        private set

    fun write(
        blockBytes: Int,
        timeoutMillis: Int,
        onWaiting: () -> Unit,
        onReady: (elapsedMillis: Long, attempts: Int) -> Unit,
        transfer: (timeoutMillis: Int) -> Int,
    ): Int {
        if (ready) return transfer(timeoutMillis)
        val started = nowMillis()
        val deadline = started + startupTimeoutMillis
        var attempts = 0
        var waitingReported = false
        while (true) {
            val remaining = deadline - nowMillis()
            if (remaining <= 0) throw IOException("NCM bulk OUT did not become ready within ${startupTimeoutMillis}ms")
            attempts++
            val transferred = transfer(minOf(timeoutMillis.toLong(), remaining).toInt())
            if (transferred > 0) {
                if (transferred == blockBytes) {
                    ready = true
                    onReady(nowMillis() - started, attempts)
                }
                return transferred
            }
            // Below wMaxPacketSize this NTB is one USB short packet; multi-packet errors do not
            // expose enough progress through bulkTransfer() to make a whole-block retry safe.
            if (blockBytes >= maxPacketBytes) return transferred
            if (!waitingReported) {
                waitingReported = true
                onWaiting()
            }
            // Some firmware returns an immediate error instead of waiting for the USB deadline.
            // Keep this bounded retry from spinning; a real detach is detected by the next check.
            val pauseMillis = minOf(20L, deadline - nowMillis())
            if (pauseMillis > 0) pause(pauseMillis)
        }
    }
}
