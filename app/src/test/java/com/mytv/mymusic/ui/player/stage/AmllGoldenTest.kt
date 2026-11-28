package com.mytv.mymusic.ui.player.stage

import com.mytv.mymusic.ui.player.stage.modes.AMLL_MESH_PRESETS
import com.mytv.mymusic.ui.player.stage.modes.AmllDots
import com.mytv.mymusic.ui.player.stage.modes.AmllMeshPreset
import com.mytv.mymusic.ui.player.stage.modes.AmllSpring
import com.mytv.mymusic.ui.player.stage.modes.AmllWord
import com.mytv.mymusic.ui.player.stage.modes.amllChunkWords
import com.mytv.mymusic.ui.player.stage.modes.amllEmphasis
import com.mytv.mymusic.ui.player.stage.modes.amllEmphasisCurve
import com.mytv.mymusic.ui.player.stage.modes.amllEmphasisFloat
import com.mytv.mymusic.ui.player.stage.modes.amllGenerateControlPoints
import com.mytv.mymusic.ui.player.stage.modes.amllKeyframed
import com.mytv.mymusic.ui.player.stage.modes.amllMask
import com.mytv.mymusic.ui.player.stage.modes.amllMeshGeometry
import com.mytv.mymusic.ui.player.stage.modes.amllPickPreset
import com.mytv.mymusic.ui.player.stage.modes.amllPosYPolicy
import com.mytv.mymusic.ui.player.stage.modes.amllProcessCover
import com.mytv.mymusic.ui.player.stage.modes.amllStaggerDelays
import com.mytv.mymusic.ui.player.stage.modes.amllSubdivisions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AMLL 的金样本：AMLL core 0.7.2 的 TS 源码照抄成 JS，在 Node 里跑出 [AmllGolden]，这里逐个对照 Kotlin 改写。
 * Double 算的部分（弹簧、错峰、网格、图片处理）要求几乎逐位一致；用 Compose 贝塞尔（Float）的部分放宽到 1e-3 以内。
 */
class AmllGoldenTest {

    // ---- 弹簧 ----

    private fun runSpring(start: Double, stiffness: Double, damping: Double, mass: Double, steps: Int, actions: Map<Int, (AmllSpring) -> Unit>): Pair<DoubleArray, Int> {
        val spring = AmllSpring(start)
        spring.setParams(stiffness = stiffness, damping = damping, mass = mass)
        val out = DoubleArray(steps)
        var arrivedAt = -1
        for (k in 0 until steps) {
            actions[k]?.invoke(spring)
            spring.update(0.016)
            out[k] = spring.position
            if (arrivedAt < 0 && spring.arrived()) arrivedAt = k
        }
        return out to arrivedAt
    }

    private fun assertSpring(expected: DoubleArray, arrived: Int, actual: Pair<DoubleArray, Int>) {
        assertArrayEquals(expected, actual.first, 1e-9)
        assertEquals(arrived, actual.second)
    }

    @Test
    fun springsFollowAmll() {
        assertSpring(AmllGolden.spring1, AmllGolden.spring1Arrived, runSpring(0.0, 90.0, 15.0, 0.9, 60, mapOf(0 to { s -> s.setTarget(100.0) })))
        val p300 = amllPosYPolicy(seeking = false, interlude = false, intervalMs = 300.0)
        assertSpring(
            AmllGolden.spring2,
            AmllGolden.spring2Arrived,
            runSpring(0.0, p300.stiffness, p300.damping, 0.9, 60, mapOf(0 to { s -> s.setTarget(-250.0, 0.1) })),
        )
        assertSpring(
            AmllGolden.spring3,
            AmllGolden.spring3Arrived,
            runSpring(0.0, 90.0, 15.0, 0.9, 60, mapOf(0 to { s -> s.setTarget(100.0) }, 12 to { s -> s.setTarget(40.0) })),
        )
        assertSpring(AmllGolden.spring4, AmllGolden.spring4Arrived, runSpring(100.0, 100.0, 25.0, 2.0, 80, mapOf(0 to { s -> s.setTarget(97.0, 0.05) })))
        assertSpring(AmllGolden.spring5, AmllGolden.spring5Arrived, runSpring(-80.0, 90.0, 15.0, 0.9, 120, mapOf(0 to { s -> s.setTarget(0.0) })))
        assertSpring(AmllGolden.spring6, AmllGolden.spring6Arrived, runSpring(75.0, 50.0, 20.0, 1.0, 120, mapOf(0 to { s -> s.setTarget(100.0) })))
    }

