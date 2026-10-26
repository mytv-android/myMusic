package com.mytv.mymusic.ui.player

import android.content.Context
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.hct.Hct
import com.materialkolor.ktx.themeColors
import com.materialkolor.ktx.toColor
import com.materialkolor.ktx.toHct
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.LyricsBackgroundMode
import com.mytv.mymusic.data.RhythmIntensity
import com.mytv.mymusic.playback.RhythmAnalyzer
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 歌词背景每帧写出的律动信号，给封面光晕、进度条波幅这类跟节奏的 UI 用。
 *
 * 播放页里用 [rememberBackdropPulse] 建一次，同一个实例传给 [LyricsBackdrop] 和要读它的组件。
 * [bass] 每帧都在变，只能在绘制阶段读：`drawBehind {}`、`drawWithCache {}` 里的 `onDraw*` 或
 * `graphicsLayer {}` 的 lambda；放在组合阶段读会让整个播放页跟着每帧重组。[reactive] 和 [intensity]
 * 只随设置变化，在哪读都行。
 *
 * 和背景保持同一套幅度：
 * - 封面光晕：不透明度加 `FlowIntensity.of(intensity).cover * bass`（柔和最多 +0.04，标准 +0.08）。
 * - 进度条波幅：只在 `reactive && intensity == RhythmIntensity.Standard` 时乘 `0.7 + 0.5 * bass`，否则固定。
 */
@Stable
class BackdropPulse internal constructor() {
    /** 平滑后的低频，0..1。非律动模式、减少动画时恒为 0；暂停后一两秒内平滑落到 0。 */
    var bass by mutableFloatStateOf(0f)
        internal set

    /** 背景此刻是否在跟音乐律动：选了律动、没开减少动画、分析器可用。 */
    var reactive by mutableStateOf(false)
        internal set

    /** 用户选的律动强度；[reactive] 为 false 时没有意义。 */
    var intensity by mutableStateOf(RhythmIntensity.Standard)
        internal set
}

/** 见 [BackdropPulse]：每个播放页只建一个。 */
@Composable
fun rememberBackdropPulse(): BackdropPulse = remember { BackdropPulse() }

/**
 * 播放页背景。关闭时是纯色；“动态”和“律动”共用流光场：放大慢转的封面、四团封面色光、固定遮罩。
 * 律动时音乐只调流速、光团大小和光量，标准档在鼓点时从 [rippleOrigin]（按宽高的比例）散开一圈淡光环。
 * 减少动画时定格成静止的流光场，不跑帧循环也不做分析。
 */
