package com.mytv.mymusic.ui.player.stage.modes

import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import com.mytv.mymusic.data.LyricSubline
import com.mytv.mymusic.ui.player.stage.StageColors
import com.mytv.mymusic.ui.player.stage.splitCodePoints
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * 巨幕的画法：一块“屏”（安全区）上的扫描线、暗角、三种版式的正文和跑马灯带。
 * [CineramaScene] 在 drawWithCache 的缓存块里按尺寸建一次（尺寸、安全区变了才重建），
 * 每句的排版（量字）在这一句第一次上屏时做一次、按句缓存；逐帧只换位置、透明度、裁切。
 */

/** 面板设置。 */
internal class CineramaConfig(
    val marquee: String,
    val weights: CineramaWeights,
    val fontScale: Float,
    val speed: Float,
)

/**
 * 和尺寸有关的几何（px）：整页 [page]、屏 [screen]（安全区，字只放在这里面）、带厚 [band]、
 * 原模组 1 个 CSS px 折成多少屏幕像素（[unit]，按屏的短边 720 折算；[pageUnit] 按整页折算），
 * 以及宿主副行占的那一条 [subtitleBand]（14dp + 抬升 + 两行副行字）。
 */
internal class CineramaGeometry(
    val page: Size,
    val screen: Rect,
    val band: Float,
    val unit: Float,
    val pageUnit: Float,
    val subtitleBand: Float,
) {
    /** 正文离带子的距离：带厚 × 1.25（原版 safeClearanceRatio 0.25）。 */
    val clear: Float get() = band * CINERAMA_CLEARANCE
}

/** 每首歌一份、逐帧复用的可变对象。 */
internal class CineramaState {
    val frame = CineramaFrame()
    val look = CineramaChunkLook()
    val layerPaint = Paint()
}

/** 辉光：浓度量化成 20 档、半径取整后缓存 Shadow（不要光晕时给 Shadow.None，不能给 null）。 */
internal class CineramaGlow(private val color: Color, blur: Float) {
    private val radius = cineramaShadowRadius(blur)
    private val cache = arrayOfNulls<Shadow>(GLOW_LEVELS + 1)

    fun at(alpha: Float): Shadow {
        if (radius <= 0) return Shadow.None
        val level = (alpha.coerceIn(0f, 1f) * GLOW_LEVELS).roundToInt()
        if (level <= 0) return Shadow.None
        // 阴影色的不透明度压在 1 以下：Android 上不透明的阴影色会改跟画笔的不透明度走，透明字就画不出光
        return cache[level] ?: Shadow(
            color.copy(alpha = color.alpha * min(0.99f, level / GLOW_LEVELS.toFloat())),
            Offset.Zero,
            radius.toFloat(),
        ).also { cache[level] = it }
    }

    private companion object {
        const val GLOW_LEVELS = 20
    }
}

/** CSS 模糊半径（2σ）换成 Paint 阴影半径（σ ≈ 0.57735 × 半径 + 0.5），上限 63。 */
internal fun cineramaShadowRadius(blur: Float): Int =
    ((blur / 2f - 0.5f) / 0.57735f).roundToInt().coerceIn(0, 63)

/** 一句上屏要的全部排版：正文（三种版式之一）和它的跑马灯带。 */
private class CineramaArt(val main: CineramaMainArt?, val bands: CineramaBandArt?)

private sealed interface CineramaMainArt

private class CineramaSmallArt(
    val layouts: Array<TextLayoutResult?>,
    val x: FloatArray,
    val y: FloatArray,
    val jitter: FloatArray,
    val units: List<CineramaUnit>,
) : CineramaMainArt

private class CineramaChunkArt(
    val chunk: CineramaChunk,
    val layout: TextLayoutResult,
    val fontPx: Float,
    /** 摊开（字距变宽）的块逐字画：每个字的排版和它在块里的起点。 */
    val glyphs: Array<TextLayoutResult>?,
    val glyphX: FloatArray?,
    val glyphWidth: Float,
)

private class CineramaHeroArt(
    val chunks: List<CineramaChunkArt>,
    val center: Offset,
    val clip: Rect,
    /** 减少动态效果时的整句海报：每块的排版和左上角。 */
    val poster: Array<TextLayoutResult>?,
    val posterAt: FloatArray?,
) : CineramaMainArt

private class CineramaStripArt(
    val strip: CineramaStrip,
    val cx: Float,
    val cy: Float,
    val length: Float,
    val thickness: Float,
    val text: TextLayoutResult,
    val textUnit: Float,
    val shade: Brush,
    val glowAbove: Brush,
    val glowBelow: Brush,
    val dropAbove: Brush,
    val dropBelow: Brush,
    val glowExtent: Float,
    val dropExtent: Float,
)

private class CineramaRibbonArt(
    val strips: List<CineramaStripArt>,
    val area: Rect,
    val clampPx: Double,
    val spanSec: Double,
    /** 丝带在区域四边渐隐的遮罩（DstIn）：竖向、横向各一张。 */
    val fade: List<Brush>,
) : CineramaMainArt

private sealed interface CineramaBandArt

private class CineramaBandsArt(
    val marquee: CineramaMarquee,
    val top: TextLayoutResult?,
    val bottom: TextLayoutResult?,
) : CineramaBandArt

private class CineramaRingArt(
    val marquee: CineramaMarquee,
    val text: String,
    val unit: Float,
    val paint: android.graphics.Paint,
    val vOffset: Float,
) : CineramaBandArt

