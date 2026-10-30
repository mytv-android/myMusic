package com.mytv.mymusic.ui.library

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.CollectionView
import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.sync.SyncUiState
import com.mytv.mymusic.ui.ConfirmDialog
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.EmptyState
import com.mytv.mymusic.ui.ErrorBlock
import com.mytv.mymusic.ui.InputDialog
import com.mytv.mymusic.ui.LibrarySearchUiState
import com.mytv.mymusic.ui.LocalPlaylistDetail
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.PlaylistPickerSheet
import com.mytv.mymusic.ui.TrackActionsSheet
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.AppLoading
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalOverlayPadding
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.PlayAllSplitButton
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SelectionBar
import com.mytv.mymusic.ui.components.SelectionPredictiveBack
import com.mytv.mymusic.ui.components.SelectionState
import com.mytv.mymusic.ui.components.selectionBackPreview
import com.mytv.mymusic.ui.components.SkeletonRows
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.distinctKeys
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.formatDuration
import com.mytv.mymusic.ui.poster.CollectionPosterOps
import com.mytv.mymusic.ui.poster.CollectionPosterView
import com.mytv.mymusic.ui.poster.LOCAL_PLAYLIST_LABEL
import com.mytv.mymusic.ui.poster.PosterExtraAction
import com.mytv.mymusic.ui.providerName
import com.mytv.mymusic.ui.relativeTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/** detailFlow 发出 null 表示歌单不存在；还没发出时是加载中。 */
private class Loaded(val detail: LocalPlaylistDetail?)

/**
 * 自建歌单详情 + 编辑。编辑是同一页的模式，不另开页面：
 * 手机是单栏；≥840dp 平板和电视左栏放拼贴和操作、右栏放曲目；embedded 时嵌在“我的”的右侧详情窗格里。
 * collection_view 选了海报时显示海报视图（CollectionPosterView）；编辑（排序、移除、添加）只在列表视图里，
 * 海报视图里点“编辑”就进列表的编辑模式，点“完成”回到海报。
 */
