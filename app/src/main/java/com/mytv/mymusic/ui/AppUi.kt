package com.mytv.mymusic.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.aggregate.CanonicalCollection
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.MatchStrictness
import com.mytv.mymusic.aggregate.PlaybackMode
import com.mytv.mymusic.aggregate.ProviderSearchState
import com.mytv.mymusic.aggregate.ProviderStatus
import com.mytv.mymusic.aggregate.SearchScope
import com.mytv.mymusic.aggregate.SearchStatus
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.aggregate.availabilityOf
import com.mytv.mymusic.data.DownloadRecord
import com.mytv.mymusic.data.LibrarySection
import com.mytv.mymusic.data.LocalPlaylist
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.SearchKind
import com.mytv.mymusic.data.Song

private enum class Destination(val label: String, val icon: ImageVector) { Home("首页", Icons.Default.Home), Search("搜索", Icons.Default.Search), Library("音乐库", Icons.AutoMirrored.Filled.QueueMusic), Settings("设置", Icons.Default.Settings) }

@Composable
fun MyMusicApp(
    state: MusicUiState,
    width: WindowWidthSizeClass,
    favorites: List<Song>,
    history: List<Song>,
    downloads: List<DownloadRecord>,
    playlists: List<LocalPlaylist>,
    actions: MusicActions,
) {
    var destination by remember { mutableStateOf(Destination.Home) }
    val snackbar = remember { SnackbarHostState() }
    val television = isTelevision()
    val rail = television || width != WindowWidthSizeClass.Compact

    LaunchedEffect(state.error) {
        val message = state.error
        if (message.isNotBlank()) {
            snackbar.showSnackbar(message)
            actions.clearError()
        }
    }
    LaunchedEffect(state.playbackNotice) {
        val message = state.playbackNotice
        if (message.isNotBlank()) {
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = if (state.deepLink.isNotBlank()) "在平台打开" else null,
            )
            if (result == SnackbarResult.ActionPerformed) actions.openDeepLink()
            actions.clearNotice()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = { if (!rail) BottomDestinationBar(destination) { destination = it }; PlayerBar(state, actions) }) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (event.key != Key.MediaPlayPause) return@onPreviewKeyEvent false
            actions.transport("play")
            true
        }) {
            if (rail) RailDestinationBar(destination, television) { destination = it }
            Box(Modifier.weight(1f).padding(if (television) 28.dp else 16.dp)) {
                when (destination) {
                    Destination.Home -> HomePage(state, television, actions)
                    Destination.Search -> SearchPage(state, television, actions)
                    Destination.Library -> LibraryPage(favorites, history, downloads, playlists, actions)
                    Destination.Settings -> SettingsPage(state, actions)
                }
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.TopEnd))
            }
        }
    }
    if (state.collectionOpen) CollectionPage(state, television, actions)
    if (state.playerOpen && state.current != null) PlayerPage(state, television, actions)
    if (state.sourcePickerTrack != null) SourcePickerDialog(state, actions)
    if (state.mergeTrack != null) MergeDialog(state, actions)
}

