package com.mytv.mymusic.ui.player.stage.modes

import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.player.stage.StageLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * 跳一跳的“路”（照 visualizer-jump 1.2.0 的 jumpSplit / jumpTimeline / jumpBoard / client.layoutLine）：纯计算，不碰 android.*。
 *
 * 一句 → 切块 → 每字时刻 → 每块一根柱子 → 时间表（每块的落地、起跳、柱子被踩下去 / 弹回来的时刻）→ 一行一行折成一条路。
 * 时间一律是秒（Double），全都由时刻直接算出来、不累加，所以拖进度、暂停、减少动态效果看到的是同一帧。
 */

// ---- 面板之外写死的那些旋钮（原模组的默认值） ----

/** 相邻两块柱子之间的缝（格，原模组 spacing）。 */
internal const val JUMP_SPACING = 1.2f

/** 柱高（格，原模组 pillarHeight × PILLAR_RATIO）。 */
internal const val JUMP_PILLAR_HEIGHT = 1.9f

/** 两行之间空出这么久才在行末立歇脚柱（秒，原模组 restGap）。 */
internal const val JUMP_REST_GAP = 1.75

/** 小人尺寸（原模组 runnerScale）。 */
internal const val JUMP_RUNNER_SCALE = 1.2f

/** 一次跨行交接里，镜头末段在“末块落地 → 下一行开口”之间的起点占比（原模组 HANDOVER_SHARE）。 */
private const val HANDOVER_SHARE = 0.22

/** 跳一跳这一屏的设置：面板上的 5 项（译文开关另算，不影响路）。 */
internal data class JumpConfig(
    val face: JumpFace,
    val pitch: Float,
    val chunkChars: Int,
    val flySeconds: Float,
    val arc: Float,
)

// ---- 切块（jumpSplit.splitJumpChunks） ----

/** 一块字：[text] 带着分词留下的空白（时刻按它数），[display] 是写在柱子上的那一截，[charCount] 是 [text] 的码位数。 */
internal class JumpChunk(val text: String, val display: String, val charCount: Int)

/**
 * 把一句的分词结果切成一块块。照原版：
 * 1. 相邻的中日韩词合起来，只要合起来不超过 [maxChunkChars] 个字宽（拉丁字母算 0.55）；
 * 2. 一块超宽就再切：先在空白后切，再在连字符、撇号后切，最后按字宽切，拉丁词内部永远不切；
 * 3. 只剩标点 / 空白的那一截黏到前一块上（柱子上不会单独立一个逗号）。
 */
internal fun jumpSplitChunks(segments: List<String>, maxChunkChars: Int, preferSplit: Boolean = true): List<JumpChunk> {
    val maxChars = max(1, maxChunkChars)
    val merged = ArrayList<String>()
    for (text in segments) {
        if (text.isEmpty()) continue
        val trimmed = trimSpaces(text)
        if (punctuationOnly(text) || trimmed.isEmpty()) {
            if (merged.isNotEmpty()) merged[merged.lastIndex] = merged.last() + text else merged += text
            continue
        }
        val lastTrimmed = if (merged.isNotEmpty()) trimSpaces(merged.last()) else ""
        val lastIsGlyph = lastTrimmed.isNotEmpty() && allWide(lastTrimmed)
        if (lastIsGlyph && allWide(trimmed) && widthOf(merged.last()) + widthOf(trimmed) <= maxChars) {
            merged[merged.lastIndex] = merged.last() + text
            continue
        }
        merged += text
    }
    val chunks = ArrayList<String>()
    fun push(text: String) {
        if (text.isEmpty()) return
        if (chunks.isNotEmpty() && punctuationOnly(text)) {
            chunks[chunks.lastIndex] = chunks.last() + text
            return
        }
        chunks += text
    }
    for (text in merged) {
        val trimmed = trimSpaces(text)
        if (punctuationOnly(text) || trimmed.isEmpty()) {
            push(text)
            continue
        }
        val pieces = if (preferSplit && widthOf(trimmed) > maxChars) splitText(trimmed, maxChars) else listOf(trimmed)
        val at = text.indexOf(trimmed)
        val leading = text.substring(0, at)
        val trailing = text.substring(at + trimmed.length)
        pieces.forEachIndexed { index, piece ->
            var slice = piece
            if (index == 0) slice = leading + slice
            if (index == pieces.lastIndex) slice += trailing
            push(slice)
        }
    }
    return chunks.map { JumpChunk(it, trimSpaces(it), it.codePointCount(0, it.length)) }
}

