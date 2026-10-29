package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoRecoveryPolicyTest {
    @Test fun disabledStrategyAndFirstOutputBeforeRecoveryAlwaysRebuild() {
        val disabled = VideoRecoveryPolicy(false)
        disabled.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, disabled.begin(true, true))
        val enabled = VideoRecoveryPolicy(true)
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, enabled.begin(true, true))
    }

    @Test fun onlyReferenceGapWithLiveDecoderCanKeepAnAlreadyRenderingCodec() {
        val policy = VideoRecoveryPolicy(true)
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(false, true))
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(true, false))
        assertEquals(VideoRecoveryPolicy.Action.KEEP_DECODER, policy.begin(true, true))
    }

    @Test fun oldOutputRemainsSuppressedUntilAcceptedNewKeyframeOutput() {
        val policy = VideoRecoveryPolicy(true)
        policy.onRendered()
        policy.begin(true, true)
        assertFalse(policy.canRender(999))
        policy.onKeyFrameQueued(1_000, 1_000_000)
        assertFalse(policy.canRender(999))
        assertTrue(policy.canRender(1_000))
        policy.onRendered()
        assertTrue(policy.canRender(1_001))
        // Delayed pre-recovery output must not reappear after the first good output.
        assertFalse(policy.canRender(999))
        assertFalse(policy.disableIfOutputTimedOut(Long.MAX_VALUE))
    }

    @Test fun noNewKeyframeMeansNoStaticScreenWatchdog() {
        val policy = VideoRecoveryPolicy(true, outputTimeoutNs = 100)
        policy.onRendered()
        assertFalse(policy.disableIfOutputTimedOut(10_000))
        policy.begin(true, true)
        assertFalse(policy.disableIfOutputTimedOut(10_000))
    }

    @Test fun acceptedKeyframeWithoutOutputDisablesSoftRecoveryForStreamLifetime() {
        val policy = VideoRecoveryPolicy(true, outputTimeoutNs = 100)
        policy.onRendered()
        policy.begin(true, true)
        policy.onKeyFrameQueued(10, 50)
        assertFalse(policy.disableIfOutputTimedOut(149))
        assertTrue(policy.disableIfOutputTimedOut(150))
        assertFalse(policy.disableIfOutputTimedOut(200))
        policy.onDecoderChanged()
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(true, true))
    }

    @Test fun repeatedGapInvalidatesPendingKeyframeWithoutExtendingDeadline() {
        val policy = VideoRecoveryPolicy(true, outputTimeoutNs = 100)
        policy.onRendered()
        policy.begin(true, true)
        policy.onKeyFrameQueued(10, 50)
        assertEquals(VideoRecoveryPolicy.Action.ALREADY_WAITING, policy.begin(true, true))
        assertFalse(policy.canRender(10))
        policy.onKeyFrameQueued(20, 140)
        assertFalse(policy.canRender(10))
        assertTrue(policy.disableIfOutputTimedOut(150))
    }

    @Test fun formatOrSurfaceChangeRequiresFreshFirstOutputAndClearsOldTimestampBoundary() {
        val policy = VideoRecoveryPolicy(true)
        policy.onRendered()
        policy.begin(true, true)
        policy.onKeyFrameQueued(1_000, 1_000_000)
        policy.onDecoderChanged()
        assertTrue(policy.canRender(1))
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(true, true))
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.KEEP_DECODER, policy.begin(true, true))
    }

    @Test fun decoderExceptionDuringSoftAttemptDisablesTheFailedStrategy() {
        val policy = VideoRecoveryPolicy(true)
        policy.onRendered()
        policy.begin(true, true)
        policy.onDecoderFailure()
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(true, true))
    }

    @Test fun hardInputFailureDuringSoftAttemptAlsoDisablesTheFailedStrategy() {
        val policy = VideoRecoveryPolicy(true)
        policy.onRendered()
        policy.begin(true, true)
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(false, true))
        policy.onDecoderChanged()
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(true, true))
    }
}
