package com.mytv.mymusic

import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.BilibiliTitleParser
import com.mytv.mymusic.data.native.parseDurationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B 站稿件标题是 UP 主手写的宣传文案，接口里「歌手」那一栏是 UP 主名。
 * 聚合匹配只认歌名 + 歌手，所以标题必须能拆回曲目信息，否则这个平台永远选不中。
 */
class BilibiliTitleParserTest {

    private fun parse(raw: String, uploader: String = "某UP主") =
        BilibiliTitleParser.parse(raw, uploader)

    private fun pairs(raw: String, uploader: String = "某UP主") =
        parse(raw, uploader).map { it.title to it.artist }

    @Test
    fun bookTitleCarriesTheSongName() {
        assertTrue(pairs("周杰伦——《晴天》").contains("晴天" to "周杰伦"))
        assertTrue(pairs("《晴天》- 周杰伦").contains("晴天" to "周杰伦"))
        assertTrue(pairs("《倔强》 五月天 动态鼓谱").contains("倔强" to "五月天"))
    }

    @Test
    fun decorationsAroundTheBookTitleAreIgnored() {
        assertTrue(
            pairs("【𝐇𝐢-𝐑𝐞𝐬无损音质】｜《晴天》- 周杰伦 -‘故事的小黄花’").contains("晴天" to "周杰伦"),
        )
    }

    @Test
    fun plainDashTitlesSplitIntoArtistAndSong() {
        assertTrue(pairs("【4K修复】周杰伦 - 晴天MV 2160P修复版").contains("晴天" to "周杰伦"))
        assertTrue(pairs("【4K】五月天 - 倔强（Live纯享版）").contains("倔强" to "五月天"))
        assertTrue(pairs("《晴天》周杰伦 ACOUSTIC POP cover").contains("晴天" to "周杰伦"))
    }

    @Test
    fun ambiguousDashReturnsBothInterpretations() {
        // 「晴天-周杰伦」和「周杰伦-晴天」都合法；多 P 合集更常见的是「周杰伦-晴天」，
        // 所以两种拆法都要给出，由匹配器按歌名和时长挑
        val results = pairs("晴天-周杰伦")
        assertTrue(results.contains("晴天" to "周杰伦"))
        assertTrue(results.contains("周杰伦" to "晴天"))
        assertTrue(pairs("周杰伦-晴天").contains("晴天" to "周杰伦"))
    }

    @Test
    fun trailingBracketIsAnArtist() {
        assertTrue(pairs("【吉他指弹】十年（陈奕迅）").contains("十年" to "陈奕迅"))
    }

    @Test
    fun spaceSeparatedArtistIsRecognised() {
        assertTrue(pairs("【怀旧经典歌曲】十年--陈奕迅演唱--动态简谱").contains("十年" to "陈奕迅"))
    }

    @Test
    fun noArtistFallsBackToEmptySoTheUploaderIsUsed() {
        val parsed = parse("【无损音质】歌曲精选100首 歌词版纯享")
        assertEquals(1, parsed.size)
        assertTrue(parsed.first().artist.isEmpty() || parsed.first().artist.length <= 16)
    }

    @Test
    fun numericAndNoisyFragmentsAreNotArtists() {
        // 4K / 1080P / 数字这类东西不能被当成歌手
        parse("【4K】五月天 - 倔强").forEach { assertFalse(it.artist.any { c -> c.isDigit() }) }
        parse("晴天 动态鼓谱").forEach { assertFalse(it.artist.contains("鼓谱")) }
    }

    /**
     * 这就是用户报的「选什么歌都匹配不到 B 站」：UP 主名被当成歌手时一律判歌手不匹配，
     * 歌手留空、时长又拿不到（详情请求失败退化成 0）时总分只有 0.74，够不到 0.92 的严格阈值。
     * 所以歌名和歌手至少要有一个是准的，时长也得从搜索结果里带上。
     */
    @Test
    fun uploaderAsArtistOrMissingDurationNeverReachesTheThreshold() {
        val source = Song(id = "n", name = "晴天", artist = "周杰伦", album = "叶惠美", duration = 269, source = "netease")
        fun candidate(name: String, artist: String, duration: Int) =
            Song(id = "b", name = name, artist = artist, duration = duration, source = "bilibili")
        val threshold = 0.92f

        // UP 主名当歌手：歌名和时长恰好都对时还能靠「标题 1.0 + 时长一致」豁免，
        // 时长稍差就被判歌手不匹配直接拒掉
        val uploaderAsArtist = TrackMatcher.score(source, "netease", candidate("晴天", "无损音乐合集", 269), "bilibili")
        assertTrue(uploaderAsArtist.total < threshold)
        val uploaderAsArtistOffDuration =
            TrackMatcher.score(source, "netease", candidate("晴天", "无损音乐合集", 250), "bilibili")
        assertTrue(uploaderAsArtistOffDuration.rejected)

        val noArtistNoDuration = TrackMatcher.score(source, "netease", candidate("晴天", "", 0), "bilibili")
        assertTrue(noArtistNoDuration.total < threshold)

        val parsedBoth = TrackMatcher.score(source, "netease", candidate("晴天", "周杰伦", 269), "bilibili")
        assertFalse(parsedBoth.rejected)
        assertTrue(parsedBoth.total >= threshold)

        // 歌手一栏留空时总分只有 0.80（标题 .40 + 中性歌手 .15 + 时长 .20 + 专辑 .05），
        // 光有时长也过不了严格阈值；所以标题拆不出歌手时必须用检索词里的歌手兜底，
        // 这也是 BilibiliProvider 里 keywordArtist 的由来
        val noArtistButDuration = TrackMatcher.score(source, "netease", candidate("晴天", "", 269), "bilibili")
        assertTrue(noArtistButDuration.total < threshold)
        assertFalse(noArtistButDuration.rejected)
    }

    /** 搜索接口的时长是 `"4:30"` 这种字符串；当成数字解析会让整页搜索直接失败。 */
    @Test
    fun searchDurationsAreParsedAsClockText() {
        assertEquals(270, parseDurationText("4:30"))
        assertEquals(3723, parseDurationText("1:02:03"))
        assertEquals(45, parseDurationText("45"))
        assertEquals(0, parseDurationText(""))
        assertEquals(0, parseDurationText("--:--"))
    }

    @Test
    fun uploadFileNamesAreDetected() {
        assertTrue(BilibiliTitleParser.isUploadFileName("studio_video_1784508392756.mp4"))
        assertTrue(BilibiliTitleParser.isUploadFileName("lv_0_20261119083652"))
        assertTrue(BilibiliTitleParser.isUploadFileName(""))
        assertFalse(BilibiliTitleParser.isUploadFileName("晴天 完整版"))
    }
}
