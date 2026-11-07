package com.mytv.mymusic.ui.player.stage.modes

import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.data.LyricSubline
import com.mytv.mymusic.ui.player.stage.LyricSetting
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.LyricVisualizer
import com.mytv.mymusic.ui.player.stage.ModOrigin
import com.mytv.mymusic.ui.player.stage.StageColors
import com.mytv.mymusic.ui.player.stage.StageLine
import com.mytv.mymusic.ui.player.stage.formatInt
import com.mytv.mymusic.ui.player.stage.formatSeconds
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.Matrix as NativeMatrix
import android.graphics.Paint as NativePaint

/**
 * 跳一跳：移植自 Folia 歌词可视化模组 visualizer-jump 1.2.0（作者 tumuyan，AGPL-3.0，
 * 源码 https://cnb.cool/ai-zoomer/fork-from/folia 提交 f77181ca），用 Compose Canvas 原生重写，不用 WebView。
 *
 * 效果（数值、时序、配色照原版）：
 * - 一句歌词切成几块（中文按“每块字数”合字，拉丁按词，标点黏在前一块），每块是一根等轴测方柱，字贴在柱子的顶面 / 正面 / 侧面
 *   或悬在上方；柱子沿路一行一行折成 Z 字（偶数行往右上、奇数行往左上），柱长跟着字宽走。
 * - 小人（带高光的胶囊棋子）在每块第一个字开唱的那一刻落到那根柱子上：起跳是落地往回推一个滞空，抛物线高度 = 跳跃高度；
 *   起跳蹲、落地压、空中拉长；柱子被踩下去，离开时弹簧似的弹回来；落地踩出一圈椭圆波纹和六粒尘土，停够 0.36 秒的起跳也有一圈小的。
 * - 一行唱完跳到下一行首块上等开口；两行之间空得久就在行末立一根歇脚的圆柱，他唱完跳上去歇着。第一行开口前原地一跳登场。
 * - 镜头跟着时间表的站点走、跨行时沿“末块 → 拐角 → 下一行首块”的折线拐过去，并往路前进的那一侧多看一格；后面的行从地里逐块升起。
 * - 跳过的那几段弧线留下渐淡的点迹与落脚的小椭圆。
 * - 译文浮在柱子后面、钉在安全区上沿：按俯角压扁、往右上侧倾，靠上的行再收一点（原版“逐字透视”），带主题色光晕与底色描边。
 *
 * 移植取舍：
 * - 空行不进路（原版空行会让小人凭空消失、拐弯方向也会错一格），路只由立得起柱子的句子组成，换句节奏不变。
 * - 字贴侧面时柱高只放宽一次（原版在量字号那一处又放宽了一遍，两处不一致）。
 * - 译文的逐字 matrix3d 换成每行一个仿射矩阵（压扁 + 侧倾 + 按行收缩），丢掉的真透视在一个字高里只差百分之二三；
 *   原版“行末立了歇脚柱的句子，译文要等这句唱完才亮”看上去是笔误，这里统一在这一句开口时淡入。
 * - 原版取 Folia 主题的 secondaryColor 当柱面色，播放页没有这个色，用 surface 与 text 的中间色再掺一点主题色代替。
 * - 原版的格子地板（26 像素）在又宽又矮的舞台（桌面姿态）上会把小人推出画面，这里按预算上下比例摆，并保证头顶装得下人和跳跃高度；
 *   整条路按安全区取景，安全区外清掉、边上渐隐（离屏层 + DstIn）。
 * - 减少动态效果（也是面板缩略图）：只摆当前句，柱子全部立好，小人站在唱到的那一块（没有逐字时间就站第一块），
 *   按整行外接框取景（不太损失字号时给译文让出上沿那一条）；没有跳、波纹、轨迹。
 * - 柱子上的字和译文用 android.graphics.Paint 画（贴面要整块仿射变换），字体是系统无衬线（API 28+ 取 600 / 500 字重，低版本粗体 / 常规）；
 *   分词用 java.text.BreakIterator（安卓上是 ICU，等价于原版宿主的 Intl.Segmenter）。
 * - 译文字号原版只看柱顶的字，在手机和缩略图上会大到一屏两三个字，这里再按正文字号的 1.3 倍、安全区高的 12% 封顶。
 * - 原模组 25 项设置，面板放 6 项（字贴在、俯角、每块字数、滞空、跳跃高度、译文开关），其余按原默认值写死：
 *   镜头距离 1、柱距 1.2、柱高 1.9、歇脚柱 1.75 秒、柱子弹性 1、小人尺寸 1.2、轨迹 0.5、柱面 / 背光面 / 棱线 0.8、上下两行 0.9、
 *   译文字号 1.05、最多 3 行、顶部对齐、偏移 0、不透明度 0.8、逐字透视、光晕 0.28；译文内容跟宿主“歌词下面那一行”的设置走。
 */
