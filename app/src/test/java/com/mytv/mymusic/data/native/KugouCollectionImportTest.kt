package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 酷狗歌单分享链接的导入（issue #35）。
 *
 * 分享页只内嵌前 10 首，概念版自建歌单又没有 specialid 可翻全量；现在按链接里的 gcid
 * 解出 `global_collection_id`，再翻 `get_other_list_file` 的全量分页接口。
 * 这里只测不联网的两段：链接里 gcid 的取法、接口条目到 [Song] 的映射（含音质挑选）。
 */
class KugouCollectionImportTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `gcid comes out of the web share page and the concept app share page`() {
        assertEquals(
            "gcid_3ztmcse8z6z022",
            KugouProvider().kugouLinkGcid("https://www.kugou.com/songlist/gcid_3ztmcse8z6z022/"),
        )
        // 概念版 App 的官方分享页：specialid=-2147483648 是自建歌单的哨兵值，gcid 在 global_specialid
        assertEquals(
            "gcid_3ztmcse8z6z022",
            KugouProvider().kugouLinkGcid(
                "https://activity.kugou.com/share/v-a00a45b0/index.html?u=1556697423" +
                    "&h1=143298444507010930495471534764048014549&h2=406dd14b2efd5e51affd6da64c84d0f6" +
                    "&specialid=-2147483648&global_specialid=gcid_3ztmcse8z6z022&cType=0"
            ),
        )
        // 自建歌单的官方分享页 / 短链跳转后带的是解好的 collection id，不是 gcid
        assertNull(
            KugouProvider().kugouLinkGcid(
                "https://activity.kugou.com/share/v-a00a45b0/index.html?u=2159380325" +
                    "&specialid=-2147483648&global_specialid=collection_3_2159380325_8_0&cType=0"
            ),
        )
        assertNull(KugouProvider().kugouLinkGcid("https://www.kugou.com/yy/special/single/5763404.html"))
        assertNull(KugouProvider().kugouLinkGcid("https://music.163.com/playlist?id=1"))
    }

    @Test
    fun `share pages that already carry the collection id skip decoding`() {
        val share = "https://activity.kugou.com/share/v-a00a45b0/index.html?u=2159380325" +
            "&specialid=-2147483648&global_specialid=collection_3_2159380325_8_0&cType=0"
        assertEquals("collection_3_2159380325_8_0", KugouProvider().kugouLinkCollectionId(share))
        assertEquals(
            "collection_3_2159380325_8_0",
            KugouProvider().kugouLinkCollectionId("https://www.kugou.com/songlist/collection_3_2159380325_8_0/"),
        )
        assertEquals(
            "collection_3_2159380325_8_0",
            KugouProvider().kugouLinkCollectionId(
                "https://www.kugou.com/songlist/?global_collection_id=collection_3_2159380325_8_0"
            ),
        )
        assertNull(KugouProvider().kugouLinkCollectionId("https://t1.kugou.com/5ZcU352G7V2"))
        assertNull(KugouProvider().kugouLinkCollectionId("https://www.kugou.com/songlist/gcid_3ztmcse8z6z022/"))
    }

    @Test
    fun `concept app short link yields the share chain`() {
        // 概念版 App 「分享 → 复制链接」出来的就是这种短链（issue #35 里用户贴的那条）
        assertEquals("5ZcU352G7V2", KugouProvider().kugouLinkChain("https://t1.kugou.com/5ZcU352G7V2"))
        assertEquals("5ZcU352G7V2", KugouProvider().kugouLinkChain("t1.kugou.com/5ZcU352G7V2"))
        assertEquals(
            "5ZcU352G7V2",
            KugouProvider().kugouLinkChain(
                "http://m.kugou.com/schain/transfer?pagesize=300&chain=5ZcU352G7V2&su=1&page=1"
            ),
        )
        assertNull(KugouProvider().kugouLinkChain("https://www.kugou.com/songlist/gcid_3ztmcse8z6z022/"))
        assertNull(KugouProvider().kugouLinkChain("https://www.kugou.com/yy/special/single/5763404.html"))
    }

    @Test
    fun `songlist payload exposes the global collection id`() {
        val html = "<script> window.${'$'}output = " +
            """{"encode_gic":"gcid_test123","global_collection_id":"collection_3_1556697423_6_0",""" +
            """"info":{"listinfo":{"name":"测试歌单","pic":"http://imge.kugou.com/cover.jpg",""" +
            """"intro":"简介","list_create_username":"某人","count":277,"heat":1234,"specialid":0},"songs":[]}}; </script>"""
        val payload = KugouProvider().parseSonglistPayload(html)
        assertEquals("collection_3_1556697423_6_0", payload.globalCollectionId)
        // 概念版自建歌单没有 specialid：走不通 special/song，只能靠 gcid 解 global_collection_id
        assertEquals(0, payload.info.listInfo.specialid)
    }

    @Test
    fun `collection file item maps to a song with the best quality hash`() {
        // 真实接口回的一段（get_other_list_file，277 首里排序第一的那首）
        val raw = """{"hash":"B0BB4EB6A7A5A121DCE67B6B3069734E","audio_id":27846718,"size":3391170,
            "name":"Taylor Swift - Look What You Made Me Do","album_id":"5293931","timelen":211905,
            "bitrate":128,"fileid":283,"privilege":10,"sort":0,
            "cover":"http://imge.kugou.com/stdmusic/{size}/20240510/20240510110428670256.jpg",
            "mvhash":"7CDCB967413B571728055F41AF8CACDD","mixsongid":96462581,
            "audio_group_id":"9549030","add_mixsongid":96462581,
            "relate_goods":[
              {"hash":"B0BB4EB6A7A5A121DCE67B6B3069734E","size":3391170,"bitrate":128,"privilege":10,"level":2},
              {"hash":"5554F5639222A2ADD9FF23FD4D3BD5F3","size":8477525,"bitrate":320,"privilege":10,"level":4},
              {"hash":"BAE342B2CCD0B1EC5B719C01C6F67431","size":25476532,"bitrate":961,"privilege":10,"level":5},
              {"hash":"BCFCE5072C635D5AE1D73D8C1EF0FB81","size":44092948,"bitrate":1664,"privilege":10,"level":6}],
            "trans_param":{"ogg_320_hash":"96ED89E40DBF3F3E1DF4A6F7A477AD5B","ogg_320_filesize":8913348,
              "ogg_128_hash":"49E8336A3D9B0A80CECB7CC0835805DE","ogg_128_filesize":2536782,
              "union_cover":"http://imge.kugou.com/stdmusic/{size}/20240510/20240510110428670256.jpg"},
            "albuminfo":{"name":"reputation","id":5293931},
            "singerinfo":[{"name":"Taylor Swift","id":34450}]}"""
        val item = json.decodeFromString<KugouCollectionFileItem>(raw)
        val song = KugouProvider().collectionFileToSong(item)!!

        // 音质挑最高的：1664k 母带
        assertEquals("BCFCE5072C635D5AE1D73D8C1EF0FB81", song.id)
        assertEquals(44092948L, song.size)
        assertEquals("Look What You Made Me Do", song.name)
        assertEquals("Taylor Swift", song.artist)
        assertEquals("reputation", song.album)
        assertEquals("5293931", song.albumId)
        assertEquals(211, song.duration)
        assertEquals("kugou", song.source)
        assertEquals(true, song.isVip)
        assertEquals(
            "http://imge.kugou.com/stdmusic/240/20240510/20240510110428670256.jpg",
            song.cover,
        )
        assertEquals("96ED89E40DBF3F3E1DF4A6F7A477AD5B", song.extra["ogg_320_hash"])
        // pickSonglistHashes 把 700k 以上都算作 sq，母带（1664k）会盖掉无损（961k）
        assertEquals(song.id, song.extra["sq_hash"])
        assertEquals("96462581", song.extra["album_audio_id"])
        assertEquals("https://www.kugou.com/song/#hash=BCFCE5072C635D5AE1D73D8C1EF0FB81", song.link)
    }

    @Test
    fun `items without any usable hash are dropped`() {
        val raw = """{"hash":"","name":"某首歌","size":0,"bitrate":0,"sort":3,"relate_goods":[]}"""
        val item = json.decodeFromString<KugouCollectionFileItem>(raw)
        assertNull(KugouProvider().collectionFileToSong(item))
    }
}
