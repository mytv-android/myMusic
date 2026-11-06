package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.ui.player.stage.LyricSetting
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.LyricVisualizer
import com.mytv.mymusic.ui.player.stage.ModOrigin
import com.mytv.mymusic.ui.player.stage.SettingValues
import com.mytv.mymusic.ui.player.stage.StageLine
import com.mytv.mymusic.ui.player.stage.formatInt
import com.mytv.mymusic.ui.player.stage.formatSeconds
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 星潮·银河（Folia 模组 stardrift 1.38.0，作者 w0w1w2，MIT）。
 * 整页是一片不断更替的星海：四角星（圆润星芒）在随机位置、随机时刻冒出来，按各自的大小和较慢的节奏闪亮，再淡出消失；
 * 约四成聚在斜向的银河带里，中央一团很淡的星云光晕。流星不时从屏幕外滑入、斜穿画面再滑出，拖尾由亮到透明，头部也是四角星。
 * 当前句的每个字出现在安全区里的随机位置（同句和还亮着的旧字之间尽量不重叠）：先是一颗小光点，放大成字、颜色从强调色变成文字色，
 * 正在唱的字罩一圈柔和的径向光罩、带同色辉光；唱完停留 [StardriftConfig.hold] 秒，再变回强调色缩小消失，光罩先快速缩没。
 * 换句时上一句已经开始的字不打断，自己走完。音乐呼吸：响度调制星星亮度、星云和字的辉光、停留时字的不透明度。
 *
 * 移植取舍：
 * - 原版用 Math.random，这里一律以歌曲种子派生：星星按“星位 × 轮次”算出位置和节奏（[StardriftSky]，不存历史），
 *   每句的落点用该句自己的随机序列，同一首歌画面可复现，也方便截图测试。
 * - 星星、流星、闪烁用宿主的动画时钟（暂停时停，跳转不重建星海）；字的生命周期用歌词时间，往回跳或往前跳超过 0.5 秒时清空重排（同原版）。
 * - 所有星点按颜色（3 种）× 亮度（10 档）分批合成 Path，一帧最多 30 次 drawPath，不逐个画；星星数量乘电视的 countScale。
 * - 光罩是一支以原点为中心的径向渐变，按字的位置和光罩大小平移缩放后画圆，全句共用；辉光用文字阴影（所有版本可用），
 *   模糊半径取整后缓存 Shadow。没有律动数据（[LyricStageContext.audio] 为 null）时不呼吸，原版这时按时间缓慢起伏。
 * - 流星数量面板按 0.25 步进：整数部分照原版“少了立刻补”，小数部分按屏上有流星的时间占比补（见 [stardriftMeteorRate]）。
 * - 原模组 15 项设置，面板放 8 项；变化速度 1、辉光强度 1、星星大小 1、光罩深浅 1、光圈大小 1、字缩放时间 0.5 秒、流星大小 1 按原默认值写死。
 * - 减少动态效果：星海取固定时刻的静帧，一颗定格的流星，当前句的字按落点全部显示在停留态（各带光罩），没有运动。
 */
object StardriftVisualizer : LyricVisualizer {
    override val id = "stardrift"
    override val label = "星潮·银河"
    override val origin = ModOrigin("stardrift", "1.38.0", "w0w1w2", "MIT")
    override val order = 70
    override val hostBackground = true
    override val hostSubtitles = true
    override val wantsAudio = true
    override val drawsWithoutLyrics = true
    override val hiddenSettings = 7

