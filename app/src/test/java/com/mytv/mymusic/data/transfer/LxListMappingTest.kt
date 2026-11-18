package com.mytv.mymusic.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LxListMappingTest {
    private fun song(source: String, id: String = "${source}_1", meta: Map<String, String> = emptyMap(), name: String = "歌", singer: String = "人") =
        LxSong(id = id, name = name, singer = singer, source = source, intervalSec = 200, meta = meta)

    private fun ready(mapped: LxMapped) = (mapped as LxMapped.Ready).song

    @Test
    fun netease() {
        val s = ready(song("wy", meta = mapOf("songId" to "1901371647", "albumName" to "专辑", "picUrl" to "https://p1/a.jpg")).mapToSong())
        assertEquals("netease", s.source)
        assertEquals("1901371647", s.id)
        assertEquals(mapOf("song_id" to "1901371647"), s.extra)
        assertEquals("专辑", s.album)
        assertEquals("https://p1/a.jpg", s.cover)
        assertEquals(200, s.duration)
        assertEquals("netease:1901371647", s.key)
    }

    @Test
    fun qqMediaMidOnlyWhenDifferentAndNoBlanks() {
        val same = ready(song("tx", meta = mapOf("songId" to "0039Mn", "strMediaMid" to "0039Mn", "id" to "97773")).mapToSong())
        assertEquals("qq", same.source)
        assertEquals("0039Mn", same.id)
        assertEquals(mapOf("songmid" to "0039Mn", "song_id" to "97773"), same.extra)
        val different = ready(song("tx", meta = mapOf("songId" to "002Z", "strMediaMid" to "001X", "albumMid" to "003D", "albumId" to "8220")).mapToSong())
        assertEquals("001X", different.extra["media_mid"])
        assertEquals("003D", different.extra["album_mid"])
        assertEquals("8220", different.albumId)
        assertFalse(different.extra.containsKey("song_id"))
    }

    @Test
    fun kugouIdSplit() {
        val split = ready(song("kg", id = "123_ABCDEF", meta = emptyMap()).mapToSong())
        assertEquals("kugou", split.source)
        assertEquals("ABCDEF", split.id)
        assertEquals("ABCDEF", split.extra["hash"])
        assertEquals("123", split.extra["audio_id"])
        val full = ready(song("kg", id = "9_X", meta = mapOf("songId" to "32218394", "hash" to "9E0A", "albumAudioId" to "107", "albumId" to "96")).mapToSong())
        assertEquals("9E0A", full.id)
        assertEquals(mapOf("hash" to "9E0A", "audio_id" to "32218394", "album_audio_id" to "107", "album_id" to "96"), full.extra)
        assertEquals(LxMapped.Skip(SkipReason.MissingId), song("kg", id = "123", meta = emptyMap()).mapToSong())
    }

    @Test
    fun kuwoDropsMusicPrefix() {
        val s = ready(song("kw", meta = mapOf("songId" to "MUSIC_228908")).mapToSong())
        assertEquals("kuwo", s.source)
        assertEquals("228908", s.id)
        assertEquals(mapOf("rid" to "228908"), s.extra)
    }

    @Test
    fun miguNeedsCopyright() {
        val mapped = song("mg", meta = mapOf("songId" to "1121438701", "copyrightId" to "60084600529")).mapToSong()
        assertTrue(mapped is LxMapped.Migu)
        mapped as LxMapped.Migu
        assertEquals("60084600529", mapped.copyrightId)
        assertEquals("migu", mapped.fallback.source)
        assertEquals("60084600529|2|PQ", mapped.fallback.id)
        assertEquals("60084600529", mapped.fallback.extra["content_id"])
        assertEquals("60084600529", mapped.fallback.extra["copyright_id"])
        assertEquals("1121438701", mapped.fallback.extra["song_id"])
        assertEquals("2", mapped.fallback.extra["resource_type"])
        assertEquals("PQ", mapped.fallback.extra["format_type"])
        assertEquals(LxMapped.Skip(SkipReason.MissingId), song("mg", meta = mapOf("songId" to "1")).mapToSong())
    }

    @Test
    fun localKeepsOnlyFileName() {
        val unix = song("local", meta = mapOf("filePath" to "/storage/emulated/0/Music/夜曲.mp3", "albumName" to "十一月的萧邦")).mapToSong()
        assertEquals(LxMapped.Local("歌", "人", "十一月的萧邦", 200, "夜曲.mp3"), unix)
        val windows = song("local", meta = mapOf("songId" to "D:\\Music\\晴天.flac")).mapToSong() as LxMapped.Local
        assertEquals("晴天.flac", windows.fileName)
    }

    @Test
    fun unknownSourceIsSkipped() {
        assertEquals(LxMapped.Skip(SkipReason.UnknownSource), song("xyz", meta = mapOf("songId" to "1")).mapToSong())
        assertEquals(null, lxProvider("xyz"))
        assertEquals("netease", lxProvider("wy"))
    }

    @Test
    fun nonWebCoverIsDropped() {
        assertEquals("", lxCover("/storage/emulated/0/cover.jpg"))
        assertEquals("", lxCover("file:///a.jpg"))
        assertEquals("http://a/b.jpg", lxCover("http://a/b.jpg"))
        val s = ready(song("wy", meta = mapOf("songId" to "1", "picUrl" to "C:\\a.jpg")).mapToSong())
        assertEquals("", s.cover)
    }
}
