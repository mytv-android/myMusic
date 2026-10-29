package com.mytv.mymusic.ui.poster

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.PosterPrefs
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.player.rememberSystemAnimationsOff
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.max
import kotlin.math.roundToInt

/** 听歌模式的续流状态：非空时墙按带子排（不循环），[appending] 时队尾占位格走微光。 */
@Immutable
internal data class PosterFeed(val appending: Boolean)

@Composable
internal fun rememberPosterWallState(): PosterWallState = remember { PosterWallState() }

/**
 * 海报墙：曲目按封面铺成一面四向拖动的无限墙（听歌模式是一条往右长的带子），[currentIndex] 那首放大成 6×6 的大卡。
 *
 * - 只组合视口外扩两个格距内的格子，按 (块, 位) 复用；镜头平移只在摆放阶段读、对齐整像素，格子不重组；
 * - 点格子放大（不是播放），拖动带惯性，长按交给 [onLongPress]；
 * - 电视上墙自己收方向键、自己记焦点（见 WallFocus.kt），OK 放大，焦点在大卡上按 OK 交给 [onGrownConfirm]，
 *   长按 OK / 菜单键交给 [onLongPress]；空闲 6 秒浮层淡出（[PosterWallState.chromeVisible]），30 秒起漂；
 * - [grownContent] 是大卡内容（GrownPoster），墙负责槽位、圆角裁切、描边、阴影和放大动画，底下垫着小封面；
 *   `settled` 在放大的弹簧落定或 1 秒后为 true；
 * - [insetTop] / [insetBottom] 是浮层占的高度，大卡居中在两者之间；墙自己画背景（surface + 两团主题色光晕 + 暗角）。
 * - [active] 为 false（播放页盖在墙上）时帧循环停下，切歌跟随直接到位；大卡里的动画由页面按它停（GrownPoster 的 settled）；
 * - [chromeAbove] 电视顶部一排浮层的焦点入口：墙上没有选中格时按上键交给它，下键回来由页面调 [PosterWallState.focusFromAbove]；
 * - [resetKey] 换了一份队列（首数一样也算）时变，整面墙重新入场；[currentKey] 正在播放那首的 key，用来分辨队列重排和切歌；
 * - [holdAwake] 墙上开着面板时为 true：空闲计时不走（电视浮层不淡出、不起漂）。
 *
 * 页面要接：BackHandler → [PosterWallState.handleBack]（返回 false 再离开），根节点挂 [posterWallWake]，
 * GrownPoster 的 `focused` / `coverPlaceholderKey` 取 [PosterWallState.grownFocused] / [PosterWallState.grownCoverKey]。
 */
