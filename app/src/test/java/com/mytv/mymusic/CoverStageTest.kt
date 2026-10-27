package com.mytv.mymusic

import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.ui.player.CoverShapeSession
import com.mytv.mymusic.ui.player.MorphStep
import com.mytv.mymusic.ui.player.PausedCoverPolygon
import com.mytv.mymusic.ui.player.RandomCoverShapes
import com.mytv.mymusic.ui.player.advanceSpin
import com.mytv.mymusic.ui.player.coverPolygon
import com.mytv.mymusic.ui.player.fitForRotation
import com.mytv.mymusic.ui.player.planMorph
import com.mytv.mymusic.ui.player.polygon
import com.mytv.mymusic.ui.player.restingAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** 形状封面的纯逻辑：随机形状会话、旋转用的形状归一、形变路线、旋转角度。 */
class CoverStageTest {
    @Test
    fun sessionKeepsTheShapeWhenThePlayerReopensOnTheSameSong() {
        val session = CoverShapeSession(Random(1))
        val first = session.resolve(CoverShapeChoice.Random, "qq:1")
        repeat(20) { assertEquals(first, session.resolve(CoverShapeChoice.Random, "qq:1")) }
    }

    @Test
    fun sessionNeverRepeatsThePreviousSongsShape() {
        val session = CoverShapeSession(Random(26))
        var previous = session.resolve(CoverShapeChoice.Random, "song-0")
        val seen = mutableSetOf(previous)
        for (i in 1..500) {
            val next = session.resolve(CoverShapeChoice.Random, "song-$i")
            assertNotEquals(previous, next)
            assertNotEquals(CoverShapeChoice.Random, next)
            seen += next
            previous = next
        }
        assertEquals(RandomCoverShapes.toSet(), seen)
    }

    @Test
    fun fixedShapeWinsAndSwitchingBackToRandomKeepsItUntilTheNextSong() {
        val session = CoverShapeSession(Random(3))
        session.resolve(CoverShapeChoice.Random, "a")
        assertEquals(CoverShapeChoice.Sunny, session.resolve(CoverShapeChoice.Sunny, "a"))
        // 切回随机：这首保持太阳
        assertEquals(CoverShapeChoice.Sunny, session.resolve(CoverShapeChoice.Random, "a"))
        // 下一首才重新抽，且不和太阳重复
        repeat(50) { i ->
            val s = CoverShapeSession(Random(i))
            s.resolve(CoverShapeChoice.Sunny, "a")
            assertNotEquals(CoverShapeChoice.Sunny, s.resolve(CoverShapeChoice.Random, "b"))
        }
    }

    @Test
    fun fittedShapesAreCenteredInsideTheRotationCircle() {
        RandomCoverShapes.forEach { choice ->
            val fitted = fitForRotation(choice.polygon!!)
            assertEquals(choice.name, 0.5f, fitted.centerX, 1e-3f)
            assertEquals(choice.name, 0.5f, fitted.centerY, 1e-3f)
            var max = 0f
            fitted.cubics.forEach { c ->
                for (i in 0..16) {
                    val t = i / 16f
                    val u = 1 - t
                    val x = u * u * u * c.anchor0X + 3 * u * u * t * c.control0X + 3 * u * t * t * c.control1X + t * t * t * c.anchor1X
                    val y = u * u * u * c.anchor0Y + 3 * u * u * t * c.control0Y + 3 * u * t * t * c.control1Y + t * t * t * c.anchor1Y
                    max = maxOf(max, sqrt((x - 0.5f) * (x - 0.5f) + (y - 0.5f) * (y - 0.5f)))
                }
            }
            // 转到任何角度都不会被方形边界切掉，也不会缩得太小
            assertTrue("${choice.name} max=$max", max <= 0.501f)
            assertTrue("${choice.name} max=$max", max >= 0.49f)
        }
    }

