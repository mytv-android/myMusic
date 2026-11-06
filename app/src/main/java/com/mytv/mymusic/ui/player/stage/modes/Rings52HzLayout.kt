package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.ui.geometry.Rect
import com.mytv.mymusic.ui.player.stage.StageRandom
import com.mytv.mymusic.ui.player.stage.splitCodePoints
import com.mytv.mymusic.ui.player.stage.stableHash
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 52Hz 的纯几何（照 visualizer52hz 1.0.1 的 lineLayout.mjs、pithShape.mjs）：整句排版和每个字的种子错位、
 * 年轮源的形状（每个字一个圆角矩形，或者髓心多边形）、矩形合并到上限、兜底画法用的髓心外扩轮廓。
 * 不碰 android.*，单元测试直接跑。
 */

/** 一个字：字心位置（px）、旋转（弧度）、缩放、步进宽度；[word] 是拉丁词的序号，宽字（CJK）为 −1。 */
internal class RingsGlyph(
    val char: String,
    val charIndex: Int,
    val x: Float,
    val y: Float,
    val rotation: Float,
    val scale: Float,
    val width: Float,
    val wide: Boolean,
    val row: Int,
    val word: Int,
)

/** 一句的排版：字表、最终字号（px）、行数。 */
internal class RingsLineLayout(val glyphs: List<RingsGlyph>, val size: Float, val rowCount: Int)

/** 年轮源的一个轴对齐矩形（中心、半宽、半高，px），[row] 是所在行。 */
internal class RingRect(val cx: Float, val cy: Float, val hw: Float, val hh: Float, val row: Int)

private class RingsEntry(val char: String, val index: Int, val width: Float)

/** 原模组的 WIDE_CHAR：韩文字母、CJK、兼容汉字、竖排标点、全角。增补平面的字不算（和 JS 正则一样）。 */
internal fun isWideChar(char: String): Boolean {
    if (char.isEmpty()) return false
    val cp = char.codePointAt(0)
    return cp in 0x1100..0x11FF || cp in 0x2E80..0x9FFF || cp in 0xAC00..0xD7AF ||
        cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFFEF
}

private fun isSpace(char: String) = char.isBlank()

/** 断词：宽字自成一段，其余按词断开，空格跟在前一个词后面。 */
private fun tokenize(entries: List<RingsEntry>): List<List<RingsEntry>> {
    val tokens = ArrayList<List<RingsEntry>>()
    var current = ArrayList<RingsEntry>()
    fun flush() {
        if (current.isNotEmpty()) tokens += current
        current = ArrayList()
    }
    for (entry in entries) {
        if (isWideChar(entry.char)) {
            flush()
            tokens += listOf(entry)
        } else {
            current += entry
            if (isSpace(entry.char)) flush()
        }
    }
    flush()
    return tokens
}

private fun trimmedEnd(row: List<RingsEntry>): Int {
    var end = row.size
    while (end > 0 && isSpace(row[end - 1].char)) end--
    return end
}

private fun rowWidth(row: List<RingsEntry>): Float {
    var sum = 0f
    for (i in 0 until trimmedEnd(row)) sum += row[i].width
    return sum
}

private fun wrap(tokens: List<List<RingsEntry>>, maxWidth: Float): List<List<RingsEntry>> {
    val rows = ArrayList<List<RingsEntry>>()
    var row: List<RingsEntry> = emptyList()
    for (token in tokens) {
        val next = row + token
        if (row.isNotEmpty() && rowWidth(next) > maxWidth) {
            rows += row
            row = token.filterIndexed { i, entry -> !(i == 0 && isSpace(entry.char)) }
        } else {
            row = next
        }
    }
    if (row.isNotEmpty()) rows += row
    return rows
}

/**
 * 整句排版（照 layoutLine）：行宽 [maxWidth]，最多 3 行，放不下字号乘 0.88 重排（最多 6 次）；各行居中，行距 1.34 倍字号，
 * 整块以 ([centerX], [centerY]) 居中。种子取歌词文本的 FNV-1a 哈希，同一句每次一样：
 * 宽字逐个错位、旋转、缩放，相邻的宽字多留 0.06 倍字号；拉丁字母按整词错位，字母只带两成的抖动，保证词还能读。
 * [advanceAtBase] 是每个字在 [baseSize] 字号下的步进宽度，换字号时按比例缩放（不重新测量）。
 */
