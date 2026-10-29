package com.mytv.mymusic.ui.poster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class WallGeometryTest {
    @Test
    fun templatesAreExactCoversWithAllowedSizes() {
        // test/check.js：每个模板 12 块、面积 96、无重叠、尺寸都在 SIZES 里
        val sizes = WallLayout.SIZES.toSet()
        WallLayout.TEMPLATES.forEachIndexed { t, rects ->
            assertEquals(12, rects.size)
            assertEquals(96, rects.sumOf { it.w * it.h })
            assertExactCover(rects, "模板 $t")
            for (r in rects) assertTrue("模板 $t 尺寸 $r", (r.w to r.h) in sizes)
        }
    }

    @Test
    fun everyMirrorIsAnExactCoverInReadingOrder() {
        for (t in 0 until 4) for (m in 0 until 4) {
            val rects = WallLayout.slotRects(t, m)
            assertExactCover(rects, "$t.$m")
            // 阅读顺序：先按行再按列
            for (i in 1 until rects.size) {
                val a = rects[i - 1]
                val b = rects[i]
                assertTrue("$t.$m 第 $i 位", a.y < b.y || (a.y == b.y && a.x < b.x))
            }
            assertEquals(WallLayout.TEMPLATES[t].toSet(), WallLayout.mirrored(rects, m).toSet())
        }
    }

    @Test
    fun blockHashMatchesPrototype() {
        // 原型 blockHash 在 node 里的输出
        val expected = listOf(
            Triple(0, 0, 0L), Triple(1, 0, 2182377942L), Triple(0, 1, 3299714085L), Triple(-1, -1, 731167729L),
            Triple(5, -3, 818043104L), Triple(123, 456, 3899980354L), Triple(-7, 9, 1621631971L),
        )
        val mirrors = listOf(0, 2, 1, 1, 0, 2, 3)
        val templates = listOf(0, 1, 2, 1, 3, 3, 3)
        expected.forEachIndexed { i, (bx, by, hash) ->
            assertEquals("hash($bx, $by)", hash, WallLayout.blockHash(bx, by))
            assertEquals("mirror($bx, $by)", mirrors[i], WallLayout.mirrorOf(bx, by))
            assertEquals("template($bx, $by)", templates[i], WallLayout.templateOf(bx, by))
        }
    }

    @Test
    fun neighbouringBlocksNeverShareATemplate() {
        for (by in -5..5) for (bx in -5..5) {
            assertNotEquals(WallLayout.templateOf(bx, by), WallLayout.templateOf(bx + 1, by))
            assertNotEquals(WallLayout.templateOf(bx, by), WallLayout.templateOf(bx, by + 1))
        }
    }

    @Test
    fun fieldForMatchesPrototype() {
        val expected = mapOf(0 to (2 to 1), 1 to (2 to 1), 5 to (2 to 1), 12 to (2 to 1), 13 to (2 to 1), 30 to (3 to 1), 100 to (4 to 3), 1000 to (12 to 7))
        for ((n, cr) in expected) {
            val f = fieldFor(n)
            assertEquals("cols($n)", cr.first, f.cols)
            assertEquals("rows($n)", cr.second, f.rows)
            assertTrue("单元放得下全部 $n 首", f.cols * f.rows * WallLayout.SLOTS >= n)
        }
    }

    @Test
    fun loopingCoversEverySongInEachRepeatingCell() {
        for (n in 1..200) {
            val f = fieldFor(n)
            // 任取几个单元的起点（含负坐标），单元里每首至少出现一次
            for ((ox, oy) in listOf(0 to 0, f.cols to 0, -f.cols to -f.rows, 3 * f.cols to -2 * f.rows)) {
                val seen = BooleanArray(n)
                for (by in oy until oy + f.rows) for (bx in ox until ox + f.cols) for (s in 0 until WallLayout.SLOTS) {
                    val i = f.songAt(bx, by, s)
                    assertTrue("n=$n songAt=$i", i in 0 until n)
                    seen[i] = true
                }
                assertTrue("n=$n 单元 ($ox,$oy) 漏了歌", seen.all { it })
            }
        }
    }

    @Test
    fun loopingWrapsNegativeBlocks() {
        val f = fieldFor(100)
        for (by in -7..7) for (bx in -9..9) for (s in 0 until WallLayout.SLOTS) {
            val i = f.songAt(bx, by, s)
            assertEquals(i, f.songAt(bx + f.cols, by, s))
            assertEquals(i, f.songAt(bx - 3 * f.cols, by + 2 * f.rows, s))
        }
        assertEquals(f.songAt(f.cols - 1, f.rows - 1, 4), f.songAt(-1, -1, 4))
        // 第一个单元的第 0 块第 0 位是第 1 首
        assertEquals(0, f.songAt(0, 0, 0))
        assertEquals(0, f.songAt(-f.cols, -f.rows, 0))
    }

    @Test
    fun emptyLoopingWallDrawsNothing() {
        val f = fieldFor(0)
        assertEquals(-1, f.songAt(0, 0, 0))
        assertTrue(WallScene(WallMetrics.Phone, f).visibleTiles(WallPoint(0f, 0f), WallViewport(360f, 800f)).isEmpty())
    }

    @Test
    fun feedBandIsColumnMajorWithGhostsAndNothingOutside() {
        val f = WallField.FeedBand(rows = 2, songs = 15)
        assertEquals(33, f.tiles)
        assertEquals(SlotPos(0, 0, 0), f.posOf(0))
        assertEquals(SlotPos(0, 0, 11), f.posOf(11))
        assertEquals(SlotPos(0, 1, 0), f.posOf(12))
        assertEquals(SlotPos(1, 0, 0), f.posOf(24))
        assertEquals(SlotPos(1, 1, 5), f.posOf(41))
        for (i in 0 until f.tiles) {
            val p = f.posOf(i)
            assertEquals(i, f.songAt(p.bx, p.by, p.slot))
            assertEquals(i >= 15, f.isGhost(i))
        }
        // 最后一个占位格之后、带子上下和左边都不画
        val after = f.posOf(f.tiles)
        assertEquals(-1, f.songAt(after.bx, after.by, after.slot))
        assertEquals(-1, f.songAt(-1, 0, 0))
        assertEquals(-1, f.songAt(0, -1, 0))
        assertEquals(-1, f.songAt(0, 2, 0))
        assertEquals(1, feedFor(WallMetrics.Tv, 15).rows)
        assertEquals(1, feedFor(WallMetrics.Tablet, 15).rows)
        assertEquals(2, feedFor(WallMetrics.Phone, 15).rows)
    }

    @Test
    fun appendingToFeedNeverMovesExistingTiles() {
        for (metrics in WallMetrics.entries) {
            val before = feedFor(metrics, 15)
            val after = feedFor(metrics, 30)
            val grown = before.posOf(4)
            val a = WallScene(metrics, before, grown)
            val b = WallScene(metrics, after, grown)
            for (i in 0 until 15) {
                val p = before.posOf(i)
                assertEquals(p, after.posOf(i))
                assertEquals(a.rectAt(p), b.rectAt(p))
                assertEquals(i, before.songAt(p.bx, p.by, p.slot))
                assertEquals(i, after.songAt(p.bx, p.by, p.slot))
            }
            // 原来的占位格就地翻成封面（30 首之后的仍是占位格），位置不变
            for (i in 15 until before.tiles) {
                val p = before.posOf(i)
                assertEquals(p, after.posOf(i))
                assertEquals(i, after.songAt(p.bx, p.by, p.slot))
                assertTrue(before.isGhost(i))
                assertEquals(i >= 30, after.isGhost(i))
            }
            // 屏幕上已有的格子一个都不动
            val cam = WallPoint(-40f, -10f)
            val vp = WallViewport(2000f, 900f)
            val old = a.visibleTiles(cam, vp).filter { it.song < 15 }
            assertTrue(old.isNotEmpty())
            assertEquals(old, b.visibleTiles(cam, vp).filter { it.song < 15 })
        }
    }

    @Test
    fun ghostsFadeFromOpaqueToAboutAFifth() {
        val f = WallField.FeedBand(rows = 1, songs = 15)
        assertEquals(1f, f.ghostAlpha(15), 1e-6f)
        assertEquals(1f - 17f / 18f * 0.85f, f.ghostAlpha(32), 1e-6f)
        for (i in 16 until f.tiles) assertTrue(f.ghostAlpha(i) < f.ghostAlpha(i - 1))
    }

    @Test
    fun worldRectsUsePitchAndGap() {
        assertEquals(332f, WallMetrics.Phone.grownSize, 0f)
        assertEquals(606f, WallMetrics.Tablet.grownSize, 0f)
        assertEquals(451f, WallMetrics.Tv.grownSize, 0f)
        val r = WallLayout.world(-1, 2, UnitRect(4, 2, 2, 3), WallMetrics.Tv)
        assertEquals(WallRect((-12 + 4) * 76f, (16 + 2) * 76f, 2 * 76f - 5f, 3 * 76f - 5f), r)
    }

    @Test
    fun cornerRadiusFollowsShortSideAndGrown() {
        val m = WallMetrics.Tablet
        assertEquals(12f, m.cornerRadius(UnitRect(0, 0, 2, 4), grown = false), 0f)
        assertEquals(16f, m.cornerRadius(UnitRect(0, 0, 3, 4), grown = false), 0f)
        assertEquals(28f, m.cornerRadius(UnitRect(0, 0, 6, 6), grown = true), 0f)
        assertEquals(8f, WallMetrics.Phone.cornerRadius(UnitRect(0, 0, 6, 2), grown = false), 0f)
        assertEquals(10f, WallMetrics.Tv.cornerRadius(UnitRect(0, 0, 2, 2), grown = false), 0f)
    }

    @Test
    fun onlyTheGrownBlockReflows() {
        val grown = SlotPos(3, -2, 7)
        for (by in -4..0) for (bx in 1..5) {
            val t = WallLayout.templateOf(bx, by)
            val m = WallLayout.mirrorOf(bx, by)
            val expected = if (bx == 3 && by == -2) WallLayout.reflowedRects(t, m, 7) else WallLayout.slotRects(t, m)
            assertEquals(expected, WallLayout.blockRects(bx, by, grown))
        }
        val scene = WallScene(WallMetrics.Tv, fieldFor(60), grown)
        assertEquals(WallMetrics.Tv.grownSize, scene.rectAt(grown).w, 0f)
        assertTrue(scene.tileAt(grown).grown)
    }

    @Test
    fun visibleTilesCoverTheViewportExactlyOnce() {
        val vp = WallViewport(1280f, 800f)
        val cam = WallPoint(-1234.5f, 777.25f)
        val scene = WallScene(WallMetrics.Tablet, fieldFor(40), SlotPos(-1, 1, 3))
        val tiles = scene.visibleTiles(cam, vp)
        assertEquals(tiles.size, tiles.map { it.pos }.toSet().size)
        val shown = tiles.map { it.pos }.toSet()
        val range = scene.visibleBlocks(cam, vp)
        val ov = WallMetrics.Tablet.pitch * 2
        for (by in range.by0 - 2..range.by1 + 2) for (bx in range.bx0 - 2..range.bx1 + 2) for (s in 0 until 12) {
            val p = SlotPos(bx, by, s)
            val r = scene.rectAt(p)
            val onScreen = r.right > cam.x && r.x < cam.x + vp.width && r.bottom > cam.y && r.y < cam.y + vp.height
            if (onScreen) assertTrue("$p 在屏幕上却没画", p in shown)
            if (p in shown) {
                assertTrue("$p 超出外扩区", r.right >= cam.x - ov && r.x <= cam.x + vp.width + ov && r.bottom >= cam.y - ov && r.y <= cam.y + vp.height + ov)
            }
        }
        for (t in tiles) {
            assertEquals(scene.songAt(t.pos.bx, t.pos.by, t.pos.slot), t.song)
            assertEquals(t.pos == SlotPos(-1, 1, 3), t.grown)
        }
    }

    @Test
    fun feedVisibleTilesStayOnTheBand() {
        val f = WallField.FeedBand(rows = 1, songs = 15)
        val scene = WallScene(WallMetrics.Tv, f)
        val tiles = scene.visibleTiles(WallPoint(-300f, -200f), WallViewport(4000f, 1200f))
        assertEquals(f.tiles, tiles.size)
        assertEquals((0 until f.tiles).toSet(), tiles.map { it.song }.toSet())
        for (t in tiles) assertTrue(t.pos.bx >= 0 && t.pos.by == 0)
    }

    @Test
    fun nearestCopyIsTheClosestRepeat() {
        val m = WallMetrics.Phone
        val scene = WallScene(m, fieldFor(30))
        val cam = WallPoint(100f, -50f)
        val center = WallPoint(180f, 400f)
        val wx = cam.x + center.x
        val wy = cam.y + center.y
        for (song in 0 until 30) {
            val p = scene.nearestCopy(song, cam, center)!!
            assertEquals(song, scene.songAt(p.bx, p.by, p.slot))
            val r = scene.rectAt(p)
            val d = hypot(r.centerX - wx, r.centerY - wy)
            for (by in -5..5) for (bx in -5..5) for (s in 0 until 12) {
                if (scene.songAt(bx, by, s) != song) continue
                val o = scene.rectAt(SlotPos(bx, by, s))
                assertTrue("第 $song 首有更近的一份 ($bx,$by,$s)", hypot(o.centerX - wx, o.centerY - wy) >= d)
            }
        }
    }

    @Test
    fun nearestCopyInFeedIsItsOnlyCopy() {
        val f = WallField.FeedBand(rows = 2, songs = 15)
        val scene = WallScene(WallMetrics.Phone, f)
        val far = WallPoint(99999f, 99999f)
        for (i in 0 until 15) assertEquals(f.posOf(i), scene.nearestCopy(i, far, WallPoint(0f, 0f)))
        assertNull(scene.nearestCopy(15, far, WallPoint(0f, 0f)))
        assertNull(scene.nearestCopy(-1, far, WallPoint(0f, 0f)))
    }

    @Test
    fun focusScorePenalisesAMissedSpan() {
        val cur = WallRect(0f, 0f, 100f, 100f)
        val p = 50f
        assertEquals(FocusScore(10f, 25f), focusScore(cur, WallRect(110f, 0f, 50f, 50f), 1, 0, p))
        // 间距更小但横向错开 50：5 + 2×50 + 一个格距
        assertEquals(FocusScore(155f, 125f), focusScore(cur, WallRect(105f, 150f, 50f, 50f), 1, 0, p))
        // 不整格在前方的不算；1dp 以内的重叠放过
        assertNull(focusScore(cur, WallRect(50f, 0f, 100f, 100f), 1, 0, p))
        assertEquals(-0.5f, focusScore(cur, WallRect(99.5f, 0f, 10f, 10f), 1, 0, p)!!.score, 0f)
        assertEquals(10f, focusScore(cur, WallRect(-60f, 20f, 50f, 50f), -1, 0, p)!!.score, 0f)
        assertEquals(4f, focusScore(cur, WallRect(0f, 104f, 100f, 40f), 0, 1, p)!!.score, 0f)
        assertEquals(100f, focusScore(cur, WallRect(120f, -50f, 30f, 40f), 0, -1, p)!!.score, 0f)
        assertNull(focusScore(cur, WallRect(0f, 104f, 100f, 40f), 0, -1, p))
    }

    @Test
    fun dpadMovesToTheSpatialNeighbour() {
        // 第 (0,0) 块是模板 0、不镜像；位依次是
        // 0:[0,0,4,4] 1:[4,0,2,2] 2:[6,0,3,2] 3:[9,0,3,4] 4:[4,2,2,3] 5:[6,2,3,2]
        // 6:[0,4,4,2] 7:[6,4,4,4] 8:[10,4,2,4] 9:[4,5,2,3] 10:[0,6,2,2] 11:[2,6,2,2]
        val scene = WallScene(WallMetrics.Tablet, fieldFor(500))
        assertEquals(UnitRect(4, 2, 2, 3), scene.rectsOf(0, 0)[4])
        // 往右：1 和 4 间距相同，1 的中心更对齐
        assertEquals(SlotPos(0, 0, 1), scene.focusNeighbour(SlotPos(0, 0, 0), 1, 0))
        assertEquals(SlotPos(0, 0, 4), scene.focusNeighbour(SlotPos(0, 0, 1), 0, 1))
        assertEquals(SlotPos(0, 0, 0), scene.focusNeighbour(SlotPos(0, 0, 6), 0, -1))
        // 2 和 5 得分、横向偏移都一样，保留先找到的
        assertEquals(SlotPos(0, 0, 2), scene.focusNeighbour(SlotPos(0, 0, 3), -1, 0))
        // 往右 5 比 7 对齐
        assertEquals(SlotPos(0, 0, 5), scene.focusNeighbour(SlotPos(0, 0, 4), 1, 0))
        // 出块：往上进到 (0,-1)，落在正上方那一行
        val up = scene.focusNeighbour(SlotPos(0, 0, 1), 0, -1)!!
        assertEquals(0, up.bx)
        assertEquals(-1, up.by)
        val r = scene.rectAt(up)
        val cur = scene.rectAt(SlotPos(0, 0, 1))
        assertEquals(-WallMetrics.Tablet.gap, r.bottom, 1e-3f)
        assertTrue(r.x < cur.right && r.right > cur.x)
    }

    @Test
    fun dpadStopsAtTheEndOfTheFeedBand() {
        val f = WallField.FeedBand(rows = 1, songs = 0, ghosts = 12)
        val scene = WallScene(WallMetrics.Tv, f)
        val rightmost = (0 until 12).maxBy { scene.rectAt(f.posOf(it)).right }
        assertNull(scene.focusNeighbour(f.posOf(rightmost), 1, 0))
    }

    @Test
    fun initialFocusIsNearestToTheUsableCenter() {
        val scene = WallScene(WallMetrics.Tablet, fieldFor(500))
        val target = scene.rectAt(SlotPos(0, 0, 7))
        val vp = WallViewport(1280f, 800f)
        val cam = WallPoint(target.centerX - vp.width / 2, target.centerY - vp.height / 2)
        assertEquals(SlotPos(0, 0, 7), scene.initialFocus(cam, vp))
        // 电视：可用区从顶部 80 起
        val tv = WallScene(WallMetrics.Tv, fieldFor(500))
        val tr = tv.rectAt(SlotPos(2, 1, 0))
        val tvp = WallViewport(960f, 540f, insetTop = 80f)
        val tcam = WallPoint(tr.centerX - 480f, tr.centerY - 80f - tvp.usableHeight / 2)
        assertEquals(SlotPos(2, 1, 0), tv.initialFocus(tcam, tvp))
    }
}
