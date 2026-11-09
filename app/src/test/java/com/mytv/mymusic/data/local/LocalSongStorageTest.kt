package com.mytv.mymusic.data.local

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.sync.SyncTrack
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 本地歌进歌单、收藏和同步时只带身份，不带只在本机有效的地址。 */
class LocalSongStorageTest {
    private val row = LocalScanDiff.merge(
        previous = null,
        file = ScannedFile(
            uri = "content://media/external/audio/media/42",
            origin = LOCAL_ORIGIN_MEDIA,
            displayName = "周杰伦 - 晴天.flac",
            relativePath = "Music/",
            size = 30_000_000,
            modifiedAt = 1,
            mimeType = "audio/flac",
            title = "Track 01",
            durationMs = 269_000,
        ),
        localId = "sha256:" + "ab".repeat(32),
        now = 1,
    ).copy(coverPath = "file:///data/user/0/x/files/local_covers/ab.jpg", bitrate = 900_000)

    @Test
    fun songOfUsesLocalKeyAndFileFacts() {
        val song = LocalLibrary.songOf(row, null, LocalTitlePolicy.FillGaps)
        assertEquals("local:sha256:" + "ab".repeat(32), song.key)
        assertEquals(LOCAL_SOURCE, song.source)
        assertEquals("", song.url)
        assertEquals(900, song.bitrate)
        assertEquals("flac", song.ext)
        assertEquals("周杰伦 - 晴天.flac", song.title)
        assertEquals(row.coverPath, song.cover)
    }

    @Test
    fun forStorageStripsDeviceOnlyFields() {
        val playing = LocalLibrary.songOf(row, null, LocalTitlePolicy.FillGaps).copy(url = row.uri)
        val stored = playing.forStorage()
        assertEquals("", stored.url)
        assertEquals("", stored.cover)
        assertEquals(playing.key, stored.key)
        // 匹配来的平台封面是网址，留着
        val withRemoteCover = playing.copy(cover = "https://p1.music.126.net/x.jpg")
        assertEquals("https://p1.music.126.net/x.jpg", withRemoteCover.forStorage().cover)
        // 平台歌原样不动
        val platform = Song(id = "1", name = "晴天", source = "netease", url = "https://m.example/1.mp3", cover = "https://c/1.jpg")
        assertSame(platform, platform.forStorage())
    }

    @Test
    fun syncV2CarriesLocalKeysAsOrdinaryTracks() {
        val song = LocalLibrary.songOf(row, null, LocalTitlePolicy.FillGaps).forStorage()
        val track = SyncTrack(
            key = song.key,
            provider = song.source,
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationSec = song.duration,
            cover = song.cover,
        )
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(SyncTrack.serializer(), json.encodeToString(SyncTrack.serializer(), track)).toSong()
        assertEquals(song.key, back.key)
        assertEquals(LOCAL_SOURCE, back.source)
        assertEquals(269, back.duration)
    }
}
