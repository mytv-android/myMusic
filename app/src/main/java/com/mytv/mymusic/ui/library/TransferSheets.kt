package com.mytv.mymusic.ui.library

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.transfer.ExportList
import com.mytv.mymusic.data.transfer.ExportPreview
import com.mytv.mymusic.data.transfer.ImportChoice
import com.mytv.mymusic.data.transfer.ImportPlan
import com.mytv.mymusic.data.transfer.ImportTarget
import com.mytv.mymusic.data.transfer.ImportedList
import com.mytv.mymusic.data.transfer.LxFixed
import com.mytv.mymusic.data.transfer.PLAYLIST_FILE_MIME
import com.mytv.mymusic.data.transfer.importSkippedText
import com.mytv.mymusic.data.transfer.applySameNameToggle
import com.mytv.mymusic.data.transfer.defaultChoices
import com.mytv.mymusic.data.transfer.selectedCount
import com.mytv.mymusic.data.transfer.targetLabel
import com.mytv.mymusic.data.transfer.targetsFor
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.TransferUi
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.AppLoading
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalSheetClose
import com.mytv.mymusic.ui.components.ProviderIcon
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.providerName

// ======================= 导出歌单（设计稿 06 节 ⑪ ⑫） =======================

/**
 * 导出歌单面板。[ids] 为 null 时先选歌单（同步页「导出歌单…」多选，可以勾上我的收藏），选好再到确认这一步；
 * 从歌单 ⋮ 进来直接是确认这一步。只出 myMusic 歌单文件一种格式，没有格式选择。
 */
@Composable
internal fun ExportSheet(ids: List<String>?, actions: MusicActions, onDismiss: () -> Unit) {
    val fromPicker = ids == null
    var picked by rememberSaveable { mutableStateOf(ids) }
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val current = picked
    val preview by produceState<ExportPreview?>(null, current) {
        value = null
        if (current != null) value = actions.exportPreview(current)
    }
    val loaded = preview?.takeIf { current != null }
    AdaptiveSheet(
        onDismiss = onDismiss,
        title = when {
            current == null -> "导出歌单"
            loaded == null -> "导出歌单"
            loaded.lists.size == 1 -> "导出「${loaded.title}」"
            else -> "导出 ${loaded.lists.size} 个歌单"
        },
        subtitle = if (current == null) "挑几个歌单导出成一个文件，可以发给别人" else "myMusic 歌单文件 · 不包含平台账号和 Cookie",
    ) {
        if (current == null) {
            ExportPickStep(
                actions = actions,
                selection = selection,
                onSelection = { selection = it },
                onNext = { picked = it },
            )
        } else {
            // 多选进来的：返回回到第 1 步，选好的还勾着
            BackHandler(enabled = fromPicker) { picked = null }
            ExportConfirmStep(
                preview = loaded,
                actions = actions,
                onBack = if (fromPicker) ({ picked = null }) else null,
            )
        }
    }
}

/** 第 1 步：我的收藏 + 没删的自建 / 绑定歌单，勾选后「下一步」。 */
@Composable
private fun ColumnScope.ExportPickStep(
    actions: MusicActions,
    selection: List<String>,
    onSelection: (List<String>) -> Unit,
    onNext: (List<String>) -> Unit,
) {
    val playlists by actions.localPlaylists.collectAsState()
    val favorites by actions.favorites.collectAsState()
    val tv = LocalAppWindow.current.isTv
    val rows = remember(playlists, favorites.size) {
        listOf(PickRow(ExportList.FAVORITES_ID, ExportList.FAVORITES_NAME, favorites.size, "", favorite = true)) +
            playlists.map { PickRow(it.id, it.name, it.trackCount, if (it.bound) it.linkProvider else "") }
    }
    val chosen = selection.toSet()
    val allIds = rows.map { it.id }
    val listState = rememberLazyListState()
    val firstRowFocus = Modifier.sheetDefaultFocus(tv)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "已选 ${chosen.size} 个",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val all = chosen.containsAll(allIds)
        TextButton(
            onClick = { onSelection(if (all) emptyList() else allIds) },
            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
        ) { Text(if (all) "全不选" else "全选") }
    }
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.id }, contentType = { _, _ -> "exportPick" }) { index, row ->
            val checked = row.id in chosen
            ListRow(
                title = row.name,
                index = index,
                count = rows.size,
                onClick = { onSelection(if (checked) selection - row.id else selection + row.id) },
                modifier = Modifier
                    .then(if (index == 0) firstRowFocus else Modifier)
                    .semantics {
                        role = Role.Checkbox
                        toggleableState = ToggleableState(checked)
                    },
                icon = if (row.favorite) Icons.Filled.Favorite else Icons.AutoMirrored.Filled.QueueMusic,
                summary = listOfNotNull(
                    "${row.count} 首",
                    row.linkProvider.takeIf { it.isNotBlank() }?.let { "绑定${shortPlatform(it)}" },
                ).joinToString(" · "),
                showChevron = false,
                trailing = {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                },
            )
        }
    }
    Button(
        onClick = { onNext(allIds.filter { it in chosen }) },
        enabled = chosen.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp)
            .heightIn(min = 52.dp)
            .tvFocusRing(RoundedCornerShape(26.dp)),
    ) { Text(if (chosen.isEmpty()) "下一步" else "下一步 · 已选 ${chosen.size} 个") }
}

