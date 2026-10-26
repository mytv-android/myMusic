package com.mytv.mymusic.ui.player

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.components.distinctKeys
import com.mytv.mymusic.ui.components.rememberSettlingOffsetState
import com.mytv.mymusic.ui.theme.BlurMode
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 控件 4 秒无操作后隐藏。任意按下或按键唤醒；电视上唤醒的那一下不触发按键动作。 */
@Stable
class IdleState {
    var idle by mutableStateOf(false)
        internal set
    internal var tick by mutableLongStateOf(0L)

    fun poke() {
        idle = false
        tick++
    }
}

@Composable
fun rememberIdleState(playing: Boolean, timeoutMs: Long = 4_000L): IdleState {
    val state = remember { IdleState() }
    val screenReader = rememberScreenReaderOn()
    LaunchedEffect(state.tick, playing, screenReader) {
        state.idle = false
        // 读屏用户看不到控件淡入淡出，隐藏后焦点会落到看不见的地方，所以不进入空闲
        if (playing && !screenReader) {
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
    .onPreviewKeyEvent {
        val wasIdle = idle.idle
        idle.poke()
        consumeKeyWhenIdle && wasIdle
    }

/** 封面可以呼吸/旋转的前提：可见、播放中、未减少动画并且确实有封面。 */
internal fun shouldBreathe(playing: Boolean, visible: Boolean, reduceMotion: Boolean, cover: String): Boolean =
    playing && visible && !reduceMotion && cover.isNotBlank()

internal fun ambientSpinEnabled(playing: Boolean, reduceMotion: Boolean, cover: String): Boolean =
    playing && !reduceMotion && cover.isNotBlank()

/** 封面边缘渐隐：竖向（手机，向下淡出）或横向（平板 / 电视，向右淡出）。 */
fun Modifier.fadeEdge(horizontal: Boolean): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithCache {
        val brush = if (horizontal) {
            Brush.horizontalGradient(0f to Color.Black, 0.6f to Color.Black, 0.98f to Color.Transparent)
        } else {
            Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black, 0.97f to Color.Transparent)
        }
        onDrawWithContent {
            drawContent()
            drawRect(brush, blendMode = BlendMode.DstIn)
        }
    }

/**
 * 铺满的封面：左右滑切歌、双击收藏。播放时缓慢“呼吸”，暂停时略微缩小变暗。
 * [visible] 为 false 时不启动呼吸动画，供横屏歌词淡出完成后停用不可见动画。
 */
@Composable
fun PlayerArt(
    cover: String,
    title: String,
    playing: Boolean,
    horizontalFade: Boolean,
    onSkip: (Int) -> Unit,
    onDoubleTap: () -> Unit,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    val drag = rememberSettlingOffsetState()
    val threshold = with(LocalDensity.current) { 88.dp.toPx() }
    val currentThreshold by rememberUpdatedState(threshold)
    // pointerInput(Unit) 不会重启，回调要取最新的，否则双击收藏的是播放页刚打开时那首歌
    val currentSkip by rememberUpdatedState(onSkip)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val breathe = if (shouldBreathe(playing, visible, reduceMotion, cover)) {
        val transition = rememberInfiniteTransition(label = "breathe")
        transition.animateFloat(1f, 1.035f, infiniteRepeatable(tween(7_000), RepeatMode.Reverse), label = "breatheScale")
    } else {
        null
    }
    val pausedScale = animateFloatAsState(if (playing) 1f else 0.965f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "pausedScale")
    val dim = animateFloatAsState(if (playing) 0f else 0.3f, MaterialTheme.motionScheme.slowEffectsSpec(), label = "dim")
    Box(
        modifier
            .fadeEdge(horizontalFade)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { currentDoubleTap() })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag.begin() },
                    onDragEnd = {
                        val value = drag.offset
                        if (abs(value) > currentThreshold) currentSkip(if (value < 0) 1 else -1)
                        drag.settle()
                    },
                    onDragCancel = { drag.settle() },
                ) { change, amount ->
                    change.consume()
                    drag.add(amount * 0.5f)
                }
            }
            // 滑动和双击对读屏用户不可用，提供等价的自定义操作
            .semantics {
                contentDescription = "$title 的封面"
                customActions = listOf(
                    CustomAccessibilityAction("下一首") { currentSkip(1); true },
                    CustomAccessibilityAction("上一首") { currentSkip(-1); true },
                    CustomAccessibilityAction("收藏") { currentDoubleTap(); true },
                )
            },
    ) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = (breathe?.value ?: 1f) * pausedScale.value
                    scaleX = scale
                    scaleY = scale
                    translationX = drag.offset
                    alpha = 1f - (abs(drag.offset) / (currentThreshold * 4)).coerceIn(0f, 0.5f)
                },
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }.background(Color.Black))
    }
}

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
        /** 原来的默认档：常规行 titleLarge 22sp，当前行 headlineSmall 24sp。 */
        val Standard = LyricsSizing(22f, 24f / 22f, 16f, 0.38f)

        /** 原来的 large 档：常规行 headlineMedium 28sp，当前行 displaySmall 36sp。 */
        val Large = LyricsSizing(28f, 36f / 28f, 20f, 0.38f)
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

