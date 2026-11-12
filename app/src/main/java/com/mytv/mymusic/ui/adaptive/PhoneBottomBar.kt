package com.mytv.mymusic.ui.adaptive

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.MiniPlayerTarget
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.TopTab
import com.mytv.mymusic.ui.openLabel
import com.mytv.mymusic.ui.components.PlayButtonSize
import com.mytv.mymusic.ui.components.PlayPauseButton
import com.mytv.mymusic.ui.components.rememberSettlingOffsetState
import com.mytv.mymusic.ui.components.sharedNowPlayingCover
import com.mytv.mymusic.ui.theme.LocalBlurPolicy
import com.mytv.mymusic.ui.theme.frosted
import kotlin.math.abs
import kotlin.math.roundToInt

private val NavBarHeight = 64.dp
private val MiniHeight = 64.dp
private val MiniGap = 8.dp
/** FloatingToolbar 离屏幕底边和左右边的距离（ContainerExternalPadding）。 */
private val FloatingMargin = 16.dp

/**
 * 浮层总高度（不含系统导航栏），列表底部要让出这么多。
 * 有歌在播时是迷你播放器 + 短导航栏；没歌在播时只有悬浮工具栏，它离底边还有 16dp。
 */
fun phoneOverlayHeight(hasMini: Boolean): Dp =
    if (hasMini) NavBarHeight + MiniHeight + MiniGap * 2 else NavBarHeight + FloatingMargin

/** 手机底部：有歌在播时是悬浮迷你播放器 + 贴底短导航栏；没歌在播时换成悬浮工具栏。 */
@Composable
fun PhoneBottomBar(
    state: MusicUiState,
    position: State<Long>,
    selected: String,
    actions: MusicActions,
    onSelect: (String) -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blur = LocalBlurPolicy.current.enabled
    val container = MaterialTheme.colorScheme.surfaceContainer
    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = state.current != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            MiniPlayer(state, position, actions, onOpenQueue, Modifier.padding(horizontal = 12.dp, vertical = MiniGap))
        }
        if (state.current != null) {
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
        } else {
            // 外层占满整行只为居中，工具栏本身按内容收缩；系统导航栏的高度也要让出来
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = FloatingMargin)
                    .padding(bottom = FloatingMargin),
                contentAlignment = Alignment.Center,
            ) {
                FloatingToolbar(selected = selected, onSelect = onSelect, blur = blur, container = container)
            }
        }
    }
}

/** 槽位固定 56 × 48；工具栏自身只有 64dp 高的下界，子项不能撑满高度。 */
private val SlotWidth = 56.dp
private val SlotHeight = 48.dp

/** 没歌在播时的底栏：M3E 悬浮工具栏，选中项用 secondaryContainer 底，和贴底导航栏的指示器一致。 */
@Composable
private fun FloatingToolbar(
    selected: String,
    onSelect: (String) -> Unit,
    blur: Boolean,
    container: Color,
    modifier: Modifier = Modifier,
) {
    // FloatingToolbarDefaults.ContainerShape 走主题的 extraLarge（28dp），大屏手机上是圆角矩形而不是胶囊；
    // 这里的槽位自己就是 50% 圆角，容器同样按 50% 裁，两端才是半圆
    val shape = RoundedCornerShape(percent = 50)
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier
            // frosted 的模糊层和底色都按矩形画，不裁的话胶囊会变成一个方角灰块
            .clip(shape)
            .then(if (blur) Modifier.frosted(container.copy(alpha = 0.72f), shape) else Modifier),
        shape = shape,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = if (blur) Color.Transparent else container,
        ),
    ) {
        TopTab.entries.forEach { tab ->
            val on = selected == tab.route
            // 定宽定高，不用 fillMaxHeight：工具栏的内容行拿到的是整屏高度上限，撑满会把容器拉到屏幕高
            Column(
                modifier = Modifier
                    .size(SlotWidth, SlotHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .then(if (on) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(tab.route) }
                    .semantics {
                        this.selected = on
                        contentDescription = tab.label
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(if (on) tab.selectedIcon else tab.icon, null, Modifier.size(22.dp))
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 悬浮胶囊迷你播放器。点按按设置打开播放页或海报墙，左右滑切歌，上滑总是展开播放页，长按打开队列；
 * 手势都有对应的按钮或读屏自定义操作，读屏用户不需要手势。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(state: MusicUiState, position: State<Long>, actions: MusicActions, onOpenQueue: () -> Unit, modifier: Modifier = Modifier) {
    val song = state.current ?: return
    val drag = rememberSettlingOffsetState()
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    // 手势协程不随重组重启，阈值和回调都从最新状态读取
    val currentThreshold by rememberUpdatedState(threshold)
    val currentActions by rememberUpdatedState(actions)
    val blur = LocalBlurPolicy.current.enabled
    val container = MaterialTheme.colorScheme.secondaryContainer
    val shape = RoundedCornerShape(20.dp)
    val source = state.currentSource?.providerName ?: song.source
    Row(
        modifier
            .fillMaxWidth()
            .height(MiniHeight)
            .offset { IntOffset(drag.offset.roundToInt(), 0) }
            .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(shape)
            .frosted(if (blur) container.copy(alpha = 0.82f) else container, shape)
            .miniPlayerProgress(position, state.duration, state.current?.isLive == true)
            .combinedClickable(
                onClickLabel = state.miniPlayerTarget.openLabel,
                onLongClickLabel = "打开播放队列",
                onClick = actions::openNowPlaying,
                onLongClick = onOpenQueue,
            )
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag.begin() },
                    onDragEnd = {
                        val value = drag.offset
                        if (abs(value) > currentThreshold) currentActions.transport(if (value < 0) "next" else "previous")
                        drag.settle()
                    },
                    onDragCancel = { drag.settle() },
                ) { change, amount ->
                    change.consume()
                    drag.add(amount * 0.6f)
                }
            }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -currentThreshold / 2) currentActions.openPlayer() },
                ) { change, amount ->
                    change.consume()
                    total += amount
                }
            }
            .semantics {
                contentDescription = "正在播放 ${song.title}，${song.artist}"
                customActions = listOfNotNull(
                    CustomAccessibilityAction("下一首") { actions.transport("next"); true },
                    CustomAccessibilityAction("上一首") { actions.transport("previous"); true },
                    CustomAccessibilityAction("打开播放队列") { onOpenQueue(); true },
                    // 点按去了海报墙时，上滑展开播放页要有对应的读屏操作
                    if (state.miniPlayerTarget == MiniPlayerTarget.PosterWall) {
                        CustomAccessibilityAction("打开播放页") { actions.openPlayer(); true }
                    } else {
                        null
                    },
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
        PlayPauseButton(state.playing, { actions.transport("play") }, size = PlayButtonSize.Mini)
        IconButton(onClick = { actions.transport("next") }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.SkipNext, "下一首", tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** 整条迷你播放器底边的 2dp 进度；只在绘制阶段读进度，不触发其他内容重组。 */
@Composable
private fun Modifier.miniPlayerProgress(position: State<Long>, duration: Long, live: Boolean = false): Modifier {
    val progressColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f)
    return drawBehind {
        val fraction = if (!live && duration > 0) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val lineHeight = 2.dp.toPx()
        val topLeft = Offset(0f, size.height - lineHeight)
        drawRect(trackColor, topLeft = topLeft, size = Size(size.width, lineHeight))
        drawRect(progressColor, topLeft = topLeft, size = Size(size.width * fraction, lineHeight))
    }
}
