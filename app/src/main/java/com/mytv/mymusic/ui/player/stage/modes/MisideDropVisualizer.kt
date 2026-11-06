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
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.ui.player.stage.LyricSetting
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.LyricVisualizer
import com.mytv.mymusic.ui.player.stage.ModOrigin
import com.mytv.mymusic.ui.player.stage.SettingValues
import com.mytv.mymusic.ui.player.stage.StageLine
import com.mytv.mymusic.ui.player.stage.StageRandom
import com.mytv.mymusic.ui.player.stage.charTimings
import com.mytv.mymusic.ui.player.stage.fitText
import com.mytv.mymusic.ui.player.stage.formatInt
import com.mytv.mymusic.ui.player.stage.formatSeconds
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MiSide 掉落（Folia 模组 miside-drop 2.5.3，作者 baiyangmuzz，MIT）：灵感来自游戏 MiSide 的对话字幕。
 * 当前句在安全区中央逐字浮现（打字机，按逐字时间），白字带 7 层明度递减的灰阶挤出和一层深投影，读成实体厚度；
 * 整句唱完后整行交给粒子：每个字带随机的上抛、横向散开和三轴翻滚，按重力下落、在地面线（安全区 82% 处）回弹、停住，
 * 落地平躺但保留平面旋转，停留 [MisideConfig.settleHold] 秒后 1.5 秒淡出；地上最多留 keepRows 句，超出的整行回收
 * （原模组不会把旧行往上推，这里照做）。逐字模式下每个字唱到就单独掉（原模组“唱到就逐字崩落”）。
 * 换句时还在空中的字以至少 650px/s 急坠，免得上一句悬在半空和下一句同时挂着。
 *
 * 移植取舍：
 * - 数值照原模组的 px 写，按“正文基准字号 ÷ 56”折成屏幕像素：重力、速度、挤出偏移、投影都跟着正文字号走，
 *   字号设置只改字的大小（原模组也是字号变了挤出仍是 1–7px）。
 * - 翻转：原模组的父元素没有 perspective，rotateX/rotateY 是正交投影，等价于一个 2×2 线性变换（[misideTilt]），
 *   这里直接把它写进画布矩阵，挤出层跟着字一起翻；比 graphicsLayer 的相机透视更贴近原版，也不用每个字一个图层。
 * - 挤出：7 次偏移 drawText，灰阶照原版 168→64，再乘上正文颜色，让灰带一点主题色调；最深一层顺带画深投影（Paint 阴影，所有版本可用）。
 *   字形每句只量一次（整句排版取位置，每个字单独量一次字形，量过的字 TextMeasurer 会缓存）。
 * - 淡出的字用 saveLayer 整体降透明度，不然半透明的字面会透出底下的挤出层。
 * - 物理按宿主的帧间隔积分（暂停时为 0），落地停留也按播放时间算，暂停期间不会偷偷淡掉；
 *   原模组用墙钟。跳转（前后跳超过 1.5 秒）清空地面和粒子，从当前句重新开始；逐字模式跳进一句中间时，已唱过的字不再补掉。
 * - 原模组 14 项设置，面板放 7 项；掉落延迟 0、提前量 0、打字机间隔 0、落地淡出开、淡出 1.5 秒、翻转上限 16°、
 *   地面高度 82% 按原默认值写死。
 * - 减少动态效果：当前句整句显示在打字区，地面上是离线模拟到落定的上一句（和再上一句，淡一些），没有运动。
 */
object MisideDropVisualizer : LyricVisualizer {
    override val id = "miside"
    override val label = "MiSide 掉落"
    override val origin = ModOrigin("miside-drop", "2.5.3", "baiyangmuzz", "MIT")
    override val order = 30
    override val hostBackground = true
    override val hostSubtitles = true
    override val wantsAudio = false
    override val drawsWithoutLyrics = false
    override val hiddenSettings = 7

