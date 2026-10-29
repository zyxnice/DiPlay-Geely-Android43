package com.shilapi.xcertplay.airplay

/** C decrypt-worker statistics; the TCP reader has its own StreamReceiveStats and shares no timers. */
internal class ScreenWorkStats(
    private val queue: ScreenReceiveQueue,
    private val report: (String) -> Unit,
    private val cryptoDetails: () -> String,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNs()
    private var decryptCount = 0L
    private var decryptNs = 0L
    private var decryptMaxNs = 0L
    private var processMaxNs = 0L
    private var workerStaleDrops = 0L

    fun decrypted(elapsedNs: Long) {
        decryptCount++
        decryptNs += elapsedNs.coerceAtLeast(0)
        decryptMaxNs = maxOf(decryptMaxNs, elapsedNs)
    }

    fun processed(elapsedNs: Long) { processMaxNs = maxOf(processMaxNs, elapsedNs) }
    fun expiredAfterDecrypt() { workerStaleDrops++ }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        val snapshot = queue.snapshot()
        runCatching {
            report("VideoQueue: queued=${snapshot.messages} bytes=${snapshot.bytes} " +
                "peakMessages=${snapshot.peakMessages} peakBytes=${snapshot.peakBytes} " +
                "overflowDrops=${snapshot.overflowDrops} staleDrops=${snapshot.staleDrops} " +
                "workerStaleDrops=$workerStaleDrops ageMaxMs=${snapshot.maxAgeNs / 1_000_000} " +
                "processMaxUs=${processMaxNs / 1000} decryptCount=$decryptCount " +
                "decryptAvgUs=${if (decryptCount == 0L) 0 else decryptNs / decryptCount / 1000} " +
                "decryptMaxUs=${decryptMaxNs / 1000} cryptoCumulative=${cryptoDetails()} ended=$ended")
        }
        windowStart = now
        decryptCount = 0
        decryptNs = 0
        decryptMaxNs = 0
        processMaxNs = 0
    }
}
