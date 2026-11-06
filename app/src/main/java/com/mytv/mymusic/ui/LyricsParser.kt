package com.mytv.mymusic.ui

import java.util.Locale

data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    /** 同一时间戳的最后一层文本（翻译；只有两层时也可能是罗马音）：QQ、酷我、网易云都按原文、罗马音、翻译的顺序写。 */
    val translation: String = "",
    /** 同一时间戳有三层时中间那层（罗马音），或者打开“自动转写”后补上的罗马音；没有就是空串。 */
    val romanization: String = "",
)

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val raw: String,
    val synced: Boolean,
)

object LyricsParser {
    private val lineTimeRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    // 逐字标签：<起>文字</止>；只有起标签的来源（旧增强 LRC）仍按下一个字或行尾推算结束时间。
    private val wordTimeRegex = Regex("""<(/?)(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val offsetRegex = Regex("""\[offset:([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ParsedLyrics {
        if (raw.isBlank()) return ParsedLyrics(emptyList(), raw, false)
        val offset = offsetRegex.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<LyricLine>()
        raw.lineSequence().forEach { sourceLine ->
            val timestamps = lineTimeRegex.findAll(sourceLine).toList()
            if (timestamps.isEmpty()) return@forEach
            val content = lineTimeRegex.replace(sourceLine, "").trim()
            if (content.isBlank()) return@forEach
            val words = parseWords(content, offset)
            val display = words.joinToString("") { it.text }.trim().ifBlank { wordTimeRegex.replace(content, "").trim() }
            for (timestamp in timestamps) {
                val time = parseTime(timestamp, offset)
                val shiftedWords = if (words.isEmpty()) {
                    emptyList()
                } else {
                    val delta = time - parseTime(timestamps.first(), offset)
                    words.map { it.copy(startMs = it.startMs + delta, endMs = it.endMs + delta) }
                }
                lines += LyricLine(time, display, shiftedWords)
            }
        }
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
        if (sorted.isEmpty()) return ParsedLyrics(emptyList(), raw, false)
        val merged = mergeLayers(sorted)
        return ParsedLyrics(merged.mapIndexed { index, line -> clampWordEnds(line, merged.getOrNull(index + 1)?.timeMs) }, raw, true)
    }

    fun activeLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val index = lines.indexOfLast { it.timeMs <= positionMs }
        return if (index < 0) 0 else index
    }

    private fun parseWords(content: String, offset: Long): List<LyricWord> {
        val matches = wordTimeRegex.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        val words = mutableListOf<LyricWord>()
        val firstTagStart = matches.first().range.first
        if (firstTagStart > 0) {
            val prefix = content.substring(0, firstTagStart).trim()
            if (prefix.isNotBlank()) {
                val start = parseWordTime(matches.first(), offset) - 400
                words += LyricWord(start.coerceAtLeast(0), start + 400, prefix)
            }
        }
        var wordStart = -1L
        var textStart = 0
        for (match in matches) {
            val time = parseWordTime(match, offset)
            val text = content.substring(textStart, match.range.first)
            if (wordStart >= 0L && text.isNotEmpty()) {
                words += LyricWord(wordStart, time.coerceAtLeast(wordStart + 1), text)
            }
            // </止> 结束当前字；<起> 开一个新字。没有止标签时由下一个起标签收尾。
            wordStart = if (match.groupValues[1] == "/") -1L else time
            textStart = match.range.last + 1
        }
        if (wordStart >= 0L) {
            val text = content.substring(textStart)
            if (text.isNotEmpty()) words += LyricWord(wordStart, wordStart + 5_000, text)
        }
        return words
    }

    /**
     * 同一时间戳的连续几行并成一句：第一行是原文，最后一行是翻译（只有两层时也可能是罗马音，分不出来，照旧当翻译）；
     * 三层及以上时第二行是罗马音（各平台都按原文、罗马音、翻译的顺序写）。和原文一样的层不算。
     */
    private fun mergeLayers(lines: List<LyricLine>): List<LyricLine> {
        val out = ArrayList<LyricLine>(lines.size)
        var i = 0
        while (i < lines.size) {
            val head = lines[i]
            var j = i + 1
            while (j < lines.size && lines[j].timeMs == head.timeMs) j++
            val original = head.text.trim()
            val extra = if (j - i > 1) lines[j - 1].text.trim() else ""
            val middle = if (j - i > 2) lines[i + 1].text.trim() else ""
            out += head.copy(
                translation = extra.takeUnless { it == original }.orEmpty(),
                romanization = middle.takeUnless { it == original || it == extra }.orEmpty(),
            )
            i = j
        }
        return out
    }

    /** 没有止标签的来源按 +5 秒兜底；这里再按下一行压一下，最多唱到下一句开始。 */
    private fun clampWordEnds(line: LyricLine, nextLineMs: Long?): LyricLine {
        if (nextLineMs == null || line.words.isEmpty()) return line
        var changed = false
        val words = line.words.map { word ->
            if (word.endMs > nextLineMs) {
                changed = true
                word.copy(endMs = nextLineMs.coerceAtLeast(word.startMs + 1))
            } else {
                word
            }
        }
        return if (changed) line.copy(words = words) else line
    }

    private fun parseTime(match: MatchResult, offset: Long): Long {
        val minutes = match.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
        val seconds = match.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
        val fraction = match.groupValues.getOrNull(3).orEmpty()
        return parseTime(minutes, seconds, fraction, offset)
    }

    /** 逐字标签的斜杠和起止标记在分组 1，时间在分组 2..4。 */
    private fun parseWordTime(match: MatchResult, offset: Long): Long {
        val minutes = match.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
        val seconds = match.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
        val fraction = match.groupValues.getOrNull(4).orEmpty()
        return parseTime(minutes, seconds, fraction, offset)
    }

    private fun parseTime(minutes: Long, seconds: Long, fraction: String, offset: Long): Long {
        val millis = when (fraction.length) {
            1 -> fraction.toLongOrNull()?.times(100) ?: 0L
            2 -> fraction.toLongOrNull()?.times(10) ?: 0L
            3 -> fraction.toLongOrNull() ?: 0L
            else -> 0L
        }
        return (minutes * 60_000 + seconds * 1_000 + millis + offset).coerceAtLeast(0)
    }

    fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
        return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
