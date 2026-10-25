package com.mytv.mymusic.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.TopTab

private val Collapsed = 88.dp
private val Expanded = 240.dp

/**
 * 电视导航抽屉：平时只显示图标；焦点进入后展开显示文字，并压暗右侧内容。展开是浮在内容上的，内容不重新布局。
 * 顺序按遥控器习惯把搜索放在最上面。
 */
@Composable
fun TvNavRail(
    selected: String,
    current: Song?,
    onSelect: (String) -> Unit,
    onSettings: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val width = animateDpAsState(if (expanded) Expanded else Collapsed, MaterialTheme.motionScheme.fastSpatialSpec(), label = "tvRail")
    val order = listOf(TopTab.Search, TopTab.Charts, TopTab.Playlists, TopTab.Library)
    // 从内容区按左键进入抽屉时，焦点落在当前入口上，而不是几何上最近的一项
    val tabFocus = remember { order.associateWith { FocusRequester() } }
    val settingsFocus = remember { FocusRequester() }
    val selectedFocus = order.find { it.route == selected }?.let { tabFocus[it] } ?: if (selected == "settings") settingsFocus else null
    Box(Modifier.fillMaxHeight().width(Collapsed).zIndex(2f)) {
        Row(Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start, unbounded = true)) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .animatedWidth(width)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .onFocusChanged { expanded = it.hasFocus }
                    .focusProperties { onEnter = { selectedFocus?.requestFocus() } }
                    .focusGroup()
                    .padding(horizontal = 12.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (current != null) {
                    TvRailItem(
                        label = "正在播放",
                        detail = current.title,
                        selected = false,
                        expanded = expanded,
                        onClick = onOpenPlayer,
                        leading = { Cover(current.cover, current.title, 32.dp, corner = 8.dp) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                order.forEach { tab ->
                    val on = selected == tab.route
                    TvRailItem(tab.label, "", on, expanded, { onSelect(tab.route) }, icon = if (on) tab.selectedIcon else tab.icon, modifier = Modifier.focusRequester(tabFocus.getValue(tab)))
                }
                Spacer(Modifier.weight(1f))
                val on = selected == "settings"
                TvRailItem("设置", "", on, expanded, onSettings, icon = if (on) Icons.Filled.Settings else Icons.Outlined.Settings, modifier = Modifier.focusRequester(settingsFocus))
            }
            AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(480.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f), MaterialTheme.colorScheme.scrim.copy(alpha = 0f)),
                            ),
                        ),
                )
            }
        }
    }
}

/** 宽度动画在布局阶段读值，展开/收起时只重新测量，不触发重组。 */
private fun Modifier.animatedWidth(width: State<Dp>): Modifier = layout { measurable, constraints ->
    val w = width.value.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(w, placeable.height) { placeable.place(0, 0) }
}

@Composable
private fun TvRailItem(
    label: String,
    detail: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val container = when {
        focused -> MaterialTheme.colorScheme.onSurface
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val content = when {
        focused -> MaterialTheme.colorScheme.surface
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        // 当前入口只靠底色区分，读屏要读出“已选择”
        modifier = modifier.fillMaxWidth().height(56.dp).semantics {
            if (selected) this.selected = true
            // 收起时只有封面（没有图标描述），要给一个名字
            if (!expanded && icon == null) contentDescription = listOf(label, detail).filter { it.isNotBlank() }.joinToString("，")
        },
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                leading != null -> leading()
                icon != null -> Icon(icon, if (expanded) null else label, Modifier.size(28.dp))
            }
            if (expanded) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    if (detail.isNotBlank()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
