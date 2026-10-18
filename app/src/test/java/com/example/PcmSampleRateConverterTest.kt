package com.example

import com.example.audio.PcmSampleRateConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun malformedInterleavedPcmIsRejected() {
        assertNull(PcmSampleRateConverter.resample(shortArrayOf(1, 2, 3), 44_100, 48_000, 2))
    }
}
