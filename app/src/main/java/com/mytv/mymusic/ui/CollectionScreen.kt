package com.mytv.mymusic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mytv.mymusic.ui.components.TintedGradient
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.data.CollectionView
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.adaptive.FoldRow
import com.mytv.mymusic.ui.adaptive.LocalFoldPane
import com.mytv.mymusic.ui.adaptive.bottom
import com.mytv.mymusic.ui.adaptive.top
import com.mytv.mymusic.ui.charts.DiscoverErrorState
import com.mytv.mymusic.ui.charts.SongAction
import com.mytv.mymusic.ui.charts.SongActionsSheet
import com.mytv.mymusic.ui.charts.TvDefaultFocus
import com.mytv.mymusic.ui.charts.rememberCoverTint
import com.mytv.mymusic.ui.components.CollectionSearchField
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.filterCollectionSongs
import com.mytv.mymusic.ui.components.LocalOverlayPadding
import com.mytv.mymusic.ui.components.PlatformBadge
import com.mytv.mymusic.ui.components.PlayAllSplitButton
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SelectionBar
import com.mytv.mymusic.ui.components.SelectionPredictiveBack
import com.mytv.mymusic.ui.components.SelectionState
import com.mytv.mymusic.ui.components.selectionBackPreview
import com.mytv.mymusic.ui.components.SkeletonRows
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.rememberRowSwipe
import com.mytv.mymusic.ui.components.distinctKeys
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.poster.CollectionPosterOps
import com.mytv.mymusic.ui.poster.CollectionPosterView
import com.mytv.mymusic.ui.poster.PosterExtraAction
import com.mytv.mymusic.ui.poster.collectionKindLabel
import com.mytv.mymusic.ui.artist.AlbumCard
import com.mytv.mymusic.ui.artist.ArtistPill
import com.mytv.mymusic.ui.artist.ExpandableText
import com.mytv.mymusic.ui.artist.VinylCover
import com.mytv.mymusic.ui.artist.albumArtists
import com.mytv.mymusic.ui.artist.albumMetaLine
import com.mytv.mymusic.ui.artist.totalDurationLabel
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import com.mytv.mymusic.data.Playlist

/** 详情页的几种主操作，三种布局共用。 */
private class CollectionOps(
    val playAll: () -> Unit,
    val shuffle: () -> Unit,
    val queue: () -> Unit,
    val save: () -> Unit,
    val openPlaybackSettings: (() -> Unit)?,
)

/**
 * 已收藏 / 已绑定：歌单按“绑定了这个平台歌单”判定（跟随平台更新）；榜单和专辑没有绑定，
 * 沿用同名本地歌单视为已收藏。
 */
internal fun collectionSaved(
    kind: CollectionKind,
    id: String,
    source: String,
    title: String,
    playlists: List<LocalPlaylistSummary>,
    savedNow: Boolean,
): Boolean {
    if (kind == CollectionKind.Playlist) {
        return savedNow || playlists.any { it.bound && it.linkProvider == source && it.linkId == id }
    }
    val trimmed = title.trim()
    return savedNow || (trimmed.isNotEmpty() && playlists.any { it.name == trimmed })
}

/**
 * 榜单 / 歌单 / 专辑详情。手机：头部渐变取封面色，Split button 播放全部，分段曲目列表；
 * 平板（≥840dp）和电视：左栏封面、标题和操作不随列表滚动，右栏是曲目。
 * collection_view 选了海报时整页换成海报视图（CollectionPosterView），手机在顶栏、平板和电视在左栏操作区切换。
 */
