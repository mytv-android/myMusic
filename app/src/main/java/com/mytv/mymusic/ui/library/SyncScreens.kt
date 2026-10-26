package com.mytv.mymusic.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mytv.mymusic.data.sync.InitialMergePreview
import com.mytv.mymusic.data.sync.SyncConflictUi
import com.mytv.mymusic.data.sync.SyncStatus
import com.mytv.mymusic.data.sync.SyncStrategy
import com.mytv.mymusic.data.sync.SyncUiState
import com.mytv.mymusic.ui.ConfirmDialog
import com.mytv.mymusic.ui.ErrorBlock
import com.mytv.mymusic.ui.InfoNote
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalOverlayPadding
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.relativeTime

// ======================= 同步中心 =======================

/**
 * 音乐库同步：状态卡片 + 冲突 + 自动同步开关 + 备份 + 断开。
 * 手机单栏；≥840dp 平板和电视左栏是状态和冲突，右栏是开关和备份。
 * embedded = true 时不带顶栏，直接作为设置双栏的右栏内容（标题由设置页提供）。
 */
@Composable
fun SyncCenterScreen(
    sync: SyncUiState,
    actions: MusicActions,
    onBack: () -> Unit,
    embedded: Boolean = false,
    onConnect: (String) -> Unit,
) {
    val window = LocalAppWindow.current
    var disconnectOpen by remember { mutableStateOf(false) }
    val exportFile = rememberExportLauncher(actions)
    val importFile = rememberImportLauncher(actions)
    val primaryFocus = remember { FocusRequester() }
    // 嵌在设置右栏时焦点留在左侧列表，不抢
    if (!embedded) TvDefaultFocus(primaryFocus, sync.account == null)

    val status: LazyListScope.() -> Unit = {
        item(key = "hero", contentType = "hero") {
            if (sync.account == null) {
                SyncIntroCard(onConnect, primaryFocus)
            } else {
                SyncAccountCard(sync, actions, primaryFocus)
            }
        }
        if (sync.status == SyncStatus.Syncing) {
            item(key = "progress", contentType = "progress") {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        sync.phase.ifBlank { "正在同步…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (sync.error.isNotBlank()) {
            item(key = "error", contentType = "error") {
                Box(Modifier.padding(top = 8.dp)) {
                    ErrorBlock(sync.error, onRetry = if (sync.account != null) actions::syncNow else null)
                }
            }
        } else if (sync.message.isNotBlank()) {
            item(key = "message", contentType = "message") {
                Box(Modifier.padding(top = 8.dp)) { InfoNote(sync.message) }
            }
        }
        if (sync.conflicts.isNotEmpty()) {
            item(key = "conflictHead", contentType = "header") { SectionLabel("待处理冲突", trailing = { CountTag("${sync.conflicts.size} 个") }) }
            items(sync.conflicts, key = { "conflict:${it.playlistId}" }, contentType = { "conflict" }) { conflict ->
                SyncConflictCard(conflict, actions)
            }
        }
    }
    val settings: LazyListScope.() -> Unit = {
        val account = sync.account
        if (account != null) {
            item(key = "autoHead", contentType = "header") { SectionLabel("自动同步") }
            item(key = "auto", contentType = "switch") {
                SwitchRow(
                    title = "修改后自动同步",
                    checked = account.autoSync,
                    onCheckedChange = actions::setSyncAutoSync,
                    index = 0,
                    count = 2,
                    icon = Icons.Filled.Sync,
                    summary = "歌单修改后在后台调度，回到前台超过 15 分钟也会检查",
                )
            }
            item(key = "wifi", contentType = "switch") {
                SwitchRow(
                    title = "仅在 Wi-Fi 下同步",
                    checked = account.wifiOnly,
                    onCheckedChange = actions::setSyncWifiOnly,
                    index = 1,
                    count = 2,
                    icon = Icons.Filled.Wifi,
                    summary = "移动网络下先暂存，连上 Wi-Fi 再上传",
                )
            }
        }
        item(key = "backupHead", contentType = "header") { SectionLabel("备份") }
        item(key = "export", contentType = "row") {
            ListRow(
                title = "导出到文件",
                index = 0,
                count = 2,
                onClick = exportFile,
                icon = Icons.Filled.SaveAlt,
                iconTone = IconTone.Tertiary,
                summary = "保存为 JSON，可在其他设备导入",
            )
        }
        item(key = "import", contentType = "row") {
            ListRow(
                title = "从文件导入",
                index = 1,
                count = 2,
                onClick = importFile,
                icon = Icons.Filled.FileOpen,
                iconTone = IconTone.Tertiary,
                summary = "与本机歌单合并，不会覆盖",
            )
        }
        if (account != null) {
            item(key = "providerHead", contentType = "header") { SectionLabel("同步位置") }
            val providers = listOf("gist" to "GitHub Gist", "gitee" to "Gitee 代码片段")
            providers.forEachIndexed { index, (id, label) ->
                item(key = "provider:$id", contentType = "row") {
                    val current = account.provider == id
                    ListRow(
                        title = label,
                        index = index,
                        count = providers.size,
                        onClick = { onConnect(id) },
                        icon = Icons.Filled.Cloud,
                        selected = current,
                        summary = if (current) "当前使用 · ${account.fileName}" else "改用这里，需要输入令牌",
                    )
                }
            }
            item(key = "disconnect", contentType = "row") {
                ListRow(
                    title = "断开同步",
                    index = 0,
                    count = 1,
                    onClick = { disconnectOpen = true },
                    modifier = Modifier.padding(top = 20.dp),
                    icon = Icons.Filled.CloudOff,
                    iconTone = IconTone.Error,
                    danger = true,
                    summary = "删除本机保存的令牌，云端数据保留",
                    showChevron = false,
                )
            }
        }
        item(key = "note", contentType = "note") {
            Box(Modifier.padding(top = 16.dp)) {
                InfoNote("只同步自建歌单和歌曲元数据。收藏、最近播放、下载文件、Cookie、播放地址都不会进入云端。")
            }
        }
    }

    val bottom = LocalOverlayPadding.current.calculateBottomPadding() + 24.dp
    when {
        embedded -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            status()
            settings()
        }
        window.twoPane -> PageScaffold(title = "音乐库同步", subtitle = syncHeadline(sync), onBack = onBack) { padding ->
            TwoColumns(padding, if (window.isTv) 420.dp else 400.dp, status, settings)
        }
        else -> PageScaffold(title = "音乐库同步", subtitle = syncHeadline(sync), onBack = onBack) { padding ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = padding.withGutter(window.gutter),
                verticalArrangement = Arrangement.spacedBy(SegmentGap),
            ) {
                status()
                settings()
            }
        }
    }

    sync.initialMerge?.let { preview ->
        InitialMergeDialog(preview, sync, actions)
    }
    if (disconnectOpen) {
        ConfirmDialog(
            title = "断开同步",
            message = "会删除本机保存的令牌、同步基线和冲突记录。本机歌单保留，云端文件不会删除。",
            confirmLabel = "断开",
            danger = true,
            onConfirm = actions::disconnectSync,
            onDismiss = { disconnectOpen = false },
        )
    }
}

