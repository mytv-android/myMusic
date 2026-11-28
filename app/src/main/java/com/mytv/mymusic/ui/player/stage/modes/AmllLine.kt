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
// 2026-11-28 myMusic 改写：取自 AMLL core 0.7.2（@4d8e0270）的 lyric-player/dom/lyric-line.ts、lyric-group.ts、
// line-brightness.ts、animation/mask/animator-web.ts（逐词遮罩的时间轴）、animation/float、animation/emphasize
// 和 styles/lyric-player.module.css 里句子的尺寸。
// 照原样：逐词遮罩（唱过 100%、没唱 40%，所有词的宽度首尾相接成一条扫光轴，第一个词多走 1.5 个羽化宽、最后一个词多走 0.5 个）；
// 亮层（带遮罩）和暗层（整句 20%）用加亮混合叠，进当前句 0.3 秒、离开 0.45 秒 ease-out 交叉淡入；每个词唱到时上浮 0.05 字号
// （和声两倍），用时 max(1 秒, 词长) ease-out；长音强调的参数、逐字错峰、32 帧关键帧（线性插值）的放大、两侧推挤、上提和白色辉光。
// 改动：DOM 换成 Canvas：每个词单独量一次、自己按单位之间折行（单位里面只有放不下时才拆），羽化宽度按 0.5 字号（稿子定的）；
// 两层叠加在数学上等于一层透明度为“亮层 × 遮罩 + 暗层 × 0.2”的字，这里直接按这个透明度画一次；
// 离开当前句时上浮和强调在 0.25 秒里落回（原版倒放，最长也是 0.25 秒收完辉光）；辉光要 Android 12 起才画，更早只有放大和上浮。
// 不搬：注音、逐字音译、换行平衡（LineBalancer）、脏词遮罩。
package com.mytv.mymusic.ui.player.stage.modes

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 一行字的三种字体：主句、和声（0.7 倍）、下面那一行（0.5 倍）。字号单位是 sp，像素在测量时换。 */
@Immutable
internal class AmllStyles(val main: TextStyle, val bg: TextStyle, val sub: TextStyle, val bgSub: TextStyle) {
    /** 中日韩字的字体：和 Rush 一样，没有指定字体时退回系统默认字体（主题字体只有拉丁字，回退时粗体不生效）。 */
    fun cjk(style: TextStyle): TextStyle = style.copy(fontFamily = style.fontFamily ?: FontFamily.Default)
}

/** 每个词量一次、按文字缓存（中文字大量重复）。换字号、换字体时整份重建。 */
internal class AmllTextCache(private val measurer: TextMeasurer, val styles: AmllStyles) {
    private val main = HashMap<String, TextLayoutResult>()
    private val bg = HashMap<String, TextLayoutResult>()
    private var mainSpace = -1f
    private var bgSpace = -1f

    fun token(text: String, bgLine: Boolean): TextLayoutResult {
        val cache = if (bgLine) bg else main
        return cache.getOrPut(text) {
            val style = if (bgLine) styles.bg else styles.main
            measurer.measure(text, if (amllIsCjk(text)) styles.cjk(style) else style, softWrap = false, maxLines = 1)
        }
    }

    /** 空白的宽度：量 "x x" 减去两个 "x"，量不出来时按 0.25 字号。 */
    fun space(bgLine: Boolean, fontPx: Float): Float {
        val known = if (bgLine) bgSpace else mainSpace
        if (known >= 0f) return known
        val pair = token("x x", bgLine).size.width - 2f * token("x", bgLine).size.width
        val width = if (pair > 0f) pair else fontPx * 0.25f
        if (bgLine) bgSpace = width else mainSpace = width
        return width
    }

    fun sub(text: String, maxWidth: Int, bgLine: Boolean, end: Boolean): TextLayoutResult =
        measurer.measure(
            text,
            (if (bgLine) styles.bgSub else styles.sub).copy(textAlign = if (end) TextAlign.End else TextAlign.Start),
            constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(1)),
        )
}

