package com.mytv.mymusic.ui.poster

import androidx.compose.ui.geometry.Rect
import com.mytv.mymusic.ui.adaptive.FoldSplit
import com.mytv.mymusic.ui.adaptive.foldSplit
import com.mytv.mymusic.ui.components.Fold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 折叠屏上的海报墙：大卡居中到折痕切出的那一段（书本右页、桌面上半屏、双屏避开铰链），格距按那一段选；
 * 没有折痕时一切照旧。尺寸按 Pixel Fold 内屏（2208×1840px，2.625x）和一台示意的双屏（每屏 1350px 宽，铰链 84px，2.5x）。
 */
class WallFoldTest {
    private val fold = 2.625f
    private val margin = 16f * fold

    /** 横握半开：竖折痕在 2208px 宽的正中（420dp），宽度 0 已按 1px 报上来。 */
    private val bookCrease = Fold(Rect(1104f, 0f, 1105f, 1840f), vertical = true, halfOpened = true, separating = true, occluding = false)
    private val bookSplit = foldSplit(Rect(0f, 0f, 2208f, 1840f), bookCrease, margin)!!

    /** 竖握半开：横折痕在 2208px 高的正中。 */
    private val tableCrease = Fold(Rect(0f, 1104f, 1840f, 1105f), vertical = false, halfOpened = true, separating = true, occluding = false)
    private val tableSplit = foldSplit(Rect(0f, 0f, 1840f, 2208f), tableCrease, margin)!!

    private val duo = 2.5f
    private val hinge = Fold(Rect(1350f, 0f, 1434f, 1800f), vertical = true, halfOpened = false, separating = true, occluding = true)
    private val hingeSplit = foldSplit(Rect(0f, 0f, 2784f, 1800f), hinge, 16f * duo)!!
    private val flatHinge = Fold(Rect(0f, 1350f, 1800f, 1434f), vertical = false, halfOpened = false, separating = true, occluding = true)
    private val flatHingeSplit = foldSplit(Rect(0f, 0f, 1800f, 2784f), flatHinge, 16f * duo)!!

    /** 手机档浮层：顶上让到胶囊下沿，底下让到右下按钮上沿（见 wallInsets）。 */
    private val top = 80f
    private val bottom = 116f

    /** 大卡 [p] 在镜头 [cam] 下的屏幕矩形。 */
    private fun WallScene.onScreen(p: SlotPos, cam: WallPoint): WallRect {
        val r = rectAt(p)
        return WallRect(r.x - cam.x, r.y - cam.y, r.w, r.h)
    }

    private val e = 1e-3f

    // ------------------------------------------------------------------ 选哪一段、格距

    @Test
    fun noFoldKeepsTheWindowMetricsAndNoSegment() {
        for ((w, h) in listOf(360 to 800, 800 to 1280, 1280 to 800, 841 to 701, 701 to 841, 915 to 412)) {
            assertEquals(WallSpec(wallMetricsFor(false, w, h)), wallSpecFor(false, w, h, null, fold))
        }
        // 电视不管折痕
        assertEquals(WallSpec(WallMetrics.Tv), wallSpecFor(true, 960, 540, bookSplit, 2f))
        // 没有分段时大卡区就是原来的可用区
        val vp = WallViewport(841f, 701f, top, bottom)
        assertEquals(vp.usableArea, vp.cardArea)
        assertEquals(WallRect(0f, top, 841f, 701f - top - bottom), vp.cardArea)
    }

    @Test
    fun verticalCreasePutsTheCardOnTheRightPage() {
        val spec = wallSpecFor(false, 841, 701, bookSplit, fold)
        val seg = spec.segment!!
        assertTrue(seg.vertical)
        assertEquals((1105f + margin) / fold, seg.start, e)
        assertEquals(2208f / fold, seg.end, e)
        // 右页约 404dp 宽：手机档（整屏 841×701 本来也是手机档）
        assertEquals(WallMetrics.Phone, spec.metrics)
        // 胶囊收在左页：折痕左缘再让 16dp
        assertEquals((1104f - margin) / fold, spec.leftPage!!, e)
    }

