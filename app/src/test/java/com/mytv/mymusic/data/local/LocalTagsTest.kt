package com.mytv.mymusic.data.local

import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTagsTest {
    @Test
    fun junkTitles() {
        listOf("", "  ", "<unknown>", "Track 01", "track_3", "Audio", "Untitled", "未知", "音轨 12", "新录音 3", "录音#2", "01", "2023-01-01")
            .forEach { assertTrue("“$it” 应算占位", LocalTags.isJunkTitle(it, "x.mp3")) }
        // 媒体库没有标题标签时用文件名顶上：等于没有
        assertTrue(LocalTags.isJunkTitle("周杰伦 - 晴天", "周杰伦 - 晴天.flac"))
        assertTrue(LocalTags.isJunkTitle("周杰伦 - 晴天.flac", "周杰伦 - 晴天.flac"))
        // 纯数字标题（如专辑名“1989”）会被当成占位：只补空缺时由匹配结果顶上，代价可以接受
        listOf("晴天", "Track of My Life", "Untitled Love Song", "七里香")
            .forEach { assertFalse("“$it” 是正常标题", LocalTags.isJunkTitle(it, "a.mp3")) }
    }

    @Test
    fun junkArtists() {
        listOf("", "<unknown>", "Unknown Artist", "未知艺术家", "未知歌手", "群星", "Various Artists", "  ")
            .forEach { assertTrue("“$it” 应算占位", LocalTags.isJunkArtist(it)) }
        assertFalse(LocalTags.isJunkArtist("周杰伦"))
        assertFalse(LocalTags.isJunkArtist("Taylor Swift"))
    }

    @Test
    fun usableTagsNeedBothTitleAndArtist() {
        assertTrue(LocalTags.hasUsableTags("晴天", "周杰伦", "01.mp3"))
        assertFalse(LocalTags.hasUsableTags("晴天", "<unknown>", "01.mp3"))
        assertFalse(LocalTags.hasUsableTags("Track 01", "周杰伦", "01.mp3"))
    }

    @Test
    fun guessFromFileName() {
        assertEquals("晴天" to "周杰伦", LocalTags.guessFromFileName("周杰伦 - 晴天.mp3"))
        assertEquals("晴天" to "周杰伦", LocalTags.guessFromFileName("03. 周杰伦 - 晴天.flac"))
        assertEquals("Shake It Off" to "Taylor Swift", LocalTags.guessFromFileName("Taylor_Swift_-_Shake_It_Off.m4a"))
        assertEquals("晴天" to "", LocalTags.guessFromFileName("晴天.mp3"))
        // 连字符夹在词里不拆
        assertEquals("Hi-Fi Rush" to "", LocalTags.guessFromFileName("Hi-Fi Rush.ogg"))
    }

    @Test
    fun searchQuery() {
        assertEquals("晴天 周杰伦", LocalTags.searchQuery("晴天", "周杰伦", "x.mp3"))
        assertEquals("晴天", LocalTags.searchQuery("晴天", "未知艺术家", "x.mp3"))
        assertEquals("晴天 周杰伦", LocalTags.searchQuery("Track 01", "", "周杰伦 - 晴天.mp3"))
        assertEquals("晴天 周杰伦", LocalTags.searchQuery("", "周杰伦", "晴天.mp3"))
    }

    @Test
    fun rematchQueryFallsBackToMatchOnlyWhenFileSaysNothing() {
        val aether = Song(id = "447925558", name = "Aether", artist = "Wisp X", source = "netease")
        assertEquals("Aether Wisp X", LocalTags.rematchQuery("Track 05", "<unknown>", "Track 05.mp3", aether))
        assertEquals("Track 05", LocalTags.rematchQuery("Track 05", "<unknown>", "Track 05.mp3", null))
        // 文件名能猜出歌名时还是用文件自己的说法
        assertEquals("晴天 周杰伦", LocalTags.rematchQuery("", "", "周杰伦 - 晴天.mp3", aether))
        assertEquals("晴天 周杰伦", LocalTags.rematchQuery("晴天", "周杰伦", "01.mp3", aether))
    }

    @Test
    fun splitQueryTakesCandidateArtistOutOfTheQuery() {
        assertEquals("Aether" to "Wisp X", LocalTags.splitQuery("Aether Wisp X", "Wisp X"))
        // 大小写不敏感；歌名保留用户的写法
        assertEquals("aether" to "Wisp X", LocalTags.splitQuery("aether wisp x", "Wisp X、DJ OKAWARI"))
        assertEquals("晴天" to "周杰伦", LocalTags.splitQuery("周杰伦 - 晴天", "周杰伦"))
        // 歌手不在搜索词里：整句当歌名
        assertEquals("Track 05" to "", LocalTags.splitQuery("Track 05", "Buckethead"))
        // 搜索词就是歌手名：拆完歌名为空，不拆
        assertEquals("周杰伦" to "", LocalTags.splitQuery("周杰伦", "周杰伦"))
    }

    private val match = Song(id = "186016", name = "晴天", artist = "周杰伦", album = "叶惠美", source = "netease")

    @Test
    fun titlePolicyFile() {
        val shown = LocalTags.display("Track 01", "<unknown>", "", "01 晴天.mp3", match, LocalTitlePolicy.File)
        assertEquals(LocalDisplay("Track 01", LocalTags.UNKNOWN_ARTIST, ""), shown)
        // 文件策略下标题为空才用文件名
        assertEquals("01 晴天.mp3", LocalTags.display("", "", "", "01 晴天.mp3", match, LocalTitlePolicy.File).title)
    }

    @Test
    fun titlePolicyFillGapsOnlyReplacesPlaceholders() {
        val junk = LocalTags.display("Track 01", "未知艺术家", "", "01.mp3", match, LocalTitlePolicy.FillGaps)
        assertEquals(LocalDisplay("晴天", "周杰伦", "叶惠美"), junk)
        // 像样的标签不覆盖，哪怕和匹配结果写法不同
        val good = LocalTags.display("晴天 (Live)", "Jay Chou", "魔天伦", "x.flac", match, LocalTitlePolicy.FillGaps)
        assertEquals(LocalDisplay("晴天 (Live)", "Jay Chou", "魔天伦"), good)
        // 只缺歌手：标题保留，歌手补上
        val half = LocalTags.display("晴天 (Live)", "", "", "x.flac", match, LocalTitlePolicy.FillGaps)
        assertEquals(LocalDisplay("晴天 (Live)", "周杰伦", "叶惠美"), half)
    }

    @Test
    fun titlePolicyMatchUsesMatchWholesale() {
        val shown = LocalTags.display("晴天 (Live)", "Jay Chou", "魔天伦", "x.flac", match, LocalTitlePolicy.Match)
        assertEquals(LocalDisplay("晴天", "周杰伦", "叶惠美"), shown)
    }

    @Test
    fun unmatchedFallsBackToFileName() {
        val shown = LocalTags.display("Track 01", "", "", "周杰伦 - 晴天.mp3", null, LocalTitlePolicy.FillGaps)
        assertEquals(LocalDisplay("周杰伦 - 晴天.mp3", LocalTags.UNKNOWN_ARTIST, ""), shown)
        val good = LocalTags.display("晴天", "周杰伦", "叶惠美", "a.mp3", null, LocalTitlePolicy.Match)
        assertEquals(LocalDisplay("晴天", "周杰伦", "叶惠美"), good)
    }
}