    override val settings: List<LyricSetting> = listOf(
        LyricSetting.Choice(
            "dropModeV2", "掉落方式", listOf("sentence" to "整句", "word" to "逐字"), "sentence",
            summary = "整句：逐字打出来，唱完整行坠落；逐字：唱到就单独掉",
        ),
        LyricSetting.Number("fontSizeV2", "字号", 24f, 120f, 4f, MISIDE_BASE_FONT, ::formatInt),
        LyricSetting.Number("gravity", "重力", 100f, 6000f, 100f, 1400f, ::formatInt, summary = "越小落得越慢"),
        LyricSetting.Number("elasticity", "回弹", 0f, 0.85f, 0.05f, 0.34f, ::formatMisideDecimal, summary = "触地反弹的力度"),
        LyricSetting.Number("settleHold", "落地停留", 0f, 10f, 0.5f, 2.5f, ::formatSeconds, summary = "落地后多久开始淡出"),
        LyricSetting.Number("keepRows", "地上保留几行", 0f, 8f, 1f, 2f, ::formatInt, summary = "超出的整行回收"),
        LyricSetting.Toggle("glowBoost", "立体字发光", true, summary = "7 层灰阶挤出加深投影；关掉只剩柔和投影"),
    )

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) {
        val measurer = rememberTextMeasurer(cacheSize = 160)
        val cfg = remember(ctx.settings) { misideConfig(ctx.settings) }
        val state = remember(ctx.lines, ctx.seed) { MisideStageState(ctx.seed) }
        Spacer(
            modifier.drawWithCache {
                val safe = ctx.display.safeRect(size)
                val unit = ctx.display.textSp.sp.toPx() / MISIDE_BASE_FONT
                val style = misideStyle(ctx.display.textSp * cfg.fontSize / MISIDE_BASE_FONT)
                val groundY = safe.top + safe.height * MISIDE_GROUND_RATIO
                val line = ctx.currentLine?.takeIf { it.text.isNotBlank() }?.let { layoutMisideLine(measurer, it, style, safe) }
                val ink = MisideInk(ctx.colors.text, unit, cfg.glowBoost)
                val groundLeft = safe.left + safe.width * GROUND_INSET
                val groundRight = safe.right - safe.width * GROUND_INSET
                val groundColor = ctx.colors.text.copy(alpha = 0.28f)
                val groundBrush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.18f to groundColor,
                    0.82f to groundColor,
                    1f to Color.Transparent,
                    startX = groundLeft,
                    endX = groundRight,
                )
                val groundSize = Size((groundRight - groundLeft).coerceAtLeast(0f), 1.dp.toPx())
                val groundAt = Offset(groundLeft, groundY)

                if (ctx.display.reduceMotion) {
                    val rows = misideStaticRows(ctx, measurer, style, safe, cfg, unit, groundY, size.height)
                    onDrawBehind {
                        drawRect(groundBrush, groundAt, groundSize, alpha = 0.5f)
                        for (row in rows) for (p in row.particles) drawParticle(p, ink, state, row.alpha)
                        line?.chars?.forEach { drawExtruded(it.glyph, it.x, it.y, ink) }
                    }
                } else {
                    // 尺寸变了（转屏、分栏）：旧坐标作废，地面和粒子清空
                    if (state.size != size) {
                        if (state.size != Size.Unspecified) state.drop.reset()
                        state.size = size
                    }
                    val stageHeight = size.height
                    onDrawBehind {
                        val ms = ctx.clock.ms
                        state.drop.advance(line, ms, state.frameDt(ctx), cfg, groundY, stageHeight, unit)
                        drawRect(groundBrush, groundAt, groundSize, alpha = 0.5f)
                        for (p in state.drop.field.particles) drawParticle(p, ink, state, 1f)
                        val shown = state.drop.shown ?: return@onDrawBehind
                        for (i in shown.chars.indices) {
                            if (!state.drop.typed(i, ms)) continue
                            val c = shown.chars[i]
                            drawExtruded(c.glyph, c.x, c.y, ink)
                        }
                    }
                }
            },
        )
    }
}

/** 一首歌一份的状态：控制器、上次的舞台尺寸，以及绘制时复用的矩阵和画笔。 */
private class MisideStageState(seed: Long) {
    val drop = MisideDrop<MisideGlyph>(seed)
    var size = Size.Unspecified
    val matrix = Matrix()
    val tilt = FloatArray(4)
    val layerPaint = Paint()
    private var lastAnim = Float.NaN

    /** 这一帧该推进多少秒：同一帧被重画第二次时（动画时钟没走）给 0，物理不会走两遍。 */
    fun frameDt(ctx: LyricStageContext): Float {
        val anim = ctx.clock.seconds
        val dt = if (anim != lastAnim) ctx.clock.dt else 0f
        lastAnim = anim
        return dt
    }
}

