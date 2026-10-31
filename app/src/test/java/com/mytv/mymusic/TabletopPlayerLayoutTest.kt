package com.mytv.mymusic

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.mytv.mymusic.ui.adaptive.FoldSplit
import com.mytv.mymusic.ui.components.Fold
import com.mytv.mymusic.ui.player.clockDateText
import com.mytv.mymusic.ui.player.playerFoldSplit
import com.mytv.mymusic.ui.player.tabletopControlSlots
import com.mytv.mymusic.ui.player.tabletopFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 桌面姿态、双横的播放页：上下分界和下半屏的竖向分配（按 1dp = 1px 计算）。 */
class TabletopPlayerLayoutTest {
    private val density = Density(1f)

    private fun table(at: Float, width: Float) =
        Fold(Rect(0f, at, width, at + 1f), vertical = false, halfOpened = true, separating = true, occluding = false)

    /** 歌名行 56、进度 48、按钮组（大号 88 / 小号 56）、工具栏 60。 */
    private fun blocks(group: Int) = intArrayOf(56, 48, group, 60)
    private val ideal = intArrayOf(16, 14, 14)
    private val min = intArrayOf(8, 4, 8)

    @Test
    fun largeFoldableSplitsAtTheCreaseWithMargins() {
        // Pixel Fold 内屏竖握 701×841，状态栏 30、手势条 24，折痕在 420
        val insets = WindowInsets(top = 30, bottom = 24)
        val height = 841 - 54
        val split = playerFoldSplit(table(420f, 701f), insets, density, LayoutDirection.Ltr, 701, height)
        val f = tabletopFrame(height, 1f, split)
        assertEquals(420 - 30 - 16, f.topHeight)
        assertEquals(421 - 30 + 16, f.bottomTop)
        assertEquals(height - f.bottomTop, f.bottomHeight)
        // 下半屏放得下大号按钮组和理想间距，整组竖向居中
        val s = tabletopControlSlots(f.bottomHeight, blocks(88), ideal, min)
        assertEquals(1f, s.scale)
        val bottom = s.tops.last() + 60
        assertTrue("top=${s.tops[0]} bottom=$bottom", kotlin.math.abs(s.tops[0] - (f.bottomHeight - bottom)) <= 1)
    }

    @Test
    fun flipStyleFoldableSplitsMidHeight() {
        // 6.7" 横向折叠：412×915，折痕在正中
        val insets = WindowInsets(top = 32, bottom = 24)
        val height = 915 - 56
        val split = playerFoldSplit(table(457f, 412f), insets, density, LayoutDirection.Ltr, 412, height)
        val f = tabletopFrame(height, 1f, split)
        assertEquals(457 - 32 - 16, f.topHeight)
        assertEquals(458 - 32 + 16, f.bottomTop)
        assertTrue(f.bottomHeight > 300)
        val s = tabletopControlSlots(f.bottomHeight, blocks(56), ideal, min)
        assertEquals(1f, s.scale)
        assertTrue(s.tops.last() + 60 <= f.bottomHeight)
    }

    @Test
    fun dualLandscapeHingeLeavesItsWidthEmpty() {
        // 双横：上下两块屏，中间 34 的铰链挡像素，不另让安全距离
        val hinge = Fold(Rect(0f, 540f, 720f, 574f), vertical = false, halfOpened = false, separating = true, occluding = true)
        val insets = WindowInsets(top = 24, bottom = 24)
        val height = 1114 - 48
        val split = playerFoldSplit(hinge, insets, density, LayoutDirection.Ltr, 720, height)
        val f = tabletopFrame(height, 1f, split)
        assertEquals(540 - 24, f.topHeight)
        assertEquals(574 - 24, f.bottomTop)
    }

    @Test
    fun withoutAHorizontalFoldItSplitsInTheMiddle() {
        val f = tabletopFrame(800, 1f, null)
        assertEquals(384, f.topHeight)
        assertEquals(416, f.bottomTop)
        assertEquals(384, f.bottomHeight)
        // 竖折痕不管
        val book = FoldSplit(vertical = true, firstEnd = 404f, secondStart = 437f, size = 841f, occluding = false)
        assertEquals(f, tabletopFrame(800, 1f, book))
    }

    @Test
    fun controlsNeverOverflowOrOverlap() {
        for (height in 120..600 step 3) {
            listOf(88, 56).forEach { group ->
                val b = blocks(group)
                val s = tabletopControlSlots(height, b, ideal, min)
                assertTrue(s.scale in 0f..1f)
                assertTrue(s.tops[0] >= 0)
                for (i in 1 until b.size) assertTrue("height=$height", s.tops[i] >= s.tops[i - 1] + b[i - 1])
                val bottom = (s.tops.last() + b.last()) * s.scale
                assertTrue("height=$height group=$group bottom=$bottom", bottom <= height + 0.5f)
                if (s.scale < 1f) assertTrue(height < b.sum() + min.sum())
            }
        }
    }

    @Test
    fun clockDateLine() {
        assertEquals("10月31日 周五", clockDateText(10, 31, Calendar.FRIDAY))
        assertEquals("1月1日 周日", clockDateText(1, 1, Calendar.SUNDAY))
        assertEquals("12月6日 周六", clockDateText(12, 6, Calendar.SATURDAY))
    }
}
