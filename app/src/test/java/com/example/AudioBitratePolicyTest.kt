package com.example

import com.example.audio.AudioBitratePolicy
import com.example.model.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioBitratePolicyTest {
    @Test
    fun aacPreservesHighestUiBitrateForMonoStereoAndDownmixedSources() {
        assertEquals(320, AudioBitratePolicy.appliedKbps(AudioFormat.M4A, 320, 44_100, 1))
        assertEquals(320, AudioBitratePolicy.appliedKbps(AudioFormat.M4A, 320, 44_100, 2))
        assertEquals(320, AudioBitratePolicy.appliedKbps(AudioFormat.M4A, 320, 48_000, 6))
    }

    @Test
    fun aacRequestWithinTheSupportedRangeIsPreserved() {
        assertEquals(128, AudioBitratePolicy.appliedKbps(AudioFormat.M4A, 128, 48_000, 2))
    }

    @Test
    fun aacPolicyBoundsOutOfRangeRequests() {
        assertEquals(32, AudioBitratePolicy.appliedKbps(AudioFormat.M4A, 8, 48_000, 2))
        assertEquals(320, AudioBitratePolicy.appliedKbps(AudioFormat.M4A, 512, 48_000, 2))
    }

    @Test
    fun lowSampleRateMp3UsesTheEncoderLimit() {
        assertEquals(160, AudioBitratePolicy.appliedKbps(AudioFormat.MP3, 320, 22_050, 2))
        assertEquals(320, AudioBitratePolicy.appliedKbps(AudioFormat.MP3, 320, 44_100, 2))
    }

    @Test
    fun wavReportsItsUncompressedPcmBitrate() {
        assertEquals(1_411, AudioBitratePolicy.appliedKbps(AudioFormat.WAV, 128, 44_100, 2))
    }

    @Test
    fun invalidAudioGeometryAndVideoFormatsHaveNoAudioBitrate() {
        assertNull(AudioBitratePolicy.appliedKbps(AudioFormat.MP3, 128, 0, 2))
        assertNull(AudioBitratePolicy.appliedKbps(AudioFormat.WEBM, 128, 48_000, 2))
    }
}