/** 长音强调的参数（calculateEmphasizeParams）。[startMs] 是整块的绝对开始时间，[chars] 是整块的字数。 */
@Immutable
internal class AmllEmphasis(val amount: Double, val blur: Double, val durationMs: Double, val startMs: Double, val anchorChars: Int, val chars: Int) {
    /** 第 [index] 个字什么时候开始：每个字晚 时长 / 2.5 / 字数。 */
    fun charStart(index: Int): Double = startMs + durationMs / 2.5 / anchorChars * index
}

/**
 * 强调参数：时长至少 1 秒；强度 a = 时长 / 2000、辉光 b = 时长 / 3000（大于 1 开方、小于 1 立方），再乘 0.6 / 0.5；
 * 整句最后一个词 a × 1.6、b × 1.5、时长 × 1.2；上限 1.2 / 0.8。[delayMs] 是相对句首的开始时间（负数按 0）。
 */
internal fun amllEmphasis(durationMs: Double, delayMs: Double, lineStartMs: Double, chars: Int, lastWord: Boolean): AmllEmphasis {
    var du = max(1000.0, durationMs)
    var amount = du / 2000
    amount = if (amount > 1) sqrt(amount) else amount.pow(3)
    var blur = du / 3000
    blur = if (blur > 1) sqrt(blur) else blur.pow(3)
    amount *= 0.6
    blur *= 0.5
    if (lastWord) {
        amount *= 1.6
        blur *= 1.5
        du *= 1.2
    }
    amount = min(1.2, amount)
    blur = min(0.8, blur)
    return AmllEmphasis(amount, blur, du, lineStartMs + max(0.0, delayMs), max(1, chars), chars)
}

/** 强调曲线（empEasing）：前半段 bezIn 升到 1，后半段 1 − bezOut 落回 0。 */
internal fun amllEmphasisCurve(x: Double): Double =
    if (x < 0.5) {
        AmllEase.emphasisIn.transform(amllClamp01(x / 0.5).toFloat()).toDouble()
    } else {
        1.0 - AmllEase.emphasisOut.transform(amllClamp01((x - 0.5) / 0.5).toFloat())
    }

/** 强调字的上浮（generateFloatKeyframes，单位：字号）：sin 鼓一下再停在上面；和声两倍。返回往上的距离。 */
internal fun amllEmphasisFloat(x: Double, bg: Boolean): Double {
    val y = sin(x * PI) * if (bg) 2 else 1
    val up = if (bg) 0.1 else 0.05
    return y * 0.05 + x * up
}

/** 关键帧动画的取值：原版把曲线在 1/32、2/32 … 1 上取 32 帧，帧之间线性插值（第 0 帧就是静止样子，和曲线在 0 处一致）。 */
internal fun amllKeyframed(x: Double, curve: (Double) -> Double): Double {
    if (x <= 0) return curve(0.0)
    if (x >= 1) return curve(1.0)
    val scaled = x * AMLL_KEYFRAMES
    val k = floor(scaled).toInt()
    val a = curve(k.toDouble() / AMLL_KEYFRAMES)
    val b = curve((k + 1).toDouble() / AMLL_KEYFRAMES)
    return a + (b - a) * (scaled - k)
}

/**
 * 一行的扫光轴（WebMaskAnimator 的关键帧推导）：[offsets] 是时间（占整行时长的比例），[travel] 是扫光推进的像素。
 * 词之间的停顿原地不动，每个词在自己的时长里推进自己的宽度，第一个词多推 1.5 个羽化宽，最后一个词多推 0.5 个。
 */
@Immutable
internal class AmllMask(private val offsets: DoubleArray, private val travel: DoubleArray, private val startMs: Double, private val totalMs: Double) {
    /** 推满之后的位置（整行全亮）。 */
    val full: Double get() = travel.last()

    /** [ms] 时推到哪儿（px）。 */
    fun travelAt(ms: Double): Double {
        if (totalMs <= 0.0) return if (ms >= startMs) full else 0.0
        val t = min(totalMs, max(0.0, ms - startMs))
        val f = t / totalMs
        var k = 0
        while (k + 1 < offsets.size && offsets[k + 1] <= f) k++
        if (k + 1 >= offsets.size) return travel[k]
        val span = offsets[k + 1] - offsets[k]
        return if (span <= 0.0) travel[k + 1] else travel[k] + (travel[k + 1] - travel[k]) * (f - offsets[k]) / span
    }
}

