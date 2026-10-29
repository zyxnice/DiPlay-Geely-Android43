package com.shilapi.xcertplay.media

/**
 * Decoder-worker-owned recovery state. Only a lost reference chain after a real output may keep
 * the codec; format, surface and hardware failures must still rebuild it. No Android calls live
 * here, so the first-output, stale-output and bounded-fallback decisions can be tested directly.
 */
internal class VideoRecoveryPolicy(
    private val enabled: Boolean,
    private val outputTimeoutNs: Long = 1_000_000_000L,
) {
    enum class Action { REBUILD, KEEP_DECODER, ALREADY_WAITING }

    private var hasRenderedOutput = false
    private var disabledForSession = false
    private var recovering = false
    private var keyFramePtsUs: Long? = null
    private var keyFrameQueuedNs: Long? = null
    private var minimumOutputPtsUs: Long? = null

    /** The caller classifies only queue/age discontinuities as a recoverable reference gap. */
    fun begin(referenceGap: Boolean, decoderAvailable: Boolean): Action {
        if (!enabled || disabledForSession || !referenceGap || !decoderAvailable || !hasRenderedOutput) {
            if (recovering && (!referenceGap || !decoderAvailable)) disabledForSession = true
            return Action.REBUILD
        }
        if (recovering) {
            // A second loss invalidates the previous recovery IDR, but must not extend the
            // already-started fallback deadline indefinitely under recurring queue pressure.
            keyFramePtsUs = null
            return Action.ALREADY_WAITING
        }
        recovering = true
        keyFramePtsUs = null
        keyFrameQueuedNs = null
        return Action.KEEP_DECODER
    }

    /** Called only after MediaCodec accepted a new random-access frame, never on receipt alone. */
    fun onKeyFrameQueued(presentationTimeUs: Long, nowNs: Long) {
        if (!recovering || keyFramePtsUs != null) return
        keyFramePtsUs = presentationTimeUs
        minimumOutputPtsUs = presentationTimeUs
        if (keyFrameQueuedNs == null) keyFrameQueuedNs = nowNs
    }

    /** Old codec output is discarded until output for the newly submitted keyframe arrives. */
    fun canRender(presentationTimeUs: Long): Boolean {
        val boundary = if (recovering) keyFramePtsUs ?: return false else minimumOutputPtsUs
        return boundary == null || presentationTimeUs >= boundary
    }

    fun onRendered() {
        hasRenderedOutput = true
        recovering = false
        keyFramePtsUs = null
        keyFrameQueuedNs = null
    }

    /**
     * A static screen or a phone still withholding an IDR is not a failed decoder. The timeout
     * starts with accepted keyframe input; failure disables this strategy for the stream lifetime.
     */
    fun disableIfOutputTimedOut(nowNs: Long): Boolean {
        val submitted = keyFrameQueuedNs ?: return false
        if (!recovering || nowNs - submitted < outputTimeoutNs) return false
        disabledForSession = true
        onDecoderChanged()
        return true
    }

    /** A new codec/format/surface needs its own first output; a failed strategy stays disabled. */
    fun onDecoderChanged() {
        hasRenderedOutput = false
        recovering = false
        keyFramePtsUs = null
        keyFrameQueuedNs = null
        minimumOutputPtsUs = null
    }

    /** A hardware/input exception during an attempted soft recovery must not restart that loop. */
    fun onDecoderFailure() {
        if (recovering) disabledForSession = true
        onDecoderChanged()
    }
}
