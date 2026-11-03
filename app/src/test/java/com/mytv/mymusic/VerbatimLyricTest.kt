package com.mytv.mymusic

import com.mytv.mymusic.data.native.KuwoProvider
import com.mytv.mymusic.data.native.SodaProvider
import com.mytv.mymusic.data.native.convertTtmlToLrc
import com.mytv.mymusic.data.native.convertYrcToLrc
import com.mytv.mymusic.data.native.mergeLyricLayers
import com.mytv.mymusic.ui.LyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 逐字歌词来源转换：网易 YRC、酷我/汽水逐字标签、Apple TTML，输出统一为增强 LRC。 */
class VerbatimLyricTest {
    private val kuwo = KuwoProvider()
    private val soda = SodaProvider()

    @Test
    fun neteaseYrcConvertsToVerbatimLrc() {
        val yrc = "[1000,2000](1000,300,0)难(1300,300,0)道(1600,600,0)是"
        assertEquals(
            "[00:01.000]<00:01.000>难</00:01.300><00:01.300>道</00:01.600><00:01.600>是</00:02.200>",
            convertYrcToLrc(yrc),
        )
    }

    @Test
    fun neteaseYrcKeepsWordDurations() {
        val words = LyricsParser.parse(convertYrcToLrc("[1000,2000](1000,600,0)长(1600,300,0)短")).lines.single().words
        assertEquals(1_600L, words[0].endMs)
        assertEquals(1_900L, words[1].endMs)
    }

    @Test
    fun neteaseTranslationMergesByTimestamp() {
        val merged = mergeLyricLayers(
            original = "[00:01.000]<00:01.000>Hello",
            translation = "[00:01.00]你好\n[00:03.00]世界",
            romaji = "[00:01.00]haro",
        )
        assertEquals("[00:01.000]<00:01.000>Hello\n[00:01.000]haro\n[00:01.000]你好", merged)
    }

    @Test
    fun neteaseYrcLineWithoutWordsKeepsText() {
        assertEquals("[00:01.000]前奏", convertYrcToLrc("[1000,2000]前奏"))
    }

    @Test
    fun neteaseYrcFallsBackWhenUnparseable() {
        assertEquals("", convertYrcToLrc("not a yrc"))
        assertEquals("", convertYrcToLrc(" "))
    }

    @Test
    fun kuwoKeepsAbsoluteWordTimings() {
        val raw = "[00:26.500]<27600,300>难<27900,300>道"
        assertEquals(
            "[00:26.500]<00:27.600>难</00:27.900><00:27.900>道</00:28.200>",
            kuwo.convertKuwoNewLyric(raw),
        )
    }

    @Test
    fun kuwoRelativeWordTimingsShiftToLineStart() {
        val raw = "[00:26.500]<100,300>难<400,300>道"
        assertEquals(
            "[00:26.500]<00:26.600>难</00:26.900><00:26.900>道</00:27.200>",
            kuwo.convertKuwoNewLyric(raw),
        )
    }

    @Test
    fun kuwoTranslationLinesStayWholeLine() {
        val raw = "[00:26.500]<27600,300>难<27900,300>道\n[00:26.500]<0,0>难道是错"
        assertEquals(
            "[00:26.500]<00:27.600>难</00:27.900><00:27.900>道</00:28.200>\n[00:26.500]难道是错",
            kuwo.convertKuwoNewLyric(raw),
        )
    }

    @Test
    fun kuwoSongInfoPutsOriginalBeforeTranslation() {
        val lrc = kuwo.convertKuwoSongInfoLrc(
            listOf(
                13.93 to "要怎么做？就这么做",
                13.93 to "またほら君と話そうか",
                19.36 to "现在还能和你说话吧",
                19.36 to "あれだってこれだって",
            ),
        )
        assertEquals(
            "[00:13.93]またほら君と話そうか\n[00:13.93]要怎么做？就这么做\n" +
                "[00:19.36]あれだってこれだって\n[00:19.36]现在还能和你说话吧\n",
            lrc,
        )
    }

