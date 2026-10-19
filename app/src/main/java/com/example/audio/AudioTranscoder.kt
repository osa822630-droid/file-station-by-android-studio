package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.example.model.AudioFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.UUID

class AudioTranscoder(private val context: Context) {

    private data class AacEncoderSettings(
        val codecName: String,
        val sampleRate: Int,
        val channels: Int,
        val bitrateKbps: Int,
        val bitrateBps: Int
    )

    private data class AacOutputFormat(
        val sampleRate: Int,
        val channels: Int,
        val bitrateKbps: Int
    )

    data class DecodedPcm(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
        val durationUs: Long
    )

    data class AudioOutputInfo(
        val format: AudioFormat,
        val requestedBitrateKbps: Int,
        val appliedBitrateKbps: Int,
        val sampleRate: Int,
        val channels: Int,
        val sourceSampleRate: Int,
        val sourceChannels: Int
    ) {
        val requestWasLimited: Boolean
            get() = format != AudioFormat.WAV && appliedBitrateKbps < requestedBitrateKbps
        val requestWasAdjusted: Boolean
            get() = format != AudioFormat.WAV && appliedBitrateKbps != requestedBitrateKbps
    }

    suspend fun transcodeAudio(
        sourceUri: Uri?,
        sourceFile: File?,
        outputFile: File,
        targetSpeed: Float,
        targetFormat: AudioFormat,
        bitrateKbps: Int = 128,
        onOutputInfo: (AudioOutputInfo) -> Unit = {},
        onProgress: (progress: Float, stage: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (targetFormat !in setOf(AudioFormat.WAV, AudioFormat.M4A, AudioFormat.MP3) ||
            !outputFile.extension.equals(targetFormat.extension, ignoreCase = true)) {
            return@withContext false
        }
        val parent = outputFile.parentFile ?: return@withContext false
        if (!parent.exists() && !parent.mkdirs()) return@withContext false
        val stagedOutput = File(
            parent,
            ".${outputFile.nameWithoutExtension}.${UUID.randomUUID()}.pending.${targetFormat.extension}"
        )
        var outputInfo: AudioOutputInfo? = null
        try {
            val encoded = transcodeAudioInternal(
                sourceUri = sourceUri,
                sourceFile = sourceFile,
                outputFile = stagedOutput,
                targetSpeed = targetSpeed,
                targetFormat = targetFormat,
                bitrateKbps = bitrateKbps,
                onOutputInfo = { outputInfo = it },
                onProgress = onProgress
            )
            if (!encoded || !isValidEncodedAudio(stagedOutput, targetFormat)) {
                onProgress(0.99f, "تعذر التحقق من ملف الصوت الناتج؛ لم يُستبدل الملف السابق.")
                return@withContext false
            }
            var renameError: Exception? = null
            try {
                android.system.Os.rename(stagedOutput.absolutePath, outputFile.absolutePath)
            } catch (e: Exception) {
                renameError = e
            }
            // File.renameTo also maps to a same-filesystem rename on Android/Linux. The
            // existence check additionally handles test shadows that report success but do
            // not perform the native rename.
            if (stagedOutput.exists() && !stagedOutput.renameTo(outputFile)) {
                throw renameError ?: IllegalStateException("Unable to publish validated audio stage")
            }
            if (!isValidEncodedAudio(outputFile, targetFormat)) {
                throw IllegalStateException("Published audio output failed post-rename validation")
            }
            runCatching { outputInfo?.let(onOutputInfo) }
            runCatching { onProgress(1.0f, "تم حفظ الملف الصوتي والتحقق منه بنجاح.") }
            true
        } catch (e: Exception) {
            android.util.Log.e("AudioTranscoder", "Audio transcode failed before publish", e)
            false
        } finally {
            stagedOutput.delete()
        }
    }

    private suspend fun transcodeAudioInternal(
        sourceUri: Uri?,
        sourceFile: File?,
        outputFile: File,
        targetSpeed: Float,
        targetFormat: AudioFormat,
        bitrateKbps: Int,
        onOutputInfo: (AudioOutputInfo) -> Unit,
        onProgress: (progress: Float, stage: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        // Each advertised audio extension is paired with its own real encoder/container.
        if (targetFormat != AudioFormat.WAV && targetFormat != AudioFormat.M4A &&
            targetFormat != AudioFormat.MP3) {
            return@withContext false
        }
        if (!outputFile.extension.equals(targetFormat.extension, ignoreCase = true)) {
            return@withContext false
        }

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

            val aacSettings = if (targetFormat == AudioFormat.M4A) {
                selectAacEncoderSettings(decoded.sampleRate, decoded.channels, bitrateKbps)
                    ?: return@withContext false
            } else null
            val encodedSamples = if (aacSettings != null) {
                preparePcmForAac(
                    pcmData = finalSamples,
                    sourceSampleRate = decoded.sampleRate,
                    sourceChannels = decoded.channels,
                    target = aacSettings
                ) ?: return@withContext false
            } else finalSamples
            val appliedBitrateKbps = aacSettings?.bitrateKbps ?: AudioBitratePolicy.appliedKbps(
                format = targetFormat,
                requestedKbps = bitrateKbps,
                sampleRate = decoded.sampleRate,
                channels = decoded.channels
            ) ?: return@withContext false
            val bitrateDescription = when {
                targetFormat == AudioFormat.WAV -> "PCM WAV بمعدل خام يقارب ${appliedBitrateKbps} kbps"
                appliedBitrateKbps != bitrateKbps ->
                    "${targetFormat.name}: المطلوب ${bitrateKbps} kbps، والمطبق ${appliedBitrateKbps} kbps"
                else -> "${targetFormat.name} بمعدل ${appliedBitrateKbps} kbps"
            }
            onProgress(0.80f, "حفظ وترميز الملف — $bitrateDescription...")

            var actualAacOutput: AacOutputFormat? = null
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
                AudioFormat.MP3 -> encodePcmToMp3(
                    pcmData = finalSamples,
                    sampleRate = decoded.sampleRate,
                    channels = decoded.channels,
                    outputFile = outputFile,
                    bitrateKbps = appliedBitrateKbps
                ) { encodeProgress ->
                    onProgress(0.80f + encodeProgress * 0.20f, "ترميز MP3 (${(encodeProgress * 100).toInt()}%)")
                }
                AudioFormat.M4A -> encodePcmToM4a(
                    pcmData = encodedSamples,
                    settings = aacSettings ?: return@withContext false,
                    outputFile = outputFile,
                    onOutputFormat = { actualAacOutput = it }
                ) { encodeProgress ->
                    val visibleProgress = encodeProgress.coerceAtMost(0.99f)
                    onProgress(0.80f + visibleProgress * 0.20f, "ترميز AAC داخل M4A (${(visibleProgress * 100).toInt()}%)")
                }
                else -> false
            }

            if (success) {
                val actualSampleRate = actualAacOutput?.sampleRate ?: aacSettings?.sampleRate ?: decoded.sampleRate
                val actualChannels = actualAacOutput?.channels ?: aacSettings?.channels ?:
                    if (targetFormat == AudioFormat.MP3 && decoded.channels > 2) 1 else decoded.channels
                val actualBitrate = actualAacOutput?.bitrateKbps ?: appliedBitrateKbps
                onOutputInfo(
                    AudioOutputInfo(
                        format = targetFormat,
                        requestedBitrateKbps = bitrateKbps,
                        appliedBitrateKbps = actualBitrate,
                        sampleRate = actualSampleRate,
                        channels = actualChannels,
                        sourceSampleRate = decoded.sampleRate,
                        sourceChannels = decoded.channels
                    )
                )
            }
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun isValidEncodedAudio(file: File, format: AudioFormat): Boolean {
        if (!file.isFile || file.length() <= 32L) return false
        return when (format) {
            AudioFormat.WAV -> try {
                java.io.DataInputStream(file.inputStream()).use { input ->
                    val header = ByteArray(12)
                    input.readFully(header)
                    String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
                }
            } catch (_: Exception) {
                false
            }
            AudioFormat.MP3 -> containsMpegLayer3Frame(file)
            AudioFormat.M4A -> {
                val hasFtyp = try {
                    java.io.DataInputStream(file.inputStream()).use { input ->
                        val header = ByteArray(8)
                        input.readFully(header)
                        String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
                    }
                } catch (_: Exception) {
                    false
                }
                if (!hasFtyp) return false
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    (0 until extractor.trackCount).any { index ->
                        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                } catch (_: Exception) {
                    false
                } finally {
                    try { extractor.release() } catch (_: Exception) {}
                }
            }
            else -> false
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
        var decoder: MediaCodec? = null
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

            val activeDecoder = MediaCodec.createDecoderByType(mime)
            decoder = activeDecoder
            activeDecoder.configure(trackFormat, null, null, 0)
            activeDecoder.start()

            val pcmChunks = mutableListOf<ShortArray>()
            var totalSamples = 0L
            var decodedSampleRate = sampleRate
            var decodedChannels = channels
            var decodedPcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEos = false
            var isOutputEos = false
            val timeoutUs = 5000L

            var idleIterationCount = 0
            while (!isOutputEos) {
                var madeProgress = false
                if (!isInputEos) {
                    val inIndex = activeDecoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuffer = activeDecoder.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            madeProgress = true
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                activeDecoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputEos = true
                            } else {
                                val sampleTime = extractor.sampleTime
                                activeDecoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = activeDecoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        madeProgress = true
                        val outputFormat = activeDecoder.outputFormat
                        decodedSampleRate = if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } else decodedSampleRate
                        decodedChannels = if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else decodedChannels
                        decodedPcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else android.media.AudioFormat.ENCODING_PCM_16BIT
                        if (decodedPcmEncoding != android.media.AudioFormat.ENCODING_PCM_16BIT) {
                            throw IllegalStateException("Only 16-bit PCM decoder output is supported")
                        }
                        if (decodedSampleRate <= 0 || decodedChannels <= 0) {
                            throw IllegalStateException("Decoder returned invalid PCM metadata")
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        madeProgress = true
                    }
                    outIndex >= 0 -> {
                        madeProgress = true
                        val outputBuffer = activeDecoder.getOutputBuffer(outIndex)
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
                        activeDecoder.releaseOutputBuffer(outIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEos = true
                        }
                    }
                }
                if (madeProgress) {
                    idleIterationCount = 0
                } else {
                    idleIterationCount++
                    if (idleIterationCount > 6000) {
                        throw IllegalStateException("Audio decoder stalled before end of stream")
                    }
                }
            }

            // Merge PCM chunks
            val merged = ShortArray(totalSamples.toInt())
            var offset = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, merged, offset, chunk.size)
                offset += chunk.size
            }

            return DecodedPcm(
                samples = merged,
                sampleRate = decodedSampleRate,
                channels = decodedChannels,
                durationUs = durationUs
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            tempCachedFile?.delete()
        }
    }

