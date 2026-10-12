package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.model.AudioFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.UUID

class AudioTranscoder(private val context: Context) {

    data class DecodedPcm(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
        val durationUs: Long
    )

    suspend fun transcodeAudio(
        sourceUri: Uri?,
        sourceFile: File?,
        outputFile: File,
        targetSpeed: Float,
        targetFormat: AudioFormat,
        bitrateKbps: Int = 128,
        onProgress: (progress: Float, stage: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress(0.05f, "قراءة وفك تشفير الصوت...")
            val decoded = decodeToPcm(sourceUri, sourceFile) { decodeProgress ->
                onProgress(0.05f + decodeProgress * 0.35f, "فك تشفير الصوت (${(decodeProgress * 100).toInt()}%)")
            } ?: return@withContext false

            val finalSamples: ShortArray
            if (kotlin.math.abs(targetSpeed - 1.0f) > 0.01f) {
                onProgress(0.40f, "تعديل السرعة إلى ${targetSpeed}x مع الحفاظ على النبرة...")
                finalSamples = WsolaTimeStretcher.process(
                    input = decoded.samples,
                    sampleRate = decoded.sampleRate,
                    channels = decoded.channels,
                    speed = targetSpeed
                ) { stretchProgress ->
                    onProgress(0.40f + stretchProgress * 0.35f, "معالجة سرعة الصوت (${(stretchProgress * 100).toInt()}%)")
                }
            } else {
                finalSamples = decoded.samples
            }

            onProgress(0.80f, "حفظ وترميز الملف بمعدل ${bitrateKbps}k...")

            val success = when (targetFormat) {
                AudioFormat.WAV -> {
                    WavHelper.writeWavFile(
                        outputFile = outputFile,
                        pcmData = finalSamples,
                        sampleRate = decoded.sampleRate,
                        channels = decoded.channels
                    )
                    true
                }
                AudioFormat.M4A, AudioFormat.MP4, AudioFormat.MP3, AudioFormat.MKV, AudioFormat.WEBM -> {
                    encodePcmToAudio(
                        pcmData = finalSamples,
                        sampleRate = decoded.sampleRate,
                        channels = decoded.channels,
                        outputFile = outputFile,
                        bitrateKbps = bitrateKbps
                    ) { encodeProgress ->
                        onProgress(0.80f + encodeProgress * 0.20f, "ترميز الصوت ${bitrateKbps}k (${(encodeProgress * 100).toInt()}%)")
                    }
                }
            }

            onProgress(1.0f, "تمت المعالجة بنجاح!")
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun decodeToPcm(
        sourceUri: Uri?,
        sourceFile: File?,
        onProgress: (Float) -> Unit
    ): DecodedPcm? {
        var tempCachedFile: File? = null
        val fileToProcess: File? = try {
            if (sourceFile != null && sourceFile.exists()) {
                sourceFile
            } else if (sourceUri != null) {
                // Copy stream to cache file to ensure MediaExtractor has a real, seekable file
                val cacheFile = File(context.cacheDir, "input_transcode_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.tmp")
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempCachedFile = cacheFile
                cacheFile
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (fileToProcess == null || !fileToProcess.exists() || fileToProcess.length() < 32) {
            tempCachedFile?.delete()
            return null
        }

        // 1. Direct WAV reading fast path (avoids NuMediaExtractor entirely for WAV)
        try {
            val wavResult = WavHelper.readWavFile(fileToProcess)
            if (wavResult != null) {
                tempCachedFile?.delete()
                onProgress(1.0f)
                return wavResult
            }
        } catch (_: Exception) {}

        // Check if file is invalid HTML/JSON text response
        try {
            val headerBytes = ByteArray(32)
            java.io.FileInputStream(fileToProcess).use { it.read(headerBytes) }
            val headerString = String(headerBytes, Charsets.UTF_8).lowercase()
            if (headerString.startsWith("<!doc") || headerString.startsWith("<html") || 
                headerString.startsWith("{\"error") || headerString.startsWith("<?xml")) {
                tempCachedFile?.delete()
                return null
            }
        } catch (_: Exception) {}

        // 2. Decode using MediaExtractor + MediaCodec
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(fileToProcess.absolutePath)

            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            var mime: String? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val m = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = format
                    mime = m
                    break
                }
            }

            if (audioTrackIndex < 0 || trackFormat == null || mime == null) {
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            val sampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            val channels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val pcmChunks = mutableListOf<ShortArray>()
            var totalSamples = 0L

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEos = false
            var isOutputEos = false
            val timeoutUs = 5000L

            while (!isOutputEos) {
                if (!isInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputEos = true
                            } else {
                                val sampleTime = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val shorts = ShortArray(shortBuf.remaining())
                        shortBuf.get(shorts)
                        pcmChunks.add(shorts)
                        totalSamples += shorts.size

                        if (durationUs > 0) {
                            val progress = (bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0.0f, 1.0f)
                            onProgress(progress)
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEos = true
                    }
                }
            }

            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}

            // Merge PCM chunks
            val merged = ShortArray(totalSamples.toInt())
            var offset = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, merged, offset, chunk.size)
                offset += chunk.size
            }

            return DecodedPcm(
                samples = merged,
                sampleRate = sampleRate,
                channels = channels,
                durationUs = durationUs
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            tempCachedFile?.delete()
        }
    }