@Composable
private fun ShapeIcon(icon: ImageVector) {
    // Cookie 形状只做装饰
    Box(
        Modifier
            .size(56.dp)
            .clip(MaterialShapes.Cookie9Sided.toShape())
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun SyncIntroCard(onConnect: (String) -> Unit, focus: FocusRequester) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            ShapeIcon(Icons.Filled.CloudUpload)
            Text(
                "云端是副本，本机才是主版本",
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "连接 GitHub Gist 或 Gitee 后，自建歌单会自动同步。修改先保存在本机，断网也能用。",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onConnect("gist") },
                    modifier = Modifier.heightIn(min = 48.dp).focusRequester(focus).tvFocusRing(RoundedCornerShape(24.dp)),
                ) { Text("连接 GitHub Gist") }
                FilledTonalButton(
                    onClick = { onConnect("gitee") },
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
                ) { Text("Gitee") }
            }
        }
    }
}

@Composable
private fun SyncAccountCard(sync: SyncUiState, actions: MusicActions, focus: FocusRequester) {
    val account = sync.account ?: return
    val uri = LocalUriHandler.current
    val syncing = sync.status == SyncStatus.Syncing
    val title = when {
        syncing -> "正在同步到 ${sync.providerLabel}"
        account.lastSyncedAt <= 0 -> "已连接 ${sync.providerLabel}"
        else -> "已同步到 ${sync.providerLabel}"
    }
    val detail = listOf(
        relativeTime(account.lastSyncedAt).ifBlank { "尚未同步" },
        "${sync.playlistCount} 个歌单",
        "${sync.trackCount} 首",
        account.accountLabel,
    ).filter { it.isNotBlank() }.joinToString(" · ")
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            ShapeIcon(if (sync.conflicts.isNotEmpty()) Icons.Filled.SyncProblem else Icons.Filled.CloudDone)
            Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(
                "$detail\n只同步自建歌单和歌曲信息",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions::syncNow,
                    enabled = !syncing,
                    modifier = Modifier.heightIn(min = 48.dp).focusRequester(focus).tvFocusRing(RoundedCornerShape(24.dp)),
                ) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (syncing) "同步中…" else "立即同步")
                }
                if (account.remoteUrl.isNotBlank()) {
                    FilledTonalButton(
                        onClick = { runCatching { uri.openUri(account.remoteUrl) } },
                        modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
                    ) { Text(if (account.provider == "gitee") "查看代码片段" else "查看 Gist") }
                }
            }
        }
    }
}