@Composable
fun CollectionScreen(
    state: MusicUiState,
    collection: CollectionUiState,
    favorites: List<SavedSong>,
    playlists: List<LocalPlaylistSummary>,
    actions: MusicActions,
    onBack: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
) {
    var target by remember { mutableStateOf<SongAction?>(null) }
    // 电视海报视图里长按 / 菜单键打开时接在单曲操作下面的整页操作；列表视图里为 null
    var targetPage by remember { mutableStateOf<WallPageActions?>(null) }
    // 海报视图的设置面板由这里持有：电视上墙外不放按钮，它从单曲面板里的整页操作打开
    var posterSettingsOpen by rememberSaveable(collection.kind, collection.id) { mutableStateOf(false) }
    // 歌单的收藏 = 绑定平台歌单（跟随平台更新）；榜单 / 专辑 = 存成本地歌单，同名视为已收藏
    var savedNow by rememberSaveable(collection.kind, collection.id) { mutableStateOf(false) }
    val saved = remember(collection, playlists, savedNow) {
        collectionSaved(collection.kind, collection.id, collection.source, collection.title, playlists, savedNow)
    }
    val allSongs = collection.songs
    var searchOpen by rememberSaveable(collection.key) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(collection.key) { mutableStateOf("") }
    val songs = remember(allSongs, searchQuery) { filterCollectionSongs(allSongs, searchQuery) }
    val shownCollection = remember(collection, songs) { collection.copy(songs = songs) }
    val settingsTarget = rememberUpdatedState(onOpenPlaybackSettings)
    val openPlaybackSettings: () -> Unit = remember { { settingsTarget.value() } }
    val ops = remember(actions, allSongs, collection.id, collection.kind, openPlaybackSettings) {
        CollectionOps(
            playAll = actions::playCollection,
            shuffle = {
                val shuffled = allSongs.shuffled()
                if (shuffled.isNotEmpty()) actions.play(shuffled.first(), shuffled)
            },
            queue = actions::queueCollection,
            save = {
                actions.saveCollection()
                if (songs.isNotEmpty()) savedNow = true
            },
            openPlaybackSettings = openPlaybackSettings.takeIf { collection.kind == CollectionKind.Chart },
        )
    }
    val tint = rememberCoverTint(collection.cover, actions)
    val onMore: (Song) -> Unit = remember(songs) {
        { song ->
            targetPage = null
            target = SongAction(song, songs)
        }
    }
    val onPosterMore: (Song, WallPageActions?) -> Unit = remember(songs) {
        { song, page ->
            targetPage = page
            target = SongAction(song, songs)
        }
    }
    val selection = remember { SelectionState() }
    LaunchedEffect(searchQuery) { selection.exit() }
    var pickerOpen by remember { mutableStateOf(false) }
    SelectionPredictiveBack(selection)
    // 多选期间的批量操作：歌保持列表里的顺序
    val selectedSongs = { songs.filter { it.key in selection.selected } }
    val selectionOps = CollectionSelectionOps(
        count = selection.selected.size,
        allSelected = songs.isNotEmpty() && selection.selected.containsAll(songs.map { it.key }),
        onToggleAll = { selection.toggleAll(songs.map { it.key }) },
        onPlay = {
            val picked = selectedSongs()
            if (picked.isNotEmpty()) actions.play(picked.first(), picked)
        },
        onPlayNext = { actions.playSongsNext(selectedSongs()) },
        onAddTo = { pickerOpen = true },
        onExit = { selection.exit() },
    )
    val playingKey = state.current?.key
    // 专辑页的黑胶：正在放这张专辑里的歌时慢慢转
    val albumSpinning = collection.kind == CollectionKind.Album && state.playing &&
        playingKey != null && songs.any { it.key == playingKey }
    // 切到海报视图前退出多选：多选只在列表视图里
    val showPoster: () -> Unit = remember(actions, selection) {
        {
            selection.exit()
            actions.setCollectionView(CollectionView.Poster)
        }
    }
    val poster = state.collectionView == CollectionView.Poster
    LaunchedEffect(state.locateRequestId) {
        if (state.locateSongKey.isNotBlank() && poster) actions.setCollectionView(CollectionView.List)
    }
    when {
        poster ->
            CollectionPoster(
                state = state,
                collection = shownCollection,
                saved = saved,
                ops = ops,
                actions = actions,
                onBack = onBack,
                onMore = onPosterMore,
                holdAwake = target != null || pickerOpen || posterSettingsOpen,
                settingsOpen = posterSettingsOpen,
                onSettingsOpenChange = { posterSettingsOpen = it },
            )
        LocalAppWindow.current.twoPane ->
            TwoPaneCollection(shownCollection, state, tint, ops, saved, actions, playingKey, onBack, onMore, selection, selectionOps, showPoster, albumSpinning, searchOpen, searchQuery, { searchOpen = it; if (!it) searchQuery = "" }, { searchQuery = it })
        else ->
            PhoneCollection(shownCollection, state, tint, ops, saved, actions, playingKey, onBack, onMore, selection, selectionOps, showPoster, albumSpinning, searchOpen, searchQuery, { searchOpen = it; if (!it) searchQuery = "" }, { searchQuery = it })
    }
    target?.let {
        SongActionsSheet(
            it,
            favorites,
            playlists,
            actions,
            onDismiss = { target = null },
            wallPage = targetPage.takeIf { poster },
            currentAlbum = if (collection.kind == CollectionKind.Album) collection.title else "",
        )
    }
    if (pickerOpen) {
        PlaylistPickerSheet(
            playlists = playlists,
            onPick = { id ->
                actions.addSongsToPlaylist(id, selectedSongs())
                selection.exit()
            },
            onCreate = { name ->
                actions.addSongsToNewPlaylist(name, selectedSongs())
                selection.exit()
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * 海报视图：曲目铺成海报墙，头部收成顶部胶囊和浮动工具栏（见 CollectionPosterView）。
 * 播放全部 / 随机 / 大卡上的“播放”都是换成这张歌单的队列（和列表里点一行一样），但不弹出播放页；
 * 长按格子和大卡的“更多”是和列表同一个单曲操作面板；加载失败、空歌单沿用列表的提示，盖在墙中间。
 */
@Composable
private fun CollectionPoster(
    state: MusicUiState,
    collection: CollectionUiState,
    saved: Boolean,
    ops: CollectionOps,
    actions: MusicActions,
    onBack: () -> Unit,
    onMore: (Song, WallPageActions?) -> Unit,
    holdAwake: Boolean,
    settingsOpen: Boolean,
    onSettingsOpenChange: (Boolean) -> Unit,
) {
    val songs = collection.songs
    val posterOps = remember(actions, songs, onBack, onMore) {
        CollectionPosterOps(
            playAll = { actions.playCollection(openPlayer = false) },
            shuffle = {
                val shuffled = songs.shuffled()
                if (shuffled.isNotEmpty()) actions.play(shuffled.first(), shuffled, openPlayer = false)
            },
            playFrom = { index -> songs.getOrNull(index)?.let { actions.play(it, songs, openPlayer = false) } },
            playNext = { index -> songs.getOrNull(index)?.let(actions::playSongNext) },
            songActions = { index, page -> songs.getOrNull(index)?.let { onMore(it, page) } },
            showList = { actions.setCollectionView(CollectionView.List) },
            back = onBack,
        )
    }
    val isPlaylist = collection.kind == CollectionKind.Playlist
    val save = remember(ops, saved, isPlaylist) {
        PosterExtraAction(
            icon = if (isPlaylist) Icons.Filled.Link else if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            description = if (isPlaylist) "绑定到我的歌单" else "收藏到本地歌单",
            onClick = { if (!saved) ops.save() },
            on = saved,
            stateDescription = if (isPlaylist) {
                if (saved) "已绑定，平台更新自动同步" else "未绑定"
            } else if (saved) "已存到本地歌单" else "未收藏",
        )
    }
    val providers = state.providers
    val sourceName: (Song) -> String = remember(collection.sourceName, providers) {
        { song -> collection.sourceName.ifBlank { providerName(providers, song.source) } }
    }
    // 电视上墙上没有歌时没有格子可长按，整页操作够不到：提示里再给一个“切回列表视图”出去
    val toList: (() -> Unit)? = if (LocalAppWindow.current.isTv) ({ actions.setCollectionView(CollectionView.List) }) else null
    val toListLabel = toList?.let { "切回列表视图" }
    val message: (@Composable () -> Unit)? = when {
        songs.isNotEmpty() || collection.loading -> null
        collection.error.isNotBlank() -> ({
            DiscoverErrorState(
                title = "歌曲没有加载出来",
                message = listOf(collection.sourceName, collection.error).filter { it.isNotBlank() }.joinToString("："),
                onRetry = actions::retryCollection,
                secondaryLabel = toListLabel,
                onSecondary = toList,
            )
        })
        else -> ({
            EmptyState(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                title = "这里还没有歌曲",
                message = "稍后重试，或换一个来源。",
                actionLabel = "重试",
                onAction = actions::retryCollection,
                secondaryLabel = toListLabel,
                onSecondary = toList,
            )
        })
    }
    CollectionPosterView(
        state = state,
        actions = actions,
        title = collection.title,
        kindLabel = collectionKindLabel(collection.kind),
        sourceLabel = collection.sourceName,
        cover = collection.cover,
        songs = songs,
        loading = collection.loading,
        playLabel = playLabel(collection),
        ops = posterOps,
        sourceName = sourceName,
        extra = save,
        sourceSettings = ops.openPlaybackSettings,
        message = message,
        resetKey = collection.kind to collection.id,
        holdAwake = holdAwake || settingsOpen,
        settingsOpen = settingsOpen,
        onSettingsOpenChange = onSettingsOpenChange,
    )
}

/** 多选操作条的数据，三端布局共用。 */
private data class CollectionSelectionOps(
    val count: Int,
    val allSelected: Boolean,
    val onToggleAll: () -> Unit,
    val onPlay: () -> Unit,
    val onPlayNext: () -> Unit,
    val onAddTo: () -> Unit,
    val onExit: () -> Unit,
)

@Composable
private fun SelectionBarFor(ops: CollectionSelectionOps, modifier: Modifier = Modifier) {
    SelectionBar(
        count = ops.count,
        allSelected = ops.allSelected,
        onToggleAll = ops.onToggleAll,
        onPlay = ops.onPlay,
        onPlayNext = ops.onPlayNext,
        onAddTo = ops.onAddTo,
        onExit = ops.onExit,
        modifier = modifier,
    )
}

@Composable
private fun PhoneCollection(
    collection: CollectionUiState,
    state: MusicUiState,
    tint: State<Color>,
    ops: CollectionOps,
    saved: Boolean,
    actions: MusicActions,
    playingKey: String?,
    onBack: () -> Unit,
    onMore: (Song) -> Unit,
    selection: SelectionState,
    selectionOps: CollectionSelectionOps,
    onPosterView: () -> Unit,
    albumSpinning: Boolean,
    searchOpen: Boolean,
    searchQuery: String,
    onSearchOpen: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
) {
    val gutter = LocalAppWindow.current.gutter
    val isAlbum = collection.kind == CollectionKind.Album
    val overlay = LocalOverlayPadding.current.calculateBottomPadding()
    val surface = MaterialTheme.colorScheme.surface
    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val songKeys = remember(collection.songs) { distinctKeys(collection.songs) { it.key } }
    LaunchedEffect(state.locateRequestId, collection.songs, searchQuery) {
        val target = state.locateSongKey
        if (target.isBlank()) return@LaunchedEffect
        if (searchQuery.isNotBlank()) {
            onSearchQuery("")
            return@LaunchedEffect
        }
        val keys = buildSet {
            add(target)
            state.currentTrack?.sources?.forEach { add(it.song.key) }
        }
        val index = collection.songs.indexOfFirst { it.key in keys }
        if (index < 0) {
            if (!collection.loading) actions.consumePlaybackLocate()
            return@LaunchedEffect
        }
        val albumIntro = if (collection.kind == CollectionKind.Album && collection.album?.description?.isNotBlank() == true) 1 else 0
        val headerCount = 2 + albumIntro + (if (searchOpen) 1 else 0) + (if (collection.error.isNotBlank()) 1 else 0)
        listState.scrollToItem(headerCount + index)
        actions.consumePlaybackLocate()
    }
    Box(Modifier.fillMaxSize().background(surface)) {
        // 头部渐变跟着第一项一起滚走，滚过头部后顶栏换成实色。
        // 静态渐变缓存进 drawWithCache，动画色只作为 ColorFilter 的 tint 在绘制阶段读取。
        Box(
            Modifier
                .fillMaxWidth()
                // 专辑头部是竖排的（唱片封面在上），渐变拉长一些
                .height(if (isAlbum) 620.dp else 380.dp)
                .graphicsLayer {
                    translationY = if (listState.firstVisibleItemIndex == 0) -listState.firstVisibleItemScrollOffset.toFloat() else -size.height
                }
                .drawWithCache {
                    val fade = TintedGradient(Brush.verticalGradient(0f to Color.White, 1f to Color.Transparent))
                    onDrawBehind {
                        drawRect(surface)
                        fade.draw(this, tint.value)
                    }
                },
        )
        Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
            TopAppBar(
                title = {
                    AnimatedVisibility(collapsed, enter = fadeIn(), exit = fadeOut()) {
                        Text(collection.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { onSearchOpen(!searchOpen) }) {
                        Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, if (searchOpen) "关闭歌单内检索" else "歌单内检索")
                    }
                    ops.openPlaybackSettings?.let { open ->
                        IconButton(onClick = open) { Icon(Icons.Filled.Tune, "换源设置") }
                    }
                    // 操作行已经放了播放全部、收藏、多选，海报视图的入口放顶栏
                    IconButton(onClick = onPosterView) { Icon(Icons.Filled.GridView, "海报视图") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surface.copy(alpha = 0f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
            )
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(bottom = overlay + 24.dp),
            ) {
                item(key = "head", contentType = "head") {
                    if (isAlbum) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = gutter).padding(top = 4.dp, bottom = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            VinylCover(collection.cover, collection.title, 208.dp, albumSpinning, tint)
                            Spacer(Modifier.height(20.dp))
                            AlbumTitleBlock(collection, actions, large = false, centered = true)
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = gutter).padding(top = 4.dp, bottom = 18.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CollectionCover(collection, large = false)
                            Column(Modifier.weight(1f)) { CollectionTitle(collection, large = false) }
                        }
                    }
                }
                item(key = "actions", contentType = "actions") {
                    if (selection.active) {
                        SelectionBarFor(selectionOps, Modifier.padding(horizontal = gutter).padding(bottom = 16.dp).selectionBackPreview(selection))
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = gutter).padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            // 专辑头部居中排，操作也居中
                            horizontalArrangement = if (isAlbum) Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(8.dp),
                        ) {
                            PlayAllSplitButton(
                                onPlayAll = ops.playAll,
                                onShuffle = ops.shuffle,
                                onQueue = ops.queue,
                                onSave = ops.save,
                                label = playLabel(collection),
                                saveLabel = saveLabel(collection),
                            )
                            SaveToggle(ops, saved, collection.kind == CollectionKind.Playlist)
                            MultiSelectToggle(selection)
                        }
                    }
                }
                albumIntro(collection, horizontal = gutter)
                if (searchOpen) {
                    item(key = "collectionSearch", contentType = "search") {
                        CollectionSearchField(searchQuery, onSearchQuery, Modifier.padding(horizontal = gutter).padding(bottom = 12.dp))
                    }
                }
                collectionSongs(collection, actions, playingKey, songKeys, onMore, horizontal = gutter, selection = selection, query = searchQuery)
                albumFooter(collection, actions, horizontal = gutter)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TwoPaneCollection(
    collection: CollectionUiState,
    state: MusicUiState,
    tint: State<Color>,
    ops: CollectionOps,
    saved: Boolean,
    actions: MusicActions,
    playingKey: String?,
    onBack: () -> Unit,
    onMore: (Song) -> Unit,
    selection: SelectionState,
    selectionOps: CollectionSelectionOps,
    onPosterView: () -> Unit,
    albumSpinning: Boolean,
    searchOpen: Boolean,
    searchQuery: String,
    onSearchOpen: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
) {
    val window = LocalAppWindow.current
    val isAlbum = collection.kind == CollectionKind.Album
    val tv = window.isTv
    val overlay = LocalOverlayPadding.current.calculateBottomPadding()
    val surface = MaterialTheme.colorScheme.surface
    val playFocus = remember { FocusRequester() }
    TvDefaultFocus(playFocus)
    val listState = rememberLazyListState()
    val songKeys = remember(collection.songs) { distinctKeys(collection.songs) { it.key } }
    LaunchedEffect(state.locateRequestId, collection.songs, searchQuery) {
        val target = state.locateSongKey
        if (target.isBlank()) return@LaunchedEffect
        if (searchQuery.isNotBlank()) {
            onSearchQuery("")
            return@LaunchedEffect
        }
        val keys = buildSet {
            add(target)
            state.currentTrack?.sources?.forEach { add(it.song.key) }
        }
        val index = collection.songs.indexOfFirst { it.key in keys }
        if (index < 0) {
            if (!collection.loading) actions.consumePlaybackLocate()
            return@LaunchedEffect
        }
        val headerCount = (if (searchOpen) 1 else 0) + (if (collection.error.isNotBlank()) 1 else 0)
        listState.scrollToItem(headerCount + index)
        actions.consumePlaybackLocate()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(surface)
            .drawWithCache {
                if (tv) {
                    // 电视：封面色的两团环境光。渐变形状缓存，动画色通过 ColorFilter 叠加。
                    val top = TintedGradient(Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0f)),
                        Offset(size.width * 0.12f, size.height * 0.2f),
                        size.maxDimension * 0.55f,
                    ))
                    val bottom = TintedGradient(Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0f)),
                        Offset(size.width * 0.85f, size.height * 0.95f),
                        size.maxDimension * 0.5f,
                    ))
                    onDrawBehind {
                        val color = tint.value
                        top.draw(this, color)
                        bottom.draw(this, color)
                    }
                } else {
                    val fade = TintedGradient(Brush.verticalGradient(0f to Color.White, 0.5f to Color.Transparent))
                    onDrawBehind {
                        drawRect(surface)
                        fade.draw(this, tint.value)
                    }
                }
            },
    ) {
        // 书本姿态、双竖：封面和操作收到折痕为止，曲目在折痕另一侧；双横：上屏封面、下屏曲目
        FoldRow(
            startWidth = if (tv) 300.dp else 320.dp,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(start = window.gutter, end = window.gutter, top = if (tv) 36.dp else 8.dp),
            spacing = if (tv) 40.dp else 32.dp,
            start = {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = LocalFoldPane.current.bottom(overlay + 24.dp)),
                ) {
                    if (!tv) {
                        IconButton(onClick = onBack, modifier = Modifier.padding(bottom = 8.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    }
                    if (isAlbum) {
                        VinylCover(collection.cover, collection.title, if (tv) 200.dp else 224.dp, albumSpinning, tint)
                        Spacer(Modifier.height(20.dp))
                        AlbumTitleBlock(collection, actions, large = true, centered = false)
                    } else {
                        CollectionCover(collection, large = true)
                        Spacer(Modifier.height(16.dp))
                        CollectionTitle(collection, large = true)
                    }
                    Spacer(Modifier.height(16.dp))
                    if (selection.active) {
                        SelectionBarFor(selectionOps, Modifier.selectionBackPreview(selection))
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (tv) {
                                // 电视不用下拉菜单：播放全部 + 随机 + 加入队列平铺，焦点默认在“播放全部”
                                Button(
                                    onClick = ops.playAll,
                                    modifier = Modifier.focusRequester(playFocus).heightIn(min = 48.dp).tvFocusRing(CircleShape),
                                ) {
                                    Icon(Icons.Filled.PlayArrow, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(playLabel(collection), Modifier.semantics { contentDescription = "${playLabel(collection)}：${collection.title}" })
                                }
                                FilledTonalIconButton(onClick = ops.shuffle, modifier = Modifier.size(48.dp).tvFocusRing(CircleShape)) {
                                    Icon(Icons.Filled.Shuffle, "随机播放")
                                }
                                FilledTonalIconButton(onClick = ops.queue, modifier = Modifier.size(48.dp).tvFocusRing(CircleShape)) {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "加入播放队列")
                                }
                            } else {
                                PlayAllSplitButton(
                                    onPlayAll = ops.playAll,
                                    onShuffle = ops.shuffle,
                                    onQueue = ops.queue,
                                    onSave = ops.save,
                                    label = playLabel(collection),
                                    saveLabel = saveLabel(collection),
                                )
                            }
                            SaveToggle(ops, saved, collection.kind == CollectionKind.Playlist, if (tv) 48.dp else null)
                            MultiSelectToggle(selection, if (tv) 48.dp else null)
                            PosterViewToggle(onPosterView, if (tv) 48.dp else null)
                            FilledTonalIconButton(onClick = { onSearchOpen(!searchOpen) }, modifier = Modifier.size(48.dp).tvFocusRing(CircleShape)) {
                                Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, if (searchOpen) "关闭歌单内检索" else "歌单内检索")
                            }
                            ops.openPlaybackSettings?.let { open ->
                                FilledTonalIconButton(onClick = open, modifier = Modifier.size(48.dp).tvFocusRing(CircleShape)) {
                                    Icon(Icons.Filled.Tune, "换源设置")
                                }
                            }
                        }
                    }
                    // 专辑介绍放在左栏操作下面，收起成几行，点开看全文
                    collection.album?.description?.takeIf { isAlbum && it.isNotBlank() }?.let { text ->
                        Spacer(Modifier.height(20.dp))
                        ExpandableText(text, collapsedLines = if (tv) 3 else 5)
                    }
                }
            },
            end = {
                LazyColumn(
                    Modifier.fillMaxHeight(),
                    state = listState,
                    contentPadding = PaddingValues(top = LocalFoldPane.current.top(if (tv) 0.dp else 56.dp), bottom = overlay + 48.dp),
                ) {
                    if (searchOpen) {
                        item(key = "collectionSearch", contentType = "search") {
                            CollectionSearchField(searchQuery, onSearchQuery, Modifier.padding(bottom = 12.dp))
                        }
                    }
                    collectionSongs(collection, actions, playingKey, songKeys, onMore, horizontal = 0.dp, selection = selection, query = searchQuery)
                    albumFooter(collection, actions, horizontal = 0.dp)
                }
            },
        )
    }
}