/** 按每个词的宽度 [widths] 和起止时间推出扫光轴；[lineStartMs]..[lineEndMs] 是整行的时间窗，[fade] 是羽化宽度（px）。 */
internal fun amllMask(widths: DoubleArray, starts: DoubleArray, ends: DoubleArray, lineStartMs: Double, lineEndMs: Double, fade: Double): AmllMask {
    val total = lineEndMs - lineStartMs
    val offsets = ArrayList<Double>(widths.size * 2 + 1)
    val travel = ArrayList<Double>(widths.size * 2 + 1)
    var time = 0.0
    var pos = 0.0
    var lastStamp = 0.0
    fun push() {
        offsets += min(1.0, max(0.0, time))
        travel += pos
    }
    push()
    for (j in widths.indices) {
        val stamp = starts[j] - lineStartMs
        val pause = stamp - lastStamp
        if (pause > 0) {
            time += pause / total
            push()
        }
        lastStamp = stamp
        val duration = max(0.0, ends[j] - starts[j])
        var move = widths[j]
        if (j == 0) move += fade * 1.5
        if (j == widths.size - 1) move += fade * 0.5
        time += duration / total
        pos += move
        if (duration > 0) push()
        lastStamp += duration
    }
    // 最后一个词时长为 0 时它的宽度还没推出去：补一帧停在终点
    if (travel.last() != pos) {
        offsets += 1.0
        travel += pos
    }
    return AmllMask(offsets.toDoubleArray(), travel.toDoubleArray(), lineStartMs, total)
}

/** 一个词排好的位置（px，相对这一行的左上角）。强调的词逐字画，[chars] / [charX] 是每个字的排版和偏移。 */
internal class AmllToken(
    val word: AmllWord,
    val layout: TextLayoutResult,
    val chars: Array<TextLayoutResult>?,
    val charX: FloatArray?,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** 扫光轴上这个词前面所有词的宽度和。 */
    val sumPrev: Float,
    val emphasis: AmllEmphasis?,
    /** 这个词的第一个字在整块里是第几个字。 */
    val charOffset: Int,
)

/** 一行字（主句或和声）排好的样子。 */
internal class AmllLineGeom(
    val tokens: List<AmllToken>,
    val sub: TextLayoutResult?,
    val subOffset: Offset,
    /** 字（不含下面那一行）的高度。 */
    val textHeight: Float,
    /** 字加下面那一行的高度。 */
    val height: Float,
    /** 这一行盒子的宽度（含对唱留白），缩放原点在它的左边或右边。 */
    val boxWidth: Float,
    val fontPx: Float,
    val fadePx: Float,
    val mask: AmllMask,
    val bg: Boolean,
    val timed: Boolean,
)

/**
 * 排一行：按排版单位折行（单位之间可以换行，单位比一行还宽时才在里面按词拆，收尾标点不放行首），对唱的行靠右；
 * 有对唱的歌，靠左的句子右边、靠右的句子左边各留 15%。下面那一行宽度同字的可用宽度。
 */
