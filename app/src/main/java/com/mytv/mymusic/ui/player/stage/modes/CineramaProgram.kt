package com.mytv.mymusic.ui.player.stage.modes

import com.mytv.mymusic.ui.player.stage.StageLine
import com.mytv.mymusic.ui.player.stage.splitCodePoints
import com.mytv.mymusic.ui.player.stage.stableHash
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 巨幕（cinerama）的纯逻辑，照原模组 cineramaRandom / Program / Split / Layout / Transition / Treatment 的数值移植，
 * 不碰 android.*，单元测试直接跑。时间一律用秒（Double），和原版的常数（0.6 秒算紧邻、转场 0.14–0.28 秒……）对得上。
 *
 * 编译期（换歌、改设置时一次）：每句抽切分、排版、样式，相邻两句不重复；样式之后再定跑马灯带的形态和取材。
 * 和尺寸有关的（大字报分块、丝带几何）留到画的时候按屏幕尺寸算一次，按句缓存。
 */

// ---------------------------------------------------------------- 随机

/** 原版 mixCineramaSeed：(seed ^ salt) × 2654435761，取低 32 位。 */
internal fun cineramaMix(seed: Long, salt: Int): Long =
    ((seed.toInt() xor salt) * CINERAMA_GOLDEN).toLong() and 0xFFFFFFFFL

/** 原版 cineramaHash01：同一个种子、序号、盐永远给同一个 [0, 1) 的数。 */
internal fun cineramaHash01(seed: Long, index: Int, salt: Int): Double =
    cineramaMix(seed + (index + 1) * 97L, salt) / 4294967296.0

/** 原版 chooseCineramaWithoutRepeat：按字符串种子挑起点，往后找第一个和 [previous] 不同的；池里只有一项时照给。 */
internal fun <T> cineramaChoose(choices: List<T>, seed: String, previous: T?): T? {
    if (choices.isEmpty()) return null
    if (choices.size == 1) return choices[0]
    val start = (stableHash(seed) % choices.size).toInt()
    for (offset in choices.indices) {
        val candidate = choices[(start + offset) % choices.size]
        if (candidate != previous) return candidate
    }
    return choices[start]
}

/** 按权重掷一次（掷点按种子和盐，seek / 重建一致）。总权重不大于 0 时给 null。 */
internal fun <T> cineramaDrawWeighted(entries: List<Pair<T, Double>>, seed: Long, salt: Int): T? {
    val total = entries.sumOf { it.second }
    if (!(total > 0.0)) return null
    var roll = cineramaHash01(seed, 0, salt) * total
    for ((kind, weight) in entries) {
        roll -= weight
        if (roll < 0) return kind
    }
    return entries.last().first
}

/** 原版 shuffleWith：Fisher–Yates，第 i 步的落点按 hash01(seed, i, salt)。 */
internal fun <T> cineramaShuffle(list: List<T>, seed: Long, salt: Int): MutableList<T> {
    val out = list.toMutableList()
    for (i in out.size - 1 downTo 1) {
        val j = floor(cineramaHash01(seed, i, salt) * (i + 1)).toInt().coerceIn(0, i)
        val keep = out[i]
        out[i] = out[j]
        out[j] = keep
    }
    return out
}

/** JS 口径的夹紧：下限比上限大时上限说了算（Kotlin 的 coerceIn 会抛异常）。 */
internal fun cineramaClamp(value: Double, low: Double, high: Double): Double = min(high, max(low, value))

internal fun cineramaSmoothstep(value: Double): Double {
    val t = cineramaClamp(value, 0.0, 1.0)
    return t * t * (3 - 2 * t)
}

/** 取小数部分，负数也落在 [0, 1)。 */
internal fun cineramaFrac(value: Double): Double = ((value % 1.0) + 1.0) % 1.0

/** JS 的 Math.round：.5 往大进（Kotlin 的 round 是逢 .5 取偶，4.5 会给 4）。 */
internal fun cineramaRound(value: Double): Double = floor(value + 0.5)

// ---------------------------------------------------------------- 样式与权重

internal enum class CineramaStyle { Hero, Small, Ribbon }

/**
 * 三种版式的出现权重（面板的大字报 / 小字报 / 斜切丝带，各 0–1）。三个都拖到 0 时按等权处理
 * （原版这时整首退成小字报；面板上三个都是 0 读作“随便”，等权更顺手）。
 */
internal class CineramaWeights(hero: Float, small: Float, ribbon: Float) {
    val hero: Double
    val small: Double
    val ribbon: Double

    init {
        val h = hero.toDouble().coerceIn(0.0, 1.0).takeUnless { it.isNaN() } ?: 0.0
        val s = small.toDouble().coerceIn(0.0, 1.0).takeUnless { it.isNaN() } ?: 0.0
        val r = ribbon.toDouble().coerceIn(0.0, 1.0).takeUnless { it.isNaN() } ?: 0.0
        val none = h <= 0.0 && s <= 0.0 && r <= 0.0
        this.hero = if (none) 1.0 else h
        this.small = if (none) 1.0 else s
        this.ribbon = if (none) 1.0 else r
    }

    fun of(style: CineramaStyle): Double = when (style) {
        CineramaStyle.Hero -> hero
        CineramaStyle.Small -> small
        CineramaStyle.Ribbon -> ribbon
    }

    /** 归一后的占比（三项加起来是 1）。 */
    fun share(style: CineramaStyle): Double = of(style) / (hero + small + ribbon)
}

/**
 * 原版 pickCineramaStyleKind：池子是“这一句排得出、权重大于 0”的版式（按 大字报、小字报、丝带 的顺序），
 * 盐 349 掷一次；和上一句相同且池里不止一种时，去掉上一句那种、用盐 353 重掷。
 * 大字报要至少一个字，丝带要有切分单元或整句是空的；小字报总能排。
 */
internal fun cineramaPickStyle(
    seed: Long,
    previous: CineramaStyle?,
    weights: CineramaWeights,
    heroOk: Boolean,
    ribbonOk: Boolean,
): CineramaStyle {
    val pool = ArrayList<Pair<CineramaStyle, Double>>(3)
    if (heroOk && weights.hero > 0) pool += CineramaStyle.Hero to weights.hero
    if (weights.small > 0) pool += CineramaStyle.Small to weights.small
    if (ribbonOk && weights.ribbon > 0) pool += CineramaStyle.Ribbon to weights.ribbon
    if (pool.isEmpty()) return CineramaStyle.Small
    val picked = cineramaDrawWeighted(pool, seed, 349) ?: return CineramaStyle.Small
    if (picked != previous || pool.size == 1) return picked
    return cineramaDrawWeighted(pool.filter { it.first != previous }, seed, 353) ?: picked
}

// ---------------------------------------------------------------- 排版（小字报的落点）

/**
 * 原版 CINERAMA_LAYOUT_TABLE：左右 / 上下留白（屏宽 / 屏高的比例）、字号倍率、字距（em）、行高、
 * 文字盒最大宽度、竖直落点（−1 顶、0 中、1 底）、水平落点（−1 左、0 中、1 右）。[group] 是权重分组。
 */
internal enum class CineramaLayout(
    val group: Int,
    val padX: Float,
    val padY: Float,
    val fontScale: Float,
    val trackEm: Float,
    val lineHeight: Float,
    val maxWidthRatio: Float,
    val alignY: Int,
    val alignX: Int,
) {
    CenterStage(0, 0.07f, 0.08f, 1f, 0.02f, 1.08f, 0.88f, 0, 0),
    UpperThird(1, 0.08f, 0.10f, 0.9f, 0.03f, 1.12f, 0.84f, -1, 0),
    LowerThird(1, 0.08f, 0.10f, 0.9f, 0.03f, 1.3f, 0.84f, 1, 0),
    LeftBand(1, 0.09f, 0.08f, 0.86f, 0.015f, 1.16f, 0.72f, 0, -1),
    RightBand(1, 0.09f, 0.08f, 0.86f, 0.015f, 1.16f, 0.72f, 0, 1),
    LetterboxWide(2, 0.06f, 0.16f, 1.12f, 0.06f, 1.1f, 0.92f, 1, 0),
}

/**
 * 原版 pickCineramaLayoutKind：居中组 1、偏置组 1、宽银幕组 0.5，盐 401 掷一次，和上一句相同就去掉它重掷。
 * 原版把字符串种子直接喂给 hash01，算出来是 NaN → 每句都掷到同一个点（于是只在左右两条带之间来回）；
 * 这里先把字符串哈希成数再掷，落点才真正按句变化。
 */
