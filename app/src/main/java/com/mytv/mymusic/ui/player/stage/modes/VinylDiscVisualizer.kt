package com.mytv.mymusic.ui.player.stage.modes

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.mytv.mymusic.ui.LyricWord
import com.mytv.mymusic.ui.player.stage.LyricSetting
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.LyricVisualizer
import com.mytv.mymusic.ui.player.stage.ModOrigin
import com.mytv.mymusic.ui.player.stage.SettingValues
import com.mytv.mymusic.ui.player.stage.StageLine
import com.mytv.mymusic.ui.player.stage.fitText
import com.mytv.mymusic.ui.player.stage.formatPercent
import com.mytv.mymusic.ui.player.stage.formatSeconds
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Canvas as NativeCanvas
import android.graphics.Color as NativeColor
import android.graphics.Paint as NativePaint
import android.graphics.Path as NativePath

/**
 * 黑胶唱片（vinyl-disc 1.7.10，作者 pomepii，MIT）：仿 QQ 音乐、网易云的黑胶播放页。数值、配色照原模组。
 *
 * - 唱片：深灰径向渐变的盘面、每 4dp 一道 1dp 的细纹、碟心是封面（占直径 60%，外圈一道黑边一道淡白边），
 *   盘面上一道固定不转的光泽（从 205° 起的锥形渐变，46°、214° 两处亮），背后一圈封面主色（或主题色）的弥散光晕和一片落地阴影。
 *   播放时碟心按“转一圈”的秒数匀速转；暂停时停在当前角度。
 * - 唱臂：右上角的支点、细长的白色臂杆、圆角弯头和玻璃质感的唱头，带一层投影；播放时落在碟心外的纹路上，暂停时转开 50° 抬起。
 * - 歌词排在唱片旁：前 2 句、当前句、后 4 句（“显示后面几句”关掉时没有后 4 句），离当前句越远越淡；
 *   当前句加粗，有逐字时间时每个词唱到时从左往右扫成封面主色，正在唱的词放大 6%；每句下面跟着自己的翻译。
 *   横屏顶上还有歌名、歌手。换句时当前句从下面 0.35em 处淡入。句间、空行保持上一句；“歌名 - 歌手”这种元数据行跳过。
 *
 * 移植取舍：
 * - 横屏照原模组左右排：唱片直径取安全区高度 × 唱片大小（最多宽度的 42%、最少 64dp），两边各留 15% 宽、中间隔 3%；
 *   唱片在左时把唱臂抬起后伸出的那一截加进间隔，免得压到歌词。竖屏改成唱片在上、歌词在下（唱片大小按宽度算，最多宽 80%、高 46%），
 *   省掉歌名行（顶栏已经有），前后句放不下就从最远的开始省。唱片、唱臂、歌词都在安全区里，光晕和阴影是背景可以出界。
 * - 盘面和纹路在缓存块里画进一张位图，唱臂（含投影）用软件画布画进另一张位图，投影是 extractAlpha + BlurMaskFilter，
 *   和原模组的 feDropShadow 一样；每帧只改碟心那一层的旋转角（图层属性，不重画），唱臂只在抬落的弹簧动画期间重画。
 * - 原模组盘面的白色描边（rim）用的是 farthest-corner 尺寸的径向渐变，落在唱片外面被裁掉了，实际看不见，这里不画；
 *   光晕的 blur(9%) 和落地阴影的 60px 模糊按原模组的尺寸预先算成径向渐变的色标，不做实时模糊。
 * - 唱臂抬落原模组是 0.9 秒带回弹的 cubic-bezier 过渡，这里用 Material 的慢速空间弹簧。
 * - 逐字填色照原模组只给有逐字时间（多于一个词）的句子；整句时间的歌只加粗不填色。
 * - 原模组隐藏的 3 项保持默认：碟心占比 60%、不画中心孔、歌词左对齐。
 * - 碟心封面用 Coil 加载；没有封面或加载失败时画原模组的“没有封面”碟心：深棕色细条纹（每 14° 换一色）上居中写歌名、歌手，
 *   跟着唱片一起转。加载中是一块纯深棕色。
 * - 减少动态效果：唱片不转、唱臂落着、当前句整句填满色，不播入场。
 */
object VinylDiscVisualizer : LyricVisualizer {
    override val id = "vinyl"
    override val label = "黑胶唱片"
    override val origin = ModOrigin("vinyl-disc", "1.7.10", "pomepii", "MIT")
    override val order = 50
    override val hostBackground = true
    override val hostSubtitles = false
    override val wantsAudio = false
    override val drawsWithoutLyrics = true
    override val hiddenSettings = 3

