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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
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
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.charts.DiscoverErrorState
import com.mytv.mymusic.ui.charts.SongAction
import com.mytv.mymusic.ui.charts.SongActionsSheet
import com.mytv.mymusic.ui.charts.TvDefaultFocus
import com.mytv.mymusic.ui.charts.rememberCoverTint
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalOverlayPadding
import com.mytv.mymusic.ui.components.PlatformBadge
import com.mytv.mymusic.ui.components.PlayAllSplitButton
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SkeletonRows
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.distinctKeys
import com.mytv.mymusic.ui.components.tvFocusRing

/** 详情页的几种主操作，三种布局共用。 */
private class CollectionOps(
    val playAll: () -> Unit,
    val shuffle: () -> Unit,
    val queue: () -> Unit,
    val save: () -> Unit,
    val openPlaybackSettings: (() -> Unit)?,
)

/** 已收藏 = 这次会话里刚保存，或已有同名本地歌单。 */
internal fun collectionSaved(title: String, playlists: List<LocalPlaylistSummary>, savedNow: Boolean): Boolean {
    val trimmed = title.trim()
    return savedNow || (trimmed.isNotEmpty() && playlists.any { it.name == trimmed })
}

/**
 * 榜单 / 歌单 / 专辑详情。手机：头部渐变取封面色，Split button 播放全部，分段曲目列表；
 * 平板（≥840dp）和电视：左栏封面、标题和操作不随列表滚动，右栏是曲目。
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
    // 没有“收藏歌单”的数据模型：收藏 = 存成本地歌单，同名本地歌单视为已收藏
    var savedNow by rememberSaveable(collection.kind, collection.id) { mutableStateOf(false) }
    val saved = remember(collection.title, playlists, savedNow) {
        collectionSaved(collection.title, playlists, savedNow)
    }
    val songs = collection.songs
    val settingsTarget = rememberUpdatedState(onOpenPlaybackSettings)
    val openPlaybackSettings: () -> Unit = remember { { settingsTarget.value() } }
    val ops = remember(actions, songs, collection.id, collection.kind, openPlaybackSettings) {
        CollectionOps(
            playAll = actions::playCollection,
            shuffle = {
                val shuffled = songs.shuffled()
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
    val onMore: (Song) -> Unit = remember(songs) { { song -> target = SongAction(song, songs) } }
    val playingKey = state.current?.key
    if (LocalAppWindow.current.twoPane) {
        TwoPaneCollection(collection, tint, ops, saved, actions, playingKey, onBack, onMore)
    } else {
        PhoneCollection(collection, tint, ops, saved, actions, playingKey, onBack, onMore)
    }
    target?.let { SongActionsSheet(it, favorites, playlists, actions) { target = null } }
}

@Composable
private fun PhoneCollection(
    collection: CollectionUiState,
    tint: State<Color>,
    ops: CollectionOps,
    saved: Boolean,
    actions: MusicActions,
    playingKey: String?,
    onBack: () -> Unit,
    onMore: (Song) -> Unit,
) {
    val gutter = LocalAppWindow.current.gutter
    val overlay = LocalOverlayPadding.current.calculateBottomPadding()
    val surface = MaterialTheme.colorScheme.surface
    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val songKeys = remember(collection.songs) { distinctKeys(collection.songs) { it.key } }
    Box(Modifier.fillMaxSize().background(surface)) {
        // 头部渐变跟着第一项一起滚走，滚过头部后顶栏换成实色。
        // 静态渐变缓存进 drawWithCache，动画色只作为 ColorFilter 的 tint 在绘制阶段读取。
        Box(
            Modifier
                .fillMaxWidth()
                .height(380.dp)
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
                    ops.openPlaybackSettings?.let { open ->
                        IconButton(onClick = open) { Icon(Icons.Filled.Tune, "换源设置") }
                    }
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
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = gutter).padding(top = 4.dp, bottom = 18.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CollectionCover(collection, large = false)
                        Column(Modifier.weight(1f)) { CollectionTitle(collection, large = false) }
                    }
                }
                item(key = "actions", contentType = "actions") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = gutter).padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlayAllSplitButton(
                            onPlayAll = ops.playAll,
                            onShuffle = ops.shuffle,
                            onQueue = ops.queue,
                            onSave = ops.save,
                            label = playLabel(collection),
                        )
                        SaveToggle(ops, saved)
                    }
                }
                collectionSongs(collection, actions, playingKey, songKeys, onMore, horizontal = gutter)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TwoPaneCollection(
    collection: CollectionUiState,
    tint: State<Color>,
    ops: CollectionOps,
    saved: Boolean,
    actions: MusicActions,
    playingKey: String?,
    onBack: () -> Unit,
    onMore: (Song) -> Unit,
) {
    val window = LocalAppWindow.current
    val tv = window.isTv
    val overlay = LocalOverlayPadding.current.calculateBottomPadding()
    val surface = MaterialTheme.colorScheme.surface
    val playFocus = remember { FocusRequester() }
    TvDefaultFocus(playFocus)
    val songKeys = remember(collection.songs) { distinctKeys(collection.songs) { it.key } }
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
        Row(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(start = window.gutter, end = window.gutter, top = if (tv) 36.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 40.dp else 32.dp),
        ) {
            Column(
                Modifier
                    .width(if (tv) 300.dp else 320.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = overlay + 24.dp),
            ) {
                if (!tv) {
                    IconButton(onClick = onBack, modifier = Modifier.padding(bottom = 8.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
                CollectionCover(collection, large = true)
                Spacer(Modifier.height(16.dp))
                CollectionTitle(collection, large = true)
                Spacer(Modifier.height(16.dp))
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
                        )
                    }
                    SaveToggle(ops, saved, if (tv) 48.dp else null)
                    ops.openPlaybackSettings?.let { open ->
                        FilledTonalIconButton(onClick = open, modifier = Modifier.size(48.dp).tvFocusRing(CircleShape)) {
                            Icon(Icons.Filled.Tune, "换源设置")
                        }
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(top = if (tv) 0.dp else 56.dp, bottom = overlay + 48.dp),
            ) {
                collectionSongs(collection, actions, playingKey, songKeys, onMore, horizontal = 0.dp)
            }
        }
    }
}

private fun playLabel(collection: CollectionUiState) = if (collection.kind == CollectionKind.Album) "播放专辑" else "播放全部"

/** 收藏 = 存成本地歌单。已收藏时只显示状态，取消收藏要去“我的”里删歌单（删除需要二次确认）。 */
@Composable
private fun SaveToggle(ops: CollectionOps, saved: Boolean, size: androidx.compose.ui.unit.Dp? = null) {
    FilledTonalIconToggleButton(
        checked = saved,
        onCheckedChange = { if (!saved) ops.save() },
        modifier = Modifier
            .then(if (size != null) Modifier.size(size) else Modifier)
            .tvFocusRing(CircleShape)
            .semantics { stateDescription = if (saved) "已存到本地歌单" else "未收藏" },
    ) {
        Icon(
            if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = "收藏到本地歌单",
        )
    }
}

