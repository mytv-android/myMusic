package com.mytv.mymusic.data.native

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 歌手页 / 专辑页接口的联网核对，默认跳过；`MYMUSIC_LIVE_TEST=1` 时才跑。
 * 用周杰伦在四个平台的 id，结果打到标准输出（测试报告 XML 的 system-out 里）。
 */
class ArtistAlbumLiveTest {
    private val cases = listOf(
        Triple(NetEaseProvider(), "6452", "netease"),
        Triple(QQProvider(), "0025NhlN2yWrP4", "qq"),
        Triple(KugouProvider(), "3520", "kugou"),
        Triple(KuwoProvider(), "336", "kuwo"),
    )

    @Test
    fun `jay chou artist and album pages`() = runBlocking {
        assumeTrue(System.getenv("MYMUSIC_LIVE_TEST") == "1")
        for ((provider, artistId, name) in cases) {
            val detail = provider.artistDetail("", artistId)
            val profile = detail.profile
            println("[$name] ${profile.name} alias=${profile.alias} avatar=${profile.avatar}")
            println("[$name] songs=${profile.songCount} albums=${profile.albumCount} mvs=${profile.mvCount} fans=${profile.fans} hot=${detail.hotSongs.size}")
            println("[$name] facts=${profile.facts}")
            println("[$name] desc=${profile.description.take(80).replace('\n', '|')}… (${profile.description.length})")
            detail.hotSongs.firstOrNull()?.let { println("[$name] hot[0]=$it") }
            val first = provider.artistAlbums("", artistId, 1, 5)
            println("[$name] albums total=${first.total} hasMore=${first.hasMore}")
            first.albums.forEach { println("[$name]   $it") }
            val second = provider.artistAlbums("", artistId, 2, 5)
            println("[$name] page2: ${second.albums.map { it.name + " " + it.publishTime }}")
            val album = provider.albumDetail("", first.albums.first().id)
            println("[$name] albumDetail=${album.album.copy(description = album.album.description.take(40))} songs=${album.songs.size}")
            assertTrue(profile.name.isNotBlank())
            assertTrue(first.albums.isNotEmpty())
            assertTrue(album.songs.isNotEmpty())
        }
    }
}
