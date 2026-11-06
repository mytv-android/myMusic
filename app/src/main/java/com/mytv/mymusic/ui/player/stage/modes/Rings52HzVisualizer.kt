package com.mytv.mymusic.ui.player.stage.modes

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.ui.player.stage.LyricSetting
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.LyricVisualizer
import com.mytv.mymusic.ui.player.stage.ModOrigin
import com.mytv.mymusic.ui.player.stage.StageChar
import com.mytv.mymusic.ui.player.stage.StageLine
import com.mytv.mymusic.ui.player.stage.charTimings
import com.mytv.mymusic.ui.player.stage.formatPercent
import com.mytv.mymusic.ui.player.stage.formatTimes
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 52Hz（Folia 官方模组 visualizer52hz 1.0.1，AGPL-3.0；原版用自带的 PixiJS，这里不移植它，全部用 Compose 重画）。
 *
 * 当前句整句出现在安全区中央：每个字按歌词文本的种子错位、旋转、缩放（宽字逐个，拉丁字母按词），底色是 30% 的文字色；
 * 唱到时点亮成带强调色光晕的一份（easeOutCubic，用时取字长并夹在 0.12–0.45 秒），点亮过程中鼓起最多 12%；整句 0.45 秒淡入淡出。
 * 每个拍点从整句字形的外轮廓（外扩 0.55 倍字号）发出一圈“年轮”，响度决定线宽和亮度，年轮在歌曲时间上往外走：
 * 速度是半对角线 ÷ 10 秒 × 扩散倍数，到屏幕边缘时已按距离淡没，所以一屏是这首歌的一段年轮记录。
 * 换句后旧句的年轮带着旧字形继续走（4 个槽位，同原版）。没有歌词、或者句间离下一句还有 4 秒以上时，
 * 照原版每 11 秒从一个随机“髓心”往外长。暂停时冻结，往回跳整份记录重来。
 *
 * 移植取舍：
 * - 拍点不做谱通量检测，用宿主的低频起拍计数；灵敏度折算成最小间隔和补拍间隔，2 秒没有拍点补一拍（见 [RingsBeatGate]）。
 *   响度照原版：0.6 × 响度 + 0.4 × 低频，除以缓慢回落的峰值，快起慢落；没有律动数据时取固定中值 0.5。
 * - 年轮：Android 13+ 用 AGSL（[RingsShader]）解析计算圆角矩形 / 多边形并集的距离场，一次 drawRect 画完，带原版的木纹歪斜和两圈之间的底色；
 *   原版的距离场来自字形掩膜，这里每个字（拉丁按词）用一个字身框近似，旋转不计。同时最多 [RING_CAP_SHADER] 圈只是保护，
 *   原版不限圈数（它的 4 个“槽位”是同时保留 4 句的字形，不是 4 圈）。
 * - 12 及以下（或着色器编译失败）：每圈用 Path.op 求“各矩形外扩后”的并集外轮廓再描边（髓心按射线取外扩轮廓），
 *   同时最多 [RING_CAP_FALLBACK] 圈，不画木纹和底色。
 * - 减少动态效果：整句全亮，照原版静态预览铺一份每 0.72 秒一拍的年轮记录，静止不动。
 * - 电视：同时存在的年轮数乘 0.6。
 */
object Rings52HzVisualizer : LyricVisualizer {
    override val id = "rings"
    override val label = "52Hz"
    override val origin = ModOrigin("visualizer52hz", "1.0.1", "Folia", "AGPL-3.0")
    override val order = 20
    override val wantsAudio = true
    override val drawsWithoutLyrics = true
    override val hostBackground = true
    override val hostSubtitles = true
    override val hiddenSettings = 0

    override val settings: List<LyricSetting> = listOf(
        LyricSetting.Number("reach", "年轮扩散", 0.4f, 2f, 0.1f, 1f, ::formatTimes, summary = "年轮往外走的速度；快慢都在屏幕边缘淡没"),
        LyricSetting.Number("beatSensitivity", "拍点灵敏度", 0.4f, 2.5f, 0.1f, 1f, ::formatTimes, summary = "越大越容易算作拍点，年轮越密"),
        LyricSetting.Number("sensitivity", "响度灵敏度", 0.2f, 3f, 0.1f, 1f, ::formatTimes, summary = "响度决定年轮的粗细和亮度"),
        LyricSetting.Number("opacity", "年轮浓度", 0.1f, 1f, 0.05f, 0.8f, ::formatPercent),
        LyricSetting.Number("fontScale", "字号", 0.6f, 1.6f, 0.05f, 1f, ::formatTimes),
    )

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) {
        val measurer = rememberTextMeasurer(cacheSize = 128)
        val stage = remember(ctx.lines, ctx.seed) { RingsStage() }
        val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RingsShader.createOrNull() else null }
        Spacer(
            modifier.drawWithCache {
                val safe = ctx.display.safeRect(size)
                stage.prepare(this, measurer, ctx, ctx.currentLine, safe, size, ctx.settings.number("fontScale"))
                onDrawBehind { stage.draw(this, ctx, shader, safe) }
            },
        )
    }
}