private fun playLabel(collection: CollectionUiState) = when (collection.kind) {
    CollectionKind.Album -> "播放专辑"
    CollectionKind.Artist -> "播放歌手"
    else -> "播放全部"
}

private fun saveLabel(collection: CollectionUiState) =
    if (collection.kind == CollectionKind.Playlist) "绑定到我的歌单" else "存到本地歌单"

/**
 * 歌单 = 绑定（跟随平台更新）；榜单 / 专辑 = 存成本地歌单。
 * 已收藏 / 已绑定时只显示状态，取消要去“我的”里删歌单或解除绑定（删除需要二次确认）。
 */
@Composable
private fun SaveToggle(ops: CollectionOps, saved: Boolean, bind: Boolean, size: androidx.compose.ui.unit.Dp? = null) {
    val onLabel = if (bind) "已绑定，平台更新自动同步" else "已存到本地歌单"
    val offLabel = if (bind) "未绑定" else "未收藏"
    FilledTonalIconToggleButton(
        checked = saved,
        onCheckedChange = { if (!saved) ops.save() },
        modifier = Modifier
            .then(if (size != null) Modifier.size(size) else Modifier)
            .tvFocusRing(CircleShape)
            .semantics { stateDescription = if (saved) onLabel else offLabel },
    ) {
        Icon(
            if (bind) Icons.Filled.Link else if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (bind) "绑定到我的歌单" else "收藏到本地歌单",
        )
    }
}