    override val settings: List<LyricSetting> = listOf(
        LyricSetting.Choice("discSide", "唱片位置", listOf("left" to "左", "right" to "右"), "right", summary = "竖屏固定上下排"),
        LyricSetting.Number("discSize", "唱片大小", 0.3f, 1f, 0.02f, 0.62f, ::formatPercent),
        LyricSetting.Number("spinSeconds", "转一圈", 6f, 60f, 2f, 20f, ::formatSeconds),
        LyricSetting.Choice("halo", "光晕", listOf("off" to "关", "cover" to "封面色", "theme" to "主题色"), "cover"),
        LyricSetting.Toggle("showArm", "唱臂", true),
        LyricSetting.Toggle("sheen", "唱片光泽", true),
        LyricSetting.Toggle("showNext", "显示后面几句", true),
        LyricSetting.Number("textScale", "字号", 0.6f, 1.8f, 0.05f, 1.25f),
    )

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) {
        val config = remember(ctx.settings) { VinylConfig(ctx.settings) }
        val still = ctx.display.reduceMotion
        val spec: AnimationSpec<Float> = if (still) snap() else MaterialTheme.motionScheme.slowSpatialSpec()
        // 唱臂：播放时落下（0°），暂停时绕支点逆时针转开 50°；减少动态效果时一直落着
        val arm = animateFloatAsState(if (!still && ctx.paused.value) ARM_LIFT_DEG else 0f, spec, label = "vinylArm")
        // 填色用的封面主色：没有封面时原模组退到主题色
        val sung = if (ctx.song.cover.isNotBlank()) WaveVinylKit.dominant(ctx.colors.cover[0]) else ctx.colors.accent
        val halo = when (config.halo) {
            "cover" -> sung
            "theme" -> ctx.colors.accent
            else -> null
        }
        Box(modifier) {
            DiscBase(ctx, config, halo)
            DiscLabel(ctx, config)
            DiscTop(ctx, config, arm)
            VinylLyrics(ctx, config, sung)
        }
    }
}

/** 面板里的设置，一次读好。 */
private class VinylConfig(settings: SettingValues) {
    val discLeft = settings.choice("discSide") == "left"
    val discSize = settings.number("discSize")
    val spinSeconds = settings.number("spinSeconds")
    val halo = settings.choice("halo")
    val showArm = settings.toggle("showArm")
    val sheen = settings.toggle("sheen")
    val showNext = settings.toggle("showNext")
    val textScale = settings.number("textScale")

    /** 当前尺寸、安全区下的唱片和歌词区；读安全区就订阅了它。 */
    fun geometry(ctx: LyricStageContext, size: Size, density: Density): VinylGeometry = vinylGeometry(
        safe = ctx.display.safeRect(size),
        wide = ctx.display.wide,
        discLeft = discLeft,
        discSize = discSize,
        showArm = showArm,
        minDisc = with(density) { MIN_DISC.toPx() },
    )
}

/** 唱片外接正方形取整到像素：盘面位图、碟心图层、光泽都按它对齐。 */
private fun VinylGeometry.discBox(): IntRect {
    val side = disc.width.roundToInt().coerceAtLeast(1)
    val left = disc.left.roundToInt()
    val top = disc.top.roundToInt()
    return IntRect(left, top, left + side, top + side)
}

// ---------------------------------------------------------------------------------------------
// 唱片
// ---------------------------------------------------------------------------------------------

/** 光晕、落地阴影、盘面位图、碟心外的两道细边。不读时钟，只在尺寸、设置变化时重画。 */
@Composable
private fun DiscBase(ctx: LyricStageContext, config: VinylConfig, halo: Color?) {
    val bake = remember { DiscBake() }
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val box = config.geometry(ctx, size, this).discBox()
            val r = box.width / 2f
            val center = Offset(box.left + r, box.top + r)
            val haloBrush = halo?.let { color ->
                Brush.radialGradient(
                    *Array(HALO_STOPS.size) { i -> HALO_STOPS[i] to color.copy(alpha = HALO_ALPHAS[i]) },
                    center = center,
                    radius = r * HALO_EXTENT,
                )
            }
            val shadowCenter = center + Offset(0f, r * SHADOW_DROP)
            val shadowBrush = Brush.radialGradient(
                *Array(SHADOW_STOPS.size) { i -> SHADOW_STOPS[i] to Color.Black.copy(alpha = SHADOW_ALPHA * SHADOW_ALPHAS[i]) },
                center = shadowCenter,
                radius = r * SHADOW_EXTENT,
            )
            val body = bake.body(this, box.width)
            val labelR = r * LABEL_RATIO
            val px = 1.dp.toPx()
            onDrawBehind {
                haloBrush?.let { drawCircle(it, r * HALO_EXTENT, center) }
                drawCircle(shadowBrush, r * SHADOW_EXTENT, shadowCenter)
                drawImage(body, topLeft = Offset(box.left.toFloat(), box.top.toFloat()))
                // 碟心的 box-shadow：贴边 1px 黑（65%），外面再 2.5px 淡白（5%）
                drawCircle(Color.White.copy(alpha = 0.05f), labelR + 1.75f * px, center, style = Stroke(1.5f * px))
                drawCircle(Color.Black.copy(alpha = 0.65f), labelR + 0.5f * px, center, style = Stroke(px))
            }
        },
    )
}

/** 盘面位图：尺寸、密度不变就复用，不在安全区微调时重画。 */
private class DiscBake {
    private var image: ImageBitmap? = null
    private var side = -1
    private var density = 0f

    fun body(density: Density, side: Int): ImageBitmap {
        image?.let { if (this.side == side && this.density == density.density) return it }
        val bitmap = ImageBitmap(side, side)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(side.toFloat(), side.toFloat())) {
            val r = side / 2f
            // 原模组的 radial-gradient(circle …) 没写尺寸，按 farthest-corner（半径 √2·r）算色标
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFF262626),
                    0.3f to Color(0xFF1B1B1B),
                    0.7f to Color(0xFF121212),
                    1f to Color(0xFF0D0D0D),
                    center = center,
                    radius = r * SQRT2,
                ),
                r,
                center,
            )
            // repeating-radial-gradient：每 4px 一道 1px 的白（4.5%）；碟心以内被封面盖住，从碟心边缘稍里面开始画
            val spacing = GROOVE_SPACING.toPx()
            val line = GROOVE_LINE.toPx()
            var radius = floor(r * 0.55f / spacing) * spacing + line / 2f
            while (radius + line / 2f <= r) {
                drawCircle(Color.White.copy(alpha = 0.045f), radius, center, style = Stroke(line))
                radius += spacing
            }
        }
        image = bitmap
        this.side = side
        this.density = density.density
        return bitmap
    }
}

