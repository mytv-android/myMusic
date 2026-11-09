package com.mytv.mymusic.data.local

import com.mytv.mymusic.ui.LyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class LocalLrcTest {
    private val lrc = "[ti:晴天]\n[ar:周杰伦]\n[00:12.30]故事的小黄花\n[00:15.80]从出生那年就飘着\n"

    @Test
    fun sidecarIsSameBaseNameCaseInsensitive() {
        assertEquals("晴天.lrc", LocalLrc.sidecarName("晴天.flac"))
        val siblings = listOf("晴天.flac", "晴天.LRC", "七里香.lrc", "cover.jpg")
        assertEquals("晴天.LRC", LocalLrc.findSidecar("晴天.flac", siblings))
        assertEquals("Song.lrc", LocalLrc.findSidecar("SONG.MP3", listOf("Song.lrc")))
        assertNull(LocalLrc.findSidecar("稻香.mp3", siblings))
        // 名字只是前缀相同不算
        assertNull(LocalLrc.findSidecar("晴天.mp3", listOf("晴天 (Live).lrc", "晴天.txt")))
    }

    @Test
    fun decodesUtf8WithAndWithoutBom() {
        val bytes = lrc.toByteArray(Charsets.UTF_8)
        assertEquals(lrc, LocalLrc.decode(bytes))
        assertEquals(lrc, LocalLrc.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes))
    }

    @Test
    fun decodesUtf16Boms() {
        assertEquals(lrc, LocalLrc.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + lrc.toByteArray(Charsets.UTF_16LE)))
        assertEquals(lrc, LocalLrc.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + lrc.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun fallsBackToGb18030ForLegacyFiles() {
        val gbk = lrc.toByteArray(Charset.forName("GBK"))
        assertEquals(lrc, LocalLrc.decode(gbk))
    }

    @Test
    fun onlyTimedTextCountsAsLrc() {
        assertTrue(LocalLrc.looksLikeLrc(lrc))
        assertTrue(LocalLrc.looksLikeLrc("[1:02]x"))
        assertFalse(LocalLrc.looksLikeLrc("故事的小黄花\n从出生那年就飘着"))
        assertFalse(LocalLrc.looksLikeLrc("[ti:晴天]\n[ar:周杰伦]"))
    }

    @Test
    fun sidecarTextGoesThroughTheExistingParser() {
        val parsed = LyricsParser.parse(LocalLrc.decode(lrc.toByteArray(Charset.forName("GBK"))))
        assertTrue(parsed.synced)
        assertEquals(listOf(12_300L, 15_800L), parsed.lines.map { it.timeMs })
        assertEquals("故事的小黄花", parsed.lines.first().text)
    }
}
