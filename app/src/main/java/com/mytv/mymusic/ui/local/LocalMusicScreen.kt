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
import androidx.compose.foundation.layout.PaddingValues
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
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.local.LocalUploadRules
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.data.local.LocalCopyKind
import com.mytv.mymusic.data.local.LocalCopyRules
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
import com.mytv.mymusic.ui.components.PlatformBadge
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

/** 本地音乐页的筛选：全部、没匹配、没上传（哪个云端都还没有能播的一份）、按文件夹分组。 */
internal enum class LocalFilter(val label: String) { All("全部"), Unmatched("没匹配"), NotUploaded("没上传"), Folders("文件夹") }

/**
 * 本地音乐页，「本机 / 云端」两个视图用连接按钮组切换（设计稿第 ②③ 张）。
 *
 * 本机视图结构照「下载」页：顶上一张占用卡片（分段条：已上传 / 已匹配 / 没匹配），再往下是播放全部、
 * “上传…”、筛选、正在上传的那一段和歌曲列表。第一次进来没有权限也没有文件夹时是空状态，
 * 主按钮“允许读取音乐”，拒绝后换成“改为选择文件夹”（系统文件夹选择器，不要权限）。
 *
 * 云端视图：网易云盘卡片（容量、这台手机上没有文件的几首），WebDAV 和 QQ 云盘各一行，下面是
 * “网易云盘里，本机没有的”。
 */
