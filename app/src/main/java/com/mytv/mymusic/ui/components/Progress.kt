package com.mytv.mymusic.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.formatTime
import com.mytv.mymusic.ui.theme.LocalAppearance

/**
 * 波浪进度条：播放时有波浪，暂停或减少动画时拉平。可拖动、可点按跳转；电视上左右键快退快进 10 秒。
 */
@Composable
fun WavySeekBar(
    position: Long,
    duration: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
    showTimes: Boolean = true,
) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val seek by rememberUpdatedState(onSeek)
    val fraction = if (dragging) dragFraction else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val wave = playing && !dragging && !reduceMotion
    // 读屏按 10 秒取整：焦点停在进度条上时，每次数值变化都会被播报，按毫秒更新会一直打断
    val spokenPosition = if (dragging) (dragFraction * duration).toLong() else position / 10_000 * 10_000
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .semantics {
                    contentDescription = "播放进度"
                    stateDescription = "已播放 ${spokenTime(spokenPosition)}，共 ${spokenTime(duration)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(if (duration > 0) (spokenPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f, 0f..1f)
                    setProgress { target ->
                        if (duration > 0) onSeek((target.coerceIn(0f, 1f) * duration).toLong())
                        true
                    }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || duration <= 0) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { onSeek((position - 10_000).coerceAtLeast(0)); true }
                        Key.DirectionRight -> { onSeek((position + 10_000).coerceAtMost(duration)); true }
                        else -> false
                    }
                }
                // 不可聚焦的话遥控器既走不到进度条，左右键快进快退也收不到
                .focusable()
                .pointerInput(duration) {
                    // 宽度每次现取：旋转、分屏改变尺寸时不会重启这个协程
                    detectTapGestures { offset ->
                        if (duration > 0) seek(((offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            if (duration > 0) seek((dragFraction * duration).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        dragFraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = color,
                trackColor = trackColor,
                amplitude = { if (wave) 1f else 0f },
            )
        }
        if (showTimes) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = if (dragging) (dragFraction * duration).toLong() else position
                Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** “3 分 05 秒”这类读法；“03:05”会被读成时刻。 */
private fun spokenTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 60) "${seconds / 60} 分 ${seconds % 60} 秒" else "$seconds 秒"
}

/** 迷你播放器播放键外的一圈波浪进度环。 */
@Composable
fun ProgressRing(fraction: Float, playing: Boolean, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    CircularWavyProgressIndicator(
        progress = { fraction },
        modifier = modifier.size(size),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.18f),
        amplitude = { if (playing && !reduceMotion) 1f else 0f },
    )
}

/** 200ms–5s 的等待用形变 LoadingIndicator；更短不显示，更长换成骨架屏 + 文案。 */
@Composable
fun AppLoading(modifier: Modifier = Modifier, text: String? = null, contained: Boolean = false) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (contained) ContainedLoadingIndicator(Modifier.size(64.dp)) else LoadingIndicator(Modifier.size(56.dp))
        if (text != null) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 骨架块：低对比度的流光。减少动画时静止。 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val shine = MaterialTheme.colorScheme.surfaceContainerHighest
    val offset = if (reduceMotion) 0f else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "shimmer")
        value
    }
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    0f to base,
                    (offset - 0.2f).coerceIn(0f, 1f) to base,
                    offset to shine,
                    (offset + 0.2f).coerceIn(0f, 1f) to base,
                    1f to base,
                ),
            ),
    )
}

/** 歌曲列表的骨架：封面 + 两行文字。 */
@Composable
fun SkeletonRows(count: Int = 6, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(segmentShape(index, count))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Skeleton(Modifier.size(48.dp), RoundedCornerShape(12.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skeleton(Modifier.fillMaxWidth(0.62f).height(14.dp), CircleShape)
                    Skeleton(Modifier.fillMaxWidth(0.38f).height(12.dp), CircleShape)
                }
            }
        }
    }
}