/** 一个字的绘制数据：位置、量好的字形、逐字时间（空格没有）。 */
private class RingsGlyphDraw(val glyph: RingsGlyph, val text: TextLayoutResult, val timing: StageChar?)

/** 屏幕上的一句：排版、字形、年轮源形状；出现、隐去的动画时钟（秒），NaN 表示下一帧再记。 */
private class RingsShownLine(val index: Int, val geometry: Any, val glyphs: List<RingsGlyphDraw>, val size: Float, val shape: RingShape) {
    var appearAt = Float.NaN
    var hideAt = Float.NaN
}

/**
 * 模式的状态：一首歌一份。排版在 drawWithCache 的缓存块里按句做，年轮记录在绘制阶段按歌曲时间推进。
 */
private class RingsStage {
    private val record = RingsRecord()
    private val energy = RingsEnergy()
    private val gate = RingsBeatGate()
    private val uniforms = RingUniforms()
    private val pithShapes = LinkedHashMap<String, RingShape>()
    private var current: RingsShownLine? = null
    private var fading: RingsShownLine? = null
    private var stageSize = Size.Zero
    private var lastTime = Double.NaN
    private var lastKick = -1
    private var nowEnergy = NO_AUDIO_ENERGY
    private val pathA = Path()
    private val pathB = Path()
    private val pathPiece = Path()
    private val outlineBuffer = FloatArray(OFFSET_DIRECTIONS * 2)

    fun prepare(density: Density, measurer: TextMeasurer, ctx: LyricStageContext, line: StageLine?, safe: Rect, size: Size, fontScale: Float) {
        if (size != stageSize) {
            // 尺寸变了：形状全部作废，记录从头来
            stageSize = size
            record.clear()
            pithShapes.clear()
            lastTime = Double.NaN
            fading = null
        }
        val baseSize = with(density) { (ctx.display.textSp * fontScale).sp.toPx() }
        val geometry = listOf(safe, baseSize)
        val shown = current
        if (shown != null && shown.index == line?.index && shown.geometry == geometry) return
        val next = line?.takeIf { it.text.isNotBlank() }?.let { buildLine(density, measurer, it, safe, baseSize, geometry) }
        if (shown != null && next != null && shown.index == next.index) {
            // 同一句换了字号或安全区：字形跟着变，旧形状的年轮作废
            next.appearAt = shown.appearAt
            record.clear()
            lastTime = Double.NaN
        } else if (shown != null) {
            fading = shown.also { it.hideAt = Float.NaN }
        }
        current = next
    }

    private fun buildLine(density: Density, measurer: TextMeasurer, line: StageLine, safe: Rect, baseSize: Float, geometry: Any): RingsShownLine {
        val baseStyle = TextStyle(fontSize = with(density) { baseSize.toSp() }, fontWeight = FontWeight.Medium)
        val advances = HashMap<String, Float>()
        val layout = layoutRingsLine(
            line.text,
            advanceAtBase = { char ->
                advances.getOrPut(char) {
                    val width = measurer.measure(char, baseStyle).multiParagraph.intrinsics.maxIntrinsicWidth
                    if (width > 0f) width else baseSize * 0.28f
                }
            },
            baseSize = baseSize,
            centerX = safe.center.x,
            centerY = safe.center.y,
            maxWidth = safe.width * 0.8f,
        )
        val style = baseStyle.copy(fontSize = with(density) { layout.size.toSp() })
        val measured = HashMap<String, TextLayoutResult>()
        val timings = line.charTimings(spread = 0.85f)
        val glyphs = layout.glyphs.map { g ->
            RingsGlyphDraw(g, measured.getOrPut(g.char) { measurer.measure(g.char, style) }, timings.getOrNull(g.charIndex))
        }
        val rects = ringRects(layout)
        val shape = rectShape(mergeRingRects(rects, LINE_ELEMENT_CAP), mergeRingRects(rects, MAX_COARSE_RECTS), layout.size)
        return RingsShownLine(line.index, geometry, glyphs, layout.size, shape)
    }

    fun draw(scope: DrawScope, ctx: LyricStageContext, shader: RingsShader?, safe: Rect) = with(scope) { drawFrame(ctx, shader, safe) }

