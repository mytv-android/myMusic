package com.mytv.mymusic.ui.poster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

class WallCameraTest {
    private val phoneView = WallViewport(360f, 800f, insetTop = 84f, insetBottom = 92f)
    private val tabletView = WallViewport(1280f, 800f)
    private val tvView = WallViewport(960f, 540f, insetTop = 80f)

    private fun viewOf(m: WallMetrics) = when (m) {
        WallMetrics.Phone -> phoneView
        WallMetrics.Tablet, WallMetrics.ShortTablet -> tabletView
        WallMetrics.Tv -> tvView
    }

    private val tvFeed = WallScene(WallMetrics.Tv, feedFor(WallMetrics.Tv, 15))

    @Test
    fun fillRangeCoversTheBandWithoutEmptyEdges() {
        // 电视：一块高 608，比屏幕 540 高；15 首 + 18 个占位格占 3 块，右边到 3 × 912
        assertEquals(WallBounds(0f, 2736f - 960f, 0f, 68f), tvFeed.fillRange(tvView))
        assertEquals(WallBounds(-76f, 2736f - 960f + 76f, -76f, 144f), tvFeed.bounds(tvView))
        assertNull(WallScene(WallMetrics.Tv, fieldFor(15)).fillRange(tvView))
        assertNull(WallScene(WallMetrics.Tv, fieldFor(15)).bounds(tvView))
    }

    @Test
    fun aBandShorterThanTheScreenIsCentered() {
        // 带子 608 高、屏幕 800：上下各露 96；带子 2736 宽、屏幕 4000：贴左不动
        val f = tvFeed.fillRange(WallViewport(4000f, 800f))!!
        assertEquals(WallBounds(0f, 0f, -96f, -96f), f)
        assertEquals(WallBounds(-76f, 76f, -172f, -20f), tvFeed.bounds(WallViewport(4000f, 800f)))
    }

    @Test
    fun clampCamDoesNotPullBackACameraThatStartedOutside() {
        val b = WallBounds(0f, 100f, 0f, 50f)
        val inside = WallPoint(10f, 10f)
        assertEquals(CamClamp(WallPoint(40f, 20f), hitX = false, hitY = false), clampCam(WallPoint(40f, 20f), inside, b))
        assertEquals(CamClamp(WallPoint(100f, 0f), hitX = true, hitY = true), clampCam(WallPoint(130f, -5f), inside, b))
        // 起点已在右边界外 160：可以往回走，也可以停在原地，但不能再往外
        val outside = WallPoint(160f, 70f)
        assertEquals(CamClamp(WallPoint(150f, 60f), hitX = false, hitY = false), clampCam(WallPoint(150f, 60f), outside, b))
        assertEquals(CamClamp(WallPoint(160f, 70f), hitX = true, hitY = true), clampCam(WallPoint(190f, 90f), outside, b))
        // 往另一边越过 x0 照样挡在 x0
        assertEquals(CamClamp(WallPoint(0f, 70f), hitX = true, hitY = false), clampCam(WallPoint(-20f, 70f), outside, b))
        // 普通墙无边
        assertEquals(CamClamp(WallPoint(-999f, 5f), hitX = false, hitY = false), clampCam(WallPoint(-999f, 5f), inside, null))
    }

    @Test
    fun centerTargetPutsTheRectInTheMiddleOfTheUsableArea() {
        val p = SlotPos(-3, 2, 5)
        val scene = WallScene(WallMetrics.Phone, fieldFor(50), p)
        val t = scene.centerTarget(p, phoneView)
        val r = scene.rectAt(p)
        assertEquals(phoneView.width / 2, r.centerX - t.x, 1e-3f)
        assertEquals(phoneView.insetTop + phoneView.usableHeight / 2, r.centerY - t.y, 1e-3f)
    }