object JumpVisualizer : LyricVisualizer {
    override val id = "jump"
    override val label = "跳一跳"
    override val origin = ModOrigin("visualizer-jump", "1.2.0", "tumuyan", "AGPL-3.0")
    override val order = 80
    override val hostBackground = true
    override val hostSubtitles = false
    override val wantsAudio = false

    /** 原版没有歌词时一根柱子都不立。 */
    override val drawsWithoutLyrics = false
    override val hiddenSettings = 19

    override val settings: List<LyricSetting> = listOf(
        LyricSetting.Choice(
            "textFace",
            "字贴在",
            listOf("top" to "顶面", "front" to "正面", "side" to "侧面", "above" to "上方"),
            "top",
        ),
        LyricSetting.Number("pitch", "俯角", 18f, 60f, 2f, 34f, ::jumpFormatDegrees),
        LyricSetting.Number("chunkChars", "每块字数", 1f, 4f, 1f, 2f, ::formatInt, summary = "只对中文有用"),
        LyricSetting.Number("flySeconds", "滞空时间", 0.1f, 1.2f, 0.05f, 0.45f, ::formatSeconds),
        LyricSetting.Number("arc", "跳跃高度", 0f, 2.4f, 0.1f, 1.2f),
        LyricSetting.Toggle("translationMode", "显示译文", true),
    )

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) {
        val settings = ctx.settings
        val config = remember(settings) {
            JumpConfig(
                face = JumpFace.of(settings.choice("textFace")),
                pitch = settings.number("pitch"),
                chunkChars = settings.number("chunkChars").roundToInt(),
                flySeconds = settings.number("flySeconds"),
                arc = settings.number("arc"),
            )
        }
        val showTranslation = remember(settings) { settings.toggle("translationMode") }
        val paints = remember { JumpPaints() }
        val course = remember(ctx.lines, config) { jumpBuildCourse(ctx.lines, config, paints::span, ::jumpWordSegments) }
        val ink = remember(ctx.colors) { JumpInk(ctx.colors) }
        val translations = remember(ctx.lines, ctx.subline, course, showTranslation) {
            if (showTranslation) JumpTranslations(ctx.lines, ctx.subline, course) else null
        }
        Box(modifier.fillMaxSize()) {
            if (translations != null) JumpTranslationLayer(ctx, course, translations, paints, ink)
            JumpScene(ctx, course, translations, paints, ink)
        }
    }
}

/** 俯角的显示：“34°”。 */
internal fun jumpFormatDegrees(value: Float): String = "${value.roundToInt()}°"

/** 一句的分词（安卓上 java.text.BreakIterator 由 ICU 实现，中文按词典切词）。 */
internal fun jumpWordSegments(text: String): List<String> {
    val iterator = BreakIterator.getWordInstance(Locale.getDefault())
    iterator.setText(text)
    val out = ArrayList<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        out += text.substring(start, end)
        start = end
        end = iterator.next()
    }
    return out
}

/** 动起来时格子不小于这么大（原版 CELL_FLOOR_PX）。 */
private val JUMP_CELL_FLOOR = 26.dp

/** 柱面、棱线、译文的浓度（原版 jumpPalette + 默认 0.8 的三根旋钮）。 */
private val JUMP_LIT_ALPHA = jumpFaceOpacity(0.8f, 0.17f)
private val JUMP_SHADE_ALPHA = jumpFaceOpacity(0.8f, 0.08f)
private val JUMP_EDGE_ALPHA = jumpFaceOpacity(0.8f, 0.42f)
private const val JUMP_OTHER_ROW = 0.9f
private const val JUMP_TRAIL = 0.5f
private const val JUMP_TRANSLATION_OPACITY = 0.8f
private const val JUMP_TRANSLATION_ROWS = 3
private const val JUMP_TRANSLATION_LINE_HEIGHT = 1.26f
/** 静帧里译文的纵向预算（安全区高的比例）：缩略图卡片很扁，再多就把路挤得太小。 */
private const val JUMP_STILL_TRANSLATION_ROOM = 0.26f
private const val JUMP_GLOW = 0.28f
private val JUMP_SHADOW_INK = Color(6, 10, 22)

/** 原生画笔：柱上的字（600 字重）、量字宽（128 像素）、译文（500 字重）。 */
private class JumpPaints {
    val label = NativePaint(NativePaint.ANTI_ALIAS_FLAG or NativePaint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = jumpTypeface(600)
        textAlign = NativePaint.Align.CENTER
    }
    private val measure = NativePaint(NativePaint.ANTI_ALIAS_FLAG or NativePaint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = jumpTypeface(600)
        textSize = MEASURE_PX
    }
    val translation = NativePaint(NativePaint.ANTI_ALIAS_FLAG or NativePaint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = jumpTypeface(500)
        textAlign = NativePaint.Align.CENTER
    }
    val metrics = NativePaint.FontMetrics()

