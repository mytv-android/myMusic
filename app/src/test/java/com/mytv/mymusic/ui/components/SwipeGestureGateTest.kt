package com.mytv.mymusic.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 都按 1dp = 1px 算。 */
class SwipeGestureGateTest {
    private val slop = 16f

    @Test
    fun `under 16 is undecided`() {
        assertEquals(SwipeClaim.Undecided, swipeClaim(10f, 5f, slop))
        assertEquals(SwipeClaim.Undecided, swipeClaim(-15f, 0f, slop))
    }

    @Test
    fun `mostly sideways is horizontal`() {
        assertEquals(SwipeClaim.Horizontal, swipeClaim(20f, 5f, slop))
        assertEquals(SwipeClaim.Horizontal, swipeClaim(-20f, -5f, slop))
    }

    @Test
    fun `steeper than 30 degrees is vertical`() {
        // (20, 15) 约 37°
        assertEquals(SwipeClaim.Vertical, swipeClaim(20f, 15f, slop))
    }

    @Test
    fun `exactly 30 degrees still counts as horizontal`() {
        assertEquals(SwipeClaim.Horizontal, swipeClaim(20f, 20f * SwipeAngleTan, slop))
    }

    @Test
    fun `straight down is vertical`() {
        assertEquals(SwipeClaim.Vertical, swipeClaim(0f, 30f, slop))
    }

    @Test
    fun `left edge is reserved for back`() {
        assertTrue(inEdgeZone(10f, 400f, 24f))
    }

    @Test
    fun `right edge is reserved for back`() {
        assertTrue(inEdgeZone(390f, 400f, 24f))
    }

    @Test
    fun `the middle is free`() {
        assertFalse(inEdgeZone(200f, 400f, 24f))
        assertFalse(inEdgeZone(24f, 400f, 24f))
    }

    @Test
    fun `system gesture insets wider than 24 win`() {
        val edge = swipeEdgePx(24f, 32f, 0f)
        assertEquals(32f, edge, 0f)
        assertTrue(inEdgeZone(30f, 400f, edge))
        assertEquals(24f, swipeEdgePx(24f, 0f, 16f), 0f)
    }
}
