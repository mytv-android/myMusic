/*
 * Copyright (C) 2026  Shubham Gorai
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 2026-11-09 myMusic 改写：取自 Rush 7.2.1 的 SyllableSyncedLyrics.kt（SyllableLine / SyllableWord）、
// LineSyncedLyrics.kt 与 DotLoadingProgress.kt。每个词的两层文字和 DstIn 羽化遮罩、按时长定字重、三点间奏照原样；
// 改动：一句歌词画在一个绘制节点里（不是每个词三个 Text），只有当前句逐帧重画；词的进度直接按舞台时钟算；
// 中文逐字歌词不加词距、不改字重（系统中文字体没有字重轴）；没有逐字时间的句子并进同一套（整句亮或按字均分）；
// 句下的副行改成跟随“歌词下面那一行”（翻译 / 罗马音 / 都显示）。
package com.mytv.mymusic.ui.player.stage.modes

import android.os.Build
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.player.stage.StageClock
import com.mytv.mymusic.ui.player.stage.StageLine
import com.mytv.mymusic.ui.player.stage.charTimings
import com.mytv.mymusic.ui.player.stage.splitCodePoints
import kotlin.math.max
import kotlin.math.roundToInt

/** 一句在列表里的状态：唱过的整句亮，还没唱到的只有底层，当前句逐词扫。 */
internal enum class RushPhase { Past, Current, Future }

/** 对齐：左 / 中 / 右，缩放原点跟着对齐边走。 */
internal enum class RushAlign { Start, Center, End }

/**
 * 一个词（中文是一个字或一个逐字时间段）：去掉首尾空白的文字和起止时间。
 * [gapBefore]：和前一个词之间原来有空白，中间留一个词距（Rush 每个词左右各 4dp）。
 * [breakBefore]：能在它前面换行；拉丁单词拆成几个音节时只有第一个音节能换行，标点贴着前一个字。
 */
@Immutable
internal class RushToken(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** 中日韩文字：不改字重（系统中文字体没有字重轴），可以在前后换行。 */
    val cjk: Boolean,
    val gapBefore: Boolean,
    val breakBefore: Boolean,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)

    /** [ms] 时唱到哪儿，0..1；起止相同（整句亮）时一到句首就是 1。 */
    fun progress(ms: Long): Float = when {
        ms >= endMs -> 1f
        ms <= startMs -> 0f
        else -> (ms - startMs).toFloat() / durationMs
    }
}

/**
 * 一句拆成词。有逐字时间就按逐字时间；这首歌别的句子有逐字时间、这句没有，按 [charTimings] 均分（和其它模式一致）；
 * 整首都没有逐字时间（普通 LRC），整句一到就全亮（Rush 的 LineSyncedLyrics）。
 */
internal fun rushTokens(line: StageLine, wordTimed: Boolean): List<RushToken> {
    val pieces = when {
        line.words.isNotEmpty() -> line.words.map { RushPiece(it.text, it.startMs, it.endMs) }
        wordTimed -> {
            val chars = line.charTimings()
            rushRuns(chars.map { it.text }, { chars[it].startMs }, { chars[it].endMs })
        }
        else -> rushRuns(splitCodePoints(line.text), { line.startMs }, { line.startMs })
    }
    val out = ArrayList<RushToken>(pieces.size)
    var spaceBefore = false
    for (piece in pieces) {
        val text = piece.text
        if (text.isBlank()) {
            spaceBefore = true
            continue
        }
        val core = text.trim()
        val gap = out.isNotEmpty() && (spaceBefore || text.first().isWhitespace())
        val cjk = rushIsCjk(core)
        val previous = out.lastOrNull()
        val stick = !gap && previous != null && (isClosingPunctuation(core) || (!cjk && !previous.cjk))
        out += RushToken(core, piece.startMs, piece.endMs, cjk, gapBefore = gap, breakBefore = !stick)
        spaceBefore = text.last().isWhitespace()
    }
    return out
}

private class RushPiece(val text: String, val startMs: Long, val endMs: Long)