    /** 柱上的字：基线离字盒竖直中心多远（× 字号），让字的墨迹盒居中。 */
    val labelCenter: Float = measure.fontMetrics.let { -(it.ascent + it.descent) / 2f / MEASURE_PX }

    /** 一块字有多宽（以字号为单位）。 */
    fun span(text: String): Float = measure.measureText(text) / MEASURE_PX

    private companion object {
        const val MEASURE_PX = 128f
    }
}

private fun jumpTypeface(weight: Int): Typeface = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Typeface.create(Typeface.DEFAULT, weight, false)
    weight >= 600 -> Typeface.DEFAULT_BOLD
    else -> Typeface.DEFAULT
}

/** 配色（原版 jumpPalette）：字和译文用正文色，小人、光晕、波纹用主题色，柱面是中间色，棱线是正文色与主题色各半。 */
private class JumpInk(colors: StageColors) {
    val accent = colors.accent
    val face = lerp(lerp(colors.surface, colors.text, 0.5f), colors.accent, 0.15f).copy(alpha = 1f)
    val edge = lerp(colors.text, colors.accent, 0.5f).copy(alpha = 1f)
    val textArgb = colors.text.toArgb()
    val accentArgb = colors.accent.toArgb()
    val paperArgb = colors.surface.toArgb()
}

