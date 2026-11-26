package com.mytv.mymusic.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mytv.mymusic.data.lx.LxDesktopLibraries
import com.mytv.mymusic.data.lx.LxPlatform
import com.mytv.mymusic.data.lx.LxSourceRecord
import com.mytv.mymusic.data.lx.LxSources
import com.mytv.mymusic.data.lx.MAX_LX_SOURCES
import com.mytv.mymusic.data.lx.canBrowseStorage
import com.mytv.mymusic.data.lx.requestStorageAccess
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentedGroup
import com.mytv.mymusic.ui.components.tvFocusRing

/** 音乐源页只留一个入口，不把整套管理器塞进内置平台列表。 */
@Composable
internal fun LxSourcesSettingsEntry(onClick: () -> Unit) {
    val records by LxSources.records.collectAsState()
    ListRow(
        title = "自定义音源",
        index = 0,
        count = 1,
        onClick = onClick,
        icon = Icons.Filled.Code,
        summary = if (records.isEmpty()) {
            "落雪脚本 · 在线或本地导入"
        } else {
            "${records.count { it.enabled }} 个已启用 · 共 ${records.size} 个"
        },
    )
}

@Composable
internal fun LxSourcesSettingsScreen(
    state: MusicUiState,
    onBack: () -> Unit,
    embedded: Boolean = false,
    onOpenInputPanel: (() -> Unit)? = null,
    model: LxSourcesViewModel = viewModel(),
) {
    val records by model.records.collectAsState()
    val operations by model.operations.collectAsState()
    val notice by model.notice.collectAsState()
    var importOpen by rememberSaveable { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
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

    SettingsPage(SettingsSection.LxSources, embedded, onBack) {
        item(key = "add", contentType = "settingRow") {
            ListRow(
                title = "添加自定义音源",
                index = 0,
                count = 1,
                onClick = if (records.size < MAX_LX_SOURCES) ({ importOpen = true }) else null,
                icon = Icons.Filled.Add,
                iconTone = IconTone.Primary,
                summary = when {
                    records.size >= MAX_LX_SOURCES -> "最多保存 $MAX_LX_SOURCES 个音源"
                    records.isEmpty() -> "在线地址或本地文件，可添加多个"
                    else -> "在线地址或本地文件 · 已添加 ${records.size} 个"
                },
                showChevron = records.size < MAX_LX_SOURCES,
            )
        }
        if (records.isEmpty()) {
            item(key = "empty", contentType = "note") {
                SettingsNote("还没有自定义音源。可以添加多个落雪脚本，为已有平台增加取流通道，不改变搜索和榜单来源。")
            }
        } else {
            item(key = "sourcesHead", contentType = "sectionHeader") {
                SectionLabel("我的音源") {
                    Text(
                        "${records.count { it.enabled }} / ${records.size} 已启用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "sources", contentType = "sourceList") {
                SegmentedGroup {
                    records.forEachIndexed { index, record ->
                        ListRow(
                            title = record.name,
                            index = index,
                            count = records.size,
                            onClick = { selectedId = record.id },
                            icon = Icons.Filled.Code,
                            summary = sourceSummary(record),
                            showChevron = false,
                            trailing = {
                                Switch(
                                    checked = record.enabled,
                                    onCheckedChange = { model.setEnabled(record, !record.enabled) },
                                    modifier = Modifier.semantics {
                                        contentDescription = "${if (record.enabled) "关闭" else "启用"}${record.name}"
                                    },
                                )
                            },
                        )
                    }
                }
            }
            item(key = "sourcesNote", contentType = "note") {
                SettingsNote("这里只调整统一「洛雪」内部的脚本顺序。全部脚本失败后，按播放与换源设置继续回退；点按音源可测试、更新、排序或删除。")
            }
        }
        operations.message.takeIf { it.isNotBlank() }?.let { message ->
            item(key = "message", contentType = "note") {
                ListRow(
                    title = message,
                    index = 0,
                    count = 1,
                    onClick = model::clearMessage,
                    summary = "点按关闭提示",
                    showChevron = false,
                )
            }
        }
    }

    val selected = records.firstOrNull { it.id == selectedId }
    selected?.let { record ->
        LxSourceDetailSheet(
            record = record,
            index = records.indexOfFirst { it.id == record.id },
            count = records.size,
            testing = "test:${record.id}" in operations.busy,
            updating = "update:${record.id}" in operations.busy,
            testResult = operations.tests[record.id].orEmpty(),
            error = operations.errors["test:${record.id}"]
                ?: operations.errors["update:${record.id}"]
                ?: operations.errors["toggle:${record.id}"]
                ?: operations.errors["move:${record.id}"],
            onDismiss = { selectedId = null },
            onTest = { model.test(record, state.current, state.cookies) },
            onUpdate = { model.update(record) },
            onReplace = {
                replaceId = record.id
                filePicker.launch(arrayOf("*/*"))
            },
            onMove = { model.move(record, it) },
            onDelete = { deleteFor = record },
            onOpenHomepage = { if (record.homepage.isNotBlank()) openUri(record.homepage) },
        )
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
            onSendFromPhone = onOpenInputPanel,
        )
    }
    deleteFor?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除 ${record.name}？") },
            text = { Text("会删除本机保存的脚本；已有歌曲和歌单不会删除，之后取流回退到内置接口。") },
            confirmButton = {
                TextButton(onClick = {
                    model.delete(record)
                    selectedId = null
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
private fun LxSourceDetailSheet(
    record: LxSourceRecord,
    index: Int,
    count: Int,
    testing: Boolean,
    updating: Boolean,
    testResult: String,
    error: String?,
    onDismiss: () -> Unit,
    onTest: () -> Unit,
    onUpdate: () -> Unit,
    onReplace: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onOpenHomepage: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss, title = record.name, subtitle = sourceSubtitle(record)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (record.desktop) {
                SettingsNote(
                    "桌面版插件：只用来取流，脚本自己按歌名 / id 去它指定的地方要链接。" +
                        "运行必需的 axios、he 从官方 CDN 下载并逐个校验 sha256 后缓存在本机；" +
                        "脚本本身仍是可执行代码，能访问任意 HTTP 地址，只导入你信任的来源。",
                )
            }
            if (record.sourceUrl.isBlank()) {
                SettingsNote("本地导入的脚本没有下载地址：会连脚本内容一起同步到你连接的 Gist / Gitee / WebDAV；" +
                    "没连同步时它只存在这台设备上，卸载或清数据就没了，需要重新导入。")
            }
            if (record.description.isNotBlank()) {
                Text(
                    record.description,
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SegmentedGroup {
                ListRow(
                    title = "支持平台与音质",
                    index = 0,
                    count = if (record.homepage.isBlank()) 1 else 2,
                    onClick = null,
                    icon = Icons.Filled.Tune,
                    summary = sourceCapabilities(record),
                    showChevron = false,
                )
                if (record.homepage.isNotBlank()) {
                    ListRow(
                        title = "脚本主页",
                        index = 1,
                        count = 2,
                        onClick = onOpenHomepage,
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        summary = record.homepage.substringAfter("://").substringBefore('/'),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SegmentedGroup {
                ListRow(
                    title = "测试取流",
                    index = 0,
                    count = 4,
                    onClick = if (testing) null else onTest,
                    icon = Icons.Filled.PlayArrow,
                    iconTone = IconTone.Primary,
                    summary = if (testing) "正在执行脚本并读取媒体…" else "检查脚本和真实媒体响应",
                    showChevron = false,
                    trailing = if (testing) {
                        { LoadingIndicator(Modifier.size(24.dp)) }
                    } else null,
                )
                ListRow(
                    title = if (record.sourceUrl.isBlank()) "替换脚本" else "更新脚本",
                    index = 1,
                    count = 4,
                    onClick = if (updating) null else if (record.sourceUrl.isBlank()) onReplace else onUpdate,
                    icon = if (record.sourceUrl.isBlank()) Icons.Filled.FileOpen else Icons.Filled.Refresh,
                    summary = if (updating) "正在下载并验证…" else if (record.sourceUrl.isBlank()) "选择新的本地文件" else "从原地址下载最新版本",
                    showChevron = false,
                    trailing = if (updating) {
                        { LoadingIndicator(Modifier.size(24.dp)) }
                    } else null,
                )
                ListRow(
                    title = "调整顺序",
                    index = 2,
                    count = 4,
                    onClick = null,
                    icon = Icons.Filled.SwapVert,
                    summary = "第 ${index + 1} 位，共 $count 个音源",
                    showChevron = false,
                    trailing = {
                        IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "上移${record.name}")
                        }
                        IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                            Icon(Icons.Filled.KeyboardArrowDown, "下移${record.name}")
                        }
                    },
                )
                ListRow(
                    title = "删除音源",
                    index = 3,
                    count = 4,
                    onClick = onDelete,
                    icon = Icons.Filled.DeleteOutline,
                    iconTone = IconTone.Error,
                    summary = "只删除本机脚本，不影响歌曲和歌单",
                    danger = true,
                    showChevron = false,
                )
            }
            if (testResult.isNotBlank()) {
                Text(
                    testResult,
                    Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!error.isNullOrBlank()) {
                Text(
                    error,
                    Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
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
    onSendFromPhone: (() -> Unit)? = null,
) {
    var mode by rememberSaveable { mutableStateOf("online") }
    var url by rememberSaveable { mutableStateOf("") }
    var remoteInput by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val tv = LocalAppWindow.current.isTv
    if (remoteInput) {
        TvFieldInputDialog(
            targetId = "lx-source-url",
            label = "自定义音源 · 脚本地址",
            value = url,
            busy = busy,
            onValueChange = { url = it },
            onSubmit = onImport,
            onDismiss = { remoteInput = false },
        )
    }
    AdaptiveSheet(onDismiss = onDismiss, title = "添加自定义音源", subtitle = "兼容落雪音乐移动端 JavaScript 脚本") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            ConnectedToggleGroup(
                options = listOf("online", "local"),
                selected = mode,
                onSelect = { mode = it },
                label = { if (it == "online") "在线地址" else "本地文件" },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            if (mode == "online") {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("脚本地址") },
                    placeholder = { Text("https://…/latest.js") },
                    singleLine = true,
                    enabled = !busy,
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { clipboard.getText()?.text?.let { url = it } }, enabled = !busy) {
                                Icon(Icons.Filled.ContentPaste, "粘贴脚本地址")
                            }
                            if (tv) {
                                IconButton(onClick = { remoteInput = true }, enabled = !busy) {
                                    Icon(Icons.Filled.PhoneAndroid, "用手机输入脚本地址")
                                }
                            }
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
                    Text(if (busy) "下载并验证中…" else "下载并导入", Modifier.padding(start = 8.dp))
                }
            } else {
                // 电视上常常没有文件管理器，本地选择会直接失败；扫码面板是能用的那条路。
                val phoneChannel = onSendFromPhone != null && tv
                // 面板翻电视存储要「所有文件管理」；用户可能正在电视设置里开，回前台重新查
                var storageGranted by remember { mutableStateOf(canBrowseStorage(context)) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { storageGranted = canBrowseStorage(context) }
                val grantRow = tv && !storageGranted
                val rows = 1 + (if (phoneChannel) 1 else 0) + (if (grantRow) 1 else 0)
                SegmentedGroup {
                    ListRow(
                        title = if (busy) "正在读取并验证…" else "选择 .js 脚本",
                        index = 0,
                        count = rows,
                        onClick = if (busy) null else onFile,
                        icon = Icons.Filled.FileOpen,
                        summary = "UTF-8 编码 · 最大 2 MB",
                        showChevron = !busy,
                        trailing = if (busy) {
                            { LoadingIndicator(Modifier.size(24.dp)) }
                        } else null,
                    )
                    if (phoneChannel) {
                        ListRow(
                            title = "用手机选择脚本文件",
                            index = 1,
                            count = rows,
                            onClick = onSendFromPhone,
                            icon = Icons.Filled.PhoneAndroid,
                            summary = "扫码后在手机上翻这台电视的文件夹，或从手机存储上传",
                        )
                    }
                    if (grantRow) {
                        ListRow(
                            title = "授权浏览电视存储",
                            index = if (phoneChannel) 2 else 1,
                            count = rows,
                            onClick = { requestStorageAccess(context) },
                            icon = Icons.Filled.Lock,
                            summary = "翻电视文件夹需要系统设置里的「所有文件管理」权限",
                        )
                    }
                }
            }
            if (error.isNotBlank()) {
                Text(
                    error,
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SettingsNote(
                "只导入信任的脚本。脚本可以访问网络，但不会获得 myMusic 保存的平台 Cookie。验证失败不会保存。" +
                    "移动版脚本直接运行；桌面版（Electron）插件需要的 " + LxDesktopLibraries.summary() +
                    " 会从官方 CDN 下载、逐个校验 sha256 后缓存在本机。",
            )
        }
    }
}

private fun sourceSubtitle(record: LxSourceRecord): String = listOf(
    record.version.takeIf { it.isNotBlank() }?.let { "v$it" }.orEmpty(),
    record.author,
    if (record.desktop) "桌面版插件" else if (record.sourceUrl.isBlank()) "本地脚本" else "在线脚本",
).filter { it.isNotBlank() }.joinToString(" · ")

private fun sourceSummary(record: LxSourceRecord): String = listOf(
    sourceSubtitle(record),
    sourcePlatforms(record),
).filter { it.isNotBlank() }.joinToString("\n")

private fun sourceCapabilities(record: LxSourceRecord): String {
    val qualities = record.capabilities.values.flatMap { it.qualities }.distinct()
    return listOf(
        sourcePlatforms(record),
        qualities.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "音质：") { qualityLabel(it) }.orEmpty(),
    ).filter { it.isNotBlank() }.joinToString("\n")
}

private fun sourcePlatforms(record: LxSourceRecord): String = LxPlatform.labels.mapNotNull { (source, label) ->
    val capability = record.capabilities[source] ?: return@mapNotNull null
    if ("musicUrl" in capability.actions) label else null
}.joinToString(" · ")

private fun qualityLabel(quality: String): String = when (quality) {
    "flac" -> "FLAC"
    "flac24bit" -> "24bit"
    "hires" -> "Hi-Res"
    "atmos" -> "全景声"
    "atmos_plus" -> "全景声+"
    "master" -> "母带"
    "sq" -> "无损"
    else -> quality
}