/**
 * 碟心：封面（或没有封面时的条纹 + 歌名），圆形裁切，整层按动画时钟旋转。
 * 旋转是图层属性，每帧只改角度，不重画封面。
 */
@Composable
private fun DiscLabel(ctx: LyricStageContext, config: VinylConfig) {
    val cover = ctx.song.cover
    var failed by remember(cover) { mutableStateOf(false) }
    val measurer = rememberTextMeasurer(cacheSize = 2)
    val family = MaterialTheme.typography.bodyLarge.fontFamily
    Box(
        Modifier
            .layout { measurable, constraints ->
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
                val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
                val box = config.geometry(ctx, Size(width.toFloat(), height.toFloat()), this).discBox()
                val side = (box.width * LABEL_RATIO).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(side, side))
                layout(width, height) {
                    placeable.place(box.left + (box.width - side) / 2, box.top + (box.height - side) / 2)
                }
            }
            .graphicsLayer {
                rotationZ = if (ctx.display.reduceMotion) 0f else vinylAngle(ctx.clock.seconds, config.spinSeconds)
                shape = CircleShape
                clip = true
            },
    ) {
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val plain = cover.isNotBlank() && !failed
                val side = size.minDimension
                if (plain) {
                    return@drawWithCache onDrawBehind { drawCircle(LABEL_BASE) }
                }
                // 没有封面：条纹底上居中写歌名（标签宽 13%）和歌手（8.5%），四周留 12%
                val stripes = Brush.sweepGradient(*LABEL_STRIPES, center = Offset(size.width / 2f, size.height / 2f))
                val room = (side * (1f - 2f * LABEL_PADDING)).toInt().coerceAtLeast(1)
                val titlePx = max(8.dp.toPx(), side * 0.13f)
                val artistPx = max(7.dp.toPx(), side * 0.085f)
                val title = measurer.measure(
                    ctx.song.name.ifBlank { "♪" },
                    TextStyle(fontFamily = family, fontSize = titlePx.toSp(), fontWeight = FontWeight.SemiBold, lineHeight = 1.25.em),
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = room),
                )
                val artist = ctx.song.artist.takeIf { it.isNotBlank() }?.let {
                    measurer.measure(
                        it,
                        TextStyle(fontFamily = family, fontSize = artistPx.toSp(), fontWeight = FontWeight.Normal, lineHeight = 1.25.em),
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = room),
                    )
                }
                val gap = if (artist != null) artistPx * 0.4f else 0f
                val total = title.size.height + gap + (artist?.size?.height ?: 0)
                val top = (side - total) / 2f
                onDrawBehind {
                    drawCircle(stripes)
                    drawText(title, LABEL_TEXT, Offset((side - title.size.width) / 2f, top))
                    artist?.let { drawText(it, LABEL_TEXT.copy(alpha = LABEL_TEXT.alpha * 0.7f), Offset((side - it.size.width) / 2f, top + title.size.height + gap)) }
                }
            },
        )
        if (cover.isNotBlank()) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { failed = it is AsyncImagePainter.State.Error },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 光泽和唱臂：光泽不动；唱臂只在抬落动画期间重画（绘制阶段读 [arm]）。 */
@Composable
private fun DiscTop(ctx: LyricStageContext, config: VinylConfig, arm: State<Float>) {
    val bake = remember { ArmBake() }
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val box = config.geometry(ctx, size, this).discBox()
            val r = box.width / 2f
            val center = Offset(box.left + r, box.top + r)
            val unit = box.width / 100f
            val sheen = if (config.sheen) Brush.sweepGradient(*SHEEN_STOPS, center = center) else null
            val armImage = if (config.showArm) bake.image(unit) else null
            val pivot = Offset(box.left + ARM_PIVOT_X * unit, box.top + ARM_PIVOT_Y * unit)
            val armAt = Offset(box.left + ARM_BOX_LEFT * unit, box.top + ARM_BOX_TOP * unit)
            onDrawBehind {
                // conic-gradient(from 205deg)：CSS 的 0° 在正上方，Compose 的扫描渐变从 3 点钟方向起，差 90°
                sheen?.let { rotate(SHEEN_FROM_DEG - 90f, center) { drawCircle(it, r, center) } }
                armImage?.let { rotate(arm.value, pivot) { drawImage(it, armAt) } }
            }
        },
    )
}

/** 唱臂位图（落下时的姿态，含投影），按唱片大小缓存。 */
private class ArmBake {
    private var unit = -1f
    private var image: ImageBitmap? = null

    fun image(unit: Float): ImageBitmap {
        image?.let { if (this.unit == unit) return it }
        return bakeArm(unit).also {
            image = it
            this.unit = unit
        }
    }
}

/**
 * 照原模组的 SVG 画唱臂：坐标是唱片的 0–100（[unit] px 一格），支点在 (90, −8)。
 * 软件画布先画臂，再用 extractAlpha + BlurMaskFilter 取出模糊的轮廓当投影（feDropShadow：下移 2.5、标准差 2.2、黑 40%）垫在下面。
 */
