package com.example.audio

/** Linear-interpolation resampler for interleaved signed 16-bit PCM samples. */
internal object PcmSampleRateConverter {
    fun resample(
        pcmData: ShortArray,
        sourceSampleRate: Int,
        targetSampleRate: Int,
        channels: Int
    ): ShortArray? {
        if (sourceSampleRate <= 0 || targetSampleRate <= 0 || channels <= 0 ||
            pcmData.isEmpty() || pcmData.size % channels != 0) return null
        if (sourceSampleRate == targetSampleRate) return pcmData

        val sourceFrames = pcmData.size / channels
        val targetFramesLong =
            (sourceFrames.toLong() * targetSampleRate + sourceSampleRate / 2) / sourceSampleRate
        if (targetFramesLong <= 0L || targetFramesLong > Int.MAX_VALUE / channels) return null
        val targetFrames = targetFramesLong.toInt()
        val output = ShortArray(targetFrames * channels)
        for (frame in 0 until targetFrames) {
            val sourcePosition = frame.toDouble() * sourceSampleRate / targetSampleRate
            val leftFrame = sourcePosition.toInt().coerceIn(0, sourceFrames - 1)
            val rightFrame = minOf(leftFrame + 1, sourceFrames - 1)
            val fraction = sourcePosition - leftFrame
            for (channel in 0 until channels) {
                val left = pcmData[leftFrame * channels + channel].toDouble()
                val right = pcmData[rightFrame * channels + channel].toDouble()
                output[frame * channels + channel] =
                    (left + (right - left) * fraction).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return output
    }
}
