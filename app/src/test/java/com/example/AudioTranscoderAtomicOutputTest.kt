package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioTranscoder
import com.example.audio.WavHelper
import com.example.model.AudioFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioTranscoderAtomicOutputTest {
    @Test
    fun failedLocalTranscodesKeepThePreviousDestinationForEveryAudioFormat() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val workDir = File(context.cacheDir, "atomic-audio-${UUID.randomUUID()}").apply { mkdirs() }
        val oldContents = "previous valid destination must survive".toByteArray()
        val transcoder = AudioTranscoder(context)

        try {
            for (format in listOf(AudioFormat.WAV, AudioFormat.M4A, AudioFormat.MP3)) {
                val destination = File(workDir, "existing.${format.extension}")
                destination.writeBytes(oldContents)

                val result = transcoder.transcodeAudio(
                    sourceUri = null,
                    sourceFile = null,
                    outputFile = destination,
                    targetSpeed = 1.0f,
                    targetFormat = format,
                    onProgress = { _, _ -> }
                )

                assertFalse("Invalid input should fail for $format", result)
                assertArrayEquals("Previous $format output must remain byte-for-byte intact", oldContents, destination.readBytes())
                assertTrue(
                    "No staged $format output should be left behind",
                    workDir.listFiles().orEmpty().none { it.name.contains(".pending.") }
                )
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun successfulWavConversionPublishesTheValidatedStageOverThePreviousFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val workDir = File(context.cacheDir, "atomic-audio-success-${UUID.randomUUID()}").apply { mkdirs() }
        val input = File(workDir, "source.wav")
        val destination = File(workDir, "destination.wav")
        val previousContents = "old file".toByteArray()
        destination.writeBytes(previousContents)
        WavHelper.writeWavFile(input, ShortArray(2_000) { (it % 31).toShort() }, 44_100, 1)

        try {
            val stages = mutableListOf<String>()
            val result = AudioTranscoder(context).transcodeAudio(
                sourceUri = null,
                sourceFile = input,
                outputFile = destination,
                targetSpeed = 1.0f,
                targetFormat = AudioFormat.WAV,
                onProgress = { _, stage -> stages += stage }
            )

            assertTrue("Valid WAV transcode should publish successfully; stages=$stages", result)
            assertTrue(destination.length() > 100L)
            assertFalse(destination.readBytes().contentEquals(previousContents))
            assertTrue(workDir.listFiles().orEmpty().none { it.name.contains(".pending.") })
        } finally {
            workDir.deleteRecursively()
        }
    }
}
