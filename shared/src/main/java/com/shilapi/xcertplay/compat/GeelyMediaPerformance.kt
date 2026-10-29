package com.shilapi.xcertplay.compat

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.net.ServerSocket
import java.net.Socket

/**
 * Immutable options owned by one controller; reconnecting within it keeps the same options.
 * @property stage Local candidate identity (off/A/B/C); never sent to the iPhone.
 * @property network Enables the local event/socket buffer adjustments.
 * @property urgentVideo Opt-in nice=-8 for video workers; false preserves platform scheduling.
 * @property nativeMedia B/C authenticated media decryption, with Java fallback.
 * @property videoQueue C-only bounded receive/decrypt separation, captured for the whole session.
 * @property softVideoRecovery C-only decoder retention after a recoverable backlog; opt-in, never flushes.
 */
data class GeelyMediaOptions(
    val stage: String = "off",
    val network: Boolean = false,
    val urgentVideo: Boolean = false,
    val nativeMedia: Boolean = false,
    val videoQueue: Boolean = false,
    val softVideoRecovery: Boolean = false,
)

/** Opt-in for the isolated 8600 build, independent of USB privilege/role preparation. */
object GeelyMediaPerformance {
    private const val TAG = "DiPlay-Performance"
    private const val STAGE_METADATA = "com.shilapi.xcertplay.GEELY_MEDIA_STAGE"
    private const val PREFS = "geely_media_performance"
    const val NETWORK = "network"
    const val URGENT_VIDEO = "urgent_video"
    const val NATIVE_MEDIA = "native_media"
    const val VIDEO_QUEUE = "video_queue"
    const val SOFT_VIDEO_RECOVERY = "soft_video_recovery"
    private const val RECEIVE_BYTES = 512 * 1024

    fun load(context: Context): GeelyMediaOptions {
        val stage = try {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString(STAGE_METADATA) ?: "off"
        } catch (error: Exception) {
            Log.w(TAG, "performance metadata unavailable", error)
            "off"
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return select(stage, Build.VERSION.SDK_INT, Build.BOARD, Build.DISPLAY,
            prefs.getBoolean(NETWORK, true), prefs.getBoolean(URGENT_VIDEO, false),
            prefs.getBoolean(NATIVE_MEDIA, true), prefs.getBoolean(VIDEO_QUEUE, true),
            prefs.getBoolean(SOFT_VIDEO_RECOVERY, false))
    }

    fun save(context: Context, key: String, enabled: Boolean) {
        require(key == NETWORK || key == URGENT_VIDEO || key == NATIVE_MEDIA ||
            key == VIDEO_QUEUE || key == SOFT_VIDEO_RECOVERY)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().putBoolean(key, enabled)
        // Disabling the queue also retires its optional recovery strategy. Re-enabling just the
        // queue must not silently restore a strategy that the settings screen showed as off.
        if (key == VIDEO_QUEUE && !enabled) editor.putBoolean(SOFT_VIDEO_RECOVERY, false)
        if (key == SOFT_VIDEO_RECOVERY && !prefs.getBoolean(VIDEO_QUEUE, true)) {
            editor.putBoolean(SOFT_VIDEO_RECOVERY, false)
        }
        editor.apply()
    }

    internal fun select(stage: String, sdk: Int, board: String?, display: String?,
                        network: Boolean, urgent: Boolean, native: Boolean,
                        videoQueue: Boolean = true, softVideoRecovery: Boolean = false): GeelyMediaOptions {
        if (stage !in listOf("A", "B", "C") || sdk != 18 || board != "C3ALFUS" ||
            display?.startsWith("06.03.08600") != true) return GeelyMediaOptions()
        return GeelyMediaOptions(stage, network, urgent, stage in listOf("B", "C") && native,
            stage == "C" && videoQueue, stage == "C" && videoQueue && softVideoRecovery)
    }

    /** Must run before bind, so the receive window can participate in the TCP handshake. */
    fun prepareVideoServer(server: ServerSocket, enabled: Boolean, report: (String) -> Unit) {
        if (!enabled) return
        option("video receive window", report) {
            server.receiveBufferSize = RECEIVE_BYTES
            "requested=$RECEIVE_BYTES actual=${server.receiveBufferSize}"
        }
    }

    fun configureSocket(socket: Socket, video: Boolean, enabled: Boolean, report: (String) -> Unit) {
        if (!enabled) return
        option(if (video) "video socket" else "event socket", report) {
            // NODELAY controls local writes, not the phone's sending or delayed ACK policy.
            socket.tcpNoDelay = true
            if (video) socket.receiveBufferSize = RECEIVE_BYTES
            "noDelay=${socket.tcpNoDelay} receiveBytes=${socket.receiveBufferSize}"
        }
    }

    /** Called by the worker itself; system refusal preserves its original scheduling. */
    fun configureVideoThread(enabled: Boolean, report: (String) -> Unit) {
        if (!enabled) return
        option("video priority", report) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            "tid=${Process.myTid()} nice=${Process.getThreadPriority(Process.myTid())}"
        }
    }

    private inline fun option(name: String, report: (String) -> Unit, operation: () -> String) {
        val detail = try { operation() } catch (error: Exception) {
            "unavailable=${error.javaClass.simpleName}: ${error.message}"
        }
        runCatching { report("Performance: $name $detail") }
    }
}
