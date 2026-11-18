package com.mytv.mymusic.data.transfer

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.sync.SyncLocal
import com.mytv.mymusic.data.sync.SyncMatch
import com.mytv.mymusic.data.sync.SyncTrackRefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyMusicPlaylistCodecTest {
    private val kugou = Song(
        id = "9E0A", name = "海阔天空", artist = "Beyond", album = "海阔天空", albumId = "960399", duration = 326,
        source = "kugou", url = "https://temp/stream.mp3", size = 123, bitrate = 320, ext = "mp3",
        cover = "https://imge.kugou.com/a.jpg", link = "https://www.kugou.com/song/#hash=9E0A",
        extra = mapOf("hash" to "9E0A", "audio_id" to "32218394", "album_audio_id" to "107", "cache" to "file:///data/x", "uri" to "content://media/1", "path" to "/sdcard/a"),
        isVip = true,
    )
    private val migu = Song(
        id = "600|2|PQ", name = "小幸运", artist = "田馥甄", source = "migu",
        extra = mapOf("content_id" to "600", "copyright_id" to "6008", "song_id" to "11"),
    )
    private val local = Song(
        id = "sha256:abc", name = "夜曲", artist = "周杰伦", album = "十一月的萧邦", duration = 226, source = "local",
        cover = "/data/user/0/cache/cover.jpg", extra = mapOf("file_name" to "夜曲.mp3"),
    )
    private val refs = mapOf(
        local.key to SyncTrackRefs(
            local = SyncLocal(hash = "sha256:abc", fileName = "夜曲.mp3", fileSize = 9_000_000, device = "Pixel 8"),
            match = SyncMatch("netease", "186", 0.92f, "tag", "2026-11-01T00:00:00Z"),
            cover = "https://p1.music.126.net/c.jpg",
        ),
    )

    private fun build(vararg lists: ExportList) = PlaylistExport.build(lists.toList(), now = 1_763_500_000_000, app = "myMusic test", refs = refs)

    private fun decode(file: ExportFile): DecodedPlaylists {
        val content = TransferReader.read(file.bytes)
        assertTrue(content.toString(), content is TransferContent.Playlists)
        return (content as TransferContent.Playlists).decoded
    }

    @Test
    fun roundTripKeepsExtraAlbumIdAndLocalRefs() {
        val file = build(ExportList("p1", "跑步", listOf(kugou, migu, local)))
        val decoded = decode(file)
        val tracks = decoded.file.playlists.single().tracks
        val k = tracks[0].toSong()
        assertEquals(kugou.key, k.key)
        assertEquals("960399", k.albumId)
        assertEquals(mapOf("hash" to "9E0A", "audio_id" to "32218394", "album_audio_id" to "107"), k.extra)
        assertEquals("6008", tracks[1].extra["copyright_id"])
        val l = tracks[2]
        assertTrue(l.isLocal)
        assertEquals("sha256:abc", l.id)
        assertEquals(SyncLocal(hash = "sha256:abc", fileSize = 9_000_000), l.local)
        assertEquals("netease", l.match?.provider)
        assertEquals("https://p1.music.126.net/c.jpg", l.cover)
        assertFalse(decoded.newerVersion)
        assertEquals(listOf(0), decoded.dropped)
    }

    @Test
    fun localPathsAndUrlsNeverLeaveTheDevice() {
        val text = build(ExportList("p1", "跑步", listOf(kugou, local))).bytes.toString(Charsets.UTF_8)
        listOf("file:", "content:", "/sdcard", "/data/", "夜曲.mp3", "Pixel 8", "temp/stream.mp3", "\"url\"", "isVip", "bitrate").forEach {
            assertFalse("文件里不该有 $it：$text", text.contains(it))
        }
        assertFalse(text.startsWith("﻿"))
        assertFalse(text.contains("\n"))
    }

    @Test
    fun nonWebCoverIsDropped() {
        val track = PortableTrack.of(kugou.copy(cover = "content://x"))
        assertEquals("", track.cover)
        // 本地歌没有匹配封面时，内嵌封面（本机路径）也不写
        assertEquals("", PortableTrack.of(local, null).cover)
    }

    @Test
    fun newerVersionIsReadAndFlagged() {
        val text = """{"type":"mymusic.playlists","version":2,"future":{"x":1},"playlists":[{"name":"a","kind":"favorites","tracks":[{"provider":"qq","id":"m","title":"t","newField":3}]}]}"""
        val decoded = MyMusicPlaylistCodec.decode(Json.parseToJsonElement(text).jsonObject)!!
        assertTrue(decoded.newerVersion)
        assertTrue(decoded.file.playlists.single().favorites)
        assertEquals("t", decoded.file.playlists.single().tracks.single().title)
    }

    @Test
    fun blankProviderOrIdIsDropped() {
        val text = """{"type":"mymusic.playlists","version":1,"playlists":[{"name":"a","tracks":[
            {"provider":"","id":"1"},{"provider":"qq","id":""},{"id":"x"},"oops",{"provider":"qq","id":"m"}]}]}"""
        val decoded = MyMusicPlaylistCodec.decode(Json.parseToJsonElement(text).jsonObject)!!
        assertEquals(1, decoded.file.playlists.single().tracks.size)
        assertEquals(listOf(4), decoded.dropped)
    }

    @Test
    fun otherTypeReturnsNull() {
        assertNull(MyMusicPlaylistCodec.decode(Json.parseToJsonElement("""{"type":"playList_v2","data":[]}""").jsonObject))
    }

    @Test
    fun fileNamesAndCounts() {
        val one = build(ExportList("p1", "跑步/夜跑", listOf(kugou, kugou.copy(name = "重复"), migu, local)))
        assertEquals("跑步夜跑.mymusic.json", one.fileName)
        assertEquals(PLAYLIST_FILE_MIME, one.mime)
        assertEquals(ExportCounts(total = 4, kept = 3, byProvider = mapOf("kugou" to 1, "migu" to 1, "local" to 1), local = 1), one.counts)
        val many = build(
            ExportList(ExportList.FAVORITES_ID, ExportList.FAVORITES_NAME, listOf(migu)),
            ExportList("p1", "跑步", listOf(kugou), unreadable = 2),
            ExportList("p2", "通勤", emptyList()),
        )
        assertEquals("myMusic 歌单 3 个.mymusic.json", many.fileName)
        assertEquals(4, many.counts.total)
        assertEquals(2, many.counts.kept)
        val decoded = decode(many)
        assertEquals(listOf(KIND_FAVORITES, KIND_PLAYLIST, KIND_PLAYLIST), decoded.file.playlists.map { it.kind })
        assertEquals("3 个歌单", PlaylistExport.title(many.let { listOf(ExportList("a", "a", emptyList()), ExportList("b", "b", emptyList()), ExportList("c", "c", emptyList())) }))
    }

    @Test
    fun safeFileNames() {
        assertEquals("abcdefghij", safeFileName("a\\b/c:d*e?f#g\"h<i>j|"))
        assertEquals("歌单", safeFileName("  ///  "))
        assertEquals("歌单", safeFileName(""))
        assertEquals(150, safeFileName("长".repeat(200)).length)
        assertEquals("ab", safeFileName(" a\tb\u0000 "))
    }

    @Test
    fun exportThenReadBack() {
        val decoded = decode(build(ExportList("p1", "跑步", listOf(kugou, migu), source = PortableSource("netease", "8888"))))
        val playlist = decoded.file.playlists.single()
        assertEquals(PortableSource("netease", "8888"), playlist.source)
        assertEquals(listOf("kugou:9E0A", "migu:600|2|PQ"), playlist.tracks.map { it.toSong().key })
        assertEquals("32218394", playlist.tracks[0].toSong().extra["audio_id"])
        assertEquals("2025-11-18T21:06:40Z", decoded.file.exportedAt)
    }
}
