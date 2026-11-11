package com.mytv.mymusic

import com.mytv.mymusic.data.native.NativeProviders
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeProvidersTest {
    @Test
    fun allNativeProvidersAreRegistered() {
        val expected = setOf(
            "netease",
            "qq",
            "kugou",
            "kuwo",
            "migu",
            "qianqian",
            "soda",
            "fivesing",
            "jamendo",
            "joox",
            "bilibili",
            "apple",
            "youtubemusic",
            "yunting",
        )
        val providers = NativeProviders.all()
        assertEquals(expected, providers.map { it.info.id }.toSet())
        assertEquals(expected.size, providers.size)
    }

    @Test
    fun playlistWriteCapabilitiesMatchImplementedProviders() {
        val write = NativeProviders.all().filter { it.info.playlistWrite }.map { it.info.id }.toSet()
        assertEquals(setOf("netease", "qq", "kugou"), write)
        val reorder = NativeProviders.all().filter { it.info.playlistReorder }.map { it.info.id }.toSet()
        assertEquals(setOf("qq", "kugou"), reorder)
    }
}
