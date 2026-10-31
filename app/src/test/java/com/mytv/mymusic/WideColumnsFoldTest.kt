package com.mytv.mymusic

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.mytv.mymusic.ui.adaptive.FoldSplit
import com.mytv.mymusic.ui.adaptive.foldSplit
import com.mytv.mymusic.ui.components.Fold
import com.mytv.mymusic.ui.player.playerFoldSplit
import com.mytv.mymusic.ui.player.wideColumns
import com.mytv.mymusic.ui.player.wideFocusFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平板播放页按竖折痕分栏（书本姿态、双竖）。尺寸按 Pixel Fold 内屏 841×701dp、折痕在 420dp 估，
 * 默认 1dp = 1px，另有一组按 2.625px/dp 核对换算。
 */
class WideColumnsFoldTest {
    private val margin = 16f

    /** 半开的竖折痕：宽度 0 的折痕按 1px 报上来。 */
    private fun crease(at: Float, height: Float = 701f) =
        Fold(Rect(at, 0f, at + 1f, height), vertical = true, halfOpened = true, separating = true, occluding = false)

    private fun split(width: Float, fold: Fold, height: Float = 701f, margin: Float = this.margin) =
        foldSplit(Rect(0f, 0f, width, height), fold, margin)!!

    @Test
    fun withoutAFoldTheColumnsAreUnchanged() {
        listOf(752, 841, 960, 1024, 1280, 1600).forEach { width ->
            val plain = wideColumns(width, 1f)
            val none = wideColumns(width, 1f, null)
            assertEquals(plain.left, none.left)
            assertEquals(plain.block, none.block)
            assertEquals(plain.coverMax, none.coverMax)
            assertEquals(plain.lyricsX, none.lyricsX)
            assertEquals(plain.lyricsWidth, none.lyricsWidth)
        }
        // 设计稿 1280：左栏 560、歌词栏 x 600 宽 616，和原来一样
        val ref = wideColumns(1280, 1f, null)
        assertEquals(560, ref.left)
        assertEquals(600, ref.lyricsX)
        assertEquals(616, ref.lyricsWidth)
        // 专注歌词同样不变
        val f = wideFocusFrame(1280, 772, 1f, 88, 413, 22, 48)
        val g = wideFocusFrame(1280, 772, 1f, 88, 413, 22, 48, null)
        assertEquals(f.dockX, g.dockX)
        assertEquals(f.contentX, g.contentX)
        assertEquals(f.lyricsWidth, g.lyricsWidth)
    }

    @Test
    fun bookPostureKeepsBothColumnsOffTheCrease() {
        // 原来：左栏 368、歌词栏从 408 开始，每行第一个字压在 420 的折痕上
        val before = wideColumns(841, 1f)
        assertEquals(368, before.left)
        assertTrue(before.lyricsX in 400..421)

        val s = split(841f, crease(420f))
        val cols = wideColumns(841, 1f, s)
        // 左栏右缘 = 折痕左缘 − 16；歌词栏从折痕右缘 + 32 开始，右边仍给更多按钮留 64
        assertEquals(404, cols.left)
        assertEquals(421 + 32, cols.lyricsX)
        assertEquals(841 - 453 - 64, cols.lyricsWidth)
        // 左栏里最宽的标题 / 进度条和封面都在左页里，封面离左栏两边各 72 以上
        assertTrue(cols.block <= cols.left - 48)
        assertTrue((cols.left - cols.coverMax) / 2 >= 72)
        assertTrue(cols.lyricsX + cols.lyricsWidth <= 841)
    }

    @Test
    fun bookPostureFocusLyricsUseOnlyTheRightPage() {
        val s = split(841f, crease(420f))
        val f = wideFocusFrame(841, 645, 1f, dockWidth = 88, dockHeight = 413, headerHeight = 22, seekHeight = 48, split = s)
        // 竖向工具栏在左页正中，整条在折痕左缘之前
        assertEquals(((404 - 88) / 2f).toInt(), f.dockX)
        assertTrue(f.dockX + 88 <= 404)
        // 标题、歌词、进度条从右页起点 + 16 开始，右边留 32，都不过折痕
        assertEquals(437 + 16, f.contentX)
        assertEquals(841 - 453 - 32, f.lyricsWidth)
        assertTrue(f.contentX >= 421 + 32)
        assertTrue(f.contentX + f.seekWidth <= 841)
        assertTrue(f.lyricsHeight > 0)
    }

