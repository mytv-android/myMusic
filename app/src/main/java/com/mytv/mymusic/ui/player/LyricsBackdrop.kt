package com.mytv.mymusic.ui.player

import android.content.Context
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
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
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.ktx.themeColors
import com.materialkolor.ktx.toColor
import com.materialkolor.ktx.toHct
import com.materialkolor.material3.rememberDynamicColorScheme
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.LyricsBackgroundMode
import com.mytv.mymusic.data.RhythmIntensity
import com.mytv.mymusic.playback.ONSET_DETECT_LAG_S
import com.mytv.mymusic.playback.PlaybackClock
import com.mytv.mymusic.playback.RhythmAnalyzer
import com.mytv.mymusic.playback.RhythmFrame
import com.mytv.mymusic.playback.RhythmTimeline
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.theme.LocalAppearance
import com.mytv.mymusic.ui.theme.LocalSeedColor
import com.mytv.mymusic.ui.theme.coverColorCacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 歌词背景每帧写出的律动信号，给封面、光晕、进度条这类跟节奏的 UI 用。
 *
 * 播放页里用 [rememberBackdropPulse] 建一次，同一个实例传给 [LyricsBackdrop] 和要读它的组件。
 * [bass]、[swell]、[beat]、[glow] 和由它们算出的 [coverScale]、[haloBoost]、[haloScale] 每帧都在变，只能在绘制阶段读：
 * `drawBehind {}`、`drawWithCache {}` 里的 `onDraw*` 或 `graphicsLayer {}` 的 lambda；放在组合阶段读会让整个播放页
 * 跟着每帧重组。[reactive]、[intensity] 只随设置变化，[bpm] 只在锁上、换速度时变，在哪读都行。
 *
 * 和背景保持同一套幅度（[FlowIntensity]）：
 * - 封面：跟拍弹一下（[coverScale]，标准 3%、强烈 5%）。
 * - 封面光晕：不透明度加段落的慢包络和拍子（[haloBoost]），跟拍放大一点（[haloScale]）。
 * - 形状光环：从封面轮廓散开（[rippleCount] 圈，见 [rippleGrow]、[rippleAlpha]），画在封面后面。
 * - 进度条：标准和强烈档波幅跟 [swell] 起伏（见 pulseWaveScale），锁上拍子后每拍走一个波长（见 pulseWaveBpm）。
 */
@Stable
class BackdropPulse internal constructor() {
    /** 平滑后的低频，0..1。非律动模式、减少动画时恒为 0；暂停后一两秒内平滑落到 0。 */
    var bass by mutableFloatStateOf(0f)
        internal set

    /** 低频的慢包络（0.3s 起、1s 落），0..1：随段落的低频强弱起伏，不在单拍上跳。归零的时机同 [bass]。 */
    var swell by mutableFloatStateOf(0f)
        internal set

    /** 拍子包络 0..1（已乘上力度）：拍前 50ms 起势、0.16s 回落，封面弹跳用。不跟拍时为 0。 */
    var beat by mutableFloatStateOf(0f)
        internal set

    /** 拍子包络 0..1：0.28s 回落，光晕用。 */
    var glow by mutableFloatStateOf(0f)
        internal set

    /** 拍子网格锁住时的速度（BPM），没锁上是 0；变化不到 2% 不更新。 */
    var bpm by mutableFloatStateOf(0f)
        internal set

    /** 背景此刻是否在跟音乐律动：选了律动、没开减少动画、分析器可用。 */
    var reactive by mutableStateOf(false)
        internal set

    /** 用户选的律动强度；[reactive] 为 false 时没有意义。 */
    var intensity by mutableStateOf(RhythmIntensity.Standard)
        internal set

    private val flow: FlowIntensity get() = FlowIntensity.of(intensity)

    /** 封面的缩放倍数。 */
    val coverScale: Float get() = 1f + flow.pop * beat