class MusicActions(private val model: MusicViewModel) {
    fun provider(id: String) = model.selectProvider(id)
    fun theme(value: String) = model.selectTheme(value)
    fun query(value: String) = model.query(value)
    fun searchKind(value: SearchKind) = model.searchKind(value)
    fun searchScope(value: SearchScope) = model.setSearchScope(value)
    fun search() = model.search()
    fun searchMore() = model.searchMore()
    fun category(id: String, page: Int = 1) = model.loadCategory(id, page)
    fun clearCategory() = model.clearCategory()
    fun refreshHome() = model.refreshHome()
    fun playlist(item: Playlist, album: Boolean) = model.openPlaylist(item, album)
    fun chart(item: com.mytv.mymusic.data.TopList) = model.openChart(item)
    fun play(song: Song, queue: List<Song>) = model.play(song, queue)
    fun playTrack(track: CanonicalTrack, queue: List<CanonicalTrack>) = model.playTrack(track, queue)
    fun openSource(track: CanonicalTrack, queue: List<CanonicalTrack>) = model.openSource(track, queue)
    fun openSourceForSong(song: Song) = model.openSourceForSong(song)
    fun closeCollection() = model.closeCollection()
    fun closeSource() = model.closeSource()
    fun chooseSource(providerId: String, remember: Boolean) = model.chooseSource(providerId, remember)
    fun playAlternative(track: CanonicalTrack, source: TrackSource) = model.playAlternative(track, source)
    fun splitSource(track: CanonicalTrack, source: TrackSource) = model.splitSource(track, source)
    fun clearOverrides() = model.clearOverrides()
    fun openMerge(track: CanonicalTrack) = model.openMerge(track)
    fun closeMerge() = model.closeMerge()
    fun mergeTo(target: CanonicalTrack) = model.mergeTo(target)
    fun mergeAlternative(track: CanonicalTrack, source: TrackSource) = model.mergeAlternative(track, source)
    fun favorite(song: Song) = model.toggleFavorite(song)
    fun download(song: Song) = model.download(song)
    fun transport(action: String) = model.transport(action)
    fun seek(position: Long) = model.seek(position)
    fun closePlayer() = model.openPlayer(false)
    fun openPlayer() = model.openPlayer(true)
    fun login(type: String) = model.startLogin(type)
    fun cookie(value: String) = model.saveCookie(value)
    fun logout() = model.logout()
    fun createPlaylist(name: String) = model.createPlaylist(name)
    fun deleteDownload(record: DownloadRecord) = model.deleteDownload(record)
    fun openLocal(playlist: LocalPlaylist) = model.openLocalPlaylist(playlist)
    fun toggleAggregateProvider(id: String) = model.toggleAggregateProvider(id)
    fun setPlaybackMode(mode: PlaybackMode) = model.setPlaybackMode(mode)
    fun setPinnedProvider(id: String?) = model.setPinnedProvider(id)
    fun setAllowPreview(value: Boolean) = model.setAllowPreview(value)
    fun setMatchStrictness(value: MatchStrictness) = model.setMatchStrictness(value)
    fun movePriority(id: String, delta: Int) = model.moveProviderPriority(id, delta)
    fun clearMatchCache() = model.clearMatchCache()
    fun clearMediaCache() = model.clearMediaCache()
    fun clearError() = model.clearError()
    fun clearNotice() = model.clearNotice()
    fun openDeepLink() = model.openDeepLink()
}

@Composable private fun BottomDestinationBar(current: Destination, select: (Destination) -> Unit) { NavigationBar { Destination.entries.forEach { NavigationBarItem(current == it, { select(it) }, icon = { Icon(it.icon, it.label) }, label = { Text(it.label) }) } } }
@Composable private fun RailDestinationBar(current: Destination, television: Boolean, select: (Destination) -> Unit) { NavigationRail(Modifier.width(if (television) 112.dp else 88.dp)) { Destination.entries.forEach { NavigationRailItem(current == it, { select(it) }, icon = { Icon(it.icon, it.label) }, label = { Text(it.label) }) } } }

@Composable
private fun HomePage(state: MusicUiState, television: Boolean, actions: MusicActions) {
    val home = state.home
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("发现", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(actions::refreshHome) { Text("刷新") }
                }
                ProviderPicker(state, actions)
            }
        }
        when {
            home == null || home.loading -> item { HomeLoading(home?.provider?.name ?: state.providers.find { it.id == state.providerId }?.name) }
            home.error.isNotBlank() && home.recommendations.isEmpty() && home.charts.isEmpty() && home.hotSongs.isEmpty() ->
                item { HomeError(home.error, actions) }
            else -> {
                if (home.error.isNotBlank()) {
                    item { Text("部分内容加载失败：${home.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                if (home.recommendations.isNotEmpty()) {
                    item { Section("推荐歌单") { MediaRow(home.recommendations, television) { actions.playlist(it, false) } } }
                }
                if (home.charts.isNotEmpty()) {
                    item {
                        val charts = home.charts.map {
                            Playlist(
                                id = it.id,
                                name = it.name,
                                cover = it.cover,
                                creator = it.updateFrequency.ifBlank { it.description },
                                source = it.source,
                            )
                        }
                        Section("排行榜") {
                            MediaRow(charts, television) { selected ->
                                home.charts.find { it.id == selected.id }?.let(actions::chart)
                            }
                        }
                    }
                }
                item { CategorySection(state, television, actions) }
                if (home.hotSongs.isNotEmpty()) {
                    item { Section("热门歌曲") { SongList(home.hotSongs.take(12), actions) } }
                }
            }
        }
    }
}

