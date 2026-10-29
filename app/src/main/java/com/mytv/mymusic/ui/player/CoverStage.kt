package com.mytv.mymusic.ui.player

import android.os.Build
import androidx.collection.FloatFloatPair
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.MutableCubic
import androidx.graphics.shapes.RoundedPolygon
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.ui.CoverBox
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.rememberSettlingOffsetState
import com.mytv.mymusic.ui.components.sharedNowPlayingCover
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 形状封面：封面裁成 MaterialShapes 形状，后面垫一层放大模糊的同图光晕。
 *
 * - 形状：[shapeChoice] 为“随机”时每首歌抽一种（[PlayerCoverShapes]，关掉再开不换）；固定时始终用那一种。
 *   暂停变成圆角方形看全图（慢速空间弹簧），切歌形变到新形状；固定模式切歌形状不变，只交叉淡入封面图。
 * - 旋转：播放时外轮廓 40 秒一圈（没有封面图时转的是首字占位的轮廓），画面本身保持正向；暂停时停下并随形变转回正向。
 *   减少动画时不转、形变直接切换。电视上旋转和背景共用 30 帧节拍（[TvFrameGate]）。
 * - [collapsed]：收成圆形（手机歌词页左上角的小封面），光晕淡出，手势和读屏描述交给调用方。
 * - 跟拍（[pulse]）：封面弹一下、光晕亮一下并放大一点，封面轮廓往外散开同形状的光环（画在光晕后面）。
 *
 * 形变和旋转都只在绘制阶段读：遮罩路径按 Morph 进度缓存，旋转是画布变换，逐帧不触发重组和布局。
 * 尺寸由 [modifier] 决定（正方形）；光晕画在边界外，父布局不要裁剪。
 *
 * @param trackKey 歌曲 key：随机形状按它记忆，封面图按 [cover] 地址交叉淡入。
 * @param pulse 律动信号；光晕随低频慢包络变亮，封面、光晕和光环跟拍。没有背景律动时传 null。
 * @param onSkip 横滑切歌（-1 上一首，1 下一首）；null 时不响应横滑。
 * @param onDoubleTap 双击（收藏）；null 时不响应双击。
 * @param sharedElement 是否参与迷你播放器 ↔ 播放页的共享封面转场（只有手机播放页需要）。
 */
