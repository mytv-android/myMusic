package com.mytv.mymusic.ui.player.stage

import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.player.stage.modes.CineramaChunkLook
import com.mytv.mymusic.ui.player.stage.modes.CineramaFrame
import com.mytv.mymusic.ui.player.stage.modes.CineramaHeroMetrics
import com.mytv.mymusic.ui.player.stage.modes.CineramaLayout
import com.mytv.mymusic.ui.player.stage.modes.CineramaMarqueeKind
import com.mytv.mymusic.ui.player.stage.modes.CineramaSegmenter
import com.mytv.mymusic.ui.player.stage.modes.CineramaSource
import com.mytv.mymusic.ui.player.stage.modes.CineramaSplit
import com.mytv.mymusic.ui.player.stage.modes.CineramaStyle
import com.mytv.mymusic.ui.player.stage.modes.CineramaVisualizer
import com.mytv.mymusic.ui.player.stage.modes.CineramaWeights
import com.mytv.mymusic.ui.player.stage.modes.cineramaAdjacent
import com.mytv.mymusic.ui.player.stage.modes.cineramaBandShift
import com.mytv.mymusic.ui.player.stage.modes.cineramaBandSlot
import com.mytv.mymusic.ui.player.stage.modes.cineramaChoose
import com.mytv.mymusic.ui.player.stage.modes.cineramaChunkWidthEm
import com.mytv.mymusic.ui.player.stage.modes.cineramaChunkLook
import com.mytv.mymusic.ui.player.stage.modes.cineramaCompile
import com.mytv.mymusic.ui.player.stage.modes.cineramaEdgeGlow
import com.mytv.mymusic.ui.player.stage.modes.cineramaErfc
import com.mytv.mymusic.ui.player.stage.modes.cineramaEvaluate
import com.mytv.mymusic.ui.player.stage.modes.cineramaFillChance
import com.mytv.mymusic.ui.player.stage.modes.cineramaFlow
import com.mytv.mymusic.ui.player.stage.modes.cineramaFrac
import com.mytv.mymusic.ui.player.stage.modes.cineramaHash01
import com.mytv.mymusic.ui.player.stage.modes.cineramaHeroChunks
import com.mytv.mymusic.ui.player.stage.modes.cineramaMarquee
import com.mytv.mymusic.ui.player.stage.modes.cineramaPickLayout
import com.mytv.mymusic.ui.player.stage.modes.cineramaPickStyle
import com.mytv.mymusic.ui.player.stage.modes.cineramaRibbonAngles
import com.mytv.mymusic.ui.player.stage.modes.cineramaRibbonDrift
import com.mytv.mymusic.ui.player.stage.modes.cineramaRibbonDriftClamp
import com.mytv.mymusic.ui.player.stage.modes.cineramaRibbonScroll
import com.mytv.mymusic.ui.player.stage.modes.cineramaRibbonStrips
import com.mytv.mymusic.ui.player.stage.modes.cineramaRingCopies
import com.mytv.mymusic.ui.player.stage.modes.cineramaRingLap
import com.mytv.mymusic.ui.player.stage.modes.cineramaRingOffset
import com.mytv.mymusic.ui.player.stage.modes.cineramaShadeStops
import com.mytv.mymusic.ui.player.stage.modes.cineramaSplitBalanced
import com.mytv.mymusic.ui.player.stage.modes.cineramaSplitClauses
import com.mytv.mymusic.ui.player.stage.modes.cineramaTransitionSec
import com.mytv.mymusic.ui.player.stage.modes.cineramaUnitProgress
import com.mytv.mymusic.ui.player.stage.modes.cineramaWords
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * 巨幕的纯逻辑。期望值里标“原版”的，是把同样的输入喂给原模组 cinerama 1.0.0 的 mjs（Node 24、Intl.Segmenter）算出来的。
 */
