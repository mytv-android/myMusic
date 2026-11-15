package com.mytv.mymusic.lyricon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyriconSubscriptionStoreTest {
    @Test
    fun `provider changes and disconnects clear stale playback`() {
        val store = LyriconSubscriptionStore(supported = true)
        store.setEnabled(true)
        store.onConnected()
        store.onProvider("provider", "player")
        store.onSong(song("one"))
        store.onPlaying(true)
        store.onPosition(2_000, seek = true)
        assertEquals("one", store.state.value.song?.id)
        assertEquals(2_000, store.state.value.positionMs)

        store.onProvider("other", "other-player")
        assertEquals("other", store.state.value.providerPackageName)
        assertNull(store.state.value.song)
        assertFalse(store.state.value.playing)
        assertEquals(0, store.state.value.positionMs)

        store.onSong(song("two"))
        store.onText("plain")
        store.onDisconnected(timeout = true)
        assertTrue(store.state.value.enabled)
        assertTrue(store.state.value.timedOut)
        assertFalse(store.state.value.connected)
        assertNull(store.state.value.song)
        assertNull(store.state.value.plainText)
    }

    @Test
    fun `position is throttled but seeks and backwards jumps are immediate`() {
        val store = LyriconSubscriptionStore(supported = true)
        store.setEnabled(true)
        store.onPosition(1_000, seek = true)
        store.onPosition(1_100)
        assertEquals(1_000, store.state.value.positionMs)

        store.onPosition(1_200)
        assertEquals(1_200, store.state.value.positionMs)
        store.onPosition(1_250, seek = true)
        assertEquals(1_250, store.state.value.positionMs)
        store.onPosition(100)
        assertEquals(100, store.state.value.positionMs)
    }

    @Test
    fun `structured songs replace plain text without depending on callback order`() {
        val store = LyriconSubscriptionStore(supported = true)
        store.setEnabled(true)
        store.onSong(song("one"))
        store.onText("hello\nworld")
        assertEquals("hello\nworld", store.state.value.plainText)

        // sendText 的另一路实现可能先清 Song，再发文本；null 不应把文本抹掉。
        store.onSong(null)
        assertEquals("hello\nworld", store.state.value.plainText)
        store.onSong(song("two"))
        assertNull(store.state.value.plainText)
        assertEquals("two", store.state.value.song?.id)
    }

    @Test
    fun `current line follows received position and display flags`() {
        val store = LyriconSubscriptionStore(supported = true)
        store.setEnabled(true)
        store.onSong(song("one"))
        assertEquals("first", store.state.value.currentLine?.text)
        store.onPosition(2_000, seek = true)
        assertEquals("second", store.state.value.currentLine?.text)
        assertEquals("二", store.state.value.currentLine?.translation)

        store.onDisplayTranslation(false)
        store.onDisplayRoma(true)
        assertFalse(store.state.value.displayTranslation)
        assertTrue(store.state.value.displayRoma)

        store.setEnabled(false)
        assertFalse(store.state.value.enabled)
        assertNull(store.state.value.song)
        store.onSong(song("ignored"))
        store.onPosition(9_000, seek = true)
        assertNull(store.state.value.song)
        assertEquals(0, store.state.value.positionMs)
    }

    @Test
    fun `unsupported systems never register a receiver state`() {
        val store = LyriconSubscriptionStore(supported = false)
        store.setEnabled(true)
        assertFalse(store.state.value.supported)
        assertFalse(store.state.value.enabled)
        assertFalse(store.state.value.connecting)
    }

    private fun song(id: String) = LyriconReceivedSong(
        id = id,
        name = "Song $id",
        artist = "Artist",
        lyrics = listOf(
            LyriconReceivedLine(1_000, 2_000, "first", "一", "ichi"),
            LyriconReceivedLine(2_000, 3_000, "second", "二", "ni"),
        ),
    )
}
