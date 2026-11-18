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
// 2026-11-18 myMusic 改写：乱排卡每个词的随机参数（大小写、字重 300–900、字号 40–70px、字宽 50–110、角度 −10°～9°、
// 一半反色，长于 10 个字符的词字宽 100）取自 Rush（shub39/Rush@5a952bf）的 MessyCard；「随机一套」取自 ShareVM.randomize。
// 改动：随机数改成按句子和会话种子确定（缩略图、预览、导出三处一样），中文按词切（ICU BreakIterator）而不是按空格；
// 随机一套只抽样式、配色（不含自定义）、圆角、形状。其余选句、省略行、文字、文件名、布局都是 myMusic 自己的。
package com.mytv.mymusic.ui.player.share

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.data.SharePrefs
import com.mytv.mymusic.data.ShareCardCorners
import com.mytv.mymusic.data.ShareCardPalette
import com.mytv.mymusic.data.ShareCardShape
import com.mytv.mymusic.data.ShareCardStyle
import com.mytv.mymusic.data.ShareTextFormat
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.local.LOCAL_SOURCE
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.player.stage.StageRandom
import com.mytv.mymusic.ui.player.stage.modes.CineramaSegmenter
import com.mytv.mymusic.ui.player.stage.modes.CineramaWordSegmenter
import com.mytv.mymusic.ui.player.stage.modes.cineramaWords
import com.mytv.mymusic.ui.player.stage.stableHash
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

// ---------- 选句 ----------

/**
 * 选句列表里的一句。[order] 是去掉空行后的连续编号（选中集合存它），[source] 是全屏歌词那份行表里的下标
 * （同步歌词就是 ParsedLyrics.lines 的下标），[sub] 是歌词下面那一行（翻译 / 罗马音 / 两行，按设置）。
 * 未同步的纯文本歌词 [timeMs] 是 −1。
 */
@Immutable
internal data class PickLine(val order: Int, val source: Int, val timeMs: Long, val text: String, val sub: String)

/** 一次最多选几句（稿子：最多 8 句）。 */
internal const val SHARE_MAX_LINES = 8

/** 全屏歌词的行表（已按「歌词下面那一行」换好副行）→ 选句列表：去掉正文为空白的行，[PickLine.order] 连续编号。 */
internal fun pickLines(lines: List<LyricLine>): List<PickLine> {
    val out = ArrayList<PickLine>(lines.size)
    lines.forEachIndexed { index, line ->
        if (line.text.isNotBlank()) out += PickLine(out.size, index, line.timeMs, line.text.trim(), line.translation.trim())
    }
    return out
}

/**
 * 进选句层时先选上哪句（返回 [PickLine.order]）：长按命中的那句 [hit]（全屏歌词行表下标），认不出是哪句时用当前句 [active]。
 * 落在空行（LRC 的间奏空行）上就往后找第一句非空的；后面都没有了取最后一句；[active] 为 −1（未同步）取第一句。
 */
internal fun initialPick(lines: List<PickLine>, hit: Int?, active: Int): Int {
    if (lines.isEmpty()) return 0
    val target = hit ?: active
    if (target < 0) return lines.first().order
    return (lines.firstOrNull { it.source >= target } ?: lines.last()).order
}

internal enum class ToggleResult { Added, Removed, Full }

/** 点一句：选上 / 取消；已经选满 [SHARE_MAX_LINES] 句再加返回 [ToggleResult.Full]、集合不变。 */
internal fun toggleLine(selected: Set<Int>, order: Int): Pair<Set<Int>, ToggleResult> = when {
    order in selected -> (selected - order) to ToggleResult.Removed
    selected.size >= SHARE_MAX_LINES -> selected to ToggleResult.Full
    else -> (selected + order) to ToggleResult.Added
}

// ---------- 上卡的行 ----------

/** 卡片上的一行：一句歌词（带副行），或两组不连续的句子中间的省略行。 */
internal sealed interface ShareRow {
    data class Line(val text: String, val sub: String) : ShareRow
    data object Gap : ShareRow
}

