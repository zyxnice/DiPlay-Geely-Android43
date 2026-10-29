package com.shilapi.xcertplay.airplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ScreenDeliveryGateTest {
    @Test fun closeFencesInflightCallbackAndRejectsLateFrameFromOldStream() {
        val old = ScreenDeliveryGate()
        val current = ScreenDeliveryGate()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val count = AtomicInteger()
        val callback = Thread {
            old.deliver { started.countDown(); release.await(); count.incrementAndGet() }
        }
        callback.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val closer = Thread { old.close(); closed.countDown() }
        closer.start()
        assertFalse(closed.await(30, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        assertFalse(old.deliver { count.incrementAndGet() })
        assertTrue(current.deliver { count.incrementAndGet() })
        assertEquals(2, count.get())
        callback.join(1000)
        closer.join(1000)
    }

    @Test fun listenerCanCloseItsOwnStreamWithoutDeadlock() {
        val gate = ScreenDeliveryGate()
        assertTrue(gate.deliver { gate.close() })
        assertFalse(gate.deliver { fail("Callback after close") })
    }
}