    private fun selectAacEncoderSettings(
        sourceSampleRate: Int,
        sourceChannels: Int,
        requestedBitrateKbps: Int
    ): AacEncoderSettings? {
        if (sourceSampleRate <= 0 || sourceChannels <= 0) return null
        val mime = "audio/mp4a-latm"
        val codecs = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        } catch (_: Exception) {
            return null
        }
        val sampleRates = listOf(
            sourceSampleRate, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000
        ).distinct()
        val channelCounts = when {
            sourceChannels > 2 -> listOf(1)
            sourceChannels == 2 -> listOf(2, 1)
            else -> listOf(1)
        }

        for (sampleRate in sampleRates) {
            for (channels in channelCounts) {
                val policyBitrateKbps = AudioBitratePolicy.appliedKbps(
                    format = AudioFormat.M4A,
                    requestedKbps = requestedBitrateKbps,
                    sampleRate = sampleRate,
                    channels = channels
                ) ?: continue
                val requestedBps = policyBitrateKbps * 1000
                var bestSettings: AacEncoderSettings? = null
                var bestBitrateDistance = Long.MAX_VALUE

                // Check every AAC-LC encoder for this sample-rate/channel layout before
                // accepting a lower bitrate. Device codecs often advertise different ranges.
                for (codecInfo in codecs) {
                    val capabilities = try {
                        codecInfo.getCapabilitiesForType(mime)
                    } catch (_: Exception) {
                        continue
                    }
                    val profiles = capabilities.profileLevels
                    if (profiles.isNotEmpty() && profiles.none {
                            it.profile == MediaCodecInfo.CodecProfileLevel.AACObjectLC
                        }) continue
                    val audioCapabilities = capabilities.audioCapabilities ?: continue
                    if (!audioCapabilities.isSampleRateSupported(sampleRate) ||
                        channels > audioCapabilities.maxInputChannelCount) continue

                    val bitrateRange = audioCapabilities.bitrateRange
                    val bitrateBps = requestedBps.coerceIn(bitrateRange.lower, bitrateRange.upper)
                    if (bitrateBps <= 0) continue
                    val distance = kotlin.math.abs(bitrateBps.toLong() - requestedBps.toLong())
                    if (distance < bestBitrateDistance) {
                        val appliedKbps = ((bitrateBps + 500) / 1000).coerceAtLeast(1)
                        bestSettings = AacEncoderSettings(
                            codecName = codecInfo.name,
                            sampleRate = sampleRate,
                            channels = channels,
                            bitrateKbps = appliedKbps,
                            bitrateBps = bitrateBps
                        )
                        bestBitrateDistance = distance
                        if (distance == 0L) break
                    }
                }
                if (bestSettings != null) return bestSettings
            }
        }
        return null
    }

    private fun preparePcmForAac(
        pcmData: ShortArray,
        sourceSampleRate: Int,
        sourceChannels: Int,
        target: AacEncoderSettings
    ): ShortArray? {
        if (sourceSampleRate <= 0 || sourceChannels <= 0 || pcmData.isEmpty() ||
            pcmData.size % sourceChannels != 0) return null
        val channelAdjusted = when {
            sourceChannels == target.channels -> pcmData
            target.channels == 1 && sourceChannels > 1 -> downmixToMono(pcmData, sourceChannels)
            else -> return null
        }
        return PcmSampleRateConverter.resample(
            pcmData = channelAdjusted,
            sourceSampleRate = sourceSampleRate,
            targetSampleRate = target.sampleRate,
            channels = target.channels
        )
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
     * Encodes PCM with the pure-Java LAME implementation and publishes only after
     * a valid MPEG Layer III frame has been written to a staged file.
     */
    private fun encodePcmToMp3(
        pcmData: ShortArray,
        sampleRate: Int,
        channels: Int,
        outputFile: File,
        bitrateKbps: Int,
        onProgress: (Float) -> Unit
    ): Boolean {
        if (pcmData.isEmpty() || sampleRate <= 0 || channels <= 0 || pcmData.size % channels != 0) {
            return false
        }

        val outputChannels = if (channels <= 2) channels else 1
        val pcmToEncode = if (channels <= 2) pcmData else downmixToMono(pcmData, channels)
        val totalFrames = pcmToEncode.size / outputChannels
        if (totalFrames <= 0) return false

        val parent = outputFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val stagedFile = File(parent, ".${outputFile.name}.${UUID.randomUUID()}.partial")
        var encoder: Mp3PcmEncoder? = null
        var success = false

        try {
            val activeEncoder = Mp3PcmEncoder(sampleRate, outputChannels, bitrateKbps)
            encoder = activeEncoder
            val blockFrames = 4096
            val encodedBuffer = ByteArray(blockFrames + blockFrames / 4 + 7200)
            var frameOffset = 0

            FileOutputStream(stagedFile).use { output ->
                while (frameOffset < totalFrames) {
                    val frames = minOf(blockFrames, totalFrames - frameOffset)
                    val encodedBytes = activeEncoder.encodeInterleaved(
                        pcm = pcmToEncode,
                        frameOffset = frameOffset,
                        frameCount = frames,
                        output = encodedBuffer
                    )
                    if (encodedBytes < 0 || encodedBytes > encodedBuffer.size) {
                        throw IllegalStateException("LAME MP3 encoding failed (code $encodedBytes)")
                    }
                    if (encodedBytes > 0) output.write(encodedBuffer, 0, encodedBytes)
                    frameOffset += frames
                    onProgress((frameOffset.toFloat() / totalFrames).coerceIn(0f, 0.99f))
                }

                val flushBytes = activeEncoder.flush(encodedBuffer)
                if (flushBytes < 0 || flushBytes > encodedBuffer.size) {
                    throw IllegalStateException("LAME MP3 finalization failed (code $flushBytes)")
                }
                if (flushBytes > 0) output.write(encodedBuffer, 0, flushBytes)
                output.flush()
            }

            if (stagedFile.length() <= 64L || !containsMpegLayer3Frame(stagedFile)) {
                throw IllegalStateException("LAME did not produce a valid MP3 frame")
            }
            if (!stagedFile.renameTo(outputFile)) {
                throw IllegalStateException("Unable to publish the completed MP3 file")
            }
            success = outputFile.isFile && outputFile.length() > 64L && containsMpegLayer3Frame(outputFile)
            return success
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { encoder?.close() } catch (_: Exception) {}
            if (!success) stagedFile.delete()
            if (!success && outputFile.exists() && outputFile.length() == 0L) outputFile.delete()
        }
    }

    private fun containsMpegLayer3Frame(file: File): Boolean {
        return try {
            java.io.RandomAccessFile(file, "r").use { input ->
                var frameStart = 0L
                if (input.length() >= 10L) {
                    val id3Header = ByteArray(10)
                    input.readFully(id3Header)
                    if (id3Header[0] == 'I'.code.toByte() && id3Header[1] == 'D'.code.toByte() &&
                        id3Header[2] == '3'.code.toByte()) {
                        val tagSize = ((id3Header[6].toInt() and 0x7f) shl 21) or
                            ((id3Header[7].toInt() and 0x7f) shl 14) or
                            ((id3Header[8].toInt() and 0x7f) shl 7) or
                            (id3Header[9].toInt() and 0x7f)
                        val hasFooter = (id3Header[5].toInt() and 0x10) != 0
                        frameStart = 10L + tagSize + if (hasFooter) 10L else 0L
                    }
                }
                if (frameStart + 4L > input.length()) return false
                input.seek(frameStart)
                val header = ByteArray(minOf(4096L, input.length() - frameStart).toInt())
                val bytesRead = input.read(header)
                if (bytesRead < 4) return false
                for (i in 0 until bytesRead - 3) {
                    val b0 = header[i].toInt() and 0xff
                    val b1 = header[i + 1].toInt() and 0xff
                    val b2 = header[i + 2].toInt() and 0xff
                    val mpegVersion = (b1 shr 3) and 0x03
                    val layer = (b1 shr 1) and 0x03
                    val bitrateIndex = (b2 shr 4) and 0x0f
                    val sampleRateIndex = (b2 shr 2) and 0x03
                    if (b0 == 0xff && (b1 and 0xe0) == 0xe0 && mpegVersion != 1 &&
                        layer == 1 && bitrateIndex in 1..14 && sampleRateIndex != 3) {
                        return true
                    }
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Encodes AAC-LC and muxes the codec output into an actual MPEG-4/M4A container.
     * Unsupported output formats are rejected by [transcodeAudio] instead of being
     * written as AAC or WAV under a misleading extension.
     */
    private fun encodePcmToM4a(
        pcmData: ShortArray,
        settings: AacEncoderSettings,
        outputFile: File,
        onOutputFormat: (AacOutputFormat) -> Unit,
        onProgress: (Float) -> Unit
    ): Boolean {
        val sampleRate = settings.sampleRate
        val outputChannels = settings.channels
        val pcmToEncode = pcmData
        val bitrateBps = settings.bitrateBps
        if (pcmToEncode.isEmpty() || sampleRate <= 0 || outputChannels <= 0 ||
            pcmToEncode.size % outputChannels != 0) return false
        val parent = outputFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false

        val stagedFile = File(parent, ".${outputFile.name}.${UUID.randomUUID()}.partial")
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var success = false

        try {
            val mime = "audio/mp4a-latm"
            val format = MediaFormat.createAudioFormat(mime, sampleRate, outputChannels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            }

            encoder = MediaCodec.createByCodecName(settings.codecName)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(stagedFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var muxerTrack = -1
            var pcmOffset = 0
            var inputEos = false
            var outputEos = false
            var idleOutputCount = 0
            val timeoutUs = 5000L
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputEos) {
                if (!inputEos) {
                    val inputIndex = encoder.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("AAC encoder returned a null input buffer")
                        inputBuffer.clear()

                        val remainingSamples = pcmToEncode.size - pcmOffset
                        if (remainingSamples == 0) {
                            encoder.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            val capacitySamples = inputBuffer.capacity() / 2
                            var samplesToWrite = minOf(remainingSamples, capacitySamples)
                            samplesToWrite -= samplesToWrite % outputChannels
                            if (samplesToWrite <= 0) {
                                encoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                                return false
                            }

                            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                                .put(pcmToEncode, pcmOffset, samplesToWrite)
                            val bytesToWrite = samplesToWrite * 2
                            val framesSubmitted = pcmOffset.toLong() / outputChannels
                            val presentationTimeUs = framesSubmitted * 1_000_000L / sampleRate
                            encoder.queueInputBuffer(
                                inputIndex, 0, bytesToWrite, presentationTimeUs, 0
                            )
                            pcmOffset += samplesToWrite
                            onProgress((pcmOffset.toFloat() / pcmToEncode.size).coerceIn(0f, 1f))
                        }
                    }
                }

                when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerTrack >= 0) {
                            throw IllegalStateException("AAC encoder changed output format more than once")
                        }
                        val negotiatedFormat = encoder.outputFormat
                        muxerTrack = muxer.addTrack(negotiatedFormat)
                        muxer.start()
                        muxerStarted = true
                        val actualSampleRate = if (negotiatedFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            negotiatedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } else settings.sampleRate
                        val actualChannels = if (negotiatedFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            negotiatedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else settings.channels
                        if (actualSampleRate != settings.sampleRate || actualChannels != settings.channels) {
                            throw IllegalStateException("AAC encoder negotiated a different sample rate or channel count")
                        }
                        val actualBitrateKbps = if (negotiatedFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            ((negotiatedFormat.getInteger(MediaFormat.KEY_BIT_RATE) + 500) / 1000).coerceAtLeast(1)
                        } else settings.bitrateKbps
                        onOutputFormat(AacOutputFormat(actualSampleRate, actualChannels, actualBitrateKbps))
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputEos) {
                            idleOutputCount++
                            // Keep waiting up to roughly 30 seconds for slow hardware encoders to flush EOS.
                            if (idleOutputCount > 6000) {
                                throw IllegalStateException("AAC encoder did not signal end of stream")
                            }
                        }
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        idleOutputCount = 0
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (!muxerStarted || muxerTrack < 0) {
                                throw IllegalStateException("AAC output arrived before the M4A muxer was ready")
                            }
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerTrack, outputBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true
                        }
                    }
                }
            }

            if (!muxerStarted || muxerTrack < 0) {
                throw IllegalStateException("AAC encoder produced no M4A track")
            }
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            if (!stagedFile.isFile || stagedFile.length() <= 0L) {
                throw IllegalStateException("M4A output is empty")
            }

            // Same-directory rename keeps the finished container intact and avoids exposing a partial file.
            android.system.Os.rename(stagedFile.absolutePath, outputFile.absolutePath)
            success = outputFile.isFile && outputFile.length() > 0L
            return success
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            if (!success) stagedFile.delete()
            if (!success && outputFile.exists() && outputFile.length() == 0L) outputFile.delete()
        }
    }

}
