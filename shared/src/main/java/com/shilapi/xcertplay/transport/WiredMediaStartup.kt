package com.shilapi.xcertplay.transport

/**
 * Tracks the first media TCP handshake after wired StartSession. When StartSession preceded USB
 * OUT readiness, the phone may exhaust NDP before the queued replies can be delivered. Allow those
 * replies a short grace period, then reannounce once on the same control channel. A TCP acceptance
 * permanently disarms this policy for the attachment; it never restarts an established session.
 * All methods are called by the single iAP2 control worker, using monotonic milliseconds.
 */
internal class WiredMediaStartup {
    enum class Action { NONE, REANNOUNCE, TIMED_OUT }

    private var firstStartMillis: Long? = null
    private var awaitingOutput = false
    private var outputReadyMillis: Long? = null
    private var reannounced = false
    private var connected = false
    val isWatching: Boolean get() = !connected

    /** The attachment owner rejected expiry because TCP won the race or this generation ended. */
    fun disarm() { connected = true }

    /** Later phone availability announcements do not extend the first connection deadline. */
    fun startSent(nowMillis: Long, outputReady: Boolean) {
        if (firstStartMillis != null) return
        firstStartMillis = nowMillis
        awaitingOutput = !outputReady
    }

    fun poll(nowMillis: Long, outputReady: Boolean, tcpAccepted: Boolean): Action {
        if (tcpAccepted) connected = true
        if (connected) return Action.NONE
        val started = firstStartMillis ?: return Action.NONE
        if (nowMillis - started >= FIRST_TCP_TIMEOUT_MILLIS) return Action.TIMED_OUT
        if (!awaitingOutput || reannounced || !outputReady) return Action.NONE
        val readyAt = outputReadyMillis ?: nowMillis.also { outputReadyMillis = it }
        if (nowMillis - readyAt < REPLY_GRACE_MILLIS) return Action.NONE
        reannounced = true
        return Action.REANNOUNCE
    }

    companion object {
        const val POLL_MILLIS = 250L
        private const val REPLY_GRACE_MILLIS = 500L
        private const val FIRST_TCP_TIMEOUT_MILLIS = 25_000L
    }
}
