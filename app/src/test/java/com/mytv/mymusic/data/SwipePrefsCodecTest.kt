package com.mytv.mymusic.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipePrefsCodecTest {
    private fun read(vararg pairs: Pair<String, String>): SwipePrefs {
        val values = mutablePreferencesOf()
        pairs.forEach { (key, value) ->
            when (key) {
                "start" -> values[SwipePrefsCodec.start] = value
                "startFull" -> values[SwipePrefsCodec.startFull] = value
                "end" -> values[SwipePrefsCodec.end] = value
                "endFull" -> values[SwipePrefsCodec.endFull] = value
            }
        }
        return SwipePrefsCodec.read(values)
    }

    @Test
    fun `nothing stored reads the defaults from the request`() {
        val prefs = SwipePrefsCodec.read(mutablePreferencesOf())
        assertEquals(SwipePrefs(), prefs)
        assertTrue(prefs.enabled)
        assertEquals(listOf(SwipeAction.Download, SwipeAction.AddToPlaylist), prefs.start)
        assertEquals(SwipeAction.Favorite, prefs.startFull)
        assertEquals(listOf(SwipeAction.Unfavorite, SwipeAction.Delete), prefs.end)
        assertEquals(SwipeAction.None, prefs.endFull)
    }

    @Test
    fun `written prefs read back the same`() {
        val samples = listOf(
            SwipePrefs(),
            SwipePrefs(enabled = false, start = listOf(SwipeAction.PlayNext), startFull = SwipeAction.Download, end = emptyList(), endFull = SwipeAction.Delete),
            SwipePrefs(start = emptyList(), startFull = SwipeAction.None, end = emptyList(), endFull = SwipeAction.None),
        )
        samples.forEach { prefs ->
            val values = mutablePreferencesOf()
            SwipePrefsCodec.write(values, prefs)
            assertEquals(prefs, SwipePrefsCodec.read(values))
        }
    }

    @Test
    fun `unknown ids are dropped and known ones kept`() {
        assertEquals(listOf(SwipeAction.Download), read("start" to "foo,download").start)
    }

    @Test
    fun `duplicates on one side collapse`() {
        assertEquals(listOf(SwipeAction.Download), read("start" to "download,download").start)
    }

    @Test
    fun `more than two buttons are cut to two`() {
        assertEquals(listOf(SwipeAction.Download, SwipeAction.PlayNext), read("end" to "download,play_next,queue").end)
    }

    @Test
    fun `do nothing cannot sit in a button slot`() {
        assertEquals(listOf(SwipeAction.Delete), read("end" to "none,delete").end)
    }

    @Test
    fun `an empty string is a cleared side, a missing key is the default`() {
        assertEquals(emptyList<SwipeAction>(), read("start" to "").start)
        assertEquals(SwipePrefs().end, read("start" to "").end)
    }

    @Test
    fun `full with an unknown id falls back to the default, none is kept`() {
        assertEquals(SwipeAction.Favorite, read("startFull" to "whatever").startFull)
        assertEquals(SwipeAction.None, read("startFull" to "none").startFull)
        assertEquals(SwipeAction.OpenAlbum, read("endFull" to "album").endFull)
    }

    @Test
    fun `writing sanitizes first`() {
        val values = mutablePreferencesOf()
        SwipePrefsCodec.write(values, SwipePrefs(start = listOf(SwipeAction.Download, SwipeAction.PlayNext, SwipeAction.AddToQueue)))
        assertEquals("download,play_next", values[SwipePrefsCodec.start])
    }

    @Test
    fun `ids with spaces around them are still recognized`() {
        assertEquals(listOf(SwipeAction.Download, SwipeAction.AddToPlaylist), read("start" to " download , playlist ").start)
        assertEquals(SwipeAction.PlayNext, SwipeAction.byId(" play_next "))
    }
}
