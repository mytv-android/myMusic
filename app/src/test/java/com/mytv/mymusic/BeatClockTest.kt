package com.mytv.mymusic

import com.mytv.mymusic.playback.BeatGrid
import com.mytv.mymusic.ui.player.BeatClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeatClockTest {
    /** 120 BPM、每拍 0.5 秒、第 0 拍在 0 秒的理想网格。 */
    private val grid: BeatGrid = steadyGrid()

    @Test
    fun firesEachBeatOnceJustBeforeItAtAnyFrameRate() {
        for (fps in listOf(30, 60, 120)) {
            val clock = BeatClock()
            val fired = mutableListOf<Double>()
            var t = 0.0
            val dt = 1.0 / fps
            while (t < 10.0) {
                if (clock.update(t, dt.toFloat(), grid)) fired += clock.lastBeatS
                t += dt
            }
            // 锁定要 0.35 秒起，之后每拍一次，不重复；10.0 秒那拍在拍前 50ms 就起了
            val expected = (1..20).map { it * 0.5 }
            val tail = fired.filter { it >= 0.5 }
            assertEquals("$fps fps", expected.size, tail.size)
            tail.zip(expected).forEach { (a, b) -> assertEquals(b, a, 1e-9) }
        }
    }

    @Test
    fun gridNudgesDoNotFireTheSameBeatTwice() {
        val clock = BeatClock()
        var count = 0
        var t = 0.0
        while (t < 6.0) {
            // 每帧把网格前后挪 8ms，像跟踪器每 0.27 秒的微调
            val nudged = if ((t * 60).toInt() % 2 == 0) steadyGrid(t0 = 0.008) else steadyGrid(t0 = -0.008)
            if (clock.update(t, 1f / 60f, nudged)) count++
            t += 1.0 / 60
        }
        // 0.5..6.0 共 12 拍（6.0 那拍在拍前 50ms 起）
        assertEquals(12, count)
    }

    @Test
    fun seekingBackDoesNotSkipBeats() {
        val clock = BeatClock()
        var t = 0.0
        while (t < 4.0) {
            clock.update(t, 1f / 60f, grid)
            t += 1.0 / 60
        }
        // 往回跳到 1 秒：1.5 秒那一拍要照常起，不能因为“离上一拍太近”被跳过
        var fired = false
        t = 1.0
        while (t < 1.6) {
            if (clock.update(t, 1f / 60f, grid) && abs(clock.lastBeatS - 1.5) < 1e-9) fired = true
            t += 1.0 / 60
        }
        assertTrue(fired)
    }

    @Test
    fun envelopeRisesBeforeTheBeatAndDecaysWithTime() {
        val clock = BeatClock()
        var t = 0.0
        while (t < 2.0 - 0.03) {
            clock.update(t, 1f / 60f, grid)
            t += 1.0 / 120
        }
        // 2.0 秒那拍的起势已经开始
        val rising = clock.envelope(0.16)
        assertTrue("rising $rising", rising > 0.1f)
        clock.update(2.0, 1f / 60f, grid)
        val peak = clock.envelope(0.16)
        clock.update(2.16, 1f / 60f, grid)
        assertEquals(peak / Math.E.toFloat(), clock.envelope(0.16), 0.02f)
    }

    @Test
    fun pausingLetsTheEnvelopeFadeAndResumingDoesNotJumpBack() {
        val clock = BeatClock()
        var t = 0.0
        while (t <= 2.0) {
            clock.update(t, 1f / 60f, grid)
            t += 1.0 / 60
        }
        val before = clock.envelope(0.16)
        repeat(30) { clock.idle(1f / 60f) }
        val paused = clock.envelope(0.16)
        assertTrue(paused < before * 0.1f)
        // 恢复时听到的位置还在暂停那一刻：包络不应弹回去
        clock.update(t, 1f / 60f, grid)
        assertTrue(clock.envelope(0.16) <= paused + 1e-4f)
    }

    @Test
    fun withoutAGridKicksFireAtHalfStrength() {
        val clock = BeatClock()
        clock.pushKick(1.0)
        var fired = false
        var t = 0.9
        while (t < 1.2) {
            if (clock.update(t, 1f / 60f, BeatGrid.NONE)) {
                fired = true
                assertEquals(1.0, clock.lastBeatS, 1e-9)
                assertEquals(0.45f, clock.lastAccent, 1e-6f)
            }
            t += 1.0 / 60
        }
        assertTrue(fired)
    }

    /** 120 BPM 的理想网格：每拍 0.5 秒，第 0 拍在 [t0] 秒，置信度满。 */
    private fun steadyGrid(t0: Double = 0.0): BeatGrid =
        BeatGrid(0.5, t0, 0L, 1f, 0, true, LongArray(0), FloatArray(0))
}