@Composable
fun LocalPlaylistScreen(
    playlistId: String,
    detailFlow: Flow<LocalPlaylistDetail?>,
    state: MusicUiState,
    sync: SyncUiState,
    librarySearch: LibrarySearchUiState,
    favorites: List<SavedSong>,
    playlists: List<LocalPlaylistSummary>,
    actions: MusicActions,
    onBack: () -> Unit,
    onOpenSync: () -> Unit,
    embedded: Boolean = false,
) {
    val window = LocalAppWindow.current
    val load by remember(detailFlow) { detailFlow.map { Loaded(it) } }.collectAsStateWithLifecycle(initialValue = null)
    val detail = load?.detail
    val playlist = detail?.playlist
    val entries = detail?.entries.orEmpty()
    val songs = detail?.songs.orEmpty()
    val conflict = sync.conflicts.any { it.playlistId == playlistId }
    val undo = rememberUndo()
    val playFocus = remember { FocusRequester() }
    val editFocus = remember { FocusRequester() }

    var editing by rememberSaveable(playlistId) { mutableStateOf(false) }
    var nameDraft by rememberSaveable(playlistId, playlist?.name) { mutableStateOf(playlist?.name.orEmpty()) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var actionEntry by remember { mutableStateOf<Pair<Song, PlaylistEntryEntity>?>(null) }
    val selection = remember { SelectionState() }
    var pickerOpen by remember { mutableStateOf(false) }
    SelectionPredictiveBack(selection)

    // 确认删除会返回，软删除写库后 deletedAt 变化又会返回一次（退场动画期间页面仍在组合中），只能返回一次
    var left by remember { mutableStateOf(false) }
    val leave = {
        if (!left) {
            left = true
            onBack()
        }
    }
    LaunchedEffect(playlist?.deletedAt) {
        if (playlist?.deletedAt != null) leave()
    }
    val finishEdit = {
        val name = nameDraft.trim()
        if (playlist != null && name.isNotBlank() && name != playlist.name) actions.renamePlaylist(playlistId, name)
        // 名称清空后没有保存，草稿恢复成原名，下次编辑不会是空的
        if (name.isBlank()) nameDraft = playlist?.name.orEmpty()
        editing = false
    }
    // 编辑模式返回：手势过程中编辑界面跟手淡出上移，松手才退出编辑
    var editingBack by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = editing) { events ->
        try {
            events.collect { event -> editingBack = event.progress }
            finishEdit()
        } catch (_: CancellationException) {
        } finally {
            editingBack = 0f
        }
    }
    // 编辑中总是列表：海报视图里点“编辑”只打开编辑模式，不改记住的视图
    val posterView = state.collectionView == CollectionView.Poster && !editing
    val showPoster: () -> Unit = remember(actions, selection) {
        {
            selection.exit()
            actions.setCollectionView(CollectionView.Poster)
        }
    }
    // 电视：普通模式焦点在“播放全部”，编辑模式在第一行的“下移”（不默认落在移除上）；海报视图自己管焦点，切回列表时重新落一次
    TvDefaultFocus(if (editing) editFocus else playFocus, Triple(playlist?.id, editing, posterView))

    val remove: (PlaylistEntryEntity, Song) -> Unit = { entry, song ->
        actions.removeEntry(playlistId, entry.position)
        undo.show("已移除“${song.title}”") { actions.restoreEntry(entry) }
    }
    // 多选：移除一次重排，撤销按原位置逐条插回
    val removeSelected: () -> Unit = {
        val doomed = entries.filter { it.songKey in selection.selected }
        if (doomed.isNotEmpty()) {
            actions.removeEntries(playlistId, doomed.map { it.position })
            undo.show("已移除 ${doomed.size} 首") {
                doomed.sortedBy { it.position }.forEach { actions.restoreEntry(it) }
            }
            selection.exit()
        }
    }
    val rowContainer = if (embedded) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val coverUrls = remember(songs) { songs.map { it.cover }.filter { it.isNotBlank() }.take(4) }
    // 以 songKey 作行 key，移动后焦点跟着这首歌走；重复的旧数据由 distinctKeys 追加序号
    val rowKeys = remember(entries) { distinctKeys(entries) { it.songKey } }
    val meta = remember(songs) { playlistMeta(songs) }

    val menu = PlaylistMenu(
        onEdit = { editing = true },
        onRename = { renameOpen = true },
        onDelete = { deleteOpen = true },
    )

    // ---- 列表片段：普通模式 / 编辑模式 ----
    val trackList: LazyListScope.() -> Unit = {
        if (editing) {
            itemsIndexed(
                entries,
                key = { i, _ -> "edit:${rowKeys[i]}" },
                contentType = { _, _ -> "edit" },
            ) { index, entry ->
                val song = detail?.songByPosition?.get(entry.position) ?: Song()
                EditTrackRow(
                    song = song,
                    index = index,
                    count = entries.size,
                    container = rowContainer,
                    moveFocus = if (index == 0) editFocus else null,
                    onUp = { actions.moveEntry(playlistId, entry.position, -1) },
                    onDown = { actions.moveEntry(playlistId, entry.position, 1) },
                    onRemove = { remove(entry, song) },
                )
            }
        } else {
            itemsIndexed(
                entries,
                key = { i, _ -> "row:${rowKeys[i]}" },
                contentType = { _, _ -> "song" },
            ) { index, entry ->
                val song = detail?.songByPosition?.get(entry.position) ?: Song()
                val picking = selection.active
                val isSelected = picking && song.key in selection.selected
                val rowMore: (() -> Unit)? = if (picking) {
                    null
                } else {
                    { actionEntry = song to entry }
                }
                val rowLongPress: (() -> Unit)? = if (picking) {
                    null
                } else {
                    { selection.enter(song.key) }
                }
                val selectTrailing: (@Composable RowScope.() -> Unit)? = if (picking) {
                    {
                        Icon(
                            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "取消选择“${song.title}”" else "选择“${song.title}”",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    null
                }
                val durationTrailing: (@Composable RowScope.() -> Unit)? = if (song.duration > 0 && !window.isPhone) {
                    {
                        Text(formatDuration(song.duration), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    null
                }
                SongRow(
                    title = song.title,
                    subtitle = song.subtitle,
                    cover = song.cover,
                    index = index,
                    count = entries.size,
                    onClick = { if (picking) selection.toggle(song.key) else actions.play(song, songs) },
                    playing = state.current?.key == song.key,
                    container = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else rowContainer,
                    trailing = selectTrailing ?: durationTrailing,
                    onMore = rowMore,
                    onLongClick = rowLongPress,
                )
            }
        }
        if (entries.isEmpty() && playlist != null) {
            item(key = "empty", contentType = "empty") {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = "歌单还是空的",
                    message = "点“添加”搜索歌曲，或在任意歌曲的菜单里选择“加到歌单”。",
                    actionLabel = "添加歌曲",
                    onAction = { addOpen = true },
                )
            }
        }
    }
    val headerExtras: LazyListScope.() -> Unit = {
        if (conflict) {
            item(key = "conflict", contentType = "card") {
                ListRow(
                    title = "这个歌单有同步冲突",
                    index = 0,
                    count = 1,
                    onClick = onOpenSync,
                    icon = Icons.Filled.SyncProblem,
                    iconTone = IconTone.Error,
                    summary = "本机和云端都改过，处理前不会上传",
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        if (editing) {
            item(key = "editNote", contentType = "note") {
                Text(
                    if (window.isTv) "用行尾的上移、下移按钮调整顺序，左侧按钮移除。修改自动保存到本机。"
                    else "用上下箭头调整顺序（键盘 Alt + ↑↓），点左侧按钮移除。修改自动保存到本机。",
                    Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val nameField: @Composable () -> Unit = {
        OutlinedTextField(
            value = nameDraft,
            onValueChange = { nameDraft = it },
            label = { Text("歌单名称") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val name = nameDraft.trim()
                if (playlist != null && name.isNotBlank() && name != playlist.name) actions.renamePlaylist(playlistId, name)
            }),
            modifier = Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(4.dp)),
        )
    }
    val deleteButton: @Composable () -> Unit = {
        TextButton(
            onClick = { deleteOpen = true },
            modifier = Modifier.padding(top = 12.dp).tvFocusRing(RoundedCornerShape(20.dp)),
        ) {
            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text("删除歌单", color = MaterialTheme.colorScheme.error)
        }
    }
    val playActions: @Composable (Boolean) -> Unit = { showEdit ->
        if (selection.active) {
            SelectionBar(
                count = selection.selected.size,
                allSelected = songs.isNotEmpty() && selection.selected.containsAll(songs.map { it.key }),
                onToggleAll = { selection.toggleAll(songs.map { it.key }) },
                onPlay = {
                    val picked = songs.filter { it.key in selection.selected }
                    if (picked.isNotEmpty()) actions.play(picked.first(), picked)
                },
                onAddTo = { pickerOpen = true },
                onExit = { selection.exit() },
                onRemove = removeSelected,
                modifier = Modifier.selectionBackPreview(selection),
            )
        } else {
            PlaylistActionRow(
                enabled = songs.isNotEmpty(),
                onPlayAll = { actions.play(songs.first(), songs) },
                onShuffle = { val list = songs.shuffled(); actions.play(list.first(), list) },
                onAdd = { addOpen = true },
                menu = if (showEdit) menu else null,
                playFocus = playFocus,
                onMultiSelect = if (songs.isNotEmpty()) ({ selection.start() }) else null,
                // 嵌在“我的”里没有顶栏，入口放操作行；手机和双栏的左栏操作行已经排满，入口在顶栏
                onPosterView = if (embedded) showPoster else null,
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = 1f - 0.7f * editingBack
                translationY = -16.dp.toPx() * editingBack
            },
    ) {
        when {
            posterView && (load == null || playlist != null) -> LocalPlaylistPoster(
                state = state,
                actions = actions,
                detail = detail,
                cover = coverUrls.firstOrNull().orEmpty(),
                onEdit = { editing = true },
                onAddTracks = { addOpen = true },
                onSongActions = { song, entry -> actionEntry = song to entry },
                onBack = onBack,
                resetKey = playlistId,
                holdAwake = actionEntry != null || addOpen || renameOpen || deleteOpen,
            )
            load == null -> PlaylistLoading(embedded)
            playlist == null -> MissingPlaylist(onBack)
            embedded -> {
                // 嵌在“我的”右栏：没有顶栏，头部是拼贴 + 标题
                val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 28.dp,
                        end = 28.dp,
                        top = top + 28.dp,
                        bottom = LocalOverlayPadding.current.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(SegmentGap),
                ) {
                    item(key = "head", contentType = "header") {
                        if (editing) {
                            Column(Modifier.padding(bottom = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("编辑歌单", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                                    DoneButton(finishEdit)
                                }
                                Spacer(Modifier.size(12.dp))
                                nameField()
                            }
                        } else {
                            PlaylistHeader(playlist.name, meta, coverUrls, 180.dp, playlist.dirty, sync, conflict, showTitle = true)
                            Spacer(Modifier.size(16.dp))
                            playActions(true)
                            Spacer(Modifier.size(12.dp))
                        }
                    }
                    headerExtras()
                    trackList()
                    if (editing) item(key = "delete", contentType = "delete") { deleteButton() }
                }
            }
            window.twoPane -> PageScaffold(
                title = if (editing) "编辑歌单" else playlist.name,
                subtitle = if (editing) "" else meta,
                onBack = if (editing) finishEdit else onBack,
                actions = {
                    if (editing) {
                        DoneButton(finishEdit)
                    } else {
                        IconButton(onClick = showPoster, modifier = Modifier.tvFocusRing(CircleShape)) {
                            Icon(Icons.Filled.GridView, "海报视图")
                        }
                    }
                },
            ) { padding ->
                TwoColumns(
                    padding,
                    if (window.isTv) 320.dp else 340.dp,
                    left = {
                        item(key = "left", contentType = "left") {
                            Column {
                                if (editing) {
                                    nameField()
                                    Text(
                                        "名称在这里修改，曲目在右栏调整。",
                                        Modifier.padding(start = 4.dp, top = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    deleteButton()
                                } else {
                                    PlaylistMosaic(coverUrls, playlist.name, if (window.isTv) 220.dp else 240.dp, 28.dp)
                                    Spacer(Modifier.size(16.dp))
                                    PlaylistSyncTag(playlist.dirty, sync, conflict, long = true)
                                    Text(
                                        "${relativeTime(playlist.updatedAt).ifBlank { "刚刚" }}编辑",
                                        Modifier.padding(top = 8.dp, bottom = 16.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    playActions(true)
                                }
                            }
                        }
                    },
                    right = {
                        headerExtras()
                        trackList()
                    },
                )
            }
            else -> PageScaffold(
                title = if (editing) "编辑歌单" else playlist.name,
                subtitle = if (editing) "" else meta,
                onBack = if (editing) finishEdit else onBack,
                actions = {
                    if (editing) {
                        DoneButton(finishEdit, Modifier.padding(end = 8.dp))
                    } else {
                        IconButton(onClick = showPoster, modifier = Modifier.tvFocusRing(CircleShape)) {
                            Icon(Icons.Filled.GridView, "海报视图")
                        }
                        IconButton(onClick = { editing = true }, modifier = Modifier.tvFocusRing(CircleShape)) {
                            Icon(Icons.Filled.Edit, "编辑歌单")
                        }
                        PlaylistMoreMenu(menu.copy(onEdit = null))
                    }
                },
            ) { padding ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = padding.withGutter(window.gutter),
                    verticalArrangement = Arrangement.spacedBy(SegmentGap),
                ) {
                    item(key = "head", contentType = "header") {
                        if (editing) {
                            Column(Modifier.padding(bottom = 4.dp)) { nameField() }
                        } else {
                            PlaylistHeader(playlist.name, meta, coverUrls, 132.dp, playlist.dirty, sync, conflict, showTitle = false)
                            Spacer(Modifier.size(16.dp))
                            playActions(false)
                            Spacer(Modifier.size(12.dp))
                        }
                    }
                    headerExtras()
                    trackList()
                    if (editing) item(key = "delete", contentType = "delete") { deleteButton() }
                }
            }
        }
    }

    actionEntry?.let { (song, entry) ->
        TrackActionsSheet(
            song = song,
            favorite = favorites.any { it.key == song.key },
            playlists = playlists.filterNot { it.id == playlistId },
            onToggleFavorite = { actions.favorite(song) },
            onDownload = { actions.download(song) },
            onAddToPlaylist = { id -> actions.addSongToPlaylist(id, song) },
            onOpenSource = {
                actionEntry = null
                actions.openSourceForSong(song, songs)
            },
            onRemoveFromPlaylist = {
                remove(entry, song)
                actionEntry = null
            },
            onDismiss = { actionEntry = null },
        )
    }
    if (pickerOpen) {
        PlaylistPickerSheet(
            playlists = playlists.filterNot { it.id == playlistId },
            onPick = { id ->
                actions.addSongsToPlaylist(id, songs.filter { it.key in selection.selected })
                selection.exit()
            },
            onCreate = { name ->
                actions.addSongsToNewPlaylist(name, songs.filter { it.key in selection.selected })
                selection.exit()
            },
            onDismiss = { pickerOpen = false },
        )
    }
    if (addOpen) {
        AddTracksSheet(
            search = librarySearch,
            existing = remember(entries) { entries.map { it.songKey }.toSet() },
            onSearch = actions::searchLibrary,
            onAdd = { song -> actions.addSongToPlaylist(playlistId, song) },
            onDismiss = {
                addOpen = false
                actions.clearLibrarySearch()
            },
        )
    }
    if (renameOpen && playlist != null) {
        InputDialog(
            title = "重命名歌单",
            label = "歌单名称",
            initial = playlist.name,
            confirmLabel = "保存",
            onConfirm = { name -> actions.renamePlaylist(playlistId, name) },
            onDismiss = { renameOpen = false },
        )
    }
    if (deleteOpen && playlist != null) {
        ConfirmDialog(
            title = "删除“${playlist.name}”？",
            message = "歌单和其中的 ${entries.size} 首歌会从本机移除。已连接同步时，下一次同步会把删除带到云端。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                editing = false
                actions.deletePlaylist(playlistId)
                leave()
            },
            onDismiss = { deleteOpen = false },
        )
    }
}

private data class PlaylistMenu(
    val onEdit: (() -> Unit)?,
    val onRename: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * 自建歌单的海报视图（[detail] 为 null 时是读取中，墙上是骨架格）。工具栏第三格是“编辑”：进列表的编辑模式。
 * 播放都不弹出播放页；长按格子、大卡的“更多”打开和列表同一个 TrackActionsSheet（带“从歌单移除”）。
 */
@Composable
private fun LocalPlaylistPoster(
    state: MusicUiState,
    actions: MusicActions,
    detail: LocalPlaylistDetail?,
    cover: String,
    onEdit: () -> Unit,
    onAddTracks: () -> Unit,
    onSongActions: (Song, PlaylistEntryEntity) -> Unit,
    onBack: () -> Unit,
    resetKey: Any?,
    holdAwake: Boolean,
) {
    val songs = detail?.songs.orEmpty()
    // 解不出来的条目不在 songs 里：按同样的过滤把条目和 songs 一一对齐，下标才对得上
    val songEntries = remember(detail) {
        detail?.let { d -> d.entries.filter { it.position in d.songByPosition } }.orEmpty()
    }
    val ops = remember(actions, songs, songEntries, onSongActions, onBack) {
        CollectionPosterOps(
            playAll = { if (songs.isNotEmpty()) actions.play(songs.first(), songs, openPlayer = false) },
            shuffle = {
                val list = songs.shuffled()
                if (list.isNotEmpty()) actions.play(list.first(), list, openPlayer = false)
            },
            playFrom = { index -> songs.getOrNull(index)?.let { actions.play(it, songs, openPlayer = false) } },
            playNext = { index -> songs.getOrNull(index)?.let(actions::playSongNext) },
            songActions = { index ->
                val song = songs.getOrNull(index)
                val entry = songEntries.getOrNull(index)
                if (song != null && entry != null) onSongActions(song, entry)
            },
            showList = { actions.setCollectionView(CollectionView.List) },
            back = onBack,
        )
    }
    val edit = remember(onEdit) { PosterExtraAction(Icons.Filled.Edit, "编辑歌单", onEdit) }
    val providers = state.providers
    val sourceName: (Song) -> String = remember(providers) { { song -> providerName(providers, song.source) } }
    // 空歌单沿用列表的提示；“添加歌曲”是搜索面板，不用切回列表
    val message: (@Composable () -> Unit)? = if (detail != null && songs.isEmpty()) ({
        EmptyState(
            icon = Icons.Filled.Search,
            title = "歌单还是空的",
            message = "点“添加歌曲”搜索歌曲，或在任意歌曲的菜单里选择“加到歌单”。",
            actionLabel = "添加歌曲",
            onAction = onAddTracks,
        )
    }) else null
    CollectionPosterView(
        state = state,
        actions = actions,
        title = detail?.playlist?.name.orEmpty(),
        kindLabel = LOCAL_PLAYLIST_LABEL,
        sourceLabel = "",
        cover = cover,
        songs = songs,
        loading = detail == null,
        playLabel = "播放全部",
        ops = ops,
        sourceName = sourceName,
        extra = edit,
        message = message,
        resetKey = resetKey,
        holdAwake = holdAwake,
    )
}

@Composable
private fun PlaylistHeader(
    name: String,
    meta: String,
    covers: List<String>,
    coverSize: Dp,
    dirty: Boolean,
    sync: SyncUiState,
    conflict: Boolean,
    showTitle: Boolean,
) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PlaylistMosaic(covers, name, coverSize, 28.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showTitle) {
                Text(
                    name,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PlaylistSyncTag(dirty, sync, conflict, long = true)
        }
    }
}

/**
 * 播放全部（Split button，展开随机播放）+ 添加；大屏再加编辑和更多。多选入口在最右侧。
 */
@Composable
private fun PlaylistActionRow(
    enabled: Boolean,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAdd: () -> Unit,
    menu: PlaylistMenu?,
    playFocus: FocusRequester,
    onMultiSelect: (() -> Unit)? = null,
    onPosterView: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.focusRequester(playFocus)) {
            if (enabled) {
                PlayAllSplitButton(onPlayAll = onPlayAll, onShuffle = onShuffle)
            } else {
                Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp))) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加歌曲")
                }
            }
        }
        if (enabled) {
            FilledTonalButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp))) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加")
            }
        }
        if (enabled && onMultiSelect != null) {
            FilledTonalIconButton(onClick = onMultiSelect, modifier = Modifier.tvFocusRing(CircleShape)) {
                Icon(Icons.Filled.DoneAll, contentDescription = "批量选择歌曲")
            }
        }
        if (onPosterView != null) {
            FilledTonalIconButton(onClick = onPosterView, modifier = Modifier.tvFocusRing(CircleShape)) {
                Icon(Icons.Filled.GridView, contentDescription = "海报视图")
            }
        }
        if (menu != null) {
            Spacer(Modifier.weight(1f))
            menu.onEdit?.let { edit ->
                IconButton(onClick = edit, modifier = Modifier.tvFocusRing(CircleShape)) { Icon(Icons.Filled.Edit, "编辑歌单") }
            }
            PlaylistMoreMenu(menu)
        }
    }
}

@Composable
private fun PlaylistMoreMenu(menu: PlaylistMenu) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.tvFocusRing(CircleShape)) {
            Icon(Icons.Filled.MoreVert, "歌单更多操作")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { open = false; menu.onRename() },
            )
            DropdownMenuItem(
                text = { Text("删除歌单", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; menu.onDelete() },
            )
        }
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 40.dp).tvFocusRing(RoundedCornerShape(20.dp))) {
        Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("完成")
    }
}

