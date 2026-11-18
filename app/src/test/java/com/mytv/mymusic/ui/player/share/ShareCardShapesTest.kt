package com.mytv.mymusic.ui.player.share

import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.graphics.RectangleShape
import com.mytv.mymusic.data.ShareCardShape
import com.mytv.mymusic.data.SharePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCardShapesTest {

    /** Rush（shub39/Rush@5a952bf）AlbumArtShape 的顺序。 */
    private val rushNames = listOf(
        "CIRCLE", "SUNNY", "VERY_SUNNY", "SQUARE", "SLANTED", "ARCH", "PILL", "PENTAGON", "GEM",
        "COOKIE_4", "COOKIE_6", "COOKIE_9", "COOKIE_12", "CLOVER_4", "CLOVER_8", "GHOSTISH", "FLOWER",
        "SOFT_BURST", "PUFFY_DIAMOND", "BUN", "RECTANGLE",
    )

    @Test
    fun orderMatchesRush() {
        assertEquals(21, ShareCardShape.entries.size)
        val ours = ShareCardShape.entries.map { shape ->
            shape.name.replace(Regex("([a-z])([A-Z0-9])"), "$1_$2").uppercase()
        }
        assertEquals(rushNames, ours)
    }

    @Test
    fun labelsAreDistinct() {
        val labels = ShareCardShape.entries.map { it.label }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun onlyRectangleHasNoPolygon() {
        ShareCardShape.entries.filter { it != ShareCardShape.Rectangle }.forEach { assertNotNull(it.name, it.polygon) }
        assertNull(ShareCardShape.Rectangle.polygon)
        assertSame(RectangleShape, ShareCardShape.Rectangle.toShape())
    }

    @Test
    fun defaultIsTwelveSidedCookie() {
        assertEquals(ShareCardShape.Cookie12, SharePrefs().shape)
        assertSame(MaterialShapes.Cookie12Sided, SharePrefs().shape.polygon)
    }
}