    @Test
    fun horizontalCreasePutsTheCardOnTheTopHalfWithPhoneMetrics() {
        val spec = wallSpecFor(false, 701, 841, tableSplit, fold)
        val seg = spec.segment!!
        assertFalse(seg.vertical)
        assertEquals(0f, seg.start, 0f)
        assertEquals((1104f - margin) / fold, seg.end, e)
        // 整屏 701×841 是 ShortTablet（451 的大卡），上半屏只有约 404dp 高：按那一段选，走手机档
        assertEquals(WallMetrics.ShortTablet, wallMetricsFor(false, 701, 841))
        assertEquals(WallMetrics.Phone, spec.metrics)
        assertNull(spec.leftPage)
    }

    @Test
    fun occludingHingeKeepsItsWidthClear() {
        val book = wallSpecFor(false, 1114, 720, hingeSplit, duo)
        val seg = book.segment!!
        // 铰链本身有宽度、不另加安全距离：右屏从铰链右缘开始
        assertEquals(1434f / duo, seg.start, e)
        assertEquals(2784f / duo, seg.end, e)
        assertEquals(WallMetrics.Phone, book.metrics)
        // 双横：上屏，到铰链上缘为止
        val stacked = wallSpecFor(false, 720, 1114, flatHingeSplit, duo).segment!!
        assertEquals(WallSegment(vertical = false, start = 0f, end = 1350f / duo), stacked)
    }

    @Test
    fun aPageTooNarrowForTheCardFallsBackToTheOtherOne() {
        // 折痕贴近右边：右页只有约 100dp，大卡放左页
        val nearRight = foldSplit(Rect(0f, 0f, 2208f, 1840f), bookCrease.copy(bounds = Rect(1900f, 0f, 1901f, 1840f)), margin)!!
        val seg = cardSegment(nearRight, fold)!!
        assertEquals(WallSegment(vertical = true, start = 0f, end = (1900f - margin) / fold), seg)
        // 两页都放不下 332 的大卡：当没有折痕，按整个窗口居中
        val narrow = FoldSplit(vertical = true, firstEnd = 600f, secondStart = 684f, size = 1300f, occluding = false)
        assertNull(cardSegment(narrow, fold))
        assertEquals(WallSpec(wallMetricsFor(false, 495, 701)), wallSpecFor(false, 495, 701, narrow, fold))
        assertNull(cardSegment(null, fold))
    }

    // ------------------------------------------------------------------ 镜头

    @Test
    fun withoutASegmentCenterTargetIsUnchanged() {
        val p = SlotPos(-3, 2, 5)
        val scene = WallScene(WallMetrics.Phone, fieldFor(50), p)
        val r = scene.rectAt(p)
        val vp = WallViewport(841f, 701f, top, bottom)
        // 逐位相同：和改之前的算式一模一样
        assertEquals(WallPoint(r.centerX - vp.width / 2, r.centerY - top - vp.usableHeight / 2), scene.centerTarget(p, vp))
    }

    @Test
    fun bookCardIsCentredOnTheRightPageAndOffTheCrease() {
        val seg = cardSegment(bookSplit, fold)!!
        val p = SlotPos(-3, 2, 5)
        val scene = WallScene(WallMetrics.Phone, fieldFor(50), p)
        val vp = WallViewport(2208f / fold, 1840f / fold, top, bottom, seg)
        val card = scene.onScreen(p, scene.centerTarget(p, vp))
        assertEquals(seg.start + seg.size / 2, card.centerX, e)
        assertEquals(top + vp.usableHeight / 2, card.centerY, e)
        assertTrue("左缘 ${card.x} 压进折痕", card.x >= seg.start - e)
        assertTrue(card.right <= seg.end + e)
        // 对照：不分段时大卡的中线正好是折痕（实测 ②）
        val old = scene.onScreen(p, scene.centerTarget(p, vp.copy(segment = null)))
        val crease = 1104.5f / fold
        assertTrue(old.x < crease && old.right > crease)
        // 方向键第一下选的就是大卡
        assertEquals(p, scene.initialFocus(scene.centerTarget(p, vp), vp))
    }

