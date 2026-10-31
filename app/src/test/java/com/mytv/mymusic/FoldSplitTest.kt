package com.mytv.mymusic

import androidx.compose.ui.geometry.Rect
import com.mytv.mymusic.ui.adaptive.foldSplit
import com.mytv.mymusic.ui.components.AppWindow
import com.mytv.mymusic.ui.components.DeviceKind
import com.mytv.mymusic.ui.components.Fold
import com.mytv.mymusic.ui.components.Posture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 折痕切分（窗口像素坐标），尺寸按 Pixel Fold 内屏 2208×1840 估。 */
class FoldSplitTest {
    private val window = Rect(0f, 0f, 2208f, 1840f)

    /** 半开的竖折痕：宽度 0 的折痕已经按 1px 报上来。 */
    private val bookCrease = Fold(Rect(1104f, 0f, 1105f, 1840f), vertical = true, halfOpened = true, separating = true, occluding = false)
    private val tableCrease = Fold(Rect(0f, 920f, 2208f, 921f), vertical = false, halfOpened = true, separating = true, occluding = false)
    private val flatCrease = bookCrease.copy(halfOpened = false, separating = false)
    /** 双屏的铰链：有宽度、挡像素，展平也分页。 */
    private val hinge = Fold(Rect(1350f, 0f, 1434f, 1800f), vertical = true, halfOpened = false, separating = true, occluding = true)

    @Test
    fun verticalCreaseSplitsLeftAndRightWithMargins() {
        val s = foldSplit(window, bookCrease, margin = 42f)!!
        assertTrue(s.vertical)
        assertEquals(1104f - 42f, s.firstEnd, 0f)
        assertEquals(1105f + 42f, s.secondStart, 0f)
        assertEquals(2208f, s.size, 0f)
        assertEquals(2208f - 1147f, s.secondSize, 0f)
    }

    @Test
    fun horizontalCreaseSplitsTopAndBottom() {
        val s = foldSplit(window, tableCrease, margin = 42f)!!
        assertFalse(s.vertical)
        assertEquals(920f - 42f, s.firstEnd, 0f)
        assertEquals(921f + 42f, s.secondStart, 0f)
        assertEquals(1840f, s.size, 0f)
    }

    @Test
    fun localCoordinatesFollowSelf() {
        // 扣掉 96dp（252px）侧栏后的内容区：折痕位置换成内容区自己的坐标
        val content = Rect(252f, 0f, 2208f, 1840f)
        val s = foldSplit(content, bookCrease, margin = 42f)!!
        assertEquals(1104f - 252f - 42f, s.firstEnd, 0f)
        assertEquals(1105f - 252f + 42f, s.secondStart, 0f)
        assertEquals(2208f - 252f, s.size, 0f)
    }

    @Test
    fun occludingHingeUsesItsOwnWidthWithoutMargin() {
        val s = foldSplit(Rect(0f, 0f, 2784f, 1800f), hinge, margin = 42f)!!
        assertTrue(s.occluding)
        assertEquals(1350f, s.firstEnd, 0f)
        assertEquals(1434f, s.secondStart, 0f)
    }

    @Test
    fun flatCreaseDoesNotSplit() {
        assertNull(foldSplit(window, flatCrease, margin = 42f))
        assertNull(foldSplit(window, null, margin = 42f))
    }

    @Test
    fun noIntersectionMeansNoFold() {
        // 只占右页的面板：折痕在它左边，不相交
        assertNull(foldSplit(Rect(1200f, 0f, 2208f, 1840f), bookCrease, margin = 42f))
        // 只占上半屏的东西碰不到横折痕
        assertNull(foldSplit(Rect(0f, 0f, 2208f, 800f), tableCrease, margin = 42f))
    }

    @Test
    fun foldTooCloseToAnEdgeIsIgnored() {
        // 折痕离左边只有 100px：左段扣掉安全距离只剩 58px，比要求的 160 短
        val near = Rect(1004f, 0f, 2208f, 1840f)
        assertNull(foldSplit(near, bookCrease, margin = 42f, minSegment = 160f))
        assertTrue(foldSplit(near, bookCrease, margin = 42f) != null)
        // 安全距离把左段吃光了
        assertNull(foldSplit(Rect(1080f, 0f, 2208f, 1840f), bookCrease, margin = 42f))
    }

    @Test
    fun postureFollowsTheFold() {
        fun posture(fold: Fold?, kind: DeviceKind = DeviceKind.Tablet) = AppWindow(kind, 841, 701, fold).posture
        assertEquals(Posture.Normal, posture(null))
        assertEquals(Posture.Flat, posture(flatCrease))
        assertEquals(Posture.Book, posture(bookCrease))
        assertEquals(Posture.Tabletop, posture(tableCrease))
        // 双屏的铰链展平也分页：竖着算书本，横着算桌面
        assertEquals(Posture.Book, posture(hinge))
        assertEquals(Posture.Tabletop, posture(hinge.copy(vertical = false)))
        // 电视不管折痕
        assertEquals(Posture.Normal, posture(bookCrease, DeviceKind.Tv))
    }

    @Test
    fun shortWindowIsAPhoneUnder480() {
        assertTrue(AppWindow(DeviceKind.Phone, 402, 406).short)
        assertFalse(AppWindow(DeviceKind.Phone, 402, 480).short)
        assertFalse(AppWindow(DeviceKind.Tablet, 841, 406).short)
    }
}