@Composable
fun CoverStage(
    cover: String,
    title: String,
    trackKey: String,
    playing: Boolean,
    shapeChoice: CoverShapeChoice,
    modifier: Modifier = Modifier,
    pulse: BackdropPulse? = null,
    collapsed: Boolean = false,
    halo: Boolean = true,
    onSkip: ((Int) -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    sharedElement: Boolean = false,
) {
    // 和背景一样把系统“移除动画”当成减少动画：旋转是自己跑的帧循环，不受动画时长缩放影响
    val reduceMotion = LocalAppearance.current.reduceMotion || rememberSystemAnimationsOff()
    val motion = MaterialTheme.motionScheme
    val songShape = remember(shapeChoice, trackKey) { PlayerCoverShapes.resolve(shapeChoice, trackKey) }
    val target = when {
        collapsed -> CollapsedPolygon
        !playing -> PausedPolygon
        else -> coverPolygon(songShape)
    }
    // 打开播放页时从圆角方形（迷你播放器的样子）形变到当前形状，和共享封面的飞入同时进行
    val stage = remember { CoverStageState(PausedPolygon) }
    val slow = motion.slowSpatialSpec<Float>()
    val default = motion.defaultSpatialSpec<Float>()
    LaunchedEffect(stage, target, reduceMotion) {
        val spec = when {
            reduceMotion -> null
            // 歌词页的收放和位移同一条弹簧；暂停 / 继续用慢速空间弹簧
            target === CollapsedPolygon || stage.restsOn(CollapsedPolygon) -> default
            target === PausedPolygon || stage.restsOn(PausedPolygon) -> slow
            else -> default
        }
        stage.morphTo(target, spec)
    }
    val spin = !collapsed && ambientSpinEnabled(playing, reduceMotion)
    val tv = LocalAppWindow.current.isTv
    LaunchedEffect(stage, spin, reduceMotion, tv) {
        when {
            spin -> {
                var last = withFrameNanos { it }
                while (true) {
                    withFrameNanos { now ->
                        // 按真实间隔累加角度：电视上隔一帧才转一次，转速不变
                        if (!tv || TvFrameGate.due(now)) {
                            stage.angle = advanceSpin(stage.angle, now - last)
                            last = now
                        }
                    }
                }
            }
            reduceMotion -> stage.angle = 0f
            // 圆角方形四向对称，转回最近的直角就是正向
            else -> animate(stage.angle, restingAngle(stage.angle), animationSpec = slow) { value, _ -> stage.angle = value }
        }
    }

    val drag = rememberSettlingOffsetState()
    val threshold = with(LocalDensity.current) { 88.dp.toPx() }
    val currentThreshold by rememberUpdatedState(threshold)
    // pointerInput 不随回调重启，回调取最新的，否则切歌后双击收藏的还是旧歌
    val currentSkip by rememberUpdatedState(onSkip)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val gestures = !collapsed && (onSkip != null || onDoubleTap != null)
    val haloAlpha = animateFloatAsState(if (collapsed || !halo) 0f else 1f, motion.defaultEffectsSpec(), label = "halo")

    Box(
        modifier
            .then(
                if (!gestures) {
                    Modifier
                } else {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { currentDoubleTap?.invoke() })
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { drag.begin() },
                                onDragEnd = {
                                    val value = drag.offset
                                    if (abs(value) > currentThreshold) currentSkip?.invoke(if (value < 0) 1 else -1)
                                    drag.settle()
                                },
                                onDragCancel = { drag.settle() },
                            ) { change, amount ->
                                if (currentSkip == null) return@detectHorizontalDragGestures
                                change.consume()
                                drag.add(amount * 0.5f)
                            }
                        }
                },
            )
            // 收起成小圆时由调用方给出“回到封面”之类的描述；展开时读歌名，滑动和双击给出等价的自定义操作
            .then(
                if (collapsed) {
                    Modifier
                } else {
                    Modifier.semantics {
                        contentDescription = "$title 的封面"
                        customActions = buildList {
                            if (onSkip != null) {
                                add(CustomAccessibilityAction("下一首") { currentSkip?.invoke(1); true })
                                add(CustomAccessibilityAction("上一首") { currentSkip?.invoke(-1); true })
                            }
                            if (onDoubleTap != null) add(CustomAccessibilityAction("收藏") { currentDoubleTap?.invoke(); true })
                        }
                    }
                },
            ),
    ) {
        if (halo && pulse != null) {
            CoverRipples(stage, pulse, haloAlpha, offset = { drag.offset }, fadeDistance = { currentThreshold * 4 })
        }
        if (halo) {
            CoverHalo(cover, pulse, haloAlpha, offset = { drag.offset }, fadeDistance = { currentThreshold * 4 })
        }
        Box(
            Modifier
                .fillMaxSize()
                // 共享封面不另加浮层裁切：下面的遮罩是内容的一部分，飞行途中的画面本身就按此刻的形状和角度裁好了。
                // （按形状另给 OverlayClip 时，打开播放页的整段飞行里封面都画不出来）
                .then(if (sharedElement) Modifier.sharedNowPlayingCover(RectangleShape) else Modifier)
                .graphicsLayer {
                    translationX = drag.offset
                    alpha = 1f - (abs(drag.offset) / (currentThreshold * 4)).coerceIn(0f, 0.5f)
                    // 跟拍弹一下；收成小圆时不弹
                    val pop = if (collapsed) 1f else pulse?.coverScale ?: 1f
                    scaleX = pop
                    scaleY = pop
                    // 离屏合成后用 DstOut 擦掉形状以外的部分：低版本上 clipPath 没有抗锯齿，大封面的边缘会有锯齿
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithCache {
                    val shape = Path()
                    // 混合模式只作用于画到的像素，要擦掉形状外面就得把外面画出来：奇偶填充的“外框 − 形状”。
                    // 外框取两倍大，旋转到任何角度都盖得住整个图层
                    val outside = Path()
                    val frame = Rect(-size.width / 2f, -size.height / 2f, size.width * 1.5f, size.height * 1.5f)
                    val scratch = MutableCubic()
                    var builtMorph: Morph? = null
                    var builtProgress = Float.NaN
                    onDrawWithContent {
                        val morph = stage.morph
                        val progress = stage.drawProgress
                        if (morph !== builtMorph || progress != builtProgress) {
                            morph.fillPath(progress, shape, scratch, size.width, size.height)
                            outside.rewind()
                            // rewind 会把填充规则重置成非零环绕，每次重建后再设
                            outside.fillType = PathFillType.EvenOdd
                            outside.addRect(frame)
                            outside.addPath(shape)
                            builtMorph = morph
                            builtProgress = progress
                        }
                        drawContent()
                        rotate(stage.angle) { drawPath(outside, Color.Black, blendMode = BlendMode.DstOut) }
                    }
                },
        ) {
            CoverImage(cover, title)
        }
    }
}

