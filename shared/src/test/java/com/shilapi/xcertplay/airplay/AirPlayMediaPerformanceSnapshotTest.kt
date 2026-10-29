package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.compat.GeelyMediaOptions
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controller snapshots survive per-connection port copies and other controllers' preference changes. */
class AirPlayMediaPerformanceSnapshotTest {
    @Test
    fun defaultSessionHasNoPerformanceOptIn() {
        val config = config()
        val session = session(config)
        try {
            assertEquals("off", session.mediaPerformance.stage)
            assertFalse(session.mediaPerformance.network)
            assertFalse(session.mediaPerformance.urgentVideo)
            assertFalse(session.mediaPerformance.nativeMedia)
            assertFalse(session.mediaPerformance.videoQueue)
            assertFalse(session.mediaPerformance.softVideoRecovery)
        } finally {
            session.close()
        }
    }

    @Test
    fun portCopyKeepsTheControllersCapturedOptions() {
        val options = GeelyMediaOptions(stage = "C", network = true, nativeMedia = true,
            videoQueue = true, softVideoRecovery = true)
        val controllerConfig = config().copy(mediaPerformance = options)
        val boundConfig = controllerConfig.copy(port = 49152)
        val session = session(boundConfig)
        try {
            assertEquals(7000, controllerConfig.port)
            assertEquals(49152, boundConfig.port)
            assertSame(options, boundConfig.mediaPerformance)
            assertSame(options, session.mediaPerformance)
        } finally {
            session.close()
        }
    }

    @Test
    fun newControllerOptionsDoNotChangeAnExistingSession() {
        val originalOptions = GeelyMediaOptions(stage = "A", network = true)
        val originalConfig = config().copy(mediaPerformance = originalOptions)
        val originalSession = session(originalConfig)
        val nextOptions = originalOptions.copy(stage = "B", network = false, urgentVideo = true, nativeMedia = true)
        val nextConfig = originalConfig.copy(port = 49153, mediaPerformance = nextOptions)
        val nextSession = session(nextConfig)
        try {
            assertEquals(originalOptions, originalConfig.mediaPerformance)
            assertSame(originalOptions, originalSession.mediaPerformance)
            assertEquals("A", originalSession.mediaPerformance.stage)
            assertTrue(originalSession.mediaPerformance.network)
            assertFalse(originalSession.mediaPerformance.urgentVideo)
            assertFalse(originalSession.mediaPerformance.nativeMedia)
            assertSame(nextOptions, nextSession.mediaPerformance)
            assertFalse(nextSession.mediaPerformance.network)
            assertTrue(nextSession.mediaPerformance.urgentVideo)
            assertTrue(nextSession.mediaPerformance.nativeMedia)
        } finally {
            nextSession.close()
            originalSession.close()
        }
    }

    private fun config(): AirPlayConfig = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "1.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
    )

    private fun session(config: AirPlayConfig): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = config,
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
