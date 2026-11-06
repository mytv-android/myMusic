package com.mytv.mymusic.ui.player.stage

import androidx.compose.ui.geometry.Rect
import com.mytv.mymusic.ui.player.stage.modes.RING_CAP_FALLBACK
import com.mytv.mymusic.ui.player.stage.modes.RingRect
import com.mytv.mymusic.ui.player.stage.modes.RingUniforms
import com.mytv.mymusic.ui.player.stage.modes.RingsBeatGate
import com.mytv.mymusic.ui.player.stage.modes.RingsEnergy
import com.mytv.mymusic.ui.player.stage.modes.RingsRecord
import com.mytv.mymusic.ui.player.stage.modes.SHADER_ELEMENTS
import com.mytv.mymusic.ui.player.stage.modes.SHAPE_POLYGON
import com.mytv.mymusic.ui.player.stage.modes.STATIC_BEAT_S
import com.mytv.mymusic.ui.player.stage.modes.easeOutCubic
import com.mytv.mymusic.ui.player.stage.modes.isWideChar
import com.mytv.mymusic.ui.player.stage.modes.layoutRingsLine
import com.mytv.mymusic.ui.player.stage.modes.mergeRingRects
import com.mytv.mymusic.ui.player.stage.modes.offsetOutline
import com.mytv.mymusic.ui.player.stage.modes.packRingUniforms
import com.mytv.mymusic.ui.player.stage.modes.pithCenter
import com.mytv.mymusic.ui.player.stage.modes.pithOutline
import com.mytv.mymusic.ui.player.stage.modes.pithSeed
import com.mytv.mymusic.ui.player.stage.modes.pithShape
import com.mytv.mymusic.ui.player.stage.modes.planFine
import com.mytv.mymusic.ui.player.stage.modes.rectShape
import com.mytv.mymusic.ui.player.stage.modes.ringAlpha
import com.mytv.mymusic.ui.player.stage.modes.ringFade
import com.mytv.mymusic.ui.player.stage.modes.ringHalfWidthDp
import com.mytv.mymusic.ui.player.stage.modes.ringRects
import com.mytv.mymusic.ui.player.stage.modes.ringWindow
import com.mytv.mymusic.ui.player.stage.modes.ringsFillerGap
import com.mytv.mymusic.ui.player.stage.modes.ringsLightProgress
import com.mytv.mymusic.ui.player.stage.modes.ringsMinGap
import com.mytv.mymusic.ui.player.stage.modes.ringsSwell
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class Rings52HzTest {
    /** 测试用的字宽：宽字一个字号，其余 0.55 个，空格 0.3 个。 */
    private fun advance(size: Float): (String) -> Float = { c ->
        when {
            isWideChar(c) -> size
            c.isBlank() -> size * 0.3f
            else -> size * 0.55f
        }
    }

    private fun layout(text: String, maxWidth: Float = 800f) =
        layoutRingsLine(text, advance(60f), 60f, 500f, 400f, maxWidth)

    @Test
    fun wideCharsFollowTheOriginalRanges() {
        assertTrue(isWideChar("穿"))
        assertTrue(isWideChar("가"))
        assertTrue(isWideChar("，"))
        assertFalse(isWideChar("a"))
        assertFalse(isWideChar(" "))
        assertFalse(isWideChar("𠀀")) // 增补平面，JS 的正则也不认
    }

    @Test
    fun scatterIsSeededByTheText() {
        val a = layout("穿过晚风与星河")
        val b = layout("穿过晚风与星河")
        assertEquals(7, a.glyphs.size)
        a.glyphs.zip(b.glyphs).forEach { (x, y) ->
            assertEquals(x.x, y.x, 0f)
            assertEquals(x.y, y.y, 0f)
            assertEquals(x.rotation, y.rotation, 0f)
            assertEquals(x.scale, y.scale, 0f)
        }
        val other = layout("穿过晚风与星海")
        assertTrue(a.glyphs.zip(other.glyphs).any { (x, y) -> x.rotation != y.rotation })
    }

    @Test
    fun wideGlyphsScatterWithinTheOriginalBounds() {
        val l = layout("穿过晚风与星河")
        for (g in l.glyphs) {
            assertTrue(abs(g.rotation) <= 0.075f + 1e-4f)
            assertTrue(g.scale in 0.93f..1.07f)
            assertTrue(abs(g.y - 400f) <= 0.11f * l.size + 1e-3f)
        }
        // 行宽居中：首尾字心关于中心大致对称（错位最多 0.04 + 0.035 倍字号）
        val mid = (l.glyphs.first().x + l.glyphs.last().x) / 2f
        assertEquals(500f, mid, 0.08f * l.size)
        // 宽字从左到右排
        assertTrue(l.glyphs.zipWithNext().all { (a, b) -> b.x > a.x })
    }

    @Test
    fun latinLettersMoveAsWords() {
        val l = layout("hello world")
        assertEquals(10, l.glyphs.size)
        val first = l.glyphs.filter { it.word == l.glyphs.first().word }
        assertEquals(5, first.size)
        // 同一个词的字母只带两成抖动：旋转差不超过 2 × 0.015
        val spread = first.maxOf { it.rotation } - first.minOf { it.rotation }
        assertTrue(spread <= 0.03f + 1e-4f)
        assertEquals(1, ringRects(l).count { it.row == 0 && it.cx < 500f })
        assertEquals(2, ringRects(l).size)
    }

    @Test
    fun longLinesWrapToThreeRowsAndShrink() {
        val text = "我们头发里的风后来干了".repeat(4)
        val l = layout(text, maxWidth = 600f)
        assertTrue(l.rowCount <= 3)
        assertTrue(l.size < 60f)
        assertEquals(text.length, l.glyphs.size)
        // 行距 1.34 倍字号，整块上下居中
        val rows = l.glyphs.groupBy { it.row }.mapValues { (_, g) -> g.map { it.y }.average() }
        assertEquals(400.0, rows.values.average(), 0.15 * l.size)
    }

    @Test
    fun rectsMergeWithinRowsUpToTheCap() {
        val rects = (0 until 30).map { RingRect(it * 10f + (it / 10) * 0f, (it / 10) * 50f, 4f, 10f, it / 10) }
        val merged = mergeRingRects(rects, 12)
        assertTrue(merged.size <= 12)
        assertTrue(merged.size > 3)
        // 每个原矩形都被某个合并后的矩形包住，且不跨行
        for (r in rects) {
            assertTrue(merged.any { m ->
                m.row == r.row && r.cx - r.hw >= m.cx - m.hw - 1e-3f && r.cx + r.hw <= m.cx + m.hw + 1e-3f &&
                    r.cy - r.hh >= m.cy - m.hh - 1e-3f && r.cy + r.hh <= m.cy + m.hh + 1e-3f
            })
        }
        assertEquals(3, mergeRingRects(rects, 3).size)
        assertEquals(rects, mergeRingRects(rects, 64))
    }

    @Test
    fun pithIsReproducibleAndStaysNearTheCenter() {
        val area = Rect(0f, 0f, 1000f, 2000f)
        val seed = pithSeed(12345L, 3)
        val a = pithOutline(seed, area)
        assertArrayEquals(a, pithOutline(seed, area), 0f)
        assertFalse(a.contentEquals(pithOutline(pithSeed(12345L, 4), area)))
        val c = pithCenter(seed, area)
        assertTrue(abs(c[0] - 500f) <= 140f + 1e-3f && abs(c[1] - 1000f) <= 280f + 1e-3f)
        // 半径不超过短边 7% × 拉伸 1.35 × 谐波最大的 (1 + Σ 0.32/√k)
        for (i in 0 until a.size / 2) {
            assertTrue(hypot(a[i * 2] - c[0], a[i * 2 + 1] - c[1]) < 1000f * 0.07f * 1.35f * 2f)
        }
    }

    @Test
    fun offsetOutlineGrowsTowardACircle() {
        val area = Rect(0f, 0f, 1000f, 1000f)
        val seed = pithSeed(7L, 0)
        val points = pithOutline(seed, area)
        val c = pithCenter(seed, area)
        val out = FloatArray(96 * 2)
        offsetOutline(points, c[0], c[1], 0f, 96, out)
        val r0 = (0 until 96).map { hypot(out[it * 2] - c[0], out[it * 2 + 1] - c[1]) }
        assertTrue(r0.all { it > 0f })
        offsetOutline(points, c[0], c[1], 400f, 96, out)
        val big = (0 until 96).map { hypot(out[it * 2] - c[0], out[it * 2 + 1] - c[1]) }
        // 外扩后的半径在“这个方向的原半径 + 400”和“最大原半径 + 400”之间，越扩越接近圆
        val widest = r0.max()
        big.zip(r0).forEach { (b, s) -> assertTrue(b >= s + 400f - 1f && b <= widest + 400f + 1f) }
        assertTrue(big.max() / big.min() < r0.max() / r0.min())
    }

    @Test
    fun beatGateFiltersDenseKicksAndFillsSilence() {
        val gate = RingsBeatGate()
        assertTrue(gate.step(0.0, 1, 1f))
        assertFalse(gate.step(0.2, 1, 1f)) // 0.28 秒内的第二拍不算
        assertTrue(gate.step(0.3, 1, 1f))
        assertFalse(gate.step(1.5, 0, 1f))
        assertTrue(gate.step(2.31, 0, 1f)) // 2 秒没拍补一拍
        assertEquals(0.7f, ringsMinGap(0.4f), 1e-4f)
        assertEquals(0.28f, ringsMinGap(2.5f), 1e-4f)
        assertEquals(2f, ringsFillerGap(0.4f), 1e-4f)
        assertEquals(0.8f, ringsFillerGap(2.5f), 1e-4f)
        val sparse = RingsBeatGate()
        assertTrue(sparse.step(0.0, 1, 0.4f))
        assertFalse(sparse.step(0.5, 1, 0.4f))
        assertTrue(sparse.step(0.71, 1, 0.4f))
    }

    @Test
    fun energyFollowerKeepsAFloorAndRisesWithLoudness() {
        val e = RingsEnergy()
        var quiet = 0f
        repeat(60) { quiet = e.step(0f, 0f, 1f, 1 / 60f) }
        assertEquals(0.06f, quiet, 1e-3f)
        var loud = 0f
        repeat(30) { loud = e.step(0.8f, 0.9f, 1f, 1 / 60f) }
        assertTrue(loud > 0.8f && loud <= 1f)
        val soft = RingsEnergy()
        var low = 0f
        repeat(30) { low = soft.step(0.8f, 0.9f, 0.3f, 1 / 60f) }
        assertTrue(low < loud)
    }

    @Test
    fun ringVisualsFollowTheOriginalCurves() {
        assertEquals(0.5f, ringHalfWidthDp(0f), 1e-4f)
        assertEquals(2.4f, ringHalfWidthDp(1f), 1e-4f)
        assertEquals(1f, ringFade(0f, 500f), 1e-4f)
        assertEquals(0f, ringFade(500f, 500f), 1e-4f)
        assertTrue(ringFade(250f, 500f) in 0.25f..0.3f)
        assertEquals(0f, ringAlpha(0f, 0.8f), 1e-4f)
        assertTrue(ringAlpha(10f, 0.8f) in 0.79f..0.8f)
        assertEquals(1f, ringWindow(5.0, 0.0, 1e9), 1e-4f)
        assertTrue(ringWindow(0.05, 0.0, 1e9) < 0.2f)
        assertTrue(ringWindow(9.95, 0.0, 10.0) < 0.3f)
        assertEquals(0f, ringsLightProgress(1000, 1000, 2000), 0f)
        assertEquals(1f, ringsLightProgress(1450, 1000, 2000), 0f) // 用时夹到 0.45 秒
        assertEquals(0.5f, ringsLightProgress(1060, 1000, 1010), 1e-4f) // 至少 0.12 秒
        assertEquals(1f, easeOutCubic(1f), 0f)
        assertEquals(0f, ringsSwell(0f), 0f)
        assertEquals(0f, ringsSwell(1f), 0f)
        assertEquals(0.12f, ringsSwell(0.5f), 1e-4f)
    }

    private fun lineShape() = rectShape(listOf(RingRect(500f, 400f, 30f, 25f, 0), RingRect(560f, 400f, 30f, 25f, 0)), listOf(RingRect(530f, 400f, 60f, 25f, 0)), 60f)

    @Test
    fun recordCapsRingsAndFadesTheOldest() {
        val record = RingsRecord()
        record.switchSource("line:0", lineShape(), 0.0)
        for (i in 0 until 6) record.addRing(i * 0.5, 0.5f, RING_CAP_FALLBACK)
        assertEquals(RING_CAP_FALLBACK, record.rings.count { it.evictedAt.isNaN() })
        assertEquals(6, record.rings.size)
        record.prune(3.0, 10f, 1000f)
        assertEquals(RING_CAP_FALLBACK, record.rings.size) // 挤掉的 0.35 秒后删掉
        record.prune(200.0, 10f, 1000f)
        assertTrue(record.rings.isEmpty()) // 走出淡出距离
    }

    @Test
    fun switchingSourcesKeepsOldRingsOnTheirShape() {
        val record = RingsRecord()
        record.switchSource("line:0", lineShape(), 0.0)
        record.addRing(0.5, 0.5f, 24)
        record.switchSource("line:1", lineShape(), 2.0)
        record.addRing(2.5, 0.5f, 24)
        assertEquals(2, record.rings.map { it.slot }.distinct().size)
        assertEquals("line:1", record.openKey)
        val first = record.slots.first { it.key == "line:0" }
        assertEquals(2.0, first.end, 0.0)
        // 停发（句间）后旧槽位的年轮还在
        record.switchSource(null, null, 3.0)
        assertEquals(null, record.openKey)
        record.prune(3.0, 10f, 1000f)
        assertEquals(2, record.rings.size)
        // 跳转：清空，只留正在发的
        record.switchSource("line:2", lineShape(), 4.0)
        record.reset(10.0)
        assertTrue(record.rings.isEmpty())
        assertEquals(1, record.slots.count { it.key != null })
        assertEquals(10.0, record.slots.first { it.key == "line:2" }.start, 0.0)
    }

    @Test
    fun staticRecordBeatsEvery072Seconds() {
        val record = RingsRecord()
        record.switchSource("line:0", lineShape(), 30.0, always = true)
        record.seedStatic(30.0, 50f, 500f, 24)
        assertEquals(14, record.rings.size) // 0.72 × 50 = 36px 一圈，500px 内 14 圈
        record.rings.zipWithNext().forEach { (a, b) -> assertEquals(STATIC_BEAT_S, b.birth - a.birth, 1e-9) }
        assertTrue(record.rings.all { it.energy in 0.2f..0.65f })
    }

    @Test
    fun uniformsGroupRingsBySlotOldestFirst() {
        val record = RingsRecord()
        record.switchSource("line:0", lineShape(), 0.0)
        record.addRing(0.5, 0.4f, 24)
        record.addRing(1.0, 0.6f, 24)
        record.switchSource("pith:0", pithShape(pithOutline(1L, Rect(0f, 0f, 1000f, 800f)), pithCenter(1L, Rect(0f, 0f, 1000f, 800f))), 2.0)
        record.addRing(2.5, 0.9f, 24)
        val u = RingUniforms()
        packRingUniforms(record, 3.0, 100f, 2f, 0.3f, u)
        // 第 0 槽 2 个矩形，第 1 槽髓心 24 条边
        assertEquals(2f, u.slotEnd[0], 0f)
        assertEquals(26f, u.slotEnd[1], 0f)
        assertEquals(26, u.elemCount)
        assertEquals(SHAPE_POLYGON.toFloat(), u.slotKind[1], 0f)
        assertEquals(-1f, u.slotKind[2], 0f)
        // 年轮：第 0 槽两圈（外圈在前），第 1 槽一圈加一个“现在”
        assertEquals(2f, u.ringEnd[0], 0f)
        assertEquals(4f, u.ringEnd[1], 0f)
        assertEquals(4f, u.ringEnd[3], 0f)
        assertEquals(250f, u.ring[0], 1e-3f)
        assertEquals(200f, u.ring[4], 1e-3f)
        assertEquals(50f, u.ring[8], 1e-3f)
        assertEquals(0f, u.ring[12], 0f)
        assertTrue(u.ring[15] > 0f) // 当前响度的底色
        assertTrue(u.slotMax[0] > 250f)
    }

    @Test
    fun elementBudgetPrefersTheNewestSource() {
        assertArrayEquals(booleanArrayOf(true, true), planFine(intArrayOf(24, 24), intArrayOf(3, 3), SHADER_ELEMENTS))
        assertArrayEquals(booleanArrayOf(true, true, false), planFine(intArrayOf(24, 24, 24), intArrayOf(3, 3, 3), SHADER_ELEMENTS))
        assertArrayEquals(booleanArrayOf(true, false, false, false), planFine(intArrayOf(40, 30, 24, 24), intArrayOf(3, 3, 12, 3), SHADER_ELEMENTS))
    }
}
