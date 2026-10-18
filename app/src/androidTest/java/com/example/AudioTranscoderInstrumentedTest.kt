package com.example

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.audio.AudioTranscoder
import com.example.audio.WavHelper
import com.example.model.AudioFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioTranscoderInstrumentedTest {
    @Test
    fun m4aOutputIsAnAacTrackInAnMp4Container() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "m4a-source-$id.wav")
        val output = File(context.cacheDir, "m4a-output-$id.m4a")
        val roundTripOutput = File(context.cacheDir, "m4a-roundtrip-$id.m4a")
        val sampleRate = 44_100
        val samples = ShortArray(sampleRate) { i ->
            (sin(2.0 * PI * 440.0 * i / sampleRate) * 12_000).toInt().toShort()
        }
        var extractor: MediaExtractor? = null
        var roundTripExtractor: MediaExtractor? = null
        var outputInfo: AudioTranscoder.AudioOutputInfo? = null
        var roundTripInfo: AudioTranscoder.AudioOutputInfo? = null
        val transcoder = AudioTranscoder(context)

        try {
            WavHelper.writeWavFile(source, samples, sampleRate, 1)
            val success = transcoder.transcodeAudio(
                sourceUri = null,
                sourceFile = source,
                outputFile = output,
                targetSpeed = 1.0f,
                targetFormat = AudioFormat.M4A,
                bitrateKbps = 128,
                onOutputInfo = { outputInfo = it }
            ) { _, _ -> }

            assertTrue("M4A AAC transcode should succeed on this Android device", success)
            assertTrue("M4A output should be nonempty", output.isFile && output.length() > 128L)
            val header = ByteArray(12)
            java.io.DataInputStream(output.inputStream()).use { input ->
                input.readFully(header)
            }
            assertEquals("ftyp", String(header, 4, 4, StandardCharsets.US_ASCII))

            extractor = MediaExtractor()
            extractor.setDataSource(output.absolutePath)
            assertTrue("M4A should contain at least one media track", extractor.trackCount > 0)
            val audioTrack = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { (it.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/") }
            val actualAudioTrack = audioTrack ?: throw AssertionError("M4A should contain an audio track")
            assertEquals("audio/mp4a-latm", actualAudioTrack.getString(MediaFormat.KEY_MIME))
            assertNotNull("The transcoder should report negotiated output metadata", outputInfo)
            assertEquals(actualAudioTrack.getInteger(MediaFormat.KEY_SAMPLE_RATE), outputInfo!!.sampleRate)
            assertEquals(actualAudioTrack.getInteger(MediaFormat.KEY_CHANNEL_COUNT), outputInfo!!.channels)
            extractor.release()
            extractor = null

            val roundTripSuccess = transcoder.transcodeAudio(
                sourceUri = null,
                sourceFile = output,
                outputFile = roundTripOutput,
                targetSpeed = 1.0f,
                targetFormat = AudioFormat.M4A,
                bitrateKbps = 128,
                onOutputInfo = { roundTripInfo = it }
            ) { _, _ -> }
            assertTrue("M4A-to-M4A decode and re-encode should succeed", roundTripSuccess)
            roundTripExtractor = MediaExtractor()
            roundTripExtractor.setDataSource(roundTripOutput.absolutePath)
            val roundTripTrack = (0 until roundTripExtractor.trackCount)
                .map { roundTripExtractor.getTrackFormat(it) }
                .firstOrNull { (it.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/") }
                ?: throw AssertionError("Round-trip M4A should contain an audio track")
            assertNotNull(roundTripInfo)
            assertEquals(roundTripTrack.getInteger(MediaFormat.KEY_SAMPLE_RATE), roundTripInfo!!.sampleRate)
            assertEquals(roundTripTrack.getInteger(MediaFormat.KEY_CHANNEL_COUNT), roundTripInfo!!.channels)
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { roundTripExtractor?.release() } catch (_: Exception) {}
            source.delete()
            output.delete()
            roundTripOutput.delete()
        }
    }
}
