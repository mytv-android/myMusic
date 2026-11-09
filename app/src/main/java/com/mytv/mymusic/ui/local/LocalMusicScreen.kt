package com.mytv.mymusic.ui.local

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.local.LocalAvailability
import com.mytv.mymusic.data.local.LocalIndex
import com.mytv.mymusic.data.local.LocalItem
import com.mytv.mymusic.data.local.LocalLibrary
import com.mytv.mymusic.data.local.LocalMatchProgress
import com.mytv.mymusic.data.local.LocalMusicSettings
import com.mytv.mymusic.data.local.LocalScanState
import com.mytv.mymusic.ui.EmptyState
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.PlaylistPickerSheet
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.PlayAllSplitButton
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SelectionBar
import com.mytv.mymusic.ui.components.SelectionPredictiveBack
import com.mytv.mymusic.ui.components.SelectionState
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.selectionBackPreview
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.formatBytes
import com.mytv.mymusic.ui.formatDuration
import com.mytv.mymusic.ui.library.CenteredEmpty
import com.mytv.mymusic.ui.library.DayLabel
import com.mytv.mymusic.ui.library.FilterRow
import com.mytv.mymusic.ui.library.Tag
import com.mytv.mymusic.ui.library.TvDefaultFocus
import com.mytv.mymusic.ui.library.TwoColumns
import com.mytv.mymusic.ui.library.withGutter
import kotlinx.coroutines.launch

/** 本地音乐页的筛选：全部、没匹配、按文件夹分组。 */
internal enum class LocalFilter(val label: String) { All("全部"), Unmatched("没匹配"), Folders("文件夹") }

/**
 * 本地音乐 · 本机视图。结构照「下载」页：顶上一张占用卡片（分段条：已匹配 / 没匹配），
 * 再往下是播放全部、筛选和歌曲列表。第一次进来没有权限也没有文件夹时是空状态，
 * 主按钮“允许读取音乐”，拒绝后换成“改为选择文件夹”（系统文件夹选择器，不要权限）。
 */
