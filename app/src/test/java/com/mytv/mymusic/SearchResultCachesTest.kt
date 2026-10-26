package com.mytv.mymusic

import com.mytv.mymusic.aggregate.CanonicalCollection
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.CollectionSource
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.aggregate.VersionKind
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.SearchKind
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.search.buildSongResultsCache
import com.mytv.mymusic.ui.search.collectionRowKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultCachesTest {
    private fun source(provider: String, id: String): TrackSource = TrackSource(
        providerId = provider,
        providerName = provider,
        song = Song(id = id, name = "song-$id", source = provider),
    )

    private fun track(id: String, alternatives: List<TrackSource> = emptyList()): CanonicalTrack = CanonicalTrack(
        id = id,
        title = "track-$id",
        artists = listOf("artist"),
        album = "",
        durationSec = 200,
        version = VersionKind.Studio,
        cover = "",
        sources = listOf(source("netease", id)),
        alternatives = alternatives,
    )

    @Test
    fun dropsBestMatchAndBuildsRowKeys() {
        val cache = buildSongResultsCache(listOf(track("a"), track("b"), track("c")))

        assertEquals(listOf("a", "b", "c"), cache.groups.map { it.id })
        assertEquals(listOf("b", "c"), cache.rows.map { it.id })
        assertEquals(listOf("b", "c"), cache.rowKeys)
    }

    @Test
    fun disambiguatesDuplicateTrackIds() {
        val cache = buildSongResultsCache(listOf(track("a"), track("dup"), track("dup"), track("dup")))

        assertEquals(listOf("dup", "dup#2", "dup#3"), cache.rowKeys)
    }

    @Test
    fun flattensAlternativesAndDeduplicatesBySongKey() {
        val shared = source("qq", "same")
        val cache = buildSongResultsCache(
            listOf(
                track("a", listOf(shared, source("qq", "only-a"))),
                track("b", listOf(shared, source("kugou", "only-b"))),
            ),
        )

        assertEquals(listOf("same", "only-a", "only-b"), cache.alternatives.map { it.source.song.id })
        assertEquals(listOf("alt:qq:same", "alt:qq:only-a", "alt:kugou:only-b"), cache.alternativeKeys)
        assertEquals(listOf("a", "a", "b"), cache.alternatives.map { it.track.id })
    }

    @Test
    fun emptyResultsProduceEmptyCaches() {
        val cache = buildSongResultsCache(emptyList())

        assertTrue(cache.rows.isEmpty())
        assertTrue(cache.alternatives.isEmpty())
        assertTrue(cache.alternativeKeys.isEmpty())
        assertTrue(collectionRowKeys(emptyList()).isEmpty())
    }

    @Test
    fun collectionKeysDisambiguateDuplicates() {
        val source = CollectionSource(
            providerId = "netease",
            providerName = "网易云音乐",
            playlist = Playlist(id = "p", name = "p"),
            score = 1f,
        )
        fun collection(id: String) = CanonicalCollection(
            id = id,
            name = "c-$id",
            creator = "",
            cover = "",
            trackCount = 0,
            kind = SearchKind.Playlist,
            sources = listOf(source),
            confidence = 1f,
        )

        assertEquals(listOf("x", "x#2", "y"), collectionRowKeys(listOf(collection("x"), collection("x"), collection("y"))))
    }
}