/** 一个字的字形，[dy] 是字形顶边相对字框顶边的偏移（按基线对齐整句排版）。 */
private class MisideGlyph(val layout: TextLayoutResult, val dy: Float)

/** 静帧里落在地上的一句。 */
private class MisideStaticRow(val particles: List<MisideParticle<MisideGlyph>>, val alpha: Float)

/** 字面色、7 层挤出色和两种投影；投影偏移、模糊都按原模组 px 乘 [unit]。 */
private class MisideInk(val face: Color, val unit: Float, val boost: Boolean) {
    val layers = Array(MISIDE_EXTRUDE_LAYERS) { shade(face, misideExtrudeGray(it)) }
    val offsets = FloatArray(MISIDE_EXTRUDE_LAYERS) { misideExtrudeOffset(it, unit) }

    /** 开挤出时最底下那层（偏 7px）带的深投影：原版是相对字面 (10, 12) 模糊 24 的 88% 黑。 */
    val deep = Shadow(Color.Black.copy(alpha = 0.88f), Offset(3f * unit, 5f * unit), cssBlurRadius(24f * unit))

    /** 关挤出时字面带的柔和投影：(0, 6) 模糊 22 的 85% 黑。 */
    val soft = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 6f * unit), cssBlurRadius(22f * unit))

    /** 挤出和投影能伸出字框多远，淡出时 saveLayer 的边界用。 */
    val reach = (if (boost) 7f + 12f + 24f else 6f + 22f) * unit

    private fun shade(face: Color, gray: Int): Color {
        val k = gray / 255f
        val kb = min(255, gray + 5) / 255f
        return Color(face.red * k, face.green * k, face.blue * kb, 1f)
    }
}

/** 字框左上角在 ([x], [y]) 的一个字：7 层挤出从深到浅，再画字面。没投影的层必须显式给 Shadow.None，量好的字形会记住上次的阴影。 */
private fun DrawScope.drawExtruded(glyph: MisideGlyph, x: Float, y: Float, ink: MisideInk) {
    val top = y + glyph.dy
    if (ink.boost) {
        for (i in MISIDE_EXTRUDE_LAYERS - 1 downTo 0) {
            val o = ink.offsets[i]
            val shadow = if (i == MISIDE_EXTRUDE_LAYERS - 1) ink.deep else Shadow.None
            drawText(glyph.layout, ink.layers[i], Offset(x + o, top + o), shadow = shadow)
        }
        drawText(glyph.layout, ink.face, Offset(x, top), shadow = Shadow.None)
    } else {
        drawText(glyph.layout, ink.face, Offset(x, top), shadow = ink.soft)
    }
}

/** 一个粒子：绕字框中心按俯仰 / 偏航 / 平面旋转的正交投影变换；淡出时整体降透明度。 */
private fun DrawScope.drawParticle(p: MisideParticle<MisideGlyph>, ink: MisideInk, state: MisideStageState, rowAlpha: Float) {
    val alpha = p.fade.coerceIn(0f, 1f) * rowAlpha
    if (alpha <= 0f) return
    val halfW = p.w / 2f
    val halfH = p.h / 2f
    misideTilt(p.rx, p.ry, p.rz, state.tilt)
    val m = state.matrix
    m.reset()
    m.values[Matrix.ScaleX] = state.tilt[0]
    m.values[Matrix.SkewX] = state.tilt[1]
    m.values[Matrix.SkewY] = state.tilt[2]
    m.values[Matrix.ScaleY] = state.tilt[3]
    withTransform({
        translate(p.x + halfW, p.y + halfH)
        transform(m)
    }) {
        val layered = alpha < 1f
        if (layered) {
            state.layerPaint.alpha = alpha
            val r = ink.reach
            drawContext.canvas.saveLayer(Rect(-halfW - r, -halfH - r, halfW + r, halfH + r), state.layerPaint)
        }
        drawExtruded(p.glyph, -halfW, -halfH, ink)
        if (layered) drawContext.canvas.restore()
    }
}

/**
 * 整句排在安全区中央（最宽 84%，最多 3 行，放不下缩字号），取每个字的字框；空白不出粒子。
 * 没有逐字时间时按句长均分（原模组按整行均分，不留尾巴）。
 */
