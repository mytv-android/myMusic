package com.mytv.mymusic.ui.poster

import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSizesTest {
    @Test
    fun tierIsTheSmallestThatCoversThePixels() {
        assertEquals(240, coverTier(0))
        assertEquals(240, coverTier(108))
        assertEquals(240, coverTier(240))
        assertEquals(480, coverTier(241))
        assertEquals(480, coverTier(480))
        assertEquals(800, coverTier(606))
        assertEquals(800, coverTier(800))
        // 大卡在平板上要 1200px 左右，也只取到 800
        assertEquals(800, coverTier(1212))
    }

    @Test
    fun kugouSwapsTheSizeSegmentBeforeTheDate() {
        val list = "http://imge.kugou.com/stdmusic/240/20230920/20230920142503632013.jpg"
        assertEquals(list, coverUrlAt(list, 200))
        assertEquals("http://imge.kugou.com/stdmusic/480/20230920/20230920142503632013.jpg", coverUrlAt(list, 400))
        assertEquals("http://imge.kugou.com/stdmusic/800/20230920/20230920142503632013.jpg", coverUrlAt(list, 1200))
        // 榜单封面列表里用的是 400，歌手头像在 softhead 下面
        assertEquals(
            "http://imge.kugou.com/mcommon/240/20241120/20241120202644296668.jpg",
            coverUrlAt("http://imge.kugou.com/mcommon/400/20241120/20241120202644296668.jpg", 100),
        )
        assertEquals(
            "http://singerimg.kugou.com/uploadpic/softhead/800/20260427/20260427091131157350.jpg",
            coverUrlAt("http://singerimg.kugou.com/uploadpic/softhead/240/20260427/20260427091131157350.jpg", 700),
        )
    }

    @Test
    fun kugouFillsAnUnreplacedPlaceholder() {
        assertEquals(
            "http://imge.kugou.com/stdmusic/480/20231016/20231016104901469008.jpg",
            coverUrlAt("http://imge.kugou.com/stdmusic/{size}/20231016/20231016104901469008.jpg", 300),
        )
    }

    @Test
    fun qqUsesTheSizesItActuallyServes() {
        val list = "https://y.gtimg.cn/music/photo_new/T002R300x300M000000MkMni19ClKG.jpg"
        assertEquals(list, coverUrlAt(list, 120))
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R500x500M000000MkMni19ClKG.jpg", coverUrlAt(list, 332))
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R800x800M000000MkMni19ClKG.jpg", coverUrlAt(list, 900))
    }

    @Test
    fun neteaseAddsOrReplacesTheParam() {
        val raw = "https://p1.music.126.net/ZGffiDQZrGj5s_hnR1CNbg==/109951165566379710.jpg"
        assertEquals("$raw?param=240y240", coverUrlAt(raw, 108))
        assertEquals("$raw?param=480y480", coverUrlAt(raw, 400))
        assertEquals("$raw?param=800y800", coverUrlAt("$raw?param=300y300", 606))
        // 其他参数和锚点保留
        assertEquals("$raw?type=webp&param=480y480", coverUrlAt("$raw?type=webp", 300))
        assertEquals("$raw?param=240y240#x", coverUrlAt("$raw#x", 1))
    }

    @Test
    fun kuwoSwapsTheAlbumCoverSize() {
        val list = "http://img1.kuwo.cn/star/albumcover/500/s3s94/93/211513640.jpg"
        assertEquals("http://img1.kuwo.cn/star/albumcover/240/s3s94/93/211513640.jpg", coverUrlAt(list, 150))
        assertEquals("http://img1.kuwo.cn/star/albumcover/480/s3s94/93/211513640.jpg", coverUrlAt(list, 451))
        assertEquals("http://img1.kuwo.cn/star/albumcover/800/s3s94/93/211513640.jpg", coverUrlAt(list, 606))
    }

    @Test
    fun jooxUsesTheSizesItActuallyServes() {
        val list = "https://image.joox.com/JOOXcover/0/6b8aa61497402011/300"
        assertEquals(list, coverUrlAt(list, 200))
        assertEquals("https://image.joox.com/JOOXcover/0/6b8aa61497402011/500", coverUrlAt(list, 451))
        assertEquals("https://image.joox.com/JOOXcover/0/6b8aa61497402011/1000", coverUrlAt(list, 606))
    }

    @Test
    fun sodaRewritesBothTemplates() {
        val base = "https://p3-luna.douyinpic.com/img/tos-cn-v-2774c002/oAACAq3BUHoEt0FZkVIA9DCNyegfYA8WAGyIgD"
        assertEquals("$base~c5_240x240.jpg", coverUrlAt("$base~c5_375x375.jpg", 108))
        assertEquals("$base~c5_800x800.jpg", coverUrlAt("$base~c5_375x375.jpg", 606))
        // 960 的 png 换档时改成 jpg
        assertEquals("$base~tplv-b829550vbb-resize:480:480.jpg", coverUrlAt("$base~tplv-b829550vbb-resize:960:960.png", 332))
    }

    @Test
    fun appleSwapsTheBoundingBox() {
        val base = "https://is1-ssl.mzstatic.com/image/thumb/Music114/v4/85/a5/52/85a55237-6d96-a656-21cf-715b72be15a9/cover.jpg"
        assertEquals("$base/240x240bb.jpg", coverUrlAt("$base/600x600bb.jpg", 147))
        assertEquals("$base/800x800bb.jpg", coverUrlAt("$base/300x300bb.jpg", 700))
    }

    @Test
    fun blankAndUnknownUrlsStayAsTheyAre() {
        assertEquals("", coverUrlAt("", 480))
        assertEquals("  ", coverUrlAt("  ", 480))
        val unknown = listOf(
            "https://i0.hdslb.com/bfs/archive/3d2c5b0f1a.jpg",
            "https://d.musicapp.migu.cn/prod/file-service/file-down/abc.jpg",
            "http://img01.dmhmusic.com/0418/M00/9C/9A/abc.jpg",
            "https://usercontent.jamendo.com?type=album&id=123&width=300",
            "content://media/external/audio/albumart/12",
            // 不认识的 QQ 图（歌手 T001）和不带日期段的酷狗地址也不动
            "https://y.gtimg.cn/music/photo_new/T001R300x300M000002J4UUk29y8BY.jpg",
            "http://imge.kugou.com/temppic/abc.jpg",
        )
        unknown.forEach { assertEquals(it, coverUrlAt(it, 800)) }
    }

    @Test
    fun songCoverAtReadsTheCover() {
        val song = Song(id = "1", cover = "http://img1.kuwo.cn/star/albumcover/500/s4s38/21/3966098545.jpg")
        assertEquals("http://img1.kuwo.cn/star/albumcover/240/s4s38/21/3966098545.jpg", song.coverAt(56))
        assertEquals("", Song(id = "2").coverAt(800))
    }
}
