package com.mytv.mymusic.ui.artist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import coil3.compose.AsyncImage
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.AppNav
import com.mytv.mymusic.ui.EmptyState
import com.mytv.mymusic.ui.ErrorBlock
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.adaptive.FoldRow
import com.mytv.mymusic.ui.adaptive.LocalFoldPane
import com.mytv.mymusic.ui.adaptive.bottom
import com.mytv.mymusic.ui.charts.DiscoverErrorState
import com.mytv.mymusic.ui.charts.GridRow
import com.mytv.mymusic.ui.charts.SkeletonCardGrid
import com.mytv.mymusic.ui.charts.SongAction
import com.mytv.mymusic.ui.charts.SongActionsSheet
import com.mytv.mymusic.ui.charts.TvDefaultFocus
import com.mytv.mymusic.ui.charts.rememberCoverTint
import com.mytv.mymusic.ui.components.AppLoading
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalOverlayPadding
import com.mytv.mymusic.ui.components.PlayAllSplitButton
import com.mytv.mymusic.ui.components.ProviderIcon
import com.mytv.mymusic.ui.components.RequestDarkBars
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SkeletonRows
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.rememberRowSwipe
import com.mytv.mymusic.ui.components.TintedGradient
import com.mytv.mymusic.ui.components.distinctKeys
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.coverInitial
import com.mytv.mymusic.ui.formatDuration

/** 歌手页路由：参数是 Uri 编码过的页面 key（见 [artistKey]）。 */
const val ARTIST_ROUTE = "artist"

fun artistRoutes(graph: NavGraphBuilder, app: AppNav) {
    graph.composable("$ARTIST_ROUTE/{key}") { entry ->
        val key = entry.arguments?.getString("key").orEmpty()
        // 进程被回收后从返回栈恢复时页面状态是空的，按 key 重新加载
        LaunchedEffect(key) { app.model.ensureArtist(key) }
        val state by app.model.state.collectAsState()
        val pages by app.model.artistPagesState.collectAsState()
        val favorites by app.model.favorites.collectAsState()
        val localPlaylists by app.model.localPlaylists.collectAsState()
        val artist = pages[key] ?: ArtistUiState(
            key = key,
            source = parseArtistKey(key)?.first.orEmpty(),
            name = parseArtistKey(key)?.third.orEmpty(),
            resolving = true,
        )
        ArtistScreen(state, artist, favorites, localPlaylists, app.actions, onBack = app::back)
    }
}

internal enum class ArtistTab { Songs, Albums, About }

/** 歌手页的主操作，两种布局共用。 */
private class ArtistOps(
    val play: () -> Unit,
    val shuffle: () -> Unit,
    val queue: () -> Unit,
    val save: () -> Unit,
    val retry: () -> Unit,
    val loadMoreAlbums: () -> Unit,
    val openAlbum: (com.mytv.mymusic.data.AlbumSummary) -> Unit,
)

/**
 * 歌手页。手机：整张歌手照片铺满头部、向下渐隐到页面底色，名字压在照片底部；下面是数字、播放按钮和
 * 吸顶的“热门歌曲 / 专辑 / 简介”切换。平板（≥840dp）和电视：左栏圆形头像、名字和操作不随列表滚动，右栏是三个分页。
 * 头部配色取头像的主色（和详情页一样），资料、歌曲、专辑各自加载，谁先回来先画谁。
 */
@Composable
internal fun ArtistScreen(
    state: MusicUiState,
    artist: ArtistUiState,
    favorites: List<SavedSong>,
    playlists: List<LocalPlaylistSummary>,
    actions: MusicActions,
    onBack: () -> Unit,
) {
    var tab by rememberSaveable(artist.key) { mutableStateOf(ArtistTab.Songs) }
    var target by remember { mutableStateOf<SongAction?>(null) }
    val tint = rememberCoverTint(artist.displayAvatar, actions)
    val songs = artist.visibleSongs
    val onMore: (Song) -> Unit = remember(songs) { { song -> target = SongAction(song, songs) } }
    val key = artist.key
    val ops = remember(actions, key) {
        ArtistOps(
            play = { actions.playArtist(key) },
            shuffle = { actions.playArtist(key, shuffle = true) },
            queue = { actions.queueArtist(key) },
            save = { actions.saveArtistSongs(key) },
            retry = { actions.retryArtist(key) },
            loadMoreAlbums = { actions.loadMoreArtistAlbums(key) },
            openAlbum = actions::openAlbum,
        )
    }
    val playingKey = state.current?.key
    if (LocalAppWindow.current.twoPane) {
        TwoPaneArtist(artist, tab, { tab = it }, tint, ops, actions, playingKey, onMore, onBack)
    } else {
        PhoneArtist(artist, tab, { tab = it }, tint, ops, actions, playingKey, onMore, onBack)
    }
    target?.let {
        SongActionsSheet(
            it,
            favorites,
            playlists,
            actions,
            onDismiss = { target = null },
            currentArtist = artist.displayName,
        )
    }
}