private fun bakeArm(unit: Float): ImageBitmap {
    val width = ceil(ARM_BOX_WIDTH * unit).toInt().coerceAtLeast(1)
    val height = ceil(ARM_BOX_HEIGHT * unit).toInt().coerceAtLeast(1)
    val arm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = NativeCanvas(arm)
    canvas.scale(unit, unit)
    canvas.translate(-ARM_BOX_LEFT, -ARM_BOX_TOP)
    fun fill(color: Int, opacity: Float = 1f) = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        alpha = (opacity * 255).roundToInt()
    }
    // 一根连续的臂杆：支点 → 长直杆 → 圆角弯头 → 短尾 → 唱头
    val rod = NativePath().apply {
        moveTo(90f, -8f)
        lineTo(85.64f, 12.07f)
        quadTo(85f, 15f, 82.63f, 16.83f)
        lineTo(77f, 21.2f)
    }
    canvas.drawPath(
        rod,
        NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
            style = NativePaint.Style.STROKE
            strokeWidth = 1.9f
            strokeCap = NativePaint.Cap.ROUND
            strokeJoin = NativePaint.Join.ROUND
            shader = LinearGradient(90f, -8f, 77f, 21.2f, 0xFFF4F4F6.toInt(), 0xFFDCDCE0.toInt(), Shader.TileMode.CLAMP)
            alpha = (0.94f * 255).roundToInt()
        },
    )
    // 唱头：接着臂杆的玻璃小胶囊，上半高光、下半一点暗
    canvas.save()
    canvas.rotate(142f, 77f, 21.2f)
    canvas.drawRoundRect(RectF(71.5f, 18.7f, 82.5f, 23.7f), 2.5f, 2.5f, fill(0xFFF2F2F4.toInt(), 0.88f))
    canvas.drawRoundRect(RectF(71.5f, 18.7f, 82.5f, 20.9f), 1.1f, 1.1f, fill(NativeColor.WHITE, 0.35f))
    canvas.drawRoundRect(RectF(71.5f, 21.6f, 82.5f, 23.7f), 1.05f, 1.05f, fill(NativeColor.BLACK, 0.07f))
    canvas.drawCircle(81.4f, 21.2f, 0.95f, fill(0xFF3A3A3E.toInt()))
    canvas.restore()
    // 支点：玻璃圆盘 + 深色圆心
    canvas.drawCircle(90f, -8f, 4.3f, fill(0xFFF6F6F8.toInt(), 0.9f))
    canvas.drawCircle(
        90f,
        -8f,
        4.3f,
        fill(NativeColor.WHITE, 0.5f).apply {
            style = NativePaint.Style.STROKE
            strokeWidth = 0.45f
        },
    )
    canvas.drawCircle(90f, -8f, 1.8f, fill(0xFF2A2A2E.toInt()))

    val offset = IntArray(2)
    val blur = NativePaint().apply {
        maskFilter = BlurMaskFilter(WaveVinylKit.sigmaToRadius(2.2f * unit).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
    }
    val silhouette = arm.extractAlpha(blur, offset)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    NativeCanvas(out).apply {
        drawBitmap(silhouette, offset[0].toFloat(), offset[1] + 2.5f * unit, fill(NativeColor.BLACK, 0.4f))
        drawBitmap(arm, 0f, 0f, null)
    }
    silhouette.recycle()
    arm.recycle()
    return out.asImageBitmap()
}

/** 唱片转到的角度（度，0..360）：转一圈 [spinSeconds] 秒，动画时钟只在播放时走，暂停时自然停住。 */
internal fun vinylAngle(seconds: Float, spinSeconds: Float): Float {
    if (spinSeconds <= 0f) return 0f
    val turns = seconds / spinSeconds
    return (turns - floor(turns)) * 360f
}

// ---------------------------------------------------------------------------------------------
// 布局
// ---------------------------------------------------------------------------------------------

/** 唱片外接正方形 [disc] 和歌词区 [text]（px）；[header] 为 true 时歌词区顶上排歌名、歌手。 */
internal class VinylGeometry(val disc: Rect, val text: Rect, val header: Boolean)

/**
 * 唱片和歌词区，都在安全区 [safe] 里。
 * - 横屏：直径 = 高 × [discSize]，最多宽的 42%、最少 [minDisc]，唱臂支点（直径的 12.5%）露在唱片上面也不出安全区；
 *   两边各留 15% 宽，唱片和歌词隔 3% 宽，唱片在左时再让出唱臂抬起后伸出的那一截；唱片竖向居中。
 * - 竖屏：直径 = 宽 × [discSize]，最多宽 80%、高 46%；唱片水平居中贴顶，歌词区在下面，左右各留 8%。
 */
internal fun vinylGeometry(safe: Rect, wide: Boolean, discLeft: Boolean, discSize: Float, showArm: Boolean, minDisc: Float): VinylGeometry {
    val w = safe.width
    val h = safe.height
    val reach = if (showArm) ARM_REACH_TOP else 0f
    if (wide) {
        val d = min(h * discSize, w * 0.42f).coerceAtLeast(minDisc).coerceAtMost(h / (1f + reach)).coerceAtLeast(0f)
        val pad = w * 0.15f
        val gap = w * 0.03f + if (discLeft && showArm) d * ARM_OVERHANG else 0f
        val cy = max(safe.center.y, safe.top + reach * d + d / 2f)
        val discX = if (discLeft) safe.left + pad else safe.right - pad - d
        val disc = Rect(discX, cy - d / 2f, discX + d, cy + d / 2f)
        val text = if (discLeft) {
            Rect(disc.right + gap, safe.top, max(disc.right + gap, safe.right - pad), safe.bottom)
        } else {
            Rect(safe.left + pad, safe.top, max(safe.left + pad, disc.left - gap), safe.bottom)
        }
        return VinylGeometry(disc, text, header = true)
    }
    val d = min(w * discSize, min(w * 0.8f, h * 0.46f)).coerceAtLeast(minDisc).coerceAtMost(min(w, h / (1f + reach))).coerceAtLeast(0f)
    val top = safe.top + reach * d + h * 0.02f
    val disc = Rect(safe.center.x - d / 2f, top, safe.center.x + d / 2f, top + d)
    val margin = w * 0.08f
    val textTop = min(safe.bottom, disc.bottom + max(h * 0.04f, d * 0.08f))
    return VinylGeometry(disc, Rect(safe.left + margin, textTop, safe.right - margin, safe.bottom), header = false)
}

