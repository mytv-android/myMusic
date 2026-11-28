/*
 * AMLL (Apple Music-like Lyrics), https://github.com/amll-dev/applemusic-like-lyrics
 * Copyright (C) the AMLL contributors (amll-dev)
 * Modified for myMusic on 2026-11-28 (Kotlin / Jetpack Compose rewrite); the changes are listed below.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 2026-11-28 myMusic 改写：取自 AMLL core 0.7.2（@4d8e0270）的 utils/lyric-split-words.ts（chunkAndSplitLyricWords）、
// utils/is-cjk.ts、lyric-player/base/line.ts（shouldEmphasize）和 lyric-player/base/timeline.ts 的间奏计算（≥ 7 秒）。
// 改动：AMLL 的歌词自带背景人声（isBG）和对唱（isDuet）标记，myMusic 的歌词源都没有，这里按文字认：
// 句末的括号段「…（…）」拆成和声，整句都在括号里的行并到上一句当和声（括号去掉）；中文歌词常见的「男：」「女：」「合：」前缀
// 管到下一个前缀为止，「女」靠右（对唱），前缀不显示。只在这个模式里认，别的模式看到的歌词不变。
// 没有逐字时间的句子按字数均分句长（和其它模式一致），拉丁字母连成一个词；这种句子不做长音强调。注音（ruby）、逐字音译不搬。
package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.runtime.Immutable
import com.mytv.mymusic.data.LyricSubline
import com.mytv.mymusic.ui.player.stage.StageLine
import com.mytv.mymusic.ui.player.stage.charTimings
import com.mytv.mymusic.ui.player.stage.splitCodePoints
import kotlin.math.max
import kotlin.math.min

/** 一个词（或一个字、一段空白）。时间是毫秒，拆字时按字数均分，可能带小数（和原版一样）。 */
@Immutable
internal class AmllWord(val text: String, val startMs: Double, val endMs: Double) {
    val space: Boolean get() = text.isBlank()
    val durationMs: Double get() = endMs - startMs

    override fun toString(): String = "AmllWord($text, $startMs, $endMs)"
}

/**
 * 排版单位（chunkAndSplitLyricWords 的一项）：一段空白、一个中日韩字，或几个中间没有空白的拉丁音节拼成的词块。
 * 中间可以换行的只有单位之间；[emphasize] 是长音强调（整块按合起来的时长算），[lastWord] 是整句最后一个词（强调再加强）。
 */
@Immutable
internal class AmllUnit(val words: List<AmllWord>, val emphasize: Boolean, val lastWord: Boolean) {
    val space: Boolean get() = words.size == 1 && words[0].space
    val text: String = words.joinToString("") { it.text }
    val startMs: Double = words.minOf { it.startMs }
    val endMs: Double = words.maxOf { it.endMs }
}

/** 一行字（主句或和声）：排版单位、下面那一行（翻译 / 罗马音），[timed] 是有真实的逐字时间。 */
@Immutable
internal class AmllLineText(val units: List<AmllUnit>, val subtitle: String, val timed: Boolean) {
    /** 非空白的词，按顺序：逐词扫光的遮罩按它们的宽度首尾相接算。 */
    val words: List<AmllWord> = units.filter { !it.space }.flatMap { it.words }
}

/**
 * 一个句组：主句、可能的和声行、是不是对唱（靠右）。[line] 是主句在舞台句表里的下标（点句跳转、长按选句用）；
 * [startMs]、[endMs] 已经和和声行对齐（原版的 syncMainAndBackgroundLines）。
 */
@Immutable
internal class AmllGroup(
    val line: Int,
    val startMs: Double,
    val endMs: Double,
    val main: AmllLineText,
    val bg: AmllLineText?,
    val duet: Boolean,
)

/** 间奏：[startMs] 到 [endMs] 之间没有句子在唱，三点挂在第 [anchor] 组下面（−1 是第一句之前）。 */
@Immutable
internal data class AmllInterlude(val startMs: Double, val endMs: Double, val anchor: Int)

