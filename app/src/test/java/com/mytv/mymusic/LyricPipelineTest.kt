package com.mytv.mymusic

import com.mytv.mymusic.data.LyricSubline
import com.mytv.mymusic.data.LyricTextPrefs
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.LyricPipeline
import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.LyricsParser
import com.mytv.mymusic.ui.ScriptConverter
import com.mytv.mymusic.ui.withSubline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricPipelineTest {
    /** 假的转换器：按小表逐字简转繁；罗马音给每个字加前缀，看得出转过。 */
    private class FakeConverter(
        override val traditionalAvailable: Boolean = true,
        override val romanizeAvailable: Boolean = true,
    ) : ScriptConverter {
        val table = mapOf('风' to '風', '过' to '過', '们' to '們', '声' to '聲', '听' to '聽', '见' to '見')
        override fun toTraditional(text: String): String = text.map { table[it] ?: it }.joinToString("")
        override fun romanize(text: String): String = "roma(" + text + ")"
    }

    private fun line(ms: Long, text: String, translation: String = "", words: List<LyricWord> = emptyList()) =
        LyricLine(ms, text, words, translation)

    @Test
    fun splitsWholeSongWrittenAsOriginalSlashTranslation() {
        val lines = (0 until 6).map { line(it * 1000L, "第${it}句 / Line $it") }
        val out = LyricPipeline.splitBilingual(lines)
        out.forEachIndexed { i, l ->
            assertEquals("第${i}句", l.text)
            assertEquals("Line $i", l.translation)
        }
    }

    @Test
    fun leavesOccasionalSlashAlone() {
        // 20 句里只有 2 句带斜杠：(2 + 4) / 20 = 0.3，不到 0.8，不拆
        val lines = (0 until 20).map { line(it * 1000L, if (it < 2) "AC/DC 第$it 句" else "普通的第$it 句") }
        val out = LyricPipeline.splitBilingual(lines)
        assertSame(lines, out)
    }

    @Test
    fun thresholdCountsOnlyRealLines() {
        // 4 句能拆 + 4 句署名 / 间奏 / 空行：剔除后 (4 + 4) / 4 > 0.8，拆；署名行本身不动
        val lines = listOf(
            line(0, "作词：张三/李四"),
            line(500, "♪ ♪"),
            line(800, ""),
            line(900, "/"),
        ) + (1..4).map { line(it * 1000L, "原文$it / 译文$it") }
        val out = LyricPipeline.splitBilingual(lines)
        assertEquals("作词：张三/李四", out[0].text)
        assertEquals("", out[0].translation)
        assertEquals("原文1", out[4].text)
        assertEquals("译文1", out[4].translation)
    }

    @Test
    fun splitPointEdges() {
        assertNull(LyricPipeline.splitPoint("/ 开头", "/"))
        assertNull(LyricPipeline.splitPoint("结尾 /", "/"))
        assertNull(LyricPipeline.splitPoint("a/b/c", "/"))
        assertEquals(6, LyricPipeline.splitPoint("a/b c / d", "/"))
        // “//”不算两个“/”
        assertNull(LyricPipeline.splitPoint("原文 // 译文", "/"))
        assertEquals(3, LyricPipeline.splitPoint("原文 // 译文", "//"))
        assertNull(LyricPipeline.splitPoint("   ", "/"))
    }

    @Test
    fun prefersDoubleSlashWhenSongUsesIt() {
        val lines = (0 until 5).map { line(it * 1000L, "原文$it // a/b $it") }
        val out = LyricPipeline.splitBilingual(lines)
        assertEquals("原文0", out[0].text)
        assertEquals("a/b 0", out[0].translation)
    }

    @Test
    fun keepsExistingTranslationAndTrimsWordTimings() {
        val words = listOf(LyricWord(0, 400, "你好"), LyricWord(400, 800, " / "), LyricWord(800, 1200, "hello"))
        val lines = listOf(
            line(0, "你好 / hello", words = words),
            line(2000, "已经 / 有翻译", translation = "kept"),
        ) + (1..4).map { line(3000L + it * 1000, "再$it / again") }
        val out = LyricPipeline.splitBilingual(lines)
        assertEquals("你好", out[0].text)
        assertEquals("hello", out[0].translation)
        assertEquals(listOf(LyricWord(0, 400, "你好")), out[0].words)
        assertEquals("已经 / 有翻译", out[1].text)
        assertEquals("kept", out[1].translation)
    }

    @Test
    fun dropsWordTimingsWhenTheyDoNotEndAtTheDivider() {
        val words = listOf(LyricWord(0, 600, "你好 /"), LyricWord(600, 1200, " hello"))
        val lines = listOf(line(0, "你好 / hello", words = words)) + (1..4).map { line(it * 1000L, "再$it / again") }
        val out = LyricPipeline.splitBilingual(lines)
        assertEquals("你好", out[0].text)
        assertTrue(out[0].words.isEmpty())
    }

    @Test
    fun traditionalConvertsTextTranslationAndWordsButSkipsKana() {
        val converter = FakeConverter()
        val plain = LyricPipeline.toTraditional(line(0, "风吹过", translation = "我们听见"), converter)
        assertEquals("風吹過", plain.text)
        assertEquals("我們聽見", plain.translation)

        val worded = LyricPipeline.toTraditional(
            line(0, "风吹过", words = listOf(LyricWord(0, 300, "风"), LyricWord(300, 600, "吹过"))),
            converter,
        )
        assertEquals("風吹過", worded.text)
        assertEquals(listOf("風", "吹過"), worded.words.map { it.text })
        assertEquals(listOf(0L, 300L), worded.words.map { it.startMs })

        // 日文里的汉字不转；它的中文翻译照转
        val japanese = LyricPipeline.toTraditional(line(0, "君の声が听こえる", translation = "听见你的声音"), converter)
        assertEquals("君の声が听こえる", japanese.text)
        assertEquals("聽見你的聲音", japanese.translation)

        val english = line(0, "hello", translation = "hi")
        assertSame(english, LyricPipeline.toTraditional(english, converter))
    }

    @Test
    fun romanizesOnlyKanaHangulCyrillicWithoutHan() {
        val converter = FakeConverter()
        assertEquals("roma(사랑해)", LyricPipeline.romanize(line(0, "사랑해"), converter).romanization)
        assertEquals("roma(ありがとう)", LyricPipeline.romanize(line(0, "ありがとう"), converter).romanization)
        assertEquals("roma(привет)", LyricPipeline.romanize(line(0, "привет"), converter).romanization)
        // 带汉字的日文会被读成拼音，不转；英文不用转；自带罗马音的不动
        assertEquals("", LyricPipeline.romanize(line(0, "君の声"), converter).romanization)
        assertEquals("", LyricPipeline.romanize(line(0, "hello"), converter).romanization)
        val own = LyricLine(0, "사랑해", romanization = "saranghae")
        assertSame(own, LyricPipeline.romanize(own, converter))
    }

    @Test
    fun prepareIsIdentityWithDefaultsAndRespectsAvailability() {
        val raw = "[00:01.00]风吹过 / Wind\n[00:02.00]我们 / We\n[00:03.00]听见 / Hear"
        val parsed = LyricsParser.parse(raw)
        assertEquals(parsed, LyricPipeline.prepare(raw, LyricTextPrefs(), FakeConverter()))

        val all = LyricTextPrefs(splitBilingual = true, traditional = true, autoRomanize = true)
        val out = LyricPipeline.prepare(raw, all, FakeConverter())
        assertEquals(listOf("風吹過", "我們", "聽見"), out.lines.map { it.text })
        assertEquals(listOf("Wind", "We", "Hear"), out.lines.map { it.translation })

        val old = LyricPipeline.prepare(raw, all, FakeConverter(traditionalAvailable = false, romanizeAvailable = false))
        assertEquals(listOf("风吹过", "我们", "听见"), old.lines.map { it.text })

        // 没有时间轴的歌词不动
        val unsynced = LyricPipeline.prepare("风吹过 / Wind\n我们 / We", all, FakeConverter())
        assertEquals(LyricsParser.parse("风吹过 / Wind\n我们 / We"), unsynced)
    }

    @Test
    fun parserStoresMiddleOfThreeLayersAsRomanization() {
        val parsed = LyricsParser.parse(
            """
            [00:01.00]사랑해
            [00:01.00]saranghae
            [00:01.00]我爱你
            [00:03.00]원문
            [00:03.00]翻译
            """.trimIndent(),
        )
        assertEquals(2, parsed.lines.size)
        assertEquals("사랑해", parsed.lines[0].text)
        assertEquals("saranghae", parsed.lines[0].romanization)
        assertEquals("我爱你", parsed.lines[0].translation)
        assertEquals("", parsed.lines[1].romanization)
        assertEquals("翻译", parsed.lines[1].translation)
    }

    @Test
    fun sublineForClassicList() {
        val parsed = LyricsParser.parse("[00:01.00]사랑해\n[00:01.00]saranghae\n[00:01.00]我爱你\n[00:03.00]원문\n[00:03.00]翻译")
        assertSame(parsed, parsed.withSubline(LyricSubline.Translation))
        val roman = parsed.withSubline(LyricSubline.Romanization)
        assertEquals("saranghae", roman.lines[0].translation)
        // 这句没有罗马音，回退成翻译
        assertEquals("翻译", roman.lines[1].translation)
        val both = parsed.withSubline(LyricSubline.Both)
        assertEquals("saranghae\n我爱你", both.lines[0].translation)
        assertEquals("翻译", both.lines[1].translation)

        val noRoman = LyricsParser.parse("[00:01.00]原文\n[00:01.00]翻译")
        assertSame(noRoman, noRoman.withSubline(LyricSubline.Both))
    }
}
