package com.mytv.mymusic.ui.player

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.distinctKeys
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.theme.BlurMode
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 控件 4 秒无操作后隐藏，播放和暂停一样。任意按下或按键唤醒；电视上唤醒的那一下不触发按键动作。 */
@Stable
class IdleState {
    var idle by mutableStateOf(false)
        internal set
    /** 是否允许进入空闲；读屏开着时为 false，此时返回键也不该只收起控件。 */
    var canAutoHide by mutableStateOf(true)
        internal set
    internal var tick by mutableLongStateOf(0L)

    fun poke() {
        idle = false
        tick++
    }

    /** 返回键在控制栏亮着时的处理：只收起控件，不退出播放页。 */
    fun sleep() {
        if (canAutoHide) idle = true
    }
}

/**
 * 计时与是否在播放无关：暂停、播放失败时控制栏同样淡出，按确认键或点一下屏幕再调出。
 * [holdAwake] 为 true 时不进入空闲：播放页上开着队列、定时、换源这类面板时，面板是单独的窗口，里面的按键到不了
 * 播放页的唤醒逻辑；照样计时的话环境模式会在面板后面开始，焦点也被挪到看不见的占位上，关掉面板后回不到原来的按钮。
 */
@Composable
fun rememberIdleState(holdAwake: Boolean = false, timeoutMs: Long = 4_000L): IdleState {
    val state = remember { IdleState() }
    val screenReader = rememberScreenReaderOn()
    LaunchedEffect(state.tick, screenReader, holdAwake) {
        state.idle = false
        // 读屏用户看不到控件淡入淡出，隐藏后焦点会落到看不见的地方，所以不进入空闲
        state.canAutoHide = !screenReader
        if (!screenReader && !holdAwake) {
            delay(timeoutMs)
            state.idle = true
        }
    }
    return state
}

/** 是否开着朗读类无障碍服务（TalkBack 等），开关变化时实时更新。 */
@Composable
fun rememberScreenReaderOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    fun spoken() = manager?.isEnabled == true &&
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
    var on by remember { mutableStateOf(spoken()) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { on = spoken() }
        manager?.addAccessibilityStateChangeListener(listener)
        onDispose { manager?.removeAccessibilityStateChangeListener(listener) }
    }
    return on
}

fun Modifier.wakeOnInput(idle: IdleState, consumeKeyWhenIdle: Boolean): Modifier = this
    .pointerInput(idle) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) idle.poke()
            }
        }
    }
    .onPreviewKeyEvent { event ->
        // 返回键留给播放页的返回处理：控制栏亮着时先收起，环境模式下才退出播放页
        if (event.key == Key.Back) return@onPreviewKeyEvent false
        val wasIdle = idle.idle
        idle.poke()
        consumeKeyWhenIdle && wasIdle
    }

/**
 * 形状封面轮廓旋转的前提：播放中、未减少动画（[reduceMotion] 已含系统“移除动画”）。
 * 设计稿要求播放时轮廓一直转，没有封面图时转的是首字占位的轮廓。
 */
internal fun ambientSpinEnabled(playing: Boolean, reduceMotion: Boolean): Boolean = playing && !reduceMotion

