package com.example.audio

import com.example.model.AudioFormat

/**
 * Describes the bitrate the current encoder path will actually request from its codec.
 * The UI and library must persist this value, not the user's pre-clamp selection.
 */
object AudioBitratePolicy {
    fun appliedKbps(
        format: AudioFormat,
        requestedKbps: Int,
        sampleRate: Int,
        channels: Int
    ): Int? {
        if (sampleRate <= 0 || channels <= 0) return null
        return when (format) {
            // MPEG-2/2.5 Layer III cannot represent 320 kbps at these lower sample rates.
            AudioFormat.MP3 -> requestedKbps.coerceIn(8, if (sampleRate < 32_000) 160 else 320)
            // Do not impose a mono/stereo quality cap here. The selected platform AAC encoder's
            // advertised bitrate range is authoritative; selectAacEncoderSettings clamps to that
            // range and reports the applied bitrate back to the UI/library.
            AudioFormat.M4A -> requestedKbps.coerceIn(32, 320)
            AudioFormat.WAV -> {
                (sampleRate.toLong() * channels * 16L / 1_000L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            }
            else -> null
        }
    }
}