/** 封面图：换歌时新图加载完成后盖在旧图上淡入，淡完再移走旧图；没有封面时显示首字占位。 */
@Composable
private fun CoverImage(cover: String, title: String) {
    val swap = remember { CoverImageSwap(cover) }
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scope = rememberCoroutineScope()
    val reveal: () -> Unit = {
        scope.launch {
            swap.fade.animateTo(1f, fade)
            swap.dropPrevious()
        }
    }
    LaunchedEffect(cover) { swap.show(cover) }
    CoverBox(url = "", key = title, modifier = Modifier.fillMaxSize(), shape = RectangleShape, fontSize = 96f)
    // 两张图按地址 key：旧图从“当前”挪到“上一张”时保留已加载的画面，不会闪一下空白
    for (url in listOfNotNull(swap.previous, swap.current)) {
        key(url) {
            if (url.isNotBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { if (url == swap.current) reveal() },
                    onError = { if (url == swap.current) reveal() },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (url == swap.current) swap.fade.value else 1f },
                )
            }
        }
    }
}

@Stable
private class CoverImageSwap(initial: String) {
    var current by mutableStateOf(initial)
        private set
    var previous by mutableStateOf<String?>(null)
        private set
    val fade = Animatable(1f)

    suspend fun show(url: String) {
        if (url == current) return
        when {
            // 没有新封面就直接露出占位，不等加载回调
            url.isBlank() -> {
                previous = null
                current = url
                fade.snapTo(1f)
            }
            // 切回还垫在底下、完整显示着的那张：它已加载完，不会再有加载回调，直接露出来
            url == previous -> {
                previous = null
                current = url
                fade.snapTo(1f)
            }
            // 当前这张还没淡入完（连切几首）：保留底下那张，只换掉当前，不闪占位也不跳出半路的封面
            previous != null -> {
                current = url
                fade.snapTo(0f)
            }
            else -> {
                previous = current.takeIf { it.isNotBlank() }
                current = url
                fade.snapTo(0f)
            }
        }
    }

    fun dropPrevious() {
        previous = null
    }
}

/**
 * 封面轮廓散开的形状光环（见 [BackdropPulse.rippleGrow]）：从轮廓外 2% 起往外扩，描边随扩散变细，跟着外轮廓一起转。
 * 原型画在背景里、遮罩下面；这里画在封面层，透明度按封面附近遮罩的深浅（约 25%）先压一下。
 * 节点按 1/[RIPPLE_DOWNSCALE] 布局再用图层放大，Android 12+ 再做一点模糊（原型是封面半径的 5%）。
 * 没有光环时什么都不画；有光环时跟着背景的帧循环每帧重画。
 */
