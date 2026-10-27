package com.mytv.mymusic

import com.mytv.mymusic.ui.player.TvDp
import com.mytv.mymusic.ui.player.tvStageFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 电视播放页的摆放（960×540dp 设计稿，按 1dp = 1px 计算）。 */
class TvStageLayoutTest {
    private val width = 960f
    private val height = 540f
    private val dockHeight = 112f
    private val dockTop = height - TvDp.DockBottom - dockHeight
    private val pill = 32f

    private fun frame(clock: Boolean = true, w: Float = width, h: Float = height, dock: Float = h - TvDp.DockBottom - dockHeight, unit: Float = 1f) =
        tvStageFrame(w * unit, h * unit, dock * unit, pill * unit, clock, unit)

    @Test
    fun defaultModeMatchesTheMock() {
        val f = frame()
        assertEquals(280f, f.coverSize(0f), 0.01f)
        assertEquals(50f, f.coverY(0f), 0.01f)
        assertEquals(400f, f.textX(0f), 0.01f)
        assertEquals(350f, f.pillY, 0.01f)
        assertEquals(500f, f.lyricsWidth, 0.01f)
        // 单行歌名（46 + 40 + 4 + 26）时歌词栏在设计稿的 136
        assertEquals(136f, f.lyricsTop(0f, metaBottom = 116f), 0.01f)
        assertEquals(504f, f.titleWidth(ambient = false), 0.01f)
    }

    @Test
    fun ambientModeMatchesTheMock() {
        val f = frame()
        assertEquals(300f, f.coverSize(1f), 0.01f)
        assertEquals(60f, f.coverY(1f), 0.01f)
        assertEquals(420f, f.textX(1f), 0.01f)
        assertEquals(128f, f.lyricsTop(1f, metaBottom = 110f), 0.01f)
        assertEquals(370f, f.lyricsBottom(1f) - f.lyricsTop(1f, metaBottom = 110f), 0.01f)
        // 右边给时钟留 150；时钟关了只留 56
        assertEquals(390f, f.titleWidth(ambient = true), 0.01f)
        assertEquals(484f, frame(clock = false).titleWidth(ambient = true), 0.01f)
    }

    @Test
    fun defaultModeKeepsEverythingClearOfTheDock() {
        val f = frame()
        assertTrue("pill bottom ${f.pillY + pill} vs dock $dockTop", f.pillY + pill <= dockTop - TvDp.PillDockGap)
        assertTrue("lyrics bottom ${f.lyricsBottom(0f)} vs dock $dockTop", f.lyricsBottom(0f) <= dockTop - TvDp.LyricsDockGap)
        assertTrue(f.coverY(0f) + f.coverSize(0f) < f.pillY)
        // 歌词栏右边不出屏，环境模式右移后也一样
        assertTrue(f.textX(1f) + f.lyricsWidth <= width - 40f)
    }

    @Test
    fun twoLineTitlePushesTheLyricsDown() {
        val f = frame()
        // 两行歌名：46 + 80 + 4 + 26 = 156，歌词栏让到 168
        assertEquals(168f, f.lyricsTop(0f, metaBottom = 156f), 0.01f)
    }

    @Test
    fun transitionInterpolatesBetweenTheTwoModes() {
        val f = frame()
        assertEquals(290f, f.coverSize(0.5f), 0.01f)
        assertEquals(410f, f.textX(0.5f), 0.01f)
        // 歌词栏底边从控制条上方滑到细进度上方
        assertEquals(dockTop - TvDp.LyricsDockGap, f.lyricsBottom(0f), 0.01f)
        assertEquals(height - TvDp.LyricsAmbientBottom, f.lyricsBottom(1f), 0.01f)
    }

    @Test
    fun shortScreensShrinkTheCoverSoThePillClearsTheDock() {
        val f = frame(h = 480f)
        val dock = 480f - TvDp.DockBottom - dockHeight
        assertTrue(f.coverSize(0f) < 280f)
        assertEquals(dock - TvDp.PillDockGap, f.pillY + pill, 0.01f)
        assertEquals(f.coverSize(0f) + TvDp.CoverGrow, f.coverSize(1f), 0.01f)
    }

    @Test
    fun largerScreensWidenTheLyricsInsteadOfGrowingTheCover() {
        val f = frame(w = 1280f, h = 720f)
        assertEquals(280f, f.coverSize(0f), 0.01f)
        assertEquals(1280f - 400f - TvDp.LyricsEnd, f.lyricsWidth, 0.01f)
    }

    @Test
    fun densityScalesEveryPosition() {
        val f = frame(unit = 2f)
        assertEquals(560f, f.coverSize(0f), 0.01f)
        assertEquals(800f, f.textX(0f), 0.01f)
        assertEquals(272f, f.lyricsTop(0f, metaBottom = 232f), 0.01f)
    }

    @Test
    fun coverCentreFractionsForTheBackdropRipple() {
        val f = frame()
        val default = f.coverCenter(0f, width, height)
        assertEquals(212f / 960f, default.x, 0.001f)
        assertEquals(190f / 540f, default.y, 0.001f)
        val ambient = f.coverCenter(1f, width, height)
        assertEquals(222f / 960f, ambient.x, 0.001f)
        assertEquals(210f / 540f, ambient.y, 0.001f)
    }
}
