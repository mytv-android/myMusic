package com.mytv.mymusic

import com.mytv.mymusic.playback.BeatGrid
import com.mytv.mymusic.playback.BeatState
import com.mytv.mymusic.playback.BeatTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 用合成的起拍序列（不是音频）喂跟踪器：每个打击乐事件在它之后的第一帧落 70% 的通量、下一帧 30%，
 * 和真实分析里起拍跨两个重叠窗口的样子差不多；底下垫一层随机噪声。
 */
class BeatTrackerTest {
    @Test
    fun locksTempoWithinOnePercentAndFourSecondsAfterHistoryIsFull() {
        for (track in listOf(FOUR, GROOVE, BALLAD)) {
            // 电子曲第 9–10 小节（15–18.8 秒）没有底鼓，网格位置上空着，置信度会暂时掉下去；跑到 24 秒等它回来
            val frames = track.frames(seconds = 24.0)
            val tracker = BeatTracker()
            var lockedAt = Double.NaN
            frames.forEach { f ->
                tracker.push(f.pts, f.bass, f.mid)
                if (lockedAt.isNaN() && tracker.grid().confidence >= 0.5f) lockedAt = f.pts
            }
            val grid = tracker.grid()
            assertEquals("${track.name} tempo", track.bpm, grid.bpm, track.bpm * 0.01)
            assertTrue("${track.name} locked at $lockedAt", lockedAt <= 6.0)
            assertTrue("${track.name} confidence ${grid.confidence}", grid.confidence > 0.6f)
            // 网格上的拍和真实拍点对得上（检测延迟已在跟踪器里扣掉）
            val error = phaseError(grid, track, around = 23.0)
            assertTrue("${track.name} phase error ${error * 1000} ms", abs(error) < 0.04)
        }
    }

    @Test
    fun beatlessMusicRarelyGetsConfidentGrid() {
        // 稀疏的和弦变化（原型里的氛围曲）：起拍太少，置信度一直起不来
        val soft = BeatTracker()
        var peak = 0f
        AMBIENT_SOFT.frames(seconds = 30.0).forEach { f ->
            soft.push(f.pts, f.bass, f.mid)
            peak = maxOf(peak, soft.grid().confidence)
        }
        assertTrue("soft ambient confidence peaked at $peak", peak < 0.3f)
        // 随机出现的打击声偶尔几下凑巧等距：靠网格覆盖率压住，锁上的时间不到 3%
        var locked = 0
        var total = 0
        for (seed in 1..10) {
            val tracker = BeatTracker()
            AMBIENT.frames(seconds = 30.0, seed = seed).forEach { f ->
                tracker.push(f.pts, f.bass, f.mid)
                if (f.pts > 8.0) {
                    total++
                    if (tracker.grid().confidence >= 0.5f) locked++
                }
            }
        }
        assertTrue("random hits locked ${100.0 * locked / total}% of the time", locked < total * 0.03)
    }

    @Test
    fun seekKeepsTempoAndRelocksWithinTwoSeconds() {
        val frames = GROOVE.frames(seconds = 40.0)
        val tracker = BeatTracker()
        frames.filter { it.pts < 15.0 }.forEach { tracker.push(it.pts, it.bass, it.mid) }
        assertTrue(tracker.grid().confidence > 0.8f)
        // 往回跳到 3 秒（拖进度条、单曲循环回开头）
        var relocked = Double.NaN
        frames.filter { it.pts >= 3.0 }.forEach { f ->
            tracker.push(f.pts, f.bass, f.mid)
            if (relocked.isNaN() && tracker.grid().confidence >= 0.5f) relocked = f.pts
        }
        assertTrue("relocked ${relocked - 3.0} s after the seek", relocked - 3.0 <= 2.0)
        assertEquals(GROOVE.bpm, tracker.grid().bpm, GROOVE.bpm * 0.01)
        // 往前一下跳出两秒也一样
        val forward = BeatTracker()
        frames.filter { it.pts < 12.0 }.forEach { forward.push(it.pts, it.bass, it.mid) }
        relocked = Double.NaN
        frames.filter { it.pts >= 25.0 }.forEach { f ->
            forward.push(f.pts, f.bass, f.mid)
            if (relocked.isNaN() && forward.grid().confidence >= 0.5f) relocked = f.pts
        }
        assertTrue("relocked ${relocked - 25.0} s after the jump", relocked - 25.0 <= 2.0)
    }

    @Test
    fun newSongStartsWithoutTheOldTempo() {
        val tracker = BeatTracker()
        FOUR.frames(seconds = 12.0).forEach { tracker.push(it.pts, it.bass, it.mid) }
        tracker.reset(keepTempo = false)
        val ballad = BALLAD.frames(seconds = 3.5)
        ballad.forEach { tracker.push(100.0 + it.pts, it.bass, it.mid) }
        assertEquals("no grid before four seconds of the new song", 0.0, tracker.grid().periodS, 0.0)
    }

    @Test
    fun droppedFramesAreFilledWithSilence() {
        val tracker = BeatTracker()
        // 每 7 帧丢一帧（分析任务排不上），速度照样对
        FOUR.frames(seconds = 16.0).filterIndexed { i, _ -> i % 7 != 3 }.forEach { tracker.push(it.pts, it.bass, it.mid) }
        assertEquals(FOUR.bpm, tracker.grid().bpm, FOUR.bpm * 0.01)
    }

