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
)

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val raw: String,
    val synced: Boolean,
)

object LyricsParser {
    private val lineTimeRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTimeRegex = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
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
        return ParsedLyrics(sorted, raw, true)
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
                val start = parseTime(matches.first(), offset) - 400
                words += LyricWord(start.coerceAtLeast(0), start + 400, prefix)
            }
        }
        for ((index, match) in matches.withIndex()) {
            val start = parseTime(match, offset)
            val next = matches.getOrNull(index + 1)
            val end = if (next != null) parseTime(next, offset) else start + 5_000
            val textStart = match.range.last + 1
            val textEnd = next?.range?.first ?: content.length
            val text = content.substring(textStart, textEnd)
            if (text.isNotEmpty()) words += LyricWord(start, end, text)
        }
        return words
    }

    private fun parseTime(match: MatchResult, offset: Long): Long {
        val minutes = match.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
        val seconds = match.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
        val fraction = match.groupValues.getOrNull(3).orEmpty()
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