@Composable
private fun CoverRipples(
    stage: CoverStageState,
    pulse: BackdropPulse,
    visible: State<Float>,
    offset: () -> Float,
    fadeDistance: () -> Float,
) {
    val color = MaterialTheme.colorScheme.primary
    Spacer(
        Modifier
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val small = ceil(width * RIPPLE_SIDE / RIPPLE_DOWNSCALE).toInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(small, small))
                layout(width, height) {
                    placeable.placeWithLayer((width - small) / 2, (height - small) / 2) {
                        scaleX = RIPPLE_DOWNSCALE
                        scaleY = RIPPLE_DOWNSCALE
                        translationX = offset()
                        val dragFade = 1f - (abs(offset()) / fadeDistance()).coerceIn(0f, 0.5f)
                        alpha = (visible.value * dragFade).coerceIn(0f, 1f)
                    }
                }
            }
            .then(
                if (Build.VERSION.SDK_INT >= 31) {
                    Modifier.graphicsLayer {
                        val sigma = size.width / RIPPLE_SIDE / 2f * RIPPLE_BLUR
                        val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(1f)
                        renderEffect = BlurEffect(radius, radius, TileMode.Decal)
                    }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                // 封面在这个图层里的边长；形状路径按它建，只随形变重建
                val side = size.width / RIPPLE_SIDE
                val shape = Path()
                val scratch = MutableCubic()
                var builtMorph: Morph? = null
                var builtProgress = Float.NaN
                // 描边按年龄分档缓存，逐帧不新建
                val strokes = arrayOfNulls<Stroke>(RIPPLE_STROKE_STEPS)
                val maxGrow = FlowFieldMotion.rippleGrow(FlowFieldMotion.RIPPLE_LIFE_S)
                onDrawBehind {
                    pulse.rippleFrame
                    val count = pulse.rippleCount
                    if (count == 0) return@onDrawBehind
                    val morph = stage.morph
                    val progress = stage.drawProgress
                    if (morph !== builtMorph || progress != builtProgress) {
                        morph.fillPath(progress, shape, scratch, side, side)
                        builtMorph = morph
                        builtProgress = progress
                    }
                    val angle = stage.angle
                    for (i in 0 until count) {
                        val grow = pulse.rippleGrow[i]
                        val alpha = pulse.rippleAlpha[i] * RIPPLE_UNDER_SCRIM
                        if (alpha <= 0f) continue
                        val scale = RIPPLE_START + grow
                        // 描边宽度按封面半径算，路径放大了 scale 倍，宽度先除掉
                        val width = side / 2f * (RIPPLE_WIDTH - RIPPLE_THIN * grow.coerceAtMost(1f)) / scale
                        withTransform({
                            translate(size.width / 2f, size.height / 2f)
                            scale(scale, scale, Offset.Zero)
                            rotate(angle, Offset.Zero)
                            translate(-side / 2f, -side / 2f)
                        }) {
                            val step = (grow / maxGrow * RIPPLE_STROKE_STEPS).toInt().coerceIn(0, RIPPLE_STROKE_STEPS - 1)
                            val stroke = strokes[step] ?: Stroke(width).also { strokes[step] = it }
                            drawPath(shape, color, alpha = alpha.coerceAtMost(1f), style = stroke)
                        }
                    }
                }
            },
    )
}

/**
 * 封面后的光晕：同一张图放大到 1.03 倍（原型的内缩 4% 再放大 1.12），饱和度 1.4，透明度 0.62，律动时随低频慢包络变亮，
 * 跟拍再亮一下、放大一点（[BackdropPulse.haloBoost]、[BackdropPulse.haloScale]）。
 * 节点按 1/[HALO_DOWNSCALE] 布局再用图层放大：Android 12+ 用 RenderEffect 模糊，模糊只在小图层上做；
 * 更低版本没有模糊，改用 24px 的小图放大，再用径向渐变把边缘渐隐成圆形，本身就足够柔和。
 */