@Composable
fun LyricsBackdrop(
    cover: String,
    playing: Boolean,
    mode: LyricsBackgroundMode,
    intensity: RhythmIntensity,
    trackKey: String,
    wide: Boolean,
    pulse: BackdropPulse,
    rippleOrigin: Offset,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    val context = LocalContext.current
    val analyzer = remember(context) { (context.applicationContext as? MyMusicApp)?.rhythmAnalyzer }
    val reactive = mode == LyricsBackgroundMode.Reactive && !reduceMotion && analyzer != null
    SideEffect {
        pulse.reactive = reactive
        pulse.intensity = intensity
        if (mode == LyricsBackgroundMode.Off || reduceMotion) pulse.bass = 0f
    }
    if (mode == LyricsBackgroundMode.Off) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }

    val scheme = MaterialTheme.colorScheme
    val moving = playing && !reduceMotion
    val motion = remember { FlowFieldMotion(moving) }
    // 帧计数只在绘制 / 图层回调里读，60/120Hz 推进时只重画背景，不重组播放页
    val tick = remember { mutableIntStateOf(0) }
    if (reactive && analyzer != null) RhythmFeed(analyzer, playing, trackKey, motion)

    val covers = remember { CoverSwap() }
    LaunchedEffect(cover) {
        val image = loadBackdropCover(context, cover)
        if (image == null && covers.current == null) return@LaunchedEffect
        covers.show(image)
        motion.startCoverFade()
    }

    val fallback = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primaryContainer)
    val extracted by produceState<List<Color>?>(blobColorCache.get(cover), cover) { value = coverBlobColors(context, cover) }
    val targetColors = remember(extracted, fallback) { blobColors(extracted, fallback) }
    val palette = remember { BlobPalette(targetColors) }
    SideEffect(targetColors) { palette.retarget(targetColors, motion) }

    SideEffect {
        motion.playing = moving
        motion.reactive = reactive
        motion.intensity = intensity
        if (reduceMotion) {
            motion.settle()
            covers.dropPrevious()
        }
    }
    if (!reduceMotion) {
        LaunchedEffect(motion, moving, reactive, covers.current, palette.to) {
            while (true) {
                val settled = withFrameNanos { now ->
                    motion.advanceTo(now)
                    pulse.bass = motion.bass
                    if (motion.coverFade >= 1f && covers.previous != null) covers.dropPrevious()
                    tick.intValue++
                    motion.isSettled
                }
                if (settled) break
            }
            // 暂停后流动已经停稳，不再空跑帧循环；播放、换封面或换色时按 key 重新启动
            motion.stopClock()
        }
    }

    Box(modifier.fillMaxSize().clipToBounds().background(scheme.surface)) {
        covers.previous?.let { image -> CoverSheet(image, wide, motion, tick) { motion.coverAlpha * (1f - motion.coverFade) } }
        covers.current?.let { image -> CoverSheet(image, wide, motion, tick) { motion.coverAlpha * motion.coverFade } }
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val m = size.maxDimension
                    val colors = palette.to
                    val blobBrushes = colors.map(::blobBrush)
                    val rippleBrushes = arrayOfNulls<Brush>(RIPPLE_STEPS)
                    val origin = Offset(size.width * rippleOrigin.x, size.height * rippleOrigin.y)
                    val scrim = scrimBrush(scheme.surface, wide)
                    onDrawBehind {
                        tick.intValue
                        val fade = motion.colorFade
                        for (i in FlowBlobs.indices) {
                            // 换色过渡的 0.45 秒里才临时建渐变，平时用缓存
                            val brush = if (fade >= 1f) blobBrushes[i] else blobBrush(lerp(palette.from[i], colors[i], fade))
                            val scale = m * motion.blobRadius(i) / UNIT
                            withTransform({
                                translate(size.width * motion.blobX(i), size.height * motion.blobY(i))
                                scale(scale, scale, Offset.Zero)
                            }) {
                                drawCircle(brush, UNIT, Offset.Zero, alpha = motion.blobAlpha(i), blendMode = BlendMode.Screen)
                            }
                        }
                        for (i in 0 until motion.rippleCount) {
                            val age = motion.rippleAge(i)
                            val step = (age / FlowFieldMotion.RIPPLE_LIFE_S * RIPPLE_STEPS).toInt().coerceIn(0, RIPPLE_STEPS - 1)
                            val brush = rippleBrushes[step]
                                ?: rippleBrush(scheme.primary, (step + 0.5f) / RIPPLE_STEPS * FlowFieldMotion.RIPPLE_LIFE_S)
                                    .also { rippleBrushes[step] = it }
                            val scale = m * (FlowFieldMotion.rippleRadius(age) + RIPPLE_OUTER) / UNIT
                            withTransform({
                                translate(origin.x, origin.y)
                                scale(scale, scale, Offset.Zero)
                            }) {
                                drawCircle(brush, UNIT, Offset.Zero, alpha = FlowFieldMotion.rippleAlpha(age), blendMode = BlendMode.Screen)
                            }
                        }
                        drawRect(scrim)
                    }
                },
        )
    }
}