/**
 * 所有行都按当前行放大后的宽度折行，变成当前行时只放大、不重新折行。
 * 预设字号变小的过渡中（[baseRatio] > 1）再收窄，旧字号的放大不会超出列宽。
 */
internal fun lyricTextWidth(width: Int, grow: Float, baseRatio: Float): Int =
    (width / (grow * max(1f, baseRatio))).toInt()

/** 占位高度按缩放后的视觉高度算：放大的当前行把邻行推开，而不是压在上面。 */
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
 * 同步歌词：当前行放大并停在锚点，相邻行 60%、更远的行 38% 透明度（Android 12+ 再轻微模糊）；
 * 点某一行跳到该处播放，手动滚动后 3 秒回到当前行。
 * [active] 以 State 下传，每行只在自己离当前行的远近变化时重组；进度只由当前行的逐字 leaf 读取。
 * [sizing] 默认按 [large] 沿用原来的字号；运行中换预设（如电视进入环境模式）会平滑过渡。
 * [loading] 为 true 且没有可显示的同步歌词时显示加载态：既不会把尚未解析误报成“没有歌词”，
 * 也不会把未同步原文里的时间戳当成歌词；已有上一份同步歌词时继续保留到新结果就绪。
 */
@Composable
fun LyricsList(
    lyrics: ParsedLyrics,
    active: State<Int>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    center: Boolean = false,
    large: Boolean = false,
    loading: Boolean = false,
    position: State<Long>? = null,
    playing: Boolean = false,
    sizing: LyricsSizing = if (large) LyricsSizing.Large else LyricsSizing.Standard,
) {
    // 列表状态在空/加载分支之前创建：切换歌词时 LazyColumn 重建也能接回原滚动位置。
    val list = rememberLazyListState()
    // 换预设时字号、行距、锚点一起过渡；这三个值只在布局阶段读取，过渡期间不重组
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val base = animateFloatAsState(sizing.baseSp, spatial, label = "lyricBase")
    val gap = animateFloatAsState(sizing.gapDp, spatial, label = "lyricGap")
    val anchor = animateFloatAsState(sizing.anchor, spatial, label = "lyricAnchor")
    if (loading && (lyrics.raw.isBlank() || !lyrics.synced)) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在加载歌词…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (lyrics.raw.isBlank()) {
        Box(modifier.fillMaxSize(), contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
            Text("这首歌没有歌词", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val lines = remember(lyrics) { lyrics.displayLines() }
    val lyricKeys = remember(lines) { distinctKeys(lines) { it.timeMs.toString() } }
    val typography = MaterialTheme.typography.titleLarge
    // 当前行靠缩放放大，Animated 让字形不按像素取整，缩放过程中字不抖
    val style = remember(typography, sizing.baseSp) {
        typography.copy(
            fontSize = sizing.baseSp.sp,
            lineHeight = (sizing.baseSp * LyricLineHeight).sp,
            textMotion = TextMotion.Animated,
        )
    }
    // 播放/暂停只有当前行的逐字填色关心，以 State 下传，其他行不跟着重组
    val playingState = rememberUpdatedState(playing)
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var manualAt by remember { mutableLongStateOf(0L) }
    SideEffect(dragged) { if (dragged) manualAt = System.currentTimeMillis() }
    BoxWithConstraints(modifier) {
        // 上下留白按锚点比例分配：当前行中心固定在 anchor 处，视口高度变化后也不用重新滚动
        val padding = remember(maxHeight, anchor) { AnchorPadding(maxHeight, anchor) }
        LyricsAutoScroll(active, list, manualAt, lyrics, sizing, anchor)
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
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
                    center = center,
                    sizing = sizing,
                    style = style,
                    base = base,
                    gap = gap,
                    position = position,
                    playing = playingState,
                    onSeek = onSeek,
                )
            }
        }
    }
}