/** 一首歌拆好的句组；[duet] 是整首有对唱（靠左的句子右边、靠右的句子左边各留 15%）。 */
@Immutable
internal class AmllSong(val groups: List<AmllGroup>, val duet: Boolean, val interludes: List<AmllInterlude>)

/**
 * 舞台句表 → 句组。[voices] 是“对唱与和声”开关：关掉时不认前缀和括号，一句一组原样显示。
 */
internal fun amllSong(lines: List<StageLine>, subline: LyricSubline, voices: Boolean): AmllSong {
    val builders = ArrayList<GroupBuilder>(lines.size)
    var voice: Char? = null
    for (line in lines) {
        val timed = line.words.isNotEmpty()
        var pieces = amllPieces(line)
        if (voices) {
            val text = pieces.joinToString("") { it.text }
            amllVoicePrefix(text)?.let { (who, length) ->
                if (text.substring(length).isNotBlank()) {
                    voice = who
                    pieces = amllSlice(pieces, length, text.length)
                }
            }
        }
        val subtitle = line.subtitle(subline)
        val text = pieces.joinToString("") { it.text }
        val split = if (voices) amllBackgroundSplit(text) else null
        val previous = builders.lastOrNull()
        when {
            split != null && split.whole && previous != null && previous.bg == null -> {
                previous.bg = amllLineText(amllSlice(pieces, split.bgFrom, split.bgTo), subtitle, timed)
                previous.start = min(previous.start, line.startMs.toDouble())
                previous.end = max(previous.end, line.endMs.toDouble())
            }
            split != null && !split.whole -> builders += GroupBuilder(
                line = line.index,
                start = line.startMs.toDouble(),
                end = line.endMs.toDouble(),
                main = amllLineText(amllSlice(pieces, 0, split.mainTo), subtitle, timed),
                bg = amllLineText(amllSlice(pieces, split.bgFrom, split.bgTo), "", timed),
                duet = voice == '女',
            )
            else -> {
                val shown = if (split != null) amllSlice(pieces, split.bgFrom, split.bgTo) else pieces
                builders += GroupBuilder(line.index, line.startMs.toDouble(), line.endMs.toDouble(), amllLineText(shown, subtitle, timed), null, voice == '女')
            }
        }
    }
    val groups = builders.map { AmllGroup(it.line, it.start, it.end, it.main, it.bg, it.duet) }.sortedBy { it.startMs }
    return AmllSong(
        groups = groups,
        duet = voices && groups.any { it.duet },
        interludes = amllInterludes(DoubleArray(groups.size) { groups[it].startMs }, DoubleArray(groups.size) { groups[it].endMs }),
    )
}

private class GroupBuilder(
    val line: Int,
    var start: Double,
    var end: Double,
    val main: AmllLineText,
    var bg: AmllLineText?,
    val duet: Boolean,
)

/**
 * 一句的词：有逐字时间就用逐字时间；没有就按 [charTimings] 把句长均分给每个字，空白单独一段、中日韩一字一段、
 * 其它连续的字拼成一个词（拉丁单词整体唱）。
 */
internal fun amllPieces(line: StageLine): List<AmllWord> {
    if (line.words.isNotEmpty()) return line.words.map { AmllWord(it.text, it.startMs.toDouble(), it.endMs.toDouble()) }
    val chars = line.charTimings()
    val out = ArrayList<AmllWord>(chars.size)
    var runStart = -1
    fun flush(until: Int) {
        if (runStart < 0) return
        out += AmllWord(chars.subList(runStart, until).joinToString("") { it.text }, chars[runStart].startMs.toDouble(), chars[until - 1].endMs.toDouble())
        runStart = -1
    }
    chars.forEachIndexed { i, c ->
        when {
            c.space -> {
                flush(i)
                out += AmllWord(c.text, c.startMs.toDouble(), c.startMs.toDouble())
            }
            amllIsCjk(c.text) -> {
                flush(i)
                out += AmllWord(c.text, c.startMs.toDouble(), c.endMs.toDouble())
            }
            runStart < 0 -> runStart = i
        }
    }
    flush(chars.size)
    return out
}

