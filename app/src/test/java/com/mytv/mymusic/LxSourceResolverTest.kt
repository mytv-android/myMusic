package com.mytv.mymusic

import com.mytv.mymusic.aggregate.AggregationSettings
import com.mytv.mymusic.aggregate.Resolution
import com.mytv.mymusic.aggregate.SourcePolicy
import com.mytv.mymusic.aggregate.SourceResolver
import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.aggregate.TrackSource
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

class LxSourceResolverTest {
    private class CustomRepository : MusicRepository() {
        var fail = false
        var generation = 0L
        override fun supportsCustomSource(provider: String): Boolean = provider == "kugou"
        override fun customSourceGeneration(): Long = generation
        override suspend fun playableStream(song: Song, cookie: String, workDir: File, quality: AudioQuality): PlayableStream =
            if (fail) PlayableStream(url = "") else PlayableStream(
                url = "https://cdn.example.com/full.mp3",
                ext = "mp3",
                bitrate = 320,
                extra = mapOf("lx_source" to "测试脚本"),
            )
    }

    private val vipSong = Song(id = "HASH", name = "晴天", source = "kugou", isVip = true)
    private val source = TrackSource("kugou", "酷狗音乐", vipSong, isPreview = true)
    private val track = TrackMatcher.singleSource(vipSong, "酷狗音乐", "kugou").copy(sources = listOf(source))
    private val resolver = SourceResolver(
        repository = CustomRepository(),
        cookies = { emptyMap() },
        settings = { AggregationSettings() },
        workDir = { File(".") },
    )

    @Test
    fun customSourceKeepsVipPreviewCandidate() {
        val candidates = resolver.orderCandidates(
            track = track,
            pinnedProvider = null,
            excludeProviders = emptySet(),
            policy = SourcePolicy(allowPreview = false),
        )
        assertEquals(listOf("kugou"), candidates.map { it.providerId })
    }

    @Test
    fun successfulCustomStreamIsNotMarkedAsNativePreview() = runBlocking {
        val resolution = resolver.resolve(track, policy = SourcePolicy(allowPreview = false))
        val ready = resolution as Resolution.Ready
        assertTrue(ready.url.contains("cdn.example.com"))
        assertFalse(ready.isPreview)
        assertEquals("测试脚本", ready.source.song.extra["lx_source"])
    }

    @Test
    fun newCustomGenerationBypassesOldFailure() = runBlocking {
        val repository = CustomRepository().apply { fail = true }
        val resolver = SourceResolver(
            repository = repository,
            cookies = { emptyMap() },
            settings = { AggregationSettings() },
            workDir = { File(".") },
        )
        val failed = resolver.resolve(track, policy = SourcePolicy(allowPreview = false))
        assertTrue(failed is Resolution.None)
        repository.fail = false
        repository.generation = 1
        val resolved = resolver.resolve(track, policy = SourcePolicy(allowPreview = false))
        assertTrue(resolved is Resolution.Ready)
    }
}
