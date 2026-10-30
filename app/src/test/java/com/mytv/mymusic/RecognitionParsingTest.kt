package com.mytv.mymusic

import com.mytv.mymusic.data.native.KugouProvider
import com.mytv.mymusic.data.native.NetEaseProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionParsingTest {
    @Test
    fun `netease audio match response maps songs`() {
        val body = """
            {"data":{"type":1,"result":[
              {"startTime":70020,"song":{"name":"小苹果","id":28606305,
                "artists":[{"name":"筷子兄弟"}],
                "album":{"name":"老男孩之猛龙过江 电影原声","id":2857059,
                         "picUrl":"http://p1.music.126.net/x.jpg"},
                "duration":211363,"fee":0}},
              {"startTime":1000,"song":{"name":"付费歌","id":42,
                "artists":[{"name":"甲"},{"name":"乙"}],
                "album":{"name":"专辑","id":7,"picUrl":""},
                "duration":90000,"fee":1}},
              {"startTime":0}
            ]},"code":200}
        """.trimIndent()
        val songs = NetEaseProvider().parseAudioMatchResponse(body)
        assertEquals(2, songs.size)
        with(songs[0]) {
            assertEquals("28606305", id)
            assertEquals("小苹果", name)
            assertEquals("筷子兄弟", artist)
            assertEquals("老男孩之猛龙过江 电影原声", album)
            assertEquals("2857059", albumId)
            assertEquals(211, duration)
            assertEquals("http://p1.music.126.net/x.jpg", cover)
            assertEquals("netease", source)
            assertEquals("28606305", extra["song_id"])
            assertFalse(isVip)
        }
        assertEquals("甲、乙", songs[1].artist)
        assertTrue(songs[1].isVip)
    }

    @Test
    fun `kugou match response maps hashes and skips entries without hash`() {
        val body = """
            {"error_code":0,"status":1,"data":[
              {"songname":"义勇军进行曲","singername":"中国人民解放军军乐团",
               "hash_128":"041076FB1243213151B85A315C5BD431",
               "hash_320":"87FEF312491B3A38B09D9E102E59F6A0",
               "hash_flac":"17E121858A9F972981F10FD315EA578C",
               "filesize_128":123456,"timelength_128":50000,
               "privilege":8,"mixsongid":40226836,
               "album":[{"albumname":"标准礼仪曲集","albumid":"1078202",
                         "img":"20190610211342908264.jpg"}]},
              {"songname":"没有 hash","singername":"x","album":[]},
              {"songname":"只有 320","singername":"乙","hash_320":"AAAA",
               "timelength_320":30000,"privilege":10,"album":[]}
            ]}
        """.trimIndent()
        val songs = KugouProvider().parseMatchResponse(body)
        assertEquals(2, songs.size)
        with(songs[0]) {
            assertEquals("041076FB1243213151B85A315C5BD431", id)
            assertEquals("义勇军进行曲", name)
            assertEquals("中国人民解放军军乐团", artist)
            assertEquals("标准礼仪曲集", album)
            assertEquals("1078202", albumId)
            assertEquals(50, duration)
            assertEquals(123456L, size)
            assertEquals(19, bitrate)
            assertEquals("http://imge.kugou.com/stdmusic/240/20190610211342908264.jpg", cover)
            assertEquals("17E121858A9F972981F10FD315EA578C", extra["sq_hash"])
            assertEquals("40226836", extra["album_audio_id"])
            assertEquals("kugou", source)
            assertFalse(isVip)
        }
        assertEquals("AAAA", songs[1].id)
        assertTrue(songs[1].isVip)
    }

    @Test
    fun `kugou match signature follows the binary protocol vector`() {
        val params = mapOf(
            "area_code" to "1",
            "clienttime" to "1700000000",
            "dfid" to "-",
            "fpid" to "123",
            "include_unpublish" to "1",
            "mid" to "ABCDEF",
            "multi_result" to "1",
            "useid" to "0",
            "uuid" to "-",
            "appid" to "3116",
            "clientver" to "11440",
        )
        val pcm = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, -1)
        assertEquals(
            "a8400be3387241d5cf6498597fae1cce",
            KugouProvider().matchSignature(params, pcm),
        )
    }
}