@Composable
internal fun PosterWall(
    state: PosterWallState,
    songs: List<Song>,
    currentIndex: Int,
    feed: PosterFeed?,
    prefs: PosterPrefs,
    insetTop: Dp,
    insetBottom: Dp,
    onLongPress: (index: Int) -> Unit,
    grownContent: @Composable BoxScope.(index: Int, current: Boolean, settled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onGrownConfirm: (index: Int, current: Boolean) -> Unit = { _, _ -> },
    autoFocus: Boolean = true,
    active: Boolean = true,
    chromeAbove: FocusRequester? = null,
    resetKey: Any? = null,
    currentKey: String? = null,
    holdAwake: Boolean = false,
) {
    val window = LocalAppWindow.current
    val metrics = wallMetricsFor(window.isTv, window.widthDp, window.heightDp)
    val reduceMotion = LocalAppearance.current.reduceMotion || rememberSystemAnimationsOff()
    val count = songs.size
    val banded = feed != null
    val field = remember(metrics, count, banded) { if (banded) feedFor(metrics, count) else fieldFor(count) }
    val scope = rememberCoroutineScope()
    val env = remember(state, scope) { TileEnv(state, scope) }
    val motion = MaterialTheme.motionScheme
    val family = MaterialTheme.typography.titleLarge.fontFamily
    val config = WallConfig(
        metrics = metrics,
        field = field,
        current = if (currentIndex in 0 until count) currentIndex else -1,
        follow = prefs.follow,
        isTv = window.isTv,
        drift = window.isTv && prefs.tvDrift,
        reduceMotion = reduceMotion,
        appending = feed?.appending == true,
        insetTop = insetTop.value,
        insetBottom = insetBottom.value,
        active = active,
        escapeUp = chromeAbove != null,
        resetKey = resetKey,
        currentKey = currentKey,
        holdAwake = holdAwake,
    )
    SideEffect {
        state.onLongPress = onLongPress
        state.onGrownConfirm = onGrownConfirm
        env.metrics = metrics
        env.style = tileStyleFor(metrics)
        env.tint = prefs.tint.alpha
        env.lightsOut = prefs.lightsOut
        env.showIndex = prefs.showIndex
        env.titleFamily = family
        env.reduceMotion = reduceMotion
        env.rectSpec = motion.defaultSpatialSpec()
        env.radiusSpec = motion.defaultSpatialSpec()
        env.focusSpec = motion.fastSpatialSpec()
        state.configure(config)
    }
    // 被盖住时不跑帧循环；揭开时空闲计时从头算（漂移着被盖住的话这一下顺便弹回正在播放）
    LaunchedEffect(state, active) {
        if (!active) return@LaunchedEffect
        state.wake()
        state.runFrames()
    }
    if (autoFocus && window.isTv) LaunchedEffect(state) { state.requestWallFocus() }
    PaletteSync(state.palette)

    val ring = remember { Animatable(0f) }
    LaunchedEffect(state, ring) {
        snapshotFlow { state.litFocus != null }.collectLatest { on ->
            val target = if (on) 1f else 0f
            if (env.reduceMotion) ring.snapTo(target) else ring.animateTo(target, tween(RING_FADE_MS))
        }
    }

    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { state.setViewportSize(it.width / density, it.height / density) }
            .wallBackground(state.palette)
            .wallGestures(state, haptic)
            .onPreviewKeyEvent { e -> state.previewKey(wallKeyOf(e.key), e.type == KeyEventType.KeyDown) }
            .onKeyEvent { e ->
                val down = when (e.type) {
                    KeyEventType.KeyDown -> true
                    KeyEventType.KeyUp -> false
                    else -> return@onKeyEvent false
                }
                // 焦点在大卡的按钮上时，没吃掉的键冒泡到这里：交回普通焦点规则
                if (!state.wallFocused) return@onKeyEvent false
                state.onKey(wallKeyOf(e.key), down, e.nativeKeyEvent.repeatCount, e.nativeKeyEvent.isLongPress)
            }
            .focusRequester(state.focusRequester)
            .onFocusChanged { state.onWallFocus(it.isFocused, it.hasFocus) }
            .focusProperties { if (chromeAbove != null) up = chromeAbove }
            .focusable(),
    ) {
        WallTiles(state, songs, currentIndex, env, grownContent)
        WallOverlay(state, ring)
    }
}

/**
 * 页面根节点挂这个：漂移中第一下按键 / 触摸只唤醒（连同自动重复和松开一起吞掉，浮层按钮也收不到），
 * 平时任何按键、触摸都把空闲计时归零。返回键不经过这里，见 [PosterWallState.handleBack]。
 */
internal fun Modifier.posterWallWake(state: PosterWallState): Modifier = this
    .onPreviewKeyEvent { e -> state.previewKey(wallKeyOf(e.key), e.type == KeyEventType.KeyDown) }
    .pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (state.onPointerDown()) {
                down.consume()
                consumeUntilUp(PointerEventPass.Initial)
            }
        }
    }

/** 切歌时整个 App 的配色逐帧过渡：只有这个叶子读 MaterialTheme，墙和格子在绘制阶段读 [WallPalette]。 */
@Composable
private fun PaletteSync(palette: WallPalette) {
    val scheme = MaterialTheme.colorScheme
    SideEffect { palette.scheme = scheme }
}

