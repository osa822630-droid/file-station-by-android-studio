package com.example

import com.example.audio.PcmFileUtils
import com.example.audio.PcmSampleRateConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class PcmSampleRateConverterTest {
    @Test
    fun sameRateReturnsOriginalPcmWithoutChangingSamples() {
        val pcm = shortArrayOf(-100, 0, 100)

        val result = PcmSampleRateConverter.resample(pcm, 44_100, 44_100, 1)

        assertSame(pcm, result)
    }

    @Test
    fun rateConversionProducesExpectedFrameCountAndInterpolatedValues() {
        val pcm = shortArrayOf(0, 100, 200, 300)

        val result = PcmSampleRateConverter.resample(pcm, 2, 4, 1)!!

        assertEquals(8, result.size)
        assertEquals(0, result[0].toInt())
        assertEquals(50, result[1].toInt())
        assertEquals(150, result[3].toInt())
        assertEquals(300, result.last().toInt())
    }

    @Test
    fun rawPcmFileConversionMatchesInMemoryInterpolationWithoutLoadingWholeFile() {
        val workDir = java.io.File(System.getProperty("java.io.tmpdir"), "pcm-file-${System.nanoTime()}")
        assertTrue(workDir.mkdirs())
        val input = java.io.File(workDir, "input.pcm")
        val output = java.io.File(workDir, "output.pcm")
        val source = shortArrayOf(0, 100, 200, 300)
        try {
            PcmFileUtils.writeSamples(input, source)

            val converted = PcmSampleRateConverter.convertRawFile(
                inputFile = input,
                outputFile = output,
                sourceSampleRate = 2,
                sourceChannels = 1,
                targetSampleRate = 4,
                targetChannels = 1
            )

            assertNotNull(converted)
            assertEquals(8L, converted!!.sampleCount)
            assertEquals(listOf(0, 50, 100, 150, 200, 250, 300, 300),
                PcmFileUtils.readSamples(output, 1024)!!.map { it.toInt() })
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun multichannelRawPcmCanBeDownmixedInStreamingConverter() {
        val workDir = java.io.File(System.getProperty("java.io.tmpdir"), "pcm-mono-${System.nanoTime()}")
        assertTrue(workDir.mkdirs())
        val input = java.io.File(workDir, "input.pcm")
        val output = java.io.File(workDir, "output.pcm")
        try {
            PcmFileUtils.writeSamples(input, shortArrayOf(100, 300, -100, 100))
            val converted = PcmSampleRateConverter.convertRawFile(
                inputFile = input,
                outputFile = output,
                sourceSampleRate = 48_000,
                sourceChannels = 2,
                targetSampleRate = 48_000,
                targetChannels = 1
            )
            assertNotNull(converted)
            assertEquals(listOf(200, 0), PcmFileUtils.readSamples(output, 1024)!!.map { it.toInt() })
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun timeStretchRejectsAnOutputLargerThanTheConfiguredMemoryBudget() {
        val samples = ShortArray(2_000) { it.toShort() }
        var rejected = false
        try {
            com.example.audio.WsolaTimeStretcher.process(
                input = samples,
                sampleRate = 44_100,
                channels = 1,
                speed = 0.5f,
                maxOutputSamples = 1_000
            )
        } catch (_: com.example.audio.PcmResourceLimitException) {
            rejected = true
        }
        assertTrue("WSOLA must refuse to allocate beyond the supplied memory budget", rejected)
    }

    @Test
    fun malformedInterleavedPcmIsRejected() {
        assertNull(PcmSampleRateConverter.resample(shortArrayOf(1, 2, 3), 44_100, 48_000, 2))
    }
}