/** 把 [argb] 的不透明度乘上 [alpha]。 */
private fun jumpArgb(argb: Int, alpha: Float): Int {
    val a = ((argb ushr 24) * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (argb and 0xFFFFFF)
}

// ---- 译文 ----

/** 译文排版的参数（像素）：变了就整份缓存作废。 */
private data class JumpTranslationParams(
    val room: Float,
    val roomHeight: Float,
    val target: Float,
    val minSize: Float,
    val minFloor: Float,
    val depth: Float,
    val pitch: Float,
)

/** 排好的一句译文：每行字、行宽、字号、行高、基线在行盒里的位置、行盒比字高出来的那半截、整体缩放、每行的透视。 */
private class JumpTranslationLayout(
    val rows: List<String>,
    val sizePx: Float,
    val lineHeight: Float,
    val baseline: Float,
    val halfLeading: Float,
    val scaleX: Float,
    val scaleY: Float,
    val tilts: List<FloatArray>,
) {
    val height: Float get() = rows.size * lineHeight * scaleY
}

/**
 * 整首歌的译文：每句显示的文字（跟宿主“歌词下面那一行”）、能不能读、什么时候淡入；排版按句缓存（两层画面共用）。
 */
private class JumpTranslations(lines: List<StageLine>, subline: LyricSubline, course: JumpCourse) {
    private val texts = lines.map { it.subtitle(subline) }
    val starts = LongArray(lines.size) { lines[it].startMs }
    val readable = BooleanArray(lines.size) { jumpReadable(texts[it]) }

    /** 这句译文的淡入时刻（秒）：这句在路上就是它开口的那一刻，否则是句首。 */
    val openAt = DoubleArray(lines.size) { index ->
        val row = course.rowOfLine.getOrElse(index) { -1 }
        if (row >= 0) course.rows[row].window.writeStart else lines[index].startMs / 1000.0
    }
    private val cache = HashMap<Int, JumpTranslationLayout?>()
    private var params: JumpTranslationParams? = null

    fun layout(index: Int, params: JumpTranslationParams, paint: NativePaint, metrics: NativePaint.FontMetrics): JumpTranslationLayout? {
        if (params != this.params) {
            cache.clear()
            this.params = params
        }
        if (cache.containsKey(index)) return cache[index]
        val block = jumpTranslationBlock(
            paragraphs = texts[index].split('\n'),
            room = params.room,
            roomHeight = params.roomHeight,
            target = params.target,
            minSize = params.minSize,
            maxRows = JUMP_TRANSLATION_ROWS,
            lineHeightRatio = JUMP_TRANSLATION_LINE_HEIGHT,
            minFloor = params.minFloor,
        ) { row, size ->
            paint.textSize = size
            paint.measureText(row)
        }
        val layout = block?.let {
            paint.textSize = it.sizePx
            paint.getFontMetrics(metrics)
            val halfLeading = (it.lineHeight - (metrics.descent - metrics.ascent)) / 2f
            val fit = jumpTranslationFit(it.width, params.room, it.height, params.roomHeight)
            JumpTranslationLayout(
                rows = it.rows,
                sizePx = it.sizePx,
                lineHeight = it.lineHeight,
                baseline = halfLeading - metrics.ascent,
                halfLeading = max(0f, halfLeading),
                scaleX = fit[0],
                scaleY = fit[1],
                tilts = it.rows.indices.map { i -> jumpRowTilt(params.pitch, (it.rows.size - 1 - i) * it.lineHeight, params.depth) },
            )
        }
        cache[index] = layout
        return layout
    }
}

/**
 * 译文字号：以柱顶那块字的字号为尺子（× 1.05），再按正文字号的 1.3 倍、安全区高的 12% 封顶（手机和缩略图上原版会大到一屏两三个字）；
 * 收到底不小于尺子的五成五。横向占安全区宽的 84%，纵向折行预算是安全区高的八成；静帧（缩略图、减少动态效果）只给
 * [JUMP_STILL_TRANSLATION_ROOM]，整条路要在它下面完整露出来。
 */
private fun jumpTranslationParams(course: JumpCourse, safe: Rect, stageHeight: Float, density: Density, textSp: Float, still: Boolean): JumpTranslationParams =
    with(density) {
        val frame = jumpFrame(safe.width, safe.height, course.config.pitch, course.frameInput, JUMP_CELL_FLOOR.toPx())
        val pillarType = max(10.dp.toPx(), jumpPillarTypeCells(course.config.face, course.frameInput.heightCells) * frame.cellPx)
        val textPx = textSp.sp.toPx()
        val target = max(6.dp.toPx(), min(pillarType * 1.05f, min(textPx * 1.3f, safe.height * 0.12f)))
        JumpTranslationParams(
            room = max(1f, safe.width * 0.84f),
            roomHeight = max(target, safe.height * if (still) JUMP_STILL_TRANSLATION_ROOM else 0.8f),
            target = target,
            minSize = min(pillarType, target) * 0.55f,
            minFloor = min(10.dp.toPx(), textPx * 0.8f),
            depth = max(240.dp.toPx(), stageHeight * 1.1f),
            pitch = course.config.pitch,
        )
    }

/** 这一刻的译文是哪一句（−1 不显示）。 */
private fun jumpTranslationIndex(ctx: LyricStageContext, translations: JumpTranslations): Int =
    jumpTranslationLine(translations.starts, translations.readable, ctx.lineIndex.value, ctx.clock.ms)

@Composable
private fun JumpTranslationLayer(ctx: LyricStageContext, course: JumpCourse, translations: JumpTranslations, paints: JumpPaints, ink: JumpInk) {
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val safe = ctx.display.safeRect(size)
            val params = jumpTranslationParams(course, safe, size.height, this, ctx.display.textSp, ctx.display.reduceMotion)
            val look = JumpTranslationLook(this)
            val matrix = NativeMatrix()
            val values = FloatArray(9)
            onDrawBehind {
                val index = jumpTranslationIndex(ctx, translations)
                if (index < 0) return@onDrawBehind
                val reveal = if (ctx.display.reduceMotion) 1f else jumpTranslationReveal(ctx.clock.ms / 1000.0, translations.openAt[index])
                if (reveal <= 0.004f) return@onDrawBehind
                val layout = translations.layout(index, params, paints.translation, paints.metrics) ?: return@onDrawBehind
                drawJumpTranslation(layout, safe, reveal * JUMP_TRANSLATION_OPACITY, ink, paints.translation, look, matrix, values)
            }
        },
    )
}

/** 译文的描边与阴影（像素）。 */
private class JumpTranslationLook(density: Density) {
    val stroke = with(density) { 1.dp.toPx() }
    val paperSoft = jumpShadowRadius(with(density) { 8.dp.toPx() })
    val paperSoftDy = with(density) { 1.dp.toPx() }
    val paperHard = jumpShadowRadius(with(density) { 3.dp.toPx() })
    val paperHardDy = with(density) { 2.dp.toPx() }
}

/** 光晕三层：(字号的倍数, 浓度占比)，从外往里画。 */
private val JUMP_GLOW_LAYERS = floatArrayOf(0.55f, 0.4f, 0.26f, 0.7f, 0.12f, 1f)