    @Test
    fun downbeatFollowsTheHeaviestBeatOfTheBar() {
        val tracker = BeatTracker()
        val frames = GROOVE.frames(seconds = 24.0)
        frames.forEach { tracker.push(it.pts, it.bass, it.mid) }
        val grid = tracker.grid()
        // 律动曲底鼓在 1、3 拍，首拍要落在其中一拍上（1 和 3 一样重，分不出也算对）
        val beat = Math.round((22.0 - grid.t0S) / grid.periodS)
        var index = grid.k0 + beat
        while (!grid.isDownbeat(index)) index++
        val at = grid.t0S + (index - grid.k0) * grid.periodS
        val position = Math.floorMod(((at + 0.05) / (60.0 / GROOVE.bpm)).toLong(), 4L)
        assertTrue("downbeat landed on beat $position", position == 0L || position == 2L)
    }

    @Test
    fun stateHandsTheOldGridToTimesBeforeTheBoundary() {
        val old = BeatTracker().apply { FOUR.frames(seconds = 12.0).forEach { push(it.pts, it.bass, it.mid) } }.grid()
        val state = BeatState(BeatGrid.NONE, old, 50.0)
        assertEquals(old, state.gridAt(49.9))
        assertEquals(BeatGrid.NONE, state.gridAt(50.0))
        assertEquals(BeatGrid.NONE, BeatState.EMPTY.gridAt(0.0))
        assertFalse(BeatGrid.NONE.periodS > 0.0)
    }

    /** 离 [around] 最近的网格拍和最近的真实拍之差（秒）。 */
    private fun phaseError(grid: BeatGrid, track: Track, around: Double): Double {
        val k = ((around - grid.t0S) / grid.periodS).roundToLong()
        val gridBeat = grid.t0S + k * grid.periodS
        val beat = 60.0 / track.bpm
        val truth = Math.round(gridBeat / beat) * beat
        return gridBeat - truth
    }

    private class Frame(val pts: Double, val bass: Float, val mid: Float)

    /** 一首合成曲：[events] 给出第 n 拍（从 0 数）里的打击乐，返回（拍内位置 0..1, 低频强度, 中频强度）。 */
    private class Track(
        val name: String,
        val bpm: Double,
        val periodic: Boolean = true,
        val gaps: ClosedFloatingPointRange<Double> = 0.15..1.55,
        val strength: Float = 1f,
        val events: (beat: Int, random: Random) -> List<Triple<Double, Float, Float>>,
    ) {
        fun frames(seconds: Double, fps: Double = 30.0, seed: Int = 7): List<Frame> {
            val random = Random(seed)
            val count = (seconds * fps).toInt()
            val bass = FloatArray(count + 2)
            val mid = FloatArray(count + 2)
            val beat = 60.0 / bpm
            val hits = mutableListOf<Triple<Double, Float, Float>>()
            if (periodic) {
                var n = 0
                while (n * beat < seconds) {
                    events(n, random).forEach { (at, b, m) -> hits += Triple((n + at) * beat, b, m) }
                    n++
                }
            } else {
                var t = 0.0
                while (t < seconds) {
                    hits += Triple(t, strength * (4f + random.nextFloat() * 20f), strength * (2f + random.nextFloat() * 12f))
                    t += gaps.start + random.nextDouble() * (gaps.endInclusive - gaps.start)
                }
            }
            for ((t, b, m) in hits) {
                // 窗口末尾第一次越过事件的那一帧拿 70%，下一帧 30%
                val first = kotlin.math.ceil(t * fps).toInt()
                if (first in 0 until count) {
                    bass[first] += 0.7f * b
                    mid[first] += 0.7f * m
                    bass[first + 1] += 0.3f * b
                    mid[first + 1] += 0.3f * m
                }
            }
            return List(count) { i ->
                Frame(i / fps, bass[i] + random.nextFloat() * 1.5f, mid[i] + random.nextFloat() * 1.5f)
            }
        }
    }

    private companion object {
        /** 128 BPM 四拍底鼓，2、4 拍拍手，反拍开镲；第 9–10 小节去掉底鼓。 */
        val FOUR = Track("four", 128.0) { n, _ ->
            val bar = n / 4
            val drop = bar == 8 || bar == 9
            buildList {
                if (!drop) add(Triple(0.0, 30f, 4f))
                if (!drop && n % 2 == 1) add(Triple(0.0, 3f, 24f))
                add(Triple(0.5, 1f, if (drop) 3f else 6f))
            }
        }

        /** 104 BPM：1、3 拍底鼓，2、4 拍军鼓，八分音符踩镲；后半段加一个切分底鼓。 */
        val GROOVE = Track("groove", 104.0) { n, _ ->
            val pos = n % 4
            val big = n / 4 >= 6
            buildList {
                if (pos == 0 || pos == 2) add(Triple(0.0, 30f, 3f))
                if (pos == 1 || pos == 3) add(Triple(0.0, 5f, 25f))
                add(Triple(0.5, 0.5f, 4f))
                if (big && pos == 2) add(Triple(0.75, 22f, 2f))
            }
        }

        /** 76 BPM 慢歌：第一拍底鼓、第三拍边击，钢琴按八分音符分解和弦，低音在 1、3 拍。 */
        val BALLAD = Track("ballad", 76.0) { n, _ ->
            val pos = n % 4
            buildList {
                if (pos == 0) add(Triple(0.0, 22f, 2f))
                if (pos == 2) add(Triple(0.0, 10f, 15f))
                add(Triple(0.0, 3f, 8f))
                add(Triple(0.5, 3f, 8f))
            }
        }

        /** 没有拍子：随机间隔的打击声。 */
        val AMBIENT = Track("ambient", 0.0, periodic = false) { _, _ -> emptyList() }

        /** 没有拍子：和弦每 2.2–5.6 秒不规则地换一次，起音柔和。 */
        val AMBIENT_SOFT = Track("ambientSoft", 0.0, periodic = false, gaps = 2.2..5.6, strength = 0.5f) { _, _ -> emptyList() }
    }
}
