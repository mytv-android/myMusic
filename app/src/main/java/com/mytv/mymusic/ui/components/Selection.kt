package com.mytv.mymusic.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException

/**
 * 歌曲批量选择状态。长按一行或点“多选”进入；进入后行的点击语义换成切换选中。
 * 一首不剩时仍停留在多选，只有退出、返回或完成批量操作才离开。selected 存 song.key。
 */
@Stable
class SelectionState {
    var active: Boolean by mutableStateOf(false)
        private set
    var selected: Set<String> by mutableStateOf(emptySet())
        private set

    /** 区间起点和已经完成的终点；只读给行内标记、提示和读屏使用。 */
    var anchor: String? by mutableStateOf<String?>(null)
        private set
    var rangeEnd: String? by mutableStateOf<String?>(null)
        private set

    /** 区间选择模式：第一次点行定起点，第二次点行连选到终点；关掉就是逐首点选。 */
    var rangeMode: Boolean by mutableStateOf(false)
        private set

    /** 预测性返回手势进度，只由 [SelectionPredictiveBack] 写入。 */
    var backProgress: Float by mutableFloatStateOf(0f)
        internal set

    /** 页面级外壳注册使用；选择条滚出 Lazy 列表后这份配置仍由 SelectionState 持有。 */
    internal var chromeContent: SelectionContent? by mutableStateOf(null)

    /** 进入多选但不预选任何歌，全选按钮仍可用。 */
    fun start() {
        active = true
        selected = emptySet()
        anchor = null
        rangeEnd = null
        rangeMode = false
    }

    fun enter(key: String) {
        active = true
        selected = setOf(key)
        anchor = key
        rangeEnd = null
    }

    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
        anchor = null
        rangeEnd = null
    }

    fun toggleRangeMode() {
        rangeMode = !rangeMode
        anchor = null
        rangeEnd = null
    }

    /** 普通模式切换这一首；区间模式每两次点击构成一段，已选结果始终保留列表顺序。 */
    fun tap(key: String, ordered: List<String>) {
        if (!rangeMode) {
            toggle(key)
            return
        }
        val to = ordered.indexOf(key)
        if (to < 0) return
        val from = anchor?.let(ordered::indexOf)?.takeIf { it >= 0 }
        if (from == null || rangeEnd != null) {
            selected = selected + key
            anchor = key
            rangeEnd = null
            return
        }
        if (from == to) {
            selected = selected - key
            anchor = null
            rangeEnd = null
            return
        }
        selected = selected + ordered.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
        rangeEnd = key
    }

    /** 全选 / 全不选当前可见的可选行；部分选择时再点会选满。 */
    fun toggleAll(keys: Collection<String>) {
        selected = if (keys.isNotEmpty() && keys.all { it in selected }) emptySet() else keys.toSet()
        anchor = null
        rangeEnd = null
    }

    fun exit() {
        active = false
        selected = emptySet()
        anchor = null
        rangeEnd = null
        rangeMode = false
        chromeContent = null
    }
}

/** 当前页面发布给外壳的一份多选配置；由页面级的 [SelectionPredictiveBack] 注册与撤回。 */
@Stable
class SelectionContent(
    val state: SelectionState,
    keys: List<String>,
    showDock: Boolean,
    val onPlay: () -> Unit,
    val onPlayNext: () -> Unit,
    val onAddTo: () -> Unit,
    val onExit: () -> Unit,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "移除",
    onUpload: (() -> Unit)? = null,
) {
    var keys: List<String> by mutableStateOf(keys)
        internal set
    var showDock: Boolean by mutableStateOf(showDock)
        internal set
    var onRemove: (() -> Unit)? by mutableStateOf(onRemove)
        internal set
    var removeLabel: String by mutableStateOf(removeLabel)
        internal set
    var onUpload: (() -> Unit)? by mutableStateOf(onUpload)
        internal set

    val count: Int get() = state.selected.size
    val allSelected: Boolean get() = keys.isNotEmpty() && state.selected.containsAll(keys)
}

/** 外壳只读当前页面，不拥有页面里的 SelectionState，避免导航返回后残留上一页的选择。 */
@Stable
class SelectionChrome {
    private var owner: Any? = null
    var current: SelectionContent? by mutableStateOf(null)
        private set

    fun publish(owner: Any, content: SelectionContent) {
        this.owner = owner
        current = content
    }

    fun clear(owner: Any) {
        if (this.owner != owner) return
        this.owner = null
        current = null
    }
}

val LocalSelectionChrome = staticCompositionLocalOf<SelectionChrome?> { null }

