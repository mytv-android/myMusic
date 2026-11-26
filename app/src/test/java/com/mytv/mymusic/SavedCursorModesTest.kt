package com.mytv.mymusic

import com.mytv.mymusic.data.RepeatMode
import com.mytv.mymusic.playback.SavedCursor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** cursor.json 新加了随机 / 循环：旧文件缺字段要读成 false / Off，新文件要能原样读回。 */
class SavedCursorModesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `a cursor without modes decodes as off`() {
        val cursor = json.decodeFromString<SavedCursor>("""{"key":"netease:1","index":2,"positionMs":1234,"durationMs":300000}""")
        assertEquals("netease:1", cursor.key)
        assertFalse(cursor.shuffle)
        assertEquals(RepeatMode.Off, cursor.repeatMode)
    }

    @Test
    fun `modes round trip`() {
        val cursor = SavedCursor(key = "a", shuffle = true, repeat = RepeatMode.All.name)
        val copy = json.decodeFromString<SavedCursor>(json.encodeToString(cursor))
        assertEquals(cursor, copy)
        assertEquals(RepeatMode.All, copy.repeatMode)
    }

    @Test
    fun `an unknown repeat name falls back to off`() {
        val cursor = json.decodeFromString<SavedCursor>("""{"key":"a","repeat":"Loop"}""")
        assertEquals(RepeatMode.Off, cursor.repeatMode)
    }
}
