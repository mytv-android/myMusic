package com.mytv.mymusic.ui.player

import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.runtime.DisposableEffect
import android.view.accessibility.AccessibilityManager
import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mytv.mymusic.ui.LyricLine
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.components.distinctKeys
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

/**
 * 流动的封面光：一张 24px 的封面放大、慢慢旋转，本身就是模糊的；Android 12+ 再叠一层真模糊。
 * 暂停、减少动画或不可见时停止转动。
 */
@Composable
fun AmbientBackdrop(cover: String, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduceMotion = LocalAppearance.current.reduceMotion
    val scheme = MaterialTheme.colorScheme
    val request = remember(cover) { ImageRequest.Builder(context).data(cover).size(24).build() }
    val spin = !reduceMotion && playing
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
 */
@Composable
fun PlayerArt(
    cover: String,
    title: String,
    playing: Boolean,
    horizontalFade: Boolean,
    onSkip: (Int) -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 88.dp.toPx() }
    // pointerInput(Unit) 不会重启，回调要取最新的，否则双击收藏的是播放页刚打开时那首歌
    val currentSkip by rememberUpdatedState(onSkip)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val breathe = if (playing && !reduceMotion) {
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
                    onDragEnd = {
                        val value = drag.value
                        if (abs(value) > threshold) currentSkip(if (value < 0) 1 else -1)
                        scope.launch { drag.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { drag.snapTo(drag.value + amount * 0.5f) }
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
                    translationX = drag.value
                    alpha = 1f - (abs(drag.value) / (threshold * 4)).coerceIn(0f, 0.5f)
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

/**
 * 同步歌词：当前行居中放大，点某一行跳到该处播放；手动滚动后 3 秒回到当前行。
 */
@Composable
fun LyricsList(
    lyrics: ParsedLyrics,
    active: Int,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    center: Boolean = false,
    large: Boolean = false,
) {
    if (lyrics.raw.isBlank()) {
        Box(modifier.fillMaxSize(), contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
            Text("这首歌没有歌词", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val lines: List<LyricLine> = if (lyrics.synced) lyrics.lines else lyrics.raw.lines().filter { it.isNotBlank() }.map { LyricLine(-1, it) }
    val lyricKeys = remember(lines) { distinctKeys(lines) { it.timeMs.toString() } }
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var manualAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) { if (dragged) manualAt = System.currentTimeMillis() }
    BoxWithConstraints(modifier) {
        val pad = maxHeight / 3
        LaunchedEffect(active, manualAt) {
            if (active < 0) return@LaunchedEffect
            val since = System.currentTimeMillis() - manualAt
            if (since < 3_000) delay(3_000 - since)
            list.animateScrollToItem(active)
        }
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
                val current = index == active
                val near = abs(index - active) == 1
                val weight = animateFloatAsState(if (current) 1f else if (near) 0.6f else 0.38f, label = "lyricAlpha")
                Text(
                    line.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (lyrics.synced && line.timeMs >= 0) Modifier.clickable(onClickLabel = "从这一句播放") { onSeek(line.timeMs) } else Modifier)
                        .padding(horizontal = 8.dp, vertical = if (large) 10.dp else 8.dp)
                        .graphicsLayer { alpha = weight.value },
                    style = when {
                        large && current -> MaterialTheme.typography.displaySmall
                        large -> MaterialTheme.typography.headlineMedium
                        current -> MaterialTheme.typography.headlineSmall
                        else -> MaterialTheme.typography.titleLarge
                    },
                    fontWeight = if (current) FontWeight.Black else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = if (center) TextAlign.Center else TextAlign.Start,
                )
            }
        }
    }
}