/**
 * 一种尺寸下的整块屏：静态的屏面家具（扫描线、暗角、带边辉光）在这里建好，正文按句懒排。
 */
internal class CineramaScene(
    private val program: CineramaProgram,
    private val measurer: TextMeasurer,
    private val colors: StageColors,
    private val config: CineramaConfig,
    private val geo: CineramaGeometry,
    private val still: Boolean,
    private val countScale: Float,
    private val subline: LyricSubline,
    private val density: Density,
) {
    private val arts = HashMap<Int, CineramaArt>()
    private val accent = colors.accent
    private val textGlow = CineramaGlow(accent, 30f * geo.unit)
    private val scanBrush = cineramaScanBrush(colors.text, density.density)
    private val layerPaint = Paint()
    private val vignette = cineramaVignette(geo.page, colors.text, 150f * geo.pageUnit)
    private val edge = cineramaEdgeGlow(geo.band.toDouble())
    private val bandFontPx = max(12f * geo.unit, geo.band * CINERAMA_BAND_FONT)
    private val bandStyle = textStyle(bandFontPx, FontWeight.SemiBold, CINERAMA_BAND_TRACK)
    private val bandGlow = CineramaGlow(accent.copy(alpha = 0.11f), bandFontPx * 0.044f)
    private val bandEdges by lazy { cineramaBandEdges(geo, edge, accent) }
    private val ringEdges by lazy { cineramaRingEdges(geo, edge, accent) }
    private val ringPath by lazy { cineramaRingPath(geo) }

    /** 屏面家具：宿主画背景，屏面填充让位，只留扫描线和暗角（铺满整页）。 */
    fun drawChrome(scope: DrawScope) = with(scope) {
        drawRect(scanBrush, alpha = 0.5f)
        for (i in vignette.indices) {
            val (rect, brush) = vignette[i]
            drawRect(brush, rect.topLeft, rect.size)
        }
    }

    /** 画一句：正文按包络（溶解的透明度与缩放）成组淡入淡出，带子按自己的在场程度。 */
    fun drawPlan(scope: DrawScope, index: Int, t: Double, envelope: CineramaEnvelope, state: CineramaState) {
        val plan = program.plans[index]
        val art = art(plan, index)
        val main = art.main
        if (main != null && envelope.opacity > 0.002f) {
            scope.layer(envelope.opacity, envelope.scale, state) {
                when (main) {
                    is CineramaSmallArt -> drawSmall(this, main, t)
                    is CineramaHeroArt -> drawHero(this, main, t, state.look)
                    is CineramaRibbonArt -> drawRibbon(this, main, plan, t)
                }
            }
        }
        val bands = art.bands
        if (bands != null && envelope.bands > 0.002f) {
            scope.layer(envelope.bands, 1f, state) {
                when (bands) {
                    is CineramaBandsArt -> drawBands(this, bands, t)
                    is CineramaRingArt -> drawRing(this, bands, t)
                }
            }
        }
    }

    /** 不透明度小于 1 时整组进离屏层再合成（叠在一起的丝带、字和光一起淡），缩放绕屏心。 */
    private inline fun DrawScope.layer(alpha: Float, scale: Float, state: CineramaState, block: DrawScope.() -> Unit) {
        val faded = alpha < 0.998f
        if (faded) {
            state.layerPaint.alpha = alpha
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), state.layerPaint)
        }
        if (abs(scale - 1f) > 1e-4f) {
            withTransform({ scale(scale, scale, geo.screen.center) }) { block() }
        } else {
            block()
        }
        if (faded) drawContext.canvas.restore()
    }

    // ------------------------------------------------------------ 排版（每句一次）

    private fun art(plan: CineramaPlan, index: Int): CineramaArt {
        arts[index]?.let { return it }
        if (arts.size >= ART_CACHE) {
            val keep = setOf(index - 1, index, index + 1)
            arts.keys.retainAll(keep)
        }
        val reserve = if (plan.line.subtitle(subline).isBlank()) 0f else geo.subtitleBand
        val main = when (plan.style) {
            CineramaStyle.Small -> buildSmall(plan, reserve)
            CineramaStyle.Hero -> buildHero(plan, reserve)
            CineramaStyle.Ribbon -> buildRibbon(plan, reserve)
        }
        val bands = plan.marquee?.let { buildBands(it) }
        return CineramaArt(main, bands).also { arts[index] = it }
    }

    /** 正文能用的区域：屏去掉跑马灯带（带厚 × 1.25）和宿主副行；四边带时两侧也让开。 */
    private fun content(plan: CineramaPlan, reserve: Float): Rect {
        val s = geo.screen
        val marquee = plan.marquee
        val clear = if (marquee != null) geo.clear else 0f
        val side = if (marquee?.kind == CineramaMarqueeKind.Frame) geo.clear else 0f
        val top = s.top + clear
        val bottom = max(top + 1f, s.bottom - max(clear, reserve))
        return Rect(s.left + side, top, max(s.left + side + 1f, s.right - side), bottom)
    }

    private fun textStyle(px: Float, weight: FontWeight, trackEm: Float, italic: Boolean = false): TextStyle = with(density) {
        TextStyle(
            fontSize = px.toSp(),
            fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            letterSpacing = trackEm.em,
        )
    }

    private fun measure(text: String, style: TextStyle): TextLayoutResult =
        measurer.measure(text, style, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1)

    /**
     * 小字报：按排版表的留白和落点，字号是屏高 14% × 排版倍率（夹在 5%–26%）× 字号设置；
     * 再按文字盒宽限到一行至少 7 个字，排不下就 × 0.88 缩，单元像 inline-block 一样流式换行、整块居中。
     */
    private fun buildSmall(plan: CineramaPlan, reserve: Float): CineramaSmallArt {
        val s = geo.screen
        val layout = plan.layout
        val marquee = plan.marquee
        val clear = if (marquee != null) geo.clear else 0f
        val side = if (marquee?.kind == CineramaMarqueeKind.Frame) geo.clear else 0f
        val padTop = max(layout.padY * s.height, clear)
        val padBottom = max(layout.padY * s.height, max(clear, reserve))
        val padSide = max(layout.padX * s.width, side)
        val area = Rect(s.left + padSide, s.top + padTop, max(s.left + padSide + 1f, s.right - padSide), max(s.top + padTop + 1f, s.bottom - padBottom))
        val boxWidth = area.width * layout.maxWidthRatio
        var font = (cineramaClamp(layout.fontScale * 0.14, 0.05, 0.26) * s.height).toFloat() * config.fontScale
        font = max(font, 20f * geo.unit)
        font = min(font, boxWidth / SMALL_MIN_CHARS)
        val units = plan.units
        val blank = BooleanArray(units.size) { units[it].text.isBlank() }
        val reference = font
        val baseStyle = textStyle(reference, FontWeight.SemiBold, layout.trackEm)
        val baseWidths = FloatArray(units.size) { i -> measure(units[i].text, baseStyle).size.width.toFloat() }
        val size = FloatArray(2)
        val widths = FloatArray(units.size)
        for (attempt in 0 until 12) {
            val k = font / reference
            for (i in widths.indices) widths[i] = baseWidths[i] * k
            cineramaFlow(widths, blank, font * layout.lineHeight, boxWidth, size)
            if (size[1] <= area.height && size[0] <= boxWidth + 0.5f) break
            font *= 0.88f
        }
        val style = textStyle(font, FontWeight.SemiBold, layout.trackEm)
        val layouts = arrayOfNulls<TextLayoutResult>(units.size)
        for (i in units.indices) {
            if (!blank[i]) layouts[i] = measure(units[i].text, style)
            widths[i] = if (blank[i]) baseWidths[i] * font / reference else layouts[i]!!.size.width.toFloat()
        }
        val rowHeight = font * layout.lineHeight
        val pos = cineramaFlow(widths, blank, rowHeight, boxWidth, size)
        val centerX = when (layout.alignX) {
            -1 -> area.left + boxWidth / 2f
            1 -> area.right - boxWidth / 2f
            else -> area.center.x
        }
        val top = when (layout.alignY) {
            -1 -> area.top
            1 -> area.bottom - size[1]
            else -> area.center.y - size[1] / 2f
        }
        val left = centerX - size[0] / 2f
        val x = FloatArray(units.size)
        val y = FloatArray(units.size)
        for (i in units.indices) {
            val l = layouts[i]
            if (l == null || pos[i * 2].isNaN()) {
                layouts[i] = null
                continue
            }
            x[i] = left + pos[i * 2]
            y[i] = top + pos[i * 2 + 1] + (rowHeight - l.size.height) / 2f
        }
        val jitter = FloatArray(units.size) { cineramaHash01(plan.seed, it, 7).toFloat() }
        return CineramaSmallArt(layouts, x, y, jitter, units)
    }

    /**
     * 大字报：按屏的宽高比分块（见 [cineramaHeroChunks]），每块单独居中闪出；字号超出内容区时按量出来的宽高再缩。
     * 减少动态效果时把所有块按各自字号排成一张整句海报（一起缩到放得进内容区）。
     */
    private fun buildHero(plan: CineramaPlan, reserve: Float): CineramaHeroArt {
        val s = geo.screen
        val box = content(plan, reserve)
        val side = if (plan.marquee?.kind == CineramaMarqueeKind.Frame) geo.clear * 2 else 0f
        val metrics = CineramaHeroMetrics.of(s.width.toDouble(), s.height.toDouble(), box.width.toDouble(), box.height.toDouble(), side.toDouble())
        val chunks = cineramaHeroChunks(plan, metrics, CineramaWordSegmenter)
        val clip = Rect(s.left, s.top, s.right, max(s.top + 1f, s.bottom - reserve))
        val minFont = 28f * geo.unit
        val arts = chunks.map { chunk ->
            var font = max(minFont, chunk.font.toFloat() * config.fontScale)
            var style = textStyle(font, FontWeight.SemiBold, CINERAMA_HERO_TRACK_EM.toFloat(), chunk.italic)
            var layout = measure(chunk.text, style)
            val fit = min(box.width * 0.98f / max(1, layout.size.width), box.height / max(1, layout.size.height))
            if (fit < 1f) {
                font *= fit
                style = textStyle(font, FontWeight.SemiBold, CINERAMA_HERO_TRACK_EM.toFloat(), chunk.italic)
                layout = measure(chunk.text, style)
            }
            var glyphs: Array<TextLayoutResult>? = null
            var glyphX: FloatArray? = null
            var glyphWidth = 0f
            if (!still && chunk.motion.tracks) {
                val chars = splitCodePoints(chunk.text)
                val g = Array(chars.size) { measure(chars[it], style) }
                val gx = FloatArray(chars.size)
                var cursor = 0f
                for (i in chars.indices) {
                    gx[i] = cursor
                    val w = g[i].size.width.toFloat()
                    cursor += if (w <= 0f && chars[i].isBlank()) font * 0.28f else w
                }
                glyphs = g
                glyphX = gx
                glyphWidth = cursor
            }
            CineramaChunkArt(chunk, layout, font, glyphs, glyphX, glyphWidth)
        }
        var poster: Array<TextLayoutResult>? = null
        var posterAt: FloatArray? = null
        if (still && arts.isNotEmpty()) {
            val n = arts.size
            val widths = FloatArray(n * 2 - 1)
            val blank = BooleanArray(n * 2 - 1) { it % 2 == 1 }
            val gap = arts.minOf { it.fontPx } * 0.3f
            var rowHeight = 0f
            for (a in arts) rowHeight = max(rowHeight, a.layout.size.height.toFloat())
            val size = FloatArray(2)
            var k = 1f
            for (attempt in 0 until 24) {
                for (i in 0 until n) {
                    widths[i * 2] = arts[i].layout.size.width * k
                    if (i + 1 < n) widths[i * 2 + 1] = gap * k
                }
                cineramaFlow(widths, blank, rowHeight * k, box.width, size)
                if (size[0] <= box.width + 0.5f && size[1] <= box.height) break
                k *= 0.92f
            }
            poster = Array(n) { i ->
                val c = arts[i]
                if (k >= 0.999f) c.layout else measure(c.chunk.text, textStyle(c.fontPx * k, FontWeight.SemiBold, CINERAMA_HERO_TRACK_EM.toFloat(), c.chunk.italic))
            }
            for (i in 0 until n) {
                widths[i * 2] = poster[i].size.width.toFloat()
                if (i + 1 < n) widths[i * 2 + 1] = gap * k
            }
            val row = rowHeight * k
            val pos = cineramaFlow(widths, blank, row, box.width, size)
            val left = box.center.x - size[0] / 2f
            val top = box.center.y - size[1] / 2f
            posterAt = FloatArray(n * 2)
            for (i in 0 until n) {
                posterAt[i * 2] = left + pos[i * 4]
                posterAt[i * 2 + 1] = top + pos[i * 4 + 1] + (row - poster[i].size.height) / 2f
            }
        }
        return CineramaHeroArt(arts, box.center, clip, poster, posterAt)
    }

    /**
     * 斜切丝带：屏（去掉宿主副行那一条）上铺 6 条（电视按六成，3–9 条），强调色的带身、主题底色的粗体字，
     * 斜向光泽、外发光和投影都按原版的 CSS 折成渐变；字沿带子往左走，带子整条上下慢慢漂。
     */
    private fun buildRibbon(plan: CineramaPlan, reserve: Float): CineramaRibbonArt {
        val s = geo.screen
        val area = Rect(s.left, s.top, s.right, max(s.top + 1f, s.bottom - reserve))
        val w = area.width.toDouble()
        val h = area.height.toDouble()
        val count = (CINERAMA_RIBBON_COUNT * countScale).roundToInt().coerceIn(3, 9)
        val strips = cineramaRibbonStrips(plan.seed, count, plan.line.text, plan.line.translation)
        val span = plan.end - plan.start
        val clampPx = cineramaRibbonDriftClamp(strips, span, h)
        val u = geo.unit
        val glowExtent = 36f * u
        val dropExtent = 30f * u
        val glowStops = cineramaShadowStops(0.6, 24.0 * u, 0.0, glowExtent.toDouble())
        val dropAboveStops = cineramaShadowStops(0.45, 16.0 * u, -6.0 * u, dropExtent.toDouble())
        val dropBelowStops = cineramaShadowStops(0.45, 16.0 * u, 6.0 * u, dropExtent.toDouble())
        val glowColor = accent.copy(alpha = 1f)
        val arts = strips.sortedByDescending { it.topPct }.map { strip ->
            val thickness = (strip.thicknessPct * h / 100).toFloat()
            val cy = (area.top + strip.topPct * h / 100).toFloat() + thickness / 2f
            val length = cineramaRibbonLength(w, h, strip, clampPx).toFloat()
            val font = min(thickness, max(10f * u, (strip.fontPct * h / 100).toFloat()))
            val text = measure(strip.text + "　", textStyle(font, FontWeight.ExtraBold, CINERAMA_RIBBON_TRACK))
            val half = thickness / 2f
            CineramaStripArt(
                strip = strip,
                cx = area.center.x,
                cy = cy,
                length = length,
                thickness = thickness,
                text = text,
                textUnit = max(1f, text.size.width.toFloat()),
                shade = cineramaShadeBrush(strip, length, thickness),
                glowAbove = cineramaFalloff(glowColor, glowStops, -half, -half - glowExtent),
                glowBelow = cineramaFalloff(glowColor, glowStops, half, half + glowExtent),
                dropAbove = cineramaFalloff(Color.Black, dropAboveStops, -half, -half - dropExtent),
                dropBelow = cineramaFalloff(Color.Black, dropBelowStops, half, half + dropExtent),
                glowExtent = glowExtent,
                dropExtent = dropExtent,
            )
        }
        return CineramaRibbonArt(arts, area, clampPx, span, cineramaEdgeFade(area, density.density))
    }

    /** 跑马灯带：双带量上下两份字（“字　”为一份），四边量一份并按整圈调字距。 */
    private fun buildBands(marquee: CineramaMarquee): CineramaBandArt {
        if (marquee.kind == CineramaMarqueeKind.Bands) {
            val top = marquee.top.takeIf { it.isNotEmpty() }?.let { measure(it + "　", bandStyle) }
            val bottom = marquee.bottom.takeIf { it.isNotEmpty() }?.let { measure(it + "　", bandStyle) }
            return CineramaBandsArt(marquee, top, bottom)
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = bandFontPx
            typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT_BOLD
            color = accent.toArgb()
            letterSpacing = CINERAMA_BAND_TRACK
            val r = cineramaShadowRadius(bandFontPx * 0.044f)
            if (r > 0) setShadowLayer(r.toFloat(), 0f, 0f, accent.copy(alpha = 0.11f).toArgb())
        }
        val one = if (marquee.ring.isEmpty()) "" else marquee.ring + "　"
        var unit = 0f
        var text = ""
        if (one.isNotEmpty()) {
            val glyphs = splitCodePoints(one).size
            val period = paint.measureText(one).toDouble()
            val lap = ringPath.second
            val (copies, spacing) = cineramaRingCopies(lap, period, glyphs)
            // 原版的字距是额外加的 px，这里折成 em 叠到 0.12em 上；再量一次整圈，把剩下的误差摊回字距
            var extra = spacing
            paint.letterSpacing = CINERAMA_BAND_TRACK + (extra / bandFontPx).toFloat()
            text = one.repeat(copies)
            val total = paint.measureText(text).toDouble()
            extra += (lap - total) / (copies * glyphs)
            paint.letterSpacing = CINERAMA_BAND_TRACK + (extra / bandFontPx).toFloat()
            unit = paint.measureText(text) / copies
        }
        val metrics = paint.fontMetrics
        return CineramaRingArt(marquee, text, unit, paint, -(metrics.ascent + metrics.descent) / 2f)
    }

    // ------------------------------------------------------------ 逐帧

    private fun drawSmall(scope: DrawScope, art: CineramaSmallArt, t: Double) = with(scope) {
        for (i in art.units.indices) {
            val layout = art.layouts[i] ?: continue
            val unit = art.units[i]
            val p = if (still) 1f else cineramaUnitProgress(unit.start, unit.end, t).toFloat()
            if (p <= 0.002f) continue
            val color = accent.copy(alpha = accent.alpha * p)
            val shadow = textGlow.at(p)
            val at = Offset(art.x[i], art.y[i])
            if (p >= 0.999f) {
                drawText(layout, color, at, shadow = shadow, drawStyle = Fill)
                continue
            }
            val h = layout.size.height
            val dy = (1 - p) * (8f + art.jitter[i] * 10f) / 100f * h
            val scale = 0.94f + 0.06f * p
            val pivot = Offset(at.x + layout.size.width / 2f, at.y + h / 2f + dy)
            withTransform({
                translate(0f, dy)
                scale(scale, scale, pivot)
            }) {
                drawText(layout, color, at, shadow = shadow, drawStyle = Fill)
            }
        }
    }

    private fun drawHero(scope: DrawScope, art: CineramaHeroArt, t: Double, look: CineramaChunkLook) = with(scope) {
        val poster = art.poster
        val at = art.posterAt
        if (poster != null && at != null) {
            val shadow = textGlow.at(1f)
            for (i in poster.indices) drawText(poster[i], accent, Offset(at[i * 2], at[i * 2 + 1]), shadow = shadow, drawStyle = Fill)
            return@with
        }
        val s = geo.screen
        clipRect(art.clip.left, art.clip.top, art.clip.right, art.clip.bottom) {
            for (ci in art.chunks.indices) {
                val c = art.chunks[ci]
                if (!cineramaChunkLook(c.chunk, t, look)) continue
                val cx = art.center.x + look.driftX / 100f * s.width
                val cy = art.center.y + look.driftY / 100f * s.height
                val alpha = look.opacity
                val color = accent.copy(alpha = accent.alpha * alpha)
                val glow = textGlow.at(alpha)
                withTransform({ scale(look.scale, look.scale, Offset(cx, cy)) }) {
                    val glyphs = c.glyphs
                    val gx = c.glyphX
                    if (glyphs != null && gx != null) {
                        val track = look.trackEm * c.fontPx
                        val left = cx - (c.glyphWidth + track * (glyphs.size - 1)) / 2f
                        for (g in glyphs.indices) {
                            val layout = glyphs[g]
                            drawText(layout, color, Offset(left + gx[g] + track * g, cy - layout.size.height / 2f), shadow = glow, drawStyle = Fill)
                        }
                        return@withTransform
                    }
                    val layout = c.layout
                    val w = layout.size.width.toFloat()
                    val h = layout.size.height.toFloat()
                    val topLeft = Offset(cx - w / 2f, cy - h / 2f)
                    if (!c.chunk.fill) {
                        drawText(layout, color, topLeft, shadow = glow, drawStyle = Fill)
                        return@withTransform
                    }
                    // 描边填色：光单独一层（透明字带光），描边留在没填的一侧，实心字在填过的一侧
                    drawText(layout, Color.Transparent, topLeft, shadow = glow, drawStyle = Fill)
                    val pad = h * 0.3f
                    val stroke = Stroke(width = (CINERAMA_OUTLINE_EM * c.fontPx).toFloat().coerceAtLeast(1f))
                    if (c.chunk.fillVertical) {
                        val split = topLeft.y + h * look.fill
                        clipRect(topLeft.x - pad, split, topLeft.x + w + pad, topLeft.y + h + pad) {
                            drawText(layout, color, topLeft, shadow = Shadow.None, drawStyle = stroke)
                        }
                        clipRect(topLeft.x - pad, topLeft.y - pad, topLeft.x + w + pad, split) {
                            drawText(layout, color, topLeft, shadow = Shadow.None, drawStyle = Fill)
                        }
                    } else {
                        val split = topLeft.x + w * look.fill
                        clipRect(split, topLeft.y - pad, topLeft.x + w + pad, topLeft.y + h + pad) {
                            drawText(layout, color, topLeft, shadow = Shadow.None, drawStyle = stroke)
                        }
                        clipRect(topLeft.x - pad, topLeft.y - pad, split, topLeft.y + h + pad) {
                            drawText(layout, color, topLeft, shadow = Shadow.None, drawStyle = Fill)
                        }
                    }
                }
            }
        }
    }

    private fun drawRibbon(scope: DrawScope, art: CineramaRibbonArt, plan: CineramaPlan, t: Double) = with(scope) {
        val elapsed = if (still) 0.0 else t - plan.start
        val h = art.area.height.toDouble()
        val w = art.area.width.toDouble()
        val scroll = cineramaRibbonScroll(elapsed, w, art.spanSec).toFloat()
        val ink = colors.surface.copy(alpha = 1f)
        val lineH = max(1f, geo.unit)
        // 丝带横穿整个区域，在区域边上硬切很显眼（原版切在窗口边上）：离屏画完再用遮罩把四边擦淡
        drawContext.canvas.saveLayer(art.area, layerPaint)
        clipRect(art.area.left, art.area.top, art.area.right, art.area.bottom) {
            for (si in art.strips.indices) {
                val s = art.strips[si]
                val drift = cineramaRibbonDrift(s.strip, elapsed, h, art.clampPx).toFloat()
                val half = s.thickness / 2f
                val l = s.length / 2f
                withTransform({
                    translate(s.cx, s.cy + drift)
                    rotate(s.strip.angle.toFloat(), Offset.Zero)
                }) {
                    drawRect(Color.White.copy(alpha = 0.3f), Offset(-l, half), Size(s.length, lineH))
                    drawRect(s.dropAbove, Offset(-l, -half - s.dropExtent), Size(s.length, s.dropExtent))
                    drawRect(s.dropBelow, Offset(-l, half), Size(s.length, s.dropExtent))
                    drawRect(s.glowAbove, Offset(-l, -half - s.glowExtent), Size(s.length, s.glowExtent))
                    drawRect(s.glowBelow, Offset(-l, half), Size(s.length, s.glowExtent))
                    drawRect(accent.copy(alpha = 1f), Offset(-l, -half), Size(s.length, s.thickness))
                    drawRect(s.shade, Offset(-l, -half), Size(s.length, s.thickness))
                    clipRect(-l, -half, l, half) {
                        val unit = s.textUnit
                        val y = -s.text.size.height / 2f
                        var k = floor(scroll / unit).toInt()
                        var x = -l - scroll + k * unit
                        while (x < l) {
                            drawText(s.text, ink, Offset(x, y), shadow = Shadow.None, drawStyle = Fill)
                            k++
                            x = -l - scroll + k * unit
                        }
                    }
                }
            }
        }
        for (brush in art.fade) drawRect(brush, art.area.topLeft, art.area.size, blendMode = BlendMode.DstIn)
        drawContext.canvas.restore()
    }

    private fun drawBands(scope: DrawScope, art: CineramaBandsArt, t: Double) = with(scope) {
        val s = geo.screen
        val m = art.marquee
        val topUnit = art.top?.size?.width?.toDouble() ?: 0.0
        val shift = if (still) 0.0 else cineramaBandShift(t, m.screensPerSec, config.speed.toDouble(), s.width.toDouble(), topUnit)
        art.top?.let { drawBandText(this, it, Rect(s.left, s.top, s.right, s.top + geo.band), cineramaBandSlot(shift, m.orbit, false, m.mirror), false) }
        art.bottom?.let { drawBandText(this, it, Rect(s.left, s.bottom - geo.band, s.right, s.bottom), cineramaBandSlot(shift, m.orbit, true, m.mirror), m.mirror) }
        // 原版的辉光层叠在带盒之上
        for (i in bandEdges.indices) {
            val (rect, brush) = bandEdges[i]
            drawRect(brush, rect.topLeft, rect.size)
        }
    }

    /** 一条带上的字：“字　”一份接一份铺满整条，按 [slot]（以份为单位）平移；镜像的下带整条转 180°。 */
    private fun drawBandText(scope: DrawScope, layout: TextLayoutResult, rect: Rect, slot: Double, flipped: Boolean) = with(scope) {
        val unit = layout.size.width.toFloat()
        if (unit <= 1f) return@with
        val y = rect.center.y - layout.size.height / 2f
        val shadow = bandGlow.at(1f)
        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
            withTransform({ if (flipped) rotate(180f, rect.center) }) {
                var x = rect.left + cineramaFrac(slot).toFloat() * unit - unit
                while (x < rect.right) {
                    drawText(layout, accent, Offset(x, y), shadow = shadow, drawStyle = Fill)
                    x += unit
                }
            }
        }
    }

    private fun drawRing(scope: DrawScope, art: CineramaRingArt, t: Double) = with(scope) {
        for (i in ringEdges.indices) {
            val (path, brush) = ringEdges[i]
            drawPath(path, brush)
        }
        if (art.text.isEmpty() || art.unit <= 1f) return@with
        val m = art.marquee
        val shift = if (still) 0.0 else cineramaBandShift(t, m.screensPerSec, config.speed.toDouble(), geo.screen.width.toDouble(), art.unit.toDouble())
        val offset = cineramaRingOffset(shift, m.orbit, art.unit.toDouble()).toFloat()
        drawContext.canvas.nativeCanvas.drawTextOnPath(art.text, ringPath.first, offset, art.vOffset, art.paint)
    }

    private companion object {
        const val ART_CACHE = 4
        const val SMALL_MIN_CHARS = 7f
    }
}

