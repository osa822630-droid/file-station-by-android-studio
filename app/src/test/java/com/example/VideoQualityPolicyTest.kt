package com.example

import com.example.audio.VideoQualityPolicy
import com.example.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoQualityPolicyTest {
    @Test
    fun higherSourceThanTargetRequiresRealDownscale() {
        assertTrue(VideoQualityPolicy.requiresDownscale(1920, 1080, VideoQuality.Q720P))
    }

    @Test
    fun sourceAtTargetDoesNotRequireDownscale() {
        assertFalse(VideoQualityPolicy.requiresDownscale(1280, 720, VideoQuality.Q720P))
    }

    @Test
    fun lowerSourceIsNotUpscaled() {
        assertFalse(VideoQualityPolicy.requiresDownscale(640, 360, VideoQuality.Q720P))
        assertEquals(360, VideoQualityPolicy.effectiveShortSide(640, 360, VideoQuality.Q720P))
    }

    @Test
    fun portraitAndLandscapePresetsUseTheSameShortEdge() {
        assertEquals(720, VideoQualityPolicy.effectiveShortSide(1920, 1080, VideoQuality.Q720P))
        assertEquals(720, VideoQualityPolicy.effectiveShortSide(1080, 1920, VideoQuality.Q720P))
    }

    @Test
    fun onlyVisibleSpeedRangeIsAccepted() {
        assertTrue(VideoQualityPolicy.isSupportedSpeed(0.5f))
        assertTrue(VideoQualityPolicy.isSupportedSpeed(2.0f))
        assertFalse(VideoQualityPolicy.isSupportedSpeed(0.25f))
        assertFalse(VideoQualityPolicy.isSupportedSpeed(Float.NaN))
    }
}
