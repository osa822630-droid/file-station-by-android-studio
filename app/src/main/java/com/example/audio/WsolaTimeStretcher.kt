package com.example.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * High quality Synchronous Overlap-Add (SOLA / WSOLA) time-scale modification
 * for 16-bit PCM audio. Preserves original pitch and natural voice formant
 * characteristics while scaling playback duration by [speed].
 */
object WsolaTimeStretcher {

    fun process(
        input: ShortArray,
        sampleRate: Int,
        channels: Int,
        speed: Float,
        onProgress: ((Float) -> Unit)? = null
    ): ShortArray {
        if (abs(speed - 1.0f) < 0.01f || input.isEmpty()) {
            return input
        }

        val clampedSpeed = speed.coerceIn(0.5f, 3.0f)
        val ch = max(1, channels)
        
        // Window size ~ 25ms per channel
        var windowSize = ((sampleRate * 0.025f).toInt() * ch)
        if (windowSize % ch != 0) windowSize += (ch - windowSize % ch)
        
        var overlap = windowSize / 2
        if (overlap % ch != 0) overlap += (ch - overlap % ch)
        
        var maxOffset = ((sampleRate * 0.007f).toInt() * ch)
        if (maxOffset % ch != 0) maxOffset += (ch - maxOffset % ch)

        val expectedOutputLength = ((input.size / clampedSpeed).toInt() + windowSize * 2)
        var output = ShortArray(expectedOutputLength)
        var outputPos = 0

        // Copy initial frame
        val initialCopy = min(windowSize, input.size)
        System.arraycopy(input, 0, output, 0, initialCopy)
        outputPos = overlap

        var inputTargetPos = (overlap * clampedSpeed).toInt()
        val totalSteps = (input.size / (overlap * clampedSpeed)).toInt().coerceAtLeast(1)
        var stepCount = 0

        while (inputTargetPos + windowSize + maxOffset < input.size) {
            // Find best alignment offset within [-maxOffset, maxOffset] to maximize cross-correlation
            val bestOffset = findBestOffset(
                input = input,
                targetPos = inputTargetPos,
                maxOffset = maxOffset,
                output = output,
                overlapPos = outputPos,
                overlapLength = overlap,
                channels = ch
            )

            val matchedInputPos = inputTargetPos + bestOffset

            // Crossfade overlap segment
            val invOverlap = 1.0f / (overlap / ch)
            for (i in 0 until overlap step ch) {
                val weight = (i / ch) * invOverlap
                val invWeight = 1.0f - weight
                for (c in 0 until ch) {
                    val outIdx = outputPos + i + c
                    val inIdx = matchedInputPos + i + c
                    if (outIdx < output.size && inIdx < input.size) {
                        val blended = (output[outIdx] * invWeight + input[inIdx] * weight).toInt()
                        output[outIdx] = blended.coerceIn(-32768, 32767).toShort()
                    }
                }
            }

            // Copy non-overlapping segment
            val nonOverlap = windowSize - overlap
            for (i in 0 until nonOverlap step ch) {
                for (c in 0 until ch) {
                    val outIdx = outputPos + overlap + i + c
                    val inIdx = matchedInputPos + overlap + i + c
                    if (outIdx < output.size && inIdx < input.size) {
                        output[outIdx] = input[inIdx]
                    }
                }
            }

            outputPos += overlap
            inputTargetPos = (outputPos * clampedSpeed).toInt()
            stepCount++

            if (stepCount % 50 == 0 && onProgress != null) {
                val progress = (matchedInputPos.toFloat() / input.size).coerceIn(0.0f, 1.0f)
                onProgress(progress)
            }

            // Expand output buffer if needed
            if (outputPos + windowSize * 2 >= output.size) {
                val newBuffer = ShortArray(output.size + expectedOutputLength / 4 + windowSize * 4)
                System.arraycopy(output, 0, newBuffer, 0, outputPos + windowSize)
                output = newBuffer
            }
        }

        onProgress?.invoke(1.0f)

        val finalResult = ShortArray(outputPos + overlap)
        System.arraycopy(output, 0, finalResult, 0, finalResult.size)
        return finalResult
    }

    private fun findBestOffset(
        input: ShortArray,
        targetPos: Int,
        maxOffset: Int,
        output: ShortArray,
        overlapPos: Int,
        overlapLength: Int,
        channels: Int
    ): Int {
        var bestCorrelation = Long.MIN_VALUE
        var bestOffset = 0
        val step = channels

        val minOffset = -maxOffset
        for (offset in minOffset..maxOffset step step) {
            val candidatePos = targetPos + offset
            if (candidatePos < 0 || candidatePos + overlapLength > input.size) continue

            var correlation = 0L
            // Subsample for fast correlation check
            val stride = channels * 2
            for (i in 0 until overlapLength step stride) {
                val s1 = output[overlapPos + i].toLong()
                val s2 = input[candidatePos + i].toLong()
                correlation += (s1 * s2)
            }

            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestOffset = offset
            }
        }

        return bestOffset
    }
}