    override val settings: List<LyricSetting> = listOf(
        LyricSetting.Number("density", "星星数量", 30f, 300f, 10f, 150f, ::formatInt),
        LyricSetting.Number("fontScale", "字号", 0.6f, 1.6f, 0.05f, 1f),
        LyricSetting.Number("hold", "字停留", 0f, 5f, 0.25f, 0.5f, ::formatSeconds, summary = "每个字唱完后继续停留的时间"),
        LyricSetting.Number("meteor", "流星数量", 0f, 3f, 0.25f, 1f, summary = "同屏最多几颗；带小数时按比例时有时无"),
        LyricSetting.Toggle("ring", "读字光罩", true, summary = "正在唱的字罩一圈柔光"),
        LyricSetting.Toggle("ringLatin", "英文不亮圈", true, summary = "英文字母和数字不罩光圈"),
        LyricSetting.Toggle("punctHide", "屏蔽标点", true, summary = "标点不显示，也不亮光圈"),
        LyricSetting.Toggle("pulse", "音乐呼吸", true, summary = "星星和字的辉光随响度起伏"),
    )

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) {
        val measurer = rememberTextMeasurer(cacheSize = 160)
        val cfg = remember(ctx.settings) { stardriftConfig(ctx.settings) }
        val state = remember(ctx.lines, ctx.seed) { StardriftState(ctx.seed) }
        Spacer(
            modifier.drawWithCache {
                val safe = ctx.display.safeRect(size)
                val skyUnit = 1.dp.toPx()
                val textUnit = ctx.display.textSp.sp.toPx() / STARDRIFT_BASE_FONT
                val fontSp = ctx.display.textSp * cfg.fontScale
                val fontPx = fontSp.sp.toPx()
                val style = TextStyle(fontSize = fontSp.sp, fontWeight = FontWeight.Medium)
                val sky = state.sky(size, stardriftStarCount(cfg.density, ctx.display.countScale), skyUnit)
                val line = ctx.currentLine?.let { measureDriftLine(measurer, it, style) }
                // 宿主副行排在安全区底部往上 14dp，字不落进那一条（按两行留）
                val subtitle = ctx.currentLine?.subtitle(ctx.subline).orEmpty()
                val subtitleBand = if (subtitle.isBlank()) 0f else 14.dp.toPx() + (ctx.display.textSp * SUBTITLE_BAND_LINES).sp.toPx()
                val area = driftArea(safe, fontPx, subtitleBand)
                val minDist = max(90f * textUnit, fontPx * 1.15f)
                val text = ctx.colors.text
                val accent = ctx.colors.accent
                val palette = arrayOf(text, accent, ctx.colors.accentAlt)
                // 星云的两档透明度按呼吸上限 1.2 先放大，画的时候再乘 pulse / 1.2
                val nebula = Brush.radialGradient(
                    0f to text.copy(alpha = 0.05f * PULSE_MAX),
                    0.5f to accent.copy(alpha = 0.035f * PULSE_MAX),
                    1f to Color.Transparent,
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = max(size.width, size.height) * 0.55f,
                )
                val ringRadius = fontPx * RING_SCALE
                // 原版的径向渐变从 0.05 倍半径起，色标按它折到 0..1
                val ringBrush = Brush.radialGradient(
                    0f to accent.copy(alpha = 0.12f),
                    RING_INNER to accent.copy(alpha = 0.12f),
                    ringStop(0.35f) to accent.copy(alpha = 0.08f),
                    ringStop(0.7f) to accent.copy(alpha = 0.035f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = ringRadius,
                )
                val ink = DriftInk(text, accent, ringBrush, ringRadius, textUnit, state.glows(accent))

                if (ctx.display.reduceMotion) {
                    val glyphs = line?.let { l ->
                        val spots = placeDriftGlyphs(l.chars.size, area, minDist, driftLineRandom(ctx.seed, l.index))
                        l.chars.mapIndexed { i, c -> DriftGlyph(c.glyph, c.timing.char, c.timing.start, c.timing.end, spots[i].x, spots[i].y) }
                    }.orEmpty()
                    val meteor = if (stardriftMeteorCap(cfg.meteor) > 0) frozenStardriftMeteor(ctx.seed, size.width, size.height, skyUnit) else null
                    val look = DriftLook().apply {
                        scale = 1f
                        alpha = 1f
                        primary = 1f
                        ring = 1f
                        glow = 18f
                    }
                    onDrawBehind {
                        drawRect(nebula, alpha = 1f / PULSE_MAX)
                        drawStars(sky, STILL_T, 1f, palette, state)
                        meteor?.let { drawMeteor(it, palette, state.headPath) }
                        for (g in glyphs) drawDriftGlyph(g, look, cfg, ink)
                    }
                } else {
                    onDrawBehind {
                        val ms = ctx.clock.ms
                        val t = ctx.clock.seconds
                        val audio = ctx.audio
                        val pulse = if (cfg.pulse && audio != null) 0.8f + 0.4f * min(1f, audio.power) else 1f
                        state.lyrics.advance(line, ms, ctx.paused.value, area, minDist, cfg)
                        state.meteors.advance(state.frameDt(t, ctx.clock.dt), cfg.meteor, size.width, size.height, skyUnit)

                        drawRect(nebula, alpha = pulse / PULSE_MAX)
                        drawStars(sky, t, pulse, palette, state)
                        for (m in state.meteors.meteors) drawMeteor(m, palette, state.headPath)
                        val time = ms / 1000f
                        val look = state.look
                        for (g in state.lyrics.glyphs) {
                            if (!driftLook(time - g.startS, cfg.appearS, g.durS, cfg.hold, cfg.fadeS, pulse, look)) continue
                            drawDriftGlyph(g, look, cfg, ink)
                        }
                    }
                }
            },
        )
    }
}