@Composable
fun LocalMusicScreen(
    state: MusicUiState,
    playlists: List<LocalPlaylistSummary>,
    actions: MusicActions,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccounts: () -> Unit = {},
    onConnectWebDav: () -> Unit = {},
    onOpenCloudList: (CloudProvider) -> Unit = {},
) {
    val library = rememberLocalLibrary() ?: return
    val uploads = rememberLocalUploads() ?: return
    val window = LocalAppWindow.current
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val index by library.index.collectAsStateWithLifecycle()
    val scan by library.scan.collectAsStateWithLifecycle()
    val matching by library.matching.collectAsStateWithLifecycle()
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val uploadState by uploads.state.collectAsStateWithLifecycle()
    val access = rememberLocalAccess(library, prefs)

    var view by rememberSaveable { mutableStateOf(LocalView.Device) }
    var filter by rememberSaveable { mutableStateOf(LocalFilter.All) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var infoFor by remember { mutableStateOf<String?>(null) }
    var rematchFor by remember { mutableStateOf<String?>(null) }
    var pickerSongs by remember { mutableStateOf<List<Song>?>(null) }
    // 文件不在、也没有云端副本和平台版本的歌：点了打开“放不了”面板（第 ⑧ 张）
    val unplayable = rememberLocalUnplayableHost()
    val selection = remember { SelectionState() }
    SelectionPredictiveBack(selection)
    val firstFocus = remember { FocusRequester() }

    // 打开页面时：上次扫描超过 10 分钟就增量扫一遍（没变的文件不会再打开）
    LaunchedEffect(Unit) { library.scanIfStale() }

    val shown = remember(index, filter, query) { filterItems(index, filter, query) }
    val songs = remember(shown) { shown.map { it.song } }
    val playable = remember(shown) { shown.filter { it.onDevice }.map { it.song } }
    // “上传…”：这一屏里这台设备上有文件、哪个云端都还没有能播的一份的
    val uploadable = remember(shown) {
        shown.filter { it.onDevice && !LocalUploadRules.hasAnyReadyCopy(index.remotes[it.localId].orEmpty()) }.map { it.localId }
    }
    val empty = index.items.isEmpty()
    val sourcesReady = library.canScan(prefs)
    TvDefaultFocus(firstFocus, empty to view)
    // 只在 Wi-Fi 下上传时，“等 Wi-Fi”那句要知道现在是不是移动网络；进度一变重查一次就够
    val metered = remember(uploadState.running, uploadState.pendingCount) { LocalUploadNetwork.metered(context) }

    // 云端视图
    val netease = rememberCloudList(CloudProvider.NeteaseCloud, view == LocalView.Cloud && CloudProvider.NeteaseCloud in index.clouds)
    val links = remember(index) { CloudLinks(index) }
    val entryActions = rememberCloudEntryActions(library, actions)
    val nickname = rememberNeteaseNickname()
    val webDav = rememberWebDavInfo()

    val toggle: LazyListScope.() -> Unit = {
        item(key = "view", contentType = "toggle") {
            LocalViewToggle(
                view,
                { view = it },
                Modifier.padding(bottom = 8.dp).then(if (view == LocalView.Cloud) Modifier.focusRequester(firstFocus) else Modifier),
            )
        }
    }
    val cloudHeader: LazyListScope.() -> Unit = {
        toggle()
        localCloudHeader(
            index = index,
            netease = netease,
            neteaseName = nickname,
            webDavLabel = webDav?.hostLabel,
            onOpenAccounts = onOpenAccounts,
            onConnectWebDav = onConnectWebDav,
            onOpenList = onOpenCloudList,
        )
    }
    val cloudList: LazyListScope.() -> Unit = {
        localCloudNeteaseList(
            index = index,
            netease = netease,
            links = links,
            playing = state.current?.key,
            onPlay = { entry, localId -> entryActions?.play(entry, localId, index) },
            onDelete = { entry, localId -> entryActions?.confirm = Triple(entry, localId, netease) },
            onLoadMore = { (context.applicationContext as? com.mytv.mymusic.MyMusicApp)?.let { scope.launch { netease.load(it, reset = false) } } },
        )
    }

    val header: LazyListScope.() -> Unit = {
        toggle()
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
                    onUpload = {
                        val picked = shown.filter { it.song.key in selection.selected }
                        val ids = picked.filter { it.onDevice }.map { it.localId }
                        if (ids.isEmpty()) {
                            scope.launch { snackbar.showSnackbar("选中的歌这台设备上都没有文件，传不了") }
                        } else {
                            uploads.openSheet(ids)
                            selection.exit()
                        }
                    },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayAllSplitButton(
                        onPlayAll = { playable.firstOrNull()?.let { actions.play(it, playable) } },
                        modifier = if (view == LocalView.Device) Modifier.focusRequester(firstFocus) else Modifier,
                        onShuffle = {
                            val list = playable.shuffled()
                            list.firstOrNull()?.let { actions.play(it, list) }
                        },
                    )
                    // 设计稿第 ② 张：播放全部旁边的“上传…”，传这一屏里还没上传的
                    if (uploadable.isNotEmpty()) {
                        TextButton(
                            onClick = { uploads.openSheet(uploadable) },
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                        ) { Text("上传…") }
                    }
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
        localUploadSection(uploadState, index, prefs.uploadWifiOnly, metered, uploads)
        if (shown.isEmpty()) {
            item(key = "none", contentType = "empty") {
                Text(
                    when {
                        query.isNotBlank() -> "没有找到“$query”。"
                        filter == LocalFilter.Unmatched -> "所有歌都匹配上了。"
                        filter == LocalFilter.NotUploaded -> "所有歌都上传过了。"
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
                        when {
                            item.onDevice -> actions.play(item.song, playable)
                            // 文件不在了，但云端副本或平台版本能放：只放这一首
                            !index.copyState(item.localId).blocked -> actions.play(item.song, listOf(item.song))
                            else -> unplayable.intercept(item.song, index.copyState(item.localId))
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
        // 本机视图没有歌时（没权限、没扫到）也能切到云端：空状态上面留着切换按钮
        val emptyWithToggle: @Composable (@Composable () -> Unit) -> Unit = { content ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = window.gutter)) {
                LocalViewToggle(view, { view = it }, Modifier.padding(top = 8.dp, bottom = 8.dp))
                Box(Modifier.weight(1f)) { CenteredEmpty(PaddingValues(0.dp), firstFocus) { content() } }
            }
        }
        when {
            view == LocalView.Cloud && window.twoPane -> TwoColumns(padding, if (window.isTv) 360.dp else 380.dp, cloudHeader, cloudList)
            view == LocalView.Cloud -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = padding.withGutter(window.gutter),
                verticalArrangement = Arrangement.spacedBy(SegmentGap),
            ) {
                cloudHeader()
                cloudList()
            }
            empty && !sourcesReady -> emptyWithToggle { LocalAccessEmpty(access) }
            empty && !index.loaded -> Box(Modifier.padding(padding))
            empty && !scan.running && scan.error.isEmpty() && prefs.lastScanAt > 0 -> emptyWithToggle {
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
                // 先收起歌曲信息再选歌单：加完的提示（只在本机的歌带“上传”）在底部，被这张面板盖住就看不到了
                onAddToPlaylist = {
                    infoFor = null
                    pickerSongs = listOf(item.song)
                },
                onPlayNext = {
                    actions.playSongNext(item.song)
                    infoFor = null
                },
                onDismiss = { infoFor = null },
                cloudCopies = localCloudCopiesSection(
                    id,
                    index.remotes[id].orEmpty(),
                    library,
                    // 这台设备上有文件才传得了；先收起歌曲信息，上传面板不叠在它上面
                    onUpload = if (item.onDevice) ({ provider, force ->
                        infoFor = null
                        uploads.openSheet(listOf(id), force, provider)
                    }) else null,
                ),
            )
        }
    }
    LocalUnplayableSheets(
        host = unplayable,
        removeLabel = "从本地音乐移除",
        removeSummary = "只清这台设备上的记录，歌单里的这首不受影响",
        onRemove = { song -> library.launch { forget(song.id) } },
    )
    rematchFor?.let { id ->
        val item = index.byId[id]
        if (item == null) {
            rematchFor = null
        } else {
            LocalRematchSheet(item = item, library = library, onDismiss = { rematchFor = null })
        }
    }
    entryActions?.let { CloudDeleteConfirm(it, index) }
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
            (filter != LocalFilter.NotUploaded || !LocalUploadRules.hasAnyReadyCopy(index.remotes[item.localId].orEmpty())) &&
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
    val copy = index.copyState(item.localId)
    val subtitle = listOf(
        song.artist,
        when {
            // 文件不在时写现在会放哪份（云端副本照常写歌手）或为什么放不了
            !item.onDevice && copy.kind != LocalCopyKind.Cloud -> localSubtitle(song, copy)
            window.isPhone && song.duration > 0 -> formatDuration(song.duration)
            else -> ""
        },
    ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    SongRow(
        title = song.title,
        subtitle = subtitle,
        cover = song.cover,
        index = position,
        count = count,
        onClick = { if (picking) selection.toggle(song.key) else onPlay() },
        playing = playing,
        container = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow,
        dimmed = copy.gone,
        badges = {
            LocalSongBadge(copy)
            // 已经传到云端的歌在本机行里也标出来（设计稿第 ② 张：本机 + 网易云盘）
            if (item.onDevice) {
                index.remotes[item.localId].orEmpty()
                    .filter { it.ready }
                    .mapNotNull { it.cloudProvider }
                    .sortedBy { LocalCopyRules.CLOUD_ORDER.indexOf(it) }
                    .forEach { provider -> PlatformBadge(provider.label, Availability.Playable) }
            }
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
 * 顶上的占用卡片：总数和大小，分段条按已上传 / 已匹配（只匹配没上传）/ 没匹配 / 等待匹配分（设计稿第 ② 张）；
 * 扫描时换成进度。有“文件不在了”的歌时给一个清理入口——只清本地音乐里的记录，歌单不受影响。
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
    // 四类互不重叠：传上去了的先算，剩下的再按匹配分
    val uploaded = index.items.filter { LocalUploadRules.isUploaded(index.remotes[it.localId].orEmpty()) }.map { it.localId }.toSet()
    val rest = index.items.filter { it.localId !in uploaded }
    val matched = rest.count { it.row.matched }
    val unmatched = rest.count { index.isUnmatched(it) }
    val pending = (rest.size - matched - unmatched).coerceAtLeast(0)
    val segments = listOf(
        Triple("已上传", uploaded.size, scheme.primary),
        Triple("已匹配", matched, scheme.tertiary),
        Triple("没匹配", unmatched, scheme.surfaceContainerHighest),
        Triple(if (matching.running) "正在匹配" else "等待匹配", pending, scheme.secondary),
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
                message = "只读取音乐文件的信息和标签，不改文件，也不会自己上传。封面和歌词会自动补上。",
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
