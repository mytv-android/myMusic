// AGC and spectral-flux onset detection adapted from ijoji42/YesPlayMusic (MIT).
// Upstream: https://github.com/ijoji42/YesPlayMusic
package com.mytv.mymusic.playback

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val RHYTHM_FFT_SIZE = 2048

data class RhythmFrame(
    val energy: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val pulse: Float = 0f,
    val beat: Boolean = false,
    /** 整个分析窗口的 RMS 响度，-42 dBFS → 0、-8 dBFS → 1；逐帧原始值，平滑交给界面。 */
    val loudness: Float = 0f,
    /** 只看 0–690 Hz 的起拍：镲片和人声咬字不算鼓点。只表示这一帧分析出了鼓点，界面按 [kickCount] 判断。 */
    val kick: Boolean = false,
    /**
     * 到这一帧为止发布过的鼓点数，只增不减，由 [RhythmAnalyzer] 填写。StateFlow 会合并来不及收的帧，
     * 主线程卡顿超过一个分析帧时单帧的 [kick] 会丢；按序号前进判断就不会漏。重置和切代时保持不变。
     */
    val kickCount: Int = 0,
    /**
     * 分析窗口末尾那个样本的媒体时刻（微秒，渲染器时间轴，和 [PlaybackClock] 同一条轴），由 [RhythmAnalyzer] 填写。
     * 界面等耳朵听到这个位置才用这一帧；分析比耳朵早一个 AudioTrack 缓冲（约半秒）。
     */
    val ptsUs: Long = 0L,
    /** 0–690 Hz 的谱通量（只算变强的部分），按频点数平均；给节拍跟踪用。 */
    val fluxBass: Float = 0f,
    /** 690–4000 Hz 的谱通量，按频点数平均：军鼓、拍手那一路。中频频点比低频多得多，不平均的话拍手会比底鼓还“响”。 */
    val fluxMid: Float = 0f,
)

class RhythmAnalysis {
    private val fft = RealFft()
    private val real = FloatArray(RHYTHM_FFT_SIZE)
    private val imag = FloatArray(RHYTHM_FFT_SIZE)
    private val spectrum = FloatArray(RHYTHM_FFT_SIZE / 2)
    private val window = FloatArray(RHYTHM_FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (RHYTHM_FFT_SIZE - 1))).toFloat()
    }

    private val bassAgc = AgcTracker()
    private val midAgc = AgcTracker()
    private val trebleAgc = AgcTracker()
    private val onset = OnsetDetector(bins = RHYTHM_FFT_SIZE / 2)
    private val kickOnset = OnsetDetector(bins = RHYTHM_FFT_SIZE / 2, refractoryMs = KICK_REFRACTORY_MS)
    private val midFlux = BandFlux()
    private var smoothEnergy = 0f

    /** Raw all-band level, retained for diagnostics; the soft preset drives energy from bass. */
    internal var lastRawEnergy = 0f
        private set

    fun reset() {
        bassAgc.reset()
        midAgc.reset()
        trebleAgc.reset()
        onset.reset()
        kickOnset.reset()
        midFlux.reset()
        smoothEnergy = 0f
        lastRawEnergy = 0f
    }

    fun analyzePcm(
        samples: FloatArray,
        sampleRateHz: Int,
        dtMs: Float = 1000f / 30f,
    ): RhythmFrame {
        require(samples.size == RHYTHM_FFT_SIZE) { "Expected exactly $RHYTHM_FFT_SIZE PCM samples" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }

        var squares = 0f
        for (i in samples.indices) {
            val sample = samples[i]
            squares += sample * sample
            real[i] = sample * window[i]
            imag[i] = 0f
        }
        fft.transform(real, imag)

        for (i in spectrum.indices) {
            val magnitude = 2f * hypot(real[i], imag[i]) / RHYTHM_FFT_SIZE
            val db = 20f * log10(magnitude.coerceAtLeast(1e-12f))
            spectrum[i] = ((db - MIN_DB) / (MAX_DB - MIN_DB) * 255f).coerceIn(0f, 255f)
        }
        return analyzeSpectrum(spectrum, sampleRateHz, dtMs, rhythmLoudness(sqrt(squares / samples.size)))
    }

    internal fun analyzeSpectrum(
        spectrum: FloatArray,
        sampleRateHz: Int,
        dtMs: Float,
        loudness: Float = 0f,
    ): RhythmFrame {
        require(spectrum.isNotEmpty()) { "Spectrum must not be empty" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }

        val bassEnd = rhythmBandEnd(690f, spectrum.size, sampleRateHz)
        val midEnd = rhythmBandEnd(4_000f, spectrum.size, sampleRateHz)
        val trebleEnd = rhythmBandEnd(14_000f, spectrum.size, sampleRateHz)
        // Convert the 14 kHz cutoff instead of reusing the upstream analyser's bin count.
        val onsetResult = onset.push(spectrum, dtMs, binLimit = trebleEnd)
        val kickResult = kickOnset.push(spectrum, dtMs, sensitivity = KICK_SENSITIVITY, binLimit = bassEnd)
        val fluxMid = midFlux.push(spectrum, bassEnd, midEnd)

        val rawBass = average(spectrum, 0, bassEnd)
        val rawMid = average(spectrum, bassEnd, midEnd)
        val rawTreble = average(spectrum, midEnd, trebleEnd)
        lastRawEnergy = (rawBass * 1.4f + rawMid + rawTreble * 0.6f) / 3f

        val bass = bassAgc.push(rawBass)
        val mid = midAgc.push(rawMid)
        val treble = trebleAgc.push(rawTreble)

        val coefficient = if (bass > smoothEnergy) ENERGY_ATTACK else ENERGY_RELEASE
        smoothEnergy += (bass - smoothEnergy) * coefficient

        return RhythmFrame(
            energy = smoothEnergy,
            bass = bass,
            mid = mid,
            treble = treble,
            pulse = onsetResult.pulse,
            beat = onsetResult.onset,
            loudness = loudness,
            kick = kickResult.onset,
            fluxBass = if (bassEnd > 0) kickResult.flux / bassEnd else 0f,
            fluxMid = fluxMid,
        )
    }

    private fun average(values: FloatArray, from: Int, to: Int): Float {
        if (to <= from) return 0f
        var sum = 0f
        for (i in from until to) sum += values[i]
        return sum / (to - from)
    }
}

