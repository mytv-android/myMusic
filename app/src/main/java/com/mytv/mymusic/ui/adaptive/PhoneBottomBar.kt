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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
/** 没歌在播时的浮动导航条离屏幕底边（手势条上沿）和左右边的距离。 */
private val FloatingMargin = 16.dp

/**
 * 浮层总高度（不含系统导航栏），列表底部要让出这么多。
 * 有歌在播时是迷你播放器 + 短导航栏；没歌在播时只有浮动导航条，它离底边还有 16dp。
 */
fun phoneOverlayHeight(hasMini: Boolean): Dp =
    if (hasMini) NavBarHeight + MiniHeight + MiniGap * 2 else NavBarHeight + FloatingMargin

/** 手机底部：有歌在播时是悬浮迷你播放器 + 贴底短导航栏；没歌在播时换成浮动导航条（[FloatingNavBar]）。 */
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
            // 外层占满整行只为居中，导航条本身按内容收缩；系统导航栏的高度也要让出来
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = FloatingMargin)
                    .padding(bottom = FloatingMargin),
                contentAlignment = Alignment.Center,
            ) {
                FloatingNavBar(selected = selected, onSelect = onSelect, blur = blur, container = container)
            }
        }
    }
}

/** 浮动导航条一项的宽：4 项加左右各 8dp ≈ 304dp。 */
private val PillItemWidth = 72.dp
private val PillPadding = 8.dp

/**
 * 没歌在播时的底栏：浮动导航条（设计稿 09 节，选定 A）。项直接用贴底那条的 [ShortNavigationBarItem]：
 * 24dp 图标放在 56×32 的指示器里、下面 12sp 字，选中是实心图标加 secondaryContainer 指示器，水波纹只铺在指示器上，
 * 读屏是带选中态的 Tab。开始放歌换成贴底导航栏时只是容器变了，图标和字不跳。
 * 容器 64dp 起、全圆角，每项 72dp、整条居中；surfaceContainer 88%（开模糊是磨砂，关模糊是实色），
 * 阴影 Level 2，浅色主题再加一圈 1dp outlineVariant 40% 描边——压在白列表上也看得出是浮着的；深色只有阴影。
 * 大字号时跟着长高，不裁字。
 */
@Composable
private fun FloatingNavBar(
    selected: String,
    onSelect: (String) -> Unit,
    blur: Boolean,
    container: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    // 跟着外壳配色走（详情页海报视图会换成深色），不看系统深浅色
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    Row(
        modifier
            // M3 Level 2 = 3dp
            .shadow(3.dp, shape)
            .clip(shape)
            // frosted 的模糊层按矩形画，前面的 clip 把它裁成胶囊
            .then(if (blur) Modifier.frosted(container.copy(alpha = 0.88f), shape) else Modifier.background(container, shape))
            .then(if (light) Modifier.border(1.dp, outline, shape) else Modifier)
            .heightIn(min = NavBarHeight)
            .padding(horizontal = PillPadding)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopTab.entries.forEach { tab ->
            val on = selected == tab.route
            ShortNavigationBarItem(
                selected = on,
                onClick = { onSelect(tab.route) },
                icon = { Icon(if (on) tab.selectedIcon else tab.icon, null) },
                label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.width(PillItemWidth),
            )
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