@Composable
private fun CoverHalo(
    cover: String,
    pulse: BackdropPulse?,
    visible: State<Float>,
    offset: () -> Float,
    fadeDistance: () -> Float,
) {
    if (cover.isBlank()) return
    val context = LocalContext.current
    val blur = Build.VERSION.SDK_INT >= 31
    val request = remember(context, cover, blur) {
        // AsyncImage 默认按 INEXACT 取图，会直接复用内存里同地址的大封面，低版本上光晕就成了一张清楚的放大封面；
        // 固定成 EXACT 并单独占一个缓存键，既拿到真正的小图，也不把大封面从缓存里挤掉
        ImageRequest.Builder(context)
            .data(cover)
            .size(if (blur) HALO_BLUR_PX else HALO_TINY_PX)
            .precision(Precision.EXACT)
            .memoryCacheKey("$cover#halo")
            .build()
    }
    val saturate = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(HALO_SATURATION) }) }
    Box(
        Modifier
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val small = ceil(width * HALO_SIDE / HALO_DOWNSCALE).toInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(small, small))
                layout(width, height) {
                    placeable.placeWithLayer((width - small) / 2, (height - small) / 2) {
                        val grow = pulse?.haloScale ?: 1f
                        scaleX = HALO_DOWNSCALE * grow
                        scaleY = HALO_DOWNSCALE * grow
                        translationX = offset()
                        val bright = pulse?.haloBoost ?: 0f
                        val dragFade = 1f - (abs(offset()) / fadeDistance()).coerceIn(0f, 0.5f)
                        alpha = ((HALO_ALPHA + bright) * visible.value * dragFade).coerceIn(0f, 1f)
                    }
                }
            }
            .then(
                if (blur) {
                    Modifier.graphicsLayer {
                        // 原型是 CSS blur(30px)（标准差）对 280px 封面；按封面边长折算到这个缩小的图层，再换成 Android 的半径
                        val sigma = size.width / HALO_SIDE * HALO_SIGMA
                        val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(1f)
                        renderEffect = BlurEffect(radius, radius, TileMode.Decal)
                    }
                } else {
                    Modifier
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithCache {
                            val falloff = Brush.radialGradient(
                                0f to Color.Black,
                                0.5f to Color.Black.copy(alpha = 0.8f),
                                0.72f to Color.Black.copy(alpha = 0.3f),
                                1f to Color.Transparent,
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = size.minDimension / 2f,
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(falloff, blendMode = BlendMode.DstIn)
                            }
                        }
                },
            ),
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = saturate,
            modifier = if (blur) {
                // 模糊前先裁成圆，模糊会把边缘晕开到四周的留白里
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val side = constraints.maxWidth
                        val circle = (side * HALO_IMAGE / HALO_SIDE).roundToInt()
                        val placeable = measurable.measure(Constraints.fixed(circle, circle))
                        layout(side, side) { placeable.place((side - circle) / 2, (side - circle) / 2) }
                    }
                    .clip(CircleShape)
            } else {
                Modifier.fillMaxSize()
            },
        )
    }
}

/**
 * 封面形状的形变状态：当前 Morph、进度和外轮廓角度。三者都只在绘制阶段读。
 * 目标是当前 Morph 的起点或终点时沿原路退回 / 前进；中途换成第三个形状（连按下一首）时先把这一段走完，
 * 再从终点形变到新形状：Morph 只能在两个多边形之间插值，中途的轮廓没有公开 API 能截成新的多边形，
 * 直接换起点会跳一下。
 */
@Stable
internal class CoverStageState(initial: RoundedPolygon) {
    var morph by mutableStateOf(Morph(initial, initial))
        private set
    private val progress = Animatable(1f)
    private var from = initial
    private var to = initial

    /** 外轮廓角度（度）。 */
    var angle by mutableFloatStateOf(0f)

    /** 绘制用的进度：弹簧略微过冲时外推一点点，不超出太多以免形状自交。 */
    val drawProgress: Float get() = progress.value.coerceIn(-MORPH_OVERSHOOT, 1f + MORPH_OVERSHOOT)