/**
 * 编辑行：左侧移除，右侧上移 / 下移。电视和键盘都能操作，不依赖拖动和长按；
 * 平板外接键盘时 Alt + ↑↓ 直接移动当前行。
 */
@Composable
private fun EditTrackRow(
    song: Song,
    index: Int,
    count: Int,
    container: Color,
    moveFocus: FocusRequester?,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = segmentShape(index, count)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(container)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isAltPressed) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { if (index > 0) onUp(); true }
                    Key.DirectionDown -> { if (index < count - 1) onDown(); true }
                    else -> false
                }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onRemove, modifier = Modifier.tvFocusRing(CircleShape)) {
            Icon(Icons.Filled.RemoveCircleOutline, "移除“${song.title}”", tint = MaterialTheme.colorScheme.error)
        }
        Cover(song.cover, song.title, 48.dp, corner = 12.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onUp, enabled = index > 0, modifier = Modifier.tvFocusRing(CircleShape)) {
            Icon(Icons.Filled.ArrowUpward, "上移“${song.title}”")
        }
        IconButton(
            onClick = onDown,
            enabled = index < count - 1,
            modifier = (if (moveFocus != null) Modifier.focusRequester(moveFocus) else Modifier).tvFocusRing(CircleShape),
        ) {
            Icon(Icons.Filled.ArrowDownward, "下移“${song.title}”")
        }
    }
}