/** 双击收藏时的心形爆发，裁成 12 瓣饼干形；减少动画时不播放。 */
@Composable
fun HeartBurst(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger == 0 || LocalAppearance.current.reduceMotion) return
    val scale = remember(trigger) { Animatable(0.2f) }
    val alpha = remember(trigger) { Animatable(1f) }
    LaunchedEffect(trigger) {
        launch { scale.animateTo(1.1f, BurstSpring) }
        delay(450)
        alpha.animateTo(0f, tween(400))
    }
    Box(
        modifier
            .size(124.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
            .clip(MaterialShapes.Cookie12Sided.toShape())
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Favorite, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

private val BurstSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.6f, stiffness = 800f)

/** 同步歌词拆行；无时间戳歌词按原文缓存，避免每次重组重新分列。 */
internal fun ParsedLyrics.displayLines(): List<LyricLine> =
    if (synced) lines else raw.lineSequence().filter { it.isNotBlank() }.map { LyricLine(-1, it) }.toList()

/**
 * 歌词列排版：常规行字号 [baseSp]、当前行放大倍数 [grow]、行距 [gapDp]，
 * 以及当前行中心在视口里的高度比例 [anchor]。各预设对照设计稿里每种布局的歌词列。
 */
data class LyricsSizing(val baseSp: Float, val grow: Float, val gapDp: Float, val anchor: Float) {
    /** 当前行放大后的视觉字号。 */
    val currentSp: Float get() = baseSp * grow

    companion object {
        val PhoneLyrics = LyricsSizing(23f, 1.22f, 18f, 0.42f)
        val Tablet = LyricsSizing(28f, 1.36f, 24f, 0.44f)
        val TabletFocus = LyricsSizing(34f, 1.42f, 28f, 0.46f)
        val Tv = LyricsSizing(22f, 1.36f, 12f, 0.42f)
        val TvAmbient = LyricsSizing(26f, 1.38f, 16f, 0.44f)
    }
}

/** 行高取字号的 1.3 倍，和原来 titleLarge / headlineMedium 的行高基本一致。 */
private const val LyricLineHeight = 1.3f
private const val FarLyricBlurDp = 1.4f

/** 歌词栏两端渐隐的范围（视口高度的比例，对照设计稿的遮罩）：顶上 0–14% 淡入，底下 80–100% 淡出。 */
internal const val LyricFadeTop = 0.14f
internal const val LyricFadeBottom = 0.8f

/** 压到两端渐隐区的歌词行：占位中心在视口里的位置 [center] 和视口高度 [viewport]（px）。 */
@Immutable
internal data class LyricEdge(val center: Float, val viewport: Float)

/**
 * 歌词栏上下两端渐隐，滚出栏外的行不会被硬切在歌名下面、控制条上面。原来是整栏离屏合成后用渐变擦两端，
 * 当前行的逐字填色每帧都让整栏重新合成；现在只有占位压到渐隐区的那几行各自离屏、按自己在视口里的位置擦，样子不变。
 * [itemTop]、[itemSize] 是这一行占位在视口里的顶边和高度（px），[viewport] 是视口高度。离两端都远时返回 null。
 */
internal fun lyricEdgeOf(itemTop: Int, itemSize: Int, viewport: Int): LyricEdge? {
    if (viewport <= 0) return null
    val top = itemTop.toFloat()
    if (top >= viewport * LyricFadeTop && top + itemSize <= viewport * LyricFadeBottom) return null
    return LyricEdge(top + itemSize / 2f, viewport.toFloat())
}

/**
 * 视口里的高度 [viewportY] 换到行图层的本地坐标：图层竖直居中在占位里，以自己的中心为原点缩放了 [scale] 倍，
 * 未缩放时高 [layerHeight]。遮罩渐变按视口顶边、底边换算后的位置铺，和整栏一张遮罩逐像素一致。
 */
internal fun lyricEdgeLocalY(viewportY: Float, edge: LyricEdge, scale: Float, layerHeight: Float): Float =
    (viewportY - edge.center) / scale.coerceAtLeast(0.01f) + layerHeight / 2f

/** 离当前行的远近：0 当前行，1 相邻行，2 更远。没有当前行（未同步歌词）时都按相邻行显示，不模糊。 */
internal fun lyricDistance(index: Int, active: Int): Int =
    if (active < 0) 1 else abs(index - active).coerceAtMost(2)

/** [distance] 在动画中连续变化：0→1 透明度从 100% 降到 60%，1→2 再降到 38%。 */
internal fun lyricAlpha(distance: Float): Float {
    val d = distance.coerceIn(0f, 2f)
    return if (d <= 1f) 1f - 0.4f * d else 0.6f - 0.22f * (d - 1f)
}

/** 只有更远的行模糊；Android 12 以下没有 RenderEffect，只降透明度。 */
internal fun lyricBlurDp(distance: Float, blurSupported: Boolean): Float =
    if (blurSupported) (distance - 1f).coerceIn(0f, 1f) * FarLyricBlurDp else 0f

/** 某行此刻的视觉字号：常规字号 [base] 按放大进度 [lift]（0 常规行，1 当前行）放大到 [grow] 倍。 */
internal fun lyricVisualSp(base: Float, grow: Float, lift: Float): Float = base * (1f + (grow - 1f) * lift)

/**
 * 图层缩放：所有行都按放大后的字号 [layoutSp] 排版，这里只缩不放。API 23–27 的 OpenGL 渲染器把放大图层里的文字
 * 按取整后的倍数（至少 1 倍）栅格化再拉伸，1.2–1.4 倍的当前行会发虚；缩小时按原大栅格化再缩，仍然清楚。
 * 弹簧过冲带来的那一点放大也一并截掉。
 */
internal fun lyricLayerScale(visualSp: Float, layoutSp: Float): Float = (visualSp / layoutSp).coerceAtMost(1f)

/**
 * 排版字号：保留的旧字号 [heldSp] 和新预设放大后的字号 [targetSp] 取大。换成更小的预设时，过渡期间仍按旧的大字号排版，
 * 正在缩小的当前行不会超过排版字号、也不会超出列宽；过渡结束后再换成新字号。
 */
internal fun lyricLayoutSp(heldSp: Float, targetSp: Float): Float = max(heldSp, targetSp)

/** 占位高度按缩放后的视觉高度算：常规行缩小后不留大块空白，当前行长大时把邻行推开，而不是压在上面。 */
internal fun lyricSlotHeight(textHeight: Int, scale: Float, gapPx: Float): Int =
    (textHeight * scale + gapPx).roundToInt()

/** 某行中心到锚点的距离（px），正数表示在锚点下方、需要往后滚。 */
internal fun lyricAnchorError(itemOffset: Int, itemSize: Int, viewportStartOffset: Int, viewportHeight: Int, anchor: Float): Float =
    itemOffset - viewportStartOffset + itemSize / 2f - viewportHeight * anchor

/**
 * 弹簧进度为 [progress] 时这一帧要滚的距离。终点 = 已滚 + 剩余，每帧重算：
 * 目标行和上一行同时在缩放、终点一直在移动，也能沿弹簧曲线落到锚点。
 */
internal fun anchorScrollStep(scrolled: Float, remaining: Float, progress: Float): Float =
    (scrolled + remaining) * progress - scrolled

/**
 * 同步歌词：当前行放大并停在锚点，相邻行 60%、更远的行 38% 透明度（Android 12+ 再轻微模糊），上下两端渐隐（[lyricEdgeOf]）；
 * 点某一行跳到该处播放，手动滚动后 3 秒回到当前行。
 * [active] 以 State 下传，每行只在自己离当前行的远近变化时重组；进度只由当前行的逐字 leaf 读取。
 * 运行中换 [sizing]（如电视进入环境模式）会平滑过渡；视口高度逐帧变化（电视环境模式的过渡）时只重新测量，不重组。
 * [loading] 为 true 且没有可显示的同步歌词时显示加载态：既不会把尚未解析误报成“没有歌词”，
 * 也不会把未同步原文里的时间戳当成歌词；已有上一份同步歌词时继续保留到新结果就绪。
 */
@Composable
fun LyricsList(
    lyrics: ParsedLyrics,
    active: State<Int>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    position: State<Long>? = null,
    playing: Boolean = false,
    sizing: LyricsSizing,
) {
    // 列表状态在空/加载分支之前创建：切换歌词时 LazyColumn 重建也能接回原滚动位置。
    val list = rememberLazyListState()
    // 换预设时字号、行距、锚点一起过渡；这三个值只在布局阶段读取，过渡期间不重组
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val base = animateFloatAsState(sizing.baseSp, spatial, label = "lyricBase")
    val grow = animateFloatAsState(sizing.grow, spatial, label = "lyricGrow")
    val gap = animateFloatAsState(sizing.gapDp, spatial, label = "lyricGap")
    val anchor = animateFloatAsState(sizing.anchor, spatial, label = "lyricAnchor")
    val layoutSp = rememberLyricLayoutSp(sizing) { base.value == sizing.baseSp && grow.value == sizing.grow }
    if (loading && (lyrics.raw.isBlank() || !lyrics.synced)) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在加载歌词…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (lyrics.raw.isBlank()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Text("这首歌没有歌词", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val lines = remember(lyrics) { lyrics.displayLines() }
    val lyricKeys = remember(lines) { distinctKeys(lines) { it.timeMs.toString() } }
    val typography = MaterialTheme.typography.titleLarge
    // 所有行都按当前行放大后的字号排版：当前行图层不缩放，字是原大栅格化的；其他行缩小显示。
    // 非当前行一直带着缩放、换行时还要连续缩放，Animated 让字形不按像素取整，缩放过程中字不抖
    val style = remember(typography, layoutSp) {
        typography.copy(
            fontSize = layoutSp.sp,
            lineHeight = (layoutSp * LyricLineHeight).sp,
            textMotion = TextMotion.Animated,
        )
    }
    // 播放/暂停只有当前行的逐字填色关心，以 State 下传，其他行不跟着重组
    val playingState = rememberUpdatedState(playing)
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var manualAt by remember { mutableLongStateOf(0L) }
    SideEffect(dragged) { if (dragged) manualAt = System.currentTimeMillis() }
    // 上下留白按锚点比例分配：当前行中心固定在 anchor 处，视口高度变化后也不用重新滚动。
    // 视口高度在测量阶段交给留白，不用 BoxWithConstraints：它每次约束变化都重新组合，电视进出环境模式时歌词栏是逐帧变高的
    val padding = remember(anchor) { AnchorPadding(anchor) }
    val edgeOf = remember(list) { { index: Int -> list.edgeOf(index) } }
    LyricsAutoScroll(active, list, manualAt, lyrics, sizing, anchor)
    LazyColumn(
        state = list,
        modifier = modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                padding.height = if (constraints.hasBoundedHeight) constraints.maxHeight.toDp() else 0.dp
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
        contentPadding = padding,
    ) {
        itemsIndexed(
            lines,
            key = { index, _ -> lyricKeys[index] },
            contentType = { _, _ -> "lyric" },
        ) { index, line ->
            LyricRow(
                line = line,
                index = index,
                active = active,
                synced = lyrics.synced,
                style = style,
                layoutSp = layoutSp,
                base = base,
                grow = grow,
                gap = gap,
                position = position,
                playing = playingState,
                edgeOf = edgeOf,
                onSeek = onSeek,
            )
        }
    }
}

/** 第 [index] 行此刻压不压到两端渐隐区；不在可见范围内时为 null。 */
private fun LazyListState.edgeOf(index: Int): LyricEdge? {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val first = visible.firstOrNull()?.index ?: return null
    // 可见项按序号连续排列，直接按偏移取
    val item = visible.getOrNull(index - first)?.takeIf { it.index == index } ?: return null
    return lyricEdgeOf(item.offset - info.viewportStartOffset, item.size, info.viewportSize.height)
}

/**
 * 当前的排版字号（见 [lyricLayoutSp]）。换成更大的预设时立刻换；换成更小的预设时保留旧字号，
 * 等 [settled]（常规字号和放大倍数都过渡到位）再换，这一下只重组一次可见的行。
 */
@Composable
private fun rememberLyricLayoutSp(sizing: LyricsSizing, settled: () -> Boolean): Float {
    val held = remember { mutableFloatStateOf(sizing.currentSp) }
    val layoutSp = lyricLayoutSp(held.floatValue, sizing.currentSp)
    LaunchedEffect(sizing) {
        // 中途又换预设时从此刻的排版字号接着保留，不会缩到正在显示的字号以下
        held.floatValue = layoutSp
        snapshotFlow(settled).first { it }
        held.floatValue = sizing.currentSp
    }
    return layoutSp
}

/**
 * 上下留白跟随动画中的锚点；LazyColumn 只在测量时读取，锚点过渡时整列平移而不重组。
 * [height] 是视口高度，由 LazyColumn 上的布局修饰符在测量它之前写入，不必是快照状态：高度变了 LazyColumn 本来就要重新测量。
 */
@Stable
private class AnchorPadding(private val anchor: State<Float>) : PaddingValues {
    var height: Dp = 0.dp

    override fun calculateTopPadding(): Dp = height * anchor.value
    override fun calculateBottomPadding(): Dp = height * (1f - anchor.value)
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
}

@Composable
private fun LyricsAutoScroll(
    active: State<Int>,
    list: LazyListState,
    manualAt: Long,
    lyrics: ParsedLyrics,
    sizing: LyricsSizing,
    anchor: State<Float>,
) {
    val activeIndex = active.value
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    // 换了一份歌词后第一次直接定位，不从上一首的滚动位置滑过来
    val placed = remember(lyrics) { mutableStateOf(false) }
    LaunchedEffect(activeIndex, manualAt, sizing, lyrics) {
        if (activeIndex < 0) return@LaunchedEffect
        val since = System.currentTimeMillis() - manualAt
        if (since < 3_000) delay(3_000 - since)
        list.centerOn(activeIndex, { anchor.value }, if (placed.value) spec else null)
        placed.value = true
    }
}

/** 把第 [index] 行的中心移到锚点；[spec] 为 null 时直接定位。 */
private suspend fun LazyListState.centerOn(index: Int, anchor: () -> Float, spec: AnimationSpec<Float>?) {
    // 直接定位多用于刚换歌词，旧的布局信息可能还是上一首的，先跳一次强制重新测量
    if (spec == null) {
        scrollToItem(index)
    } else if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        animateScrollToItem(index)
    }
    scroll {
        if (spec == null) {
            anchorErrorOf(index, anchor())?.let { scrollBy(it) }
            return@scroll
        }
        var scrolled = 0f
        animate(0f, 1f, animationSpec = spec) { progress, _ ->
            val remaining = anchorErrorOf(index, anchor()) ?: return@animate
            scrolled += scrollBy(anchorScrollStep(scrolled, remaining, progress))
        }
    }
}

private fun LazyListState.anchorErrorOf(index: Int, anchor: Float): Float? {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    return lyricAnchorError(item.offset, item.size, info.viewportStartOffset, info.viewportSize.height, anchor)
}

@Composable
private fun LyricRow(
    line: LyricLine,
    index: Int,
    active: State<Int>,
    synced: Boolean,
    style: TextStyle,
    layoutSp: Float,
    base: State<Float>,
    grow: State<Float>,
    gap: State<Float>,
    position: State<Long>?,
    playing: State<Boolean>,
    edgeOf: (Int) -> LyricEdge?,
    onSeek: (Long) -> Unit,
) {
    // 每行只订阅自己离当前行的远近：换行时只有前后几行重组，远处的行不动
    val distance by remember(index, active) { derivedStateOf { lyricDistance(index, active.value) } }
    // 只在图层和绘制里读：中间的行一直是 null，滚动时不重画；只有渐隐区里的几行随位置重画遮罩
    val edge = remember(index, edgeOf) { derivedStateOf(structuralEqualityPolicy()) { edgeOf(index) } }
    val current = distance == 0
    val motion = MaterialTheme.motionScheme
    // 透明度、模糊、缩放都只在布局和 graphicsLayer 里读取，动画逐帧不重组
    val emphasis = animateFloatAsState(distance.toFloat(), motion.defaultEffectsSpec(), label = "lyricEmphasis")
    // 放大进度：0 常规行，1 当前行；放大倍数本身在列表层过渡，换预设时不用每行各起一个动画
    val lift = animateFloatAsState(if (current) 1f else 0f, motion.slowSpatialSpec(), label = "lyricLift")
    // 用户在外观里明确关掉模糊时也只降透明度
    val blurSupported = Build.VERSION.SDK_INT >= 31 && LocalAppearance.current.blur != BlurMode.Off
    val origin = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TransformOrigin(1f, 0.5f) else TransformOrigin(0f, 0.5f)
    val layerScale = { lyricLayerScale(lyricVisualSp(base.value, grow.value, lift.value), layoutSp) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 电视上遥控器停在哪一句要看得清，渐隐区里也一样：描边画在行的裁切和渐隐图层外面。
            // 不放大：整行放大会让左对齐的字横着跳一下
            .tvFocusRing(LyricRowShape, scale = 1f)
            .clip(LyricRowShape)
            .then(if (synced && line.timeMs >= 0) Modifier.clickable(onClickLabel = "从这一句播放") { onSeek(line.timeMs) } else Modifier)
            .padding(horizontal = 8.dp)
            // 文字按放大后的字号、整列宽度排版，变成当前行时不重新折行；占位高度按缩小后的视觉高度算，
            // 缩放本身在下面的 graphicsLayer 里做，不重新排版
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val scale = lyricLayerScale(lyricVisualSp(base.value, grow.value, lift.value), layoutSp)
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                val height = constraints.constrainHeight(lyricSlotHeight(placeable.height, scale, gap.value.dp.toPx()))
                layout(width, height) {
                    placeable.placeRelative(0, (height - placeable.height) / 2)
                }
            }
            .graphicsLayer {
                val scale = layerScale()
                scaleX = scale
                scaleY = scale
                transformOrigin = origin
                val level = emphasis.value
                alpha = lyricAlpha(level)
                val blur = lyricBlurDp(level, blurSupported).dp.toPx()
                renderEffect = if (blur > 0.05f) BlurEffect(blur, blur, TileMode.Decal) else null
                // 渐隐区里的行离屏合成，下面才能用遮罩擦；其余不模糊的行把透明度直接乘到绘制上，
                // 60% / 38% 的行不用每帧各开一个临时图层
                compositingStrategy = when {
                    edge.value != null -> CompositingStrategy.Offscreen
                    blur > 0.05f -> CompositingStrategy.Auto
                    else -> CompositingStrategy.ModulateAlpha
                }
            }
            .drawWithCache {
                val mask = edge.value?.let {
                    val scale = layerScale()
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        LyricFadeTop to Color.Black,
                        LyricFadeBottom to Color.Black,
                        1f to Color.Transparent,
                        startY = lyricEdgeLocalY(0f, it, scale, size.height),
                        endY = lyricEdgeLocalY(it.viewport, it, scale, size.height),
                    )
                }
                onDrawWithContent {
                    drawContent()
                    mask?.let { drawRect(it, blendMode = BlendMode.DstIn) }
                }
            },
    ) {
        // 逐字数据只用于当前行：其他行保持整行文本，避免列表里出现成倍的字级节点
        if (current && synced && line.words.isNotEmpty() && position != null) {
            KaraokeLine(line, position, playing.value, style)
        } else {
            Text(
                line.text,
                modifier = Modifier.fillMaxWidth(),
                style = style,
                fontWeight = if (current) FontWeight.Black else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 逐字卡拉OK行：底层暗字、上层亮字按各字起止时间裁剪。播放进度 500ms 才刷新一次，
 * 播放中用帧时钟在两次校准之间线性外推；时间只在 draw 阶段读取，逐帧变化不触发列表重组。
 * 电视上和背景、封面旋转共用 30 帧节拍（[TvFrameGate]），十英尺外看不出填色少了一半帧；
 * [capFrames] 为 true 时（海报墙的大卡）手机、平板上也限 30 帧。
 * [playing] 为 false 时停在当前进度，不跑帧循环。
 */
@Composable
internal fun KaraokeLine(
    line: LyricLine,
    position: State<Long>,
    playing: Boolean,
    style: TextStyle,
    capFrames: Boolean = LocalAppWindow.current.isTv,
) {
    val now = remember { mutableLongStateOf(0L) }
    LaunchedEffect(position.value, playing, capFrames) {
        if (!playing) {
            now.longValue = position.value
            return@LaunchedEffect
        }
        val base = position.value
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameNanos ->
                if (!capFrames || TvFrameGate.due(frameNanos)) now.longValue = base + (frameNanos - startNanos) / 1_000_000
            }
        }
    }
    val sung = MaterialTheme.colorScheme.onSurface
    val unsung = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val words = line.words
    FlowRow(
        // 每个字是暗字、亮字两层 Text，读屏会逐字、每字两遍地读；整行只读一遍原文。点按跳转的语义在外层行上，合并后照常可用
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = line.text },
    ) {
        for ((wordIndex, word) in words.withIndex()) {
            // 与整行文本一致：首行首字去前导空格、末字去尾随空格
            val text = word.text
                .let { if (wordIndex == 0) it.trimStart() else it }
                .let { if (wordIndex == words.lastIndex) it.trimEnd() else it }
            Box {
                Text(text, style = style, fontWeight = FontWeight.Black, color = unsung)
                Text(
                    text,
                    style = style,
                    fontWeight = FontWeight.Black,
                    color = sung,
                    modifier = Modifier
                        .matchParentSize()
                        .drawWithContent {
                            val progress = ((now.longValue - word.startMs).toFloat() /
                                (word.endMs - word.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
                            if (progress > 0f) {
                                clipRect(0f, 0f, size.width * progress, size.height) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        },
                )
            }
        }
    }
}

private val LyricRowShape = RoundedCornerShape(12.dp)
