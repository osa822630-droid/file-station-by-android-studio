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
import java.io.FileInputStream
import java.io.FileOutputStream
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
            if (!encoded) return@withContext false
            if (!isValidEncodedAudio(stagedOutput, targetFormat)) {
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
        if (targetFormat != AudioFormat.WAV && targetFormat != AudioFormat.M4A &&
            targetFormat != AudioFormat.MP3) return@withContext false
        if (!outputFile.extension.equals(targetFormat.extension, ignoreCase = true)) return@withContext false

        val workingFiles = mutableListOf<File>()
        try {
            onProgress(0.05f, "قراءة وفك تشفير الصوت إلى ملف مؤقت محدود الذاكرة...")
            val decoded = decodeToPcmFile(sourceUri, sourceFile) { decodeProgress ->
                onProgress(0.05f + decodeProgress * 0.35f, "فك تشفير الصوت (${(decodeProgress * 100).toInt()}%)")
            } ?: return@withContext false
            workingFiles += decoded.file

            val originalSampleRate = decoded.sampleRate
            val originalChannels = decoded.channels
            var pcmFile = decoded.file
            var sampleRate = decoded.sampleRate
            var channels = decoded.channels

            if (kotlin.math.abs(targetSpeed - 1.0f) > 0.01f) {
                val maxInputBytes = PcmFileUtils.maxWsolaInputBytes()
                if (pcmFile.length() > maxInputBytes) {
                    throw PcmResourceLimitException(
                        "تغيير السرعة متاح لهذا الملف حتى ${maxInputBytes / (1024 * 1024)} MiB من PCM؛ " +
                            "اختر سرعة 1x للمعالجة المتدفقة أو ملفًا أقصر."
                    )
                }
                onProgress(0.40f, "تعديل السرعة إلى ${targetSpeed}x مع الحفاظ على النبرة...")
                val samples = PcmFileUtils.readSamples(pcmFile, maxInputBytes)
                    ?: throw PcmResourceLimitException("تعذر تحميل كتلة الصوت ضمن حد الذاكرة الآمن.")
                val maxOutputSamples = (PcmFileUtils.maxWsolaOutputBytes() / 2L)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val stretched = WsolaTimeStretcher.process(
                    input = samples,
                    sampleRate = sampleRate,
                    channels = channels,
                    speed = targetSpeed,
                    maxOutputSamples = maxOutputSamples
                ) { stretchProgress ->
                    onProgress(0.40f + stretchProgress * 0.35f,
                        "معالجة سرعة الصوت (${(stretchProgress * 100).toInt()}%)")
                }
                val stretchedFile = PcmFileUtils.newRawFile(context.cacheDir, "stretched_audio")
                workingFiles += stretchedFile
                PcmFileUtils.writeSamples(stretchedFile, stretched)
                pcmFile = stretchedFile
            }

            val aacSettings = if (targetFormat == AudioFormat.M4A) {
                selectAacEncoderSettings(sampleRate, channels, bitrateKbps)
                    ?: return@withContext false
            } else null
            if (aacSettings != null) {
                val convertedFile = PcmFileUtils.newRawFile(context.cacheDir, "aac_input")
                val converted = PcmSampleRateConverter.convertRawFile(
                    inputFile = pcmFile,
                    outputFile = convertedFile,
                    sourceSampleRate = sampleRate,
                    sourceChannels = channels,
                    targetSampleRate = aacSettings.sampleRate,
                    targetChannels = aacSettings.channels
                ) ?: return@withContext false
                workingFiles += convertedFile
                pcmFile = converted.file
                sampleRate = converted.sampleRate
                channels = converted.channels
            }

            val appliedBitrateKbps = aacSettings?.bitrateKbps ?: AudioBitratePolicy.appliedKbps(
                format = targetFormat,
                requestedKbps = bitrateKbps,
                sampleRate = sampleRate,
                channels = channels
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
                AudioFormat.WAV -> WavHelper.writeWavFileFromPcmFile(
                    outputFile = outputFile,
                    pcmFile = pcmFile,
                    sampleRate = sampleRate,
                    channels = channels
                )
                AudioFormat.MP3 -> encodePcmFileToMp3(
                    pcmFile = pcmFile,
                    sampleRate = sampleRate,
                    channels = channels,
                    outputFile = outputFile,
                    bitrateKbps = appliedBitrateKbps
                ) { encodeProgress ->
                    onProgress(0.80f + encodeProgress * 0.20f,
                        "ترميز MP3 (${(encodeProgress * 100).toInt()}%)")
                }
                AudioFormat.M4A -> encodePcmFileToM4a(
                    pcmFile = pcmFile,
                    settings = aacSettings ?: return@withContext false,
                    outputFile = outputFile,
                    onOutputFormat = { actualAacOutput = it }
                ) { encodeProgress ->
                    val visibleProgress = encodeProgress.coerceAtMost(0.99f)
                    onProgress(0.80f + visibleProgress * 0.20f,
                        "ترميز AAC داخل M4A (${(visibleProgress * 100).toInt()}%)")
                }
                else -> false
            }

            if (success) {
                val actualSampleRate = actualAacOutput?.sampleRate ?: aacSettings?.sampleRate ?: sampleRate
                val actualChannels = actualAacOutput?.channels ?: aacSettings?.channels ?:
                    if (targetFormat == AudioFormat.MP3 && channels > 2) 1 else channels
                val actualBitrate = actualAacOutput?.bitrateKbps ?: appliedBitrateKbps
                onOutputInfo(
                    AudioOutputInfo(
                        format = targetFormat,
                        requestedBitrateKbps = bitrateKbps,
                        appliedBitrateKbps = actualBitrate,
                        sampleRate = actualSampleRate,
                        channels = actualChannels,
                        sourceSampleRate = originalSampleRate,
                        sourceChannels = originalChannels
                    )
                )
            }
            success
        } catch (e: PcmResourceLimitException) {
            val message = "توقف التحويل بأمان: ${e.message ?: "تجاوز الملف الحد الآمن للموارد."}"
            onProgress(0.99f, message)
            android.util.Log.w("AudioTranscoder", message)
            false
        } catch (e: Exception) {
            android.util.Log.e("AudioTranscoder", "Audio transcode failed before publish", e)
            onProgress(0.99f, "فشل تحويل الصوت؛ لم يُنشر ملف جزئي.")
            false
        } finally {
            workingFiles.forEach { it.delete() }
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

    private fun decodeToPcmFile(
        sourceUri: Uri?,
        sourceFile: File?,
        onProgress: (Float) -> Unit
    ): PcmFileMetadata? {
        var tempCachedFile: File? = null
        var pcmOutputFile: File? = null
        var success = false
        try {
            val fileToProcess = when {
                sourceFile != null && sourceFile.isFile -> sourceFile
                sourceUri != null -> {
                    val cacheFile = PcmFileUtils.newRawFile(context.cacheDir, "input_transcode")
                    tempCachedFile = cacheFile
                    val input = context.contentResolver.openInputStream(sourceUri)
                        ?: throw IllegalStateException("تعذر فتح الملف المحدد للقراءة.")
                    input.use { source ->
                        FileOutputStream(cacheFile).buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                copied += read
                                if (copied > PcmFileUtils.MAX_SOURCE_FILE_BYTES) {
                                    throw PcmResourceLimitException("حجم الملف الأصلي يتجاوز حد الإدخال الآمن (1 GiB).")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    cacheFile
                }
                else -> null
            } ?: return null

            if (!fileToProcess.isFile || fileToProcess.length() < 32L) return null
            if (fileToProcess.length() > PcmFileUtils.MAX_SOURCE_FILE_BYTES) {
                throw PcmResourceLimitException("حجم الملف الأصلي يتجاوز حد الإدخال الآمن (1 GiB).")
            }

            val rawFile = PcmFileUtils.newRawFile(context.cacheDir, "decoded_audio")
            pcmOutputFile = rawFile
            val wavMetadata = WavHelper.copyWavPcmToRawFile(
                inputFile = fileToProcess,
                rawOutputFile = rawFile,
                maxPcmBytes = PcmFileUtils.MAX_DECODED_PCM_BYTES
            )
            if (wavMetadata != null) {
                if (wavMetadata.durationUs > PcmFileUtils.MAX_DURATION_US) {
                    throw PcmResourceLimitException("مدة الملف تتجاوز الحد الأقصى للتحويل (120 دقيقة).")
                }
                onProgress(1f)
                success = true
                return wavMetadata
            }
            rawFile.delete()

            // Reject obvious text responses before invoking platform media codecs.
            val headerBytes = ByteArray(64)
            FileInputStream(fileToProcess).use { it.read(headerBytes) }
            val headerString = String(headerBytes, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase()
            if (headerString.startsWith("<!doc") || headerString.startsWith("<html") ||
                headerString.startsWith("{\"error") || headerString.startsWith("<?xml")) {
                return null
            }

            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                extractor.setDataSource(fileToProcess.absolutePath)
                var audioTrackIndex = -1
                var trackFormat: MediaFormat? = null
                var mime: String? = null
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val type = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (type.startsWith("audio/")) {
                        audioTrackIndex = index
                        trackFormat = format
                        mime = type
                        break
                    }
                }
                if (audioTrackIndex < 0 || trackFormat == null || mime == null) return null
                extractor.selectTrack(audioTrackIndex)

                val declaredDurationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    trackFormat.getLong(MediaFormat.KEY_DURATION)
                } else 0L
                if (declaredDurationUs > PcmFileUtils.MAX_DURATION_US) {
                    throw PcmResourceLimitException("مدة الملف تتجاوز الحد الأقصى للتحويل (120 دقيقة).")
                }
                var sampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else 44_100
                var channels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else 2
                if (sampleRate <= 0 || channels <= 0 || channels > 32) {
                    throw IllegalStateException("Decoder returned invalid PCM metadata")
                }

                val activeDecoder = MediaCodec.createDecoderByType(mime)
                decoder = activeDecoder
                activeDecoder.configure(trackFormat, null, null, 0)
                activeDecoder.start()
                val bufferInfo = MediaCodec.BufferInfo()
                var inputEos = false
                var outputEos = false
                var decodedBytes = 0L
                var idleIterations = 0
                val timeoutUs = 5_000L
                val copyBuffer = ByteArray(64 * 1024)

                java.io.BufferedOutputStream(FileOutputStream(rawFile), 64 * 1024).use { pcmOutput ->
                    while (!outputEos) {
                        var madeProgress = false
                        if (!inputEos) {
                            val inputIndex = activeDecoder.dequeueInputBuffer(timeoutUs)
                            if (inputIndex >= 0) {
                                val inputBuffer = activeDecoder.getInputBuffer(inputIndex)
                                if (inputBuffer != null) {
                                    madeProgress = true
                                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                    if (sampleSize < 0) {
                                        activeDecoder.queueInputBuffer(
                                            inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        inputEos = true
                                    } else {
                                        activeDecoder.queueInputBuffer(
                                            inputIndex, 0, sampleSize, extractor.sampleTime, 0
                                        )
                                        extractor.advance()
                                    }
                                }
                            }
                        }

                        val outputIndex = activeDecoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                        when {
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                madeProgress = true
                                val outputFormat = activeDecoder.outputFormat
                                sampleRate = if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                    outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                } else sampleRate
                                channels = if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                } else channels
                                val pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                    outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                } else android.media.AudioFormat.ENCODING_PCM_16BIT
                                if (pcmEncoding != android.media.AudioFormat.ENCODING_PCM_16BIT) {
                                    throw IllegalStateException("Only 16-bit PCM decoder output is supported")
                                }
                                if (sampleRate <= 0 || channels <= 0 || channels > 32) {
                                    throw IllegalStateException("Decoder returned invalid PCM metadata")
                                }
                            }
                            outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> madeProgress = true
                            outputIndex >= 0 -> {
                                madeProgress = true
                                val outputBuffer = activeDecoder.getOutputBuffer(outputIndex)
                                if (outputBuffer != null && bufferInfo.size > 0) {
                                    if (decodedBytes + bufferInfo.size > PcmFileUtils.MAX_DECODED_PCM_BYTES) {
                                        activeDecoder.releaseOutputBuffer(outputIndex, false)
                                        throw PcmResourceLimitException(
                                            "الصوت بعد فك الضغط يتجاوز الحد الآمن للبيانات المؤقتة (512 MiB)."
                                        )
                                    }
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    while (outputBuffer.hasRemaining()) {
                                        val count = minOf(copyBuffer.size, outputBuffer.remaining())
                                        outputBuffer.get(copyBuffer, 0, count)
                                        pcmOutput.write(copyBuffer, 0, count)
                                    }
                                    decodedBytes += bufferInfo.size
                                    if (declaredDurationUs > 0L) {
                                        val progress = (bufferInfo.presentationTimeUs.toFloat() / declaredDurationUs)
                                            .coerceIn(0f, 1f)
                                        onProgress(progress)
                                    }
                                }
                                activeDecoder.releaseOutputBuffer(outputIndex, false)
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputEos = true
                                }
                            }
                        }
                        if (madeProgress) idleIterations = 0 else {
                            idleIterations++
                            if (idleIterations > 6_000) {
                                throw IllegalStateException("Audio decoder stalled before end of stream")
                            }
                        }
                    }
                }

                val frameBytes = channels.toLong() * 2L
                if (decodedBytes <= 0L || decodedBytes % frameBytes != 0L) return null
                val actualDurationUs =
                    (decodedBytes * 1_000_000L) / (sampleRate.toLong() * frameBytes)
                if (declaredDurationUs > PcmFileUtils.MAX_DURATION_US ||
                    actualDurationUs > PcmFileUtils.MAX_DURATION_US) {
                    throw PcmResourceLimitException("مدة الملف تتجاوز الحد الأقصى للتحويل (120 دقيقة).")
                }
                val durationUs = if (declaredDurationUs > 0L) declaredDurationUs else actualDurationUs
                onProgress(1f)
                success = true
                return PcmFileMetadata(rawFile, sampleRate, channels, durationUs)
            } finally {
                try { decoder?.stop() } catch (_: Exception) {}
                try { decoder?.release() } catch (_: Exception) {}
                try { extractor.release() } catch (_: Exception) {}
            }
        } catch (e: PcmResourceLimitException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AudioTranscoder", "Unable to decode audio to PCM file", e)
            return null
        } finally {
            tempCachedFile?.delete()
            if (!success) pcmOutputFile?.delete()
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

    /**
     * Encodes PCM from a bounded file in fixed-size frames and publishes only after
     * a valid MPEG Layer III frame has been written to a staged file.
     */
    private fun encodePcmFileToMp3(
        pcmFile: File,
        sampleRate: Int,
        channels: Int,
        outputFile: File,
        bitrateKbps: Int,
        onProgress: (Float) -> Unit
    ): Boolean {
        val frameBytes = channels.toLong() * 2L
        if (!pcmFile.isFile || pcmFile.length() <= 0L || sampleRate <= 0 || channels <= 0 ||
            pcmFile.length() % frameBytes != 0L) return false
        val outputChannels = if (channels <= 2) channels else 1
        val totalFrames = pcmFile.length() / frameBytes
        if (totalFrames <= 0L) return false

        val parent = outputFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val stagedFile = File(parent, ".${outputFile.name}.${UUID.randomUUID()}.partial")
        var encoder: Mp3PcmEncoder? = null
        var success = false

        try {
            val activeEncoder = Mp3PcmEncoder(sampleRate, outputChannels, bitrateKbps)
            encoder = activeEncoder
            val blockFrames = 4_096
            val sourceBuffer = ByteArray(blockFrames * channels * 2)
            val pcmBlock = ShortArray(blockFrames * outputChannels)
            val encodedBuffer = ByteArray(blockFrames + blockFrames / 4 + 7_200)
            var framesEncoded = 0L

            java.io.BufferedInputStream(FileInputStream(pcmFile), 64 * 1024).use { input ->
                FileOutputStream(stagedFile).use { output ->
                    while (framesEncoded < totalFrames) {
                        val frames = minOf(blockFrames.toLong(), totalFrames - framesEncoded).toInt()
                        val bytesToRead = frames * channels * 2
                        var readCount = 0
                        while (readCount < bytesToRead) {
                            val read = input.read(sourceBuffer, readCount, bytesToRead - readCount)
                            if (read < 0) throw IllegalStateException("Truncated PCM file during MP3 encoding")
                            if (read == 0) continue
                            readCount += read
                        }
                        for (frame in 0 until frames) {
                            if (channels <= 2) {
                                for (channel in 0 until channels) {
                                    val offset = (frame * channels + channel) * 2
                                    val low = sourceBuffer[offset].toInt() and 0xff
                                    val high = sourceBuffer[offset + 1].toInt()
                                    pcmBlock[frame * outputChannels + channel] = (low or (high shl 8)).toShort()
                                }
                            } else {
                                var sum = 0
                                for (channel in 0 until channels) {
                                    val offset = (frame * channels + channel) * 2
                                    val low = sourceBuffer[offset].toInt() and 0xff
                                    val high = sourceBuffer[offset + 1].toInt()
                                    sum += (low or (high shl 8)).toShort().toInt()
                                }
                                pcmBlock[frame] = (sum / channels)
                                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                        }

                        val encodedBytes = activeEncoder.encodeInterleaved(
                            pcm = pcmBlock,
                            frameOffset = 0,
                            frameCount = frames,
                            output = encodedBuffer
                        )
                        if (encodedBytes < 0 || encodedBytes > encodedBuffer.size) {
                            throw IllegalStateException("LAME MP3 encoding failed (code $encodedBytes)")
                        }
                        if (encodedBytes > 0) output.write(encodedBuffer, 0, encodedBytes)
                        framesEncoded += frames
                        onProgress((framesEncoded.toFloat() / totalFrames).coerceIn(0f, 0.99f))
                    }

                    val flushBytes = activeEncoder.flush(encodedBuffer)
                    if (flushBytes < 0 || flushBytes > encodedBuffer.size) {
                        throw IllegalStateException("LAME MP3 finalization failed (code $flushBytes)")
                    }
                    if (flushBytes > 0) output.write(encodedBuffer, 0, flushBytes)
                    output.flush()
                }
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
            android.util.Log.e("AudioTranscoder", "MP3 file-stream encoding failed", e)
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
    private fun encodePcmFileToM4a(
        pcmFile: File,
        settings: AacEncoderSettings,
        outputFile: File,
        onOutputFormat: (AacOutputFormat) -> Unit,
        onProgress: (Float) -> Unit
    ): Boolean {
        val sampleRate = settings.sampleRate
        val outputChannels = settings.channels
        val frameBytes = outputChannels * 2
        if (!pcmFile.isFile || pcmFile.length() <= 0L || sampleRate <= 0 || outputChannels <= 0 ||
            pcmFile.length() % frameBytes != 0L) return false
        val totalFrames = pcmFile.length() / frameBytes
        if (totalFrames <= 0L) return false
        val bitrateBps = settings.bitrateBps
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
            var framesSubmitted = 0L
            var inputEos = false
            var outputEos = false
            var idleOutputCount = 0
            val timeoutUs = 5_000L
            val bufferInfo = MediaCodec.BufferInfo()
            val pcmChunk = ByteArray(128 * 1024)

            java.io.BufferedInputStream(FileInputStream(pcmFile), 64 * 1024).use { pcmInput ->
                while (!outputEos) {
                    if (!inputEos) {
                        val inputIndex = encoder.dequeueInputBuffer(timeoutUs)
                        if (inputIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inputIndex)
                                ?: throw IllegalStateException("AAC encoder returned a null input buffer")
                            inputBuffer.clear()

                            val remainingFrames = totalFrames - framesSubmitted
                            if (remainingFrames == 0L) {
                                // API 29 AAC encoders may emit a final packet using the EOS timestamp.
                                val endOfStreamTimeUs = framesSubmitted * 1_000_000L / sampleRate
                                encoder.queueInputBuffer(
                                    inputIndex, 0, 0, endOfStreamTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                val capacityFrames = inputBuffer.capacity() / frameBytes
                                val framesToWrite = minOf(
                                    remainingFrames,
                                    capacityFrames.toLong(),
                                    (pcmChunk.size / frameBytes).toLong()
                                ).toInt()
                                if (framesToWrite <= 0) {
                                    val endOfStreamTimeUs = framesSubmitted * 1_000_000L / sampleRate
                                    encoder.queueInputBuffer(
                                        inputIndex, 0, 0, endOfStreamTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEos = true
                                    return false
                                }
                                val bytesToWrite = framesToWrite * frameBytes
                                var readCount = 0
                                while (readCount < bytesToWrite) {
                                    val read = pcmInput.read(pcmChunk, readCount, bytesToWrite - readCount)
                                    if (read < 0) throw IllegalStateException("Truncated PCM file during AAC encoding")
                                    if (read == 0) continue
                                    readCount += read
                                }
                                inputBuffer.put(pcmChunk, 0, bytesToWrite)
                                val presentationTimeUs = framesSubmitted * 1_000_000L / sampleRate
                                encoder.queueInputBuffer(inputIndex, 0, bytesToWrite, presentationTimeUs, 0)
                                framesSubmitted += framesToWrite
                                onProgress((framesSubmitted.toFloat() / totalFrames).coerceIn(0f, 1f))
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
                                if (idleOutputCount > 6_000) {
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
            android.system.Os.rename(stagedFile.absolutePath, outputFile.absolutePath)
            success = outputFile.isFile && outputFile.length() > 0L
            return success
        } catch (e: Exception) {
            android.util.Log.e("AudioTranscoder", "AAC file-stream encoding failed", e)
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
