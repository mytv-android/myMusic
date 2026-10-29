package com.mytv.mymusic

import com.mytv.mymusic.data.RhythmIntensity
import com.mytv.mymusic.playback.BeatGrid
import com.mytv.mymusic.playback.ONSET_DETECT_LAG_S
import com.mytv.mymusic.playback.RhythmAnalyzer
import com.mytv.mymusic.playback.RhythmFrame
import com.mytv.mymusic.ui.player.FlowFieldMotion
import com.mytv.mymusic.ui.player.FrameGate
import com.mytv.mymusic.ui.player.NO_FRAME_NS
import com.mytv.mymusic.ui.player.RhythmFeedReader
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
        fun play(fps: Int) = reactiveMotion().also { m ->
            m.feed(LOUD)
            repeat(fps * 2) { i -> m.beatAt((i + 1).toDouble() / fps, GRID_120); m.step(1f / fps) }
        }
        val at60 = play(60)
        val at120 = play(120)
        // 电视限 30 帧时也一样
        val at30 = play(30)
        for (other in listOf(at30, at120)) {
            assertEquals(at60.bass, other.bass, 1e-4f)
            assertEquals(at60.mid, other.mid, 1e-4f)
            assertEquals(at60.treble, other.treble, 1e-4f)
            assertEquals(at60.loudness, other.loudness, 1e-4f)
            assertEquals(at60.swell, other.swell, 1e-4f)
            assertEquals(at60.run, other.run, 1e-4f)
            // 拍子包络按拍点的真实时刻算，不按帧数
            assertEquals(at60.beat, other.beat, 1e-3f)
            assertEquals(at60.glow, other.glow, 1e-3f)
            assertEquals(at60.shove, other.shove, 1e-3f)
            assertEquals(at60.beats.count, other.beats.count)
            for (i in 0 until 5) assertEquals(at60.phase(i), other.phase(i), 2e-2)
            assertEquals(at60.angle, other.angle, 1e-2)
        }
        assertTrue("one second of loud bass should be close to its target", at60.bass > 0.75f)
        assertTrue("locked onto the grid within two seconds", at60.beats.count >= 2)
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
    fun beatsMoveShapesAndLocalLightNotTheWholeScreen() {
        // 光量的粗略代理：四团光的“不透明度 × 面积”（屏幕混合在低不透明度下近似相加）加上封面层和光晕。
        // 光晕约占屏幕的 4%（按光团同样的“半径²”口径），亮度和大小跟拍。
        // 128 BPM 的四拍：每拍低频冲到 1，拍间回落，网格锁住；按 10 帧/秒取样，看相邻两次取样的变化和整体起伏
        fun light(m: FlowFieldMotion): Float {
            val blobs = (0 until 4).sumOf { (m.blobAlpha(it) * m.blobRadius(it) * m.blobRadius(it)).toDouble() }.toFloat()
            val flow = intensityOf(m)
            val halo = (0.62f + flow.cover * m.swell + flow.glow * m.glow) * (1f + flow.halo * m.glow).let { it * it } * HALO_SHARE
            return blobs + m.coverAlpha * 0.3f + halo
        }
        fun run(intensity: RhythmIntensity, reactive: Boolean): Triple<Float, Float, Int> {
            val m = FlowFieldMotion(moving = true).apply {
                this.reactive = reactive
                this.intensity = intensity
            }
            val quiet = light(m)
            var last = quiet
            var maxStep = 0f
            var peak = quiet
            for (frame in 0 until 60 * 8) {
                val t = frame / 60.0
                val beat = (t * 128.0 / 60.0) % 1.0
                val bass = if (beat < 0.12) 1f else 0.35f
                m.feed(RhythmFrame(bass = bass, mid = 0.5f, treble = 0.4f, loudness = 0.8f))
                m.beatAt(t, GRID_128)
                m.step(1f / 60f)
                if (frame % 6 == 0) {
                    val now = light(m)
                    maxStep = maxOf(maxStep, abs(now - last) / quiet)
                    peak = maxOf(peak, now)
                    last = now
                }
            }
            return Triple(maxStep, (peak - quiet) / quiet, m.beats.count)
        }
        val (ambientStep, ambientRise, ambientBeats) = run(RhythmIntensity.Standard, reactive = false)
        val (softStep, softRise, softBeats) = run(RhythmIntensity.Soft, reactive = true)
        val (standardStep, standardRise, standardBeats) = run(RhythmIntensity.Standard, reactive = true)
        val (strongStep, strongRise, strongBeats) = run(RhythmIntensity.Strong, reactive = true)
        assertEquals(0f, ambientStep, 1e-6f)
        assertEquals(0f, ambientRise, 1e-6f)
        assertEquals(0, ambientBeats)
        // 8 秒 17 拍，锁上要零点几秒
        for (beats in listOf(softBeats, standardBeats, strongBeats)) assertTrue("beats $beats", beats in 14..17)
        // 标准档比柔和档活跃，但单拍的跳变和整体提亮都有上限。按真机标定（手机空闲页，同一段歌）：这个代理的取样跳变 ×16
        // 约等于屏幕平均亮度每 0.1 秒的变化（百分点），起伏 ×12 约等于去掉漂移后的起伏；上限对应约 3% 和约 5.5 个百分点。
        assertTrue("soft $softStep/$softRise vs standard $standardStep/$standardRise", standardStep > softStep * 1.5f && standardRise > softRise * 1.5f)
        // 强烈档多出来的只有光晕：整屏的跳变和起伏和标准档差不多，都在上限以内
        for ((step, rise) in listOf(standardStep to standardRise, strongStep to strongRise)) {
            assertTrue("step $step", step < 0.18f)
            assertTrue("rise $rise", rise < 0.45f)
        }
        assertTrue("strong $strongStep vs standard $standardStep", strongStep < standardStep * 1.25f)
    }

    @Test
    fun beatEnvelopesPeakOnTheBeatAndDecay() {
        val motion = reactiveMotion(RhythmIntensity.Strong)
        var t = 0.0
        // 网格要先锁一会儿（0.35 秒起），再看第 3 拍附近
        while (t < 1.4) {
            motion.beatAt(t, GRID_120)
            motion.step(1f / 120f)
            t += 1.0 / 120
        }
        var peakBeat = 0f
        var peakAt = 0.0
        while (t < 1.75) {
            motion.beatAt(t, GRID_120)
            motion.step(1f / 120f)
            if (motion.beat > peakBeat) {
                peakBeat = motion.beat
                peakAt = t
            }
            t += 1.0 / 120
        }
        // 拍点在 1.5 秒；拍前 50ms 起势，峰值落在拍点上
        assertEquals(1.5, peakAt, 1.0 / 60)
        assertTrue("peak $peakBeat", peakBeat > 0.55f)
        // 0.16 / 0.28 / 0.4 秒回落：越慢的包络此刻剩得越多
        assertTrue("${motion.beat} < ${motion.glow} < ${motion.shove}", motion.beat < motion.glow && motion.glow < motion.shove)
    }

    @Test
    fun shoveSpeedsTheFlowOnlyAroundBeats() {
        val still = reactiveMotion().also { m -> m.feed(LOUD); repeat(240) { m.step(1f / 60f) } }
        val pushed = reactiveMotion().also { m ->
            m.feed(LOUD)
            repeat(240) { i -> m.beatAt((i + 1) / 60.0, GRID_120); m.step(1f / 60f) }
        }
        val base = still.phase(0) - INITIAL_PHASES[0]
        val beat = pushed.phase(0) - INITIAL_PHASES[0]
        // 推一把的平均效果在 1.5–2.5 倍之间：拍上快，拍间回到原速
        assertTrue("pushed $beat vs $base", beat > base * 1.3 && beat < base * 2.5)
    }

    @Test
    fun shapeRippleFadesInAndOut() {
        assertEquals(0f, FlowFieldMotion.rippleAlpha(0f, 0.16f), 0f)
        val peak = (0..110).maxOf { FlowFieldMotion.rippleAlpha(it / 100f, 0.16f) }
        assertTrue("peak $peak", peak <= 0.16f && peak > 0.14f)
        assertTrue(FlowFieldMotion.rippleAlpha(0.04f, 0.16f) < FlowFieldMotion.rippleAlpha(0.08f, 0.16f))
        assertEquals(0f, FlowFieldMotion.rippleAlpha(FlowFieldMotion.RIPPLE_LIFE_S, 0.16f), 0f)
        assertEquals(0f, FlowFieldMotion.rippleGrow(0f), 0f)
        assertTrue(FlowFieldMotion.rippleGrow(1f) > 1f)
    }

    @Test
    fun ripplesFollowTheIntensity() {
        fun births(intensity: RhythmIntensity): List<Float> {
            val m = reactiveMotion(intensity)
            val born = mutableListOf<Float>()
            var maxCount = 0
            repeat(60 * 6) { i ->
                m.beatAt((i + 1) / 60.0, GRID_120)
                m.step(1f / 60f)
                maxCount = maxOf(maxCount, m.rippleCount)
                val newest = m.rippleCount - 1
                if (newest >= 0 && m.rippleAge(newest) <= 1f / 60f + 1e-6f) born += m.rippleAmplitude(newest)
            }
            assertTrue("at most three at once", maxCount <= FlowFieldMotion.MAX_RIPPLES)
            return born
        }
        assertEquals("soft never ripples", 0, births(RhythmIntensity.Soft).size)
        // 6 秒 12 拍、3 个小节首拍；锁上之前的第一拍不算
        val standard = births(RhythmIntensity.Standard)
        assertTrue("standard ripples on downbeats only: $standard", standard.size in 2..3 && standard.all { it == 0.16f })
        val strong = births(RhythmIntensity.Strong)
        assertTrue("strong ripples on every beat: ${strong.size}", strong.size in 10..12)
        assertEquals(strong.count { it == 0.16f }, standard.size)
        assertTrue(strong.count { it == 0.1f } >= 8)
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
        var t = 0.0
        repeat(120) {
            t += 1.0 / 60
            motion.feed(LOUD)
            motion.beatAt(t, GRID_120)
            motion.step(1f / 60f)
        }
        val beforePause = DoubleArray(5) { motion.phase(it) }
        assertTrue(beforePause.indices.any { abs(beforePause[it] - INITIAL_PHASES[it]) > 0.1 })

        motion.playing = false
        var previous = beforePause.copyOf()
        // 最快的一步：基础速度 × 标准档最大响度增益 × 满推一把 × 第五个相位的系数
        val maxStep = 2 * PI / 26 * 2.3 * 3.6 * (0.7 + 4 * 0.13) / 60
        repeat(90) {
            motion.feed(LOUD)
            // 暂停时听到的位置停在原处
            motion.beatAt(t, GRID_120)
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
        assertTrue("beat envelopes keep decaying while paused", motion.shove < 0.05f)

        val stopped = DoubleArray(5) { motion.phase(it) }
        repeat(600) { motion.step(1f / 60f) }
        assertTrue(motion.isSettled)
        assertEquals(0f, motion.run, 0f)
        assertEquals(0f, motion.bass, 0f)
        assertEquals(0f, motion.beat, 0f)
        for (i in 0 until 5) {
            assertEquals("phase $i stays where it stopped", stopped[i], motion.phase(i), 0.05)
            assertTrue(abs(motion.phase(i) - INITIAL_PHASES[i]) > 0.1)
        }
    }

    @Test
    fun signalsStayZeroWhenNotReactiveOrPaused() {
        val ambient = FlowFieldMotion(moving = true).apply { reactive = false }
        val paused = reactiveMotion().apply { playing = false }
        for (motion in listOf(ambient, paused)) {
            repeat(120) {
                motion.feed(LOUD)
                motion.beatAt((it + 1) / 60.0, GRID_120)
                motion.step(1f / 60f)
            }
            assertEquals(0f, motion.bass, 0f)
            assertEquals(0f, motion.mid, 0f)
            assertEquals(0f, motion.treble, 0f)
            assertEquals(0f, motion.loudness, 0f)
            assertEquals(0f, motion.beat, 0f)
            assertEquals(0f, motion.glow, 0f)
            assertEquals(0f, motion.shove, 0f)
            assertEquals(0, motion.beats.count)
            assertEquals(0, motion.rippleCount)
            assertEquals(0f, motion.lockedBpm, 0f)
        }
        assertFalse("ambient keeps flowing at its base speed", ambient.isSettled)
        assertTrue(abs(ambient.phase(0) - INITIAL_PHASES[0]) > 0.1)
    }

    @Test
    fun unknownPositionFiresNothing() {
        val motion = reactiveMotion()
        repeat(120) {
            motion.beatTimeS = Double.NaN
            motion.beatGrid = GRID_120
            motion.step(1f / 60f)
        }
        assertEquals(0, motion.beats.count)
        assertEquals(0f, motion.beat, 0f)
    }

    @Test
    fun lockedBpmNeedsAConfidentGrid() {
        val motion = reactiveMotion()
        repeat(120) { motion.beatAt((it + 1) / 60.0, GRID_120); motion.step(1f / 60f) }
        assertEquals(120f, motion.lockedBpm, 0.01f)
        // 换成一首还没起网格的歌：锁慢慢松开（1.5 秒回落），松开前没有网格也报 0
        motion.beatGrid = BeatGrid.NONE
        assertEquals(0f, motion.lockedBpm, 0f)
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
    fun readerFeedsLevelsOnlyOncePlayedAndKicksAheadOfTime() {
        val analyzer = RhythmAnalyzer()
        val reader = RhythmFeedReader(analyzer)
        val motion = reactiveMotion()
        // 还不知道听到哪儿：不取帧，跟拍时间是 NaN
        reader.feedUntil(0L, 0L, motion)
        assertTrue(motion.beatTimeS.isNaN())

        analyzer.clock.setRunning(true, 0L)
        analyzer.clock.onPosition(1_000_000L, 0L)
        // 分析器是全局的，打开播放页前已经数过 37 个鼓点：第一帧只记序号
        analyzer.timeline.push(RhythmFrame(ptsUs = 990_000L, kickCount = 37))
        analyzer.timeline.push(RhythmFrame(ptsUs = 1_000_000L, bass = 0.4f, kickCount = 37))
        // 拍前 50ms 以内的鼓点提前取走交给拍子时钟，电平不提前用
        analyzer.timeline.push(RhythmFrame(ptsUs = 1_040_000L, bass = 0.9f, kickCount = 38))
        analyzer.timeline.push(RhythmFrame(ptsUs = 1_400_000L, bass = 0.1f, kickCount = 39))
        reader.feedUntil(0L, 0L, motion)
        assertEquals(1.0, motion.beatTimeS, 1e-9)
        motion.step(1f / 60f)
        assertTrue("levels from the frame at 1.0 s: ${motion.bass}", motion.bass in 0.05f..0.4f * 0.3f)
        // 1.04 秒的鼓点（扣掉检测延迟是 1.01 秒）在拍前 50ms 以内，没有网格时按低频起拍起了一拍
        assertEquals(1, motion.beats.count)
        assertEquals(1.04 - ONSET_DETECT_LAG_S, motion.beats.lastBeatS, 1e-6)

        // 跟拍校准 +100ms：画面晚 100ms，1.4 秒的鼓点要等听到 1.45 秒（跟拍时间 1.35 秒）才取到、起拍
        reader.feedUntil(120_000_000L, 100_000L, motion)
        assertEquals(1.02, motion.beatTimeS, 1e-9)
        motion.step(1f / 60f)
        assertEquals(1, motion.beats.count)
        reader.feedUntil(450_000_000L, 100_000L, motion)
        assertEquals(1.35, motion.beatTimeS, 1e-9)
        motion.step(1f / 60f)
        assertEquals(2, motion.beats.count)
        assertEquals(1.4 - ONSET_DETECT_LAG_S, motion.beats.lastBeatS, 1e-6)

        // 往回跳：留着的帧作废，序号不倒退也不补放
        analyzer.timeline.clear()
        analyzer.clock.onPosition(200_000L, 300_000_000L)
        analyzer.timeline.push(RhythmFrame(ptsUs = 200_000L, bass = 0.2f, kickCount = 39))
        reader.feedUntil(300_000_000L, 0L, motion)
        assertEquals(0.2, motion.beatTimeS, 1e-9)
        motion.step(1f / 60f)
        assertEquals(2, motion.beats.count)
    }

    private fun reactiveMotion(intensity: RhythmIntensity = RhythmIntensity.Standard) =
        FlowFieldMotion(moving = true).apply {
            reactive = true
            this.intensity = intensity
        }

    private fun FlowFieldMotion.beatAt(tS: Double, grid: BeatGrid) {
        beatTimeS = tS
        beatGrid = grid
    }

    private fun intensityOf(m: FlowFieldMotion) = com.mytv.mymusic.ui.player.FlowIntensity.of(m.intensity)

    private companion object {
        val LOUD = RhythmFrame(bass = 0.8f, mid = 0.5f, treble = 0.3f, loudness = 0.6f)
        val INITIAL_PHASES = doubleArrayOf(0.0, 1.7, 3.1, 4.4, 0.6)

        /** 120 BPM、置信度 1、拍号 0 在 0 秒、首拍在拍号 0 上：1.5 秒是第 3 拍。 */
        val GRID_120 = BeatGrid(0.5, 0.0, 0L, 1f, 0, true, LongArray(0), FloatArray(0))
        val GRID_128 = BeatGrid(60.0 / 128.0, 0.0, 0L, 1f, 0, true, LongArray(0), FloatArray(0))
        const val HALO_SHARE = 0.04f
    }
}