internal fun layoutRingsLine(
    text: String,
    advanceAtBase: (String) -> Float,
    baseSize: Float,
    centerX: Float,
    centerY: Float,
    maxWidth: Float,
): RingsLineLayout {
    val chars = splitCodePoints(text)
    val baseWidths = FloatArray(chars.size) { advanceAtBase(chars[it]) }
    var size = baseSize
    var rows: List<List<RingsEntry>> = emptyList()
    for (attempt in 0 until FIT_ATTEMPTS) {
        val k = size / baseSize
        rows = wrap(tokenize(chars.mapIndexed { i, c -> RingsEntry(c, i, baseWidths[i] * k) }), maxWidth)
        if (rows.size <= MAX_ROWS) break
        size *= FIT_SHRINK
    }

    val random = StageRandom(stableHash(text))
    fun between(min: Float, max: Float) = random.range(min, max)
    val step = size * LINE_HEIGHT
    val top = centerY - step * (rows.size - 1) / 2f
    val glyphs = ArrayList<RingsGlyph>()
    var wordId = -1
    rows.forEachIndexed { rowIndex, row ->
        val list = row.subList(0, trimmedEnd(row))
        val wide = BooleanArray(list.size) { isWideChar(list[it].char) }
        val advance = FloatArray(list.size) { i ->
            val gap = if (i + 1 < list.size) size * (if (wide[i] || wide[i + 1]) 0.06f else 0.02f) else 0f
            list[i].width + gap
        }
        var cursor = centerX - advance.sum() / 2f + between(-0.04f, 0.04f) * size
        val rowY = top + step * rowIndex
        var word: FloatArray? = null
        list.forEachIndexed { i, entry ->
            val space = isSpace(entry.char)
            if (space || wide[i]) word = null
            if (!space) {
                if (!wide[i] && word == null) {
                    word = floatArrayOf(between(-0.1f, 0.1f), between(-0.05f, 0.05f), between(0.94f, 1.06f))
                    wordId++
                }
                val jitter = if (wide[i]) 1f else 0.2f
                val w = word
                val x = cursor + entry.width / 2f + between(-0.035f, 0.035f) * size * jitter
                val y = rowY + ((w?.get(0) ?: 0f) + between(-0.11f, 0.11f) * jitter) * size
                val rotation = (w?.get(1) ?: 0f) + between(-0.075f, 0.075f) * jitter
                val scale = (w?.get(2) ?: 1f) * between(1f - 0.07f * jitter, 1f + 0.07f * jitter)
                glyphs += RingsGlyph(entry.char, entry.index, x, y, rotation, scale, entry.width, wide[i], rowIndex, if (wide[i]) -1 else wordId)
            }
            cursor += advance[i]
        }
    }
    return RingsLineLayout(glyphs, size, rows.size)
}

/**
 * 年轮源的矩形：宽字一字一个，拉丁字母按词合成一个（原模组按字形掩膜算距离场，这里用字身框近似）。
 * 字身取步进宽度的 92%、字号的 84%（拉丁 76%），乘字自己的缩放；旋转不计（最多 7°，外扩 0.55 倍字号后看不出）。
 */
internal fun ringRects(layout: RingsLineLayout): List<RingRect> {
    val out = ArrayList<RingRect>()
    var i = 0
    val glyphs = layout.glyphs
    while (i < glyphs.size) {
        val g = glyphs[i]
        if (g.wide || g.word < 0) {
            out += RingRect(g.x, g.y, g.width * 0.46f * g.scale, layout.size * WIDE_HALF_HEIGHT * g.scale, g.row)
            i++
            continue
        }
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var topY = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var j = i
        while (j < glyphs.size && glyphs[j].word == g.word && !glyphs[j].wide) {
            val h = glyphs[j]
            left = min(left, h.x - h.width * 0.46f * h.scale)
            right = max(right, h.x + h.width * 0.46f * h.scale)
            topY = min(topY, h.y - layout.size * LATIN_HALF_HEIGHT * h.scale)
            bottom = max(bottom, h.y + layout.size * LATIN_HALF_HEIGHT * h.scale)
            j++
        }
        out += RingRect((left + right) / 2f, (topY + bottom) / 2f, (right - left) / 2f, (bottom - topY) / 2f, g.row)
        i = j
    }
    return out
}

/**
 * 把矩形合并到不超过 [cap] 个：每行内相邻的 n 个并成一个外接矩形，n 从 1 往上加，最坏每行一个。不跨行合并。
 */
internal fun mergeRingRects(rects: List<RingRect>, cap: Int): List<RingRect> {
    if (rects.size <= cap) return rects
    val rows = rects.groupBy { it.row }.toSortedMap().values.toList()
    val longest = rows.maxOf { it.size }
    var group = 2
    while (group < longest && rows.sumOf { (it.size + group - 1) / group } > cap) group++
    val out = ArrayList<RingRect>()
    for (row in rows) row.chunked(group).forEach { out += boundsOf(it) }
    return out
}

/** 一组矩形的外接矩形。 */
internal fun boundsOf(rects: List<RingRect>): RingRect {
    var left = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (r in rects) {
        left = min(left, r.cx - r.hw)
        right = max(right, r.cx + r.hw)
        top = min(top, r.cy - r.hh)
        bottom = max(bottom, r.cy + r.hh)
    }
    return RingRect((left + right) / 2f, (top + bottom) / 2f, (right - left) / 2f, (bottom - top) / 2f, rects.firstOrNull()?.row ?: 0)
}

