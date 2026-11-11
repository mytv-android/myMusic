package com.mytv.mymusic

import com.mytv.mymusic.data.native.MHubProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MHubProviderTest {
    private val provider = MHubProvider()

    @Test
    fun detectsAppleLinks() {
        assertEquals(
            "applemusic" to "1440833098",
            provider.detectLink("https://music.apple.com/cn/album/name/1440833097?i=1440833098"),
        )
        assertEquals(
            "applemusic" to "1440833098",
            provider.detectLink("https://music.apple.com/us/song/name/1440833098"),
        )
        assertEquals("applemusic" to "1440833098", provider.detectLink("1440833098"))
        assertEquals(
            "applemusic" to "https://music.apple.com/us/album/name/1440833097",
            provider.detectLink("https://music.apple.com/us/album/name/1440833097"),
        )
    }

    @Test
    fun detectsYouTubeAndNetEaseLinks() {
        assertEquals(
            "youtubemusic" to "DxpndDEAqGk",
            provider.detectLink("https://music.youtube.com/watch?v=DxpndDEAqGk"),
        )
        assertEquals(
            "netease" to "https://music.163.com/playlist?id=123",
            provider.detectLink("https://music.163.com/playlist?id=123"),
        )
    }

    @Test
    fun rejectsUnknownLinks() {
        assertTrue(runCatching { provider.detectLink("https://example.com/song") }.isFailure)
    }
}
