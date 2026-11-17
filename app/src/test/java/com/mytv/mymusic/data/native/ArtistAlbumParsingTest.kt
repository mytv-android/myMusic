package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌手页 / 专辑页平台层的纯解析：简介清洗、QQ 百科、各平台歌手资料与专辑列表（样本取自 2026-11-17 的真实响应，删减过）。 */
class ArtistAlbumParsingTest {

    @Test
    fun `plain text decodes entities and keeps paragraphs`() {
        val raw = "周杰伦（Jay&nbsp;Chou）获得&quot;最佳国语专辑&quot;奖<br>第二段&amp;nbsp;结尾<br/><br />\n\n\n" +
            "<p>第三段 <strong>加粗</strong></p><p>&#20320;&#x597D;</p>"
        assertEquals(
            "周杰伦（Jay Chou）获得\"最佳国语专辑\"奖\n第二段 结尾\n\n第三段 加粗\n你好",
            artistPlainText(raw),
        )
    }

    @Test
    fun `plain text trims indentation and stray backslashes`() {
        assertEquals("第一段\n\n第二段", artistPlainText("　　第一段\\\\\n \n\n　　第二段  "))
        assertEquals("", artistPlainText("  <p></p>&nbsp;"))
        // 不认识的实体和不像标签的尖括号原样保留
        assertEquals("a &unknown; <3", artistPlainText("a &unknown; <3"))
    }

    @Test
    fun `qq wiki gives description and short basic facts`() {
        val xml = "<info><id>4558</id><desc><![CDATA[周杰伦（Jay Chou），1979年1月18日出生。\n\n2000年发行首张专辑。]]></desc>" +
            "<basic><item><key><![CDATA[外文名]]></key><value><![CDATA[Jay Chou]]></value></item>" +
            "<item><key><![CDATA[生日]]></key><value><![CDATA[1979年1月18日]]></value></item>" +
            "<item><key><![CDATA[主要成就]]></key><value><![CDATA[" + "奖".repeat(ARTIST_FACT_MAX_LENGTH + 1) + "]]></value></item>" +
            "<item><key>出道日期</key><value>2000&#24180;</value></item></basic>" +
            "<other><item><key><![CDATA[从艺历程]]></key><value><![CDATA[1997年…]]></value></item></other></info>"
        val wiki = parseQQSingerWiki(xml)
        assertEquals("周杰伦（Jay Chou），1979年1月18日出生。\n\n2000年发行首张专辑。", wiki.description)
        assertEquals(
            listOf("外文名" to "Jay Chou", "生日" to "1979年1月18日", "出道日期" to "2000年"),
            wiki.facts,
        )
        assertEquals(QQSingerWiki(), parseQQSingerWiki(""))
    }

    @Test
    fun `qq singer detail batch maps profile`() {
        val body = """
            {"code":0,
             "detail":{"code":0,"data":{"singer_list":[{
               "basic_info":{"singer_mid":"0025NhlN2yWrP4","name":"周杰伦","singer_pmid":"0025NhlN2yWrP4_11"},
               "ex_info":{"desc":"旧简介","foreign_name":"Jay Chou","birthday":"1979-01-18"},
               "wiki":"<info><desc><![CDATA[百科简介]]></desc><basic><item><key><![CDATA[国籍]]></key><value><![CDATA[中国]]></value></item></basic></info>"}]}},
             "songs":{"code":0,"data":{"singerMid":"0025NhlN2yWrP4","totalNum":1012,"songList":[]}},
             "albums":{"code":0,"data":{"albumList":[],"total":43}},
             "fans":{"code":0,"data":{"map_user_num":{},"map_singer_num":{"0025NhlN2yWrP4":{"user_fansnum":51023750}}}}}
        """.trimIndent()
        val profile = QQProvider().parseSingerDetail(body, "0025NhlN2yWrP4")
        assertEquals("周杰伦", profile.name)
        assertEquals("https://y.gtimg.cn/music/photo_new/T001R800x800M0000025NhlN2yWrP4_11.jpg", profile.avatar)
        assertEquals("Jay Chou", profile.alias)
        assertEquals("百科简介", profile.description)
        assertEquals(1012, profile.songCount)
        assertEquals(43, profile.albumCount)
        assertEquals(51023750L, profile.fans)
        assertEquals(listOf("国籍" to "中国"), profile.facts)
        assertEquals("https://y.qq.com/n/ryqq/singer/0025NhlN2yWrP4", profile.link)
    }