// ---------------- 手机 ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneArtist(
    artist: ArtistUiState,
    tab: ArtistTab,
    onTab: (ArtistTab) -> Unit,
    tint: State<Color>,
    ops: ArtistOps,
    actions: MusicActions,
    playingKey: String?,
    onMore: (Song) -> Unit,
    onBack: () -> Unit,
) {
    val gutter = LocalAppWindow.current.gutter
    val overlay = LocalOverlayPadding.current.calculateBottomPadding()
    val surface = MaterialTheme.colorScheme.surface
    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val photo = artist.displayAvatar
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight = statusTop + 64.dp
    // 照片压在顶栏下面：照片露出的高度 = 照片高 - 顶栏高，列表第一项占这么高，名字落在照片底部
    BoxWithConstraints(Modifier.fillMaxSize().background(surface)) {
        val photoHeight = min(maxWidth, 460.dp).coerceAtLeast(barHeight + 180.dp)
        // 照片上方用深色压暗，系统栏图标改成浅色；滚过头部后顶栏变实色，图标跟主题
        RequestDarkBars(!collapsed && (photo.isNotBlank() || MaterialTheme.colorScheme.surface.luminance() < 0.5f))
        ArtistBackdrop(
            photo = photo,
            name = artist.displayName,
            tint = tint,
            listState = listState,
            modifier = Modifier.fillMaxWidth().height(photoHeight),
        )
        Column(Modifier.fillMaxSize()) {
            val barColor by animateColorAsState(
                if (collapsed) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
                label = "artistBar",
            )
            val iconColor = if (collapsed || photo.isBlank()) MaterialTheme.colorScheme.onSurface else Color.White
            TopAppBar(
                title = {
                    AnimatedVisibility(collapsed, enter = fadeIn(), exit = fadeOut()) {
                        Text(artist.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = iconColor) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor),
            )
            val songKeys = remember(artist.visibleSongs) { distinctKeys(artist.visibleSongs) { it.key } }
            BoxWithConstraints(Modifier.weight(1f)) {
                val columns = albumColumns(maxWidth - gutter * 2)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = overlay + 24.dp),
                ) {
                    item(key = "hero", contentType = "hero") {
                        Box(Modifier.fillMaxWidth().height(photoHeight - barHeight), contentAlignment = Alignment.BottomStart) {
                            ArtistNameBlock(artist, large = false, modifier = Modifier.padding(horizontal = gutter).padding(bottom = 4.dp))
                        }
                    }
                    item(key = "stats", contentType = "stats") {
                        Column(Modifier.padding(horizontal = gutter).padding(top = 12.dp, bottom = 16.dp)) {
                            ArtistStats(artist)
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlayAllSplitButton(
                                    onPlayAll = ops.play,
                                    onShuffle = ops.shuffle,
                                    onQueue = ops.queue,
                                    onSave = ops.save,
                                    label = "播放热门",
                                    saveLabel = "存为本地歌单",
                                )
                                FilledTonalIconButton(onClick = ops.shuffle, modifier = Modifier.size(48.dp).tvFocusRing(CircleShape)) {
                                    Icon(Icons.Filled.Shuffle, "随机播放")
                                }
                            }
                        }
                    }
                    stickyHeader(key = "tabs", contentType = "tabs") {
                        ArtistTabs(
                            artist,
                            tab,
                            onTab,
                            Modifier
                                .fillMaxWidth()
                                .background(surface)
                                .padding(horizontal = gutter, vertical = 8.dp),
                        )
                    }
                    artistTabContent(artist, tab, ops, actions, playingKey, songKeys, onMore, columns, gutter)
                }
            }
        }
    }
}

/**
 * 手机头部的照片层：在列表后面，跟着第一项一起滚走；照片在框里再慢一点（视差）。
 * 上沿压暗给状态栏和返回键，下沿渐隐到页面底色，名字压在渐隐区里。没有照片时用头像主色的渐变 + 装饰形状里的首字。
 */