    private fun DrawScope.drawFrame(ctx: LyricStageContext, shader: RingsShader?, safe: Rect) {
        val ms = ctx.clock.ms
        val anim = ctx.clock.seconds
        val time = ms / 1000.0
        val still = ctx.display.reduceMotion
        val settings = ctx.settings
        val halfDiagonal = hypot(size.width, size.height) / 2f
        val speed = halfDiagonal / 10f * settings.number("reach")
        val baseCap = if (shader != null) RING_CAP_SHADER else RING_CAP_FALLBACK
        val cap = (baseCap * ctx.display.countScale).roundToInt().coerceAtLeast(2)
        val source = sourceKey(ctx, ms)

        if (still) {
            record.clear()
            record.switchSource(source, source?.let { shapeFor(it, ctx, safe) }, time, always = true)
            record.seedStatic(time, speed, halfDiagonal, cap)
            nowEnergy = staticEnergy(Math.floorMod(floor(time * HISTORY_RATE).toLong(), HISTORY_SIZE.toLong()).toInt())
            fading = null
        } else {
            advanceRecord(ctx, time, source, safe, cap, speed, halfDiagonal)
        }

        val opacity = settings.number("opacity")
        val near = ctx.colors.accent
        val far = ctx.colors.text
        if (shader != null) {
            // 一圈都没有时只剩“现在”那一项，画不出东西，省掉整屏的着色器
            if (record.rings.isNotEmpty()) {
                packRingUniforms(record, time, speed, density, nowEnergy, uniforms)
                shader.update(uniforms, halfDiagonal, opacity, density, near, far)
                drawRect(shader.brush)
            }
        } else {
            drawFallbackRings(time, speed, halfDiagonal, opacity, near, far)
        }

        fading?.let { line ->
            if (line.hideAt.isNaN()) line.hideAt = anim
            val alpha = 1f - (anim - line.hideAt) / LINE_FADE_S
            if (alpha <= 0f) fading = null else drawLine(line, ms, alpha, still = false, ctx)
        }
        current?.let { line ->
            if (line.appearAt.isNaN()) line.appearAt = anim
            val alpha = if (still) 1f else ((anim - line.appearAt) / LINE_FADE_S).coerceIn(0f, 1f)
            drawLine(line, ms, alpha, still, ctx)
        }
    }

    /** 推进记录（照 stage.mjs 的 recordAudio + tick）：判断跳转、跟响度、过拍点、换源、发年轮、清理。 */
    private fun advanceRecord(ctx: LyricStageContext, time: Double, source: String?, safe: Rect, cap: Int, speed: Float, fade: Float) {
        val audio = ctx.audio
        val settings = ctx.settings
        val songDt = time - lastTime
        val jumped = lastTime.isNaN() || songDt < -BACK_JUMP_S || abs(songDt - ctx.clock.dt) > SEEK_JUMP_S
        var beat = false
        if (jumped) {
            record.reset(time)
            energy.reset()
            gate.reset()
            current?.appearAt = Float.NaN
            fading = null
        } else if (songDt > 0.0) {
            nowEnergy = if (audio != null) {
                energy.step(audio.power, audio.bass, settings.number("sensitivity"), songDt.toFloat())
            } else {
                NO_AUDIO_ENERGY
            }
            val kicks = if (audio != null && lastKick >= 0) (audio.kickCount - lastKick).coerceAtLeast(0) else 0
            beat = gate.step(time, kicks, settings.number("beatSensitivity"))
        }
        lastKick = audio?.kickCount ?: -1
        lastTime = time
        if (source != record.openKey) record.switchSource(source, source?.let { shapeFor(it, ctx, safe) }, time)
        if (beat) record.addRing(time, nowEnergy, cap)
        record.prune(time, speed, fade)
    }

    /** 这一刻谁在发年轮：当前句；没有歌词、或句间离下一句还远时是这 11 秒段的髓心；否则不发。 */
    private fun sourceKey(ctx: LyricStageContext, ms: Long): String? {
        current?.let { return "line:${it.index}" }
        val pith = "pith:${floor(ms / 1000.0 / PITH_PERIOD_S).toLong()}"
        if (ctx.lines.isEmpty()) return pith
        if (ctx.lineIndex.value >= 0) return null
        var next: StageLine? = null
        for (line in ctx.lines) if (line.startMs > ms) { next = line; break }
        return if (next == null || next.startMs - ms > INTERLUDE_PITH_MS) pith else null
    }

    private fun shapeFor(key: String, ctx: LyricStageContext, safe: Rect): RingShape? {
        if (key.startsWith("line:")) return current?.shape
        return pithShapes.getOrPut(key) {
            if (pithShapes.size >= MAX_PITH_CACHE) pithShapes.remove(pithShapes.keys.first())
            val seed = pithSeed(ctx.seed, key.removePrefix("pith:").toLong())
            pithShape(pithOutline(seed, safe), pithCenter(seed, safe))
        }
    }

