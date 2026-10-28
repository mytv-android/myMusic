package com.mytv.mymusic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 歌曲批量选择状态。长按一行或点“多选”进入；进入后行的点击语义换成切换选中，
 * 一首不剩时自动退出。selected 存 song.key。
 */
@Stable
class SelectionState {
    var active: Boolean by mutableStateOf(false)
        private set
    var selected: Set<String> by mutableStateOf(emptySet())
        private set

    /** 进入多选但不预选任何歌，全选按钮可用。 */
    fun start() {
        active = true
        selected = emptySet()
    }

    fun enter(key: String) {
        active = true
        selected = setOf(key)
    }

    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
        if (selected.isEmpty()) active = false
    }

    /** 全选 / 全不选，按当前是否已全选翻转。 */
    fun toggleAll(keys: Collection<String>) {
        selected = if (keys.isNotEmpty() && keys.all { it in selected }) emptySet() else keys.toSet()
    }

    fun exit() {
        active = false
        selected = emptySet()
    }
}

/** 批量操作条：替换列表头部的常规操作行。 */
@Composable
fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onPlay: () -> Unit,
    onAddTo: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 $count",
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onToggleAll, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                Text(if (allSelected) "清除" else "全选")
            }
            onRemove?.let {
                TextButton(
                    onClick = it,
                    enabled = count > 0,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                ) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onPlay, enabled = count > 0, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                Text("播放")
            }
            Button(
                onClick = onAddTo,
                enabled = count > 0,
                modifier = Modifier.tvFocusRing(RoundedCornerShape(24.dp)),
            ) {
                Text("加入歌单")
            }
            IconButton(onExit, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                Icon(Icons.Filled.Close, contentDescription = "退出多选")
            }
        }
    }
}