    @Test
    fun feedCenterKeepsTheGrownCardFullyVisible() {
        for (m in WallMetrics.entries) {
            val vp = viewOf(m)
            val field = feedFor(m, 30)
            val fill = WallScene(m, field).fillRange(vp)!!
            for (i in 0 until field.songs) {
                val p = field.posOf(i)
                val scene = WallScene(m, field, p)
                val t = scene.centerTarget(p, vp)
                val r = scene.rectAt(p)
                val e = 1e-3f
                val what = "$m 第 $i 首"
                assertTrue("$what 左 $r $t", r.x >= t.x - e)
                assertTrue("$what 右", r.right <= t.x + vp.width + e)
                assertTrue("$what 上", r.y >= t.y + vp.insetTop - e)
                assertTrue("$what 下", r.bottom <= t.y + vp.insetTop + vp.usableHeight + e)
                // 横向不露带子外的空边
                assertTrue("$what 横向露边 $t", t.x >= fill.x0 - e && t.x <= fill.x1 + e)
            }
            // 第一首贴着带子左端
            assertEquals(0f, WallScene(m, field, field.posOf(0)).centerTarget(field.posOf(0), vp).x, 0f)
        }
    }

    @Test
    fun revealMovesOnlyWhenTheFocusLeavesTheSafeArea() {
        val scene = WallScene(WallMetrics.Tv, fieldFor(80))
        val p = SlotPos(1, 1, 7)
        val r = scene.rectAt(p)
        // 安全区：左右内缩 40，上从 80 + 40 起，下到可用区底边内缩 40
        assertNull(scene.revealTarget(p, WallPoint(r.x - 100f, r.y - 140f), tvView))
        // 右边出界 30：只往右挪 30
        val base = WallPoint(r.right - 950f, r.y - 140f)
        val t1 = scene.revealTarget(p, base, tvView)!!
        assertEquals(base.x + 30f, t1.x, 1e-3f)
        assertEquals(base.y, t1.y, 0f)
        // 左边出界 10、上边出界 15
        val base2 = WallPoint(r.x - 30f, r.y - 105f)
        val t2 = scene.revealTarget(p, base2, tvView)!!
        assertEquals(base2.x - 10f, t2.x, 1e-3f)
        assertEquals(base2.y - 15f, t2.y, 1e-3f)
        // 手机上放大卡 332 比安全区 312 还宽：横向改为居中
        val g = SlotPos(0, 0, 3)
        val phone = WallScene(WallMetrics.Phone, fieldFor(80), g)
        val gr = phone.rectAt(g)
        val t3 = phone.revealTarget(g, WallPoint(gr.x - 10f, gr.y - 100f), phoneView)!!
        assertEquals(gr.centerX - 180f, t3.x, 1e-3f)
    }

    @Test
    fun springSettlesTheSameAtAnyFrameRate() {
        fun run(fps: Int, seconds: Double): WallCamera = WallCamera().also { c ->
            c.animateTo(WallPoint(480f, -260f))
            repeat((seconds * fps).roundToInt()) { c.step(1f / fps) }
        }
        val mid = listOf(30, 60, 120).map { run(it, 0.2) }
        for (c in mid) {
            assertTrue(c.isAnimating)
            assertEquals(mid[1].x, c.x, 1e-2f)
            assertEquals(mid[1].y, c.y, 1e-2f)
        }
        // 解析解：0.2 秒时偏差剩 (1 + 13×0.2)·e^(−2.6) ≈ 26.7%
        assertEquals(480f * (1 - 0.2674f), mid[1].x, 0.5f)
        for (fps in listOf(30, 60, 120)) {
            val c = run(fps, 1.5)
            assertFalse(c.isAnimating)
            assertNull(c.target)
            assertEquals(WallPoint(480f, -260f), c.position)
        }
    }

    @Test
    fun retargetingKeepsTheSpringVelocity() {
        val c = WallCamera()
        c.animateTo(WallPoint(1000f, 0f))
        repeat(6) { c.step(1f / 60f) }
        val before = c.x
        c.animateTo(WallPoint(1000f, 300f))
        c.step(1f / 60f)
        // 横向仍在往前冲，没有因为换目标而停住
        assertTrue(c.x > before + 5f)
    }