    @Test
    fun everyMorphStartsAndEndsOnItsShapesAndStaysInTheFrameWhenOvershooting() {
        val paused = PausedCoverPolygon
        val circle = MaterialShapes.Circle
        RandomCoverShapes.forEach { choice ->
            val shape = coverPolygon(choice)
            assertSame("按选项缓存", shape, coverPolygon(choice))
            for ((from, to) in listOf(paused to shape, shape to paused, shape to circle, circle to shape)) {
                val morph = Morph(from, to)
                // 两端和起止形状的轮廓重合：暂停、继续、收成小圆的开头和结尾都不会跳一下
                assertSameOutline(choice.name, from.cubics, morph.asCubics(0f))
                assertSameOutline(choice.name, to.cubics, morph.asCubics(1f))
                for (progress in listOf(-0.08f, 0.5f, 1.08f)) {
                    val cubics = morph.asCubics(progress)
                    // 首尾相接，填充路径没有缺口
                    cubics.zipWithNext().forEach { (a, b) -> assertNear(choice.name, a.anchor1X, a.anchor1Y, b.anchor0X, b.anchor0Y) }
                    assertNear(choice.name, cubics.last().anchor1X, cubics.last().anchor1Y, cubics.first().anchor0X, cubics.first().anchor0Y)
                    // 弹簧过冲时外推的那一点不会冲出画框（遮罩外框是两倍大，这里留足余量）
                    samples(cubics, 4).forEach { (x, y) ->
                        assertTrue("$choice at $progress: ($x, $y)", x in -0.1f..1.1f && y in -0.1f..1.1f)
                    }
                }
            }
        }
        // 刚打开播放页时起点和终点是同一个形状
        assertSameOutline("paused", paused.cubics, Morph(paused, paused).asCubics(1f))
    }

    /** [actual] 的轮廓和 [expected] 重合：双向取样点到对方折线的最远距离都小于 0.002（单位是封面边长，大封面上不到 2px）。 */
    private fun assertSameOutline(name: String, expected: List<Cubic>, actual: List<Cubic>) {
        val a = samples(expected, 32)
        val b = samples(actual, 32)
        val ab = farthest(a, b)
        val ba = farthest(b, a)
        assertTrue("$name: outline differs $ab $ba", ab < 0.002f && ba < 0.002f)
    }

    /** [points] 里离 [line]（按顺序连成的折线）最远的那个点的距离。 */
    private fun farthest(points: List<Pair<Float, Float>>, line: List<Pair<Float, Float>>): Float = points.maxOf { (x, y) ->
        line.zipWithNext().minOf { (p, q) ->
            val dx = q.first - p.first
            val dy = q.second - p.second
            val t = (((x - p.first) * dx + (y - p.second) * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-12f)).coerceIn(0f, 1f)
            val ex = p.first + t * dx - x
            val ey = p.second + t * dy - y
            sqrt(ex * ex + ey * ey)
        }
    }

    private fun samples(cubics: List<Cubic>, perCubic: Int): List<Pair<Float, Float>> = cubics.flatMap { c ->
        (0..perCubic).map { i ->
            val t = i / perCubic.toFloat()
            val u = 1 - t
            val x = u * u * u * c.anchor0X + 3 * u * u * t * c.control0X + 3 * u * t * t * c.control1X + t * t * t * c.anchor1X
            val y = u * u * u * c.anchor0Y + 3 * u * u * t * c.control0Y + 3 * u * t * t * c.control1Y + t * t * t * c.anchor1Y
            x to y
        }
    }

    private fun assertNear(name: String, x: Float, y: Float, u: Float, v: Float) =
        assertTrue("$name: gap at ($x, $y) vs ($u, $v)", abs(x - u) < 1e-3f && abs(y - v) < 1e-3f)

    @Test
    fun morphPlanFollowsTheCurrentLegOrStartsFromWhereItIsHeading() {
        val a = coverPolygon(CoverShapeChoice.Cookie12)
        val b = coverPolygon(CoverShapeChoice.Sunny)
        val c = coverPolygon(CoverShapeChoice.Clover8)
        assertEquals(MorphStep.Forward, planMorph(a, b, 0.4f, 1f, b))
        // 暂停后马上继续：沿原路退回
        assertEquals(MorphStep.Backward, planMorph(a, b, 0.4f, 1f, a))
        // 停稳后换形状：直接从终点出发
        assertEquals(MorphStep.Restart(b, finishFirst = false), planMorph(a, b, 1f, 1f, c))
        assertEquals(MorphStep.Restart(a, finishFirst = false), planMorph(a, b, 0f, 0f, c))
        // 连按下一首：先把这一段走完再出发，画面不跳
        assertEquals(MorphStep.Restart(b, finishFirst = true), planMorph(a, b, 0.6f, 1f, c))
        assertEquals(MorphStep.Restart(a, finishFirst = true), planMorph(a, b, 0.6f, 0f, c))
    }

    @Test
    fun outlineTurnsOncePerFortySecondsAndRestsOnARightAngle() {
        var angle = 0f
        repeat(40 * 60) { angle = advanceSpin(angle, 1_000_000_000L / 60) }
        assertTrue("一圈回到起点附近：$angle", angle < 0.5f || angle > 359.5f)
        assertEquals(9f, advanceSpin(0f, 1_000_000_000L), 1e-4f)
        assertEquals(0f, restingAngle(44f), 0f)
        assertEquals(90f, restingAngle(46f), 0f)
        assertEquals(360f, restingAngle(359f), 0f)
    }
}