/** 封面：榜单用 Arch 裁切做装饰，歌单 28dp 圆角，专辑 16dp 圆角（与平台封面观感一致）。 */
@Composable
private fun CollectionCover(collection: CollectionUiState, large: Boolean) {
    val (size, shape) = when (collection.kind) {
        CollectionKind.Chart -> (if (large) DpSize(240.dp, 270.dp) else DpSize(150.dp, 170.dp)) to MaterialShapes.Arch.toShape()
        CollectionKind.Album -> (if (large) DpSize(240.dp, 240.dp) else DpSize(140.dp, 140.dp)) to (RoundedCornerShape(16.dp) as Shape)
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

/** 曲目区：骨架 → 失败 / 空 → 分段列表；有歌但部分失败时在列表上方提示。 */
private fun LazyListScope.collectionSongs(
    collection: CollectionUiState,
    actions: MusicActions,
    playingKey: String?,
    songKeys: List<String>,
    onMore: (Song) -> Unit,
    horizontal: androidx.compose.ui.unit.Dp,
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
            EmptyState(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                title = "这里还没有歌曲",
                message = "稍后重试，或换一个来源。",
                actionLabel = "重试",
                onAction = actions::retryCollection,
            )
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
            itemsIndexed(
                songs,
                key = { index, _ -> songKeys[index] },
                contentType = { _, _ -> "song" },
            ) { index, song ->
                SongRow(
                    title = song.title,
                    subtitle = if (collection.kind == CollectionKind.Album) song.artist.ifBlank { song.subtitle } else song.subtitle,
                    cover = song.cover,
                    index = index,
                    count = songs.size,
                    onClick = { actions.play(song, songs) },
                    modifier = Modifier
                        .padding(horizontal = horizontal)
                        .padding(bottom = if (index < songs.lastIndex) SegmentGap else 0.dp),
                    rank = if (ranked) index + 1 else null,
                    playing = playingKey == song.key,
                    badges = songBadge(song, collection.sourceName),
                    trailing = formatDuration(song.duration).takeIf { it.isNotBlank() && collection.kind != CollectionKind.Chart }?.let { time ->
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