/** 一行最多几块（原版 resolveChunkCap）：不超过 [maxChunks]，也不多于字数（至少 2）。 */
internal fun jumpChunkCap(charCount: Int, maxChunks: Int = JUMP_MAX_CHUNKS_PER_ROW): Int {
    val cap = max(2, maxChunks)
    return if (charCount <= 0) cap else min(cap, max(2, charCount))
}

/**
 * 切块并控制块数（原版 layoutLine）：块数超过上限就一档档放宽每块字数，直到落进上限或放宽到 [JUMP_MAX_CHUNK_CHARS]；
 * 放宽一次块数没变少就停（剩下的是劈不开的拉丁词，长英文句宁可多几根柱子）。
 */
internal fun jumpChunks(segments: List<String>, chunkChars: Int, charCount: Int): List<JumpChunk> {
    val cap = jumpChunkCap(charCount)
    var perChunk = max(1, chunkChars)
    var chunks = jumpSplitChunks(segments, perChunk)
    while (chunks.size > cap && perChunk < JUMP_MAX_CHUNK_CHARS) {
        perChunk += 1
        val wider = jumpSplitChunks(segments, perChunk)
        if (wider.size >= chunks.size) break
        chunks = wider
    }
    return chunks
}

/**
 * 一句的分词：逐字 / 逐词时间拼起来正好是这句（去掉首尾空白也算）时直接用它，字和时刻一一对得上；
 * 否则交给 [segment]（播放器里是 ICU 分词，等价于原版宿主的 segmentWords / Intl.Segmenter）。
 */
internal fun jumpSegments(text: String, words: List<LyricWord>, segment: (String) -> List<String>): List<String> {
    if (words.isNotEmpty()) {
        val joined = words.joinToString("") { it.text }
        if (joined == text) return words.map { it.text }
        if (joined.trim() == text) {
            val list = words.map { it.text }.toMutableList()
            list[0] = list[0].trimStart()
            list[list.lastIndex] = list[list.lastIndex].trimEnd()
            if (list.joinToString("") == text) return list.filter { it.isNotEmpty() }
        }
    }
    val pieces = segment(text).filter { it.isNotEmpty() }
    return if (pieces.joinToString("") == text) pieces else listOf(text)
}

// ---- 每字时刻（jumpSplit.buildCharTimings） ----

private const val MIN_HOP = 0.02

/**
 * 这一句每个码位的起止（秒），按 [text] 的码位排：返回长 2n 的数组（起、止交替）。
 *
 * 有逐字时间时每个词的时长均分给它的字；只有一个跨整句的词（等于没有逐字时间）或没有词时，整句均分。
 * 句末不比句首晚就按字数估一个（每字 0.12 秒，至少 0.5 秒）。词拼起来和这句对不上时按非空白字对齐，空白取前一个字的收尾；
 * 连非空白字都对不上才整句均分。
 */
internal fun jumpCharTimings(text: String, startS: Double, endS: Double, words: List<LyricWord>): DoubleArray {
    val count = text.codePointCount(0, text.length)
    val end = if (endS > startS) endS else startS + max(0.5, count * 0.12)
    val out = DoubleArray(count * 2)
    if (words.isNotEmpty() && fillFromWords(text, startS, end, words, out)) return out
    val step = max(MIN_HOP, (end - startS) / max(1, count))
    for (i in 0 until count) {
        out[i * 2] = startS + step * i
        out[i * 2 + 1] = startS + step * (i + 1)
    }
    return out
}