/** 句首的「男：」「女：」「合：」（全角半角冒号都认，前后空白一起去掉）：(谁, 前缀长度)。 */
internal fun amllVoicePrefix(text: String): Pair<Char, Int>? {
    val match = VOICE_PREFIX.find(text) ?: return null
    return match.groupValues[1][0] to match.range.last + 1
}

/**
 * 和声的切法：[whole] 是整句都在括号里（并到上一句），否则是句末一段括号（[mainTo] 之前是主句）。
 * [bgFrom]..[bgTo] 是括号里的字（不含括号）。下标都是拼起来的整句文字里的位置。
 */
internal class AmllBackgroundSplit(val whole: Boolean, val mainTo: Int, val bgFrom: Int, val bgTo: Int)

/** 认句末的括号段：最后一个非空白字是右括号，和它配对的左括号前面还有字就是句末和声；左括号就是第一个字时整句是和声。 */
internal fun amllBackgroundSplit(text: String): AmllBackgroundSplit? {
    var end = text.length
    while (end > 0 && text[end - 1].isWhitespace()) end--
    if (end == 0 || !isClose(text[end - 1])) return null
    var depth = 0
    var open = -1
    for (i in end - 1 downTo 0) {
        val c = text[i]
        if (isClose(c)) depth++
        if (isOpen(c)) {
            depth--
            if (depth == 0) {
                open = i
                break
            }
        }
    }
    if (open < 0) return null
    if (text.substring(open + 1, end - 1).isBlank()) return null
    val before = text.substring(0, open)
    if (before.isBlank()) return AmllBackgroundSplit(whole = true, mainTo = 0, bgFrom = open + 1, bgTo = end - 1)
    var mainTo = open
    while (mainTo > 0 && text[mainTo - 1].isWhitespace()) mainTo--
    return AmllBackgroundSplit(whole = false, mainTo = mainTo, bgFrom = open + 1, bgTo = end - 1)
}

private fun isOpen(c: Char) = c == '(' || c == '（'

private fun isClose(c: Char) = c == ')' || c == '）'

/** 拼起来的文字里 [from, to) 这一段对应的词；跨在边界上的词按字数比例切开时间。 */
internal fun amllSlice(pieces: List<AmllWord>, from: Int, to: Int): List<AmllWord> {
    val out = ArrayList<AmllWord>()
    var offset = 0
    for (piece in pieces) {
        val start = offset
        val end = offset + piece.text.length
        offset = end
        val a = max(start, from)
        val b = min(end, to)
        if (a >= b) continue
        if (a == start && b == end) {
            out += piece
            continue
        }
        val length = piece.text.length.toDouble()
        val span = piece.endMs - piece.startMs
        out += AmllWord(
            piece.text.substring(a - start, b - start),
            piece.startMs + span * (a - start) / length,
            piece.startMs + span * (b - start) / length,
        )
    }
    return out
}

/** 一行字：拆成排版单位，按逐字时间判长音强调（[timed] 为 false 时不强调）。 */
internal fun amllLineText(words: List<AmllWord>, subtitle: String, timed: Boolean): AmllLineText {
    val last = words.lastOrNull { !it.space }?.text?.trim().orEmpty()
    val units = amllChunkWords(words).map { chunk ->
        val unit = AmllUnit(chunk, emphasize = false, lastWord = false)
        if (unit.space) return@map unit
        val emphasize = timed && if (amllIsCjk(unit.text)) {
            chunk.any { amllShouldEmphasize(it.text, it.durationMs) }
        } else {
            amllShouldEmphasize(unit.text, unit.endMs - unit.startMs)
        }
        AmllUnit(chunk, emphasize, lastWord = last.isNotEmpty() && unit.text.contains(last))
    }
    return AmllLineText(units, subtitle, timed)
}

/**
 * 重新分组（chunkAndSplitLyricWords）：每个词先按空白拆开（时长按去掉空白后的字数均分），多字的中日韩段再拆成单字；
 * 然后中间没有空白、又不是中日韩的相邻段拼成一块。空白和中日韩字各自成一项。
 */