internal fun amllLayoutLine(
    text: AmllLineText,
    cache: AmllTextCache,
    boxWidth: Float,
    fontPx: Float,
    bgLine: Boolean,
    duet: Boolean,
    songDuet: Boolean,
    lineStartMs: Double,
    lineEndMs: Double,
): AmllLineGeom {
    val pad = if (songDuet) boxWidth * AMLL_DUET_PADDING else 0f
    val avail = (boxWidth - pad).coerceAtLeast(1f)
    val left = if (duet) pad else 0f
    val space = cache.space(bgLine, fontPx)
    class Placed(val unit: AmllUnit, val word: AmllWord, val layout: TextLayoutResult, val chars: Array<TextLayoutResult>?, val charX: FloatArray?, val width: Float, val charOffset: Int)
    // 先量每个单位里的词
    val units = ArrayList<Pair<AmllUnit, List<Placed>>>()
    for (unit in text.units) {
        if (unit.space) {
            units += unit to emptyList()
            continue
        }
        var charOffset = 0
        val placed = unit.words.filter { !it.space }.map { word ->
            val shown = word.text.trim()
            val layout = cache.token(shown, bgLine)
            if (unit.emphasize) {
                val parts = com.mytv.mymusic.ui.player.stage.splitCodePoints(shown)
                val chars = Array(parts.size) { cache.token(parts[it], bgLine) }
                val xs = FloatArray(parts.size)
                var acc = 0f
                for (k in parts.indices) {
                    xs[k] = acc
                    acc += chars[k].size.width
                }
                Placed(unit, word, layout, chars, xs, acc, charOffset).also { charOffset += parts.size }
            } else {
                Placed(unit, word, layout, null, null, layout.size.width.toFloat(), 0)
            }
        }
        units += unit to placed
    }
    val lineHeight = (units.firstNotNullOfOrNull { (_, p) -> p.firstOrNull()?.layout?.size?.height } ?: cache.token("x", bgLine).size.height).toFloat()

    // 折行
    val rowOf = ArrayList<Int>()
    val xs = ArrayList<Float>()
    val rowWidths = ArrayList<Float>()
    val flat = ArrayList<Placed>()
    var row = 0
    var cursor = 0f
    var used = false
    var gap = false
    fun newRow() {
        rowWidths += cursor
        row++
        cursor = 0f
        used = false
    }
    for ((unit, placed) in units) {
        if (unit.space) {
            gap = true
            continue
        }
        val width = placed.sumOf { it.width.toDouble() }.toFloat()
        val lead = if (used && gap) space else 0f
        val sticky = placed.isNotEmpty() && isClosingPunctuation(unit.text.trim())
        if (used && !sticky && cursor + lead + width > avail) newRow()
        if (used && gap) cursor += space
        val split = width > avail
        for (p in placed) {
            if (split && used && cursor + p.width > avail) newRow()
            rowOf += row
            xs += cursor
            flat += p
            cursor += p.width
            used = true
        }
        gap = false
    }
    rowWidths += cursor
    val rows = row + 1
    val textHeight = if (flat.isEmpty()) lineHeight else rows * lineHeight

    // 扫光轴：所有词首尾相接
    val fade = fontPx * AMLL_FADE_WIDTH
    val widths = DoubleArray(flat.size) { flat[it].width.toDouble() }
    val mask = amllMask(
        widths,
        DoubleArray(flat.size) { flat[it].word.startMs },
        DoubleArray(flat.size) { flat[it].word.endMs },
        lineStartMs,
        max(lineEndMs, flat.maxOfOrNull { it.word.endMs } ?: lineEndMs),
        fade.toDouble(),
    )
    var sum = 0f
    val emphasisByUnit = HashMap<AmllUnit, AmllEmphasis>()
    val tokens = flat.mapIndexed { i, p ->
        val r = rowOf[i]
        val align = if (duet) avail - rowWidths[r] else 0f
        val emphasis = if (p.chars != null) {
            emphasisByUnit.getOrPut(p.unit) {
                val count = units.first { it.first === p.unit }.second.sumOf { it.chars?.size ?: 0 }
                amllEmphasis(p.unit.endMs - p.unit.startMs, p.unit.startMs - lineStartMs, lineStartMs, count, p.unit.lastWord)
            }
        } else {
            null
        }
        AmllToken(p.word, p.layout, p.chars, p.charX, left + align + xs[i], r * lineHeight, p.width, lineHeight, sum, emphasis, p.charOffset)
            .also { sum += p.width }
    }
    val sub = text.subtitle.takeIf { it.isNotBlank() }?.let { cache.sub(it, avail.toInt(), bgLine, duet) }
    val subHeight = sub?.size?.height?.toFloat() ?: 0f
    return AmllLineGeom(
        tokens = tokens,
        sub = sub,
        subOffset = Offset(left, textHeight),
        textHeight = textHeight,
        height = textHeight + subHeight,
        boxWidth = boxWidth,
        fontPx = fontPx,
        fadePx = fade,
        mask = mask,
        bg = bgLine,
        timed = text.timed,
    )
}

/** 整段都是收尾的标点（，。！？」等）：不放到行首。 */
private fun isClosingPunctuation(text: String): Boolean = text.isNotEmpty() && text.all {
    when (Character.getType(it).toByte()) {
        Character.END_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }
}

