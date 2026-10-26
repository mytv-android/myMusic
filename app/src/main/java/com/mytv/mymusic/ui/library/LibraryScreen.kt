package com.mytv.mymusic.ui.library

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.DownloadRecord
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.sync.SyncStatus
import com.mytv.mymusic.data.sync.SyncStrategy
import com.mytv.mymusic.data.sync.SyncUiState
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.EmptyState
import com.mytv.mymusic.ui.InputDialog
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalOverlayPadding
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.Skeleton
import com.mytv.mymusic.ui.components.SkeletonRows
import com.mytv.mymusic.ui.components.tvCardFocus
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.formatBytes
import com.mytv.mymusic.ui.relativeTime
import com.mytv.mymusic.ui.song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** “我的”页面的跳转。设置入口只在手机上出现（平板和电视在导航栏底部）。 */
class LibraryNav(
    val openSettings: () -> Unit,
    val openSync: () -> Unit,
    val openPlaylist: (String) -> Unit,
    val openFavorites: () -> Unit,
    val openHistory: () -> Unit,
    val openDownloads: () -> Unit,
    val openCharts: () -> Unit,
)

/** 新建菜单的三项：新建歌单、导入歌单链接、从文件导入。三端只是外形不同。 */
private class NewMenu(val onCreate: () -> Unit, val onLink: () -> Unit, val onFile: () -> Unit)

@Composable
fun LibraryScreen(
    state: MusicUiState,
    favorites: List<SavedSong>,
    history: List<SavedSong>,
    downloads: List<DownloadRecord>,
    playlists: List<LocalPlaylistSummary>,
    covers: Map<String, List<String>>,
    sync: SyncUiState,
    actions: MusicActions,
    nav: LibraryNav,
    detailPane: @Composable (playlistId: String, onClosed: () -> Unit) -> Unit,
) {
    val window = LocalAppWindow.current
    var createOpen by remember { mutableStateOf(false) }
    var linkOpen by remember { mutableStateOf(false) }
    val importFile = rememberImportLauncher(actions)
    val menu = remember(importFile) { NewMenu({ createOpen = true }, { linkOpen = true }, importFile) }
    val stats = rememberLibraryStats(favorites, history, downloads)
    LaunchedEffect(Unit) { actions.syncIfStaleOnScreen() }

    when {
        window.isTv -> TvLibrary(state, history, playlists, covers, sync, stats, actions, nav, menu)
        window.isTablet && window.twoPane -> TabletLibrary(playlists, covers, sync, stats, favorites, actions, nav, menu, detailPane)
        else -> PhoneLibrary(playlists, covers, sync, stats, favorites, actions, nav, menu)
    }

    if (createOpen) {
        InputDialog(
            title = "新建歌单",
            label = "歌单名称",
            confirmLabel = "创建",
            onConfirm = actions::createPlaylist,
            onDismiss = { createOpen = false },
        )
    }
    if (linkOpen) {
        LinkImportDialog(
            isLink = actions::isLink,
            onImport = actions::importLink,
            onDismiss = { linkOpen = false },
        )
    }
}

// ---------------- 手机（以及 600–839dp 的平板） ----------------

