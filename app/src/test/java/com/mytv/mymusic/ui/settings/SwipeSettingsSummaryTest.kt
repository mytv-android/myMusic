package com.mytv.mymusic.ui.settings

import com.mytv.mymusic.data.SwipeAction
import com.mytv.mymusic.data.SwipePrefs
import com.mytv.mymusic.data.SwipeSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeSettingsSummaryTest {
    @Test
    fun `default summary names the right swipe buttons and the full action`() {
        assertEquals("右滑 下载、加到歌单 · 拉到头 收藏", swipeSummary(SwipePrefs()))
    }

    @Test
    fun `switched off says so`() {
        assertEquals("已关闭", swipeSummary(SwipePrefs(enabled = false)))
    }

    @Test
    fun `no buttons and nothing at full`() {
        assertEquals("右滑 无按钮", swipeSummary(SwipePrefs(start = emptyList(), startFull = SwipeAction.None)))
    }

    @Test
    fun `right to left swaps which side is called right`() {
        assertEquals("向右滑", swipeSideTitle(SwipeSide.Start, rtl = false))
        assertEquals("向左滑", swipeSideTitle(SwipeSide.End, rtl = false))
        assertEquals("向左滑", swipeSideTitle(SwipeSide.Start, rtl = true))
        assertEquals("左滑 下载、加到歌单 · 拉到头 收藏", swipeSummary(SwipePrefs(), rtl = true))
    }

    @Test
    fun `tv settings do not list swipe`() {
        assertFalse(SettingsSection.groupsFor(tv = true).flatten().contains(SettingsSection.Swipe))
        assertTrue(SettingsSection.groupsFor(tv = false).flatten().contains(SettingsSection.Swipe))
    }
}