    @Test
    fun posYPolicyFollowsAmll() {
        AmllGolden.policyIntervals.forEachIndexed { i, interval ->
            val policy = amllPosYPolicy(false, false, if (interval < 0) null else interval)
            assertEquals("interval $interval", AmllGolden.policy[i][0], policy.stiffness, 1e-9)
            assertEquals("interval $interval", AmllGolden.policy[i][1], policy.damping, 1e-9)
        }
        amllPosYPolicy(seeking = true, interlude = false, intervalMs = 300.0).let {
            assertArrayEquals(AmllGolden.policySeek, doubleArrayOf(it.stiffness, it.damping), 1e-9)
        }
        amllPosYPolicy(seeking = false, interlude = true, intervalMs = 300.0).let {
            assertArrayEquals(AmllGolden.policyInterlude, doubleArrayOf(it.stiffness, it.damping), 1e-9)
        }
        amllPosYPolicy(seeking = false, interlude = false, intervalMs = 300.0, endOfSong = true).let {
            assertArrayEquals(AmllGolden.policyEnd, doubleArrayOf(it.stiffness, it.damping), 1e-9)
        }
    }

    @Test
    fun staggerFollowsAmll() {
        val ys1 = doubleArrayOf(-300.0, -200.0, -100.0, 0.0, 80.0, 160.0, 240.0, 320.0, 400.0, 480.0)
        assertArrayEquals(AmllGolden.stagger1, amllStaggerDelays(ys1, DoubleArray(10) { 80.0 }, 10, 4, false), 1e-12)
        val ys2 = doubleArrayOf(-50.0, 10.0, 95.0, 170.0, 300.0, 410.0, 560.0, 900.0, 1500.0, 2400.0, 3000.0)
        val h2 = doubleArrayOf(70.0, 85.0, 75.0, 130.0, 110.0, 150.0, 340.0, 600.0, 900.0, 600.0, 80.0)
        assertArrayEquals(AmllGolden.stagger2, amllStaggerDelays(ys2, h2, 11, 0, false), 1e-12)
        val ys3 = doubleArrayOf(-500.0, -420.0, -330.0, -250.0, -170.0, -60.0, 40.0)
        val h3 = doubleArrayOf(80.0, 90.0, 80.0, 80.0, 110.0, 100.0, 80.0)
        assertArrayEquals(AmllGolden.stagger3, amllStaggerDelays(ys3, h3, 7, 6, false), 1e-12)
        // 跳转、拖动时不错峰
        assertArrayEquals(DoubleArray(11), amllStaggerDelays(ys2, h2, 11, 0, true), 0.0)
    }

    // ---- 间奏三点 ----

    private fun assertDots(start: Double, end: Double, current: Double, force: Boolean, anchor: Int, ok: Boolean, times: DoubleArray, rows: Array<DoubleArray>) {
        val dots = AmllDots()
        assertEquals(ok, dots.setInterlude(start, end, current, force, anchor))
        times.forEachIndexed { k, t ->
            dots.syncClock(t)
            dots.update(0.0)
            val row = rows[k]
            val at = "t=$t"
            assertEquals(at, row[0], dots.opacity.toDouble(), 1e-3)
            assertEquals(at, row[1], dots.scale.toDouble(), 1e-3)
            for (d in 0 until 3) assertEquals("$at dot $d", row[2 + d], dots.dotOpacities[d].toDouble(), 1e-3)
        }
    }

