package com.mytv.mymusic

import com.mytv.mymusic.data.RhythmIntensity
import com.mytv.mymusic.playback.RhythmFrame
import com.mytv.mymusic.ui.player.FlowFieldMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class FlowFieldMotionTest {
    @Test
    fun smoothingIsFrameRateIndependent() {
        val at60 = reactiveMotion().also { it.feed(LOUD); repeat(60) { _ -> it.step(1f / 60f) } }
        val at120 = reactiveMotion().also { it.feed(LOUD); repeat(120) { _ -> it.step(1f / 120f) } }

        assertEquals(at60.bass, at120.bass, 1e-4f)
        assertEquals(at60.mid, at120.mid, 1e-4f)
        assertEquals(at60.treble, at120.treble, 1e-4f)
        assertEquals(at60.loudness, at120.loudness, 1e-4f)
        assertEquals(at60.bloom, at120.bloom, 1e-4f)
        assertEquals(at60.run, at120.run, 1e-4f)
        for (i in 0 until 5) assertEquals(at60.phase(i), at120.phase(i), 5e-3)
        assertEquals(at60.angle, at120.angle, 5e-3)
        assertTrue("one second of loud bass should be close to its target", at60.bass > 0.75f)
    }

    @Test
    fun pauseSlowsToAStopWithoutJumpingBack() {
        val motion = reactiveMotion()
        repeat(120) { motion.feed(LOUD); motion.step(1f / 60f) }
        val beforePause = DoubleArray(5) { motion.phase(it) }
        assertTrue(beforePause.indices.any { abs(beforePause[it] - INITIAL_PHASES[it]) > 0.1 })

        motion.playing = false
        var previous = beforePause.copyOf()
        // Fastest possible phase step: base speed × Standard's max loudness gain × the fifth phase's factor.
        val maxStep = 2 * PI / 26 * 2.3 * (0.7 + 4 * 0.13) / 60
        repeat(90) {
            motion.feed(LOUD)
            motion.step(1f / 60f)
            for (i in 0 until 5) {
                val delta = motion.phase(i) - previous[i]
                assertTrue("phase $i moved backwards", delta >= 0.0)
                assertTrue("phase $i jumped by $delta", delta <= maxStep + 1e-9)
                previous[i] = motion.phase(i)
            }
        }
        assertTrue("run should be near 0 after 1.5 s, was ${motion.run}", motion.run < 0.06f)
        assertEquals("paused signals fall back to 0", 0f, motion.bass, 0.02f)

        val stopped = DoubleArray(5) { motion.phase(it) }
        repeat(600) { motion.step(1f / 60f) }
        assertTrue(motion.isSettled)
        assertEquals(0f, motion.run, 0f)
        assertEquals(0f, motion.bass, 0f)
        for (i in 0 until 5) {
            assertEquals("phase $i stays where it stopped", stopped[i], motion.phase(i), 0.05)
            assertTrue(abs(motion.phase(i) - INITIAL_PHASES[i]) > 0.1)
        }
    }

    @Test
    fun softNeverCreatesRipples() {
        val soft = reactiveMotion(RhythmIntensity.Soft)
        repeat(10) {
            soft.feed(KICK)
            soft.step(0.3f)
            assertEquals(0, soft.rippleCount)
        }
        soft.feed(KICK)
        soft.step(1f / 60f)
        assertTrue("kicks still bloom the blobs in Soft", soft.bloom > 0f)

        val standard = reactiveMotion(RhythmIntensity.Standard)
        standard.feed(KICK)
        standard.step(1f / 60f)
        assertEquals(1, standard.rippleCount)
        standard.feed(KICK)
        standard.feed(KICK)
        assertEquals("at most two ripples at once", 2, standard.rippleCount)
        repeat(60) { standard.step(1f / 60f) }
        assertEquals("ripples live 0.9 s", 0, standard.rippleCount)
    }

    @Test
    fun signalsStayZeroWhenNotReactiveOrPaused() {
        val ambient = FlowFieldMotion(moving = true).apply { reactive = false }
        val paused = reactiveMotion().apply { playing = false }
        for (motion in listOf(ambient, paused)) {
            repeat(60) {
                motion.feed(LOUD.copy(kick = true))
                motion.step(1f / 60f)
            }
            assertEquals(0f, motion.bass, 0f)
            assertEquals(0f, motion.mid, 0f)
            assertEquals(0f, motion.treble, 0f)
            assertEquals(0f, motion.loudness, 0f)
            assertEquals(0f, motion.bloom, 0f)
            assertEquals(0, motion.rippleCount)
        }
        assertFalse("ambient keeps flowing at its base speed", ambient.isSettled)
        assertTrue(abs(ambient.phase(0) - INITIAL_PHASES[0]) > 0.1)
    }

    @Test
    fun frameClockClampsLongGaps() {
        val motion = FlowFieldMotion(moving = true)
        motion.advanceTo(1_000_000_000L)
        assertEquals("first frame only records the time", INITIAL_PHASES[0], motion.phase(0), 0.0)

        motion.advanceTo(61_000_000_000L)
        val oneClampedStep = FlowFieldMotion(moving = true).apply { step(0.1f) }
        assertEquals(oneClampedStep.phase(0), motion.phase(0), 1e-9)

        motion.stopClock()
        motion.advanceTo(90_000_000_000L)
        assertEquals("restarted clock does not integrate the idle gap", oneClampedStep.phase(0), motion.phase(0), 1e-9)
    }

    private fun reactiveMotion(intensity: RhythmIntensity = RhythmIntensity.Standard) =
        FlowFieldMotion(moving = true).apply {
            reactive = true
            this.intensity = intensity
        }

    private companion object {
        val LOUD = RhythmFrame(bass = 0.8f, mid = 0.5f, treble = 0.3f, loudness = 0.6f)
        val KICK = RhythmFrame(bass = 0.5f, kick = true)
        val INITIAL_PHASES = doubleArrayOf(0.0, 1.7, 3.1, 4.4, 0.6)
    }
}