/** 扫描线：一张 1 × 3dp 的小图（顶上 1dp 是文字色 8%），纵向平铺；整层再按 0.5 画。 */
private fun cineramaScanBrush(text: Color, density: Float): Brush {
    val period = max(2, (3f * density).roundToInt())
    val line = (density.roundToInt()).coerceIn(1, period - 1)
    val image = ImageBitmap(1, period)
    Canvas(image).drawRect(0f, 0f, 1f, line.toFloat(), Paint().apply { color = text.copy(alpha = text.alpha * 0.08f) })
    return ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))
}

/** 暗角：原版 inset 0 0 150px 文字色 16% 的内阴影，四条边各一条渐变（角上两条相叠）。 */
private fun cineramaVignette(page: Size, text: Color, blur: Float): List<Pair<Rect, Brush>> {
    val extent = min(blur * 1.5f, min(page.width, page.height) / 2f)
    if (extent <= 1f) return emptyList()
    val stops = cineramaShadowStops(0.16 * text.alpha, blur.toDouble(), 0.0, extent.toDouble())
    val w = page.width
    val h = page.height
    return listOf(
        Rect(0f, 0f, w, extent) to cineramaFalloff(text, stops, 0f, extent, vertical = true),
        Rect(0f, h - extent, w, h) to cineramaFalloff(text, stops, h, h - extent, vertical = true),
        Rect(0f, 0f, extent, h) to cineramaFalloff(text, stops, 0f, extent, vertical = false),
        Rect(w - extent, 0f, w, h) to cineramaFalloff(text, stops, w, w - extent, vertical = false),
    )
}