@Composable
private fun PhoneLibrary(
    playlists: List<LocalPlaylistSummary>,
    covers: Map<String, List<String>>,
    sync: SyncUiState,
    stats: LibraryStats,
    favorites: List<SavedSong>,
    actions: MusicActions,
    nav: LibraryNav,
    menu: NewMenu,
) {
    val window = LocalAppWindow.current
    val phone = window.isPhone
    var fabOpen by rememberSaveable { mutableStateOf(false) }
    val settled = rememberSettled()
    val conflictIds = sync.conflicts.map { it.playlistId }.toSet()
    Box(Modifier.fillMaxSize()) {
        PageScaffold(
            title = "我的",
            subtitle = syncHeadline(sync),
            actions = {
                SyncCloudButton(sync, nav.openSync)
                if (phone) {
                    IconButton(onClick = nav.openSettings, modifier = Modifier.tvFocusRing(CircleShape)) {
                        Icon(Icons.Filled.Settings, "设置")
                    }
                } else {
                    NewPlaylistSplitButton(menu, Modifier.padding(end = 8.dp))
                }
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = padding.withGutter(horizontal = window.gutter, extraBottom = if (phone) 88.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(SegmentGap),
            ) {
                item(key = "bento", contentType = "bento") {
                    LibraryBento(
                        stats = stats,
                        onFavorites = nav.openFavorites,
                        // 解码和播放都交给 VM 的后台任务，回调里不做全量 JSON 解析
                        onPlayFavorites = favorites.takeIf { it.isNotEmpty() }?.let { list ->
                            { actions.playSavedSongs(list) }
                        },
                        onHistory = nav.openHistory,
                        onDownloads = nav.openDownloads,
                        tileHeight = if (phone) 100.dp else 112.dp,
                    )
                }
                item(key = "sync", contentType = "card") { SyncStatusCard(sync, nav.openSync, Modifier.padding(top = 12.dp)) }
                item(key = "plHead", contentType = "section") {
                    SectionLabel("自建歌单", trailing = { CountTag("${playlists.size} 个") })
                }
                playlistItems(playlists, covers, sync, conflictIds, settled, null, onOpen = nav.openPlaylist, onCreate = menu.onCreate)
            }
        }
        if (phone) {
            if (fabOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { fabOpen = false },
                )
            }
            LibraryFabMenu(fabOpen, { fabOpen = it }, menu)
        }
    }
}

/** 自建歌单列表：手机、平板共用。selectedId 非空时用于平板的列表 + 详情。 */
private fun androidx.compose.foundation.lazy.LazyListScope.playlistItems(
    playlists: List<LocalPlaylistSummary>,
    covers: Map<String, List<String>>,
    sync: SyncUiState,
    conflictIds: Set<String>,
    settled: Boolean,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
) {
    if (playlists.isEmpty()) {
        item(key = "plEmpty", contentType = "empty") {
            if (!settled) {
                SkeletonRows(3)
            } else {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = "还没有自建歌单",
                    message = "新建歌单后可以添加歌曲、调整顺序，并同步到 GitHub Gist 或 Gitee。",
                    actionLabel = "新建歌单",
                    onAction = onCreate,
                )
            }
        }
        return
    }
    itemsIndexed(
        playlists,
        key = { _, it -> "pl:${it.id}" },
        contentType = { _, _ -> "playlist" },
    ) { index, playlist ->
        ListRow(
            title = playlist.name,
            index = index,
            count = playlists.size,
            onClick = { onOpen(playlist.id) },
            selected = playlist.id == selectedId,
            summary = "${playlist.trackCount} 首 · ${relativeTime(playlist.updatedAt).ifBlank { "刚创建" }}",
            leading = { PlaylistMosaic(covers[playlist.id].orEmpty(), playlist.name, 52.dp, 14.dp) },
            trailing = { PlaylistSyncTag(playlist, sync, playlist.id in conflictIds) },
        )
    }
}

// ---------------- 平板 ≥840dp：列表 + 详情 ----------------

