package com.mytv.mymusic

import com.mytv.mymusic.data.RhythmIntensity
import com.mytv.mymusic.playback.RhythmFrame
import com.mytv.mymusic.ui.player.FlowFieldMotion
import com.mytv.mymusic.ui.player.FrameGate
import com.mytv.mymusic.ui.player.NO_FRAME_NS
import com.mytv.mymusic.ui.player.backdropFrameDue
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
        // 电视限 30 帧时也一样
        val at30 = reactiveMotion().also { it.feed(LOUD); repeat(30) { _ -> it.step(1f / 30f) } }
        assertEquals(at60.bass, at30.bass, 1e-4f)
        assertEquals(at60.run, at30.run, 1e-4f)
        for (i in 0 until 5) assertEquals(at60.phase(i), at30.phase(i), 1e-2)
        assertEquals(at60.angle, at30.angle, 1e-2)

        assertEquals(at60.bass, at120.bass, 1e-4f)
        assertEquals(at60.mid, at120.mid, 1e-4f)
        assertEquals(at60.treble, at120.treble, 1e-4f)
        assertEquals(at60.loudness, at120.loudness, 1e-4f)
        assertEquals(at60.bloom, at120.bloom, 1e-4f)
        assertEquals(at60.run, at120.run, 1e-4f)
        for (i in 0 until 5) assertEquals(at60.phase(i), at120.phase(i), 5e-3)
        assertEquals(at60.angle, at120.angle, 5e-3)
        assertTrue("one second of loud bass should be close to its target", at60.bass > 0.75f)
        assertEquals(at60.swell, at30.swell, 1e-4f)
        assertEquals(at60.swell, at120.swell, 1e-4f)
    }

    @Test
    fun swellIsASlowEnvelopeOfTheBass() {
        val motion = reactiveMotion()
        motion.feed(LOUD)
        // 0.1 秒：逐帧低频已经起来大半，慢包络才走了三成左右（0.3 秒起音）
        repeat(6) { motion.step(1f / 60f) }
        assertTrue("bass ${motion.bass}", motion.bass > 0.55f)
        assertTrue("swell ${motion.swell}", motion.swell in 0.2f..0.3f)
        repeat(120) { motion.step(1f / 60f) }
        assertEquals(0.8f, motion.swell, 0.05f)
        // 安静下来：1 秒回落，比低频的 0.38 秒慢
        motion.feed(RhythmFrame())
        repeat(30) { motion.step(1f / 60f) }
        assertTrue("bass ${motion.bass} swell ${motion.swell}", motion.swell > motion.bass + 0.15f)
        repeat(600) { motion.step(1f / 60f) }
        assertEquals(0f, motion.swell, 0.01f)
    }

    @Test
    fun standardStaysWithinTheBrightnessBudgetAndAboveSoft() {
        // 光量的粗略代理：四团光的“不透明度 × 面积”（屏幕混合在低不透明度下近似相加）加上封面层不透明度。
        // 128 BPM 的四拍：每拍低频冲到 1、鼓点一次，拍间回落；按 10 帧/秒取样，看相邻两次取样的变化和整体起伏
        fun light(m: FlowFieldMotion) =
            (0 until 4).sumOf { (m.blobAlpha(it) * m.blobRadius(it) * m.blobRadius(it)).toDouble() }.toFloat() + m.coverAlpha * 0.3f
        fun run(intensity: RhythmIntensity, reactive: Boolean): Pair<Float, Float> {
            val m = FlowFieldMotion(moving = true).apply {
                this.reactive = reactive
                this.intensity = intensity
                feed(RhythmFrame())
            }
            val quiet = light(m)
            var kicks = 0
            var last = quiet
            var maxStep = 0f
            var peak = quiet
            for (frame in 0 until 60 * 8) {
                val t = frame / 60f
                val beat = (t * 128f / 60f) % 1f
                val bass = if (beat < 0.12f) 1f else 0.35f
                if (beat < 1f / 60f * 128f / 60f) kicks++
                m.feed(RhythmFrame(bass = bass, mid = 0.5f, treble = 0.4f, loudness = 0.8f, kickCount = kicks))
                m.step(1f / 60f)
                if (frame % 6 == 0) {
                    val now = light(m)
                    maxStep = maxOf(maxStep, abs(now - last) / quiet)
                    peak = maxOf(peak, now)
                    last = now
                }
            }
            return maxStep to (peak - quiet) / quiet
        }
        val (ambientStep, ambientRise) = run(RhythmIntensity.Standard, reactive = false)
        val (softStep, softRise) = run(RhythmIntensity.Soft, reactive = true)
        val (standardStep, standardRise) = run(RhythmIntensity.Standard, reactive = true)
        assertEquals(0f, ambientStep, 1e-6f)
        assertEquals(0f, ambientRise, 1e-6f)
        // 标准档比柔和档活跃，但单拍的跳变和整体提亮都有上限。按真机标定（手机空闲页，同一段歌）：这个代理的取样跳变 ×16
        // 约等于屏幕平均亮度每 0.1 秒的变化（百分点），起伏 ×12 约等于去掉漂移后的起伏；上限对应约 3% 和约 5.5 个百分点。
        // 调整前的标准档是 0.33 / 0.64（真机 5.3% / 7.9 点）
        assertTrue("soft $softStep/$softRise vs standard $standardStep/$standardRise", standardStep > softStep * 1.5f && standardRise > softRise * 1.5f)
        assertTrue("standard step $standardStep", standardStep < 0.18f)
        assertTrue("standard rise $standardRise", standardRise < 0.45f)
    }

    @Test
    fun rippleFadesInInsteadOfAppearingAtFullStrength() {
        assertEquals(0f, FlowFieldMotion.rippleAlpha(0f), 0f)
        val peak = (0..90).maxOf { FlowFieldMotion.rippleAlpha(it / 100f) }
        assertTrue("peak $peak", peak <= 0.06f && peak > 0.045f)
        assertTrue(FlowFieldMotion.rippleAlpha(0.06f) < FlowFieldMotion.rippleAlpha(0.12f))
        assertEquals(0f, FlowFieldMotion.rippleAlpha(FlowFieldMotion.RIPPLE_LIFE_S), 0f)
    }

    @Test
    fun sharedFrameGateKeepsEveryLoopOnTheSameVsyncs() {
        val gate = FrameGate(1_000_000_000L / 30)
        val period = 1_000_000_000L / 60
        val drawn = mutableListOf<Int>()
        for (i in 0 until 120) {
            val now = 5_000_000L + i * period
            // 同一帧里三个循环先后来问，答案必须一样；第一个问的那个不一定是同一个
            val answers = List(3) { gate.due(now) }
            assertTrue(answers.all { it == answers[0] })
            if (answers[0]) drawn += i
        }
        assertEquals(60, drawn.size)
        assertTrue(drawn.zipWithNext().all { (a, b) -> b - a == 2 })
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
            soft.feed(kick(it + 1))
            soft.step(0.3f)
            assertEquals(0, soft.rippleCount)
        }
        soft.feed(kick(11))
        soft.step(1f / 60f)
        assertTrue("kicks still bloom the blobs in Soft", soft.bloom > 0f)

        val standard = reactiveMotion(RhythmIntensity.Standard)
        standard.feed(kick(1))
        standard.step(1f / 60f)
        assertEquals(1, standard.rippleCount)
        standard.feed(kick(2))
        standard.feed(kick(3))
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
                motion.feed(LOUD.copy(kick = true, kickCount = it + 1))
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

    @Test
    fun kicksFireWhenTheCountAdvancesEvenIfFramesWereMerged() {
        val motion = reactiveMotion()
        // 主线程卡了一下，StateFlow 把中间带鼓点的帧合并掉了：序号已经从 0 跳到 2，仍然算一次
        motion.feed(RhythmFrame(bass = 0.5f, kickCount = 2))
        assertEquals(1, motion.rippleCount)
        // 同一序号再收到（包括分析器重置后发出的空帧）不再触发；单帧的 kick 标记本身不算数
        motion.feed(RhythmFrame(kickCount = 2))
        motion.feed(RhythmFrame(bass = 0.5f, kick = true, kickCount = 2))
        assertEquals(1, motion.rippleCount)
        motion.feed(kick(3))
        assertEquals(2, motion.rippleCount)
    }

    @Test
    fun firstFrameAndFramesWhileNotLiveOnlyRecordTheCount() {
        // 分析器是全局的，打开播放页前已经数过 37 个鼓点：第一帧不能当成新鼓点
        val fresh = FlowFieldMotion(moving = true).apply { reactive = true }
        fresh.feed(RhythmFrame(kickCount = 37))
        fresh.step(1f / 60f)
        assertEquals(0, fresh.rippleCount)
        assertEquals(0f, fresh.bloom, 0f)
        fresh.feed(kick(38))
        assertEquals(1, fresh.rippleCount)

        // 暂停时收到的鼓点只记序号，恢复播放后不会补放
        val paused = reactiveMotion().apply { playing = false }
        paused.feed(kick(1))
        paused.playing = true
        paused.feed(kick(1))
        assertEquals(0, paused.rippleCount)
        paused.feed(kick(2))
        assertEquals(1, paused.rippleCount)
    }

    @Test
    fun tvFrameCapDrawsEveryOtherVsyncAt60HzDespiteJitter() {
        val cap = 1_000_000_000L / 30
        assertTrue("the first frame always draws", backdropFrameDue(5L, NO_FRAME_NS, cap))
        assertTrue("no cap draws every frame", backdropFrameDue(1L, 0L, 0L))

        fun drawn(hz: Int, jitterNanos: Long): List<Long> {
            val period = 1_000_000_000L / hz
            var last = NO_FRAME_NS
            val frames = mutableListOf<Long>()
            for (i in 0 until hz * 2) {
                // 垂直同步时间戳前后抖动：每两帧换一次方向，正好让画过的那帧偏晚、下一个候选帧偏早
                val now = i * period + if ((i / 2) % 2 == 0) jitterNanos else -jitterNanos
                if (backdropFrameDue(now, last, cap)) {
                    frames += i.toLong()
                    last = now
                }
            }
            return frames
        }
        val at60 = drawn(60, 1_500_000L)
        assertEquals(60, at60.size)
        assertTrue("60Hz draws every other vsync", at60.zipWithNext().all { (a, b) -> b - a == 2L })
        assertEquals(60, drawn(120, 1_000_000L).size)
        assertEquals(50, drawn(50, 1_000_000L).size)
        assertEquals("30Hz panels are not throttled further", 60, drawn(30, 1_000_000L).size)
    }

    private fun reactiveMotion(intensity: RhythmIntensity = RhythmIntensity.Standard) =
        FlowFieldMotion(moving = true).apply {
            reactive = true
            this.intensity = intensity
            // 第一帧只记鼓点序号
            feed(RhythmFrame())
        }

    private fun kick(count: Int) = RhythmFrame(bass = 0.5f, kick = true, kickCount = count)

    private companion object {
        val LOUD = RhythmFrame(bass = 0.8f, mid = 0.5f, treble = 0.3f, loudness = 0.6f)
        val INITIAL_PHASES = doubleArrayOf(0.0, 1.7, 3.1, 4.4, 0.6)
    }
}