@Composable
private fun WallTiles(
    state: PosterWallState,
    songs: List<Song>,
    currentIndex: Int,
    env: TileEnv,
    grownContent: @Composable BoxScope.(index: Int, current: Boolean, settled: Boolean) -> Unit,
) {
    val tiles = state.composedTiles
    val generation = state.generation
    val band = state.scene?.field as? WallField.FeedBand
    val policy = remember(state) { WallMeasurePolicy(state) }
    Layout(
        content = {
            for (t in tiles) {
                key(generation, t.pos) {
                    val ghost = if (band != null && band.isGhost(t.song)) band.ghostAlpha(t.song) else null
                    PosterTile(
                        tile = t,
                        song = songs.getOrNull(t.song),
                        ghostAlpha = ghost,
                        current = currentIndex >= 0 && t.song == currentIndex,
                        env = env,
                        grownContent = if (t.grown) grownContent else null,
                    )
                }
            }
        },
        measurePolicy = policy,
    )
}

/**
 * 每格按动画中的矩形（墙面 dp，取整到像素）量、摆；镜头位置只在摆放阶段读，镜头动时只重新摆放，不重新量、不重组。
 * 放大的格 z = 3，焦点格 z = 2。缓冲区复用，摆放阶段不分配。
 */
private class WallMeasurePolicy(private val state: PosterWallState) : MeasurePolicy {
    private val placeables = ArrayList<Placeable>()
    private val anims = ArrayList<TileAnim>()
    private var xs = IntArray(64)
    private var ys = IntArray(64)

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val d = density
        placeables.clear()
        anims.clear()
        if (xs.size < measurables.size) {
            xs = IntArray(measurables.size * 2)
            ys = IntArray(measurables.size * 2)
        }
        for (m in measurables) {
            val a = m.layoutId as? TileAnim ?: continue
            val r = a.rect.value
            val l = (r.left * d).roundToInt()
            val t = (r.top * d).roundToInt()
            val w = max(0, (r.right * d).roundToInt() - l)
            val h = max(0, (r.bottom * d).roundToInt() - t)
            xs[anims.size] = l
            ys[anims.size] = t
            placeables += m.measure(Constraints.fixed(w, h))
            anims += a
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        return layout(width, height) {
            val cx = (state.camX * d).roundToInt()
            val cy = (state.camY * d).roundToInt()
            for (i in placeables.indices) {
                val a = anims[i]
                val z = when {
                    a.tile.grown -> 3f
                    a.lit.value -> 2f
                    else -> 0f
                }
                placeables[i].placeWithLayer(xs[i] - cx, ys[i] - cy, z, a.layerBlock)
            }
        }
    }
}

/**
 * 触摸：拖动过阈值才算拖（拖动不算点击），松手按 VelocityTracker 的速度甩出；没动就是点，点到小格放大；
 * 按住到长按时间没动是长按。都在 Main 阶段处理：子节点（大卡里的进度条）先吃掉的拖动让给它们；
 * 墙一旦开始拖，消费掉移动，子节点的点击随之取消；长按之后在 Initial 阶段吞掉剩下的事件，大卡按钮不会再响应松开。
 */