    @Test
    fun tabletopCardStaysAboveTheCrease() {
        val seg = cardSegment(tableSplit, fold)!!
        val p = SlotPos(1, -1, 9)
        val scene = WallScene(WallMetrics.Phone, fieldFor(50), p)
        val vp = WallViewport(1840f / fold, 2208f / fold, top, bottom, seg)
        val card = scene.onScreen(p, scene.centerTarget(p, vp))
        assertEquals(vp.width / 2, card.centerX, e)
        // 上半屏扣掉顶栏只剩约 325dp，比 332 的大卡矮：贴住折痕的安全线，宁可压到胶囊的边也不跨折痕
        assertEquals(seg.end, card.bottom, e)
        assertTrue(card.y >= 0f)
        // 顶栏矮一些放得下：在顶栏和折痕之间居中
        val roomy = vp.copy(insetTop = 40f)
        val c2 = scene.onScreen(p, scene.centerTarget(p, roomy))
        assertEquals((40f + seg.end) / 2, c2.centerY, e)
        // 下半屏的按钮不让：大卡区的下沿是折痕，不是 insetBottom
        assertEquals(WallRect(0f, 40f, vp.width, seg.end - 40f), roomy.cardArea)
    }

    @Test
    fun hingeHidesNoPartOfTheCard() {
        val seg = cardSegment(hingeSplit, duo)!!
        val p = SlotPos(0, 0, 4)
        val scene = WallScene(WallMetrics.Phone, fieldFor(30), p)
        val vp = WallViewport(2784f / duo, 1800f / duo, top, bottom, seg)
        val card = scene.onScreen(p, scene.centerTarget(p, vp))
        // 铰链 540..573.6dp：大卡整张在它右边
        assertTrue(card.x >= 1434f / duo - e)
        assertEquals(seg.start + seg.size / 2, card.centerX, e)
        val stacked = cardSegment(flatHingeSplit, duo)!!
        val sv = WallViewport(1800f / duo, 2784f / duo, top, bottom, stacked)
        val sc = scene.onScreen(p, scene.centerTarget(p, sv))
        assertTrue(sc.bottom <= 1350f / duo + e)
    }

    @Test
    fun feedBandCardNeverCrossesTheFold() {
        val cases = listOf(
            WallViewport(2208f / fold, 1840f / fold, top, bottom, cardSegment(bookSplit, fold)),
            WallViewport(1840f / fold, 2208f / fold, top, bottom, cardSegment(tableSplit, fold)),
            WallViewport(2784f / duo, 1800f / duo, top, bottom, cardSegment(hingeSplit, duo)),
        )
        for (vp in cases) {
            val seg = vp.segment!!
            val field = feedFor(WallMetrics.Phone, 30)
            for (i in 0 until field.songs) {
                val p = field.posOf(i)
                val scene = WallScene(WallMetrics.Phone, field, p)
                val card = scene.onScreen(p, scene.centerTarget(p, vp))
                val what = "$seg 第 $i 首 $card"
                if (seg.vertical) {
                    assertTrue(what, card.x >= seg.start - e && card.right <= seg.end + e)
                    assertTrue(what, card.y >= -e && card.bottom <= vp.height + e)
                } else {
                    assertTrue(what, card.y >= seg.start - e && card.bottom <= seg.end + e)
                    assertTrue(what, card.x >= -e && card.right <= vp.width + e)
                }
            }
        }
    }

    @Test
    fun revealMovesTheGrownCardIntoItsSegmentButNotOtherTiles() {
        val seg = cardSegment(bookSplit, fold)!!
        val g = SlotPos(0, 0, 3)
        val scene = WallScene(WallMetrics.Phone, fieldFor(80), g)
        val vp = WallViewport(2208f / fold, 1840f / fold, top, bottom, seg)
        val flat = vp.copy(segment = null)
        // 方向键 OK 原地放大后大卡压在折痕上：平移到右页的安全区里，只移最小距离
        val base = scene.centerTarget(g, flat)
        val t = scene.revealTarget(g, base, vp)!!
        val card = scene.onScreen(g, t)
        assertEquals(seg.start + WallMetrics.Phone.safeMargin, card.x, e)
        assertTrue(card.right <= seg.end)
        assertEquals(base.y, t.y, 0f)
        // 已经在右页里就不动
        assertNull(scene.revealTarget(g, scene.centerTarget(g, vp), vp))
        // 别的格子是背景：和没有折痕时一样
        val random = Random(5)
        repeat(50) {
            val other = SlotPos(random.nextInt(-2, 3), random.nextInt(-2, 3), random.nextInt(0, 12))
            if (other == g) return@repeat
            val cam = WallPoint(random.nextFloat() * 1600f - 800f, random.nextFloat() * 1600f - 800f)
            assertEquals(scene.revealTarget(other, cam, flat), scene.revealTarget(other, cam, vp))
        }
        // 桌面：上半屏扣掉安全区放不下大卡，居中后贴住折痕
        val tseg = cardSegment(tableSplit, fold)!!
        val tv = WallViewport(1840f / fold, 2208f / fold, top, bottom, tseg)
        val tt = scene.revealTarget(g, scene.centerTarget(g, tv.copy(segment = null)), tv)!!
        assertEquals(tseg.end, scene.onScreen(g, tt).bottom, e)
    }

