package com.mytv.mymusic.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricFontRulesTest {
    @Test
    fun acceptsSupportedFontContainersCaseInsensitively() {
        listOf("NotoSans.ttf", "SourceHan.OTF", "fallback.ttc", "collection.oTc").forEach { name ->
            assertTrue(name, LyricFontRules.supportedName(name))
        }
    }

    @Test
    fun rejectsUnknownExtensionsAndMissingNames() {
        listOf("", "font", "font.txt", "font.apk", ".ttf.hidden", "font.ttf.exe").forEach { name ->
            assertFalse(name, LyricFontRules.supportedName(name))
        }
    }

    @Test
    fun importLimitIsThirtyMebibytes() {
        assertEquals(30 * 1024 * 1024, LyricFontRules.MAX_BYTES)
    }
}