    @Test
    fun flingDecaysTheSameAtAnyFrameRate() {
        fun run(fps: Int, seconds: Double): WallCamera = WallCamera().also { c ->
            c.fling(1500f, -800f, WallPoint(0f, 0f))
            repeat((seconds * fps).roundToInt()) { c.step(1f / fps) }
        }
        val mid = listOf(30, 60, 120).map { run(it, 0.3) }
        for (c in mid) {
            assertTrue(c.isFlinging)
            assertEquals(mid[0].x, c.x, 1e-3f)
            assertEquals(mid[0].y, c.y, 1e-3f)
        }
        // 速度降到 8dp/s 时停：走过 v₀(1 − 8/|v₀|)/4.2，方向和手指相反
        val keep = (1 - 8.0 / hypot(1500.0, 800.0)) / 4.2
        for (fps in listOf(30, 60, 120)) {
            val c = run(fps, 2.0)
            assertFalse(c.isFlinging)
            assertEquals((-1500 * keep).toFloat(), c.x, 1e-2f)
            assertEquals((800 * keep).toFloat(), c.y, 1e-2f)
        }
    }

    @Test
    fun flingStopsPerAxisOnTheBandEdge() {
        val b = tvFeed.bounds(tvView)!!
        for (fps in listOf(30, 60, 120)) {
            val c = WallCamera(100f, 30f).apply { bounds = b }
            // 镜头往右下甩：纵向撞到 y1 就停，横向接着滑
            c.fling(-2000f, -3000f, WallPoint(100f, 30f))
            repeat(2 * fps) { c.step(1f / fps) }
            assertFalse(c.isAnimating)
            assertEquals(b.y1, c.y, 0f)
            assertEquals(100f + (2000.0 * (1 - 8.0 / 2000.0) / 4.2).toFloat(), c.x, 1e-2f)
        }
    }

    @Test
    fun dragIsLimitedByTheGestureStart() {
        val c = WallCamera(1900f, 0f).apply { bounds = tvFeed.bounds(tvView) }
        val from = c.beginDrag()
        // 起点在右边界 1852 外：可以往回拖，往外拖被挡在起点
        assertEquals(CamClamp(WallPoint(1900f, 0f), hitX = true, hitY = false), c.dragTo(WallPoint(1950f, 0f), from))
        assertEquals(WallPoint(1700f, 0f), c.dragTo(WallPoint(1700f, 0f), from).point)
        assertEquals(1700f, c.x, 0f)
    }

    @Test
    fun driftStartsAfterThirtyIdleSecondsAtTenDpPerSecond() {
        val c = WallCamera(50f, 60f, Random(3)).apply { driftEnabled = true }
        repeat(29 * 60) { c.step(1f / 60f) }
        assertFalse(c.isDrifting)
        assertTrue(c.chromeIdle)
        assertEquals(WallPoint(50f, 60f), c.position)
        repeat(2 * 60) { c.step(1f / 60f) }
        assertTrue(c.isDrifting)
        // 起步 3 秒加速，之后匀速 10dp/s（方向慢慢拐，1 秒内弦长和弧长几乎一样）
        repeat(10 * 60) { c.step(1f / 60f) }
        val a = c.position
        repeat(60) { c.step(1f / 60f) }
        val d = hypot(c.x - a.x, c.y - a.y)
        assertTrue("1 秒走了 $d", d in 9.9f..10.001f)
    }

    @Test
    fun driftNeedsTheSwitch() {
        val c = WallCamera(0f, 0f, Random(1))
        repeat(60 * 30) { c.step(1f / 30f) }
        assertFalse(c.isDrifting)
        assertEquals(WallPoint(0f, 0f), c.position)
    }

