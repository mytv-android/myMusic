package com.mytv.mymusic

import com.mytv.mymusic.aggregate.AggregationSettings
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.aggregate.SourcePolicy
import com.mytv.mymusic.aggregate.SourceResolver
import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.aggregate.availabilityOf
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResolverTest {
    private val resolver = SourceResolver(
        repository = MusicRepository(),
        cookies = { emptyMap() },
        settings = { AggregationSettings() },
    )

    private fun song(id: String, provider: String, vip: Boolean = false, url: String = "") = Song(
        id = id,
        name = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        duration = 269,
        source = provider,
        url = url,
        bitrate = 320,
        isVip = vip,
    )

    private fun track(sources: List<TrackSource>) = TrackMatcher.singleSource(song("base", "netease"), "网易云音乐", "netease")
        .copy(sources = sources)

    @Test
    fun previewAndVipSourcesAreSkippedByDefault() {
        val netease = TrackSource("netease", "网易云音乐", song("n1", "netease", url = "https://example.com/a.mp3"))
        val apple = TrackSource("apple", "Apple Music", song("a1", "apple"), isPreview = true)
        val vip = TrackSource("kugou", "酷狗音乐", song("k1", "kugou", vip = true))
        val candidates = resolver.orderCandidates(
            track(listOf(netease, apple, vip)),
            pinnedProvider = null,
            excludeProviders = emptySet(),
            policy = SourcePolicy(allowPreview = false),
        )
        assertEquals(listOf("netease"), candidates.map { it.providerId })
    }

    @Test
    fun previewSourceCanBeEnabled() {
        val netease = TrackSource("netease", "网易云音乐", song("n1", "netease", url = "https://example.com/a.mp3"))
        val apple = TrackSource("apple", "Apple Music", song("a1", "apple"), isPreview = true)
        val candidates = resolver.orderCandidates(
            track(listOf(netease, apple)),
            pinnedProvider = null,
            excludeProviders = emptySet(),
            policy = SourcePolicy(allowPreview = true),
        )
        assertEquals(listOf("netease", "apple"), candidates.map { it.providerId })
    }

    @Test
    fun explicitPinnedPreviewSourceIsKeptFirst() {
        val netease = TrackSource("netease", "网易云音乐", song("n1", "netease", url = "https://example.com/a.mp3"))
        val apple = TrackSource("apple", "Apple Music", song("a1", "apple"), isPreview = true)
        val candidates = resolver.orderCandidates(
            track(listOf(netease, apple)),
            pinnedProvider = "apple",
            excludeProviders = emptySet(),
            policy = SourcePolicy(allowPreview = false, pinnedProvider = "apple"),
        )
        assertEquals("apple", candidates.first().providerId)
    }

    @Test
    fun vipSourceWithoutCookieIsReportedAsVipOnly() {
        val vip = TrackSource("kugou", "酷狗音乐", song("k1", "kugou", vip = true))
        assertEquals(Availability.VipOnly, availabilityOf(vip, ""))
    }

    @Test
    fun excludedProviderIsNotReturned() {
        val netease = TrackSource("netease", "网易云音乐", song("n1", "netease", url = "https://example.com/a.mp3"))
        val qq = TrackSource("qq", "QQ 音乐", song("q1", "qq", url = "https://example.com/b.mp3"))
        val candidates = resolver.orderCandidates(
            track(listOf(netease, qq)),
            pinnedProvider = null,
            excludeProviders = setOf("qq"),
            policy = SourcePolicy(),
        )
        assertFalse(candidates.any { it.providerId == "qq" })
        assertTrue(candidates.any { it.providerId == "netease" })
    }
}