/** 窗口 RMS（时域、未加窗）换算成 0..1 的响度。 */
internal fun rhythmLoudness(rms: Float): Float {
    val db = 20f * log10(rms.coerceAtLeast(1e-6f))
    return ((db - LOUDNESS_FLOOR_DB) / (LOUDNESS_CEIL_DB - LOUDNESS_FLOOR_DB)).coerceIn(0f, 1f)
}

internal fun rhythmBandEnd(frequencyHz: Float, bins: Int, sampleRateHz: Int): Int {
    val binWidth = sampleRateHz.toDouble() / (bins * 2)
    return minOf(bins, ceil(frequencyHz / binWidth).toInt())
}

/** In-place radix-2 FFT; real and imag are transformed together. */
internal class RealFft(private val size: Int = RHYTHM_FFT_SIZE) {
    private val reversed: IntArray
    private val cosTable = FloatArray(size / 2) { i -> cos(-2.0 * PI * i / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { i -> sin(-2.0 * PI * i / size).toFloat() }

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT size must be a power of two" }
        reversed = IntArray(size)
        var value = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while ((value and bit) != 0) {
                value = value xor bit
                bit = bit shr 1
            }
            value = value xor bit
            reversed[i] = value
        }
    }

    fun transform(real: FloatArray, imag: FloatArray) {
        require(real.size == size && imag.size == size) { "Input arrays must match FFT size" }

        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }

        var length = 2
        while (length <= size) {
            val half = length / 2
            val tableStep = size / length
            var base = 0
            while (base < size) {
                for (j in 0 until half) {
                    val even = base + j
                    val odd = even + half
                    val tableIndex = j * tableStep
                    val wr = cosTable[tableIndex]
                    val wi = sinTable[tableIndex]
                    val oddReal = real[odd]
                    val oddImag = imag[odd]
                    val productReal = oddReal * wr - oddImag * wi
                    val productImag = oddReal * wi + oddImag * wr
                    val evenReal = real[even]
                    val evenImag = imag[even]
                    real[even] = evenReal + productReal
                    imag[even] = evenImag + productImag
                    real[odd] = evenReal - productReal
                    imag[odd] = evenImag - productImag
                }
                base += length
            }
            length = length shl 1
        }
    }
}

internal class AgcTracker {
    internal var min = 0f
        private set
    internal var max = 0f
        private set
    internal var span = AGC_FLOOR_SPAN
        private set
    private var ready = false

    fun reset() {
        min = 0f
        max = 0f
        span = AGC_FLOOR_SPAN
        ready = false
    }

    fun push(value: Float): Float {
        // Silence must not teach the tracker a new baseline.
        if (!value.isFinite() || value < AGC_SILENCE) return 0f
        update(value.coerceIn(0f, 255f))
        val normalized = ((value - min) / span).coerceIn(0f, 1f)
        return normalized.pow(AGC_GAMMA)
    }

    private fun update(value: Float) {
        if (!ready) {
            min = value
            max = value + AGC_FLOOR_SPAN
            span = AGC_FLOOR_SPAN
            ready = true
            return
        }

        if (value > max) max += (value - max) * AGC_MAX_RISE
        else max -= (max - value) * AGC_MAX_FALL

        if (value < min) min += (value - min) * AGC_MIN_FALL
        else min += (value - min) * AGC_MIN_RISE

        min = min.coerceIn(0f, 255f - AGC_FLOOR_SPAN)
        span = maxOf(AGC_FLOOR_SPAN, max - min)
        max = minOf(255f, min + span)
    }
}

