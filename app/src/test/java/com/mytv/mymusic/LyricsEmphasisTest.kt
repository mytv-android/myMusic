package com.mytv.mymusic

import com.mytv.mymusic.ui.player.LyricEdge
import com.mytv.mymusic.ui.player.LyricFadeBottom
import com.mytv.mymusic.ui.player.LyricFadeTop
import com.mytv.mymusic.ui.player.LyricsSizing
import com.mytv.mymusic.ui.player.anchorScrollStep
import com.mytv.mymusic.ui.player.lyricAlpha
import com.mytv.mymusic.ui.player.lyricAnchorError
import com.mytv.mymusic.ui.player.lyricBlurDp
import com.mytv.mymusic.ui.player.lyricDistance
import com.mytv.mymusic.ui.player.lyricEdgeLocalY
import com.mytv.mymusic.ui.player.lyricEdgeOf
import com.mytv.mymusic.ui.player.lyricLayerScale
import com.mytv.mymusic.ui.player.lyricLayoutSp
import com.mytv.mymusic.ui.player.lyricSlotHeight
import com.mytv.mymusic.ui.player.lyricVisualSp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌词列按距离降透明度 / 模糊、当前行缩放占位和锚点滚动的纯计算部分。 */
class LyricsEmphasisTest {
    @Test
    fun presetsMatchTheMockedCurrentLineSizes() {
        assertEquals(28f, LyricsSizing.PhoneLyrics.currentSp, 0.5f)
        assertEquals(38f, LyricsSizing.Tablet.currentSp, 0.5f)
        assertEquals(48f, LyricsSizing.TabletFocus.currentSp, 0.5f)
        assertEquals(30f, LyricsSizing.Tv.currentSp, 0.5f)
        assertEquals(36f, LyricsSizing.TvAmbient.currentSp, 0.5f)
        listOf(LyricsSizing.PhoneLyrics, LyricsSizing.Tablet, LyricsSizing.TabletFocus, LyricsSizing.Tv, LyricsSizing.TvAmbient)
            .forEach { assertTrue(it.toString(), it.anchor in 0.42f..0.46f) }
    }

    @Test
    fun distanceIsCappedAndUnsyncedLyricsCountAsAdjacent() {
        assertEquals(0, lyricDistance(5, 5))
        assertEquals(1, lyricDistance(4, 5))
        assertEquals(1, lyricDistance(6, 5))
        assertEquals(2, lyricDistance(3, 5))
        assertEquals(2, lyricDistance(40, 5))
        assertEquals(1, lyricDistance(0, -1))
        assertEquals(1, lyricDistance(30, -1))
    }

    @Test
    fun alphaStepsDownWithDistanceAndInterpolatesBetween() {
        assertEquals(1f, lyricAlpha(0f), 0.0001f)
        assertEquals(0.6f, lyricAlpha(1f), 0.0001f)
        assertEquals(0.38f, lyricAlpha(2f), 0.0001f)
        assertEquals(0.8f, lyricAlpha(0.5f), 0.0001f)
        assertEquals(0.49f, lyricAlpha(1.5f), 0.0001f)
        assertEquals(1f, lyricAlpha(-0.2f), 0.0001f)
        assertEquals(0.38f, lyricAlpha(2.3f), 0.0001f)
    }

    @Test
    fun onlyFarLinesBlurAndOnlyWhenSupported() {
        assertEquals(0f, lyricBlurDp(0f, true), 0.0001f)
        assertEquals(0f, lyricBlurDp(1f, true), 0.0001f)
        assertEquals(0.7f, lyricBlurDp(1.5f, true), 0.0001f)
        assertEquals(1.4f, lyricBlurDp(2f, true), 0.0001f)
        assertEquals(0f, lyricBlurDp(2f, false), 0.0001f)
    }

    @Test
    fun rowsLayOutAtTheGrownSizeAndOnlyShrink() {
        val presets = listOf(
            LyricsSizing.PhoneLyrics, LyricsSizing.Tablet, LyricsSizing.TabletFocus, LyricsSizing.Tv, LyricsSizing.TvAmbient,
        )
        for (sizing in presets) {
            val layout = lyricLayoutSp(sizing.currentSp, sizing.currentSp)
            // 当前行图层不缩放（API 23–27 上放大的文字会糊），其他行缩到 1/grow，视觉字号还是常规字号
            assertEquals(sizing.toString(), 1f, lyricLayerScale(lyricVisualSp(sizing.baseSp, sizing.grow, 1f), layout), 1e-6f)
            assertEquals(sizing.toString(), 1f / sizing.grow, lyricLayerScale(lyricVisualSp(sizing.baseSp, sizing.grow, 0f), layout), 1e-6f)
            assertEquals(sizing.currentSp, lyricVisualSp(sizing.baseSp, sizing.grow, 1f), 1e-4f)
            // 放大过程单调、连续，弹簧过冲也不会放大到 1 倍以上
            var previous = 0f
            for (k in 0..24) {
                val scale = lyricLayerScale(lyricVisualSp(sizing.baseSp, sizing.grow, k / 20f), layout)
                assertTrue(scale >= previous && scale <= 1f)
                previous = scale
            }
        }
    }