@Composable
fun LocalMusicScreen(
    state: MusicUiState,
    playlists: List<LocalPlaylistSummary>,
    actions: MusicActions,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val library = rememberLocalLibrary() ?: return
    val window = LocalAppWindow.current
    val index by library.index.collectAsStateWithLifecycle()
    val scan by library.scan.collectAsStateWithLifecycle()
    val matching by library.matching.collectAsStateWithLifecycle()
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val access = rememberLocalAccess(library, prefs)

    var filter by rememberSaveable { mutableStateOf(LocalFilter.All) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var infoFor by remember { mutableStateOf<String?>(null) }
    var rematchFor by remember { mutableStateOf<String?>(null) }
    var pickerSongs by remember { mutableStateOf<List<Song>?>(null) }
    val selection = remember { SelectionState() }
    SelectionPredictiveBack(selection)
    val firstFocus = remember { FocusRequester() }

    // 打开页面时：上次扫描超过 10 分钟就增量扫一遍（没变的文件不会再打开）
    LaunchedEffect(Unit) { library.scanIfStale() }

    val shown = remember(index, filter, query) { filterItems(index, filter, query) }
    val songs = remember(shown) { shown.map { it.song } }
    val playable = remember(shown) { shown.filter { it.onDevice }.map { it.song } }
    val empty = index.items.isEmpty()
    val sourcesReady = library.canScan(prefs)
    TvDefaultFocus(firstFocus, empty)

    val header: LazyListScope.() -> Unit = {
        item(key = "card", contentType = "card") {
            LocalStorageCard(index, scan, matching, onCleanMissing = { library.launch { removeMissing() } })
        }
        item(key = "actions", contentType = "actions") {
            if (selection.active) {
                SelectionBar(
                    count = selection.selected.size,
                    allSelected = songs.isNotEmpty() && selection.selected.containsAll(songs.map { it.key }),
                    onToggleAll = { selection.toggleAll(songs.map { it.key }) },
                    onPlay = {
                        val picked = songs.filter { it.key in selection.selected }
                        if (picked.isNotEmpty()) actions.play(picked.first(), picked)
                    },
                    onAddTo = { pickerSongs = songs.filter { it.key in selection.selected } },
                    onExit = { selection.exit() },
                    modifier = Modifier.padding(top = 12.dp).selectionBackPreview(selection),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayAllSplitButton(
                        onPlayAll = { playable.firstOrNull()?.let { actions.play(it, playable) } },
                        modifier = Modifier.focusRequester(firstFocus),
                        onShuffle = {
                            val list = playable.shuffled()
                            list.firstOrNull()?.let { actions.play(it, list) }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    if (songs.isNotEmpty()) {
                        IconButton(onClick = { selection.start() }, modifier = Modifier.tvFocusRing(CircleShape)) {
                            Icon(Icons.Filled.Checklist, "多选")
                        }
                    }
                }
            }
        }
        item(key = "filters", contentType = "filters") {
            FilterRow(LocalFilter.entries, filter, { it.label }) { filter = it }
        }
        if (searching) {
            item(key = "search", contentType = "search") {
                // 点了搜索就是要输入：打开时直接把焦点和键盘给输入框，不用再点一下
                val field = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).focusRequester(field),
                    singleLine = true,
                    label = { Text("搜索本地音乐") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Filled.Close, "关闭搜索") }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // 边输边筛，回车只收起键盘
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
            }
        }
    }
    val list: LazyListScope.() -> Unit = {
        if (shown.isEmpty()) {
            item(key = "none", contentType = "empty") {
                Text(
                    when {
                        query.isNotBlank() -> "没有找到“$query”。"
                        filter == LocalFilter.Unmatched -> "所有歌都匹配上了。"
                        else -> "还没有扫到歌。"
                    },
                    Modifier.padding(start = 4.dp, top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val groups = if (filter == LocalFilter.Folders) {
            shown.groupBy { it.row.relativePath.ifBlank { "其他位置" } }.toSortedMap().toList()
        } else {
            listOf("" to shown)
        }
        groups.forEach { (folder, items) ->
            if (folder.isNotEmpty()) {
                item(key = "folder:$folder", contentType = "header") { DayLabel("${folder.trimEnd('/')} · ${items.size} 首") }
            }
            itemsIndexed(items, key = { _, item -> "song:${folder}:${item.localId}" }, contentType = { _, _ -> "song" }) { position, item ->
                LocalSongRow(
                    item = item,
                    index = index,
                    position = position,
                    count = items.size,
                    playing = state.current?.key == item.song.key,
                    selection = selection,
                    onPlay = {
                        if (item.onDevice) {
                            actions.play(item.song, playable)
                        } else {
                            infoFor = item.localId
                        }
                    },
                    onMore = { infoFor = item.localId },
                )
            }
        }
    }

    PageScaffold(
        title = "本地音乐",
        subtitle = localPageSubtitle(index, scan, prefs.lastScanAt),
        onBack = onBack,
        actions = {
            if (!empty) {
                IconButton(onClick = { searching = !searching }, modifier = Modifier.tvFocusRing(CircleShape)) {
                    Icon(Icons.Filled.Search, "搜索本地音乐")
                }
            }
            if (sourcesReady) {
                IconButton(
                    onClick = { if (scan.running) library.cancelScan() else library.scan() },
                    modifier = Modifier.tvFocusRing(CircleShape),
                ) {
                    Icon(if (scan.running) Icons.Filled.Close else Icons.Filled.Refresh, if (scan.running) "停止扫描" else "重新扫描")
                }
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.tvFocusRing(CircleShape)) {
                Icon(Icons.Filled.Settings, "本地音乐设置")
            }
        },
    ) { padding ->
        when {
            empty && !sourcesReady -> CenteredEmpty(padding) { LocalAccessEmpty(access) }
            empty && !index.loaded -> Box(Modifier.padding(padding))
            empty && !scan.running && scan.error.isEmpty() && prefs.lastScanAt > 0 -> CenteredEmpty(padding) {
                EmptyState(
                    icon = Icons.Filled.AudioFile,
                    title = "没有找到音乐",
                    message = if (access.canPickFolder) "媒体库里没有歌，或者歌不在扫描范围里。可以把放歌的文件夹加进来。" else "媒体库里没有歌。",
                    actionLabel = if (access.canPickFolder) "添加文件夹" else "重新扫描",
                    onAction = if (access.canPickFolder) access.pickFolder else ({ library.scan() }),
                )
            }
            window.twoPane -> TwoColumns(padding, if (window.isTv) 360.dp else 380.dp, header, list)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = padding.withGutter(window.gutter),
                verticalArrangement = Arrangement.spacedBy(SegmentGap),
            ) {
                header()
                list()
            }
        }
    }

    infoFor?.let { id ->
        val item = index.byId[id]
        if (item == null) {
            infoFor = null
        } else {
            LocalTrackInfoSheet(
                item = item,
                library = library,
                preferLrc = prefs.preferLrc,
                onRematch = { rematchFor = id },
                onAddToPlaylist = { pickerSongs = listOf(item.song) },
                onPlayNext = {
                    actions.playSongNext(item.song)
                    infoFor = null
                },
                onDismiss = { infoFor = null },
            )
        }
    }
    rematchFor?.let { id ->
        val item = index.byId[id]
        if (item == null) {
            rematchFor = null
        } else {
            LocalRematchSheet(item = item, library = library, onDismiss = { rematchFor = null })
        }
    }
    pickerSongs?.let { picked ->
        PlaylistPickerSheet(
            playlists = playlists,
            onPick = { playlistId ->
                if (picked.size == 1) actions.addSongToPlaylist(playlistId, picked.first()) else actions.addSongsToPlaylist(playlistId, picked)
                pickerSongs = null
                selection.exit()
            },
            onCreate = { name ->
                actions.addSongsToNewPlaylist(name, picked)
                pickerSongs = null
                selection.exit()
            },
            onDismiss = { pickerSongs = null },
        )
    }
}

private fun filterItems(index: LocalIndex, filter: LocalFilter, query: String): List<LocalItem> {
    val text = query.trim()
    return index.items.filter { item ->
        (filter != LocalFilter.Unmatched || index.isUnmatched(item)) &&
            (text.isEmpty() || item.song.name.contains(text, ignoreCase = true) ||
                item.song.artist.contains(text, ignoreCase = true) ||
                item.row.displayName.contains(text, ignoreCase = true))
    }
}

@Composable
private fun LocalSongRow(
    item: LocalItem,
    index: LocalIndex,
    position: Int,
    count: Int,
    playing: Boolean,
    selection: SelectionState,
    onPlay: () -> Unit,
    onMore: () -> Unit,
) {
    val window = LocalAppWindow.current
    val scheme = MaterialTheme.colorScheme
    val picking = selection.active
    val selected = item.song.key in selection.selected
    val unmatched = index.isUnmatched(item)
    val song = item.song
    val subtitle = listOf(
        song.artist,
        when {
            item.row.missing -> "文件不在了"
            window.isPhone && song.duration > 0 -> formatDuration(song.duration)
            else -> ""
        },
    ).filter { it.isNotBlank() }.joinToString(" · ")
    SongRow(
        title = song.title,
        subtitle = subtitle,
        cover = song.cover,
        index = position,
        count = count,
        onClick = { if (picking) selection.toggle(song.key) else onPlay() },
        playing = playing,
        container = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow,
        badges = {
            LocalSongBadge(if (item.onDevice) LocalAvailability.OnDevice else LocalAvailability.Missing)
            if (unmatched) Tag("没匹配", scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        },
        trailing = when {
            picking -> {
                {
                    Icon(
                        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (selected) "取消选择“${song.title}”" else "选择“${song.title}”",
                        tint = scheme.primary,
                    )
                }
            }
            !window.isPhone && song.duration > 0 -> {
                { Text(formatDuration(song.duration), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant) }
            }
            else -> null
        },
        onMore = if (picking) null else onMore,
        onLongClick = if (picking) null else ({ selection.enter(song.key) }),
    )
}

/**
 * 顶上的占用卡片：总数和大小，分段条按已匹配 / 没匹配 / 等待匹配分；扫描时换成进度。
 * 有“文件不在了”的歌时给一个清理入口——只清本地音乐里的记录，歌单不受影响。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalStorageCard(
    index: LocalIndex,
    scan: LocalScanState,
    matching: LocalMatchProgress,
    onCleanMissing: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val matched = index.matchedCount
    val unmatched = index.unmatchedCount
    val pending = (index.items.size - matched - unmatched).coerceAtLeast(0)
    val segments = listOf(
        Triple("已匹配", matched, scheme.primary),
        Triple("没匹配", unmatched, scheme.surfaceContainerHighest),
        Triple(if (matching.running) "正在匹配" else "等待匹配", pending, scheme.tertiary),
    ).filter { it.second > 0 }
    val total = segments.sumOf { it.second }.coerceAtLeast(1)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "${index.items.size} 首 · ${formatBytes(index.totalBytes)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                if (scan.running) scanProgressLabel(scan) else "封面和歌词自动匹配，原文件不改。",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (scan.running) {
                val progress = if (scan.total > 0) scan.done.toFloat() / scan.total else 0f
                if (scan.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.padding(top = 14.dp).fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.padding(top = 14.dp).fillMaxWidth())
                }
            } else if (segments.isNotEmpty()) {
                Row(
                    Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .height(14.dp)
                        .semantics { contentDescription = segments.joinToString("，") { "${it.first} ${it.second} 首" } },
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    segments.forEachIndexed { at, (_, value, color) ->
                        Box(
                            Modifier
                                .weight((value.toFloat() / total).coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(
                                    color,
                                    RoundedCornerShape(
                                        topStart = if (at == 0) 7.dp else 3.dp,
                                        bottomStart = if (at == 0) 7.dp else 3.dp,
                                        topEnd = if (at == segments.lastIndex) 7.dp else 3.dp,
                                        bottomEnd = if (at == segments.lastIndex) 7.dp else 3.dp,
                                    ),
                                ),
                        )
                    }
                }
                FlowRow(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    segments.forEach { (label, value, color) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(10.dp).background(color, CircleShape))
                            Text("$label $value", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (scan.error.isNotBlank() && !scan.running) {
                Text(
                    "上次扫描出错：${scan.error}",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            }
            if (index.missingCount > 0 && !scan.running) {
                TextButton(
                    onClick = onCleanMissing,
                    modifier = Modifier.padding(top = 4.dp).tvFocusRing(RoundedCornerShape(20.dp)),
                ) {
                    Text("清理 ${index.missingCount} 首不在了的文件")
                }
            }
        }
    }
}

/** 权限和文件夹两条路，空状态和设置页共用。 */
internal class LocalAccess(
    val hasPermission: Boolean,
    val denied: Boolean,
    val canPickFolder: Boolean,
    val requestPermission: () -> Unit,
    val pickFolder: () -> Unit,
)

@Composable
internal fun rememberLocalAccess(library: LocalLibrary, prefs: LocalMusicSettings): LocalAccess {
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val canPickFolder = rememberCanPickFolder()
    var granted by remember { mutableStateOf(library.hasAudioPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val changed = ok != granted
        granted = ok
        // 拒绝也要记下来（空状态换成“改为选择文件夹”）；允许时回到前台那次检查可能已经处理过，别重复扫描
        if (changed || !ok) library.onPermissionChanged(ok)
    }
    // 权限状态不能只记在界面里：用户可能从应用设置里打开或收回，回到前台时重新查一次
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = library.hasAudioPermission()
        if (now != granted) {
            granted = now
            library.onPermissionChanged(now)
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                if (!library.addFolder(uri)) snackbar.showSnackbar("这个文件夹读不了，换一个试试")
            }
        }
    }
    return LocalAccess(
        hasPermission = granted,
        denied = prefs.permissionDenied && !granted,
        canPickFolder = canPickFolder,
        requestPermission = {
            val activity = context.findActivity()
            val permission = library.audioPermission()
            // 拒绝过两次后系统不再弹框，只能去应用设置里打开
            val blocked = prefs.permissionDenied && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            if (blocked) openAppSettings(context) else permissionLauncher.launch(permission)
        },
        pickFolder = {
            runCatching { folderLauncher.launch(null) }
                .onFailure { scope.launch { snackbar.showSnackbar("这台设备没有文件夹选择器") } }
        },
    )
}

@Composable
private fun LocalAccessEmpty(access: LocalAccess) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            !access.denied -> EmptyState(
                icon = Icons.Filled.AudioFile,
                title = "扫描手机里的歌",
                message = "只读取音乐文件的信息和标签，不改文件，也不会上传。封面和歌词会自动补上。",
                actionLabel = "允许读取音乐",
                onAction = access.requestPermission,
            )
            access.canPickFolder -> EmptyState(
                icon = Icons.Filled.Folder,
                title = "改为选择文件夹",
                message = "没有读取音乐的权限也能用：选一个放歌的文件夹，只读这个文件夹里的歌。",
                actionLabel = "改为选择文件夹",
                onAction = access.pickFolder,
            )
            else -> EmptyState(
                icon = Icons.Filled.AudioFile,
                title = "需要读取音乐的权限",
                message = "这台设备没有文件夹选择器，只能读系统媒体库。请允许读取音乐和音频。",
                actionLabel = "允许读取音乐",
                onAction = access.requestPermission,
            )
        }
        // 两条路都给：拒绝后仍能回头允许权限；没拒绝时也能只选文件夹
        if (access.denied) {
            TextButton(onClick = access.requestPermission, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                Text("仍然允许读取整个媒体库")
            }
        } else if (access.canPickFolder) {
            TextButton(onClick = access.pickFolder, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                Text("只选择文件夹")
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
