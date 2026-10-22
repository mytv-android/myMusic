package com.mytv.mymusic

import com.mytv.mymusic.data.sync.SyncJson
import com.mytv.mymusic.data.sync.SyncMerge
import com.mytv.mymusic.data.sync.SyncPlaylist
import com.mytv.mymusic.data.sync.SyncSnapshot
import com.mytv.mymusic.data.sync.SyncStrategy
import com.mytv.mymusic.data.sync.SyncTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private fun track(key: String) = SyncTrack(
        key = key,
        provider = key.substringBefore(':'),
        id = key.substringAfter(':'),
        title = "Song $key",
        artist = "Artist",
        album = "Album",
        durationSec = 200,
    )

    private fun playlist(
        id: String = "p1",
        name: String = "夜间驾驶",
        tracks: List<String> = listOf("netease:1"),
        deletedAt: String? = null,
        updatedAt: String = "2026-10-20T22:40:10Z",
    ) = SyncPlaylist(
        id = id,
        name = name,
        createdAt = "2026-09-03T12:10:00Z",
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        tracks = tracks.map(::track),
    )

    private fun snapshot(vararg playlists: SyncPlaylist) = SyncSnapshot(
        deviceId = "device-a",
        updatedAt = "2026-10-21T02:19:41Z",
        playlists = playlists.toList(),
    )

    @Test
    fun takesLocalChangesWhenRemoteIsUnchanged() {
        val base = snapshot(playlist(tracks = listOf("netease:1")))
        val local = snapshot(playlist(tracks = listOf("netease:1", "netease:2")))
        val remote = snapshot(playlist(tracks = listOf("netease:1")))

        val result = SyncMerge.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf("netease:1", "netease:2"), result.playlists.single().tracks.map { it.key })
    }

    @Test
    fun takesRemoteChangesWhenLocalIsUnchanged() {
        val base = snapshot(playlist(tracks = listOf("netease:1")))
        val local = snapshot(playlist(tracks = listOf("netease:1")))
        val remote = snapshot(playlist(name = "新的名字", tracks = listOf("netease:1", "qq:9")))

        val result = SyncMerge.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals("新的名字", result.playlists.single().name)
        assertEquals(2, result.playlists.single().tracks.size)
    }

    @Test
    fun reportsConflictWhenBothSidesChangeDifferently() {
        val base = snapshot(playlist(tracks = listOf("netease:1")))
        val local = snapshot(playlist(tracks = listOf("netease:1", "netease:2")))
        val remote = snapshot(playlist(tracks = listOf("netease:1", "qq:9")))

        val result = SyncMerge.merge(base, local, remote)

        assertEquals(1, result.conflicts.size)
        assertTrue(result.playlists.isEmpty())
        assertEquals("p1", result.conflicts.single().playlistId)
    }

    @Test
    fun doesNotConflictWhenBothSidesMadeTheSameChange() {
        val base = snapshot(playlist(tracks = listOf("netease:1")))
        val local = snapshot(playlist(tracks = listOf("netease:1", "netease:2")))
        val remote = snapshot(playlist(tracks = listOf("netease:1", "netease:2")))

        val result = SyncMerge.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals(2, result.playlists.single().tracks.size)
    }

    @Test
    fun localDeletionPropagatesWhenRemoteIsUnchanged() {
        val base = snapshot(playlist())
        val local = snapshot(playlist(deletedAt = "2026-10-21T01:00:00Z"))
        val remote = snapshot(playlist())

        val result = SyncMerge.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals("2026-10-21T01:00:00Z", result.playlists.single().deletedAt)
        assertTrue(result.playlists.single().tracks.isEmpty())
    }

    @Test
    fun deletionVersusRemoteEditIsConflict() {
        val base = snapshot(playlist())
        val local = snapshot(playlist(deletedAt = "2026-10-21T01:00:00Z"))
        val remote = snapshot(playlist(tracks = listOf("netease:1", "qq:9")))

        val result = SyncMerge.merge(base, local, remote)

        assertEquals(1, result.conflicts.size)
    }

    @Test
    fun mergeResolutionKeepsLocalOrderAndAppendsRemoteOnlyTracks() {
        val local = playlist(tracks = listOf("netease:1", "netease:2"))
        val remote = playlist(tracks = listOf("netease:2", "netease:3", "qq:9"))

        val merged = SyncMerge.resolve(local, remote, SyncStrategy.Merge)!!

        assertEquals(listOf("netease:1", "netease:2", "netease:3", "qq:9"), merged.tracks.map { it.key })
        assertEquals("夜间驾驶", merged.name)
    }

    @Test
    fun mergeResolutionDoesNotRestoreDeletedSide() {
        val local = playlist(deletedAt = "2026-10-21T01:00:00Z")
        val remote = playlist(tracks = listOf("netease:1", "netease:2"))

        val merged = SyncMerge.resolve(local, remote, SyncStrategy.Merge)!!

        assertNull(merged.deletedAt)
        assertEquals(2, merged.tracks.size)
    }

    @Test
    fun initialStrategyMergeUnionsBothLibraries() {
        val local = snapshot(playlist(id = "p1", tracks = listOf("netease:1")))
        val remote = snapshot(
            playlist(id = "p1", tracks = listOf("netease:1", "qq:9")),
            playlist(id = "p2", name = "云端歌单", tracks = listOf("kugou:3")),
        )

        val playlists = SyncMerge.applyStrategy(local, remote, SyncStrategy.Merge)

        assertEquals(2, playlists.size)
        assertEquals(listOf("netease:1", "qq:9"), playlists.first { it.id == "p1" }.tracks.map { it.key })
        assertEquals("云端歌单", playlists.first { it.id == "p2" }.name)
    }

    @Test
    fun snapshotJsonRoundTripsTombstones() {
        val snapshot = snapshot(
            playlist(id = "p1", deletedAt = "2026-10-21T01:00:00Z"),
            playlist(id = "p2", tracks = listOf("netease:2", "qq:9")),
        )
        val raw = SyncJson.encode(snapshot)
        val decoded = SyncJson.decode(raw)

        assertEquals(snapshot.schema, decoded.schema)
        assertEquals(2, decoded.playlists.size)
        assertEquals("2026-10-21T01:00:00Z", decoded.playlists.first { it.id == "p1" }.deletedAt)
        assertEquals(listOf("netease:2", "qq:9"), decoded.playlists.first { it.id == "p2" }.tracks.map { it.key })
    }
}
