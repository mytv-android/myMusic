package com.mytv.mymusic.ui.artist

import com.mytv.mymusic.data.AlbumSummary
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.normalizePublishTime
import com.mytv.mymusic.data.toAlbumSummary
import com.mytv.mymusic.ui.CollectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌手页 / 专辑页的纯逻辑：发行时间、按名字认歌手和专辑、页面 key、从歌曲归并专辑。 */
class ArtistAlbumPagesTest {

    @Test
    fun `publish time accepts timestamps and common date shapes`() {
        // 网易云：毫秒时间戳（北京时间 2026-03-25 零点）
        assertEquals("2026-03-25", normalizePublishTime("1774368000000"))
        // 秒时间戳
        assertEquals("2026-03-25", normalizePublishTime("1774368000"))
        assertEquals("2026-03-25", normalizePublishTime("2026-03-25 00:00:00"))
        assertEquals("2025-06-06", normalizePublishTime("2025/6/6"))
        assertEquals("2026-03-25", normalizePublishTime("2026年3月25日"))
        assertEquals("2019", normalizePublishTime("2019"))
        assertEquals("2019", normalizePublishTime("2019 年"))
        assertEquals("", normalizePublishTime("0"))
        assertEquals("", normalizePublishTime("-1"))
        assertEquals("", normalizePublishTime("未知"))
        assertEquals("", normalizePublishTime(""))
    }

    @Test
    fun `album summary reads detail extras and builds year`() {
        val playlist = Playlist(
            id = "0041WVfh2vtlJE",
            name = "太阳之子",
            creator = "周杰伦",
            source = "qq",
            trackCount = 13,
            extra = mapOf(
                "publish_time" to "2026-03-25",
                "company" to "杰威尔",
                "artist_id" to "0025NhlN2yWrP4",
                "album_type" to "录音室专辑",
            ),
        )
        val album = playlist.toAlbumSummary()
        assertEquals("2026", album.year)
        assertEquals("0025NhlN2yWrP4", album.artistId)
        assertEquals("录音室专辑", album.type)
        assertEquals("2026 · 录音室专辑", albumCardSubtitle(album))
        assertTrue(album.hasHeaderInfo())
        // 往返一次不丢头部信息
        assertEquals(album, album.toPlaylist().toAlbumSummary())
        assertFalse(AlbumSummary(id = "1", name = "x", source = "qq").hasHeaderInfo())
    }

    @Test
    fun `merge keeps seed fields the detail does not have`() {
        val seed = AlbumSummary(id = "1", name = "太阳之子", source = "netease", company = "杰威尔", type = "专辑", publishTime = "2026-03-25")
        val detail = AlbumSummary(id = "1", name = "太阳之子", source = "netease", description = "简介", artistId = "6452")
        val merged = mergeAlbumHeader(seed, detail)
        assertEquals("杰威尔", merged.company)
        assertEquals("专辑", merged.type)
        assertEquals("2026-03-25", merged.publishTime)
        assertEquals("简介", merged.description)
        assertEquals("6452", merged.artistId)
        assertEquals(detail, mergeAlbumHeader(null, detail))
    }

    @Test
    fun `artist names split on list separators but not ampersand`() {
        assertEquals(listOf("周杰伦", "言承旭", "五月天 阿信"), splitArtistNames("周杰伦/言承旭/五月天 阿信"))
        assertEquals(listOf("派伟俊", "周杰伦"), splitArtistNames("派伟俊, 周杰伦"))
        assertEquals(listOf("A", "B"), splitArtistNames("A、B；A"))
        assertEquals(listOf("Simon & Garfunkel"), splitArtistNames("Simon & Garfunkel"))
        assertEquals(listOf("Taylor Swift", "Ed Sheeran"), splitArtistNames("Taylor Swift feat. Ed Sheeran"))
        assertEquals(emptyList<String>(), splitArtistNames("未知歌手"))
    }

    @Test
    fun `pick artist prefers exact then contained names and never guesses`() {
        val candidates = listOf(
            Playlist(id = "1", name = "周杰伦乐队"),
            Playlist(id = "2", name = "周杰伦"),
        )
        assertEquals("2", pickArtist("周杰伦", candidates)?.id)
        assertEquals("9", pickArtist("邓紫棋", listOf(Playlist(id = "8", name = "林俊杰"), Playlist(id = "9", name = "G.E.M. 邓紫棋")))?.id)
        // 规整后一样：标点、空格、大小写
        assertEquals("3", pickArtist("g.e.m.邓紫棋", listOf(Playlist(id = "3", name = "G.E.M. 邓紫棋")))?.id)
        // 对不上就不认（B 站 UP 主名、电台名）
        assertNull(pickArtist("某UP主", listOf(Playlist(id = "1", name = "周杰伦"))))
        assertNull(pickArtist("周杰伦", emptyList()))
    }

    @Test
    fun `pick album needs a matching name and prefers the right artist`() {
        val candidates = listOf(
            Playlist(id = "cover", name = "范特西", creator = "翻唱合集"),
            Playlist(id = "real", name = "范特西", creator = "周杰伦"),
            Playlist(id = "plus", name = "范特西 Plus", creator = "周杰伦"),
        )
        assertEquals("real", pickAlbum("范特西", "周杰伦", candidates)?.id)
        assertEquals("cover", pickAlbum("范特西", "", candidates)?.id)
        assertEquals("plus", pickAlbum("范特西plus", "周杰伦", candidates)?.id)
        assertNull(pickAlbum("叶惠美", "周杰伦", candidates))
    }