// ---------------------------------------------------------------------------------------------
// 歌词
// ---------------------------------------------------------------------------------------------

/** 一行（一句 + 它的翻译）排好后的样子。 */
private class VinylRow(
    val line: TextLayoutResult,
    val lineAt: Offset,
    val sub: TextLayoutResult?,
    val subAt: Offset,
    val alpha: Float,
    val active: Boolean,
)

/** 当前句里一个词（或被折到两行的词的一段）：单独排的字形、位置、填色从词宽的哪段到哪段。 */
private class VinylPiece(val layout: TextLayoutResult, val at: Offset, val word: LyricWord, val from: Float, val to: Float)

/**
 * 歌词区。换句时（组合阶段）定下显示哪几句；排版在缓存块里，只随换句、尺寸、安全区变；
 * 逐帧只读时钟算填色和入场，整句时间的句子入场结束后就不再逐帧重画。
 */
@Composable
private fun VinylLyrics(ctx: LyricStageContext, config: VinylConfig, sung: Color) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val family = MaterialTheme.typography.bodyLarge.fontFamily
    val usable = remember(ctx.lines, ctx.song) { vinylUsable(ctx.lines, ctx.song.name, ctx.song.artist) }
    val hold = remember(ctx.lines) { VinylHold() }
    val shown = hold.resolve(usable, ctx.lineIndex.value)
    val rows = remember(usable, shown, config.showNext) { vinylRows(usable, shown, config.showNext) }
    // 首屏不播入场（原模组只在换句时播）
    val entrance = remember(shown) { WaveVinylKit.Entrance(LINE_ENTER_MS).also { if (!hold.moved) it.finish() } }
    val text = ctx.colors.text
    val secondary = text.copy(alpha = SECONDARY_ALPHA)
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val safe = ctx.display.safeRect(size)
            val geometry = config.geometry(ctx, size, this)
            val area = geometry.text
            val width = area.width.toInt().coerceAtLeast(1)
            // 歌词区很窄时（面板缩略图、小窗口）按一行至少放下 [MIN_ROW_CHARS] 个字缩小，平板横屏一行本来就有十几个字，不受影响
            val baseSp = min(ctx.display.textSp * TEXT_SCALE * config.textScale, area.width.toSp().value / MIN_ROW_CHARS)
            val basePx = baseSp.sp.toPx()

            // 歌名、歌手（只横屏）
            val header = if (geometry.header) {
                listOf(
                    measureLine(measurer, ctx.song.name, TextStyle(fontFamily = family, fontSize = baseSp.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.01.em), width),
                    measureLine(measurer, ctx.song.artist, TextStyle(fontFamily = family, fontSize = (baseSp * 0.8f).sp, fontWeight = FontWeight.Normal), width),
                )
            } else {
                emptyList()
            }
            val artistGap = baseSp * 0.8f * 0.35f
            val headerHeight = if (header.isEmpty()) 0f else header[0].size.height + artistGap.sp.toPx() + header[1].size.height + basePx * HEADER_GAP

            // 每行：正文 + 翻译；当前句有逐字时间时用词拼出整句，记下每个词的位置
            var pieces: List<VinylPiece> = emptyList()
            val subPx = baseSp * 0.62f
            val subGap = subPx * 0.18f
            val measured = rows.map { i ->
                val line = ctx.lines[i]
                val active = i == shown
                val words = line.words
                val karaoke = active && words.size > 1
                val content = if (karaoke) karaokeText(words) else line.text.trim()
                val layout = if (active) {
                    fitText(
                        measurer,
                        content,
                        TextStyle(fontFamily = family, fontSize = baseSp.sp, fontWeight = FontWeight.Bold, lineHeight = 1.5.em),
                        width,
                        maxLines = 3,
                        align = TextAlign.Left,
                    )
                } else {
                    measurer.measure(
                        content,
                        TextStyle(fontFamily = family, fontSize = (baseSp * 0.8f).sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.5.em),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 3,
                        constraints = Constraints(maxWidth = width),
                    )
                }
                if (karaoke) pieces = karaokePieces(measurer, layout, words)
                val subtitle = line.subtitle(ctx.subline).trim()
                val sub = subtitle.takeIf { it.isNotEmpty() }?.let {
                    measurer.measure(
                        it,
                        TextStyle(fontFamily = family, fontSize = subPx.sp, fontWeight = FontWeight.Normal, lineHeight = 1.4.em),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 3,
                        constraints = Constraints(maxWidth = width),
                    )
                }
                Triple(i, layout, sub)
            }
            val subGapPx = subGap.sp.toPx()
            val heights = FloatArray(measured.size) { k ->
                val (_, layout, sub) = measured[k]
                layout.size.height + (sub?.let { subGapPx + it.size.height } ?: 0f)
            }
            val gap = basePx * ROW_GAP
            val fit = vinylFitRows(heights, measured.indexOfFirst { it.first == shown }, headerHeight, gap, area.height)

            // 整块（歌名 + 留下的行）在歌词区里竖向居中，放不下时贴顶
            var block = if (fit.header) headerHeight else 0f
            var kept = 0
            for (k in heights.indices) if (fit.keep[k]) {
                block += heights[k]
                kept++
            }
            if (kept > 1) block += gap * (kept - 1)
            var y = area.top + max(0f, (area.height - block) / 2f)
            val headerAt = y
            if (fit.header) y += headerHeight
            val laid = ArrayList<VinylRow>(kept)
            var activeTop = 0f
            for (k in measured.indices) {
                if (!fit.keep[k]) continue
                val (i, layout, sub) = measured[k]
                val active = i == shown
                if (active) activeTop = y
                laid += VinylRow(
                    line = layout,
                    lineAt = Offset(area.left, y),
                    sub = sub,
                    subAt = Offset(area.left, y + layout.size.height + subGapPx),
                    alpha = vinylRowAlpha(abs(i - shown)),
                    active = active,
                )
                y += heights[k] + gap
            }
            val karaoke = pieces.map { VinylPiece(it.layout, it.at + Offset(area.left, activeTop), it.word, it.from, it.to) }
            val rise = basePx * 0.35f
            onDrawBehind {
                clipRect(safe.left, safe.top, safe.right, safe.bottom) {
                    if (fit.header) {
                        drawText(header[0], secondary.copy(alpha = secondary.alpha * 0.85f), Offset(area.left, headerAt))
                        drawText(header[1], secondary.copy(alpha = secondary.alpha * 0.55f), Offset(area.left, headerAt + header[0].size.height + artistGap.sp.toPx()))
                    }
                    for (row in laid) {
                        if (!row.active) {
                            drawText(row.line, text, row.lineAt, alpha = row.alpha)
                            row.sub?.let { drawText(it, secondary, row.subAt, alpha = row.alpha * 0.7f) }
                            continue
                        }
                        val p = WaveVinylKit.EnterEasing.transform((entrance.elapsedMs(ctx) / LINE_ENTER_MS).coerceIn(0f, 1f))
                        translate(0f, rise * (1f - p)) {
                            if (karaoke.isEmpty()) {
                                drawText(row.line, text, row.lineAt, alpha = p)
                            } else {
                                drawKaraoke(karaoke, ctx, text, sung, p)
                            }
                            row.sub?.let { drawText(it, secondary, row.subAt, alpha = p * 0.78f) }
                        }
                    }
                }
            }
        },
    )
}

