package com.example

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.audio.VideoTranscoder
import com.example.model.VideoQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoTranscoderInstrumentedTest {
    @Test
    fun invalidMkvSourceFailsWithoutReplacingExistingOutputOrLeavingStagingFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "video-remux-invalid-$id.bin")
        val output = File(context.cacheDir, "video-remux-existing-$id.mkv")
        val originalOutput = "preserve-existing-output-$id".toByteArray()
        source.writeText("not a media container")
        output.writeBytes(originalOutput)

        try {
            val success = VideoTranscoder(context).remuxVideo(source, output) { _, _ -> }

            assertFalse("Invalid media must not be reported as a successful MKV", success)
            assertArrayEquals("A failed remux must leave the previous output untouched", originalOutput, output.readBytes())
            assertTrue(
                "A failed remux must remove its temporary output",
                context.cacheDir.listFiles().orEmpty().none {
                    it.name.startsWith(".${output.nameWithoutExtension}.") && it.extension == "mkv"
                }
            )
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun invalidMp4SourceDoesNotCreateAnOutputWithAnMp4Extension() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "video-remux-invalid-$id.bin")
        val output = File(context.cacheDir, "video-remux-invalid-$id.mp4")
        source.writeText("not a media container")

        try {
            val success = VideoTranscoder(context).remuxVideo(source, output) { _, _ -> }

            assertFalse("Invalid media must not be reported as a successful MP4", success)
            assertFalse("A failed remux must not leave an output file", output.exists())
        } finally {
            source.delete()
            output.delete()
        }
    }
    @Test
    fun realExportAppliesSmallerResolutionAndFasterPresentationSpeed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "video-transcode-source-$id.mp4")
        val output = File(context.cacheDir, "video-transcode-result-$id.mp4")

        try {
            val encoderInfo = findByteBufferAvcEncoder()
            assumeTrue("No byte-buffer AVC encoder is available on this device", encoderInfo != null)
            createSmallAvcMp4(source, encoderInfo!!)

            val transcoder = VideoTranscoder(context)
            val sourceInfo = transcoder.inspectVideo(source)
            assertNotNull("The generated source must contain a readable video track", sourceInfo)

            val outputInfo = transcoder.transcodeVideo(
                sourceFile = source,
                outputFile = output,
                targetQuality = VideoQuality.Q240P,
                targetSpeed = 1.5f
            ) { _, _ -> }

            assertNotNull("The transformed MP4 must have readable video metadata", outputInfo)
            assertTrue("The output must be a real, non-empty MP4", output.isFile && output.length() > 128L)
            assertTrue(
                "The requested 240p maximum must reduce the short edge",
                minOf(outputInfo!!.width, outputInfo.height) <= 240
            )
            assertTrue(
                "The 1.5x export must have a shorter measured duration",
                outputInfo.durationUs < sourceInfo!!.durationUs * 0.95
            )
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun findByteBufferAvcEncoder(): MediaCodecInfo? {
        val supportedYuvFormats = setOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        )
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats.any { it in supportedYuvFormats }
        }
    }

    private fun createSmallAvcMp4(file: File, encoderInfo: MediaCodecInfo) {
        val width = 640
        val height = 360
        val frameCount = 12
        val frameRate = 10
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, encoderInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .colorFormats.first { it in setOf(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                ) })
            setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createByCodecName(encoderInfo.name)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var muxerTrack = -1
        var encoderStarted = false
        try {
            codec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoderStarted = true
            var nextFrame = 0
            var inputEnded = false
            var outputEnded = false
            var loopCount = 0
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputEnded && loopCount++ < 10_000) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("AVC encoder returned no input buffer")
                        inputBuffer.clear()
                        if (nextFrame < frameCount) {
                            writeYuvFrame(inputBuffer, width, height, videoFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT), nextFrame)
                            val size = inputBuffer.position()
                            codec.queueInputBuffer(inputIndex, 0, size, nextFrame * 1_000_000L / frameRate, 0)
                            nextFrame++
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                frameCount * 1_000_000L / frameRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerTrack = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outputIndex)
                            ?: throw IllegalStateException("AVC encoder returned no output buffer")
                        if (bufferInfo.size > 0 && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            val muxerInfo = MediaCodec.BufferInfo().apply {
                                set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                            }
                            muxer.writeSampleData(muxerTrack, encoded, muxerInfo)
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEnded = true
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (!outputEnded || !muxerStarted) throw IllegalStateException("Unable to encode the synthetic AVC fixture")
            muxer.stop()
            muxerStarted = false
        } finally {
            if (encoderStarted) {
                try { codec.stop() } catch (_: Exception) {}
            }
            try { codec.release() } catch (_: Exception) {}
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Exception) {}
            }
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    private fun writeYuvFrame(buffer: java.nio.ByteBuffer, width: Int, height: Int, colorFormat: Int, frameIndex: Int) {
        val lumaSize = width * height
        val chromaSize = lumaSize / 4
        repeat(lumaSize) { pixel -> buffer.put((16 + ((pixel + frameIndex * 17) % 200)).toByte()) }
        when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                repeat(chromaSize) {
                    buffer.put(128.toByte())
                    buffer.put(128.toByte())
                }
            }
            else -> {
                repeat(chromaSize) { buffer.put(96.toByte()) }
                repeat(chromaSize) { buffer.put(160.toByte()) }
            }
        }
    }

}
