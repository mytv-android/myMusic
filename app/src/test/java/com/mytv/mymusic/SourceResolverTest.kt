package com.mytv.mymusic

import com.mytv.mymusic.aggregate.AggregationSettings
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.aggregate.Resolution
import com.mytv.mymusic.aggregate.SourcePolicy
import com.mytv.mymusic.aggregate.SourceResolver
import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.aggregate.availabilityOf
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.PlayableStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceResolverTest {
    private val resolver = SourceResolver(
        repository = MusicRepository(),
        cookies = { emptyMap() },
        settings = { AggregationSettings() },
        workDir = { java.io.File(".") },
    )

    private fun song(id: String, provider: String, vip: Boolean = false, url: String = "", bitrate: Int = 320, ext: String = "") = Song(
        id = id,
        name = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        duration = 269,
        source = provider,
        url = url,
        bitrate = bitrate,
        ext = ext,
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

    @Test
    fun qualityFirstPutsLosslessSourceAheadOfPlatformPriority() {
        // 平台顺序把网易云排在前面，但它的源只有 320k；酷狗是 FLAC
        val netease = TrackSource("netease", "网易云音乐", song("n1", "netease", url = "https://example.com/a.mp3", bitrate = 320, ext = "mp3"))
        val kugou = TrackSource("kugou", "酷狗音乐", song("k1", "kugou", url = "https://example.com/b.flac", bitrate = 999, ext = "flac"))
        val sources = listOf(netease, kugou)
        val priority = SourcePolicy(priority = listOf("netease", "kugou"))

        val normal = resolver.orderCandidates(
            track(sources),
            pinnedProvider = null,
            excludeProviders = emptySet(),
            policy = priority,
        )
        assertEquals(listOf("netease", "kugou"), normal.map { it.providerId })

        // VIP「无损优先」：FLAC 排到 320k 前面
        val vip = resolver.orderCandidates(
            track(sources),
            pinnedProvider = null,
            excludeProviders = emptySet(),
            policy = priority.copy(qualityFirst = true),
        )
        assertEquals(listOf("kugou", "netease"), vip.map { it.providerId })
    }

    @Test
    fun qualityFirstKeepsPlatformOrderWhenBothLossless() {
        val netease = TrackSource("netease", "网易云音乐", song("n1", "netease", url = "https://example.com/a.flac", bitrate = 999, ext = "flac"))
        val kugou = TrackSource("kugou", "酷狗音乐", song("k1", "kugou", url = "https://example.com/b.flac", bitrate = 999, ext = "flac"))
        val candidates = resolver.orderCandidates(
            track(listOf(kugou, netease)),
            pinnedProvider = null,
            excludeProviders = emptySet(),
            policy = SourcePolicy(priority = listOf("netease", "kugou"), qualityFirst = true),
        )
        assertEquals(listOf("netease", "kugou"), candidates.map { it.providerId })
    }

    @Test
    fun priorityModeIgnoresBitrateWithoutVip() {
        // 「优先级」播放模式下码率不参与排序，同平台优先级按匹配度来
        val netease = TrackSource("netease", "网易云音乐", song("n1", "netease", url = "https://example.com/a.mp3", bitrate = 128), matchScore = 0.9f)
        val kugou = TrackSource("kugou", "酷狗音乐", song("k1", "kugou", url = "https://example.com/b.mp3", bitrate = 320), matchScore = 0.8f)
        val candidates = resolver.orderCandidates(
            track(listOf(netease, kugou)),
            pinnedProvider = null,
            excludeProviders = emptySet(),
            policy = SourcePolicy(mode = com.mytv.mymusic.aggregate.PlaybackMode.Priority, priority = listOf("netease", "kugou")),
        )
        assertEquals(listOf("netease", "kugou"), candidates.map { it.providerId })
    }

    // ---------- resolve()：取流后才知道是试听（汽水会员曲）的换源行为 ----------

    /** 按平台返回固定流；没配置的平台返回空地址（等价于解析失败）。 */
    private class FakeRepository(private val streams: Map<String, PlayableStream>) : MusicRepository() {
        override suspend fun playableStream(song: Song, cookie: String, workDir: File): PlayableStream =
            streams[song.key] ?: PlayableStream(url = "")
    }

    /** 汽水会员曲：静态可用性查不出试听（签名器在），取流才带回顾片段标记。 */
    private fun sodaVipSource() = TrackSource("soda", "汽水音乐", song("s1", "soda", vip = true))

    private fun neteaseSource() = TrackSource("netease", "网易云音乐", song("n1", "netease", url = "https://example.com/full.mp3"))

    private fun previewResolver(streams: Map<String, PlayableStream>, cookies: Map<String, String> = mapOf("soda" to "sid=1")) = SourceResolver(
        repository = FakeRepository(streams),
        cookies = { cookies },
        settings = { AggregationSettings() },
        workDir = { File(".") },
    )

    @Test
    fun previewStreamFallsThroughToNextPlatform() = runBlocking {
        val resolver = previewResolver(
            mapOf(
                "soda:s1" to PlayableStream(url = "https://example.com/soda-trial.mp3", preview = true),
                "netease:n1" to PlayableStream(url = "https://example.com/netease-full.mp3"),
            ),
        )
        val resolution = resolver.resolve(
            track(listOf(sodaVipSource(), neteaseSource())),
            policy = SourcePolicy(priority = listOf("soda", "netease"), allowPreview = false),
        )
        val ready = resolution as Resolution.Ready
        assertEquals("netease", ready.source.providerId)
        assertEquals("https://example.com/netease-full.mp3", ready.url)
        assertFalse(ready.isPreview)
    }

    @Test
    fun previewOnlyAttemptReportsTrialReason() = runBlocking {
        val resolver = previewResolver(
            mapOf("soda:s1" to PlayableStream(url = "https://example.com/soda-trial.mp3", preview = true)),
        )
        val resolution = resolver.resolve(
            track(listOf(sodaVipSource())),
            policy = SourcePolicy(priority = listOf("soda"), allowPreview = false),
        )
        val none = resolution as Resolution.None
        assertEquals(listOf("仅提供试听"), none.attempts.map { it.reason })
    }

    @Test
    fun previewStreamPlaysWhenPreviewAllowed() = runBlocking {
        val resolver = previewResolver(
            mapOf("soda:s1" to PlayableStream(url = "https://example.com/soda-trial.mp3", preview = true)),
        )
        val resolution = resolver.resolve(
            track(listOf(sodaVipSource())),
            policy = SourcePolicy(priority = listOf("soda"), allowPreview = true),
        )
        val ready = resolution as Resolution.Ready
        assertEquals("soda", ready.source.providerId)
        assertTrue(ready.isPreview)
    }

    @Test
    fun explicitPinnedPreviewStreamStillPlays() = runBlocking {
        val resolver = previewResolver(
            mapOf("soda:s1" to PlayableStream(url = "https://example.com/soda-trial.mp3", preview = true)),
        )
        val resolution = resolver.resolve(
            track(listOf(sodaVipSource(), neteaseSource())),
            pinnedProvider = "soda",
            policy = SourcePolicy(priority = listOf("soda", "netease"), pinnedProvider = "soda", allowPreview = false),
        )
        val ready = resolution as Resolution.Ready
        assertEquals("soda", ready.source.providerId)
        assertTrue(ready.isPreview)
    }

    @Test
    fun cachedPreviewIsSkippedAfterPreviewDisallowed() = runBlocking {
        val resolver = previewResolver(
            mapOf(
                "soda:s1" to PlayableStream(url = "https://example.com/soda-trial.mp3", preview = true),
                "netease:n1" to PlayableStream(url = "https://example.com/netease-full.mp3"),
            ),
        )
        val track = track(listOf(sodaVipSource(), neteaseSource()))
        // 第一次允许试听：汽水片段进地址缓存
        val first = resolver.resolve(track, policy = SourcePolicy(priority = listOf("soda", "netease"), allowPreview = true))
        assertEquals("soda", (first as Resolution.Ready).source.providerId)
        // 关掉「允许试听」后再解析：命中的缓存带试听标记，要跳过去换下一个平台
        val second = resolver.resolve(track, policy = SourcePolicy(priority = listOf("soda", "netease"), allowPreview = false))
        val ready = second as Resolution.Ready
        assertEquals("netease", ready.source.providerId)
        assertFalse(ready.isPreview)
    }
}