    @Test
    fun occludingHingeUsesItsWidthAndLyricsStartSixteenAfterIt() {
        // 双竖：两块 540dp 的屏中间夹 34dp 的铰链，铰链本身挡像素，不另让安全距离
        val hinge = Fold(Rect(540f, 0f, 574f, 720f), vertical = true, halfOpened = false, separating = true, occluding = true)
        val s = split(1114f, hinge, height = 720f)
        assertTrue(s.occluding)
        val cols = wideColumns(1114, 1f, s)
        assertEquals(540, cols.left)
        assertEquals(574 + 16, cols.lyricsX)
        assertEquals(1114 - 590 - 64, cols.lyricsWidth)
        // 左页宽，封面和标题照样封顶 320
        assertEquals(320, cols.coverMax)
        assertEquals(320, cols.block)
    }

    @Test
    fun horizontalSplitIsIgnored() {
        val table = FoldSplit(vertical = false, firstEnd = 334f, secondStart = 367f, size = 701f, occluding = false)
        listOf(841, 1280).forEach { width ->
            val plain = wideColumns(width, 1f)
            val withTable = wideColumns(width, 1f, table)
            assertEquals(plain.left, withTable.left)
            assertEquals(plain.lyricsX, withTable.lyricsX)
            assertEquals(plain.lyricsWidth, withTable.lyricsWidth)
            val f = wideFocusFrame(width, 645, 1f, 88, 413, 22, 48)
            val g = wideFocusFrame(width, 645, 1f, 88, 413, 22, 48, table)
            assertEquals(f.dockX, g.dockX)
            assertEquals(f.contentX, g.contentX)
        }
    }

    @Test
    fun densityOnlyScalesTheBookColumns() {
        // Pixel Fold 内屏 2208px 宽，折痕在 1104px；16dp = 42px
        val dp = 2.625f
        val s = foldSplit(Rect(0f, 0f, 2208f, 1840f), crease(1104f, 1840f), margin = 16 * dp)!!
        val cols = wideColumns(2208, dp, s)
        assertEquals((1104 - 42), cols.left)
        assertEquals(1105 + 42 + 42, cols.lyricsX)
        assertTrue(cols.lyricsX + cols.lyricsWidth <= 2208 - (64 * dp).toInt())
    }

    @Test
    fun playerSplitIsMeasuredFromTheSafeAreaNotTheWindow() {
        val density = Density(1f)
        // 安全区左上角 (0, 30)：竖折痕的 x 不受影响，横折痕按本地坐标往上挪 30
        val insets = WindowInsets(left = 0, top = 30, right = 0, bottom = 24)
        val book = playerFoldSplit(crease(420f), insets, density, LayoutDirection.Ltr, width = 841, height = 701 - 54)!!
        assertEquals(404f, book.firstEnd, 0f)
        assertEquals(437f, book.secondStart, 0f)
        val tableFold = Fold(Rect(0f, 420f, 701f, 421f), vertical = false, halfOpened = true, separating = true, occluding = false)
        val table = playerFoldSplit(tableFold, insets, density, LayoutDirection.Ltr, width = 701, height = 841 - 54)!!
        assertEquals(420f - 30f - 16f, table.firstEnd, 0f)
        assertEquals(421f - 30f + 16f, table.secondStart, 0f)
        // 左边有安全区（横屏的挖孔）时竖折痕也换成本地坐标
        val left = playerFoldSplit(crease(420f), WindowInsets(left = 40), density, LayoutDirection.Ltr, width = 801, height = 701)!!
        assertEquals(420f - 40f - 16f, left.firstEnd, 0f)
        // 没有折痕、展平的折痕都不分
        assertNull(playerFoldSplit(null, insets, density, LayoutDirection.Ltr, 841, 647))
        assertNull(playerFoldSplit(crease(420f).copy(separating = false, halfOpened = false), insets, density, LayoutDirection.Ltr, 841, 647))
    }
}