private fun DrawScope.drawJumpTranslation(
    layout: JumpTranslationLayout,
    safe: Rect,
    alpha: Float,
    ink: JumpInk,
    paint: NativePaint,
    look: JumpTranslationLook,
    matrix: NativeMatrix,
    values: FloatArray,
) {
    val canvas = drawContext.canvas.nativeCanvas
    paint.textSize = layout.sizePx
    val saved = canvas.saveLayerAlpha(0f, 0f, size.width, size.height, (alpha * 255f).roundToInt().coerceIn(0, 255))
    canvas.translate(safe.center.x, safe.top - layout.halfLeading * layout.scaleY)
    canvas.scale(layout.scaleX, layout.scaleY)
    layout.rows.forEachIndexed { i, row ->
        val tilt = layout.tilts[i]
        canvas.save()
        canvas.translate(0f, (i + 1) * layout.lineHeight)
        values[0] = 1f; values[1] = -tilt[0]; values[2] = 0f
        values[3] = 0f; values[4] = tilt[1]; values[5] = 0f
        values[6] = 0f; values[7] = 0f; values[8] = 1f
        matrix.setValues(values)
        canvas.concat(matrix)
        val y = layout.baseline - layout.lineHeight
        paint.style = NativePaint.Style.FILL
        var g = 0
        while (g < JUMP_GLOW_LAYERS.size) {
            val color = jumpArgb(ink.accentArgb, JUMP_GLOW * JUMP_GLOW_LAYERS[g + 1])
            paint.color = color
            paint.setShadowLayer(jumpShadowRadius(layout.sizePx * JUMP_GLOW_LAYERS[g]), 0f, 0f, color)
            canvas.drawText(row, 0f, y, paint)
            g += 2
        }
        paint.color = jumpArgb(ink.paperArgb, 0.4f)
        paint.setShadowLayer(look.paperHard, 0f, look.paperHardDy, paint.color)
        canvas.drawText(row, 0f, y, paint)
        paint.color = jumpArgb(ink.paperArgb, 0.55f)
        paint.setShadowLayer(look.paperSoft, 0f, look.paperSoftDy, paint.color)
        canvas.drawText(row, 0f, y, paint)
        paint.clearShadowLayer()
        paint.style = NativePaint.Style.STROKE
        paint.strokeWidth = look.stroke
        paint.color = jumpArgb(ink.paperArgb, 0.45f)
        canvas.drawText(row, 0f, y, paint)
        paint.style = NativePaint.Style.FILL
        paint.color = ink.textArgb
        canvas.drawText(row, 0f, y, paint)
        canvas.restore()
    }
    canvas.restoreToCount(saved)
}

// ---- 柱子、小人、波纹 ----

@Composable
private fun JumpScene(ctx: LyricStageContext, course: JumpCourse, translations: JumpTranslations?, paints: JumpPaints, ink: JumpInk) {
    val pose = remember(course) { JumpPose(course.maxUnits) }
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val safe = ctx.display.safeRect(size)
                val frame = jumpFrame(safe.width, safe.height, course.config.pitch, course.frameInput, JUMP_CELL_FLOOR.toPx())
                val painter = JumpPainter(this, course, ink, paints, ctx.display.countScale)
                val fade = jumpEdgeMasks(safe, size.width, size.height, this)
                val params = translations?.let { jumpTranslationParams(course, safe, size.height, this, ctx.display.textSp, ctx.display.reduceMotion) }
                onDrawBehind {
                    if (course.rows.isEmpty() || safe.width < 1f || safe.height < 1f) return@onDrawBehind
                    val time = ctx.clock.ms / 1000.0
                    val cell: Float
                    val originX: Float
                    val originY: Float
                    if (ctx.display.reduceMotion) {
                        jumpStillPose(course, ctx.lineIndex.value, time, pose)
                        val row = course.rows[pose.rows[JUMP_CUR]]
                        val runnerCells = jumpRunnerCells(course)
                        val runnerAt = jumpStillUnit(row, time)
                        var top = safe.top
                        var still = jumpStillFrame(row, runnerCells, safe.width, safe.height, frame.cellPx, runnerAt)
                        // 给译文让出上沿那一条（静帧的译文最多占安全区高的 [JUMP_STILL_TRANSLATION_ROOM]）：
                        // 动起来时柱子从译文后面过去是原版的叠法，静帧里压着字就看不清路，所以总是让
                        if (translations != null && params != null) {
                            val index = jumpTranslationIndex(ctx, translations)
                            val layout = if (index >= 0) translations.layout(index, params, paints.translation, paints.metrics) else null
                            if (layout != null) {
                                val band = layout.height + 4.dp.toPx()
                                if (safe.height - band >= safe.height * 0.5f) {
                                    still = jumpStillFrame(row, runnerCells, safe.width, safe.height - band, frame.cellPx, runnerAt)
                                    top += band
                                }
                            }
                        }
                        cell = still[0]
                        originX = safe.left + still[1]
                        originY = top + still[2]
                    } else {
                        jumpPose(course, time, pose)
                        if (pose.rows[JUMP_CUR] < 0) return@onDrawBehind
                        cell = frame.cellPx
                        originX = safe.left + safe.width / 2f - pose.lookDir * 0.5f * cell - pose.focusX * cell
                        originY = safe.bottom - frame.anchorBelowPx - pose.focusY * cell
                    }
                    with(painter) { drawScene(pose, cell, originX, originY) }
                    drawRect(fade[0], blendMode = BlendMode.DstIn)
                    drawRect(fade[1], blendMode = BlendMode.DstIn)
                }
            },
    )
}

