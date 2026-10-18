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
            AudioFormat.MP3 -> requestedKbps.coerceIn(8, if (sampleRate < 32_000) 160 else 320)
            AudioFormat.M4A -> {
                val outputChannels = if (channels <= 2) channels else 1
                requestedKbps.coerceIn(32, if (outputChannels == 1) 96 else 160)
            }
            AudioFormat.WAV -> {
                (sampleRate.toLong() * channels * 16L / 1_000L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            }
            else -> null
        }
    }
}