@Composable
private fun TabletLibrary(
    playlists: List<LocalPlaylistSummary>,
    covers: Map<String, List<String>>,
    sync: SyncUiState,
    stats: LibraryStats,
    favorites: List<SavedSong>,
    actions: MusicActions,
    nav: LibraryNav,
    menu: NewMenu,
    detailPane: @Composable (playlistId: String, onClosed: () -> Unit) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedId?.takeIf { id -> playlists.any { it.id == id } } ?: playlists.firstOrNull()?.id
    // 默认选中的第一项要记住：歌单按修改时间排序，同步改动别的歌单时右栏不该跟着跳。
    // 这只是同步的状态修正，没有挂起或绘制时序依赖，用带 key 的 SideEffect。
    SideEffect(selected) { if (selected != null && selected != selectedId) selectedId = selected }
    val settled = rememberSettled()
    val conflictIds = sync.conflicts.map { it.playlistId }.toSet()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalOverlayPadding.current.calculateBottomPadding()
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        LazyColumn(
            Modifier.width(380.dp).fillMaxHeight(),
            contentPadding = PaddingValues(start = 24.dp, end = 16.dp, top = top + 28.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            item(key = "head", contentType = "header") {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("我的", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                        Text(
                            syncHeadline(sync),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SyncCloudButton(sync, nav.openSync)
                }
            }
            item(key = "bento", contentType = "bento") {
                LibraryBento(
                    stats = stats,
                    onFavorites = nav.openFavorites,
                    onPlayFavorites = favorites.takeIf { it.isNotEmpty() }?.let { list ->
                        { actions.playSavedSongs(list) }
                    },
                    onHistory = nav.openHistory,
                    onDownloads = nav.openDownloads,
                    tileHeight = 84.dp,
                    compact = true,
                )
            }
            item(key = "sync", contentType = "card") { SyncStatusCard(sync, nav.openSync, Modifier.padding(top = 12.dp)) }
            item(key = "plHead", contentType = "section") {
                SectionLabel("自建歌单", trailing = { NewPlaylistSplitButton(menu) })
            }
            playlistItems(playlists, covers, sync, conflictIds, settled, selected, onOpen = { selectedId = it }, onCreate = menu.onCreate)
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
        ) {
            if (selected != null) {
                androidx.compose.runtime.key(selected) {
                    detailPane(selected) { selectedId = null }
                }
            } else if (settled) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Filled.LibraryMusic,
                        title = "选择或新建一个歌单",
                        message = "歌单内容会显示在这里，不用跳页。",
                        actionLabel = "新建歌单",
                        onAction = menu.onCreate,
                    )
                }
            }
        }
    }
}

// ---------------- 电视：磁贴行 + 自建歌单行 + 最近播放行 ----------------