/** 一首歌一份的状态：屏上的字、流星、星海，以及每帧复用的 Path 和 Shadow。 */
private class StardriftState(private val seed: Long) {
    val lyrics = DriftLyrics<TextLayoutResult>(seed)
    val meteors = StardriftMeteors(seed)
    val look = DriftLook()
    val headPath = Path()
    val starPaths = Array(STAR_COLORS * ALPHA_LEVELS) { Path() }
    val starUsed = BooleanArray(STAR_COLORS * ALPHA_LEVELS)
    private var sky: StardriftSky? = null
    private var glows: DriftGlows? = null
    private var lastAnim = Float.NaN

    /** 这一帧该推进多少秒：同一帧被重画第二次时（动画时钟 [anim] 没走）给 0，流星不会走两遍。 */
    fun frameDt(anim: Float, dt: Float): Float {
        val step = if (anim != lastAnim) dt else 0f
        lastAnim = anim
        return step
    }

    /** 尺寸或星星数变了才重建星海（换句不重建）。 */
    fun sky(size: Size, count: Int, unit: Float): StardriftSky {
        sky?.let { if (it.count == count && it.width == size.width && it.height == size.height) return it }
        return StardriftSky(seed, count, size.width, size.height, unit).also { sky = it }
    }

    fun glows(color: Color): DriftGlows = glows?.takeIf { it.color == color } ?: DriftGlows(color).also { glows = it }
}

/** 字的辉光：模糊半径取整后缓存 Shadow，避免每帧每字新建。 */
private class DriftGlows(val color: Color) {
    private val cache = arrayOfNulls<Shadow>(MAX_GLOW_RADIUS + 1)

    fun at(radius: Float): Shadow {
        val r = radius.roundToInt().coerceAtMost(MAX_GLOW_RADIUS)
        if (r <= 0) return Shadow.None
        return cache[r] ?: Shadow(color, Offset.Zero, r.toFloat()).also { cache[r] = it }
    }
}

/** 画字用的配色、光罩和辉光。 */
private class DriftInk(
    val text: Color,
    val accent: Color,
    val ringBrush: Brush,
    val ringRadius: Float,
    /** 原模组 1px 折成的屏幕像素（按正文基准字号 ÷ 64）。 */
    val unit: Float,
    val glows: DriftGlows,
)

/** 量一句：每个可见字单独量一次字形（TextMeasurer 会缓存重复的字）。 */
private fun measureDriftLine(measurer: TextMeasurer, line: StageLine, style: TextStyle): DriftLine<TextLayoutResult> =
    DriftLine(line.index, stardriftTimings(line).map { DriftSource(measurer.measure(it.char, style), it) })

/**
 * 字的落点范围：安全区左右各收 8%、上下各收 14%（原模组按整屏取这个比例），至少收半个字，保证字不出安全区；
 * 底部再让出宿主副行占的 [subtitleBand]。
 */
private fun driftArea(safe: Rect, fontPx: Float, subtitleBand: Float = 0f): Rect {
    val half = fontPx * 0.6f
    val mx = max(safe.width * 0.08f, half)
    val my = max(safe.height * 0.14f, half)
    var left = safe.left + mx
    var right = safe.right - mx
    var top = safe.top + my
    var bottom = safe.bottom - max(my, subtitleBand + half)
    if (left > right) {
        left = safe.center.x
        right = safe.center.x
    }
    if (top > bottom) {
        top = safe.center.y
        bottom = safe.center.y
    }
    return Rect(left, top, right, bottom)
}

/** 星海：按颜色 × 亮度档把所有星合成几条 Path，一档一次 drawPath。 */
private fun DrawScope.drawStars(sky: StardriftSky, t: Float, pulse: Float, palette: Array<Color>, state: StardriftState) {
    val paths = state.starPaths
    val used = state.starUsed
    for (i in paths.indices) {
        if (used[i]) {
            paths[i].rewind()
            used[i] = false
        }
    }
    val bright = 0.8f + 0.2f * pulse
    for (i in 0 until sky.count) {
        val env = stardriftStarEnvelope(sky.life(i, t))
        if (env <= 0f) continue
        val breathe = 0.5f + 0.5f * sin(sky.speed[i] * t * 1.2f + sky.phase[i])
        val alpha = min(1f, (0.15f + 0.85f * breathe) * env * bright)
        val level = (alpha * ALPHA_LEVELS).roundToInt()
        if (level <= 0) continue
        val radius = sky.baseSize[i] * (0.6f + 0.8f * env) * (0.85f + 0.3f * breathe)
        val tint = sky.tint[i]
        val color = if (tint < 0.55f) 0 else if (tint < 0.85f) 1 else 2
        val slot = color * ALPHA_LEVELS + level - 1
        paths[slot].addSparkle(sky.x[i], sky.y[i], radius)
        used[slot] = true
    }
    for (i in paths.indices) {
        if (!used[i]) continue
        drawPath(paths[i], palette[i / ALPHA_LEVELS], alpha = (i % ALPHA_LEVELS + 1f) / ALPHA_LEVELS)
    }
}