/**
 * 髓心轮廓（照 pithOutline）：[area] 中心附近 ±14% 处、短边 3.5%–7% 半径的圆，叠 2、3、4、5、7 次随机谐波再拉伸、旋转。
 * 返回 [points] 个点的 x、y 交错数组（px）；中心见 [pithCenter]。
 */
internal fun pithOutline(seed: Long, area: Rect, points: Int = PITH_POINTS): FloatArray {
    val random = StageRandom(seed)
    fun between(min: Float, max: Float) = random.range(min, max)
    val minSide = min(area.width, area.height)
    val cx = area.center.x + between(-0.14f, 0.14f) * area.width
    val cy = area.center.y + between(-0.14f, 0.14f) * area.height
    val radius = minSide * between(0.035f, 0.07f)
    val stretch = between(0.75f, 1.35f)
    val tilt = between(0f, PI.toFloat())
    val ks = intArrayOf(2, 3, 4, 5, 7)
    val amplitude = FloatArray(ks.size)
    val phase = FloatArray(ks.size)
    ks.forEachIndexed { i, k ->
        amplitude[i] = between(0f, 0.32f) / sqrt(k.toFloat())
        phase[i] = between(0f, (PI * 2).toFloat())
    }
    val out = FloatArray(points * 2)
    val ct = cos(tilt)
    val st = sin(tilt)
    for (i in 0 until points) {
        val theta = i.toFloat() / points * (PI * 2).toFloat()
        var r = 1f
        ks.forEachIndexed { h, k -> r += amplitude[h] * sin(k * theta + phase[h]) }
        r = max(0.35f, r) * radius
        val x = cos(theta) * r * stretch
        val y = sin(theta) * r
        out[i * 2] = cx + x * ct - y * st
        out[i * 2 + 1] = cy + x * st + y * ct
    }
    return out
}

/** 髓心的中心（轮廓围着它是星形的）：和 [pithOutline] 用同一串随机数的前两个。 */
internal fun pithCenter(seed: Long, area: Rect): FloatArray {
    val random = StageRandom(seed)
    return floatArrayOf(
        area.center.x + random.range(-0.14f, 0.14f) * area.width,
        area.center.y + random.range(-0.14f, 0.14f) * area.height,
    )
}

/** 第 [period] 个 11 秒段的髓心种子（照原版：歌曲种子异或 (period+1)×黄金比例常数，取 32 位）。 */
internal fun pithSeed(songSeed: Long, period: Long): Long = (songSeed xor ((period + 1) * 0x9E3779B1L)) and 0xFFFFFFFFL

/** 每隔 [every] 个点取一个，给着色器用的粗多边形。 */
internal fun decimate(points: FloatArray, every: Int): FloatArray {
    val count = points.size / 2
    val kept = (count + every - 1) / every
    return FloatArray(kept * 2) { i -> points[(i / 2) * every * 2 + i % 2] }
}

/**
 * 兜底画法用：髓心轮廓往外扩 [distance] 后的外轮廓，按 [directions] 个方向从中心 ([cx], [cy]) 取半径，写进 [out]（x、y 交错）。
 * 外扩形状 = 轮廓上每个点画半径 [distance] 的圆的并集，沿射线取最远的交点；再和射线与原轮廓的交点取大，保证不缩进去。
 */
internal fun offsetOutline(points: FloatArray, cx: Float, cy: Float, distance: Float, directions: Int, out: FloatArray) {
    val count = points.size / 2
    val r2 = distance * distance
    for (d in 0 until directions) {
        val theta = d.toFloat() / directions * (PI * 2).toFloat()
        val ux = cos(theta)
        val uy = sin(theta)
        var reach = 0f
        for (j in 0 until count) {
            val px = points[j * 2] - cx
            val py = points[j * 2 + 1] - cy
            val along = px * ux + py * uy
            val across = px * uy - py * ux
            val rest = r2 - across * across
            if (rest >= 0f) reach = max(reach, along + sqrt(rest))
            // 射线和这一条边的交点
            val k = (j + 1) % count
            val qx = points[k * 2] - cx
            val qy = points[k * 2 + 1] - cy
            val ex = qx - px
            val ey = qy - py
            val denom = ux * ey - uy * ex
            if (abs(denom) > 1e-6f) {
                val t = (px * ey - py * ex) / denom
                val s = (px * uy - py * ux) / denom
                if (t > 0f && s in 0f..1f) reach = max(reach, t)
            }
        }
        out[d * 2] = cx + ux * reach
        out[d * 2 + 1] = cy + uy * reach
    }
}

private const val MAX_ROWS = 3
private const val FIT_ATTEMPTS = 6
private const val FIT_SHRINK = 0.88f
internal const val LINE_HEIGHT = 1.34f
private const val WIDE_HALF_HEIGHT = 0.42f
private const val LATIN_HALF_HEIGHT = 0.38f
internal const val PITH_POINTS = 120
