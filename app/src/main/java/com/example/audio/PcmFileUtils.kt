package com.example.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

internal data class PcmFileMetadata(
    val file: File,
    val sampleRate: Int,
    val channels: Int,
    val durationUs: Long
) {
    val sampleCount: Long
        get() = file.length() / 2L
}

internal class PcmResourceLimitException(message: String) : IllegalStateException(message)

/** File-backed PCM helpers keep ordinary-rate conversions independent of audio duration. */
internal object PcmFileUtils {
    const val MAX_SOURCE_FILE_BYTES = 1L * 1024 * 1024 * 1024
    const val MAX_DECODED_PCM_BYTES = 512L * 1024 * 1024
    const val MAX_DURATION_US = 2L * 60 * 60 * 1_000_000

    fun maxInMemoryPcmBytes(): Long =
        minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8L)
            .coerceAtLeast(1L * 1024 * 1024)

    fun maxWsolaInputBytes(): Long =
        minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16L)
            .coerceAtLeast(1L * 1024 * 1024)

    fun maxWsolaOutputBytes(): Long =
        minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8L)
            .coerceAtLeast(2L * 1024 * 1024)

    fun readSamples(file: File, maxBytes: Long): ShortArray? {
        val byteCount = file.length()
        if (!file.isFile || byteCount <= 0L || byteCount > maxBytes || byteCount % 2L != 0L) return null
        val sampleCount = byteCount / 2L
        if (sampleCount > Int.MAX_VALUE) return null
        val samples = ShortArray(sampleCount.toInt())
        val bytes = ByteArray(64 * 1024)
        var samplesWritten = 0
        BufferedInputStream(FileInputStream(file)).use { input ->
            while (samplesWritten < samples.size) {
                val wanted = minOf(bytes.size, (samples.size - samplesWritten) * 2)
                var count = 0
                while (count < wanted) {
                    val read = input.read(bytes, count, wanted - count)
                    if (read < 0) return null
                    if (read == 0) continue
                    count += read
                }
                var byteIndex = 0
                while (byteIndex + 1 < count) {
                    val low = bytes[byteIndex].toInt() and 0xff
                    val high = bytes[byteIndex + 1].toInt()
                    samples[samplesWritten++] = (low or (high shl 8)).toShort()
                    byteIndex += 2
                }
            }
        }
        return samples
    }

    fun readSamplesFromRange(
        file: File,
        offset: Long,
        byteLength: Long,
        maxBytes: Long
    ): ShortArray? {
        if (!file.isFile || offset < 0L || byteLength <= 0L || byteLength > maxBytes ||
            byteLength % 2L != 0L || offset + byteLength > file.length()) return null
        val sampleCount = byteLength / 2L
        if (sampleCount > Int.MAX_VALUE) return null
        val samples = ShortArray(sampleCount.toInt())
        val bytes = ByteArray(64 * 1024)
        var samplesWritten = 0
        java.io.RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            while (samplesWritten < samples.size) {
                val wanted = minOf(bytes.size, (samples.size - samplesWritten) * 2)
                var count = 0
                while (count < wanted) {
                    val read = input.read(bytes, count, wanted - count)
                    if (read < 0) return null
                    if (read == 0) continue
                    count += read
                }
                var byteIndex = 0
                while (byteIndex + 1 < count) {
                    val low = bytes[byteIndex].toInt() and 0xff
                    val high = bytes[byteIndex + 1].toInt()
                    samples[samplesWritten++] = (low or (high shl 8)).toShort()
                    byteIndex += 2
                }
            }
        }
        return samples
    }

    fun writeSamples(file: File, samples: ShortArray) {
        val parent = file.parentFile ?: throw IllegalArgumentException("PCM file must have a parent directory")
        if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("Cannot create PCM working directory")
        val bytes = ByteArray(64 * 1024)
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            var sampleIndex = 0
            while (sampleIndex < samples.size) {
                val sampleCount = minOf(bytes.size / 2, samples.size - sampleIndex)
                var byteIndex = 0
                repeat(sampleCount) {
                    val value = samples[sampleIndex++].toInt()
                    bytes[byteIndex++] = value.toByte()
                    bytes[byteIndex++] = (value ushr 8).toByte()
                }
                output.write(bytes, 0, byteIndex)
            }
        }
    }

    fun newRawFile(directory: File, prefix: String): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Cannot create PCM working directory")
        }
        return File(directory, "${prefix}_${UUID.randomUUID()}.pcm")
    }
}