/** 流星：头部亮、尾部透明的渐隐线，加一颗四角星头。 */
private fun DrawScope.drawMeteor(m: StardriftMeteor, palette: Array<Color>, head: Path) {
    val base = palette[if (m.tint < 0.5f) 0 else if (m.tint < 0.85f) 1 else 2]
    val speed = hypot(m.vx, m.vy).coerceAtLeast(1e-3f)
    val tail = Offset(m.x - m.vx / speed * m.len, m.y - m.vy / speed * m.len)
    val tip = Offset(m.x, m.y)
    val brush = Brush.linearGradient(0f to base.copy(alpha = 0.95f), 1f to base.copy(alpha = 0f), start = tip, end = tail)
    drawLine(brush, tail, tip, strokeWidth = m.width, cap = StrokeCap.Round, alpha = 0.9f)
    head.rewind()
    head.addSparkle(m.x, m.y, m.width * 2.6f)
    drawPath(head, base)
}

/** 一个字：先画光罩（拉丁字母可不画），再按缩放画带辉光的字。 */
private fun DrawScope.drawDriftGlyph(g: DriftGlyph<TextLayoutResult>, look: DriftLook, cfg: StardriftConfig, ink: DriftInk) {
    if (cfg.punctHide && g.punct) return
    val ring = look.ring
    if (cfg.ring && ring > 0.02f && !(cfg.ringLatin && g.latin)) {
        withTransform({
            translate(g.x, g.y)
            scale(ring, ring, Offset.Zero)
        }) {
            drawCircle(ink.ringBrush, ink.ringRadius, Offset.Zero, alpha = ring)
        }
    }
    val layout = g.glyph
    val color = lerp(ink.accent, ink.text, look.primary.coerceIn(0f, 1f))
    val shadow = ink.glows.at(cssBlurRadius(look.glow * ink.unit))
    val topLeft = Offset(g.x - layout.size.width / 2f, g.y - layout.size.height / 2f)
    withTransform({ scale(look.scale, look.scale, Offset(g.x, g.y)) }) {
        drawText(layout, color, topLeft, alpha = look.alpha.coerceIn(0f, 1f), shadow = shadow)
    }
}

/** 四角星（圆润星芒）：8 个顶点，尖角半径 [size]，凹角 0.45 倍，第一个尖朝上。 */
private fun Path.addSparkle(x: Float, y: Float, size: Float) {
    val inner = size * 0.45f
    for (i in 0 until 8) {
        val r = if (i % 2 == 0) size else inner
        val px = x + SPARKLE_COS[i] * r
        val py = y + SPARKLE_SIN[i] * r
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

private fun stardriftConfig(s: SettingValues) = StardriftConfig(
    density = s.number("density"),
    fontScale = s.number("fontScale"),
    hold = s.number("hold"),
    meteor = s.number("meteor"),
    ring = s.toggle("ring"),
    ringLatin = s.toggle("ringLatin"),
    punctHide = s.toggle("punctHide"),
    pulse = s.toggle("pulse"),
)

/** Canvas 的 shadowBlur（2σ）换成 Paint 阴影半径（σ ≈ 0.57735 × 半径 + 0.5）。 */
private fun cssBlurRadius(blur: Float): Float = ((blur / 2f - 0.5f) / 0.57735f).coerceAtLeast(0f)

private val SPARKLE_COS = FloatArray(8) { cos(Math.PI / 4 * it - Math.PI / 2).toFloat() }
private val SPARKLE_SIN = FloatArray(8) { sin(Math.PI / 4 * it - Math.PI / 2).toFloat() }

/** 星星颜色数（文字色、强调色、第二强调色）和亮度分档。 */
/** 副行（字号 0.55 倍、行高 1.35 倍）按两行留：0.55 × 1.35 × 2。 */
private const val SUBTITLE_BAND_LINES = 1.5f
private const val STAR_COLORS = 3
private const val ALPHA_LEVELS = 10

/** 呼吸的上限（0.8 + 0.4 × 响度）。 */
private const val PULSE_MAX = 1.2f

/** 光罩半径是字号的 1.45 倍，渐变从 0.05 倍半径开始。 */
private const val RING_SCALE = 1.45f
private const val RING_INNER = 0.05f

private fun ringStop(stop: Float): Float = RING_INNER + (1f - RING_INNER) * stop

private const val MAX_GLOW_RADIUS = 63

/** 静帧取星海的这一刻（秒），各星位处在不同的生命阶段。 */
private const val STILL_T = 12.5f
