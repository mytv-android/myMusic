package com.mytv.mymusic.ui.library

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.DownloadRecord
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.ConfirmDialog
import com.mytv.mymusic.ui.EmptyState
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.TrackActionsSheet
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SkeletonRows
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.formatBytes
import com.mytv.mymusic.ui.relativeTime
import com.mytv.mymusic.ui.song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ======================= 喜欢 =======================

private enum class FavFilter(val label: String) { All("全部"), Downloaded("已下载"), Playable("可播放"), Recent("最近添加") }

@Composable
fun FavoritesScreen(
    state: MusicUiState,
    favorites: List<SavedSong>,
    downloads: List<DownloadRecord>,
    playlists: List<LocalPlaylistSummary>,
    actions: MusicActions,
    onBack: () -> Unit,
    onBrowse: () -> Unit,
) {
    val window = LocalAppWindow.current
    val undo = rememberUndo()
    val settled = rememberSettled()
    val playFocus = remember { FocusRequester() }
    var filter by rememberSaveable { mutableStateOf(FavFilter.All) }
    var actionSong by remember { mutableStateOf<Pair<SavedSong, Song>?>(null) }
    // 收藏列表的 payload 是 JSON；解码放后台，别在组合里逐条 parse
    val all by produceState(initialValue = emptyList<Pair<SavedSong, Song>>(), favorites) {
        value = withContext(Dispatchers.IO) { favorites.map { it to it.song() } }
    }
    val downloaded = remember(downloads) { downloads.filter { it.state == DOWNLOAD_DONE }.map { it.key }.toSet() }
    val shown = remember(all, filter, downloaded) {
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        when (filter) {
            FavFilter.All -> all
            FavFilter.Downloaded -> all.filter { it.second.key in downloaded }
            FavFilter.Playable -> all.filter { !it.second.isInvalid && !it.second.isVip }
            FavFilter.Recent -> all.filter { it.first.updatedAt >= weekAgo }
        }
    }
    val songs = remember(shown) { shown.map { it.second } }
    val unfavorite: (SavedSong, Song) -> Unit = { saved, song ->
        actions.favorite(song)
        undo.show("已取消收藏“${song.title}”") { actions.restoreSaved(listOf(saved)) }
    }
    TvDefaultFocus(playFocus, favorites.isEmpty())

    val head: LazyListScope.() -> Unit = {
        item(key = "hero") {
            FavoritesHero(all.size, favorites.maxOfOrNull { it.updatedAt } ?: 0L, all.count { it.second.key in downloaded })
        }
        item(key = "buttons") {
            PlayShuffleRow(
                enabled = songs.isNotEmpty(),
                onPlay = { actions.play(songs.first(), songs) },
                onShuffle = { val list = songs.shuffled(); actions.play(list.first(), list) },
                playModifier = Modifier.focusRequester(playFocus),
            )
        }
        item(key = "filters") {
            FilterRow(FavFilter.entries, filter, { it.label }) { filter = it }
        }
    }
    val list: LazyListScope.() -> Unit = {
        if (shown.isEmpty() && favorites.isNotEmpty()) {
            item(key = "noMatch") {
                Text(
                    "没有符合“${filter.label}”的歌曲",
                    Modifier.padding(vertical = 24.dp, horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(
            shown,
            key = { _, it -> "fav:${it.first.key}" },
            contentType = { _, _ -> "song" },
        ) { index, (saved, song) ->
            SongRow(
                title = song.title,
                subtitle = listOf(song.artist, sourceLabel(song)).filter { it.isNotBlank() }.joinToString(" · "),
                cover = song.cover,
                index = index,
                count = shown.size,
                onClick = { actions.play(song, songs) },
                playing = state.current?.key == song.key,
                trailing = {
                    IconButton(onClick = { unfavorite(saved, song) }, modifier = Modifier.tvFocusRing(CircleShape)) {
                        Icon(Icons.Filled.Favorite, "取消收藏“${song.title}”", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                onMore = { actionSong = saved to song },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        PageScaffold(title = "喜欢", subtitle = if (window.isTv) "${all.size} 首 · 只保存在本机" else "", onBack = onBack) { padding ->
            when {
                favorites.isEmpty() && !settled -> SkeletonRows(6, Modifier.padding(padding).padding(horizontal = window.gutter))
                favorites.isEmpty() -> CenteredEmpty(padding, playFocus) {
                    EmptyState(
                        icon = Icons.Filled.Favorite,
                        title = "还没有收藏的歌曲",
                        message = "在播放页点亮爱心，或在歌曲菜单里收藏。收藏只保存在本机。",
                        actionLabel = "去榜单看看",
                        onAction = onBrowse,
                    )
                }
                window.twoPane -> TwoColumns(padding, if (window.isTv) 300.dp else 320.dp, head, list)
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = padding.withGutter(window.gutter),
                    verticalArrangement = Arrangement.spacedBy(SegmentGap),
                ) {
                    head()
                    item(key = "gap") { Spacer(Modifier.height(8.dp)) }
                    list()
                }
            }
        }
    }

    actionSong?.let { (saved, song) ->
        TrackActionsSheet(
            song = song,
            favorite = true,
            playlists = playlists,
            onToggleFavorite = { unfavorite(saved, song); actionSong = null },
            onDownload = { actions.download(song) },
            onAddToPlaylist = { id -> actions.addSongToPlaylist(id, song) },
            onOpenSource = {
                actionSong = null
                actions.openSourceForSong(song, songs)
            },
            onRemoveFromPlaylist = null,
            onDismiss = { actionSong = null },
        )
    }
}

@Composable
private fun FavoritesHero(count: Int, latest: Long, downloaded: Int) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HeartBadge(72.dp)
            Column {
                Text("$count 首", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                Text(
                    listOfNotNull(
                        relativeTime(latest).takeIf { it.isNotBlank() }?.let { "最近一首：$it" },
                        "$downloaded 首已下载",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// ======================= 最近播放 =======================

@Composable
fun HistoryScreen(
    state: MusicUiState,
    history: List<SavedSong>,
    favorites: List<SavedSong>,
    playlists: List<LocalPlaylistSummary>,
    actions: MusicActions,
    onBack: () -> Unit,
) {
    val window = LocalAppWindow.current
    val undo = rememberUndo()
    val settled = rememberSettled()
    val firstRow = remember { FocusRequester() }
    var actionSong by remember { mutableStateOf<Song?>(null) }
    val songs by produceState(initialValue = emptyList<Pair<SavedSong, Song>>(), history) {
        value = withContext(Dispatchers.IO) { history.map { it to it.song() } }
    }
    val queue = remember(songs) { songs.map { it.second } }
    val groups = remember(songs) { songs.groupBy { dayLabel(it.first.updatedAt) }.toList() }
    TvDefaultFocus(firstRow, history.isEmpty())

    fun LazyListScope.group(label: String, items: List<Pair<SavedSong, Song>>, focusFirst: Boolean) {
        item(key = "day:$label") { DayLabel(label) }
        itemsIndexed(
            items,
            key = { _, it -> "hist:${it.first.key}" },
            contentType = { _, _ -> "song" },
        ) { index, (saved, song) ->
            SongRow(
                title = song.title,
                subtitle = song.artist.ifBlank { song.album },
                cover = song.cover,
                index = index,
                count = items.size,
                onClick = { actions.play(song, queue) },
                modifier = if (focusFirst && index == 0) Modifier.focusRequester(firstRow) else Modifier,
                playing = state.current?.key == song.key,
                trailing = {
                    Text(
                        timeLabel(saved.updatedAt),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onMore = { actionSong = song },
            )
        }
    }
    val note: LazyListScope.() -> Unit = {
        item(key = "note") {
            Text(
                "只保存在本机，不会上传或同步。",
                Modifier.padding(start = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        PageScaffold(
            title = "最近播放",
            subtitle = if (window.isTv) "只保存在本机" else "",
            onBack = onBack,
            actions = {
                if (history.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            val snapshot = history
                            actions.clearPlayHistory()
                            undo.show("已清除 ${snapshot.size} 条播放记录") { actions.restoreSaved(snapshot) }
                        },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                    ) { Text("清除") }
                }
            },
        ) { padding ->
            when {
                history.isEmpty() && !settled -> SkeletonRows(6, Modifier.padding(padding).padding(horizontal = window.gutter))
                history.isEmpty() -> CenteredEmpty(padding) {
                    EmptyState(
                        icon = Icons.Filled.History,
                        title = "还没有播放记录",
                        message = "播放过的歌曲会按日期保存在本机，不会上传到云端。",
                    )
                }
                // 两栏：今天（或最近的一天）在左，更早的在右
                window.twoPane -> TwoColumns(
                    padding,
                    if (window.isTv) 420.dp else 400.dp,
                    left = {
                        note()
                        groups.firstOrNull()?.let { (label, items) -> group(label, items, focusFirst = true) }
                    },
                    right = {
                        if (groups.size <= 1) {
                            item(key = "older") { DayLabel("更早") }
                            item(key = "olderEmpty") {
                                Text(
                                    "更早的记录会显示在这里。",
                                    Modifier.padding(start = 4.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        groups.drop(1).forEach { (label, items) -> group(label, items, focusFirst = false) }
                    },
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = padding.withGutter(window.gutter),
                    verticalArrangement = Arrangement.spacedBy(SegmentGap),
                ) {
                    note()
                    groups.forEachIndexed { index, (label, items) -> group(label, items, focusFirst = index == 0) }
                }
            }
        }
    }

    actionSong?.let { song ->
        TrackActionsSheet(
            song = song,
            favorite = favorites.any { it.key == song.key },
            playlists = playlists,
            onToggleFavorite = { actions.favorite(song) },
            onDownload = { actions.download(song) },
            onAddToPlaylist = { id -> actions.addSongToPlaylist(id, song) },
            onOpenSource = {
                actionSong = null
                actions.openSourceForSong(song, queue)
            },
            onRemoveFromPlaylist = null,
            onDismiss = { actionSong = null },
        )
    }
}

// ======================= 下载 =======================

@Composable
fun DownloadsScreen(
    state: MusicUiState,
    downloads: List<DownloadRecord>,
    cacheBytes: Long,
    actions: MusicActions,
    onBack: () -> Unit,
) {
    val window = LocalAppWindow.current
    val context = LocalContext.current
    val settled = rememberSettled()
    val firstRow = remember { FocusRequester() }
    var deleting by remember { mutableStateOf<DownloadRecord?>(null) }
    // 文件大小、是否存在、剩余空间都要走磁盘：放到 IO 线程，别在组合里同步取
    val items by produceState(initialValue = emptyList<DownloadItem>(), downloads) {
        value = withContext(Dispatchers.IO) {
            downloads.map { record ->
                val file = File(record.path)
                DownloadItem(record, record.song(), file.length(), record.state == DOWNLOAD_DONE && !file.exists())
            }
        }
    }
    val done = items.filter { it.record.state == DOWNLOAD_DONE }
    val active = items.filter { it.record.state != DOWNLOAD_DONE }
    val freeBytes by produceState(initialValue = 0L, downloads) {
        value = withContext(Dispatchers.IO) {
            runCatching { (context.getExternalFilesDir("music") ?: context.filesDir).usableSpace }.getOrDefault(0L)
        }
    }
    val doneBytes = done.sumOf { it.size }
    TvDefaultFocus(firstRow, done.isEmpty())

    val summary: LazyListScope.() -> Unit = {
        item(key = "usage") { StorageCard(done.size, doneBytes, cacheBytes, freeBytes) }
        if (active.isNotEmpty()) {
            item(key = "activeHead") { DayLabel("正在下载") }
            itemsIndexed(active, key = { _, it -> "active:${it.record.key}" }, contentType = { _, _ -> "download" }) { index, item ->
                SongRow(
                    title = item.song.title,
                    subtitle = "${item.song.artist.ifBlank { "未知歌手" }} · 正在下载",
                    cover = item.song.cover,
                    index = index,
                    count = active.size,
                    onClick = {},
                    trailing = { CircularProgressIndicator(Modifier.padding(end = 4.dp).size(24.dp), strokeWidth = 3.dp) },
                )
            }
        }
    }
    val doneList: LazyListScope.() -> Unit = {
        item(key = "doneHead") { DayLabel("已下载") }
        if (done.isEmpty()) {
            item(key = "doneEmpty") {
                Text(
                    "下载完成的歌曲会出现在这里。",
                    Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(done, key = { _, it -> "done:${it.record.key}" }, contentType = { _, _ -> "download" }) { index, item ->
            val detail = if (item.missing) "文件已不存在" else listOf(item.song.ext.uppercase(), formatBytes(item.size))
                .filter { it.isNotBlank() }.joinToString(" · ")
            SongRow(
                title = item.song.title,
                subtitle = listOf(item.song.artist, detail).filter { it.isNotBlank() }.joinToString(" · "),
                cover = item.song.cover,
                index = index,
                count = done.size,
                onClick = { actions.playDownload(item.record) },
                // 电视：菜单键删除文件；其余设备用行尾的删除按钮
                modifier = Modifier
                    .then(if (index == 0) Modifier.focusRequester(firstRow) else Modifier)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                            deleting = item.record
                            true
                        } else {
                            false
                        }
                    },
                playing = state.current?.key == item.song.key,
                trailing = {
                    IconButton(onClick = { deleting = item.record }, modifier = Modifier.tvFocusRing(CircleShape)) {
                        Icon(Icons.Outlined.Delete, "删除“${item.song.title}”的下载文件", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
    }

    PageScaffold(title = "下载", subtitle = if (window.isTv) "菜单键删除文件" else "", onBack = onBack) { padding ->
        when {
            downloads.isEmpty() && !settled -> SkeletonRows(6, Modifier.padding(padding).padding(horizontal = window.gutter))
            downloads.isEmpty() -> CenteredEmpty(padding) {
                EmptyState(
                    icon = Icons.Filled.Download,
                    title = "还没有下载",
                    message = "在歌曲菜单里选择下载。下载的歌曲会优先作为本地音源，没网也能播放。",
                )
            }
            window.twoPane -> TwoColumns(padding, if (window.isTv) 340.dp else 360.dp, summary, doneList)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = padding.withGutter(window.gutter),
                verticalArrangement = Arrangement.spacedBy(SegmentGap),
            ) {
                summary()
                doneList()
            }
        }
    }

    deleting?.let { record ->
        ConfirmDialog(
            title = "删除下载文件",
            message = "会删除本机上的音频文件，不影响收藏和歌单。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = { actions.deleteDownload(record) },
            onDismiss = { deleting = null },
        )
    }
}

private class DownloadItem(val record: DownloadRecord, val song: Song, val size: Long, val missing: Boolean)

/** 占用条：下载、播放缓存、剩余空间三段，图例用文字标出数值。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageCard(count: Int, downloadBytes: Long, cacheBytes: Long, freeBytes: Long) {
    val scheme = MaterialTheme.colorScheme
    val segments = listOf(
        Triple("下载", downloadBytes, scheme.primary),
        Triple("缓存", cacheBytes, scheme.tertiary),
        Triple("可用", freeBytes, scheme.surfaceContainerHighest),
    )
    val total = segments.sumOf { it.second }.coerceAtLeast(1L)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("$count 首 · ${formatBytes(downloadBytes)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(
                "下载的歌曲会优先作为本地音源，没网也能播放。",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .height(14.dp)
                    .semantics {
                        contentDescription = segments.joinToString("，") { "${it.first} ${formatBytes(it.second)}" }
                    },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                segments.forEachIndexed { index, (_, bytes, color) ->
                    val weight = (bytes.toFloat() / total).coerceAtLeast(0.01f)
                    Box(
                        Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .background(
                                color,
                                RoundedCornerShape(
                                    topStart = if (index == 0) 7.dp else 3.dp,
                                    bottomStart = if (index == 0) 7.dp else 3.dp,
                                    topEnd = if (index == segments.lastIndex) 7.dp else 3.dp,
                                    bottomEnd = if (index == segments.lastIndex) 7.dp else 3.dp,
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
                segments.forEach { (label, bytes, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Text("$label ${formatBytes(bytes)}", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ======================= 共用 =======================

/** 列表 + 详情的两栏：左栏固定宽度，右栏占满；两栏各自滚动。 */
@Composable
internal fun TwoColumns(
    padding: PaddingValues,
    leftWidth: Dp,
    left: LazyListScope.() -> Unit,
    right: LazyListScope.() -> Unit,
) {
    val window = LocalAppWindow.current
    val vertical = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
    Row(
        Modifier.fillMaxSize().padding(horizontal = window.gutter),
        horizontalArrangement = Arrangement.spacedBy(if (window.isTv) 32.dp else 24.dp),
    ) {
        LazyColumn(
            Modifier.width(leftWidth).fillMaxHeight(),
            contentPadding = vertical,
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
            content = left,
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            contentPadding = vertical,
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
            content = right,
        )
    }
}

@Composable
internal fun CenteredEmpty(padding: PaddingValues, focus: FocusRequester? = null, content: @Composable () -> Unit) {
    // focus 挂在容器上：电视 requestFocus 时会落到里面第一个可聚焦的按钮
    Box(
        Modifier.fillMaxSize().padding(padding).then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
        contentAlignment = if (LocalAppWindow.current.isPhone) Alignment.TopCenter else Alignment.Center,
    ) {
        content()
    }
}

/** 日期 / 小节分隔：比 SectionLabel 轻，用在列表中间。 */
@Composable
internal fun DayLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 播放 + 随机播放并排；电视默认焦点在“播放”。 */
@Composable
internal fun PlayShuffleRow(
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    playModifier: Modifier = Modifier,
) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onPlay,
            enabled = enabled,
            modifier = playModifier.weight(1f).heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("播放")
        }
        FilledTonalButton(
            onClick = onShuffle,
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
        ) {
            Icon(Icons.Filled.Shuffle, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("随机播放")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> FilterRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            FilterChip(
                selected = on,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                leadingIcon = if (on) ({ Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                // 单选筛选，FilterChip 默认读成“复选框”
                modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(8.dp)).semantics { role = Role.RadioButton },
            )
        }
    }
}

private fun sourceLabel(song: Song): String = when {
    song.isInvalid -> "无可用音源"
    song.isVip -> "需要会员"
    else -> ""
}
