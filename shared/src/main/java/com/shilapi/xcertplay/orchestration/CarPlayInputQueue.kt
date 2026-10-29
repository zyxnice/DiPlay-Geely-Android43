package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.concurrent.Executor

/**
 * Serial input admission on the controller's existing executor. Only a still-pending MOVE at
 * the tail can be replaced. Contact transitions and commands are barriers, so a slow socket
 * cannot turn a queued release into a stuck press or reorder Siri/media commands around it.
 * [executor] must be asynchronous and serial; its lifetime remains owned by the controller.
 */
internal class CarPlayInputQueue(
    private val executor: Executor,
    private val nanoTime: () -> Long = System::nanoTime,
    private val report: (String) -> Unit = {},
) {
    private val lock = Any()
    private var tailMove: Touch? = null
    private var closed = false
    // Counters below are read/written only by the serial worker, never by the UI thread.
    private var windowStartNs = nanoTime()
    private var dispatched = 0
    private var coalesced = 0
    private var stale = 0
    private var maxQueueNs = 0L
    private var maxSendNs = 0L

    private class Touch(
        val sessionToken: Any,
        var contacts: List<AirPlayContact>,
        var queuedAtNs: Long,
        val send: (List<AirPlayContact>) -> Boolean,
    ) {
        var replaced = 0
    }

    /**
     * [sessionToken] uses identity, not equality. [move] is true only for ACTION_MOVE with
     * unchanged contacts. [send] must reject a stale/closed session and returns whether sent.
     * Returns admission success, not proof of delivery to the phone. Inputs are snapshotted.
     */
    fun touch(sessionToken: Any, contacts: List<AirPlayContact>, move: Boolean,
              send: (List<AirPlayContact>) -> Boolean): Boolean = synchronized(lock) {
        if (closed) return false
        val snapshot = contacts.toList()
        val now = nanoTime()
        val pending = tailMove
        if (move && pending != null && pending.sessionToken === sessionToken &&
            sameContactState(pending.contacts, snapshot)) {
            pending.contacts = snapshot
            pending.queuedAtNs = now
            pending.replaced++
            return true
        }
        val task = Touch(sessionToken, snapshot, now, send)
        tailMove = if (move) task else null
        try {
            executor.execute {
                val accepted = synchronized(lock) {
                    if (tailMove === task) tailMove = null
                    !closed
                }
                if (accepted) dispatch(task)
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (tailMove === task) tailMove = null
            false
        }
    }

    /** Commands always form a barrier; they share the same worker/order as touch reports. */
    fun command(action: () -> Unit): Boolean = synchronized(lock) {
        if (closed) return false
        tailMove = null
        try {
            executor.execute { if (synchronized(lock) { !closed }) action() }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        tailMove = null
    }

    private fun dispatch(task: Touch) {
        val started = nanoTime()
        val sent = task.send(task.contacts)
        val ended = nanoTime()
        dispatched++
        coalesced += task.replaced
        if (!sent) stale++
        maxQueueNs = maxOf(maxQueueNs, started - task.queuedAtNs)
        maxSendNs = maxOf(maxSendNs, ended - started)
        if (ended - windowStartNs < 5_000_000_000L) return
        report("input queue reports=$dispatched coalesced=$coalesced notSent=$stale " +
            "queueMaxMs=${maxQueueNs / 1_000_000} sendMaxMs=${maxSendNs / 1_000_000}")
        windowStartNs = ended
        dispatched = 0
        coalesced = 0
        stale = 0
        maxQueueNs = 0
        maxSendNs = 0
    }

    private fun sameContactState(first: List<AirPlayContact>, second: List<AirPlayContact>): Boolean =
        first.size == second.size && first.indices.all {
            first[it].id == second[it].id && first[it].down == second[it].down
        }
}
