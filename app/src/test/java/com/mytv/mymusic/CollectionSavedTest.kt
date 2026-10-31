package com.mytv.mymusic

import com.mytv.mymusic.ui.CollectionKind
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.collectionSaved
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionSavedTest {
    private fun playlist(name: String) = LocalPlaylistSummary(
        id = name,
        name = name,
        trackCount = 0,
        updatedAt = 0L,
        dirty = false,
    )

    private fun bound(name: String, provider: String, remoteId: String) = LocalPlaylistSummary(
        id = name,
        name = name,
        trackCount = 0,
        updatedAt = 0L,
        dirty = false,
        linkProvider = provider,
        linkId = remoteId,
    )

    @Test
    fun savedNowWinsWithoutMatchingPlaylist() {
        assertTrue(
            collectionSaved(CollectionKind.Chart, "c1", "netease", "周杰伦精选", emptyList(), savedNow = true)
        )
    }

    @Test
    fun matchesTrimmedTitleAgainstLocalPlaylists() {
        assertTrue(
            collectionSaved(CollectionKind.Chart, "c1", "netease", "  周杰伦精选 ", listOf(playlist("周杰伦精选")), savedNow = false)
        )
    }

    @Test
    fun blankTitleIsNotSavedByEmptyName() {
        assertFalse(
            collectionSaved(CollectionKind.Chart, "c1", "netease", "   ", listOf(playlist("")), savedNow = false)
        )
    }

    @Test
    fun unrelatedPlaylistDoesNotCount() {
        assertFalse(
            collectionSaved(CollectionKind.Chart, "c1", "netease", "周杰伦精选", listOf(playlist("其他歌单")), savedNow = false)
        )
    }

    // 歌单按平台绑定判定，同名副本不再算“已收藏”（可以再点绑定原地升级）
    @Test
    fun playlistBoundByProviderAndRemoteId() {
        assertTrue(
            collectionSaved(
                CollectionKind.Playlist, "p1", "netease", "每日推荐",
                listOf(bound("我的推荐", "netease", "p1")), savedNow = false,
            )
        )
    }

    @Test
    fun playlistSameNameWithoutBindingIsNotBound() {
        assertFalse(
            collectionSaved(
                CollectionKind.Playlist, "p1", "netease", "每日推荐",
                listOf(playlist("每日推荐")), savedNow = false,
            )
        )
    }

    @Test
    fun playlistBoundToOtherRemoteIdOrProviderIsNotBound() {
        assertFalse(
            collectionSaved(
                CollectionKind.Playlist, "p1", "netease", "每日推荐",
                listOf(bound("x", "netease", "p2"), bound("y", "qq", "p1")), savedNow = false,
            )
        )
    }
}
