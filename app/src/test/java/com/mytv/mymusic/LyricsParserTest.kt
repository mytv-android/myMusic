package com.mytv.mymusic

import com.mytv.mymusic.ui.LyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {
    @Test
    fun parsesStandardLrcWithOffset() {
        val parsed = LyricsParser.parse(
            """
            [ti:晴天]
            [ar:周杰伦]
            [offset:500]
            [00:01.00]第一行
            [00:03.50]第二行
            """.trimIndent()
        )
        assertTrue(parsed.synced)
        assertEquals(2, parsed.lines.size)
        assertEquals(1_500L, parsed.lines[0].timeMs)
        assertEquals(4_000L, parsed.lines[1].timeMs)
        assertEquals(0, LyricsParser.activeLineIndex(parsed.lines, 1_600L))
        assertEquals(1, LyricsParser.activeLineIndex(parsed.lines, 4_100L))
    }

    @Test
    fun expandsRepeatedTimestamps() {
        val parsed = LyricsParser.parse("[00:01.00][00:05.00]副歌")
        assertEquals(2, parsed.lines.size)
        assertEquals("副歌", parsed.lines[0].text)
        assertEquals("副歌", parsed.lines[1].text)
    }

    @Test
    fun parsesEnhancedWordTimings() {
        val parsed = LyricsParser.parse("[00:01.00]<00:01.00>你<00:01.50>好")
        assertEquals(1, parsed.lines.size)
        assertEquals(2, parsed.lines.single().words.size)
        assertEquals("你好", parsed.lines.single().text)
        assertEquals(1_000L, parsed.lines.single().words[0].startMs)
        assertEquals(1_500L, parsed.lines.single().words[1].startMs)
    }

    @Test
    fun plainLyricsFallBackToRawText() {
        val parsed = LyricsParser.parse("没有时间戳的歌词\n第二行")
        assertFalse(parsed.synced)
        assertEquals(0, parsed.lines.size)
    }
}