internal fun cineramaPickLayout(seed: Long, previous: CineramaLayout?): CineramaLayout {
    val pool = CineramaLayout.entries.map { it to CINERAMA_LAYOUT_GROUP_WEIGHTS[it.group] }
    val picked = cineramaDrawWeighted(pool, seed, 401) ?: return CineramaLayout.CenterStage
    if (previous == null || picked != previous) return picked
    return cineramaDrawWeighted(pool.filter { it.first != previous }, seed, 401) ?: picked
}

// ---------------------------------------------------------------- 切分

internal enum class CineramaSplit { Whole, Word, Clause, Grapheme, Half }

/** 分词：把一句切成段（词、空白、标点各算一段，拼回去等于原文）。测试里可以换成确定的切法。 */
internal fun interface CineramaSegmenter {
    fun segments(text: String): List<String>
}

/** 系统分词（Android 上是 ICU，中文按词典切词），对应原版的 Intl.Segmenter(word)。 */
internal val CineramaWordSegmenter = CineramaSegmenter { text ->
    val out = ArrayList<String>()
    val it = BreakIterator.getWordInstance()
    it.setText(text)
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        if (end > start) out += text.substring(start, end)
        start = end
        end = it.next()
    }
    if (out.isEmpty() && text.isNotEmpty()) out += text
    out
}

/** 一段是“词”：含字母或数字。 */
internal fun cineramaWordLike(text: String): Boolean = CINERAMA_WORD_LIKE.containsMatchIn(text)

/** 原版 segmentCineramaWords：词照留，空白和标点并进前一个词（句首的标点自成一段）。 */
internal fun cineramaWords(text: String, segmenter: CineramaSegmenter): List<String> {
    if (text.isBlank()) return emptyList()
    val words = ArrayList<String>()
    for (part in segmenter.segments(text)) {
        if (part.isEmpty()) continue
        if (cineramaWordLike(part)) {
            words += part
            continue
        }
        val trimmed = part.trim()
        if (trimmed.isEmpty()) {
            if (words.isNotEmpty()) words[words.lastIndex] = words.last() + part
            continue
        }
        if (words.isNotEmpty()) words[words.lastIndex] = words.last() + trimmed else words += trimmed
    }
    return words.filter { it.isNotBlank() }
}

internal fun cineramaSupportsSplit(kind: CineramaSplit, text: String, segmenter: CineramaSegmenter): Boolean = when (kind) {
    CineramaSplit.Whole -> true
    CineramaSplit.Word -> segmenter.segments(text).size > 1
    CineramaSplit.Clause -> CINERAMA_CLAUSE.containsMatchIn(text)
    CineramaSplit.Grapheme -> splitCodePoints(text).size.let { it in 2..CINERAMA_MAX_GRAPHEME_UNITS }
    CineramaSplit.Half -> splitCodePoints(text).size >= 6
}

internal fun cineramaPickSplit(text: String, seed: String, previous: CineramaSplit?, segmenter: CineramaSegmenter): CineramaSplit {
    val supported = CineramaSplit.entries.filter { cineramaSupportsSplit(it, text, segmenter) }
    return cineramaChoose(supported, seed, previous) ?: CineramaSplit.Whole
}

/** 原版 splitBalanced：在目标位置左右 6 个字以内找空白或标点断开，分成 [count] 段（不劈开代理对）。 */
internal fun cineramaSplitBalanced(text: String, count: Int): List<String> {
    if (count < 2) return listOf(text)
    fun isBreak(index: Int): Boolean {
        if (index < 0 || index >= text.length) return false
        val c = text[index]
        return c.isWhitespace() || CINERAMA_CLAUSE.matches(c.toString())
    }
    val cuts = ArrayList<Int>()
    for (step in 1 until count) {
        val target = cineramaRound(text.length.toDouble() * step / count).toInt()
        var best = -1
        for (offset in 0..6) {
            if (isBreak(target + offset)) {
                best = target + offset
                break
            }
            if (isBreak(target - offset)) {
                best = target - offset
                break
            }
        }
        var cut = if (best > 0) best + 1 else target
        if (cut in 1 until text.length && text[cut - 1].isHighSurrogate()) cut += 1
        if (cut > (cuts.lastOrNull() ?: 0) && cut < text.length) cuts += cut
    }
    val parts = ArrayList<String>(cuts.size + 1)
    var cursor = 0
    for (cut in cuts) {
        parts += text.substring(cursor, cut)
        cursor = cut
    }
    parts += text.substring(cursor)
    return parts.filter { it.isNotEmpty() }
}

/** 原版 splitKeepingDelimiters：按断句标点切开，标点自成一段。 */
internal fun cineramaSplitClauses(text: String): List<String> {
    val parts = ArrayList<String>()
    var cursor = 0
    for (match in CINERAMA_CLAUSE.findAll(text)) {
        if (match.range.first > cursor) parts += text.substring(cursor, match.range.first)
        parts += match.value
        cursor = match.range.last + 1
    }
    if (cursor < text.length) parts += text.substring(cursor)
    return parts.filter { it.isNotEmpty() }
}

internal fun cineramaParts(text: String, kind: CineramaSplit, segmenter: CineramaSegmenter): List<String> = when (kind) {
    CineramaSplit.Word -> segmenter.segments(text)
    CineramaSplit.Clause -> cineramaSplitClauses(text)
    CineramaSplit.Grapheme -> splitCodePoints(text)
    CineramaSplit.Half -> cineramaSplitBalanced(text, if (splitCodePoints(text).size > 16) 3 else 2)
    CineramaSplit.Whole -> listOf(text)
}

// ---------------------------------------------------------------- 时间线

internal class CineramaSpan(val start: Double, val end: Double)

/** 一个逐字时间（秒）。 */
internal class CineramaWord(val text: String, val start: Double, val end: Double)

/**
 * 原版 resolveCineramaRevealWindow：出字窗口从句首到最后一个词唱完（没有逐字时到作者写的句末），
 * 至少占行窗口的三成，且不超过行窗口。
 */
internal fun cineramaReveal(start: Double, windowEnd: Double, lastWordEnd: Double?, authoredEnd: Double): CineramaSpan {
    val span = max(0.001, windowEnd - start)
    val sung = lastWordEnd ?: authoredEnd
    val end = min(windowEnd, max(sung, start + span * CINERAMA_REVEAL_FLOOR))
    return CineramaSpan(start, max(end, start + 0.001))
}

private fun cineramaEven(count: Int, start: Double, end: Double): List<CineramaSpan> {
    val unit = max(0.0, end - start) / max(1, count)
    return List(count) { i -> CineramaSpan(start + unit * i, if (i == count - 1) end else start + unit * (i + 1)) }
}

private fun cineramaFindSequence(source: List<String>, target: List<String>, from: Int): Int {
    if (target.isEmpty()) return from
    for (index in from..source.size - target.size) {
        var matched = true
        for (j in target.indices) {
            if (source[index + j] != target[j]) {
                matched = false
                break
            }
        }
        if (matched) return index
    }
    return -1
}

/**
 * 原版 buildCineramaGraphemeTimeline：每个字什么时候出。逐字时间按词在整句里对位，词的时长均分给它的字；
 * 没对上的字落在前一个词开口处，零长的字挪到前一个字唱完的时刻，整条单调不减、夹在行窗口里。没有逐字时间就把出字窗口均分。
 */