private data class PickRow(val id: String, val name: String, val count: Int, val linkProvider: String, val favorite: Boolean = false)

/** 第 2 步：照稿子 renderK——说明、会导出几首、各平台多少首、分享 / 保存到文件、文件名。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ExportConfirmStep(preview: ExportPreview?, actions: MusicActions, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val tv = LocalAppWindow.current.isTv
    val close = LocalSheetClose.current
    // 电视多半没有能接收文件的应用，没有就不出「分享」
    val canShare = remember(context) {
        Intent(Intent.ACTION_SEND).setType(PLAYLIST_FILE_MIME).resolveActivity(context.packageManager) != null
    }
    var noPicker by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PLAYLIST_FILE_MIME)) { uri ->
        if (uri != null && preview != null) {
            actions.saveExport(preview, uri)
            close()
        }
    }
    val saveFocus = Modifier.sheetDefaultFocus(tv && preview != null, preview != null)
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        if (onBack != null) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.padding(bottom = 4.dp).tvFocusRing(RoundedCornerShape(20.dp)),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新选择歌单")
            }
        }
        if (preview == null) {
            AppLoading(text = "正在读取歌单…")
            return@Column
        }
        val counts = preview.counts
        val empty = counts.kept == 0
        Text(
            if (empty) "歌单里还没有歌" else "发给另一台装了 myMusic 的设备，用「我的 › 新建 › 从文件导入」打开，所有平台都能完整还原。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionLabel("会导出", trailing = {
            Text("${counts.kept} 首", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        })
        if (!empty) {
            val ordered = counts.byProvider.entries.sortedByDescending { it.value }
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    // 读屏把整组读成一句
                    .clearAndSetSemantics {
                        contentDescription = ordered.joinToString("，") { (provider, n) -> "${platformLabel(provider)} $n 首" }
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ordered.forEach { (provider, n) -> PlatformChip(provider, n) }
            }
        }
        if (counts.local > 0) {
            Text(
                "本地 ${counts.local} 首按歌名歌手对，对方设备上有同一首才能放。",
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (counts.total > counts.kept) {
            Text(
                "${counts.total - counts.kept} 首重复或存档坏了，不写进文件。",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canShare) {
                FilledTonalButton(
                    onClick = {
                        actions.shareExport(preview)
                        close()
                    },
                    enabled = !empty,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
                ) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("分享")
                }
            }
            Button(
                onClick = {
                    try {
                        saveLauncher.launch(preview.fileName)
                    } catch (_: ActivityNotFoundException) {
                        noPicker = true
                    }
                },
                enabled = !empty,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).then(saveFocus).tvFocusRing(RoundedCornerShape(24.dp)),
            ) {
                Icon(Icons.Filled.SaveAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("保存到文件")
            }
        }
        if (noPicker) {
            Text(
                "这台设备没有系统文件选择器，无法选择保存位置。",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "文件名：${preview.fileName}",
            Modifier.fillMaxWidth().padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ======================= 从文件导入：预览（设计稿 06 节 ⑬） =======================

/**
 * 导入预览：外壳按 [transfer] 挂，从哪一页导入都盖在上面（电视扫码收到的文件也直接弹）。
 * 读文件、本地匹配、咪咕换取时是同一个面板显示进度；关掉就是放弃这次导入。
 */