private fun fillFromWords(text: String, start: Double, end: Double, words: List<LyricWord>, out: DoubleArray): Boolean {
    val chars = ArrayList<Int>()
    val starts = ArrayList<Double>()
    val ends = ArrayList<Double>()
    for (word in words) {
        val wordStart = word.startMs / 1000.0
        val wordEnd = if (word.endMs / 1000.0 > wordStart) word.endMs / 1000.0 else end
        val n = word.text.codePointCount(0, word.text.length)
        if (n == 0) continue
        val step = max(MIN_HOP, (wordEnd - wordStart) / n)
        var i = 0
        var k = 0
        while (i < word.text.length) {
            val cp = word.text.codePointAt(i)
            chars += cp
            starts += wordStart + step * k
            ends += wordStart + step * (k + 1)
            k++
            i += Character.charCount(cp)
        }
    }
    // 一个词、却不止一个字 = 没有真正的逐字时间
    if (chars.isEmpty() || (words.size == 1 && chars.size > 1)) return false
    val count = out.size / 2
    if (chars.size == count && words.joinToString("") { it.text } == text) {
        for (i in 0 until count) {
            out[i * 2] = starts[i]
            out[i * 2 + 1] = ends[i]
        }
        return true
    }
    // 按非空白字对齐
    var w = 0
    var i = 0
    var c = 0
    var last = start
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (jumpIsSpace(cp)) {
            out[c * 2] = last
            out[c * 2 + 1] = last
        } else {
            while (w < chars.size && jumpIsSpace(chars[w])) w++
            if (w >= chars.size || chars[w] != cp) return false
            out[c * 2] = starts[w]
            out[c * 2 + 1] = ends[w]
            last = ends[w]
            w++
        }
        c++
        i += Character.charCount(cp)
    }
    return true
}

// ---- 时间窗（client.buildWriteWindows / jumpTimeline.resolveMotionWindow） ----

/** 一句的时刻（秒）：[enter] 是第一个词开口（没有词时 NaN），[voicedEnd] 是最后一个词唱完（没有真逐字时间时 NaN）。 */
internal class JumpLineTimes(val start: Double, val end: Double, val enter: Double, val voicedEnd: Double)

/**
 * 一行的时间窗：[writeStart] 行首字开口；[writeEnd] 这一行最后一跳必须落地的时刻（下一行开口前留一小截）；
 * [afterStart]..[afterEnd] 是收尾窗（唱完到下一行开口）。
 */
internal class JumpWindow(val writeStart: Double, val writeEnd: Double, val afterStart: Double, val afterEnd: Double)

private const val MIN_IDLE_SECONDS = 0.16

/** 从一句歌词取时刻（原版 resolveEnterTime / resolveVoicedEnd）。 */
internal fun jumpLineTimes(line: StageLine): JumpLineTimes {
    val start = line.startMs / 1000.0
    val end = line.endMs / 1000.0
    val words = line.words
    val enter = if (words.isNotEmpty()) words[0].startMs / 1000.0 else Double.NaN
    val voiced = when {
        words.isEmpty() -> Double.NaN
        words.size == 1 && abs(words[0].endMs - line.endMs) < 1 -> Double.NaN
        else -> words.last().endMs / 1000.0
    }
    return JumpLineTimes(start, end, enter, voiced)
}

/** 收尾窗（原版 resolveMotionWindow，holdShare 0.85）：返回 [afterStart, afterEnd]，排不出来时返回 null。 */
internal fun jumpMotionWindow(start: Double, end: Double, nextStart: Double, voicedEnd: Double, motionEnd: Double): DoubleArray? {
    val voiced = if (voicedEnd.isFinite()) voicedEnd else end
    val target = if (nextStart.isFinite()) nextStart else end
    if (!(target > start)) return null
    val span = target - start
    val motionRoom = if (motionEnd.isFinite()) max(0.0, motionEnd - start) else span
    val fallback = start + max(0.0, min(span, min(motionRoom, span * 0.85)))
    val estimate = !voiced.isFinite() || (motionEnd.isFinite() && voiced > motionEnd + 1e-6)
    val sung = if (estimate) fallback else min(max(voiced, start), target)
    val afterStart = min(sung, target - MIN_IDLE_SECONDS)
    return doubleArrayOf(max(start, afterStart), target)
}

