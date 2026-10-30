package com.mytv.mymusic.recognize

object AudioResampler {
    /** 录音转成 8kHz 单声道 Float32，网易云指纹和酷狗指纹都吃这个采样率。 */
    fun toMono8k(recorded: RecordedAudio): FloatArray =
        resample(recorded.samples, recorded.sampleRate, MicRecorder.TARGET_RATE)

    fun resample(samples: ShortArray, fromRate: Int, toRate: Int): FloatArray {
        if (samples.isEmpty() || fromRate <= 0 || toRate <= 0) return FloatArray(0)
        val outputCount = (samples.size.toLong() * toRate / fromRate).toInt()
        if (outputCount <= 0) return FloatArray(0)
        val step = fromRate.toDouble() / toRate
        val lastIndex = samples.lastIndex
        return FloatArray(outputCount) { index ->
            val position = index * step
            val base = position.toInt().coerceIn(0, lastIndex)
            val next = (base + 1).coerceAtMost(lastIndex)
            val fraction = position - base
            val value = samples[base] * (1 - fraction) + samples[next] * fraction
            (value / 32768.0).toFloat()
        }
    }

    /** Float32 PCM（-1..1）转 16bit 小端，酷狗指纹接口的 body 格式。 */
    fun toPcm16(samples: FloatArray): ByteArray {
        val output = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            val value = (samples[index].coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767)
            output[index * 2] = (value and 0xff).toByte()
            output[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return output
    }
}