/**
 * 一行这一帧怎么画：[lit] 是亮层的淡入进度（0 只有暗层 20%，1 只有亮层），[sweepMs] 是扫光用的时间，
 * [motionMs] / [motion] 是上浮和强调用的时间和幅度（1 正在唱，0..1 离开后落回，0 静止）。[fullLit] 时扫光直接满（减少动画）。
 */
internal class AmllLineFrame {
    var lit = 0f
    var sweepMs = 0.0
    var motionMs = 0.0
    var motion = 0f
    var fullLit = false
    var emphasis = true
    var alpha = 1f
}

/** 画一行字。画字用的 [paint] 由调用方复用（强调的词要离屏合成）。 */
internal fun DrawScope.drawAmllLine(geom: AmllLineGeom, frame: AmllLineFrame, color: Color, paint: Paint) {
    val alpha = frame.alpha
    if (alpha <= 0.001f) return
    val lit = frame.lit
    val hi = (DIM_ALPHA + (1f - DIM_ALPHA) * lit) * alpha
    val lo = (DIM_ALPHA + (DARK_MASK_ALPHA - DIM_ALPHA) * lit) * alpha
    val uniform = lit <= 0.001f
    val travel = when {
        uniform -> 0.0
        frame.fullLit -> geom.mask.full
        else -> geom.mask.travelAt(frame.sweepMs)
    }
    val fade = geom.fadePx
    val up = if (geom.bg) 0.1f else 0.05f
    val glow = Build.VERSION.SDK_INT >= 31
    for (token in geom.tokens) {
        val start = (travel - token.sumPrev - 2 * fade).toFloat()
        val end = start + fade
        val emphasis = token.emphasis
        // 强调的词只在动着的时候逐字画；静止时和普通词一样（强调落定后也停在上浮 0.05 字号处）
        if (emphasis != null && frame.emphasis && frame.motion > 0f && token.chars != null && token.charX != null) {
            drawEmphasized(token, emphasis, frame, color, if (uniform) DIM_ALPHA * alpha else hi, if (uniform) DIM_ALPHA * alpha else lo, start, end, geom, paint, glow)
            continue
        }
        var dy = 0f
        if (frame.motion > 0f) {
            val p = amllClamp01((frame.motionMs - token.word.startMs) / max(1000.0, token.word.durationMs)).toFloat()
            dy = -up * geom.fontPx * AmllEase.cssOut.transform(p) * frame.motion
        }
        val at = Offset(token.x, token.y + dy)
        when {
            uniform -> drawText(token.layout, color.copy(alpha = color.alpha * DIM_ALPHA * alpha), at, shadow = Shadow.None)
            end <= 0f -> drawText(token.layout, color.copy(alpha = color.alpha * lo), at, shadow = Shadow.None)
            start >= token.width -> drawText(token.layout, color.copy(alpha = color.alpha * hi), at, shadow = Shadow.None)
            // drawText 先平移到 at 再上色，渐变的坐标是相对这个词的
            else -> drawText(
                token.layout,
                Brush.horizontalGradient(
                    listOf(color.copy(alpha = color.alpha * hi), color.copy(alpha = color.alpha * lo)),
                    startX = start,
                    endX = end,
                ),
                at,
                shadow = Shadow.None,
            )
        }
    }
    geom.sub?.let { drawText(it, color.copy(alpha = color.alpha * SUB_ALPHA * alpha), geom.subOffset, shadow = Shadow.None) }
}

/**
 * 长音强调的词：每个字自己放大、往两侧推、上提、发白光（字的原点在字的中心），扫光遮罩不跟着字动：
 * 字先画进离屏层，再用遮罩 DstIn 擦。所有字都还没开始或都已经落定时整词照常画。
 */