    @Test
    fun `qq singer without wiki falls back to ex info`() {
        val body = """
            {"detail":{"code":0,"data":{"singer_list":[{"basic_info":{"singer_mid":"m1","name":"A"},
              "ex_info":{"desc":"简介&nbsp;一","foreign_name":"","birthday":"1990-02-03"},"wiki":""}]}}}
        """.trimIndent()
        val profile = QQProvider().parseSingerDetail(body, "m1")
        assertEquals("简介 一", profile.description)
        assertEquals(listOf("生日" to "1990-02-03"), profile.facts)
        assertEquals("https://y.gtimg.cn/music/photo_new/T001R800x800M000m1.jpg", profile.avatar)
        assertEquals(0L, profile.fans)
    }

    @Test
    fun `qq singer albums page`() {
        val body = """
            {"code":0,"albums":{"code":0,"data":{"singerMid":"0025NhlN2yWrP4","albumList":[
              {"albumMid":"0041WVfh2vtlJE","albumName":"太阳之子","publishDate":"2026-03-25","totalNum":13,"albumType":"录音室专辑","singerName":"周杰伦"},
              {"albumMid":"004Bux530GytNj","albumName":"恒星不忘 Forever Forever","publishDate":"2025-12-05","totalNum":1,"albumType":"Single","singerName":"周杰伦/五月天 阿信"},
              {"albumMid":"","albumName":"坏数据"}],"total":43}}}
        """.trimIndent()
        val page = QQProvider().parseSingerAlbums(body, "0025NhlN2yWrP4", begin = 0, limit = 3)
        assertEquals(43, page.total)
        assertTrue(page.hasMore)
        assertEquals(2, page.albums.size)
        val first = page.albums[0]
        assertEquals("0041WVfh2vtlJE", first.id)
        assertEquals("qq", first.source)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R300x300M0000041WVfh2vtlJE.jpg", first.cover)
        assertEquals("2026-03-25", first.publishTime)
        assertEquals("录音室专辑", first.type)
        assertEquals(13, first.trackCount)
        assertEquals("0025NhlN2yWrP4", first.artistId)
        assertFalse(QQProvider().parseSingerAlbums(body, "x", begin = 41, limit = 3).hasMore)
    }

    @Test
    fun `kuwo single quoted album list`() {
        val body = "{'albumlist':[{'PAY':'1','aartist':'Jay&nbsp;Chou','albumid':'87758985','artist':'周杰伦'," +
            "'artistid':'336','company':'杰威尔音乐有限公司','hts_img':'https://img1.kuwo.cn/star/albumcover/240/s4s86/95/3059703046.jpg'," +
            "'id':'87758985','info':'第一行\\n第二行','musiccnt':'13','name':'太阳之子','pub':'2026-03-25','startype':'专辑'}," +
            "{'albumid':'80569629','artist':'周杰伦&言承旭&五月天&nbsp;阿信','name':'恒星不忘&nbsp;Forever&nbsp;Forever'," +
            "'img':'http://img4.sycdn.kuwo.cn/star/albumcover/240/s4s54/82/1778775391.jpg','musiccnt':'1','pub':'2025-12-05','startype':'专辑'}]," +
            "'pn':'0','return':'2','total':'45'}"
        val page = KuwoProvider().parseArtistAlbums(body, "336", page = 1, limit = 2)
        assertEquals(45, page.total)
        assertTrue(page.hasMore)
        assertEquals(listOf("87758985", "80569629"), page.albums.map { it.id })
        val first = page.albums[0]
        assertEquals("太阳之子", first.name)
        assertEquals("https://img1.kuwo.cn/star/albumcover/500/s4s86/95/3059703046.jpg", first.cover)
        assertEquals("2026-03-25", first.publishTime)
        assertEquals("杰威尔音乐有限公司", first.company)
        assertEquals(13, first.trackCount)
        assertEquals("336", first.artistId)
        assertEquals("恒星不忘 Forever Forever", page.albums[1].name)
        assertEquals("周杰伦&言承旭&五月天 阿信", page.albums[1].artist)
        assertFalse(KuwoProvider().parseArtistAlbums(body, "336", page = 23, limit = 2).hasMore)
    }

