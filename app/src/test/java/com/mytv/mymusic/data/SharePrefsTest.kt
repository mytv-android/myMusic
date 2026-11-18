package com.mytv.mymusic.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

class SharePrefsTest {

    @Test
    fun `nothing stored reads the draft defaults`() {
        val prefs = SharePrefsCodec.read(mutablePreferencesOf())
        assertEquals(SharePrefs(), prefs)
        assertEquals(ShareCardStyle.Rushed, prefs.style)
        assertEquals(ShareCardPalette.Vibrant, prefs.palette)
        assertEquals(ShareCardCorners.Rounded, prefs.corners)
        assertEquals(ShareCardShape.Cookie12, prefs.shape)
        assertEquals(false, prefs.fullScreen)
        assertEquals(false, prefs.translation)
        assertEquals(ShareTextFormat.WithSong, prefs.textFormat)
        assertEquals(0xFFD8F05A.toInt(), prefs.customBackground)
        assertEquals(0xFF14140F.toInt(), prefs.customContent)
    }

    @Test
    fun `written prefs read back the same`() {
        val prefs = SharePrefs(
            style = ShareCardStyle.Messy,
            palette = ShareCardPalette.Custom,
            corners = ShareCardCorners.Square,
            shape = ShareCardShape.Rectangle,
            fullScreen = true,
            translation = true,
            textFormat = ShareTextFormat.WithLink,
            customBackground = 0xFF102030.toInt(),
            customContent = 0xFFFFFFFF.toInt(),
        )
        val values = mutablePreferencesOf()
        SharePrefsCodec.write(values, prefs)
        assertEquals(prefs, SharePrefsCodec.read(values))
    }

    @Test
    fun `unknown enum names fall back without touching other fields`() {
        val values = mutablePreferencesOf()
        SharePrefsCodec.write(values, SharePrefs(style = ShareCardStyle.Brat, fullScreen = true, corners = ShareCardCorners.Square))
        values[SharePrefsCodec.style] = "Bogus"
        values[SharePrefsCodec.palette] = "Bogus"
        values[SharePrefsCodec.shape] = "Bogus"
        values[SharePrefsCodec.textFormat] = "Bogus"
        val prefs = SharePrefsCodec.read(values)
        assertEquals(ShareCardStyle.Rushed, prefs.style)
        assertEquals(ShareCardPalette.Vibrant, prefs.palette)
        assertEquals(ShareCardShape.Cookie12, prefs.shape)
        assertEquals(ShareTextFormat.WithSong, prefs.textFormat)
        assertEquals(ShareCardCorners.Square, prefs.corners)
        assertEquals(true, prefs.fullScreen)
    }

    @Test
    fun `key names stay fixed`() {
        assertEquals(
            listOf(
                "share_style", "share_palette", "share_corners", "share_shape", "share_full",
                "share_translation", "share_text_format", "share_custom_bg", "share_custom_fg",
            ),
            listOf(
                SharePrefsCodec.style, SharePrefsCodec.palette, SharePrefsCodec.corners, SharePrefsCodec.shape,
                SharePrefsCodec.fullScreen, SharePrefsCodec.translation, SharePrefsCodec.textFormat,
                SharePrefsCodec.customBackground, SharePrefsCodec.customContent,
            ).map { it.name },
        )
    }

    @Test
    fun `style line counts follow the draft`() {
        assertEquals(2, ShareCardStyle.Couplet.lines)
        assertEquals(0, ShareCardStyle.AlbumArt.lines)
        listOf(ShareCardStyle.Quote, ShareCardStyle.Messy, ShareCardStyle.Brat).forEach { assertEquals(1, it.lines) }
        listOf(ShareCardStyle.Spotify, ShareCardStyle.Rushed, ShareCardStyle.Vertical, ShareCardStyle.Hypnotic, ShareCardStyle.Player)
            .forEach { assertEquals(null, it.lines) }
        assertEquals(10, ShareCardStyle.entries.size)
        assertEquals(21, ShareCardShape.entries.size)
    }
}
