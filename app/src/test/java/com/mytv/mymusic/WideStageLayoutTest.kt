package com.mytv.mymusic

import com.mytv.mymusic.ui.player.WideCoverFloor
import com.mytv.mymusic.ui.player.wideBlockWidth
import com.mytv.mymusic.ui.player.wideColumns
import com.mytv.mymusic.ui.player.wideFocusFrame
import com.mytv.mymusic.ui.player.wideFullTier
import com.mytv.mymusic.ui.player.wideStackSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 平板播放页的横竖向分配（按 1dp = 1px 计算，另有一组按 2px/dp 核对换算）。 */
class WideStageLayoutTest {
    // 标题一行 68、进度条 48；完整左栏的块：标题、胶囊 32、进度、按钮组 88、工具栏 56；收起后按钮组 56、没有胶囊
    private val meta = 68
    private val seek = 48
    private val fullBlocks = intArrayOf(meta, 32, seek, 88, 56)
    private val shortBlocks = intArrayOf(meta, seek, 56, 56)

    /** 排完后最后一块（工具栏）的底边，按整栏缩放折算。 */
    private fun bottomOf(height: Int, blocks: IntArray, coverMax: Int, full: Boolean): Pair<Float, Float> {
        val s = wideStackSlots(height, 1f, blocks, coverMax, full)
        val top = s.tops.last()
        return s.tops[0] * s.scale to (top + blocks.last()) * s.scale
    }

    @Test
    fun referenceTabletMatchesTheMock() {
        val cols = wideColumns(1280, 1f)
        assertEquals(560, cols.left)
        assertEquals(320, cols.block)
        assertEquals(320, cols.coverMax)
        assertEquals(600, cols.lyricsX)
        assertEquals(616, cols.lyricsWidth)
        // 状态栏下 772dp：完整左栏，封面 320，顶边约 44（设计稿 72 − 状态栏 28），工具栏底边离底约 30
        assertTrue(wideFullTier(772, 1f, meta, seek, cols.coverMax))
        val s = wideStackSlots(772, 1f, fullBlocks, cols.coverMax, full = true)
        assertEquals(320, s.cover)
        assertEquals(1f, s.scale)
        assertTrue("coverTop=${s.tops[0]}", s.tops[0] in 44..52)
        val toolbarBottom = s.tops[5] + 56
        assertTrue("toolbarBottom=$toolbarBottom", 772 - toolbarBottom in 30..38)
    }

    @Test
    fun threeButtonNavigationOnlyCompressesGaps() {
        // 1280×800，状态栏 24、三键导航 48：间距压一压，封面仍是 320
        val s = wideStackSlots(728, 1f, fullBlocks, 320, full = true)
        assertEquals(320, s.cover)
        assertEquals(1f, s.scale)
        assertTrue(s.tops[0] >= 12)
    }

    @Test
    fun smallTabletsDropThePillAndUseTheCompactGroup() {
        // 960×600，状态栏 24、三键导航 48：完整左栏的封面只剩 160 出头，收起胶囊后封面回到 230 以上
        val cols = wideColumns(960, 1f)
        assertEquals(420, cols.left)
        assertEquals(276, cols.coverMax)
        assertFalse(wideFullTier(528, 1f, meta, seek, cols.coverMax))
        val s = wideStackSlots(528, 1f, shortBlocks, cols.coverMax, full = false)
        assertEquals(1f, s.scale)
        assertTrue("cover=${s.cover}", s.cover in 230..276)
        // 1138×712 这类中等高度保留完整左栏
        assertTrue(wideFullTier(640, 1f, meta, seek, wideColumns(1138, 1f).coverMax))
    }

    @Test
    fun titleAndSeekBarFollowTheCoverWidth() {
        // 封面 320 时就是设计稿的 320；封面缩小时一起变窄、左边对齐，但不窄于小号按钮组的 200
        assertEquals(320, wideBlockWidth(320, 320, 1f))
        assertEquals(238, wideBlockWidth(238, 320, 1f))
        assertEquals(200, wideBlockWidth(128, 320, 1f))
        assertEquals(292, wideBlockWidth(320, 292, 1f))
        assertEquals(476, wideBlockWidth(476, 640, 2f))
    }

    @Test
    fun coverStaysClearOfTheCloseButton() {
        // 收起按钮占左上角 16..64；封面顶边可以和它同高，所以封面左边必须在 72 以右
        listOf(752, 800, 960, 1024, 1138, 1280, 1600).forEach { width ->
            val cols = wideColumns(width, 1f)
            assertTrue("width=$width", (cols.left - cols.coverMax) / 2 >= 72)
            assertTrue("width=$width", cols.lyricsX + cols.lyricsWidth <= width - 64)
        }
    }

