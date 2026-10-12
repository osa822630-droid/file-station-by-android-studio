package com.example.audio

import java.io.File
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
        val totalAudioLen = pcmData.size * 2L
        val totalDataLen = totalAudioLen + 36L
        val byteRate = (sampleRate * channels * 2).toLong()

        FileOutputStream(outputFile).use { fos ->
            val header = ByteArray(44)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            buffer.put("RIFF".toByteArray())
            buffer.putInt(totalDataLen.toInt())
            buffer.put("WAVE".toByteArray())

            // Format sub-chunk
            buffer.put("fmt ".toByteArray())
            buffer.putInt(16) // SubChunk1Size (16 for PCM)
            buffer.putShort(1.toShort()) // AudioFormat (1 for PCM)
            buffer.putShort(channels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate.toInt())
            buffer.putShort((channels * 2).toShort()) // BlockAlign
            buffer.putShort(16.toShort()) // BitsPerSample

            // Data sub-chunk
            buffer.put("data".toByteArray())
            buffer.putInt(totalAudioLen.toInt())

            fos.write(header)

            // Write PCM samples in byte chunks
            val byteBuffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcmData) {
                byteBuffer.putShort(sample)
                if (!byteBuffer.hasRemaining()) {
                    fos.write(byteBuffer.array())
                    byteBuffer.clear()
                }
            }
            if (byteBuffer.position() > 0) {
                fos.write(byteBuffer.array(), 0, byteBuffer.position())
            }
        }
    }

    fun readWavFile(file: File): AudioTranscoder.DecodedPcm? {
        if (!file.exists() || file.length() < 44) return null
        return try {
            java.io.FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) < 44) return null
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

                val riff = ByteArray(4)
                buf.get(riff)
                if (String(riff) != "RIFF") return null

                buf.getInt() // size
                val wave = ByteArray(4)
                buf.get(wave)
                if (String(wave) != "WAVE") return null

                val fmt = ByteArray(4)
                buf.get(fmt)
                if (String(fmt) != "fmt ") return null

                val fmtSize = buf.getInt()
                val audioFormat = buf.getShort().toInt()
                val channels = buf.getShort().toInt()
                val sampleRate = buf.getInt()
                buf.getInt() // byteRate
                buf.getShort() // blockAlign
                val bitsPerSample = buf.getShort().toInt()

                if (fmtSize > 16) {
                    val extra = fmtSize - 16
                    fis.skip(extra.toLong())
                }

                var dataSize = 0
                var dataFound = false

                if (fmtSize == 16) {
                    val dataTag = ByteArray(4)
                    buf.get(dataTag)
                    if (String(dataTag) == "data") {
                        dataSize = buf.getInt()
                        dataFound = true
                    }
                }

                if (!dataFound) {
                    val tagBuf = ByteArray(8)
                    while (fis.read(tagBuf) == 8) {
                        val tagWrap = ByteBuffer.wrap(tagBuf).order(ByteOrder.LITTLE_ENDIAN)
                        val tag = ByteArray(4)
                        tagWrap.get(tag)
                        val size = tagWrap.getInt()
                        if (String(tag) == "data") {
                            dataSize = size
                            dataFound = true
                            break
                        } else {
                            if (size > 0) fis.skip(size.toLong()) else break
                        }
                    }
                }

                if (!dataFound || dataSize <= 0 || channels <= 0 || sampleRate <= 0) {
                    return null
                }

                val samplesCount = dataSize / 2
                val pcmData = ShortArray(samplesCount)
                val byteChunk = ByteArray(4096)
                var samplesRead = 0
                val byteBuffer = ByteBuffer.wrap(byteChunk).order(ByteOrder.LITTLE_ENDIAN)

                while (samplesRead < samplesCount) {
                    val bytesToRead = Math.min(byteChunk.size, (samplesCount - samplesRead) * 2)
                    val read = fis.read(byteChunk, 0, bytesToRead)
                    if (read <= 0) break
                    byteBuffer.position(0)
                    byteBuffer.limit(read)
                    while (byteBuffer.remaining() >= 2 && samplesRead < samplesCount) {
                        pcmData[samplesRead++] = byteBuffer.short
                    }
                }

                val finalSamples = if (samplesRead == samplesCount) pcmData else pcmData.copyOf(samplesRead)
                val durationUs = (finalSamples.size.toLong() * 1000000L) / (sampleRate * channels)

                AudioTranscoder.DecodedPcm(
                    samples = finalSamples,
                    sampleRate = sampleRate,
                    channels = channels,
                    durationUs = durationUs
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
