package com.mytv.mymusic

import com.mytv.mymusic.aggregate.AggregationSettings
import com.mytv.mymusic.aggregate.Resolution
import com.mytv.mymusic.aggregate.SourcePolicy
import com.mytv.mymusic.aggregate.SourceResolver
import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.lx.LxPlaybackResult
import com.mytv.mymusic.data.native.AudioQuality
import com.mytv.mymusic.data.native.PlayableStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class LxSourceResolverTest {
    private class CustomRepository : MusicRepository() {
        var fail = false
        var generation = 0L
        var supported = setOf("kugou")
        // 并发探测会同时自增，普通 Int 会丢更新
        val nativeCalls = AtomicInteger()
        val lxCalls = AtomicInteger()
        var songSelector: (List<Song>) -> Song = { it.first() }

        override fun supportsCustomSource(provider: String): Boolean = provider in supported
        override fun customSourceGeneration(): Long = generation

        override suspend fun playableStream(song: Song, cookie: String, workDir: File, quality: AudioQuality): PlayableStream {
            nativeCalls.incrementAndGet()
            return PlayableStream(url = "https://native.example.com/${song.source}.mp3", ext = "mp3", bitrate = 320)
        }

        override suspend fun playableLxStream(songs: List<Song>, quality: AudioQuality): LxPlaybackResult? {
            lxCalls.incrementAndGet()
            if (fail || songs.isEmpty()) return null
            val song = songSelector(songs)
            return LxPlaybackResult(
                song,
                PlayableStream(
                    url = "https://cdn.example.com/full.mp3",
                    ext = "mp3",
                    bitrate = 320,
                    extra = mapOf("lx_source" to "测试脚本", "lx_source_id" to "script-1"),
                ),
            )
        }
    }

    private val vipSong = Song(id = "HASH", name = "晴天", source = "kugou", isVip = true)
    private val source = TrackSource("kugou", "酷狗音乐", vipSong, isPreview = true)
    private val track = TrackMatcher.singleSource(vipSong, "酷狗音乐", "kugou").copy(sources = listOf(source))

    private fun resolver(repository: CustomRepository) = SourceResolver(
        repository = repository,
        cookies = { emptyMap() },
        settings = { AggregationSettings() },
        workDir = { File(".") },
    )

    private fun policy(vararg priority: String) = SourcePolicy(
        priority = priority.toList(),
        allowPreview = false,
    )

    @Test
    fun customSourceIsUnifiedCandidateForVipPreviewTrack() {
        val candidates = resolver(CustomRepository()).orderCandidates(
            track = track,
            pinnedProvider = null,
            excludeProviders = emptySet(),
            policy = policy("kugou", "lx"),
        )
        assertEquals(listOf("lx"), candidates.map { it.providerId })
    }

    @Test
    fun successfulCustomStreamIsNotMarkedAsNativePreview() = runBlocking {
        val resolution = resolver(CustomRepository()).resolve(track, policy = policy("lx", "kugou"))
        val ready = resolution as Resolution.Ready
        assertTrue(ready.url.contains("cdn.example.com"))
        assertFalse(ready.isPreview)
        assertEquals("lx", ready.source.providerId)
        assertEquals("洛雪 · 测试脚本", ready.source.actualProviderName)
    }

    @Test
    fun nativeCandidateDoesNotImplicitlyUseCustomScripts() = runBlocking {
        val repository = CustomRepository()
        val song = vipSong.copy(isVip = false)
        val native = TrackSource("kugou", "酷狗音乐", song)
        val track = TrackMatcher.singleSource(song, "酷狗音乐", "kugou").copy(sources = listOf(native))
        val resolution = resolver(repository).resolve(track, policy = policy("kugou", "lx"))
        val ready = resolution as Resolution.Ready
        assertEquals("kugou", ready.source.providerId)
        assertEquals(1, repository.nativeCalls.get())
        // 洛雪也参与码率竞争，会被问到；防的是结果被标成来自脚本，不是问没问
        assertEquals(1, repository.lxCalls.get())
        assertFalse(ready.source.song.extra.containsKey("lx_source"))
    }

    @Test
    fun unifiedSourceSurvivesNativeExclusionsAndCachesActualSong() = runBlocking {
        val repository = CustomRepository().apply {
            supported = setOf("kugou", "netease")
            songSelector = { songs -> songs.first { it.source == "netease" } }
        }
        val kgSong = Song(id = "KG", name = "晴天", source = "kugou")
        val wySong = Song(id = "WY", name = "晴天", source = "netease")
        val kg = TrackSource("kugou", "酷狗音乐", kgSong, matchScore = 0.95f)
        val wy = TrackSource("netease", "网易云音乐", wySong, matchScore = 0.9f)
        val multi = TrackMatcher.singleSource(kgSong, "酷狗音乐", "kugou").copy(sources = listOf(kg, wy))
        val resolver = resolver(repository)
        val excluded = setOf("kugou", "netease")
        val candidates = resolver.orderCandidates(multi, null, excluded, policy("lx", "kugou", "netease"))
        assertEquals(listOf("lx"), candidates.map { it.providerId })

        val first = resolver.resolve(multi, excludeProviders = excluded, policy = policy("lx", "kugou", "netease")) as Resolution.Ready
        assertEquals("netease", first.source.song.source)
        assertEquals("lx", first.source.providerId)
        val cached = resolver.resolve(multi, excludeProviders = excluded, policy = policy("lx", "kugou", "netease")) as Resolution.Ready
        assertEquals("netease", cached.source.song.source)
        assertEquals(1, repository.lxCalls.get())

        resolver.invalidate(first.source)
        resolver.resolve(multi, excludeProviders = excluded, policy = policy("lx", "kugou", "netease"))
        assertEquals(2, repository.lxCalls.get())
        assertEquals(0, repository.nativeCalls.get())
    }

    @Test
    fun newCustomGenerationBypassesOldFailure() = runBlocking {
        val repository = CustomRepository().apply { fail = true }
        val resolver = resolver(repository)
        val failed = resolver.resolve(track, policy = policy("lx", "kugou"))
        assertTrue(failed is Resolution.None)
        repository.fail = false
        repository.generation = 1
        val resolved = resolver.resolve(track, policy = policy("lx", "kugou"))
        assertTrue(resolved is Resolution.Ready)
    }

    // ---------- 候选白名单：洛雪没启用时即使有候选也不合成 ----------

    private fun nativeSource(provider: String, id: String) =
        TrackSource(provider, provider, Song(id = id, name = "晴天", source = provider))

    @Test
    fun disabledLxIsNotSynthesizedEvenWithCandidates() {
        val kg = nativeSource("kugou", "kg")
        val target = TrackMatcher.singleSource(kg.song, "酷狗音乐", "kugou").copy(sources = listOf(kg))
        val candidates = resolver(CustomRepository()).orderCandidates(
            track = target,
            pinnedProvider = null,
            excludeProviders = emptySet(),
            policy = SourcePolicy(priority = listOf("kugou", "lx"), allowedProviders = setOf("kugou")),
        )
        assertEquals(listOf("kugou"), candidates.map { it.providerId })
    }

    @Test
    fun allowedLxIsSynthesizedWhenCandidatesExist() {
        val kg = nativeSource("kugou", "kg")
        val target = TrackMatcher.singleSource(kg.song, "酷狗音乐", "kugou").copy(sources = listOf(kg))
        val candidates = resolver(CustomRepository()).orderCandidates(
            track = target,
            pinnedProvider = null,
            excludeProviders = emptySet(),
            policy = SourcePolicy(priority = listOf("lx", "kugou"), allowedProviders = setOf("kugou", "lx")),
        )
        assertEquals(listOf("lx", "kugou"), candidates.map { it.providerId })
    }

    @Test
    fun expandedCandidatesBypassOldNegativeCache() = runBlocking {
        val repository = CustomRepository().apply {
            supported = setOf("kugou", "netease")
            fail = true
            songSelector = { songs -> songs.first { it.source == "netease" } }
        }
        val resolver = resolver(repository)
        val kg = nativeSource("kugou", "kg")
        val first = TrackMatcher.singleSource(kg.song, "酷狗音乐", "kugou").copy(id = "track-1", sources = listOf(kg))
        val lxOnly = SourcePolicy(priority = listOf("lx"), pinnedProvider = "lx", allowedProviders = setOf("lx"))
        // 第一次只有酷狗候选：洛雪失败进负缓存
        assertTrue(resolver.resolve(first, pinnedProvider = "lx", policy = lxOnly) is Resolution.None)
        assertEquals(1, repository.lxCalls.get())

        // expand 补配出网易云变体：候选指纹变了，旧负缓存不能挡住重新尝试
        repository.fail = false
        val wy = nativeSource("netease", "wy")
        val expanded = first.copy(sources = listOf(kg, wy))
        val ready = resolver.resolve(expanded, pinnedProvider = "lx", policy = lxOnly) as Resolution.Ready
        assertEquals("lx", ready.source.providerId)
        assertEquals(2, repository.lxCalls.get())
    }
}
