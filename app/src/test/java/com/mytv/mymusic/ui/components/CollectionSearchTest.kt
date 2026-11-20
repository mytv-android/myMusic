package com.mytv.mymusic.ui.components

import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionSearchTest {
    private val songs = listOf(
        Song(id = "1", name = "晴天", artist = "周杰伦", album = "叶惠美", source = "netease"),
        Song(id = "2", name = "Perfect", artist = "Ed Sheeran", album = "Divide", source = "qq"),
        Song(id = "3", name = "晴天（翻唱）", artist = "另一位歌手", album = "现场合集", source = "netease"),
    )

    @Test
    fun blankQueryKeepsTheOriginalListAndOrder() {
        assertSame(songs, filterCollectionSongs(songs, ""))
        assertSame(songs, filterCollectionSongs(songs, "  \t "))
        assertEquals(listOf("1", "2", "3"), filterCollectionSongs(songs, " ").map { it.id })
    }

    @Test
    fun searchesTitleArtistAndAlbumWithoutChangingOrder() {
        assertEquals(listOf("1", "3"), filterCollectionSongs(songs, "晴天").map { it.id })
        assertEquals(listOf("1"), filterCollectionSongs(songs, "周杰伦").map { it.id })
        assertEquals(listOf("2"), filterCollectionSongs(songs, "divide").map { it.id })
    }

    @Test
    fun multipleWordsAreAndedAcrossFields() {
        assertEquals(listOf("1"), filterCollectionSongs(songs, "晴天 叶惠美").map { it.id })
        assertEquals(listOf("2"), filterCollectionSongs(songs, "perfect ed").map { it.id })
        assertTrue(filterCollectionSongs(songs, "晴天 ed").isEmpty())
    }

    @Test
    fun singleSongHelperUsesTheSameRules() {
        assertTrue(songMatchesCollectionQuery(songs[1], "SHEERAN perfect"))
        assertFalse(songMatchesCollectionQuery(songs[1], "周杰伦"))
    }
}
