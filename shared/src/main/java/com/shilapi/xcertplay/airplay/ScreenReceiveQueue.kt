package com.shilapi.xcertplay.airplay

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One complete wire message. Arrays are transferred to the worker and must not be reused. */
internal data class ScreenPacket(
    val header: ByteArray,
    val body: ByteArray,
    /** Null for configuration/unknown messages and legacy unsealed short frames. */
    val nonce: Long?,
    val receivedNs: Long,
) {
    val opcode: Int get() = header[4].toInt() and 0xff
    val isVideo: Boolean get() = opcode == 0
    val bytes: Long get() = header.size.toLong() + body.size
}

/** Reader-owned sequence: a dropped encrypted message still consumes its wire nonce. */
internal class ScreenPacketSequence {
    private var nextNonce = 0L

    fun packet(header: ByteArray, body: ByteArray, receivedNs: Long): ScreenPacket {
        val nonce = if ((header[4].toInt() and 0xff) == 0 && body.size >= ScreenCodec.TAG_SIZE) nextNonce++ else null
        return ScreenPacket(header, body, nonce, receivedNs)
    }
}

/**
 * Bounded FIFO between the TCP reader and decrypt worker. Configuration messages are never dropped;
 * inability to admit one ends the stream instead of silently applying the wrong decoder format.
 * A dropped video leaves an ordered loss marker after the messages that preceded it on the wire.
 * The queue budget includes headers. In addition to it, reader and worker can each own one message.
 */
internal class ScreenReceiveQueue(
    private val maxMessages: Int = 10,
    private val maxBytes: Long = 4L * 1024 * 1024,
    private val offerWaitMillis: Long = 30,
    private val maxFrameAgeNs: Long = TimeUnit.MILLISECONDS.toNanos(250),
    private val nowNs: () -> Long = System::nanoTime,
) {
    sealed interface Item {
        data class Packet(val packet: ScreenPacket, val lossBefore: String?) : Item
        data class Loss(val reason: String) : Item
    }

    data class Snapshot(
        val messages: Int, val bytes: Long, val peakMessages: Int, val peakBytes: Long,
        val overflowDrops: Long, val staleDrops: Long, val maxAgeNs: Long,
    )

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val items = ArrayDeque<Item.Packet>()
    private var bytes = 0L
    private var pendingLoss: String? = null
    private var finished = false
    private var closed = false
    private var terminalFailure: Throwable? = null
    private var peakMessages = 0
    private var peakBytes = 0L
    private var overflowDrops = 0L
    private var staleDrops = 0L
    private var maxAgeNs = 0L

    init {
        require(maxMessages > 0 && maxBytes > 0 && offerWaitMillis >= 0 && maxFrameAgeNs > 0)
    }

    /** False means closed or a dropped video. Interrupts propagate to the owning stream worker. */
    fun offer(packet: ScreenPacket): Boolean {
        lock.lockInterruptibly()
        try {
            var waitNs = TimeUnit.MILLISECONDS.toNanos(offerWaitMillis)
            while (!closed && !finished && !fits(packet) && waitNs > 0) {
                waitNs = changed.awaitNanos(waitNs)
            }
            if (closed || finished) return false
            if (!fits(packet)) {
                if (!packet.isVideo) throw IOException("video configuration queue budget exceeded")
                overflowDrops++
                pendingLoss = "receive queue overflow"
                changed.signalAll()
                return false
            }
            items.addLast(Item.Packet(packet, pendingLoss))
            pendingLoss = null
            bytes += packet.bytes
            peakMessages = maxOf(peakMessages, items.size)
            peakBytes = maxOf(peakBytes, bytes)
            changed.signalAll()
            return true
        } finally {
            lock.unlock()
        }
    }

    /** Null is terminal; otherwise the caller receives configuration and loss markers in wire order. */
    fun take(): Item? {
        lock.lockInterruptibly()
        try {
            while (true) {
                if (closed) return null
                val item = items.pollFirst()
                if (item != null) {
                    bytes -= item.packet.bytes
                    changed.signalAll()
                    val age = (nowNs() - item.packet.receivedNs).coerceAtLeast(0)
                    maxAgeNs = maxOf(maxAgeNs, age)
                    if (item.packet.isVideo && age > maxFrameAgeNs) {
                        staleDrops++
                        return Item.Loss("receive frame exceeded 250 ms")
                    }
                    return item
                }
                pendingLoss?.let { reason -> pendingLoss = null; return Item.Loss(reason) }
                if (finished) return null
                changed.await()
            }
        } finally {
            lock.unlock()
        }
    }

    /** Reader EOF drains accepted messages before the worker reports closure. */
    fun finish(failure: Throwable?) = lock.withLock {
        if (!closed && !finished) {
            finished = true
            terminalFailure = failure
            changed.signalAll()
        }
    }

    /** Explicit cancellation discards queued payloads and wakes blocked reader/worker immediately. */
    fun close() = lock.withLock {
        closed = true
        items.clear()
        bytes = 0
        pendingLoss = null
        changed.signalAll()
    }

    fun failure(): Throwable? = lock.withLock { terminalFailure }

    fun snapshot(): Snapshot = lock.withLock {
        Snapshot(items.size, bytes, peakMessages, peakBytes, overflowDrops, staleDrops, maxAgeNs)
    }

    private fun fits(packet: ScreenPacket): Boolean =
        items.size < maxMessages && bytes + packet.bytes <= maxBytes
}

/** Serializes listener delivery with close; it never waits for worker termination or holds queue locks. */
internal class ScreenDeliveryGate {
    private val lock = ReentrantLock()
    private var closed = false

    fun deliver(callback: () -> Unit): Boolean = lock.withLock {
        if (closed) return false
        callback()
        true
    }

    fun close() = lock.withLock { closed = true }
}
