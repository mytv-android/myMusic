package com.mytv.mymusic.ui.adaptive

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.TopTab
import com.mytv.mymusic.ui.components.PlayButtonSize
import com.mytv.mymusic.ui.components.PlayPauseButton
import com.mytv.mymusic.ui.components.ProgressRing
import com.mytv.mymusic.ui.components.sharedNowPlayingCover
import com.mytv.mymusic.ui.theme.LocalBlurPolicy
import com.mytv.mymusic.ui.theme.frosted
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val NavBarHeight = 64.dp
private val MiniHeight = 64.dp
private val MiniGap = 8.dp

/** 浮层总高度（不含系统导航栏），列表底部要让出这么多。 */
fun phoneOverlayHeight(hasMini: Boolean): Dp = NavBarHeight + if (hasMini) MiniHeight + MiniGap * 2 else 0.dp

/** 手机底部：悬浮迷你播放器 + 短导航栏，都从内容区取模糊背景。 */
@Composable
fun PhoneBottomBar(
    state: MusicUiState,
    selected: String,
    actions: MusicActions,
    onSelect: (String) -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = state.current != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            MiniPlayer(state, actions, onOpenQueue, Modifier.padding(horizontal = 12.dp, vertical = MiniGap))
        }
        val blur = LocalBlurPolicy.current.enabled
        val container = MaterialTheme.colorScheme.surfaceContainer
        ShortNavigationBar(
            modifier = Modifier.frosted(if (blur) container.copy(alpha = 0.72f) else container),
            containerColor = Color.Transparent,
            windowInsets = WindowInsets.navigationBars,
        ) {
            TopTab.entries.forEach { tab ->
                val on = selected == tab.route
                ShortNavigationBarItem(
                    selected = on,
                    onClick = { onSelect(tab.route) },
                    icon = { Icon(if (on) tab.selectedIcon else tab.icon, null) },
                    label = { Text(tab.label) },
                )
            }
        }
    }
}

/**
 * 悬浮胶囊迷你播放器。左右滑切歌，上滑展开，长按打开队列；
 * 手势都有对应的按钮或读屏自定义操作，读屏用户不需要手势。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(state: MusicUiState, actions: MusicActions, onOpenQueue: () -> Unit, modifier: Modifier = Modifier) {
    val song = state.current ?: return
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val blur = LocalBlurPolicy.current.enabled
    val container = MaterialTheme.colorScheme.secondaryContainer
    val shape = RoundedCornerShape(20.dp)
    val source = state.currentSource?.providerName ?: song.source
    Row(
        modifier
            .fillMaxWidth()
            .height(MiniHeight)
            .offset { IntOffset(drag.value.roundToInt(), 0) }
            .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(shape)
            .frosted(if (blur) container.copy(alpha = 0.82f) else container, shape)
            .combinedClickable(
                onClickLabel = "打开播放页",
                onLongClickLabel = "打开播放队列",
                onClick = actions::openPlayer,
                onLongClick = onOpenQueue,
            )
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val value = drag.value
                        if (abs(value) > threshold) actions.transport(if (value < 0) "next" else "previous")
                        scope.launch { drag.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { drag.snapTo(drag.value + amount * 0.6f) }
                }
            }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -threshold / 2) actions.openPlayer() },
                ) { change, amount ->
                    change.consume()
                    total += amount
                }
            }
            .semantics {
                contentDescription = "正在播放 ${song.title}，${song.artist}"
                customActions = listOf(
                    CustomAccessibilityAction("下一首") { actions.transport("next"); true },
                    CustomAccessibilityAction("上一首") { actions.transport("previous"); true },
                    CustomAccessibilityAction("打开播放队列") { onOpenQueue(); true },
                )
            }
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(song.cover, song.title, 48.dp, Modifier.sharedNowPlayingCover(RoundedCornerShape(12.dp)), corner = 12.dp)
        // 整行的读屏文案已包含歌名和歌手，这里的文字不再单独读
        Column(Modifier.weight(1f).clearAndSetSemantics {}) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${song.artist} · $source",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(contentAlignment = Alignment.Center) {
            val fraction = if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f
            ProgressRing(fraction, state.playing, size = 52.dp)
            PlayPauseButton(state.playing, { actions.transport("play") }, size = PlayButtonSize.Mini)
        }
        IconButton(onClick = { actions.transport("next") }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.SkipNext, "下一首", tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
