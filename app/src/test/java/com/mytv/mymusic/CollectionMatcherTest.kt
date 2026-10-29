package com.mytv.mymusic

import com.mytv.mymusic.aggregate.CollectionMatcher
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.SearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionMatcherTest {
    private val names = mapOf("netease" to "网易云音乐", "qq" to "QQ 音乐")
    private val priority = listOf("netease", "qq")

    private fun playlist(
        id: String,
        name: String,
        creator: String,
        tracks: Int,
        source: String,
    ) = Playlist(id = id, name = name, creator = creator, trackCount = tracks, source = source)

    @Test
    fun groupsTheSameAlbum() {
        val groups = CollectionMatcher.group(
            mapOf(
                "netease" to listOf(playlist("1", "叶惠美", "周杰伦", 11, "netease")),
                "qq" to listOf(playlist("2", "叶惠美", "周杰伦", 11, "qq")),
            ),
            names,
            SearchKind.Album,
            priority,
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().sources.size)
    }

    @Test
    fun doesNotGroupAlbumsByDifferentArtists() {
        val groups = CollectionMatcher.group(
            mapOf(
                "netease" to listOf(playlist("1", "叶惠美", "周杰伦", 11, "netease")),
                "qq" to listOf(playlist("2", "叶惠美", "其他歌手", 11, "qq")),
            ),
            names,
            SearchKind.Album,
            priority,
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun groupsSameNamePlaylistsWithoutClaimingIdentity() {
        val groups = CollectionMatcher.group(
            mapOf(
                "netease" to listOf(playlist("1", "华语热歌", "用户A", 50, "netease")),
                "qq" to listOf(playlist("2", "华语热歌", "用户B", 60, "qq")),
            ),
            names,
            SearchKind.Playlist,
            priority,
        )
        assertEquals(1, groups.size)
        assertTrue(groups.single().sameNameOnly)
        assertEquals(2, groups.single().sources.size)
    }

    @Test
    fun groupsTheSameArtistByName() {
        val groups = CollectionMatcher.group(
            mapOf(
                "netease" to listOf(playlist("1", "周杰伦", "Jay Chou", 589, "netease")),
                "qq" to listOf(playlist("2", "周杰伦", "", 1013, "qq")),
            ),
            names,
            SearchKind.Artist,
            priority,
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().sources.size)
    }

    @Test
    fun doesNotMergeArtistsFromTheSameProvider() {
        val groups = CollectionMatcher.group(
            mapOf(
                "netease" to listOf(
                    playlist("1", "周杰伦", "Jay Chou", 589, "netease"),
                    playlist("2", "周杰伦.", "", 20, "netease"),
                ),
            ),
            names,
            SearchKind.Artist,
            priority,
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun doesNotGroupArtistsWithDifferentNames() {
        val groups = CollectionMatcher.group(
            mapOf(
                "netease" to listOf(playlist("1", "周杰伦", "", 589, "netease")),
                "qq" to listOf(playlist("2", "林俊杰", "", 800, "qq")),
            ),
            names,
            SearchKind.Artist,
            priority,
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun doesNotGroupPlaylistsWithDifferentNames() {
        val groups = CollectionMatcher.group(
            mapOf(
                "netease" to listOf(playlist("1", "华语热歌", "用户A", 50, "netease")),
                "qq" to listOf(playlist("2", "华语经典", "用户B", 60, "qq")),
            ),
            names,
            SearchKind.Playlist,
            priority,
        )
        assertEquals(2, groups.size)
    }
}
