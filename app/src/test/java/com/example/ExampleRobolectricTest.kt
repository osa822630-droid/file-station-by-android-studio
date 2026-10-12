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
