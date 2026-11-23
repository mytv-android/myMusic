package com.mytv.mymusic

import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.local.forStorage
import com.mytv.mymusic.data.sync.SyncJson
import com.mytv.mymusic.data.sync.buildSnapshot
import com.mytv.mymusic.data.sync.toSyncTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicSyncTest {
    @Test
    fun localLibraryKeepsCoverButSyncExportsOnlyRebuildableId() {
        val song = Song(
            id = "s1",
            name = "Song",
            source = "subsonic:home",
            cover = "file:///data/user/0/com.mytv.mymusic/cache/subsonic-covers/home/cover.image",
            ext = "flac",
            extra = mapOf("subsonic_cover" to "al-cover", "isrc" to "ABC", "private" to "drop"),
        )
        assertEquals(song.cover, song.forStorage().cover)
        val track = song.toSyncTrack()
        assertEquals("", track.cover)
        assertEquals("flac", track.ext)
        assertEquals("al-cover", track.extra["subsonic_cover"])
        assertFalse(track.extra.containsKey("private"))
        val restored = SyncJson.decode(SyncJson.encode(com.mytv.mymusic.data.sync.SyncSnapshot(
            favorites = listOf(com.mytv.mymusic.data.sync.SyncFavorite(key = song.key, track = track)),
        ))).favorites.single().track!!.toSong()
        assertEquals("al-cover", restored.extra["subsonic_cover"])
        assertTrue(restored.cover.startsWith("content://com.mytv.mymusic.subsonic-covers/home/al-cover"))
        assertEquals("flac", restored.ext)
        assertEquals("subsonic:home:s1", restored.key)
    }

    @Test
    fun boundPlaylistDoesNotExportDeviceCoverPath() {
        val playlist = PlaylistEntity(
            id = "p1",
            name = "Server",
            createdAt = 1,
            updatedAt = 2,
            linkProvider = "subsonic:home",
            linkId = "pl1",
            linkCover = "file:///cache/subsonic-covers/home/pl.image",
        )
        val snapshot = buildSnapshot(listOf(playlist), emptyList(), "device")
        assertEquals("", snapshot.playlists.single().link!!.cover)
        val raw = SyncJson.encode(snapshot)
        assertFalse(raw.contains("file://"))
        assertFalse(raw.contains("subsonic-covers"))
    }

    @Test
    fun portableCoverUriSurvivesWithoutExposingFilePath() {
        val cover = com.mytv.mymusic.data.subsonic.SubsonicCoverUris.create("subsonic:home", "al/a cover")
        assertTrue(com.mytv.mymusic.data.subsonic.SubsonicCoverUris.isPortable(cover))
        val track = Song(id = "s1", source = "subsonic:home", cover = cover, ext = "flac").toSyncTrack()
        assertEquals(cover, track.cover)
        val playlist = PlaylistEntity(
            id = "p2", name = "Portable", createdAt = 1, updatedAt = 2,
            linkProvider = "subsonic:home", linkId = "pl2", linkCover = cover,
        )
        assertEquals(cover, buildSnapshot(listOf(playlist), emptyList(), "device").playlists.single().link!!.cover)
    }

    @Test
    fun accountJsonIsPresentInCookieSnapshot() {
        val account = com.mytv.mymusic.data.subsonic.SubsonicAccount(
            id = "home", name = "Home", url = "https://nas", username = "demo", password = "secret",
        )
        val snapshot = buildSnapshot(
            emptyList(), emptyList(), "device", cookies = mapOf(account.providerId to account.encode()),
            cookieUpdatedAt = mapOf(account.providerId to 123L),
        )
        assertTrue(snapshot.cookies.single().value.contains("secret"))
        assertEquals(account.providerId, snapshot.cookies.single().provider)
    }
}
