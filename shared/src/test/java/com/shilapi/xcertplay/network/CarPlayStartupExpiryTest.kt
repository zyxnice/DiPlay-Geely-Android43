package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Use the service's normal listener/accept path, without a physical USB/VPN or phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayStartupExpiryTest {
    @Test
    fun tcpAcceptedAfterPendingSnapshotWinsOverExpiry() = withService { service, errors ->
        val generation = attach(service, errors)
        assertEquals(false, service.hasAcceptedConnection(generation))
        Socket("127.0.0.1", requireNotNull(service.boundPort())).use {
            awaitAccepted(service, generation)
            assertFalse(service.expireStartupIfPending(generation))
            assertTrue(service.isAttached())
            assertEquals(0, errors.get())
        }
    }

    @Test
    fun oldGenerationCannotExpireReplacement() = withService { service, errors ->
        val old = attach(service, errors)
        val current = attach(service, errors)
        assertNull(service.hasAcceptedConnection(old))
        assertFalse(service.expireStartupIfPending(old))
        Socket("127.0.0.1", requireNotNull(service.boundPort())).use {
            awaitAccepted(service, current)
            assertTrue(service.isAttached())
            assertEquals(0, errors.get())
        }
    }

    @Test
    fun pendingAttachmentExpiresOnceAndNotifiesRecovery() = withService { service, errors ->
        val generation = attach(service, errors)
        assertTrue(service.expireStartupIfPending(generation))
        assertFalse(service.isAttached())
        assertFalse(service.expireStartupIfPending(generation))
        assertEquals(1, errors.get())
    }

    private fun attach(service: CarPlayVpnService, errors: AtomicInteger): Int {
        assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
            bindAddress = InetAddress.getByName("127.0.0.1"),
            config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
                AirPlayDisplayConfig(800, 480), port = 0),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onTransportError(message: String) { errors.incrementAndGet() }
            },
            media = object : AirPlayMediaHandler {},
        ))
        return service.startupGeneration()
    }

    private fun awaitAccepted(service: CarPlayVpnService, generation: Int) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (service.hasAcceptedConnection(generation) == false && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(true, service.hasAcceptedConnection(generation))
    }

    private fun withService(test: (CarPlayVpnService, AtomicInteger) -> Unit) {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val service = controller.get()
        try {
            test(service, AtomicInteger())
        } finally {
            service.detach()
            controller.destroy()
        }
    }
}
