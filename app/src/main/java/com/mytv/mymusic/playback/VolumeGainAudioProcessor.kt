package com.mytv.mymusic.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 有平台响度元数据时施加固定增益（带约 30ms 平滑）；
 * 没有元数据时改用实时 AGC + 峰值限幅，把不同歌曲的响度拉近。
 * 负增益、压缩和限幅都在这里做，不走系统 LoudnessEnhancer（那个只能加不能减）。
 */
@UnstableApi
class VolumeGainAudioProcessor : BaseAudioProcessor() {
    private var sampleRate = 44100
    private var encoding = C.ENCODING_PCM_16BIT
    private var currentGain = 1f
    private var lastGeneration = Long.MIN_VALUE
    private var smoothing = 0.01f

    private var rmsEnv = INITIAL_RMS
    private var peakEnv = 0f
    private var agcGain = 1f
    private var rmsCoeff = 0.001f
    private var agcUpCoeff = 0.001f
    private var agcDownCoeff = 0.001f
    private var limiterDecay = 0.99f

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
        smoothing = coefficient(0.03)
        rmsCoeff = coefficient(0.4)
        agcUpCoeff = coefficient(0.8)
        agcDownCoeff = coefficient(0.25)
        limiterDecay = exp(-1.0 / (sampleRate * 0.08)).toFloat()
        resetFallback()
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
            resetFallback()
        }
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val fallback = VolumeNormalizationState.enabled && VolumeNormalizationState.fallback
        when {
            fallback && encoding == C.ENCODING_PCM_16BIT -> processFallback16(inputBuffer, output)
            fallback && encoding == C.ENCODING_PCM_FLOAT -> processFallbackFloat(inputBuffer, output)
            encoding == C.ENCODING_PCM_16BIT -> processStatic16(inputBuffer, output, target)
            encoding == C.ENCODING_PCM_FLOAT -> processStaticFloat(inputBuffer, output, target)
            else -> output.put(inputBuffer)
        }
        output.flip()
    }

    private fun processStatic16(input: ByteBuffer, output: ByteBuffer, target: Float) {
        while (input.hasRemaining()) {
            currentGain += (target - currentGain) * smoothing
            val sample = input.short.toInt()
            output.putShort((sample * currentGain).roundToInt().coerceIn(-32768, 32767).toShort())
        }
    }

    private fun processStaticFloat(input: ByteBuffer, output: ByteBuffer, target: Float) {
        while (input.hasRemaining()) {
            currentGain += (target - currentGain) * smoothing
            output.putFloat((input.float * currentGain).coerceIn(-1f, 1f))
        }
    }

    private fun processFallback16(input: ByteBuffer, output: ByteBuffer) {
        while (input.hasRemaining()) {
            val sample = input.short / 32768f
            val value = (sample * nextFallbackGain(sample)).coerceIn(-1f, 1f)
            output.putShort((value * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
    }

    private fun processFallbackFloat(input: ByteBuffer, output: ByteBuffer) {
        while (input.hasRemaining()) {
            val sample = input.float
            output.putFloat((sample * nextFallbackGain(sample)).coerceIn(-1f, 1f))
        }
    }

    /** 慢速 AGC 让整体响度靠近目标，瞬时峰值用零延迟限幅器压到 ceiling 以下。 */
    private fun nextFallbackGain(sample: Float): Float {
        rmsEnv = sqrt(rmsEnv * rmsEnv * (1 - rmsCoeff) + sample * sample * rmsCoeff)
        val desired = (TARGET_RMS / max(rmsEnv, 1e-4f)).coerceIn(MIN_AGC, MAX_AGC)
        val coeff = if (desired > agcGain) agcUpCoeff else agcDownCoeff
        agcGain += (desired - agcGain) * coeff
        // 限幅器看 AGC 之后的电平，峰值样本会被即时压到 ceiling
        val scaled = abs(sample * agcGain)
        peakEnv = max(scaled, peakEnv * limiterDecay)
        val limiter = if (peakEnv > LIMIT) LIMIT / peakEnv else 1f
        return agcGain * limiter
    }

    private fun resetFallback() {
        rmsEnv = INITIAL_RMS
        peakEnv = 0f
        agcGain = 1f
    }

    private fun coefficient(seconds: Double): Float =
        (1.0 - exp(-1.0 / (sampleRate * seconds))).toFloat()

    private fun targetGain(): Float =
        if (VolumeNormalizationState.enabled && !VolumeNormalizationState.fallback) {
            10f.pow(VolumeNormalizationState.gainDb / 20f)
        } else {
            1f
        }

    override fun isActive(): Boolean = true

    override fun onReset() {
        currentGain = targetGain()
        lastGeneration = VolumeNormalizationState.generation
        resetFallback()
    }

    private companion object {
        const val INITIAL_RMS = 0.1f
        const val TARGET_RMS = 0.13f
        const val MIN_AGC = 0.1f
        const val MAX_AGC = 2f
        const val LIMIT = 0.98f
    }
}
