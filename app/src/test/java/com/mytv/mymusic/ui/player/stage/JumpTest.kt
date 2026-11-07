package com.mytv.mymusic.ui.player.stage

import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.player.stage.modes.JUMP_CUR
import com.mytv.mymusic.ui.player.stage.modes.JUMP_NEXT
import com.mytv.mymusic.ui.player.stage.modes.JUMP_PREV
import com.mytv.mymusic.ui.player.stage.modes.JUMP_REFERENCE_PITCH
import com.mytv.mymusic.ui.player.stage.modes.JUMP_TEXT_FILL
import com.mytv.mymusic.ui.player.stage.modes.JumpConfig
import com.mytv.mymusic.ui.player.stage.modes.JumpCourse
import com.mytv.mymusic.ui.player.stage.modes.JumpFace
import com.mytv.mymusic.ui.player.stage.modes.JumpFrameInput
import com.mytv.mymusic.ui.player.stage.modes.JumpLineTimes
import com.mytv.mymusic.ui.player.stage.modes.JumpPose
import com.mytv.mymusic.ui.player.stage.modes.JumpProjection
import com.mytv.mymusic.ui.player.stage.modes.JumpRunnerState
import com.mytv.mymusic.ui.player.stage.modes.JumpShape
import com.mytv.mymusic.ui.player.stage.modes.JumpVisualizer
import com.mytv.mymusic.ui.player.stage.modes.jumpArcLift
import com.mytv.mymusic.ui.player.stage.modes.jumpBlockShape
import com.mytv.mymusic.ui.player.stage.modes.jumpBoardRow
import com.mytv.mymusic.ui.player.stage.modes.jumpBox
import com.mytv.mymusic.ui.player.stage.modes.jumpBudget
import com.mytv.mymusic.ui.player.stage.modes.jumpBuildCourse
import com.mytv.mymusic.ui.player.stage.modes.jumpBurstFade
import com.mytv.mymusic.ui.player.stage.modes.jumpBurstRadius
import com.mytv.mymusic.ui.player.stage.modes.jumpCharTimings
import com.mytv.mymusic.ui.player.stage.modes.jumpChunkCap
import com.mytv.mymusic.ui.player.stage.modes.jumpChunks
import com.mytv.mymusic.ui.player.stage.modes.jumpFaceOpacity
import com.mytv.mymusic.ui.player.stage.modes.jumpFormatDegrees
import com.mytv.mymusic.ui.player.stage.modes.jumpFrame
import com.mytv.mymusic.ui.player.stage.modes.jumpHopSquash
import com.mytv.mymusic.ui.player.stage.modes.jumpIsWide
import com.mytv.mymusic.ui.player.stage.modes.jumpPillarHeight
import com.mytv.mymusic.ui.player.stage.modes.jumpPillarSpring
import com.mytv.mymusic.ui.player.stage.modes.jumpPose
import com.mytv.mymusic.ui.player.stage.modes.jumpRestPillar
import com.mytv.mymusic.ui.player.stage.modes.jumpRowReveal
import com.mytv.mymusic.ui.player.stage.modes.jumpRowTilt
import com.mytv.mymusic.ui.player.stage.modes.jumpRunnerCells
import com.mytv.mymusic.ui.player.stage.modes.jumpRunnerState
import com.mytv.mymusic.ui.player.stage.modes.jumpSegments
import com.mytv.mymusic.ui.player.stage.modes.jumpShiftLanding
import com.mytv.mymusic.ui.player.stage.modes.jumpSplitChunks
import com.mytv.mymusic.ui.player.stage.modes.jumpStillFrame
import com.mytv.mymusic.ui.player.stage.modes.jumpStillPose
import com.mytv.mymusic.ui.player.stage.modes.jumpTiltAmount
import com.mytv.mymusic.ui.player.stage.modes.jumpTimeline
import com.mytv.mymusic.ui.player.stage.modes.jumpTrailFade
import com.mytv.mymusic.ui.player.stage.modes.jumpTranslationBlock
import com.mytv.mymusic.ui.player.stage.modes.jumpTranslationLine
import com.mytv.mymusic.ui.player.stage.modes.jumpTranslationReveal
import com.mytv.mymusic.ui.player.stage.modes.jumpTypeBox
import com.mytv.mymusic.ui.player.stage.modes.jumpWindows
import com.mytv.mymusic.ui.player.stage.modes.jumpWrapRows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class JumpTest {

    // ---- 夹具：按码位拆的分词、按字身估的字宽 ----

    private val perChar: (String) -> List<String> = { text ->
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val n = Character.charCount(text.codePointAt(i))
            out += text.substring(i, i + n)
            i += n
        }
        out
    }

    private val estimate: (String) -> Float = { text ->
        var w = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            w += if (Character.isWhitespace(cp)) 0.35f else if (jumpIsWide(cp)) 1f else 0.58f
            i += Character.charCount(cp)
        }
        w
    }

    private fun words(text: String, startMs: Long, stepMs: Long): List<LyricWord> =
        perChar(text).mapIndexed { i, s -> LyricWord(startMs + i * stepMs, startMs + (i + 1) * stepMs, s) }

    private fun line(index: Int, startMs: Long, endMs: Long, text: String, words: List<LyricWord> = emptyList(), translation: String = "") =
        StageLine(index, startMs, endMs, text, words, translation, "")

    private val config = JumpConfig(JumpFace.Top, 34f, 2, 0.45f, 1.2f)

    private fun course(lines: List<StageLine>, cfg: JumpConfig = config): JumpCourse = jumpBuildCourse(lines, cfg, estimate, perChar)

    /** 两句逐字歌词：每字 0.6 秒，句间 0.9 秒（不够立歇脚柱）。 */
    private val sung = listOf(
        line(0, 10_000, 14_000, "风吹过的地方", words("风吹过的地方", 10_000, 600)),
        line(1, 14_500, 18_500, "我们沿着河走", words("我们沿着河走", 14_500, 600)),
        line(2, 19_000, 23_000, "回到家门口", words("回到家门口", 19_000, 600)),
    )

    // ---- 声明 ----

    @Test
    fun declaresTheModeLikeTheOriginal() {
        assertEquals("jump", JumpVisualizer.id)
        assertEquals(80, JumpVisualizer.order)
        assertEquals(ModOrigin("visualizer-jump", "1.2.0", "tumuyan", "AGPL-3.0"), JumpVisualizer.origin)
        assertTrue(JumpVisualizer.hostBackground)
        assertFalse(JumpVisualizer.hostSubtitles)
        assertFalse(JumpVisualizer.wantsAudio)
        assertFalse(JumpVisualizer.drawsWithoutLyrics)
        assertEquals(19, JumpVisualizer.hiddenSettings)
        assertEquals(
            listOf("textFace", "pitch", "chunkChars", "flySeconds", "arc", "translationMode"),
            JumpVisualizer.settings.map { it.key },
        )
        assertEquals(25, JumpVisualizer.settings.size + JumpVisualizer.hiddenSettings)
        assertEquals("34°", jumpFormatDegrees(34f))
        val values = SettingValues(JumpVisualizer.settings, emptyMap())
        assertEquals("top", values.choice("textFace"))
        assertEquals(34f, values.number("pitch"), 0f)
        assertTrue(values.toggle("translationMode"))
    }

    // ---- 切块 ----

    @Test
    fun cjkMergesByChunkChars() {
        val segments = perChar("风吹过的地方")
        assertEquals(listOf("风吹", "过的", "地方"), jumpSplitChunks(segments, 2).map { it.display })
        assertEquals(listOf("风吹过", "的地方"), jumpSplitChunks(segments, 3).map { it.display })
        assertEquals(6, jumpSplitChunks(segments, 1).size)
        // 词典分词给的词超宽时按字宽切，切下来的零头不再和后面的词合（照原版：先合词、再切）
        assertEquals(listOf("风吹", "过", "的", "地方"), jumpSplitChunks(listOf("风吹过", "的", "地方"), 2).map { it.display })
        assertEquals(listOf("风吹", "过的", "地方"), jumpSplitChunks(listOf("风吹", "过", "的", "地方"), 2).map { it.display })
    }

    @Test
    fun latinChunksAreWholeWords() {
        val segments = listOf("I", " ", "love", " ", "you", " ", "so", " ", "much")
        val chunks = jumpSplitChunks(segments, 2)
        assertEquals(listOf("I", "love", "you", "so", "much"), chunks.map { it.display })
        // 空白留在块尾，码位数仍然加起来是整句
        assertEquals("I love you so much".length, chunks.sumOf { it.charCount })
        // 连字符之后才能断
        assertEquals(listOf("rock-", "and-", "roll"), jumpSplitChunks(listOf("rock-and-roll"), 2).map { it.display })
    }

    @Test
    fun punctuationSticksToThePreviousChunk() {
        val chunks = jumpSplitChunks(perChar("你好，世界！"), 2)
        assertEquals(listOf("你好，", "世界！"), chunks.map { it.display })
    }

    @Test
    fun tooManyChunksWidenTheChunkSize() {
        assertEquals(2, jumpChunkCap(1))
        assertEquals(3, jumpChunkCap(3))
        assertEquals(5, jumpChunkCap(40))
        assertEquals(5, jumpChunkCap(0))
        val text = "一二三四五六七八九十一二三四"
        val chunks = jumpChunks(perChar(text), 1, text.length)
        assertEquals(5, chunks.size)
        assertEquals(listOf(3, 3, 3, 3, 2), chunks.map { it.display.length })
        // 拉丁词劈不开：放宽一次没变少就停
        val latin = listOf("a ", "b ", "c ", "d ", "e ", "f ", "g")
        assertEquals(7, jumpChunks(latin, 1, 13).size)
    }

    @Test
    fun segmentsPreferWordTimings() {
        val w = listOf(LyricWord(0, 500, "Hello "), LyricWord(500, 900, "world"))
        assertEquals(listOf("Hello ", "world"), jumpSegments("Hello world", w, perChar))
        // 拼不上（缺空格）就交给分词
        val loose = listOf(LyricWord(0, 500, "Hello"), LyricWord(500, 900, "world"))
        assertEquals(perChar("Hello world"), jumpSegments("Hello world", loose, perChar))
        assertEquals(listOf("你好"), jumpSegments("你好", emptyList()) { listOf("你", "x") })
    }

    // ---- 每字时刻 ----

    @Test
    fun charTimingsFollowWordsOrSplitEvenly() {
        val w = listOf(LyricWord(1000, 1500, "风"), LyricWord(1500, 2000, "吹"))
        assertArrayEquals(doubleArrayOf(1.0, 1.5, 1.5, 2.0), jumpCharTimings("风吹", 1.0, 2.0, w), 1e-9)
        // 一个跨整句的词 = 没有逐字时间，整句均分
        val whole = listOf(LyricWord(0, 4000, "abcd"))
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 1.0, 2.0, 2.0, 3.0, 3.0, 4.0), jumpCharTimings("abcd", 0.0, 4.0, whole), 1e-9)
        // 句末不比句首晚：按字数估（至少 0.5 秒）
        assertArrayEquals(doubleArrayOf(5.0, 5.25, 5.25, 5.5), jumpCharTimings("ab", 5.0, 5.0, emptyList()), 1e-9)
        // 词里缺空格：按非空白字对齐，空白取前一个字的收尾
        val loose = listOf(LyricWord(0, 1000, "hello"), LyricWord(1000, 2000, "world"))
        val t = jumpCharTimings("hello world", 0.0, 2.0, loose)
        assertEquals(0.8, t[4 * 2], 1e-9)
        assertEquals(1.0, t[5 * 2], 1e-9)
        assertEquals(1.0, t[6 * 2], 1e-9)
    }

    // ---- 时间窗与时间表 ----

    @Test
    fun windowsReserveAHandoverBeforeTheNextLine() {
        val w = jumpWindows(
            listOf(
                JumpLineTimes(10.0, 14.0, Double.NaN, Double.NaN),
                JumpLineTimes(14.5, 18.5, Double.NaN, Double.NaN),
            ),
        )
        assertEquals(10.0, w[0].writeStart, 1e-9)
        assertEquals(14.0, w[0].writeEnd, 1e-9)
        assertEquals(14.0, w[0].afterStart, 1e-9)
        assertEquals(14.5, w[0].afterEnd, 1e-9)
        // 最后一句：收尾窗到句末，唱完至少留 0.16 秒
        assertEquals(18.5, w[1].writeEnd, 1e-9)
        assertEquals(18.34, w[1].afterStart, 1e-9)
        // 有逐字时间时从第一个字开口算起，下一句开口前留 0.12..0.28 秒
        val sungWindows = jumpWindows(
            listOf(JumpLineTimes(10.0, 20.0, 10.4, 12.0), JumpLineTimes(12.5, 16.0, 12.6, 15.0)),
        )
        assertEquals(10.4, sungWindows[0].writeStart, 1e-9)
        assertEquals(12.6 - 0.28, sungWindows[0].writeEnd, 1e-9)
        assertEquals(12.0, sungWindows[0].afterStart, 1e-9)
        assertEquals(12.6, sungWindows[0].afterEnd, 1e-9)
    }

    @Test
    fun hopsLandOnTheFirstCharAndLiftOneFlightEarlier() {
        val tl = jumpTimeline(10.0, 14.0, 3, 0.45, doubleArrayOf(10.0, 11.0, 12.5), 14.0, null, Double.NaN, 14.5)
        assertArrayEquals(doubleArrayOf(10.0, 11.0, 12.5), tl.landings, 1e-9)
        assertEquals(10.55, tl.hops[0].liftTime, 1e-9)
        assertEquals(11.0, tl.hops[0].landTime, 1e-9)
        // 第二跳窗口 1.5 秒，滞空 0.45 秒（占三成）
        assertEquals(12.05, tl.hops[1].liftTime, 1e-9)
        assertArrayEquals(doubleArrayOf(10.0, 11.0, 12.5), tl.pressTimes, 1e-9)
        assertArrayEquals(doubleArrayOf(10.55, 12.05, 14.0), tl.releaseTimes, 1e-9)
        assertArrayEquals(doubleArrayOf(10.0, 11.525, 13.25, 14.5), tl.cameraTimes, 1e-9)
        // 跨行：唱完那一刻起跳、下一句开口（不晚于唱完 + 1.5 倍滞空）落地
        val shift = tl.gapShift!!
        assertEquals(14.0, shift.begin, 1e-9)
        assertEquals(14.5, shift.end, 1e-9)
        assertEquals(14.5, tl.shiftLand, 1e-9)
        assertEquals(-1, tl.restIndex)
        // 太挤的块至少隔开 minGap，滞空最多占九成五
        val tight = jumpTimeline(10.0, 14.0, 2, 0.45, doubleArrayOf(10.0, 10.05), 14.0, null, Double.NaN, Double.NaN)
        assertEquals(10.3, tight.landings[1], 1e-9)
        assertEquals(10.3 - 0.3 * 0.95, tight.hops[0].liftTime, 1e-9)
    }

    @Test
    fun longGapsGetARestPillar() {
        assertNull(jumpRestPillar(14.0, 15.0, 0.45))
        val rest = jumpRestPillar(14.0, 20.0, 0.45)!!
        assertEquals(14.675, rest[0], 1e-9)
        assertEquals(19.325, rest[1], 1e-9)
        assertEquals(20.0, jumpShiftLanding(14.0, 20.0, 0.45), 1e-9)
        assertEquals(14.5, jumpShiftLanding(14.0, 14.5, 0.45), 1e-9)
        assertEquals(14.675, jumpShiftLanding(14.0, 15.0, 0.45), 1e-9)
        assertTrue(jumpShiftLanding(14.0, 13.0, 0.45).isNaN())

        val tl = jumpTimeline(10.0, 14.0, 3, 0.45, doubleArrayOf(10.0, 11.0, rest[0]), 14.0, rest, Double.NaN, 20.0)
        assertEquals(2, tl.restIndex)
        assertEquals(14.0, tl.hops[1].liftTime, 1e-9)
        assertEquals(14.675, tl.hops[1].landTime, 1e-9)
        assertEquals(19.325, tl.releaseTimes[2], 1e-9)
        val shift = tl.gapShift!!
        assertEquals(19.325, shift.begin, 1e-9)
        assertEquals(20.0, shift.end, 1e-9)
        assertNull(tl.shiftHold)

        // 整首排成路时，句间空 6 秒的那一句末尾多一根圆柱
        val c = course(listOf(line(0, 10_000, 14_000, "风吹过的地方"), line(1, 20_000, 24_000, "我们沿着河走")))
        assertTrue(c.rows[0].units.last().rest)
        assertNotNull(c.rows[0].units.last().cylinder)
        assertFalse(c.rows[1].units.last().rest)
    }

    @Test
    fun runnerStateFollowsTheTimeline() {
        val tl = jumpTimeline(10.0, 14.0, 3, 0.45, doubleArrayOf(10.0, 11.0, 12.5), 14.0, null, Double.NaN, 14.5)
        val s = JumpRunnerState()
        jumpRunnerState(tl, 9.0, s)
        assertEquals(0, s.from); assertFalse(s.airborne); assertEquals(0.0, s.progress, 0.0)
        jumpRunnerState(tl, 10.3, s)
        assertEquals(0, s.to); assertFalse(s.airborne)
        jumpRunnerState(tl, 10.775, s)
        assertEquals(0, s.from); assertEquals(1, s.to); assertTrue(s.airborne); assertEquals(0.5, s.progress, 1e-9)
        jumpRunnerState(tl, 11.5, s)
        assertEquals(1, s.from); assertEquals(1, s.to); assertFalse(s.airborne)
        // 跨行那一跳：停在末块，进度给跨行那一跳
        jumpRunnerState(tl, 14.25, s)
        assertEquals(2, s.from); assertTrue(s.airborne); assertEquals(0.5, s.progress, 1e-9)
        jumpRunnerState(tl, 30.0, s)
        assertEquals(2, s.to); assertFalse(s.airborne)
    }

    // ---- 投影与取景 ----

    @Test
    fun projectionIsTheFixedIsometricMatrix() {
        val p = JumpProjection(34f)
        val s = sin(34.0 * PI / 180).toFloat()
        val c = cos(34.0 * PI / 180).toFloat()
        assertEquals(c / sqrt(2f), p.lift, 1e-6f)
        assertEquals(-0.5f, p.x(1f, 0f), 1e-6f)
        assertEquals(0.5f, p.x(0f, 1f), 1e-6f)
        assertEquals(-0.5f * s, p.y(1f, 0f, 0f), 1e-6f)
        assertEquals(-p.lift, p.y(0f, 0f, 1f), 1e-6f)
        // 线性：任意一点 = 三根轴的组合
        assertEquals(2 * -0.5f + 3 * 0.5f, p.x(2f, 3f), 1e-6f)
        assertEquals(-0.5f * s * 5 - 4 * p.lift, p.y(2f, 3f, 4f), 1e-5f)
        // 俯角越大柱子越矮、台阶越陡
        val steep = JumpProjection(60f)
        assertTrue(steep.lift < p.lift)
        assertTrue(steep.y(1f, 0f, 0f) < p.y(1f, 0f, 0f))
    }

    private val frameInput = JumpFrameInput(JumpFace.Top, 1.9f, 2.5f, 2.5f, 1.2f, 1.2f, 1.2f)

    @Test
    fun frameFitsTheWholeBudgetOnALargeStage() {
        val f = jumpFrame(1280f, 720f, 34f, frameInput, 26f)
        val b = jumpBudget(34f, frameInput)
        assertTrue(f.cellPx > 26f)
        assertTrue((b.below + b.above) * f.cellPx <= 720f + 1e-3f)
        assertEquals(b.below * f.cellPx, f.anchorBelowPx, 1e-3f)
        // 俯角往下调不放大画面
        assertTrue(jumpFrame(1280f, 720f, 20f, frameInput, 26f).cellPx <= f.cellPx + 1e-4f)
        assertEquals(f.cellPx, jumpFrame(1280f, 720f, JUMP_REFERENCE_PITCH, frameInput, 26f).cellPx, 1e-4f)
    }

    @Test
    fun shortStagesKeepTheFloorAndTheRunnerInView() {
        // 桌面姿态：安全区很宽很矮
        val f = jumpFrame(1500f, 280f, 34f, frameInput, 26f)
        val b = jumpBudget(34f, frameInput)
        assertEquals(26f, f.cellPx, 1e-4f)
        assertTrue(f.anchorBelowPx >= b.crown * f.cellPx - 1e-3f)
        assertTrue(280f - f.anchorBelowPx >= b.crownTop * f.cellPx - 1e-3f)
        // 再矮到连人都装不下：格子缩回来
        val tiny = jumpFrame(600f, 60f, 34f, frameInput, 26f)
        assertTrue((b.crown + b.crownTop) * tiny.cellPx <= 60f)
    }

    // ---- 柱子与贴面字 ----

    @Test
    fun blockShapesFollowTheText() {
        val two = jumpBlockShape(JumpFace.Top, 2f, 1.9f)
        assertEquals(5f, two.len, 1e-5f)
        assertEquals(2.5f, two.depth, 1e-5f)
        // 侧面：块长固定，端墙随字宽；柱高放宽一档
        assertEquals(2.47f, jumpPillarHeight(JumpFace.Side, 1.9f), 1e-5f)
        val side = jumpBlockShape(JumpFace.Side, 2f, 2.47f)
        assertEquals(1.8f, side.len, 1e-5f)
        assertEquals(5f, side.depth, 1e-5f)
        assertEquals(12f, jumpBlockShape(JumpFace.Top, 40f, 1.9f).len, 1e-5f)
    }

    @Test
    fun typeBoxFillsTheFaceWithoutStretching() {
        assertArrayEquals(floatArrayOf(5f * JUMP_TEXT_FILL, 2.5f * JUMP_TEXT_FILL), jumpTypeBox(5f, 2.5f, 2f), 1e-5f)
        val wide = jumpTypeBox(2f, 2f, 4f)
        assertEquals(1.56f, wide[0], 1e-5f)
        assertEquals(0.39f, wide[1], 1e-5f)
        val tall = jumpTypeBox(10f, 1f, 1f)
        assertEquals(tall[0], tall[1], 1e-5f)
    }

    @Test
    fun labelsReadLeftToRightAndSitOnTheFaceCenter() {
        val p = JumpProjection(34f)
        val shape = JumpShape(5f, 2.5f, 1.9f)
        for (face in listOf(JumpFace.Top, JumpFace.Front, JumpFace.Side)) {
            for (alongX in listOf(false, true)) {
                val box = jumpBox(p, alongX, shape, face, 2f)
                val m = box.label
                // 字的横排方向朝屏幕右边（背着镜头的面已经翻过来）
                assertTrue("$face $alongX", m[0] > 0f)
                val quad = when (face) {
                    JumpFace.Front -> box.front
                    JumpFace.Side -> box.side
                    else -> box.top
                }
                val cx = (quad[0] + quad[2] + quad[4] + quad[6]) / 4f
                val cy = (quad[1] + quad[3] + quad[5] + quad[7]) / 4f
                val x = m[0] * box.labelW / 2f + m[2] * box.labelH / 2f + m[4]
                val y = m[1] * box.labelW / 2f + m[3] * box.labelH / 2f + m[5]
                assertEquals(cx, x, 1e-4f)
                assertEquals(cy, y, 1e-4f)
            }
        }
        // 悬浮的字正立在柱顶之上
        val above = jumpBox(p, false, shape, JumpFace.Above, 2f)
        assertEquals(1f, above.label[0], 0f)
        assertEquals(0f, above.label[1], 0f)
        assertTrue(above.label[5] + above.labelH < above.top[1] + 1e-4f || above.minY <= above.label[5])
    }

    // ---- 跳跃、柱子弹簧、落地环 ----

    @Test
    fun hopSquashChargesStretchesAndLands() {
        assertEquals(0.92f, jumpHopSquash(0.1f), 2e-3f)
        assertEquals(0.9f, jumpHopSquash(0.9f), 2e-3f)
        assertTrue(jumpHopSquash(0.5f) > 1.04f)
        for (i in 0..100) {
            val v = jumpHopSquash(i / 100f)
            assertTrue(v in 0.88f..1.07f)
        }
    }

    @Test
    fun pillarSinksThenSpringsBack() {
        assertEquals(0f, jumpPillarSpring(9.9, 10.0, 11.0), 0f)
        assertEquals(0.45f, jumpPillarSpring(10.09, 10.0, 11.0), 1e-4f)
        assertEquals(0.45f, jumpPillarSpring(10.8, 10.0, 11.0), 1e-4f)
        assertEquals(0.45f, jumpPillarSpring(11.0, 10.0, 11.0), 1e-4f)
        // 半个周期后弹过头
        assertTrue(jumpPillarSpring(11.15, 10.0, 11.0) < 0f)
        assertEquals(0f, jumpPillarSpring(12.5, 10.0, 11.0), 0f)
        assertEquals(0f, jumpPillarSpring(10.5, 10.0, 10.0), 0f)
        // 还没离开（release 未定）就一直压着
        assertEquals(0.45f, jumpPillarSpring(20.0, 10.0, Double.NaN), 1e-4f)
    }

    @Test
    fun burstSpreadsAndFades() {
        assertEquals(0.62f, jumpBurstRadius(0f), 1e-6f)
        assertEquals(1f, jumpBurstRadius(1f), 1e-6f)
        assertEquals(1f, jumpBurstFade(0f), 1e-6f)
        assertEquals(0f, jumpBurstFade(1f), 1e-6f)
        assertTrue(jumpBurstRadius(0.3f) > 0.62f + 0.38f * 0.3f)
    }

    @Test
    fun rowsRiseInOrderWithinTheirBudget() {
        assertEquals(1f, jumpRowReveal(5.0, Double.NaN, 0.0, 0, 3), 0f)
        assertEquals(0f, jumpRowReveal(10.0, 10.0, 0.0, 0, 3), 0f)
        assertEquals(1f, jumpRowReveal(10.4, 10.0, 0.0, 0, 3), 1e-6f)
        assertEquals(0f, jumpRowReveal(10.07, 10.0, 0.0, 1, 3), 0f)
        assertTrue(jumpRowReveal(10.2, 10.0, 0.0, 0, 3) > jumpRowReveal(10.2, 10.0, 0.0, 1, 3))
        // 预算只有 0.27 秒：整行压缩，单块至少 0.12 秒
        assertEquals(1f, jumpRowReveal(10.27 + 1e-6, 10.0, 0.27, 2, 3), 1e-4f)
    }

    @Test
    fun trailFadesOverFourHops() {
        assertEquals(1f, jumpTrailFade(10.0, 10.0, 1.0), 0f)
        assertEquals(0.61f, jumpTrailFade(12.0, 10.0, 1.0), 1e-5f)
        assertEquals(0.22f, jumpTrailFade(30.0, 10.0, 1.0), 1e-5f)
        // 跳得很密时淡化时间不短于 0.8 秒
        assertEquals(0.22f, jumpTrailFade(10.8, 10.0, 0.05), 1e-5f)
    }

    // ---- 整条路与逐帧 ----

    @Test
    fun courseSkipsBlankLinesAndAlternatesDirections() {
        val lines = listOf(
            line(0, 10_000, 14_000, "风吹过的地方"),
            line(1, 14_000, 14_400, "  "),
            line(2, 14_500, 18_500, "我们沿着河走"),
            line(3, 19_000, 23_000, "回到家门口"),
        )
        val c = course(lines)
        assertEquals(3, c.rows.size)
        assertArrayEquals(intArrayOf(0, -1, 1, 2), c.rowOfLine)
        assertEquals(listOf(1, -1, 1), c.rows.map { it.direction })
        assertEquals(listOf("风吹", "过的", "地方"), c.rows[0].units.map { it.text })
        // 偶数行往右上、奇数行往左上
        val r0 = c.rows[0].units
        assertTrue(r0[1].sx > r0[0].sx && r0[1].sy < r0[0].sy)
        val r1 = c.rows[1].units
        assertTrue(r1[1].sx < r1[0].sx && r1[1].sy < r1[0].sy)
        // 前两行一开始就立着，第三行从第一行的收尾窗结束起升起
        assertTrue(c.rows[1].revealStart.isNaN())
        assertEquals(c.rows[0].window.afterEnd, c.rows[2].revealStart, 1e-9)
    }

    @Test
    fun courseLandsEachBlockOnItsFirstChar() {
        val c = course(sung)
        assertArrayEquals(doubleArrayOf(10.0, 11.2, 12.4), c.rows[0].timeline.landings, 1e-9)
        assertEquals(14.5, c.rows[1].window.writeStart, 1e-9)
        assertEquals(0, jumpBoardRow(c.windows, 12.0))
        assertEquals(1, jumpBoardRow(c.windows, 14.5))
        assertEquals(2, jumpBoardRow(c.windows, 60.0))
    }

    @Test
    fun runnerFollowsTheHopParabola() {
        val c = course(sung)
        val pose = JumpPose(c.maxUnits)
        val tl = c.rows[0].timeline
        val hop = tl.hops[0]
        val mid = (hop.liftTime + hop.landTime) / 2
        jumpPose(c, mid, pose)
        assertTrue(pose.runner)
        assertEquals(jumpArcLift(c), pose.groundY - pose.feetY, 1e-4f)
        assertTrue(pose.squash > 1f)
        val from = c.rows[0].units[0]
        val to = c.rows[0].units[1]
        assertEquals((from.tx + to.tx) / 2f, pose.feetX, 1e-4f)
        // 四分之一处：sin(π/4)
        jumpPose(c, hop.liftTime + (hop.landTime - hop.liftTime) / 4, pose)
        assertEquals(jumpArcLift(c) * sin(PI / 4).toFloat(), pose.groundY - pose.feetY, 1e-4f)
        // 第一行开口前原地一跳登场
        jumpPose(c, 10.0 - 0.225, pose)
        assertEquals(from.tx, pose.feetX, 1e-4f)
        assertEquals(jumpArcLift(c), pose.groundY - pose.feetY, 1e-4f)
    }

    @Test
    fun landingStampsARingAndPressesThePillar() {
        val c = course(sung)
        val pose = JumpPose(c.maxUnits)
        val land = c.rows[0].timeline.hops[0].landTime
        jumpPose(c, land + 0.05, pose)
        assertTrue(pose.impactCount >= 1)
        val to = c.rows[0].units[1]
        var found = false
        for (i in 0 until pose.impactCount) {
            if (abs(pose.impactX[i] - to.tx) < 1e-4f && pose.impactDust[i]) found = true
        }
        assertTrue(found)
        assertTrue(pose.sink[JUMP_CUR][1] > 0f)
        // 0.36 秒后环散完
        jumpPose(c, land + 0.37, pose)
        for (i in 0 until pose.impactCount) assertTrue(abs(pose.impactX[i] - to.tx) > 1e-4f || !pose.impactDust[i])
        // 跳过的那一段留下轨迹
        assertEquals(1, pose.trailCount)
    }

    @Test
    fun crossingHopsToTheNextLineAndTheCameraTurnsTheCorner() {
        val c = course(sung)
        val pose = JumpPose(c.maxUnits)
        val shift = c.rows[0].timeline.gapShift!!
        jumpPose(c, (shift.begin + shift.end) / 2, pose)
        assertEquals(0, pose.rows[JUMP_CUR])
        assertEquals(1, pose.rows[JUMP_NEXT])
        assertEquals(-1, pose.rows[JUMP_PREV])
        val last = c.rows[0].units.last()
        val first = c.rows[1].units[0]
        assertEquals((last.tx + first.tx) / 2f, pose.feetX, 1e-4f)
        // 镜头朝向从右上转向左上
        assertTrue(pose.lookDir < 1f)
        // 换到下一行之后上一行还在画面上
        jumpPose(c, 15.0, pose)
        assertEquals(1, pose.rows[JUMP_CUR])
        assertEquals(0, pose.rows[JUMP_PREV])
        assertEquals(-1f, pose.lookDir, 0f)
    }

    @Test
    fun poseDependsOnlyOnTheTime() {
        val c = course(sung)
        val fresh = JumpPose(c.maxUnits)
        jumpPose(c, 12.1, fresh)
        val reused = JumpPose(c.maxUnits)
        // 来回拖进度之后再回到同一刻
        for (t in doubleArrayOf(16.0, 3.0, 22.0, 10.7, 14.3, 40.0)) jumpPose(c, t, reused)
        jumpPose(c, 12.1, reused)
        assertEquals(fresh.feetX, reused.feetX, 0f)
        assertEquals(fresh.feetY, reused.feetY, 0f)
        assertEquals(fresh.focusX, reused.focusX, 0f)
        assertEquals(fresh.focusY, reused.focusY, 0f)
        assertEquals(fresh.squash, reused.squash, 0f)
        assertEquals(fresh.impactCount, reused.impactCount)
        assertEquals(fresh.trailCount, reused.trailCount)
        assertArrayEquals(fresh.rows, reused.rows)
        assertArrayEquals(fresh.sink[JUMP_CUR], reused.sink[JUMP_CUR], 0f)
        assertArrayEquals(fresh.reveal[JUMP_NEXT], reused.reveal[JUMP_NEXT], 0f)
    }

    // ---- 静帧 ----

    @Test
    fun stillFrameStandsOnTheSungBlock() {
        val c = course(sung)
        val pose = JumpPose(c.maxUnits)
        jumpStillPose(c, 0, 11.5, pose)
        assertEquals(-1, pose.rows[JUMP_NEXT])
        assertEquals(c.rows[0].units[1].tx, pose.feetX, 0f)
        assertEquals(pose.groundY, pose.feetY, 0f)
        assertEquals(0, pose.impactCount)
        assertEquals(0, pose.trailCount)
        assertTrue(pose.reveal[JUMP_CUR].take(c.rows[0].units.size).all { it == 1f })
        // 没有逐字时间：站在第一块
        val plain = course(listOf(line(0, 10_000, 14_000, "风吹过的地方都有光"), line(1, 14_500, 18_500, "我们沿着河走回家")))
        val plainPose = JumpPose(plain.maxUnits)
        jumpStillPose(plain, 0, 12.4, plainPose)
        assertEquals(plain.rows[0].units[0].tx, plainPose.feetX, 0f)
        // 句间按时刻取当前行
        jumpStillPose(plain, -1, 14.6, plainPose)
        assertEquals(1, plainPose.rows[JUMP_CUR])
    }

    @Test
    fun stillFrameFitsTheRowInAThumbnail() {
        val c = course(listOf(line(0, 10_000, 14_000, "风吹过的地方都有光"), line(1, 14_500, 18_500, "我们沿着河走回家")))
        val row = c.rows[0]
        val runner = jumpRunnerCells(c)
        val f = jumpStillFrame(row, runner, 470f, 290f, 78f, 0)
        val cell = f[0]
        assertTrue(cell > 0f && cell <= 78f)
        for (unit in row.units) {
            val box = unit.box!!
            assertTrue(f[1] + (unit.sx + box.minX) * cell >= -1e-2f)
            assertTrue(f[1] + (unit.sx + box.maxX) * cell <= 470f + 1e-2f)
            assertTrue(f[2] + (unit.sy + box.minY) * cell >= -1e-2f)
            assertTrue(f[2] + (unit.sy + box.maxY) * cell <= 290f + 1e-2f)
        }
        assertTrue(f[2] + (row.units[0].ty - runner) * cell >= -1e-2f)
    }

    // ---- 译文 ----

    @Test
    fun translationFollowsTheSungLine() {
        val starts = longArrayOf(10_000, 14_500, 19_000)
        val readable = booleanArrayOf(true, false, true)
        assertEquals(0, jumpTranslationLine(starts, readable, 0, 12_000))
        assertEquals(0, jumpTranslationLine(starts, readable, -1, 14_200))
        assertEquals(-1, jumpTranslationLine(starts, readable, 1, 15_000))
        assertEquals(-1, jumpTranslationLine(starts, readable, -1, 5_000))
        assertEquals(0f, jumpTranslationReveal(10.0, 10.0), 0f)
        assertEquals(1f, jumpTranslationReveal(10.3, 10.0), 0f)
        assertTrue(jumpTranslationReveal(10.1, 10.0) in 0.5f..1f)
    }

    @Test
    fun translationWrapsByWordsAndShrinksToFit() {
        assertEquals(listOf("风吹过的", "地方都有", "光"), jumpWrapRows("风吹过的地方都有光", 4f))
        val rows = jumpWrapRows("Where the wind goes, there is light", 8f)
        assertEquals("Where the wind goes, there is light", rows.joinToString(" "))
        assertTrue(rows.all { row -> row.split(' ').all { it.isNotEmpty() } })
        val measure: (String, Float) -> Float = { text, size -> estimate(text) * size }
        val block = jumpTranslationBlock(listOf("Where the wind goes, there is light"), 200f, 300f, 40f, 10f, 3, 1.26f, 8f, measure)!!
        assertTrue(block.rows.size <= 3)
        assertTrue(block.sizePx < 40f)
        assertEquals(block.rows.size * block.sizePx * 1.26f, block.height, 1e-3f)
        assertNull(jumpTranslationBlock(listOf(" "), 200f, 300f, 40f, 10f, 3, 1.26f, 8f, measure))
    }

    @Test
    fun translationTiltFollowsThePitch() {
        assertEquals(1f, jumpTiltAmount(34f), 1e-5f)
        assertTrue(jumpTiltAmount(60f) < 1f)
        assertTrue(jumpTiltAmount(18f) > 1f)
        val tilt = jumpRowTilt(34f, 0f, 1000f)
        assertEquals(0.18f, tilt[0], 1e-5f)
        assertEquals(cos(34.0 * PI / 180).toFloat(), tilt[1], 1e-5f)
        // 靠上的行再收一点，最多收到 0.78
        assertTrue(jumpRowTilt(34f, 400f, 1000f)[1] < tilt[1])
        assertTrue(jumpRowTilt(34f, 1e6f, 1000f)[1] >= tilt[1] * 0.78f - 1e-5f)
    }

    @Test
    fun faceOpacityMatchesThePalette() {
        assertEquals(0.17f * 0.8f + 0.83f * 0.512f, jumpFaceOpacity(0.8f, 0.17f), 1e-5f)
        assertEquals(1f, jumpFaceOpacity(1f, 0.08f), 1e-6f)
        assertEquals(0f, jumpFaceOpacity(0f, 0.42f), 1e-6f)
    }
}
