package com.mytv.mymusic.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mytv.mymusic.data.lx.LxPlatform
import com.mytv.mymusic.data.lx.LxSourceRecord
import com.mytv.mymusic.data.lx.MAX_LX_SOURCES
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.tvFocusRing

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LxSourcesSettingsSection(
    state: MusicUiState,
    model: LxSourcesViewModel = viewModel(),
) {
    val records by model.records.collectAsState()
    val operations by model.operations.collectAsState()
    val notice by model.notice.collectAsState()
    var importOpen by rememberSaveable { mutableStateOf(false) }
    var replaceId by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<LxSourceRecord?>(null) }
    val openUri = rememberOpenUri()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val target = replaceId
            model.importLocal(uri, target) {
                importOpen = false
                replaceId = null
            }
        }
    }

    SectionLabel("自定义音源") {
        Text(
            if (records.isEmpty()) "落雪脚本" else "${records.count { it.enabled }} / ${records.size} 已启用",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (records.isEmpty()) {
        LxSourceEmptyCard(
            enabled = records.size < MAX_LX_SOURCES,
            onAdd = { importOpen = true },
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            records.forEachIndexed { index, record ->
                LxSourceCard(
                    record = record,
                    index = index,
                    count = records.size,
                    testing = "test:${record.id}" in operations.busy,
                    updating = "update:${record.id}" in operations.busy,
                    testResult = operations.tests[record.id].orEmpty(),
                    error = operations.errors["test:${record.id}"]
                        ?: operations.errors["update:${record.id}"],
                    onToggle = { model.setEnabled(record, !record.enabled) },
                    onTest = { model.test(record, state.current, state.cookies) },
                    onUpdate = { model.update(record) },
                    onReplace = {
                        replaceId = record.id
                        filePicker.launch(arrayOf("*/*"))
                    },
                    onDelete = { deleteFor = record },
                    onMove = { model.move(record, it) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(
            onClick = { importOpen = true },
            enabled = records.size < MAX_LX_SOURCES,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusRing(),
        ) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (records.size < MAX_LX_SOURCES) "添加自定义音源" else "最多添加 $MAX_LX_SOURCES 个")
        }
    }
    SettingsNote("启用后按列表顺序优先尝试脚本取流，失败自动回退内置接口。脚本只接收歌曲信息，不会获得平台 Cookie。")

    operations.message.takeIf { it.isNotBlank() }?.let { message ->
        Surface(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp))
                Text(message, Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = model::clearMessage) { Icon(Icons.Filled.Close, "关闭提示") }
            }
        }
    }

    if (importOpen) {
        LxImportSheet(
            busy = "import" in operations.busy,
            error = operations.errors["import"].orEmpty(),
            onDismiss = { if ("import" !in operations.busy) importOpen = false },
            onImport = { url -> model.importOnline(url) { importOpen = false } },
            onFile = {
                replaceId = null
                filePicker.launch(arrayOf("*/*"))
            },
            onOpenCatalog = { openUri("https://github.com/pdone/lx-music-source") },
        )
    }
    deleteFor?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            icon = { Icon(Icons.Filled.DeleteOutline, null) },
            title = { Text("删除 ${record.name}？") },
            text = { Text("会删除本机保存的脚本；已有歌曲和歌单不会删除，之后取流回退到内置接口。") },
            confirmButton = {
                TextButton(onClick = {
                    model.delete(record)
                    deleteFor = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } },
        )
    }
    notice?.let { update ->
        AlertDialog(
            onDismissRequest = model::dismissNotice,
            title = { Text("${update.name} 有更新提示") },
            text = { Text(update.log, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                if (update.updateUrl.startsWith("http://") || update.updateUrl.startsWith("https://")) {
                    TextButton(onClick = {
                        openUri(update.updateUrl)
                        model.dismissNotice()
                    }) { Text("查看更新") }
                } else {
                    TextButton(onClick = model::dismissNotice) { Text("知道了") }
                }
            },
            dismissButton = if (update.updateUrl.isNotBlank()) {
                { TextButton(onClick = model::dismissNotice) { Text("关闭") } }
            } else null,
        )
    }
}