    @Test
    fun `kuwo artist info facts avatar and intro`() {
        val body = "{'aartist':'Jay&nbsp;Chou','albumnum':'45','birthday':'1979-01-18','birthplace':'台湾'," +
            "'constellation':'魔羯座','country':'中国台湾','desc':'同 info','gender':'男'," +
            "'hts_pic':'https://img1.kuwo.cn/star/starheads/240/s4s56/58/291211030.jpg','id':'336'," +
            "'info':'周杰伦（Jay&nbsp;Chou），华语男歌手。&nbsp;2001年发行《范特西》。\\n基本资料\\n外文名：Jay&nbsp;Chou\\n从艺历程\\n1997年…'," +
            "'language':'国语;粤语;','musicnum':'1707','mvnum':'436','name':'周杰伦','oname':'周董'," +
            "'pic':'120/s4s56/58/291211030.jpg','tag':[{'cat1':'中国风','type':'Category'}],'tall':'173cm','weight':''}"
        val profile = KuwoProvider().parseArtistInfo(body, "336")
        assertEquals("周杰伦", profile.name)
        assertEquals("Jay Chou / 周董", profile.alias)
        assertEquals("https://img1.kuwo.cn/star/starheads/1000/s4s56/58/291211030.jpg", profile.avatar)
        assertEquals("周杰伦（Jay Chou），华语男歌手。 2001年发行《范特西》。", profile.description)
        assertEquals(1707, profile.songCount)
        assertEquals(45, profile.albumCount)
        assertEquals(436, profile.mvCount)
        assertEquals(
            listOf(
                "性别" to "男",
                "生日" to "1979-01-18",
                "星座" to "魔羯座",
                "出生地" to "台湾",
                "国家/地区" to "中国台湾",
                "语种" to "国语、粤语",
                "身高" to "173cm",
            ),
            profile.facts,
        )
    }

    @Test
    fun `kuwo avatar from relative pic path`() {
        val body = "{'id':'47','name':'陈奕迅','pic':'120/37/26/3816222178.jpg','hts_pic':''}"
        assertEquals(
            "https://img1.kuwo.cn/star/starheads/1000/37/26/3816222178.jpg",
            KuwoProvider().parseArtistInfo(body, "47").avatar,
        )
    }