/** 进入批量选择；长按任意一行也可以进入。 */
@Composable
private fun MultiSelectToggle(selection: SelectionState, size: androidx.compose.ui.unit.Dp? = null) {
    FilledTonalIconButton(
        onClick = { selection.start() },
        modifier = Modifier
            .then(if (size != null) Modifier.size(size) else Modifier)
            .tvFocusRing(CircleShape),
    ) {
        Icon(Icons.Filled.DoneAll, contentDescription = "批量选择歌曲")
    }
}

/** 平板和电视左栏的“海报视图”：切过去之后记住，下次打开任何详情页都是海报。 */
@Composable
private fun PosterViewToggle(onClick: () -> Unit, size: androidx.compose.ui.unit.Dp? = null) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier
            .then(if (size != null) Modifier.size(size) else Modifier)
            .tvFocusRing(CircleShape),
    ) {
        Icon(Icons.Filled.GridView, contentDescription = "海报视图")
    }
}

/** 封面：榜单用 Arch 裁切做装饰，歌单 28dp 圆角，专辑 16dp 圆角（与平台封面观感一致）。 */
@Composable
private fun CollectionCover(collection: CollectionUiState, large: Boolean) {
    val (size, shape) = when (collection.kind) {
        CollectionKind.Chart -> (if (large) DpSize(240.dp, 270.dp) else DpSize(150.dp, 170.dp)) to MaterialShapes.Arch.toShape()
        CollectionKind.Album -> (if (large) DpSize(240.dp, 240.dp) else DpSize(140.dp, 140.dp)) to (RoundedCornerShape(16.dp) as Shape)
        // 歌手头像用圆形，和专辑、歌单的方形封面分开
        CollectionKind.Artist -> (if (large) DpSize(240.dp, 240.dp) else DpSize(140.dp, 140.dp)) to (CircleShape as Shape)
        CollectionKind.Playlist -> (if (large) DpSize(240.dp, 240.dp) else DpSize(128.dp, 128.dp)) to (RoundedCornerShape(28.dp) as Shape)
    }
    CoverBox(collection.cover, collection.title, Modifier.size(size), shape, size.width.value * 0.36f)
}