    private fun DrawScope.drawLine(line: RingsShownLine, ms: Long, alpha: Float, still: Boolean, ctx: LyricStageContext) {
        val text = ctx.colors.text
        val glow = Shadow(ctx.colors.accent, Offset.Zero, line.size * GLOW_BLUR)
        for (g in line.glyphs) {
            val progress = if (still || g.timing == null) 1f else ringsLightProgress(ms, g.timing.startMs, g.timing.endMs)
            val lit = easeOutCubic(progress)
            val scale = g.glyph.scale * (1f + if (still) 0f else ringsSwell(progress))
            val half = Offset(-g.text.size.width / 2f, -g.text.size.height / 2f)
            withTransform({
                translate(g.glyph.x, g.glyph.y)
                rotate(Math.toDegrees(g.glyph.rotation.toDouble()).toFloat(), Offset.Zero)
                scale(scale, scale, Offset.Zero)
            }) {
                drawText(g.text, text, half, alpha = DIM_ALPHA * alpha, shadow = Shadow.None)
                if (lit > 0f) drawText(g.text, text, half, alpha = lit * alpha, shadow = glow)
            }
        }
    }

    /** 兜底：每圈求一次外轮廓再描边，不画木纹和底色。 */
    private fun DrawScope.drawFallbackRings(time: Double, speed: Float, fade: Float, opacity: Float, near: Color, far: Color) {
        val dp = density
        for (ring in record.rings) {
            val slot = record.slots[ring.slot]
            val shape = slot.shape ?: continue
            val radius = ((time - ring.birth) * speed).toFloat()
            if (radius < 0f) continue
            val weight = ringWindow(ring.birth, slot.start, slot.end) * ringEvictFade(time, ring.evictedAt)
            val surface = smooth(0.0, 24.0 * dp, radius.toDouble()).toFloat()
            val alpha = ringAlpha(ringStrength(ring.energy) * weight * ringFade(radius, fade) * surface, opacity)
            if (alpha < 0.004f) continue
            val path = outlinePath(shape, radius) ?: continue
            val color = lerp(near, far, (radius / fade * 1.6f).coerceIn(0f, 1f))
            drawPath(path, color, alpha, Stroke(width = 2f * ringHalfWidthDp(ring.energy) * dp))
        }
    }

    /** 形状外扩 [radius] 后的外轮廓：矩形逐个外扩求并集（近处用细形状，远处用按行合并的），髓心按射线取。 */
    private fun outlinePath(shape: RingShape, radius: Float): Path? {
        val outline = shape.outline
        val center = shape.center
        if (outline != null && center != null) {
            offsetOutline(outline, center[0], center[1], radius, OFFSET_DIRECTIONS, outlineBuffer)
            pathA.reset()
            pathA.moveTo(outlineBuffer[0], outlineBuffer[1])
            for (i in 1 until OFFSET_DIRECTIONS) pathA.lineTo(outlineBuffer[i * 2], outlineBuffer[i * 2 + 1])
            pathA.close()
            return pathA
        }
        val elements = if (radius < shape.gap * 2f) shape.fine else shape.coarse
        val count = elements.size / 4
        if (count == 0) return null
        val inflate = shape.gap + radius
        var acc = pathA
        var out = pathB
        acc.reset()
        for (i in 0 until count) {
            val hw = elements[i * 4 + 2]
            val hh = elements[i * 4 + 3]
            val corner = CornerRadius(min(shape.corner, min(hw, hh)) + inflate)
            val rect = RoundRect(
                elements[i * 4] - hw - inflate, elements[i * 4 + 1] - hh - inflate,
                elements[i * 4] + hw + inflate, elements[i * 4 + 1] + hh + inflate, corner,
            )
            if (i == 0) {
                acc.addRoundRect(rect)
                continue
            }
            pathPiece.reset()
            pathPiece.addRoundRect(rect)
            out.reset()
            out.op(acc, pathPiece, PathOperation.Union)
            val swap = acc
            acc = out
            out = swap
        }
        return acc
    }
}

private const val NO_AUDIO_ENERGY = 0.5f
private const val LINE_FADE_S = 0.45f
private const val DIM_ALPHA = 0.3f
/** 原版 shadowBlur 0.45 倍字号；Canvas 的 shadowBlur 约是 Android 阴影半径的 1.15 倍。 */
private const val GLOW_BLUR = 0.39f
private const val SEEK_JUMP_S = 0.5
/** 宿主进度每 100ms 校准，外推最多回拉 120ms；往回超过这个才算跳转。 */
private const val BACK_JUMP_S = 0.25
private const val PITH_PERIOD_S = 11.0
private const val INTERLUDE_PITH_MS = 4_000L
private const val MAX_PITH_CACHE = 6
private const val MAX_COARSE_RECTS = 3
private const val OFFSET_DIRECTIONS = 96
