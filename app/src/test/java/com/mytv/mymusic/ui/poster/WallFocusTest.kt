package com.mytv.mymusic.ui.poster

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallFocusTest {
    @Test
    fun keysMapToWallActions() {
        assertEquals(WallKey.Left, wallKeyOf(Key.DirectionLeft))
        assertEquals(WallKey.Down, wallKeyOf(Key.DirectionDown))
        assertEquals(WallKey.Confirm, wallKeyOf(Key.DirectionCenter))
        assertEquals(WallKey.Confirm, wallKeyOf(Key.Enter))
        assertEquals(WallKey.Menu, wallKeyOf(Key.Menu))
        assertEquals(WallKey.Passthrough, wallKeyOf(Key.Back))
        assertEquals(WallKey.Passthrough, wallKeyOf(Key.VolumeUp))
        assertEquals(WallKey.Passthrough, wallKeyOf(Key.MediaPlayPause))
        assertEquals(WallKey.Other, wallKeyOf(Key.A))
        assertTrue(WallKey.Up.isArrow)
        assertFalse(WallKey.Confirm.isArrow)
        assertEquals(-1, WallKey.Up.dy)
        assertEquals(1, WallKey.Right.dx)
    }

    @Test
    fun confirmActsOnReleaseUnlessHeldToLongPress() {
        val c = ConfirmPress()
        assertEquals(ConfirmResult.None, c.onDown(0, false))
        assertEquals(ConfirmResult.Short, c.onUp())

        assertEquals(ConfirmResult.None, c.onDown(0, false))
        assertEquals(ConfirmResult.Long, c.onDown(1, true))
        assertEquals(ConfirmResult.None, c.onDown(2, false))
        assertEquals(ConfirmResult.None, c.onUp())

        // 松开的事件丢了（焦点被切走）：下一次按下重新算
        assertEquals(ConfirmResult.None, c.onDown(0, false))
        c.cancel()
        assertEquals(ConfirmResult.None, c.onUp())
        // 没按下就松开（按下发生在别处）不算短按
        assertEquals(ConfirmResult.None, c.onUp())
    }

    @Test
    fun backStepsFollowTheInteractionTable() {
        assertEquals(WallBackStep.Wake, wallBackStep(drifting = true, focusInCard = true, grownIsOther = true, hasFocus = true, isTv = true))
        assertEquals(WallBackStep.ReturnFocus, wallBackStep(false, focusInCard = true, grownIsOther = true, hasFocus = true, isTv = true))
        assertEquals(WallBackStep.Collapse, wallBackStep(false, false, grownIsOther = true, hasFocus = true, isTv = true))
        assertEquals(WallBackStep.ClearFocus, wallBackStep(false, false, grownIsOther = false, hasFocus = true, isTv = true))
        assertEquals(WallBackStep.Leave, wallBackStep(false, false, grownIsOther = false, hasFocus = false, isTv = true))
        // 手机：不收大卡，直接离开（接了键盘、有焦点时先清焦点）
        assertEquals(WallBackStep.Leave, wallBackStep(false, false, grownIsOther = true, hasFocus = false, isTv = false))
        assertEquals(WallBackStep.ClearFocus, wallBackStep(false, false, grownIsOther = true, hasFocus = true, isTv = false))
    }

    @Test
    fun slotAtHitsEveryTileCentreAndMissesGaps() {
        for (m in WallMetrics.entries) {
            val scene = WallScene(m, fieldFor(30), grown = SlotPos(1, 0, 4))
            for (by in -1..1) for (bx in -1..2) {
                val rs = scene.rectsOf(bx, by)
                for (s in 0 until WallLayout.SLOTS) {
                    val r = scene.world(bx, by, rs[s])
                    assertEquals(SlotPos(bx, by, s), scene.slotAt(WallPoint(r.centerX, r.centerY)))
                    assertEquals(SlotPos(bx, by, s), scene.slotAt(WallPoint(r.x + 0.5f, r.y + 0.5f)))
                    // 右下角外的缝
                    assertNull(scene.slotAt(WallPoint(r.right + m.gap / 2, r.bottom + m.gap / 2)))
                }
            }
        }
    }

    @Test
    fun slotAtMissesOutsideTheFeedBand() {
        val scene = WallScene(WallMetrics.Tv, feedFor(WallMetrics.Tv, 15))
        assertNull(scene.slotAt(WallPoint(-10f, 10f)))
        assertNull(scene.slotAt(WallPoint(10f, WallMetrics.Tv.blockHeight + 10f)))
        // 第 33 格之后整块都是空的
        assertNull(scene.slotAt(WallPoint(3 * WallMetrics.Tv.blockWidth + 30f, 30f)))
        assertEquals(SlotPos(0, 0, 0), scene.slotAt(WallPoint(20f, 20f)))
    }

    @Test
    fun followTargetIsTheCopyNearestTheScreenCentre() {
        val scene = WallScene(WallMetrics.Phone, fieldFor(5))
        val view = WallViewport(360f, 800f, 84f, 92f)
        assertNull(scene.followTarget(-1, WallPoint(0f, 0f), view))
        val cam = WallPoint(1000f, 2000f)
        val p = scene.followTarget(3, cam, view)!!
        assertEquals(3, scene.songAt(p.bx, p.by, p.slot))
        assertEquals(p, scene.nearestCopy(3, cam, WallPoint(180f, 400f)))
    }

    @Test
    fun ghostsOnlyExistInTheFeedBand() {
        assertTrue(feedFor(WallMetrics.Phone, 15).isGhost(15))
        assertFalse((feedFor(WallMetrics.Phone, 15) as WallField).isGhost(14))
        assertFalse((fieldFor(15) as WallField).isGhost(20))
    }
}