@Composable
private fun CollectionTitle(collection: CollectionUiState, large: Boolean) {
    val tv = LocalAppWindow.current.isTv
    Text(
        collection.title.ifBlank { "详情" },
        Modifier.semantics { heading() },
        style = when {
            tv -> MaterialTheme.typography.headlineLarge
            large -> MaterialTheme.typography.headlineMedium
            else -> MaterialTheme.typography.headlineSmall
        },
        fontWeight = FontWeight.Black,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    val source = listOf(collection.sourceName, collection.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
    if (source.isNotBlank()) {
        Text(
            source,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Text(
        when {
            collection.songs.isNotEmpty() -> "${collection.songs.size} 首 · 按聚合策略播放"
            collection.loading -> "正在读取歌曲…"
            else -> "按聚合策略播放"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 曲目区：骨架 → 失败 / 空 → 分段列表；有歌但部分失败时在列表上方提示。多选时行点击变成勾选。 */
private fun LazyListScope.collectionSongs(
    collection: CollectionUiState,
    actions: MusicActions,
    playingKey: String?,
    songKeys: List<String>,
    onMore: (Song) -> Unit,
    horizontal: androidx.compose.ui.unit.Dp,
    selection: SelectionState,
    query: String = "",
) {
    val songs = collection.songs
    when {
        songs.isEmpty() && collection.loading -> item(key = "skeleton", contentType = "skeleton") {
            SkeletonRows(8, Modifier.padding(horizontal = horizontal))
        }
        songs.isEmpty() && collection.error.isNotBlank() -> item(key = "error", contentType = "error") {
            DiscoverErrorState(
                title = "歌曲没有加载出来",
                message = listOf(collection.sourceName, collection.error).filter { it.isNotBlank() }.joinToString("："),
                onRetry = actions::retryCollection,
            )
        }
        songs.isEmpty() -> item(key = "empty", contentType = "empty") {
            if (query.isNotBlank()) {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = "没有找到匹配歌曲",
                    message = "换个歌名、歌手或专辑关键词试试。",
                )
            } else {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = "这里还没有歌曲",
                    message = "稍后重试，或换一个来源。",
                    actionLabel = "重试",
                    onAction = actions::retryCollection,
                )
            }
        }
        else -> {
            if (collection.error.isNotBlank()) {
                item(key = "partial", contentType = "partial") {
                    Box(Modifier.padding(horizontal = horizontal).padding(bottom = 12.dp)) {
                        ErrorBlock(collection.error, actions::retryCollection)
                    }
                }
            }
            val ranked = collection.kind != CollectionKind.Playlist
            val album = collection.kind == CollectionKind.Album
            itemsIndexed(
                songs,
                key = { index, _ -> songKeys[index] },
                contentType = { _, _ -> "song" },
            ) { index, song ->
                val picking = selection.active
                val isSelected = picking && song.key in selection.selected
                val rowMore: (() -> Unit)? = if (picking) {
                    null
                } else {
                    { onMore(song) }
                }
                val rowLongPress: (() -> Unit)? = if (picking) {
                    null
                } else {
                    { selection.enter(song.key) }
                }
                SongRow(
                    title = song.title,
                    subtitle = if (collection.kind == CollectionKind.Album) song.artist.ifBlank { song.subtitle } else song.subtitle,
                    cover = song.cover,
                    index = index,
                    count = songs.size,
                    onClick = {
                        if (picking) selection.toggle(song.key) else actions.play(song, songs)
                    },
                    container = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .padding(horizontal = horizontal)
                        .padding(bottom = if (index < songs.lastIndex) SegmentGap else 0.dp),
                    rank = if (ranked) index + 1 else null,
                    showCover = !album,
                    rankEmphasis = !album,
                    rankLabel = if (album) ({ "第 $it 首" }) else ({ "第 $it 名" }),
                    playing = playingKey == song.key,
                    badges = songBadge(song, collection.sourceName),
                    trailing = when {
                        picking -> ({
                            Icon(
                                if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = if (isSelected) "取消选择“${song.title}”" else "选择“${song.title}”",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        })
                        else -> formatDuration(song.duration).takeIf { it.isNotBlank() && collection.kind != CollectionKind.Chart }?.let { time ->
                            {
                                Text(
                                    time,
                                    Modifier.padding(start = 4.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onMore = rowMore,
                    onLongClick = rowLongPress,
                    // 详情页没有可删的：左滑只有取消收藏（收藏过的）；专辑页不给「查看专辑」，歌手页不给他自己
                    swipe = rememberRowSwipe(
                        song,
                        songs,
                        enabled = !picking,
                        hideArtist = if (collection.kind == CollectionKind.Artist) collection.title else "",
                        hideAlbum = if (album) collection.title else "",
                    ),
                )
            }
        }
    }
}

/** 只标出“需要注意”的状态：VIP 和无音源；可播的歌不加徽标，列表保持安静。 */
private fun songBadge(song: Song, sourceName: String): (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? {
    val status = when {
        song.isInvalid -> Availability.Unavailable
        song.isVip -> Availability.VipOnly
        else -> return null
    }
    return {
        Box(Modifier.clearAndSetSemantics { contentDescription = "${sourceName.ifBlank { "当前平台" }}${availabilityLabel(status)}" }) {
            PlatformBadge(providerMark(sourceName.ifBlank { song.source }), status)
        }
    }
}

// ---------------- 专辑页 ----------------

/**
 * 专辑标题区：类型 · 平台、专辑名、歌手胶囊（点开歌手页）、发行日期 · 厂牌、曲目数 · 总时长。
 * 手机居中排在唱片封面下面；平板和电视左对齐放在左栏。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumTitleBlock(collection: CollectionUiState, actions: MusicActions, large: Boolean, centered: Boolean) {
    val tv = LocalAppWindow.current.isTv
    val album = collection.album
    val horizontal = if (centered) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    Column(horizontalAlignment = horizontal, modifier = Modifier.fillMaxWidth()) {
        Text(
            listOf(album?.type?.takeIf { it.isNotBlank() } ?: "专辑", collection.sourceName).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Text(
            collection.title.ifBlank { "专辑" },
            Modifier.padding(top = 4.dp).semantics { heading() },
            style = when {
                tv -> MaterialTheme.typography.headlineLarge
                large -> MaterialTheme.typography.headlineMedium
                else -> MaterialTheme.typography.headlineSmall
            },
            fontWeight = FontWeight.Black,
            textAlign = textAlign,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        val artists = albumArtists(album, collection.songs, collection.subtitle.substringBefore(" / "))
        if (artists.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, horizontal),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                artists.forEachIndexed { index, name ->
                    ArtistPill(name, onClick = { openAlbumArtist(collection, index, name, actions) })
                }
            }
        }
        val meta = albumMetaLine(album)
        if (meta.isNotBlank()) {
            Text(
                meta,
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = textAlign,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val songs = collection.songs
        Text(
            when {
                songs.isNotEmpty() -> listOf("${songs.size} 首", totalDurationLabel(songs)).filter { it.isNotBlank() }.joinToString(" · ")
                collection.loading -> "正在读取曲目…"
                else -> ""
            },
            Modifier.padding(top = if (meta.isNotBlank()) 2.dp else 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 头部歌手胶囊：第一位歌手有平台 id 就直接开，其余按名字在这个平台上找。 */
private fun openAlbumArtist(collection: CollectionUiState, index: Int, name: String, actions: MusicActions) {
    val artistId = collection.album?.artistId.orEmpty()
    if (index == 0 && artistId.isNotBlank()) {
        actions.openArtist(Playlist(id = artistId, name = name, source = collection.source))
    } else {
        actions.openArtistByName(name, Song(source = collection.source))
    }
}

/** 手机专辑页的专辑介绍：操作按钮下面一张卡片，收起成 3 行。 */
private fun LazyListScope.albumIntro(collection: CollectionUiState, horizontal: androidx.compose.ui.unit.Dp) {
    val text = collection.album?.description.orEmpty().trim()
    if (collection.kind != CollectionKind.Album || text.isEmpty()) return
    item(key = "albumIntro", contentType = "albumIntro") {
        androidx.compose.material3.Surface(
            Modifier.fillMaxWidth().padding(horizontal = horizontal).padding(bottom = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text(
                    "专辑介绍",
                    Modifier.padding(bottom = 6.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                ExpandableText(text)
            }
        }
    }
}

/**
 * 专辑页末尾：发行信息（日期、厂牌）和“更多 某某 的专辑”横排卡片，最后一张后面是“全部”，打开歌手页。
 */
private fun LazyListScope.albumFooter(collection: CollectionUiState, actions: MusicActions, horizontal: androidx.compose.ui.unit.Dp) {
    if (collection.kind != CollectionKind.Album || collection.songs.isEmpty()) return
    val album = collection.album
    val release = listOfNotNull(
        album?.publishTime?.takeIf { it.isNotBlank() }?.let { "$it 发行" },
        album?.company?.takeIf { it.isNotBlank() }?.let { "℗ $it" },
    )
    if (release.isNotEmpty()) {
        item(key = "albumRelease", contentType = "albumRelease") {
            Text(
                release.joinToString("\n"),
                Modifier.padding(horizontal = horizontal).padding(start = 4.dp, top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val more = collection.moreAlbums
    if (more.isEmpty()) return
    val artist = album?.artist?.let { com.mytv.mymusic.ui.artist.splitArtistNames(it).firstOrNull() }.orEmpty()
    item(key = "moreAlbums", contentType = "moreAlbums") {
        val tv = LocalAppWindow.current.isTv
        Column(Modifier.padding(top = 12.dp)) {
            com.mytv.mymusic.ui.components.SectionLabel(
                if (artist.isNotBlank()) "更多 $artist 的专辑" else "同一歌手的其他专辑",
                Modifier.padding(horizontal = horizontal),
                trailing = album?.artistId?.takeIf { it.isNotBlank() && artist.isNotBlank() }?.let { artistId ->
                    {
                        TextButton(
                            onClick = { actions.openArtist(Playlist(id = artistId, name = artist, source = collection.source)) },
                            modifier = Modifier.tvFocusRing(CircleShape),
                        ) { Text("歌手主页") }
                    }
                },
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = horizontal),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(more, key = { it.id.ifBlank { it.name } }, contentType = { "album" }) { item ->
                    AlbumCard(item, onClick = { actions.openAlbum(item) }, modifier = Modifier.width(if (tv) 150.dp else 140.dp))
                }
            }
        }
    }
}