/** 冲突提醒：一行说明 + “处理”。处理时手机弹底部面板，大屏弹居中对话框。 */
@Composable
fun SyncConflictCard(conflict: SyncConflictUi, actions: MusicActions) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.SyncProblem, null)
            Column(Modifier.weight(1f)) {
                Text("“${conflict.name}”有冲突", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("本机和云端都改过，处理前不会上传", style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = { open = true },
                modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("处理") }
        }
    }
    if (open) {
        ConflictResolver(
            title = "“${conflict.name}”两边都改过",
            detail = "本机：${conflictSide(conflict.localTracks, conflict.localUpdatedAt, conflict.localDeleted)} · " +
                "云端：${conflictSide(conflict.remoteTracks, conflict.remoteUpdatedAt, conflict.remoteDeleted)}",
            onApply = { strategy -> actions.resolveConflict(conflict.playlistId, strategy) },
            onCancel = { open = false },
            onClose = { open = false },
        )
    }
}

private fun conflictSide(tracks: Int, updatedAt: Long, deleted: Boolean): String =
    if (deleted) "已删除" else if (updatedAt > 0) "$tracks 首，${dayLabel(updatedAt)} ${timeLabel(updatedAt)} 修改" else "$tracks 首"

private val strategyChoices = listOf(
    Triple(SyncStrategy.Merge, "合并两版", "保留本机顺序，把云端新增的歌补在后面（推荐）"),
    Triple(SyncStrategy.Local, "保留本机", "用本机版本覆盖云端"),
    Triple(SyncStrategy.Remote, "使用云端", "放弃本机的修改"),
)