/** 逐字填色：每个词先画一层原色，再按唱到的比例从左裁出一层封面色；正在唱的词放大 6%。 */
private fun DrawScope.drawKaraoke(pieces: List<VinylPiece>, ctx: LyricStageContext, text: Color, sung: Color, alpha: Float) {
    val still = ctx.display.reduceMotion
    val ms = if (still) 0L else ctx.clock.ms
    for (piece in pieces) {
        val word = piece.word
        val progress = if (still) 1f else vinylWordProgress(ms, word.startMs, word.endMs)
        val fill = ((progress - piece.from) / (piece.to - piece.from).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
        val grow = if (still) 1f else vinylWordScale(ms, word.startMs, word.endMs)
        val w = piece.layout.size.width.toFloat()
        val h = piece.layout.size.height.toFloat()
        withTransform({ scale(grow, grow, piece.at + Offset(w / 2f, h / 2f)) }) {
            when {
                fill >= 1f -> drawText(piece.layout, sung, piece.at, alpha = alpha)
                fill <= 0f -> drawText(piece.layout, text, piece.at, alpha = alpha)
                else -> {
                    drawText(piece.layout, text, piece.at, alpha = alpha)
                    clipRect(piece.at.x - h, piece.at.y - h, piece.at.x + w * fill, piece.at.y + h * 2f) {
                        drawText(piece.layout, sung, piece.at, alpha = alpha)
                    }
                }
            }
        }
    }
}

/** 单行、超宽省略。空串量出来高度为 0。 */
private fun measureLine(measurer: TextMeasurer, text: String, style: TextStyle, width: Int): TextLayoutResult =
    measurer.measure(text, style, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = width))

/** 用词拼出整句（和经典列表的逐字行一样：首词去前导空白、末词去尾随空白）。 */
private fun karaokeText(words: List<LyricWord>): String = buildString {
    words.forEachIndexed { i, word ->
        append(word.text.let { if (i == 0) it.trimStart() else it }.let { if (i == words.lastIndex) it.trimEnd() else it })
    }
}

/**
 * 当前句每个词的字形和位置（相对整句排版左上角）。词按自己的文字单独排，按基线对齐到整句排版里的位置；
 * 被折到两行的词分成两段，填色按两段宽度先后扫过。
 */
private fun karaokePieces(measurer: TextMeasurer, line: TextLayoutResult, words: List<LyricWord>): List<VinylPiece> {
    val text = line.layoutInput.text.text
    val style = line.layoutInput.style.copy(textAlign = TextAlign.Unspecified)
    val out = ArrayList<VinylPiece>(words.size)
    var cursor = 0
    words.forEachIndexed { i, word ->
        val raw = word.text.let { if (i == 0) it.trimStart() else it }.let { if (i == words.lastIndex) it.trimEnd() else it }
        val start = cursor
        cursor += raw.length
        // 词里可见部分（去掉两头空白）；排版被省略截断时后面的词不画
        var a = start
        var b = min(cursor, text.length)
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        if (a >= b) return@forEachIndexed
        val segments = ArrayList<Pair<TextLayoutResult, Offset>>(1)
        var s = a
        while (s < b) {
            val row = line.getLineForOffset(s)
            var e = s
            var left = Float.MAX_VALUE
            while (e < b && line.getLineForOffset(e) == row) {
                left = min(left, line.getBoundingBox(e).left)
                e += Character.charCount(text.codePointAt(e))
            }
            val piece = text.substring(s, e).trimEnd()
            if (piece.isNotEmpty()) {
                val glyph = measurer.measure(piece, style, softWrap = false, maxLines = 1)
                segments += glyph to Offset(left, line.getLineBaseline(row) - glyph.firstBaseline)
            }
            s = e
        }
        val total = segments.sumOf { it.first.size.width }.toFloat().coerceAtLeast(1f)
        var done = 0f
        for ((glyph, at) in segments) {
            val from = done / total
            done += glyph.size.width
            out += VinylPiece(glyph, at, word, from, done / total)
        }
    }
    return out
}

