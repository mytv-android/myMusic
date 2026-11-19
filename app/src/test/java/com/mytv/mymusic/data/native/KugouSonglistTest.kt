package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷狗分享页 `songlist/gcid_xxx` 的导入。
 *
 * 分享页只内嵌前 10 首（实测 22/30/34/38/277 首的歌单都只给 10 首，且多数是
 * `privilege = 10` 的付费曲目，非会员过滤后只剩 3 首——就是 issue 里「几百首只剩三首」），
 * 所以补齐要靠页面里的 `specialid` 去翻 `special/song` 的全量接口。
 * 这里只测不联网的两段：页面 payload 的解析、全量结果与页面内嵌条目的合并。
 */
class KugouSonglistTest {

    @Test
    fun `songlist payload exposes specialid and embedded songs`() {
        // 真实页面里这段 JSON 是同一行，正则不跨行
        val html = "<script> window.${'$'}output = " +
            """{"encode_gic":"gcid_test123","info":{"listinfo":{"name":"测试歌单",""" +
            """"pic":"http://imge.kugou.com/cover.jpg","intro":"简介","list_create_username":"某人",""" +
            """"count":277,"heat":1234,"specialid":5763404},"songs":[{"hash":"AAA",""" +
            """"name":"Taylor Swift - Look What You Made Me Do","singerinfo":[{"name":"Taylor Swift"}],""" +
            """"albuminfo":{"name":"reputation"},"privilege":10}]}}; </script>"""
        val payload = KugouProvider().parseSonglistPayload(html)
        assertEquals("gcid_test123", payload.encodeGic)
        assertEquals(277, payload.info.listInfo.count)
        assertEquals(5763404, payload.info.listInfo.specialid)
        assertEquals(1, payload.info.songs.size)
        assertEquals("AAA", payload.info.songs[0].hash)
    }

    @Test
    fun `merge enriches matching names and keeps full list order`() {
        val full = listOf(
            song("hash1", "歌一"),
            song("hash2", "歌二"),
            song("hash3", "歌三"),
        )
        val embedded = listOf(
            song("other-hash-2", "歌二", artist = "甲", album = "专辑二", cover = "cover-2", bitrate = 320),
        )
        val merged = KugouProvider().mergeSonglistSongs(full, embedded)
        assertEquals(listOf("歌一", "歌二", "歌三"), merged.map { it.name })
        assertEquals("hash2", merged[1].id)
        assertEquals("甲", merged[1].artist)
        assertEquals("专辑二", merged[1].album)
        assertEquals("cover-2", merged[1].cover)
        assertEquals(320, merged[1].bitrate)
        // 没有对应内嵌条目的保持原样
        assertEquals("", merged[0].album)
        assertEquals("hash3", merged[2].id)
    }

    @Test
    fun `merge without embedded entries returns the full list unchanged`() {
        val full = listOf(song("hash1", "歌一"))
        assertEquals(full, KugouProvider().mergeSonglistSongs(full, emptyList()))
        assertTrue(KugouProvider().mergeSonglistSongs(emptyList(), emptyList()).isEmpty())
    }

    private fun song(
        id: String,
        name: String,
        artist: String = "",
        album: String = "",
        cover: String = "",
        bitrate: Int = 0,
    ) = Song(
        id = id,
        name = name,
        artist = artist,
        album = album,
        cover = cover,
        bitrate = bitrate,
        source = "kugou",
    )
}