/** 从 [from] 到 [to] 的衰减渐变：[alphas] 按 [CINERAMA_GLOW_STOPS] 取样。 */
private fun cineramaFalloff(color: Color, alphas: FloatArray, from: Float, to: Float, vertical: Boolean = true): Brush {
    val stops = Array(alphas.size) { i -> CINERAMA_GLOW_STOPS[i] to color.copy(alpha = alphas[i].coerceIn(0f, 1f)) }
    return if (vertical) {
        Brush.verticalGradient(*stops, startY = from, endY = to)
    } else {
        Brush.horizontalGradient(*stops, startX = from, endX = to)
    }
}

/** 双带的边线辉光：只画朝屏心的那条长边，屏面一侧亮芯 + 中间层，带子一侧一层外溢。 */
private fun cineramaBandEdges(geo: CineramaGeometry, glow: CineramaEdgeGlow, accent: Color): List<Pair<Rect, Brush>> {
    val s = geo.screen
    val e = glow.extent
    if (e <= 0.5f) return emptyList()
    val color = accent.copy(alpha = 1f)
    val top = s.top + geo.band
    val bottom = s.bottom - geo.band
    return listOf(
        Rect(s.left, top - e, s.right, top) to cineramaFalloff(color, glow.band, top, top - e),
        Rect(s.left, top, s.right, top + e) to cineramaFalloff(color, glow.screen, top, top + e),
        Rect(s.left, bottom, s.right, bottom + e) to cineramaFalloff(color, glow.band, bottom, bottom + e),
        Rect(s.left, bottom - e, s.right, bottom) to cineramaFalloff(color, glow.screen, bottom, bottom - e),
    )
}