    @Test
    fun interludeDotsFollowAmll() {
        assertDots(10000.0, 17800.0, 10000.0, false, 3, AmllGolden.dots1Ok, AmllGolden.dots1Times, AmllGolden.dots1)
        assertDots(0.0, 3500.0, 0.0, false, -1, AmllGolden.dots2Ok, AmllGolden.dots2Times, AmllGolden.dots2)
        assertDots(10000.0, 20000.0, 12000.0, true, 2, AmllGolden.dots3Ok, AmllGolden.dots3Times, AmllGolden.dots3)
        assertDots(0.0, 2000.0, 0.0, false, 0, AmllGolden.dots4Ok, AmllGolden.dots4Times, AmllGolden.dots4)
    }

    // ---- 长音强调 ----

    @Test
    fun emphasisParamsFollowAmll() {
        for (row in AmllGolden.emphasis) {
            val e = amllEmphasis(row[0], 0.0, 0.0, 2, lastWord = row[1] == 1.0)
            val at = "du=${row[0]} last=${row[1]}"
            assertEquals(at, row[2], e.amount, 1e-12)
            assertEquals(at, row[3], e.blur, 1e-12)
            assertEquals(at, row[4], e.durationMs, 1e-12)
        }
    }

    @Test
    fun emphasisCurvesFollowAmll() {
        AmllGolden.empCurve.forEachIndexed { j, v -> assertEquals("x=$j/32", v, amllEmphasisCurve(j / 32.0), 2e-4) }
        AmllGolden.keyframeXs.forEachIndexed { k, x ->
            assertEquals("glow x=$x", AmllGolden.glowSamples[k], amllKeyframed(x, ::amllEmphasisCurve), 2e-4)
            assertEquals("float x=$x", AmllGolden.floatSamples[k], amllKeyframed(x) { amllEmphasisFloat(it, false) }, 1e-12)
            assertEquals("bg float x=$x", AmllGolden.floatSamplesBg[k], amllKeyframed(x) { amllEmphasisFloat(it, true) }, 1e-12)
        }
    }

    // ---- 逐字扫光 ----

    @Test
    fun maskFollowsAmll() {
        val widths = doubleArrayOf(50.0, 30.0, 80.0, 20.0, 60.0)
        val starts = doubleArrayOf(1000.0, 1400.0, 1600.0, 2500.0, 3000.0)
        val ends = doubleArrayOf(1400.0, 1400.0, 2500.0, 2600.0, 4200.0)
        val fade = 15.0
        val mask = amllMask(widths, starts, ends, 900.0, 4200.0, fade)
        var before = 0.0
        widths.forEachIndexed { w, width ->
            AmllGolden.maskTimes.forEachIndexed { k, ms ->
                // AMLL 每个词各有一条遮罩关键帧（位置夹在 −(宽 + 羽化)..0）；myMusic 整行一根扫光轴，按词的左边换算后应当一致
                val actual = (mask.travelAt(ms) - before - 2 * fade).coerceIn(-fade, width)
                assertEquals("word $w at $ms", AmllGolden.mask[w][k], actual, 1e-9)
            }
            before += width
        }
        assertEquals(widths.sum() + fade * 2, mask.full, 1e-9)
    }

    // ---- 分词 ----