    private fun downmixToMono(pcmData: ShortArray, originalChannels: Int): ShortArray {
        if (originalChannels <= 1) return pcmData
        val numFrames = pcmData.size / originalChannels
        val mono = ShortArray(numFrames)
        for (i in 0 until numFrames) {
            var sum = 0
            for (c in 0 until originalChannels) {
                sum += pcmData[i * originalChannels + c].toInt()
            }
            mono[i] = (sum / originalChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return mono
    }

    /**
     * Appends a standard 7-byte ADTS header to an AAC packet.
     * This turns raw AAC frames into a standalone stream readable by Android MediaPlayer,
     * ExoPlayer, VLC, and mobile devices without requiring MediaMuxer or MPEG4Writer.
     */
    private fun addAdtsHeader(packet: ByteArray, packetLen: Int, sampleRate: Int, channelCount: Int) {
        val sampleRateIndex = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            else -> 4 // 44100 default
        }
        val profile = 2 // AAC LC = 2 in MPEG-4 (ADTS field is profile - 1 = 1)
        val freqIdx = sampleRateIndex
        val chanCfg = channelCount.coerceIn(1, 2)

        packet[0] = 0xFF.toByte() // Syncword (all 1s)
        packet[1] = 0xF9.toByte() // 0b11111001: Syncword + MPEG-2 + Layer 0 + Protection absent (1 = no CRC)
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    /**
     * Encodes PCM audio to a highly compatible standalone audio stream using ADTS AAC headers.
     * Completely eliminates MediaMuxer and native MPEG4Writer errors (Stop() called on track).
     * Automatically falls back to clean PCM WAV if MediaCodec hardware/software encoder is unavailable.
     */
    private fun encodePcmToAudio(
        pcmData: ShortArray,
        sampleRate: Int,
        channels: Int,
        outputFile: File,
        bitrateKbps: Int = 128,
        onProgress: (Float) -> Unit
    ): Boolean {
        // Safe channel and bitrate calculations:
        // Clamped to conservative AAC-LC limits to prevent Codec2 resource query error 6
        val (pcmToEncode, effectiveChannels, effectiveBitrate) = if (bitrateKbps <= 64 || channels > 2) {
            val mono = downmixToMono(pcmData, channels)
            Triple(mono, 1, (bitrateKbps * 1000).coerceIn(32000, 96000))
        } else {
            val safeCh = if (channels in 1..2) channels else 2
            val minBps = if (safeCh == 1) 32000 else 64000
            val maxBps = if (safeCh == 1) 96000 else 160000
            Triple(pcmData, safeCh, (bitrateKbps * 1000).coerceIn(minBps, maxBps))
        }

        val standardSampleRates = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
        val effectiveSampleRate = if (sampleRate in standardSampleRates) {
            sampleRate
        } else {
            standardSampleRates.minByOrNull { kotlin.math.abs(it - sampleRate) } ?: 44100
        }

        val tempStreamFile = File(context.cacheDir, "temp_stream_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.tmp")
        var encoder: MediaCodec? = null
        var totalBytesWritten = 0L

        try {
            val mime = "audio/mp4a-latm"
            val format = MediaFormat.createAudioFormat(mime, effectiveSampleRate, effectiveChannels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate)
            }

            encoder = try {
                MediaCodec.createEncoderByType(mime).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to baseline 64k mono format
                val fallbackFormat = MediaFormat.createAudioFormat(mime, 44100, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                }
                try {
                    MediaCodec.createEncoderByType(mime).apply {
                        configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }
                } catch (e2: Exception) {
                    e2.printStackTrace()
                    null
                }
            }

            if (encoder == null) {
                // If AAC encoder cannot be allocated, write 100% compliant WAV file
                WavHelper.writeWavFile(outputFile, pcmData, sampleRate, channels)
                return true
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var pcmOffset = 0
            val timeoutUs = 5000L
            var isInputEos = false
            var isOutputEos = false
            var idleOutputCount = 0
            val adtsHeader = ByteArray(7)

            FileOutputStream(tempStreamFile).use { outStream ->
                while (!isOutputEos) {
                    if (!isInputEos) {
                        val inIndex = encoder.dequeueInputBuffer(timeoutUs)
                        if (inIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                val remainingSamples = pcmToEncode.size - pcmOffset
                                val samplesToPut = kotlin.math.min(remainingSamples, inputBuffer.capacity() / 2)

                                if (samplesToPut <= 0) {
                                    encoder.queueInputBuffer(
                                        inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    isInputEos = true
                                } else {
                                    val shortBuf = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                    shortBuf.put(pcmToEncode, pcmOffset, samplesToPut)
                                    val bytesWritten = samplesToPut * 2
                                    val presentationTimeUs = (pcmOffset.toLong() * 1000000L) / (effectiveSampleRate * effectiveChannels)
                                    encoder.queueInputBuffer(inIndex, 0, bytesWritten, presentationTimeUs, 0)
                                    pcmOffset += samplesToPut

                                    val progress = (pcmOffset.toFloat() / pcmToEncode.size).coerceIn(0.0f, 1.0f)
                                    onProgress(progress)
                                }
                            }
                        }
                    }

                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    if (outIndex >= 0) {
                        idleOutputCount = 0
                        val outputBuffer = encoder.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val frameLen = bufferInfo.size + 7
                            addAdtsHeader(adtsHeader, frameLen, effectiveSampleRate, effectiveChannels)
                            outStream.write(adtsHeader)

                            val payload = ByteArray(bufferInfo.size)
                            outputBuffer.get(payload)
                            outStream.write(payload)
                            totalBytesWritten += frameLen
                        }
                        encoder.releaseOutputBuffer(outIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEos = true
                        }
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (isInputEos) {
                            idleOutputCount++
                            if (idleOutputCount > 50) {
                                // Break to prevent endless hang if encoder dropped EOS
                                isOutputEos = true
                            }
                        }
                    }
                }
                outStream.flush()
            }

            if (totalBytesWritten > 64 && tempStreamFile.exists()) {
                if (outputFile.exists()) outputFile.delete()
                val renamed = tempStreamFile.renameTo(outputFile)
                if (!renamed) {
                    tempStreamFile.inputStream().use { inp ->
                        FileOutputStream(outputFile).use { out ->
                            inp.copyTo(out)
                        }
                    }
                    tempStreamFile.delete()
                }
                return true
            } else {
                tempStreamFile.delete()
                // If stream was empty, write clean WAV
                WavHelper.writeWavFile(outputFile, pcmData, sampleRate, channels)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            tempStreamFile.delete()
            return try {
                WavHelper.writeWavFile(outputFile, pcmData, sampleRate, channels)
                true
            } catch (ex: Exception) {
                ex.printStackTrace()
                false
            }
        } finally {
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
        }
    }
}