@Composable
internal fun ImportPreviewSheet(transfer: TransferUi, actions: MusicActions) {
    val plan = when (transfer) {
        is TransferUi.Preview -> transfer.plan
        is TransferUi.Applying -> transfer.plan
        else -> null
    }
    val reading = transfer as? TransferUi.Reading
    val subtitle = when {
        plan != null -> listOf(
            (if (plan.fromPhone) "手机传来 " else "") + plan.fileName,
            plan.sourceLabel,
            "${plan.lists.size} 个歌单",
        ).joinToString(" · ")
        reading != null -> (if (reading.fromPhone) "手机传来 " else "") + reading.fileName
        else -> ""
    }
    AdaptiveSheet(onDismiss = actions::dismissImport, title = "从文件导入", subtitle = subtitle) {
        if (plan == null) {
            val tv = LocalAppWindow.current.isTv
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppLoading(text = reading?.step ?: "正在读取…")
                TextButton(
                    onClick = actions::dismissImport,
                    modifier = Modifier.sheetDefaultFocus(tv).tvFocusRing(RoundedCornerShape(20.dp)),
                ) { Text("取消") }
            }
        } else {
            ImportPreviewBody(plan, applying = transfer is TransferUi.Applying, actions = actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ImportPreviewBody(plan: ImportPlan, applying: Boolean, actions: MusicActions) {
    val tv = LocalAppWindow.current.isTv
    val hasSameName = plan.lists.any { plan.sameName[it.sourceId]?.bound == false && it.fixed != LxFixed.Love }
    var merge by remember(plan) { mutableStateOf(true) }
    var overridden by remember(plan) { mutableStateOf(emptySet<String>()) }
    var choices by remember(plan) { mutableStateOf(defaultChoices(plan, mergeSameName = true)) }
    val (listCount, songCount) = selectedCount(plan, choices)
    // 第一行能勾的给默认焦点
    val firstEnabled = plan.lists.indexOfFirst { it.importable > 0 }.coerceAtLeast(0)
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        val note = buildString {
            append(if (plan.lx) "认出是洛雪导出的歌单文件。" else "认出是 myMusic 歌单文件。")
            append("勾选要导入的；导入后是普通自建歌单，会跟着同步。")
            if (plan.legacy) append("旧版格式，已按洛雪的规则转换。")
            if (plan.newerVersion) append("文件来自更新的版本，部分信息可能丢失。")
        }
        Text(
            note,
            Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            plan.lists.forEachIndexed { index, list ->
                val choice = choices[list.sourceId] ?: ImportChoice(false, ImportTarget.New)
                ImportListRow(
                    plan = plan,
                    list = list,
                    choice = choice,
                    index = index,
                    count = plan.lists.size,
                    enabled = !applying,
                    modifier = if (index == firstEnabled) Modifier.sheetDefaultFocus(tv) else Modifier,
                    onToggle = { choices = choices + (list.sourceId to choice.copy(include = !choice.include)) },
                    onTarget = { target ->
                        choices = choices + (list.sourceId to choice.copy(target = target))
                        overridden = overridden + list.sourceId
                    },
                )
            }
        }
        if (hasSameName) {
            SectionLabel("同名歌单")
            ConnectedToggleGroup(
                options = listOf(true, false),
                selected = merge,
                onSelect = { value ->
                    merge = value
                    choices = applySameNameToggle(plan, choices, overridden, value)
                },
                label = { if (it) "合并" else "另建一个" },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }
        val skippedText = importSkippedText(plan)
        if (skippedText.isNotBlank()) {
            SectionLabel("导不了的")
            Text(
                skippedText,
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (plan.migu > plan.miguResolved) {
            TextButton(
                onClick = actions::recheckImport,
                enabled = !applying,
                modifier = Modifier.padding(top = 4.dp).tvFocusRing(RoundedCornerShape(20.dp)),
            ) { Text("重新换取咪咕歌曲") }
        }
        Spacer(Modifier.size(8.dp))
    }
    // 按钮固定在列表外面：方向键下到底一定能落上
    Button(
        onClick = { actions.applyImport(choices) },
        enabled = !applying && listCount > 0,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .heightIn(min = 52.dp)
            .tvFocusRing(RoundedCornerShape(26.dp)),
    ) {
        Text(
            when {
                applying -> "正在导入…"
                listCount == 0 -> "没有勾选歌单"
                else -> "导入 $listCount 个歌单 · $songCount 首"
            },
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** 一个歌单：勾选框 + 名字 + 首数，下面是目标（点开选）和平台小块。整行点一下切换勾选。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportListRow(
    plan: ImportPlan,
    list: ImportedList,
    choice: ImportChoice,
    index: Int,
    count: Int,
    enabled: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onTarget: (ImportTarget) -> Unit,
) {
    val usable = list.importable > 0
    val shape = segmentShape(index, count)
    val checked = choice.include && usable
    Row(
        modifier
            .fillMaxWidth()
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (usable && enabled) Modifier.clickable(onClick = onToggle) else Modifier)
            .semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(checked)
            }
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = usable && enabled)
        }
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    list.name,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text("${list.shown} 首", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!usable) {
                Text("没有能导入的歌", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TargetPicker(plan, list, choice.target, enabled = enabled && checked, onTarget = onTarget)
            }
            val chips = list.platformCounts.entries.sortedByDescending { it.value }.map { Triple(it.key, it.value, false) } +
                list.skippedPlatforms.entries.sortedByDescending { it.value }.map { Triple(it.key, it.value, true) }
            if (chips.isNotEmpty()) {
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clearAndSetSemantics {
                            contentDescription = chips.joinToString("，") { (p, n, skipped) ->
                                "${platformLabel(p)} $n 首" + if (skipped) "导不了" else ""
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    chips.forEach { (p, n, skipped) -> PlatformChip(p, n, skipped) }
                }
            }
        }
    }
}

/** 目标「合并到收藏 ▾」：只有一个可选时不出下拉。电视上它是行里单独一个可聚焦按钮。 */
@Composable
private fun TargetPicker(plan: ImportPlan, list: ImportedList, target: ImportTarget, enabled: Boolean, onTarget: (ImportTarget) -> Unit) {
    val options = targetsFor(plan, list)
    var open by remember { mutableStateOf(false) }
    val label = targetLabel(plan, list, target)
    if (options.size <= 1) {
        Text(label, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        return
    }
    Box {
        Row(
            Modifier
                .padding(vertical = 2.dp)
                .tvFocusRing(RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClickLabel = "改导入方式") { open = true }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(targetLabel(plan, list, option)) },
                    leadingIcon = if (option == target) ({ Icon(Icons.Filled.Check, null) }) else null,
                    onClick = {
                        open = false
                        onTarget(option)
                    },
                )
            }
        }
    }
}

// ======================= 共用小件 =======================

/**
 * 电视上打开面板时焦点落在这里。面板是滑进来的，内容晚几帧才摆好，没接住就下一帧再试；
 * 接住了就停，之后焦点跟着用户走。
 */
@Composable
internal fun Modifier.sheetDefaultFocus(enabled: Boolean, key: Any? = Unit): Modifier {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    if (enabled) {
        LaunchedEffect(key) {
            repeat(12) {
                withFrameNanos { }
                if (focused) return@LaunchedEffect
                runCatching { requester.requestFocus() }
            }
        }
    }
    return this.focusRequester(requester).onFocusChanged { focused = it.isFocused || it.hasFocus }
}

/** 平台小块：图标 + 「网易云 30」；[skipped] 的画删除线（导入时本应用没有的平台）。 */
@Composable
internal fun PlatformChip(provider: String, count: Int, skipped: Boolean = false) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProviderIcon(id = provider, name = platformLabel(provider), size = 22.dp, corner = 7.dp)
            Text(
                "${shortPlatform(provider)} $count",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (skipped) TextDecoration.LineThrough else null,
            )
        }
    }
}

/** 平台全称（读屏、说明里用）。 */
internal fun platformLabel(provider: String): String = when (provider) {
    "local" -> "本地"
    else -> providerName(com.mytv.mymusic.data.native.NativeProviders.infos(), provider)
}

/** 小块上的短名，照稿子：网易云、QQ、酷狗、酷我、咪咕、本地。 */
internal fun shortPlatform(provider: String): String = when (provider) {
    "netease" -> "网易云"
    "qq" -> "QQ"
    "kugou" -> "酷狗"
    "kuwo" -> "酷我"
    "migu" -> "咪咕"
    "local" -> "本地"
    else -> platformLabel(provider)
}