/** 按字拆开的句子并成段：空白一段，中日韩一字一段，其它连续的字（拉丁单词连同标点）并成一段。 */
private fun rushRuns(chars: List<String>, start: (Int) -> Long, end: (Int) -> Long): List<RushPiece> {
    val out = ArrayList<RushPiece>()
    var runStart = -1
    fun flush(until: Int) {
        if (runStart < 0) return
        out += RushPiece(chars.subList(runStart, until).joinToString(""), start(runStart), end(until - 1))
        runStart = -1
    }
    chars.forEachIndexed { i, char ->
        when {
            char.isBlank() -> {
                flush(i)
                out += RushPiece(char, start(i), start(i))
            }
            rushIsCjk(char) -> {
                flush(i)
                out += RushPiece(char, start(i), end(i))
            }
            runStart < 0 -> runStart = i
        }
    }
    flush(chars.size)
    return out
}

/** 第一个字是汉字、假名、谚文或全角标点。 */
internal fun rushIsCjk(text: String): Boolean {
    if (text.isEmpty()) return false
    val cp = text.codePointAt(0)
    if (cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF) return true
    return when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.BOPOMOFO,
        -> true
        else -> false
    }
}

/** 整段都是收尾的标点（，。！？」等）：不能放到行首。 */
private fun isClosingPunctuation(text: String): Boolean = text.all {
    when (Character.getType(it).toByte()) {
        Character.END_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }
}

/** Rush 的逐字粗细：唱过的字按这个字的时长定字重（未唱的底层固定 300）。 */
internal fun rushWordWeight(durationMs: Long): Int = when {
    durationMs <= 500 -> 300
    durationMs <= 1000 -> 500
    durationMs <= 1500 -> 700
    else -> 900
}

/** 间奏三点里第 [index] 个点的进度：(句进度 − 0.15 i) × 1.4，夹到 0..1。 */
internal fun rushDotProgress(progress: Float, index: Int): Float = ((progress - index * 0.15f) * 1.4f).coerceIn(0f, 1f)

/** 一行（歌词或间奏）的进度：(现在 − 句首) / (下一行 − 句首)，没有下一行时是 1。 */
internal fun rushLineProgress(ms: Long, startMs: Long, nextMs: Long?): Float {
    if (nextMs == null) return 1f
    val span = nextMs - startMs
    return if (span <= 0) 1f else ((ms - startMs).toFloat() / span).coerceIn(0f, 1f)
}

/** 排一句用到的字体和尺寸（像素在 [RushLine] 里按密度换）。[unit] = 字号 / 28sp，Rush 的 dp 数都按它缩放。 */
@Immutable
internal class RushStyles(
    val base: TextStyle,
    val subtitle: TextStyle,
    val expressive: Boolean,
    val align: RushAlign,
    val unit: Float,
) {
    /**
     * 中日韩字用系统默认字体：主题的 Roboto Flex 只含拉丁字，汉字回退到系统字体时按 400 字重取、粗体不生效；
     * 系统默认字体要粗体时回退字体会合成加粗，Rush 的粗体大字才出得来。
     */
    private val cjk = base.copy(fontFamily = FontFamily.Default)

    /** 唱过的那层：开了逐字粗细的拉丁词按时长定字重，其余用粗体。 */
    fun top(token: RushToken): TextStyle = when {
        token.cjk -> cjk
        expressive -> base.copy(fontWeight = FontWeight(rushWordWeight(token.durationMs)))
        else -> base
    }

    /** 未唱的底层：开了逐字粗细的拉丁词是 300。 */
    fun under(token: RushToken): TextStyle = when {
        token.cjk -> cjk
        expressive -> base.copy(fontWeight = FontWeight(RUSH_UNDER_WEIGHT))
        else -> base
    }

    val textAlign: TextAlign
        get() = when (align) {
            RushAlign.Start -> TextAlign.Start
            RushAlign.Center -> TextAlign.Center
            RushAlign.End -> TextAlign.End
        }
}

