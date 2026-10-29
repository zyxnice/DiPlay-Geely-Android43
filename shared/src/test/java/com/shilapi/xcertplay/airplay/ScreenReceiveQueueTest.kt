package com.shilapi.xcertplay.airplay

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ScreenReceiveQueueTest {
    @Test fun droppedEncryptedFrameStillConsumesNonceAndLossPrecedesNextFrame() {
        val sequence = ScreenPacketSequence()
        val queue = ScreenReceiveQueue(maxMessages = 1, offerWaitMillis = 0)
        val first = sequence.packet(header(0), ByteArray(16), System.nanoTime())
        assertTrue(queue.offer(first))
        assertFalse(queue.offer(sequence.packet(header(0), ByteArray(16), System.nanoTime())))
        assertEquals(0L, (queue.take() as ScreenReceiveQueue.Item.Packet).packet.nonce)
        val third = sequence.packet(header(0), ByteArray(16), System.nanoTime())
        assertTrue(queue.offer(third))
        val delivered = queue.take() as ScreenReceiveQueue.Item.Packet
        assertEquals(2L, delivered.packet.nonce)
        assertEquals("receive queue overflow", delivered.lossBefore)
        assertEquals(1L, queue.snapshot().overflowDrops)
    }

    @Test fun configurationAndShortUnsealedFramesDoNotConsumeNonce() {
        val sequence = ScreenPacketSequence()
        assertNull(sequence.packet(header(1), ByteArray(30), 0).nonce)
        assertNull(sequence.packet(header(0), ByteArray(15), 0).nonce)
        assertEquals(0L, sequence.packet(header(0), ByteArray(16), 0).nonce)
        assertNull(sequence.packet(header(9), ByteArray(16), 0).nonce)
        assertEquals(1L, sequence.packet(header(0), ByteArray(16), 0).nonce)
    }

    @Test fun lossDoesNotMoveBeforePreviouslyAcceptedConfigOrFrame() {
        val queue = ScreenReceiveQueue(maxMessages = 2, offerWaitMillis = 0)
        val config = packet(opcode = 1)
        val first = packet()
        assertTrue(queue.offer(config))
        assertTrue(queue.offer(first))
        assertFalse(queue.offer(packet()))
        assertSame(config, (queue.take() as ScreenReceiveQueue.Item.Packet).packet)
        assertSame(first, (queue.take() as ScreenReceiveQueue.Item.Packet).packet)
        assertTrue(queue.take() is ScreenReceiveQueue.Item.Loss)
    }

    @Test fun byteBudgetIncludesWireHeadersAndRejectsSingleOversizedFrame() {
        val queue = ScreenReceiveQueue(maxMessages = 10, maxBytes = 150, offerWaitMillis = 0)
        assertTrue(queue.offer(packet(size = 16)))
        assertFalse(queue.offer(packet(size = 16)))
        assertEquals(144L, queue.snapshot().bytes)
        assertEquals(144L, queue.snapshot().peakBytes)
        queue.take()
        assertFalse(queue.offer(packet(size = 151)))
        assertEquals(0L, queue.snapshot().bytes)
        assertEquals(2L, queue.snapshot().overflowDrops)
    }

    @Test fun configurationCannotBeDroppedWhenBudgetExhausted() {
        val queue = ScreenReceiveQueue(maxMessages = 1, offerWaitMillis = 0)
        val first = packet()
        queue.offer(first)
        try {
            queue.offer(packet(opcode = 1))
            fail("Configuration loss must terminate the reader")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("configuration"))
        }
        assertSame(first, (queue.take() as ScreenReceiveQueue.Item.Packet).packet)
    }

    @Test fun staleFrameIsReplacedByLossButConfigurationOrderIsPreserved() {
        var now = 0L
        val queue = ScreenReceiveQueue(nowNs = { now }, offerWaitMillis = 0)
        val config = packet(opcode = 1, receivedNs = 0)
        queue.offer(packet(receivedNs = 0))
        queue.offer(config)
        now = 250_000_001L
        assertTrue(queue.take() is ScreenReceiveQueue.Item.Loss)
        assertSame(config, (queue.take() as ScreenReceiveQueue.Item.Packet).packet)
        assertEquals(1L, queue.snapshot().staleDrops)
        assertEquals(0L, queue.snapshot().bytes)
    }

    @Test fun frameAtExactAgeLimitRemainsValid() {
        val queue = ScreenReceiveQueue(nowNs = { 250_000_000L })
        queue.offer(packet(receivedNs = 0))
        assertTrue(queue.take() is ScreenReceiveQueue.Item.Packet)
    }

    @Test fun eofDrainsAcceptedFramesAndOrderedLossBeforeReportingFailure() {
        val queue = ScreenReceiveQueue(maxMessages = 1, offerWaitMillis = 0)
        queue.offer(packet())
        queue.offer(packet())
        val failure = IOException("test EOF failure")
        queue.finish(failure)
        assertFalse(queue.offer(packet()))
        assertTrue(queue.take() is ScreenReceiveQueue.Item.Packet)
        assertTrue(queue.take() is ScreenReceiveQueue.Item.Loss)
        assertNull(queue.take())
        assertSame(failure, queue.failure())
    }

    @Test fun offerWaitsAtMostConfiguredBudgetWithoutPolling() {
        val queue = ScreenReceiveQueue(maxMessages = 1, offerWaitMillis = 30)
        queue.offer(packet())
        val start = System.nanoTime()
        assertFalse(queue.offer(packet()))
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("wait returned too early: $elapsed", elapsed >= 20)
        assertTrue("wait exceeded bounded scheduling allowance: $elapsed", elapsed < 1000)
    }

    @Test fun closeWakesBlockedProducerAndDiscardsPayloads() {
        val queue = ScreenReceiveQueue(maxMessages = 1, offerWaitMillis = 5000)
        queue.offer(packet())
        val started = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val producer = Thread {
            started.countDown()
            accepted.set(queue.offer(packet()))
            ended.countDown()
        }
        producer.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        queue.close()
        assertTrue(ended.await(1, TimeUnit.SECONDS))
        assertFalse(accepted.get())
        assertNull(queue.take())
        assertEquals(0L, queue.snapshot().bytes)
        producer.join(1000)
    }

    @Test fun closeWakesBlockedConsumerAndInterruptIsNotSwallowed() {
        val queue = ScreenReceiveQueue()
        val ended = CountDownLatch(1)
        val consumer = Thread { assertNull(queue.take()); ended.countDown() }
        consumer.start()
        queue.close()
        assertTrue(ended.await(1, TimeUnit.SECONDS))
        consumer.join(1000)
        val interruptedQueue = ScreenReceiveQueue()
        val interrupted = AtomicBoolean(false)
        val waiter = Thread {
            try { interruptedQueue.take() } catch (_: InterruptedException) { interrupted.set(true) }
        }
        waiter.start()
        waiter.interrupt()
        waiter.join(1000)
        assertFalse(waiter.isAlive)
        assertTrue(interrupted.get())
    }

    @Test fun encryptedFramesAfterOverflowAuthenticateWithOriginalWireCounter() {
        val key = ByteArray(32) { it.toByte() }
        val sequence = ScreenPacketSequence()
        val queue = ScreenReceiveQueue(maxMessages = 1, offerWaitMillis = 0)
        val packets = (0L..2L).map { counter ->
            val header = header(0)
            val body = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), byteArrayOf(counter.toByte()), header)
            sequence.packet(header, body, System.nanoTime())
        }
        queue.offer(packets[0])
        queue.offer(packets[1])
        queue.take()
        queue.offer(packets[2])
        val frame = (queue.take() as ScreenReceiveQueue.Item.Packet).packet
        assertArrayEquals(byteArrayOf(2), AirPlayCrypto.chachaOpen(key,
            AirPlayCrypto.nonce64(frame.nonce!!), frame.body, frame.header))
    }

    private fun header(opcode: Int) = ByteArray(128).also { it[4] = opcode.toByte() }
    private fun packet(opcode: Int = 0, size: Int = 16, receivedNs: Long = System.nanoTime()) =
        ScreenPacket(header(opcode), ByteArray(size), if (opcode == 0) 0 else null, receivedNs)
}