/** 每一行的时间窗（原版 buildWriteWindows）：只对立得起柱子的那些行算，空行不占位。 */
internal fun jumpWindows(lines: List<JumpLineTimes>): List<JumpWindow> = lines.mapIndexed { index, line ->
    val writeStart = if (line.enter.isFinite()) line.enter else line.start
    val nextLine = lines.getOrNull(index + 1)
    val nextEnter = nextLine?.let { if (it.enter.isFinite()) it.enter else it.start } ?: Double.NaN
    val renderEnd = line.end
    val reserve = if (nextEnter.isFinite()) min(0.28, max(0.12, (nextEnter - writeStart) * 0.15)) else 0.12
    val writeEnd = max(writeStart, if (nextEnter.isFinite()) min(renderEnd, nextEnter - reserve) else renderEnd)
    val afterEnd = if (nextEnter.isFinite()) nextEnter else max(writeEnd, renderEnd)
    val motion = jumpMotionWindow(line.start, writeEnd, afterEnd, line.voicedEnd, renderEnd)
    JumpWindow(writeStart, writeEnd, motion?.get(0) ?: writeEnd, motion?.get(1) ?: writeEnd)
}

// ---- 跨行那一跳、歇脚柱（jumpTimeline） ----

/** 跨行那一跳的滞空：比行内的长一档（路更长），夹在 0.14..1.5 秒。 */
internal fun jumpShiftFly(flySeconds: Double): Double = (flySeconds * 1.5).coerceIn(0.14, 1.5)

/**
 * 行末那根歇脚柱（原版 resolveRestPillar）：唱完到下一行开口隔得比 [restGap]（和两个滞空）还久时，
 * 他唱完跳上去歇着、下一行开口前再起跳。返回 [落上去, 起跳]，不立时返回 null。
 */
internal fun jumpRestPillar(release: Double, nextStart: Double, flySeconds: Double, restGap: Double = JUMP_REST_GAP): DoubleArray? {
    if (!release.isFinite() || !nextStart.isFinite()) return null
    val target = max(release, nextStart)
    if (!(target > release)) return null
    val fly = jumpShiftFly(flySeconds)
    if (!(target - release > max(max(0.0, restGap), fly * 2))) return null
    return doubleArrayOf(release + fly, target - fly)
}

/** 他从上一行落到这一行首块上的那一刻（原版 resolveShiftLanding）：立了歇脚柱时是开口那一刻，否则唱完 + 一个滞空、不晚于开口。 */
internal fun jumpShiftLanding(release: Double, nextStart: Double, flySeconds: Double, restGap: Double = JUMP_REST_GAP): Double {
    if (!release.isFinite() || !nextStart.isFinite() || !(nextStart > release)) return Double.NaN
    val target = max(release, nextStart)
    if (jumpRestPillar(release, nextStart, flySeconds, restGap) != null) return target
    return min(release + jumpShiftFly(flySeconds), target)
}

// ---- 一行的时间表（jumpTimeline.buildLineTimeline） ----

/** 一跳：从第 [from] 块到第 [to] 块，[liftTime] 起跳、[landTime] 落地；[start] 是上一块落地的时刻。 */
internal class JumpHopTime(val from: Int, val to: Int, val start: Double, val liftTime: Double, val landTime: Double)

/** 一段时间区间（秒）。 */
internal class JumpSpan(val begin: Double, val end: Double)

/**
 * 一行的时间表。[landings] 第 i 块落地（= 这块开唱）的时刻；[pressTimes] / [releaseTimes] 柱子被踩下去、弹回来的时刻；
 * [cameraTimes] 镜头的站点（每块停留的中点，末尾可能多一站：下一行开口）；[gapShift] 跨行那一跳；
 * [shiftHold] 落到下一行首块之后等开口的那一截；[restIndex] 行末歇脚柱是第几块（没有 −1）。
 */
internal class JumpTimeline(
    val start: Double,
    val end: Double,
    val landings: DoubleArray,
    val hops: List<JumpHopTime>,
    val pressTimes: DoubleArray,
    val releaseTimes: DoubleArray,
    val cameraTimes: DoubleArray,
    val gap: Double,
    val lastRelease: Double,
    val tailSpan: JumpSpan,
    val cameraTail: JumpSpan?,
    val restIndex: Int,
    val gapShift: JumpSpan?,
    val shiftLand: Double,
    val shiftHold: JumpSpan?,
) {
    val count: Int get() = landings.size
}

/**
 * 照原版逐条：每块落在它自己开唱的那一刻（同一行里至少隔开 minGap，不超过 [endTime]），起跳 = 落地往回推一个滞空
 * （滞空最多占这一跳窗口的 95%、至少 15%）；跳上歇脚柱的那一跳从唱完那一刻起跳。最后一块在唱完那一刻弹回来，
 * 之后到下一行开口之间是跨行那一跳与镜头的末段。
 */