    @Test
    fun `kugou singer albums strip res tag`() {
        val body = "<!--KG_TAG_RES_START-->{\"data\":{\"timestamp\":1794887510,\"total\":49,\"info\":[" +
            "{\"singerid\":3520,\"songcount\":13,\"publishtime\":\"2026-03-25 00:00:00\",\"singername\":\"周杰伦\"," +
            "\"albumname\":\"太阳之子\",\"albumid\":179652761,\"intro\":\"…\",\"imgurl\":\"http://imge.kugou.com/stdmusic/{size}/20260319/20260319101420611881.jpg\"}," +
            "{\"singerid\":3520,\"songcount\":1,\"publishtime\":\"2025-12-05 00:00:00\",\"singername\":\"周杰伦、言承旭\"," +
            "\"albumname\":\"恒星不忘 Forever Forever\",\"albumid\":169677025,\"imgurl\":\"\"}]}," +
            "\"errcode\":0,\"status\":1,\"error\":\"\"}<!--KG_TAG_RES_END-->"
        val page = KugouProvider().parseSingerAlbums(body, "3520", page = 1, limit = 2)
        assertEquals(49, page.total)
        assertTrue(page.hasMore)
        val first = page.albums[0]
        assertEquals("179652761", first.id)
        assertEquals("http://imge.kugou.com/stdmusic/240/20260319/20260319101420611881.jpg", first.cover)
        assertEquals("2026-03-25", first.publishTime)
        assertEquals(13, first.trackCount)
        assertEquals("3520", first.artistId)
        assertEquals("https://www.kugou.com/album/179652761.html", first.link)
        assertFalse(KugouProvider().parseSingerAlbums(body, "3520", page = 25, limit = 2).hasMore)
    }

    @Test
    fun `kugou singer profile prefers long profile`() {
        val body = "<!--KG_TAG_RES_START-->{\"data\":{\"songcount\":1872,\"singername\":\"周杰伦\"," +
            "\"profile\":\"第一段。\\n\\n第二段。\",\"mvcount\":2840,\"singerid\":3520,\"albumcount\":49," +
            "\"mix_intro\":\"<p>HTML 版</p>\",\"alias\":\"\",\"intro\":\"短简介\"," +
            "\"imgurl\":\"http://singerimg.kugou.com/uploadpic/softhead/{size}/20260324/20260324203006202058.jpg\"}," +
            "\"errcode\":0,\"status\":1,\"error\":\"\"}<!--KG_TAG_RES_END-->"
        val profile = KugouProvider().parseSingerProfile(body, "3520")
        assertEquals("周杰伦", profile.name)
        assertEquals("http://singerimg.kugou.com/uploadpic/softhead/800/20260324/20260324203006202058.jpg", profile.avatar)
        assertEquals("第一段。\n\n第二段。", profile.description)
        assertEquals(1872, profile.songCount)
        assertEquals(49, profile.albumCount)
        assertEquals(2840, profile.mvCount)
    }

    @Test
    fun `netease artist albums page`() {
        val body = """
            {"code":200,"more":true,
             "artist":{"id":6452,"name":"周杰伦","albumSize":49},
             "hotAlbums":[
               {"id":403900711,"name":"太阳之子","picUrl":"https://p2.music.126.net/a.jpg","size":13,"company":"杰威尔",
                "description":"","briefDesc":"","publishTime":1774409488576,"type":"专辑","subType":"录音室版",
                "artist":{"id":6452,"name":"周杰伦"},"artists":[{"id":6452,"name":"周杰伦"}]},
               {"id":2,"name":"Fantasy Show香港演唱会","picUrl":"","size":6,"publishTime":1004803200000,
                "type":"EP","subType":"现场版","artist":{"id":6452,"name":"周杰伦"}}]}
        """.trimIndent()
        val page = NetEaseProvider().parseArtistAlbums(body, "6452")
        assertEquals(49, page.total)
        assertTrue(page.hasMore)
        val first = page.albums[0]
        assertEquals("403900711", first.id)
        assertEquals("2026-03-25", first.publishTime)
        assertEquals("专辑", first.type)
        assertEquals(13, first.trackCount)
        assertEquals("杰威尔", first.company)
        assertEquals("6452", first.artistId)
        assertEquals("EP · 现场版", page.albums[1].type)
        assertEquals("2001-11-04", page.albums[1].publishTime)
    }

