package com.mytv.mymusic.ui.player

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.components.distinctKeys
import com.mytv.mymusic.ui.components.rememberSettlingOffsetState
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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

/**
 * 流动的封面光：一张 24px 的封面放大、慢慢旋转，本身就是模糊的；Android 12+ 再叠一层真模糊。
 * 暂停、减少动画、没有封面或不可见时停止转动。
 */
@Composable
fun AmbientBackdrop(cover: String, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduceMotion = LocalAppearance.current.reduceMotion
    val scheme = MaterialTheme.colorScheme
    val request = remember(context, cover) { ImageRequest.Builder(context).data(cover).size(24).build() }
    val spin = ambientSpinEnabled(playing, reduceMotion, cover)
    // 只持有 State，在绘制阶段读 value；否则整个背景每帧重组
    val angle = if (spin) {
        val transition = rememberInfiniteTransition(label = "ambient")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(38_000, easing = LinearEasing)), label = "spin")
    } else {
        null
    }
    val glow = remember(scheme.primaryContainer) {
        Brush.radialGradient(listOf(scheme.primaryContainer.copy(alpha = 0.55f), Color.Transparent), radius = 1400f)
    }
    val shade = remember(scheme.surface) {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.35f to Color.Transparent,
            1f to scheme.surface.copy(alpha = 0.72f),
        )
    }
    Box(modifier.fillMaxSize().background(scheme.surface)) {
        if (cover.isNotBlank()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = angle?.value ?: 0f
                        scaleX = 2.4f
                        scaleY = 2.4f
                        alpha = 0.78f
                    }
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp) else Modifier),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(glow)
                .background(shade),
        )
    }
}

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
 * 同步歌词：当前行居中放大，点某一行跳到该处播放；手动滚动后 3 秒回到当前行。
 * [active] 以 State 下传，只有读它的歌词行/自动滚动小组件会随当前行变化重组。
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
) {
    // 列表状态在空/加载分支之前创建：切换歌词时 LazyColumn 重建也能接回原滚动位置。
    val list = rememberLazyListState()
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
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var manualAt by remember { mutableLongStateOf(0L) }
    SideEffect(dragged) { if (dragged) manualAt = System.currentTimeMillis() }
    BoxWithConstraints(modifier) {
        val pad = maxHeight / 3
        LyricsAutoScroll(active, list, manualAt)
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = pad, bottom = pad * 2),
        ) {
            itemsIndexed(
                lines,
                key = { index, _ -> lyricKeys[index] },
                contentType = { _, _ -> "lyric" },
            ) { index, line ->
                LyricRow(
                    line = line,
                    index = index,
                    activeProvider = { active.value },
                    synced = lyrics.synced,
                    center = center,
                    large = large,
                    position = position,
                    playing = playing,
                    onSeek = onSeek,
                )
            }
        }
    }
}

@Composable
private fun LyricsAutoScroll(active: State<Int>, list: LazyListState, manualAt: Long) {
    val activeIndex = active.value
    LaunchedEffect(activeIndex, manualAt) {
        if (activeIndex < 0) return@LaunchedEffect
        val since = System.currentTimeMillis() - manualAt
        if (since < 3_000) delay(3_000 - since)
        list.animateScrollToItem(activeIndex)
    }
}

@Composable
private fun LyricRow(
    line: LyricLine,
    index: Int,
    activeProvider: () -> Int,
    synced: Boolean,
    center: Boolean,
    large: Boolean,
    position: State<Long>?,
    playing: Boolean,
    onSeek: (Long) -> Unit,
) {
    val active = activeProvider()
    val current = index == active
    val near = abs(index - active) == 1
    val weight = animateFloatAsState(if (current) 1f else if (near) 0.6f else 0.38f, label = "lyricAlpha")
    val style = when {
        large && current -> MaterialTheme.typography.displaySmall
        large -> MaterialTheme.typography.headlineMedium
        current -> MaterialTheme.typography.headlineSmall
        else -> MaterialTheme.typography.titleLarge
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (synced && line.timeMs >= 0) Modifier.clickable(onClickLabel = "从这一句播放") { onSeek(line.timeMs) } else Modifier)
            .padding(horizontal = 8.dp, vertical = if (large) 10.dp else 8.dp)
            .graphicsLayer { alpha = weight.value },
    ) {
        // 逐字数据只用于当前行：其他行保持整行文本，避免列表里出现成倍的字级节点
        if (current && synced && line.words.isNotEmpty() && position != null) {
            KaraokeLine(line, position, playing, center, style)
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
private fun KaraokeLine(
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