internal fun jumpTimeline(
    startTime: Double,
    endTime: Double,
    count: Int,
    flySeconds: Double,
    landTimes: DoubleArray,
    lastRelease: Double,
    rest: DoubleArray?,
    entryLand: Double,
    nextStart: Double,
    handover: Double = HANDOVER_SHARE,
): JumpTimeline {
    val fly = flySeconds.coerceIn(0.05, 3.0)
    val shiftFly = jumpShiftFly(flySeconds)
    val start = if (startTime.isFinite()) startTime else 0.0
    val end = if (endTime.isFinite() && endTime > start) endTime else start + 2
    val total = max(0, count)
    val span = end - start
    val steps = span / max(1, total)
    val minGap = (steps * 0.35).coerceIn(0.06, 0.3)
    val restIndex = if (rest != null && rest[0].isFinite() && total > 1) total - 1 else -1
    val restLift = if (restIndex >= 0) rest!![1] else Double.NaN

    val landings = DoubleArray(total)
    var previous = start
    for (i in 0 until total) {
        val due = landTimes.getOrNull(i)?.takeIf { it.isFinite() } ?: (start + steps * i)
        val landing = when (i) {
            0 -> min(start, due)
            restIndex -> due
            else -> max(due, min(previous + minGap, end))
        }
        landings[i] = max(previous, landing)
        previous = landings[i]
    }
    val release = if (lastRelease.isFinite()) lastRelease else end
    val lastLanding = if (total > 0) landings[total - 1] else start
    val tailEnd = if (nextStart.isFinite()) max(release, nextStart) else release
    val tailRelease = tailEnd > lastLanding + 1e-6
    val shiftLanding = when {
        restIndex >= 0 -> tailEnd
        tailRelease -> min(release + shiftFly, tailEnd)
        else -> lastLanding
    }
    val hops = ArrayList<JumpHopTime>(max(0, total - 1))
    for (i in 0 until total - 1) {
        val hopStart = landings[i]
        val hopEnd = landings[i + 1]
        val share = (fly / max(0.05, hopEnd - hopStart)).coerceIn(0.15, 0.95)
        val air = (hopEnd - hopStart) * share
        val toRest = i + 1 == restIndex
        hops += JumpHopTime(i, i + 1, hopStart, if (toRest) release else hopEnd - air, hopEnd)
    }
    val leave = DoubleArray(total) { i ->
        if (i == restIndex && restLift.isFinite()) restLift else if (i < hops.size) hops[i].liftTime else release
    }
    val camera = ArrayList<Double>(total + 1)
    for (i in 0 until total) {
        val landing = landings[i]
        camera += when (i) {
            0 -> landing
            restIndex -> landing + min(max(0.0, leave[i] - landing) / 2, CAMERA_REST_SETTLE)
            else -> landing + max(0.0, leave[i] - landing) / 2
        }
    }
    val cameraStop = camera.lastOrNull() ?: start
    if (tailRelease && restIndex < 0) camera += tailEnd
    for (i in 1 until camera.size) {
        if (camera[i] <= camera[i - 1]) camera[i] = camera[i - 1] + 1e-3
    }
    val tailBegin = if (restIndex >= 0 && restLift.isFinite()) max(cameraStop + 1e-3, restLift - shiftFly) else cameraStop
    val cameraTail = if (tailRelease && nextStart.isFinite() && shiftLanding > tailBegin) JumpSpan(tailBegin, shiftLanding) else null
    val press = DoubleArray(total) { i ->
        if (i == 0) (if (entryLand.isFinite()) min(entryLand, start) else start) else hops[i - 1].landTime
    }
    val gap = if (total > 1 && landings[total - 1] > landings[0]) (landings[total - 1] - landings[0]) / (total - 1) else steps
    return JumpTimeline(
        start = start,
        end = end,
        landings = landings,
        hops = hops,
        pressTimes = press,
        releaseTimes = leave,
        cameraTimes = camera.toDoubleArray(),
        gap = gap,
        lastRelease = release,
        tailSpan = JumpSpan(lastLanding, if (tailRelease) tailEnd else lastLanding),
        cameraTail = cameraTail,
        restIndex = if (restLift.isFinite()) restIndex else -1,
        gapShift = when {
            !tailRelease -> null
            restIndex >= 0 && restLift.isFinite() -> JumpSpan(restLift, tailEnd)
            else -> JumpSpan(release, shiftLanding)
        },
        shiftLand = if (tailRelease) shiftLanding else Double.NaN,
        shiftHold = if (tailRelease && restIndex < 0) JumpSpan(shiftLanding, tailEnd) else null,
    )
}

