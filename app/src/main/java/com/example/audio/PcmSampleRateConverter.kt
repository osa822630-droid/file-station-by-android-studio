package com.example.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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

    /** Converts channel layout and sample rate using bounded frame/output buffers. */
    fun convertRawFile(
        inputFile: File,
        outputFile: File,
        sourceSampleRate: Int,
        sourceChannels: Int,
        targetSampleRate: Int,
        targetChannels: Int,
        onProgress: (Float) -> Unit = {}
    ): PcmFileMetadata? {
        if (!inputFile.isFile || sourceSampleRate <= 0 || targetSampleRate <= 0 ||
            sourceChannels <= 0 || sourceChannels > 32 || targetChannels !in 1..2 ||
            (sourceChannels != targetChannels && targetChannels != 1)) return null
        val sourceFrameBytes = sourceChannels.toLong() * 2L
        val sourceBytes = inputFile.length()
        if (sourceBytes <= 0L || sourceBytes % sourceFrameBytes != 0L) return null
        val sourceFrames = sourceBytes / sourceFrameBytes
        val targetFrames =
            (sourceFrames * targetSampleRate + sourceSampleRate / 2L) / sourceSampleRate
        val outputBytes = targetFrames * targetChannels * 2L
        if (targetFrames <= 0L) return null
        if (outputBytes > PcmFileUtils.MAX_DECODED_PCM_BYTES) {
            throw PcmResourceLimitException("تحويل معدل العينة يحتاج مساحة PCM تتجاوز الحد الآمن.")
        }

        val parent = outputFile.parentFile ?: return null
        if (!parent.exists() && !parent.mkdirs()) return null
        if (sourceSampleRate == targetSampleRate && sourceChannels == targetChannels) {
            return try {
                inputFile.copyTo(outputFile, overwrite = true, bufferSize = 64 * 1024)
                PcmFileMetadata(outputFile, targetSampleRate, targetChannels,
                    targetFrames * 1_000_000L / targetSampleRate)
            } catch (_: Exception) {
                outputFile.delete()
                null
            }
        }

        val rawFrame = ByteArray(sourceFrameBytes.toInt())
        val left = ShortArray(targetChannels)
        val right = ShortArray(targetChannels)
        val outputBuffer = ByteArray(64 * 1024)
        val frameBytes = targetChannels * 2
        var outputPosition = 0
        var leftIndex = 0L
        var rightIndex = if (sourceFrames > 1L) 1L else 0L
        var success = false
        try {
            BufferedInputStream(FileInputStream(inputFile), 64 * 1024).use { input ->
                BufferedOutputStream(FileOutputStream(outputFile), 64 * 1024).use { output ->
                    fun readFrame(destination: ShortArray): Boolean {
                        var readCount = 0
                        while (readCount < rawFrame.size) {
                            val read = input.read(rawFrame, readCount, rawFrame.size - readCount)
                            if (read < 0) return false
                            if (read == 0) continue
                            readCount += read
                        }
                        if (targetChannels == 1 && sourceChannels > 1) {
                            var sum = 0
                            for (channel in 0 until sourceChannels) {
                                val low = rawFrame[channel * 2].toInt() and 0xff
                                val high = rawFrame[channel * 2 + 1].toInt()
                                sum += (low or (high shl 8)).toShort().toInt()
                            }
                            destination[0] = (sum / sourceChannels)
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        } else {
                            for (channel in 0 until targetChannels) {
                                val low = rawFrame[channel * 2].toInt() and 0xff
                                val high = rawFrame[channel * 2 + 1].toInt()
                                destination[channel] = (low or (high shl 8)).toShort()
                            }
                        }
                        return true
                    }

                    if (!readFrame(left)) return null
                    if (sourceFrames > 1L && !readFrame(right)) return null
                    if (sourceFrames == 1L) right[0] = left[0]
                    if (sourceFrames == 1L && targetChannels > 1) {
                        for (channel in 1 until targetChannels) right[channel] = left[channel]
                    }

                    for (outFrame in 0 until targetFrames) {
                        val position = (outFrame.toDouble() * sourceSampleRate / targetSampleRate)
                            .coerceAtMost((sourceFrames - 1L).toDouble())
                        val wantedLeft = position.toLong()
                        while (leftIndex < wantedLeft) {
                            for (channel in 0 until targetChannels) left[channel] = right[channel]
                            leftIndex++
                            if (rightIndex < sourceFrames - 1L) {
                                if (!readFrame(right)) throw IllegalStateException("Truncated PCM source")
                                rightIndex++
                            } else {
                                for (channel in 0 until targetChannels) right[channel] = left[channel]
                                rightIndex = leftIndex
                            }
                        }
                        val fraction = position - leftIndex
                        for (channel in 0 until targetChannels) {
                            val mixed = (left[channel] + (right[channel] - left[channel]) * fraction)
                                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            outputBuffer[outputPosition++] = mixed.toByte()
                            outputBuffer[outputPosition++] = (mixed ushr 8).toByte()
                        }
                        if (outputPosition >= outputBuffer.size - frameBytes) {
                            output.write(outputBuffer, 0, outputPosition)
                            outputPosition = 0
                        }
                        if (outFrame % 16_384L == 0L) {
                            onProgress((outFrame.toFloat() / targetFrames).coerceIn(0f, 0.99f))
                        }
                    }
                    if (outputPosition > 0) output.write(outputBuffer, 0, outputPosition)
                    output.flush()
                }
            }
            if (outputFile.length() != outputBytes) throw IllegalStateException("PCM output length mismatch")
            onProgress(1f)
            success = true
            return PcmFileMetadata(
                outputFile,
                targetSampleRate,
                targetChannels,
                targetFrames * 1_000_000L / targetSampleRate
            )
        } catch (e: PcmResourceLimitException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            if (!success) outputFile.delete()
        }
    }
}
