package com.mytv.mymusic

import com.mytv.mymusic.data.native.NetEaseProvider
import com.mytv.mymusic.data.native.NetEaseProvider.NeteaseLinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 网易分享链接的各路形式：查询参数、#/ 路由、路径 id、移动端域名和分享文本。 */
class NeteaseLinkParseTest {
    private val provider = NetEaseProvider()

    private fun assertSong(link: String, id: String) {
        assertEquals(NeteaseLinkKind.Song to id, provider.parseNeteaseLink(link))
    }

    @Test
    fun parsesSongQueryLink() = assertSong("https://music.163.com/song?id=186016", "186016")

    @Test
    fun parsesHashRouteLink() = assertSong("https://music.163.com/#/song?id=186016", "186016")

    @Test
    fun parsesPathIdLink() = assertSong("https://music.163.com/song/186016", "186016")

    @Test
    fun parsesPathIdWithTrailingQuery() =
        assertSong("https://music.163.com/song/186016/?userid=123", "186016")

    @Test
    fun parsesMobileLink() = assertSong("https://y.music.163.com/m/song?id=186016&uct2=abc", "186016")

    @Test
    fun parsesLinkInsideShareText() =
        assertSong("分享单曲《晴天》: https://music.163.com/song?id=186016 (来自网易云音乐)", "186016")

    @Test
    fun parsesPlaylistAndAlbumPathLinks() {
        assertEquals(
            NeteaseLinkKind.Playlist to "123456",
            provider.parseNeteaseLink("https://music.163.com/playlist/123456"),
        )
        assertEquals(
            NeteaseLinkKind.Album to "654321",
            provider.parseNeteaseLink("https://music.163.com/album/654321?userid=1"),
        )
    }

    @Test
    fun rejectsUnrelatedText() {
        assertNull(provider.parseNeteaseLink("随便一段文字，没有链接"))
        assertNull(provider.parseNeteaseLink("https://music.163.com/"))
    }
}
