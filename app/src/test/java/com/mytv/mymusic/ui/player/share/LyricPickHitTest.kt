package com.mytv.mymusic.ui.player.share

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricPickHitTest {

    private val rows = listOf(
        3 to Rect(0f, 0f, 400f, 60f),
        4 to Rect(0f, 60f, 400f, 120f),
        5 to Rect(0f, 120f, 400f, 200f),
    )

    @Test
    fun pointInsideARowPicksIt() {
        assertEquals(3, hitLine(rows, Offset(10f, 30f)))
        assertEquals(4, hitLine(rows, Offset(399f, 61f)))
        assertEquals(5, hitLine(rows, Offset(200f, 150f)))
    }

    @Test
    fun pointOutsideEveryRowIsNull() {
        assertNull(hitLine(rows, Offset(10f, 250f)))
        assertNull(hitLine(rows, Offset(-1f, 30f)))
        assertNull(hitLine(emptyList(), Offset(10f, 30f)))
    }

    @Test
    fun emptyRectsDoNotCount() {
        // 滚出视口、被裁掉的行 boundsInRoot 是空矩形
        val clipped = listOf(7 to Rect(10f, 10f, 10f, 10f), 8 to Rect(0f, 0f, 400f, 0f))
        assertNull(hitLine(clipped, Offset(10f, 10f)))
    }

    @Test
    fun overlappingRowsPickTheShorterOne() {
        // 缩放中的经典行、嵌套的命中区：取更矮（更里层）的那个
        val nested = listOf(1 to Rect(0f, 0f, 400f, 300f), 2 to Rect(0f, 100f, 400f, 160f))
        assertEquals(2, hitLine(nested, Offset(5f, 120f)))
        assertEquals(1, hitLine(nested, Offset(5f, 200f)))
    }

    @Test
    fun edgesAreHalfOpen() {
        // Rect.contains：左、上闭，右、下开
        assertEquals(3, hitLine(rows, Offset(0f, 0f)))
        assertEquals(4, hitLine(rows, Offset(0f, 60f)))
        assertNull(hitLine(rows, Offset(400f, 30f)))
    }
}
