package com.mytv.mymusic

import com.mytv.mymusic.ui.charts.findFocusedHero
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartsHeroTest {
    private data class Item(val key: String, val label: String)

    @Test
    fun focusedHeroUsesLatestItemByKey() {
        val latest = listOf(Item("a", "新"), Item("b", "B2"))
        val recommended = listOf(Item("r", "推荐"))

        val focused = findFocusedHero("a", listOf(latest, recommended)) { it.key }
        assertEquals("新", focused?.label)
    }

    @Test
    fun searchesAllGroupsBeforeFallback() {
        val charts = listOf(Item("a", "榜单"))
        val history = listOf(Item("h", "最近播放"))

        assertEquals("最近播放", findFocusedHero("h", listOf(charts, history)) { it.key }?.label)
    }

    @Test
    fun staleKeyFallsBackToFirstAvailableGroup() {
        val charts = listOf(Item("a", "榜单"))
        val hot = listOf(Item("hot", "热歌"))

        assertEquals("榜单", findFocusedHero("missing", listOf(charts, hot)) { it.key }?.label)
        assertNull(findFocusedHero<Item>("missing", listOf(emptyList(), emptyList())) { it.key })
    }
}