/**
 * 安全区的渐隐遮罩（DstIn）：安全区外清掉，上下边各 min(24dp, 8%) 渐隐、左右边各 min(32dp, 6%) 渐隐。
 * 整条路是铺开的，硬切在安全区边上很难看，原版靠的是窗口边自然裁掉。
 */
private fun jumpEdgeMasks(safe: Rect, width: Float, height: Float, density: Density): Array<Brush> = with(density) {
    val w = max(1f, width)
    val h = max(1f, height)
    val bandY = min(min(24.dp.toPx(), safe.height * 0.08f), safe.height / 2f)
    val bandX = min(min(32.dp.toPx(), safe.width * 0.06f), safe.width / 2f)
    val clear = Color.Transparent
    val keep = Color.White
    fun stops(a: Float, b: Float, band: Float, total: Float): Array<Pair<Float, Color>> {
        val s0 = (a / total).coerceIn(0f, 1f)
        val s1 = ((a + band) / total).coerceIn(s0, 1f)
        val s3 = (b / total).coerceIn(s1, 1f)
        val s2 = ((b - band) / total).coerceIn(s1, s3)
        return arrayOf(0f to clear, s0 to clear, s1 to keep, s2 to keep, s3 to clear, 1f to clear)
    }
    arrayOf(
        Brush.verticalGradient(*stops(safe.top, safe.bottom, bandY, h), startY = 0f, endY = h),
        Brush.horizontalGradient(*stops(safe.left, safe.right, bandX, w), startX = 0f, endX = w),
    )
}

/** 一屏的画法：复用一条 Path、一个 Matrix 和几组笔触，逐帧不分配。 */
private class JumpPainter(density: Density, private val course: JumpCourse, private val ink: JumpInk, private val paints: JumpPaints, countScale: Float) {
    private val path = Path()
    private val edge = Stroke(with(density) { 1.2.dp.toPx() })
    private val ring = Stroke(with(density) { 2.dp.toPx() })
    private val thin = Stroke(with(density) { 1.dp.toPx() })
    private val ringMin = with(density) { 10.dp.toPx() }
    private val dotMin = with(density) { 1.6.dp.toPx() }
    private val trailDot = with(density) { 1.2.dp.toPx() }
    private val trailRx = with(density) { 5.dp.toPx() }
    private val trailRy = with(density) { 2.5.dp.toPx() }
    private val dustCount = max(1, (JUMP_IMPACT_DUST * countScale).roundToInt())
    private val matrix = NativeMatrix()
    private val values = FloatArray(9)
    private val dust = FloatArray(2)
    private val orderKey = FloatArray(course.maxUnits * 3)
    private val orderCode = IntArray(course.maxUnits * 3)

    // 小人：本地坐标以字号（em）为单位，原点在脚底中心
    private val body = Path().apply {
        addRoundRect(
            RoundRect(
                left = -0.26f,
                top = -0.68f,
                right = 0.26f,
                bottom = 0f,
                topLeftCornerRadius = CornerRadius(0.26f),
                topRightCornerRadius = CornerRadius(0.26f),
                bottomRightCornerRadius = CornerRadius(0.14f),
                bottomLeftCornerRadius = CornerRadius(0.14f),
            ),
        )
    }
    private val bodyGloss = Brush.radialGradient(
        0f to Color.White.copy(alpha = 0.5f),
        0.62f to Color.White.copy(alpha = 0f),
        center = Offset(-0.0936f, -0.5304f),
        radius = 0.6375f,
    )
    private val bodyShade = Brush.verticalGradient(
        0f to JUMP_SHADOW_INK.copy(alpha = 0f),
        1f to JUMP_SHADOW_INK.copy(alpha = 0.2f),
        startY = -0.3264f,
        endY = 0f,
    )
    private val headCenter = Offset(0f, -0.83f)
    private val headGloss = Brush.radialGradient(
        0f to Color.White.copy(alpha = 0.5f),
        0.62f to Color.White.copy(alpha = 0f),
        center = Offset(-0.0612f, -0.9184f),
        radius = 0.3467f,
    )
    private val shadow = Brush.radialGradient(
        0f to JUMP_SHADOW_INK.copy(alpha = 0.62f),
        0.7f to JUMP_SHADOW_INK.copy(alpha = 0f),
        center = Offset.Zero,
        radius = 1f,
    )

