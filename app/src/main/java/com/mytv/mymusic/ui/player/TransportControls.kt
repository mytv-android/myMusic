package com.mytv.mymusic.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 连接按钮组的尺寸。总宽固定：播放中两段间距从 [gap] 拉到 [playingGap]，多出来的从两侧段里扣；
 * 按下哪段哪段加宽 [pressGrow]，相邻段让出同样的宽度，整组宽度始终不变。
 */
@Immutable
data class TransportGroupSize(
    val height: Dp,
    val sideWidth: Dp,
    val playWidth: Dp,
    val iconSize: Dp,
    val playIconSize: Dp,
    val pressGrow: Dp,
    val innerCorner: Dp = 14.dp,
    val gap: Dp = 4.dp,
    val playingGap: Dp = 8.dp,
) {
    val width: Dp get() = sideWidth * 2 + playWidth + gap * 2

    companion object {
        /** 手机播放页：256×88，播放键 88 见方。 */
        val Phone = TransportGroupSize(88.dp, 80.dp, 88.dp, 34.dp, 40.dp, pressGrow = 20.dp)

        /** 平板播放栏：256×88。 */
        val Tablet = TransportGroupSize(88.dp, 80.dp, 88.dp, 34.dp, 40.dp, pressGrow = 20.dp)

        /** 手机歌词页底部的小号：高 56，播放键 56 见方。 */
        val Compact = TransportGroupSize(56.dp, 52.dp, 56.dp, 26.dp, 32.dp, pressGrow = 12.dp)

        /** 电视控制条：192×56（控制条分两行，按钮组压到 56 高给进度条让出一行）。 */
        val Tv = TransportGroupSize(56.dp, 64.dp, 56.dp, 26.dp, 32.dp, pressGrow = 14.dp)
    }
}

/**
 * 上一首 / 播放 / 下一首连成一块的按钮组：两端满圆、内侧 [TransportGroupSize.innerCorner]。
 * 中间播放键是 M3E 花瓣形（Cookie12），各尺寸里播放段宽=高，花瓣才不会被拉扁。
 * 按下哪段哪段变宽，相邻段让位（快速空间弹簧）；播放中两段间距拉开（默认空间弹簧）。
 * 宽度和间距只在布局阶段读，形状只在图层里读：按下和播放状态的过渡不触发组合。
 *
 * 不用 material3 的 ButtonGroup：alpha25 的版本按条目 DSL 布局并在放不下时把按钮收进溢出菜单，
 * 不支持固定段宽、动画间距和中段单独变形，窄屏上还可能把上一首 / 下一首藏进菜单。
 *
 * @param playFocusRequester 电视上让播放段拿初始焦点。
 */
@Composable
fun TransportButtonGroup(
    playing: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    size: TransportGroupSize = TransportGroupSize.Phone,
    playFocusRequester: FocusRequester? = null,
) {
    val motion = MaterialTheme.motionScheme
    val leadSource = remember { MutableInteractionSource() }
    val playSource = remember { MutableInteractionSource() }
    val trailSource = remember { MutableInteractionSource() }
    val leadPressed by leadSource.collectIsPressedAsState()
    val playPressed by playSource.collectIsPressedAsState()
    val trailPressed by trailSource.collectIsPressedAsState()
    val leadGrow = animateFloatAsState(if (leadPressed) 1f else 0f, motion.fastSpatialSpec(), label = "leadGrow")
    val playGrow = animateFloatAsState(if (playPressed) 1f else 0f, motion.fastSpatialSpec(), label = "playGrow")
    val trailGrow = animateFloatAsState(if (trailPressed) 1f else 0f, motion.fastSpatialSpec(), label = "trailGrow")
    val gap = animateDpAsState(if (playing) size.playingGap else size.gap, motion.defaultSpatialSpec(), label = "groupGap")

    val outer = size.height / 2
    val inner = size.innerCorner
    // 媒体控制在 RTL 下也不镜像，所以用绝对方向的圆角和摆放
    val leadShape = remember(size) { AbsoluteRoundedCornerShape(outer, inner, inner, outer) }
    val trailShape = remember(size) { AbsoluteRoundedCornerShape(inner, outer, outer, inner) }
    // 播放键的花瓣形容器，电视焦点环也沿花瓣描
    val playShape = MaterialShapes.Cookie12Sided.toShape()
    val secondary = MaterialTheme.colorScheme.secondaryContainer
    val onSecondary = MaterialTheme.colorScheme.onSecondaryContainer

    Layout(
        content = {
            GroupSegment(
                onClick = onPrevious,
                shape = { leadShape },
                focusShape = leadShape,
                container = secondary,
                content = onSecondary,
                interaction = leadSource,
                modifier = Modifier.semantics { contentDescription = "上一首" },
            ) { Icon(Icons.Filled.SkipPrevious, null, Modifier.size(size.iconSize)) }
            GroupSegment(
                onClick = onPlayPause,
                shape = { playShape },
                focusShape = playShape,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                interaction = playSource,
                focusScale = 1.06f,
                accentFocus = true,
                modifier = Modifier
                    .then(if (playFocusRequester != null) Modifier.focusRequester(playFocusRequester) else Modifier)
                    .semantics {
                        contentDescription = if (playing) "暂停" else "播放"
                        stateDescription = if (playing) "正在播放" else "已暂停"
                    },
            ) {
                Crossfade(playing, label = "playIcon") { isPlaying ->
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(size.playIconSize))
                }
            }
            GroupSegment(
                onClick = onNext,
                shape = { trailShape },
                focusShape = trailShape,
                container = secondary,
                content = onSecondary,
                interaction = trailSource,
                modifier = Modifier.semantics { contentDescription = "下一首" },
            ) { Icon(Icons.Filled.SkipNext, null, Modifier.size(size.iconSize)) }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val total = size.width.roundToPx().coerceAtMost(constraints.maxWidth)
        val height = size.height.roundToPx()
        val g = gap.value.roundToPx()
        val widths = segmentWidths(
            total = total.toFloat(),
            play = size.playWidth.toPx(),
            gap = g.toFloat(),
            grow = size.pressGrow.toPx(),
            lead = leadGrow.value,
            middle = playGrow.value,
            trail = trailGrow.value,
        )
        val lead = widths[0].roundToInt()
        val middle = widths[1].roundToInt()
        // 末段吃掉取整误差，整组宽度一像素不差
        val trail = (total - lead - middle - g * 2).coerceAtLeast(0)
        val placeables = listOf(lead, middle, trail).mapIndexed { i, w -> measurables[i].measure(Constraints.fixed(w.coerceAtLeast(0), height)) }
        layout(total, height) {
            var x = 0
            placeables.forEachIndexed { i, it ->
                // 播放段的电视焦点环画在裁切外、还放大 1.06 倍，会伸进两侧的间距里；摆在上层，不被后摆的“下一首”盖住
                it.place(x, 0, zIndex = if (i == 1) 1f else 0f)
                x += it.width + g
            }
        }
    }
}