/** 选中的句子按原来的先后排；相邻两句在歌词里不挨着时中间插一个省略行。 */
internal fun shareRows(lines: List<PickLine>, selected: Set<Int>): List<ShareRow> {
    val byOrder = lines.associateBy { it.order }
    val out = ArrayList<ShareRow>()
    var previous: Int? = null
    for (order in selected.sorted()) {
        val line = byOrder[order] ?: continue
        if (previous != null && order - previous > 1) out += ShareRow.Gap
        out += ShareRow.Line(line.text, line.sub)
        previous = order
    }
    return out
}

/** [style] 实际上卡的行：对句取前两句、引言 / 乱排 / 粗糙取第一句（都不带省略行），封面不放歌词，其余原样。 */
internal fun rowsForStyle(style: ShareCardStyle, rows: List<ShareRow>): List<ShareRow> =
    style.lines?.let { n -> rows.filterIsInstance<ShareRow.Line>().take(n) } ?: rows

/** 选的句子用不完时样式条下面的一句提示；用得完时 null。 */
internal fun unusedLinesNote(style: ShareCardStyle, selected: Int): String? = when {
    style == ShareCardStyle.AlbumArt -> "「封面」样式不放歌词"
    style.lines == 1 && selected >= 2 -> "「${style.label}」只用第一句，其余选中的句子不上卡。"
    style.lines == 2 && selected >= 3 -> "「${style.label}」用前两句，其余选中的句子不上卡。"
    else -> null
}

// ---------- 文字、文件名、链接 ----------

/**
 * 「复制文字」和分享时附带的文字。歌词用选中的全部句子（不按样式截），每句一行；[withSub] 时副行跟在下一行（多行照样），
 * 省略行写「…」。[ShareTextFormat.WithSong] 再空一行写「歌名 · 歌手」，[ShareTextFormat.WithLink] 再下一行写链接（没有就不写）。
 */
internal fun shareText(
    rows: List<ShareRow>,
    withSub: Boolean,
    format: ShareTextFormat,
    title: String,
    artist: String,
    link: String,
): String {
    val lyric = rows.flatMap { row ->
        when (row) {
            ShareRow.Gap -> listOf("…")
            is ShareRow.Line -> listOf(row.text) + if (withSub && row.sub.isNotBlank()) row.sub.lines().filter { it.isNotBlank() } else emptyList()
        }
    }
    val tail = when (format) {
        ShareTextFormat.Lyrics -> emptyList()
        ShareTextFormat.WithSong -> listOf("", songLine(title, artist))
        ShareTextFormat.WithLink -> listOf("", songLine(title, artist)) + listOfNotNull(link.takeIf { it.isNotBlank() })
    }
    return (lyric + tail).joinToString("\n").trimEnd()
}

private fun songLine(title: String, artist: String): String = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" · ")

/** 导出的 PNG 文件名：「歌名 - 歌手 yyyyMMdd-HHmmss.png」，去掉文件名里不能用的字符，歌名歌手部分最长 80 个字符。 */
internal fun shareFileName(title: String, artist: String, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val name = listOf(title, artist).map(::cleanFileName).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "歌词" }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = zone }.format(Date(now))
    return "${name.take(SHARE_FILE_NAME_MAX).trim()} $stamp.png"
}

private fun cleanFileName(text: String): String =
    text.filterNot { it in "\\/:*?\"<>|" || it.isISOControl() }.replace(Regex("\\s+"), " ").trim()

private const val SHARE_FILE_NAME_MAX = 80

/**
 * 平台上这首歌的页面链接：本地歌没有；歌曲自己带 http 链接就用它，聚合歌再从 [track] 的各平台版本里找第一个。
 * 不用 deepLink：那个只在这首放不出来时才有值。
 */
internal fun shareLink(song: Song, track: CanonicalTrack?): String = when {
    song.source == LOCAL_SOURCE -> ""
    song.link.startsWith("http") -> song.link
    else -> track?.sources?.firstOrNull { it.song.link.startsWith("http") }?.song?.link.orEmpty()
}

// ---------- 随机一套、粗糙卡的默认配色 ----------

