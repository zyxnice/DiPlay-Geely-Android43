package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.compat.GeelyMediaOptions
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ScreenStreamLifecycleTest {
    @Test fun invalidUnsignedBodyLengthEndsCStreamOnceInsteadOfAllocating() {
        for (length in listOf(-1, 8 * 1024 * 1024 + 1)) {
            val ended = CountDownLatch(1)
            val count = AtomicInteger()
            val closedCause = AtomicReference<Throwable?>()
            val stream = stream()
            val port = stream.listen(object : ScreenStream.Listener {
                override fun onClosed(cause: Throwable?) {
                    closedCause.set(cause)
                    count.incrementAndGet()
                    ended.countDown()
                }
            })
            try {
                Socket("127.0.0.1", port).use { socket ->
                    socket.getOutputStream().write(header(length))
                    socket.getOutputStream().flush()
                    assertTrue(ended.await(2, TimeUnit.SECONDS))
                    assertTrue(closedCause.get() is IOException)
                    assertTrue(closedCause.get()!!.message!!.contains("Invalid video body length"))
                }
                stream.close()
                assertEquals(1, count.get())
            } finally { stream.close() }
        }
    }

    @Test fun closeDuringPartialBodyUnblocksReadWithoutDeliveringClosureOrFrame() {
        val callbacks = AtomicInteger()
        val stream = stream()
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onFrame(naluBytes: ByteArray) { callbacks.incrementAndGet() }
            override fun onClosed(cause: Throwable?) { callbacks.incrementAndGet() }
        })
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write(header(16) + byteArrayOf(1))
                socket.getOutputStream().flush()
                stream.close()
                // The reader can still be in accept; both paths must close their accepted socket.
                try { assertEquals(-1, socket.getInputStream().read()) } catch (_: java.net.SocketException) { }
                assertEquals(0, callbacks.get())
            }
        } finally { stream.close() }
    }

    @Test fun peerEofNotifiesOnceAndListenerMayCloseSameStream() {
        val ended = CountDownLatch(1)
        val count = AtomicInteger()
        val stream = stream()
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onClosed(cause: Throwable?) {
                assertNull(cause)
                count.incrementAndGet()
                stream.close()
                ended.countDown()
            }
        })
        try {
            Socket("127.0.0.1", port).use { it.shutdownOutput() }
            assertTrue(ended.await(2, TimeUnit.SECONDS))
            assertEquals(1, count.get())
        } finally { stream.close() }
    }

    @Test fun concurrentListenAndCloseNeverLeaveAnAcceptingServerAfterBothReturn() {
        repeat(100) {
            val stream = stream()
            val start = CountDownLatch(1)
            val port = AtomicInteger(-1)
            val failure = AtomicReference<Throwable?>()
            val listener = Thread {
                start.await()
                try { port.set(stream.listen(object : ScreenStream.Listener {})) }
                catch (_: IOException) { /* Close won publication. */ }
                catch (error: Throwable) { failure.set(error) }
            }
            val closer = Thread { start.await(); stream.close() }
            listener.start()
            closer.start()
            start.countDown()
            listener.join(2000)
            closer.join(2000)
            assertFalse(listener.isAlive)
            assertFalse(closer.isAlive)
            assertNull(failure.get())
            if (port.get() > 0) {
                try {
                    Socket("127.0.0.1", port.get()).use { fail("Closed screen retained an accepting server") }
                } catch (_: java.net.ConnectException) { }
            }
        }
    }

    @Test fun everyConcurrentCloseWaitsForTheInflightCallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstClosed = CountDownLatch(1)
        val secondClosed = CountDownLatch(1)
        val stream = stream()
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onClosed(cause: Throwable?) {
                entered.countDown()
                // close interrupts the worker; a callback may legitimately finish cleanup anyway.
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
            }
        })
        Socket("127.0.0.1", port).use { it.shutdownOutput() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val first = Thread { stream.close(); firstClosed.countDown() }
        val second = Thread { stream.close(); secondClosed.countDown() }
        try {
            first.start()
            second.start()
            assertFalse(firstClosed.await(30, TimeUnit.MILLISECONDS))
            assertFalse(secondClosed.await(30, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            first.join(2000)
            second.join(2000)
        }
        assertEquals(0L, firstClosed.count)
        assertEquals(0L, secondClosed.count)
    }

    private fun stream() = ScreenStream(ByteArray(32), performance = GeelyMediaOptions(stage = "C", videoQueue = true))
    private fun header(length: Int) = ByteArray(128).also { bytes ->
        for (index in 0..3) bytes[index] = (length ushr (index * 8)).toByte()
    }
}