    /** 光晕不透明度在 0.62 之上加多少。 */
    val haloBoost: Float get() = flow.cover * swell + flow.glow * glow

    /** 光晕的缩放倍数。 */
    val haloScale: Float get() = 1f + flow.halo * glow

    // 形状光环：帧循环每帧抄一份，读的一方先读 rippleFrame（快照状态）再读数组，光环在的时候跟着每帧重画
    internal val rippleGrow = FloatArray(FlowFieldMotion.MAX_RIPPLES)
    internal val rippleAlpha = FloatArray(FlowFieldMotion.MAX_RIPPLES)
    internal var rippleCount = 0
        private set
    internal var rippleFrame by mutableIntStateOf(0)
        private set

    internal fun publish(motion: FlowFieldMotion) {
        bass = motion.bass
        swell = motion.swell
        beat = motion.beat
        glow = motion.glow
        val count = motion.rippleCount
        if (count > 0 || rippleCount > 0) {
            for (i in 0 until count) {
                val age = motion.rippleAge(i)
                rippleGrow[i] = FlowFieldMotion.rippleGrow(age)
                rippleAlpha[i] = FlowFieldMotion.rippleAlpha(age, motion.rippleAmplitude(i))
            }
            rippleCount = count
            rippleFrame++
        }
        val locked = motion.lockedBpm
        val shown = bpm
        // 进度条的波速是组合阶段的参数：只在锁上、解锁或速度变了 2% 以上时改
        if ((locked == 0f) != (shown == 0f) || (shown > 0f && abs(locked / shown - 1f) > BPM_HYSTERESIS)) bpm = locked
    }

    internal fun clear() {
        bass = 0f
        swell = 0f
        beat = 0f
        glow = 0f
        bpm = 0f
        if (rippleCount > 0) {
            rippleCount = 0
            rippleFrame++
        }
    }
}

private const val BPM_HYSTERESIS = 0.02f

/** 见 [BackdropPulse]：每个播放页只建一个。 */
@Composable
fun rememberBackdropPulse(): BackdropPulse = remember { BackdropPulse() }

/**
 * 播放页背景。关闭时是纯色；“动态”和“律动”共用流光场：放大慢转的封面、四团封面色光、固定遮罩。
 * 律动时段落的强弱调流速、光团大小和光量；拍子按此刻听到的位置起拍（[rhythmOffsetMs] 是跟拍校准，正数让画面晚一点），
 * 推一把流光，并通过 [pulse] 让封面弹跳、光晕跟拍、封面轮廓散开光环。
 * 减少动画（应用设置或系统“移除动画”）时定格成静止的流光场，不跑帧循环也不做分析。
 * 封面和光团画在 1/2（电视 1/4）分辨率的离屏图层里再放大，只有 [scrim] 遮罩按全分辨率画在最上面；
 * 换遮罩样式（如进入歌词页）时淡变过去。
 */
