package com.mytv.mymusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.ui.providerName

/**
 * 歌单分组 tab：全部 / 本地 / 各绑定平台。歌单库和「加到歌单」面板共用同一套外观，
 * 免得同一个"分组"在两个地方长得不一样。
 *
 * [groups] 是分组 id（空串 = 本地），是否包含本地组由调用方决定；平台之间按名称排。
 * [selected] 为 null 表示「全部」；点已选中的 tab 不重复回调 [onSelect]，而是走 [onConfirm]
 * （电视上用确认键从 tab 进入下面的列表）。
 */
@Composable
fun PlaylistGroupTabs(
    groups: List<String>,
    selected: String?,
    providers: List<ProviderInfo>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onConfirm: (() -> Unit)? = null,
) {
    val sources: List<String?> = listOf(null) +
        (if ("" in groups) listOf("") else emptyList()) +
        groups.filter { it.isNotBlank() }.sortedBy { providerName(providers, it) }
    // 选中的分组已经不在（歌单解绑、被删）时按「全部」显示，别让 Tab 行越界
    val active = selected?.takeIf { it in groups }
    val container = MaterialTheme.colorScheme.surface
    val selectedIndex = sources.indexOf(active).coerceAtLeast(0)
    SecondaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        // 背景铺满整行、传入的 modifier（页边距等）在背景里面，和歌单库原来的样子一致
        modifier = Modifier.fillMaxWidth().background(container).then(modifier),
        edgePadding = 0.dp,
        minTabWidth = 64.dp,
        containerColor = container,
    ) {
        sources.forEach { source ->
            val isSelected = source == active
            val label = when (source) {
                null -> "全部"
                "" -> "本地"
                else -> providerName(providers, source)
            }
            Tab(
                selected = isSelected,
                onClick = { if (isSelected) onConfirm?.invoke() else onSelect(source) },
                modifier = Modifier.tvFocusRing(scale = 1f).then(
                    if (isSelected) {
                        Modifier
                            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                            .then(if (onFocusChange != null) Modifier.onFocusChanged { onFocusChange(it.isFocused) } else Modifier)
                    } else {
                        Modifier
                    },
                ),
            ) {
                Row(
                    Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (source == null) {
                        Icon(Icons.Outlined.Apps, null, Modifier.size(18.dp))
                    } else {
                        ProviderIcon(id = source.ifBlank { "local" }, name = label, size = 20.dp, active = isSelected)
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
