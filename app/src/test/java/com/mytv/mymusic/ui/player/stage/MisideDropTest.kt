package com.mytv.mymusic.ui.player.stage

import com.mytv.mymusic.ui.player.stage.modes.MISIDE_EXTRUDE_LAYERS
import com.mytv.mymusic.ui.player.stage.modes.MISIDE_HURRY_VY
import com.mytv.mymusic.ui.player.stage.modes.MISIDE_STAGGER_S
import com.mytv.mymusic.ui.player.stage.modes.MisideChar
import com.mytv.mymusic.ui.player.stage.modes.MisideConfig
import com.mytv.mymusic.ui.player.stage.modes.MisideDrop
import com.mytv.mymusic.ui.player.stage.modes.MisideDropVisualizer
import com.mytv.mymusic.ui.player.stage.modes.MisideField
import com.mytv.mymusic.ui.player.stage.modes.MisideLine
import com.mytv.mymusic.ui.player.stage.modes.MisideParticle
import com.mytv.mymusic.ui.player.stage.modes.formatMisideDecimal
import com.mytv.mymusic.ui.player.stage.modes.integrateMiside
import com.mytv.mymusic.ui.player.stage.modes.misideExtrudeGray
import com.mytv.mymusic.ui.player.stage.modes.misideExtrudeOffset
import com.mytv.mymusic.ui.player.stage.modes.misideTilt
import com.mytv.mymusic.ui.player.stage.modes.spawnMisideParticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class MisideDropTest {
    private val cfg = MisideConfig(word = false, fontSize = 56f, gravity = 1400f, elasticity = 0.34f, settleHold = 2.5f, keepRows = 2, glowBoost = true)
    private val wordCfg = MisideConfig(word = true, fontSize = 56f, gravity = 1400f, elasticity = 0.34f, settleHold = 2.5f, keepRows = 2, glowBoost = true)

    private fun particle(row: Int = 0, y: Float = 100f, vy: Float = 0f, rest: Float = 0.34f, vrx: Float = 0f, g: Float = 1400f) =
        MisideParticle(Unit, row, x = 0f, y = y, w = 40f, h = 50f, vx = 0f, vy = vy, vrx = vrx, vry = 0f, vrz = 0f, g = g, rest = rest, delay = 0f)

    private fun line(index: Int, endMs: Long, vararg starts: Long) =
        MisideLine(index, endMs, starts.mapIndexed { i, s -> MisideChar(Unit, s, x = 100f + i * 40f, y = 300f, w = 40f, h = 50f) })

    @Test
    fun fallsBouncesAndSettlesOnTheGround() {
        val p = particle()
        var bounces = 0
        var lastVy = p.vy
        var steps = 0
        while (!p.settled && steps < 600) {
            integrateMiside(p, 1f / 60f, groundY = 600f, unit = 1f)
            if (lastVy > 0f && p.vy < 0f) bounces++
            lastVy = p.vy
            steps++
        }
        assertTrue(p.settled)
        assertTrue("至少弹一下", bounces >= 1)
        assertEquals(550f, p.y, 1e-3f)
        assertEquals(0f, p.vx, 0f)
        assertEquals(0f, p.vy, 0f)
    }

    @Test
    fun bounceHeightShrinksWithElasticity() {
        val p = particle(rest = 0.5f)
        var peakAfterBounce = Float.MAX_VALUE
        var bounced = false
        repeat(600) {
            integrateMiside(p, 1f / 120f, groundY = 600f, unit = 1f)
            if (p.vy < 0f) bounced = true
            if (bounced) peakAfterBounce = minOf(peakAfterBounce, p.y)
        }
        // 从 100 落下 450px，回弹 0.5 倍速度只能弹回四分之一高度左右
        assertTrue(peakAfterBounce > 550f - 450f * 0.3f)
        assertTrue(p.settled)
    }

    @Test
    fun zeroElasticitySettlesRightAway() {
        val p = particle(rest = 0f, y = 540f, vy = 300f)
        repeat(3) { integrateMiside(p, 1f / 60f, groundY = 600f, unit = 1f) }
        assertTrue(p.settled)
    }

    @Test
    fun tiltStaysWithinTheLimitAndClearsOnLanding() {
        val p = particle(y = -4000f, vrx = 300f)
        var maxTilt = 0f
        while (!p.settled) {
            integrateMiside(p, 1f / 60f, groundY = 600f, unit = 1f)
            maxTilt = maxOf(maxTilt, abs(p.rx))
        }
        assertTrue(maxTilt <= 16f + 1e-3f)
        assertTrue("碰到上限后反向", maxTilt >= 15.9f)
        assertEquals(0f, p.rx, 0f)
        assertEquals(0f, p.ry, 0f)
    }

    @Test
    fun pausedFrameChangesNothing() {
        val field = MisideField<Unit>()
        val p = particle()
        p.delay = 0.3f
        field.particles += p
        field.step(0f, groundY = 600f, stageHeight = 800f, unit = 1f, settleHold = 2.5f)
        assertEquals(100f, p.y, 0f)
        assertEquals(0.3f, p.delay, 0f)
    }

    @Test
    fun delayedParticleWaitsThenFalls() {
        val field = MisideField<Unit>()
        val p = particle()
        p.delay = 0.1f
        field.particles += p
        field.step(0.05f, groundY = 600f, stageHeight = 800f, unit = 1f, settleHold = 2.5f)
        assertEquals(100f, p.y, 0f)
        field.step(0.1f, groundY = 600f, stageHeight = 800f, unit = 1f, settleHold = 2.5f)
        assertTrue(p.y > 100f)
    }

    @Test
    fun settledHoldsThenFadesThenGoes() {
        val field = MisideField<Unit>()
        val p = particle(y = 550f)
        p.settled = true
        field.particles += p
        repeat(24) { field.step(0.1f, 600f, 800f, 1f, settleHold = 2.5f) }
        assertEquals(1f, p.fade, 0f)
        repeat(9) { field.step(0.1f, 600f, 800f, 1f, settleHold = 2.5f) }
        assertEquals(0.5f, p.fade, 0.05f)
        repeat(10) { field.step(0.1f, 600f, 800f, 1f, settleHold = 2.5f) }
        assertTrue(field.particles.isEmpty())
    }

    @Test
    fun pruneKeepsTheNewestRowsAndNeverTouchesAirborne() {
        val field = MisideField<Unit>()
        listOf(1, 1, 2, 3).forEach { row -> field.particles += particle(row = row).also { it.settled = true } }
        field.particles += particle(row = 4)
        field.prune(2)
        assertEquals(listOf(3, 4), field.particles.map { it.row })
        field.prune(0)
        assertEquals(listOf(4), field.particles.map { it.row })

        // 同一句的字连着排时只算一行
        val rows = MisideField<Unit>()
        listOf(3, 4, 4, 5).forEach { row -> rows.particles += particle(row = row).also { it.settled = true } }
        rows.prune(2)
        assertEquals(listOf(4, 4, 5), rows.particles.map { it.row })
        rows.prune(8)
        assertEquals(listOf(4, 4, 5), rows.particles.map { it.row })
    }

    @Test
    fun extrusionDarkensLayerByLayer() {
        val grays = (0 until MISIDE_EXTRUDE_LAYERS).map(::misideExtrudeGray)
        assertEquals(168, grays.first())
        assertEquals(64, grays.last())
        assertTrue(grays.zipWithNext().all { (a, b) -> b < a })
        assertEquals(7f * 1.5f, misideExtrudeOffset(6, 1.5f), 1e-4f)
        assertEquals(1.5f, misideExtrudeOffset(0, 1.5f), 1e-4f)
    }

    @Test
    fun tiltIsAnOrthographicProjection() {
        val m = FloatArray(4)
        misideTilt(0f, 0f, 0f, m)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), m)
        misideTilt(60f, 0f, 0f, m)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0.5f), m)
        misideTilt(0f, 0f, 90f, m)
        assertArrayEquals(floatArrayOf(0f, -1f, 1f, 0f), m)
        // 面积缩放 = cos(rx)·cos(ry)：翻得越多厚度越扁
        misideTilt(16f, -12f, 37f, m)
        val det = m[0] * m[3] - m[1] * m[2]
        assertEquals(cos(Math.toRadians(16.0)) * cos(Math.toRadians(-12.0)), det.toDouble(), 1e-5)
    }

    @Test
    fun sentenceModeTypesThenDropsTheWholeLine() {
        val drop = MisideDrop<Unit>(seed = 7L)
        val a = line(0, endMs = 1000, 0, 100, 200)
        drop.advance(a, 50, 1f / 60f, cfg, 600f, 800f, 1f)
        assertTrue(drop.typed(0, 50))
        assertFalse(drop.typed(1, 50))
        assertTrue(drop.field.particles.isEmpty())
        drop.advance(a, 999, 1f / 60f, cfg, 600f, 800f, 1f)
        assertTrue(drop.field.particles.isEmpty())
        drop.advance(a, 1000, 0f, cfg, 600f, 800f, 1f)
        assertEquals(3, drop.field.particles.size)
        assertEquals(listOf(0f, MISIDE_STAGGER_S, 2 * MISIDE_STAGGER_S), drop.field.particles.map { it.delay })
        assertFalse((0..2).any { drop.typed(it, 1000) })
        // 整句一起落时横向散开收着
        assertTrue(drop.field.particles.all { abs(it.vx) <= 170f * 0.3f / 2f })
    }

    @Test
    fun wordModeDropsEachCharWhenSung() {
        val drop = MisideDrop<Unit>(seed = 7L)
        val a = line(0, endMs = 1000, 0, 100, 200)
        drop.advance(a, 150, 1f / 60f, wordCfg, 600f, 800f, 1f)
        assertEquals(2, drop.field.particles.size)
        assertFalse(drop.typed(0, 150))
        assertFalse(drop.typed(2, 150))
        drop.advance(a, 250, 1f / 60f, wordCfg, 600f, 800f, 1f)
        assertEquals(3, drop.field.particles.size)
    }

    @Test
    fun lineChangeHurriesAirborneAndDropsTheRest() {
        val drop = MisideDrop<Unit>(seed = 7L)
        val a = line(0, endMs = 1000, 0, 100, 200)
        val b = line(1, endMs = 2000, 1000, 1100)
        drop.advance(a, 150, 1f / 60f, wordCfg, 600f, 800f, 1f)
        val airborne = drop.field.particles.toList()
        drop.advance(b, 600, 1f / 60f, wordCfg, 600f, 800f, 1f)
        assertTrue(airborne.all { it.vy >= MISIDE_HURRY_VY })
        // 第三个字没唱到也跟着整行落
        assertEquals(3, drop.field.particles.count { it.row == 0 })
        assertEquals(1, drop.shown?.index)
    }

    @Test
    fun jumpClearsTheGroundAndRestartsFromTheCurrentLine() {
        val drop = MisideDrop<Unit>(seed = 7L)
        val a = line(0, endMs = 1000, 0, 100, 200)
        drop.advance(a, 1000, 1f / 60f, cfg, 600f, 800f, 1f)
        assertEquals(3, drop.field.particles.size)
        val c = line(5, endMs = 9000, 8000, 8200, 8400)
        drop.advance(c, 8300, 1f / 60f, wordCfg, 600f, 800f, 1f)
        // 跳进一句中间：已经唱过的字不补掉，没唱到的照常
        assertTrue(drop.field.particles.isEmpty())
        assertFalse(drop.typed(0, 8300))
        drop.advance(c, 8420, 1f / 60f, wordCfg, 600f, 800f, 1f)
        assertEquals(1, drop.field.particles.size)
        // 往回跳也清
        drop.advance(c, 6000, 1f / 60f, wordCfg, 600f, 800f, 1f)
        assertTrue(drop.field.particles.isEmpty())
    }

    @Test
    fun relayoutOfTheSameLineKeepsDroppedChars() {
        val drop = MisideDrop<Unit>(seed = 7L)
        drop.advance(line(0, endMs = 1000, 0, 100, 200), 150, 1f / 60f, wordCfg, 600f, 800f, 1f)
        drop.advance(line(0, endMs = 1000, 0, 100, 200), 160, 1f / 60f, wordCfg, 600f, 800f, 1f)
        assertEquals(2, drop.field.particles.size)
    }

    @Test
    fun sameSeedSameFall() {
        fun velocities(seed: Long): List<Float> {
            val drop = MisideDrop<Unit>(seed)
            drop.advance(line(3, endMs = 1000, 0, 100, 200), 1000, 0f, cfg, 600f, 800f, 1f)
            return drop.field.particles.flatMap { listOf(it.vx, it.vy, it.vrz, it.g, it.rest) }
        }
        assertEquals(velocities(42L), velocities(42L))
        assertTrue(velocities(42L) != velocities(43L))
    }

    @Test
    fun spawnFollowsTheOriginalRanges() {
        val random = StageRandom(99L)
        repeat(200) {
            val p = spawnMisideParticle(Unit, 0, 0f, 0f, 10f, 10f, wordCfg, unit = 2f, delay = 0f, random = random)
            assertTrue(abs(p.vx) <= 85f * 2f)
            assertTrue(p.vy in -140f * 2f..-60f * 2f)
            assertTrue(abs(p.vrz) <= 150f)
            assertTrue(p.g in 1400f * 0.88f * 2f..1400f * 1.12f * 2f)
            assertTrue(p.rest in 0.34f * 0.85f..0.34f * 1.1f)
        }
    }

    @Test
    fun staticSettleLeavesEveryoneOnTheGround() {
        val field = MisideField<Unit>()
        val random = StageRandom(5L)
        val bouncy = MisideConfig(word = true, fontSize = 56f, gravity = 300f, elasticity = 0.85f, settleHold = 2.5f, keepRows = 2, glowBoost = true)
        repeat(6) { field.particles += spawnMisideParticle(Unit, 0, it * 40f, 100f, 40f, 50f, bouncy, 1f, it * MISIDE_STAGGER_S, random) }
        field.settleAll(groundY = 600f, stageHeight = 800f, unit = 1f)
        assertTrue(field.particles.all { it.settled && it.y == 550f && it.fade == 1f })
    }

    @Test
    fun settingsMatchThePanel() {
        val v = MisideDropVisualizer
        assertEquals("miside", v.id)
        assertEquals(7, v.hiddenSettings)
        assertEquals(listOf("dropModeV2", "fontSizeV2", "gravity", "elasticity", "settleHold", "keepRows", "glowBoost"), v.settings.map { it.key })
        val values = SettingValues(v.settings, emptyMap())
        assertEquals("sentence", values.choice("dropModeV2"))
        assertEquals(1400f, values.number("gravity"), 0f)
        assertEquals("0.34", formatMisideDecimal(0.34f))
        assertEquals("0.4", formatMisideDecimal(0.4f))
        assertEquals("0", formatMisideDecimal(0f))
        assertEquals("0.85", formatMisideDecimal(0.85f))
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("[$i]", expected[i], actual[i], 1e-5f)
    }
}