@Composable
private fun HomeLoading(providerName: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp))
        Text(
            if (providerName.isNullOrBlank()) "正在加载推荐内容…" else "正在加载${providerName}的推荐内容…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeError(message: String, actions: MusicActions) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("首页加载失败：$message", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(actions::refreshHome) { Text("重试") }
        }
    }
}

@Composable
private fun CategorySection(state: MusicUiState, television: Boolean, actions: MusicActions) {
    Section("歌单分类") {
        when {
            state.categoriesLoading -> HomeLoading(state.home?.provider?.name)
            state.categoriesError.isNotBlank() -> HomeError(state.categoriesError, actions)
            state.categories.isEmpty() -> Text(
                "当前平台不提供歌单分类",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                var group by remember(state.home?.provider?.id) { mutableStateOf("全部") }
                val groups = remember(state.categories) {
                    listOf("全部") + state.categories.map { it.group }.filter { it.isNotBlank() && it != "全部" }.distinct()
                }
                val visible = remember(state.categories, group) {
                    if (group == "全部") state.categories else state.categories.filter { it.group == group }
                }
                if (groups.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(groups) { name -> FilterChip(group == name, { group = name }, { Text(name) }) }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { "${it.group}:${it.id}" }) { category ->
                        val selected = state.categoryOpen && state.categoryId == category.id
                        FilterChip(
                            selected,
                            {
                                if (selected) actions.clearCategory()
                                else actions.category(category.id)
                            },
                            { Text(category.name) },
                        )
                    }
                }
                if (state.categoryOpen) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${state.categoryName.ifBlank { "分类" }} · 第 ${state.page} 页",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton({ actions.category(state.categoryId, state.page - 1) }, enabled = state.page > 1) { Text("上一页") }
                        TextButton({ actions.category(state.categoryId, state.page + 1) }, enabled = state.categoryPlaylists.isNotEmpty()) { Text("下一页") }
                        TextButton(actions::clearCategory) { Text("收起") }
                    }
                    if (state.categoryPlaylists.isEmpty()) {
                        Text("这一页没有歌单", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        PlaylistRows(state.categoryPlaylists, television) { actions.playlist(it, false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRows(items: List<Playlist>, television: Boolean, select: (Playlist) -> Unit) {
    val columns = if (television) 5 else 3
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { MediaCard(it, television, select) }
            }
        }
    }
}

@Composable
private fun SearchPage(state: MusicUiState, television: Boolean, actions: MusicActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            state.query,
            actions::query,
            Modifier.fillMaxWidth(),
            label = { Text("歌曲、歌单、专辑或链接") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions { actions.search() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.searchScope == SearchScope.Aggregated, { actions.searchScope(SearchScope.Aggregated) }, { Text("聚合") })
            FilterChip(state.searchScope == SearchScope.SingleProvider, { actions.searchScope(SearchScope.SingleProvider) }, { Text("当前平台") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchKind.entries.forEach { FilterChip(state.searchKind == it, { actions.searchKind(it) }, { Text(it.label) }) }
        }
        if (state.searchScope == SearchScope.Aggregated && state.searchKind == SearchKind.Link) {
            Text(
                "链接解析按当前平台处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ actions.search() }) { Text("搜索") }
            if (state.loading) CircularProgressIndicator(Modifier.size(20.dp))
        }
        when {
            state.searchScope == SearchScope.Aggregated && state.searchKind == SearchKind.Song ->
                AggregateResults(state, actions)
            state.searchScope == SearchScope.Aggregated && state.searchKind == SearchKind.Album ->
                AggregateCollectionResults(state, SearchKind.Album, actions)
            state.searchScope == SearchScope.Aggregated && state.searchKind == SearchKind.Playlist ->
                AggregateCollectionResults(state, SearchKind.Playlist, actions)
            else -> SingleProviderResults(state, television, actions)
        }
    }
}

@Composable
private fun SingleProviderResults(state: MusicUiState, television: Boolean, actions: MusicActions) {
    LazyColumn {
        when (state.searchKind) {
            SearchKind.Song -> item { SongList(state.songs, actions) }
            SearchKind.Playlist -> item { MediaGrid(state.playlists, television) { actions.playlist(it, false) } }
            SearchKind.Album -> item { MediaGrid(state.albums, television) { actions.playlist(it, true) } }
            SearchKind.Link -> item { if (state.collectionSongs.isNotEmpty()) SongList(state.collectionSongs, actions) }
        }
    }
}

@Composable
private fun AggregateResults(state: MusicUiState, actions: MusicActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProviderStatusRow(state)
        when {
            state.aggregateGroups.isEmpty() && state.aggregateStatus == SearchStatus.Loading ->
                Text("正在同时搜索已启用的平台…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.aggregateGroups.isEmpty() && (state.aggregateStatus == SearchStatus.Complete || state.aggregateStatus == SearchStatus.Empty) ->
                Text("没有找到相关歌曲。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.aggregateGroups.isEmpty() && state.aggregateStatus == SearchStatus.Failed ->
                Text("聚合搜索失败，请重试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.aggregateGroups, key = { it.id }) { track ->
                AggregateCard(track, state, actions)
            }
            if (state.aggregateHasMore) {
                item {
                    TextButton(
                        onClick = actions::searchMore,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.loading,
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.size(18.dp))
                        } else {
                            Text("加载更多")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AggregateCollectionResults(state: MusicUiState, kind: SearchKind, actions: MusicActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProviderStatusRow(state, state.collectionProviderStates, kind)
        when {
            state.aggregateCollections.isEmpty() && state.collectionSearchStatus == SearchStatus.Loading ->
                Text("正在同时搜索${kind.label}…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.aggregateCollections.isEmpty() && (state.collectionSearchStatus == SearchStatus.Complete || state.collectionSearchStatus == SearchStatus.Empty) ->
                Text("没有找到相关${kind.label}。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.aggregateCollections.isEmpty() && state.collectionSearchStatus == SearchStatus.Failed ->
                Text("聚合搜索失败，请重试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.aggregateCollections, key = { it.id }) { item ->
                CollectionCard(item, kind, actions)
            }
        }
    }
}

@Composable
private fun CollectionCard(item: CanonicalCollection, kind: SearchKind, actions: MusicActions) {
    val first = item.sources.firstOrNull()
    val providerCount = item.sources.map { it.providerId }.distinct().size
    val sourceCountLabel = if (kind == SearchKind.Playlist && item.sources.size > providerCount) {
        "$providerCount 个平台 · ${item.sources.size} 个歌单"
    } else {
        "$providerCount 个平台"
    }
    FocusableColumn(
        onClick = { first?.let { actions.playlist(it.playlist, kind == SearchKind.Album) } },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), tonalElevation = 1.dp) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(item.cover, 56)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOf(item.creator, "${item.trackCount} 首").filter { it.isNotBlank() }.joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(sourceCountLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    item.sources
                        .map { it.providerId to it.providerName }
                        .distinct()
                        .take(5)
                        .forEach { (_, name) -> ProviderBadge(name) }
                    if (item.sameNameOnly) ProviderBadge("同名歌单")
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(item.sources, key = { "${it.providerId}:${it.playlist.id}" }) { source ->
                        val sameProviderCount = item.sources.count { it.providerId == source.providerId }
                        val label = if (sameProviderCount > 1) {
                            "打开 ${source.providerName} · ${source.playlist.creator.ifBlank { source.playlist.id }}"
                        } else {
                            "打开 ${source.providerName}"
                        }
                        TextButton({ actions.playlist(source.playlist, kind == SearchKind.Album) }) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderStatusRow(
    state: MusicUiState,
    providerStates: Map<String, ProviderSearchState> = state.providerStates,
    kind: SearchKind = SearchKind.Song,
) {
    val all = state.providers.filter {
        when (kind) {
            SearchKind.Album -> it.searchAlbum
            SearchKind.Playlist -> it.searchPlaylist
            else -> it.search
        }
    }
    val enabledIds = state.aggregation.enabledOrDefault(all.map { it.id })
    val providers = all.filter { it.id in enabledIds }
    if (providers.isEmpty()) return
    val states = providerStates
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(providers, key = { it.id }) { provider ->
                val status = states[provider.id]
                val label = when (status?.status) {
                    ProviderStatus.Success -> "${provider.name} ✓"
                    ProviderStatus.Loading -> "${provider.name} …"
                    ProviderStatus.Timeout -> "${provider.name} 超时"
                    ProviderStatus.Error -> "${provider.name} !"
                    ProviderStatus.Skipped -> "${provider.name} 暂停"
                    else -> provider.name
                }
                val failed = status?.status == ProviderStatus.Timeout || status?.status == ProviderStatus.Error || status?.status == ProviderStatus.Skipped
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (states.values.any { it.status == ProviderStatus.Timeout || it.status == ProviderStatus.Error }) {
            Text("部分平台响应异常，已显示其余平台的结果。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AggregateCard(track: CanonicalTrack, state: MusicUiState, actions: MusicActions) {
    val queue = state.aggregateGroups
    FocusableColumn({ actions.openSource(track, queue) }, Modifier.fillMaxWidth()) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), tonalElevation = 1.dp) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(track.cover, 56)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(track.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(
                            listOf(track.durationLabel, "${track.sources.size} 个平台").filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    track.sources.take(4).forEach { ProviderBadge(it.providerName) }
                    if (track.sources.size > 4) ProviderBadge("+${track.sources.size - 4}")
                    if (track.alternatives.isNotEmpty()) ProviderBadge("其他版本 ${track.alternatives.size}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvailabilityLabel(track, state)
                    Spacer(Modifier.weight(1f))
                    TextButton({ actions.playTrack(track, queue) }) { Text("播放") }
                    TextButton({ actions.openSource(track, queue) }) { Text("选择音源") }
                    TextButton({ actions.openMerge(track) }) { Text("合并到…") }
                }
            }
        }
    }
}

@Composable
private fun AvailabilityLabel(track: CanonicalTrack, state: MusicUiState) {
    val statuses = track.sources.map { availabilityOf(it, state.cookies[it.providerId].orEmpty()) }
    val label = when {
        Availability.Playable in statuses -> "可播"
        Availability.Unknown in statuses -> "待检测"
        Availability.VipOnly in statuses -> "需要会员"
        Availability.PreviewOnly in statuses -> "仅试听"
        else -> "暂无音源"
    }
    val color = when (label) {
        "可播" -> MaterialTheme.colorScheme.primary
        "需要会员", "暂无音源" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun ProviderBadge(name: String) {
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(name, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CollectionPage(state: MusicUiState, television: Boolean, actions: MusicActions) {
    BackHandler(onBack = actions::closeCollection)
    val sourceName = providerName(state, state.collectionSource).ifBlank { "本地" }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(if (television) 32.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.collectionTitle.ifBlank { "歌曲" },
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "来源：$sourceName · ${state.collectionSongs.size} 首，播放时会自动选择可用音源",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(actions::closeCollection) { Text("关闭") }
            }
            when {
                state.loading && state.collectionSongs.isEmpty() -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("正在加载…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.collectionSongs.isEmpty() -> Text(
                    "没有加载到歌曲",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(state.collectionSongs, key = { it.key }) { song ->
                        CollectionSongRow(song, state, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionSongRow(song: Song, state: MusicUiState, actions: MusicActions) {
    FocusableRow({ actions.play(song, state.collectionSongs) }) {
        Cover(song.cover, 52)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            ProviderBadge(providerName(state, song.source))
        }
        TextButton({ actions.openSourceForSong(song) }) { Text("换源") }
    }
}

@Composable
private fun LibraryPage(favorites: List<Song>, history: List<Song>, downloads: List<DownloadRecord>, playlists: List<LocalPlaylist>, actions: MusicActions) {
    var section by remember { mutableStateOf(LibrarySection.Favorites) }
    var playlistName by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { LibrarySection.entries.forEach { FilterChip(section == it, { section = it }, { Text(it.label) }) } }
        when (section) {
            LibrarySection.Favorites -> SongList(favorites, actions)
            LibrarySection.History -> SongList(history, actions)
            LibrarySection.Downloads -> LazyColumn { items(downloads) { record -> Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(record.song().title + " · " + record.state); TextButton({ actions.deleteDownload(record) }) { Text("删除") } } } }
            LibrarySection.Playlists -> Column {
                Row {
                    OutlinedTextField(playlistName, { playlistName = it }, label = { Text("新歌单名称") })
                    Button({ actions.createPlaylist(playlistName); playlistName = "" }) { Text("创建") }
                }
                playlists.forEach { TextButton({ actions.openLocal(it) }) { Text(it.name) } }
            }
        }
    }
}

@Composable
private fun SettingsPage(state: MusicUiState, actions: MusicActions) {
    var cookie by remember(state.providerId) { mutableStateOf(state.cookies[state.providerId].orEmpty()) }
    val searchable = state.providers.filter { it.search }
    val aggregation = state.aggregation
    val priority = aggregation.priorityFor(searchable.map { it.id })
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("外观", style = MaterialTheme.typography.titleLarge) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (id, label) -> FilterChip(state.theme == id, { actions.theme(id) }, { Text(label) }) } } }

        item { Text("跨平台聚合", style = MaterialTheme.typography.titleLarge) }
        item { Text("聚合搜索平台", style = MaterialTheme.typography.titleMedium) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(searchable, key = { it.id }) { provider ->
                    FilterChip(aggregation.enabledProviders.contains(provider.id), { actions.toggleAggregateProvider(provider.id) }, { Text(provider.name) })
                }
            }
        }
        item { Text("播放策略", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackMode.entries.forEach { mode ->
                    FilterChip(
                        aggregation.playbackMode == mode,
                        { actions.setPlaybackMode(mode) },
                        { Text(when (mode) { PlaybackMode.Auto -> "自动"; PlaybackMode.Priority -> "按优先平台"; PlaybackMode.Pinned -> "固定平台" }) },
                    )
                }
            }
        }
        if (aggregation.playbackMode == PlaybackMode.Pinned) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(searchable, key = { it.id }) { provider ->
                        FilterChip(aggregation.pinnedProvider == provider.id, { actions.setPinnedProvider(provider.id) }, { Text(provider.name) })
                    }
                }
            }
        }
        item { Text("优先平台", style = MaterialTheme.typography.titleMedium) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                priority.forEachIndexed { index, id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(providerName(state, id), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton({ actions.movePriority(id, -1) }, enabled = index > 0) { Text("上移") }
                        TextButton({ actions.movePriority(id, 1) }, enabled = index < priority.lastIndex) { Text("下移") }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(aggregation.allowPreview, { actions.setAllowPreview(!aggregation.allowPreview) }, { Text("允许试听片段") })
                FilterChip(aggregation.matchStrictness == MatchStrictness.Strict, { actions.setMatchStrictness(if (aggregation.matchStrictness == MatchStrictness.Strict) MatchStrictness.Balanced else MatchStrictness.Strict) }, { Text("严格匹配") })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ actions.clearMatchCache() }) { Text("清除匹配缓存") }
                TextButton({ actions.clearOverrides() }) { Text("恢复自动匹配") }
                TextButton({ actions.clearMediaCache() }) { Text("清除播放缓存") }
            }
        }

        item { Text("平台账号", style = MaterialTheme.typography.titleLarge) }
        item { ProviderPicker(state, actions) }
        item { state.providers.find { it.id == state.providerId }?.note?.takeIf { it.isNotBlank() }?.let { Text(it) } }
        item { OutlinedTextField(cookie, { cookie = it }, Modifier.fillMaxWidth(), label = { Text("Cookie") }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ actions.cookie(cookie) }) { Text("保存 Cookie") }
                if (state.providers.find { it.id == state.providerId }?.qrLogin == true) Button({ actions.login(if (state.providerId == "qq") "qq" else "") }) { Text("扫码登录") }
                TextButton(actions::logout) { Text("退出") }
            }
        }
        state.login?.let { item { Text("扫码地址：${it.session.url}\n${state.loginStatus}") } }
        item { Text("平台层由 AGPL-3.0 的 music-lib 移植为 Kotlin 原生实现（7cc4467）。源码发布于 https://github.com/mytv-android/myMusic。") }
    }
}

@Composable
private fun SourcePickerDialog(state: MusicUiState, actions: MusicActions) {
    val track = state.sourcePickerTrack ?: return
    val providerIds = buildList {
        addAll(state.aggregation.enabledProviders)
        addAll(track.sources.map { it.providerId })
        addAll(track.alternatives.map { it.providerId })
    }.distinct()
    val orderedProviders = state.aggregation.priorityFor(providerIds)
    var rememberChoice by remember(track.id) { mutableStateOf(false) }

    Dialog(onDismissRequest = actions::closeSource, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp).padding(12.dp),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择播放平台", style = MaterialTheme.typography.titleLarge)
                Text("${track.title} · ${track.artistLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(orderedProviders, key = { it }) { providerId ->
                        SourcePickerRow(track, providerId, state, rememberChoice, actions)
                    }
                    if (track.alternatives.isNotEmpty()) {
                        item { Text("其他版本", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                        items(track.alternatives, key = { "alt-${it.song.key}" }) { alternative ->
                            AlternativeRow(track, alternative, actions)
                        }
                    }
                }
                if (state.sourcePickerInfo.isNotBlank()) {
                    Text(state.sourcePickerInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.sourcePickerError.isNotBlank()) {
                    Text(state.sourcePickerError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(rememberChoice, { rememberChoice = !rememberChoice }, { Text("记住此选择") })
                    Spacer(Modifier.weight(1f))
                    if (state.sourcePickerBusy.isNotBlank()) CircularProgressIndicator(Modifier.size(18.dp))
                    TextButton(actions::closeSource) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun MergeDialog(state: MusicUiState, actions: MusicActions) {
    val source = state.mergeTrack ?: return
    val candidates = state.aggregateGroups.filter { it.id != source.id }
    Dialog(onDismissRequest = actions::closeMerge, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 540.dp).padding(12.dp),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("合并到…", style = MaterialTheme.typography.titleLarge)
                Text("将「${source.title} · ${source.artistLabel}」合并到另一条结果。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (candidates.isEmpty()) {
                    Text("没有其他可合并的曲目", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(candidates, key = { it.id }) { target ->
                        FocusableRow({ actions.mergeTo(target) }) {
                            Cover(target.cover, 44)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(target.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(target.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("${target.sources.size} 个平台", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("合并只影响本机；可在设置中“恢复自动匹配”。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(actions::closeMerge) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun SourcePickerRow(track: CanonicalTrack, providerId: String, state: MusicUiState, rememberChoice: Boolean, actions: MusicActions) {
    val source = track.sources.find { it.providerId == providerId }
    val busy = state.sourcePickerBusy == providerId
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !busy) { actions.chooseSource(providerId, rememberChoice) }
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(providerName(state, providerId), style = MaterialTheme.typography.titleSmall)
            Text(
                if (source == null) "未匹配 · 点击检索同一版本" else source.qualityLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (source != null) {
            AvailabilityTag(availabilityOf(source, state.cookies[providerId].orEmpty()))
            if (track.sources.size > 1) {
                TextButton({ actions.splitSource(track, source) }) { Text("拆分") }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp))
    }
}

@Composable
private fun AlternativeRow(track: CanonicalTrack, alternative: TrackSource, actions: MusicActions) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .clickable { actions.playAlternative(track, alternative) }
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("${alternative.song.name} · ${alternative.providerName}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(alternative.qualityLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton({ actions.mergeAlternative(track, alternative) }) { Text("合并") }
        Text("其他版本", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AvailabilityTag(status: Availability) {
    val label = when (status) {
        Availability.Playable -> "完整"
        Availability.PreviewOnly -> "试听"
        Availability.VipOnly -> "VIP"
        Availability.Unavailable -> "无源"
        Availability.Unknown -> "待检测"
    }
    val color = when (status) {
        Availability.Playable -> MaterialTheme.colorScheme.tertiaryContainer
        Availability.PreviewOnly -> MaterialTheme.colorScheme.secondaryContainer
        Availability.VipOnly -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(shape = RoundedCornerShape(4.dp), color = color) {
        Text(label, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun PlayerBar(state: MusicUiState, actions: MusicActions) {
    val song = state.current ?: return
    Surface(Modifier.fillMaxWidth().focusable().clickable(onClick = actions::openPlayer), tonalElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(song.cover, 48)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.currentSource?.providerName ?: song.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(
                    progress = { if (state.duration > 0) state.position / state.duration.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TransportButton(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停") { actions.transport("play") }
        }
    }
}

@Composable
private fun PlayerPage(state: MusicUiState, television: Boolean, actions: MusicActions) {
    val song = state.current ?: return
    BackHandler(onBack = actions::closePlayer)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(if (television) 48.dp else 20.dp), Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(state.currentSource?.providerName ?: song.source)
                TextButton(actions::closePlayer) { Text("关闭") }
            }
            if (television) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Cover(song.cover, 320)
                    PlayerControls(state, song, actions, television, Modifier.weight(1f))
                }
            } else {
                Cover(song.cover, 240)
                PlayerControls(state, song, actions, television, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PlayerControls(state: MusicUiState, song: Song, actions: MusicActions, television: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(song.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2)
        Text(song.subtitle)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.currentSource?.let { source ->
                Text("播放源：${source.providerName} · ${source.qualityLabel}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton({
                state.currentTrack?.let { track -> actions.openSource(track, state.canonicalQueue.ifEmpty { listOf(track) }) }
            }) { Text("换源") }
        }
        if (state.playbackNotice.isNotBlank()) {
            Text(state.playbackNotice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.deepLink.isNotBlank()) {
            TextButton({ actions.openDeepLink() }) { Text("在平台打开") }
        }
        Slider((if (state.duration > 0) state.position / state.duration.toFloat() else 0f), { actions.seek((it * state.duration).toLong()) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportButton(Icons.Default.SkipPrevious, "上一首") { actions.transport("previous") }
            TransportButton(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停") { actions.transport("play") }
            TransportButton(Icons.Default.SkipNext, "下一首") { actions.transport("next") }
            TextButton({ actions.transport("shuffle") }) { Text(if (state.shuffle) "随机开" else "随机关") }
            TextButton({ actions.transport("repeat") }) { Text(state.repeat.name) }
            TransportButton(Icons.Default.FavoriteBorder, "收藏") { actions.favorite(song) }
            TransportButton(Icons.Default.Download, "下载") { actions.download(song) }
        }
        LyricsView(state.lyrics, state.position, television)
    }
}

@Composable private fun ProviderPicker(state: MusicUiState, actions: MusicActions) { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(state.providers) { FocusChip(it.name, state.providerId == it.id) { actions.provider(it.id) } } } }
@Composable private fun Section(title: String, content: @Composable () -> Unit) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); content() } }
@Composable private fun MediaRow(items: List<Playlist>, television: Boolean, select: (Playlist) -> Unit) { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(items) { MediaCard(it, television, select) } } }
@Composable private fun MediaGrid(items: List<Playlist>, television: Boolean, select: (Playlist) -> Unit) { LazyVerticalGrid(GridCells.Adaptive(if (television) 180.dp else 132.dp), contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(520.dp)) { items(items) { MediaCard(it, television, select) } } }
@Composable private fun MediaCard(item: Playlist, television: Boolean, select: (Playlist) -> Unit) { val size = if (television) 168.dp else 124.dp; FocusableColumn({ select(item) }, Modifier.width(size)) { Cover(item.cover, size.value.toInt()); Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(item.creator, maxLines = 1, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun SongList(songs: List<Song>, actions: MusicActions) { Column { songs.forEach { song -> FocusableRow({ actions.play(song, songs) }) { Cover(song.cover, 52); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }; Text(song.source); Icon(Icons.Default.FavoriteBorder, "收藏", Modifier.clickable { actions.favorite(song) }.padding(8.dp)); Icon(Icons.Default.Download, "下载", Modifier.clickable { actions.download(song) }.padding(8.dp)) } } } }
@Composable private fun Cover(url: String, size: Int) { AsyncImage(url, null, Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop) }
@Composable private fun FocusChip(text: String, selected: Boolean = false, onClick: () -> Unit) { var focused by remember { mutableStateOf(false) }; Surface(onClick, shape = RoundedCornerShape(50), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, modifier = Modifier.onFocusChanged { focused = it.isFocused }.border(if (focused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))) { Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) } }
@Composable private fun FocusableColumn(onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) { var focused by remember { mutableStateOf(false) }; Column(modifier.clickable(onClick = onClick).onFocusChanged { focused = it.isFocused }.border(if (focused) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } }
@Composable private fun FocusableRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) { var focused by remember { mutableStateOf(false) }; Row(Modifier.fillMaxWidth().clickable(onClick = onClick).onFocusChanged { focused = it.isFocused }.background(if (focused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface).padding(6.dp), verticalAlignment = Alignment.CenterVertically) { content() } }
@Composable private fun TransportButton(icon: ImageVector, label: String, onClick: () -> Unit) { var focused by remember { mutableStateOf(false) }; Surface(onClick, Modifier.size(48.dp).onFocusChanged { focused = it.isFocused }.border(if (focused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)), shape = RoundedCornerShape(24.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, label) } } }
@Composable private fun isTelevision(): Boolean { val configuration = LocalConfiguration.current; val uiMode = LocalContext.current.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK; return uiMode == Configuration.UI_MODE_TYPE_TELEVISION || (configuration.smallestScreenWidthDp >= 600 && configuration.screenWidthDp >= 960) }

private fun providerName(state: MusicUiState, id: String): String =
    state.providers.find { it.id == id }?.name ?: when (id) {
        "netease" -> "网易云音乐"
        "qq" -> "QQ 音乐"
        "kugou" -> "酷狗音乐"
        "kuwo" -> "酷我音乐"
        "migu" -> "咪咕音乐"
        "apple" -> "Apple Music"
        "soda" -> "汽水音乐"
        "bilibili" -> "Bilibili"
        "local" -> "本地"
        else -> id
    }
