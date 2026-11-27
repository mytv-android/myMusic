package com.mytv.mymusic

import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.bindingTarget
import com.mytv.mymusic.data.boundDiff
import com.mytv.mymusic.data.canReorderBoundPlaylist
import com.mytv.mymusic.data.platformImportMarks
import com.mytv.mymusic.data.prependPlaylistEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundPlaylistTest {
    private fun playlist(id: String, name: String, count: Int) = com.mytv.mymusic.data.Playlist(
        id = id,
        name = name,
        trackCount = count,
        source = "netease",
    )

    private fun entity(
        id: String,
        name: String,
        provider: String = "",
        remoteId: String = "",
        deleted: Boolean = false,
    ) = PlaylistEntity(
        id = id,
        name = name,
        createdAt = 0L,
        updatedAt = 0L,
        dirty = false,
        deletedAt = if (deleted) 1L else null,
        linkProvider = provider,
        linkId = remoteId,
    )

    // ---- boundDiff：刷新后的内容差异 ----

    @Test
    fun diffCountsAdditionsAndRemovals() {
        val diff = boundDiff(listOf("a", "b", "c"), listOf("b", "c", "d"))
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertTrue(diff.changed)
    }

    @Test
    fun diffSameSongsIsUnchanged() {
        val diff = boundDiff(listOf("a", "b"), listOf("b", "a"))
        assertEquals(0, diff.added)
        assertEquals(0, diff.removed)
        assertFalse(diff.changed)
    }

    @Test
    fun diffToleratesDuplicateKeysInNewList() {
        val diff = boundDiff(listOf("a"), listOf("a", "a"))
        assertEquals(0, diff.added)
        assertEquals(0, diff.removed)
    }

    // ---- bindingTarget：绑定落到哪个本地歌单 ----

    @Test
    fun prefersExistingBindingToSamePlatformPlaylist() {
        val bound = entity("1", "旧名", provider = "netease", remoteId = "p1")
        val sameName = entity("2", "新名")
        val target = bindingTarget(listOf(bound, sameName), "netease", "p1", "新名")
        assertEquals("1", target?.id)
    }

    @Test
    fun upgradesSameNameUnboundPlaylist() {
        val copy = entity("2", "每日推荐")
        val target = bindingTarget(listOf(copy), "netease", "p1", "  每日推荐 ")
        assertEquals("2", target?.id)
    }

    @Test
    fun skipsDeletedAndAlreadyBoundOthersWhenUpgradingByName() {
        val deleted = entity("1", "每日推荐", deleted = true)
        val otherBound = entity("3", "每日推荐", provider = "qq", remoteId = "other")
        assertNull(bindingTarget(listOf(deleted, otherBound), "netease", "p1", "每日推荐"))
    }

    @Test
    fun blankNameWithNoBindingCreatesNew() {
        assertNull(bindingTarget(emptyList(), "netease", "p1", "  "))
    }

    // ---- 排序能力：没写接口的平台仍按编辑即解绑 ----

    @Test
    fun unboundPlaylistCanAlwaysReorder() {
        assertTrue(canReorderBoundPlaylist(bound = false, writeSupported = true, reorderSupported = false))
    }

    @Test
    fun boundProviderWithoutWriteApiKeepsLocalReorder() {
        assertTrue(canReorderBoundPlaylist(bound = true, writeSupported = false, reorderSupported = false))
    }

    @Test
    fun boundWritableProviderFollowsReorderCapability() {
        assertTrue(canReorderBoundPlaylist(bound = true, writeSupported = true, reorderSupported = true))
        assertFalse(canReorderBoundPlaylist(bound = true, writeSupported = true, reorderSupported = false))
    }

    // ---- 加歌落点：默认插到最上面，position 连续 ----

    @Test
    fun prependPutsNewSongsFirstAndReindexesPositions() {
        val existing = listOf(entry("p", 0, "netease:a"), entry("p", 1, "netease:b"), entry("p", 2, "netease:c"))
        val result = prependPlaylistEntries("p", existing, listOf(song("x"), song("y")), factory)
        assertEquals(listOf("netease:x", "netease:y", "netease:a", "netease:b", "netease:c"), result.map { it.songKey })
        assertEquals(listOf(0, 1, 2, 3, 4), result.map { it.position })
        assertTrue(result.all { it.playlistId == "p" })
    }

    @Test
    fun prependIntoEmptyPlaylistKeepsRequestedOrder() {
        val result = prependPlaylistEntries("p", emptyList(), listOf(song("x"), song("y")), factory)
        assertEquals(listOf("netease:x", "netease:y"), result.map { it.songKey })
        assertEquals(listOf(0, 1), result.map { it.position })
    }

    @Test
    fun prependKeepsExistingEntriesApartFromPosition() {
        val existing = listOf(entry("p", 0, "netease:a", payload = "pa"), entry("p", 1, "netease:b", payload = "pb"))
        val result = prependPlaylistEntries("p", existing, listOf(song("x")), factory)
        val kept = result.drop(1)
        assertEquals(listOf("netease:a", "netease:b"), kept.map { it.songKey })
        assertEquals(listOf("pa", "pb"), kept.map { it.payload })
        assertEquals(listOf(1, 2), kept.map { it.position })
    }

    private fun song(id: String) = Song(id = id, name = id, artist = "歌手", source = "netease")

    private fun entry(playlistId: String, position: Int, songKey: String, payload: String = "") =
        PlaylistEntryEntity(playlistId = playlistId, position = position, songKey = songKey, payload = payload)

    /** 和 MusicViewModel.entryOf 同形状：建好的 position 会被 helper 重排，这里给几无所谓。 */
    private val factory: (String, Int, Song) -> PlaylistEntryEntity = { playlistId, _, song ->
        PlaylistEntryEntity(playlistId = playlistId, position = 0, songKey = song.key, payload = song.id)
    }

    // ---- 登录后导入平台歌单：哪些行灰掉、哪些会合并 ----

    @Test
    fun platformImportMarksBoundAndMergeable() {
        val local = listOf(
            // 已经绑过这个平台的两张
            entity("1", "我喜欢的音乐", provider = "netease", remoteId = "n1"),
            // 同名但没绑的普通歌单：导入时会合并进去
            entity("2", "通勤"),
            // 别的平台绑的，同名也不算合并
            entity("3", "跑步", provider = "qq", remoteId = "q1"),
            // 删掉的同名歌单不算
            entity("4", "睡前", deleted = true),
        )
        val remote = listOf(
            playlist("n1", "我喜欢的音乐", 486),
            playlist("n2", "通勤", 64),
            playlist("n3", "跑步", 20),
            playlist("n4", "睡前", 31),
        )
        val marks = platformImportMarks("netease", remote, local)
        assertEquals(setOf("n1"), marks.boundIds)
        assertEquals(setOf("n2"), marks.mergeableIds)
    }

    @Test
    fun platformImportMarksIgnoreOtherProvidersAndMissing() {
        val local = listOf(entity("1", "歌一", provider = "qq", remoteId = "a"))
        val remote = listOf(playlist("a", "歌一", 10), playlist("b", "歌二", 5))
        val marks = platformImportMarks("netease", remote, local)
        assertTrue(marks.boundIds.isEmpty())
        assertTrue(marks.mergeableIds.isEmpty())
        assertTrue(platformImportMarks("netease", emptyList(), local).boundIds.isEmpty())
    }
}
