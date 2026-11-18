package com.mytv.mymusic.ui.player.share

import androidx.compose.ui.unit.dp
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.aggregate.VersionKind
import com.mytv.mymusic.data.ShareCardPalette
import com.mytv.mymusic.data.ShareCardStyle
import com.mytv.mymusic.data.SharePrefs
import com.mytv.mymusic.data.ShareTextFormat
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.player.stage.modes.CineramaSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.random.Random

class ShareModelTest {

    private val lyric = listOf(
        LyricLine(0, "The kettle hums a minor key", translation = "水壶哼着小调"),
        LyricLine(1000, "  "),
        LyricLine(2000, "I keep the porch light on", translation = "我把门廊的灯一直开着"),
        LyricLine(3000, "for a signal in the rain", translation = "等雨里传来一个信号"),
        LyricLine(4000, ""),
        LyricLine(5000, "every echo sounds like you", translation = "e-bu-ri\n每一声回响都像你"),
    )
    private val lines = pickLines(lyric)

    // ---------- 选句 ----------

    @Test
    fun pickLinesDropsBlankLinesAndKeepsSources() {
        assertEquals(listOf(0, 1, 2, 3), lines.map { it.order })
        assertEquals(listOf(0, 2, 3, 5), lines.map { it.source })
        assertEquals("水壶哼着小调", lines[0].sub)
        assertEquals("e-bu-ri\n每一声回响都像你", lines[3].sub)
        val plain = pickLines(listOf(LyricLine(-1, "one"), LyricLine(-1, "two")))
        assertEquals(listOf(-1L, -1L), plain.map { it.timeMs })
        assertEquals(listOf("one", "two"), plain.map { it.text })
    }

    @Test
    fun initialPickPrefersHitThenActive() {
        assertEquals(2, initialPick(lines, hit = 3, active = 0))
        // 命中间奏空行：往后找第一句非空的
        assertEquals(1, initialPick(lines, hit = 1, active = 0))
        assertEquals(3, initialPick(lines, hit = 4, active = 0))
        // 认不出是哪句：用当前句
        assertEquals(1, initialPick(lines, hit = null, active = 2))
        // 当前句越界：最后一句；未同步（−1）：第一句
        assertEquals(3, initialPick(lines, hit = null, active = 99))
        assertEquals(0, initialPick(lines, hit = null, active = -1))
    }

    @Test
    fun toggleLineCapsAtEight() {
        var selected = emptySet<Int>()
        repeat(8) { i ->
            val (next, result) = toggleLine(selected, i)
            assertEquals(ToggleResult.Added, result)
            selected = next
        }
        val (full, result) = toggleLine(selected, 8)
        assertEquals(ToggleResult.Full, result)
        assertEquals(selected, full)
        val (fewer, removed) = toggleLine(selected, 3)
        assertEquals(ToggleResult.Removed, removed)
        assertEquals(7, fewer.size)
        var empty = setOf(1)
        empty = toggleLine(empty, 1).first
        assertTrue(empty.isEmpty())
    }

    // ---------- 上卡的行 ----------

    private fun picks(n: Int) = List(n) { PickLine(it, it, it * 1000L, "line $it", "sub $it") }

    @Test
    fun shareRowsSortAndInsertGaps() {
        val all = picks(10)
        assertEquals(
            listOf(ShareRow.Line("line 1", "sub 1"), ShareRow.Line("line 2", "sub 2")),
            shareRows(all, setOf(2, 1)),
        )
        assertEquals(listOf(ShareRow.Line("line 1", "sub 1"), ShareRow.Gap, ShareRow.Line("line 3", "sub 3")), shareRows(all, setOf(3, 1)))
        val rows = shareRows(all, setOf(7, 1, 4, 3))
        assertEquals(2, rows.count { it == ShareRow.Gap })
        assertEquals(listOf("line 1", "gap", "line 3", "line 4", "gap", "line 7"), rows.map { (it as? ShareRow.Line)?.text ?: "gap" })
        assertFalse(rows.first() == ShareRow.Gap || rows.last() == ShareRow.Gap)
    }

