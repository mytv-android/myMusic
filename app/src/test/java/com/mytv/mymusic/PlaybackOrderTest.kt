package com.mytv.mymusic

import com.mytv.mymusic.aggregate.AggregationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 自动播放候选顺序：pinned → 已启用的来源平台 → 其余已启用平台；未启用的来源平台不参与。 */
class PlaybackOrderTest {
    private val settings = AggregationSettings(enabledProviders = listOf("netease", "qq", "kugou", "lx"))

    @Test
    fun originPlatformComesFirstWhenEnabled() {
        val order = settings.playbackOrder(listOf("qq", "netease", "kugou"), "kugou", null)
        assertEquals(listOf("kugou", "netease", "qq"), order)
    }

    @Test
    fun disabledOriginIsNotIncluded() {
        val order = settings.playbackOrder(listOf("apple", "netease", "qq"), "apple", null)
        assertEquals(listOf("netease", "qq"), order)
        assertFalse("apple" in order)
    }

    @Test
    fun pinnedPlatformIsAlwaysFirst() {
        val order = settings.playbackOrder(listOf("netease", "qq"), "qq", "kugou")
        assertEquals(listOf("kugou", "qq", "netease"), order)
    }

    @Test
    fun enabledRelativeOrderIsKept() {
        val order = settings.playbackOrder(listOf("lx", "kugou", "qq", "netease"), null, null)
        assertEquals(listOf("netease", "qq", "kugou", "lx"), order)
    }

    @Test
    fun disabledPlatformsAreNotBroughtBackByFallback() {
        // providerPriority 是默认顺序表，不能拿它把没启用的平台兜回自动播放
        val order = settings.playbackOrder(listOf("apple"), null, null)
        assertEquals(emptyList<String>(), order)
    }
}
