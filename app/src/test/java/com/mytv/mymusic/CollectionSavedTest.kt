package com.mytv.mymusic

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

    @Test
    fun savedNowWinsWithoutMatchingPlaylist() {
        assertTrue(collectionSaved("周杰伦精选", emptyList(), savedNow = true))
    }

    @Test
    fun matchesTrimmedTitleAgainstLocalPlaylists() {
        assertTrue(collectionSaved("  周杰伦精选 ", listOf(playlist("周杰伦精选")), savedNow = false))
    }

    @Test
    fun blankTitleIsNotSavedByEmptyName() {
        assertFalse(collectionSaved("   ", listOf(playlist("")), savedNow = false))
    }

    @Test
    fun unrelatedPlaylistDoesNotCount() {
        assertFalse(collectionSaved("周杰伦精选", listOf(playlist("其他歌单")), savedNow = false))
    }
}
