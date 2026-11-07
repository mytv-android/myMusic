package com.mytv.mymusic

import androidx.compose.ui.geometry.Size
import com.mytv.mymusic.data.DefaultTempoRules
import com.mytv.mymusic.playback.RHYTHM_BAND_COUNT
import com.mytv.mymusic.playback.peakFrequency
import com.mytv.mymusic.playback.spectrumBands
import com.mytv.mymusic.data.LyricSubline
import com.mytv.mymusic.data.TempoRule
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.LyricsParser
import com.mytv.mymusic.ui.player.stage.LyricSetting
import com.mytv.mymusic.ui.player.stage.SettingValues
import com.mytv.mymusic.ui.player.stage.StageDisplay
import com.mytv.mymusic.ui.player.stage.StageInsets
import com.mytv.mymusic.ui.player.stage.StageLine
import com.mytv.mymusic.ui.player.stage.StageRandom
import com.mytv.mymusic.ui.player.stage.canSwitchAt
import com.mytv.mymusic.ui.player.stage.charTimings
import com.mytv.mymusic.ui.player.stage.modeForTempo
import com.mytv.mymusic.ui.player.stage.pickRandomMode
import com.mytv.mymusic.ui.player.stage.sanitizeSettings
import com.mytv.mymusic.ui.player.stage.splitCodePoints
import com.mytv.mymusic.ui.player.stage.stableHash
import com.mytv.mymusic.ui.player.stage.stageLine
import com.mytv.mymusic.ui.player.stage.stageLineIndex
import com.mytv.mymusic.ui.player.stage.stageLines
import com.mytv.mymusic.ui.player.stage.tempoRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LyricStageTest {
    private fun stage(index: Int, start: Long, end: Long, text: String = "句子", words: List<LyricWord> = emptyList()) =
        StageLine(index, start, end, text, words, "", "")

    // ---------- 句子和字的时间 ----------

    @Test
    fun lineEndsFollowWordsNextLineOrDefaults() {
        val worded = LyricLine(1_000, "a", words = listOf(LyricWord(1_000, 1_500, "a"), LyricWord(1_500, 2_200, "b")))
        assertEquals(2_200L, stageLine(0, worded, 5_000).endMs)
        // 逐字比下一句晚结束：压到下一句开始
        assertEquals(2_000L, stageLine(0, worded, 2_000).endMs)
        // 没有逐字：下一句开始，最长 10 秒
        assertEquals(4_000L, stageLine(0, LyricLine(1_000, "a"), 4_000).endMs)
        assertEquals(11_000L, stageLine(0, LyricLine(1_000, "a"), 30_000).endMs)
        // 最后一句没有逐字：5 秒
        assertEquals(6_000L, stageLine(0, LyricLine(1_000, "a"), null).endMs)
        // 同一时刻的下一句也至少留 1 毫秒
        assertEquals(1_001L, stageLine(0, LyricLine(1_000, "a"), 1_000).endMs)
    }

    @Test
    fun stageLinesSkipUnsyncedLyrics() {
        assertTrue(stageLines(LyricsParser.parse("没有时间轴\n第二行")).isEmpty())
        val lines = stageLines(LyricsParser.parse("[00:01.00]一\n[00:03.00]二"))
        assertEquals(listOf(1_000L, 3_000L), lines.map { it.startMs })
        assertEquals(listOf(0, 1), lines.map { it.index })
    }

    @Test
    fun lineIndexIsMinusOneBetweenLines() {
        val lines = listOf(stage(0, 1_000, 2_000), stage(1, 3_000, 4_000), stage(2, 4_000, 6_000))
        assertEquals(-1, stageLineIndex(emptyList(), 500))
        assertEquals(-1, stageLineIndex(lines, 500))
        assertEquals(0, stageLineIndex(lines, 1_000))
        assertEquals(-1, stageLineIndex(lines, 2_500))
        assertEquals(1, stageLineIndex(lines, 3_999))
        assertEquals(2, stageLineIndex(lines, 4_000))
        assertEquals(-1, stageLineIndex(lines, 7_000))
        // 提示是后面的句子（往回拖进度）也能找回来
        assertEquals(0, stageLineIndex(lines, 1_500, hint = 2))
        assertEquals(-1, stageLineIndex(lines, 500, hint = 2))
        assertEquals(2, stageLineIndex(lines, 5_000, hint = 0))
    }

    @Test
    fun charTimingsSplitWordDurationsAcrossTheirChars() {
        val line = stage(0, 1_000, 3_000, "风吹 过", listOf(LyricWord(1_000, 2_000, "风吹"), LyricWord(2_000, 3_000, " 过")))
        val chars = line.charTimings()
        assertEquals(listOf("风", "吹", " ", "过"), chars.map { it.text })
        assertEquals(listOf(1_000L, 1_500L, 1_000L, 2_000L), chars.map { it.startMs })
        assertEquals(listOf(1_500L, 2_000L, 1_000L, 3_000L), chars.map { it.endMs })
        assertTrue(chars[2].space)
        assertEquals(0f, chars[2].progress(5_000), 0f)
        assertEquals(0.5f, chars[0].progress(1_250), 1e-4f)
        assertEquals(1f, chars[3].progress(9_000), 0f)
    }

    @Test
    fun charTimingsSpreadEvenlyWithoutMatchingWords() {
        // 逐字只有一个字，对不上三个字：按句长 85% 均分
        val line = stage(0, 0, 3_000, "一二三", listOf(LyricWord(0, 500, "一")))
        val chars = line.charTimings()
        val span = (3_000 * 0.85f).toLong()
        assertEquals(listOf(0L, span / 3, span * 2 / 3), chars.map { it.startMs })
        assertEquals(span, chars.last().endMs)
        // spread = 1 时铺满整句
        assertEquals(3_000L, stage(0, 0, 3_000, "一二三").charTimings(spread = 1f).last().endMs)
        // 很短的句子也至少铺 200 毫秒
        assertEquals(200L, stage(0, 0, 10, "一二").charTimings().last().endMs)
    }

    @Test
    fun codePointsKeepSurrogatePairsTogether() {
        assertEquals(listOf("a", "😀", "𠀀", "b"), splitCodePoints("a😀𠀀b"))
        assertEquals(4, stage(0, 0, 1_000, "a😀𠀀b").charTimings().size)
    }

    @Test
    fun subtitleFollowsSublinePreference() {
        val both = StageLine(0, 0, 1, "사랑해", emptyList(), "我爱你", "saranghae")
        assertEquals("我爱你", both.subtitle(LyricSubline.Translation))
        assertEquals("saranghae", both.subtitle(LyricSubline.Romanization))
        assertEquals("saranghae\n我爱你", both.subtitle(LyricSubline.Both))
        val onlyTranslation = StageLine(0, 0, 1, "原文", emptyList(), "翻译", "")
        assertEquals("翻译", onlyTranslation.subtitle(LyricSubline.Romanization))
        assertEquals("翻译", onlyTranslation.subtitle(LyricSubline.Both))
    }

    // ---------- 设置表 ----------

    private val scale = LyricSetting.Number("scale", "大小", min = 0.4f, max = 2f, step = 0.1f, default = 1f)
    private val glow = LyricSetting.Toggle("glow", "光晕", default = true)
    private val style = LyricSetting.Choice("style", "样式", listOf("soft" to "柔和", "hard" to "硬朗"), default = "soft")
    private val schema = listOf(scale, glow, style)

    @Test
    fun numberClampSnapsToStepWithoutFloatNoise() {
        assertEquals(0.7f, scale.clamp(0.4f + 3 * 0.1f), 0f)
        assertEquals("0.7", scale.clamp(0.4f + 3 * 0.1f).toString())
        assertEquals(0.7f, scale.clamp(0.73f), 0f)
        assertEquals(0.4f, scale.clamp(-5f), 0f)
        assertEquals(2f, scale.clamp(99f), 0f)
        assertEquals(1f, scale.clamp(Float.NaN), 0f)
        // 从下限往上数刻度：min 不是 step 的整数倍时也对齐
        val odd = LyricSetting.Number("x", "x", min = 0.25f, max = 1f, step = 0.5f, default = 0.25f)
        assertEquals(0.75f, odd.clamp(0.6f), 0f)
    }

    @Test
    fun sanitizeDropsUnknownInvalidAndDefaultValues() {
        val clean = sanitizeSettings(
            schema,
            mapOf(
                "scale" to "1.26",
                "glow" to "true",
                "style" to "hard",
                "gone" to "1",
            ),
        )
        assertEquals(mapOf("scale" to "1.3", "style" to "hard"), clean)
        assertEquals(emptyMap<String, String>(), sanitizeSettings(schema, mapOf("scale" to "abc", "glow" to "yes", "style" to "loud")))
        assertEquals(emptyMap<String, String>(), sanitizeSettings(schema, mapOf("scale" to "1.0", "style" to "soft")))
    }

    @Test
    fun settingValuesMergeDefaults() {
        val values = SettingValues(schema, mapOf("scale" to "9", "glow" to "nope", "style" to "loud", "gone" to "x"))
        assertEquals(2f, values.number("scale"), 0f)
        assertTrue(values.toggle("glow"))
        assertEquals("soft", values.choice("style"))
        assertEquals(1, values.changed)
        assertEquals(0, SettingValues(schema, emptyMap()).changed)
    }

    // ---------- 随机数 ----------

    @Test
    fun stageRandomStaysInUnitRangeAndRepeatsPerSeed() {
        val seeds = listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, stableHash("晴天"))
        for (seed in seeds) {
            val random = StageRandom(seed)
            repeat(50_000) {
                val x = random.next()
                assertTrue("seed $seed gave $x", x >= 0f && x < 1f)
            }
        }
        val a = StageRandom(42)
        val b = StageRandom(42)
        repeat(100) { assertEquals(a.next(), b.next(), 0f) }
        assertNotEquals(StageRandom(1).next(), StageRandom(2).next(), 0f)
        val ints = StageRandom(7)
        repeat(10_000) { assertTrue(ints.int(5) in 0..4) }
        assertEquals(0, StageRandom(7).int(0))
    }

    @Test
    fun stableHashIsFnv1a() {
        assertEquals(0x811C9DC5L, stableHash(""))
        assertEquals(0xE40C292CL, stableHash("a"))
        assertEquals(stableHash("rings"), stableHash("rings"))
    }

    // ---------- 自动换模式 ----------

    @Test
    fun switchesOnlyAtSafeMoments() {
        val lines = listOf(stage(0, 5_000, 8_000), stage(1, 8_500, 10_000), stage(2, 13_000, 15_000))
        assertTrue(canSwitchAt(emptyList(), 123))
        // 第一句前：还剩 0.4 秒以上才换
        assertTrue(canSwitchAt(lines, 0))
        assertTrue(canSwitchAt(lines, 4_600))
        assertFalse(canSwitchAt(lines, 4_700))
        // 唱的时候不换
        assertFalse(canSwitchAt(lines, 6_000))
        // 0.5 秒的空当太短
        assertFalse(canSwitchAt(lines, 8_200))
        // 3 秒的空当：离下一句还有 0.8 秒以上才换
        assertTrue(canSwitchAt(lines, 10_500))
        assertTrue(canSwitchAt(lines, 12_200))
        assertFalse(canSwitchAt(lines, 12_300))
        // 最后一句唱完
        assertFalse(canSwitchAt(lines, 14_000))
        assertTrue(canSwitchAt(lines, 15_000))
    }

    @Test
    fun tempoBandsAreLeftClosedRightOpen() {
        assertEquals("vinyl", modeForTempo(DefaultTempoRules, 60f))
        assertEquals("vinyl", modeForTempo(DefaultTempoRules, 89.9f))
        assertEquals("rings", modeForTempo(DefaultTempoRules, 90f))
        assertEquals("wave", modeForTempo(DefaultTempoRules, 149f))
        assertEquals("jump", modeForTempo(DefaultTempoRules, 150f))
        assertEquals("jump", modeForTempo(DefaultTempoRules, 5_000f))
        assertNull(modeForTempo(emptyList(), 120f))
        // 顺序乱了也按上限排
        assertEquals("b", modeForTempo(listOf(TempoRule(200, "b"), TempoRule(100, "a")), 150f))
    }

    @Test
    fun tempoRangeLabels() {
        assertEquals("每分钟 90 拍以下", tempoRange(DefaultTempoRules, 0))
        assertEquals("每分钟 90–120 拍", tempoRange(DefaultTempoRules, 1))
        assertEquals("每分钟 150 拍及以上", tempoRange(DefaultTempoRules, 3))
    }

    @Test
    fun randomPickAvoidsThePreviousMode() {
        val candidates = listOf("a", "b", "c")
        val random = Random(1)
        repeat(500) { assertNotEquals("b", pickRandomMode(candidates, "b", random)) }
        assertEquals("a", pickRandomMode(listOf("a"), "a", random))
        assertEquals("classic", pickRandomMode(emptyList(), null, random))
        val seen = HashSet<String>()
        repeat(500) { seen += pickRandomMode(candidates, null, random) }
        assertEquals(candidates.toSet(), seen)
    }

    // ---------- 频谱 ----------

    @Test
    fun spectrumBandsUseSquaredMappingUpTo16k() {
        // 4096 点 FFT、48 kHz：每个频点 ~11.7 Hz，16 kHz 落在第 1365 个频点
        val spectrum = FloatArray(2048) { it.toFloat() / 2047f * 255f }
        val bands = spectrumBands(spectrum, 48_000)
        assertEquals(RHYTHM_BAND_COUNT, bands.size)
        assertEquals(0f, bands[0], 0f)
        assertEquals(1365f / 2047f, bands.last(), 1e-3f)
        // 平方映射：低频段分得细
        assertTrue(bands[1] < bands[47] / 100f)
        for (i in 1 until bands.size) assertTrue(bands[i] >= bands[i - 1])
        assertEquals(RHYTHM_BAND_COUNT, spectrumBands(FloatArray(0), 48_000).size)
    }

    @Test
    fun peakFrequencyFindsLoudestBin() {
        val spectrum = FloatArray(2048)
        spectrum[100] = 200f
        spectrum[50] = 120f
        assertEquals(100 * 48_000f / 4096f, peakFrequency(spectrum, 48_000), 1e-3f)
        assertEquals(0f, peakFrequency(FloatArray(2048), 48_000), 0f)
    }

    @Test
    fun safeRectLeavesRoomForTheSpectrumCard() {
        val insets = StageInsets().apply { set(top = 100f, bottom = 50f, start = 20f, end = 30f) }
        val plain = StageDisplay(30f, reduceMotion = false, tv = false, wide = false, insets = insets)
        val reserved = StageDisplay(30f, reduceMotion = false, tv = false, wide = false, insets = insets, reserveBottom = 70f)
        val size = Size(400f, 800f)
        assertEquals(750f, plain.safeRect(size).bottom, 0f)
        val safe = reserved.safeRect(size)
        assertEquals(680f, safe.bottom, 0f)
        assertEquals(100f, safe.top, 0f)
        assertEquals(20f, safe.left, 0f)
        assertEquals(370f, safe.right, 0f)
        // 舞台矮到放不下卡片时安全区收成一条线，不会倒过来
        val tiny = reserved.safeRect(Size(400f, 200f))
        assertEquals(tiny.top, tiny.bottom, 0f)
    }
}