@Composable
private fun ArtistBackdrop(
    photo: String,
    name: String,
    tint: State<Color>,
    listState: LazyListState,
    modifier: Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .graphicsLayer {
                translationY = if (listState.firstVisibleItemIndex == 0) -listState.firstVisibleItemScrollOffset.toFloat() else -size.height
            }
            .clipToBounds(),
    ) {
        if (photo.isNotBlank()) {
            AsyncImage(
                model = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 照片比框慢 40%：往上滚时照片像在框里往下沉
                        if (listState.firstVisibleItemIndex == 0) translationY = listState.firstVisibleItemScrollOffset * 0.4f
                    },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val fade = TintedGradient(Brush.verticalGradient(0f to Color.White, 1f to Color.Transparent))
                        onDrawBehind { fade.draw(this, tint.value) }
                    },
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 40.dp)
                    .size(168.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    coverInitial(name),
                    Modifier.clearAndSetSemantics {},
                    fontSize = 72.sp,
                    lineHeight = 72.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to surface.copy(alpha = 0.82f), 1f to surface)),
        )
    }
}

// ---------------- 平板 / 电视 ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TwoPaneArtist(
    artist: ArtistUiState,
    tab: ArtistTab,
    onTab: (ArtistTab) -> Unit,
    tint: State<Color>,
    ops: ArtistOps,
    actions: MusicActions,
    playingKey: String?,
    onMore: (Song) -> Unit,
    onBack: () -> Unit,
) {
    val window = LocalAppWindow.current
    val tv = window.isTv
    val overlay = LocalOverlayPadding.current.calculateBottomPadding()
    val surface = MaterialTheme.colorScheme.surface
    val playFocus = remember { FocusRequester() }
    TvDefaultFocus(playFocus)
    val songKeys = remember(artist.visibleSongs) { distinctKeys(artist.visibleSongs) { it.key } }
    Box(
        Modifier
            .fillMaxSize()
            .background(surface)
            .drawWithCache {
                // 头像主色的两团环境光（和详情页电视版同一种做法），平板也用：左上照亮头像，右下压住列表
                val top = TintedGradient(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0f)),
                        Offset(size.width * 0.14f, size.height * 0.22f),
                        size.maxDimension * 0.55f,
                    ),
                )
                val bottom = TintedGradient(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0f)),
                        Offset(size.width * 0.88f, size.height * 0.96f),
                        size.maxDimension * 0.5f,
                    ),
                )
                onDrawBehind {
                    val color = tint.value
                    top.draw(this, color)
                    bottom.draw(this, color)
                }
            },
    ) {
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
                    ArtistPortrait(artist, tint, if (tv) 200.dp else 232.dp)
                    Spacer(Modifier.height(20.dp))
                    ArtistNameBlock(artist, large = true)
                    Spacer(Modifier.height(16.dp))
                    ArtistStats(artist)
                    Spacer(Modifier.height(20.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tv) {
                            // 电视不用下拉菜单：播放 + 随机 + 加入队列平铺，焦点默认在“播放热门”
                            Button(
                                onClick = ops.play,
                                modifier = Modifier.focusRequester(playFocus).heightIn(min = 48.dp).tvFocusRing(CircleShape),
                            ) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text("播放热门", Modifier.semantics { contentDescription = "播放${artist.displayName}的热门歌曲" })
                            }
                            FilledTonalIconButton(onClick = ops.shuffle, modifier = Modifier.size(48.dp).tvFocusRing(CircleShape)) {
                                Icon(Icons.Filled.Shuffle, "随机播放")
                            }
                            FilledTonalIconButton(onClick = ops.queue, modifier = Modifier.size(48.dp).tvFocusRing(CircleShape)) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, "加入播放队列")
                            }
                        } else {
                            PlayAllSplitButton(
                                onPlayAll = ops.play,
                                onShuffle = ops.shuffle,
                                onQueue = ops.queue,
                                onSave = ops.save,
                                label = "播放热门",
                                saveLabel = "存为本地歌单",
                            )
                            FilledTonalIconButton(onClick = ops.shuffle, modifier = Modifier.tvFocusRing(CircleShape)) {
                                Icon(Icons.Filled.Shuffle, "随机播放")
                            }
                        }
                    }
                }
            },
            end = {
                Column(Modifier.fillMaxHeight()) {
                    ArtistTabs(artist, tab, onTab, Modifier.fillMaxWidth().padding(top = if (tv) 0.dp else 56.dp, bottom = 12.dp))
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val columns = albumColumns(maxWidth)
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = overlay + 48.dp),
                        ) {
                            artistTabContent(artist, tab, ops, actions, playingKey, songKeys, onMore, columns, 0.dp)
                        }
                    }
                }
            },
        )
    }
}