/** 多选退出的预测性返回；放在页面里多选界面之后，保证比页面返回先处理。 */
@Composable
fun SelectionPredictiveBack(selection: SelectionState) {
    val chrome = LocalSelectionChrome.current
    val owner = remember { Any() }
    val active = selection.active
    val content = selection.chromeContent
    SideEffect {
        if (active && content != null) chrome?.publish(owner, content) else chrome?.clear(owner)
    }
    DisposableEffect(owner, chrome) { onDispose { chrome?.clear(owner) } }
    PredictiveBackHandler(enabled = active) { events ->
        try {
            events.collect { event -> selection.backProgress = event.progress }
            selection.exit()
        } catch (_: CancellationException) {
        } finally {
            selection.backProgress = 0f
        }
    }
}

/** 多选界面的跟手退出，只在绘制阶段读进度。 */
fun Modifier.selectionBackPreview(selection: SelectionState): Modifier = graphicsLayer {
    val progress = selection.backProgress
    alpha = 1f - progress
    translationY = -8.dp.toPx() * progress
}

/**
 * 多选共享入口。单屏只画数量、逐首/区间和全选，动作交给外壳的悬浮 Dock；
 * 双栏和电视使用固定侧栏，动作也在这张面板里。特殊页面可强制 [showDock] / [sidePanel]。
 */
@Composable
fun SelectionBar(
    selection: SelectionState,
    orderedKeys: List<String>,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddTo: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "移除",
    onUpload: (() -> Unit)? = null,
    showDock: Boolean? = null,
    sidePanel: Boolean? = null,
) {
    val window = LocalAppWindow.current
    val dock = showDock ?: !window.twoPane
    val playState = rememberUpdatedState(onPlay)
    val playNextState = rememberUpdatedState(onPlayNext)
    val addToState = rememberUpdatedState(onAddTo)
    val exitState = rememberUpdatedState(onExit)
    // 同一个页面始终发布同一个对象；选择变化只重组真正的读者，不让整个 App 外壳跟着刷新。
    val content = remember(selection) {
        SelectionContent(
            state = selection,
            keys = orderedKeys,
            showDock = dock,
            onPlay = { playState.value() },
            onPlayNext = { playNextState.value() },
            onAddTo = { addToState.value() },
            onExit = { exitState.value() },
            onRemove = onRemove,
            removeLabel = removeLabel,
            onUpload = onUpload,
        )
    }
    SideEffect {
        content.keys = orderedKeys
        content.showDock = dock
        content.onRemove = onRemove
        content.removeLabel = removeLabel
        content.onUpload = onUpload
        selection.chromeContent = content
    }
    if (sidePanel ?: window.twoPane) SelectionSidePanel(content, modifier)
    else SelectionInlineControls(content, modifier)
}

@Composable
private fun SelectionHeader(content: SelectionContent) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(content.onExit, modifier = Modifier.tvFocusRing(CircleShape)) {
            Icon(Icons.Filled.Close, contentDescription = "退出多选")
        }
        Column(Modifier.padding(start = 4.dp, end = 8.dp)) {
            Text(
                if (content.count > 0) "已选 ${content.count} 首" else "选择歌曲",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "当前列表 ${content.keys.size} 首",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectionInlineControls(content: SelectionContent, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
            SelectionHeader(content)
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectionModeToggle(content, Modifier.weight(1f))
                SelectAllButton(content)
            }
            SelectionRangeGuide(content)
        }
    }
}

@Composable
private fun SelectionSidePanel(content: SelectionContent, modifier: Modifier) {
    val tv = LocalAppWindow.current.isTv
    val allFocus = remember { FocusRequester() }
    LaunchedEffect(content.state.active) {
        // 从顶栏“多选”进入时没有歌曲行可接住焦点；长按进入已选一首，焦点继续留在该行。
        if (tv && content.state.active && content.count == 0) allFocus.requestFocus()
    }
    Surface(
        modifier = modifier.widthIn(max = 360.dp).fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            SelectionHeader(content)
            SelectionModeToggle(content, Modifier.fillMaxWidth())
            SelectAllButton(content, Modifier.fillMaxWidth().then(if (tv) Modifier.focusRequester(allFocus) else Modifier))
            SelectionRangeGuide(content)
            SelectionSideActions(content)
        }
    }
}

@Composable
private fun SelectionModeToggle(content: SelectionContent, modifier: Modifier) {
    ConnectedToggleGroup(
        options = listOf(false, true),
        selected = content.state.rangeMode,
        onSelect = { if (it != content.state.rangeMode) content.state.toggleRangeMode() },
        label = { if (it) "区间" else "逐首" },
        modifier = modifier,
        compact = true,
    )
}