    @Test
    fun feedDriftTravelsAlongTheBandAndBouncesOffItsEdges() {
        val b = tvFeed.bounds(tvView)!!
        for (seed in 0 until 4) {
            val c = WallCamera(900f, 34f, Random(seed)).apply { bounds = b }
            c.startDrift()
            repeat(30) { c.step(1f / 60f) }
            val dx = c.x - 900f
            val dy = c.y - 34f
            assertTrue("seed $seed 应沿带子横漂 dx=$dx dy=$dy", abs(dx) > 0.1f && abs(dy) < abs(dx) * 0.1f)
            // 朝向偏离 a0 不超过 1.4 弧度，横向只在碰边时掉头
            var dir = if (dx > 0) 1 else -1
            var flips = 0
            var last = c.x
            repeat(600 * 30) {
                c.step(1f / 30f)
                assertTrue("x=${c.x}", c.x >= b.x0 && c.x <= b.x1)
                assertTrue("y=${c.y}", c.y >= b.y0 && c.y <= b.y1)
                val now = if (c.x > last) 1 else if (c.x < last) -1 else dir
                if (now != dir) {
                    flips++
                    assertTrue("掉头时应在边上 x=${c.x}", c.x < b.x0 + 2f || c.x > b.x1 - 2f)
                    dir = now
                }
                last = c.x
            }
            assertTrue("seed $seed 600 秒应碰过边", flips >= 1)
        }
    }

    @Test
    fun normalWallDriftHeadsAnywhere() {
        val headings = (0 until 16).map { seed ->
            val c = WallCamera(0f, 0f, Random(seed))
            c.startDrift()
            assertTrue(c.isDrifting)
            repeat(12) { c.step(1f / 60f) }
            atan2(c.y.toDouble(), c.x.toDouble())
        }
        // 随机朝向：既有朝上也有朝下、朝左也有朝右
        assertTrue(headings.any { it > 0.3 } && headings.any { it < -0.3 })
        assertTrue(headings.any { abs(it) < 1.2 } && headings.any { abs(it) > 1.9 })
    }

    @Test
    fun driftIsTheSameAtAnyFrameRate() {
        val b = tvFeed.bounds(tvView)!!
        var touchedEdge = false
        // 两个起点分别靠近左右边界：不管随机到朝左还是朝右，总有一个会在 120 秒里碰边掉头
        for (start in listOf(WallPoint(1800f, 40f), WallPoint(-40f, 40f))) {
            val runs = listOf(30, 60, 120).map { fps ->
                WallCamera(start.x, start.y, Random(11)).also { c ->
                    c.bounds = b
                    c.driftEnabled = true
                    repeat(150 * fps) {
                        c.step(1f / fps)
                        if (c.x <= b.x0 + 1f || c.x >= b.x1 - 1f) touchedEdge = true
                    }
                }
            }
            for (c in runs) {
                assertTrue(c.isDrifting)
                assertEquals("从 $start 起漂", runs[2].x, c.x, 0.05f)
                assertEquals("从 $start 起漂", runs[2].y, c.y, 0.05f)
            }
        }
        assertTrue(touchedEdge)
    }

    @Test
    fun springTakesPriorityOverDrift() {
        val c = WallCamera(0f, 0f, Random(5))
        c.startDrift()
        repeat(60) { c.step(1f / 60f) }
        c.animateTo(WallPoint(300f, 200f))
        repeat(60) { c.step(1f / 60f) }
        // 弹簧到位后漂移接着走：离终点只差到位后漂出的那一点
        assertNull(c.target)
        assertTrue(c.isDrifting)
        assertTrue(hypot(c.x - 300f, c.y - 200f) < 5f)
    }

    @Test
    fun wakeStopsDriftAndReportsTheFirstPress() {
        val c = WallCamera(0f, 0f, Random(2)).apply { driftEnabled = true }
        repeat(31 * 30) { c.step(1f / 30f) }
        assertTrue(c.isDrifting)
        assertTrue(c.chromeIdle)
        val moved = c.position
        assertTrue("漂移中", moved != WallPoint(0f, 0f))
        // 第一下只唤醒
        assertTrue(c.wake())
        assertFalse(c.isDrifting)
        assertFalse(c.chromeIdle)
        assertEquals(0.0, c.idleSeconds, 0.0)
        assertFalse(c.wake())
        c.step(1f / 30f)
        assertEquals(moved, c.position)
        // 关掉开关也算一次操作，并停下漂移
        repeat(31 * 30) { c.step(1f / 30f) }
        assertTrue(c.isDrifting)
        c.driftEnabled = false
        assertFalse(c.isDrifting)
        assertEquals(0.0, c.idleSeconds, 0.0)
    }
}