/**
 * 词唱到时的两条曲线，按“从这个词开始过了多久”取值，绘制阶段逐帧算，不起动画：
 * 缩放 0.95 → 1（MotionScheme 的 fastSpatial），底层光晕 0 → 1（fastEffects，乘 2dp）。
 */
internal class RushWordCurves(spatial: AnimationSpec<Float>, effects: AnimationSpec<Float>) {
    private val scale = TargetBasedAnimation(spatial, Float.VectorConverter, RUSH_WORD_IDLE_SCALE, 1f)
    private val glow = TargetBasedAnimation(effects, Float.VectorConverter, 0f, 1f)

    fun scale(sinceMs: Long): Float = valueAt(scale, sinceMs)

    fun glow(sinceMs: Long): Float = valueAt(glow, sinceMs)

    private fun valueAt(animation: TargetBasedAnimation<Float, *>, sinceMs: Long): Float {
        val nanos = sinceMs.coerceAtLeast(0L) * 1_000_000L
        return if (nanos >= animation.durationNanos) animation.targetValue else animation.getValueFromNanos(nanos)
    }
}

/** 一句排好的位置：每个词的左上角、副行的位置。测量阶段写，绘制阶段读。 */
private class RushLinePlacement {
    var x = FloatArray(0)
    var y = FloatArray(0)
    var subtitle: TextLayoutResult? = null
    var subtitleOffset = Offset.Zero
}

private class RushMeasuredToken(val top: TextLayoutResult, val under: TextLayoutResult) {
    val width: Float get() = top.size.width.toFloat()
    val height: Float get() = top.size.height.toFloat()
}

/**
 * 一句歌词：词按可换行的位置折行、按对齐排，句下是副行。整句在一个节点里画：
 * 唱过的句子只画上层，没唱的只画底层（[underAlpha]），当前句每帧按时钟给每个词算进度，
 * 唱到一半的词两层各用一道 16dp 羽化的 DstIn 遮罩拼起来，底层再加一点光晕。减少动画时当前句整句亮。
 */
