package com.mytv.mymusic.ui.poster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一块的 12×8 格每格正好被盖一次，且没有出界。 */
internal fun assertExactCover(rects: List<UnitRect>, what: String) {
    val count = IntArray(WallLayout.BLOCK_W * WallLayout.BLOCK_H)
    for (r in rects) {
        assertTrue("$what: $r 出界", r.x >= 0 && r.y >= 0 && r.right <= WallLayout.BLOCK_W && r.bottom <= WallLayout.BLOCK_H)
        for (y in r.y until r.bottom) for (x in r.x until r.right) count[y * WallLayout.BLOCK_W + x]++
    }
    assertTrue("$what: 有重叠或空洞 ${count.toList()}", count.all { it == 1 })
}

class WallReflowTest {
    private fun cases() = sequence {
        for (t in 0 until 4) for (m in 0 until 4) for (s in 0 until WallLayout.SLOTS) yield(Triple(t, m, s))
    }

    @Test
    fun tableHasEveryTemplateMirrorSlot() {
        val expected = cases().map { (t, m, s) -> WallReflowTable.key(t, m, s) }.toSet()
        assertEquals(WallReflowTable.CASES, expected.size)
        assertEquals(expected, WallReflowTable.keys)
    }

    @Test
    fun packedStringsRoundTrip() {
        for ((t, m, s) in cases()) {
            val packed = WallReflowTable.packed(t, m, s)!!
            assertEquals(48, packed.length)
            val rects = WallReflowTable.decode(packed)
            assertEquals(12, rects.size)
            assertEquals(packed, WallReflowTable.encode(rects))
            assertEquals(rects, WallReflowTable.rects(t, m, s))
        }
    }

    @Test
    fun everyReflowIsAnExactCoverWithTheSixBySixAtItsSlot() {
        val sizes = WallLayout.SIZES.toSet()
        for ((t, m, s) in cases()) {
            val key = WallReflowTable.key(t, m, s)
            val rects = WallLayout.reflowedRects(t, m, s)
            assertEquals(key, 12, rects.size)
            assertExactCover(rects, key)
            val g = rects[s]
            assertEquals("$key 放大格应为 6×6", 6, g.w)
            assertEquals("$key 放大格应为 6×6", 6, g.h)
            // 6×6 只落在块内 8 个对齐位置之一
            assertTrue("$key 放大格位置 $g", g.x in setOf(0, 2, 4, 6) && g.y in setOf(0, 2))
            rects.forEachIndexed { i, r ->
                if (i != s) assertTrue("$key 第 $i 位尺寸 $r", (r.w to r.h) in sizes)
            }
        }
    }

    @Test
    fun matchesPrototypeSamples() {
        // test/precompute.js 打印的两种情况
        assertEquals(
            rects(0, 0, 6, 6, 6, 0, 2, 2, 8, 0, 2, 2, 10, 0, 2, 4, 6, 2, 2, 2, 8, 2, 2, 2, 6, 4, 2, 4, 8, 4, 2, 4, 10, 4, 2, 4, 0, 6, 2, 2, 2, 6, 2, 2, 4, 6, 2, 2),
            WallLayout.reflowedRects(0, 0, 0),
        )
        assertEquals(
            rects(6, 0, 2, 2, 8, 0, 2, 2, 10, 0, 2, 3, 6, 2, 2, 2, 8, 2, 2, 3, 0, 0, 6, 6, 10, 3, 2, 2, 6, 4, 2, 4, 8, 5, 2, 3, 10, 5, 2, 3, 0, 6, 2, 2, 2, 6, 4, 2),
            WallLayout.reflowedRects(1, 0, 5),
        )
    }

    @Test
    fun solverReproducesTheWholeTable() {
        // 同一段分支定界在 JVM 上重跑，192 种都应和离线表逐格相同（原型 40 万与 300 万节点上限结果一致）
        val mismatches = cases().filter { (t, m, s) ->
            WallReflowTable.encode(WallReflowTable.solve(t, m, s)) != WallReflowTable.packed(t, m, s)
        }.map { (t, m, s) -> WallReflowTable.key(t, m, s) }.toList()
        assertEquals(emptyList<String>(), mismatches)
    }

    private fun rects(vararg v: Int): List<UnitRect> = v.toList().chunked(4) { UnitRect(it[0], it[1], it[2], it[3]) }
}
