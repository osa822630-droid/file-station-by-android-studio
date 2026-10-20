package com.example.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavHelper {

    fun writeWavFile(
        outputFile: File,
        pcmData: ShortArray,
        sampleRate: Int,
        channels: Int
    ) {
        require(sampleRate > 0 && channels > 0 && channels <= 0xffff && pcmData.size % channels == 0) {
            "Invalid WAV audio parameters"
        }
        val totalAudioLen = pcmData.size.toLong() * 2L
        require(totalAudioLen <= MAX_RIFF_DATA_BYTES) { "PCM data exceeds the RIFF/WAV size limit" }
        val parent = outputFile.parentFile ?: throw IllegalArgumentException("WAV output needs a parent directory")
        if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("Cannot create WAV output directory")

        FileOutputStream(outputFile).use { fos ->
            fos.write(createHeader(totalAudioLen, sampleRate, channels))
            val byteBuffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcmData) {
                byteBuffer.putShort(sample)
                if (byteBuffer.remaining() < 2) {
                    fos.write(byteBuffer.array(), 0, byteBuffer.position())
                    byteBuffer.clear()
                }
            }
            if (byteBuffer.position() > 0) {
                fos.write(byteBuffer.array(), 0, byteBuffer.position())
            }
        }
    }

    /** Streams a raw little-endian PCM file into a WAV container without materializing it in RAM. */
    fun writeWavFileFromPcmFile(
        outputFile: File,
        pcmFile: File,
        sampleRate: Int,
        channels: Int
    ): Boolean {
        if (!pcmFile.isFile || sampleRate <= 0 || channels <= 0 || channels > 0xffff) return false
        val dataLength = pcmFile.length()
        val blockAlign = channels.toLong() * 2L
        if (dataLength <= 0L || dataLength % blockAlign != 0L || dataLength > MAX_RIFF_DATA_BYTES) return false
        val parent = outputFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        return try {
            FileOutputStream(outputFile).use { output ->
                output.write(createHeader(dataLength, sampleRate, channels))
                FileInputStream(pcmFile).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) output.write(buffer, 0, read)
                    }
                }
                output.fd.sync()
            }
            outputFile.isFile && outputFile.length() == dataLength + 44L
        } catch (_: Exception) {
            outputFile.delete()
            false
        }
    }

    /**
     * Copies PCM16 samples from a RIFF/WAVE file to a raw PCM file in bounded-size chunks.
     * Unsupported WAV codecs/bit depths return null; oversized data throws a resource-limit error.
     */
    internal fun copyWavPcmToRawFile(
        inputFile: File,
        rawOutputFile: File,
        maxPcmBytes: Long = PcmFileUtils.MAX_DECODED_PCM_BYTES
    ): PcmFileMetadata? {
        val info = inspectPcm16Wave(inputFile) ?: return null
        if (info.dataLength > maxPcmBytes) {
            throw PcmResourceLimitException(
                "ملف الصوت غير المضغوط يتجاوز حد البيانات الآمن (${maxPcmBytes / (1024 * 1024)} MiB)."
            )
        }
        val durationUs = (info.dataLength * 1_000_000L) /
            (info.sampleRate.toLong() * info.channels * 2L)
        if (durationUs > PcmFileUtils.MAX_DURATION_US) {
            throw PcmResourceLimitException("مدة الملف تتجاوز الحد الأقصى للتحويل (120 دقيقة).")
        }
        val parent = rawOutputFile.parentFile ?: return null
        if (!parent.exists() && !parent.mkdirs()) return null
        return try {
            RandomAccessFile(inputFile, "r").use { source ->
                source.seek(info.dataOffset)
                BufferedOutputStream(FileOutputStream(rawOutputFile)).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = info.dataLength
                    while (remaining > 0L) {
                        val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) throw IllegalStateException("Unexpected end of WAV data chunk")
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            PcmFileMetadata(
                file = rawOutputFile,
                sampleRate = info.sampleRate,
                channels = info.channels,
                durationUs = durationUs
            )
        } catch (_: Exception) {
            rawOutputFile.delete()
            null
        }
    }

    fun readWavFile(file: File): AudioTranscoder.DecodedPcm? {
        if (!file.exists() || file.length() < 44) return null
        return try {
            val info = inspectPcm16Wave(file) ?: return null
            if (info.dataLength > PcmFileUtils.maxInMemoryPcmBytes() ||
                info.dataLength > Int.MAX_VALUE.toLong() || info.dataLength % 2L != 0L) return null
            val samples = PcmFileUtils.readSamplesFromRange(
                file = file,
                offset = info.dataOffset,
                byteLength = info.dataLength,
                maxBytes = PcmFileUtils.maxInMemoryPcmBytes()
            ) ?: return null
            AudioTranscoder.DecodedPcm(
                samples = samples,
                sampleRate = info.sampleRate,
                channels = info.channels,
                durationUs = (info.dataLength * 1_000_000L) / (info.sampleRate.toLong() * info.channels * 2L)
            )
        } catch (_: Exception) {
            null
        }
    }

    private data class WaveData(
        val dataOffset: Long,
        val dataLength: Long,
        val sampleRate: Int,
        val channels: Int
    )

    private fun inspectPcm16Wave(file: File): WaveData? {
        if (!file.isFile || file.length() < 44L) return null
        return try {
            RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(12)
                input.readFully(header)
                if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                    String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null

                var formatCode = -1
                var channels = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var dataOffset = -1L
                var dataLength = 0L
                while (input.filePointer + 8L <= input.length()) {
                    val chunkHeader = ByteArray(8)
                    input.readFully(chunkHeader)
                    val tag = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xffff_ffffL
                    val chunkStart = input.filePointer
                    if (chunkSize > input.length() - chunkStart) return null
                    val nextChunk = chunkStart + chunkSize + (chunkSize and 1L)
                    if (nextChunk > input.length()) return null

                    if (tag == "fmt ") {
                        if (chunkSize < 16L) return null
                        val fmt = ByteArray(16)
                        input.readFully(fmt)
                        val buffer = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        formatCode = buffer.getShort().toInt() and 0xffff
                        channels = buffer.getShort().toInt() and 0xffff
                        sampleRate = buffer.getInt()
                        buffer.getInt() // byte rate
                        buffer.getShort() // block alignment
                        bitsPerSample = buffer.getShort().toInt() and 0xffff
                    } else if (tag == "data") {
                        dataOffset = chunkStart
                        dataLength = chunkSize
                    }

                    if (formatCode >= 0 && dataOffset >= 0L) break
                    input.seek(nextChunk)
                }

                if (formatCode != 1 || bitsPerSample != 16 || channels <= 0 || sampleRate <= 0 ||
                    dataOffset < 0L || dataLength <= 0L || dataLength % (channels.toLong() * 2L) != 0L) {
                    return null
                }
                WaveData(dataOffset, dataLength, sampleRate, channels)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createHeader(totalAudioLen: Long, sampleRate: Int, channels: Int): ByteArray {
        val totalDataLen = totalAudioLen + 36L
        val byteRate = sampleRate.toLong() * channels * 2L
        require(byteRate <= 0xffff_ffffL) { "WAV byte rate exceeds the RIFF limit" }
        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalDataLen.toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate.toInt())
        buffer.putShort((channels * 2).toShort())
        buffer.putShort(16.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalAudioLen.toInt())
        return header
    }

    private const val MAX_RIFF_DATA_BYTES = 0xffff_ffffL - 36L
}
