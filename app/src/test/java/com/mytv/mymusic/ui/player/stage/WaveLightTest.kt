package com.mytv.mymusic.ui.player.stage

import androidx.compose.ui.graphics.Color
import com.mytv.mymusic.ui.player.stage.modes.WaveHold
import com.mytv.mymusic.ui.player.stage.modes.WaveVinylKit
import com.mytv.mymusic.ui.player.stage.modes.WaveWord
import com.mytv.mymusic.ui.player.stage.modes.WaveLightVisualizer
import com.mytv.mymusic.ui.player.stage.modes.waveArcStart
import com.mytv.mymusic.ui.player.stage.modes.waveEntranceMs
import com.mytv.mymusic.ui.player.stage.modes.waveKeyword
import com.mytv.mymusic.ui.player.stage.modes.waveKeywordFontPx
import com.mytv.mymusic.ui.player.stage.modes.wavePalette
import com.mytv.mymusic.ui.player.stage.modes.wavePulsePhase
import com.mytv.mymusic.ui.player.stage.modes.waveRand
import com.mytv.mymusic.ui.player.stage.modes.waveRingAlpha
import com.mytv.mymusic.ui.player.stage.modes.waveRingRadius
import com.mytv.mymusic.ui.player.stage.modes.waveRingWidth
import com.mytv.mymusic.ui.player.stage.modes.waveTokenProgress
import com.mytv.mymusic.ui.player.stage.modes.waveTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WaveLightTest {

    // ---- 声波 ----

    @Test
    fun randMatchesTheJsFormula() {
        // 原模组 rand(i, salt) 在 Node 里算出的值
        assertEquals(0.8303512639049586, waveRand(14, 1).toDouble(), 1e-6)
        assertEquals(0.6320307339919964, waveRand(1, 7).toDouble(), 1e-6)
        assertEquals(0.8001530624605948, waveRand(31, 4).toDouble(), 1e-6)
        for (i in 0..200) {
            val v = waveRand(i, i % 7)
            assertTrue(v >= 0f && v < 1f)
            assertEquals(v, waveRand(i, i % 7))
        }
    }

    @Test
    fun ringsSpreadOutwardAndSwellWithinFifteenPercent() {
        val baseR = 600f
        for (seed in 1..2) for (i in 1..9) {
            val spread = baseR * (0.16f + 0.92f * i / 9f)
            var t = 0f
            while (t < 120f) {
                val r = waveRingRadius(i, baseR, t, seed)
                assertTrue("ring $i at $t: $r", r >= spread * 0.85f - 0.01f && r <= spread * 1.15f + 0.01f)
                t += 0.37f
            }
        }
        // 不胀缩时一圈比一圈大：取各自的平均
        val means = (1..9).map { i -> (0 until 400).map { waveRingRadius(i, baseR, it * 0.3f, 1) }.average() }
        assertTrue(means.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun outerRingsAreFainterAndNeverVanish() {
        for (i in 1..9) {
            var t = 0f
            while (t < 60f) {
                val a = waveRingAlpha(i, t, 1, 1f)
                assertTrue(a >= 0.008f)
                assertTrue(a <= 0.03f + 0.095f * (1f - i / 9f) + 1e-6f)
                t += 0.5f
            }
        }
        val inner = (0 until 200).map { waveRingAlpha(1, it * 0.3f, 1, 1f) }.average()
        val outer = (0 until 200).map { waveRingAlpha(9, it * 0.3f, 1, 1f) }.average()
        assertTrue(inner > outer * 3)
        // 亮度设置成倍放大
        assertEquals(waveRingAlpha(2, 3f, 1, 1f) * 2f, waveRingAlpha(2, 3f, 1, 2f), 1e-6f)
    }

    @Test
    fun ringWidthScalesWithPageHeight() {
        for (i in 1..9) {
            val w = waveRingWidth(i, 1, 1000f)
            assertTrue(w >= 1.2f && w <= 6.2f)
        }
        assertEquals(0.8f, waveRingWidth(1, 1, 10f), 1e-6f)
    }

    @Test
    fun pulsesLoopOutwardAThirdApart() {
        for (seed in 1..2) {
            var t = 0f
            while (t < 100f) {
                val phases = (0..2).map { wavePulsePhase(it, t, seed) }
                phases.forEach { assertTrue(it >= 0f && it < 1f) }
                val sorted = phases.sorted()
                assertEquals(1f / 3f, sorted[1] - sorted[0], 1e-3f)
                assertEquals(1f / 3f, sorted[2] - sorted[1], 1e-3f)
                t += 1.3f
            }
        }
        // 往外走：短时间内相位变大
        assertTrue(wavePulsePhase(0, 1.1f, 1) > wavePulsePhase(0, 1f, 1))
    }

    @Test
    fun oddArcsTurnTheOtherWay() {
        for (seed in 1..2) {
            assertTrue(waveArcStart(0, 10f, seed) > waveArcStart(0, 0f, seed))
            assertTrue(waveArcStart(1, 10f, seed) < waveArcStart(1, 0f, seed))
            assertTrue(waveArcStart(2, 10f, seed) > waveArcStart(2, 0f, seed))
        }
        // 慢：每秒不到 0.12 弧度
        assertTrue(abs(waveArcStart(0, 1f, 1) - waveArcStart(0, 0f, 1)) < 0.12f)
    }

    // ---- 配色 ----

    @Test
    fun paletteFollowsTheBaseColour() {
        val base = Color(0xFF5F7CFF)
        val palette = wavePalette(base, Color(0xFFFF8A65))
        assertEquals(base.red * 0.24f, palette.bgMid.red, EIGHT_BIT)
        assertEquals(base.blue * 0.04f, palette.bgDeep.blue, EIGHT_BIT)
        assertEquals(base.green * 0.4f, palette.keyword.green, EIGHT_BIT)
        val (wh, ws, wl) = WaveVinylKit.hsl(palette.wave)
        val (bh, _, _) = WaveVinylKit.hsl(base)
        assertEquals(bh, wh, 0.01f)
        assertEquals(0.58f, wl, 0.01f)
        assertTrue(ws in 0.44f..0.91f)
        val (_, ts, tl) = WaveVinylKit.hsl(palette.tint)
        assertEquals(0.92f, tl, 0.01f)
        assertTrue(ts <= 0.46f)
        // 淡光用第二色的色相
        val (dh, _, _) = WaveVinylKit.hsl(palette.drift)
        assertEquals(WaveVinylKit.hsl(Color(0xFFFF8A65))[0], dh, 0.01f)
    }

    @Test
    fun dominantColourIsPushedIntoAUsableRange() {
        val (_, s, l) = WaveVinylKit.hsl(WaveVinylKit.dominant(Color(0xFF101010)))
        assertEquals(0.46f, l, 0.01f)
        assertTrue(s >= 0.41f)
        val (_, s2, l2) = WaveVinylKit.hsl(WaveVinylKit.dominant(Color(0xFFFF0000)))
        assertEquals(0.85f, s2, 0.01f)
        assertEquals(0.5f, l2, 0.01f)
        val (h, hs, hl) = WaveVinylKit.hsl(Color.Red)
        assertEquals(0f, h, 1e-4f)
        assertEquals(1f, hs, 1e-4f)
        assertEquals(0.5f, hl, 1e-4f)
    }

    // ---- 逐字入场 ----

    @Test
    fun hanCharactersAreSingleTokensAndLatinWordsStayWhole() {
        val han = waveTokens("再度重相逢")
        assertEquals(5, han.size)
        assertEquals(listOf(2f, 1f, 0f, 1f, 2f), han.map { it.dist })
        assertTrue(han[0].shift < 0f && han[4].shift > 0f && han[2].shift == 0f)

        val text = "Don't stop, 宝贝"
        val latin = waveTokens(text)
        assertEquals(listOf("Don't", " ", "stop", ",", " ", "宝", "贝"), latin.map { text.substring(it.start, it.end) })
        assertEquals(listOf(false, true, false, false, true, false, false), latin.map { it.space })
    }

    @Test
    fun outerTokensSlideAtMostFourStepsAway() {
        val tokens = waveTokens("一二三四五六七八九十十一十二十三")
        val far = tokens.first()
        assertTrue(far.dist > 4f)
        assertEquals(-4f * 0.7f, far.shift, 1e-6f)
        assertEquals(4f * 0.7f, tokens.last().shift, 1e-6f)
    }

    @Test
    fun centreSurfacesFirstThenOutwardEveryEightyMs() {
        assertEquals(0f, waveTokenProgress(99f, 0f), 1e-6f)
        assertTrue(waveTokenProgress(150f, 0f) > 0f)
        assertEquals(0f, waveTokenProgress(179f, 1f), 1e-6f)
        assertTrue(waveTokenProgress(200f, 1f) > 0f)
        assertEquals(1f, waveTokenProgress(100f + 520f, 0f), 1e-6f)
        assertEquals(1f, waveTokenProgress(WaveVinylKit.DONE_MS, 9f), 1e-6f)
        var last = 0f
        for (ms in 0..800 step 10) {
            val p = waveTokenProgress(ms.toFloat(), 1f)
            assertTrue(p >= last)
            last = p
        }
    }

    @Test
    fun entranceLastsUntilTheOuterTokenLands() {
        val tokens = waveTokens("我的人生很短暂")
        assertEquals(100f + 3f * 80f + 520f, waveEntranceMs(tokens), 1e-3f)
        assertEquals(620f, waveEntranceMs(waveTokens("嗯")), 1e-3f)
        assertTrue(waveEntranceMs(emptyList()) >= 550f)
    }

    @Test
    fun entranceClockStartsOnFirstDrawAndFinishes() {
        val entrance = WaveVinylKit.Entrance(500f)
        assertEquals(0f, entrance.advance(10f), 1e-3f)
        assertEquals(250f, entrance.advance(10.25f), 1e-2f)
        assertFalse(entrance.done)
        entrance.advance(10.6f)
        assertTrue(entrance.done)
        assertEquals(WaveVinylKit.DONE_MS, entrance.advance(10.7f), 0f)
        val paused = WaveVinylKit.Entrance(500f)
        assertEquals(WaveVinylKit.DONE_MS, paused.finish(), 0f)
        assertEquals(WaveVinylKit.DONE_MS, paused.advance(0f), 0f)
    }

    // ---- 水印词 ----

    @Test
    fun heaviestLatinWordIsPickedStably() {
        // 和原模组在 Node 里跑出来的一样：kinda、short 同分，按整句哈希挑中 short
        assertEquals("short", waveKeyword("My life was kinda short", 3f))
        assertEquals("short", waveKeyword("My life was kinda short", 3f))
    }

    @Test
    fun longerWordsHeldLongerWin() {
        val keyword = waveKeyword("再度重相逢", 2f, fakeSegmenter("再度", "重", "相逢"))
        assertTrue(keyword, keyword == "再度" || keyword == "相逢")
    }

    @Test
    fun singleCharacterBorrowsAHeavierNeighbour() {
        val keyword = waveKeyword("我爱你", 1f, fakeSegmenter("我", "爱", "你"))
        assertEquals(2, keyword.length)
        assertTrue(keyword, "我爱你".contains(keyword))
        // 标点不借
        val alone = waveKeyword("爱！", 1f, fakeSegmenter("爱", "！"))
        assertEquals("爱", alone)
    }

    @Test
    fun creditLinesShowTheName() {
        assertEquals("方文山", waveKeyword("作词：方文山", 0f))
        assertEquals("Jay Chou", waveKeyword("Composed by : Jay Chou", 0f))
        assertEquals("Timberlake", waveKeyword("Lyrics by: Justin Timberlake", 0f))
    }

    @Test
    fun keywordIsCleanedAndCapped() {
        assertEquals("", waveKeyword("  ", 2f))
        assertEquals("", waveKeyword("……！", 2f))
        val long = waveKeyword("Supercalifragilisticexpialidocious", 4f)
        assertTrue(long.length <= 12)
        assertTrue("Supercalifragilisticexpialidocious".startsWith(long))
    }

    @Test
    fun keywordShrinksWithLength() {
        val css = 2f
        val short = waveKeywordFontPx(1000f, 800f, 2, 1f, css)
        val long = waveKeywordFontPx(1000f, 800f, 10, 1f, css)
        assertTrue(short > long)
        assertEquals(800f * 0.42f * 0.8f, waveKeywordFontPx(4000f, 800f, 1, 1f, css), 1e-3f)
        assertEquals(28f * css * 0.8f, waveKeywordFontPx(100f, 100f, 12, 0.6f, css), 1e-3f)
    }

    // ---- 句间保持 ----

    @Test
    fun interludesKeepThePreviousLine() {
        val lines = listOf(line(0, "第一句"), line(1, ""), line(2, "第三句"))
        val hold = WaveHold()
        assertEquals(-1, hold.resolve(lines, -1))
        assertEquals(0, hold.resolve(lines, 0))
        assertEquals(0, hold.resolve(lines, -1))
        assertEquals(0, hold.resolve(lines, 1))
        assertEquals(2, hold.resolve(lines, 2))
        assertEquals(2, hold.resolve(lines, -1))
        assertEquals(-1, WaveHold().resolve(emptyList(), 3))
    }

    @Test
    fun declarationMatchesTheBrief() {
        assertEquals("wave", WaveLightVisualizer.id)
        assertEquals(40, WaveLightVisualizer.order)
        assertFalse(WaveLightVisualizer.hostBackground)
        assertFalse(WaveLightVisualizer.hostSubtitles)
        assertEquals(listOf("waveSource", "brightness", "speed", "keyword", "textScale"), WaveLightVisualizer.settings.map { it.key })
        val values = SettingValues(WaveLightVisualizer.settings, mapOf("brightness" to "5", "speed" to "0.43"))
        assertEquals(2f, values.number("brightness"), 1e-6f)
        assertEquals(0.4f, values.number("speed"), 1e-4f)
        assertEquals("cover", values.choice("waveSource"))
    }

    private fun fakeSegmenter(vararg words: String): (String) -> List<WaveWord> = {
        words.mapIndexed { k, w -> WaveWord(w, k, w.any { it.isLetterOrDigit() }) }
    }

    private fun line(index: Int, text: String) =
        StageLine(index, index * 1000L, index * 1000L + 900L, text, emptyList(), "", "")

    private companion object {
        /** sRGB 的 Color 按 8 位存，比较时允许差一级。 */
        const val EIGHT_BIT = 1.01f / 255f
    }
}
