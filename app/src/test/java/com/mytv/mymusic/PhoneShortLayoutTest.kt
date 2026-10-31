package com.mytv.mymusic

import com.mytv.mymusic.ui.player.phoneShortSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手机播放页的矮窗口排法（分屏后约 400×400dp），按 1dp = 1px 计算，另有一组按 2.625px/dp 核对。 */
class PhoneShortLayoutTest {
    /** 顶栏 52；控件区是进度 48 + 8 + 小号按钮组 56。 */
    private val top = 52
    private val controls = 112

    @Test
    fun splitScreenWindowOfAbout402By406() {
        // 上半屏分屏：402×406，状态栏 32
        val s = phoneShortSlots(402, 406 - 32, top, controls, 1f)
        assertEquals(374 - 8 - 112, s.controlsY)
        assertEquals(160, s.cover)
        assertEquals(20, s.coverX)
        // 封面在顶栏下面、控件区上面，右边的文字列有将近 200dp
        assertTrue(s.coverTop >= top)
        assertTrue(s.coverTop + s.cover <= s.controlsY)
        assertEquals(20 + 160 + 16, s.textX)
        assertEquals(402 - 196 - 12, s.textWidth)
    }

    @Test
    fun narrowWindowOf360By420() {
        // 下半屏分屏：360×420，手势条 48
        val s = phoneShortSlots(360, 420 - 48, top, controls, 1f)
        assertEquals(144, s.cover)
        assertTrue(s.coverTop + s.cover <= s.areaBottom)
        assertTrue(s.textWidth >= 160)
    }

    @Test
    fun nothingOverlapsAtAnyShortSize() {
        for (width in 280..599 step 7) {
            for (height in 240..479 step 5) {
                val s = phoneShortSlots(width, height, top, controls, 1f)
                assertTrue("w=$width h=$height", s.cover >= 0)
                assertTrue(s.coverTop >= top)
                assertTrue(s.coverTop + s.cover <= s.areaBottom)
                assertTrue(s.areaBottom <= s.controlsY)
                assertTrue(s.textX >= s.coverX + s.cover)
                assertTrue(s.textX + s.textWidth <= width)
                assertTrue(s.controlsY + controls <= height)
            }
        }
    }

    @Test
    fun coverIsCappedAndCentredInItsRow() {
        val s = phoneShortSlots(560, 470, top, controls, 1f)
        assertEquals(168, s.cover)
        val above = s.coverTop - s.areaTop
        val below = s.areaBottom - (s.coverTop + s.cover)
        assertTrue("above=$above below=$below", kotlin.math.abs(above - below) <= 1)
    }

    @Test
    fun densityOnlyScalesTheResult() {
        val dp = 2.625f
        val one = phoneShortSlots(402, 374, top, controls, 1f)
        val many = phoneShortSlots((402 * dp).toInt(), (374 * dp).toInt(), (top * dp).toInt(), (controls * dp).toInt(), dp)
        assertTrue(kotlin.math.abs(one.cover * dp - many.cover) <= 3)
        assertTrue(kotlin.math.abs(one.textX * dp - many.textX) <= 4)
        assertTrue(many.coverTop + many.cover <= many.controlsY)
    }
}