/** 律动模式下的分析器：只在播放且页面在前台时取样，切歌时清掉上一首的增益历史，分析帧交给 [motion]。 */
@Composable
private fun RhythmFeed(analyzer: RhythmAnalyzer, playing: Boolean, trackKey: String, motion: FlowFieldMotion) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, analyzer, playing) {
        fun updateCapture() {
            analyzer.setActive(
                playing && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
        }
        val observer = LifecycleEventObserver { _, _ -> updateCapture() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateCapture()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            analyzer.setActive(false)
        }
    }
    // 同步重置不需要协程；带 key 的 SideEffect 保留切歌执行顺序且不引入挂起开销
    SideEffect(analyzer, trackKey) { analyzer.reset() }
    LaunchedEffect(analyzer, motion) { analyzer.frames.collect { motion.feed(it) } }
}

/**
 * 底层封面：画在长边 1.7 倍的正方形里，慢慢旋转并沿相位漂移。
 * 节点按 1/[COVER_DOWNSCALE] 的尺寸布局、再用图层放大：模糊只在小图层上做，旋转和漂移只改图层属性，不重录绘制。
 */
@Composable
private fun CoverSheet(image: ImageBitmap, wide: Boolean, motion: FlowFieldMotion, tick: IntState, opacity: () -> Float) {
    Spacer(
        Modifier
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val side = (maxOf(width, height) * COVER_SCALE / COVER_DOWNSCALE).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(side, side))
                val centerY = height * if (wide) 0.5f else 0.38f
                layout(width, height) {
                    placeable.placeWithLayer((width - side) / 2, (centerY - side / 2f).roundToInt()) {
                        tick.intValue
                        scaleX = COVER_DOWNSCALE
                        scaleY = COVER_DOWNSCALE
                        translationX = width * motion.coverDriftX
                        translationY = height * motion.coverDriftY
                        rotationZ = Math.toDegrees(motion.angle).toFloat()
                        alpha = opacity()
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                }
            }
            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.graphicsLayer { renderEffect = coverBlur(size.width) } else Modifier)
            .drawBehind { drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())) },
    )
}

/**
 * 原型的模糊是长边的 3.5%（CSS 标准差）。Android 的模糊半径按 σ ≈ 0.577r + 0.5 换算成 σ，
 * 而这个图层还要放大 [COVER_DOWNSCALE] 倍，所以按图层边长折算。低于 Android 12 时只靠 24px 封面放大后的柔和。
 */
private fun coverBlur(side: Float): RenderEffect {
    val sigma = side * 0.035f / COVER_SCALE
    val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(1f)
    return BlurEffect(radius, radius, TileMode.Decal)
}

/** 正在显示和正在淡出的两张封面。 */
@Stable
private class CoverSwap {
    var current by mutableStateOf<ImageBitmap?>(null)
        private set
    var previous by mutableStateOf<ImageBitmap?>(null)
        private set

    fun show(image: ImageBitmap?) {
        previous = current
        current = image
    }

    fun dropPrevious() {
        previous = null
    }
}

/** 光团颜色：换色时从当前显示的颜色过渡到新颜色，进度由 [FlowFieldMotion.colorFade] 推进。 */
@Stable
private class BlobPalette(initial: List<Color>) {
    var from = initial
        private set
    var to by mutableStateOf(initial)
        private set

    fun retarget(colors: List<Color>, motion: FlowFieldMotion) {
        if (colors == to) return
        val fade = motion.colorFade
        from = if (fade >= 1f) to else List(colors.size) { lerp(from[it], to[it], fade) }
        to = colors
        motion.startColorFade()
    }
}

private suspend fun loadBackdropCover(context: Context, url: String): ImageBitmap? {
    if (url.isBlank()) return null
    val request = ImageRequest.Builder(context).data(url).size(BACKDROP_COVER_PX).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return result.image.toBitmap().asImageBitmap()
}

private val blobColorCache = LruCache<String, List<Color>>(32)