    fun DrawScope.drawScene(pose: JumpPose, cell: Float, ox: Float, oy: Float) {
        val rows = course.rows
        val current = rows[pose.rows[JUMP_CUR]]
        drawTrail(pose, current, cell, ox, oy)

        // 柱子：按柱底的屏幕高度从远到近画（画家算法）
        var count = 0
        for (role in 0..2) {
            val r = pose.rows[role]
            if (r < 0) continue
            val units = rows[r].units
            for (k in units.indices) {
                if (count >= orderKey.size) break
                orderKey[count] = units[k].sy
                orderCode[count] = role * 100_000 + k
                count++
            }
        }
        for (i in 1 until count) {
            val key = orderKey[i]
            val code = orderCode[i]
            var j = i - 1
            while (j >= 0 && orderKey[j] > key) {
                orderKey[j + 1] = orderKey[j]
                orderCode[j + 1] = orderCode[j]
                j--
            }
            orderKey[j + 1] = key
            orderCode[j + 1] = code
        }
        for (i in 0 until count) {
            val role = orderCode[i] / 100_000
            val k = orderCode[i] % 100_000
            val row = rows[pose.rows[role]]
            val reveal = pose.reveal[role][k]
            if (reveal <= 0.003f) continue
            val unit = row.units[k]
            val fade = (if (role == JUMP_CUR) 1f else JUMP_OTHER_ROW) * reveal
            val x = ox + unit.sx * cell
            val y = oy + unit.sy * cell + (1f - reveal) * 0.9f * cell + pose.sink[role][k] * cell
            drawPillar(unit, row.alongX, x, y, cell, fade)
        }

        if (!pose.runner) return
        val stature = max(6f, jumpRunnerCells(course) * cell)
        val feetX = ox + pose.feetX * cell
        val feetY = oy + pose.feetY * cell
        val groundX = ox + pose.groundX * cell
        val groundY = oy + pose.groundY * cell
        val air = ((groundY - feetY) / max(1f, stature * 0.55f)).coerceIn(0f, 1f)

        // 影子：贴在柱顶上，人越高越淡越小
        val shrink = 1f - 0.35f * air
        val rx = stature * 0.42f * shrink
        val ry = stature * 0.42f * max(0.16f, course.projection.sin) * 0.8f * shrink
        withTransform({
            translate(groundX, groundY)
            scale(rx, ry, Offset.Zero)
        }) {
            drawCircle(shadow, radius = 1f, center = Offset.Zero, alpha = 0.38f * (1f - 0.5f * air))
        }

        drawImpacts(pose, cell, ox, oy)

        // 身子：胶囊 + 头，左上高光、底下压暗；起跳蹲、落地压
        val squash = pose.squash
        val wide = 1f + (1f - squash) * 0.5f
        withTransform({
            translate(feetX, feetY)
            scale(stature * wide, stature * squash, Offset.Zero)
        }) {
            drawPath(body, ink.accent)
            drawPath(body, bodyGloss)
            drawPath(body, bodyShade)
            drawCircle(ink.accent, radius = 0.17f, center = headCenter)
            drawCircle(headGloss, radius = 0.17f, center = headCenter)
        }
    }

    private fun DrawScope.drawPillar(unit: JumpUnit, alongX: Boolean, x: Float, y: Float, cell: Float, fade: Float) {
        val lit = JUMP_LIT_ALPHA * fade
        val shade = JUMP_SHADE_ALPHA * fade
        val edgeAlpha = JUMP_EDGE_ALPHA * fade
        val frontAlpha = if (alongX) lit else shade
        val sideAlpha = if (alongX) shade else lit
        val box = unit.box
        val cylinder = unit.cylinder
        if (box != null) {
            drawFace(box.side, x, y, cell, sideAlpha, edgeAlpha)
            drawFace(box.front, x, y, cell, frontAlpha, edgeAlpha)
            drawFace(box.top, x, y, cell, lit, edgeAlpha)
            drawLabel(unit.text, box, x, y, cell, fade)
        } else if (cylinder != null) {
            val rx = cylinder.rx * cell
            val ry = cylinder.ry * cell
            val cy = y + cylinder.cy * cell
            val bottom = Rect(x - rx, y - ry, x + rx, y + ry)
            val top = Rect(x - rx, cy - ry, x + rx, cy + ry)
            // 左半个柱身
            path.rewind()
            path.moveTo(x - rx, cy)
            path.lineTo(x - rx, y)
            path.arcTo(bottom, 180f, -90f, false)
            path.lineTo(x, cy + ry)
            path.arcTo(top, 90f, 90f, false)
            path.close()
            drawPath(path, ink.face, frontAlpha)
            drawPath(path, ink.edge, edgeAlpha, edge)
            // 右半个柱身
            path.rewind()
            path.moveTo(x + rx, cy)
            path.lineTo(x + rx, y)
            path.arcTo(bottom, 0f, 90f, false)
            path.lineTo(x, cy + ry)
            path.arcTo(top, 90f, -90f, false)
            path.close()
            drawPath(path, ink.face, sideAlpha)
            drawPath(path, ink.edge, edgeAlpha, edge)
            // 柱顶
            drawOval(ink.face, top.topLeft, top.size, lit)
            drawOval(ink.edge, top.topLeft, top.size, edgeAlpha, edge)
        }
    }