@Composable
internal fun RushLine(
    tokens: List<RushToken>,
    subtitle: String,
    phase: RushPhase,
    clock: StageClock,
    styles: RushStyles,
    curves: RushWordCurves,
    content: State<Color>,
    underAlpha: State<Float>,
    glow: Boolean,
    reduceMotion: Boolean,
) {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val measured = remember(tokens, styles) { measureTokens(measurer, tokens, styles) }
    val placement = remember { RushLinePlacement() }
    val live = phase == RushPhase.Current && !reduceMotion
    Spacer(
        Modifier
            .layout { measurable, constraints ->
                val padH = RUSH_LINE_PAD_H.toPx() * styles.unit
                val padV = RUSH_LINE_PAD_V.toPx() * styles.unit
                val gap = RUSH_WORD_GAP.toPx() * styles.unit
                val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.MAX_VALUE
                val avail = (maxWidth - padH * 2f).coerceAtLeast(1f)
                val flow = flowTokens(tokens, measured, gap, avail)
                val sub = subtitle.takeIf { it.isNotBlank() }?.let {
                    measurer.measure(
                        it,
                        styles.subtitle.copy(textAlign = styles.textAlign),
                        constraints = Constraints(maxWidth = avail.toInt().coerceAtLeast(1)),
                    )
                }
                val contentWidth = max(flow.width, sub?.size?.width?.toFloat() ?: 0f)
                val width = (contentWidth + padH * 2f).coerceAtMost(maxWidth)
                val subGap = if (sub != null) RUSH_SUBTITLE_GAP.toPx() * styles.unit else 0f
                val height = padV * 2f + flow.height + subGap + (sub?.size?.height ?: 0)
                placement.x = FloatArray(tokens.size) { i ->
                    padH + flow.x[i] + alignOffset(contentWidth, flow.rowWidth[flow.row[i]], styles.align)
                }
                placement.y = FloatArray(tokens.size) { i -> padV + flow.y[i] }
                placement.subtitle = sub
                placement.subtitleOffset = Offset(
                    padH + alignOffset(contentWidth, sub?.size?.width?.toFloat() ?: 0f, styles.align),
                    padV + flow.height + subGap,
                )
                val w = width.roundToInt()
                val h = height.roundToInt()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .drawWithCache {
                val glowLayer = if (glow && live && Build.VERSION.SDK_INT >= 31) obtainGraphicsLayer() else null
                val feather = RUSH_FEATHER.toPx() * styles.unit
                val glowRadius = RUSH_GLOW.toPx() * styles.unit
                onDrawBehind {
                    val color = content.value
                    val dim = color.copy(alpha = color.alpha * underAlpha.value)
                    when {
                        phase == RushPhase.Past || (phase == RushPhase.Current && reduceMotion) ->
                            measured.forEachIndexed { i, m -> drawText(m.top, color, Offset(placement.x[i], placement.y[i]), shadow = Shadow.None) }
                        phase == RushPhase.Future ->
                            measured.forEachIndexed { i, m -> drawText(m.under, dim, Offset(placement.x[i], placement.y[i]), shadow = Shadow.None) }
                        else -> {
                            val ms = clock.ms
                            var sweeping = false
                            measured.forEachIndexed { i, m ->
                                val token = tokens[i]
                                val p = token.progress(ms)
                                val at = Offset(placement.x[i], placement.y[i])
                                if (p > 0f && p < 1f) {
                                    sweeping = true
                                    return@forEachIndexed
                                }
                                val s = if (p > 0f) curves.scale(ms - token.startMs) else RUSH_WORD_IDLE_SCALE
                                scale(s, pivot = at + Offset(m.width / 2f, m.height / 2f)) {
                                    if (p >= 1f) drawText(m.top, color, at, shadow = Shadow.None) else drawText(m.under, dim, at, shadow = Shadow.None)
                                }
                            }
                            if (sweeping) {
                                // 唱到一半的词：底层（右边还没唱的部分）先画，有光晕时整组录进一个图层再模糊
                                val drawUnders: DrawScope.() -> Unit = {
                                    forSweeping(tokens, measured, placement, ms, curves) { m, at, p ->
                                        drawSwept(m.under, at, m.width, m.height, dim, p, feather, lit = false)
                                    }
                                }
                                if (glowLayer != null) {
                                    val since = tokens.firstOrNull { val p = it.progress(ms); p > 0f && p < 1f }?.let { ms - it.startMs } ?: 0L
                                    glowLayer.renderEffect = RushBlur.of(glowRadius * curves.glow(since))
                                    glowLayer.record(this, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) { drawUnders() }
                                    drawLayer(glowLayer)
                                } else {
                                    drawUnders()
                                }
                                forSweeping(tokens, measured, placement, ms, curves) { m, at, p ->
                                    drawSwept(m.top, at, m.width, m.height, color, p, feather, lit = true)
                                }
                            }
                        }
                    }
                    placement.subtitle?.let { drawText(it, color.copy(alpha = color.alpha * RUSH_SUBTITLE_ALPHA), placement.subtitleOffset, shadow = Shadow.None) }
                }
            },
    )
}

private fun measureTokens(measurer: TextMeasurer, tokens: List<RushToken>, styles: RushStyles): List<RushMeasuredToken> =
    tokens.map { token ->
        val topStyle = styles.top(token)
        val underStyle = styles.under(token)
        val top = measurer.measure(token.text, topStyle, softWrap = false, maxLines = 1)
        val under = if (underStyle == topStyle) top else measurer.measure(token.text, underStyle, softWrap = false, maxLines = 1)
        RushMeasuredToken(top, under)
    }

/** 对每个唱到一半的词，在它自己的缩放下调 [block]。 */
private inline fun DrawScope.forSweeping(
    tokens: List<RushToken>,
    measured: List<RushMeasuredToken>,
    placement: RushLinePlacement,
    ms: Long,
    curves: RushWordCurves,
    block: DrawScope.(RushMeasuredToken, Offset, Float) -> Unit,
) {
    for (i in tokens.indices) {
        val token = tokens[i]
        val p = token.progress(ms)
        if (p <= 0f || p >= 1f) continue
        val m = measured[i]
        val at = Offset(placement.x[i], placement.y[i])
        scale(curves.scale(ms - token.startMs), pivot = at + Offset(m.width / 2f, m.height / 2f)) { block(m, at, p) }
    }
}

/**
 * Rush 的羽化扫光：扫光位置 x = (宽 + 羽化) × 进度，[x − 羽化, x] 这一段渐变。[lit] 为 true 画上层（左边亮），
 * 否则画底层（右边留着）。在词自己的离屏层里画字再用 DstIn 擦，不影响旁边的词。
 */
private fun DrawScope.drawSwept(
    layout: TextLayoutResult,
    at: Offset,
    width: Float,
    height: Float,
    color: Color,
    progress: Float,
    feather: Float,
    lit: Boolean,
) {
    val x = (width + feather) * progress
    val a = ((x - feather) / width).coerceIn(0f, 1f)
    val b = (x / width).coerceIn(0f, 1f)
    val brush = if (lit) {
        Brush.horizontalGradient(0f to Color.Black, a to Color.Black, b to Color.Transparent, 1f to Color.Transparent, startX = at.x, endX = at.x + width)
    } else {
        Brush.horizontalGradient(0f to Color.Transparent, a to Color.Transparent, b to Color.Black, 1f to Color.Black, startX = at.x, endX = at.x + width)
    }
    val bounds = Rect(at, Size(width, height)).inflate(RUSH_LAYER_SLACK_PX)
    drawContext.canvas.saveLayer(bounds, Paint())
    drawText(layout, color, at, shadow = Shadow.None)
    drawRect(brush, bounds.topLeft, bounds.size, blendMode = BlendMode.DstIn)
    drawContext.canvas.restore()
}

/** 折行结果：每个词在第几行、行内 x、行顶 y；每行宽度；整体宽高。 */
private class RushFlow(val row: IntArray, val x: FloatArray, val y: FloatArray, val rowWidth: FloatArray, val width: Float, val height: Float)

/**
 * 按 [RushToken.breakBefore] 折行：一个不可拆的段（几个音节组成的单词）放不下就整段换到下一行；
 * 整段比一行还宽时才在段里按词拆。行高取这一行最高的词。
 */
private fun flowTokens(tokens: List<RushToken>, measured: List<RushMeasuredToken>, gap: Float, avail: Float): RushFlow {
    val n = tokens.size
    val row = IntArray(n)
    val x = FloatArray(n)
    val y = FloatArray(n)
    val widths = ArrayList<Float>()
    val heights = ArrayList<Float>()
    var rowIndex = 0
    var cursor = 0f
    var rowHeight = 0f
    var rowTop = 0f
    var used = false
    fun newRow() {
        widths += cursor
        heights += rowHeight
        rowTop += rowHeight
        rowIndex++
        cursor = 0f
        rowHeight = 0f
        used = false
    }
    var i = 0
    while (i < n) {
        // 这一段：从 i 到下一个能换行的词之前
        var end = i + 1
        while (end < n && !tokens[end].breakBefore) end++
        var groupWidth = 0f
        for (k in i until end) groupWidth += measured[k].width
        val lead = if (used && tokens[i].gapBefore) gap else 0f
        if (used && cursor + lead + groupWidth > avail) newRow()
        val splitGroup = groupWidth > avail
        for (k in i until end) {
            val w = measured[k].width
            if (splitGroup && used && cursor + (if (tokens[k].gapBefore) gap else 0f) + w > avail) newRow()
            if (used && tokens[k].gapBefore) cursor += gap
            row[k] = rowIndex
            x[k] = cursor
            y[k] = rowTop
            cursor += w
            rowHeight = max(rowHeight, measured[k].height)
            used = true
        }
        i = end
    }
    if (used || widths.isEmpty()) {
        widths += cursor
        heights += rowHeight
        rowTop += rowHeight
    }
    return RushFlow(row, x, y, widths.toFloatArray(), widths.maxOrNull() ?: 0f, rowTop)
}

private fun alignOffset(container: Float, item: Float, align: RushAlign): Float = when (align) {
    RushAlign.Start -> 0f
    RushAlign.Center -> (container - item) / 2f
    RushAlign.End -> container - item
}

/**
 * 间奏三点：8dp 的点、间距 16dp、四周 12dp；第 i 个点按 [rushDotProgress] 从 1 倍放到 1.8 倍、透明度 30% 到 100%。
 * 唱过的间奏三个点都满，还没到的都是起始样子；[live] 时按时钟算（当前行，减少动画时停在换句那一刻）。
 */
@Composable
internal fun RushDots(
    startMs: Long,
    nextMs: Long?,
    phase: RushPhase,
    live: Boolean,
    clock: StageClock,
    content: State<Color>,
    unit: Float,
) {
    Spacer(
        Modifier
            .layout { measurable, _ ->
                val w = ((RUSH_DOT_PAD * 2 + RUSH_DOT * 3 + RUSH_DOT_SPACING * 2).toPx() * unit).roundToInt()
                val h = ((RUSH_DOT_PAD * 2 + RUSH_DOT).toPx() * unit).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .drawBehind {
                val p = when (phase) {
                    RushPhase.Past -> 1f
                    RushPhase.Future -> 0f
                    RushPhase.Current -> if (live) rushLineProgress(clock.ms, startMs, nextMs) else 0f
                }
                val base = content.value.let { if (phase == RushPhase.Future) it.copy(alpha = it.alpha * RUSH_UNSTARTED_ALPHA) else it }
                val dot = RUSH_DOT.toPx() * unit
                val spacing = RUSH_DOT_SPACING.toPx() * unit
                val pad = RUSH_DOT_PAD.toPx() * unit
                repeat(3) { index ->
                    val dp = rushDotProgress(p, index)
                    val radius = dot / 2f * (1f + 0.8f * dp)
                    val alpha = 0.3f + 0.7f * dp
                    drawCircle(
                        base.copy(alpha = base.alpha * alpha),
                        radius,
                        Offset(pad + dot / 2f + index * (dot + spacing), size.height / 2f),
                    )
                }
            },
    )
}

/** 模糊效果按 0.5px 一档缓存；半径不到 0.5px 时返回 null，图层就不必离屏。Android 12 以下没有 RenderEffect。 */
internal object RushBlur {
    private const val STEPS = 160
    private val cache = arrayOfNulls<RenderEffect>(STEPS)

    fun of(radiusPx: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < 31) return null
        val step = (radiusPx * 2f).roundToInt().coerceAtMost(STEPS - 1)
        if (step <= 0) return null
        return cache[step] ?: BlurEffect(step / 2f, step / 2f, TileMode.Decal).also { cache[step] = it }
    }
}

/** Rush 的字号基准（sp）：它的 dp 数（羽化、内边距、三点）都是按 28sp 定的。 */
internal const val RUSH_BASE_FONT_SP = 28f
internal const val RUSH_WORD_IDLE_SCALE = 0.95f
internal const val RUSH_UNDER_WEIGHT = 300
/** 没唱到的行，上层文字（三点）的透明度。 */
private const val RUSH_UNSTARTED_ALPHA = 0.3f
private const val RUSH_SUBTITLE_ALPHA = 0.7f
private const val RUSH_LAYER_SLACK_PX = 2f
private val RUSH_LINE_PAD_H = 4.dp
private val RUSH_LINE_PAD_V = 6.dp
/** 词距：Rush 每个词左右各 4dp。 */
private val RUSH_WORD_GAP = 8.dp
private val RUSH_SUBTITLE_GAP = 4.dp
private val RUSH_FEATHER = 16.dp
private val RUSH_GLOW = 2.dp
private val RUSH_DOT = 8.dp
private val RUSH_DOT_SPACING = 16.dp
private val RUSH_DOT_PAD = 12.dp
