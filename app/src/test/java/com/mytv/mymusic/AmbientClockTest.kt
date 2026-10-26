package com.mytv.mymusic

import com.mytv.mymusic.ui.player.clockDescription
import com.mytv.mymusic.ui.player.clockText
import com.mytv.mymusic.ui.player.millisUntilNextMinute
import org.junit.Assert.assertEquals
import org.junit.Test

/** 电视环境模式时钟：12/24 小时制格式、读屏描述和整分钟刷新的等待时间。 */
class AmbientClockTest {
    @Test
    fun twentyFourHourClockIsZeroPadded() {
        assertEquals("21:30", clockText(21, 30, use24Hour = true))
        assertEquals("09:05", clockText(9, 5, use24Hour = true))
        assertEquals("00:00", clockText(0, 0, use24Hour = true))
        assertEquals("23:59", clockText(23, 59, use24Hour = true))
    }

    @Test
    fun twelveHourClockDropsLeadingZeroAndMapsMidnightAndNoonToTwelve() {
        assertEquals("9:30", clockText(21, 30, use24Hour = false))
        assertEquals("9:05", clockText(9, 5, use24Hour = false))
        assertEquals("12:00", clockText(0, 0, use24Hour = false))
        assertEquals("12:15", clockText(12, 15, use24Hour = false))
        assertEquals("11:59", clockText(23, 59, use24Hour = false))
    }

    @Test
    fun descriptionAddsThePeriodOnlyInTwelveHourMode() {
        assertEquals("当前时间 21:30", clockDescription(21, 30, use24Hour = true))
        assertEquals("当前时间 下午9:30", clockDescription(21, 30, use24Hour = false))
        assertEquals("当前时间 上午12:05", clockDescription(0, 5, use24Hour = false))
        assertEquals("当前时间 下午12:00", clockDescription(12, 0, use24Hour = false))
    }

    @Test
    fun waitsUntilTheNextMinuteBoundary() {
        assertEquals(60_000L, millisUntilNextMinute(0L))
        assertEquals(1L, millisUntilNextMinute(59_999L))
        assertEquals(60_000L, millisUntilNextMinute(60_000L))
        assertEquals(29_500L, millisUntilNextMinute(90_500L))
        // 2026-10-26 21:30:12.345 UTC
        assertEquals(47_655L, millisUntilNextMinute(1_793_050_212_345L))
        assertEquals(1L, millisUntilNextMinute(-1L))
    }
}