/**
 * 三段宽度：[lead]、[middle]、[trail] 是各段按下的程度（0..1）。中段按下时两侧各让一半；
 * 侧段按下时由中段让位。纯函数，便于测试“总宽不变”。
 */
internal fun segmentWidths(total: Float, play: Float, gap: Float, grow: Float, lead: Float, middle: Float, trail: Float): FloatArray {
    val side = (total - play - gap * 2) / 2f
    return floatArrayOf(
        side + grow * (lead - middle / 2f),
        play + grow * (middle - lead - trail),
        side + grow * (trail - middle / 2f),
    )
}

@Composable
private fun GroupSegment(
    onClick: () -> Unit,
    shape: () -> Shape,
    focusShape: Shape,
    container: Color,
    content: Color,
    interaction: MutableInteractionSource,
    modifier: Modifier,
    focusScale: Float = 1.04f,
    accentFocus: Boolean = false,
    icon: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            // 焦点环放在裁切图层外面：放在里面时放大被裁掉、描边也只剩内侧一半
            .then(if (accentFocus) Modifier.tvAccentFocusRing(focusShape, focusScale) else Modifier.tvFocusRing(focusShape, focusScale))
            .graphicsLayer {
                this.shape = shape()
                clip = true
            }
            .semantics { role = Role.Button },
        shape = RectangleShape,
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

/**
 * 电视上主色按钮（播放键）的焦点：主色描边画在主色按钮上看不出来，按设计稿先留一圈 3dp 底色缝，
 * 外面再画 3dp onSurface 描边，同时放大 [scale] 倍。非电视为空操作；缩放只在绘制阶段读，不触发组合和布局。
 */
@Composable
private fun Modifier.tvAccentFocusRing(shape: Shape, scale: Float): Modifier {
    if (!LocalAppWindow.current.isTv) return this
    return this then AccentFocusElement(
        shape = shape,
        scale = scale,
        gap = MaterialTheme.colorScheme.surface,
        ring = MaterialTheme.colorScheme.onSurface,
        spec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
}

private data class AccentFocusElement(
    val shape: Shape,
    val scale: Float,
    val gap: Color,
    val ring: Color,
    val spec: AnimationSpec<Float>,
) : ModifierNodeElement<AccentFocusNode>() {
    override fun create() = AccentFocusNode(shape, scale, gap, ring, spec)

    override fun update(node: AccentFocusNode) {
        node.shape = shape
        node.targetScale = scale
        node.gap = gap
        node.ring = ring
        node.spec = spec
    }
}

private class AccentFocusNode(
    var shape: Shape,
    var targetScale: Float,
    var gap: Color,
    var ring: Color,
    var spec: AnimationSpec<Float>,
) : Modifier.Node(), FocusEventModifierNode, DrawModifierNode {
    private val animated = Animatable(1f)
    private var focused = false

    // 两圈轮廓只随尺寸、形状、密度变化；放大动画的每一帧直接复用
    private var inner: Outline? = null
    private var outer: Outline? = null
    private var stroke: Stroke? = null
    private var cachedShape: Shape? = null
    private var cachedSize = Size.Zero
    private var cachedDensity = Float.NaN
    private var cachedDirection: LayoutDirection? = null

    override fun onFocusEvent(focusState: FocusState) {
        val now = focusState.isFocused || focusState.hasFocus
        if (now == focused) return
        focused = now
        coroutineScope.launch { animated.animateTo(if (now) targetScale else 1f, spec) }
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val current = animated.value
        scale(current, current) {
            this@draw.drawContent()
            if (!focused) return@scale
            val width = 3.dp.toPx()
            if (inner == null || cachedShape != shape || cachedSize != size || cachedDensity != density || cachedDirection != layoutDirection) {
                // 描边以轮廓为中线：底色缝的中线在外扩半个线宽处，onSurface 环再往外一个线宽
                inner = shape.createOutline(Size(size.width + width, size.height + width), layoutDirection, this)
                outer = shape.createOutline(Size(size.width + width * 3, size.height + width * 3), layoutDirection, this)
                stroke = Stroke(width)
                cachedShape = shape
                cachedSize = size
                cachedDensity = density
                cachedDirection = layoutDirection
            }
            translate(-width / 2, -width / 2) { drawOutline(inner!!, gap, style = stroke!!) }
            translate(-width * 1.5f, -width * 1.5f) { drawOutline(outer!!, ring, style = stroke!!) }
        }
    }
}

/**
 * 播放页的浮动工具栏：surfaceContainerHigh 84% 的胶囊、柔和阴影，里面放 48dp 的项。
 * 手机高 60，平板 56；项由调用方按顺序放（歌词、队列、定时、音源、更多）。
 */
@Composable
fun PlayerToolbar(
    modifier: Modifier = Modifier,
    height: Dp = 60.dp,
    content: @Composable RowScope.() -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier.height(height),
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.84f),
            toolbarContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 6.dp),
        content = content,
    )
}

