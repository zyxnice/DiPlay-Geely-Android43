package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.compat.GeelyMediaOptions
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise real screen sockets: replacing one generation must not terminate its successor's session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayScreenReplacementTest {
    @Test fun replacingScreenClosesOldSocketAndDeliversNewConfigurationWithoutEndingSession() {
        val configs = LinkedBlockingQueue<ByteArray>()
        val lifecycle = LinkedBlockingQueue<String>()
        val ended = AtomicInteger()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onVideoConfig(type: Int, codecData: ByteArray) {
                lifecycle.offer("config")
                configs.offer(codecData)
            }
            override fun onScreenStreamActive(type: Int, active: Boolean) { lifecycle.offer("active=$active") }
        })
        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(800, 480),
                mediaPerformance = GeelyMediaOptions(stage = "C", videoQueue = true),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) { ended.incrementAndGet() }
            },
            media = engine,
        )
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, ByteArray(32) { it.toByte() })
        val setup = mapOf<String, Any?>("streamConnectionID" to 42L)
        try {
            Socket("127.0.0.1", requireNotNull(engine.onScreen(session, 110, setup))).use { old ->
                old.soTimeout = 3000
                sendConfig(old, 1)
                assertNotNull(configs.poll(3, TimeUnit.SECONDS))
                assertEquals("active=true", lifecycle.poll(3, TimeUnit.SECONDS))
                assertEquals("config", lifecycle.poll(3, TimeUnit.SECONDS))
                val replacementPort = requireNotNull(engine.onScreen(session, 110, setup))
                assertEquals(-1, old.getInputStream().read())
                assertEquals("active=false", lifecycle.poll(3, TimeUnit.SECONDS))
                assertEquals("active=true", lifecycle.poll(3, TimeUnit.SECONDS))
                Socket("127.0.0.1", replacementPort).use { replacement ->
                    // Identical CSD must still belong to a newly retired/activated sink generation.
                    sendConfig(replacement, 1)
                    val latest = requireNotNull(configs.poll(3, TimeUnit.SECONDS))
                    assertEquals(1.toByte(), latest.last())
                    assertEquals("config", lifecycle.poll(3, TimeUnit.SECONDS))
                    assertEquals(0, ended.get())
                    engine.onTeardown(session, 110)
                    replacement.soTimeout = 3000
                    assertEquals(-1, replacement.getInputStream().read())
                    assertEquals(0, ended.get())
                }
            }
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private fun sendConfig(socket: Socket, marker: Int) {
        val body = byteArrayOf(1, 0, 0, 0, -1, 1, 0, 1, 0x67, marker.toByte())
        val header = ByteArray(128).apply { this[0] = body.size.toByte(); this[4] = 1 }
        socket.getOutputStream().apply { write(header); write(body); flush() }
    }
}
