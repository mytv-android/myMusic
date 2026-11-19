package com.mytv.mymusic

import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.bindingTarget
import com.mytv.mymusic.data.boundDiff
import com.mytv.mymusic.data.canReorderBoundPlaylist
import com.mytv.mymusic.data.platformImportMarks
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