internal fun cineramaTimeline(
    graphemes: List<String>,
    words: List<CineramaWord>,
    windowStart: Double,
    windowEnd: Double,
    reveal: CineramaSpan,
): List<CineramaSpan> {
    if (graphemes.isEmpty()) return emptyList()
    if (words.isEmpty()) return cineramaEven(graphemes.size, reveal.start, reveal.end)
    fun clampTime(value: Double) = min(windowEnd, max(windowStart, value))
    val timeline = arrayOfNulls<CineramaSpan>(graphemes.size)
    var cursor = 0
    var lastEnd = reveal.start
    for (word in words) {
        val wordGraphemes = splitCodePoints(word.text)
        if (wordGraphemes.isEmpty()) continue
        val wordStart = clampTime(word.start)
        val wordEnd = clampTime(word.end)
        val matched = cineramaFindSequence(graphemes, wordGraphemes, cursor)
        val start = if (matched >= 0) matched else cursor
        val end = min(start + wordGraphemes.size, graphemes.size)
        for (i in cursor until start) timeline[i] = CineramaSpan(wordStart, wordStart)
        val timings = cineramaEven(wordGraphemes.size, wordStart, wordEnd)
        for (i in start until end) timeline[i] = timings[i - start]
        lastEnd = max(lastEnd, wordEnd)
        cursor = max(cursor, end)
    }
    var previousEnd = reveal.start
    var previousStart = windowStart
    return List(graphemes.size) { i ->
        var entry = timeline[i] ?: CineramaSpan(lastEnd, lastEnd)
        if (entry.end > entry.start) {
            previousEnd = entry.end
        } else {
            val at = max(reveal.start, previousEnd)
            entry = CineramaSpan(at, at)
        }
        val start = min(windowEnd, max(entry.start, previousStart))
        previousStart = start
        CineramaSpan(start, min(windowEnd, max(entry.end, start)))
    }
}

/** 一个切分单元：文字和它的出现窗口。 */
internal class CineramaUnit(val text: String, val start: Double, val end: Double)

/** 原版 buildCineramaUnits：按切分方式分段，每段的窗口取它覆盖的字的最早开口和最晚唱完。 */
internal fun cineramaUnits(
    text: String,
    parts: List<String>,
    graphemes: List<String>,
    timeline: List<CineramaSpan>,
    reveal: CineramaSpan,
): List<CineramaUnit> {
    if (text.isEmpty()) return emptyList()
    fun even(): List<CineramaUnit> {
        val span = max(0.001, reveal.end - reveal.start)
        return parts.mapIndexed { i, part ->
            CineramaUnit(part, reveal.start + span * i / parts.size, reveal.start + span * (i + 1) / parts.size)
        }
    }
    if (parts.joinToString("") != text || timeline.size != graphemes.size) return even()
    val units = ArrayList<CineramaUnit>(parts.size)
    var cursor = 0
    for (part in parts) {
        val size = splitCodePoints(part).size
        val to = min(cursor + size, timeline.size)
        if (to <= cursor) {
            cursor += size
            continue
        }
        var start = Double.POSITIVE_INFINITY
        var end = Double.NEGATIVE_INFINITY
        for (i in cursor until to) {
            start = min(start, timeline[i].start)
            end = max(end, timeline[i].end)
        }
        cursor += size
        units += CineramaUnit(part, start, max(end, start + 0.001))
    }
    return units.ifEmpty { even() }
}

/** 原版 resolveCineramaSegmentSpans：每段（大字报的一块）覆盖的字的最早开口与最晚唱完；段前的空白跳过。 */
internal fun cineramaSegmentSpans(parts: List<String>, graphemes: List<String>, timeline: List<CineramaSpan>): List<CineramaSpan>? {
    if (parts.isEmpty() || graphemes.isEmpty() || timeline.isEmpty()) return null
    val spans = ArrayList<CineramaSpan>(parts.size)
    var cursor = 0
    val last = min(graphemes.size, timeline.size) - 1
    for (part in parts) {
        var start = Double.POSITIVE_INFINITY
        var end = Double.NEGATIVE_INFINITY
        for (char in splitCodePoints(part)) {
            while (cursor < graphemes.size && graphemes[cursor] != char && graphemes[cursor].isBlank()) cursor++
            val timing = timeline[min(cursor, last)]
            start = min(start, timing.start)
            end = max(end, timing.end)
            cursor++
        }
        if (start.isInfinite() || end.isInfinite()) {
            val at = timeline[min(cursor, last)].start
            spans += CineramaSpan(at, at)
        } else {
            spans += CineramaSpan(start, max(end, start))
        }
    }
    return spans
}

// ---------------------------------------------------------------- 跑马灯带

internal enum class CineramaMarqueeKind { Bands, Frame }

internal enum class CineramaSource { Title, Line, Translation, LineTranslation }

/**
 * 一句的跑马灯带：形态（双带 / 四边）、取材、绕行方向（[orbit] ±1）、带速（每秒走几屏）。
 * 双带的上带跑 [top]、下带跑 [bottom]；两条相同时下带倒过来（[mirror]，像同一圈灯带转到对面），不同时反向跑。
 * 四边只有一圈 [ring]。
 */
internal class CineramaMarquee(
    val kind: CineramaMarqueeKind,
    val source: CineramaSource,
    val orbit: Int,
    val screensPerSec: Double,
    val top: String,
    val bottom: String,
    val mirror: Boolean,
    val ring: String,
)

/** 原版 resolveBandText：取材落空时退回原文。 */
internal fun cineramaBandText(source: CineramaSource, line: String, translation: String, title: String): String {
    val l = line.trim()
    val tr = translation.trim()
    return when (source) {
        CineramaSource.Title -> if (title.isNotEmpty()) title.trim() else l
        CineramaSource.Translation -> tr.ifEmpty { l }
        CineramaSource.LineTranslation -> listOf(l, tr).filter { it.isNotEmpty() }.joinToString("　")
        CineramaSource.Line -> l
    }
}

/** 原版 pickMarqueeSource：四分之一跑歌名（盐 71），其余在 原文 /（有翻译时）翻译 / 原文+翻译 里按盐 73 挑。 */
internal fun cineramaPickSource(seed: Long, salt: Int, hasTranslation: Boolean, titleChance: Double = CINERAMA_TITLE_CHANCE): CineramaSource {
    if (cineramaHash01(seed, salt, 71) < titleChance.coerceIn(0.0, 1.0)) return CineramaSource.Title
    val pool = if (hasTranslation) {
        listOf(CineramaSource.Line, CineramaSource.Translation, CineramaSource.LineTranslation)
    } else {
        listOf(CineramaSource.Line)
    }
    return pool[floor(cineramaHash01(seed, salt, 73) * pool.size).toInt() % pool.size]
}

/**
 * 原版 resolveCineramaMarquee：面板选“关”或这一句是丝带时没有带；“自动”按种子 55% 双带、45% 四边。
 * [mode] 是面板值 off / auto / bands / frame。
 */
internal fun cineramaMarquee(
    mode: String,
    style: CineramaStyle,
    seed: Long,
    line: String,
    translation: String,
    title: String,
): CineramaMarquee? {
    if (mode == "off" || style == CineramaStyle.Ribbon) return null
    val kind = when (mode) {
        "auto" -> if (cineramaHash01(seed, 21, 151) < 0.55) CineramaMarqueeKind.Bands else CineramaMarqueeKind.Frame
        "frame" -> CineramaMarqueeKind.Frame
        else -> CineramaMarqueeKind.Bands
    }
    val source = cineramaPickSource(seed, if (kind == CineramaMarqueeKind.Frame) 8 else 7, translation.isNotBlank())
    val orbit = if (cineramaHash01(seed, 9, 83) < 0.5) 1 else -1
    val screensPerSec = CINERAMA_SPEED_MIN + cineramaHash01(seed, 10, 89) * CINERAMA_SPEED_SPAN
    fun text(of: CineramaSource) = cineramaBandText(of, line, translation, title)
    if (kind == CineramaMarqueeKind.Frame) {
        val ring = text(source)
        return CineramaMarquee(kind, source, orbit, screensPerSec, ring, ring, true, ring)
    }
    val top = text(if (source == CineramaSource.LineTranslation) CineramaSource.Line else source)
    val bottom = when (source) {
        CineramaSource.LineTranslation -> text(CineramaSource.Translation)
        CineramaSource.Title -> text(CineramaSource.Title)
        else -> text(CineramaSource.Translation)
    }.ifEmpty { top }
    return CineramaMarquee(kind, source, orbit, screensPerSec, top, bottom, bottom == top, top)
}

/**
 * 跑马灯的相位（0..1，一份字走过去为一圈）：带上的字每秒走 [screensPerSec] × [speed] 个屏宽，
 * 和这一份字多长无关（原版 resolveCineramaBandPeriodSec）。量不到长度时按原版的兜底周期（每份 screensPerSec × 10 秒）。
 * [t] 是歌词时间，暂停时带子也停。
 */
internal fun cineramaBandShift(t: Double, screensPerSec: Double, speed: Double, screenWidth: Double, unitPx: Double): Double {
    val gain = if (speed > 0) speed else 1.0
    val rate = if (screensPerSec > 0 && unitPx > 0 && screenWidth > 0) {
        screensPerSec * gain * screenWidth / unitPx
    } else {
        gain / max(1e-3, screensPerSec * CINERAMA_SPEED_SPAN_SEC)
    }
    return cineramaFrac(t * rate)
}

