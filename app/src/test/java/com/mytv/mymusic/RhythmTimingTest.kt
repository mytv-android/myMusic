package com.mytv.mymusic

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.mytv.mymusic.playback.PlaybackClock
import com.mytv.mymusic.playback.RhythmAnalyzer
import com.mytv.mymusic.playback.RhythmFrame
import com.mytv.mymusic.playback.RhythmTimeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(UnstableApi::class)
class RhythmTimingTest {
    @Test
    fun clockExtrapolatesFreezesAndFollowsSpeed() {
        val clock = PlaybackClock()
        assertEquals(PlaybackClock.UNSET, clock.audibleUs(0L))
        clock.setRunning(true, 0L)
        clock.onPosition(1_000_000L, 0L)
        assertEquals(1_010_000L, clock.audibleUs(10_000_000L))
        // 渲染线程卡住时最多往后推半秒
        assertEquals(1_500_000L, clock.audibleUs(5_000_000_000L))
        // 帧时间比最近一次记录早几毫秒：往回推
        assertEquals(995_000L, clock.audibleUs(-5_000_000L))

        clock.setRunning(false, 20_000_000L)
        assertEquals(1_020_000L, clock.audibleUs(20_000_000L))
        assertEquals(1_020_000L, clock.audibleUs(900_000_000L))

        clock.setRunning(true, 1_000_000_000L)
        clock.setSpeed(2f, 1_000_000_000L)
        assertEquals(1_040_000L, clock.audibleUs(1_010_000_000L))

        clock.clear()
        assertEquals(PlaybackClock.UNSET, clock.audibleUs(1_010_000_000L))
        // 没开始出声前报来的 NOT_SET 不算位置
        clock.onPosition(PlaybackClock.UNSET, 1_020_000_000L)
        assertEquals(PlaybackClock.UNSET, clock.audibleUs(1_020_000_000L))
    }

    @Test
    fun timelineHandsOutFramesOnlyOnceTheyAreAudible() {
        val timeline = RhythmTimeline(capacity = 4)
        val cursor = RhythmTimeline.Cursor()
        val got = mutableListOf<Long>()
        listOf(100L, 200L, 300L).forEach { timeline.push(RhythmFrame(ptsUs = it)) }
        assertEquals(0, timeline.drain(cursor, 50L) { got += it.ptsUs })
        assertEquals(2, timeline.drain(cursor, 250L) { got += it.ptsUs })
        assertEquals(listOf(100L, 200L), got)
        // 每帧只交一次
        assertEquals(0, timeline.drain(cursor, 250L) { got += it.ptsUs })

        // 读得太慢被覆盖：从还留着的最旧一帧接着读
        listOf(400L, 500L, 600L, 700L).forEach { timeline.push(RhythmFrame(ptsUs = it)) }
        timeline.drain(cursor, 10_000L) { got += it.ptsUs }
        assertEquals(listOf(100L, 200L, 400L, 500L, 600L, 700L), got)

        // 跳转清空后，游标从新的一段开始
        timeline.clear()
        timeline.push(RhythmFrame(ptsUs = 50L))
        got.clear()
        timeline.drain(cursor, 60L) { got += it.ptsUs }
        assertEquals(listOf(50L), got)

        // 两个读的一方互不影响
        val other = RhythmTimeline.Cursor()
        timeline.drain(other, 60L) { got += it.ptsUs }
        assertEquals(listOf(50L, 50L), got)
    }

    @Test
    fun framePtsCountsSamplesFromTheBufferHandedToTheSinkAfterAFlush() = runBlocking {
        val analyzer = RhythmAnalyzer()
        val rate = 44_100
        val sink = analyzer.audioBufferSink
        analyzer.setActive(true)
        sink.flush(rate, 1, C.ENCODING_PCM_16BIT)
        // 第一个缓冲分两次才交完（AudioTrack 满了）：两次报的是同一个 pts
        val start = 7_000_000L
        analyzer.onSinkInput(start)
        sink.handleBuffer(silence(1_000))
        analyzer.onSinkInput(start)
        sink.handleBuffer(silence(1_500))
        var fed = 2_500
        while (fed < 2_048 + 1_470 * 6) {
            analyzer.onSinkInput(start + fed * 1_000_000L / rate)
            sink.handleBuffer(silence(1_470))
            fed += 1_470
            delay(15)
        }
        delay(100)
        val pts = mutableListOf<Long>()
        analyzer.timeline.drain(RhythmTimeline.Cursor(), Long.MAX_VALUE) { pts += it.ptsUs }
        assertTrue("some frames were analysed", pts.isNotEmpty())
        // 每一帧的 pts 都是“锚点 + 窗口末尾的样本数”，和分析任务排没排上无关
        pts.forEach { value ->
            val hops = ((value - start) * rate / 1_000_000.0 - 2_048) / 1_470
            assertTrue("pts $value is before the first full window", hops > -0.01)
            assertEquals("pts $value is not on a hop boundary", Math.round(hops).toDouble(), hops, 0.01)
        }

        // 跳转：清空处理链后，以下一个缓冲的 pts 为新锚点，旧的帧不再交出
        sink.flush(rate, 1, C.ENCODING_PCM_16BIT)
        val seek = 60_000_000L
        var after = 0
        while (after < 2_048 + 1_470 * 3) {
            analyzer.onSinkInput(seek + after * 1_000_000L / rate)
            sink.handleBuffer(silence(1_470))
            after += 1_470
            delay(15)
        }
        delay(100)
        val seeked = mutableListOf<Long>()
        analyzer.timeline.drain(RhythmTimeline.Cursor(), Long.MAX_VALUE) { seeked += it.ptsUs }
        assertTrue(seeked.isNotEmpty())
        assertTrue(seeked.all { it >= seek + 2_048 * 1_000_000L / rate - 1 })
        analyzer.setActive(false)
    }

    private fun silence(samples: Int): ByteBuffer =
        ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder()).apply {
            repeat(samples) { putShort(0) }
            flip()
        }
}
