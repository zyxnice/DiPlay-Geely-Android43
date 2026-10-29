package com.shilapi.xcertplay.airplay

/** Event-write-lock owned counters; formatting/logging happens only once per active five seconds. */
internal class EventSendStats(private val nanoTime: () -> Long = System::nanoTime) {
    enum class Stage { LOCK_WAIT, ENCODE, TRACE, ENCRYPT, WRITE }
    private val counts = LongArray(Stage.entries.size)
    private val sums = LongArray(Stage.entries.size)
    private val maxima = LongArray(Stage.entries.size)
    private var started = nanoTime()

    fun record(stage: Stage, nanos: Long) {
        val index = stage.ordinal
        counts[index]++
        sums[index] += nanos.coerceAtLeast(0)
        maxima[index] = maxOf(maxima[index], nanos)
    }

    fun takeIfDue(): String? {
        val now = nanoTime()
        if (now - started < 5_000_000_000L || counts.all { it == 0L }) return null
        val line = "event send avg/max " + Stage.entries.joinToString(" ") {
            val i = it.ordinal
            val average = if (counts[i] == 0L) 0 else sums[i] / counts[i] / 1000
            "${it.name}Us=$average/${maxima[i] / 1000} n=${counts[i]}"
        }
        counts.fill(0)
        sums.fill(0)
        maxima.fill(0)
        started = now
        return line
    }
}