/** 三选一：选中项满圆角 + secondaryContainer，读屏是单选组。电视默认焦点在推荐项。 */
@Composable
private fun StrategyChoices(selected: SyncStrategy, onSelect: (SyncStrategy) -> Unit, focus: FocusRequester) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
        strategyChoices.forEachIndexed { index, (strategy, title, summary) ->
            val on = strategy == selected
            val shape = if (on) RoundedCornerShape(16.dp) else segmentShape(index, strategyChoices.size)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .then(if (index == 0) Modifier.focusRequester(focus) else Modifier)
                    .tvFocusRing(shape, scale = 1.02f)
                    .clip(shape)
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(strategy) })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = on, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ConflictResolver(
    title: String,
    detail: String,
    onApply: (SyncStrategy) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    dismissLabel: String = "取消",
    dismissible: Boolean = true,
    error: String = "",
) {
    val window = LocalAppWindow.current
    var choice by rememberSaveable { mutableStateOf(SyncStrategy.Merge) }
    val focus = remember { FocusRequester() }
    TvDefaultFocus(focus)
    val apply = {
        onApply(choice)
        onClose()
    }
    if (window.isPhone) {
        // 底部面板被下滑 / 返回时已经收起，若不移出组合，隐藏的面板窗口会挡住整页，所以这里总是走 onCancel
        AdaptiveSheet(onDismiss = onCancel, title = title, subtitle = detail) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                StrategyChoices(choice, { choice = it }, focus)
                if (error.isNotBlank()) {
                    Text(error, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = apply,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 56.dp),
                ) { Text("应用") }
                if (dismissible && dismissLabel != "取消") {
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(dismissLabel) }
                }
            }
        }
        return
    }
    // 大屏：居中对话框，宽 440dp
    Dialog(onDismissRequest = { if (dismissible) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 440.dp).padding(24.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    detail,
                    Modifier.padding(top = 8.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StrategyChoices(choice, { choice = it }, focus)
                if (error.isNotBlank()) {
                    Text(error, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                    if (dismissible) {
                        TextButton(onClick = onCancel, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text(dismissLabel) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = apply, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("应用") }
                }
            }
        }
    }
}

/** 首次连接时本机和云端都有歌单：同样的三选一。 */
@Composable
private fun InitialMergeDialog(
    preview: InitialMergePreview,
    sync: SyncUiState,
    actions: MusicActions,
    dismissible: Boolean = true,
) {
    ConflictResolver(
        title = "首次合并",
        detail = "本机：${preview.localPlaylists} 个歌单 / ${preview.localTracks} 首 · " +
            "${preview.providerLabel}：${preview.remotePlaylists} 个歌单 / ${preview.remoteTracks} 首",
        onApply = actions::resolveInitial,
        // 大屏不可关闭时对话框不会调用它；手机面板收起后必须清掉 initialMerge
        onCancel = actions::cancelInitial,
        // 选择后 initialMerge 清空，对话框自然消失
        onClose = {},
        dismissLabel = "稍后",
        dismissible = dismissible,
        error = sync.error,
    )
}

// ======================= 连接同步 =======================

@Composable
fun SyncConnectScreen(
    sync: SyncUiState,
    actions: MusicActions,
    initialProvider: String?,
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    val window = LocalAppWindow.current
    val uri = LocalUriHandler.current
    var provider by rememberSaveable { mutableStateOf(initialProvider?.takeIf { it == "gist" || it == "gitee" } ?: "gist") }
    var token by remember { mutableStateOf("") }
    var remote by rememberSaveable { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val tokenFocus = remember { FocusRequester() }
    val exportFile = rememberExportLauncher(actions)
    val importFile = rememberImportLauncher(actions)
    val syncing = sync.status == SyncStatus.Syncing
    val gist = provider == "gist"

    // 已连接时改用另一处：重试会先把 error 清空，旧 account 还在，不等验证结束就会返回；
    // 连接完成后 account 还会再变，所以只在不在同步中时返回，且只返回一次
    var connected by remember { mutableStateOf(false) }
    LaunchedEffect(sync.account, sync.initialMerge, sync.error, syncing) {
        if (!connected && attempted && !syncing && sync.account != null && sync.initialMerge == null && sync.error.isBlank()) {
            connected = true
            onConnected()
        }
    }
    val leave = {
        if (sync.initialMerge != null) actions.cancelInitial()
        onBack()
    }
    BackHandler(enabled = sync.initialMerge != null) { leave() }
    TvDefaultFocus(tokenFocus)

    val providerPicker: @Composable () -> Unit = {
        ConnectedToggleGroup(
            options = listOf("gist", "gitee"),
            selected = provider,
            onSelect = { provider = it },
            label = { if (it == "gist") "GitHub Gist" else "Gitee 代码片段" },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val whatCard: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("会同步什么", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "自建歌单和歌曲元数据。不包括收藏、最近播放、下载文件、Cookie、播放地址和进度。",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    val help: @Composable () -> Unit = {
        TextButton(
            onClick = {
                runCatching {
                    uri.openUri(
                        if (gist) "https://github.com/settings/tokens/new?scopes=gist&description=myMusic"
                        else "https://gitee.com/profile/personal_access_tokens",
                    )
                }
            },
            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
        ) { Text("如何创建令牌？") }
    }
    val offline: @Composable () -> Unit = {
        Column {
            Text(
                "不使用账号",
                Modifier.padding(start = 4.dp, top = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = exportFile, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("导出到文件") }
                TextButton(onClick = importFile, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("从文件导入") }
            }
        }
    }
    val form: @Composable ColumnScope.() -> Unit = {
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            modifier = Modifier.fillMaxWidth().focusRequester(tokenFocus).tvFocusRing(RoundedCornerShape(4.dp)),
            singleLine = true,
            label = { Text("访问令牌") },
            leadingIcon = { Icon(Icons.Filled.Lock, null) },
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        if (showToken) "隐藏令牌" else "显示令牌",
                    )
                }
            },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            supportingText = {
                Text(
                    if (gist) "需要 gist 权限。令牌用 Android Keystore 加密，只保存在本机，不会写进同步文件。"
                    else "在 Gitee 私人令牌里勾选 gists 权限。令牌加密后只保存在本机。",
                )
            },
        )
        OutlinedTextField(
            value = remote,
            onValueChange = { remote = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).tvFocusRing(RoundedCornerShape(4.dp)),
            singleLine = true,
            label = { Text(if (gist) "Gist ID（可选）" else "代码片段 ID（可选）") },
            placeholder = { Text(if (gist) "留空则新建一个私密 Gist" else "留空则新建一个私密代码片段") },
            supportingText = { Text("也可以粘贴已有的链接") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        if (window.isTv) {
            Box(Modifier.padding(top = 8.dp)) {
                InfoNote("令牌很长，建议用手机上的 Google TV 应用输入；或者在手机上连接后导出文件，再在这里“从文件导入”。")
            }
        }
        if (sync.error.isNotBlank()) {
            Box(Modifier.padding(top = 12.dp)) { ErrorBlock(sync.error) }
        }
        if (sync.initialMerge != null) {
            Box(Modifier.padding(top = 12.dp)) {
                InfoNote("本机和云端都有歌单，请选择首次合并方式。令牌已加密保存在本机。", "amber")
            }
        }
        Button(
            onClick = {
                attempted = true
                actions.connectSync(provider, token, remote)
            },
            enabled = token.isNotBlank() && !syncing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .heightIn(min = 56.dp)
                .tvFocusRing(RoundedCornerShape(28.dp)),
        ) {
            if (syncing) {
                LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("正在验证…")
            } else {
                Icon(Icons.Filled.Cloud, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("连接并同步")
            }
        }
    }

    PageScaffold(title = "连接同步", subtitle = if (gist) "GitHub Gist" else "Gitee 代码片段", onBack = leave) { padding ->
        if (window.twoPane) {
            // 左栏选位置和说明，右栏是表单（最宽 440dp，不拉满）
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .padding(horizontal = window.gutter),
                horizontalArrangement = Arrangement.spacedBy(if (window.isTv) 32.dp else 24.dp),
            ) {
                Column(
                    Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(bottom = padding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    providerPicker()
                    whatCard()
                    help()
                    offline()
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = padding.calculateBottomPadding()),
                ) {
                    Column(Modifier.widthIn(max = 440.dp), content = form)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = window.gutter),
            ) {
                Column(Modifier.widthIn(max = 560.dp)) {
                    providerPicker()
                    Spacer(Modifier.size(16.dp))
                    form()
                    Spacer(Modifier.size(16.dp))
                    whatCard()
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { help() }
                    offline()
                }
            }
        }
    }

    sync.initialMerge?.let { preview ->
        InitialMergeDialog(preview, sync, actions, dismissible = false)
    }
}
