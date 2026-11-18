package com.mytv.mymusic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SwipePrefsEditTest {
    private val defaults = SwipePrefs()

    @Test
    fun `move up and down swaps neighbours, out of range is a no-op`() {
        val down = defaults.moveButton(SwipeSide.Start, 0, 1)
        assertEquals(listOf(SwipeAction.AddToPlaylist, SwipeAction.Download), down.start)
        assertEquals(defaults.start, down.moveButton(SwipeSide.Start, 1, -1).start)
        assertSame(defaults, defaults.moveButton(SwipeSide.Start, 0, -1))
        assertSame(defaults, defaults.moveButton(SwipeSide.End, 1, 1))
        assertSame(defaults, defaults.moveButton(SwipeSide.End, 5, -1))
    }

    @Test
    fun `set replaces, swaps with an existing one, appends and ignores a full side`() {
        assertEquals(listOf(SwipeAction.Download, SwipeAction.PlayNext), defaults.setButton(SwipeSide.Start, 1, SwipeAction.PlayNext).start)
        // 第 1 个换成已经在第 2 个的「加到歌单」：两个对调
        assertEquals(listOf(SwipeAction.AddToPlaylist, SwipeAction.Download), defaults.setButton(SwipeSide.Start, 0, SwipeAction.AddToPlaylist).start)
        val one = defaults.copy(end = listOf(SwipeAction.Delete))
        assertEquals(listOf(SwipeAction.Delete, SwipeAction.OpenArtist), one.setButton(SwipeSide.End, 1, SwipeAction.OpenArtist).end)
        assertSame(one, one.setButton(SwipeSide.End, 1, SwipeAction.Delete))
        assertSame(defaults, defaults.setButton(SwipeSide.End, 2, SwipeAction.OpenAlbum))
        assertSame(defaults, defaults.setButton(SwipeSide.End, 0, SwipeAction.None))
    }

    @Test
    fun `a side can be emptied`() {
        val emptied = defaults.removeButton(SwipeSide.End, 0).removeButton(SwipeSide.End, 0)
        assertEquals(emptyList<SwipeAction>(), emptied.end)
        assertEquals(defaults.start, emptied.start)
        assertSame(emptied, emptied.removeButton(SwipeSide.End, 0))
    }

    @Test
    fun `fulls are independent and may hold the same action`() {
        val both = defaults.setFull(SwipeSide.End, SwipeAction.Favorite)
        assertEquals(SwipeAction.Favorite, both.startFull)
        assertEquals(SwipeAction.Favorite, both.endFull)
        assertEquals(SwipeAction.None, both.setFull(SwipeSide.Start, SwipeAction.None).startFull)
        assertEquals(SwipeAction.Favorite, both.full(SwipeSide.End))
    }

    @Test
    fun `the two sides may repeat each other`() {
        val prefs = defaults.setButton(SwipeSide.End, 0, SwipeAction.AddToPlaylist)
        assertEquals(listOf(SwipeAction.AddToPlaylist, SwipeAction.Delete), prefs.end)
        assertEquals(listOf(SwipeAction.Download, SwipeAction.AddToPlaylist), prefs.buttons(SwipeSide.Start))
    }
}
