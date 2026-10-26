package com.mytv.mymusic

import com.mytv.mymusic.playback.AgcTracker
import com.mytv.mymusic.playback.OnsetDetector
import com.mytv.mymusic.playback.RHYTHM_FFT_SIZE
import com.mytv.mymusic.playback.RealFft
import com.mytv.mymusic.playback.RhythmAnalysis
import com.mytv.mymusic.playback.rhythmBandEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

class RhythmAnalysisTest {
    @Test
    fun fftPlacesSingleSineAtExpectedBin() {
        val sampleRate = 48_000.0
        val frequency = 1_500.0
        val real = FloatArray(RHYTHM_FFT_SIZE)
        val imag = FloatArray(RHYTHM_FFT_SIZE)
        for (i in real.indices) {
            val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (RHYTHM_FFT_SIZE - 1))
            real[i] = (sin(2.0 * PI * frequency * i / sampleRate) * hann).toFloat()
        }

        RealFft().transform(real, imag)

        var peak = 0
        for (i in 1 until RHYTHM_FFT_SIZE / 2) {
            if (magnitudeSquared(real, imag, i) > magnitudeSquared(real, imag, peak)) peak = i
        }
        assertEquals(64, peak)
    }

    @Test
    fun agcConstantSignalDoesNotFlicker() {
        val tracker = AgcTracker()
        val amplitudes = FloatArray(120) { tracker.push(200f) }

        assertTrue(amplitudes.all { it == 0f })
        assertEquals(24f, tracker.span, 0f)
    }

    @Test
    fun agcExpandsNarrowDynamicRange() {
        val tracker = AgcTracker()
        repeat(24) { tracker.push(100f) }

        val low = tracker.push(100f)
        val high = tracker.push(116f)

        assertEquals(0f, low, 0f)
        assertTrue("AGC output did not expand the 16-level input range", high - low > 0.6f)
    }

    @Test
    fun agcSilenceDoesNotMoveBaseline() {
        val tracker = AgcTracker()
        tracker.push(80f)
        tracker.push(140f)
        tracker.push(95f)
        val min = tracker.min
        val max = tracker.max
        val span = tracker.span

        assertEquals(0f, tracker.push(0f), 0f)
        assertEquals(0f, tracker.push(11.99f), 0f)
        assertEquals(0f, tracker.push(Float.NaN), 0f)
        assertEquals(min, tracker.min, 0f)
        assertEquals(max, tracker.max, 0f)
        assertEquals(span, tracker.span, 0f)
    }

    @Test
    fun onsetConstantSpectrumDoesNotFalseTrigger() {
        val detector = OnsetDetector(bins = 8)
        val spectrum = FloatArray(8) { 180f }

        repeat(60) { assertFalse(detector.push(spectrum, DT).onset) }
    }

    @Test
    fun onsetPureDescendingEdgesProduceNoFlux() {
        val detector = OnsetDetector(bins = 8)
        detector.push(FloatArray(8) { 200f }, DT)

        for (step in 1..20) {
            val result = detector.push(FloatArray(8) { 200f - step * 5f }, DT)
            assertEquals(0f, result.flux, 0f)
            assertFalse(result.onset)
        }
    }

    @Test
    fun onsetDetects120BpmClickTrackWithoutLatency() {
        val baseline = FloatArray(8) { 10f }
        val click = FloatArray(8) { 110f }
        val detector = warmedDetector(baseline)
        val detectedFrames = mutableListOf<Int>()

        // 30 fps, one click every 15 frames = two clicks/second = 120 BPM.
        for (frame in 0 until 120) {
            val spectrum = if (frame % 15 == 0) click else baseline
            if (detector.push(spectrum, DT).onset) detectedFrames += frame
        }

        val expectedFrames = (0 until 120 step 15).toList()
        assertEquals(8, detectedFrames.size)
        assertEquals(expectedFrames, detectedFrames)
        for (i in expectedFrames.indices) {
            val latencyMs = (detectedFrames[i] - expectedFrames[i]) * DT
            assertTrue(latencyMs >= 0f && latencyMs <= DT)
        }
    }

    @Test
    fun analysisIncludesHighFrequencyTransientsInOnsetDetection() {
        val analysis = RhythmAnalysis()
        val baseline = FloatArray(RHYTHM_FFT_SIZE / 2) { 10f }
        repeat(9) { analysis.analyzeSpectrum(baseline, 44_100, DT) }
        val transient = baseline.copyOf().also { it[200] = 200f }

        assertTrue(analysis.analyzeSpectrum(transient, 44_100, DT).beat)
    }

    @Test
    fun onsetHonors110MsRefractory() {
        val detector = warmedDetector(FloatArray(8))

        assertTrue(detector.push(FloatArray(8) { 100f }, DT).onset)
        assertFalse(detector.push(FloatArray(8) { 200f }, 50f).onset)
        assertTrue(detector.push(FloatArray(8) { 300f }, 61f).onset)
    }

    @Test
    fun pulseDecaysAt100And300Ms() {
        val detector = warmedDetector(FloatArray(8))
        val click = FloatArray(8) { 100f }
        val onset = detector.push(click, DT)
        assertTrue(onset.onset)
        assertEquals(1f, onset.pulse, 0f)

        val after100Ms = detector.push(click, 100f)
        assertEquals(exp(-100f / 125f), after100Ms.pulse, 0.0001f)
        val after300Ms = detector.push(click, 200f)
        assertEquals(exp(-300f / 125f), after300Ms.pulse, 0.0001f)
        assertTrue(after300Ms.pulse < 0.1f)
    }

    @Test
    fun productionBandBoundariesFollowPcmSampleRate() {
        val bins = RHYTHM_FFT_SIZE / 2
        assertEquals(30, rhythmBandEnd(690f, bins, 48_000))
        assertEquals(171, rhythmBandEnd(4_000f, bins, 48_000))
        assertEquals(598, rhythmBandEnd(14_000f, bins, 48_000))
        assertEquals(bins, rhythmBandEnd(14_000f, bins, 8_000))
    }

    @Test
    fun spectrumBandsUseReferenceEnergyWeights() {
        val analysis = RhythmAnalysis()
        // At 16 kHz four bins are 2 kHz wide: bass=[0], mid=[1], treble=[2, 3].
        analysis.analyzeSpectrum(floatArrayOf(60f, 120f, 180f, 240f), 16_000, DT)

        assertEquals(110f, analysis.lastRawEnergy, 0.0001f)
    }

    @Test
    fun energyUsesSoftBassAgcAttackAndRelease() {
        val analysis = RhythmAnalysis()
        val low = floatArrayOf(100f, 0f, 0f, 0f)
        val high = floatArrayOf(116f, 0f, 0f, 0f)
        analysis.analyzeSpectrum(low, 16_000, DT)

        val attack = analysis.analyzeSpectrum(high, 16_000, DT)
        assertTrue(attack.bass > 0f)
        assertEquals(attack.bass * 0.62f, attack.energy, 0.0001f)

        val release = analysis.analyzeSpectrum(low, 16_000, DT)
        assertEquals(0f, release.bass, 0f)
        assertEquals(attack.energy * (1f - 0.13f), release.energy, 0.0001f)
    }

    @Test
    fun pcmValidatesInputLengthAndSampleRate() {
        val analysis = RhythmAnalysis()
        assertIllegalArgument { analysis.analyzePcm(FloatArray(1_023), 44_100) }
        assertIllegalArgument { analysis.analyzePcm(FloatArray(RHYTHM_FFT_SIZE), 0) }
    }

    private fun warmedDetector(spectrum: FloatArray): OnsetDetector {
        val detector = OnsetDetector(bins = spectrum.size)
        detector.push(spectrum, DT)
        repeat(8) { detector.push(spectrum, DT) }
        return detector
    }

    private fun magnitudeSquared(real: FloatArray, imag: FloatArray, index: Int): Float {
        return real[index] * real[index] + imag[index] * imag[index]
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private companion object {
        const val DT = 1000f / 30f
    }
}
