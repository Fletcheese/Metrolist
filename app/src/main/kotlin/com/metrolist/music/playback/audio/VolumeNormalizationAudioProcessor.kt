package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0

    @Volatile
    var enabled = false
        set(value) {
            if (field != value) {
                field = value
                Timber.tag(TAG).d("Normalization processor enabled: $value")
            }
        }

    private data class GainState(val targetGainMb: Int, val linearGain: Double)

    @Volatile
    private var currentGain = GainState(0, 1.0)

    @Synchronized
    fun setTargetGain(gainMb: Int) {
        if (currentGain.targetGainMb != gainMb) {
            val linearGain = 10.0.pow(gainMb / 2000.0)
            currentGain = GainState(gainMb, linearGain)
            Timber.tag(TAG).d("Target gain set to $gainMb mB (Linear multiplier: $linearGain)")
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        bytesPerSample =
            when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }

        Timber.tag(TAG).d(
            "Configured: sampleRate=${inputAudioFormat.sampleRate}, channels=${inputAudioFormat.channelCount}, encoding=$encoding",
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val gain = currentGain
        val applyGain = enabled && gain.targetGainMb != 0
        val sampleCount = inputBuffer.remaining() / bytesPerSample
        val output = replaceOutputBuffer(sampleCount * bytesPerSample)

        // Every second of audio passes through here. With no gain to apply, copy in one go
        // rather than sample by sample.
        if (!applyGain) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        when (encoding) {
            C.ENCODING_PCM_16BIT -> scalePcm16(inputBuffer, output, sampleCount, gain.linearGain)

            C.ENCODING_PCM_24BIT ->
                repeat(sampleCount) {
                    val b0 = inputBuffer.get().toInt() and 0xFF
                    val b1 = inputBuffer.get().toInt() and 0xFF
                    val b2 = inputBuffer.get().toInt()
                    val sample = (b2 shl 16) or (b1 shl 8) or b0
                    val processed =
                        if (applyGain) {
                            (sample * gain.linearGain)
                                .coerceIn(-8388608.0, 8388607.0)
                                .toInt()
                        } else {
                            sample
                        }
                    output.put((processed and 0xFF).toByte())
                    output.put(((processed shr 8) and 0xFF).toByte())
                    output.put(((processed shr 16) and 0xFF).toByte())
                }

            C.ENCODING_PCM_32BIT ->
                repeat(sampleCount) {
                    val sample = inputBuffer.getInt()
                    output.putInt(
                        if (applyGain) {
                            (sample * gain.linearGain)
                                .coerceIn(-2147483648.0, 2147483647.0)
                                .toLong()
                                .toInt()
                        } else {
                            sample
                        },
                    )
                }

            C.ENCODING_PCM_FLOAT ->
                repeat(sampleCount) {
                    val sample = inputBuffer.getFloat()
                    output.putFloat(
                        if (applyGain) {
                            (sample * gain.linearGain.toFloat()).coerceIn(-1.0f, 1.0f)
                        } else {
                            sample
                        },
                    )
                }
        }

        output.flip()
    }

    private var pcm16Scratch = ShortArray(0)

    /**
     * 16-bit PCM is what YouTube's Opus and AAC streams decode to. Scale it through a plain
     * array with Q16 fixed-point gain: per-sample ByteBuffer calls and double maths here cost a
     * few percent of a CPU core for every hour of playback.
     */
    private fun scalePcm16(
        input: ByteBuffer,
        output: ByteBuffer,
        sampleCount: Int,
        linearGain: Double,
    ) {
        if (pcm16Scratch.size < sampleCount) pcm16Scratch = ShortArray(sampleCount)
        val samples = pcm16Scratch
        input.asShortBuffer().get(samples, 0, sampleCount)
        input.position(input.position() + sampleCount * 2)

        val gainQ16 = (linearGain * 65536.0).toLong()
        for (i in 0 until sampleCount) {
            val scaled = (samples[i] * gainQ16) shr 16
            samples[i] =
                when {
                    scaled > Short.MAX_VALUE -> Short.MAX_VALUE
                    scaled < Short.MIN_VALUE -> Short.MIN_VALUE
                    else -> scaled.toInt().toShort()
                }
        }

        output.asShortBuffer().put(samples, 0, sampleCount)
        output.position(output.position() + sampleCount * 2)
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
    }

    private companion object {
        const val TAG = "VolumeNormalizationProcessor"
    }
}