    /** 是否停在（或正回到）[polygon]。 */
    fun restsOn(polygon: RoundedPolygon): Boolean =
        (to === polygon && progress.targetValue == 1f) || (from === polygon && progress.targetValue == 0f)

    /** [spec] 为 null 时直接切换（减少动画）。 */
    suspend fun morphTo(target: RoundedPolygon, spec: AnimationSpec<Float>?) {
        when (val step = planMorph(from, to, progress.value, progress.targetValue, target)) {
            MorphStep.Forward -> if (spec == null) progress.snapTo(1f) else progress.animateTo(1f, spec)
            MorphStep.Backward -> if (spec == null) progress.snapTo(0f) else progress.animateTo(0f, spec)
            is MorphStep.Restart -> {
                if (spec != null && step.finishFirst) progress.animateTo(if (step.start === to) 1f else 0f, spec)
                from = step.start
                this.to = target
                morph = Morph(step.start, target)
                if (spec == null) {
                    progress.snapTo(1f)
                } else {
                    progress.snapTo(0f)
                    progress.animateTo(1f, spec)
                }
            }
        }
    }
}

/** 下一步形变怎么走。 */
internal sealed interface MorphStep {
    /** 目标就是当前 Morph 的终点：往 1 走。 */
    data object Forward : MorphStep

    /** 目标是当前 Morph 的起点：沿原路退回 0。 */
    data object Backward : MorphStep

    /** 新目标：从 [start] 出发建新的 Morph；[finishFirst] 表示正在半路，先走到 [start] 再出发。 */
    data class Restart(val start: RoundedPolygon, val finishFirst: Boolean) : MorphStep
}

/**
 * 纯函数，便于测试：[progress] 是当前进度，[heading] 是正在去的进度（1 = 去 [to]，0 = 回 [from]）。
 * 新目标总是从正在去的那一端出发；还没到就先走完这一段，画面始终连续。
 */
internal fun planMorph(
    from: RoundedPolygon,
    to: RoundedPolygon,
    progress: Float,
    heading: Float,
    target: RoundedPolygon,
): MorphStep {
    if (target === to) return MorphStep.Forward
    if (target === from) return MorphStep.Backward
    return if (heading == 0f) {
        MorphStep.Restart(from, finishFirst = abs(progress) >= SETTLED)
    } else {
        MorphStep.Restart(to, finishFirst = abs(progress - 1f) >= SETTLED)
    }
}

/** Morph 的轮廓写进 [path]，单位坐标放大到宽高；复用 [scratch]。旋转在画布上做，路径只随形变重建。 */
internal fun Morph.fillPath(progress: Float, path: Path, scratch: MutableCubic, width: Float, height: Float) {
    path.rewind()
    var first = true
    forEachCubic(progress, scratch) { c ->
        if (first) {
            path.moveTo(c.anchor0X * width, c.anchor0Y * height)
            first = false
        }
        path.cubicTo(
            c.control0X * width, c.control0Y * height,
            c.control1X * width, c.control1Y * height,
            c.anchor1X * width, c.anchor1Y * height,
        )
    }
    path.close()
}

/**
 * 让形状绕自己的中心转，并缩进半径 0.5 的圆里。MaterialShapes 是按外接框归一化的：
 * 奇数瓣（七瓣、九瓣）的外接框中心不是形状中心，直接绕框心转会晃；凸出的瓣转到边上还会被方框切掉。
 */
internal fun fitForRotation(polygon: RoundedPolygon): RoundedPolygon {
    val cx = polygon.centerX
    val cy = polygon.centerY
    var maxSq = 0f
    for (cubic in polygon.cubics) maxSq = maxOf(maxSq, cubic.maxDistanceSq(cx, cy))
    if (maxSq <= 0f) return polygon
    val scale = 0.5f / sqrt(maxSq)
    return polygon.transformed { x, y -> FloatFloatPair((x - cx) * scale + 0.5f, (y - cy) * scale + 0.5f) }
}