    @Test
    fun `collection keys round trip and refuse name lookups`() {
        val key = collectionKey(CollectionKind.Album, "mhub", "netease:album/123")
        assertEquals(Triple(CollectionKind.Album, "mhub", "netease:album/123"), parseCollectionKey(key))
        assertNull(parseCollectionKey(collectionKey(CollectionKind.Album, "qq", "name:范特西")))
        assertNull(parseCollectionKey("garbage"))
        assertNull(parseCollectionKey("Nope|qq|1"))
    }

    @Test
    fun `artist keys round trip`() {
        assertEquals(Triple("qq", "0025NhlN2yWrP4", ""), parseArtistKey(artistKey("qq", "0025NhlN2yWrP4")))
        assertEquals(Triple("netease", "", "周杰伦"), parseArtistKey(artistNameKey("netease", "周杰伦")))
        assertNull(parseArtistKey("qq|"))
        assertNull(parseArtistKey("nothing"))
    }

    @Test
    fun `album id prefers qq mid and skips zero ids`() {
        assertEquals("mid", albumIdOf(Song(albumId = "123", extra = mapOf("album_mid" to "mid"))))
        assertEquals("123", albumIdOf(Song(albumId = "123")))
        assertEquals("456", albumIdOf(Song(albumId = "0", extra = mapOf("album_id" to "456"))))
        assertEquals("", albumIdOf(Song(albumId = "0")))
    }

    @Test
    fun `derived albums keep song order and count tracks`() {
        val songs = listOf(
            Song(id = "1", album = "叶惠美", albumId = "a", cover = "c1", artist = "周杰伦"),
            Song(id = "2", album = "范特西", albumId = "b", artist = "周杰伦"),
            Song(id = "3", album = "叶惠美", albumId = "a", artist = "周杰伦"),
            Song(id = "4", album = "", albumId = "", artist = "周杰伦"),
            // 没有专辑 id 的按名字归在一起
            Song(id = "5", album = "Live", artist = "周杰伦"),
            Song(id = "6", album = "live", artist = "周杰伦", cover = "c6"),
        )
        val albums = deriveAlbums(songs, "soda", "jay")
        assertEquals(listOf("叶惠美", "范特西", "Live"), albums.map { it.name })
        assertEquals(listOf(2, 1, 2), albums.map { it.trackCount })
        assertEquals("c1", albums[0].cover)
        assertEquals("c6", albums[2].cover)
        assertEquals("", albums[2].id)
        assertTrue(albums.all { it.source == "soda" && it.artistId == "jay" })
    }

    @Test
    fun `counts and durations read naturally`() {
        assertEquals("591", compactCount(591))
        assertEquals("1.9 万", compactCount(19_230))
        assertEquals("5102 万", compactCount(51_023_750))
        assertEquals("1.2 亿", compactCount(123_000_000))
        assertEquals("3 万", compactCount(30_000))
        assertEquals("", totalDurationLabel(listOf(Song(duration = 0))))
        assertEquals("52 分钟", totalDurationLabel(List(13) { Song(duration = 240) }))
        assertEquals("1 小时 12 分钟", totalDurationLabel(List(18) { Song(duration = 240) }))
    }

    @Test
    fun `album meta and artists come from header first`() {
        val album = AlbumSummary(id = "1", name = "x", source = "qq", publishTime = "2026-03-25", type = "EP", company = "杰威尔", artist = "周杰伦/阿信")
        assertEquals("2026-03-25 · EP · 杰威尔", albumMetaLine(album))
        assertEquals("", albumMetaLine(null))
        assertEquals(listOf("周杰伦", "阿信"), albumArtists(album, emptyList(), "fallback"))
        assertEquals(listOf("林俊杰"), albumArtists(null, listOf(Song(artist = "林俊杰")), "fallback"))
        assertEquals(listOf("fallback"), albumArtists(null, emptyList(), "fallback"))
    }

    @Test
    fun `artist state falls back to seed values until the profile arrives`() {
        val seed = ArtistUiState(key = "k", source = "qq", name = "周杰伦", avatar = "a", seedSongCount = 100, seedAlbumCount = 10)
        assertEquals(100, seed.songCount)
        assertEquals(10, seed.albumCount)
        assertEquals("a", seed.displayAvatar)
        val hot = listOf(Song(id = "h"))
        val loaded = seed.copy(
            profile = com.mytv.mymusic.data.ArtistProfile(id = "1", name = "周杰伦", source = "qq", avatar = "big", songCount = 1012, albumCount = 43),
            hotSongs = hot,
        )
        assertEquals(1012, loaded.songCount)
        assertEquals(43, loaded.albumCount)
        assertEquals("big", loaded.displayAvatar)
        // 歌曲接口失败时热门歌曲顶上
        assertEquals(hot, loaded.visibleSongs)
        // 归并出来的专辑只按读到的算
        val derived = loaded.copy(albumsDerived = true, albums = listOf(AlbumSummary(id = "", name = "x", source = "qq")))
        assertEquals(1, derived.albumCount)
    }
}
