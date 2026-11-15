package com.mytv.mymusic.lyricon

import com.mytv.mymusic.data.LyricSubline
import com.mytv.mymusic.data.LyricTextPrefs
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.ParsedLyrics
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song as RemoteSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyriconMapperTest {
    @Test
    fun `timed words translations and durations survive conversion`() {
        val parsed = ParsedLyrics(
            lines = listOf(
                LyricLine(
                    timeMs = 1_000,
                    text = "hello",
                    words = listOf(
                        LyricWord(1_000, 1_200, "he"),
                        LyricWord(1_200, 1_800, "llo"),
                    ),
                    translation = "你好",
                    romanization = "hallo",
                ),
                LyricLine(timeMs = 3_000, text = "world", translation = "世界"),
            ),
            raw = "raw",
            synced = true,
        )

        val result = LyriconMapper.toRemoteSong(testSong(), 10_000, parsed)

        assertEquals("netease:42", result.id)
        assertEquals("Song", result.name)
        assertEquals("Artist", result.artist)
        assertEquals(10_000, result.duration)
        val lines = result.lyrics.orEmpty()
        assertEquals(2, lines.size)
        assertEquals(1_000, lines[0].begin)
        assertEquals(3_000, lines[0].end)
        assertEquals("你好", lines[0].translation)
        assertEquals("hallo", lines[0].roma)
        assertEquals(listOf(1_000L, 1_200L), lines[0].words.orEmpty().map { it.begin })
        assertEquals(listOf(1_200L, 1_800L), lines[0].words.orEmpty().map { it.end })
        assertEquals("hello", lines[0].words.orEmpty().joinToString("") { it.text.orEmpty() })
        assertEquals(10_000, lines[1].end)
    }

    @Test
    fun `loading and loaded empty lyrics remain distinct`() {
        val loading = LyriconMapper.toRemoteSong(testSong(duration = 0), 0, null)
        assertNull(loading.lyrics)
        assertEquals(0, loading.duration)

        val noLyrics = LyriconMapper.toRemoteSong(
            testSong(duration = 0),
            0,
            ParsedLyrics(emptyList(), "", false),
        )
        assertEquals(emptyList<RichLyricLine>(), noLyrics.lyrics)

        val oneLine = LyriconMapper.toRemoteSong(
            testSong(duration = 0),
            0,
            ParsedLyrics(
                listOf(LyricLine(500, "word", listOf(LyricWord(500, 750, "word")))),
                "raw",
                true,
            ),
        )
        assertEquals(750, oneLine.lyrics.orEmpty().single().end)
    }

    @Test
    fun `subline choice controls translation and roma display`() {
        val input = input("[00:01.00]原文\n[00:01.00]roman\n[00:01.00]翻译")

        val both = LyriconMapper.prepare(input.copy(subline = LyricSubline.Both))
        assertTrue(both.displayTranslation)
        assertTrue(both.displayRoma)

        val romaOnly = LyriconMapper.prepare(input.copy(subline = LyricSubline.Romanization))
        assertFalse(romaOnly.displayTranslation)
        assertTrue(romaOnly.displayRoma)

        val translationOnly = LyriconMapper.prepare(input.copy(subline = LyricSubline.Translation))
        assertTrue(translationOnly.displayTranslation)
        assertFalse(translationOnly.displayRoma)

        val noRoma = LyriconMapper.prepare(
            input.copy(lyricsRaw = "[00:01.00]原文\n[00:01.00]翻译", subline = LyricSubline.Romanization)
        )
        assertTrue(noRoma.displayTranslation)
        assertFalse(noRoma.displayRoma)
    }

    @Test
    fun `received songs are normalized for preview`() {
        val remote = RemoteSong(
            id = "id",
            name = "name",
            artist = "artist",
            duration = 8_000,
            lyrics = listOf(
                RichLyricLine(begin = 2_000, end = 2_000, text = "second", translation = "二"),
                RichLyricLine(begin = 1_000, end = 2_000, text = "first", roma = "ichi"),
            ),
        )

        val result = LyriconMapper.fromRemoteSong(remote)

        assertEquals(listOf("first", "second"), result.lyrics.map { it.text })
        assertEquals(2_001, result.lyrics[1].endMs)
        assertEquals("ichi", result.lyrics[0].romanization)
        assertEquals("二", result.lyrics[1].translation)
    }

    private fun input(raw: String) = LyriconTrackInput(
        song = testSong(),
        durationMs = 10_000,
        lyricsRaw = raw,
        lyricsReady = true,
        lyricText = LyricTextPrefs(),
        subline = LyricSubline.Translation,
        playing = true,
        providerEnabled = true,
        subscriberEnabled = false,
    )

    private fun testSong(duration: Int = 200) = Song(
        id = "42",
        name = "Song",
        artist = "Artist",
        duration = duration,
        source = "netease",
    )
}