    @Test
    fun noHeightOverflowsOrOverlaps() {
        for (height in 240..1100 step 7) {
            listOf(true, false).forEach { full ->
                val blocks = if (full) fullBlocks else shortBlocks
                val s = wideStackSlots(height, 1f, blocks, 320, full)
                val (coverTop, bottom) = bottomOf(height, blocks, 320, full)
                assertTrue("height=$height full=$full bottom=$bottom", bottom <= height + 0.5f)
                assertTrue(coverTop >= 0f)
                // 块与块之间不重叠
                assertTrue(s.tops[1] >= s.tops[0] + s.cover)
                for (i in blocks.indices.drop(1)) assertTrue(s.tops[i + 1] >= s.tops[i] + blocks[i - 1])
                assertTrue(s.cover >= WideCoverFloor.toInt())
            }
        }
    }

    @Test
    fun coverShrinksMonotonicallyThenTheColumnScales() {
        var lastCover = Int.MAX_VALUE
        var lastScale = 1f
        for (height in 1000 downTo 260 step 4) {
            val s = wideStackSlots(height, 1f, shortBlocks, 320, full = false)
            assertTrue(s.cover <= lastCover)
            assertTrue(s.scale <= lastScale + 1e-6f)
            if (s.scale < 1f) assertEquals(WideCoverFloor.toInt(), s.cover)
            lastCover = s.cover
            lastScale = s.scale
        }
        // 横屏手机（800×360，状态栏 24）：整栏缩小但不小于 0.75
        val phone = wideStackSlots(336, 1f, shortBlocks, wideColumns(800, 1f).coverMax, full = false)
        assertTrue("scale=${phone.scale}", phone.scale in 0.75f..0.9f)
    }

    @Test
    fun tallWindowsCenterTheColumn() {
        val s = wideStackSlots(1000, 1f, fullBlocks, 320, full = true)
        val above = s.tops[0]
        val below = 1000 - (s.tops[5] + 56)
        assertTrue("above=$above below=$below", abs((above - 44) - (below - 30)) <= 1)
    }

    @Test
    fun densityOnlyScalesTheResult() {
        val one = wideStackSlots(728, 1f, fullBlocks, 320, full = true)
        val two = wideStackSlots(1456, 2f, IntArray(fullBlocks.size) { fullBlocks[it] * 2 }, 640, full = true)
        assertEquals(one.cover * 2, two.cover)
        // 插值后的间距各自取整，误差逐块累积，每块最多 2px
        one.tops.indices.forEach { assertTrue(abs(one.tops[it] * 2 - two.tops[it]) <= 2 * (it + 1)) }
        assertEquals(wideColumns(1280, 1f).lyricsX * 2, wideColumns(2560, 2f).lyricsX)
    }

    @Test
    fun focusFrameMatchesTheMock() {
        // 设计稿：竖向工具栏 x 32、y 132（状态栏下 104）；歌词 x 180、y 84（56）、1000×610；进度条 y 716（宽 980）
        val f = wideFocusFrame(1280, 772, 1f, dockWidth = 88, dockHeight = 413, headerHeight = 22, seekHeight = 48)
        assertEquals(32, f.dockX)
        assertEquals(180, f.contentX)
        assertEquals(1000, f.lyricsWidth)
        assertEquals(56, f.lyricsY)
        assertEquals(610, f.lyricsHeight)
        assertEquals(684, f.seekY)
        assertEquals(980, f.seekWidth)
        assertEquals(1f, f.dockScale)
        assertTrue("dockY=${f.dockY}", f.dockY in 96..108)
    }

    @Test
    fun focusDockScalesDownOnShortWindows() {
        listOf(312, 360, 480, 528, 600).forEach { height ->
            val f = wideFocusFrame(800, height, 1f, dockWidth = 88, dockHeight = 413, headerHeight = 22, seekHeight = 48)
            val shown = 413 * f.dockScale
            assertTrue("height=$height", f.dockY >= 12 && f.dockY + shown <= height - 12 + 0.5f)
            assertTrue(f.lyricsHeight > 0)
            assertTrue(f.seekY + 48 <= height)
            // 工具栏在歌词和进度条左边，不重叠
            assertTrue(f.dockX + 88 < f.contentX)
        }
        assertEquals(1f, wideFocusFrame(960, 528, 1f, 88, 413, 22, 48).dockScale)
    }
}