private fun layoutMisideLine(measurer: TextMeasurer, line: StageLine, style: TextStyle, safe: Rect): MisideLine<MisideGlyph> {
    val layout = fitText(measurer, line.text, style, (safe.width * LINE_MAX_WIDTH).toInt(), maxLines = 3)
    val fitted = layout.layoutInput.style.copy(textAlign = TextAlign.Unspecified)
    val left = safe.center.x - layout.size.width / 2f
    val top = safe.center.y - layout.size.height / 2f
    val chars = ArrayList<MisideChar<MisideGlyph>>()
    var offset = 0
    for (char in line.charTimings(spread = 1f)) {
        val at = offset
        offset += char.text.length
        if (char.space) continue
        val box = layout.getBoundingBox(at)
        if (box.width <= 0f || box.height <= 0f) continue
        val glyph = measurer.measure(char.text, fitted)
        val baseline = layout.getLineBaseline(layout.getLineForOffset(at))
        val dy = baseline - box.top - glyph.firstBaseline
        chars += MisideChar(MisideGlyph(glyph, dy), char.startMs, left + box.left, top + box.top, box.width, box.height)
    }
    return MisideLine(line.index, line.endMs, chars)
}

/**
 * 静帧的地面：上一句（keepRows ≥ 2 时再加上上一句，淡一些）按原模组的掉法离线模拟到落定。
 * 句间时取最近唱完的那句。
 */
private fun misideStaticRows(
    ctx: LyricStageContext,
    measurer: TextMeasurer,
    style: TextStyle,
    safe: Rect,
    cfg: MisideConfig,
    unit: Float,
    groundY: Float,
    stageHeight: Float,
): List<MisideStaticRow> {
    val count = min(cfg.keepRows, STATIC_ROWS)
    if (count <= 0 || ctx.lines.isEmpty()) return emptyList()
    val current = ctx.lineIndex.value
    val newest = if (current >= 0) current - 1 else ctx.lines.indexOfLast { it.endMs <= ctx.clock.ms }
    val rows = ArrayList<MisideStaticRow>(count)
    for (k in count - 1 downTo 0) {
        val source = ctx.lines.getOrNull(newest - k)?.takeIf { it.text.isNotBlank() } ?: continue
        val line = layoutMisideLine(measurer, source, style, safe)
        val field = MisideField<MisideGlyph>()
        val random = StageRandom(ctx.seed xor (source.index.toLong() * STATIC_SEED_MIX))
        line.chars.forEachIndexed { n, c ->
            field.particles += spawnMisideParticle(c.glyph, source.index, c.x, c.y, c.w, c.h, cfg, unit, n * MISIDE_STAGGER_S, random)
        }
        field.settleAll(groundY, stageHeight, unit)
        rows += MisideStaticRow(field.particles.toList(), if (k == 0) 1f else OLDER_ROW_ALPHA)
    }
    return rows
}

private fun misideConfig(s: SettingValues) = MisideConfig(
    word = s.choice("dropModeV2") == "word",
    fontSize = s.number("fontSizeV2"),
    gravity = s.number("gravity"),
    elasticity = s.number("elasticity"),
    settleHold = s.number("settleHold"),
    keepRows = s.number("keepRows").roundToInt(),
    glowBoost = s.toggle("glowBoost"),
)

/** 原模组的字：粗体 700、行高 1.35、字距 0.04em。 */
private fun misideStyle(fontSp: Float) = TextStyle(
    fontSize = fontSp.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.04.em,
    lineHeight = 1.35.em,
)

/** CSS 的模糊值（2σ）换成 Paint 阴影半径（σ ≈ 0.57735 × 半径 + 0.5）。 */
private fun cssBlurRadius(blur: Float): Float = ((blur / 2f - 0.5f) / 0.57735f).coerceAtLeast(0f)

/** 打字区最宽占安全区的比例（原模组 lineBox 的 max-width）。 */
private const val LINE_MAX_WIDTH = 0.84f

/** 地面线左右各收 8%。 */
private const val GROUND_INSET = 0.08f

/** 静帧地上最多画几句。 */
private const val STATIC_ROWS = 2
private const val OLDER_ROW_ALPHA = 0.4f
private const val STATIC_SEED_MIX = -0x61C8864680B583EBL