class CineramaTest {
    /** 原版在 Node 里 Intl.Segmenter(word) 的切法，测试里照搬，结果不随 JDK 的分词变。 */
    private val segmenter = CineramaSegmenter { text ->
        SEGMENTS[text] ?: text.split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }
    }

    private fun line(index: Int, start: Long, end: Long, text: String, translation: String = "", words: List<LyricWord> = emptyList()) =
        StageLine(index, start, end, text, words, translation, "")

    private val lines = listOf(
        line(0, 10_000, 14_000, "风吹过的地方都有光", "Where the wind goes, there is light"),
        line(1, 14_500, 18_500, "我们沿着河走回家"),
        line(
            2, 19_000, 23_000, "Hello bright world, sing along",
            words = listOf(
                LyricWord(19_000, 19_500, "Hello "),
                LyricWord(19_500, 20_200, "bright "),
                LyricWord(20_200, 21_000, "world, "),
                LyricWord(21_000, 21_600, "sing "),
                LyricWord(21_600, 22_400, "along"),
            ),
        ),
        line(3, 23_200, 27_000, "一二三四五六七八九十"),
        line(4, 27_100, 28_000, "Shine on"),
    )

    private val weights = CineramaWeights(0.4f, 0.2f, 0.4f)
    private val program = cineramaCompile(lines, "12345", "示例歌", weights, "auto", segmenter)

    @Test
    fun hashesMatchTheOriginal() {
        assertEquals(0.2987398197874427, cineramaHash01(0, 0, 349), 1e-12)
        assertEquals(0.4721772172488272, cineramaHash01(123456789, 3, 211), 1e-12)
        assertEquals(0.8273358033038676, cineramaHash01(4294967295, 9, 83), 1e-12)
        assertEquals(0.9984637973830104, cineramaHash01(2166136261, 21, 151), 1e-12)
        assertEquals(1376675235L, stableHash("cinerama"))
        assertEquals(16593046L, stableHash("12345:0:风吹过的地方都有光"))
        assertEquals(3801685015L, stableHash("a:1:b:split"))
    }

    @Test
    fun chooseNeverRepeatsThePrevious() {
        assertEquals(0.95, cineramaChoose(listOf(0.95, 1.0, 1.05), "77:chunk-scale:0", null))
        assertEquals(1.05, cineramaChoose(listOf(0.95, 1.0, 1.05), "77:chunk-scale:1", 1.0))
        assertEquals("y", cineramaChoose(listOf("x", "y"), "77:fill-axis:2", "x"))
        assertEquals("only", cineramaChoose(listOf("only"), "s", "only"))
        assertNull(cineramaChoose(emptyList<String>(), "s", null))
    }

    @Test
    fun programMatchesTheOriginalPlans() {
        val plans = program.plans
        assertEquals(listOf(16593046L, 1376011248L, 2670992876L, 3400860652L, 3652299580L), plans.map { it.seed })
        assertEquals(
            listOf(CineramaSplit.Whole, CineramaSplit.Grapheme, CineramaSplit.Word, CineramaSplit.Grapheme, CineramaSplit.Half),
            plans.map { it.split },
        )
        assertEquals(
            listOf(CineramaStyle.Ribbon, CineramaStyle.Hero, CineramaStyle.Ribbon, CineramaStyle.Small, CineramaStyle.Ribbon),
            plans.map { it.style },
        )
        // 行窗口：句末 + 0.6 秒，夹到下一句开口
        assertArrayEquals(doubleArrayOf(14.5, 19.0, 23.2, 27.1, 28.6), program.ends, 1e-9)
        val words = plans[2].units
        assertEquals(listOf("Hello", " ", "bright", " ", "world", ",", " ", "sing", " ", "along"), words.map { it.text })
        assertEquals(19.416667, words[0].end, 1e-6)
        assertEquals(20.771429, words[4].end, 1e-6)
        assertEquals(21.48, words[7].end, 1e-6)
        assertEquals(22.4, words[9].end, 1e-6)
        val chars = plans[3].units
        assertEquals(10, chars.size)
        assertEquals(23.58, chars[0].end, 1e-6)
        assertEquals(27.0, chars[9].end, 1e-6)
        assertEquals(listOf("我", "们", "沿", "着", "河", "走", "回", "家"), plans[1].units.map { it.text })
        assertEquals(18.5, plans[1].units.last().end, 1e-6)
    }

    @Test
    fun marqueeMatchesTheOriginal() {
        val m = program.plans.map {
            cineramaMarquee("auto", CineramaStyle.Small, it.seed, it.line.text, it.line.translation, "示例歌")!!
        }
        assertEquals(
            listOf(CineramaMarqueeKind.Bands, CineramaMarqueeKind.Bands, CineramaMarqueeKind.Bands, CineramaMarqueeKind.Frame, CineramaMarqueeKind.Bands),
            m.map { it.kind },
        )
        assertEquals(listOf(1, -1, 1, -1, -1), m.map { it.orbit })
        assertEquals(0.1769919569045305, m[0].screensPerSec, 1e-12)
        assertEquals(0.2387313625775277, m[1].screensPerSec, 1e-12)
        // 原文 + 翻译：上带原文、下带翻译，反向跑
        assertEquals("风吹过的地方都有光", m[0].top)
        assertEquals("Where the wind goes, there is light", m[0].bottom)
        assertFalse(m[0].mirror)
        // 没翻译：下带和上带同字，倒过来
        assertTrue(m[1].mirror)
        assertEquals(CineramaSource.Title, m[2].source)
        assertEquals("示例歌", m[2].top)
        assertTrue(m[2].mirror)
        assertEquals("一二三四五六七八九十", m[3].ring)
        // 关、丝带句没有带
        assertNull(cineramaMarquee("off", CineramaStyle.Hero, 1, "a", "", ""))
        assertNull(cineramaMarquee("bands", CineramaStyle.Ribbon, 1, "a", "", ""))
        assertEquals(CineramaMarqueeKind.Frame, cineramaMarquee("frame", CineramaStyle.Hero, 1, "a", "", "")!!.kind)
        // 歌名为空时退回原文
        assertEquals("a", cineramaMarquee("bands", CineramaStyle.Small, 2670992876L, "a", "", "")!!.top)
    }

    @Test
    fun heroChunksMatchTheOriginal() {
        // 屏高 100、16:9、上下双带（各让 15%）：px 和原版的 vh 一一对应
        val w = 100.0 * 16 / 9
        val metrics = CineramaHeroMetrics.of(w, 100.0, w, 70.0, 0.0)
        val plans = program.plans
        val first = cineramaHeroChunks(plans[0], metrics, segmenter)
        assertEquals(listOf("风", "吹", "过的地方", "都有光"), first.map { it.text })
        assertEquals(listOf(34.006601, 30.767877, 32.387239, 34.006601), first.map { round6(it.font) })
        assertEquals(10.444444, first[0].end, 1e-6)
        assertEquals(12.666667, first[2].end, 1e-6)
        assertEquals(14.0, first[3].end, 1e-6)
        assertEquals(listOf(false, true, false, true), first.map { it.fillVertical })
        assertEquals(listOf(1.0, 1.5, 1.0, 1.5), first.map { it.motion.scaleFrom })

        val second = cineramaHeroChunks(plans[1], metrics, segmenter)
        assertEquals(listOf("我们沿着河", "走", "回家"), second.map { it.text })
        assertEquals(listOf(true, false, true), second.map { it.italic })
        assertEquals(listOf(24.614301, 27.205281, 24.614301), second.map { round6(it.font) })
        assertEquals(17.0, second[0].end, 1e-6)

        val english = cineramaHeroChunks(plans[2], metrics, segmenter)
        assertEquals(listOf("Hello", "bright", "world,", "sing", "along"), english.map { it.text })
        assertEquals(listOf(19.0, 19.5, 20.2, 21.0, 21.6), english.map { round6(it.start) })
        assertEquals(0.15, english[3].motion.trackTo, 1e-9)
        assertEquals(1.3, english[3].motion.scaleTo, 1e-9)

        val count = cineramaHeroChunks(plans[3], metrics, segmenter)
        assertEquals(listOf("一二三四", "五", "六", "七八", "九十"), count.map { it.text })
        assertEquals(-2.6, count[4].motion.yFrom, 1e-9)
        assertTrue(count.size <= 5)

        val shine = cineramaHeroChunks(plans[4], metrics, segmenter)
        assertEquals(listOf("Shine", "on"), shine.map { it.text })
        assertEquals(27.775, shine[0].end, 1e-6)
        assertEquals(45.590399, round6(shine[1].font), 1e-6)
    }

    @Test
    fun heroChunksNarrowOnPortraitScreens() {
        // 竖屏（宽高比 0.6）：一块只放得下一两个字，最多 5 块
        val metrics = CineramaHeroMetrics.of(60.0, 100.0, 60.0, 70.0, 0.0)
        val chunks = cineramaHeroChunks(program.plans[0], metrics, segmenter)
        // 两个字的词也放不下（门槛是屏高 25.6%），先逐字劈成 9 块，再并最窄的相邻两块并到 5 块
        assertEquals(5, chunks.size)
        assertEquals("风吹过的地方都有光", chunks.joinToString("") { it.text })
        for (c in chunks) {
            assertTrue(c.font <= metrics.font(c.text) + 1e-9)
            assertTrue(c.font * cineramaChunkWidthEm(c.text) <= metrics.usableWidth + 1e-6)
        }
        // 各块首尾相接、单调
        for (i in 1 until chunks.size) assertEquals(chunks[i - 1].end, chunks[i].start, 1e-9)
    }

    @Test
    fun ribbonStripsMatchTheOriginal() {
        val plan = program.plans[0]
        val strips = cineramaRibbonStrips(plan.seed, 6, plan.line.text, plan.line.translation)
        assertEquals(listOf(12.0, -54.0, 53.0, -23.0, 41.0, -32.0), strips.map { it.angle })
        assertEquals(listOf(5.352885, -3.835843, 77.334479, 66.216569, 26.430635, 88.730138), strips.map { round6(it.topPct) })
        assertEquals(6.930364, strips[0].thicknessPct, 1e-6)
        assertEquals(listOf(1, 1, 1, 1, 1, -1), strips.map { it.driftDir })
        assertEquals(1.023738, strips[0].driftVelScale, 1e-6)
        assertEquals(113.3396, strips[0].shadeAngle, 1e-4)
        assertEquals("Where the wind goes, there is light", strips[1].text)
        // 漂移上限：1000 高的参考屏
        assertEquals(108.326021, cineramaRibbonDriftClamp(strips, plan.end - plan.start, 1000.0), 1e-6)

        val english = program.plans[2]
        val e = cineramaRibbonStrips(english.seed, 6, english.line.text, "")
        assertEquals(listOf(-13.0, 53.0, -42.0, 21.0, -54.0, 31.0), e.map { it.angle })
        assertEquals(51.869667, cineramaRibbonDriftClamp(e, english.end - english.start, 1000.0), 1e-6)
        val shine = program.plans[4]
        assertEquals(70.0, cineramaRibbonDriftClamp(cineramaRibbonStrips(shine.seed, 6, "Shine on", ""), shine.end - shine.start, 1000.0), 1e-9)
        // 没有字就没有丝带
        assertTrue(cineramaRibbonStrips(1, 6, "  ", "").isEmpty())
    }

    @Test
    fun ribbonAnglesAlternateAndCapShallowOnes() {
        for (seed in 1L..200L) {
            for (count in 3..9) {
                val angles = cineramaRibbonAngles(count, seed * 7919)
                assertEquals(count, angles.size)
                assertTrue(angles.count { abs(it) < 26 } <= maxOf(1, count / 3))
                // 左右轮流：头两条一定一左一右；6 条以内两侧各 5 个角度够分，整组严格交替
                assertNotEquals(angles[0] > 0, angles[1] > 0)
                if (count <= 6) for (i in 1 until count) assertNotEquals(angles[i - 1] > 0, angles[i] > 0)
                assertTrue(angles.all { abs(it) <= 60 })
            }
        }
        // 倍率放大后夹到 ±60°
        assertTrue(cineramaRibbonAngles(6, 3, angleScale = 1.4).all { abs(it) <= 60.0 })
    }

    @Test
    fun ribbonMotionIsBounded() {
        val strip = cineramaRibbonStrips(42, 6, "abc", "")[0]
        val clamp = 30.0
        assertEquals(0.0, cineramaRibbonDrift(strip, 0.0, 1000.0, clamp), 1e-9)
        assertEquals(strip.driftDir * 4 * strip.driftVelScale, cineramaRibbonDrift(strip, 1.0, 1000.0, clamp), 1e-9)
        assertEquals(strip.driftDir * clamp, cineramaRibbonDrift(strip, 1000.0, 1000.0, clamp), 1e-9)
        // 字每秒走 0.16 屏宽，走满句长 + 1 秒停住
        assertEquals(160.0, cineramaRibbonScroll(1.0, 1000.0, 4.0), 1e-9)
        assertEquals(800.0, cineramaRibbonScroll(30.0, 1000.0, 4.0), 1e-9)
        assertEquals(0.0, cineramaRibbonScroll(-1.0, 1000.0, 4.0), 1e-9)
        val stops = cineramaShadeStops(strip)
        for (i in 1 until stops.size) assertTrue(stops[i] >= stops[i - 1])
        assertEquals(1f, stops.last())
    }

    @Test
    fun weightsFallBackToEqualAndNormalize() {
        val none = CineramaWeights(0f, 0f, 0f)
        assertEquals(1.0 / 3, none.share(CineramaStyle.Hero), 1e-9)
        assertEquals(1.0 / 3, none.share(CineramaStyle.Ribbon), 1e-9)
        assertEquals(0.4, weights.share(CineramaStyle.Hero), 1e-6)
        assertEquals(0.2, weights.share(CineramaStyle.Small), 1e-6)
        val clamped = CineramaWeights(2f, -1f, 0.5f)
        assertEquals(1.0, clamped.hero, 0.0)
        assertEquals(0.0, clamped.small, 0.0)
        // 只有一种权重时整首都是它（丝带句接丝带句）
        val ribbonOnly = CineramaWeights(0f, 0f, 1f)
        assertEquals(CineramaStyle.Ribbon, cineramaPickStyle(5, CineramaStyle.Ribbon, ribbonOnly, heroOk = true, ribbonOk = true))
        // 所有版式都排不出时退小字报
        assertEquals(CineramaStyle.Small, cineramaPickStyle(5, null, CineramaWeights(1f, 0f, 1f), heroOk = false, ribbonOk = false))
    }

    @Test
    fun adjacentLinesNeverRepeatStyleOrLayout() {
        val many = List(240) { i -> line(i, i * 3000L, i * 3000L + 2500, "第${i}句歌词在这里", if (i % 3 == 0) "line $i" else "") }
        for (w in listOf(weights, CineramaWeights(1f, 0f, 1f), CineramaWeights(0f, 0f, 0f), CineramaWeights(0.1f, 1f, 0.1f))) {
            val plans = cineramaCompile(many, "777", "歌", w, "auto", segmenter).plans
            for (i in 1 until plans.size) {
                assertNotEquals("style $i", plans[i - 1].style, plans[i].style)
                assertNotEquals("layout $i", plans[i - 1].layout, plans[i].layout)
            }
            if (w.small == 0.0) assertTrue(plans.none { it.style == CineramaStyle.Small })
        }
        // 排版不再只有左右侧带两种
        val layouts = cineramaCompile(many, "777", "歌", weights, "auto", segmenter).plans.map { it.layout }.toSet()
        assertTrue(layouts.size >= 5)
        // 宽银幕组权重 0.5：出现得比居中少
        var previous: CineramaLayout? = null
        val counts = IntArray(CineramaLayout.entries.size)
        for (seed in 1L..3000L) {
            val l = cineramaPickLayout(stableHash("s$seed"), previous)
            counts[l.ordinal]++
            previous = l
        }
        assertTrue(counts[CineramaLayout.LetterboxWide.ordinal] < counts[CineramaLayout.CenterStage.ordinal])
    }

    @Test
    fun styleWeightsShapeTheMix() {
        val many = List(600) { i -> line(i, i * 3000L, i * 3000L + 2500, "歌词第${i}句", "") }
        val plans = cineramaCompile(many, "seed", "歌", CineramaWeights(1f, 0.05f, 1f), "auto", segmenter).plans
        val small = plans.count { it.style == CineramaStyle.Small }
        val hero = plans.count { it.style == CineramaStyle.Hero }
        assertTrue(small < hero / 4)
        // 丝带句没有跑马灯带，其余“自动”都有
        for (p in plans) assertEquals(p.style != CineramaStyle.Ribbon, p.marquee != null)
        val off = cineramaCompile(many.take(20), "seed", "歌", weights, "off", segmenter).plans
        assertTrue(off.all { it.marquee == null })
    }

    @Test
    fun marqueeLoopsSeamlesslyAtAConstantScreenSpeed() {
        val sps = 0.18
        val speed = 1.0
        val width = 1000.0
        for (unit in listOf(120.0, 640.0)) {
            val period = unit / (sps * speed * width)
            assertEquals(cineramaBandShift(3.3, sps, speed, width, unit), cineramaBandShift(3.3 + period, sps, speed, width, unit), 1e-9)
            // 屏上速度 = 屏宽 × 每秒几屏，和一份字多长无关
            val dt = period / 10
            val moved = (cineramaBandShift(dt, sps, speed, width, unit) - cineramaBandShift(0.0, sps, speed, width, unit)) * unit
            assertEquals(sps * speed * width * dt, moved, 1e-6)
        }
        // 带速设置翻倍，位移也翻倍
        val a = cineramaBandShift(0.1, sps, 1.0, width, 500.0)
        val b = cineramaBandShift(0.1, sps, 2.0, width, 500.0)
        assertEquals(a * 2, b, 1e-9)
        // 量不到长度时按兜底周期，不停
        assertTrue(cineramaBandShift(1.0, sps, 1.0, width, 0.0) > 0)
        for (shift in listOf(0.0, 0.25, 0.999)) {
            val top = cineramaBandSlot(shift, 1, bottom = false, mirror = true)
            assertTrue(top <= 0 && top > -1)
            assertTrue(cineramaBandSlot(shift, -1, bottom = false, mirror = true) in -1.0..0.0)
            // 镜像的下带（转 180°）在自己的坐标里和上带同相；反向的下带和上带对称
            assertEquals(cineramaFrac(top), cineramaFrac(cineramaBandSlot(shift, 1, bottom = true, mirror = true)), 1e-9)
            assertEquals(-1 - top, cineramaBandSlot(shift, 1, bottom = true, mirror = false), 1e-9)
        }
    }

    @Test
    fun ringFitsWholeCopiesIntoOneLap() {
        val lap = cineramaRingLap(1000.0, 500.0, 20.0)
        assertEquals(2 * 960.0 + 2 * 460.0 + 2 * PI * 20, lap, 1e-9)
        val (copies, spacing) = cineramaRingCopies(lap, 230.0, 6)
        assertEquals(lap, copies * (230.0 + spacing * 6), 1e-6)
        assertTrue(spacing >= -0.15 * 230.0 / 6)
        // 份数按 JS 的 Math.round：4.5 份进成 5 份（字距压 10%，没超过 15% 的限）
        assertEquals(5 to -2.5, cineramaRingCopies(450.0, 100.0, 4))
        assertEquals(4 to 5.0, cineramaRingCopies(1000.0, 230.0, 4))
        // 1.6 份进成 2 份要压 20%，太紧，少放一份、把字距放开
        assertEquals(1 to 15.0, cineramaRingCopies(160.0, 100.0, 4))
        assertEquals(1 to 0.0, cineramaRingCopies(0.0, 10.0, 2))
        for (shift in listOf(0.0, 0.3, 0.9)) {
            val o = cineramaRingOffset(shift, 1, 50.0)
            assertTrue(o >= 0 && o < 50.0)
            assertEquals(50.0 - o, cineramaRingOffset(shift, -1, 50.0).let { if (it == 0.0) 50.0 else it }, 1e-9)
        }
    }

    @Test
    fun transitionsCrossfadeAdjacentLines() {
        val frame = CineramaFrame()
        val starts = program.starts
        val ends = program.ends
        assertEquals(0.28, cineramaTransitionSec(10.0, 14.5), 1e-9)
        assertEquals(0.14, cineramaTransitionSec(10.0, 10.3), 1e-9)
        assertTrue(cineramaAdjacent(14.5, 14.5))
        assertFalse(cineramaAdjacent(14.0, 15.0))
        // 第一句之前什么都不画
        cineramaEvaluate(starts, ends, 9.0, still = false, between = false, frame = frame)
        assertEquals(-1, frame.current)
        // 入场：开口时透明，0.28 秒后全亮
        cineramaEvaluate(starts, ends, 10.0, false, false, frame)
        assertEquals(0, frame.current)
        assertEquals(0f, frame.now.opacity, 1e-6f)
        cineramaEvaluate(starts, ends, 12.0, false, false, frame)
        assertEquals(1f, frame.now.opacity, 1e-6f)
        assertEquals(-1, frame.outgoing)
        // 紧邻的下一句开口：上一句开始溶解退场、它的带子前半程淡出，这一句的带子后半程淡入
        cineramaEvaluate(starts, ends, 14.5, false, false, frame)
        assertEquals(1, frame.current)
        assertEquals(0, frame.outgoing)
        assertEquals(1f, frame.out.opacity, 1e-6f)
        assertEquals(0f, frame.now.bands, 1e-6f)
        cineramaEvaluate(starts, ends, 14.5 + 0.14, false, false, frame)
        assertEquals(0f, frame.out.bands, 1e-6f)
        assertEquals(0f, frame.now.bands, 1e-6f)
        cineramaEvaluate(starts, ends, 14.5 + 0.27, false, false, frame)
        assertTrue(frame.now.bands > 0.9f)
        cineramaEvaluate(starts, ends, 14.5 + 0.3, false, false, frame)
        assertEquals(-1, frame.outgoing)
        // 最后一句不紧邻：行窗口末尾溶解退场，之后不画
        cineramaEvaluate(starts, ends, 28.55, false, false, frame)
        assertTrue(frame.now.opacity < 0.5f)
        cineramaEvaluate(starts, ends, 40.0, false, false, frame)
        assertEquals(0f, frame.now.opacity, 1e-6f)
        assertEquals(0f, frame.now.bands, 1e-6f)
    }

    @Test
    fun stillFrameShowsTheWholeLine() {
        val frame = CineramaFrame()
        cineramaEvaluate(program.starts, program.ends, 10.0, still = true, between = false, frame = frame)
        assertEquals(0, frame.current)
        assertEquals(1f, frame.now.opacity, 0f)
        assertEquals(1f, frame.now.bands, 0f)
        assertEquals(-1, frame.outgoing)
        // 句间：紧邻下一句时留着，不紧邻（最后一句之后）收起
        cineramaEvaluate(program.starts, program.ends, 14.0, still = true, between = true, frame = frame)
        assertEquals(1f, frame.now.opacity, 0f)
        cineramaEvaluate(program.starts, program.ends, 28.0, still = true, between = true, frame = frame)
        assertEquals(0f, frame.now.opacity, 0f)
    }

    @Test
    fun heroChunksFlashInAndFill() {
        val w = 100.0 * 16 / 9
        val chunks = cineramaHeroChunks(program.plans[0], CineramaHeroMetrics.of(w, 100.0, w, 70.0, 0.0), segmenter)
        val c = chunks[2]
        val look = CineramaChunkLook()
        assertFalse(cineramaChunkLook(c, c.start - 0.01, look))
        assertFalse(cineramaChunkLook(c, c.end + 0.01, look))
        // 淡入 0.108 秒，入场从 0.92 放大
        assertTrue(cineramaChunkLook(c, c.start + 0.05, look))
        assertTrue(look.opacity in 0.1f..0.9f)
        assertTrue(cineramaChunkLook(c, c.start + 0.5, look))
        assertEquals(1f, look.opacity, 1e-6f)
        assertEquals(((0.5) / (c.end - c.start)).toFloat(), look.fill, 1e-5f)
        // 在窗口末尾前 0.072 秒已经看不见（给下一块让出空当）
        assertFalse(cineramaChunkLook(c, c.end - 0.05, look))
        // 拉远：缩放从 1.5 收到 0.93、从右边 1.5% 滑回来；块只有 0.44 秒，按 0.44 / 1.2 减弱
        val pull = chunks[1]
        assertTrue(cineramaChunkLook(pull, pull.start + 0.2, look))
        val span = pull.end - pull.start
        val u = 0.2 / span
        val amount = span / 1.2
        assertEquals((1.5 * (1 - u) * amount).toFloat(), look.driftX, 1e-5f)
        val glide = 1 + ((1 - u) * 0.5 + u * (0.93 - 1)) * amount
        assertEquals(glide.toFloat(), look.scale, 1e-5f)
        assertEquals(0.0, cineramaFillChance(1), 1e-12)
        assertEquals(2.0 / 15, cineramaFillChance(5), 1e-12)
        assertEquals(2.0 / 15, cineramaFillChance(12), 1e-12)
        assertTrue(cineramaFillChance(3) in 0.01..0.12)
    }

    @Test
    fun smallTypeUnitsRiseInOneByOne() {
        assertEquals(0.0, cineramaUnitProgress(1.0, 2.0, 0.99), 0.0)
        assertEquals(0.5, cineramaUnitProgress(1.0, 2.0, 1.09), 1e-9)
        assertEquals(1.0, cineramaUnitProgress(1.0, 2.0, 1.2), 0.0)
        // 很短的单元按时长的一半入场
        assertEquals(1.0, cineramaUnitProgress(1.0, 1.1, 1.05), 1e-9)
        val size = FloatArray(2)
        val widths = floatArrayOf(40f, 10f, 50f, 10f, 30f, 10f, 70f)
        val blank = booleanArrayOf(false, true, false, true, false, true, false)
        val pos = cineramaFlow(widths, blank, 20f, 120f, size)
        // 第一行“40 空 50”= 100，第三个词放不下就换行；第二行“30 空 70”= 110 最宽，第一行居中右移 5
        assertEquals(110f, size[0], 0f)
        assertEquals(40f, size[1], 0f)
        assertEquals(5f, pos[0], 0f)
        assertEquals(0f, pos[1], 0f)
        assertEquals(55f, pos[4], 0f)
        assertEquals(0f, pos[8], 0f)
        assertEquals(20f, pos[9], 0f)
        assertEquals(40f, pos[12], 0f)
        // 换行处的空白不画，也不占下一行的行首
        val pos2 = cineramaFlow(floatArrayOf(50f, 10f, 50f, 10f, 40f), booleanArrayOf(false, true, false, true, false), 20f, 110f, size)
        assertTrue(pos2[6].isNaN())
        assertEquals(35f, pos2[8], 0f)
        assertEquals(20f, pos2[9], 0f)
        assertEquals(110f, size[0], 0f)
    }

    @Test
    fun splitsKeepTheText() {
        val text = "我们走过长长的街，看见风里的光亮"
        assertEquals(text, cineramaSplitBalanced(text, 3).joinToString(""))
        assertEquals(text, cineramaSplitBalanced(text, 2).joinToString(""))
        // 对半在逗号后断开；没标点时按 JS 的四舍五入落刀（9 个字切在第 5 个字后）
        assertEquals(listOf("我们走过长长的街，", "看见风里的光亮"), cineramaSplitBalanced(text, 2))
        assertEquals(listOf("一二三四五", "六七八九"), cineramaSplitBalanced("一二三四五六七八九", 2))
        assertEquals(listOf("我们走过长长的街", "，", "看见风里的光亮"), cineramaSplitClauses(text))
        // 不劈开代理对
        val emoji = "🎵🎵🎵🎵🎵🎵🎵"
        assertTrue(cineramaSplitBalanced(emoji, 2).all { !it.last().isHighSurrogate() })
        assertEquals(listOf("Hello ", "bright ", "world,"), cineramaWords("Hello bright world,", segmenter))
        // 句首的标点自成一段，空白并进前一个词
        assertEquals(listOf("“", "我们 ", "走"), cineramaWords("“我们 走", CineramaSegmenter { listOf("“", "我们", " ", "走") }))
    }

    @Test
    fun glowFollowsAGaussianTail() {
        assertEquals(1.0, cineramaErfc(0.0), 1e-7)
        assertEquals(0.157299207, cineramaErfc(1.0), 1e-6)
        assertEquals(1.842700793, cineramaErfc(-1.0), 1e-6)
        val glow = cineramaEdgeGlow(100.0)
        // 亮芯 + 中间层在带沿各是一半浓度：1 − (1 − 0.685/2)(1 − 0.288/2)
        assertEquals(1 - (1 - 0.685 / 2) * (1 - 0.42 * 0.685 / 2), glow.screen[0].toDouble(), 1e-4)
        assertEquals(0.22 * 0.685 / 2, glow.band[0].toDouble(), 1e-4)
        for (i in 1 until glow.screen.size) {
            assertTrue(glow.screen[i] <= glow.screen[i - 1])
            assertTrue(glow.band[i] <= glow.band[i - 1])
        }
        assertTrue(glow.screen.last() < 0.01f)
        assertEquals(1.5f * 15f, glow.extent, 1e-4f)
    }

    @Test
    fun settingsAreDeclaredLikeTheOriginal() {
        val s = CineramaVisualizer.settings
        assertEquals(listOf("marquee", "heroWeight", "smallWeight", "ribbonWeight", "fontScale", "marqueeSpeed"), s.map { it.key })
        assertEquals(24, CineramaVisualizer.hiddenSettings)
        assertEquals(30, s.size + CineramaVisualizer.hiddenSettings)
        val values = SettingValues(s, emptyMap())
        assertEquals("auto", values.choice("marquee"))
        assertEquals(0.4f, values.number("heroWeight"), 0f)
        assertEquals(0.2f, values.number("smallWeight"), 0f)
        assertEquals(1f, values.number("marqueeSpeed"), 0f)
        assertEquals("cinerama", CineramaVisualizer.id)
        assertFalse(CineramaVisualizer.drawsWithoutLyrics)
        assertEquals(CineramaVisualizer, LyricVisualizers.byId("cinerama"))
    }

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

    private companion object {
        val SEGMENTS = mapOf(
            "风吹过的地方都有光" to listOf("风", "吹", "过的", "地方", "都有", "光"),
            "我们沿着河走回家" to listOf("我们", "沿着", "河", "走", "回家"),
            "Hello bright world, sing along" to listOf("Hello", " ", "bright", " ", "world", ",", " ", "sing", " ", "along"),
            "一二三四五六七八九十" to listOf("一", "二三四", "五", "六", "七", "八", "九十"),
            "Shine on" to listOf("Shine", " ", "on"),
            "Hello bright world," to listOf("Hello", " ", "bright", " ", "world", ","),
        )
    }
}