/**
 * 四边的边线辉光：沿内框（屏往里收一个带厚）四条边，往屏心是亮芯 + 中间层，往带子是外溢；
 * 每条边画成梯形，角上按对角线分开，不叠出亮块。
 */
private fun cineramaRingEdges(geo: CineramaGeometry, glow: CineramaEdgeGlow, accent: Color): List<Pair<Path, Brush>> {
    val s = geo.screen
    val b = geo.band
    val e = glow.extent
    val l = s.left + b
    val t = s.top + b
    val r = s.right - b
    val btm = s.bottom - b
    if (e <= 0.5f || r - l < 2 * e || btm - t < 2 * e) return emptyList()
    val color = accent.copy(alpha = 1f)
    fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Path().apply {
        moveTo(x0, y0)
        lineTo(x1, y1)
        lineTo(x2, y2)
        lineTo(x3, y3)
        close()
    }
    return listOf(
        // 往屏心
        quad(l, t, r, t, r - e, t + e, l + e, t + e) to cineramaFalloff(color, glow.screen, t, t + e),
        quad(l, btm, r, btm, r - e, btm - e, l + e, btm - e) to cineramaFalloff(color, glow.screen, btm, btm - e),
        quad(l, t, l, btm, l + e, btm - e, l + e, t + e) to cineramaFalloff(color, glow.screen, l, l + e, vertical = false),
        quad(r, t, r, btm, r - e, btm - e, r - e, t + e) to cineramaFalloff(color, glow.screen, r, r - e, vertical = false),
        // 往带子
        quad(l, t, r, t, r + e, t - e, l - e, t - e) to cineramaFalloff(color, glow.band, t, t - e),
        quad(l, btm, r, btm, r + e, btm + e, l - e, btm + e) to cineramaFalloff(color, glow.band, btm, btm + e),
        quad(l, t, l, btm, l - e, btm + e, l - e, t - e) to cineramaFalloff(color, glow.band, l, l - e, vertical = false),
        quad(r, t, r, btm, r + e, btm + e, r + e, t - e) to cineramaFalloff(color, glow.band, r, r + e, vertical = false),
    )
}

