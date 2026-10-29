package com.mytv.mymusic.ui.poster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallEntranceTest {
    private val p = 56f

    @Test
    fun delayGrowsByStepAndCaps() {
        assertEquals(0f, WallEntrance.delayForSteps(0f), 1e-6f)
        assertEquals(0f, WallEntrance.delayForSteps(-3f), 1e-6f)
        assertEquals(0.15f, WallEntrance.delayForSteps(5f), 1e-6f)
        assertEquals(0.34f, WallEntrance.delayForSteps(40f), 1e-6f)
    }

    @Test
    fun openWaveStartsAtTheScreenTopLeft() {
        val wave = WallWave.Open(WallPoint(100f, -50f), WaveStyle.Full)
        // 正好在屏幕左上角：不等
        assertEquals(0f, WallEntrance.delayOf(wave, 0, WallRect(100f, -50f, 108f, 108f), p), 1e-6f)
        // 右 3 格、下 2 格：5 步
        assertEquals(0.15f, WallEntrance.delayOf(wave, 0, WallRect(100f + 3 * p, -50f + 2 * p, 108f, 108f), p), 1e-5f)
        // 屏幕左上方之外：不早于 0
        assertEquals(0f, WallEntrance.delayOf(wave, 0, WallRect(-500f, -500f, 108f, 108f), p), 1e-6f)
    }

    @Test
    fun batchWaveOnlyMovesTheNewSongs() {
        val wave = WallWave.Batch(15, 30, WaveStyle.Full)
        assertEquals(WallEntrance.NOT_IN_WAVE, WallEntrance.delayOf(wave, 14, WallRect(0f, 0f, 1f, 1f), p), 0f)
        assertEquals(WallEntrance.NOT_IN_WAVE, WallEntrance.delayOf(wave, 30, WallRect(0f, 0f, 1f, 1f), p), 0f)
        assertEquals(0f, WallEntrance.delayOf(wave, 15, WallRect(0f, 0f, 1f, 1f), p), 1e-6f)
        assertEquals(0.12f, WallEntrance.delayOf(wave, 19, WallRect(0f, 0f, 1f, 1f), p), 1e-6f)
        assertEquals(0.34f, WallEntrance.delayOf(wave, 29, WallRect(0f, 0f, 1f, 1f), p), 1e-6f)
    }

    @Test
    fun plainWaveHasNoStagger() {
        val open = WallWave.Open(WallPoint(0f, 0f), WaveStyle.Plain)
        assertEquals(0f, WallEntrance.delayOf(open, 3, WallRect(900f, 900f, 1f, 1f), p), 0f)
        val batch = WallWave.Batch(4, 8, WaveStyle.Plain)
        assertEquals(0f, WallEntrance.delayOf(batch, 7, WallRect(0f, 0f, 1f, 1f), p), 0f)
        assertEquals(WallEntrance.NOT_IN_WAVE, WallEntrance.delayOf(batch, 2, WallRect(0f, 0f, 1f, 1f), p), 0f)
    }

    @Test
    fun progressRunsFromZeroToOneWithASmallOvershoot() {
        assertEquals(0f, WallEntrance.progress(WaveStyle.Full, 0f), 0f)
        assertEquals(0f, WallEntrance.progress(WaveStyle.Full, -1f), 0f)
        assertEquals(1f, WallEntrance.progress(WaveStyle.Full, WallEntrance.LAND_DURATION), 0f)
        assertEquals(1f, WallEntrance.progress(WaveStyle.Fade, 5f), 0f)
        var peak = 0f
        var t = 0f
        while (t < WallEntrance.LAND_DURATION) {
            val v = WallEntrance.progress(WaveStyle.Full, t)
            assertTrue(v >= 0f)
            peak = maxOf(peak, v)
            t += 0.005f
        }
        // 阻尼 0.8 的弹簧只略过 1 一点
        assertTrue(peak in 1f..1.03f)
        // 走到头时已经贴着 1，不会在最后一帧跳
        assertEquals(1f, WallEntrance.progress(WaveStyle.Full, WallEntrance.LAND_DURATION - 0.001f), 0.005f)
    }

    @Test
    fun plainProgressIsALinearFade() {
        assertEquals(0.5f, WallEntrance.progress(WaveStyle.Plain, 0.1f), 1e-6f)
        assertEquals(1f, WallEntrance.progress(WaveStyle.Plain, 0.3f), 0f)
        assertEquals(WallEntrance.PLAIN_DURATION, WallEntrance.duration(WaveStyle.Plain), 0f)
        assertEquals(WallEntrance.MAX_DELAY + WallEntrance.LAND_DURATION, WallEntrance.duration(WaveStyle.Full), 1e-6f)
    }
}