/**
 * 一条带上第一份字的位移（以“份”为单位，0..−1 之间循环）：上带按绕行方向走；下带和上带同字时倒过来（画的时候转 180°），
 * 不同字时反向。原版的 m = orbit > 0 ? −shift : shift − 1，下带镜像 m + 1、反向 −1 − m。
 */
internal fun cineramaBandSlot(shift: Double, orbit: Int, bottom: Boolean, mirror: Boolean): Double {
    val m = if (orbit > 0) -shift else shift - 1
    return when {
        !bottom -> m
        mirror -> m + 1
        else -> -1 - m
    }
}

/**
 * 四边一圈排几份字、每个字再加多少字距（px），让整数份正好绕满一圈（原版 buildMarqueeRing 的 layout）：
 * 先按四舍五入取份数，字距要压得比字宽的 15% 还紧时少放一份。
 */
internal fun cineramaRingCopies(lap: Double, unit: Double, glyphs: Int): Pair<Int, Double> {
    if (!(lap > 0) || !(unit > 0) || glyphs <= 0) return 1 to 0.0
    var copies = max(1, cineramaRound(lap / unit).toInt())
    var spacing = (lap / copies - unit) / glyphs
    val limit = -CINERAMA_TIGHTEN_RATIO * (unit / glyphs)
    if (spacing < limit) {
        copies = max(1, copies - 1)
        spacing = (lap / copies - unit) / glyphs
    }
    return copies to spacing
}

/** 圆角矩形一圈的周长（四边环的文字路径）：直边加四个四分之一圆。 */
internal fun cineramaRingLap(width: Double, height: Double, radius: Double): Double =
    2 * max(0.0, width - 2 * radius) + 2 * max(0.0, height - 2 * radius) + 2 * PI * radius

/** 四边环这一帧的起点（px，0..一份长）：原版 startOffset 落在 [−一份, 0)，这里整体挪一份，从 0 起排正好一圈。 */
internal fun cineramaRingOffset(shift: Double, orbit: Int, unit: Double): Double = cineramaFrac(orbit * shift) * unit

// ---------------------------------------------------------------- 转场与相位

/** 两句紧邻（下一句开口离这一句收尾不到 0.6 秒）：这一句不淡出，由下一句的转场接走。 */
internal fun cineramaAdjacent(end: Double, nextStart: Double?): Boolean =
    nextStart != null && nextStart - end <= CINERAMA_GAP_SEC

/** 转场时长：两句开口间距的 22%，夹在 0.14–0.28 秒。 */
internal fun cineramaTransitionSec(start: Double, nextStart: Double): Double =
    cineramaClamp(max(0.001, nextStart - start) * 0.22, 0.14, 0.28)

/** 溶解转场的一帧：在场程度 [presence]（0..1）→ 不透明度（快走完时再收尾）和缩放。 */
internal fun cineramaDissolve(presence: Double, out: CineramaEnvelope) {
    val p = cineramaClamp(presence, 0.0, 1.0)
    val away = 1 - p
    val tail = 1 - cineramaClamp((away - 0.85) / 0.15, 0.0, 1.0)
    out.opacity = (p * tail).toFloat()
    out.scale = (1 - away * 0.03).toFloat()
}

/** 一层（一句的正文或它的带子）这一帧的包络。 */
internal class CineramaEnvelope {
    var opacity = 1f
    var scale = 1f
    var bands = 1f

    fun set(opacity: Float, scale: Float, bands: Float) {
        this.opacity = opacity
        this.scale = scale
        this.bands = bands
    }
}

/**
 * 这一帧画哪一句、要不要叠上一句的退场层。照原版 client.mjs 的帧循环，但全部按时间算（seek 稳定）：
 * - 当前句是“开口时间 ≤ t 的最后一句”，句间（紧邻时）一直留在屏上；
 * - 当前句的入场是溶解（0.28 秒或句长的四成），不紧邻下一句时在行窗口末尾溶解退场（0.22 秒或句长的 35%）；
 * - 和上一句紧邻时，上一句在转场时长内溶解退场；它的带子在前半程淡出，当前句的带子在后半程淡入。
 */
internal class CineramaFrame {
    var current = -1
    var outgoing = -1
    val now = CineramaEnvelope()
    val out = CineramaEnvelope()
}

