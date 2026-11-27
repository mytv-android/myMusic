package com.mytv.mymusic.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException

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

    /** 区间选择的起点：进入多选或切换模式后第一次点行时记下。 */
    private var anchor: String? = null

    /** 区间选择模式：点一行从 [anchor] 一直选到这行；关掉就是逐首点选。 */
    var rangeMode: Boolean by mutableStateOf(false)
        private set

    /**
     * 预测性返回的手势进度（0..1）。[SelectionPredictiveBack] 写，多选操作条和行内标记据此做跟手预览；
     * 只在手势期间非零，松手或取消后归零。
     */
    var backProgress: Float by mutableFloatStateOf(0f)
        internal set

    /** 进入多选但不预选任何歌，全选按钮可用。 */
    fun start() {
        active = true
        selected = emptySet()
        anchor = null
        rangeMode = false
    }

    fun enter(key: String) {
        active = true
        selected = setOf(key)
        anchor = key
    }

    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
        if (selected.isEmpty()) active = false else if (anchor == null) anchor = key
    }

    fun toggleRangeMode() {
        rangeMode = !rangeMode
        anchor = null
    }

    /** 行点击：普通模式切换这一首；区间模式从锚点连选到这一首（按列表顺序）。 */
    fun tap(key: String, ordered: List<String>) {
        if (!rangeMode) {
            toggle(key)
            return
        }
        val from = anchor?.let(ordered::indexOf) ?: -1
        val to = ordered.indexOf(key)
        if (from < 0 || to < 0) {
            // 还没有起点（或起点被筛掉了）：这一下定起点，并保持已选中的
            selected = selected + key
            anchor = key
            return
        }
        val range = ordered.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
        selected = selected + range
        anchor = key
    }

    /** 全选 / 全不选，按当前是否已全选翻转。 */
    fun toggleAll(keys: Collection<String>) {
        selected = if (keys.isNotEmpty() && keys.all { it in selected }) emptySet() else keys.toSet()
        anchor = null
    }

    fun exit() {
        active = false
        selected = emptySet()
        anchor = null
        rangeMode = false
    }
}

/**
 * 多选退出的预测性返回：手势过程中把进度写进 [SelectionState.backProgress]，
 * 松手才退出多选，取消时进度归零。放在页面里多选界面之后（注册顺序晚于编辑、菜单等返回）。
 */
@Composable
fun SelectionPredictiveBack(selection: SelectionState) {
    PredictiveBackHandler(enabled = selection.active) { events ->
        try {
            events.collect { event -> selection.backProgress = event.progress }
            selection.exit()
        } catch (_: CancellationException) {
        } finally {
            selection.backProgress = 0f
        }
    }
}

/**
 * 多选操作条的跟手退出：进度不为零时淡出并上移，预览退出多选后的页面。
 * 只读 [SelectionState.backProgress]，状态变化只重绘图层，不触发重组。
 */
fun Modifier.selectionBackPreview(selection: SelectionState): Modifier = graphicsLayer {
    val progress = selection.backProgress
    alpha = 1f - progress
    translationY = -8.dp.toPx() * progress
}

/** 批量操作条：替换列表头部的常规操作行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddTo: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "移除",
    /** 本地音乐页多选时的“上传”（打开上传面板）；其它页面为空。 */
    onUpload: (() -> Unit)? = null,
    /** 非空时在操作条上给一个“区间”开关：打开后点行从起点连选到这一首。 */
    onRangeModeToggle: (() -> Unit)? = null,
    rangeMode: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onExit, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                    Icon(Icons.Filled.Close, contentDescription = "退出多选")
                }
                Text(
                    "已选 $count",
                    Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                // 电视和平板左栏只有 300dp 左右：这组操作整块换行，别把「下一首播放」拆成两行字
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    onRangeModeToggle?.let {
                        TextButton(
                            onClick = it,
                            modifier = Modifier
                                .tvFocusRing(RoundedCornerShape(20.dp))
                                .semantics { stateDescription = if (rangeMode) "已开启" else "已关闭" },
                        ) {
                            Text("区间", color = if (rangeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    TextButton(onToggleAll, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                        Text(if (allSelected) "清除" else "全选")
                    }
                    TextButton(onPlay, enabled = count > 0, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                        Text("播放")
                    }
                    TextButton(
                        onClick = { onPlayNext(); onExit() },
                        enabled = count > 0,
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                    ) {
                        Text("下一首播放", maxLines = 1)
                    }
                }
            }
            // 第二行：次要操作靠左，主操作占满剩余宽度，避免单按钮悬在一角显得对不齐
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    onRemove?.let {
                        TextButton(
                            onClick = it,
                            enabled = count > 0,
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                        ) {
                            Text(removeLabel, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    onUpload?.let {
                        TextButton(it, enabled = count > 0, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                            Text("上传")
                        }
                    }
                }
                Button(
                    onClick = onAddTo,
                    enabled = count > 0,
                    modifier = Modifier.weight(1f).tvFocusRing(RoundedCornerShape(24.dp)),
                ) {
                    Text("加入歌单")
                }
            }
        }
    }
}

/**
 * 列表右上角的“更多”展开：目前只放“多选”，和长按一行进入多选等价。
 * 喜欢 / 最近播放 / 下载这类自带列表的页面用它进入批量选择。
 */
@Composable
fun SelectionMenu(onSelect: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.tvFocusRing(CircleShape)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            DropdownMenuItem(
                text = { Text("多选") },
                leadingIcon = { Icon(Icons.Filled.Checklist, null) },
                onClick = {
                    open = false
                    onSelect()
                },
            )
        }
    }
}
