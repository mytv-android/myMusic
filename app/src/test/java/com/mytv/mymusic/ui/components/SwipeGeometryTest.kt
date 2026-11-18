package com.mytv.mymusic.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 都按 1dp = 1px 算，读起来和稿子里的 dp 一致。 */
class SwipeGeometryTest {
    private val open = 128f
    private val cap = 240f
    private val gap = 32f
    private val fling = 800f
    private val minFling = 24f

    private fun full(width: Float) = swipeFullDistance(width, open, cap, gap)

    @Test
    fun `phone row reaches full at sixty percent`() {
        assertEquals(216f, full(360f), 0.01f)
    }

    @Test
    fun `wide rows cap at 240`() {
        assertEquals(240f, full(900f), 0.01f)
    }

    @Test
    fun `narrow rows stay 32 beyond the open width`() {
        assertEquals(160f, full(200f), 0.01f)
    }

    @Test
    fun `start buttons without a full only open`() {
        val stops = swipeStops(true, false, false, false, open, 216f)
        assertEquals(SwipeStops(128f, null, null, null), stops)
    }

    @Test
    fun `a full without buttons goes straight to full`() {
        val stops = swipeStops(false, true, false, false, open, 216f)
        assertEquals(SwipeStops(null, 216f, null, null), stops)
    }

    @Test
    fun `both sides have four stops and the end side is negative`() {
        val stops = swipeStops(true, true, true, true, open, 216f)
        assertEquals(SwipeStops(128f, 216f, -128f, -216f), stops)
        assertTrue(stops.inFull(220f))
        assertTrue(stops.inFull(-216f))
        assertFalse(stops.inFull(200f))
    }

    private val both = swipeStops(true, true, true, true, open, 216f)

    @Test
    fun `slow and under half goes back`() {
        assertEquals(SwipeAnchor.Closed, swipeSettle(50f, 0f, both, fling, minFling))
        assertEquals(SwipeAnchor.Closed, swipeSettle(-50f, 0f, both, fling, minFling))
    }

    @Test
    fun `past half opens`() {
        assertEquals(SwipeAnchor.OpenStart, swipeSettle(70f, 0f, both, fling, minFling))
        assertEquals(SwipeAnchor.OpenEnd, swipeSettle(-70f, 0f, both, fling, minFling))
        assertEquals(SwipeAnchor.OpenStart, swipeSettle(190f, 0f, both, fling, minFling))
    }

    @Test
    fun `a quick flick after 24 opens`() {
        assertEquals(SwipeAnchor.OpenStart, swipeSettle(30f, 1000f, both, fling, minFling))
        assertEquals(SwipeAnchor.OpenEnd, swipeSettle(-30f, -1000f, both, fling, minFling))
    }

    @Test
    fun `a quick flick under 24 stays closed`() {
        assertEquals(SwipeAnchor.Closed, swipeSettle(20f, 1000f, both, fling, minFling))
    }

    @Test
    fun `past full is full`() {
        assertEquals(SwipeAnchor.FullStart, swipeSettle(216f, 0f, both, fling, minFling))
        assertEquals(SwipeAnchor.FullEnd, swipeSettle(-300f, 0f, both, fling, minFling))
    }

    @Test
    fun `flicking back from open closes`() {
        assertEquals(SwipeAnchor.Closed, swipeSettle(128f, -1000f, both, fling, minFling))
        assertEquals(SwipeAnchor.Closed, swipeSettle(-128f, 1000f, both, fling, minFling))
    }

    @Test
    fun `full only side under full goes back`() {
        val fullOnly = swipeStops(false, true, false, false, open, 216f)
        assertEquals(SwipeAnchor.Closed, swipeSettle(108f, 0f, fullOnly, fling, minFling))
        assertEquals(SwipeAnchor.Closed, swipeSettle(108f, 2000f, fullOnly, fling, minFling))
        assertEquals(SwipeAnchor.FullStart, swipeSettle(216f, 0f, fullOnly, fling, minFling))
        assertNull(fullOnly.openStart)
    }
}
