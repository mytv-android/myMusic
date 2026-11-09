package com.mytv.mymusic.data.local

import com.mytv.mymusic.aggregate.MatchScore
import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMatchRulesTest {
    private fun score(total: Float, rejected: Boolean = false) =
        MatchScore(total = total, title = total, artist = total, duration = total, album = total, rejected = rejected)

    @Test
    fun tagThresholdIs085() {
        assertTrue(LocalMatchRules.acceptTag(score(0.85f)))
        assertTrue(LocalMatchRules.acceptTag(score(0.97f)))
        assertFalse(LocalMatchRules.acceptTag(score(0.849f)))
        // 打分器否决过的（时长差太多、版本不对）分数再高也不要
        assertFalse(LocalMatchRules.acceptTag(score(0.99f, rejected = true)))
    }

    private fun song(id: String, duration: Int) = Song(id = id, name = "歌 $id", source = "netease", duration = duration)

    @Test
    fun audioPrefersDurationWithinTolerance() {
        val picked = LocalMatchRules.pickAudio(listOf(song("live", 320), song("studio", 270)), localDurationSec = 268)
        assertEquals("studio", picked?.first?.id)
        assertEquals(LocalMatchRules.AUDIO_CONFIDENCE, picked?.second)
    }

    @Test
    fun audioWithoutCloseDurationIsLooser() {
        val picked = LocalMatchRules.pickAudio(listOf(song("a", 320), song("b", 100)), localDurationSec = 200)
        assertEquals("a", picked?.first?.id)
        assertEquals(LocalMatchRules.AUDIO_CONFIDENCE_LOOSE, picked?.second)
        // 本地时长未知：直接信识曲
        assertEquals(LocalMatchRules.AUDIO_CONFIDENCE, LocalMatchRules.pickAudio(listOf(song("a", 320)), 0)?.second)
        assertNull(LocalMatchRules.pickAudio(emptyList(), 200))
    }

    @Test
    fun audioConfidenceStaysBelowManualButAboveTagThreshold() {
        assertTrue(LocalMatchRules.AUDIO_CONFIDENCE >= LocalMatchRules.TAG_ACCEPT)
        assertTrue(LocalMatchRules.AUDIO_CONFIDENCE_LOOSE < LocalMatchRules.TAG_ACCEPT)
    }

    @Test
    fun sampleStartShiftsForShortSongs() {
        assertEquals(30_000L, LocalMatchRules.sampleStartMs(240_000, LocalMatchRules.SAMPLE_START_MS))
        assertEquals(60_000L, LocalMatchRules.sampleStartMs(240_000, LocalMatchRules.SAMPLE_RETRY_START_MS))
        // 45 秒的歌从第 60 秒取不到，退到最后 3 秒
        assertEquals(42_000L, LocalMatchRules.sampleStartMs(45_000, LocalMatchRules.SAMPLE_RETRY_START_MS))
        assertEquals(0L, LocalMatchRules.sampleStartMs(2_000, LocalMatchRules.SAMPLE_START_MS))
        assertEquals(30_000L, LocalMatchRules.sampleStartMs(0, LocalMatchRules.SAMPLE_START_MS))
    }
}
