package com.mytv.mymusic

import androidx.compose.ui.geometry.Rect
import com.mytv.mymusic.ui.adaptive.BottomPlayerBarSplitMin
import com.mytv.mymusic.ui.adaptive.foldSplit
import com.mytv.mymusic.ui.components.Fold
import com.mytv.mymusic.ui.library.LibraryBentoRowWidth
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表-详情双栏和底部播放条在 Pixel Fold 内屏（2208×1840px，2.625x，折痕在 1104px）书本姿态下的切法：
 * 用和页面同样的区域、安全距离和最小宽度，确认该分的地方分得开、小卡只在折痕下触发。
 */
class FoldListDetailTest {
    private val density = 2.625f
    private val widthPx = 2208f
    private val heightPx = 1840f
    private val book = Fold(Rect(1104f, 0f, 1105f, heightPx), vertical = true, halfOpened = true, separating = true, occluding = false)
    private val margin = 16f * density

    private fun px(dp: Float) = dp * density
    private fun dp(px: Float) = px / density

    @Test
    fun tabletLibraryListShrinksBelowBentoRowWidthOnBook() {
        // TabletLibrary 的 FoldRow 从 96dp 侧栏右边开始，列表内容左右各留 24 / 16dp
        val area = Rect(px(96f), 0f, widthPx, heightPx)
        val split = foldSplit(area, book, margin, minSegment = px(160f))
        assertNotNull(split)
        val content = dp(split!!.firstEnd) - 24f - 16f
        assertTrue("书本姿态下列表内容 $content dp 应改成一行三张小卡", content < LibraryBentoRowWidth.value)
        // 平常 380dp 的列表内容是 340dp，不能触发
        assertTrue(380f - 24f - 16f >= LibraryBentoRowWidth.value)
    }

    @Test
    fun twoColumnsSplitOnBookWithGutter() {
        // TwoColumns / 详情页：侧栏右边再扣 24dp 留白
        val area = Rect(px(96f + 24f), 0f, widthPx - px(24f), heightPx)
        val split = foldSplit(area, book, margin, minSegment = px(160f))
        assertNotNull(split)
        assertTrue(dp(split!!.firstEnd) < 320f)
        assertTrue(dp(split.secondSize) >= 160f)
    }

    @Test
    fun bottomPlayerBarSplitsOnBook() {
        // 播放条在侧栏右边，左右各留 16dp，锚点量的是留白里面那一块
        val area = Rect(px(96f + 16f), heightPx - px(88f), widthPx - px(16f), heightPx)
        val split = foldSplit(area, book, margin, minSegment = px(BottomPlayerBarSplitMin.value))
        assertNotNull(split)
        assertTrue(split!!.vertical)
        // 左段右缘在折痕左边，右段从折痕右边开始
        assertTrue(area.left + split.firstEnd <= 1104f - margin)
        assertTrue(area.left + split.secondStart >= 1105f + margin)
    }

    @Test
    fun bottomPlayerBarIgnoresFlatAndHorizontalCreases() {
        val area = Rect(px(96f + 16f), heightPx - px(88f), widthPx - px(16f), heightPx)
        val flat = book.copy(halfOpened = false, separating = false)
        assertNull(foldSplit(area, flat, margin, minSegment = px(BottomPlayerBarSplitMin.value)))
        // 桌面姿态的横折痕在屏幕中间，碰不到底部的播放条
        val table = Fold(Rect(0f, 920f, widthPx, 921f), vertical = false, halfOpened = true, separating = true, occluding = false)
        assertNull(foldSplit(area, table, margin, minSegment = px(BottomPlayerBarSplitMin.value)))
    }
}