/** 工具栏里的普通图标项。[selected] 时是 primaryContainer 底、16dp 圆角（歌词页里的“歌词”）。 */
@Composable
fun ToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val shape = if (selected) RoundedCornerShape(16.dp) else CircleShape
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .tvFocusRing(shape)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null) }
    }
}

/**
 * “歌词”项：开着歌词页（手机歌词页、平板专注歌词）时是 16dp 圆角的选中底，点了退出。
 * 读屏文案沿用“全屏歌词 / 退出全屏歌词”。
 */
@Composable
fun ToolbarLyricsButton(on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) =
    ToolbarIconButton(Icons.Filled.Lyrics, if (on) "退出全屏歌词" else "全屏歌词", { onToggle(!on) }, modifier, selected = on)

/**
 * “定时”项：定时开着时展开成剩余时间（primary 色、等宽数字）或“本首结束”。
 * 剩余时间每秒变化，只在这个小组件里读；读屏文案沿用原来的定时 chip。
 */
@Composable
fun ToolbarSleepButton(sleep: SleepTimerState, onOpenTimer: () -> Unit, modifier: Modifier = Modifier) {
    if (!sleep.active) {
        ToolbarIconButton(Icons.Filled.Bedtime, "定时关闭", onOpenTimer, modifier)
        return
    }
    val remaining = rememberSleepRemaining(sleep)
    SleepRemaining(sleep, remaining, onOpenTimer, modifier)
}

@Composable
private fun SleepRemaining(sleep: SleepTimerState, remaining: State<Long>, onOpenTimer: () -> Unit, modifier: Modifier) {
    val text = if (sleep.endOfTrack) "本首结束" else formatClock(remaining.value)
    Surface(
        onClick = onOpenTimer,
        modifier = modifier
            .height(48.dp)
            .widthIn(min = 48.dp)
            .animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())
            .tvFocusRing(CircleShape)
            .semantics {
                contentDescription = if (sleep.endOfTrack) "定时关闭：本首结束后停止" else "定时关闭：还剩 $text"
                role = Role.Button
            },
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp).clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.Bedtime, null, Modifier.size(20.dp))
            // labelLarge 已开 tnum：倒计时每秒跳动时宽度不抖
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 队列项。 */
@Composable
fun ToolbarQueueButton(onOpenQueue: () -> Unit, modifier: Modifier = Modifier) =
    ToolbarIconButton(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", onOpenQueue, modifier)

/** 换音源项。 */
@Composable
fun ToolbarSourceButton(onOpenSource: () -> Unit, modifier: Modifier = Modifier) =
    ToolbarIconButton(Icons.Filled.SwapHoriz, "换音源", onOpenSource, modifier)
