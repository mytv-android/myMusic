package com.mytv.mymusic.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 按元数据给整条音轨施加增益，带约 30ms 的平滑；负增益和峰值限幅都在这里做，
 * 不走系统 LoudnessEnhancer（那个只能加不能减）。
 */
@UnstableApi
class VolumeGainAudioProcessor : BaseAudioProcessor() {
    private var sampleRate = 44100
    private var encoding = C.ENCODING_PCM_16BIT
    private var currentGain = 1f
    private var lastGeneration = Long.MIN_VALUE
    private var smoothing = 0.01f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount <= 0 || inputAudioFormat.sampleRate <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        encoding = inputAudioFormat.encoding
        smoothing = (1.0 - exp(-1.0 / (sampleRate * 0.03))).toFloat()
        currentGain = targetGain()
        lastGeneration = VolumeNormalizationState.generation
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val target = targetGain()
        if (VolumeNormalizationState.generation != lastGeneration) {
            currentGain = target
            lastGeneration = VolumeNormalizationState.generation
        }
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> process16(inputBuffer, output, target)
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, output, target)
            else -> output.put(inputBuffer)
        }
        output.flip()
    }

    private fun process16(input: ByteBuffer, output: ByteBuffer, target: Float) {
        while (input.hasRemaining()) {
            currentGain += (target - currentGain) * smoothing
            val sample = input.short.toInt()
            output.putShort((sample * currentGain).roundToInt().coerceIn(-32768, 32767).toShort())
        }
    }

    private fun processFloat(input: ByteBuffer, output: ByteBuffer, target: Float) {
        while (input.hasRemaining()) {
            currentGain += (target - currentGain) * smoothing
            output.putFloat((input.float * currentGain).coerceIn(-1f, 1f))
        }
    }

    private fun targetGain(): Float =
        if (VolumeNormalizationState.enabled) {
            10f.pow(VolumeNormalizationState.gainDb / 20f)
        } else {
            1f
        }

    override fun isActive(): Boolean = true

    override fun onReset() {
        currentGain = targetGain()
        lastGeneration = VolumeNormalizationState.generation
    }
}
