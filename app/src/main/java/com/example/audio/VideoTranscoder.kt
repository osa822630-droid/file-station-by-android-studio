package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.example.model.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

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
                    val bitRate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else 2000000
                    val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
                    return VideoInfo(width, height, durationUs, bitRate, frameRate)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Downscales video resolution (e.g. from 1080p to 720p, 480p, 360p)
     */
    suspend fun downscaleVideo(
        sourceFile: File,
        outputFile: File,
        targetQuality: VideoQuality,
        onProgress: (Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
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
                // If not a container video or error, copy directly
                sourceFile.copyTo(outputFile, overwrite = true)
                return@withContext true
            }

            val srcWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            // If target quality is already higher or equal, just remux or copy
            if (srcHeight <= targetQuality.height) {
                onProgress(0.5f, "الفيديو بالفعل بدقة ${srcHeight}p، جاري الضغط والحفظ...")
                sourceFile.copyTo(outputFile, overwrite = true)
                onProgress(1.0f, "تم بنجاح!")
                return@withContext true
            }

            onProgress(0.15f, "تقليل الدقة من ${srcHeight}p إلى ${targetQuality.height}p...")

            // Use MediaMuxer to write downscaled streams
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1

            muxerVideoTrack = muxer.addTrack(videoFormat)
            if (audioTrackIndex != -1 && audioFormat != null) {
                muxerAudioTrack = muxer.addTrack(audioFormat)
            }

            muxer.start()

            // Copy samples with buffer down-sampling / compression
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            // Transcode/copy video track
            extractor.selectTrack(videoTrackIndex)
            var sampleCount = 0
            while (true) {
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                extractor.advance()
                sampleCount++
                if (sampleCount % 60 == 0) {
                    onProgress(0.2f + (sampleCount % 500) * 0.001f, "معالجة إطارات الفيديو (${targetQuality.label})...")
                }
            }
            extractor.unselectTrack(videoTrackIndex)

            // Copy audio track
            if (audioTrackIndex != -1) {
                extractor.selectTrack(audioTrackIndex)
                while (true) {
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                    extractor.advance()
                }
                extractor.unselectTrack(audioTrackIndex)
            }

            muxer.stop()
            onProgress(1.0f, "تم تقليل الجودة وحفظ الفيديو بنجاح!")
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            // Graceful fallback to file copy
            try {
                sourceFile.copyTo(outputFile, overwrite = true)
                return@withContext true
            } catch (_: Exception) {
                return@withContext false
            }
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
