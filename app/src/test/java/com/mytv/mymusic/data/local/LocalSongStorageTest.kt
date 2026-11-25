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
        // 放云端副本时本地歌带着云端的临时地址（网易云盘签名地址、WebDAV 地址），也不能存下
        val fromCloud = playing.copy(url = "https://m801.music.126.net/cloud.flac?sign=1")
        assertEquals("", fromCloud.forStorage().url)
    }

    @Test
    fun forStorageStripsShortLivedStreamUrls() {
        // 平台取流地址是短时效签名地址：网易云 exapi 只给 20 分钟，酷狗按 clienttime 签名。
        // 存下来下次播放/下载带着死地址去取，就是 403 Forbidden
        val platform = Song(
            id = "1",
            name = "孤城",
            artist = "洛先生",
            album = "孤城",
            source = "kugou",
            url = "http://webfs.y.kugou.com/20261125/ab/cd.mp3?key=1",
            cover = "https://c/1.jpg",
        )
        val stored = platform.forStorage()
        assertEquals("", stored.url)
        // 身份和展示字段原样留着，只有地址被剥掉
        assertEquals(platform.key, stored.key)
        assertEquals(platform.name, stored.name)
        assertEquals(platform.artist, stored.artist)
        assertEquals(platform.cover, stored.cover)
        assertEquals(platform.source, stored.source)
    }

    @Test
    fun forStorageKeepsSongsWithoutUrl() {
        // 没有地址的歌（列表、搜索结果）不用复制一份
        val platform = Song(id = "1", name = "晴天", source = "netease", cover = "https://c/1.jpg")
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