    @Test
    fun `netease artist detail maps hot songs like search results`() {
        val body = """
            {"code":200,"more":true,
             "artist":{"id":6452,"name":"周杰伦","picUrl":"https://p1.music.126.net/x==/109951169164936450.jpg",
               "img1v1Url":"https://p1.music.126.net/y.jpg","briefDesc":"第一段&nbsp;。\n第二段","trans":"",
               "alias":["Jay Chou","周董"],"musicSize":591,"albumSize":49,"mvSize":9},
             "hotSongs":[{"id":210049,"name":"布拉格广场","dt":294600,"fee":1,
               "ar":[{"id":7219,"name":"蔡依林"},{"id":6452,"name":"周杰伦"}],
               "al":{"id":21349,"name":"看我72变","picUrl":"https://p1.music.126.net/c.jpg"},
               "h":{"size":11786493},"m":{"size":7071913},"l":{"size":4714623},
               "privilege":{"id":210049,"fee":1,"fl":0,"maxBrLevel":"lossless"}}]}
        """.trimIndent()
        val detail = NetEaseProvider().parseArtistDetail(body)
        val profile = detail.profile
        assertEquals("周杰伦", profile.name)
        assertEquals("https://p1.music.126.net/x==/109951169164936450.jpg?param=800y800", profile.avatar)
        assertEquals("Jay Chou / 周董", profile.alias)
        assertEquals("第一段 。\n第二段", profile.description)
        assertEquals(591, profile.songCount)
        assertEquals(49, profile.albumCount)
        assertEquals(9, profile.mvCount)
        val song = detail.hotSongs.single()
        assertEquals("210049", song.id)
        assertEquals("netease", song.source)
        assertEquals("蔡依林、周杰伦", song.artist)
        assertEquals("看我72变", song.album)
        assertEquals("21349", song.albumId)
        assertEquals("https://p1.music.126.net/c.jpg", song.cover)
        assertEquals(294, song.duration)
        assertEquals("210049", song.extra["song_id"])
        assertEquals("21349", song.extra["album_id"])
        assertEquals("lossless", song.extra["netease_level"])
        assertTrue(song.isVip)
    }

    @Test
    fun `netease default artist picture means no avatar`() {
        val body = """{"code":200,"artist":{"id":1,"name":"A","picUrl":"https://p2.music.126.net/6y-UleORITEDbvrOLV0Q8A==/5639395138885805.jpg"}}"""
        assertEquals("", NetEaseProvider().parseArtistDetail(body).profile.avatar)
    }

    @Test
    fun `netease image size param`() {
        assertEquals("https://p1.music.126.net/a.jpg?param=800y800", neteaseImageAt("https://p1.music.126.net/a.jpg", 800))
        assertEquals("https://p1.music.126.net/a.jpg?param=500y500", neteaseImageAt("https://p1.music.126.net/a.jpg?param=240y240", 500))
        assertEquals("https://p1.music.126.net/a.jpg?x=1&param=800y800", neteaseImageAt("https://p1.music.126.net/a.jpg?x=1", 800))
        assertEquals("专辑", neteaseAlbumType("专辑", "录音室版"))
        assertEquals("现场版", neteaseAlbumType("", "现场版"))
    }

    @Test
    fun `loose album summary fills release date and label`() {
        val apple = Playlist(
            id = "1",
            name = "A",
            source = "apple",
            extra = mapOf("release_date" to "2016-06-24", "record_label" to "JVR"),
        ).toLooseAlbumSummary()
        assertEquals("2016-06-24", apple.publishTime)
        assertEquals("JVR", apple.company)
        val soda = Playlist(id = "2", name = "B", source = "soda", extra = mapOf("release_date" to "1774368000")).toLooseAlbumSummary()
        assertEquals("2026-03-25", soda.publishTime)
        val qq = Playlist(id = "3", name = "C", source = "qq", extra = mapOf("publish_time" to "2026-03-25", "artist_id" to "m1", "album_type" to "EP"))
            .toLooseAlbumSummary()
        assertEquals("2026-03-25", qq.publishTime)
        assertEquals("m1", qq.artistId)
        assertEquals("EP", qq.type)
    }
}
