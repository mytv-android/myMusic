package com.mytv.mymusic

import com.mytv.mymusic.data.sync.RemoteFile
import com.mytv.mymusic.data.sync.SyncCoordinator
import com.mytv.mymusic.data.sync.remoteMatches
import com.mytv.mymusic.data.sync.shouldPullOnScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTimingTest {
    private fun remote(
        revision: String = "",
        content: String = "{}",
        updatedAt: Long = 1L,
    ) = RemoteFile(
        id = "gist",
        content = content,
        revision = revision,
        updatedAt = updatedAt,
        htmlUrl = "",
    )

    @Test
    fun screenPullUsesShortThreshold() {
        val now = 1_000_000L
        assertFalse(shouldPullOnScreen(now - 5_000, now))
        assertFalse(shouldPullOnScreen(now - SyncCoordinator.SCREEN_PULL_STALE_MS + 1, now))
        assertTrue(shouldPullOnScreen(now - SyncCoordinator.SCREEN_PULL_STALE_MS, now))
        assertTrue(shouldPullOnScreen(0, now))
    }

    @Test
    fun remoteRevisionIsAuthoritative() {
        assertTrue(remoteMatches(remote("rev-1", "a"), remote("rev-1", "b")))
        assertFalse(remoteMatches(remote("rev-2", "a"), remote("rev-1", "a")))
    }

    @Test
    fun remoteWithoutRevisionFallsBackToContent() {
        assertTrue(remoteMatches(remote("", "same", 10), remote("", "same", 10)))
        assertFalse(remoteMatches(remote("", "new", 11), remote("", "old", 10)))
        assertFalse(remoteMatches(remote("", "same", 11), remote("", "same", 10)))
    }
}
