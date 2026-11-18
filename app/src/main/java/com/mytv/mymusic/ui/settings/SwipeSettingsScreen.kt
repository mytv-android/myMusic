package com.mytv.mymusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.SwipeAction
import com.mytv.mymusic.data.SwipePrefs
import com.mytv.mymusic.data.SwipeSide
import com.mytv.mymusic.data.buttons
import com.mytv.mymusic.data.full
import com.mytv.mymusic.data.moveButton
import com.mytv.mymusic.data.removeButton
import com.mytv.mymusic.data.setButton
import com.mytv.mymusic.data.setFull
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalSheetClose
import com.mytv.mymusic.ui.components.LocalSongSwipeEnv
import com.mytv.mymusic.ui.components.LocalSwipeNotifier
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentedGroup
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.rememberDemoRowSwipe
import com.mytv.mymusic.ui.components.swipeIcon
import com.mytv.mymusic.ui.components.tvFocusRing

/** 动作选择面板改的是哪一格：[index] 为 null 是「拉到头」，等于按钮数是「添加按钮」。 */
private data class SwipeSlot(val side: SwipeSide, val index: Int?)

/** 这一侧在界面上叫什么：从左往右排时起始边的按钮是向右滑露出来的，从右往左排时反过来。 */
internal fun swipeSideTitle(side: SwipeSide, rtl: Boolean): String =
    if ((side == SwipeSide.Start) != rtl) "向右滑" else "向左滑"

/** 设置首页「滑动操作」一行的摘要：只写起始那一侧（向右滑），关着写「已关闭」。 */
internal fun swipeSummary(prefs: SwipePrefs, rtl: Boolean = false): String {
    if (!prefs.enabled) return "已关闭"
    val title = swipeSideTitle(SwipeSide.Start, rtl).removePrefix("向")
    val buttons = prefs.start.joinToString("、") { it.label }.ifBlank { "无按钮" }
    val full = prefs.startFull.takeIf { it != SwipeAction.None }?.let { " · 拉到头 ${it.label}" }.orEmpty()
    return "$title $buttons$full"
}

/**
 * 设置 › 滑动操作：顶部能拖的示例行，下面是两侧各两个按钮（上下箭头换顺序）和「拉到头」。
 * 点任意一格从列表里换动作；即改即存。电视上不列出这一项，深链接进来只给一句说明。
 */
