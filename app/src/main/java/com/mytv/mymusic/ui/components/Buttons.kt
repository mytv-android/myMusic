package com.mytv.mymusic.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 固定圆形播放键的可见尺寸；不足 48dp 时由外层透明 Surface 补足点击区。 */
enum class PlayButtonSize(val diameter: Dp, val icon: Dp) {
    Mini(40.dp, 22.dp),
    Small(44.dp, 24.dp),
    Medium(56.dp, 28.dp),
    ;

    val touchTarget: Dp get() = maxOf(48.dp, diameter)
}

/** 播放 / 暂停只切换图标，轮廓始终为圆；按下时轻微缩放，不改变布局尺寸。 */
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
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "playScale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size.touchTarget)
            .tvFocusRing(CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = if (playing) "暂停" else "播放"
                stateDescription = if (playing) "正在播放" else "已暂停"
            },
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = contentColor,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(size.diameter)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        shape = CircleShape
                        clip = true
                    }
                    .background(containerColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
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