/** 一段频点的谱通量（只算变强的部分），按频点数平均。第一帧和频段变了的那一帧没有上一帧可比，记 0。 */
internal class BandFlux {
    private var previous: FloatArray? = null
    private var from = -1

    fun reset() {
        previous = null
        from = -1
    }

    fun push(spectrum: FloatArray, from: Int, to: Int): Float {
        val count = minOf(to, spectrum.size) - from
        if (count <= 0) return 0f
        val old = previous
        if (old == null || old.size != count || this.from != from) {
            previous = spectrum.copyOfRange(from, from + count)
            this.from = from
            return 0f
        }
        var flux = 0f
        for (i in 0 until count) {
            val difference = spectrum[from + i] - old[i]
            if (difference > 0f) flux += difference
            old[i] = spectrum[from + i]
        }
        return flux / count
    }
}

internal data class OnsetResult(
    val onset: Boolean,
    val flux: Float,
    val threshold: Float,
    val pulse: Float,
)

internal class OnsetDetector(
    private val bins: Int = ONSET_BINS,
    private val refractoryMs: Float = ONSET_REFRACTORY_MS,
) {
    private data class FluxSample(val timeMs: Float, val flux: Float)

    private var previous: FloatArray? = null
    private var envelope = 0f
    private var clockMs = 0f
    private var lastOnsetMs = Float.NEGATIVE_INFINITY
    private val history = mutableListOf<FluxSample>()

    fun reset() {
        previous = null
        envelope = 0f
        clockMs = 0f
        lastOnsetMs = Float.NEGATIVE_INFINITY
        history.clear()
    }

    fun push(
        spectrum: FloatArray,
        dtMs: Float,
        sensitivity: Float = 55f,
        binLimit: Int = bins,
    ): OnsetResult {
        val dt = if (dtMs.isFinite() && dtMs != 0f) {
            dtMs.coerceIn(1f, 500f)
        } else {
            1000f / 30f
        }
        clockMs += dt
        envelope *= exp(-dt / ONSET_RELEASE_MS)

        if (spectrum.isEmpty()) {
            previous = null
            return OnsetResult(false, 0f, 0f, envelope)
        }

        val count = minOf(bins.coerceAtLeast(1), binLimit.coerceAtLeast(1), spectrum.size)
        val old = previous
        if (old == null || old.size != count) {
            previous = spectrum.copyOf(count)
            return OnsetResult(false, 0f, 0f, envelope)
        }

        var flux = 0f
        for (i in 0 until count) {
            val difference = spectrum[i] - old[i]
            if (difference > 0f) flux += difference
            old[i] = spectrum[i]
        }

        val historyFloor = clockMs - ONSET_WINDOW_MS
        while (history.isNotEmpty() && history[0].timeMs <= historyFloor) history.removeAt(0)

        var mean = 0f
        for (sample in history) mean += sample.flux
        if (history.isNotEmpty()) mean /= history.size
        var variance = 0f
        for (sample in history) {
            val difference = sample.flux - mean
            variance += difference * difference
        }
        val std = if (history.isEmpty()) 0f else sqrt(variance / history.size)
        val clampedSensitivity = if (sensitivity.isFinite()) sensitivity.coerceIn(0f, 100f) else 0f
        val k = 2f - clampedSensitivity / 100f * 1.2f
        val threshold = maxOf(ONSET_MIN_FLUX, mean + k * std, mean * ONSET_JUMP)

        // History excludes this frame so a large transient cannot raise its own threshold.
        val detected = history.size >= ONSET_MIN_HISTORY &&
            flux >= ONSET_MIN_FLUX &&
            flux > threshold &&
            clockMs - lastOnsetMs >= refractoryMs
        if (detected) {
            lastOnsetMs = clockMs
            envelope = 1f
        }
        history += FluxSample(clockMs, flux)
        return OnsetResult(detected, flux, threshold, envelope)
    }
}

private const val MIN_DB = -100f
private const val MAX_DB = -30f
private const val ENERGY_ATTACK = 0.62f
private const val ENERGY_RELEASE = 0.13f

private const val AGC_FLOOR_SPAN = 24f
private const val AGC_MAX_RISE = 0.35f
private const val AGC_MAX_FALL = 0.0008f
private const val AGC_MIN_FALL = 0.15f
private const val AGC_MIN_RISE = 0.03f
private const val AGC_SILENCE = 12f
private const val AGC_GAMMA = 0.75f

private const val ONSET_BINS = 80
private const val ONSET_WINDOW_MS = 1_000f
private const val ONSET_REFRACTORY_MS = 110f
private const val ONSET_RELEASE_MS = 125f
private const val ONSET_MIN_FLUX = 40f
private const val ONSET_MIN_HISTORY = 8
private const val ONSET_JUMP = 1.6f

// 鼓点只看低频；不应期 280 ms，最快每秒约 3.5 次，灵敏度也比全频低一档
private const val KICK_REFRACTORY_MS = 280f
private const val KICK_SENSITIVITY = 45f

private const val LOUDNESS_FLOOR_DB = -42f
private const val LOUDNESS_CEIL_DB = -8f