internal fun amllChunkWords(words: List<AmllWord>): List<List<AmllWord>> {
    val result = ArrayList<List<AmllWord>>()
    var group = ArrayList<AmllWord>()
    fun flush() {
        if (group.isNotEmpty()) {
            result += group
            group = ArrayList()
        }
    }
    fun atom(word: AmllWord) {
        if (!word.space && !amllIsCjk(word.text)) {
            group += word
        } else {
            flush()
            result += listOf(word)
        }
    }
    for (w in words) {
        if (w.text.isBlank()) {
            atom(w)
            continue
        }
        val parts = splitKeepingWhitespace(w.text)
        val total = w.text.count { !it.isWhitespace() }.takeIf { it > 0 } ?: 1
        val perUnit = (w.endMs - w.startMs) / total
        var offset = 0
        for (part in parts) {
            if (part.isBlank()) {
                val start = w.startMs + offset * perUnit
                atom(AmllWord(part, start, start))
                continue
            }
            if (amllIsCjk(part) && part.length > 1) {
                // 原版按 UTF-16 单元拆，会把增补平面的汉字拆成两半；这里按码位拆，时长仍按单元数算
                for (char in splitCodePoints(part)) {
                    val start = w.startMs + offset * perUnit
                    atom(AmllWord(char, start, start + char.length * perUnit))
                    offset += char.length
                }
            } else {
                val start = w.startMs + offset * perUnit
                atom(AmllWord(part, start, start + part.length * perUnit))
                offset += part.length
            }
        }
    }
    flush()
    return result
}

/** "a b  c" → ["a", " ", "b", "  ", "c"]（JS 的 split(/(\s+)/) 再去掉空串）。 */
private fun splitKeepingWhitespace(text: String): List<String> {
    val out = ArrayList<String>()
    var last = 0
    for (match in WHITESPACE.findAll(text)) {
        if (match.range.first > last) out += text.substring(last, match.range.first)
        out += match.value
        last = match.range.last + 1
    }
    if (last < text.length) out += text.substring(last)
    return out
}

/**
 * 长音强调（LyricLineBase.shouldEmphasize）：唱满 1 秒；中日韩按字，其它（拉丁单词）还要 2–7 个字母。
 */
internal fun amllShouldEmphasize(text: String, durationMs: Double): Boolean {
    if (amllIsCjk(text)) return durationMs >= 1000.0
    val length = text.trim().length
    return durationMs >= 1000.0 && length <= 7 && length > 1
}

/**
 * 整段都是中日韩字（utils/is-cjk.ts 的 `^[\p{Unified_Ideograph}ࠀ-鿼]+$`）：U+0800–U+9FFC 或统一汉字。
 * 全角标点（U+FF0C 等）和谚文音节不在里面，和原版一致。
 */
internal fun amllIsCjk(text: String): Boolean {
    if (text.isEmpty()) return false
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val ok = cp in 0x0800..0x9FFC || (Character.isIdeographic(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
        if (!ok) return false
        i += Character.charCount(cp)
    }
    return true
}

/**
 * 间奏（TimelineController.calculateInterludes）：从第一句之前开始，到目前为止最晚的句末到下一句开始隔了 7 秒以上就是一段，
 * 挂在前一组下面（第一句之前是 −1）。
 */
internal fun amllInterludes(starts: DoubleArray, ends: DoubleArray): List<AmllInterlude> {
    val out = ArrayList<AmllInterlude>()
    var maxEnd = 0.0
    for (i in -1 until starts.size - 1) {
        if (i >= 0) maxEnd = max(maxEnd, ends[i])
        val gapEnd = max(maxEnd, starts[i + 1])
        if (gapEnd - maxEnd >= AMLL_MIN_INTERLUDE_MS) out += AmllInterlude(maxEnd, gapEnd, i)
    }
    return out
}

internal const val AMLL_MIN_INTERLUDE_MS = 7_000.0
private val VOICE_PREFIX = Regex("^\\s*([男女合])\\s*[:：]\\s*")
// JS 的 \s 含全角空格、不换行空格和 BOM；Android 的 ICU 正则不认 (?U)（UNICODE_CHARACTER_CLASS），一编译就抛异常，所以写成显式字符类
private val WHITESPACE = Regex("[\\s\\p{Z}\\uFEFF]+")
