package com.mytv.mymusic.recognize

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

data class RecordedAudio(val samples: ShortArray, val sampleRate: Int)

object MicRecorder {
    const val DEFAULT_SECONDS = 12
    const val TARGET_RATE = 8000

    /**
     * 录一段麦克风音频。优先用 VOICE_RECOGNITION（关闭系统降噪 / 自动增益），
     * 设备不支持时退回普通 MIC。进度回调大约每 100ms 一次。
     */
    suspend fun record(
        seconds: Int = DEFAULT_SECONDS,
        onProgress: (progress: Float, level: Float) -> Unit,
    ): RecordedAudio = withContext(Dispatchers.IO) {
        val sampleRate = 44100
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        val bufferSize = maxOf(minBuffer, sampleRate / 10 * 2)
        val recorder = createRecorder(sampleRate, channel, encoding, bufferSize)
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风被占用" }
            val total = sampleRate * seconds
            val samples = ShortArray(total)
            val buffer = ShortArray(maxOf(minBuffer / 2, 1024))
            var read = 0
            while (read < total) {
                coroutineContext.ensureActive()
                val count = recorder.read(buffer, 0, minOf(buffer.size, total - read))
                if (count <= 0) break
                System.arraycopy(buffer, 0, samples, read, count)
                var sum = 0.0
                for (index in 0 until count) {
                    val value = buffer[index] / 32768.0
                    sum += value * value
                }
                val rms = sqrt(sum / count)
                read += count
                onProgress(read.toFloat() / total, (rms * 7.0).toFloat().coerceIn(0f, 1f))
            }
            RecordedAudio(samples.copyOf(read), sampleRate)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun createRecorder(sampleRate: Int, channel: Int, encoding: Int, bufferSize: Int): AudioRecord {
        val preferred = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, channel, encoding, bufferSize)
        }.getOrNull()
        if (preferred != null && preferred.state == AudioRecord.STATE_INITIALIZED) return preferred
        runCatching { preferred?.release() }
        return AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channel, encoding, bufferSize)
    }
}