private fun Modifier.wallGestures(state: PosterWallState, haptic: HapticFeedback): Modifier = pointerInput(state, haptic) {
    val slop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    val maxFling = viewConfiguration.maximumFlingVelocity
    val d = density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (state.onPointerDown()) {
            // 漂移中：这一下只唤醒，整个手势吞掉
            down.consume()
            consumeUntilUp(PointerEventPass.Initial)
            return@awaitEachGesture
        }
        val id = down.id
        val origin = down.position
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var outcome = PressOutcome.Pending
        var dragStart = origin
        var timedOut = false
        while (outcome == PressOutcome.Pending) {
            val result = if (timedOut) {
                awaitPress(id, origin, slop, tracker)
            } else {
                withTimeoutOrNull(longPressMs) { awaitPress(id, origin, slop, tracker) }
            }
            if (result == null) {
                timedOut = true
                if (state.longPressAt(origin.x / d, origin.y / d)) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    consumeUntilUp(PointerEventPass.Initial)
                    return@awaitEachGesture
                }
            } else {
                outcome = result.outcome
                dragStart = result.position
            }
        }
        when (outcome) {
            PressOutcome.Tap -> state.tapAt(origin.x / d, origin.y / d)
            PressOutcome.Drag -> {
                state.beginDrag()
                var released = false
                while (!released) {
                    val e = awaitPointerEvent(PointerEventPass.Main)
                    val c = e.changes.firstOrNull { it.id == id }
                    if (c == null) break
                    tracker.addPointerInputChange(c)
                    if (!c.pressed) {
                        released = true
                    } else {
                        state.dragBy((c.position.x - dragStart.x) / d, (c.position.y - dragStart.y) / d)
                    }
                    c.consume()
                }
                val v = if (released) tracker.calculateVelocity(Velocity(maxFling, maxFling)) else Velocity.Zero
                state.endDrag(v.x / d, v.y / d)
            }
            else -> Unit
        }
    }
}

private enum class PressOutcome { Pending, Tap, Drag, Cancel }

private class PressResult(val outcome: PressOutcome, val position: Offset)

/** 等这一指过阈值（拖动）、松开（点按）或被子节点拿走（取消）。 */
private suspend fun AwaitPointerEventScope.awaitPress(
    id: PointerId,
    origin: Offset,
    slop: Float,
    tracker: VelocityTracker,
): PressResult {
    while (true) {
        val e = awaitPointerEvent(PointerEventPass.Main)
        val c = e.changes.firstOrNull { it.id == id } ?: return PressResult(PressOutcome.Cancel, origin)
        if (!c.pressed) {
            // 子节点（大卡按钮）消费了松开就不算点墙
            return PressResult(if (c.isConsumed) PressOutcome.Cancel else PressOutcome.Tap, c.position)
        }
        // 子节点先吃掉了移动（拖进度条）：让给它
        if (c.isConsumed && c.position != c.previousPosition) return PressResult(PressOutcome.Cancel, c.position)
        tracker.addPointerInputChange(c)
        if ((c.position - origin).getDistance() > slop) {
            c.consume()
            return PressResult(PressOutcome.Drag, c.position)
        }
    }
}

private suspend fun AwaitPointerEventScope.consumeUntilUp(pass: PointerEventPass) {
    while (true) {
        val e = awaitPointerEvent(pass)
        var pressed = false
        for (c in e.changes) {
            c.consume()
            if (c.pressed) pressed = true
        }
        if (!pressed) return
    }
}

/**
 * 焦点环和暗角，画在格子上面。焦点环不放大格子：圆角加 8dp（格子自己弹），外面套两圈——内圈 3dp surface、外圈 3dp onSurface，
 * 跟着焦点格动画中的位置走；空闲时淡出。没有焦点时这里不读镜头位置，镜头动不会重画它。
 */
@Composable
private fun WallOverlay(state: PosterWallState, ring: Animatable<Float, AnimationVector1D>) {
    val last = remember { arrayOfNulls<SlotPos>(1) }
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val width = FOCUS_RING.toPx()
                val stroke = Stroke(width)
                val vignette = Brush.radialGradient(
                    0f to Color.Transparent,
                    VIGNETTE_FROM to Color.Transparent,
                    1f to Color.Black.copy(alpha = VIGNETTE_ALPHA),
                    center = Offset(size.width / 2, size.height / 2),
                    radius = size.width / 2,
                )
                onDrawBehind {
                    val a = ring.value
                    val lit = state.litFocus
                    if (lit != null) last[0] = lit
                    val f = lit ?: last[0]
                    if (a > 0f && f != null) drawFocusRing(state, f, a, stroke)
                    drawVignette(vignette)
                }
            },
    )
}