/** 曲线到 (cx, cy) 的最远距离平方；控制点会高估外沿，所以在曲线上取样。 */
private fun Cubic.maxDistanceSq(cx: Float, cy: Float): Float {
    var max = 0f
    for (i in 0..CURVE_SAMPLES) {
        val t = i / CURVE_SAMPLES.toFloat()
        val u = 1f - t
        val a = u * u * u
        val b = 3f * u * u * t
        val c = 3f * u * t * t
        val d = t * t * t
        val x = a * anchor0X + b * control0X + c * control1X + d * anchor1X - cx
        val y = a * anchor0Y + b * control0Y + c * control1Y + d * anchor1Y - cy
        max = maxOf(max, x * x + y * y)
    }
    return max
}

/** 播放时轮廓匀速转：40 秒一圈，按帧间隔累加，角度保持在 0..360。 */
internal fun advanceSpin(angle: Float, elapsedNanos: Long): Float =
    (angle + elapsedNanos / 1_000_000_000f * 360f / SPIN_PERIOD_S) % 360f

/** 停下时转回最近的直角：圆角方形四向对称，任何一个直角都是正向。 */
internal fun restingAngle(angle: Float): Float = (angle / 90f).roundToInt() * 90f

/**
 * “随机”封面形状的播放会话：记住当前这首歌（按 key）抽到的形状，进程内有效。
 * 关掉再打开播放页、几处封面同时显示时都读这一份；换歌才重新抽，且不和上一首重复。
 * 固定形状也记下来：再切回“随机”时这首保持当前形状，下一首才开始抽。
 */
internal class CoverShapeSession(private val random: Random = Random.Default) {
    private var key: String? = null
    private var shape: CoverShapeChoice? = null

    fun resolve(choice: CoverShapeChoice, songKey: String): CoverShapeChoice {
        if (choice != CoverShapeChoice.Random) {
            key = songKey
            shape = choice
            return choice
        }
        val current = shape
        if (songKey == key && current != null) return current
        return nextRandomCoverShape(current, random).also {
            key = songKey
            shape = it
        }
    }
}

/** 播放页共用的随机形状会话，进程内只有一份。 */
internal val PlayerCoverShapes = CoverShapeSession()

private val fittedShapes = HashMap<CoverShapeChoice, RoundedPolygon>()

/** 某种形状转起来用的多边形，按选项缓存；“随机”不应传进来，兜底用暂停态的圆角方形。 */
internal fun coverPolygon(choice: CoverShapeChoice): RoundedPolygon =
    fittedShapes.getOrPut(choice) { choice.polygon?.let(::fitForRotation) ?: PausedPolygon }

// MaterialShapes 自带缓存；这里再固定一次引用，形变状态按引用判断“是不是暂停态 / 圆形”
private val PausedPolygon: RoundedPolygon by lazy { PausedCoverPolygon }
private val CollapsedPolygon: RoundedPolygon by lazy { MaterialShapes.Circle }

private const val SPIN_PERIOD_S = 40f
private const val SETTLED = 0.001f
private const val MORPH_OVERSHOOT = 0.08f
private const val CURVE_SAMPLES = 8
private const val HALO_SIDE = 1.7f
private const val HALO_IMAGE = 0.92f * 1.12f
private const val HALO_SIGMA = 30f / 280f
private const val HALO_ALPHA = 0.62f
private const val HALO_SATURATION = 1.4f
private const val HALO_DOWNSCALE = 4f
private const val HALO_BLUR_PX = 96
private const val HALO_TINY_PX = 24
private const val RIPPLE_SIDE = 2.4f
private const val RIPPLE_DOWNSCALE = 4f
private const val RIPPLE_START = 1.02f
private const val RIPPLE_WIDTH = 0.16f
private const val RIPPLE_THIN = 0.1f
private const val RIPPLE_BLUR = 0.05f
private const val RIPPLE_UNDER_SCRIM = 0.75f
private const val RIPPLE_STROKE_STEPS = 24