/**
 * 四边环的文字路径：屏往里收半个带厚的圆角矩形（圆角 0.4 × 带厚），顺时针、从左上圆角之后起，
 * **连走两圈**合成一条轮廓。drawTextOnPath 超出路径末端的字会堆在终点，走两圈后从 0..一份之间任一点起排一整圈都落在路径上。
 * 返回路径和一圈的长度。
 */
private fun cineramaRingPath(geo: CineramaGeometry): Pair<android.graphics.Path, Double> {
    val s = geo.screen
    val m = geo.band / 2f
    val left = s.left + m
    val top = s.top + m
    val right = s.right - m
    val bottom = s.bottom - m
    val w = right - left
    val h = bottom - top
    val r = min(geo.band * 0.4f, max(0f, min(w, h) / 2f - 1f))
    val path = android.graphics.Path()
    path.moveTo(left + r, top)
    repeat(2) {
        path.lineTo(right - r, top)
        if (r > 0f) path.arcTo(RectF(right - 2 * r, top, right, top + 2 * r), -90f, 90f, false)
        path.lineTo(right, bottom - r)
        if (r > 0f) path.arcTo(RectF(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f, false)
        path.lineTo(left + r, bottom)
        if (r > 0f) path.arcTo(RectF(left, bottom - 2 * r, left + 2 * r, bottom), 90f, 90f, false)
        path.lineTo(left, top + r)
        if (r > 0f) path.arcTo(RectF(left, top, left + 2 * r, top + 2 * r), 180f, 90f, false)
    }
    return path to cineramaRingLap(w.toDouble(), h.toDouble(), r.toDouble())
}

/**
 * 丝带的斜向光泽：CSS linear-gradient(角度) 在带子盒子上的那条渐变线（0° 朝上、顺时针），
 * 亮带白色、暗带黑色，浓度按原版暗色主题（亮 × 1.4、暗 × 0.5，都不超过 0.55）。坐标是带子自己的（中心在原点）。
 */
private fun cineramaShadeBrush(strip: CineramaStrip, length: Float, thickness: Float): Brush {
    val rad = strip.shadeAngle * PI / 180
    val dx = sin(rad).toFloat()
    val dy = (-cos(rad)).toFloat()
    val span = abs(length * dx) + abs(thickness * dy)
    val light = min(0.55, strip.shadeStrength * 1.4).toFloat()
    val dark = min(0.55, strip.shadeStrength * 0.5).toFloat()
    val at = cineramaShadeStops(strip)
    val colors = arrayOf(
        Color.White.copy(alpha = 0f),
        Color.White.copy(alpha = light),
        Color.White.copy(alpha = 0f),
        Color.Black.copy(alpha = 0f),
        Color.Black.copy(alpha = dark),
        Color.Black.copy(alpha = 0f),
    )
    val stops = Array(at.size) { at[it] to colors[it] }
    val half = span / 2f
    return Brush.linearGradient(*stops, start = Offset(-dx * half, -dy * half), end = Offset(dx * half, dy * half))
}

/** 带内字号 = 带厚 × 0.55，字距 0.12em。 */
internal const val CINERAMA_BAND_FONT = 0.55f
private const val CINERAMA_BAND_TRACK = 0.12f
private const val CINERAMA_RIBBON_TRACK = 0.05f
private const val CINERAMA_RIBBON_COUNT = 6
internal const val CINERAMA_CLEARANCE = 1.25f

/** 带厚：屏短边的 12%，不超过正文基准字号的 1.6 倍。 */
internal const val CINERAMA_BAND_RATIO = 0.12f
internal const val CINERAMA_BAND_TEXT = 1.6f

/**
 * 区域四边的渐隐遮罩（DstIn 用）：上下各 min(24dp, 高的 8%)、左右各 min(32dp, 宽的 6%) 从透明渐到不透明。
 * [dp] 是一个 dp 的像素数。
 */
private fun cineramaEdgeFade(area: Rect, dp: Float): List<Brush> {
    val bandY = min(24f * dp, area.height * 0.08f)
    val bandX = min(32f * dp, area.width * 0.06f)
    val y = (bandY / max(1f, area.height)).coerceIn(0f, 0.5f)
    val x = (bandX / max(1f, area.width)).coerceIn(0f, 0.5f)
    return listOf(
        Brush.verticalGradient(0f to Color.Transparent, y to Color.Black, 1f - y to Color.Black, 1f to Color.Transparent, startY = area.top, endY = area.bottom),
        Brush.horizontalGradient(0f to Color.Transparent, x to Color.Black, 1f - x to Color.Black, 1f to Color.Transparent, startX = area.left, endX = area.right),
    )
}
