package com.mytv.mymusic.ui.player.stage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.player.stage.modes.DriftLine
import com.mytv.mymusic.ui.player.stage.modes.DriftLook
import com.mytv.mymusic.ui.player.stage.modes.DriftLyrics
import com.mytv.mymusic.ui.player.stage.modes.DriftSource
import com.mytv.mymusic.ui.player.stage.modes.DriftTiming
import com.mytv.mymusic.ui.player.stage.modes.STARDRIFT_METEOR_REFILL
import com.mytv.mymusic.ui.player.stage.modes.StardriftConfig
import com.mytv.mymusic.ui.player.stage.modes.StardriftMeteors
import com.mytv.mymusic.ui.player.stage.modes.StardriftSky
import com.mytv.mymusic.ui.player.stage.modes.StardriftVisualizer
import com.mytv.mymusic.ui.player.stage.modes.driftLook
import com.mytv.mymusic.ui.player.stage.modes.frozenStardriftMeteor
import com.mytv.mymusic.ui.player.stage.modes.isStardriftLatin
import com.mytv.mymusic.ui.player.stage.modes.isStardriftPunct
import com.mytv.mymusic.ui.player.stage.modes.placeDriftGlyphs
import com.mytv.mymusic.ui.player.stage.modes.spawnStardriftMeteor
import com.mytv.mymusic.ui.player.stage.modes.stardriftFixTrailingGap
import com.mytv.mymusic.ui.player.stage.modes.stardriftMeteorCap
import com.mytv.mymusic.ui.player.stage.modes.stardriftMeteorRate
import com.mytv.mymusic.ui.player.stage.modes.stardriftStarCount
import com.mytv.mymusic.ui.player.stage.modes.stardriftStarEnvelope
import com.mytv.mymusic.ui.player.stage.modes.stardriftTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StardriftTest {
    private val cfg = StardriftConfig(density = 150f, fontScale = 1f, hold = 0.5f, meteor = 1f, ring = true, ringLatin = true, punctHide = true, pulse = true)

    @Test
    fun starCountFollowsTheOriginalSteadyState() {
        assertEquals(96, stardriftStarCount(150f))
        assertEquals(32, stardriftStarCount(30f))
        assertEquals(180, stardriftStarCount(300f))
        assertEquals(58, stardriftStarCount(150f, countScale = 0.6f))
    }

    @Test
    fun starEnvelopeFadesInAndOut() {
        assertEquals(0f, stardriftStarEnvelope(0f), 1e-6f)
        assertEquals(0.5f, stardriftStarEnvelope(0.075f), 1e-4f)
        assertEquals(1f, stardriftStarEnvelope(0.5f), 0f)
        assertEquals(0.5f, stardriftStarEnvelope(0.925f), 1e-4f)
        assertEquals(0f, stardriftStarEnvelope(1f), 0f)
    }

    @Test
    fun skyIsReproducibleAndStaysOnScreen() {
        val a = StardriftSky(seed = 11L, count = 96, width = 1080f, height = 2400f, unit = 2.75f)
        val b = StardriftSky(seed = 11L, count = 96, width = 1080f, height = 2400f, unit = 2.75f)
        for (t in listOf(0f, 3.7f, 12.5f, 61f)) {
            for (i in 0 until a.count) {
                val life = a.life(i, t)
                assertEquals(life, b.life(i, t), 0f)
                assertTrue(life in 0f..1f)
                assertEquals(a.x[i], b.x[i], 0f)
                assertTrue(a.x[i] in 0f..1080f && a.y[i] in 0f..2400f)
                assertTrue(a.baseSize[i] in 0.6f * 2.75f..3f * 2.75f)
            }
        }
    }

    @Test
    fun starsAreRebornSomewhereElse() {
        val sky = StardriftSky(seed = 3L, count = 40, width = 1000f, height = 1000f, unit = 1f)
        val spots = HashSet<Pair<Float, Float>>()
        var t = 0f
        while (t < 10f) {
            sky.life(0, t)
            spots += sky.x[0] to sky.y[0]
            t += 0.05f
        }
        // 寿命 1.6–4.8 秒，10 秒里至少换了两轮
        assertTrue(spots.size >= 3)
        // 往回看同一时刻，参数一样（不存历史）
        sky.life(0, 1f)
        val x = sky.x[0]
        sky.life(0, 9f)
        sky.life(0, 1f)
        assertEquals(x, sky.x[0], 0f)
    }

    @Test
    fun meteorsEnterFromOffscreenTowardTheMiddle() {
        val r = StageRandom(8L)
        repeat(200) {
            val m = spawnStardriftMeteor(r, 1000f, 600f, unit = 2f)
            val outside = m.x < 0f || m.x > 1000f || m.y < 0f || m.y > 600f
            assertTrue(outside)
            val toCenter = (500f - m.x) * m.vx + (300f - m.y) * m.vy
            assertTrue(toCenter > 0f)
            assertTrue(hypot(m.vx, m.vy) in 420f * 2f..780f * 2f)
            assertTrue(m.len in 110f * 2f..200f * 2f)
        }
    }

    @Test
    fun meteorAmountMapsToCapAndRate() {
        assertEquals(0, stardriftMeteorCap(0f))
        assertEquals(1, stardriftMeteorCap(0.25f))
        assertEquals(1, stardriftMeteorCap(1f))
        assertEquals(2, stardriftMeteorCap(1.5f))
        assertEquals(3, stardriftMeteorCap(3f))
        assertEquals(STARDRIFT_METEOR_REFILL, stardriftMeteorRate(1f, 0, 3f), 0f)
        assertEquals(0f, stardriftMeteorRate(1f, 1, 3f), 0f)
        assertEquals(0f, stardriftMeteorRate(0f, 0, 3f), 0f)
        assertEquals(1f / 3f, stardriftMeteorRate(0.5f, 0, 3f), 1e-5f)
        assertEquals(STARDRIFT_METEOR_REFILL, stardriftMeteorRate(2.25f, 1, 3f), 0f)
        assertEquals(0.25f / 0.75f / 3f, stardriftMeteorRate(2.25f, 2, 3f), 1e-5f)
    }

    @Test
    fun meteorShowerKeepsTheConfiguredCount() {
        val shower = StardriftMeteors(seed = 4L)
        repeat(60) { shower.advance(1f / 60f, 2f, 1000f, 600f, 1f) }
        assertEquals(2, shower.meteors.size)
        val frozen = shower.meteors.map { it.x }
        shower.advance(0f, 2f, 1000f, 600f, 1f)
        assertEquals(frozen, shower.meteors.map { it.x })
        val none = StardriftMeteors(seed = 4L)
        repeat(600) { none.advance(1f / 60f, 0f, 1000f, 600f, 1f) }
        assertTrue(none.meteors.isEmpty())
    }

    @Test
    fun fractionalMeteorShowsUpAboutThatShareOfTheTime() {
        val shower = StardriftMeteors(seed = 21L)
        var visible = 0
        val frames = 60 * 600
        repeat(frames) {
            shower.advance(1f / 60f, 0.5f, 1000f, 600f, 1f)
            assertTrue(shower.meteors.size <= 1)
            if (shower.meteors.isNotEmpty()) visible++
        }
        val share = visible.toFloat() / frames
        assertTrue("share=$share", share in 0.3f..0.7f)
    }

    @Test
    fun frozenMeteorIsOnScreen() {
        val m = frozenStardriftMeteor(seed = 9L, width = 1000f, height = 600f, unit = 1f)
        assertTrue(m.x in -100f..1100f && m.y in -100f..700f)
    }

    @Test
    fun punctuationAndLatinFollowTheWhitelist() {
        listOf("，", "。", "·", "…", "!", "?", "\"", "~").forEach { assertTrue(it, isStardriftPunct(it)) }
        listOf("字", "a", "★", "々").forEach { assertFalse(it, isStardriftPunct(it)) }
        listOf("a", "Z", "5").forEach { assertTrue(it, isStardriftLatin(it)) }
        listOf("字", "é", "あ", "，").forEach { assertFalse(it, isStardriftLatin(it)) }
    }

    @Test
    fun timingsSkipSpacesAndFixAMisplacedLastChar() {
        val line = StageLine(
            index = 0, startMs = 0, endMs = 9500, text = "你好 吗",
            words = listOf(LyricWord(0, 500, "你"), LyricWord(500, 1000, "好 "), LyricWord(9000, 9500, "吗")),
            translation = "", romanization = "",
        )
        val t = stardriftTimings(line)
        assertEquals(listOf("你", "好", "吗"), t.map { it.char })
        assertEquals(1.1f, t[2].start, 1e-4f)
        assertEquals(1.6f, t[2].end, 1e-4f)

        // 慢歌：空隙不到整句 30% 不动
        val slow = listOf(DriftTiming("a", 0f, 1f), DriftTiming("b", 1f, 2f), DriftTiming("c", 5.5f, 6f))
        stardriftFixTrailingGap(slow, lineSpanS = 20f)
        assertEquals(5.5f, slow[2].start, 0f)
    }

    @Test
    fun placementStaysInTheAreaAndApart() {
        val area = Rect(80f, 300f, 1000f, 1900f)
        val spots = placeDriftGlyphs(10, area, minDist = 150f, random = StageRandom(77L))
        assertEquals(10, spots.size)
        spots.forEach { assertTrue(it.toString(), area.contains(it) || it.x == area.right || it.y == area.bottom) }
        for (i in spots.indices) for (j in i + 1 until spots.size) {
            assertTrue((spots[i] - spots[j]).getDistance() >= 150f)
        }
        assertEquals(spots, placeDriftGlyphs(10, area, 150f, StageRandom(77L)))
        // 避开还亮着的旧字
        val avoid = listOf(Offset(500f, 1000f))
        placeDriftGlyphs(6, area, 150f, StageRandom(5L), avoid).forEach { assertTrue((it - avoid[0]).getDistance() >= 150f) }
        // 安全区退化成一条线时落点也在线上
        val flat = Rect(500f, 300f, 500f, 900f)
        assertTrue(placeDriftGlyphs(4, flat, 150f, StageRandom(1L)).all { it.x == 500f && it.y in 300f..900f })
    }

    @Test
    fun appearTimeNeverExceedsHold() {
        assertEquals(0.5f, cfg.appearS, 0f)
        assertEquals(1f, cfg.fadeS, 0f)
        assertEquals(0.25f, StardriftConfig(150f, 1f, 0.25f, 1f, true, true, true, true).appearS, 0f)
        assertEquals(0.2f, StardriftConfig(150f, 1f, 0f, 1f, true, true, true, true).appearS, 0f)
        assertEquals(0.5f, StardriftConfig(150f, 1f, 3f, 1f, true, true, true, true).appearS, 0f)
    }

    @Test
    fun glyphLifecycleGrowsHoldsAndShrinks() {
        val look = DriftLook()
        assertFalse(driftLook(-0.01f, 0.5f, 1f, 0.5f, 1f, 1f, look))
        assertTrue(driftLook(0.25f, 0.5f, 1f, 0.5f, 1f, 1f, look))
        assertEquals(0.5f, look.alpha, 1e-5f)
        assertTrue(look.scale > 0.12f && look.scale < 1f)
        assertTrue(look.primary > 0f && look.primary < 1f)
        // 正在唱：满尺寸、文字色、光罩全开，不透明度随呼吸
        assertTrue(driftLook(1f, 0.5f, 1f, 0.5f, 1f, 1f, look))
        assertEquals(1f, look.scale, 0f)
        assertEquals(1f, look.ring, 0f)
        assertEquals(1f, look.primary, 0f)
        driftLook(1f, 0.5f, 1f, 0.5f, 1f, 0.8f, look)
        assertEquals(0.96f, look.alpha, 1e-5f)
        // 唱完停留：光罩在一个缩放时间里缩没
        driftLook(1.75f, 0.5f, 1f, 0.5f, 1f, 1f, look)
        assertEquals(0.5f, look.ring, 1e-5f)
        assertEquals(1f, look.scale, 0f)
        // 缩小消失
        assertTrue(driftLook(2.5f, 0.5f, 1f, 0.5f, 1f, 1f, look))
        assertEquals(0.5f, look.alpha, 1e-5f)
        assertEquals(0f, look.ring, 0f)
        assertTrue(look.scale < 0.3f)
        assertFalse(driftLook(3.01f, 0.5f, 1f, 0.5f, 1f, 1f, look))
        // 停留为 0：唱完直接进消失段，光罩照原版在 0.2 秒里缩没，不是一下子没了
        assertTrue(driftLook(1.3f, 0.2f, 1f, 0f, 0.4f, 1f, look))
        assertEquals(0.5f, look.ring, 1e-4f)
    }

    @Test
    fun remeasuredLineSwapsGlyphsInPlace() {
        val lyrics = DriftLyrics<String>(seed = 5L)
        val area = Rect(100f, 300f, 1000f, 1900f)
        fun source(face: String) = DriftLine(2, listOf(DriftSource(face, DriftTiming("字", 1f, 1.3f)), DriftSource(face, DriftTiming("句", 1.3f, 1.6f))))
        lyrics.advance(source("small"), 1100, paused = false, area = area, minDist = 120f, cfg = cfg)
        val spots = lyrics.glyphs.map { it.x to it.y }
        lyrics.advance(source("large"), 1120, paused = false, area = area, minDist = 120f, cfg = cfg)
        assertEquals(listOf("large", "large"), lyrics.glyphs.map { it.glyph })
        assertEquals(spots, lyrics.glyphs.map { it.x to it.y })
    }

    @Test
    fun startedCharsOutliveTheirLineAndJumpsClear() {
        val lyrics = DriftLyrics<Unit>(seed = 5L)
        val area = Rect(100f, 300f, 1000f, 1900f)
        fun source(index: Int, vararg starts: Float) =
            DriftLine(index, starts.map { DriftSource(Unit, DriftTiming("字", it, it + 0.3f)) })
        lyrics.advance(source(0, 1f, 1.3f, 1.6f), 1400, paused = false, area = area, minDist = 120f, cfg = cfg)
        assertEquals(3, lyrics.glyphs.size)
        // 换句：已经开始的两个留下，没开始的丢掉，新句的字避开它们
        lyrics.advance(source(1, 1.5f, 1.8f), 1450, paused = false, area = area, minDist = 120f, cfg = cfg)
        assertEquals(listOf(1f, 1.3f, 1.5f, 1.8f), lyrics.glyphs.map { it.startS })
        val old = lyrics.glyphs.take(2)
        lyrics.glyphs.drop(2).forEach { g -> old.forEach { o -> assertTrue(hypot(g.x - o.x, g.y - o.y) >= 120f) } }
        lyrics.glyphs.forEach { assertTrue(area.contains(Offset(it.x, it.y))) }
        // 走完一生的字回收（出现 0.5 + 唱 0.3 + 停留 0.5 + 消失 1）
        val second = source(1, 1.5f, 1.8f)
        for (ms in 1500L..3350L step 50L) lyrics.advance(second, ms, paused = false, area = area, minDist = 120f, cfg = cfg)
        assertEquals(listOf(1.3f, 1.5f, 1.8f), lyrics.glyphs.map { it.startS })
        // 往回跳：清空，按新时间重排
        lyrics.advance(source(1, 1.5f, 1.8f), 1500, paused = false, area = area, minDist = 120f, cfg = cfg)
        assertEquals(2, lyrics.glyphs.size)
        // 同一句同一种子，落点一样
        val again = DriftLyrics<Unit>(seed = 5L)
        again.advance(source(1, 1.5f, 1.8f), 1500, paused = false, area = area, minDist = 120f, cfg = cfg)
        assertEquals(lyrics.glyphs.map { it.x to it.y }, again.glyphs.map { it.x to it.y })
    }

    @Test
    fun settingsMatchThePanel() {
        val v = StardriftVisualizer
        assertEquals("stardrift", v.id)
        assertEquals(7, v.hiddenSettings)
        assertTrue(v.wantsAudio && v.drawsWithoutLyrics)
        assertEquals(listOf("density", "fontScale", "hold", "meteor", "ring", "ringLatin", "punctHide", "pulse"), v.settings.map { it.key })
        val values = SettingValues(v.settings, mapOf("meteor" to "1.3"))
        assertEquals(1.25f, values.number("meteor"), 1e-5f)
        assertEquals(150f, values.number("density"), 0f)
    }
}
