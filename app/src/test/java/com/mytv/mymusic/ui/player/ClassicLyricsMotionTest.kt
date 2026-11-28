package com.mytv.mymusic.ui.player

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.material3.MotionScheme
import com.mytv.mymusic.ui.ParsedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 经典歌词列按 M3E 弹簧分工调过之后的几处（设计稿 docs/apple-music-lyrics.html 第 02 节）。 */
class ClassicLyricsMotionTest {
    private val schemes = listOf("expressive" to MotionScheme.expressive(), "standard" to MotionScheme.standard())

    /** 按 1 ms 采样一条弹簧从 [from] 到 [to] 的轨迹，直到停稳。 */
    private fun track(spec: AnimationSpec<Float>, from: Float, to: Float): List<Float> {
        val anim = TargetBasedAnimation(spec, Float.VectorConverter, from, to)
        val end = anim.durationNanos
        return (0..end / 1_000_000).map { anim.getValueFromNanos(it * 1_000_000) } + anim.getValueFromNanos(end)
    }

    @Test
    fun unsungHalfFadesFromRowBrightnessWithoutDippingOrFlashing() {
        for ((name, scheme) in schemes) {
            // 整行透明度（distance 1 → 0）和逐字层（1 → 0.45）用同一个 defaultEffects，一起出发
            val spec = scheme.defaultEffectsSpec<Float>()
            val distance = track(spec, 1f, 0f)
            // 逐字层的 Animatable 停稳判定更细（KaraokeFadeThreshold），整行用 animateFloatAsState 的默认判定
            val springSpec = spec as SpringSpec<Float>
            val layer = track(spring(springSpec.dampingRatio, springSpec.stiffness, KaraokeFadeThreshold), 1f, KaraokeUnsungAlpha)
            val brightness = (0..maxOf(distance.lastIndex, layer.lastIndex)).map {
                lyricAlpha(distance[it.coerceAtMost(distance.lastIndex)]) * layer[it.coerceAtMost(layer.lastIndex)]
            }
            assertEquals(name, 0.6f, brightness.first(), 1e-4f)
            assertEquals(name, KaraokeUnsungAlpha, brightness.last(), 1e-3f)
            assertTrue("$name 先闪暗：${brightness.min()}", brightness.min() >= KaraokeUnsungAlpha - 1e-3f)
            assertTrue("$name 先闪亮：${brightness.max()}", brightness.max() <= 0.61f)
        }
        // 解析式和采样一致：同一条进度下整行和逐字层相乘
        assertEquals(0.6f, karaokeUnsungBrightness(0f), 1e-6f)
        assertEquals(KaraokeUnsungAlpha, karaokeUnsungBrightness(1f), 1e-6f)
        for (k in 0..100) assertTrue(karaokeUnsungBrightness(k / 100f) in KaraokeUnsungAlpha..0.61f)
    }

    @Test
    fun currentLineBounceStaysWithinTheThreePercentCap() {
        val presets = listOf(
            LyricsSizing.PhoneLyrics, LyricsSizing.Tablet, LyricsSizing.TabletFocus, LyricsSizing.Tv, LyricsSizing.TvAmbient,
        )
        for ((name, scheme) in schemes) {
            val lift = track(scheme.fastSpatialSpec(), 0f, 1f)
            for (sizing in presets) {
                val layout = sizing.currentSp
                val peak = lift.maxOf { lyricVisualSp(sizing.baseSp, sizing.grow, it) / layout }
                assertTrue("$name $sizing 回弹 $peak", peak <= 1.03f)
                // API 28 起上限 1.03 不截掉回弹；23–27 仍截在 1
                assertEquals(peak, lift.maxOf { lyricLayerScale(lyricVisualSp(sizing.baseSp, sizing.grow, it), layout, 1.03f) }, 1e-6f)
                assertEquals(1f, lift.maxOf { lyricLayerScale(lyricVisualSp(sizing.baseSp, sizing.grow, it), layout, 1f) }, 1e-6f)
            }
        }
        // expressive 的 fastSpatial 确实回弹（阻尼比 0.6），这正是要显示出来的那一下
        val expressive = track(MotionScheme.expressive().fastSpatialSpec(), 0f, 1f)
        assertTrue(expressive.max() > 1.05f)
    }

    @Test
    fun eachSongGetsItsOwnContentAndReloadsStayInPlace() {
        val a = ParsedLyrics(emptyList(), "[00:01.00]一", true)
        val a2 = ParsedLyrics(emptyList(), "[00:01.00]一\n[00:01.00]one", true)
        val b = ParsedLyrics(emptyList(), "[00:02.00]二", true)
        // 两首歌：两份内容，各自的列表状态
        assertNotEquals(lyricsContentKey("song-a", a, false), lyricsContentKey("song-b", b, false))
        // 同一首重新取词、换翻译设置：原地更新，不过渡、不丢滚动位置
        assertEquals(lyricsContentKey("song-a", a, false), lyricsContentKey("song-a", a2, false))
        // 加载中已有同步歌词时继续显示这份
        assertEquals(lyricsContentKey("song-a", a, false), lyricsContentKey("song-a", a, true))
        // 占位不属于哪首歌：换歌先过渡到占位，新词就绪再从占位过渡进来
        val empty = ParsedLyrics(emptyList(), "", true)
        assertEquals(lyricsContentKey("song-a", empty, true), lyricsContentKey("song-b", empty, true))
        assertNotEquals(lyricsContentKey("song-b", empty, true), lyricsContentKey("song-b", b, false))
        assertNotEquals(lyricsContentKey("song-b", empty, true), lyricsContentKey("song-b", empty, false))
        val plain = ParsedLyrics(emptyList(), "没有时间轴", false)
        assertEquals(lyricsContentKey("song-b", empty, true), lyricsContentKey("song-b", plain, true))
        assertEquals("song-b", lyricsContentKey("song-b", plain, false))
    }

    @Test
    fun songTrailTellsPreviousFromNext() {
        val trail = SongTrail()
        assertEquals(1, trail.visit("a"))
        assertEquals(1, trail.visit("b"))
        assertEquals(1, trail.visit("c"))
        assertEquals(0, trail.visit("c"))
        // 连按两次上一首
        assertEquals(-1, trail.visit("b"))
        assertEquals(-1, trail.visit("a"))
        // 再往下一首
        assertEquals(1, trail.visit("b"))
        assertEquals(1, trail.visit("d"))
        // 记录有上限，最早的会被丢掉
        val short = SongTrail(limit = 3)
        listOf("1", "2", "3", "4").forEach(short::visit)
        assertEquals(-1, short.visit("3"))
        assertEquals(-1, short.visit("2"))
        assertEquals(1, short.visit("1"))
    }
}