/** 封面量化后得分最高的几个颜色，在后台线程算，按封面地址缓存；取不到时返回空表，由配色补上。 */
private suspend fun coverBlobColors(context: Context, url: String): List<Color> {
    if (url.isBlank()) return emptyList()
    blobColorCache.get(url)?.let { return it }
    // 与 CoverSeedRepository 用同样的尺寸和软件位图，通常直接命中 Coil 内存缓存
    val request = ImageRequest.Builder(context).data(url).size(COLOR_SAMPLE_PX).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return emptyList()
    val colors = withContext(Dispatchers.Default) {
        runCatching {
            // 透明色当“没有结果”的占位：灰度封面过滤后一个都不剩时 Score 会返回它
            result.image.toBitmap().asImageBitmap()
                .themeColors(fallback = Color.Transparent, desired = FlowBlobs.size)
                .filter { it.alpha > 0f }
        }.getOrNull()
    } ?: return emptyList()
    blobColorCache.put(url, colors)
    return colors
}

/** 缺的位置用播放页配色补（第二团对应 tertiary，与原型一致），再统一夹明度。 */
private fun blobColors(extracted: List<Color>?, fallback: List<Color>): List<Color> =
    List(FlowBlobs.size) { i -> clampTone(extracted?.getOrNull(i) ?: fallback[i]) }

/** 太暗的颜色在 Screen 下看不出，太亮会把歌词底色冲淡；明度夹到原型调过的区间，色相和彩度不动。 */
private fun clampTone(color: Color): Color {
    val hct = color.toHct()
    val tone = hct.tone.coerceIn(MIN_BLOB_TONE, MAX_BLOB_TONE)
    return if (tone == hct.tone) color else Hct.from(hct.hue, hct.chroma, tone).toColor()
}

/** 以原点为心、半径 [UNIT] 的光团渐变；画的时候平移缩放，渐变本身可以一直复用。 */
private fun blobBrush(color: Color): Brush = Brush.radialGradient(
    0f to color,
    0.45f to color.copy(alpha = 0.55f),
    1f to color.copy(alpha = 0f),
    center = Offset.Zero,
    radius = UNIT,
)

/** 光环：外径以内从“内径”开始渐亮、0.72 处最亮再淡出；内外径之比只随年龄变，按年龄分档缓存。 */
private fun rippleBrush(color: Color, age: Float): Brush {
    val radius = FlowFieldMotion.rippleRadius(age)
    val inner = ((radius - RIPPLE_INNER) / (radius + RIPPLE_OUTER)).coerceAtLeast(0f)
    val clear = color.copy(alpha = 0f)
    return Brush.radialGradient(
        0f to clear,
        inner to clear,
        inner + 0.72f * (1f - inner) to color,
        1f to clear,
        center = Offset.Zero,
        radius = UNIT,
    )
}

/** 固定遮罩，不随音乐变化：手机竖向往下压暗，宽屏横向往右（歌词一侧）压暗。 */
private fun scrimBrush(surface: Color, wide: Boolean): Brush = if (wide) {
    Brush.horizontalGradient(
        0f to surface.copy(alpha = 0.18f),
        0.46f to surface.copy(alpha = 0.40f),
        1f to surface.copy(alpha = 0.74f),
    )
} else {
    Brush.verticalGradient(
        0f to surface.copy(alpha = 0.14f),
        0.46f to surface.copy(alpha = 0.30f),
        1f to surface.copy(alpha = 0.82f),
    )
}

private const val BACKDROP_COVER_PX = 24
private const val COLOR_SAMPLE_PX = 128
private const val COVER_SCALE = 1.7f
private const val COVER_DOWNSCALE = 8f
private const val UNIT = 256f
private const val RIPPLE_STEPS = 18
private const val RIPPLE_INNER = 0.14f
private const val RIPPLE_OUTER = 0.04f
private const val MIN_BLOB_TONE = 30.0
private const val MAX_BLOB_TONE = 72.0
