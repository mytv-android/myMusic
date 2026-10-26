package com.mytv.mymusic

import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.ui.player.RandomCoverShapes
import com.mytv.mymusic.ui.player.label
import com.mytv.mymusic.ui.player.nextRandomCoverShape
import com.mytv.mymusic.ui.player.polygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** 播放页“随机”封面形状：切歌不和上一首重复，也不会抽到“随机”本身。 */
class CoverShapesTest {
    @Test
    fun candidatesAreTheSixConcreteShapes() {
        assertEquals(6, RandomCoverShapes.size)
        assertFalse(CoverShapeChoice.Random in RandomCoverShapes)
        assertEquals(CoverShapeChoice.entries.size, CoverShapeChoice.entries.map { it.label }.toSet().size)
    }

    @Test
    fun onlyRandomHasNoFixedPolygon() {
        assertNull(CoverShapeChoice.Random.polygon)
        RandomCoverShapes.forEach { assertNotNull(it.name, it.polygon) }
    }

    @Test
    fun consecutivePicksNeverRepeatAndReachEveryShape() {
        val random = Random(20261026)
        var previous: CoverShapeChoice? = null
        val seen = mutableSetOf<CoverShapeChoice>()
        repeat(2_000) {
            val next = nextRandomCoverShape(previous, random)
            assertNotEquals(CoverShapeChoice.Random, next)
            assertNotEquals(previous, next)
            seen += next
            previous = next
        }
        assertEquals(RandomCoverShapes.toSet(), seen)
    }

    @Test
    fun eachPreviousIsExcludedAndTheRestAreReachable() {
        val random = Random(7)
        (CoverShapeChoice.entries + listOf<CoverShapeChoice?>(null)).forEach { previous ->
            val picks = List(600) { nextRandomCoverShape(previous, random) }.toSet()
            // “随机”或空不算上一首的形状，6 种都应出现
            assertEquals("previous=$previous", RandomCoverShapes.toSet() - setOfNotNull(previous), picks)
        }
    }
}