@Composable
private fun LxSourceEmptyCard(enabled: Boolean, onAdd: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Code, null, Modifier.size(32.dp), tint = scheme.onPrimaryContainer)
            }
            Text(
                "添加落雪自定义音源",
                Modifier.padding(top = 14.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "导入你信任的 JavaScript 脚本，为现有平台增加取流通道",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Button(
                onClick = onAdd,
                enabled = enabled,
                modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp).tvFocusRing(),
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("添加音源")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LxSourceCard(
    record: LxSourceRecord,
    index: Int,
    count: Int,
    testing: Boolean,
    updating: Boolean,
    testResult: String,
    error: String?,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onUpdate: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (record.enabled) scheme.surfaceContainerLow else scheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("JS", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            record.name,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (record.version.isNotBlank()) LxBadge("v${record.version}", Modifier.padding(start = 6.dp))
                    }
                    Text(
                        listOf(
                            record.author.takeIf { it.isNotBlank() }?.let { "by $it" }.orEmpty(),
                            if (record.sourceUrl.isBlank()) "本地脚本" else "在线脚本",
                            "第 ${index + 1} 位",
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = record.enabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.tvFocusRing(CircleShape).semantics {
                        contentDescription = "${if (record.enabled) "关闭" else "启用"}${record.name}"
                    },
                )
            }
            if (record.description.isNotBlank()) {
                Text(
                    record.description,
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LxPlatform.labels.forEach { (source, label) ->
                    val capability = record.capabilities[source] ?: return@forEach
                    if ("musicUrl" !in capability.actions) return@forEach
                    LxBadge(label, highlighted = record.enabled)
                }
                val extraActions = record.capabilities.values.flatMap { it.actions }.distinct()
                    .filter { it == "lyric" || it == "pic" }
                extraActions.forEach { LxBadge(if (it == "lyric") "歌词" else "封面") }
            }
            val qualities = record.capabilities.values.flatMap { it.qualities }.distinct()
            if (qualities.isNotEmpty()) {
                Text(
                    "音质  " + qualities.joinToString(" · ") {
                        when (it) {
                            "flac" -> "FLAC"
                            "flac24bit" -> "24bit"
                            else -> it
                        }
                    },
                    Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                FilledTonalButton(onClick = onTest, enabled = !testing, modifier = Modifier.tvFocusRing()) {
                    if (testing) LoadingIndicator(Modifier.size(18.dp))
                    else Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (testing) "测试中" else "测试")
                }
                if (record.sourceUrl.isNotBlank()) {
                    TextButton(onClick = onUpdate, enabled = !updating, modifier = Modifier.tvFocusRing()) {
                        if (updating) LoadingIndicator(Modifier.size(18.dp))
                        else Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (updating) "更新中" else "更新")
                    }
                } else {
                    TextButton(onClick = onReplace, modifier = Modifier.tvFocusRing()) {
                        Icon(Icons.Filled.FileOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("替换")
                    }
                }
                TextButton(onClick = onDelete, modifier = Modifier.tvFocusRing()) {
                    Icon(Icons.Filled.DeleteOutline, null, Modifier.size(18.dp), tint = scheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("删除", color = scheme.error)
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, "上移${record.name}")
                }
                IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                    Icon(Icons.Filled.KeyboardArrowDown, "下移${record.name}")
                }
            }
            if (testing) {
                SettingsNote("正在查找测试歌曲、执行脚本并读取媒体，最多约 2 分钟…")
            }
            if (!testing && testResult.isNotBlank()) LxTestResult(testResult, true)
            if (!error.isNullOrBlank()) LxTestResult(error, false)
        }
    }
}

@Composable
private fun LxTestResult(text: String, success: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (success) scheme.tertiaryContainer else scheme.errorContainer,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                null,
                Modifier.size(18.dp),
                tint = if (success) scheme.onTertiaryContainer else scheme.onErrorContainer,
            )
            Text(
                text,
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (success) scheme.onTertiaryContainer else scheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun LxBadge(text: String, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (highlighted) scheme.secondaryContainer else scheme.surfaceContainerHigh,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun LxImportSheet(
    busy: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    onFile: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf("online") }
    var url by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val tv = LocalAppWindow.current.isTv
    AdaptiveSheet(onDismiss = onDismiss, title = "添加自定义音源", subtitle = "兼容落雪音乐移动端 JavaScript 脚本") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            ConnectedToggleGroup(
                options = listOf("online", "local"),
                selected = mode,
                onSelect = { mode = it },
                label = { if (it == "online") "在线地址" else "本地文件" },
                modifier = Modifier.fillMaxWidth(),
            )
            if (mode == "online") {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    label = { Text("脚本地址") },
                    placeholder = { Text("https://…/latest.js") },
                    singleLine = true,
                    enabled = !busy,
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.let { url = it } }, enabled = !busy) {
                            Icon(Icons.Filled.ContentPaste, "粘贴脚本地址")
                        }
                    },
                )
                TextButton(onClick = onOpenCatalog, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                    Text("打开 pdone 音源合集")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
                }
                Button(
                    onClick = { onImport(url) },
                    enabled = url.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusRing(),
                ) {
                    if (busy) LoadingIndicator(Modifier.size(20.dp))
                    else Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "下载并验证中…" else "下载并导入")
                }
            } else {
                Surface(
                    onClick = onFile,
                    enabled = !busy,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).tvFocusRing(RoundedCornerShape(20.dp)),
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (busy) LoadingIndicator(Modifier.size(32.dp))
                        else Icon(Icons.Filled.FileOpen, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            if (busy) "读取并验证中…" else "选择 .js 脚本",
                            Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "UTF-8 编码 · 最大 2 MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (error.isNotBlank()) LxTestResult(error, false)
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Shield,
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "只导入信任的脚本。脚本可以通过其接口访问网络；myMusic 不向它提供平台 Cookie。导入时会先加载并读取能力，验证失败不会保存。",
                    Modifier.padding(start = 8.dp, bottom = if (tv) 12.dp else 0.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
