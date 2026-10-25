package com.mytv.mymusic.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 宽度动画放在布局阶段读值：按下形变只重新测量，不触发重组。 */
private fun Modifier.animatedWidth(width: State<Dp>): Modifier = layout { measurable, constraints ->
    val w = width.value.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(w, placeable.height) { placeable.place(0, 0) }
}

/** 播放键尺寸。Hero 是播放页的 128×96 宽按钮；播放时圆角变小（方），暂停时是满圆。 */
enum class PlayButtonSize(
    val width: Dp,
    val height: Dp,
    val icon: Dp,
    val playingCorner: Dp,
    val pressedWidth: Dp,
) {
    Mini(34.dp, 34.dp, 20.dp, 10.dp, 30.dp),
    Small(40.dp, 40.dp, 22.dp, 12.dp, 36.dp),
    Medium(56.dp, 56.dp, 28.dp, 16.dp, 50.dp),
    Large(96.dp, 80.dp, 36.dp, 24.dp, 84.dp),
    Hero(128.dp, 96.dp, 44.dp, 28.dp, 112.dp),
}

/**
 * 形变播放键：暂停时满圆，播放时圆角方形，按下时变窄、圆角收到 16dp。
 * 形状只表达状态变化，不承载语义；读屏读“播放 / 暂停”。
 */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: PlayButtonSize = PlayButtonSize.Medium,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val corner = animateDpAsState(
        when {
            pressed -> 16.dp.coerceAtMost(size.height / 2)
            playing -> size.playingCorner
            else -> size.height / 2
        },
        spatial,
        label = "playCorner",
    )
    val width = animateDpAsState(if (pressed) size.pressedWidth else size.width, spatial, label = "playWidth")
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(size.height)
            .animatedWidth(width)
            .graphicsLayer {
                shape = RoundedCornerShape(corner.value)
                clip = true
            }
            .tvFocusRing(RoundedCornerShape(size.playingCorner))
            .semantics {
                role = Role.Button
                contentDescription = if (playing) "暂停" else "播放"
                stateDescription = if (playing) "正在播放" else "已暂停"
            },
        shape = RectangleShape,
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Crossfade(playing, label = "playIcon") { isPlaying ->
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(size.icon),
                )
            }
        }
    }
}

/** 播放页的上一首 / 下一首：半透明圆形，按下时横向拉宽、圆角变小。 */
@Composable
fun MorphIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    iconSize: Dp = 34.dp,
    containerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val width = animateDpAsState(if (pressed) size * 1.28f else size, spatial, label = "skipWidth")
    val corner = animateDpAsState(if (pressed) 16.dp else size / 2, spatial, label = "skipCorner")
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(size)
            .animatedWidth(width)
            .graphicsLayer {
                shape = RoundedCornerShape(corner.value)
                clip = true
            }
            .tvFocusRing(RoundedCornerShape(size / 2))
            .semantics { this.contentDescription = contentDescription },
        shape = RectangleShape,
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(iconSize)) }
    }
}

/**
 * 连接式按钮组：用于互斥选项（主题、对比度、搜索类型、定时时长）。
 * 选中项变成满圆角，其余保持内侧小圆角。
 */
@Composable
fun <T> ConnectedToggleGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            val checked = option == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(option) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .tvFocusRing()
                    .semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                icon?.invoke(option)?.let {
                    Icon(it, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 歌单 / 榜单的主操作：Split button。左侧“播放全部”，右侧展开随机播放、加入队列、存到本地歌单。
 */
@Composable
fun PlayAllSplitButton(
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier,
    onShuffle: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    label: String = "播放全部",
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hasMenu = onShuffle != null || onQueue != null || onSave != null
    SplitButtonLayout(
        modifier = modifier,
        leadingButton = {
            SplitButtonDefaults.LeadingButton(onClick = onPlayAll, modifier = Modifier.tvFocusRing()) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(SplitButtonDefaults.LeadingIconSize))
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        },
        trailingButton = {
            if (hasMenu) {
                Box {
                    SplitButtonDefaults.TrailingButton(
                        checked = expanded,
                        onCheckedChange = { expanded = it },
                        modifier = Modifier
                            .tvFocusRing()
                            // 这是展开菜单的开关，读“已展开 / 已收起”比“已选中”更贴切
                            .semantics {
                                contentDescription = "更多播放方式"
                                stateDescription = if (expanded) "已展开" else "已收起"
                            },
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(SplitButtonDefaults.TrailingIconSize))
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        onShuffle?.let {
                            DropdownMenuItem(
                                text = { Text("随机播放") },
                                leadingIcon = { Icon(Icons.Filled.Shuffle, null) },
                                onClick = { expanded = false; it() },
                            )
                        }
                        onQueue?.let {
                            DropdownMenuItem(
                                text = { Text("加入队列") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                                onClick = { expanded = false; it() },
                            )
                        }
                        onSave?.let {
                            DropdownMenuItem(
                                text = { Text("存到本地歌单") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                onClick = { expanded = false; it() },
                            )
                        }
                    }
                }
            }
        },
    )
}
