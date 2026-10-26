package com.mytv.mymusic

import com.mytv.mymusic.ui.player.LyricsSizing
import com.mytv.mymusic.ui.player.anchorScrollStep
import com.mytv.mymusic.ui.player.lyricAlpha
import com.mytv.mymusic.ui.player.lyricAnchorError
import com.mytv.mymusic.ui.player.lyricBlurDp
import com.mytv.mymusic.ui.player.lyricDistance
import com.mytv.mymusic.ui.player.lyricSlotHeight
import com.mytv.mymusic.ui.player.lyricTextWidth
import org.junit.Assert.assertEquals
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
    fun legacyPresetsKeepTheOldTypographySizes() {
        assertEquals(22f, LyricsSizing.Standard.baseSp, 0.001f)
        assertEquals(24f, LyricsSizing.Standard.currentSp, 0.001f)
        assertEquals(28f, LyricsSizing.Large.baseSp, 0.001f)
        assertEquals(36f, LyricsSizing.Large.currentSp, 0.001f)
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
    fun textWrapsAtTheGrownWidthAndNarrowsWhileShrinkingPreset() {
        assertEquals(800, lyricTextWidth(1000, 1.25f, 1f))
        // 预设变大（旧字号更小）不需要收窄
        assertEquals(800, lyricTextWidth(1000, 1.25f, 0.8f))
        // 预设变小时旧字号还在放大，按比例再收窄，缩放后不超出列宽
        val width = lyricTextWidth(1000, 1.25f, 1.2f)
        assertEquals(666, width)
        assertTrue(width * 1.25f * 1.2f <= 1000f)
    }

    @Test
    fun slotReservesScaledHeightPlusGap() {
        assertEquals(76, lyricSlotHeight(40, 1f, 36f))
        assertEquals(90, lyricSlotHeight(40, 1.35f, 36f))
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