/** 歇脚柱那一站镜头最多停多久就往下一行走（秒）。 */
private const val CAMERA_REST_SETTLE = 0.35

// ---- 一整条路（client.layoutLine / buildBoard + jumpBoard.accumulateRowRoad） ----

/**
 * 一根柱子。[sx], [sy] 是柱底中心、[tx], [ty] 是柱顶中心的屏幕位置（格，相对整条路的原点）；
 * [box] 是方柱的三个面和贴面字（歇脚柱是 null，用 [cylinder]）。
 */
internal class JumpUnit(
    val text: String,
    val shape: JumpShape,
    val rest: Boolean,
    val sx: Float,
    val sy: Float,
    val tx: Float,
    val ty: Float,
    val box: JumpBoxGeometry?,
    val cylinder: JumpCylinderGeometry?,
)

/**
 * 路上的一行（一句歌词）。偶数行沿世界 +Y 往右上走（[direction] = 1），奇数行沿 +X 往左上走（−1）；
 * [cornerX], [cornerY] 是这一行路的尽头（拐弯处）柱顶高度的屏幕位置。[revealStart] / [revealBudget] 是这一行柱子升起的时刻与时长预算（前两行一开始就在）。
 */
internal class JumpRow(
    val lineIndex: Int,
    val units: List<JumpUnit>,
    val window: JumpWindow,
    val timeline: JumpTimeline,
    val alongX: Boolean,
    val direction: Int,
    val cornerX: Float,
    val cornerY: Float,
    val revealStart: Double,
    val revealBudget: Double,
    val hasWords: Boolean,
)

/** 整首歌的路：[rowOfLine] 把歌词行号映射到路上的行（空行是 −1）；[frameInput] 给取景用，整首一份，换行不呼吸。 */
internal class JumpCourse(
    val rows: List<JumpRow>,
    val rowOfLine: IntArray,
    val projection: JumpProjection,
    val frameInput: JumpFrameInput,
    val config: JumpConfig,
    val maxUnits: Int,
) {
    val windows: List<JumpWindow> = rows.map { it.window }
}

private class JumpDraftUnit(val text: String, val span: Float, val start: Double, val rest: Boolean)

private class JumpDraftRow(val lineIndex: Int, val units: List<JumpDraftUnit>, val times: JumpLineTimes, val hasWords: Boolean)

/**
 * 整首歌排成一条路。[measure] 量一块字有多宽（以字号为单位，播放器里用真字体量），[segment] 是分词。
 * 只有立得起柱子的句子才进路：原版的空行会让小人凭空消失、左右拐弯也会错一格，移植时直接跳过。
 */