@Composable
internal fun SwipeSettingsScreen(prefs: SwipePrefs, actions: MusicActions, onBack: () -> Unit, embedded: Boolean = false) {
    val window = LocalAppWindow.current
    if (window.isTv) {
        SettingsPage(SettingsSection.Swipe, embedded, onBack) {
            item(key = "tv", contentType = "note") {
                SettingsNote("电视上没有滑动操作：在歌曲上长按确认键或按菜单键打开歌曲操作。")
            }
        }
        return
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val notifier = LocalSwipeNotifier.current
    val screenReader = LocalSongSwipeEnv.current?.screenReader?.value == true
    var picking by remember { mutableStateOf<SwipeSlot?>(null) }
    val enabled = prefs.enabled
    SettingsPage(SettingsSection.Swipe, embedded, onBack, subtitle = "在歌曲列表上左右滑；电视上没有") {
        item(key = "demo", contentType = "demo") {
            Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                SongRow(
                    title = "Porch Light",
                    subtitle = "示例乐队",
                    cover = "",
                    index = 0,
                    count = 1,
                    onClick = {},
                    onLongClick = null,
                    trailing = {
                        Text("23:05", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    swipe = if (enabled) rememberDemoRowSwipe(prefs) { notifier?.show(it, null) } else null,
                )
                SettingsNote(
                    when {
                        !enabled -> "滑动操作已关闭，列表上横滑没有反应。"
                        screenReader -> "读屏开着时用「操作」菜单代替滑动，示例行不能拖。"
                        else -> "↔ 拖一下这一行试试，下面改了它跟着变。"
                    },
                )
            }
        }
        item(key = "switch", contentType = "switch") {
            SwitchRow(
                title = "启用滑动操作",
                checked = enabled,
                onCheckedChange = { on -> actions.setSwipePrefs { it.copy(enabled = on) } },
                index = 0,
                count = 1,
                summary = "多选、编辑歌单、读屏开着时自动停用",
                icon = Icons.Filled.Swipe,
            )
        }
        listOf(SwipeSide.Start, SwipeSide.End).forEach { side ->
            item(key = "side:${side.name}", contentType = "swipeSide") {
                SwipeSideGroup(
                    side = side,
                    prefs = prefs,
                    rtl = rtl,
                    enabled = enabled,
                    onPick = { picking = it },
                    onMove = { index, delta -> actions.setSwipePrefs { it.moveButton(side, index, delta) } },
                )
            }
        }
        item(key = "note", contentType = "note") {
            SettingsNote(
                "「删除」在最近播放里是删记录，在歌单里是移出歌单，在收藏里是取消收藏，在下载里是删文件（会先确认），" +
                    "在播放队列里是移出队列；榜单、搜索、专辑、歌手页没有可删的，不显示这个按钮。",
            )
        }
        item(key = "reset", contentType = "reset") {
            TextButton(
                onClick = {
                    val before = prefs
                    actions.setSwipePrefs { SwipePrefs(enabled = it.enabled) }
                    notifier?.show("已恢复默认的滑动操作") { actions.setSwipePrefs { before } }
                },
                enabled = prefs != SwipePrefs(enabled = prefs.enabled),
                modifier = Modifier.padding(top = 4.dp).tvFocusRing(CircleShape),
            ) { Text("恢复默认") }
        }
    }
    picking?.let { slot ->
        SwipeActionPicker(slot, prefs, rtl, actions, onDismiss = { picking = null })
    }
}

@Composable
private fun SwipeSideGroup(
    side: SwipeSide,
    prefs: SwipePrefs,
    rtl: Boolean,
    enabled: Boolean,
    onPick: (SwipeSlot) -> Unit,
    onMove: (index: Int, delta: Int) -> Unit,
) {
    val buttons = prefs.buttons(side)
    val full = prefs.full(side)
    val canAdd = buttons.size < SwipePrefs.MAX_BUTTONS
    val count = buttons.size + (if (canAdd) 1 else 0) + 1
    Column(Modifier.alpha(if (enabled) 1f else 0.38f)) {
        SectionLabel(swipeSideTitle(side, rtl)) {
            if (buttons.isEmpty() && full == SwipeAction.None) {
                Text("这一侧不能滑", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SegmentedGroup {
            buttons.forEachIndexed { index, action ->
                ListRow(
                    title = action.label,
                    index = index,
                    count = count,
                    onClick = if (enabled) ({ onPick(SwipeSlot(side, index)) }) else null,
                    summary = "露出的第 ${index + 1} 个",
                    leading = { SlotNumber(index + 1) },
                    trailing = {
                        IconButton(onClick = { onMove(index, -1) }, enabled = enabled && index > 0, modifier = Modifier.tvFocusRing(CircleShape)) {
                            Icon(Icons.Filled.KeyboardArrowUp, "上移「${action.label}」")
                        }
                        IconButton(onClick = { onMove(index, 1) }, enabled = enabled && index < buttons.lastIndex, modifier = Modifier.tvFocusRing(CircleShape)) {
                            Icon(Icons.Filled.KeyboardArrowDown, "下移「${action.label}」")
                        }
                    },
                )
            }
            if (canAdd) {
                ListRow(
                    title = "添加按钮",
                    index = buttons.size,
                    count = count,
                    onClick = if (enabled) ({ onPick(SwipeSlot(side, buttons.size)) }) else null,
                    icon = Icons.Filled.Add,
                    summary = if (buttons.isEmpty()) "这一侧还没有按钮" else "最多两个",
                )
            }
            ListRow(
                title = "拉到头",
                index = count - 1,
                count = count,
                onClick = if (enabled) ({ onPick(SwipeSlot(side, null)) }) else null,
                value = full.label,
                summary = if (side == SwipeSide.Start) "拉过行宽 60% 松手，最多 240dp" else "默认不做事，可以换成任一动作",
            )
        }
    }
}

@Composable
private fun SlotNumber(number: Int) {
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * 换一格的动作。按钮位只列能当按钮的动作，同一侧另一个按钮已经用了的照样能选——选了两个对调；
 * 「添加按钮」时同侧已有的不能再加。拉到头多一个「不做事」。选完立即写入，面板关上。
 */
@Composable
private fun SwipeActionPicker(slot: SwipeSlot, prefs: SwipePrefs, rtl: Boolean, actions: MusicActions, onDismiss: () -> Unit) {
    val buttons = prefs.buttons(slot.side)
    val sideTitle = swipeSideTitle(slot.side, rtl)
    val adding = slot.index != null && slot.index >= buttons.size
    val title = when {
        slot.index == null -> "$sideTitle · 拉到头"
        adding -> "$sideTitle · 添加按钮"
        else -> "$sideTitle · 第 ${slot.index + 1} 个按钮"
    }
    val current = when (slot.index) {
        null -> prefs.full(slot.side)
        else -> buttons.getOrNull(slot.index)
    }
    val choices = if (slot.index == null) SwipeAction.entries else SwipeAction.entries.filter { it.button }
    AdaptiveSheet(onDismiss = onDismiss, title = title, subtitle = "选好立即生效") {
        val close = LocalSheetClose.current
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val rows = choices.size + if (slot.index != null && !adding) 1 else 0
            choices.forEachIndexed { index, action ->
                val other = buttons.indexOf(action)
                val summary = when {
                    slot.index == null || action == current || other < 0 -> ""
                    adding -> "已经是第 ${other + 1} 个"
                    else -> "和第 ${other + 1} 个对调"
                }
                ListRow(
                    title = action.label,
                    index = index,
                    count = rows,
                    onClick = if (adding && other >= 0) {
                        null
                    } else {
                        {
                            actions.setSwipePrefs {
                                if (slot.index == null) it.setFull(slot.side, action) else it.setButton(slot.side, slot.index, action)
                            }
                            close()
                        }
                    },
                    icon = if (action == SwipeAction.None) Icons.Filled.RemoveCircleOutline else swipeIcon(action),
                    summary = summary,
                    selected = action == current,
                    showChevron = false,
                )
            }
            if (slot.index != null && !adding) {
                ListRow(
                    title = "移除这个按钮",
                    index = rows - 1,
                    count = rows,
                    onClick = {
                        actions.setSwipePrefs { it.removeButton(slot.side, slot.index) }
                        close()
                    },
                    icon = Icons.Filled.Delete,
                    iconTone = IconTone.Error,
                    danger = true,
                    showChevron = false,
                )
            }
        }
    }
}