/** 添加歌曲：在默认浏览来源中搜索。手机是底部面板，平板 / 电视是侧边面板。 */
@Composable
internal fun AddTracksSheet(
    search: LibrarySearchUiState,
    existing: Set<String>,
    onSearch: (String) -> Unit,
    onAdd: (Song) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(search.query) }
    val field = remember { FocusRequester() }
    // 平台搜索结果同一 key 可能重复，按出现顺序加序号；输入框留在惰性列表外层
    val resultKeys = remember(search.results) { distinctKeys(search.results) { it.key } }
    TvDefaultFocus(field)
    AdaptiveSheet(onDismiss = onDismiss, title = "添加歌曲", subtitle = "在默认浏览来源中搜索") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(field),
                singleLine = true,
                placeholder = { Text("歌曲名或歌手") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    TextButton(onClick = { onSearch(query) }, enabled = query.isNotBlank()) { Text("搜索") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            )
            // 有界高度让 LazyColumn 可以在 ModalBottomSheet / 侧边面板里测量；
            // 不要依赖 intrinsic height（惰性布局不支持 intrinsic 测量）
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(SegmentGap),
            ) {
                when {
                    search.loading -> item(key = "loading", contentType = "loading") {
                        Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                            AppLoading(text = "正在搜索…")
                            SkeletonRows(3)
                        }
                    }
                    search.error.isNotBlank() -> item(key = "error", contentType = "error") {
                        ErrorBlock(search.error) { onSearch(query) }
                    }
                    search.query.isNotBlank() && search.results.isEmpty() -> item(key = "empty", contentType = "empty") {
                        EmptyState(
                            icon = Icons.Filled.SearchOff,
                            title = "没有找到",
                            message = "试试歌曲名加歌手。",
                        )
                    }
                }
                itemsIndexed(
                    search.results,
                    key = { index, _ -> "result:${resultKeys[index]}" },
                    contentType = { _, _ -> "result" },
                ) { index, song ->
                    val added = song.key in existing
                    SongRow(
                        title = song.title,
                        subtitle = song.subtitle,
                        cover = song.cover,
                        index = index,
                        count = search.results.size,
                        onClick = { if (!added) onAdd(song) },
                        trailing = {
                            if (added) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("已添加", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                }
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.PlaylistAdd,
                                    "添加“${song.title}”",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistLoading(embedded: Boolean) {
    val window = LocalAppWindow.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        Modifier.fillMaxSize().padding(start = if (embedded) 28.dp else window.gutter, end = if (embedded) 28.dp else window.gutter, top = top + 72.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HeaderSkeleton(if (embedded) 180.dp else 132.dp)
        SkeletonRows(6)
    }
}

@Composable
private fun MissingPlaylist(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            title = "找不到这个歌单",
            message = "它可能已被删除，或者在同步时被云端版本替换。",
            actionLabel = "返回",
            onAction = onBack,
        )
    }
}

private fun playlistMeta(songs: List<Song>): String {
    val seconds = songs.sumOf { it.duration.coerceAtLeast(0) }
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val length = when {
        seconds <= 0 -> ""
        hours > 0 -> "$hours 小时 $minutes 分"
        else -> "$minutes 分钟"
    }
    return listOf("${songs.size} 首", length).filter { it.isNotBlank() }.joinToString(" · ")
}
