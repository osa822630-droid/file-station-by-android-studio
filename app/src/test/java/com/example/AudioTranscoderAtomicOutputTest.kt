package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioTranscoder
import com.example.audio.PcmFileUtils
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
import java.io.RandomAccessFile
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

    @Test
    fun oversizedSpeedChangeFailsBeforeLoadingPcmIntoMemoryAndShowsTheReason() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val workDir = File(context.cacheDir, "bounded-pcm-${UUID.randomUUID()}").apply { mkdirs() }
        val rawPcm = File(workDir, "large-source.pcm")
        val input = File(workDir, "large-source.wav")
        val output = File(workDir, "large-output.mp3")
        val wsolaLimit = PcmFileUtils.maxWsolaInputBytes()
        val pcmBytes = wsolaLimit + if (wsolaLimit % 2L == 0L) 2L else 1L

        try {
            RandomAccessFile(rawPcm, "rw").use { it.setLength(pcmBytes) }
            assertTrue(WavHelper.writeWavFileFromPcmFile(input, rawPcm, 44_100, 1))
            val stages = mutableListOf<String>()
            val result = AudioTranscoder(context).transcodeAudio(
                sourceUri = null,
                sourceFile = input,
                outputFile = output,
                targetSpeed = 0.5f,
                targetFormat = AudioFormat.MP3,
                onProgress = { _, stage -> stages += stage }
            )

            assertFalse("Unsafe WSOLA working memory must fail cleanly", result)
            assertTrue("The failure should tell the user which resource limit was reached",
                stages.any { it.startsWith("توقف التحويل بأمان:") })
            assertFalse("No partial output may be published", output.exists())
        } finally {
            workDir.deleteRecursively()
        }
    }
}