    @Test
    fun rowsForStyleTrimsPerStyle() {
        val rows = shareRows(picks(10), setOf(1, 3, 4))
        assertEquals(listOf("line 1", "line 3"), rowsForStyle(ShareCardStyle.Couplet, rows).map { (it as ShareRow.Line).text })
        listOf(ShareCardStyle.Quote, ShareCardStyle.Messy, ShareCardStyle.Brat).forEach {
            assertEquals(listOf(ShareRow.Line("line 1", "sub 1")), rowsForStyle(it, rows))
        }
        assertTrue(rowsForStyle(ShareCardStyle.AlbumArt, rows).isEmpty())
        listOf(ShareCardStyle.Spotify, ShareCardStyle.Rushed, ShareCardStyle.Vertical, ShareCardStyle.Hypnotic, ShareCardStyle.Player).forEach {
            assertEquals(rows, rowsForStyle(it, rows))
        }
    }

    @Test
    fun unusedLinesNoteOnlyWhenLinesAreLeftOver() {
        assertEquals("「封面」样式不放歌词", unusedLinesNote(ShareCardStyle.AlbumArt, 1))
        assertNull(unusedLinesNote(ShareCardStyle.Couplet, 2))
        assertEquals("「对句」用前两句，其余选中的句子不上卡。", unusedLinesNote(ShareCardStyle.Couplet, 3))
        assertNull(unusedLinesNote(ShareCardStyle.Quote, 1))
        assertEquals("「引言」只用第一句，其余选中的句子不上卡。", unusedLinesNote(ShareCardStyle.Quote, 2))
        assertEquals("「乱排」只用第一句，其余选中的句子不上卡。", unusedLinesNote(ShareCardStyle.Messy, 5))
        assertEquals("「粗糙」只用第一句，其余选中的句子不上卡。", unusedLinesNote(ShareCardStyle.Brat, 2))
        (1..8).forEach { assertNull(unusedLinesNote(ShareCardStyle.Spotify, it)) }
    }

    // ---------- 文字、文件名、链接 ----------

    @Test
    fun shareTextFormats() {
        val rows = listOf(ShareRow.Line("a", "甲"), ShareRow.Gap, ShareRow.Line("b", "rom\n乙"))
        assertEquals("a\n…\nb", shareText(rows, false, ShareTextFormat.Lyrics, "T", "A", "http://x"))
        assertEquals("a\n甲\n…\nb\nrom\n乙", shareText(rows, true, ShareTextFormat.Lyrics, "T", "A", "http://x"))
        assertEquals("a\n…\nb\n\nT · A", shareText(rows, false, ShareTextFormat.WithSong, "T", "A", "http://x"))
        assertEquals("a\n…\nb\n\nT · A\nhttp://x", shareText(rows, false, ShareTextFormat.WithLink, "T", "A", "http://x"))
        assertEquals("a\n…\nb\n\nT · A", shareText(rows, false, ShareTextFormat.WithLink, "T", "A", ""))
        assertFalse(shareText(rows, true, ShareTextFormat.WithLink, "T", "A", "").endsWith("\n"))
    }

    @Test
    fun shareFileNameIsSafe() {
        val utc = TimeZone.getTimeZone("UTC")
        val at = 1_763_472_896_000L // 2025-11-18 13:34:56 UTC
        assertEquals("Porch Light - 示例乐队 20251118-133456.png", shareFileName("Porch Light", "示例乐队", at, utc))
        val name = shareFileName("a/b\\c:d*e?f\"g<h>i|j\u0001k", "x", at, utc)
        assertFalse(name.any { it in "/\\:*?\"<>|" || it.isISOControl() })
        assertTrue(name.startsWith("abcdefghijk - x "))
        val long = shareFileName("长".repeat(200), "歌手", at, utc)
        assertEquals(80, long.removeSuffix(" 20251118-133456.png").length)
        assertTrue(long.endsWith(".png"))
    }

    @Test
    fun shareLinkPicksHttpLinks() {
        val song = Song(id = "1", source = "netease", link = "https://music.163.com/#/song?id=1")
        assertEquals(song.link, shareLink(song, null))
        assertEquals("", shareLink(song.copy(source = "local", link = "file:///x"), null))
        val bare = song.copy(link = "")
        val track = CanonicalTrack(
            id = "t", title = "", artists = emptyList(), album = "", durationSec = 0, version = VersionKind.Studio, cover = "",
            sources = listOf(
                TrackSource("kuwo", "酷我", Song(id = "2", source = "kuwo", link = "")),
                TrackSource("qq", "QQ", Song(id = "3", source = "qq", link = "https://y.qq.com/n/ryqq/songDetail/3")),
            ),
        )
        assertEquals("https://y.qq.com/n/ryqq/songDetail/3", shareLink(bare, track))
        assertEquals("", shareLink(bare, track.copy(sources = track.sources.take(1))))
        assertEquals("", shareLink(bare, null))
    }

