package com.mytv.mymusic.ui.player.stage

import com.mytv.mymusic.data.DefaultTempoRules
import com.mytv.mymusic.data.LyricStagePrefs
import com.mytv.mymusic.data.TEMPO_RULE_OPEN_END
import com.mytv.mymusic.data.TempoRule
import com.mytv.mymusic.data.decodeStringSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StageChoicesTest {

    private val prefs = LyricStagePrefs()
    private val known: (String) -> Boolean = prefs::isKnown

    // ---- 随机挑选 ----

    @Test
    fun rememberedModeWinsEvenOutsideThePool() {
        val pick = chooseRandomMode("vinyl", listOf("rush", "aurora"), previous = "rush", known = known, random = Random(1))
        assertEquals(RandomPick("vinyl", remembered = true), pick)
    }

    @Test
    fun unknownRememberedModeIsSkipped() {
        repeat(20) { seed ->
            val pick = chooseRandomMode("rush#gone", listOf("rush", "aurora"), previous = null, known = known, random = Random(seed))
            assertFalse(pick.remembered)
            assertTrue(pick.id in listOf("rush", "aurora"))
        }
        assertFalse(chooseRandomMode("", listOf("rush"), null, known, Random(0)).remembered)
    }

    @Test
    fun drawAvoidsThePreviousMode() {
        repeat(50) { seed ->
            val pick = chooseRandomMode(null, listOf("rush", "aurora", "vinyl"), previous = "aurora", known = known, random = Random(seed))
            assertNotEquals("aurora", pick.id)
        }
    }

    @Test
    fun singleModePoolAlwaysGivesThatMode() {
        repeat(10) { seed ->
            assertEquals("vinyl", chooseRandomMode(null, listOf("vinyl"), previous = "vinyl", known = known, random = Random(seed)).id)
        }
    }

    @Test
    fun emptyPoolFallsBackToTheDefaultPool() {
        repeat(30) { seed ->
            val pick = chooseRandomMode(null, emptyList(), previous = null, known = known, random = Random(seed))
            assertTrue(pick.id in defaultPool())
        }
    }

    // ---- 随机池 ----

    @Test
    fun emptyPoolMeansAllBuiltInsExceptClassic() {
        val pool = prefs.effectivePool()
        assertEquals(defaultPool(), pool)
        assertFalse(ClassicVisualizer.id in pool)
        assertEquals(LyricVisualizers.all.size - 1, pool.size)
    }

    @Test
    fun poolDropsUnknownIdsAndFollowsCardOrder() {
        val pool = prefs.copy(randomPool = setOf("vinyl", "bogus", "rush")).effectivePool()
        assertEquals(listOf("rush", "vinyl"), pool)
        assertEquals(defaultPool(), prefs.copy(randomPool = setOf("bogus")).effectivePool())
        // 经典选进池里就能抽到
        assertEquals(listOf("classic", "rush"), prefs.copy(randomPool = setOf("rush", "classic")).effectivePool())
    }

    @Test
    fun decodeStringSetToleratesGarbage() {
        assertEquals(setOf("rush", "vinyl"), decodeStringSet("[\"rush\",\"vinyl\",\"rush\"]"))
        assertEquals(emptySet<String>(), decodeStringSet(null))
        assertEquals(emptySet<String>(), decodeStringSet(""))
        assertEquals(emptySet<String>(), decodeStringSet("{not json"))
    }

    // ---- 卡片和设置 ----

    @Test
    fun unknownIdsResolveToClassic() {
        assertEquals(ClassicVisualizer, prefs.modeOf("bogus"))
        assertEquals("rush", prefs.modeOf("rush").id)
        assertTrue(prefs.isKnown("rush"))
        assertFalse(prefs.isKnown("rush#abcd"))
        assertEquals("rush", baseIdOf("rush#abcd"))
        assertEquals("rush", baseIdOf("rush"))
        assertEquals(LyricVisualizers.all, prefs.cards())
    }

    @Test
    fun withSettingsSanitizesAndDropsEmptyMaps() {
        val changed = prefs.withSettings("rush", mapOf("align" to "center", "size" to "1.0", "bogus" to "x"))
        assertEquals(mapOf("align" to "center"), changed.settingsFor("rush"))
        val reset = changed.withSettings("rush", emptyMap())
        assertFalse("rush" in reset.pluginSettings)
        assertEquals(emptyMap<String, String>(), reset.settingsFor("rush"))
    }

    // ---- 速度档位 ----

    @Test
    fun tempoTierIsLeftClosedRightOpen() {
        val rules = DefaultTempoRules
        assertEquals(0, tempoTier(rules, 60f))
        assertEquals(0, tempoTier(rules, 89.9f))
        assertEquals(1, tempoTier(rules, 90f))
        assertEquals(2, tempoTier(rules, 120f))
        assertEquals(3, tempoTier(rules, 150f))
        assertEquals(3, tempoTier(rules, 2000f))
        assertEquals(-1, tempoTier(emptyList(), 100f))
        // 档位下标和 modeForTempo 选中的那条一致
        val shuffled = listOf(TempoRule(TEMPO_RULE_OPEN_END, "jump"), TempoRule(90, "vinyl"))
        assertEquals(1, tempoTier(shuffled, 80f))
        assertEquals("vinyl", modeForTempo(shuffled, 80f))
    }

    // ---- 什么时候换 ----

    @Test
    fun applyPendingSkipsTheGapWait() {
        assertTrue(shouldSwitchNow(immediate = false, applyPending = true, lyricsWaiting = true, canSwitch = false))
        assertTrue(shouldSwitchNow(immediate = true, applyPending = false, lyricsWaiting = true, canSwitch = false))
        assertFalse(shouldSwitchNow(immediate = false, applyPending = false, lyricsWaiting = true, canSwitch = true))
        assertFalse(shouldSwitchNow(immediate = false, applyPending = false, lyricsWaiting = false, canSwitch = false))
        assertTrue(shouldSwitchNow(immediate = false, applyPending = false, lyricsWaiting = false, canSwitch = true))
    }
}