    @Test
    fun shrinkingPresetKeepsTheLargerLayoutSizeUntilTheTransitionEnds() {
        val pairs = listOf(
            LyricsSizing.TabletFocus to LyricsSizing.Tablet,
            LyricsSizing.TvAmbient to LyricsSizing.Tv,
            LyricsSizing.Tablet to LyricsSizing.PhoneLyrics,
            LyricsSizing.Tablet to LyricsSizing.TabletFocus,
            LyricsSizing.Tv to LyricsSizing.TvAmbient,
        )
        for ((from, to) in pairs) {
            // 过渡开始时保留的是旧排版字号
            val layout = lyricLayoutSp(from.currentSp, to.currentSp)
            assertEquals(maxOf(from.currentSp, to.currentSp), layout, 0f)
            for (k in 0..20) {
                val t = k / 20f
                val base = from.baseSp + (to.baseSp - from.baseSp) * t
                val grow = from.grow + (to.grow - from.grow) * t
                for (lift in listOf(0f, 0.5f, 1f)) {
                    val visual = lyricVisualSp(base, grow, lift)
                    assertTrue("$from -> $to at $t never needs to enlarge", visual <= layout + 1e-4f)
                    assertEquals(visual / layout, lyricLayerScale(visual, layout), 1e-6f)
                }
            }
        }
        // 过渡结束后换成新字号，当前行回到 1 倍
        val settled = lyricLayoutSp(LyricsSizing.Tablet.currentSp, LyricsSizing.Tablet.currentSp)
        assertEquals(1f, lyricLayerScale(LyricsSizing.Tablet.currentSp, settled), 1e-6f)
    }

    @Test
    fun onlyRowsTouchingTheFadeZonesGetAnEdgeMask() {
        // 视口 1000px：顶上 0–140 淡入，底下 800–1000 淡出
        assertNull(lyricEdgeOf(itemTop = 140, itemSize = 60, viewport = 1000))
        assertNull(lyricEdgeOf(itemTop = 700, itemSize = 100, viewport = 1000))
        assertEquals(LyricEdge(center = 160f, viewport = 1000f), lyricEdgeOf(itemTop = 130, itemSize = 60, viewport = 1000))
        assertEquals(LyricEdge(center = 760f, viewport = 1000f), lyricEdgeOf(itemTop = 710, itemSize = 100, viewport = 1000))
        // 半截在视口外的行也要擦，否则会被视口边缘硬切
        assertEquals(LyricEdge(center = 0f, viewport = 1000f), lyricEdgeOf(itemTop = -30, itemSize = 60, viewport = 1000))
        assertNull(lyricEdgeOf(itemTop = 0, itemSize = 60, viewport = 0))
    }

    @Test
    fun edgeMaskLinesUpWithTheWholeColumnMaskWhateverTheRowScale() {
        // 行占位中心在视口 100px 处、图层高 54px：不缩放时视口顶边在图层本地 -73，底边在 927
        val edge = LyricEdge(center = 100f, viewport = 1000f)
        assertEquals(-73f, lyricEdgeLocalY(0f, edge, 1f, 54f), 1e-4f)
        assertEquals(927f, lyricEdgeLocalY(1000f, edge, 1f, 54f), 1e-4f)
        // 缩到 1/1.36 时以图层中心为原点：视口里的一段距离在本地坐标里放大 1.36 倍，图层中心仍对着占位中心
        val scale = 1f / 1.36f
        assertEquals(27f, lyricEdgeLocalY(100f, edge, scale, 54f), 1e-4f)
        assertEquals(27f - 100f * 1.36f, lyricEdgeLocalY(0f, edge, scale, 54f), 1e-3f)
        // 视口里 14% 处（遮罩完全不透明的起点）映射回去仍落在同一条视口高度上
        val local = lyricEdgeLocalY(140f, edge, scale, 54f)
        assertEquals(140f, edge.center + (local - 27f) * scale, 1e-3f)
        assertEquals(0.14f, LyricFadeTop, 0f)
        assertEquals(0.8f, LyricFadeBottom, 0f)
    }

    @Test
    fun slotReservesScaledHeightPlusGap() {
        assertEquals(76, lyricSlotHeight(40, 1f, 36f))
        assertEquals(90, lyricSlotHeight(40, 1.35f, 36f))
        // 按放大字号排版的 54px 行缩到 1/1.35，占位和原来按常规字号排版的 40px 行一样
        assertEquals(76, lyricSlotHeight(54, 1f / 1.35f, 36f))
    }

    @Test
    fun anchorErrorMeasuresLineCenterAgainstAnchorInContainerCoordinates() {
        // 视口 1000px、前留白 400px（viewportStartOffset = -400）、锚点 0.4：行顶在内容坐标 -25、高 50，中心正好在锚点
        assertEquals(0f, lyricAnchorError(-25, 50, -400, 1000, 0.4f), 0.0001f)
        assertEquals(100f, lyricAnchorError(75, 50, -400, 1000, 0.4f), 0.0001f)
        assertEquals(-60f, lyricAnchorError(-85, 50, -400, 1000, 0.4f), 0.0001f)
    }

    @Test
    fun scrollStepFollowsSpringProgressAndLandsOnAMovingTarget() {
        // 终点不变时，累计滚动 = 终点 × 进度
        var scrolled = 0f
        var remaining = 300f
        for (progress in listOf(0.2f, 0.6f, 1.04f, 1f)) {
            val step = anchorScrollStep(scrolled, remaining, progress)
            scrolled += step
            remaining -= step
            assertEquals(300f * progress, scrolled, 0.001f)
        }
        // 滚动过程中目标行变高、终点后移，最后一帧仍然正好落到锚点
        scrolled = 0f
        remaining = 300f
        for ((progress, growth) in listOf(0.3f to 12f, 0.7f to 10f, 0.95f to 2f, 1f to 0f)) {
            remaining += growth
            val step = anchorScrollStep(scrolled, remaining, progress)
            scrolled += step
            remaining -= step
        }
        assertEquals(0f, remaining, 0.001f)
        assertEquals(324f, scrolled, 0.001f)
    }
}
