package com.example.audio

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib

/**
 * Thin PCM-only adapter over jump3r's LGPL Java LAME encoder core.
 * It deliberately avoids jump3r's Java Sound wrapper because javax.sound is not
 * available on Android.
 */
internal class Mp3PcmEncoder(
    inputSampleRate: Int,
    private val channels: Int,
    bitrateKbps: Int
) : AutoCloseable {
    private val lame = Lame()
    private val flags: LameGlobalFlags
    private var closed = false

    init {
        require(inputSampleRate > 0) { "Sample rate must be positive" }
        require(channels in 1..2) { "MP3 encoder accepts mono or stereo PCM" }

        val gainAnalysis = GainAnalysis()
        val bitStream = BitStream()
        val presets = Presets()
        val quantizePvt = QuantizePVT()
        val quantize = Quantize()
        val vbrTag = VBRTag()
        val version = Version()
        val id3Tag = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val decoderInterface = Interface()
        val common = Common()

        lame.setModules(gainAnalysis, bitStream, presets, quantizePvt, quantize, vbrTag, version, id3Tag, mpg)
        bitStream.setModules(gainAnalysis, mpg, version, vbrTag)
        id3Tag.setModules(bitStream, version)
        presets.setModules(lame)
        quantize.setModules(bitStream, reservoir, quantizePvt, takehiro)
        quantizePvt.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bitStream)
        takehiro.setModules(quantizePvt)
        vbrTag.setModules(lame, bitStream, version)
        parse.setModules(version, id3Tag, presets)
        mpg.setModules(decoderInterface, common)
        decoderInterface.setModules(vbrTag, common)

        flags = requireNotNull(lame.lame_init()) { "LAME initialization failed" }
        flags.num_channels = channels
        flags.in_samplerate = inputSampleRate
        // Keep LAME's automatic output-rate selection/resampler enabled.
        flags.brate = bitrateKbps.coerceIn(8, if (inputSampleRate < 32_000) 160 else 320)
        flags.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        flags.VBR = VbrMode.vbr_off
        flags.quality = 5
        flags.findReplayGain = false
        flags.write_id3tag_automatic = false
        id3Tag.id3tag_init(flags)
        flags.write_id3tag_automatic = false

        val result = lame.lame_init_params(flags)
        check(result >= 0) { "LAME rejected the PCM parameters (error $result)" }
    }

    /** Encodes interleaved signed 16-bit PCM; returns bytes written or a negative error code. */
    fun encodeInterleaved(
        pcm: ShortArray,
        frameOffset: Int,
        frameCount: Int,
        output: ByteArray
    ): Int {
        check(!closed) { "Encoder is already closed" }
        require(frameOffset >= 0 && frameCount >= 0 &&
            (frameOffset + frameCount) * channels <= pcm.size)
        val left = IntArray(frameCount)
        val right = IntArray(frameCount)
        var sourceIndex = frameOffset * channels
        for (frame in 0 until frameCount) {
            val leftSample = pcm[sourceIndex++].toInt() shl 16
            left[frame] = leftSample
            right[frame] = if (channels == 2) pcm[sourceIndex++].toInt() shl 16 else leftSample
        }
        return lame.lame_encode_buffer_int(flags, left, right, frameCount, output, 0, output.size)
    }

    fun flush(output: ByteArray): Int {
        check(!closed) { "Encoder is already closed" }
        return lame.lame_encode_flush(flags, output, 0, output.size)
    }

    override fun close() {
        if (!closed) {
            closed = true
            lame.lame_close(flags)
        }
    }
}
