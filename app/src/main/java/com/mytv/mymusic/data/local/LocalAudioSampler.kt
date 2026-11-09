package com.mytv.mymusic.data.local

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.mytv.mymusic.recognize.AudioResampler
import com.mytv.mymusic.recognize.MicRecorder
import java.io.IOException
import java.nio.ByteOrder

/**
 * 给听歌识曲取一段：从 [startMs] 起解码 [seconds] 秒，混成单声道，重采样成 8kHz Float32——
 * 和麦克风识曲喂给 NeteaseFingerprinter 的格式一样。只解这几秒，不解整首。
 */
object LocalAudioSampler {
    private const val DECODE_BUDGET_MS = 8_000L

    fun sample(context: Context, uri: Uri, startMs: Long, seconds: Int = LocalMatchRules.SAMPLE_SECONDS): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw IOException("没有音轨")
            extractor.selectTrack(trackIndex)
            val startUs = startMs * 1000L
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("没有编码格式")
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var mono = ShortArray(sampleRate * seconds)
            var filled = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + DECODE_BUDGET_MS
            while (!outputDone && filled < mono.size) {
                if (System.currentTimeMillis() > deadline) throw IOException("解码超时")
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        // 真实采样率和容器里写的可能不同（HE-AAC），按解码器说的重新定缓冲
                        if (filled == 0) mono = ShortArray(sampleRate * seconds)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames = info.size / (bytesPerSample * channels)
                            // 从上一个关键帧开始解的，起点之前的那几帧丢掉
                            var skip = 0
                            if (info.presentationTimeUs < startUs) {
                                skip = ((startUs - info.presentationTimeUs) * sampleRate / 1_000_000L).toInt().coerceAtMost(frames)
                            }
                            for (frame in 0 until frames) {
                                var sum = 0f
                                for (channel in 0 until channels) {
                                    sum += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                        buffer.float * 32767f
                                    } else {
                                        buffer.short.toFloat()
                                    }
                                }
                                if (frame < skip || filled >= mono.size) continue
                                mono[filled++] = (sum / channels).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            // 少于 2.5 秒指纹就不可靠了
            if (filled < sampleRate * 5 / 2) throw IOException("可用音频不足")
            val pcm = AudioResampler.resample(mono.copyOf(filled), sampleRate, MicRecorder.TARGET_RATE)
            val target = MicRecorder.TARGET_RATE * seconds
            return if (pcm.size >= target) pcm.copyOf(target) else pcm
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