    @Test
    fun chunkingFollowsAmll() {
        val inputs = listOf(
            listOf(
                AmllWord("Life", 0.0, 500.0), AmllWord(" ", 500.0, 500.0), AmllWord("is", 500.0, 800.0), AmllWord(" a", 800.0, 1000.0),
                AmllWord(" su", 1000.0, 1300.0), AmllWord("gar so", 1300.0, 1900.0), AmllWord("sweet", 1900.0, 2600.0),
            ),
            listOf(AmllWord("晚安", 0.0, 1600.0), AmllWord("wind", 1600.0, 3000.0)),
            listOf(AmllWord("你好 世界", 0.0, 1000.0), AmllWord("ok", 1000.0, 1500.0)),
        )
        inputs.forEachIndexed { n, words ->
            val actual = amllChunkWords(words).map { group -> group.map { Triple(it.text, it.startMs, it.endMs) } }
            val expected = AmllGolden.chunks[n]
            assertEquals("input $n", expected.size, actual.size)
            expected.forEachIndexed { g, group ->
                assertEquals("input $n group $g", group.map { it.first }, actual[g].map { it.first })
                group.forEachIndexed { i, word ->
                    assertEquals("input $n group $g word $i", word.second, actual[g][i].second, 1e-9)
                    assertEquals("input $n group $g word $i", word.third, actual[g][i].third, 1e-9)
                }
            }
        }
    }

    // ---- 网格背景 ----

    @Test
    fun coverProcessingFollowsAmll() {
        val rgba = IntArray(32 * 32 * 4)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val i = (y * 32 + x) * 4
                rgba[i] = (x * 37 + y * 11) % 256
                rgba[i + 1] = (x * x + 3 * y * y) % 256
                rgba[i + 2] = (255 - x * 5 - y * 3 + 512) % 256
                rgba[i + 3] = 255
            }
        }
        amllProcessCover(rgba, 32, 32)
        for ((index, value) in AmllGolden.imageSamples.map { it[0] to it[1] }) assertEquals("byte $index", value, rgba[index])
        var hash = 0L
        for (v in rgba) hash = (hash * 31 + v) and 0xFFFFFFFFL
        assertEquals(AmllGolden.imageHash, hash)
    }

    @Test
    fun controlPointGenerationFollowsAmll() {
        val random = StageRandom(7)
        val preset = amllGenerateControlPoints(6, 6) { random.next().toDouble() }
        assertEquals(6, preset.width)
        assertEquals(6, preset.height)
        assertEquals(AmllGolden.generated.size, preset.points.size)
        preset.points.forEachIndexed { k, p ->
            assertArrayEquals("point $k", AmllGolden.generated[k], doubleArrayOf(p.x, p.y, p.ur, p.vr, p.up, p.vp), 1e-12)
        }
    }

    @Test
    fun presetPickFollowsAmll() {
        for (seed in 1..12) {
            val random = StageRandom(seed.toLong())
            val preset = amllPickPreset { random.next().toDouble() }
            assertEquals("seed $seed", AmllGolden.picks[seed - 1], AMLL_MESH_PRESETS.indexOf(preset))
        }
    }

    private fun assertMesh(preset: AmllMeshPreset, samples: Array<DoubleArray>) {
        val geometry = amllMeshGeometry(preset)
        val last = samples.last()
        assertEquals(last[0].toInt() + 1, geometry.columns)
        assertEquals(last[1].toInt() + 1, geometry.rows)
        assertEquals((preset.width - 1) * (amllSubdivisions(preset) - 1) + 1, geometry.columns)
        for (s in samples) {
            val index = s[1].toInt() * geometry.columns + s[0].toInt()
            assertEquals("x at ${s[0]},${s[1]}", s[2], geometry.x[index], 1e-9)
            assertEquals("y at ${s[0]},${s[1]}", s[3], geometry.y[index], 1e-9)
        }
    }

    @Test
    fun meshGeometryFollowsAmll() {
        assertMesh(AMLL_MESH_PRESETS[0], AmllGolden.mesh_preset0)
        assertMesh(AMLL_MESH_PRESETS[2], AmllGolden.mesh_preset2)
        assertMesh(AMLL_MESH_PRESETS[3], AmllGolden.mesh_preset3)
        val random = StageRandom(7)
        assertMesh(amllGenerateControlPoints(6, 6) { random.next().toDouble() }, AmllGolden.mesh_generated7)
    }
}