/** 上下留白跟随动画中的锚点；LazyColumn 只在测量时读取，锚点过渡时整列平移而不重组。 */
@Stable
private class AnchorPadding(private val height: Dp, private val anchor: State<Float>) : PaddingValues {
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
    center: Boolean,
    sizing: LyricsSizing,
    style: TextStyle,
    base: State<Float>,
    gap: State<Float>,
    position: State<Long>?,
    playing: State<Boolean>,
    onSeek: (Long) -> Unit,
) {
    // 每行只订阅自己离当前行的远近：换行时只有前后几行重组，远处的行不动
    val distance by remember(index, active) { derivedStateOf { lyricDistance(index, active.value) } }
    val current = distance == 0
    val motion = MaterialTheme.motionScheme
    // 透明度、模糊、缩放都只在布局和 graphicsLayer 里读取，动画逐帧不重组
    val emphasis = animateFloatAsState(distance.toFloat(), motion.defaultEffectsSpec(), label = "lyricEmphasis")
    val scale = animateFloatAsState(if (current) sizing.grow else 1f, motion.slowSpatialSpec(), label = "lyricScale")
    // 用户在外观里明确关掉模糊时也只降透明度
    val blurSupported = Build.VERSION.SDK_INT >= 31 && LocalAppearance.current.blur != BlurMode.Off
    val origin = when {
        center -> TransformOrigin.Center
        LocalLayoutDirection.current == LayoutDirection.Rtl -> TransformOrigin(1f, 0.5f)
        else -> TransformOrigin(0f, 0.5f)
    }
    val targetBase = sizing.baseSp
    val grow = sizing.grow
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (synced && line.timeMs >= 0) Modifier.clickable(onClickLabel = "从这一句播放") { onSeek(line.timeMs) } else Modifier)
            .padding(horizontal = 8.dp)
            // 文字按放大后的宽度排版，占位高度跟着缩放走；缩放本身在下面的 graphicsLayer 里做，不重新排版
            .layout { measurable, constraints ->
                val ratio = base.value / targetBase
                val bounded = constraints.hasBoundedWidth
                val textWidth = if (bounded) lyricTextWidth(constraints.maxWidth, grow, ratio) else constraints.maxWidth
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = textWidth, minHeight = 0))
                val width = if (bounded) constraints.maxWidth else placeable.width
                val height = constraints.constrainHeight(lyricSlotHeight(placeable.height, scale.value * ratio, gap.value.dp.toPx()))
                layout(width, height) {
                    placeable.placeRelative(if (center) (width - placeable.width) / 2 else 0, (height - placeable.height) / 2)
                }
            }
            .graphicsLayer {
                val visual = scale.value * base.value / targetBase
                scaleX = visual
                scaleY = visual
                transformOrigin = origin
                val level = emphasis.value
                alpha = lyricAlpha(level)
                val blur = lyricBlurDp(level, blurSupported).dp.toPx()
                renderEffect = if (blur > 0.05f) BlurEffect(blur, blur, TileMode.Decal) else null
            },
    ) {
        // 逐字数据只用于当前行：其他行保持整行文本，避免列表里出现成倍的字级节点
        if (current && synced && line.words.isNotEmpty() && position != null) {
            KaraokeLine(line, position, playing.value, center, style)
        } else {
            Text(
                line.text,
                modifier = Modifier.fillMaxWidth(),
                style = style,
                fontWeight = if (current) FontWeight.Black else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = if (center) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

/**
 * 逐字卡拉OK行：底层暗字、上层亮字按各字起止时间裁剪。播放进度 500ms 才刷新一次，
 * 播放中用帧时钟在两次校准之间线性外推；时间只在 draw 阶段读取，逐帧变化不触发列表重组。
 */
@Composable
internal fun KaraokeLine(
    line: LyricLine,
    position: State<Long>,
    playing: Boolean,
    center: Boolean,
    style: TextStyle,
) {
    val now = remember { mutableLongStateOf(0L) }
    LaunchedEffect(position.value, playing) {
        if (!playing) {
            now.longValue = position.value
            return@LaunchedEffect
        }
        val base = position.value
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameNanos -> now.longValue = base + (frameNanos - startNanos) / 1_000_000 }
        }
    }
    val sung = MaterialTheme.colorScheme.onSurface
    val unsung = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val words = line.words
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(0.dp, if (center) Alignment.CenterHorizontally else Alignment.Start),
    ) {
        for ((wordIndex, word) in words.withIndex()) {
            // 与整行文本一致：首行首字去前导空格、末字去尾随空格，居中时不受不可见空格影响
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