    @Test
    fun sodaRelativeWordTimingsShiftToLineStart() {
        assertEquals(
            "[00:05.00]<00:05.100>难</00:05.400><00:05.400>道</00:05.700>\n",
            soda.parseSodaLyric("[5000,2000]<100,300>难<400,300>道"),
        )
    }

    @Test
    fun sodaAbsoluteWordTimingsStayAbsolute() {
        assertEquals("[00:05.00]<00:05.100>难</00:05.400>\n", soda.parseSodaLyric("[5000,2000]<5100,300>难"))
    }

    @Test
    fun sodaPlainLinesUnchanged() {
        assertEquals("[00:05.00]难道\n", soda.parseSodaLyric("[5000,2000]难道"))
    }

    @Test
    fun appleTtmlConvertsSpansToWords() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:00:06.29" end="00:00:10.25"><span begin="00:00:06.29" end="00:00:06.83">难</span><span begin="00:00:06.83" end="00:00:07.20">道</span></p>
            <p begin="00:00:12.00" end="00:00:14.00">普通行</p>
            </div></body></tt>
        """.trimIndent()
        assertEquals(
            "[00:06.290]<00:06.290>难</00:06.830><00:06.830>道</00:07.200>\n[00:12.000]普通行",
            convertTtmlToLrc(ttml),
        )
    }

    @Test
    fun appleTtmlDecodesEntitiesOnce() {
        val ttml = "<tt><body><div><p begin=\"00:00:01.00\"><span begin=\"00:00:01.00\">A &amp; B &amp;lt; C</span></p></div></body></tt>"
        assertEquals("[00:01.000]<00:01.000>A & B &lt; C", convertTtmlToLrc(ttml))
    }

    @Test
    fun appleTtmlReturnsRawWhenNoLines() {
        assertEquals("plain text", convertTtmlToLrc("plain text"))
        assertEquals("", convertTtmlToLrc(""))
    }

    @Test
    fun verbatimLrcParsesIntoWordTimings() {
        val parsed = LyricsParser.parse(convertYrcToLrc("[1000,2000](1000,300,0)难(1300,300,0)道"))
        assertTrue(parsed.synced)
        val words = parsed.lines.single().words
        assertEquals(2, words.size)
        assertEquals(1_000L, words[0].startMs)
        assertEquals(1_300L, words[0].endMs)
        assertEquals(1_300L, words[1].startMs)
        assertEquals(1_600L, words[1].endMs)
        assertEquals("难道", parsed.lines.single().text)
    }

    @Test
    fun explicitWordEndHoldsUntilNextWord() {
        val parsed = LyricsParser.parse("[00:01.000]<00:01.000>长</00:03.000><00:03.000>短</00:03.500>")
        val words = parsed.lines.single().words
        assertEquals(3_000L, words[0].endMs)
        assertEquals(3_500L, words[1].endMs)
    }

    @Test
    fun legacyWordTagsStillEndAtNextWord() {
        val parsed = LyricsParser.parse("[00:01.000]<00:01.000>难<00:01.300>道")
        val words = parsed.lines.single().words
        assertEquals(1_300L, words[0].endMs)
        assertEquals(1_300L, words[1].startMs)
    }

    @Test
    fun lastWordWithoutEndStopsAtNextLine() {
        val parsed = LyricsParser.parse("[00:01.000]<00:01.000>最后\n[00:02.500]下一句")
        assertEquals(2_500L, parsed.lines[0].words.single().endMs)
    }

    @Test
    fun sameTimestampLayersMergeIntoTranslation() {
        val parsed = LyricsParser.parse("[00:01.000]<00:01.000>Hello\n[00:01.000]你好\n[00:03.000]World")
        assertEquals(2, parsed.lines.size)
        assertEquals("Hello", parsed.lines[0].text)
        assertEquals("你好", parsed.lines[0].translation)
        assertEquals("", parsed.lines[1].translation)
    }
}
