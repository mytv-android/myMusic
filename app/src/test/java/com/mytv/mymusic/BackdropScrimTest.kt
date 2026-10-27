package com.mytv.mymusic

import com.mytv.mymusic.ui.player.BackdropScrim
import com.mytv.mymusic.ui.player.ScrimStops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 背景遮罩的几种样式对照原型，样式之间逐项插值。 */
class BackdropScrimTest {
    @Test
    fun variantsMatchThePrototype() {
        // 08-proto.js：手机 [.12, .28, .84]，进入歌词页 [.34, .42, .86]
        assertEquals(ScrimStops(top = 0.12f, middle = 0.28f, bottom = 0.84f), BackdropScrim.Phone.stops)
        assertEquals(ScrimStops(top = 0.34f, middle = 0.42f, bottom = 0.86f), BackdropScrim.PhoneLyrics.stops)
        // 02-mock.css：.m-scrim.wide 和 .m-scrim.tv（横向 22/56@50%/78，底部 70% 往上 40% 淡出）
        assertEquals(ScrimStops(start = 0.18f, center = 0.40f, end = 0.74f), BackdropScrim.Wide.stops)
        // 平板专注歌词：同样是横向，整体比 Wide 暗，满屏大字也看得清
        assertEquals(ScrimStops(start = 0.36f, center = 0.53f, end = 0.80f), BackdropScrim.WideFocus.stops)
        assertEquals(
            ScrimStops(start = 0.22f, center = 0.56f, end = 0.78f, centerAt = 0.5f, footer = 0.70f),
            BackdropScrim.Tv.stops,
        )
    }

    @Test
    fun blendIsExactAtTheEndsAndMonotoneBetween() {
        val phone = BackdropScrim.Phone.stops
        val lyrics = BackdropScrim.PhoneLyrics.stops
        assertEquals(phone, phone.lerp(lyrics, 0f))
        assertEquals(lyrics, phone.lerp(lyrics, 1f))
        var previous = phone.top
        for (k in 1..10) {
            val top = phone.lerp(lyrics, k / 10f).top
            assertTrue(top >= previous)
            previous = top
        }
        // 手机转宽屏：竖向那层淡出、横向那层淡入，中途两层各剩一半
        val half = phone.lerp(BackdropScrim.Wide.stops, 0.5f)
        assertEquals(0.42f, half.bottom, 1e-6f)
        assertEquals(0.37f, half.end, 1e-6f)
        assertEquals(0f, half.footer, 0f)
    }

    @Test
    fun wideFocusIsDarkerThanWideEverywhereAndStaysHorizontal() {
        val wide = BackdropScrim.Wide.stops
        val focus = BackdropScrim.WideFocus.stops
        assertTrue(focus.start > wide.start && focus.center > wide.center && focus.end > wide.end)
        assertEquals(0f, focus.top + focus.middle + focus.bottom + focus.footer, 0f)
        // 进出专注歌词时逐项淡变，不会先变亮再变暗
        for (k in 0..10) {
            val mid = wide.lerp(focus, k / 10f)
            assertTrue(mid.center >= wide.center && mid.center <= focus.center)
        }
    }
}
