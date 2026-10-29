package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayInputQueueTest {
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private fun contact(x: Double, down: Boolean = true, id: Int = 0) =
        listOf(AirPlayContact(id, x, 0.5, down))

    @Test fun slowSenderRetainsPressLatestMoveAndReleaseInOrder() {
        val worker = ManualExecutor()
        val queue = CarPlayInputQueue(worker)
        val owner = Any()
        val sent = mutableListOf<List<AirPlayContact>>()
        val send: (List<AirPlayContact>) -> Boolean = { sent.add(it); true }
        queue.touch(owner, contact(0.0), false, send)
        repeat(100) { queue.touch(owner, contact(it / 100.0), true, send) }
        queue.touch(owner, contact(1.0, false), false, send)
        assertEquals(3, worker.tasks.size)
        worker.drain()
        assertEquals(listOf(contact(0.0), contact(0.99), contact(1.0, false)), sent)
    }

    @Test fun commandsEmptyReleasesAndContactChangesAreBarriers() {
        val worker = ManualExecutor()
        val queue = CarPlayInputQueue(worker)
        val owner = Any()
        val sent = mutableListOf<String>()
        val send: (List<AirPlayContact>) -> Boolean = { sent.add(it.toString()); true }
        queue.touch(owner, contact(0.1), true, send)
        queue.command { sent.add("Siri") }
        queue.touch(owner, contact(0.2), true, send)
        queue.touch(owner, emptyList(), false, send)
        queue.touch(owner, contact(0.3, id = 1), true, send)
        queue.touch(owner, contact(0.4, id = 2), true, send)
        queue.touch(owner, contact(0.5, down = false, id = 2), true, send)
        worker.drain()
        assertEquals(listOf(contact(0.1).toString(), "Siri", contact(0.2).toString(),
            emptyList<AirPlayContact>().toString(), contact(0.3, id = 1).toString(),
            contact(0.4, id = 2).toString(), contact(0.5, false, 2).toString()), sent)
    }

    @Test fun sessionReplacementCannotCoalesceOrSendOldContactsToNewSession() {
        val worker = ManualExecutor()
        val queue = CarPlayInputQueue(worker)
        val old = Any()
        val replacement = Any()
        var active = old
        val sent = mutableListOf<Double>()
        queue.touch(old, contact(0.1), true) {
            if (active !== old) false else { sent.add(it.single().x); true }
        }
        active = replacement
        queue.touch(replacement, contact(0.2), true) {
            if (active !== replacement) false else { sent.add(it.single().x); true }
        }
        assertEquals(2, worker.tasks.size)
        worker.drain()
        assertEquals(listOf(0.2), sent)
    }

    @Test fun disabledCoalescingPreservesAllMovesAndSnapshotsCallerList() {
        val worker = ManualExecutor()
        val queue = CarPlayInputQueue(worker)
        val owner = Any()
        val sent = mutableListOf<Double>()
        val mutable = contact(0.1).toMutableList()
        queue.touch(owner, mutable, false) { sent.add(it.single().x); true }
        mutable.clear()
        queue.touch(owner, contact(0.2), false) { sent.add(it.single().x); true }
        worker.drain()
        assertEquals(listOf(0.1, 0.2), sent)
    }

    @Test fun closeDiscardsQueuedWorkAndExecutorRejectionDoesNotLeaveReplaceableMove() {
        val worker = ManualExecutor()
        val queue = CarPlayInputQueue(worker)
        var delivered = false
        queue.touch(Any(), contact(0.1), true) { delivered = true; true }
        queue.command { delivered = true }
        queue.close()
        worker.drain()
        assertFalse(delivered)
        assertFalse(queue.command { delivered = true })
        assertFalse(queue.touch(Any(), contact(0.2), true) { true })
        val rejecting = CarPlayInputQueue(Executor { throw java.util.concurrent.RejectedExecutionException() })
        val owner = Any()
        repeat(2) { assertFalse(rejecting.touch(owner, contact(0.1), true) { true }) }
    }

    @Test fun actualBlockedSendDoesNotHoldUiLockOrMutateInFlightReport() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val queue = CarPlayInputQueue(worker)
        val owner = Any()
        val sent = mutableListOf<Double>()
        try {
            queue.touch(owner, contact(0.1), true) {
                entered.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
                sent.add(it.single().x)
                true
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val producer = Thread {
                repeat(100) { index ->
                    queue.touch(owner, contact(index / 100.0), true) { sent.add(it.single().x); true }
                }
                completed.countDown()
            }
            producer.start()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            release.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
            assertEquals(listOf(0.1, 0.99), sent)
        } finally {
            release.countDown()
            queue.close()
            worker.shutdownNow()
        }
    }

    @Test fun queueMetricMeasuresNewestRetainedEventSeparatelyFromSendDuration() {
        val worker = ManualExecutor()
        var now = 0L
        val lines = mutableListOf<String>()
        val queue = CarPlayInputQueue(worker, { now }, lines::add)
        val owner = Any()
        val send: (List<AirPlayContact>) -> Boolean = { now += 120_000_000; true }
        queue.touch(owner, contact(0.1), true, send)
        now = 5_000_000_000
        queue.touch(owner, contact(0.2), true, send)
        now += 30_000_000
        worker.drain()
        assertEquals(listOf("input queue reports=1 coalesced=1 notSent=0 queueMaxMs=30 sendMaxMs=120"), lines)
    }
}
