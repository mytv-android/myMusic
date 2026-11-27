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
import com.mytv.mymusic.data.native.AudioQuality
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
    private class FakeRepository(
        private val streams: Map<String, PlayableStream>,
        /** 按音质偏好返回流；返回 null 表示这个平台不区分档位，走 [streams]。 */
        private val onQuality: (Song, AudioQuality) -> PlayableStream? = { _, _ -> null },
    ) : MusicRepository() {
        override suspend fun playableStream(
            song: Song,
            cookie: String,
            workDir: File,
            quality: AudioQuality,
        ): PlayableStream = onQuality(song, quality) ?: streams[song.key] ?: PlayableStream(url = "")
    }

    private fun previewResolver(
        streams: Map<String, PlayableStream>,
        cookies: Map<String, String> = mapOf("soda" to "sid=1"),
        onQuality: (Song, AudioQuality) -> PlayableStream? = { _, _ -> null },
    ) = SourceResolver(
        repository = FakeRepository(streams, onQuality),
        cookies = { cookies },
        settings = { AggregationSettings() },
        workDir = { File(".") },
    )

    /** 汽水会员曲：静态可用性查不出试听（签名器在），取流才带回顾片段标记。 */
    private fun sodaVipSource() = TrackSource("soda", "汽水音乐", song("s1", "soda", vip = true))

    private fun neteaseSource() = TrackSource("netease", "网易云音乐", song("n1", "netease", url = "https://example.com/full.mp3"))

    // ---------- 音质偏好：锁了档位拿不到时不能整首播不出来 ----------

    @Test
    fun pinnedQualityFallsBackToAutoSoTheSongStillPlays() = runBlocking {
        var autoRetried = false
        val resolver = previewResolver(
            emptyMap(),
            onQuality = { _, quality ->
                if (quality == AudioQuality.Auto) {
                    autoRetried = true
                    PlayableStream(url = "https://example.com/netease-320.mp3", bitrate = 320)
                } else {
                    // 平台给不了这一档：没上架无损、账号没有会员
                    PlayableStream(url = "")
                }
            },
        )
        val resolution = resolver.resolve(
            track(listOf(neteaseSource())),
            policy = SourcePolicy(priority = listOf("netease")),
        )
        val ready = resolution as Resolution.Ready
        assertEquals("https://example.com/netease-320.mp3", ready.url)
        assertTrue("锁了音质后应退回自动档再取一次", autoRetried)
    }

    @Test
    fun autoPreferenceDoesNotRetry() = runBlocking {
        var calls = 0
        val resolver = previewResolver(
            emptyMap(),
            onQuality = { _, _ -> calls++; PlayableStream(url = "https://example.com/a.mp3") },
        )
        resolver.resolve(track(listOf(neteaseSource())), policy = SourcePolicy(priority = listOf("netease")))
        assertEquals(1, calls)
    }

    @Test
    fun storedSongWithoutUrlStillResolves() = runBlocking {
        // 歌单/收藏里存下来的歌不带取流地址（Song.forStorage 会剥掉），不能因此判成不可用
        val stored = TrackSource("kugou", "酷狗音乐", song("k1", "kugou"))
        val resolver = previewResolver(mapOf("kugou:k1" to PlayableStream(url = "https://example.com/kugou.mp3")))
        val resolution = resolver.resolve(
            track(listOf(stored)),
            policy = SourcePolicy(priority = listOf("kugou")),
        )
        val ready = resolution as Resolution.Ready
        assertEquals("kugou", ready.source.providerId)
        assertEquals("https://example.com/kugou.mp3", ready.url)
    }

    @Test
    fun invalidateForcesAFreshStreamOnRetry() = runBlocking {
        // 下载撞到 403 时的重试：先 invalidate 再解析，否则命中的还是被拒的那条地址
        var issued = "https://example.com/stale.mp3"
        var calls = 0
        val resolver = previewResolver(
            emptyMap(),
            onQuality = { _, _ -> calls++; PlayableStream(url = issued) },
        )
        val source = neteaseSource()
        val first = resolver.resolve(track(listOf(source)), policy = SourcePolicy(priority = listOf("netease")))
        assertEquals("https://example.com/stale.mp3", (first as Resolution.Ready).url)
        // 不清缓存：同一条地址继续用，重试没有意义
        val cached = resolver.resolve(track(listOf(source)), policy = SourcePolicy(priority = listOf("netease")))
        assertEquals("https://example.com/stale.mp3", (cached as Resolution.Ready).url)
        assertEquals(1, calls)
        // 清掉以后再解析：重新取流拿到新地址
        issued = "https://example.com/fresh.mp3"
        resolver.invalidate(source)
        val retried = resolver.resolve(track(listOf(source)), policy = SourcePolicy(priority = listOf("netease")))
        assertEquals("https://example.com/fresh.mp3", (retried as Resolution.Ready).url)
        assertEquals(2, calls)
    }

    @Test
    fun changingQualityPreferenceInvalidatesCachedUrl() = runBlocking {
        var preference = AudioQuality.Auto
        val requested = mutableListOf<AudioQuality>()
        val resolver = SourceResolver(
            repository = FakeRepository(
                emptyMap(),
                onQuality = { _, quality ->
                    requested += quality
                    PlayableStream(url = "https://example.com/${quality.id}.mp3")
                },
            ),
            cookies = { emptyMap() },
            settings = { AggregationSettings() },
            workDir = { File(".") },
            quality = { preference },
        )
        val target = track(listOf(neteaseSource()))
        resolver.resolve(target, policy = SourcePolicy(priority = listOf("netease")))
        resolver.resolve(target, policy = SourcePolicy(priority = listOf("netease")))
        // 偏好没变：第二次命中地址缓存，不再取流
        assertEquals(1, requested.size)

        preference = AudioQuality.Lossless
        val switched = resolver.resolve(target, policy = SourcePolicy(priority = listOf("netease")))
        // 偏好变了必须重新取流，否则用户选了无损还在播缓存里的低档地址
        assertEquals(2, requested.size)
        assertEquals(AudioQuality.Lossless, requested.last())
        assertEquals("https://example.com/lossless.mp3", (switched as Resolution.Ready).url)
    }

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

    // ---------- buildPolicy：自动播放只走已启用的音乐源 ----------

    private fun policyResolver(settings: AggregationSettings) = SourceResolver(
        repository = MusicRepository(),
        cookies = { emptyMap() },
        settings = { settings },
        workDir = { File(".") },
    )

    @Test
    fun buildPolicyAllowsOnlyEnabledProvidersAndLocal() {
        val netease = TrackSource("netease", "网易云音乐", song("n1", "netease", url = "https://example.com/a.mp3"))
        val apple = TrackSource("apple", "Apple Music", song("a1", "apple", url = "https://example.com/b.mp3"))
        // 来源平台 Apple 没有启用：候选里不能出现它
        val target = track(listOf(apple, netease))
        val resolver = policyResolver(AggregationSettings(enabledProviders = listOf("qq", "netease")))
        val policy = resolver.buildPolicy(target)
        assertEquals(setOf("netease", "local"), policy.allowedProviders)
        assertEquals(listOf("netease"), policy.priority)
        assertEquals(listOf("netease"), resolver.orderCandidates(target, null, emptySet(), policy).map { it.providerId })
    }

    @Test
    fun disabledOriginIsNotReturnedByOrderCandidates() {
        val apple = TrackSource("apple", "Apple Music", song("a1", "apple", url = "https://example.com/b.mp3"))
        val target = track(listOf(apple))
        val resolver = policyResolver(AggregationSettings(enabledProviders = listOf("qq")))
        val policy = resolver.buildPolicy(target)
        assertTrue(policy.priority.isEmpty())
        assertTrue(resolver.orderCandidates(target, null, emptySet(), policy).isEmpty())
    }

    @Test
    fun enabledOriginIsPlacedFirstByBuildPolicy() {
        val netease = TrackSource("netease", "网易云音乐", song("n1", "netease", url = "https://example.com/a.mp3"))
        val kugou = TrackSource("kugou", "酷狗音乐", song("k1", "kugou", url = "https://example.com/c.mp3"))
        val target = track(listOf(kugou, netease))
        val resolver = policyResolver(AggregationSettings(enabledProviders = listOf("netease", "kugou")))
        val policy = resolver.buildPolicy(target)
        assertEquals(listOf("kugou", "netease"), policy.priority)
        assertEquals(listOf("kugou", "netease"), resolver.orderCandidates(target, null, emptySet(), policy).map { it.providerId })
    }

    // ---------- 手写策略：云听这类没有开关的音源由调用方显式放行 ----------

    @Test
    fun explicitlyAllowedProviderResolvesEvenWhenNotEnabled() = runBlocking {
        // 云听没有搜索能力，「音乐源」里没有它的开关，进不了启用列表；
        // 播放层按「只能走云听」手写策略显式放行，解析要能拿到直播地址
        val resolver = previewResolver(
            mapOf(
                "yunting:live:639" to PlayableStream(
                    url = "http://ytcast2.radio.cn/110/radios/40639/index.m3u8",
                    ext = "m3u8",
                ),
            ),
        )
        val yunting = TrackSource("yunting", "云听", song("live:639", "yunting", ext = "m3u8"))
        val policy = SourcePolicy(priority = listOf("yunting"), allowedProviders = setOf("yunting", "local"))
        val ready = resolver.resolve(track(listOf(yunting)), policy = policy) as Resolution.Ready
        assertEquals("yunting", ready.source.providerId)
        assertEquals("m3u8", ready.source.song.ext)
        assertEquals("http://ytcast2.radio.cn/110/radios/40639/index.m3u8", ready.url)
    }
}
