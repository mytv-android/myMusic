package com.mytv.mymusic.overlay

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.widget.PlaybackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingLyricsRulesTest {
    private fun snapshot(
        lyrics: String = "",
        lyricsReady: Boolean = true,
        positionMs: Long = 1_500,
        playing: Boolean = false,
    ) = PlaybackSnapshot(
        song = Song(id = "1", name = "Song", artist = "Artist", source = "netease"),
        index = 0,
        queueSize = 1,
        playing = playing,
        live = true,
        positionMs = positionMs,
        anchorAt = 100,
        durationMs = 10_000,
        lyrics = lyrics,
        lyricsReady = lyricsReady,
    )

    @Test
    fun currentLinePrefersItsTranslation() {
        val content = floatingLyricsContent(
            snapshot("[00:01.00]Hello\n[00:01.00]你好\n[00:03.00]Next"),
            now = 100,
        )
        assertEquals("Hello", content.primary)
        assertEquals("你好", content.secondary)
        assertTrue(content.canControl)
        assertFalse(content.playing)
    }

    @Test
    fun withoutTranslationTheNextLineIsTheSubtitle() {
        val content = floatingLyricsContent(
            snapshot("[00:01.00]Hello\n[00:03.00]Next"),
            now = 100,
        )
        assertEquals("Hello", content.primary)
        assertEquals("Next", content.secondary)
    }

    @Test
    fun noPlaybackAndNoLyricsHaveDistinctPlaceholders() {
        val empty = floatingLyricsContent(null, now = 100)
        assertEquals("myMusic 悬浮歌词", empty.primary)
        assertFalse(empty.canControl)

        val noLyrics = floatingLyricsContent(snapshot(), now = 100)
        assertEquals("Song", noLyrics.primary)
        assertEquals("暂时没有歌词", noLyrics.secondary)

        val loading = floatingLyricsContent(snapshot(lyricsReady = false, playing = true), now = 100)
        assertEquals("正在取歌词…", loading.secondary)
        assertTrue(loading.playing)
    }

    @Test
    fun televisionCannotStartDesktopLyricsEvenFromAnOldEnabledConfiguration() {
        assertFalse(floatingLyricsServiceAllowed(television = true, enabled = true, hasPermission = true))
        assertFalse(floatingLyricsServiceAllowed(television = true, enabled = false, hasPermission = true))
    }

    @Test
    fun phonesNeedBothTheSwitchAndOverlayPermission() {
        assertTrue(floatingLyricsServiceAllowed(television = false, enabled = true, hasPermission = true))
        assertFalse(floatingLyricsServiceAllowed(television = false, enabled = true, hasPermission = false))
        assertFalse(floatingLyricsServiceAllowed(television = false, enabled = false, hasPermission = true))
    }

    @Test
    fun draggingIsClampedToEveryVisibleEdge() {
        assertEquals(
            FloatingLyricsPosition(0, 0),
            clampFloatingLyricsPosition(-20, -30, 200, 60, 1080, 1920),
        )
        assertEquals(
            FloatingLyricsPosition(880, 1860),
            clampFloatingLyricsPosition(2000, 3000, 200, 60, 1080, 1920),
        )
        assertEquals(
            FloatingLyricsPosition(100, 200),
            clampFloatingLyricsPosition(100, 200, 200, 60, 1080, 1920),
        )
    }

    @Test
    fun anOverlayLargerThanTheScreenStillGetsAUsableOrigin() {
        assertEquals(
            FloatingLyricsPosition(0, 0),
            clampFloatingLyricsPosition(500, 500, 1200, 2000, 1080, 1920),
        )
    }
}