@Composable
fun LyricsBackdrop(
    cover: String,
    playing: Boolean,
    mode: LyricsBackgroundMode,
    intensity: RhythmIntensity,
    wide: Boolean,
    pulse: BackdropPulse,
    modifier: Modifier = Modifier,
    rhythmOffsetMs: Int = 0,
    scrim: BackdropScrim = if (wide) BackdropScrim.Wide else BackdropScrim.Phone,
) {
    // 系统“移除动画”（动画时长缩放为 0）按应用里的“减少动画”处理：原来的 rememberInfiniteTransition 会自己停，帧循环不会
    val reduceMotion = LocalAppearance.current.reduceMotion || rememberSystemAnimationsOff()
    val tv = LocalAppWindow.current.isTv
    val context = LocalContext.current
    val analyzer = remember(context) { (context.applicationContext as? MyMusicApp)?.rhythmAnalyzer }
    val reactive = mode == LyricsBackgroundMode.Reactive && !reduceMotion && analyzer != null
    SideEffect {
        pulse.reactive = reactive
        pulse.intensity = intensity
        if (mode == LyricsBackgroundMode.Off || reduceMotion) pulse.clear()
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
    val feed = remember(analyzer) { analyzer?.let(::RhythmFeedReader) }
    if (reactive && analyzer != null) RhythmFeed(analyzer, playing)
    val offsetUs = rhythmOffsetMs * 1_000L

    val covers = remember { CoverSwap() }
    LaunchedEffect(cover) {
        val image = loadBackdropCover(context, cover)
        if (image == null && covers.current == null) return@LaunchedEffect
        covers.show(image)
        motion.startCoverFade()
    }

    // 补位颜色取配色的目标值：过渡中的 MaterialTheme.colorScheme 每帧都不同，会让换色过渡和帧循环每帧重来
    val target = playerTargetScheme()
    val fallback = listOf(target.primary, target.tertiary, target.secondary, target.primaryContainer)
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
        LaunchedEffect(motion, moving, reactive, covers.current, palette.to, tv, offsetUs) {
            // 电视上限 30 帧，和封面旋转、逐字歌词落在同一批垂直同步上（见 TvFrameGate）
            while (true) {
                val settled = withFrameNanos { now ->
                    if (tv && !TvFrameGate.due(now)) return@withFrameNanos false
                    if (reactive && feed != null) feed.feedUntil(now, offsetUs, motion) else motion.beatTimeS = Double.NaN
                    motion.advanceTo(now)
                    pulse.publish(motion)
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

    val scrimBlend = remember { ScrimBlend(scrim) }
    val scrimSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    LaunchedEffect(scrimBlend, scrim) { scrimBlend.animateTo(scrim, scrimSpec) }

    val surface = scheme.surface
    val downscale = if (tv) TV_FIELD_DOWNSCALE else FIELD_DOWNSCALE
    // 流光层已经缩小 downscale 倍，封面节点相对屏幕仍是 1/COVER_DOWNSCALE，模糊半径不变
    val coverDownscale = COVER_DOWNSCALE / downscale
    Box(modifier.fillMaxSize().clipToBounds()) {
        Box(
            Modifier
                .fillMaxSize()
                .reducedResolution(downscale)
                .drawWithCache {
                    val m = size.maxDimension
                    val colors = palette.to
                    val blobBrushes = colors.map(::blobBrush)
                    onDrawWithContent {
                        // 底色也画进离屏图层：图层不透明，光团的 Screen 混合和直接叠在底色、封面上时一样
                        drawRect(surface)
                        drawContent()
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
                    }
                },
        ) {
            covers.previous?.let { image ->
                CoverSheet(image, wide, coverDownscale, motion, tick) { motion.coverAlpha * (1f - motion.coverFade) }
            }
            covers.current?.let { image ->
                CoverSheet(image, wide, coverDownscale, motion, tick) { motion.coverAlpha * motion.coverFade }
            }
        }
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    // 只有样式淡变时这里读到的值才会变；平时渐变一直复用，也不跟着帧循环重画
                    val stops = scrimBlend.stops
                    val vertical = stops.verticalBrush(surface)
                    val horizontal = stops.horizontalBrush(surface)
                    val footerTop = size.height * (1f - SCRIM_FOOTER_SPAN)
                    val footer = stops.footerBrush(surface, footerTop, size.height)
                    onDrawBehind {
                        vertical?.let { drawRect(it) }
                        horizontal?.let { drawRect(it) }
                        // 电视底部那道只铺下面 40%，少填一大块全屏像素
                        footer?.let { drawRect(it, Offset(0f, footerTop), Size(size.width, size.height - footerTop)) }
                    }
                },
        )
    }
}

/**
 * 系统开发者选项或无障碍里的“移除动画”：动画时长缩放为 0。
 * 读的是 Compose 帧时钟上下文里的 [MotionDurationScale]，它背后是快照状态，设置变了这里会重组。
 */
@Composable
internal fun rememberSystemAnimationsOff(): Boolean {
    val scope = rememberCoroutineScope()
    val durationScale = remember(scope) { scope.coroutineContext[MotionDurationScale] }
    return durationScale != null && durationScale.scaleFactor == 0f
}

/**
 * 播放页配色的目标值（不带过渡），参数与 PlayerTheme 一致。PlayerTheme 用 animateColorScheme 过渡，
 * 对外只有过渡中的配色，所以这里按同样的种子和风格再生成一份；按参数缓存，只在换种子色或风格时重算。
 */
@Composable
private fun playerTargetScheme(): ColorScheme {
    val appearance = LocalAppearance.current
    return rememberDynamicColorScheme(
        seedColor = LocalSeedColor.current,
        isDark = true,
        isAmoled = false,
        style = appearance.playerStyle.palette,
        contrastLevel = appearance.contrast.toDouble(),
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
}

/**
 * 按 1/[factor] 的尺寸布局、再用离屏图层放大回原尺寸：图层里的每一遍绘制只填 1/factor² 的像素，
 * 最后整张图层双线性放大画一遍。流光本来就是模糊的，看不出分辨率降低；原型也是画在半分辨率画布上。
 */
private fun Modifier.reducedResolution(factor: Int): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val placeable = measurable.measure(Constraints.fixed(ceilDiv(width, factor), ceilDiv(height, factor)))
    layout(width, height) {
        placeable.placeWithLayer(0, 0) {
            scaleX = factor.toFloat()
            scaleY = factor.toFloat()
            transformOrigin = TransformOrigin(0f, 0f)
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }
}

private fun ceilDiv(value: Int, divisor: Int): Int = ((value + divisor - 1) / divisor).coerceAtLeast(1)

/**
 * 律动模式下的分析器：只在播放且页面在前台时取样（海报墙的跟拍也用它；两处都在时按 [RhythmAnalyzer.capture] 合起来算）。分析帧由帧循环通过 [RhythmFeedReader] 按听到的位置取走。
 * 切歌不用在这里重置：跳转、换歌由 [com.mytv.mymusic.playback.RhythmAudioSink] 在音频线程上判断，
 * 无缝衔接时分析比界面先进入下一首，界面这边知道换歌时新歌的前半秒已经分析过了。
 */
@Composable
internal fun RhythmFeed(analyzer: RhythmAnalyzer, playing: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, analyzer, playing) {
        val owner = Any()
        fun updateCapture() {
            analyzer.capture(owner, playing && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        val observer = LifecycleEventObserver { _, _ -> updateCapture() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateCapture()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            analyzer.capture(owner, false)
        }
    }
}

/**
 * 帧循环每帧调用：按“此刻听到的位置 − 跟拍校准”给 [FlowFieldMotion] 定跟拍时间和网格，再交出已经播到的分析帧。
 * 拍子时钟在拍前 [BeatClock.PRE_S] 起势，低频起拍要提前这么多交给它，所以多取这一点；电平只用 pts 不晚于跟拍时间的帧，
 * 早取的留到下一帧。还不知道听到哪儿（刚跳转、还没出声）时什么都不取，跟拍时间记成 NaN。
 */
internal class RhythmFeedReader(private val analyzer: RhythmAnalyzer) {
    private val cursor = RhythmTimeline.Cursor()
    private val pending = ArrayDeque<RhythmFrame>()
    private var lastKickCount = NO_KICK

    fun feedUntil(frameNanos: Long, offsetUs: Long, motion: FlowFieldMotion) {
        val audible = analyzer.clock.audibleUs(frameNanos)
        if (audible == PlaybackClock.UNSET) {
            motion.beatTimeS = Double.NaN
            return
        }
        val nowUs = audible - offsetUs
        val nowS = nowUs / 1e6
        motion.beatTimeS = nowS
        motion.beatGrid = analyzer.beats.gridAt(nowS)
        val aheadUs = nowUs + PRE_US
        // 往回跳（拖进度、单曲循环）后，留着的帧都在新位置后面，作废
        if (pending.isNotEmpty() && pending.first().ptsUs > aheadUs) pending.clear()
        analyzer.timeline.drain(cursor, aheadUs) { frame ->
            // 第一帧只记序号，不把分析器之前攒下的计数当成新鼓点
            if (lastKickCount != NO_KICK && frame.kickCount > lastKickCount) {
                motion.beats.pushKick(frame.ptsUs / 1e6 - ONSET_DETECT_LAG_S)
            }
            lastKickCount = frame.kickCount
            pending.addLast(frame)
        }
        var latest: RhythmFrame? = null
        while (pending.isNotEmpty() && pending.first().ptsUs <= nowUs) latest = pending.removeFirst()
        latest?.let(motion::feed)
    }

    private companion object {
        const val NO_KICK = -1
        val PRE_US = (BeatClock.PRE_S * 1e6).toLong()
    }
}

/**
 * 底层封面：画在长边 1.7 倍的正方形里，慢慢旋转并沿相位漂移。
 * 节点按 1/[downscale] 的尺寸布局、再用图层放大：模糊只在小图层上做，旋转和漂移只改图层属性，不重录绘制。
 */
@Composable
private fun CoverSheet(
    image: ImageBitmap,
    wide: Boolean,
    downscale: Float,
    motion: FlowFieldMotion,
    tick: IntState,
    opacity: () -> Float,
) {
    Spacer(
        Modifier
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val side = (maxOf(width, height) * COVER_SCALE / downscale).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(side, side))
                val centerY = height * if (wide) 0.5f else 0.38f
                layout(width, height) {
                    placeable.placeWithLayer((width - side) / 2, (centerY - side / 2f).roundToInt()) {
                        tick.intValue
                        scaleX = downscale
                        scaleY = downscale
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
 * 而这个图层到屏幕上总共要放大 [COVER_DOWNSCALE] 倍，所以按图层边长折算。低于 Android 12 时只靠 24px 封面放大后的柔和。
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
    // 单独的缓存键：和大封面共用地址做键时，24px 的小图会顶掉内存里的大封面，每次切歌大封面都得重新解码
    val request = ImageRequest.Builder(context).data(url).size(BACKDROP_COVER_PX).memoryCacheKey("$url#backdrop").build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return result.image.toBitmap().asImageBitmap()
}

private val blobColorCache = LruCache<String, List<Color>>(32)

/** 封面量化后得分最高的几个颜色，在后台线程算，按封面地址缓存；取不到时返回空表，由配色补上。 */
private suspend fun coverBlobColors(context: Context, url: String): List<Color> {
    if (url.isBlank()) return emptyList()
    blobColorCache.get(url)?.let { return it }
    // 与 CoverSeedRepository 用同样的尺寸、软件位图和缓存键，通常直接命中它刚取过色的那张；键与大封面分开，互不顶掉
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(COLOR_SAMPLE_PX)
        .allowHardware(false)
        .memoryCacheKey(coverColorCacheKey(url))
        .build()
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

/**
 * 流光场上面的固定遮罩，保证文字对比度，不随音乐变化。数值对照原型：
 * - [Phone]：竖向往下压暗。
 * - [PhoneLyrics]：手机歌词页，整体再暗一些，满屏歌词也看得清。
 * - [Wide]：平板横屏，横向往右（歌词一侧）压暗。
 * - [WideFocus]：平板专注歌词，歌词铺满右侧大半屏，整体再暗一些（同手机歌词页的思路）。
 * - [Tv]：电视，横向压得更暗，底部再压一道给控制条。
 */
enum class BackdropScrim(internal val stops: ScrimStops) {
    Phone(ScrimStops(top = 0.12f, middle = 0.28f, bottom = 0.84f)),
    PhoneLyrics(ScrimStops(top = 0.34f, middle = 0.42f, bottom = 0.86f)),
    Wide(ScrimStops(start = 0.18f, center = 0.40f, end = 0.74f)),
    WideFocus(ScrimStops(start = 0.36f, center = 0.53f, end = 0.80f)),
    Tv(ScrimStops(start = 0.22f, center = 0.56f, end = 0.78f, centerAt = 0.5f, footer = 0.70f)),
}

/**
 * 遮罩各段相对底色的不透明度：竖向渐变取顶、46%、底三处，横向渐变取左、[centerAt]、右三处；
 * [footer] 是电视底部那道，在底边最暗，往上 40% 淡到 0。用不到的方向全为 0，换样式时逐项插值。
 */
@Immutable
internal data class ScrimStops(
    val top: Float = 0f,
    val middle: Float = 0f,
    val bottom: Float = 0f,
    val start: Float = 0f,
    val center: Float = 0f,
    val end: Float = 0f,
    val centerAt: Float = SCRIM_MIDDLE_AT,
    val footer: Float = 0f,
) {
    fun lerp(to: ScrimStops, fraction: Float): ScrimStops = ScrimStops(
        top = mix(top, to.top, fraction),
        middle = mix(middle, to.middle, fraction),
        bottom = mix(bottom, to.bottom, fraction),
        start = mix(start, to.start, fraction),
        center = mix(center, to.center, fraction),
        end = mix(end, to.end, fraction),
        centerAt = mix(centerAt, to.centerAt, fraction),
        footer = mix(footer, to.footer, fraction),
    )

    private fun mix(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction
}

private fun ScrimStops.verticalBrush(surface: Color): Brush? =
    if (top + middle + bottom < MIN_SCRIM_ALPHA) {
        null
    } else {
        Brush.verticalGradient(
            0f to surface.copy(alpha = top),
            SCRIM_MIDDLE_AT to surface.copy(alpha = middle),
            1f to surface.copy(alpha = bottom),
        )
    }

private fun ScrimStops.horizontalBrush(surface: Color): Brush? =
    if (start + center + end < MIN_SCRIM_ALPHA) {
        null
    } else {
        Brush.horizontalGradient(
            0f to surface.copy(alpha = start),
            centerAt to surface.copy(alpha = center),
            1f to surface.copy(alpha = end),
        )
    }

private fun ScrimStops.footerBrush(surface: Color, startY: Float, endY: Float): Brush? =
    if (footer < MIN_SCRIM_ALPHA) {
        null
    } else {
        Brush.verticalGradient(
            0f to surface.copy(alpha = 0f),
            1f to surface.copy(alpha = footer),
            startY = startY,
            endY = endY,
        )
    }

/** 遮罩在两种样式之间淡变；起点是切换那一刻正显示的值，中途再切也不会跳。 */
@Stable
private class ScrimBlend(initial: BackdropScrim) {
    private var target = initial
    private var from = initial.stops
    private var to by mutableStateOf(initial.stops)
    private val progress = Animatable(1f)

    /** 读进度和终点，在 drawWithCache 的缓存块里读，淡变期间每帧重建一次渐变。 */
    val stops: ScrimStops get() = from.lerp(to, progress.value)

    suspend fun animateTo(scrim: BackdropScrim, spec: AnimationSpec<Float>) {
        if (scrim == target) return
        from = stops
        to = scrim.stops
        target = scrim
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
}

private const val BACKDROP_COVER_PX = 24
private const val COLOR_SAMPLE_PX = 128
private const val COVER_SCALE = 1.7f
private const val COVER_DOWNSCALE = 8f
private const val FIELD_DOWNSCALE = 2
private const val TV_FIELD_DOWNSCALE = 4
private const val UNIT = 256f
private const val MIN_BLOB_TONE = 30.0
private const val MAX_BLOB_TONE = 72.0
private const val SCRIM_MIDDLE_AT = 0.46f
private const val SCRIM_FOOTER_SPAN = 0.4f
private const val MIN_SCRIM_ALPHA = 0.002f