internal fun jumpBuildCourse(
    lines: List<StageLine>,
    config: JumpConfig,
    measure: (String) -> Float,
    segment: (String) -> List<String>,
): JumpCourse {
    val face = config.face
    val height = jumpPillarHeight(face, JUMP_PILLAR_HEIGHT)
    val fly = config.flySeconds.toDouble()
    val drafts = ArrayList<JumpDraftRow>()
    for (line in lines) {
        val text = line.text
        if (text.isBlank()) continue
        val segments = jumpSegments(text, line.words, segment)
        val chunks = jumpChunks(segments, config.chunkChars, text.codePointCount(0, text.length))
        val timings = jumpCharTimings(text, line.startMs / 1000.0, line.endMs / 1000.0, line.words)
        val units = ArrayList<JumpDraftUnit>()
        var cursor = 0
        for (chunk in chunks) {
            val from = cursor
            cursor += chunk.charCount
            if (chunk.display.isEmpty()) continue
            // 块的开唱时刻：块里第一个非空白字
            var first = from
            var i = 0
            while (i < chunk.text.length) {
                val cp = chunk.text.codePointAt(i)
                if (!jumpIsSpace(cp)) break
                first++
                i += Character.charCount(cp)
            }
            val at = min(first, timings.size / 2 - 1).coerceAtLeast(0)
            val start = if (timings.isNotEmpty()) timings[at * 2] else line.startMs / 1000.0
            units += JumpDraftUnit(chunk.display, max(0.05f, measure(chunk.display)), start, false)
        }
        if (units.isEmpty()) continue
        val hasWords = line.words.size > 1
        drafts += JumpDraftRow(line.index, units, jumpLineTimes(line), hasWords)
    }
    val windows = jumpWindows(drafts.map { it.times })
    val projection = JumpProjection(config.pitch)

    // 时间表与歇脚柱
    val timelines = ArrayList<JumpTimeline>()
    val unitLists = ArrayList<List<JumpDraftUnit>>()
    drafts.forEachIndexed { index, draft ->
        val window = windows[index]
        val nextStart = windows.getOrNull(index + 1)?.writeStart ?: Double.NaN
        val prevAfter = windows.getOrNull(index - 1)?.afterStart ?: Double.NaN
        val entryLand = jumpShiftLanding(prevAfter, window.writeStart, fly)
        val release = window.afterStart
        val rest = jumpRestPillar(release, nextStart, fly)
        val units = if (rest != null) draft.units + JumpDraftUnit("", JUMP_REST_DIAMETER, rest[0], true) else draft.units
        unitLists += units
        timelines += jumpTimeline(
            startTime = window.writeStart,
            endTime = window.writeEnd,
            count = units.size,
            flySeconds = fly,
            landTimes = DoubleArray(units.size) { units[it].start },
            lastRelease = release,
            rest = rest,
            entryLand = entryLand,
            nextStart = nextStart,
        )
    }

    // 路：沿行的方向一块块排开，下一行从这一行的尽头拐弯
    val gap = JUMP_SPACING
    var x = 0f
    var y = 0f
    var lead = gap / 2f
    var widestDepth = jumpBlockShape(face, JUMP_MIN_UNIT_CELLS, height).depth
    var widestCross = JUMP_FACE_SPAN_CELLS
    var maxUnits = 1
    val rows = ArrayList<JumpRow>()
    val rowOfLine = IntArray(lines.size) { -1 }
    drafts.forEachIndexed { index, draft ->
        val alongX = index % 2 == 1
        val units = unitLists[index]
        val shapes = units.map { unit ->
            if (unit.rest) JumpShape(JUMP_REST_DIAMETER, JUMP_REST_DIAMETER, height) else jumpBlockShape(face, unit.span, height)
        }
        var cursor = lead
        val placed = ArrayList<JumpUnit>(units.size)
        units.forEachIndexed { k, unit ->
            val shape = shapes[k]
            val middle = cursor + shape.len / 2f
            cursor += shape.len + gap
            val wx = if (alongX) x + middle else x
            val wy = if (alongX) y else y + middle
            val sx = projection.x(wx, wy)
            val sy = projection.y(wx, wy, 0f)
            val ty = projection.y(wx, wy, shape.height)
            placed += if (unit.rest) {
                JumpUnit("", shape, true, sx, sy, sx, ty, null, jumpCylinder(projection, shape.len, shape.height))
            } else {
                JumpUnit(unit.text, shape, false, sx, sy, sx, ty, jumpBox(projection, alongX, shape, face, jumpTextAspect(unit.span)), null)
            }
            widestDepth = max(widestDepth, shape.depth)
            widestCross = max(widestCross, shape.depth)
        }
        val length = cursor - gap + gap / 2f
        val cornerWx = if (alongX) x + length else x
        val cornerWy = if (alongX) y else y + length
        val revealStart = if (index <= 1) Double.NaN else windows[index - 2].afterEnd
        val revealBudget = if (index <= 1) 0.0 else max(0.0, windows[index - 1].writeEnd - revealStart) * REVEAL_BUDGET_SHARE
        rows += JumpRow(
            lineIndex = draft.lineIndex,
            units = placed,
            window = windows[index],
            timeline = timelines[index],
            alongX = alongX,
            direction = if (alongX) -1 else 1,
            cornerX = projection.x(cornerWx, cornerWy),
            cornerY = projection.y(cornerWx, cornerWy, height),
            revealStart = revealStart,
            revealBudget = revealBudget,
            hasWords = draft.hasWords,
        )
        if (draft.lineIndex in rowOfLine.indices) rowOfLine[draft.lineIndex] = index
        maxUnits = max(maxUnits, placed.size)
        if (alongX) x += length else y += length
        lead = shapes.maxOf { it.depth } / 2f + gap
    }
    val frameInput = JumpFrameInput(face, height, widestDepth, widestCross, JUMP_SPACING, JUMP_RUNNER_SCALE, config.arc)
    return JumpCourse(rows, rowOfLine, projection, frameInput, config, maxUnits)
}