private fun DrawScope.drawEmphasized(
    token: AmllToken,
    emphasis: AmllEmphasis,
    frame: AmllLineFrame,
    color: Color,
    hi: Float,
    lo: Float,
    start: Float,
    end: Float,
    geom: AmllLineGeom,
    paint: Paint,
    glow: Boolean,
) {
    val chars = token.chars ?: return
    val charX = token.charX ?: return
    val em = geom.fontPx
    val motion = frame.motion
    // 先看每个字此刻的样子
    var animating = false
    for (k in chars.indices) {
        if (motion <= 0f) break
        val st = emphasis.charStart(token.charOffset + k)
        val xg = (frame.motionMs - st) / emphasis.durationMs
        val xf = (frame.motionMs - st) / (emphasis.durationMs * 1.4)
        if (xg in 0.0..1.0 || xf in 0.0..1.0) {
            animating = true
            break
        }
    }
    val bounds = Rect(token.x - em, token.y - em, token.x + token.width + em, token.y + token.height + em)
    drawContext.canvas.saveLayer(bounds, paint)
    for (k in chars.indices) {
        val layout = chars[k]
        val cw = layout.size.width.toFloat()
        val ch = layout.size.height.toFloat()
        var scale = 1f
        var tx = 0f
        var ty = 0f
        var glowAlpha = 0f
        if (motion > 0f) {
            val index = token.charOffset + k
            val st = emphasis.charStart(index)
            val xg = amllClamp01((frame.motionMs - st) / emphasis.durationMs)
            val xf = amllClamp01((frame.motionMs - st) / (emphasis.durationMs * 1.4))
            val trans = (amllKeyframed(xg, ::amllEmphasisCurve) * motion).toFloat()
            val float = (amllKeyframed(xf) { amllEmphasisFloat(it, geom.bg) } * motion).toFloat()
            scale = 1f + trans * 0.1f * emphasis.amount.toFloat()
            tx = -trans * 0.03f * emphasis.amount.toFloat() * (emphasis.chars / 2f - index) * em
            ty = (-trans * 0.025f * emphasis.amount.toFloat() - float) * em
            glowAlpha = trans * emphasis.blur.toFloat()
        }
        val shadowRadius = amllShadowRadius(min(0.3, emphasis.blur * 0.3).toFloat() * em)
        val shadow = if (glow && animating && glowAlpha > 0.004f && shadowRadius > 0f) {
            Shadow(Color.White.copy(alpha = glowAlpha.coerceAtMost(1f)), Offset.Zero, shadowRadius)
        } else {
            Shadow.None
        }
        val cx = token.x + charX[k] + cw / 2f
        val cy = token.y + ch / 2f
        withTransform({
            translate(cx + scale * tx, cy + scale * ty)
            scale(scale, scale, Offset.Zero)
            translate(-cx, -cy)
        }) {
            drawText(layout, color, Offset(token.x + charX[k], token.y), shadow = shadow)
        }
    }
    val brush = when {
        end <= 0f -> null
        start >= token.width -> null
        else -> Brush.horizontalGradient(listOf(Color.Black.copy(alpha = hi), Color.Black.copy(alpha = lo)), startX = token.x + start, endX = token.x + end)
    }
    if (brush != null) {
        drawRect(brush, bounds.topLeft, bounds.size, blendMode = BlendMode.DstIn)
    } else {
        drawRect(Color.Black.copy(alpha = if (end <= 0f) lo else hi), bounds.topLeft, bounds.size, blendMode = BlendMode.DstIn)
    }
    drawContext.canvas.restore()
}

/**
 * CSS 文字阴影的模糊半径 [cssBlurPx]（高斯 σ = 半径 / 2）换成 Android 的阴影半径（Skia：σ = 0.57735 × 半径 + 0.5）。
 */
internal fun amllShadowRadius(cssBlurPx: Float): Float = max(0f, (cssBlurPx / 2f - 0.5f) / 0.57735f)

/** CSS 的 filter: blur(σ) 换成 RenderEffect 的模糊半径（同一个换算）。 */
internal fun amllBlurRadius(sigmaPx: Float): Float = max(0f, (sigmaPx - 0.5f) / 0.57735f)

/** 羽化宽度：0.5 字号（原版注释：模拟 Apple Music for iPad 用 0.5）。 */
internal const val AMLL_FADE_WIDTH = 0.5f

/** 有对唱的歌，句子一侧留白的比例。 */
internal const val AMLL_DUET_PADDING = 0.15f
private const val AMLL_KEYFRAMES = 32
private const val DIM_ALPHA = 0.2f
private const val DARK_MASK_ALPHA = 0.4f
private const val SUB_ALPHA = 0.3f