/** 顶栏 🎲：样式、配色（不抽自定义）、圆角、形状一起随机；全屏、副行、文字格式、自定义色不动。 */
internal fun randomLook(prefs: SharePrefs, random: Random): SharePrefs = prefs.copy(
    style = ShareCardStyle.entries.random(random),
    palette = listOf(ShareCardPalette.Vibrant, ShareCardPalette.Muted, ShareCardPalette.Player).random(random),
    corners = ShareCardCorners.entries.random(random),
    shape = ShareCardShape.entries.random(random),
)

/**
 * 在样式条上换样式。换到粗糙且配色不是自定义：记下原配色（[memo]）、换成自定义（默认就是荧光绿底黑字）；
 * 从粗糙换走且还有那次的记忆：换回原配色。用户在粗糙卡上手动点了配色时调用方把记忆清掉，离开时就不再改回。
 * 返回新的选项和新的记忆。
 */
internal fun applyStyle(prefs: SharePrefs, next: ShareCardStyle, memo: ShareCardPalette?): Pair<SharePrefs, ShareCardPalette?> = when {
    next == prefs.style -> prefs to memo
    next == ShareCardStyle.Brat && prefs.palette != ShareCardPalette.Custom ->
        prefs.copy(style = next, palette = ShareCardPalette.Custom) to prefs.palette
    next == ShareCardStyle.Brat -> prefs.copy(style = next) to null
    prefs.style == ShareCardStyle.Brat && memo != null && prefs.palette == ShareCardPalette.Custom ->
        prefs.copy(style = next, palette = memo) to null
    else -> prefs.copy(style = next) to null
}

// ---------- 乱排 ----------

/** 乱排卡上的一个词：[weight] 字重、[size] 字号（px）、[width] 字宽轴、[angle] 角度、[highlight] 反色。 */
@Immutable
internal data class MessyWord(val text: String, val weight: Int, val size: Int, val width: Int, val angle: Int, val highlight: Boolean)

/**
 * 把一句拆成词，每个词的参数照 Rush 的 MessyCard 随机，但按（句子, [seed]）确定：同一句同一种子每次一样，种子 +1 重新打乱。
 * 中文按词切（[CineramaWordSegmenter] 在 Android 上是 ICU 词典切词），空白和标点并进前一个词。
 */
internal fun messyWords(text: String, seed: Long, segmenter: CineramaSegmenter = CineramaWordSegmenter): List<MessyWord> {
    val random = StageRandom(stableHash(text) xor (seed * MESSY_SEED_MIX))
    return cineramaWords(text, segmenter).map { it.trim() }.filter { it.isNotEmpty() }.map { word ->
        val upper = random.next() < 0.5f
        val weight = (3 + random.int(7)) * 100
        val size = (4 + random.int(4)) * 10
        val width = (5 + random.int(7)) * 10
        val angle = -10 + random.int(20)
        val highlight = random.next() < 0.5f
        MessyWord(
            text = if (upper) word.uppercase(Locale.ROOT) else word.lowercase(Locale.ROOT),
            weight = weight,
            size = size,
            width = if (word.length > 10) 100 else width,
            angle = angle,
            highlight = highlight,
        )
    }
}

private const val MESSY_SEED_MIX = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

// ---------- 布局 ----------

/** 选句层和分享页的摆法：手机竖屏、宽 ≥ 600dp 的两栏、桌面姿态（按横折痕上下分）。 */
internal enum class ShareLayout { Phone, TwoPane, Tabletop }

internal fun shareLayoutOf(widthDp: Int, tabletopSplit: Boolean): ShareLayout = when {
    tabletopSplit -> ShareLayout.Tabletop
    widthDp >= 600 -> ShareLayout.TwoPane
    else -> ShareLayout.Phone
}

/** 选句列表左右留白：手机 14dp；宽屏至少 96dp，一行不超过 720dp。 */
internal fun pickSidePadding(widthDp: Int): Dp = when {
    widthDp < 600 -> 14.dp
    else -> maxOf(96, (widthDp - 720) / 2).dp
}