    @Test
    fun followPicksTheCopyNearestTheSegment() {
        val scene = WallScene(WallMetrics.Phone, fieldFor(7))
        val seg = cardSegment(bookSplit, fold)!!
        val vp = WallViewport(2208f / fold, 1840f / fold, top, bottom, seg)
        val cam = WallPoint(37f, -120f)
        val want = scene.nearestCopy(3, cam, WallPoint(seg.start + seg.size / 2, vp.height / 2))
        assertEquals(want, scene.followTarget(3, cam, vp))
        // 没有分段：整屏中心，和以前一样
        assertEquals(scene.nearestCopy(3, cam, WallPoint(vp.width / 2, vp.height / 2)), scene.followTarget(3, cam, vp.copy(segment = null)))
    }

    // ------------------------------------------------------------------ 状态机

    private var now = 0L

    private fun config(metrics: WallMetrics, segment: WallSegment?) = WallConfig(
        metrics = metrics,
        field = fieldFor(30),
        current = 4,
        follow = true,
        isTv = false,
        drift = false,
        reduceMotion = false,
        appending = false,
        insetTop = top,
        insetBottom = bottom,
        segment = segment,
    )

    @Test
    fun theWallFollowsTheSegmentWhenItArrivesOrGoes() {
        val seg = cardSegment(bookSplit, fold)!!
        val s = PosterWallState(Random(3)) { now }
        // 第一帧墙还没摆放过，不知道折痕：先按整屏居中
        s.configure(config(WallMetrics.Phone, null))
        s.setViewportSize(2208f / fold, 1840f / fold)
        val g = s.scene!!.grown!!
        assertEquals(s.scene!!.centerTarget(g, s.viewport!!), s.camera.position)
        // 下一帧拿到折痕：大卡还停在原来的正中，直接挪进右页
        s.configure(config(WallMetrics.Phone, seg))
        assertEquals(seg, s.viewport!!.segment)
        assertEquals(s.scene!!.centerTarget(g, s.viewport!!), s.camera.position)
        val card = s.scene!!.onScreen(g, s.camera.position)
        assertTrue(card.x >= seg.start)
        // 展平：回到整屏正中
        s.configure(config(WallMetrics.Phone, null))
        assertNull(s.viewport!!.segment)
        assertEquals(s.scene!!.centerTarget(g, s.viewport!!), s.camera.position)
        // 转屏、改尺寸时用的也是带分段的视口
        s.configure(config(WallMetrics.Phone, seg))
        s.setViewportSize(2208f / fold, 1800f / fold)
        assertEquals(seg, s.viewport!!.segment)
        assertEquals(s.scene!!.centerTarget(g, s.viewport!!), s.camera.position)
    }

    @Test
    fun tabletopMetricsSwitchRebuildsTheWallInTheTopHalf() {
        val seg = cardSegment(tableSplit, fold)!!
        val s = PosterWallState(Random(3)) { now }
        s.configure(config(WallMetrics.ShortTablet, null))
        s.setViewportSize(1840f / fold, 2208f / fold)
        val generation = s.generation
        // 拿到折痕后格距从 ShortTablet 换成手机档：整面墙重排，大卡直接落在上半屏
        s.configure(config(WallMetrics.Phone, seg))
        assertEquals(generation + 1, s.generation)
        assertEquals(WallMetrics.Phone, s.metrics)
        val g = s.scene!!.grown!!
        val card = s.scene!!.onScreen(g, s.camera.position)
        assertEquals(seg.end, card.bottom, e)
        assertFalse(s.camera.isAnimating)
    }
}
