package com.example

import com.example.audio.PcmFileUtils
import com.example.audio.PcmResourceLimitException
import com.example.audio.WavHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WavStreamingTest {
    @Test
    fun wavPcmCanBeCopiedAndRewrittenWithFixedSizeBuffers() {
        val workDir = File(System.getProperty("java.io.tmpdir"), "wav-stream-${System.nanoTime()}")
        assertTrue(workDir.mkdirs())
        val source = File(workDir, "source.wav")
        val raw = File(workDir, "decoded.pcm")
        val output = File(workDir, "output.wav")
        val samples = ShortArray(4_000) { (it % 201 - 100).toShort() }

        try {
            WavHelper.writeWavFile(source, samples, sampleRate = 44_100, channels = 2)
            val metadata = WavHelper.copyWavPcmToRawFile(source, raw)

            val actualMetadata = metadata ?: throw AssertionError("WAV PCM data should be recognized")
            assertEquals(44_100, actualMetadata.sampleRate)
            assertEquals(2, actualMetadata.channels)
            assertEquals(samples.size.toLong(), actualMetadata.sampleCount)
            assertTrue(WavHelper.writeWavFileFromPcmFile(output, raw, 44_100, 2))
            val decoded = WavHelper.readWavFile(output)
                ?: throw AssertionError("Rewritten WAV should be readable")
            assertTrue(samples.contentEquals(decoded.samples))
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun oversizedWavIsRejectedBeforePcmAllocationOrCopy() {
        val workDir = File(System.getProperty("java.io.tmpdir"), "wav-limit-${System.nanoTime()}")
        assertTrue(workDir.mkdirs())
        val source = File(workDir, "source.wav")
        val raw = File(workDir, "decoded.pcm")
        try {
            WavHelper.writeWavFile(source, ShortArray(256) { it.toShort() }, 44_100, 1)
            var rejected = false
            try {
                WavHelper.copyWavPcmToRawFile(source, raw, maxPcmBytes = 128L)
            } catch (_: PcmResourceLimitException) {
                rejected = true
            }
            assertTrue("Oversized PCM must fail with a resource-limit exception", rejected)
            assertFalse(raw.exists())
        } finally {
            workDir.deleteRecursively()
        }
    }
}