private fun DrawScope.drawFocusRing(state: PosterWallState, f: SlotPos, alpha: Float, stroke: Stroke) {
    val anim = state.tileAnims[f] ?: return
    val r = anim.rect.value
    val d = density
    val cx = (state.camX * d).roundToInt()
    val cy = (state.camY * d).roundToInt()
    val l = (r.left * d).roundToInt() - cx
    val t = (r.top * d).roundToInt() - cy
    val w = (r.right * d).roundToInt() - cx - l
    val h = (r.bottom * d).roundToInt() - cy - t
    val a = alpha * anim.entrance(anim.tile).coerceIn(0f, 1f)
    if (a <= 0f) return
    val s = state.palette.scheme
    val radius = anim.radius.value * d
    val sw = stroke.width
    // 内圈贴着格子边往外 0..3dp，外圈 3..6dp
    for (k in 0..1) {
        val grow = sw * (k + 0.5f)
        drawRoundRect(
            color = if (k == 0) s.surface else s.onSurface,
            topLeft = Offset(l - grow, t - grow),
            size = Size(w + 2 * grow, h + 2 * grow),
            cornerRadius = CornerRadius(radius + grow),
            style = stroke,
            alpha = a,
        )
    }
}

/** 原型的 radial-gradient(closest-side, transparent 78%, rgba(0,0,0,.42))：按宽算圆，再竖向压成椭圆。 */
private fun DrawScope.drawVignette(brush: Brush) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val sy = h / w
    scale(1f, sy, pivot = Offset(w / 2, h / 2)) {
        drawRect(brush, topLeft = Offset(0f, h / 2 - w / 2), size = Size(w, w))
    }
}

/**
 * 墙的背景：surface 上两团光晕——右下 secondaryContainer 70%（椭圆 60% × 50%，中心 90% / 95%），
 * 左上 primary 22%（中心 12% / 10%）。渐变按尺寸缓存，配色只作为滤镜色在绘制时套上。
 */
private fun Modifier.wallBackground(palette: WallPalette): Modifier = drawWithCache {
    val top = WallGlow(Offset(size.width * 0.12f, size.height * 0.10f), size.width * 0.6f, size.height * 0.5f)
    val bottom = WallGlow(Offset(size.width * 0.90f, size.height * 0.95f), size.width * 0.6f, size.height * 0.5f)
    onDrawBehind {
        val s = palette.scheme
        drawRect(s.surface)
        bottom.draw(this, s.secondaryContainer.copy(alpha = 0.7f))
        top.draw(this, s.primary.copy(alpha = 0.22f))
    }
}

/** 一团椭圆光晕：白色径向渐变 shader 只建一次，竖向压扁成椭圆，颜色用滤镜套。 */
private class WallGlow(private val center: Offset, private val rx: Float, private val ry: Float) {
    private val paint = Paint().apply {
        if (rx > 0f) {
            shader = RadialGradientShader(center, rx, listOf(Color.White, Color.Transparent), tileMode = TileMode.Clamp)
        }
    }
    private var lastTint = Color.Unspecified

    fun draw(scope: DrawScope, tint: Color) = with(scope) {
        if (rx <= 0f || ry <= 0f) return@with
        if (tint != lastTint) {
            paint.colorFilter = ColorFilter.tint(tint)
            lastTint = tint
        }
        val sy = ry / rx
        // 压扁后要盖满整个画面：未压扁坐标里的竖向范围按比例放大
        val y0 = center.y - center.y / sy
        val y1 = center.y + (size.height - center.y) / sy
        drawIntoCanvas {
            it.save()
            it.translate(0f, center.y)
            it.scale(1f, sy)
            it.translate(0f, -center.y)
            it.drawRect(0f, y0, size.width, y1, paint)
            it.restore()
        }
    }
}

private const val RING_FADE_MS = 250
private const val VIGNETTE_FROM = 0.78f
private const val VIGNETTE_ALPHA = 0.42f
private val FOCUS_RING = 3.dp
