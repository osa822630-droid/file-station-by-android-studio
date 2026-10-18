package com.example.audio

import android.content.Context
import android.net.Uri
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.braymon.kotmpeg.MkvKotlin
import com.braymon.kotmpeg.model.TrackInfo
import androidx.media3.common.C
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.model.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

@androidx.annotation.OptIn(markerClass = [UnstableApi::class, ExperimentalApi::class])
class VideoTranscoder(private val context: Context) {

    data class VideoInfo(
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val bitRate: Int,
        val frameRate: Int
    )

    /**
     * Inspects video metadata (dimensions, bitrate, duration)
     */
    fun inspectVideo(file: File): VideoInfo? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    val width = format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                    val bitRate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else 0
                    val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE) else 0
                    return VideoInfo(width, height, durationUs, bitRate, frameRate)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
        return inspectVideoWithKotmpeg(file)
    }

    private fun inspectVideoWithKotmpeg(file: File): VideoInfo? {
        if (Build.VERSION.SDK_INT < 26) return null
        return try {
            MkvKotlin.openDemuxer(file).use { demuxer ->
                val track = demuxer.tracks.filterIsInstance<TrackInfo.Video>().firstOrNull()
                    ?: return@use null
                VideoInfo(
                    width = track.width,
                    height = track.height,
                    durationUs = demuxer.durationUs,
                    bitRate = 0,
                    frameRate = track.frameRate.toInt().takeIf { it > 0 } ?: 0
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes and re-encodes video with the selected maximum resolution and playback speed.
     * Media3 uses Android's available decoders/encoders to create an MP4; MKV output is then
     * muxed from the newly encoded H.264/AAC-compatible tracks. Resolution presets never upscale.
     */
    suspend fun transcodeVideo(
        sourceFile: File,
        outputFile: File,
        targetQuality: VideoQuality,
        targetSpeed: Float = 1.0f,
        onProgress: (Float, String) -> Unit
    ): VideoInfo? = withContext(Dispatchers.IO) {
        if (!sourceFile.isFile || sourceFile.length() <= 0L ||
            !VideoQualityPolicy.isSupportedSpeed(targetSpeed)) {
            onProgress(0f, "ملف المصدر أو سرعة الفيديو غير صالحة.")
            return@withContext null
        }
        val extension = outputFile.extension.lowercase()
        if (extension !in setOf("mp4", "mkv")) {
            onProgress(0f, "إعادة الترميز متاحة حاليًا لإخراج MP4 أو MKV فقط.")
            return@withContext null
        }
        if (extension == "mkv" && Build.VERSION.SDK_INT < 26) {
            onProgress(0f, "إخراج MKV يتطلب Android 8.0 (API 26) أو أحدث.")
            return@withContext null
        }
        val sourceInfo = inspectVideo(sourceFile)
        if (sourceInfo == null || sourceInfo.width <= 0 || sourceInfo.height <= 0) {
            onProgress(0f, "تعذر قراءة مسار الفيديو أو أبعاده؛ لم يُنشأ ملف إخراج.")
            return@withContext null
        }
        val outputParent = outputFile.parentFile ?: return@withContext null
        if (!outputParent.exists() && !outputParent.mkdirs()) return@withContext null

        val id = UUID.randomUUID().toString()
        val stagedMp4 = File(outputParent, ".${outputFile.nameWithoutExtension}.$id.transform.mp4")
        val stagedMkv = if (extension == "mkv") {
            File(outputParent, ".${outputFile.nameWithoutExtension}.$id.pending.mkv")
        } else stagedMp4
        try {
            val effectiveShortSide = VideoQualityPolicy.effectiveShortSide(
                sourceInfo.width,
                sourceInfo.height,
                targetQuality
            )
            onProgress(0.03f, "إعداد إعادة الترميز إلى حد أقصى ${effectiveShortSide}p وبسرعة ${targetSpeed}x...")
            val exported = exportMp4WithMedia3(
                sourceFile = sourceFile,
                destination = stagedMp4,
                maxShortSide = effectiveShortSide,
                frameRate = sourceInfo.frameRate,
                targetSpeed = targetSpeed,
                onProgress = onProgress
            )
            if (!exported || !isValidMuxedOutput(stagedMp4, "mp4")) {
                onProgress(0.84f, "تعذر على مشفر الجهاز إكمال إعادة ترميز الفيديو؛ لم يُنشر ملف جزئي.")
                return@withContext null
            }

            if (extension == "mkv") {
                onProgress(0.86f, "اكتملت إعادة الترميز؛ تجميع مسارات الفيديو والصوت داخل MKV...")
                if (!remuxAsMatroska(stagedMp4, stagedMkv) { progress, stage ->
                        onProgress(0.86f + progress * 0.12f, stage)
                    }) {
                    return@withContext null
                }
            }

            val candidate = if (extension == "mkv") stagedMkv else stagedMp4
            val outputInfo = inspectVideo(candidate)
            if (outputInfo == null || outputInfo.width <= 0 || outputInfo.height <= 0) {
                throw IllegalStateException("The encoded output has no readable video track")
            }
            if (!publishCompletedFile(candidate, outputFile)) {
                throw IllegalStateException("Unable to publish completed video output")
            }
            onProgress(
                1f,
                "اكتملت إعادة الترميز فعليًا: ${outputInfo.width}×${outputInfo.height}، " +
                    "${targetSpeed}x، حاوية ${extension.uppercase()}."
            )
            outputInfo
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("VideoTranscoder", "Video transcode failed", e)
            onProgress(0.9f, "فشلت إعادة ترميز الفيديو: ${e.message ?: "تعذر استخدام مشفر الجهاز"}.")
            null
        } finally {
            stagedMp4.delete()
            if (stagedMkv != stagedMp4) stagedMkv.delete()
        }
    }

    /** Compatibility entry point; now performs an actual scale/re-encode instead of remuxing. */
    suspend fun downscaleVideo(
        sourceFile: File,
        outputFile: File,
        targetQuality: VideoQuality,
        onProgress: (Float, String) -> Unit
    ): Boolean = transcodeVideo(sourceFile, outputFile, targetQuality, 1.0f, onProgress) != null

    /** Repackages compressed tracks unchanged; use transcodeVideo when quality or speed must change. */
    suspend fun remuxVideo(
        sourceFile: File,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Boolean = remuxVideoInternal(sourceFile, outputFile, onProgress)

    private suspend fun exportMp4WithMedia3(
        sourceFile: File,
        destination: File,
        maxShortSide: Int,
        frameRate: Int,
        targetSpeed: Float,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        val completion = CompletableDeferred<Boolean>()
        var transformer: Transformer? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                val source = Media3Item.Builder().setUri(Uri.fromFile(sourceFile)).build()
                val editedBuilder = EditedMediaItem.Builder(source)
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(maxShortSide))))
                if (frameRate > 0) editedBuilder.setFrameRate(frameRate)
                if (targetSpeed != 1.0f) {
                    val provider = object : SpeedProvider {
                        override fun getSpeed(presentationTimeUs: Long): Float = targetSpeed
                        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
                    }
                    editedBuilder.setSpeed(SpeedParameters(provider, true))
                }
                val listener = object : Transformer.Listener {
                    override fun onCompleted(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: androidx.media3.transformer.ExportResult
                    ) {
                        completion.complete(true)
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: androidx.media3.transformer.ExportResult,
                        exportException: ExportException
                    ) {
                        android.util.Log.e("VideoTranscoder", "Media3 export failed", exportException)
                        onProgress(0.12f, "تعذر فك ترميز الفيديو أو ترميزه على هذا الجهاز.")
                        completion.complete(false)
                    }
                }
                transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(listener)
                    .build()
                transformer!!.start(editedBuilder.build(), destination.absolutePath)
            }

            val progressHolder = ProgressHolder()
            while (!completion.isCompleted) {
                val percent = withContext(Dispatchers.Main.immediate) {
                    val activeTransformer = transformer ?: return@withContext null
                    if (activeTransformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        progressHolder.progress
                    } else null
                }
                if (percent != null) {
                    onProgress(0.1f + (percent.coerceIn(0, 100) / 100f) * 0.72f, "إعادة ترميز الفيديو والصوت... $percent%")
                } else {
                    onProgress(0.1f, "فك وترميز مسارات الفيديو باستخدام مشفرات Android...")
                }
                delay(350)
            }
            return completion.await()
        } finally {
            if (!completion.isCompleted && transformer != null) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    transformer?.cancel()
                }
            }
        }
    }

    private fun publishCompletedFile(stagedFile: File, destination: File): Boolean {
        if (!stagedFile.isFile || stagedFile.length() <= 128L) return false
        return try {
            android.system.Os.rename(stagedFile.absolutePath, destination.absolutePath)
            destination.isFile && destination.length() > 128L
        } catch (e: Exception) {
            android.util.Log.e("VideoTranscoder", "Unable to publish encoded output", e)
            false
        }
    }

    private suspend fun remuxVideoInternal(
        sourceFile: File,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        when (outputFile.extension.lowercase()) {
            "mkv" -> return@withContext remuxAsMatroska(sourceFile, outputFile, onProgress)
            "mp4", "webm" -> Unit
            else -> return@withContext false
        }

        val outputParent = outputFile.parentFile ?: return@withContext false
        if (!outputParent.exists() && !outputParent.mkdirs()) return@withContext false
        val stagedOutput = File(
            outputParent,
            ".${outputFile.nameWithoutExtension}.${UUID.randomUUID()}.${outputFile.extension.lowercase()}"
        )
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var published = false
        try {
            onProgress(0.05f, "فحص مسارات الفيديو والصوت...")
            extractor.setDataSource(sourceFile.absolutePath)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                    audioFormat = format
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                throw IllegalArgumentException("The source has no recognized video track")
            }

            val srcHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            onProgress(
                0.15f,
                "الفيديو ${srcHeight}p؛ إعادة تعبئة المسارات المضغوطة دون تغيير الدقة أو السرعة..."
            )

            val muxerOutputFormat = when (outputFile.extension.lowercase()) {
                "mp4" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                "webm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else -> return@withContext false
            }
            muxer = MediaMuxer(stagedOutput.absolutePath, muxerOutputFormat)
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1

            muxerVideoTrack = muxer.addTrack(videoFormat)
            if (audioTrackIndex != -1 && audioFormat != null) {
                muxerAudioTrack = muxer.addTrack(audioFormat)
            }

            muxer.start()

            // Copy compressed samples unchanged; this path only remuxes the container.
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            // Transcode/copy video track
            extractor.selectTrack(videoTrackIndex)
            var sampleCount = 0
            while (true) {
                buffer.clear()
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = mapSampleFlags(extractor.sampleFlags)
                    ?: throw IllegalArgumentException("Encrypted video samples cannot be remuxed without decryption")
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                extractor.advance()
                sampleCount++
                if (sampleCount % 60 == 0) {
                    onProgress(0.2f + (sampleCount % 500) * 0.001f, "إعادة تعبئة عينات الفيديو المضغوطة...")
                }
            }
            extractor.unselectTrack(videoTrackIndex)

            // Copy audio track
            if (audioTrackIndex != -1) {
                extractor.selectTrack(audioTrackIndex)
                while (true) {
                    buffer.clear()
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = mapSampleFlags(extractor.sampleFlags)
                        ?: throw IllegalArgumentException("Encrypted audio samples cannot be remuxed without decryption")
                    muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                    extractor.advance()
                }
                extractor.unselectTrack(audioTrackIndex)
            }

            muxer.stop()
            muxer.release()
            muxer = null
            if (!isValidMuxedOutput(stagedOutput, outputFile.extension)) {
                throw IllegalStateException("Muxer output did not match the requested container")
            }
            if (!publishCompletedFile(stagedOutput, outputFile)) {
                throw IllegalStateException("Unable to publish completed video output")
            }
            published = true
            onProgress(1.0f, "اكتملت إعادة تعبئة مسارات الفيديو في حاوية ${outputFile.extension.uppercase()} دون تغيير الترميز أو الدقة.")
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            if (!published) stagedOutput.delete()
        }
    }

    private fun mapSampleFlags(sampleFlags: Int): Int? {
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) return null
        var codecFlags = 0
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (Build.VERSION.SDK_INT >= 26 &&
            (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }

    private fun isValidMuxedOutput(file: File, extension: String): Boolean {
        if (!file.isFile || file.length() <= 128L) return false
        val headerMatches = java.io.DataInputStream(file.inputStream()).use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            when (extension.lowercase()) {
                "mp4" -> String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
                "webm" -> header.copyOfRange(0, 4).contentEquals(
                    byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
                )
                else -> false
            }
        }
        if (!headerMatches) return false
        val check = MediaExtractor()
        return try {
            check.setDataSource(file.absolutePath)
            (0 until check.trackCount).any {
                check.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
        } catch (_: Exception) {
            false
        } finally {
            try { check.release() } catch (_: Exception) {}
        }
    }

    /**
     * Remuxes supported H.264/H.265 + AAC/Opus tracks into an actual Matroska container.
     * Kotmpeg requires Android API 26+. Unsupported input codecs fail; source bytes are never
     * copied under an .mkv suffix as a fallback.
     */
    private fun remuxAsMatroska(
        sourceFile: File,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < 26) {
            onProgress(0f, "إخراج MKV يتطلب Android 8.0 (API 26) أو أحدث.")
            return false
        }
        val parent = outputFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val stagedFile = File(parent, ".${outputFile.nameWithoutExtension}.${UUID.randomUUID()}.mkv")
        return try {
            onProgress(0.05f, "إعادة تعبئة المسارات المدعومة داخل حاوية Matroska...")
            val packetCount = MkvKotlin.remux(
                input = sourceFile,
                output = stagedFile,
                onProgress = { writtenPackets ->
                    onProgress(0.1f, "إعادة تعبئة مسارات MKV ($writtenPackets حزمة)...")
                },
                onWarning = { warning ->
                    android.util.Log.w("VideoTranscoder", "Matroska remux warning: $warning")
                }
            )
            if (packetCount <= 0L || !stagedFile.isFile || stagedFile.length() <= 128L) {
                throw IllegalStateException("Matroska muxer produced no media packets")
            }
            val hasEbmlHeader = java.io.DataInputStream(stagedFile.inputStream()).use { input ->
                val header = ByteArray(4)
                input.readFully(header)
                header.contentEquals(byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()))
            }
            if (!hasEbmlHeader) throw IllegalStateException("Output does not contain an EBML/Matroska header")
            val hasVideoTrack = MkvKotlin.openDemuxer(stagedFile).use { demuxer ->
                demuxer.tracks.any { it is TrackInfo.Video }
            }
            if (!hasVideoTrack) {
                throw IllegalStateException("The Matroska output does not contain a readable video track")
            }
            if (!publishCompletedFile(stagedFile, outputFile)) {
                throw IllegalStateException("Unable to publish the completed MKV file")
            }
            onProgress(1f, "اكتملت إعادة التعبئة دون تغيير الترميز أو الدقة أو السرعة.")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            stagedFile.delete()
            false
        } finally {
            if (stagedFile.exists()) stagedFile.delete()
        }
    }
}
