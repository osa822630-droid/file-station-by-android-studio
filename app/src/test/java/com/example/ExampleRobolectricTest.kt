package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.WavHelper
import com.example.audio.WsolaTimeStretcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("File Station", appName)
    }

    @Test
    fun `test video quality and duplicate action models`() {
        assertEquals("1080p (Full HD)", com.example.model.VideoQuality.Q1080P.label)
        assertEquals(720, com.example.model.VideoQuality.Q720P.height)
        assertEquals(com.example.model.DuplicateAction.DOWNLOAD_ANYWAY, com.example.model.DuplicateAction.valueOf("DOWNLOAD_ANYWAY"))
        assertEquals(com.example.model.DuplicateAction.REPLACE_EXISTING, com.example.model.DuplicateAction.valueOf("REPLACE_EXISTING"))
        assertEquals(com.example.model.DuplicateAction.CANCEL, com.example.model.DuplicateAction.valueOf("CANCEL"))
    }

    @Test
    fun `test wsola time stretching 2x doubles speed`() {
        val sampleRate = 44100
        val originalSamples = ShortArray(sampleRate * 2) // 2 seconds of audio
        for (i in originalSamples.indices) {
            val t = i.toDouble() / sampleRate
            originalSamples[i] = (kotlin.math.sin(2.0 * Math.PI * 440.0 * t) * 10000.0).toInt().toShort()
        }

        val stretched = WsolaTimeStretcher.process(
            input = originalSamples,
            sampleRate = sampleRate,
            channels = 1,
            speed = 2.0f
        )

        // Stretched output at 2x should be approximately half length
        val ratio = stretched.size.toDouble() / originalSamples.size
        assertTrue("Output should be approximately half length: ratio=$ratio", ratio in 0.40..0.60)
    }

    @Test
    fun `test wav write and direct read without MediaExtractor`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testFile = File(context.cacheDir, "test_audio.wav")
        val sampleRate = 44100
        val pcm = ShortArray(1000) { (it % 100).toShort() }

        WavHelper.writeWavFile(testFile, pcm, sampleRate, 1)
        assertTrue(testFile.exists())

        val decoded = WavHelper.readWavFile(testFile)
        assertNotNull(decoded)
        assertEquals(sampleRate, decoded!!.sampleRate)
        assertEquals(1, decoded.channels)
        assertEquals(1000, decoded.samples.size)

        testFile.delete()
    }

    @Test
    fun `test audio bitrates values`() {
        assertEquals(48, com.example.model.AudioBitrate.B48.kbps)
        assertEquals(128, com.example.model.AudioBitrate.B128.kbps)
        assertEquals(256, com.example.model.AudioBitrate.B256.kbps)
        assertEquals("48k", com.example.model.AudioBitrate.B48.label)
        assertEquals("128k", com.example.model.AudioBitrate.B128.label)
        assertEquals("256k", com.example.model.AudioBitrate.B256.label)
    }

    @Test
    fun `test wav writing with 48k audio parameters`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testWav = File(context.cacheDir, "speech_48k.wav")
        val sampleRate = 44100
        val pcm = ShortArray(22050) { (kotlin.math.sin(it.toDouble() * 0.1) * 8000).toInt().toShort() }

        WavHelper.writeWavFile(testWav, pcm, sampleRate, 1)
        assertTrue(testWav.exists())
        assertTrue(testWav.length() > 1000)

        val readBack = WavHelper.readWavFile(testWav)
        assertNotNull(readBack)
        assertEquals(22050, readBack!!.samples.size)
        assertEquals(1, readBack.channels)

        testWav.delete()
    }

    @Test
    fun `jump3r LAME core encodes PCM as MPEG Layer III`() {
        val pcm = ShortArray(44100) { index ->
            (kotlin.math.sin(2.0 * Math.PI * 440.0 * index / 44100) * 12000).toInt().toShort()
        }
        val encoder = com.example.audio.Mp3PcmEncoder(44100, 1, 128)
        val encoded = ByteArray(4096 + 4096 / 4 + 7200)
        val output = java.io.ByteArrayOutputStream()
        try {
            var frameOffset = 0
            while (frameOffset < pcm.size) {
                val frameCount = minOf(4096, pcm.size - frameOffset)
                val bytes = encoder.encodeInterleaved(pcm, frameOffset, frameCount, encoded)
                assertTrue("LAME returned an encoding error: $bytes", bytes >= 0)
                if (bytes > 0) output.write(encoded, 0, bytes)
                frameOffset += frameCount
            }
            val flushed = encoder.flush(encoded)
            assertTrue("LAME returned a flush error: $flushed", flushed >= 0)
            if (flushed > 0) output.write(encoded, 0, flushed)
        } finally {
            encoder.close()
        }
        assertTrue("LAME must produce bytes", output.size() > 64)
        assertTrue("LAME output must contain an MPEG Layer III frame", containsMp3Frame(output.toByteArray()))
    }

    @Test
    fun `audio transcoder writes real MP3 frames and refuses mismatched extensions`() {
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val transcoder = com.example.audio.AudioTranscoder(context)
            val testInput = File(context.cacheDir, "mp3_source_${System.nanoTime()}.wav")
            val mp3Output = File(context.cacheDir, "encoded_${System.nanoTime()}.mp3")
            val wrongExtensionOutput = File(context.cacheDir, "wrong_extension_${System.nanoTime()}.wav")
            val pcm = ShortArray(44100) { index ->
                (kotlin.math.sin(2.0 * Math.PI * 440.0 * index / 44100) * 12000).toInt().toShort()
            }
            WavHelper.writeWavFile(testInput, pcm, 44100, 1)

            val stages = mutableListOf<String>()
            val mp3Success = transcoder.transcodeAudio(
                sourceUri = null,
                sourceFile = testInput,
                outputFile = mp3Output,
                targetSpeed = 1.0f,
                targetFormat = com.example.model.AudioFormat.MP3,
                bitrateKbps = 128
            ) { _, stage -> stages += stage }
            val mismatchedSuccess = transcoder.transcodeAudio(
                sourceUri = null,
                sourceFile = testInput,
                outputFile = wrongExtensionOutput,
                targetSpeed = 1.0f,
                targetFormat = com.example.model.AudioFormat.M4A
            ) { _, _ -> }

            assertTrue("LAME must create a nonempty MP3 file; stages=$stages; exists=${mp3Output.exists()}; size=${mp3Output.length()}", mp3Success && mp3Output.length() > 128L)
            assertTrue("Output must contain a valid MPEG Layer III frame header", containsMp3Frame(mp3Output.readBytes()))
            assertTrue("M4A output must use the matching file extension", !mismatchedSuccess)
            assertTrue(!wrongExtensionOutput.exists())
            testInput.delete()
            mp3Output.delete()
        }
    }

    private fun containsMp3Frame(data: ByteArray): Boolean {
        val start = if (data.size >= 10 && data[0] == 'I'.code.toByte() &&
            data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
            val tagSize = ((data[6].toInt() and 0x7f) shl 21) or
                ((data[7].toInt() and 0x7f) shl 14) or
                ((data[8].toInt() and 0x7f) shl 7) or
                (data[9].toInt() and 0x7f)
            10 + tagSize
        } else 0
        for (index in start until data.size - 3) {
            val first = data[index].toInt() and 0xff
            val second = data[index + 1].toInt() and 0xff
            val third = data[index + 2].toInt() and 0xff
            val version = (second shr 3) and 0x03
            val layer = (second shr 1) and 0x03
            val bitrateIndex = (third shr 4) and 0x0f
            val sampleRateIndex = (third shr 2) and 0x03
            if (first == 0xff && (second and 0xe0) == 0xe0 && version != 1 && layer == 1 &&
                bitrateIndex in 1..14 && sampleRateIndex != 3) return true
        }
        return false
    }

    @Test
    fun `test audio transcoder wav format transcoding`() {
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val transcoder = com.example.audio.AudioTranscoder(context)
            val testInput = File(context.cacheDir, "input_transcode.wav")
            val testOutput = File(context.cacheDir, "output_transcode.wav")
            val pcm = ShortArray(44100) { (it % 50).toShort() }
            WavHelper.writeWavFile(testInput, pcm, 44100, 1)

            val success = transcoder.transcodeAudio(
                sourceUri = null,
                sourceFile = testInput,
                outputFile = testOutput,
                targetSpeed = 1.0f,
                targetFormat = com.example.model.AudioFormat.WAV,
                bitrateKbps = 128
            ) { _, _ -> }

            assertTrue(success)
            assertTrue(testOutput.exists())
            assertTrue(testOutput.length() > 100)

            testInput.delete()
            testOutput.delete()
        }
    }
}
