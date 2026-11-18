package com.mytv.mymusic.ui.poster

import com.mytv.mymusic.data.PosterZoom
import com.mytv.mymusic.data.ZoomScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallZoomTest {
    private val phoneArea = WallRect(0f, 84f, 360f, 624f)
    private val tvArea = WallRect(0f, 80f, 960f, 460f)
    private val tabletArea = WallRect(0f, 0f, 1280f, 800f)

    @Test
    fun snapGoesToTheNearestStep() {
        assertEquals(0.5f, PosterZoom.snap(0.6f), 0f)
        assertEquals(0.75f, PosterZoom.snap(0.63f), 0f)
        assertEquals(1.25f, PosterZoom.snap(2f), 0f)
        assertEquals(0.5f, PosterZoom.snap(-3f), 0f)
        assertEquals(1f, PosterZoom.snap(Float.NaN), 0f)
        assertEquals(1f, PosterZoom.snap(Float.POSITIVE_INFINITY), 0f)
        for (s in PosterZoom.STEPS) assertEquals(s, PosterZoom.snap(s), 0f)
    }

    @Test
    fun stepMovesOneTickAndStopsAtTheEnds() {
        assertEquals(1.25f, PosterZoom.step(1f, 1), 0f)
        assertEquals(0.75f, PosterZoom.step(1f, -1), 0f)
        assertEquals(1.25f, PosterZoom.step(1.25f, 1), 0f)
        assertEquals(0.5f, PosterZoom.step(0.5f, -1), 0f)
    }

    @Test
    fun eachScopeKeepsItsOwnValue() {
        val z = PosterZoom().with(ZoomScope.Phone, 0.5f).with(ZoomScope.Tv, 1.3f)
        assertEquals(0.5f, z.of(ZoomScope.Phone), 0f)
        assertEquals(1f, z.of(ZoomScope.Tablet), 0f)
        // 写之前吸附
        assertEquals(1.25f, z.of(ZoomScope.Tv), 0f)
    }

    @Test
    fun scopeFollowsTheWallKindNotTheWindow() {
        assertEquals(ZoomScope.Phone, WallKind.Phone.scope)
        assertEquals(ZoomScope.Tablet, WallKind.Tablet.scope)
        assertEquals(ZoomScope.Tablet, WallKind.ShortTablet.scope)
        assertEquals(ZoomScope.Tv, WallKind.Tv.scope)
    }

    @Test
    fun zoomCameraKeepsTheSameCellUnderTheAnchor() {
        val m1 = WallMetrics.Phone
        val m2 = m1.copy(zoom = 0.5f)
        val cam = WallPoint(130f, -40f)
        val anchor = WallPoint(180f, 400f)
        val next = zoomCamera(cam, anchor, m2.zoom / m1.zoom)
        // 锚点下的墙面点换成格子坐标，前后一样
        val before = (cam.x + anchor.x) / m1.pitch to (cam.y + anchor.y) / m1.pitch
        val after = (next.x + anchor.x) / m2.pitch to (next.y + anchor.y) / m2.pitch
        assertEquals(before.first, after.first, 1e-4f)
        assertEquals(before.second, after.second, 1e-4f)
    }

    @Test
    fun grownDisplayFollowsTheWorkedExamples() {
        assertEquals(240f, grownDisplaySize(WallMetrics.Phone.copy(zoom = 0.5f), phoneArea), 1e-3f)
        assertEquals(300f, grownDisplaySize(WallMetrics.Tv.copy(zoom = 0.5f), tvArea), 1e-3f)
        assertEquals(303f, grownDisplaySize(WallMetrics.Tablet.copy(zoom = 0.5f), tabletArea), 1e-3f)
        assertEquals(336f, grownDisplaySize(WallMetrics.Phone.copy(zoom = 1.25f), phoneArea), 1e-3f)
        // 电视 1.25×：不比 1× 小，也不出 460 高的可用区
        val tvNear = grownDisplaySize(WallMetrics.Tv.copy(zoom = 1.25f), tvArea)
        assertEquals(451f, tvNear, 1e-3f)
        assertTrue(tvNear <= tvArea.h)
        // 1× 原样是槽位
        for (m in WallMetrics.entries) assertEquals(m.grownSize, grownDisplaySize(m, tabletArea), 0f)
    }

    @Test
    fun worldCoordinatesAreLinearInTheZoom() {
        val r = UnitRect(3, 2, 4, 3)
        for (m in WallMetrics.entries) for (z in PosterZoom.STEPS) {
            val a = WallLayout.world(2, -1, r, m)
            val b = WallLayout.world(2, -1, r, m.copy(zoom = z))
            assertEquals(a.x * z, b.x, 1e-3f)
            assertEquals(a.y * z, b.y, 1e-3f)
            assertEquals(a.w * z, b.w, 1e-3f)
            assertEquals(a.h * z, b.h, 1e-3f)
        }
    }

    @Test
    fun compactBelowFortyDpPitch() {
        assertTrue(WallMetrics.Phone.copy(zoom = 0.5f).compact)
        assertFalse(WallMetrics.Phone.copy(zoom = 0.75f).compact)
        assertTrue(WallMetrics.Tv.copy(zoom = 0.5f).compact)
        assertFalse(WallMetrics.Tablet.copy(zoom = 0.5f).compact)
        assertFalse(WallMetrics.Phone.compact)
    }

    @Test
    fun entriesAreStillTheFourKindsAtOne() {
        assertEquals(WallKind.entries.toList(), WallMetrics.entries.map { it.kind })
        assertTrue(WallMetrics.entries.all { it.zoom == 1f })
        assertEquals(WallMetrics.Phone, WallMetrics(WallKind.Phone, 1f))
    }

    @Test
    fun smallRadiiScaleButNotBelowFourAndTheCardKeepsItsRadius() {
        val far = WallMetrics.Phone.copy(zoom = 0.5f)
        assertEquals(4f, far.radiusSmall, 0f)
        assertEquals(5f, far.radiusLarge, 0f)
        assertEquals(24f, far.radiusGrown, 0f)
        assertEquals(24f, far.safeMargin, 0f)
    }

    @Test
    fun zoomLabelDropsTrailingZeros() {
        assertEquals("0.5", zoomLabel(0.5f))
        assertEquals("0.75", zoomLabel(0.75f))
        assertEquals("1", zoomLabel(1f))
        assertEquals("1.25", zoomLabel(1.25f))
    }

    @Test
    fun pinchZoomIsClampedSlightlyPastTheSteps() {
        assertEquals(0.9f, pinchZoom(1f, 0.9f), 1e-5f)
        assertEquals(0.45f, pinchZoom(0.5f, 0.2f), 0f)
        assertEquals(1.35f, pinchZoom(1.25f, 4f), 0f)
        assertEquals(0.625f, pinchZoom(1.25f, 0.5f), 1e-5f)
    }
}
