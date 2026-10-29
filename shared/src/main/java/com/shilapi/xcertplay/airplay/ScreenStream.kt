package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.compat.GeelyMediaPerformance
import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(
    private val key: ByteArray,
    private val onDiagnostic: (String) -> Unit = {},
    private val performance: com.shilapi.xcertplay.compat.GeelyMediaOptions = com.shilapi.xcertplay.compat.GeelyMediaOptions(),
) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        /** C preserves the original complete-message arrival time through both bounded queues. */
        fun onFrame(naluBytes: ByteArray, receivedNs: Long) { onFrame(naluBytes) }
        /** A wire frame was dropped before decryption; the decoder must wait for a new keyframe. */
        fun onDiscontinuity(reason: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val crypto = MediaFrameCrypto(performance.nativeMedia, onDiagnostic)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private val delivery = ScreenDeliveryGate()
    private val stateLock = Any()
    private val closeNotified = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var decryptThread: Thread? = null
    @Volatile private var receiveQueue: ScreenReceiveQueue? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        GeelyMediaPerformance.prepareVideoServer(bound, performance.network, onDiagnostic)
        com.shilapi.xcertplay.compat.WildcardBind.bind(bound)
        synchronized(stateLock) {
            if (closed.get()) { safeClose(bound); throw IOException("Screen stream already closed") }
            check(server == null) { "Screen stream already listening" }
            server = bound
            thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        }
        return bound.localPort
    }

    override fun close() {
        val closingSocket: Socket?
        val closingServer: ServerSocket?
        val closingQueue: ScreenReceiveQueue?
        val closingReader: Thread?
        val closingWorker: Thread?
        synchronized(stateLock) {
            // Publication by listen/accept/runQueued and closure share one lock. Otherwise close
            // can observe null just before a worker publishes a live server or queue after it.
            if (closed.compareAndSet(false, true)) {
                closingSocket = socket
                closingServer = server
                closingQueue = receiveQueue
                closingReader = thread
                closingWorker = decryptThread
                socket = null
                server = null
                receiveQueue = null
                thread = null
                decryptThread = null
            } else {
                closingSocket = null
                closingServer = null
                closingQueue = null
                closingReader = null
                closingWorker = null
            }
        }
        safeClose(closingSocket)
        safeClose(closingServer)
        closingQueue?.close()
        closingReader?.interrupt()
        closingWorker?.interrupt()
        // No join: a listener may close its own stream. This fence waits only for an in-flight
        // callback, and suppresses delivery by a decrypt operation that finishes after close.
        // Every close caller crosses it, including concurrent callers that did not own resources.
        delivery.close()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            GeelyMediaPerformance.configureSocket(accepted, video = true, performance.network, onDiagnostic)
            synchronized(stateLock) {
                if (closed.get()) { safeClose(accepted); return }
                socket = accepted
            }
            if (performance.videoQueue) runQueued(accepted) else run(accepted)
        } catch (error: Exception) {
            notifyClosed(error)
        }
    }

    private fun run(sock: Socket) {
        GeelyMediaPerformance.configureVideoThread(performance.urgentVideo, onDiagnostic)
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video", onDiagnostic, cryptoDetails = { crypto.snapshot().toString() })
        try {
            val input = sock.getInputStream()
            while (!closed.get()) {
                stats.reading()
                val header = readFully(input, HEADER_LEN) ?: break
                val bodySize = readU32Le(header, 0)
                if (bodySize > MAX_BODY) break
                val body = readFully(input, bodySize) ?: break
                stats.received(HEADER_LEN + bodySize)
                onMessage(header, body, stats)
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            if (socket === sock) socket = null
            safeClose(sock)
            notifyClosed(failure)
        }
    }

    private fun onMessage(header: ByteArray, body: ByteArray, stats: StreamReceiveStats) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                val payload = if (body.size >= ScreenCodec.TAG_SIZE) {
                    val started = System.nanoTime()
                    try {
                        crypto.open(key, AirPlayCrypto.nonce64(frameCounter.get()), body, header)
                            .also { frameCounter.incrementAndGet() }
                    } finally {
                        stats.decrypted(System.nanoTime() - started)
                    }
                } else {
                    body
                }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=${body.size} plain=${payload.size} " +
                        "head=${payload.hexPrefix(16)}",
                    )
                }
                deliver { listener.onFrame(ScreenCodec.lengthPrefixedToAnnexB(payload)) }
            }
            OP_VIDEO_CONFIG -> {
                val (codec, codecData) = ScreenCodec.detectConfig(body)
                Log.i(TAG, "video codec config codec=$codec body=${body.size} data=${codecData.size}")
                deliver { listener.onCodec(codec) }
                deliver { listener.onConfig(codecData) }
            }
        }
    }

    /** C-only reader: it never decrypts and owns the wire nonce even when the queue rejects a frame. */
    private fun runQueued(sock: Socket) {
        GeelyMediaPerformance.configureVideoThread(performance.urgentVideo, onDiagnostic)
        val queue = ScreenReceiveQueue()
        val sequence = ScreenPacketSequence()
        val stats = StreamReceiveStats("video-read", onDiagnostic)
        synchronized(stateLock) {
            if (closed.get()) { safeClose(sock); return }
            receiveQueue = queue
            decryptThread = Thread({ decryptQueued(sock, queue) }, "airplay-screen-decrypt").apply {
                isDaemon = true
                start()
            }
        }
        runCatching { onDiagnostic("VideoQueue: enabled maxMessages=10 maxBytes=4194304 offerWaitMs=30 maxFrameAgeMs=250") }
        var failure: Throwable? = null
        try {
            val input = sock.getInputStream()
            while (!closed.get()) {
                stats.reading()
                val header = readFully(input, HEADER_LEN) ?: break
                val bodySize = readU32Le(header, 0)
                if (bodySize < 0 || bodySize > MAX_BODY) throw IOException("Invalid video body length: $bodySize")
                val body = readFully(input, bodySize) ?: throw IOException("Truncated video body")
                stats.received(HEADER_LEN + bodySize)
                queue.offer(sequence.packet(header, body, System.nanoTime()))
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            queue.finish(failure)
            safeClose(sock)
        }
    }

    /** Single owner for C decrypt state and ordered downstream callbacks. */
    private fun decryptQueued(sock: Socket, queue: ScreenReceiveQueue) {
        GeelyMediaPerformance.configureVideoThread(performance.urgentVideo, onDiagnostic)
        val stats = ScreenWorkStats(queue, onDiagnostic, cryptoDetails = { crypto.snapshot().toString() })
        var failure: Throwable? = null
        try {
            while (!closed.get()) {
                when (val item = queue.take() ?: break) {
                    is ScreenReceiveQueue.Item.Loss -> deliver { listener.onDiscontinuity(item.reason) }
                    is ScreenReceiveQueue.Item.Packet -> {
                        item.lossBefore?.let { reason -> deliver { listener.onDiscontinuity(reason) } }
                        val packet = item.packet
                        val started = System.nanoTime()
                        when (packet.opcode) {
                            OP_VIDEO_FRAME -> {
                                val payload = if (packet.nonce != null) {
                                    val decryptStart = System.nanoTime()
                                    try {
                                        crypto.open(key, AirPlayCrypto.nonce64(packet.nonce), packet.body, packet.header)
                                    } finally { stats.decrypted(System.nanoTime() - decryptStart) }
                                } else packet.body
                                if (firstFrameLogged.compareAndSet(false, true)) {
                                    Log.i(TAG, "video first decrypted frame sealed=${packet.body.size} plain=${payload.size} " +
                                        "head=${payload.hexPrefix(16)}")
                                }
                                val frame = ScreenCodec.lengthPrefixedToAnnexB(payload)
                                // Decryption can itself outlive the frame budget. Notify loss before the next frame.
                                if (System.nanoTime() - packet.receivedNs > MAX_FRAME_AGE_NS) {
                                    stats.expiredAfterDecrypt()
                                    deliver { listener.onDiscontinuity("decrypted frame exceeded 250 ms") }
                                } else deliver { listener.onFrame(frame, packet.receivedNs) }
                            }
                            OP_VIDEO_CONFIG -> {
                                val (codec, codecData) = ScreenCodec.detectConfig(packet.body)
                                Log.i(TAG, "video codec config codec=$codec body=${packet.body.size} data=${codecData.size}")
                                deliver { listener.onCodec(codec) }
                                deliver { listener.onConfig(codecData) }
                            }
                        }
                        stats.processed(System.nanoTime() - started)
                    }
                }
                stats.flush()
            }
            failure = queue.failure()
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            queue.close()
            safeClose(sock)
            if (socket === sock) socket = null
            notifyClosed(failure)
        }
    }

    private fun deliver(callback: () -> Unit) {
        delivery.deliver { if (!closed.get()) callback() }
    }

    private fun notifyClosed(failure: Throwable?) {
        deliver { if (closeNotified.compareAndSet(false, true)) listener.onClosed(failure) }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val output = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return output
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
        const val MAX_FRAME_AGE_NS = 250_000_000L
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        if (payload.size < 4 || payload.startsWithStartCode()) return payload

        var offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            offset += 4
            if (length <= 0 || offset + length > payload.size) return payload
            offset += length
        }
        if (offset != payload.size) return payload

        offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