    private fun DrawScope.drawFace(quad: FloatArray, x: Float, y: Float, cell: Float, alpha: Float, edgeAlpha: Float) {
        path.rewind()
        path.moveTo(x + quad[0] * cell, y + quad[1] * cell)
        path.lineTo(x + quad[2] * cell, y + quad[3] * cell)
        path.lineTo(x + quad[4] * cell, y + quad[5] * cell)
        path.lineTo(x + quad[6] * cell, y + quad[7] * cell)
        path.close()
        drawPath(path, ink.face, alpha)
        drawPath(path, ink.edge, edgeAlpha, edge)
    }

    /** 贴面字：字盒（宽 labelW、高 labelH 格）经仿射矩阵贴到面上，字号就是字盒高。 */
    private fun DrawScope.drawLabel(text: String, box: JumpBoxGeometry, x: Float, y: Float, cell: Float, alpha: Float) {
        val height = box.labelH * cell
        if (text.isEmpty() || height < 1f || alpha <= 0.003f) return
        val m = box.label
        values[0] = m[0]; values[1] = m[2]; values[2] = x + m[4] * cell
        values[3] = m[1]; values[4] = m[3]; values[5] = y + m[5] * cell
        values[6] = 0f; values[7] = 0f; values[8] = 1f
        matrix.setValues(values)
        val paint = paints.label
        paint.textSize = height
        paint.color = jumpArgb(ink.textArgb, alpha)
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.concat(matrix)
        canvas.drawText(text, box.labelW * cell / 2f, height / 2f + paints.labelCenter * height, paint)
        canvas.restore()
    }

    /** 当前行跳过的弧线：每段 8 个渐浓的点，落脚处一个小椭圆，都随时间淡到两成出头。 */
    private fun DrawScope.drawTrail(pose: JumpPose, row: JumpRow, cell: Float, ox: Float, oy: Float) {
        val arc = jumpArcLift(course) * cell
        val base = JUMP_TRAIL * 0.8f
        for (n in 0 until pose.trailCount) {
            val hop = row.timeline.hops[pose.trailHop[n]]
            val from = row.units[hop.from]
            val to = row.units[hop.to]
            val fade = pose.trailFade[n]
            val fx = ox + from.tx * cell
            val fy = oy + from.ty * cell
            val tx = ox + to.tx * cell
            val ty = oy + to.ty * cell
            for (step in 1..8) {
                val t = step / 8f
                val px = fx + (tx - fx) * t
                val py = fy + (ty - fy) * t - arc * sin(PI.toFloat() * t)
                drawCircle(ink.accent, trailDot, Offset(px, py), base * 0.45f * fade * (0.3f + 0.7f * t))
            }
            drawOval(ink.accent, Offset(tx - trailRx, ty - trailRy), Size(trailRx * 2f, trailRy * 2f), base * 0.3f * fade, thin)
        }
    }

    /** 落地 / 起跳环：压扁成俯角的椭圆，0.36 秒里缓出散开、淡掉；落地环再带一圈尘土。 */
    private fun DrawScope.drawImpacts(pose: JumpPose, cell: Float, ox: Float, oy: Float) {
        val squash = course.projection.sin
        val baseRadius = max(ringMin, cell * 0.8f)
        val opacity = 0.35f + 0.65f * JUMP_TRAIL
        for (i in 0 until pose.impactCount) {
            val u = pose.impactU[i]
            val x = ox + pose.impactX[i] * cell
            val y = oy + pose.impactY[i] * cell
            val ringRadius = baseRadius * pose.impactScale[i]
            val radius = ringRadius * jumpBurstRadius(u)
            val fade = jumpBurstFade(u)
            drawOval(
                ink.accent,
                Offset(x - radius, y - radius * squash),
                Size(radius * 2f, radius * 2f * squash),
                (opacity * fade * pose.impactFade[i]).coerceIn(0f, 1f),
                ring,
            )
            if (!pose.impactDust[i]) continue
            val dot = max(dotMin, ringRadius * 0.09f) / 2f
            for (d in 0 until dustCount) {
                jumpBurstDust(u, d * JUMP_IMPACT_DUST / dustCount, dust)
                drawCircle(
                    ink.accent,
                    dot,
                    Offset(x + dust[0] * ringRadius, y + dust[1] * ringRadius * squash),
                    (fade * 0.7f * pose.impactFade[i]).coerceIn(0f, 1f),
                )
            }
        }
    }
}