internal fun cineramaPlanAt(starts: DoubleArray, t: Double): Int {
    var lo = 0
    var hi = starts.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (starts[mid] <= t) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/**
 * [starts] / [ends] 是各句行窗口；[still] 为 true 时（减少动态效果）当前句整句定格，不叠退场层，
 * 这时宿主只在换句时发一次时间，[between] 表示已经到了句间（不紧邻下一句就收起，免得长间奏里一直停着上一句）。
 */
internal fun cineramaEvaluate(starts: DoubleArray, ends: DoubleArray, t: Double, still: Boolean, between: Boolean, frame: CineramaFrame) {
    val index = cineramaPlanAt(starts, t)
    frame.current = index
    frame.outgoing = -1
    if (index < 0) return
    val start = starts[index]
    val end = ends[index]
    val next = if (index + 1 < starts.size) starts[index + 1] else null
    val adjacent = cineramaAdjacent(end, next)
    if (still) {
        val gone = !adjacent && (t >= end || between)
        frame.now.set(if (gone) 0f else 1f, 1f, if (gone) 0f else 1f)
        return
    }
    val span = max(0.001, end - start)
    val enter = min(0.28, span * 0.4)
    val exit = if (adjacent) 0.0 else min(0.22, span * 0.35)
    var exitProgress = -1.0
    when {
        t < start + enter -> cineramaDissolve(cineramaSmoothstep((t - start) / enter), frame.now)
        exit > 0 && t > end - exit -> {
            exitProgress = min(1.0, (t - (end - exit)) / exit)
            cineramaDissolve(1 - cineramaSmoothstep(exitProgress), frame.now)
        }
        else -> frame.now.set(1f, 1f, 1f)
    }
    var bands = if (exitProgress >= 0) (1 - cineramaSmoothstep(exitProgress * 2)) else 1.0
    if (index > 0 && cineramaAdjacent(ends[index - 1], start)) {
        val duration = cineramaTransitionSec(starts[index - 1], start)
        val elapsed = t - start
        if (elapsed >= 0 && elapsed < duration) {
            val p = elapsed / duration
            frame.outgoing = index - 1
            cineramaDissolve(1 - cineramaSmoothstep(p), frame.out)
            frame.out.bands = (1 - cineramaSmoothstep(p * 2)).toFloat()
            bands = cineramaSmoothstep(p * 2 - 1)
        }
    }
    frame.now.bands = bands.toFloat()
}

/** 小字报一个单元的出现进度：开口后 0.18 秒（或单元时长的一半）内 smoothstep 到 1。 */
internal fun cineramaUnitProgress(start: Double, end: Double, t: Double, enter: Double = CINERAMA_UNIT_ENTER): Double {
    val span = max(0.001, end - start)
    return cineramaSmoothstep((t - start) / min(enter, span * 0.5))
}

// ---------------------------------------------------------------- 大字报

/** 大字报一块的持续运动：缩放起止、横 / 竖漂移（屏宽 / 屏高的百分比）、字距起止（em）。 */
internal class CineramaMotion(
    val scaleFrom: Double,
    val scaleTo: Double,
    val xFrom: Double,
    val yFrom: Double,
    val trackFrom: Double = 0.0,
    val trackTo: Double = 0.0,
) {
    val tracks: Boolean get() = trackFrom != 0.0 || trackTo != 0.0
}

internal val CINERAMA_STILL_MOTION = CineramaMotion(1.0, 1.0, 0.0, 0.0)

/** 原版 HERO_MOTION_POOL：静止 4、推近 2、拉远 2、横移 2、竖移 1、摊开 2（共 13）。 */
internal val CINERAMA_MOTION_POOL = listOf(
    4.0 to CINERAMA_STILL_MOTION,
    2.0 to CineramaMotion(0.93, 1.5, -1.5, 0.0),
    2.0 to CineramaMotion(1.5, 0.93, 1.5, 0.0),
    2.0 to CineramaMotion(1.0, 1.0, -2.2, 0.0),
    1.0 to CineramaMotion(1.0, 1.0, 0.0, -2.6),
    2.0 to CineramaMotion(1.0, 1.3, 0.0, 0.0, 0.0, 0.15),
)

internal fun cineramaPickMotion(seed: Long, index: Int): CineramaMotion {
    var roll = cineramaHash01(seed, index, 149) * CINERAMA_MOTION_POOL.sumOf { it.first }
    for ((weight, motion) in CINERAMA_MOTION_POOL) {
        roll -= weight
        if (roll < 0) return motion
    }
    return CINERAMA_MOTION_POOL[0].second
}

/** 原版 estimateCharWidthEm：估一个字多宽（em），用来在量字之前决定怎么分块。 */
internal fun cineramaCharWidthEm(char: String): Double {
    val c = char.codePointAt(0)
    return when {
        c in 0x3000..0x9FFF || c in 0xFF00..0xFFEF || c in 0x3040..0x30FF -> 1.0
        c in 'A'.code..'Z'.code || c in '0'.code..'9'.code -> 0.62
        c in 'a'.code..'z'.code -> 0.55
        Character.isWhitespace(c) -> 0.28
        else -> 0.4
    }
}

internal fun cineramaChunkWidthEm(text: String): Double =
    splitCodePoints(text).sumOf { cineramaCharWidthEm(it) + CINERAMA_HERO_TRACK_EM }

/**
 * 大字报的尺寸约束（px）：字号上限（屏高 51.2%，且一行放得进内容区）、下限（屏高 11.2%）、
 * “这一块放得下”的门槛（屏高 25.6%），以及可用宽度（屏宽 73.6%，扣掉四边带占的两侧，且不超过内容区）。
 */
internal class CineramaHeroMetrics(val maxFont: Double, val minFont: Double, val fitFont: Double, val usableWidth: Double) {
    /** 这段字按可用宽度能放多大（还没夹到上下限）。 */
    fun raw(text: String): Double = min(maxFont, usableWidth / max(0.4, cineramaChunkWidthEm(text)))

    fun font(text: String): Double = cineramaClamp(raw(text), minFont, maxFont)

    fun fits(text: String): Boolean = font(text) >= fitFont

    companion object {
        /** [screenW] × [screenH] 是屏（安全区）；内容区去掉了带子和副行；[sideInset] 是四边带两侧合计占的宽度。 */
        fun of(screenW: Double, screenH: Double, contentW: Double, contentH: Double, sideInset: Double): CineramaHeroMetrics {
            val minFont = screenH * 0.112
            val maxFont = max(minFont, min(screenH * 0.512, contentH / CINERAMA_HERO_LINE_HEIGHT))
            val usable = max(screenW * 0.2, min(screenW * 0.736 - sideInset, contentW))
            return CineramaHeroMetrics(maxFont, minFont, screenH * 0.256, usable)
        }
    }
}

/** 大字报的一块：文字、闪出窗口、斜体、字号（px）、要不要“先描边后填色”、填色方向、持续运动。 */
internal class CineramaChunk(
    val text: String,
    val start: Double,
    val end: Double,
    val italic: Boolean,
    val font: Double,
    val fill: Boolean,
    val fillVertical: Boolean,
    val motion: CineramaMotion,
)

/** 原版 splitLongWord：一个词整块放不下时逐字劈开，每段尽量长。 */
internal fun cineramaSplitLongWord(word: String, fits: (String) -> Boolean): List<String> {
    val pieces = ArrayList<String>()
    var current = ""
    for (char in splitCodePoints(word)) {
        val next = current + char
        if (current.isNotEmpty() && !fits(next)) {
            pieces += current
            current = char
            continue
        }
        current = next
    }
    if (current.isNotEmpty()) pieces += current
    return pieces
}

/** 原版 packHeroChunks：从一个词起，放得下且掷点小于 [chance]（盐 300 + 已并词数）就再并一个，最多 6 个词一块。 */
internal fun cineramaPackChunks(words: List<String>, chance: Double, seed: Long, fits: (String) -> Boolean): List<String> {
    val chunks = ArrayList<String>()
    var cursor = 0
    var chunkIndex = 0
    while (cursor < words.size) {
        var take = 1
        while (
            take < CINERAMA_MAX_WORDS_PER_CHUNK &&
            cursor + take < words.size &&
            fits(words.subList(cursor, cursor + take + 1).joinToString("")) &&
            cineramaHash01(seed, chunkIndex, 300 + take) < chance
        ) {
            take++
        }
        chunks += words.subList(cursor, cursor + take).joinToString("")
        cursor += take
        chunkIndex++
    }
    return chunks
}

/** 多于 5 块时并最窄的相邻两块（优先并完还放得下的，其次并完不小于字号下限的），并不动就停。 */
internal fun cineramaMergeChunks(chunks: List<String>, metrics: CineramaHeroMetrics): List<String> {
    var packed = chunks
    while (packed.size > CINERAMA_MAX_CHUNKS) {
        var bestIndex = -1
        var bestWidth = Double.POSITIVE_INFINITY
        var narrowIndex = -1
        var narrowWidth = Double.POSITIVE_INFINITY
        for (i in 0 until packed.size - 1) {
            val merged = packed[i] + packed[i + 1]
            val width = cineramaChunkWidthEm(merged)
            if (width < narrowWidth && metrics.raw(merged) >= metrics.minFont) {
                narrowWidth = width
                narrowIndex = i
            }
            if (!metrics.fits(merged)) continue
            if (width < bestWidth) {
                bestWidth = width
                bestIndex = i
            }
        }
        val at = if (bestIndex >= 0) bestIndex else narrowIndex
        if (at < 0) break
        packed = packed.subList(0, at) + (packed[at] + packed[at + 1]) + packed.subList(at + 2, packed.size)
    }
    return packed
}

/** 原版 heroFillChance：这一块几个可见字（字母、数字）→ 描边填色的概率，1 字 0、5 字起封顶 2/15，中间 smoothstep。 */
internal fun cineramaFillChance(visible: Int): Double =
    CINERAMA_FILL_CAP * cineramaSmoothstep((visible - 1) / 4.0)

internal fun cineramaVisibleCount(text: String): Int = splitCodePoints(text).count { cineramaWordLike(it) }

/**
 * 原版 buildHeroChunks + resolveCineramaStyle(hero-type)：按词分块（放不下的词逐字劈），按词时序定每块的闪出窗口，
 * 再给每块抽斜体、字号微调（0.95 / 1 / 1.05 相邻不重复）、描边填色（及方向）、持续运动（填色的块不动）。
 */
internal fun cineramaHeroChunks(
    plan: CineramaPlan,
    metrics: CineramaHeroMetrics,
    segmenter: CineramaSegmenter,
): List<CineramaChunk> {
    val seed = plan.seed
    val text = plan.line.text
    val words = ArrayList<String>()
    for (word in cineramaWords(text, segmenter)) {
        if (metrics.fits(word)) words += word else words += cineramaSplitLongWord(word, metrics::fits)
    }
    if (words.isEmpty()) words += text.trim().ifEmpty { "　" }
    val packed = cineramaMergeChunks(cineramaPackChunks(words, CINERAMA_CHUNK_CHANCE, seed, metrics::fits), metrics)
        .map { it.trimEnd() }

    val reveal = plan.heroReveal
    val spans = cineramaSegmentSpans(packed, plan.graphemes, plan.heroTimeline)
    val ordered = spans != null && spans.indices.all { i -> i == 0 || spans[i].start > spans[i - 1].start + 1e-4 }
    val windows = if (ordered) {
        val starts = spans.map { cineramaClamp(it.start, reveal.start, reveal.end) }
        packed.indices.map { i ->
            CineramaSpan(starts[i], max(starts[i] + 0.001, if (i + 1 < packed.size) starts[i + 1] else reveal.end))
        }
    } else {
        val step = max(0.001, reveal.end - reveal.start) / packed.size
        packed.indices.map { i -> CineramaSpan(reveal.start + step * i, reveal.start + step * (i + 1)) }
    }

    val fitFont = packed.fold(metrics.maxFont) { acc, chunk -> min(acc, metrics.font(chunk)) }
    val lineFont = cineramaClamp(fitFont, metrics.minFont, metrics.maxFont)
    var previousScale: Double? = null
    var previousAxis: Boolean? = null
    return packed.mapIndexed { i, chunk ->
        val factor = cineramaChoose(CINERAMA_CHUNK_SCALES, "$seed:chunk-scale:$i", previousScale) ?: 1.0
        previousScale = factor
        val vertical = cineramaChoose(CINERAMA_FILL_AXES, "$seed:fill-axis:$i", previousAxis) ?: false
        previousAxis = vertical
        val fill = cineramaHash01(seed, i, 191) < cineramaFillChance(cineramaVisibleCount(chunk))
        CineramaChunk(
            text = chunk,
            start = windows[i].start,
            end = windows[i].end,
            italic = cineramaHash01(seed, i, 113) < CINERAMA_ITALIC_CHANCE,
            font = cineramaClamp(lineFont * factor, metrics.minFont, metrics.font(chunk)),
            fill = fill,
            fillVertical = vertical,
            motion = if (fill) CINERAMA_STILL_MOTION else cineramaPickMotion(seed, i),
        )
    }
}

/**
 * 大字报一块这一帧的样子（原版 buildHero 的 update）：不在窗口里就不画；
 * 淡入 0.108 秒、在窗口末尾前 0.144–0.072 秒淡出；入场从 0.92 放大到 1，再叠持续运动（时长不到 1.2 秒的块按比例减弱）；
 * [fill] 是描边填色扫到哪儿（0..1，就是块内进度）。
 */
internal class CineramaChunkLook {
    var opacity = 0f
    var driftX = 0f
    var driftY = 0f
    var scale = 1f
    var trackEm = 0f
    var fill = 1f
}

internal fun cineramaChunkLook(chunk: CineramaChunk, t: Double, out: CineramaChunkLook): Boolean {
    if (t < chunk.start || t > chunk.end) return false
    val enter = CINERAMA_HERO_ENTER
    val exit = CINERAMA_HERO_EXIT
    val fadeIn = cineramaUnitProgress(chunk.start, chunk.start + enter * 2, t, enter)
    val fadeOut = cineramaUnitProgress(chunk.end - exit * 2, chunk.end, t, exit)
    val visible = fadeIn * (1 - fadeOut)
    if (visible <= 0.001) return false
    val span = max(0.001, chunk.end - chunk.start)
    val u = cineramaClamp((t - chunk.start) / span, 0.0, 1.0)
    val m = chunk.motion
    val amount = min(1.0, span / CINERAMA_MOTION_REF_SEC)
    val glide = 1 + ((1 - u) * (m.scaleFrom - 1) + u * (m.scaleTo - 1)) * amount
    out.opacity = visible.toFloat()
    out.driftX = (m.xFrom * (1 - u) * amount).toFloat()
    out.driftY = (m.yFrom * (1 - u) * amount).toFloat()
    out.scale = ((0.92 + 0.08 * fadeIn) * glide).toFloat()
    out.trackEm = (((1 - u) * m.trackFrom + u * m.trackTo) * amount).toFloat()
    out.fill = u.toFloat()
    return true
}

// ---------------------------------------------------------------- 斜切丝带

/** 一条丝带：角度、顶边（屏高 %）、厚度（屏高 %）、文字、漂移方向和速率、斜向光泽。 */
internal class CineramaStrip(
    val text: String,
    val angle: Double,
    val topPct: Double,
    val thicknessPct: Double,
    val driftDir: Int,
    val driftVelScale: Double,
    val shadeAngle: Double,
    val shadeStart: Double,
    val shadeSpan: Double,
    val shadeStrength: Double,
) {
    val fontPct: Double get() = thicknessPct * CINERAMA_RIBBON_FONT_RATIO

    /** 中线在屏高上的位置（0..1）。 */
    val centerFrac: Double get() = cineramaClamp(topPct / 100 + thicknessPct / 200, 0.0, 1.0)
}

/**
 * 原版 pickRibbonAngles：角度池洗牌（盐 211）后左右两侧轮流取（起手一侧按盐 281），
 * |角度| < 26° 的平角最多 count / 3 条（至少 1 条）。取出来的再按倍率缩放、夹到 ±60°。
 */
internal fun cineramaRibbonAngles(count: Int, seed: Long, angleScale: Double = 1.0): List<Double> {
    val pool = cineramaShuffle(CINERAMA_RIBBON_ANGLES, seed, 211)
    val sides = arrayOf(ArrayDeque(pool.filter { it < 0 }), ArrayDeque(pool.filter { it > 0 }))
    val shallowCap = max(1, count / 3)
    val picked = ArrayList<Double>(count)
    var shallowUsed = 0
    var side = if (cineramaHash01(seed, 0, 281) < 0.5) 0 else 1
    while (picked.size < count) {
        val list = sides[side]
        while (list.isNotEmpty()) {
            val angle = list.removeFirst()
            val shallow = abs(angle) < CINERAMA_RIBBON_SHALLOW
            if (shallow && shallowUsed >= shallowCap) continue
            if (shallow) shallowUsed++
            picked += angle
            break
        }
        side = 1 - side
        if (sides[0].isEmpty() && sides[1].isEmpty()) break
    }
    return picked.map { cineramaClamp(it * angleScale, -CINERAMA_RIBBON_ANGLE_LIMIT, CINERAMA_RIBBON_ANGLE_LIMIT) }
}

/** 原版 spreadRibbonTops：从屏顶外 6% 到屏底外 4% 均匀铺开，洗牌（盐 223）后再各自上下抖 ±10%。 */
internal fun cineramaRibbonTops(count: Int, seed: Long): List<Double> =
    cineramaShuffle(List(count) { i -> -6.0 + 104.0 * i / max(1, count - 1) }, seed, 223)
        .mapIndexed { i, top -> top + (cineramaHash01(seed, i, 227) - 0.5) * 20 }

/** 原版 resolveRibbonStrips：[count] 条（3–9），文字在原文和翻译之间轮流；整句和翻译都空时没有丝带。 */
internal fun cineramaRibbonStrips(seed: Long, count: Int, line: String, translation: String): List<CineramaStrip> {
    val texts = listOf(line.trim(), translation.trim()).filter { it.isNotEmpty() }
    if (texts.isEmpty()) return emptyList()
    val n = count.coerceIn(3, 9)
    val angles = cineramaRibbonAngles(n, seed)
    val tops = cineramaRibbonTops(n, seed)
    return List(n) { i ->
        val thickness = CINERAMA_RIBBON_MIN + cineramaHash01(seed, i, 241) * (CINERAMA_RIBBON_MAX - CINERAMA_RIBBON_MIN)
        CineramaStrip(
            text = texts[i % texts.size],
            angle = angles[i % angles.size],
            topPct = cineramaClamp(tops[i], -(thickness - CINERAMA_RIBBON_MIN_VISIBLE), 100 - CINERAMA_RIBBON_MIN_VISIBLE),
            thicknessPct = thickness,
            driftDir = if (cineramaHash01(seed, i, 271) < 0.5) 1 else -1,
            driftVelScale = 0.8 + cineramaHash01(seed, i, 277) * 0.4,
            shadeAngle = 100 + cineramaHash01(seed, i, 251) * 160,
            shadeStart = cineramaHash01(seed, i, 257) * 20,
            shadeSpan = 46 + cineramaHash01(seed, i, 263) * 26,
            shadeStrength = 0.2 + cineramaHash01(seed, i, 269) * 0.22,
        )
    }
}

/** 原版 ribbonCoverageWidth：斜放的丝带要多长才能横穿整屏。 */
internal fun cineramaRibbonCoverage(width: Double, height: Double, angle: Double, centerFrac: Double): Double {
    val rad = abs(angle) * PI / 180
    val c = abs(kotlin.math.cos(rad))
    val s = abs(sin(rad))
    val center = cineramaClamp(centerFrac, 0.0, 1.0)
    return width * c + 2 * max(center, 1 - center) * height * s
}

/** 原版 ribbonBoxLength + ribbonOverflowPct：丝带盒子的长度（px），两端各至少伸出屏宽 8%，还要够漂移那一段。 */
internal fun cineramaRibbonLength(width: Double, height: Double, strip: CineramaStrip, driftClampPx: Double): Double {
    val rad = abs(strip.angle) * PI / 180
    val need = cineramaRibbonCoverage(width, height, strip.angle, strip.centerFrac) * (1 + CINERAMA_RIBBON_MARGIN) +
        2 * abs(driftClampPx) * abs(sin(rad))
    val overflow = max(CINERAMA_RIBBON_MIN_OVERFLOW, (need / width - 1) / 2 * 100)
    return width * (1 + 2 * overflow / 100)
}

/**
 * 丝带上下漂移的上限（px，原版 normalizeRibbonDriftClamp）：在 1000 高的参考屏上算——
 * 每条丝带的中线离屏边还剩多远（留 3% 边），换算成沿漂移方向能走多少，取最小；
 * 走完这段要的时间比“句长 + 1 秒”短时按比例放宽（最多 12 倍、不超过屏高 15%）。再按实际屏高缩放。
 */
internal fun cineramaRibbonDriftClamp(strips: List<CineramaStrip>, spanSec: Double, height: Double): Double {
    if (strips.isEmpty()) return CINERAMA_DRIFT_CLAMP * height / 1000
    val refH = 1000.0
    val margin = refH * CINERAMA_DRIFT_MARGIN / 100
    var limit = Double.POSITIVE_INFINITY
    for (strip in strips) {
        val sinAbs = abs(sin(strip.angle * PI / 180))
        val edge = min(strip.centerFrac, 1 - strip.centerFrac) * refH
        limit = if (sinAbs < 1e-6 || edge <= margin) min(limit, CINERAMA_DRIFT_CLAMP) else min(limit, (edge - margin) / sinAbs)
    }
    val minReach = spanSec + CINERAMA_TRAVEL_TAIL
    var shortest = Double.POSITIVE_INFINITY
    for (strip in strips) {
        val sinAbs = abs(sin(strip.angle * PI / 180))
        val vel = CINERAMA_DRIFT_VEL * strip.driftVelScale
        if (vel > 0 && sinAbs >= 1e-6) shortest = min(shortest, limit * sinAbs / vel)
    }
    if (shortest.isFinite() && shortest < minReach) {
        val ceiling = refH * CINERAMA_DRIFT_MAX / 100
        limit = min(limit * (minReach / shortest), min(limit * 12, ceiling))
    }
    return limit * height / refH
}

/** 丝带这一帧的上下漂移（px）：按方向匀速走，夹在 ±[clampPx]。速度按屏高折算（原版 4px/s 对 1000 高的屏）。 */
internal fun cineramaRibbonDrift(strip: CineramaStrip, elapsed: Double, height: Double, clampPx: Double): Double {
    val vel = CINERAMA_DRIFT_VEL * strip.driftVelScale * height / 1000
    return cineramaClamp(strip.driftDir * vel * max(0.0, elapsed), -clampPx, clampPx)
}

/** 丝带上的字往左走了多少（px）：每秒 0.16 个屏宽，走满“句长 + 1 秒”就停。 */
internal fun cineramaRibbonScroll(elapsed: Double, width: Double, spanSec: Double): Double {
    val speed = CINERAMA_RIBBON_TEXT_SPEED * width
    return min(max(0.0, elapsed) * speed, speed * (spanSec + CINERAMA_TRAVEL_TAIL))
}

/**
 * 丝带的斜向光泽（原版 CSS linear-gradient 的六个色标，%）：亮带从 start 起、在 start + span/2 最亮、start + span 收住，
 * 隔 12% 再起一道暗带。色标按 CSS 的规矩单调化，再夹进 0..1。
 */
internal fun cineramaShadeStops(strip: CineramaStrip): FloatArray {
    val s = strip.shadeStart
    val span = strip.shadeSpan
    val raw = doubleArrayOf(s, s + span * 0.5, s + span, s + span + 12, min(96.0, s + span * 1.5 + 12), 100.0)
    val out = FloatArray(raw.size)
    var last = 0.0
    for (i in raw.indices) {
        last = max(last, raw[i])
        out[i] = (cineramaClamp(last, 0.0, 100.0) / 100).toFloat()
    }
    return out
}

// ---------------------------------------------------------------- 辉光

/** 互补误差函数（Abramowitz–Stegun 7.1.26，误差 < 1.5e-7）。 */
internal fun cineramaErfc(x: Double): Double {
    if (x < 0) return 2 - cineramaErfc(-x)
    val t = 1 / (1 + 0.3275911 * x)
    val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
    return poly * exp(-x * x)
}

/** CSS 盒阴影（模糊 b、σ = b/2）在边外 / 边内 [distance] 处的覆盖率：边上 0.5，往外按高斯尾巴衰减。 */
internal fun cineramaShadowCoverage(distance: Double, blur: Double): Double {
    val sigma = blur / 2
    if (sigma <= 1e-6) return if (distance <= 0) 1.0 else 0.0
    return 0.5 * cineramaErfc(distance / (sigma * sqrt(2.0)))
}

/** 渐变取样的位置（0..1，靠边处密一点）。 */
internal val CINERAMA_GLOW_STOPS = floatArrayOf(0f, 0.06f, 0.14f, 0.25f, 0.4f, 0.6f, 0.8f, 1f)

/**
 * 跑马灯带边线的辉光（原版暗色主题的“辉光”档，强度 0.3）：带厚 [band] px。返回两组透明度，按 [CINERAMA_GLOW_STOPS]
 * 取样在 0..[extent] 的距离上——屏面一侧是亮芯（模糊 0.22 × 带厚 × 强度，浓度 100%）叠中间层（0.5，42%），
 * 带子一侧是往外溢的一层（0.5，22%）；浓度都再乘响应 0.55 + 0.45 × 强度。
 */
internal class CineramaEdgeGlow(val extent: Float, val screen: FloatArray, val band: FloatArray)

internal fun cineramaEdgeGlow(band: Double, strength: Double = CINERAMA_EDGE_GLOW): CineramaEdgeGlow {
    val response = CINERAMA_ALPHA_FLOOR + (1 - CINERAMA_ALPHA_FLOOR) * strength
    val core = band * 0.22 * strength
    val mid = band * 0.5 * strength
    val wide = band * 0.5 * strength
    val extent = 1.5 * max(core, max(mid, wide))
    val screen = FloatArray(CINERAMA_GLOW_STOPS.size)
    val outward = FloatArray(CINERAMA_GLOW_STOPS.size)
    for (i in CINERAMA_GLOW_STOPS.indices) {
        val d = CINERAMA_GLOW_STOPS[i] * extent
        val a = 1.0 * response * cineramaShadowCoverage(d, core)
        val b = 0.42 * response * cineramaShadowCoverage(d, mid)
        screen[i] = (1 - (1 - a) * (1 - b)).toFloat()
        outward[i] = (0.22 * response * cineramaShadowCoverage(d, wide)).toFloat()
    }
    return CineramaEdgeGlow(extent.toFloat(), screen, outward)
}

/** 一层盒阴影在 0..extent 上按 [CINERAMA_GLOW_STOPS] 取样的透明度（浓度 [alpha]、模糊 [blur]、往外偏 [offset]）。 */
internal fun cineramaShadowStops(alpha: Double, blur: Double, offset: Double, extent: Double): FloatArray =
    FloatArray(CINERAMA_GLOW_STOPS.size) { i -> (alpha * cineramaShadowCoverage(CINERAMA_GLOW_STOPS[i] * extent - offset, blur)).toFloat() }

// ---------------------------------------------------------------- 小字报排版

/**
 * 小字报的行内排版（原版 inline-block 单元、整块居中）：单元宽 [widths]、行高 [rowHeight]、最宽 [maxWidth]，
 * 放不下就换行（空白单元不放在行首）。返回每个单元左上角相对文字盒左上角的位置（x、y 交替），NaN 表示不画；
 * [size] 写回文字盒的宽和高。
 */
internal fun cineramaFlow(widths: FloatArray, blank: BooleanArray, rowHeight: Float, maxWidth: Float, size: FloatArray): FloatArray {
    val pos = FloatArray(widths.size * 2) { Float.NaN }
    val rowStart = ArrayList<Int>()
    val rowWidth = ArrayList<Float>()
    var x = 0f
    var ink = 0f
    var first = -1
    fun closeRow() {
        rowStart += first
        rowWidth += ink
        x = 0f
        ink = 0f
        first = -1
    }
    for (i in widths.indices) {
        val w = widths[i]
        if (first >= 0 && x + w > maxWidth + 0.5f) {
            closeRow()
            // 行尾放不下的空白就是换行处，不画
            if (blank[i]) continue
        }
        if (first < 0 && blank[i]) continue
        if (first < 0) first = i
        pos[i * 2] = x
        pos[i * 2 + 1] = rowStart.size * rowHeight
        x += w
        if (!blank[i]) ink = x
    }
    if (first >= 0) closeRow()
    var boxWidth = 0f
    for (w in rowWidth) boxWidth = max(boxWidth, w)
    // 每行居中：按行宽和最宽一行的差平移
    for (r in rowStart.indices) {
        val shift = (boxWidth - rowWidth[r]) / 2f
        val to = if (r + 1 < rowStart.size) rowStart[r + 1] else widths.size
        for (i in rowStart[r] until to) if (!pos[i * 2].isNaN()) pos[i * 2] += shift
    }
    size[0] = boxWidth
    size[1] = rowStart.size * rowHeight
    return pos
}

// ---------------------------------------------------------------- 编译

/** 一句的放映计划（编译期定好的部分）。时间都是秒。 */
internal class CineramaPlan(
    val index: Int,
    val line: StageLine,
    /** 行窗口：开口到“句末 + 视觉尾巴”，不越过下一句开口。 */
    val start: Double,
    val end: Double,
    val seed: Long,
    val split: CineramaSplit,
    val layout: CineramaLayout,
    val style: CineramaStyle,
    val units: List<CineramaUnit>,
    val marquee: CineramaMarquee?,
    val graphemes: List<String>,
    /** 大字报分块用的字时间线和出字窗口（按夹过的行窗口算，同原版）。 */
    val heroTimeline: List<CineramaSpan>,
    val heroReveal: CineramaSpan,
)

internal class CineramaProgram(val plans: List<CineramaPlan>) {
    val starts = DoubleArray(plans.size) { plans[it].start }
    val ends = DoubleArray(plans.size) { plans[it].end }
}

/**
 * 原版 compileCineramaProgram：每句按 "种子:序号:原文" 派生句种子，抽切分（相邻不重复）、排版、样式（权重 + 相邻不重复），
 * 再定跑马灯带。行窗口取“句末 + [tailSec]”（Folia 的 renderEndTime 也是句末加一段视觉尾巴），夹到下一句开口。
 */
internal fun cineramaCompile(
    lines: List<StageLine>,
    seed: String,
    title: String,
    weights: CineramaWeights,
    marquee: String,
    segmenter: CineramaSegmenter = CineramaWordSegmenter,
    tailSec: Double = CINERAMA_TAIL_SEC,
): CineramaProgram {
    var previousSplit: CineramaSplit? = null
    var previousLayout: CineramaLayout? = null
    var previousStyle: CineramaStyle? = null
    val plans = lines.mapIndexed { index, line ->
        val start = line.startMs / 1000.0
        val authoredEnd = line.endMs / 1000.0
        val rawEnd = max(start + 0.001, authoredEnd + tailSec)
        val nextStart = lines.getOrNull(index + 1)?.startMs?.div(1000.0) ?: Double.POSITIVE_INFINITY
        val end = max(start + 0.001, min(rawEnd, nextStart))
        val text = line.text
        val lineSeed = "$seed:$index:$text"
        val split = cineramaPickSplit(text, "$lineSeed:split", previousSplit, segmenter)
        val layout = cineramaPickLayout(stableHash("$lineSeed:layout"), previousLayout)
        previousSplit = split
        previousLayout = layout

        val graphemes = splitCodePoints(text)
        val words = line.words.map { CineramaWord(it.text, it.startMs / 1000.0, it.endMs / 1000.0) }
        val lastWordEnd = words.lastOrNull()?.end
        val rawReveal = cineramaReveal(start, rawEnd, lastWordEnd, authoredEnd)
        val rawTimeline = cineramaTimeline(graphemes, words, start, rawEnd, rawReveal)
        val units = cineramaUnits(text, cineramaParts(text, split, segmenter), graphemes, rawTimeline, rawReveal)
        val heroReveal = cineramaReveal(start, end, lastWordEnd, authoredEnd)
        val heroTimeline = if (end == rawEnd) rawTimeline else cineramaTimeline(graphemes, words, start, end, heroReveal)

        val planSeed = stableHash(lineSeed)
        val style = cineramaPickStyle(
            planSeed,
            previousStyle,
            weights,
            heroOk = graphemes.isNotEmpty(),
            ribbonOk = units.isNotEmpty() || text.isBlank(),
        )
        previousStyle = style
        CineramaPlan(
            index = index,
            line = line,
            start = start,
            end = end,
            seed = planSeed,
            split = split,
            layout = layout,
            style = style,
            units = units,
            marquee = cineramaMarquee(marquee, style, planSeed, text, line.translation, title),
            graphemes = graphemes,
            heroTimeline = heroTimeline,
            heroReveal = if (end == rawEnd) rawReveal else heroReveal,
        )
    }
    return CineramaProgram(plans)
}

// ---------------------------------------------------------------- 常数（原模组 CINERAMA_TUNING 与默认值）

private const val CINERAMA_GOLDEN = -1640531535 // 2654435761 按 32 位有符号
private val CINERAMA_WORD_LIKE = Regex("[\\p{L}\\p{N}]")
private val CINERAMA_CLAUSE = Regex("[，,。、；;：:！!？?…—~\n]")
private const val CINERAMA_MAX_GRAPHEME_UNITS = 32
private const val CINERAMA_REVEAL_FLOOR = 0.3
private val CINERAMA_LAYOUT_GROUP_WEIGHTS = doubleArrayOf(1.0, 1.0, 0.5)

/** 句末之后的视觉尾巴（秒）。 */
internal const val CINERAMA_TAIL_SEC = 0.6
private const val CINERAMA_GAP_SEC = 0.6

/** 小字报单元的入场（0.3 × 出字节奏 0.6）。 */
private const val CINERAMA_UNIT_ENTER = 0.18

/** 大字报一块的淡入 / 淡出（0.18 / 0.12 × 出字节奏 0.6）。 */
private const val CINERAMA_HERO_ENTER = 0.108
private const val CINERAMA_HERO_EXIT = 0.072
internal const val CINERAMA_HERO_LINE_HEIGHT = 1.02
internal const val CINERAMA_HERO_TRACK_EM = 0.01
internal const val CINERAMA_OUTLINE_EM = 0.014
private const val CINERAMA_MOTION_REF_SEC = 1.2
private const val CINERAMA_MAX_CHUNKS = 5
private const val CINERAMA_MAX_WORDS_PER_CHUNK = 6
private const val CINERAMA_CHUNK_CHANCE = 0.55
private const val CINERAMA_ITALIC_CHANCE = 0.35
private const val CINERAMA_FILL_CAP = 2.0 / 15.0
private val CINERAMA_CHUNK_SCALES = listOf(0.95, 1.0, 1.05)
private val CINERAMA_FILL_AXES = listOf(false, true)

private const val CINERAMA_TITLE_CHANCE = 0.25
private const val CINERAMA_SPEED_MIN = 0.12
private const val CINERAMA_SPEED_SPAN = 0.12
private const val CINERAMA_SPEED_SPAN_SEC = 10.0
private const val CINERAMA_TIGHTEN_RATIO = 0.15
private const val CINERAMA_EDGE_GLOW = 0.3
private const val CINERAMA_ALPHA_FLOOR = 0.55

private val CINERAMA_RIBBON_ANGLES = listOf(-54.0, -42.0, -32.0, -23.0, -13.0, 12.0, 21.0, 31.0, 41.0, 53.0)
private const val CINERAMA_RIBBON_ANGLE_LIMIT = 60.0
private const val CINERAMA_RIBBON_SHALLOW = 26.0
private const val CINERAMA_RIBBON_MIN_VISIBLE = 3.0
private const val CINERAMA_RIBBON_MIN = 3.4 * 1.8
private const val CINERAMA_RIBBON_MAX = 4.6 * 1.8
private const val CINERAMA_RIBBON_FONT_RATIO = 0.66
private const val CINERAMA_RIBBON_MARGIN = 0.06
private const val CINERAMA_RIBBON_MIN_OVERFLOW = 8.0
private const val CINERAMA_RIBBON_TEXT_SPEED = 0.16
private const val CINERAMA_TRAVEL_TAIL = 1.0
private const val CINERAMA_DRIFT_VEL = 4.0
private const val CINERAMA_DRIFT_CLAMP = 70.0
private const val CINERAMA_DRIFT_MARGIN = 3.0
private const val CINERAMA_DRIFT_MAX = 15.0