    // ---------- 随机一套、粗糙卡 ----------

    @Test
    fun randomLookIsDeterministicAndNeverCustom() {
        val base = SharePrefs(fullScreen = true, translation = true, textFormat = ShareTextFormat.Lyrics, customBackground = 1, customContent = 2)
        assertEquals(randomLook(base, Random(7)), randomLook(base, Random(7)))
        val random = Random(42)
        repeat(1000) {
            val look = randomLook(base, random)
            assertNotEquals(ShareCardPalette.Custom, look.palette)
            assertEquals(true, look.fullScreen)
            assertEquals(true, look.translation)
            assertEquals(ShareTextFormat.Lyrics, look.textFormat)
            assertEquals(1, look.customBackground)
            assertEquals(2, look.customContent)
        }
    }

    @Test
    fun bratSwitchesToCustomAndBack() {
        val vibrant = SharePrefs(style = ShareCardStyle.Spotify, palette = ShareCardPalette.Vibrant)
        val (brat, memo) = applyStyle(vibrant, ShareCardStyle.Brat, null)
        assertEquals(ShareCardStyle.Brat, brat.style)
        assertEquals(ShareCardPalette.Custom, brat.palette)
        assertEquals(ShareCardPalette.Vibrant, memo)
        val (back, memo2) = applyStyle(brat, ShareCardStyle.Spotify, memo)
        assertEquals(ShareCardPalette.Vibrant, back.palette)
        assertNull(memo2)
        // 已经是自定义：不动配色，也不记
        val (custom, memo3) = applyStyle(vibrant.copy(palette = ShareCardPalette.Custom), ShareCardStyle.Brat, null)
        assertEquals(ShareCardPalette.Custom, custom.palette)
        assertNull(memo3)
        // 在粗糙上手动换过配色（记忆已清）再离开：不改
        val manual = brat.copy(palette = ShareCardPalette.Muted)
        val (left, memo4) = applyStyle(manual, ShareCardStyle.Quote, null)
        assertEquals(ShareCardPalette.Muted, left.palette)
        assertNull(memo4)
    }

    // ---------- 乱排 ----------

    @Test
    fun messyWordsAreDeterministicAndInRange() {
        val text = "I keep the porch light on, for a signal in the rain"
        assertEquals(messyWords(text, 3), messyWords(text, 3))
        assertNotEquals(messyWords(text, 3), messyWords(text, 4))
        repeat(50) { seed ->
            messyWords(text, seed.toLong()).forEach { w ->
                assertTrue(w.weight in 300..900 && w.weight % 100 == 0)
                assertTrue(w.size in 40..70 && w.size % 10 == 0)
                assertTrue(w.width in 50..110 && w.width % 10 == 0)
                assertTrue(w.angle in -10..9)
            }
        }
        val long = messyWords("extraordinarily short", 1)
        assertEquals(100, long.first().width)
        assertTrue(messyWords("   ", 1).isEmpty())
    }

    @Test
    fun messyWordsKeepChineseWordsAndAttachPunctuation() {
        val fake = CineramaSegmenter { text ->
            if (text.startsWith("我把")) listOf("我", "把", "门廊", "的", "灯") else listOf("on", ",", " ", "rain")
        }
        assertEquals(listOf("我", "把", "门廊", "的", "灯"), messyWords("我把门廊的灯", 1, fake).map { it.text })
        assertEquals(2, messyWords("on, rain", 1, fake).size)
        assertTrue(messyWords("on, rain", 1, fake).first().text.lowercase().startsWith("on,"))
    }

    // ---------- 布局 ----------

    @Test
    fun layoutAndSidePadding() {
        assertEquals(ShareLayout.Phone, shareLayoutOf(599, false))
        assertEquals(ShareLayout.TwoPane, shareLayoutOf(600, false))
        assertEquals(ShareLayout.Tabletop, shareLayoutOf(400, true))
        assertEquals(ShareLayout.Tabletop, shareLayoutOf(1200, true))
        assertEquals(14.dp, pickSidePadding(599))
        assertEquals(96.dp, pickSidePadding(800))
        assertEquals(280.dp, pickSidePadding(1280))
    }
}
