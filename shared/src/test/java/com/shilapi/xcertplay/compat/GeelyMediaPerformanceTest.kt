package com.shilapi.xcertplay.compat

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class GeelyMediaPerformanceTest {
    @Test fun stageAndDeviceMustBothOptIn() {
        fun select(stage: String = "B", sdk: Int = 18, board: String = "C3ALFUS", display: String = "06.03.08600.H53") =
            GeelyMediaPerformance.select(stage, sdk, board, display, true, false, true)
        assertEquals(GeelyMediaOptions(), select(stage = "off"))
        assertEquals(GeelyMediaOptions(), select(sdk = 19))
        assertEquals(GeelyMediaOptions(), select(board = "other"))
        assertEquals(GeelyMediaOptions(), select(display = "06.03.08500"))
        assertTrue(select().nativeMedia)
        assertFalse(select(stage = "A").nativeMedia)
        assertFalse(select().urgentVideo)
        assertEquals(GeelyMediaOptions("B", false, false, false),
            GeelyMediaPerformance.select("B", 18, "C3ALFUS", "06.03.08600", false, false, false))
    }

    @Test fun optionsApplyBeforeBindAndToEveryAcceptedConnection() {
        val lines = mutableListOf<String>()
        ServerSocket().use { server ->
            GeelyMediaPerformance.prepareVideoServer(server, true, lines::add)
            assertFalse(server.isBound)
            assertTrue(server.receiveBufferSize > 0)
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            repeat(2) {
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use {
                    server.accept().use { accepted ->
                        GeelyMediaPerformance.configureSocket(accepted, true, true, lines::add)
                        assertTrue(accepted.tcpNoDelay)
                        assertTrue(accepted.receiveBufferSize > 0)
                    }
                }
            }
        }
        assertEquals(3, lines.size)
    }

    @Test fun cDefaultsToQueueOnlyAndCannotEnableStrategiesOutsideItsPlatform() {
        fun select(stage: String = "C", queue: Boolean = true, soft: Boolean = false, sdk: Int = 18) =
            GeelyMediaPerformance.select(stage, sdk, "C3ALFUS", "06.03.08600.H53",
                true, false, true, queue, soft)
        assertTrue(select().nativeMedia)
        assertTrue(select().videoQueue)
        assertFalse(select().softVideoRecovery)
        assertTrue(select(soft = true).softVideoRecovery)
        assertFalse(select(queue = false, soft = true).softVideoRecovery)
        for (stage in listOf("off", "A", "B")) {
            assertFalse(select(stage = stage, soft = true).videoQueue)
            assertFalse(select(stage = stage, soft = true).softVideoRecovery)
        }
        assertEquals(GeelyMediaOptions(), select(sdk = 19, soft = true))
    }

    @Test fun disabledOrRejectedOptionDoesNotBreakOriginalSocketLifecycle() {
        val lines = mutableListOf<String>()
        Socket().use { socket ->
            GeelyMediaPerformance.configureSocket(socket, false, false, lines::add)
            assertFalse(socket.tcpNoDelay)
            assertTrue(lines.isEmpty())
            socket.close()
            GeelyMediaPerformance.configureSocket(socket, false, true, lines::add)
            assertTrue(lines.single().contains("unavailable="))
        }
    }
}
