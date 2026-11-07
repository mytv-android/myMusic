package com.mytv.mymusic

import com.mytv.mymusic.playback.AutomixAnalysis
import com.mytv.mymusic.playback.AutomixMethod
import com.mytv.mymusic.playback.AutomixPlanner
import com.mytv.mymusic.playback.AutomixSection
import com.mytv.mymusic.playback.BeatGrid
import com.mytv.mymusic.playback.CrossfadeReport
import com.mytv.mymusic.playback.CrossfadeTransition
import com.mytv.mymusic.playback.estimateKeyFromChroma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixTest {
    private val majorProfile = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88,
    )
    private val minorProfile = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17,
    )

    private fun rotate(profile: DoubleArray, root: Int): DoubleArray =
        DoubleArray(12) { profile[Math.floorMod(it - root, 12)] }

    @Test
    fun `a major key profile is detected at its root`() {
        val (root, minor, confidence) = estimateKeyFromChroma(rotate(majorProfile, 0))
        assertEquals(0, root)
        assertFalse(minor)
        assertTrue(confidence > 0.3f)
    }

    @Test
    fun `an a minor profile is detected as a minor key`() {
        val (root, minor, _) = estimateKeyFromChroma(rotate(minorProfile, 9))
        assertEquals(9, root)
        assertTrue(minor)
    }

    @Test
    fun `silence has no key`() {
        val (root, _, confidence) = estimateKeyFromChroma(DoubleArray(12))
        assertEquals(-1, root)
        assertEquals(0f, confidence, 0.001f)
    }

    @Test
    fun `analysis survives a database round trip`() {
        val analysis = AutomixAnalysis(
            songKey = "netease:1",
            durationMs = 200_000,
            beatGrid = BeatGrid.NONE,
            loudnessDb = -11.5f,
            keyIndex = 9,
            keyMinor = true,
            keyConfidence = 0.4f,
            introMs = 1_200,
            outroMs = 198_000,
            sections = listOf(AutomixSection(0, 0.6f), AutomixSection(32_000, 1f)),
            updatedAt = 123L,
        )
        val restored = AutomixAnalysis.fromEntity(analysis.toEntity())
        assertEquals(analysis.songKey, restored.songKey)
        assertEquals(analysis.durationMs, restored.durationMs)
        assertEquals(analysis.loudnessDb, restored.loudnessDb, 0.001f)
        assertEquals(analysis.keyIndex, restored.keyIndex)
        assertEquals(analysis.introMs, restored.introMs)
        assertEquals(analysis.outroMs, restored.outroMs)
        assertEquals(analysis.sections, restored.sections)
    }

    @Test
    fun `an analysis is only reused when the duration still matches`() {
        val entity = AutomixAnalysis(
            songKey = "qq:2",
            durationMs = 180_000,
            beatGrid = BeatGrid.NONE,
            loudnessDb = -12f,
            keyIndex = 0,
            keyMinor = false,
            keyConfidence = 0f,
            introMs = 0,
            outroMs = 180_000,
            sections = emptyList(),
            updatedAt = 0,
        ).toEntity()
        assertTrue(AutomixAnalysis.matches(entity, "qq:2", 181_000))
        assertFalse(AutomixAnalysis.matches(entity, "qq:2", 185_000))
        assertFalse(AutomixAnalysis.matches(entity, "qq:3", 180_000))
    }

    private fun analysis(
        key: String,
        bpm: Double,
        confidence: Float = 0.9f,
        t0S: Double = 0.0,
        downbeatClear: Boolean = true,
        introMs: Long = 2_000,
        outroMs: Long = 198_000,
        durationMs: Long = 200_000,
    ) = AutomixAnalysis(
        songKey = key,
        durationMs = durationMs,
        beatGrid = BeatGrid(
            periodS = 60.0 / bpm,
            t0S = t0S,
            k0 = 0L,
            confidence = confidence,
            downbeat = 0,
            downbeatClear = downbeatClear,
            strengthIndex = LongArray(0),
            strengthValue = FloatArray(0),
        ),
        loudnessDb = -10f,
        keyIndex = 0,
        keyMinor = false,
        keyConfidence = 0.5f,
        introMs = introMs,
        outroMs = outroMs,
        sections = emptyList(),
        updatedAt = 0,
    )

    @Test
    fun `two confident grids with matching tempo cut on a downbeat`() {
        val plan = AutomixPlanner.plan(
            analysis("a", 120.0),
            analysis("b", 124.0),
            6,
            durationMs = 200_000,
        )
        assertEquals(AutomixMethod.BeatCut, plan.method)
        // 上一首末尾两秒内有一个小节首拍，切点落在它上面
        assertTrue(plan.startAtRemainingMs in 500..3_200)
        assertTrue(plan.fadeMs in 400..1_200)
        // 变速把上一首的尾巴拉到和下一首一致
        assertEquals(124.0 / 120.0, plan.outgoingSpeed.toDouble(), 0.001)
        assertEquals(0L, plan.incomingStartMs)
    }

    @Test
    fun `a long clean outro and a real intro carry over instead of cutting`() {
        val plan = AutomixPlanner.plan(
            analysis("a", 100.0, confidence = 0.2f, outroMs = 196_000),
            analysis("b", 100.0, confidence = 0.2f, introMs = 3_000),
            6,
            durationMs = 200_000,
        )
        assertEquals(AutomixMethod.OutroCarry, plan.method)
        assertTrue(plan.echoMix > 0f)
    }

    @Test
    fun `one analysed song with a confident grid hands off the bass`() {
        val plan = AutomixPlanner.plan(
            analysis("a", 120.0, introMs = 300),
            analysis("b", 120.0, confidence = 0.2f, introMs = 300),
            6,
            durationMs = 200_000,
        )
        assertEquals(AutomixMethod.BassHandoff, plan.method)
        assertTrue(plan.bassCutoffHz > 0f)
    }

    @Test
    fun `without analysis it falls back to an equal power fade`() {
        val plan = AutomixPlanner.plan(null, null, 8, durationMs = 200_000)
        assertEquals(AutomixMethod.EqualPowerFade, plan.method)
        assertEquals(8_000L, plan.fadeMs)
        assertEquals(1f, plan.outgoingSpeed, 0.001f)
    }

    @Test
    fun `a transition reports its progress from the start time`() {
        val transition = CrossfadeTransition("晴天", "稻香", "低频交接", 6_000, startedAtMs = 1_000)
        assertEquals(0f, transition.progressAt(1_000), 0.001f)
        assertEquals(0.5f, transition.progressAt(4_000), 0.001f)
        assertEquals(1f, transition.progressAt(9_000), 0.001f)
    }

    @Test
    fun `a report explains the method or the fallback`() {
        val mixed = CrossfadeReport("晴天", "稻香", "等功率淡化", 5.6f)
        assertEquals("等功率淡化 · 5.6 秒", mixed.headline)
        assertEquals("《晴天》→《稻香》", mixed.detail)

        val fallback = CrossfadeReport("晴天", "稻香", "直接切", 0f, "下一首是加密整曲")
        assertEquals("已退回直接切", fallback.headline)
        assertEquals("《晴天》→《稻香》 · 下一首是加密整曲", fallback.detail)
    }
}
