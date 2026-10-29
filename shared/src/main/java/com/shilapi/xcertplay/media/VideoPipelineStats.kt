package com.shilapi.xcertplay.media

/** Worker-owned timings. Output age ends at dequeue, never at physical display presentation. */
internal class VideoPipelineStats(
    private val report: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    enum class Stage {
        QUEUE_AGE, INPUT_WAIT, INPUT_COPY, INPUT_SUBMIT, OUTPUT_POLL, CODEC_AGE, OUTPUT_RELEASE,
    }
    private val counts = LongArray(Stage.entries.size)
    private val totals = LongArray(Stage.entries.size)
    private val maxima = LongArray(Stage.entries.size)
    private var started = nanoTime()
    private var decoded = 0L
    private var rendered = 0L
    private var superseded = 0L

    fun record(stage: Stage, elapsedNs: Long) {
        if (elapsedNs < 0) return
        val index = stage.ordinal
        counts[index]++
        totals[index] += elapsedNs
        maxima[index] = maxOf(maxima[index], elapsedNs)
    }

    fun output(render: Boolean) {
        decoded++
        if (render) rendered++
    }

    fun superseded() { superseded++ }

    fun flush(ended: Boolean = false) {
        val now = nanoTime()
        if (!ended && now - started < 5_000_000_000L) return
        if (counts.all { it == 0L } && decoded == 0L) {
            started = now
            return
        }
        val fields = Stage.entries.joinToString(" ") { stage ->
            val index = stage.ordinal
            val average = if (counts[index] == 0L) 0L else totals[index] / counts[index]
            "${stage.name}Us=${average / 1000}/${maxima[index] / 1000}"
        }
        runCatching { report("pipeline avg/max $fields decoded=$decoded rendered=$rendered " +
            "superseded=$superseded ended=$ended") }
        counts.fill(0)
        totals.fill(0)
        maxima.fill(0)
        decoded = 0
        rendered = 0
        superseded = 0
        started = now
    }
}
