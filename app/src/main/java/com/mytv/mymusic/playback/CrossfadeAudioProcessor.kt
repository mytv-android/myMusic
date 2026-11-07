package com.mytv.mymusic.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 每路播放器一份的过渡参数。控制器在淡入淡出时写，音频线程读：
 * 低频交接靠低通 / 高通扫频，尾奏承接靠回声；其余接法两个频率都是 0（不过滤）。
 */
class CrossfadeParams {
    /** 0 表示不启用；单位 Hz。 */
    @Volatile
    var lowPassHz: Float = 0f

    @Volatile
    var highPassHz: Float = 0f

    /** 0..1，混进回声的比例。 */
    @Volatile
    var echoMix: Float = 0f

    fun clear() {
        lowPassHz = 0f
        highPassHz = 0f
        echoMix = 0f
    }
}

/**
 * 接在音量均衡之后的过渡效果：二阶 biquad 低通 / 高通 + 300ms 反馈回声。
 * 平时参数为 0，直接原样输出；只有智能过渡挑到低频交接 / 尾奏承接时才会有动静。
 */
@UnstableApi
class CrossfadeAudioProcessor(private val params: CrossfadeParams) : BaseAudioProcessor() {
    private var sampleRate = 44100
    private var encoding = C.ENCODING_PCM_16BIT
    private var channels = 2

    private var lowPass = Biquad()
    private var highPass = Biquad()
    private var lowPassHz = 0f
    private var highPassHz = 0f

    private var echoBuffers: Array<FloatArray> = emptyArray()
    private var echoPos = 0
    private var echoSize = 0

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
        channels = inputAudioFormat.channelCount
        lowPass = Biquad()
        highPass = Biquad()
        lowPassHz = 0f
        highPassHz = 0f
        echoSize = (sampleRate * ECHO_MS / 1000f).toInt().coerceAtLeast(1)
        echoBuffers = Array(channels) { FloatArray(echoSize) }
        echoPos = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        val lowTarget = params.lowPassHz.coerceIn(0f, sampleRate * 0.45f)
        val highTarget = params.highPassHz.coerceIn(0f, sampleRate * 0.45f)
        if (kotlin.math.abs(lowTarget - lowPassHz) > 1f || (lowTarget == 0f) != (lowPassHz == 0f)) {
            lowPassHz = lowTarget
            lowPass = Biquad()
            lowPass.configure(lowTarget, highPass = false, sampleRate)
        }
        if (kotlin.math.abs(highTarget - highPassHz) > 1f || (highTarget == 0f) != (highPassHz == 0f)) {
            highPassHz = highTarget
            highPass = Biquad()
            highPass.configure(highTarget, highPass = true, sampleRate)
        }
        val echo = params.echoMix.coerceIn(0f, 1f)

        val frames = size / (channels * bytesPerSample())
        var frame = 0
        while (frame < frames) {
            for (channel in 0 until channels) {
                var value = readSample(inputBuffer)
                value = highPass.process(value, channel)
                value = lowPass.process(value, channel)
                if (echoBuffers.isNotEmpty() && echoBuffers[channel].isNotEmpty()) {
                    val delayed = echoBuffers[channel][echoPos]
                    echoBuffers[channel][echoPos] = value + delayed * ECHO_FEEDBACK
                    if (echo > 0f) value += delayed * echo
                }
                writeSample(output, value)
            }
            if (echoBuffers.isNotEmpty() && echoSize > 0) echoPos = (echoPos + 1) % echoSize
            frame++
        }
        output.flip()
    }

    override fun isActive(): Boolean = true

    override fun onReset() {
        lowPass = Biquad()
        highPass = Biquad()
        lowPassHz = 0f
        highPassHz = 0f
        echoBuffers.forEach { it.fill(0f) }
        echoPos = 0
    }

    private fun bytesPerSample(): Int = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2

    private fun readSample(buffer: ByteBuffer): Float =
        if (encoding == C.ENCODING_PCM_FLOAT) buffer.float.coerceIn(-1f, 1f)
        else (buffer.short / 32768f).coerceIn(-1f, 1f)

    private fun writeSample(buffer: ByteBuffer, value: Float) {
        val clamped = value.coerceIn(-1f, 1f)
        if (encoding == C.ENCODING_PCM_FLOAT) {
            buffer.putFloat(clamped)
        } else {
            buffer.putShort((clamped * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
    }

    /** RBJ cookbook 二阶滤波器，每个声道各一份状态。 */
    private class Biquad {
        private var z1 = FloatArray(8)
        private var z2 = FloatArray(8)

        fun configure(cutoffHz: Float, highPass: Boolean, sampleRate: Int) {
            enabled = cutoffHz > 0f
            if (!enabled) return
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW = cos(w0)
            val sinW = sin(w0)
            val alpha = sinW / (2.0 * Q)
            val b0: Double
            val b1: Double
            val b2: Double
            if (highPass) {
                b0 = (1 + cosW) / 2
                b1 = -(1 + cosW)
                b2 = (1 + cosW) / 2
            } else {
                b0 = (1 - cosW) / 2
                b1 = 1 - cosW
                b2 = (1 - cosW) / 2
            }
            val a0 = 1 + alpha
            coefB0 = (b0 / a0).toFloat()
            coefB1 = (b1 / a0).toFloat()
            coefB2 = (b2 / a0).toFloat()
            coefA1 = (-2 * cosW / a0).toFloat()
            coefA2 = ((1 - alpha) / a0).toFloat()
        }

        private var enabled = false
        private var coefB0 = 1f
        private var coefB1 = 0f
        private var coefB2 = 0f
        private var coefA1 = 0f
        private var coefA2 = 0f

        fun process(input: Float, channel: Int): Float {
            if (!enabled) return input
            val index = channel.coerceIn(0, z1.size - 1)
            val out = coefB0 * input + z1[index]
            z1[index] = coefB1 * input - coefA1 * out + z2[index]
            z2[index] = coefB2 * input - coefA2 * out
            return out
        }
    }

    private companion object {
        const val ECHO_MS = 300f
        const val ECHO_FEEDBACK = 0.35f
        const val Q = 0.70710678
    }
}