/** 哪些句子能显示：非空，且不是“歌名 - 歌手”这种元数据行（单独的歌名、歌手照常算歌词）。 */
internal fun vinylUsable(lines: List<StageLine>, title: String, artist: String): BooleanArray {
    val meta = vinylMetaTexts(title, artist)
    return BooleanArray(lines.size) { i -> lines[i].text.trim().let { it.isNotEmpty() && it !in meta } }
}

/** 歌词源塞进来的元数据行：歌名、歌手用各种分隔符正反拼起来。歌名或歌手为空时没有。 */
internal fun vinylMetaTexts(title: String, artist: String): Set<String> {
    val t = title.trim()
    val a = artist.trim()
    if (t.isEmpty() || a.isEmpty()) return emptySet()
    return META_SEPARATORS.flatMap { sep -> listOf("$t$sep$a".trim(), "$a$sep$t".trim()) }.toSet()
}

/**
 * 句间、空行保持上一句（原模组的 holdIndex）：从当前句往回找能显示的；句间用上次显示的。
 * [moved] 记录挂载后显示的句子变过没有，首屏不播入场。
 */
internal class VinylHold {
    private var held = -1
    private var first = UNSET
    var moved = false
        private set

    fun resolve(usable: BooleanArray, index: Int): Int {
        var shown = index.coerceAtMost(usable.lastIndex)
        while (shown >= 0 && !usable[shown]) shown--
        if (shown >= 0) {
            held = shown
        } else if (index < 0 && held in usable.indices && usable[held]) {
            shown = held
        }
        if (first == UNSET) first = shown else if (shown != first) moved = true
        return shown
    }

    private companion object {
        const val UNSET = Int.MIN_VALUE
    }
}

/** 要显示的句子（行号，从上到下）：当前句之前最多 [before] 句、当前句、[showNext] 时之后最多 [after] 句，都跳过不能显示的。 */
internal fun vinylRows(usable: BooleanArray, shown: Int, showNext: Boolean, before: Int = ROWS_BEFORE, after: Int = ROWS_AFTER): List<Int> {
    val rows = ArrayList<Int>(before + after + 1)
    var i = shown - 1
    while (i >= 0 && rows.size < before) {
        if (usable[i]) rows.add(0, i)
        i--
    }
    if (shown >= 0) rows += shown
    if (showNext) {
        var count = 0
        i = shown + 1
        while (i < usable.size && count < after) {
            if (usable[i]) {
                rows += i
                count++
            }
            i++
        }
    }
    return rows
}

/** 离当前句 [distance] 句的不透明度：当前句 1，相邻 0.62，每远一句少 0.15，最低 0.16。 */
internal fun vinylRowAlpha(distance: Int): Float = if (distance == 0) 1f else max(0.16f, 0.62f - (distance - 1) * 0.15f)

/** [vinylFitRows] 的结果：每行留不留、歌名行留不留。 */
internal class VinylFit(val keep: BooleanArray, val header: Boolean)

/**
 * 放不下时省哪几行：离当前句（第 [active] 行；没有当前句时是 −1，即都算后面的）最远的先省，一样远先省后面的；
 * 只剩当前句还放不下就省歌名行。当前句总是留着。
 */
internal fun vinylFitRows(heights: FloatArray, active: Int, header: Float, gap: Float, available: Float): VinylFit {
    val keep = BooleanArray(heights.size) { true }
    var withHeader = header > 0f
    fun total(): Float {
        var sum = if (withHeader) header else 0f
        var count = 0
        for (k in heights.indices) if (keep[k]) {
            sum += heights[k]
            count++
        }
        if (count > 1) sum += gap * (count - 1)
        return sum
    }
    val order = heights.indices.filter { it != active }
        .sortedWith(compareByDescending<Int> { abs(it - active) }.thenByDescending { it })
    for (k in order) {
        if (total() <= available) break
        keep[k] = false
    }
    if (total() > available) withHeader = false
    return VinylFit(keep, withHeader)
}

