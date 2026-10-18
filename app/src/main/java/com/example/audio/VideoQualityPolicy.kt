package com.example.audio

import com.example.model.VideoQuality

/** Pure helpers for the video export settings used by Media3 Transformer. */
internal object VideoQualityPolicy {
    /**
     * Presets define the maximum short edge (720p means 1280x720 landscape or 720x1280 portrait).
     * Never enlarge a source that is already smaller than the chosen preset.
     */
    fun effectiveShortSide(sourceWidth: Int, sourceHeight: Int, targetQuality: VideoQuality): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 0
        return minOf(sourceWidth, sourceHeight, targetQuality.height)
    }

    fun requiresDownscale(sourceWidth: Int, sourceHeight: Int, targetQuality: VideoQuality): Boolean =
        minOf(sourceWidth, sourceHeight) > targetQuality.height

    fun isSupportedSpeed(speed: Float): Boolean =
        speed.isFinite() && speed in 0.5f..2.0f
}