@Composable
private fun TvLibrary(
    state: MusicUiState,
    history: List<SavedSong>,
    playlists: List<LocalPlaylistSummary>,
    covers: Map<String, List<String>>,
    sync: SyncUiState,
    stats: LibraryStats,
    actions: MusicActions,
    nav: LibraryNav,
    menu: NewMenu,
) {
    val window = LocalAppWindow.current
    val first = remember { FocusRequester() }
    var newOpen by remember { mutableStateOf(false) }
    val conflictIds = sync.conflicts.map { it.playlistId }.toSet()
    // 最近播放的 payload 解码放在页面层：惰性项被回收再进入时不会重做
    val recent by produceState(initialValue = emptyList<Song>(), history) {
        value = withContext(Dispatchers.IO) { history.take(24).map { it.song() } }
    }
    TvDefaultFocus(first)
    PageScaffold(
        title = "我的",
        subtitle = syncHeadline(sync),
        actions = { SyncCloudButton(sync, nav.openSync) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "tiles", contentType = "bento") {
                Row(
                    Modifier.padding(horizontal = window.gutter, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    TvTile(
                        modifier = Modifier.width(280.dp).focusRequester(first),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = nav.openFavorites,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            HeartBadge(48.dp)
                            // 大数字和“首喜欢的歌”分开读会变成“0，首喜欢的歌”
                            Column(Modifier.clearAndSetSemantics { contentDescription = "喜欢的歌，${stats.favorites} 首" }) {
                                Text(stats.favorites.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                                Text("首喜欢的歌", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    TvTile(
                        modifier = Modifier.width(200.dp),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                        onClick = nav.openHistory,
                    ) {
                        Text("最近播放", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stats.historyLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                    TvTile(
                        modifier = Modifier.width(200.dp),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = nav.openDownloads,
                    ) {
                        Text("下载", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stats.downloadLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item(key = "playlists", contentType = "row") {
                TvRow("自建歌单") {
                    item(key = "new", contentType = "card") {
                        TvNewCard(onClick = { newOpen = true })
                    }
                    items(playlists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                        TvCard(
                            title = playlist.name,
                            onClick = { nav.openPlaylist(playlist.id) },
                            art = { PlaylistMosaic(covers[playlist.id].orEmpty(), playlist.name, 150.dp, 20.dp) },
                            footer = { PlaylistSyncTag(playlist, sync, playlist.id in conflictIds) },
                        )
                    }
                }
            }
            if (history.isNotEmpty()) {
                item(key = "recent", contentType = "row") {
                    TvRow("最近播放") {
                        items(recent, key = { "recent:${it.key}" }, contentType = { "song" }) { song ->
                            TvCard(
                                title = song.title,
                                subtitle = song.artist,
                                playing = state.current?.key == song.key,
                                onClick = { actions.play(song, recent) },
                                art = { Cover(song.cover, song.title, 150.dp, corner = 20.dp) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (newOpen) {
        val focus = remember { FocusRequester() }
        TvDefaultFocus(focus)
        AdaptiveSheet(onDismiss = { newOpen = false }, title = "新建", subtitle = "选择创建方式") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                val rows = listOf(
                    Triple(Icons.AutoMirrored.Filled.PlaylistAdd, "新建歌单", menu.onCreate),
                    Triple(Icons.Filled.Link, "导入歌单链接", menu.onLink),
                    Triple(Icons.Filled.FileOpen, "从文件导入", menu.onFile),
                )
                rows.forEachIndexed { index, (icon, label, action) ->
                    ListRow(
                        title = label,
                        index = index,
                        count = rows.size,
                        onClick = { newOpen = false; action() },
                        modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                        icon = icon,
                        showChevron = false,
                    )
                }
                Text(
                    "“从文件导入”读取在手机上导出的 JSON，与本机歌单合并。",
                    Modifier.padding(start = 4.dp, top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TvTile(
    modifier: Modifier,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 140.dp).tvCardFocus(shape),
        shape = shape,
        color = container,
        contentColor = content,
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            content = body,
        )
    }
}

@Composable
private fun TvRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val window = LocalAppWindow.current
    Column {
        Text(
            title,
            Modifier.padding(start = window.gutter, bottom = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = window.gutter, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

@Composable
private fun TvNewCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.width(150.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(150.dp).tvCardFocus(shape),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, "新建", Modifier.size(48.dp)) }
        }
        Text("新建", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TvCard(
    title: String,
    onClick: () -> Unit,
    art: @Composable () -> Unit,
    subtitle: String = "",
    playing: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.width(150.dp)) {
        Box(
            Modifier
                .size(150.dp)
                .tvCardFocus(shape)
                .clip(shape)
                // 标题在可点区域外面，读屏要靠这里读出是哪张卡片
                .semantics {
                    contentDescription = listOf(title, subtitle).filter { it.isNotBlank() }.joinToString("，")
                    if (playing) stateDescription = "正在播放"
                }
                .clickable(onClick = onClick),
        ) { art() }
        Text(
            title,
            Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (playing) FontWeight.Bold else FontWeight.SemiBold,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        footer?.let { Box(Modifier.padding(top = 6.dp)) { it() } }
    }
}

// ---------------- 磁贴 ----------------

internal class LibraryStats(
    val favorites: Int,
    val favoriteLatest: Long,
    val historyToday: Int,
    val historyTotal: Int,
    val downloadsDone: Int,
    val downloadsActive: Int,
    val downloadBytes: Long,
) {
    val historyLabel: String get() = when {
        historyToday > 0 -> "今天 $historyToday 首"
        historyTotal > 0 -> "共 $historyTotal 首"
        else -> "还没有记录"
    }
    val downloadLabel: String get() = when {
        downloadsActive > 0 -> "$downloadsActive 首下载中"
        downloadsDone > 0 -> "$downloadsDone 首 · ${formatBytes(downloadBytes)}"
        else -> "还没有下载"
    }
}

@Composable
internal fun rememberLibraryStats(
    favorites: List<SavedSong>,
    history: List<SavedSong>,
    downloads: List<DownloadRecord>,
): LibraryStats {
    // 文件大小要走磁盘；放在组合里同步做会让下载进度每次更新都卡主线程。
    // 只以“已完成记录的路径”为 key：其他字段变化不重读磁盘，完成状态变化才重读。
    val donePaths = remember(downloads) {
        downloads.filter { it.state == DOWNLOAD_DONE }.map { it.path }
    }
    val downloadBytes by produceState(initialValue = 0L, donePaths) {
        value = withContext(Dispatchers.IO) {
            donePaths.sumOf { path -> runCatching { File(path).length() }.getOrDefault(0L) }
        }
    }
    return remember(favorites, history, downloads, downloadBytes) {
        val today = startOfToday()
        val done = downloads.filter { it.state == DOWNLOAD_DONE }
        LibraryStats(
            favorites = favorites.size,
            favoriteLatest = favorites.maxOfOrNull { it.updatedAt } ?: 0L,
            historyToday = history.count { it.updatedAt >= today },
            historyTotal = history.size,
            downloadsDone = done.size,
            downloadsActive = downloads.size - done.size,
            downloadBytes = downloadBytes,
        )
    }
}

@Composable
private fun LibraryBento(
    stats: LibraryStats,
    onFavorites: () -> Unit,
    onPlayFavorites: (() -> Unit)?,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    tileHeight: Dp,
    compact: Boolean = false,
) {
    // 喜欢占左侧两行，最近和下载叠在右侧；高度用 heightIn，字号放大时磁贴跟着长高
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val shape = RoundedCornerShape(28.dp)
        Surface(
            onClick = onFavorites,
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight()
                .heightIn(min = tileHeight * 2 + 8.dp)
                .tvFocusRing(shape),
            shape = shape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    HeartBadge(if (compact) 44.dp else 56.dp)
                    // 大数字和“首喜欢的歌”分开读会变成“0，首喜欢的歌”
                    Column(
                        Modifier
                            .padding(end = if (onPlayFavorites != null) 44.dp else 0.dp)
                            .clearAndSetSemantics { contentDescription = "喜欢的歌，${stats.favorites} 首" },
                    ) {
                        Text(
                            stats.favorites.toString(),
                            style = if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text("首喜欢的歌", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (onPlayFavorites != null) {
                    FilledIconButton(
                        onClick = onPlayFavorites,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).tvFocusRing(CircleShape),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) { Icon(Icons.Filled.PlayArrow, "播放喜欢的歌") }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallTile(
                title = if (compact) "最近" else "最近播放",
                detail = stats.historyLabel,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = onHistory,
                modifier = Modifier.weight(1f).heightIn(min = tileHeight),
            )
            SmallTile(
                title = "下载",
                detail = stats.downloadLabel,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onDownloads,
                modifier = Modifier.weight(1f).heightIn(min = tileHeight),
            )
        }
    }
}

@Composable
private fun SmallTile(title: String, detail: String, container: Color, content: Color, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().tvFocusRing(shape),
        shape = shape,
        color = container,
        contentColor = content,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            // 0.8 透明度在 tertiary / secondaryContainer 上只有约 4:1，14sp 正文要 4.5:1
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = content)
        }
    }
}

/** 爱心放在 Cookie 形状里：形状只是装饰。 */
@Composable
internal fun HeartBadge(size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(MaterialShapes.Cookie9Sided.toShape())
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Favorite, null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

// ---------------- 同步状态 ----------------

internal fun syncHeadline(sync: SyncUiState): String {
    val account = sync.account ?: return "本机音乐库 · 未连接同步"
    return when {
        sync.status == SyncStatus.Syncing -> "正在同步到 ${sync.providerLabel}…"
        sync.conflicts.isNotEmpty() -> "${sync.providerLabel} · ${sync.conflicts.size} 个冲突待处理"
        sync.error.isNotBlank() -> "${sync.providerLabel} · 上次同步失败"
        account.lastSyncedAt <= 0 -> "已连接 ${sync.providerLabel} · 尚未同步"
        else -> "已同步到 ${sync.providerLabel} · ${relativeTime(account.lastSyncedAt)}"
    }
}

private fun syncIcon(sync: SyncUiState): ImageVector = when {
    sync.account == null -> Icons.Filled.CloudOff
    sync.status == SyncStatus.Syncing -> Icons.Filled.CloudSync
    sync.conflicts.isNotEmpty() || sync.error.isNotBlank() -> Icons.Filled.SyncProblem
    else -> Icons.Filled.CloudDone
}

/** 右上角的云朵：图标形状区分状态，冲突时再加一个小红点，读屏读出完整状态。 */
@Composable
private fun SyncCloudButton(sync: SyncUiState, onClick: () -> Unit) {
    val attention = sync.conflicts.isNotEmpty() || (sync.account != null && sync.error.isNotBlank())
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .tvFocusRing(CircleShape)
            .semantics { contentDescription = "音乐库同步：${syncHeadline(sync)}" },
    ) {
        BadgedBox(badge = { if (attention) Badge() }) {
            Icon(if (sync.account == null) Icons.Outlined.Cloud else syncIcon(sync), null)
        }
    }
}

/** 只在需要用户注意时出现：未连接、同步中、冲突、失败。同步正常时状态只写在标题下面。 */
@Composable
private fun SyncStatusCard(sync: SyncUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val (title, summary, tone) = when {
        sync.conflicts.isNotEmpty() -> Triple(
            "${sync.conflicts.size} 个歌单有冲突",
            "本机和云端都改过，处理前不会上传",
            IconTone.Error,
        )
        sync.account != null && sync.error.isNotBlank() -> Triple("同步失败", sync.error, IconTone.Error)
        sync.status == SyncStatus.Syncing -> Triple("正在同步…", sync.phase.ifBlank { sync.providerLabel }, IconTone.Primary)
        sync.account == null -> Triple("把音乐库同步到云端", "连接 GitHub Gist 或 Gitee，同步歌单、收藏和平台账号", IconTone.Secondary)
        else -> return
    }
    ListRow(
        title = title,
        index = 0,
        count = 1,
        onClick = onOpen,
        modifier = modifier,
        icon = if (sync.account == null) Icons.Filled.CloudUpload else syncIcon(sync),
        iconTone = tone,
        summary = summary,
    )
}

/** 歌单的同步状态：文字 + 图标，不只靠颜色。 */
@Composable
internal fun PlaylistSyncTag(playlist: LocalPlaylistSummary, sync: SyncUiState, conflict: Boolean) {
    PlaylistSyncTag(dirty = playlist.dirty, sync = sync, conflict = conflict)
}

@Composable
internal fun PlaylistSyncTag(dirty: Boolean, sync: SyncUiState, conflict: Boolean, long: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val (label, colors) = when {
        conflict -> "有冲突" to (scheme.errorContainer to scheme.onErrorContainer)
        sync.account == null -> "仅本机" to (scheme.surfaceContainerHighest to scheme.onSurfaceVariant)
        dirty -> "待同步" to (scheme.tertiaryContainer to scheme.onTertiaryContainer)
        else -> (if (long) "已同步到 ${sync.providerLabel}" else "已同步") to (scheme.secondaryContainer to scheme.onSecondaryContainer)
    }
    val icon = when {
        conflict -> Icons.Filled.SyncProblem
        sync.account == null -> Icons.Filled.CloudOff
        dirty -> Icons.Filled.CloudUpload
        else -> Icons.Filled.CloudDone
    }
    Tag(label, colors.first, colors.second, icon)
}

@Composable
internal fun Tag(text: String, container: Color, content: Color, icon: ImageVector? = null) {
    Row(
        Modifier
            .heightIn(min = 24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = content)
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = content, maxLines = 1)
    }
}

@Composable
internal fun CountTag(text: String) {
    Tag(text, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
}

// ---------------- 新建菜单 ----------------

/** 手机：FAB menu。打开时背景压暗，返回键收起。 */
@Composable
private fun BoxScope.LibraryFabMenu(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, menu: NewMenu) {
    BackHandler(expanded) { onExpandedChange(false) }
    val bottom = LocalOverlayPadding.current.calculateBottomPadding()
    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = bottom),
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = onExpandedChange,
                modifier = Modifier.semantics {
                    contentDescription = "新建"
                    stateDescription = if (expanded) "已展开" else "已收起"
                },
            ) {
                val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add } }
                Icon(icon, null, Modifier.animateIcon({ checkedProgress }))
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = { onExpandedChange(false); menu.onLink() },
            text = { Text("导入歌单链接") },
            icon = { Icon(Icons.Filled.Link, null) },
        )
        FloatingActionButtonMenuItem(
            onClick = { onExpandedChange(false); menu.onFile() },
            text = { Text("从文件导入") },
            icon = { Icon(Icons.Filled.FileOpen, null) },
        )
        FloatingActionButtonMenuItem(
            onClick = { onExpandedChange(false); menu.onCreate() },
            text = { Text("新建歌单") },
            icon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
        )
    }
}

/** 平板：FAB 菜单不和导航栏同屏，改成标题旁的 Split button。 */
@Composable
private fun NewPlaylistSplitButton(menu: NewMenu, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    SplitButton(
        modifier = modifier,
        leadingButton = {
            SplitButtonDefaults.LeadingButton(onClick = menu.onCreate, modifier = Modifier.tvFocusRing()) {
                Icon(Icons.Filled.Add, null, Modifier.size(SplitButtonDefaults.LeadingIconSize))
                Spacer(Modifier.width(8.dp))
                Text("新建歌单", maxLines = 1)
            }
        },
        trailingButton = {
            Box {
                SplitButtonDefaults.TrailingButton(
                    checked = expanded,
                    onCheckedChange = { expanded = it },
                    modifier = Modifier.tvFocusRing().semantics {
                        contentDescription = "更多新建方式"
                        stateDescription = if (expanded) "已展开" else "已收起"
                    },
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(SplitButtonDefaults.TrailingIconSize))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(20.dp)) {
                    DropdownMenuItem(
                        text = { Text("导入歌单链接") },
                        leadingIcon = { Icon(Icons.Filled.Link, null) },
                        onClick = { expanded = false; menu.onLink() },
                    )
                    DropdownMenuItem(
                        text = { Text("从文件导入") },
                        leadingIcon = { Icon(Icons.Filled.FileOpen, null) },
                        onClick = { expanded = false; menu.onFile() },
                    )
                }
            }
        },
    )
}

/** 导入歌单链接：识别后打开歌单详情，在那里可以“存到本地歌单”。 */
@Composable
private fun LinkImportDialog(isLink: (String) -> Boolean, onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val valid = value.isNotBlank() && isLink(value)
    val submit = {
        if (valid) {
            onImport(value.trim())
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入歌单链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "粘贴平台歌单或专辑的分享链接。识别后打开详情，点“存到本地歌单”保存。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("链接") },
                    singleLine = true,
                    isError = value.isNotBlank() && !valid,
                    supportingText = if (value.isNotBlank() && !valid) ({ Text("没有识别到平台链接") }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = valid) { Text("识别") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 从文件导入：系统文件选择器（SAF），与本机歌单合并。 */
@Composable
internal fun rememberImportLauncher(actions: MusicActions): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { actions.importLibrary(it, SyncStrategy.Merge) }
    }
    val onMissing = rememberMissingPicker()
    return remember(launcher, onMissing) {
        {
            try {
                launcher.launch(arrayOf("application/json", "text/plain"))
            } catch (error: ActivityNotFoundException) {
                onMissing()
            }
        }
    }
}

@Composable
internal fun rememberExportLauncher(actions: MusicActions): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(actions::exportLibrary)
    }
    val onMissing = rememberMissingPicker()
    return remember(launcher, onMissing) {
        {
            try {
                launcher.launch("mymusic-library.json")
            } catch (error: ActivityNotFoundException) {
                onMissing()
            }
        }
    }
}

/** 不少电视盒子没有系统文件选择器，launch 会抛 ActivityNotFoundException；用 Snackbar 说明而不是崩溃。 */
@Composable
private fun rememberMissingPicker(): () -> Unit {
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    return remember(snackbar, scope) {
        { scope.launch { snackbar.showSnackbar("这台设备没有系统文件选择器，无法选择文件") } }
    }
}

// ---------------- 封面拼贴 ----------------

/** 自建歌单封面：前 4 首歌的封面拼成 2×2；不够 4 张时用首字占位，一张都没有就是单个首字封面。 */
@Composable
internal fun PlaylistMosaic(covers: List<String>, name: String, size: Dp, corner: Dp) {
    if (covers.isEmpty()) {
        Cover("", name, size, corner = corner)
        return
    }
    val tile = (size - 1.dp) / 2
    Column(
        Modifier.size(size).clip(RoundedCornerShape(corner)),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                for (col in 0 until 2) {
                    val url = covers.getOrNull(row * 2 + col).orEmpty()
                    Cover(url, name, tile, corner = 0.dp, fontSize = tile.value * 0.4f)
                }
            }
        }
    }
}

// ---------------- 通用：撤销、加载、电视焦点 ----------------

/** 页面自己的撤销 Snackbar：外壳的 SnackbarHost 不对页面开放，这里就近显示在浮层上方。 */
@Stable
internal class UndoController(val host: SnackbarHostState, private val scope: CoroutineScope, private val long: Boolean) {
    fun show(message: String, undo: () -> Unit) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = "撤销",
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }
}

@Composable
internal fun rememberUndo(): UndoController {
    val host = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val tv = LocalAppWindow.current.isTv
    return remember(host, scope, tv) { UndoController(host, scope, tv) }
}

/** Room 的列表初始值是空列表；300ms 内先显示骨架，避免一闪而过的空状态。 */
@Composable
internal fun rememberSettled(delayMs: Long = 300): Boolean {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        settled = true
    }
    return settled
}

/** 电视：进入页面后把焦点放到默认元素上。等一帧布局，否则懒加载列表里的目标还没挂上。 */
@Composable
internal fun TvDefaultFocus(requester: FocusRequester, key: Any? = Unit) {
    if (!LocalAppWindow.current.isTv) return
    LaunchedEffect(key) {
        delay(120)
        runCatching { requester.requestFocus() }
    }
}

@Composable
internal fun HeaderSkeleton(cover: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Skeleton(Modifier.size(cover), RoundedCornerShape(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Skeleton(Modifier.fillMaxWidth(0.7f).height(24.dp), CircleShape)
            Skeleton(Modifier.fillMaxWidth(0.4f).height(14.dp), CircleShape)
        }
    }
}

internal fun PaddingValues.withGutter(horizontal: Dp, extraBottom: Dp = 0.dp): PaddingValues = PaddingValues(
    start = horizontal,
    end = horizontal,
    top = calculateTopPadding(),
    bottom = calculateBottomPadding() + extraBottom,
)

internal const val DOWNLOAD_DONE = "已完成"

internal fun startOfToday(): Long {
    val zone = ZoneId.systemDefault()
    return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** 单条记录的日期文案；批量分组请用 [prepareHistory]，那里 today 只算一次。 */
internal fun dayLabel(millis: Long): String =
    LibraryDates.dayLabel(millis, LibraryDates.today())

/** 时间标签使用线程安全的 DateTimeFormatter，可在后台与主线程共用。 */
internal fun timeLabel(millis: Long): String = LibraryDates.timeLabel(millis)