/** 一个词唱到哪儿了（0..1）。起止相同或颠倒时按原模组：过了结束点算唱完。 */
internal fun vinylWordProgress(ms: Long, startMs: Long, endMs: Long): Float = when {
    endMs > startMs -> ((ms - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    ms >= endMs -> 1f
    else -> 0f
}

/**
 * 正在唱的词放大到 1.06：原模组是 transform 0.18 秒 ease 过渡，唱起时放大、唱完时缩回。
 * 这里按时间直接算出过渡到哪儿（词短于 0.18 秒时还没放到最大就开始缩回）。
 */
internal fun vinylWordScale(ms: Long, startMs: Long, endMs: Long): Float {
    if (endMs <= startMs || ms <= startMs) return 1f
    val peak = Ease.transform(((min(ms, endMs) - startMs) / POP_MS).coerceIn(0f, 1f))
    val level = if (ms <= endMs) peak else peak * (1f - Ease.transform(((ms - endMs) / POP_MS).coerceIn(0f, 1f)))
    return 1f + POP_SCALE * level
}

// ---------------------------------------------------------------------------------------------
// 常数
// ---------------------------------------------------------------------------------------------

private val MIN_DISC = 64.dp
private const val LABEL_RATIO = 0.6f
private const val SQRT2 = 1.4142135f
private val GROOVE_SPACING = 4.dp
private val GROOVE_LINE = 1.dp

/** 唱臂支点圆的顶端在唱片上方直径的 12.3% 处（支点 y = −8、半径 4.3），按 12.5% 留。 */
private const val ARM_REACH_TOP = 0.125f

/** 唱臂抬起后唱头伸出唱片右边的长度（直径的比例）。 */
private const val ARM_OVERHANG = 0.12f
private const val ARM_LIFT_DEG = -50f
private const val ARM_PIVOT_X = 90f
private const val ARM_PIVOT_Y = -8f

// 唱臂位图覆盖的范围（唱片 0–100 坐标），含投影外溢
private const val ARM_BOX_LEFT = 62f
private const val ARM_BOX_TOP = -20f
private const val ARM_BOX_WIDTH = 40f
private const val ARM_BOX_HEIGHT = 58f

/**
 * 光晕：原模组是唱片外扩 12.6% 的圆，radial-gradient 在 55% → 34%（36%）→ 14%（54%）→ 0（68.4%）之间变，
 * 再整个 blur(直径的 9%)。这里把模糊后的径向分布预先算好（半径以唱片半径为单位，到 1.55 倍归零）。
 */
private const val HALO_EXTENT = 1.55f
private val HALO_STOPS = floatArrayOf(0f, 0.258f, 0.387f, 0.516f, 0.645f, 0.710f, 0.774f, 0.839f, 0.903f, 1f)
private val HALO_ALPHAS = floatArrayOf(0.476f, 0.401f, 0.324f, 0.223f, 0.117f, 0.0725f, 0.039f, 0.0176f, 0.0065f, 0f)

/**
 * 落地阴影：原模组 box-shadow 0 18px 60px rgba(0,0,0,.45)，按预览里唱片的大小折成直径的比例
 * （下移 0.081 个半径、标准差 0.135 个半径），模糊后的分布同样预先算好。
 */
private const val SHADOW_EXTENT = 1.45f
private const val SHADOW_DROP = 0.081f
private const val SHADOW_ALPHA = 0.45f
private val SHADOW_STOPS = floatArrayOf(0f, 0.414f, 0.552f, 0.621f, 0.690f, 0.759f, 0.828f, 0.897f, 0.966f, 1f)
private val SHADOW_ALPHAS = floatArrayOf(1f, 0.998f, 0.92f, 0.748f, 0.4725f, 0.21f, 0.061f, 0.011f, 0.0012f, 0f)

/** 光泽：conic-gradient(from 205deg)，46° 处 9% 白、214° 处 6% 白，其余透明。 */
private const val SHEEN_FROM_DEG = 205f
private val SHEEN_STOPS: Array<Pair<Float, Color>> = arrayOf(
    0f to Color.White.copy(alpha = 0f),
    28f / 360f to Color.White.copy(alpha = 0f),
    46f / 360f to Color.White.copy(alpha = 0.09f),
    60f / 360f to Color.White.copy(alpha = 0.02f),
    74f / 360f to Color.White.copy(alpha = 0f),
    196f / 360f to Color.White.copy(alpha = 0f),
    214f / 360f to Color.White.copy(alpha = 0.06f),
    228f / 360f to Color.White.copy(alpha = 0.01f),
    242f / 360f to Color.White.copy(alpha = 0f),
    1f to Color.White.copy(alpha = 0f),
)

/** 碟心底色（封面加载中、透明封面后面）。 */
private val LABEL_BASE = Color(0xFF1F1A15)

/** 没有封面时的碟心：repeating-conic-gradient，#241f19 和 #1e1a15 每 14° 交替。 */
private val LABEL_STRIPES: Array<Pair<Float, Color>> = run {
    val out = ArrayList<Pair<Float, Color>>()
    var a = 0f
    var k = 0
    while (a < 360f) {
        val b = min(a + 14f, 360f)
        val color = if (k % 2 == 0) Color(0xFF241F19) else Color(0xFF1E1A15)
        out += a / 360f to color
        out += b / 360f to color
        a = b
        k++
    }
    out.toTypedArray()
}
private val LABEL_TEXT = Color.White.copy(alpha = 0.82f)
private const val LABEL_PADDING = 0.12f

/** 歌词字号相对宿主基准字号的倍数（原模组按页高 2.3%，比普通歌词小，再乘字号设置默认的 1.25）。 */
private const val TEXT_SCALE = 0.6f
private const val MIN_ROW_CHARS = 8f

/** 次要文字（歌名、翻译）的颜色：正文色的 70%（原模组用主题的次要文字色）。 */
private const val SECONDARY_ALPHA = 0.7f

/** 行距 0.9em（原模组相对 16px 的页面字号，约等于歌词字号的 0.68）、歌名行下面空 1.6em（约 1.2 倍歌词字号）。 */
private const val ROW_GAP = 0.68f
private const val HEADER_GAP = 1.2f

private const val ROWS_BEFORE = 2
private const val ROWS_AFTER = 4
private const val LINE_ENTER_MS = 320f
private const val POP_MS = 180f
private const val POP_SCALE = 0.06f

private val META_SEPARATORS = listOf(" - ", "－", " – ", " — ", " -", "- ", "-", "·")
