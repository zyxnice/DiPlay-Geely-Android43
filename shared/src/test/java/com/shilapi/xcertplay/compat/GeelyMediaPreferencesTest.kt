package com.shilapi.xcertplay.compat

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GeelyMediaPreferencesTest {
    @Test fun reenablingQueueDoesNotRestorePreviouslyDisabledRecovery() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("geely_media_performance", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            GeelyMediaPerformance.save(context, GeelyMediaPerformance.VIDEO_QUEUE, true)
            GeelyMediaPerformance.save(context, GeelyMediaPerformance.SOFT_VIDEO_RECOVERY, true)
            assertTrue(prefs.getBoolean(GeelyMediaPerformance.SOFT_VIDEO_RECOVERY, false))
            GeelyMediaPerformance.save(context, GeelyMediaPerformance.VIDEO_QUEUE, false)
            assertFalse(prefs.getBoolean(GeelyMediaPerformance.SOFT_VIDEO_RECOVERY, true))
            GeelyMediaPerformance.save(context, GeelyMediaPerformance.SOFT_VIDEO_RECOVERY, true)
            assertFalse(prefs.getBoolean(GeelyMediaPerformance.SOFT_VIDEO_RECOVERY, true))
            GeelyMediaPerformance.save(context, GeelyMediaPerformance.VIDEO_QUEUE, true)
            assertFalse(prefs.getBoolean(GeelyMediaPerformance.SOFT_VIDEO_RECOVERY, true))
        } finally { prefs.edit().clear().commit() }
    }
}