/** 后面那一行柱子升起来最多用掉“上一行还能写多久”的这么多（原版 REVEAL_BUDGET_SHARE）。 */
private const val REVEAL_BUDGET_SHARE = 0.7

// ---- 分词与小工具 ----

private fun trimSpaces(text: String): String {
    var a = 0
    var b = text.length
    while (a < b) {
        val cp = text.codePointAt(a)
        if (!jumpIsSpace(cp)) break
        a += Character.charCount(cp)
    }
    while (b > a) {
        val cp = text.codePointBefore(b)
        if (!jumpIsSpace(cp)) break
        b -= Character.charCount(cp)
    }
    return text.substring(a, b)
}

/** 只有空白、标点、符号（原版 /^[\s\p{P}\p{S}]+$/u）。 */
private fun punctuationOnly(text: String): Boolean {
    if (text.isEmpty()) return false
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (!jumpIsSpace(cp) && !isPunctuationOrSymbol(cp)) return false
        i += Character.charCount(cp)
    }
    return true
}

private fun isPunctuationOrSymbol(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
    Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
    -> true
    else -> false
}

private fun allWide(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (!jumpIsWide(cp)) return false
        i += Character.charCount(cp)
    }
    return true
}

private fun charWidth(cp: Int): Float = if (jumpIsWide(cp)) 1f else 0.55f

private fun widthOf(text: String): Float {
    var w = 0f
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        w += charWidth(cp)
        i += Character.charCount(cp)
    }
    return w
}

private fun isLatinWord(cp: Int): Boolean = cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code

/** 在连字符、撇号之后切开（接缝归前一块）。 */
private fun cutLatinJoints(text: String): List<String> {
    val pieces = ArrayList<String>()
    val current = StringBuilder()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        current.appendCodePoint(cp)
        if (jumpIsLatinJoint(cp)) {
            pieces += current.toString()
            current.setLength(0)
        }
        i += Character.charCount(cp)
    }
    if (current.isNotEmpty()) pieces += current.toString()
    return pieces
}

/** 按字宽切：一块到 [maxChars] 个字宽就切，拉丁词内部不切（连着的字母不计宽）。 */
private fun cutByWidth(text: String, maxChars: Int): List<String> {
    val limit = max(1, maxChars)
    val pieces = ArrayList<String>()
    val current = StringBuilder()
    var run = 0
    fun flush() {
        if (current.isNotEmpty()) pieces += current.toString()
        current.setLength(0)
        run = 0
    }
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        if (jumpIsLatinJoint(cp) && current.isNotEmpty()) {
            current.appendCodePoint(cp)
            flush()
            continue
        }
        val width = if (jumpIsSpace(cp)) 0f else charWidth(cp)
        val nextRun = if (isLatinWord(cp)) run + 1 else 0
        val cutting = current.isNotEmpty() && widthOf(current.toString()) + width - nextRun * charWidth(cp) > limit
        if (cutting) flush()
        current.appendCodePoint(cp)
        run = nextRun
    }
    flush()
    return pieces
}

/** 超宽的一块再切（原版 splitText）：空白之后、接缝之后、再按字宽。 */
private fun splitText(text: String, maxChars: Int): List<String> {
    val words = ArrayList<String>()
    val current = StringBuilder()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        current.appendCodePoint(cp)
        if (jumpIsSpace(cp)) {
            words += current.toString()
            current.setLength(0)
        }
        i += Character.charCount(cp)
    }
    if (current.isNotEmpty()) words += current.toString()
    return words.flatMap { cutLatinJoints(it) }.flatMap { cutByWidth(it, maxChars) }
}