/** 平板 / 电视左栏的圆形头像：外面一圈头像主色的光环。 */
@Composable
private fun ArtistPortrait(artist: ArtistUiState, tint: State<Color>, size: Dp) {
    Box(
        Modifier
            .size(size + 16.dp)
            .drawWithCache {
                val halo = TintedGradient(
                    Brush.radialGradient(
                        0.62f to Color.White,
                        1f to Color.White.copy(alpha = 0f),
                        center = Offset(this.size.width / 2f, this.size.height / 2f),
                        radius = this.size.minDimension / 2f,
                    ),
                )
                onDrawBehind { halo.draw(this, tint.value) }
            },
        contentAlignment = Alignment.Center,
    ) {
        com.mytv.mymusic.ui.CoverBox(artist.displayAvatar, artist.displayName, Modifier.size(size), CircleShape, size.value * 0.34f)
    }
}

// ---------------- 共用片段 ----------------

/** 平台标记 + 名字 + 别名。手机压在照片底部，平板 / 电视在左栏头像下面。 */
@Composable
private fun ArtistNameBlock(artist: ArtistUiState, large: Boolean, modifier: Modifier = Modifier) {
    val tv = LocalAppWindow.current.isTv
    Column(modifier) {
        if (artist.sourceName.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                ProviderIcon(id = artist.source, name = artist.sourceName, size = 18.dp, corner = 5.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${artist.sourceName} · 歌手",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(
            artist.displayName.ifBlank { "歌手" },
            Modifier.semantics { heading() },
            style = when {
                tv -> MaterialTheme.typography.displaySmall
                large -> MaterialTheme.typography.headlineLarge
                else -> MaterialTheme.typography.displaySmall
            },
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val alias = artist.profile?.alias.orEmpty()
        if (alias.isNotBlank()) {
            Text(
                alias,
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 歌曲 / 专辑 / 粉丝三个数字，没有的不显示；一个都没有时不占位。 */
@Composable
private fun ArtistStats(artist: ArtistUiState) {
    val stats = buildList {
        if (artist.songCount > 0) add(compactCount(artist.songCount.toLong()) to "首歌曲")
        if (artist.albumCount > 0) add(compactCount(artist.albumCount.toLong()) to if (artist.albumsDerived) "张专辑（已读到）" else "张专辑")
        val fans = artist.profile?.fans ?: 0L
        if (fans > 0) add(compactCount(fans) to "位粉丝")
        val mvs = artist.profile?.mvCount ?: 0
        if (mvs > 0 && size < 3) add(compactCount(mvs.toLong()) to "支 MV")
    }
    if (stats.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        stats.forEach { (value, label) -> StatBlock(value, label) }
    }
}

/** 三个分页的切换：连接式按钮组，专辑数直接写在按钮上。 */
@Composable
private fun ArtistTabs(artist: ArtistUiState, tab: ArtistTab, onTab: (ArtistTab) -> Unit, modifier: Modifier = Modifier) {
    ConnectedToggleGroup(
        options = ArtistTab.entries,
        selected = tab,
        onSelect = onTab,
        label = {
            when (it) {
                ArtistTab.Songs -> "热门歌曲"
                ArtistTab.Albums -> if (artist.albumCount > 0) "专辑 ${artist.albumCount}" else "专辑"
                ArtistTab.About -> "简介"
            }
        },
        icon = {
            when (it) {
                ArtistTab.Songs -> Icons.Filled.MusicNote
                ArtistTab.Albums -> Icons.Filled.Album
                ArtistTab.About -> null
            }
        },
        modifier = modifier,
    )
}

/** 专辑网格的列数：卡片最窄 150dp（电视 132dp），2 到 6 列。 */
@Composable
private fun albumColumns(width: Dp): Int {
    val min = if (LocalAppWindow.current.isTv) 132.dp else 150.dp
    val gap = 12.dp
    return ((width + gap) / (min + gap)).toInt().coerceIn(2, 6)
}

/** 当前分页的内容：热门歌曲是分段列表，专辑是网格（到底自动加载下一页），简介是正文 + 基本资料。 */
private fun LazyListScope.artistTabContent(
    artist: ArtistUiState,
    tab: ArtistTab,
    ops: ArtistOps,
    actions: MusicActions,
    playingKey: String?,
    songKeys: List<String>,
    onMore: (Song) -> Unit,
    columns: Int,
    horizontal: Dp,
) {
    if (artist.resolving) {
        item(key = "resolving", contentType = "skeleton") {
            Column(Modifier.padding(horizontal = horizontal)) {
                AppLoading(text = "正在${artist.sourceName.ifBlank { "平台" }}上找这位歌手…")
                SkeletonRows(6)
            }
        }
        return
    }
    if (artist.resolveError.isNotBlank()) {
        item(key = "resolveError", contentType = "error") {
            DiscoverErrorState(
                title = "没有找到这位歌手",
                message = artist.resolveError,
                onRetry = ops.retry,
            )
        }
        return
    }
    when (tab) {
        ArtistTab.Songs -> artistSongs(artist, ops, actions, playingKey, songKeys, onMore, horizontal)
        ArtistTab.Albums -> artistAlbums(artist, ops, columns, horizontal)
        ArtistTab.About -> artistAbout(artist, horizontal)
    }
}

private fun LazyListScope.artistSongs(
    artist: ArtistUiState,
    ops: ArtistOps,
    actions: MusicActions,
    playingKey: String?,
    songKeys: List<String>,
    onMore: (Song) -> Unit,
    horizontal: Dp,
) {
    val songs = artist.visibleSongs
    when {
        songs.isEmpty() && artist.songsLoading -> item(key = "songsSkeleton", contentType = "skeleton") {
            SkeletonRows(8, Modifier.padding(horizontal = horizontal))
        }
        songs.isEmpty() && artist.songsError.isNotBlank() -> item(key = "songsError", contentType = "error") {
            DiscoverErrorState(
                title = "歌曲没有加载出来",
                message = listOf(artist.sourceName, artist.songsError).filter { it.isNotBlank() }.joinToString("："),
                onRetry = ops.retry,
            )
        }
        songs.isEmpty() -> item(key = "songsEmpty", contentType = "empty") {
            EmptyState(
                icon = Icons.Filled.MusicNote,
                title = "这位歌手还没有歌曲",
                message = "稍后重试，或换一个平台搜索。",
                actionLabel = "重试",
                onAction = ops.retry,
            )
        }
        else -> itemsIndexed(songs, key = { index, _ -> "song:" + songKeys[index] }, contentType = { _, _ -> "song" }) { index, song ->
            SongRow(
                title = song.title,
                // 歌手就是这一页，副标题给专辑
                subtitle = song.album.ifBlank { song.artist },
                cover = song.cover,
                index = index,
                count = songs.size,
                onClick = { actions.play(song, songs) },
                modifier = Modifier
                    .padding(horizontal = horizontal)
                    .padding(bottom = if (index < songs.lastIndex) SegmentGap else 0.dp),
                rank = index + 1,
                playing = playingKey == song.key,
                trailing = formatDuration(song.duration).takeIf { it.isNotBlank() }?.let { time ->
                    {
                        Text(
                            time,
                            Modifier.padding(start = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onMore = { onMore(song) },
                // 歌手就是这一页：「查看歌手」不给他自己（合唱的给另一位）
                swipe = rememberRowSwipe(song, songs, hideArtist = artist.displayName),
            )
        }
    }
}

private fun LazyListScope.artistAlbums(
    artist: ArtistUiState,
    ops: ArtistOps,
    columns: Int,
    horizontal: Dp,
) {
    val albums = artist.albums
    when {
        albums.isEmpty() && (artist.albumsLoading || (artist.albumsDerived && artist.songsLoading)) ->
            item(key = "albumsSkeleton", contentType = "skeleton") {
                SkeletonCardGrid(columns, rows = 2, modifier = Modifier.padding(horizontal = horizontal))
            }
        albums.isEmpty() && artist.albumsError.isNotBlank() -> item(key = "albumsError", contentType = "error") {
            Box(Modifier.padding(horizontal = horizontal)) { ErrorBlock(artist.albumsError, ops.retry) }
        }
        albums.isEmpty() -> item(key = "albumsEmpty", contentType = "empty") {
            EmptyState(
                icon = Icons.Filled.Album,
                title = "没有找到专辑",
                message = "这个平台没有给出这位歌手的专辑。",
            )
        }
        else -> {
            item(key = "albumsHeader", contentType = "albumsHeader") {
                val total = artist.albumCount
                Text(
                    when {
                        artist.albumsDerived -> "从歌曲里整理出 ${albums.size} 张专辑"
                        total > 0 -> "共 $total 张 · 按发行时间排序"
                        else -> "按发行时间排序"
                    },
                    Modifier.padding(horizontal = horizontal).padding(start = 4.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val rows = albums.chunked(columns)
            itemsIndexed(rows, key = { index, row -> "albums:$index:" + row.first().id.ifBlank { row.first().name } }, contentType = { _, _ -> "albumRow" }) { index, row ->
                GridRow(row, columns, Modifier.padding(horizontal = horizontal).padding(bottom = 16.dp)) { album, itemModifier ->
                    AlbumCard(album, onClick = { ops.openAlbum(album) }, modifier = itemModifier)
                }
                // 滚到最后一排时取下一页
                if (index == rows.lastIndex && artist.albumsHasMore) {
                    LaunchedEffect(albums.size) { ops.loadMoreAlbums() }
                }
            }
            if (artist.albumsLoading) {
                item(key = "albumsMore", contentType = "loading") { AppLoading() }
            } else if (artist.albumsError.isNotBlank()) {
                item(key = "albumsMoreError", contentType = "error") {
                    Box(Modifier.padding(horizontal = horizontal)) { ErrorBlock(artist.albumsError, ops.loadMoreAlbums) }
                }
            }
        }
    }
}

private fun LazyListScope.artistAbout(artist: ArtistUiState, horizontal: Dp) {
    val profile = artist.profile
    val paragraphs = profile?.description.orEmpty()
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val facts = profile?.facts.orEmpty().filter { it.first.isNotBlank() && it.second.isNotBlank() }
    if (paragraphs.isEmpty() && facts.isEmpty()) {
        item(key = "aboutEmpty", contentType = "empty") {
            if (artist.songsLoading && profile == null) {
                AppLoading()
            } else {
                EmptyState(
                    icon = Icons.Filled.PersonSearch,
                    title = "暂无简介",
                    message = "${artist.sourceName.ifBlank { "这个平台" }}没有提供这位歌手的介绍。",
                )
            }
        }
        return
    }
    if (facts.isNotEmpty()) {
        item(key = "factsLabel", contentType = "label") {
            AboutLabel("基本资料", Modifier.padding(horizontal = horizontal))
        }
        itemsIndexed(facts, key = { index, fact -> "fact:$index:${fact.first}" }, contentType = { _, _ -> "fact" }) { index, (label, value) ->
            FactRow(label, value, index, facts.size, Modifier.padding(horizontal = horizontal).padding(bottom = if (index < facts.lastIndex) SegmentGap else 0.dp))
        }
    }
    if (paragraphs.isNotEmpty()) {
        item(key = "introLabel", contentType = "label") {
            AboutLabel("歌手介绍", Modifier.padding(horizontal = horizontal).padding(top = if (facts.isNotEmpty()) 12.dp else 0.dp))
        }
        // 每段一项：长简介在电视上也能一段一段地用方向键往下翻
        itemsIndexed(paragraphs, key = { index, _ -> "para:$index" }, contentType = { _, _ -> "paragraph" }) { index, text ->
            Paragraph(text, index, paragraphs.size, Modifier.padding(horizontal = horizontal))
        }
    }
}

@Composable
private fun AboutLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 4.dp, top = 4.dp, bottom = 10.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

/** 基本资料一行：左边灰色的键，右边值；分段卡片，和设置页的列表同一种形状。 */
@Composable
private fun FactRow(label: String, value: String, index: Int, count: Int, modifier: Modifier = Modifier) {
    val shape = segmentShape(index, count)
    val tv = LocalAppWindow.current.isTv
    Row(
        modifier
            .fillMaxWidth()
            .then(if (tv) Modifier.tvFocusRing(shape, scale = 1.01f).focusable() else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            Modifier.width(88.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 简介的一段：整段在一张卡片里（首段、末段圆角大一点），电视上每段可获焦点，方向键往下翻。 */
@Composable
private fun Paragraph(text: String, index: Int, count: Int, modifier: Modifier = Modifier) {
    val shape = segmentShape(index, count)
    val tv = LocalAppWindow.current.isTv
    Surface(
        modifier
            .fillMaxWidth()
            .padding(bottom = if (index < count - 1) SegmentGap else 0.dp)
            .then(if (tv) Modifier.tvFocusRing(shape, scale = 1.01f).focusable() else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
