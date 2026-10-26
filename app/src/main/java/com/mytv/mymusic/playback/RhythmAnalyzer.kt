package com.mytv.mymusic.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal const val RHYTHM_TARGET_FPS = 30

/** A hop larger than the FFT window would leave short transients unanalysed. */
internal fun rhythmHopSamples(sampleRateHz: Int): Int =
    (sampleRateHz / RHYTHM_TARGET_FPS).coerceIn(1, RHYTHM_FFT_SIZE)

/**
 * Taps decoded playback PCM without using the microphone or modifying the samples.
 * FFT work stays off ExoPlayer's audio thread; at most one analysis job is queued.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RhythmAnalyzer {
    private val _frames = MutableStateFlow(RhythmFrame())
    val frames: StateFlow<RhythmFrame> = _frames.asStateFlow()

    @Volatile
    private var active = false

    @Volatile
    private var sampleRateHz = 0

    @Volatile
    private var channelCount = 0

    @Volatile
    private var encoding = C.ENCODING_PCM_16BIT

    private val generation = AtomicInteger()
    private val frameLock = Any()
    private val queued = AtomicBoolean(false)
    private var executor: ExecutorService? = null

    // The ring is audio-thread confined; analysisInput is handed to one queued worker at a time.
    private val ring = FloatArray(RHYTHM_FFT_SIZE)
    private val analysisInput = FloatArray(RHYTHM_FFT_SIZE)
    private var ringGeneration = -1
    private var ringWrite = 0
    private var ringSize = 0
    private var hopSamples = 1
    private var samplesUntilHop = 1

    // Only the analysis executor accesses these three fields.
    private val analysis = RhythmAnalysis()
    private var analysisGeneration = -1
    private var lastAnalysisAtNs = 0L

    @Synchronized
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        reset()
    }

    /** Drops the previous track's gain history and any queued visual result. */
    fun reset() {
        synchronized(frameLock) {
            generation.incrementAndGet()
            _frames.value = RhythmFrame()
        }
    }

    val audioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            this@RhythmAnalyzer.sampleRateHz = sampleRateHz
            this@RhythmAnalyzer.channelCount = channelCount
            this@RhythmAnalyzer.encoding = encoding
            hopSamples = rhythmHopSamples(sampleRateHz)
            reset()
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!active || sampleRateHz <= 0 || channelCount <= 0) return
            val bytesPerSample = when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
            val frameBytes = bytesPerSample * channelCount
            if (frameBytes <= 0) return
            val currentGeneration = generation.get()
            if (ringGeneration != currentGeneration) {
                ringGeneration = currentGeneration
                ringWrite = 0
                ringSize = 0
                samplesUntilHop = hopSamples
            }
            val input = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
            while (input.remaining() >= frameBytes) {
                var mixed = 0f
                repeat(channelCount) {
                    mixed += when (encoding) {
                        C.ENCODING_PCM_16BIT -> (input.short / 32768f).coerceIn(-1f, 1f)
                        else -> input.float.coerceIn(-1f, 1f)
                    }
                }
                appendSample(mixed / channelCount, currentGeneration)
            }
        }
    }

    private fun appendSample(sample: Float, currentGeneration: Int) {
        ring[ringWrite] = sample
        ringWrite = (ringWrite + 1) % ring.size
        if (ringSize < ring.size) ringSize++
        if (ringSize < ring.size) return
        if (--samplesUntilHop > 0) return
        samplesUntilHop = hopSamples
        scheduleAnalysis(currentGeneration)
    }

    private fun scheduleAnalysis(currentGeneration: Int) {
        if (!queued.compareAndSet(false, true)) return
        val oldest = (ringWrite - ring.size + ring.size) % ring.size
        repeat(ring.size) { index ->
            analysisInput[index] = ring[(oldest + index) % ring.size]
        }
        val rate = sampleRateHz
        try {
            analysisExecutor().execute {
                try {
                    if (!active || generation.get() != currentGeneration) return@execute
                    if (analysisGeneration != currentGeneration) {
                        analysis.reset()
                        analysisGeneration = currentGeneration
                        lastAnalysisAtNs = 0L
                    }
                    val now = System.nanoTime()
                    val dtMs = if (lastAnalysisAtNs == 0L) {
                        1000f / RHYTHM_TARGET_FPS
                    } else {
                        ((now - lastAnalysisAtNs) / 1_000_000f).coerceIn(1f, 200f)
                    }
                    lastAnalysisAtNs = now
                    val result = analysis.analyzePcm(analysisInput, rate, dtMs)
                    synchronized(frameLock) {
                        if (active && generation.get() == currentGeneration) _frames.value = result
                    }
                } finally {
                    queued.set(false)
                }
            }
        } catch (_: RuntimeException) {
            // Analysis is visual-only; executor rejection must not interrupt playback.
            queued.set(false)
        }
    }

    @Synchronized
    private fun analysisExecutor(): ExecutorService =
        executor?.takeUnless { it.isShutdown } ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "myMusic-rhythm").apply {
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
            }
        }.also { executor = it }

}