@Composable
private fun SelectAllButton(content: SelectionContent, modifier: Modifier = Modifier) {
    TextButton(
        onClick = { content.state.toggleAll(content.keys) },
        enabled = content.keys.isNotEmpty(),
        modifier = modifier.tvFocusRing(RoundedCornerShape(20.dp)),
    ) {
        Icon(
            when {
                content.allSelected -> Icons.Filled.CheckCircle
                content.count > 0 -> Icons.Filled.RemoveCircleOutline
                else -> Icons.Filled.RadioButtonUnchecked
            },
            null,
            Modifier.size(20.dp),
            tint = if (content.count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(if (content.allSelected) "全不选" else "全选", maxLines = 1)
    }
}

@Composable
private fun SelectionRangeGuide(content: SelectionContent) {
    if (!content.state.rangeMode) return
    val from = content.state.anchor?.let(content.keys::indexOf) ?: -1
    val to = content.state.rangeEnd?.let(content.keys::indexOf) ?: -1
    val text = when {
        from >= 0 && to >= 0 -> "第 ${minOf(from, to) + 1}–${maxOf(from, to) + 1} 首已选 · 再点一首开始新区间"
        from >= 0 -> "起点：第 ${from + 1} 首 · 再点一首完成连选"
        else -> "先点一首作为起点"
    }
    val announcement = if (from >= 0 && to >= 0) {
        "已选择第 ${minOf(from, to) + 1} 至第 ${maxOf(from, to) + 1} 首，共 ${maxOf(from, to) - minOf(from, to) + 1} 首"
    } else {
        text
    }
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .clearAndSetSemantics {
                contentDescription = announcement
                liveRegion = LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LinearScale, null, Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

private data class SelectionAction(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val primary: Boolean = false,
    val danger: Boolean = false,
)

private fun SelectionContent.actions(): List<SelectionAction> {
    val result = mutableListOf(
        SelectionAction("播放", "播放选中的歌曲", Icons.Filled.PlayArrow, { onPlay(); state.exit() }, primary = true),
        SelectionAction("下一首", "下一首播放选中的歌曲", Icons.AutoMirrored.Filled.QueueMusic, { onPlayNext(); state.exit() }),
        SelectionAction("加入歌单", "把选中的歌曲加入歌单", Icons.AutoMirrored.Filled.PlaylistAdd, onAddTo),
    )
    onRemove?.let {
        result += SelectionAction(
            removeLabel,
            "${removeLabel}选中的歌曲",
            if (removeLabel == "取消收藏") Icons.Filled.Favorite else Icons.Filled.DeleteOutline,
            it,
            danger = true,
        )
    } ?: onUpload?.let {
        result += SelectionAction("上传", "上传选中的本地歌曲", Icons.Filled.Upload, it)
    }
    return result
}

/** 单屏悬浮多选菜单：占用 MiniPlayer 的槽位，原 ShortNavigationBar 仍由外壳保留。 */
@Composable
fun SelectionActionDock(content: SelectionContent, modifier: Modifier = Modifier) {
    val actions = content.actions()
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).selectionBackPreview(content.state),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                val enabled = content.count > 0
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .alpha(if (enabled) 1f else 0.38f)
                        .clickable(
                            enabled = enabled,
                            role = Role.Button,
                            onClickLabel = action.description,
                            onClick = action.onClick,
                        )
                        .padding(horizontal = 4.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        action.icon,
                        null,
                        Modifier.size(22.dp),
                        tint = when {
                            action.danger -> MaterialTheme.colorScheme.error
                            action.primary -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            action.danger -> MaterialTheme.colorScheme.error
                            action.primary -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionSideActions(content: SelectionContent) {
    val actions = content.actions()
    val enabled = content.count > 0
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = actions[0].onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(54.dp).tvFocusRing(RoundedCornerShape(24.dp)),
        ) {
            Icon(actions[0].icon, null)
            Spacer(Modifier.width(8.dp))
            Text(actions[0].label)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.drop(1).take(2).forEach { action ->
                FilledTonalButton(
                    onClick = action.onClick,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(48.dp).tvFocusRing(RoundedCornerShape(20.dp)),
                ) {
                    Icon(action.icon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        actions.getOrNull(3)?.let { action ->
            TextButton(
                onClick = action.onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(48.dp).tvFocusRing(RoundedCornerShape(20.dp)),
            ) {
                Icon(action.icon, null, Modifier.size(18.dp), tint = if (action.danger) MaterialTheme.colorScheme.error else Color.Unspecified)
                Spacer(Modifier.width(6.dp))
                Text(action.label, color = if (action.danger) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        }
    }
}

/** SongRow 多选时的统一行尾：起终点有文字，未选勾圈不提前染主色。 */
@Composable
fun SelectionRowTrailing(selection: SelectionState, key: String) {
    val selected = key in selection.selected
    Row(verticalAlignment = Alignment.CenterVertically) {
        val endpoint = when (key) {
            selection.anchor -> "起点"
            selection.rangeEnd -> "终点"
            else -> null
        }
        if (selection.rangeMode && endpoint != null) {
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(endpoint, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 列表右上角的“更多”展开：和长按一行进入多选等价。 */
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
