package com.mytv.mymusic.ui.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.ListeningMode
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.ui.CoverBox
import com.mytv.mymusic.ui.EmptyState
import com.mytv.mymusic.ui.ErrorBlock
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicChip
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.PlaylistMode
import com.mytv.mymusic.ui.PlaylistsUiState
import com.mytv.mymusic.ui.charts.DiscoverCard
import com.mytv.mymusic.ui.charts.DiscoverErrorState
import com.mytv.mymusic.ui.charts.GridRow
import com.mytv.mymusic.ui.charts.ProviderChips
import com.mytv.mymusic.ui.charts.SkeletonCardGrid
import com.mytv.mymusic.ui.charts.TvDefaultFocus
import com.mytv.mymusic.ui.charts.formatPlayCount
import com.mytv.mymusic.ui.components.AppLoading
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.Skeleton
import com.mytv.mymusic.ui.components.tvCardFocus
import com.mytv.mymusic.ui.components.tvFocusRing

/**
 * 歌单：推荐 / 分类 / 听歌模式三种浏览方式。手机两列网格，平板四列且按钮组移到标题右侧；
 * 分类在手机和平板上滚到底自动追加下一页，电视保留翻页（左侧分类列表 + 右侧网格）。
 * 听歌模式是服务端实时生成的推荐流，点一个入口直接开播，不是固定曲目表。
 */
@Composable
fun PlaylistsScreen(
    state: MusicUiState,
    playlists: PlaylistsUiState,
    actions: MusicActions,
    onOpenSearch: () -> Unit,
    categoryEnd: Boolean = false,
) {
    val sources = state.providers.filter { it.recommendedPlaylists || it.playlistCategories || it.listeningModes }
    if (LocalAppWindow.current.isTv) {
        TvPlaylists(playlists, sources, actions, onOpenSearch)
    } else {
        PhonePlaylists(playlists, sources, actions, onOpenSearch, categoryEnd)
    }
}

private fun PlaylistsUiState.categoryMode() = mode == PlaylistMode.Category && canCategorize

private fun PlaylistsUiState.listeningMode() = mode == PlaylistMode.Listening && canListen

private fun playlistsSubtitle(playlists: PlaylistsUiState): String = listOf(
    playlists.providerName,
    when {
        playlists.categoryMode() -> playlists.selectedCategoryName.ifBlank { "分类" }
        playlists.listeningMode() -> "听歌模式"
        else -> "为你推荐"
    },
).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun ModeToggle(playlists: PlaylistsUiState, onSelect: (PlaylistMode) -> Unit, modifier: Modifier = Modifier) {
    val options = buildList {
        add(PlaylistMode.Recommend)
        if (playlists.canCategorize) add(PlaylistMode.Category)
        if (playlists.canListen) add(PlaylistMode.Listening)
    }
    if (options.size < 2) return
    ConnectedToggleGroup(
        options = options,
        selected = playlists.mode,
        onSelect = onSelect,
        label = {
            when (it) {
                PlaylistMode.Recommend -> "推荐"
                PlaylistMode.Category -> "分类"
                PlaylistMode.Listening -> "听歌模式"
            }
        },
        modifier = modifier,
        icon = {
            when (it) {
                PlaylistMode.Recommend -> Icons.Filled.AutoAwesome
                PlaylistMode.Category -> Icons.Filled.Category
                PlaylistMode.Listening -> Icons.Filled.Headphones
            }
        },
    )
}

@Composable
private fun PhonePlaylists(
    playlists: PlaylistsUiState,
    sources: List<ProviderInfo>,
    actions: MusicActions,
    onOpenSearch: () -> Unit,
    categoryEnd: Boolean,
) {
    val window = LocalAppWindow.current
    val gutter = window.gutter
    val categoryMode = playlists.categoryMode()
    val listeningMode = playlists.listeningMode()
    val listState = rememberLazyListState()
    PageScaffold(
        title = "歌单",
        subtitle = playlistsSubtitle(playlists),
        actions = {
            if (window.isTablet && (playlists.canCategorize || playlists.canListen)) {
                ModeToggle(playlists, actions::setPlaylistMode, Modifier.width(if (playlists.canListen) 400.dp else 240.dp))
            }
            IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, "搜索歌单") }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when {
                maxWidth < 480.dp -> 2
                maxWidth < 1000.dp -> 4
                else -> 5
            }
            val recommendRows = remember(playlists.recommendations, columns) {
                playlists.recommendations.drop(1).chunked(columns)
            }
            val categoryRows = remember(playlists.categoryPlaylists, columns) {
                playlists.categoryPlaylists.chunked(columns)
            }
            val groups = remember(playlists.categories) { categoryGroups(playlists.categories) }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
                item(key = "sources", contentType = "sources") { ProviderChips(sources, playlists.providerId, actions::selectPlaylistSource) }
                if (!window.isTablet && (playlists.canCategorize || playlists.canListen)) {
                    item(key = "mode", contentType = "mode") {
                        ModeToggle(
                            playlists,
                            actions::setPlaylistMode,
                            Modifier.padding(horizontal = gutter, vertical = 8.dp).fillMaxWidth(),
                        )
                    }
                }
                if (categoryMode) {
                    categoryContent(playlists, actions, columns, gutter, categoryEnd, categoryRows, groups)
                } else if (listeningMode) {
                    listeningContent(playlists, actions, columns, gutter)
                } else {
                    recommendContent(playlists, sources, actions, columns, gutter, recommendRows, featureHeight = if (window.isTablet) 220.dp else 176.dp)
                }
            }
        }
    }
    if (categoryMode) {
        AutoLoadMore(listState, playlists, categoryEnd, actions)
    }
}

/** 列表最后三项进入屏幕时追加下一页；出错后不自动重试，等用户点“重试”。 */
@Composable
private fun AutoLoadMore(listState: LazyListState, playlists: PlaylistsUiState, end: Boolean, actions: MusicActions) {
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, playlists.categoryPlaylists.size, playlists.categoryLoading, playlists.categoryError, end) {
        if (nearEnd && !end && playlists.categoryOpen && !playlists.categoryLoading &&
            playlists.categoryError.isBlank() && playlists.categoryPlaylists.isNotEmpty()
        ) {
            actions.loadMoreCategory()
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recommendContent(
    playlists: PlaylistsUiState,
    sources: List<ProviderInfo>,
    actions: MusicActions,
    columns: Int,
    gutter: Dp,
    restRows: List<List<Playlist>>,
    featureHeight: Dp,
) {
    val other = sources.firstOrNull { it.id != playlists.providerId }
    val items = playlists.recommendations
    // 听歌模式的入口放在推荐页顶部：一步开播，不用先进模式页
    if (playlists.canListen) {
        item(key = "quickListen", contentType = "quickListen") {
            QuickListenCard(
                starting = playlists.quickListenStarting,
                onClick = actions::quickListen,
                modifier = Modifier.padding(horizontal = gutter).padding(top = 4.dp, bottom = 12.dp),
            )
        }
    }
    when {
        !playlists.canRecommend -> item(key = "unsupported", contentType = "unsupported") {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "这个平台没有推荐歌单",
                message = if (playlists.canCategorize) "可以按分类浏览，或换一个来源。" else "换一个来源，或到搜索页直接查找歌单。",
                actionLabel = if (playlists.canCategorize) "按分类浏览" else other?.let { "换成${it.name}" },
                onAction = {
                    if (playlists.canCategorize) actions.setPlaylistMode(PlaylistMode.Category)
                    else other?.let { actions.selectPlaylistSource(it.id) }
                },
            )
        }
        items.isEmpty() && playlists.recommendationsLoading -> item(key = "skeleton", contentType = "skeleton") {
            Column(Modifier.padding(horizontal = gutter)) {
                Skeleton(Modifier.padding(top = 20.dp).fillMaxWidth().height(featureHeight), RoundedCornerShape(28.dp))
                AppLoading(contained = true)
                SkeletonCardGrid(columns)
            }
        }
        items.isEmpty() && playlists.recommendationsError.isNotBlank() -> item(key = "error", contentType = "error") {
            DiscoverErrorState(
                title = "推荐歌单没有加载出来",
                message = "${playlists.providerName}：${playlists.recommendationsError}",
                onRetry = actions::refreshPlaylists,
                secondaryLabel = other?.let { "换成${it.name}" },
                onSecondary = other?.let { { actions.selectPlaylistSource(it.id) } },
            )
        }
        items.isEmpty() -> item(key = "empty", contentType = "empty") {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "这个平台暂时没有推荐歌单",
                message = "换一个来源，或到搜索页直接查找歌单。",
                actionLabel = "刷新",
                onAction = actions::refreshPlaylists,
            )
        }
        else -> {
            val lead = items.first()
            item(key = "leadLabel", contentType = "label") {
                SectionLabel("编辑推荐", Modifier.padding(horizontal = gutter)) {
                    TextButton(onClick = actions::refreshPlaylists, modifier = Modifier.heightIn(min = 48.dp)) { Text("换一批") }
                }
            }
            item(key = "lead", contentType = "lead") {
                FeaturePlaylist(lead, featureHeight, { actions.openPlaylist(lead) }, Modifier.padding(horizontal = gutter))
            }
            val rest = items.drop(1)
            if (rest.isNotEmpty()) {
                item(key = "restLabel", contentType = "label") { SectionLabel("为你推荐", Modifier.padding(horizontal = gutter)) }
                items(
                    restRows,
                    key = { row -> "rest:${row.first().id}" },
                    contentType = { "playlistRow" },
                ) { row ->
                    PlaylistGridRow(row, columns, actions, Modifier.padding(horizontal = gutter).padding(bottom = 16.dp))
                }
            }
            if (playlists.recommendationsError.isNotBlank()) {
                item(key = "partial", contentType = "partial") {
                    Box(Modifier.padding(horizontal = gutter, vertical = 8.dp)) {
                        ErrorBlock(playlists.recommendationsError, actions::refreshPlaylists)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.categoryContent(
    playlists: PlaylistsUiState,
    actions: MusicActions,
    columns: Int,
    gutter: Dp,
    end: Boolean,
    rows: List<List<Playlist>>,
    groups: List<String>,
) {
    if (playlists.categories.isEmpty() && playlists.categoriesLoading) {
        item(key = "chipSkeleton", contentType = "skeleton") {
            Row(Modifier.padding(horizontal = gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(5) { Skeleton(Modifier.width(64.dp).height(32.dp), RoundedCornerShape(8.dp)) }
            }
        }
    }
    if (playlists.categoriesError.isNotBlank()) {
        item(key = "categoriesError", contentType = "error") {
            Box(Modifier.padding(horizontal = gutter, vertical = 8.dp)) {
                ErrorBlock("分类列表没有加载出来：${playlists.categoriesError}", actions::refreshPlaylists)
            }
        }
    }
    if (playlists.categories.isNotEmpty()) {
        item(key = "groups", contentType = "groups") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = gutter, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups, key = { it }, contentType = { "group" }) { group ->
                    MusicChip(group, playlists.categoryGroup == group) { actions.setCategoryGroup(group) }
                }
            }
        }
        item(key = "categories", contentType = "categories") {
            CategoryChips(playlists, actions, Modifier.padding(horizontal = gutter))
        }
    }
    item(key = "categoryHead", contentType = "head") {
        val selected = playlists.categories.find { it.id == playlists.selectedCategoryId }
        SectionLabel(playlists.selectedCategoryName.ifBlank { "全部歌单" }, Modifier.padding(horizontal = gutter)) {
            if (selected != null && selected.count > 0) {
                Text("共 ${selected.count} 个歌单", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val list = playlists.categoryPlaylists
    when {
        list.isEmpty() && (playlists.categoryLoading || !playlists.categoryOpen) -> item(key = "gridSkeleton", contentType = "skeleton") {
            SkeletonCardGrid(columns, rows = 2, modifier = Modifier.padding(horizontal = gutter))
        }
        list.isEmpty() && playlists.categoryError.isNotBlank() -> item(key = "gridError", contentType = "error") {
            DiscoverErrorState(
                title = "这个分类没有加载出来",
                message = playlists.categoryError,
                onRetry = actions::loadMoreCategory,
            )
        }
        list.isEmpty() -> item(key = "gridEmpty", contentType = "empty") {
            EmptyState(
                icon = Icons.Filled.Category,
                title = "这个分类下还没有歌单",
                message = "换一个分类看看。",
            )
        }
        else -> {
            items(
                rows,
                key = { row -> "category:${row.first().id}" },
                contentType = { "playlistRow" },
            ) { row ->
                PlaylistGridRow(row, columns, actions, Modifier.padding(horizontal = gutter).padding(bottom = 16.dp))
            }
            item(key = "footer", contentType = "footer") {
                Box(Modifier.fillMaxWidth().padding(horizontal = gutter), contentAlignment = Alignment.Center) {
                    when {
                        playlists.categoryLoading -> AppLoading(contained = true, text = "正在加载第 ${playlists.categoryPage + 1} 页")
                        playlists.categoryError.isNotBlank() -> Box(Modifier.padding(vertical = 8.dp)) {
                            ErrorBlock("下一页没有加载出来：${playlists.categoryError}", actions::loadMoreCategory)
                        }
                        end -> Text(
                            "没有更多了",
                            Modifier.padding(vertical = 20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Spacer(Modifier.height(48.dp))
                    }
                }
            }
        }
    }
}

/** 分类 chip：手机上默认只展示前 12 个，避免把网格挤到第二屏。 */
// ---------------- 听歌模式 ----------------

/**
 * 听歌模式：分组 chip + 封面卡片网格。卡片上直接写着“点一下开始播”，
 * 和歌单卡片的区别是点下去不进详情页，而是立刻换成一个实时推荐队列。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.listeningContent(
    playlists: PlaylistsUiState,
    actions: MusicActions,
    columns: Int,
    gutter: Dp,
) {
    val groups = playlists.listenGroups
    if (groups.isNotEmpty()) {
        item(key = "listenGroups", contentType = "groups") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = gutter, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "listenGroupAll", contentType = "group") {
                    MusicChip("全部", playlists.listenGroup == "全部") { actions.selectListenGroup("全部") }
                }
                items(groups, key = { "listenGroup:$it" }, contentType = { "group" }) { group ->
                    MusicChip(group, playlists.listenGroup == group) { actions.selectListenGroup(group) }
                }
            }
        }
    }
    val modes = playlists.visibleListenModes
    when {
        modes.isEmpty() && playlists.listenLoading -> item(key = "listenSkeleton", contentType = "skeleton") {
            SkeletonCardGrid(columns, rows = 2, modifier = Modifier.padding(horizontal = gutter, vertical = 12.dp))
        }
        modes.isEmpty() && playlists.listenError.isNotBlank() -> item(key = "listenError", contentType = "error") {
            DiscoverErrorState(
                title = "听歌模式没有加载出来",
                message = "${playlists.providerName}：${playlists.listenError}",
                onRetry = actions::refreshListeningModes,
            )
        }
        modes.isEmpty() -> item(key = "listenEmpty", contentType = "empty") {
            EmptyState(
                icon = Icons.Filled.Headphones,
                title = "这个平台暂时没有听歌模式",
                message = "换一个来源，或看看别的分类。",
                actionLabel = "刷新",
                onAction = actions::refreshListeningModes,
            )
        }
        else -> {
            item(key = "listenLabel", contentType = "label") {
                SectionLabel(
                    if (playlists.listenGroup == "全部") "选一个直接开始播放" else playlists.listenGroup,
                    Modifier.padding(horizontal = gutter),
                )
            }
            items(
                modes.chunked(columns),
                key = { row -> "listen:${row.first().group}|${row.first().id}" },
                contentType = { "listenRow" },
            ) { row ->
                ListeningModeRow(
                    row = row,
                    columns = columns,
                    activeId = playlists.listenActive,
                    startingId = playlists.listenStarting,
                    actions = actions,
                    modifier = Modifier.padding(horizontal = gutter).padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ListeningModeRow(
    row: List<ListeningMode>,
    columns: Int,
    activeId: String,
    startingId: String,
    actions: MusicActions,
    modifier: Modifier = Modifier,
) {
    GridRow(row, columns, modifier) { mode, itemModifier ->
        ListeningModeCard(
            mode = mode,
            active = activeId.isNotBlank() && activeId == mode.id,
            starting = startingId.isNotBlank() && startingId == mode.id,
            onClick = { actions.playListeningMode(mode) },
            modifier = itemModifier,
        )
    }
}

/**
 * 听歌模式卡片：封面压满整张卡，标题落在底部渐变上——和歌单卡片（标题在封面下）区分开，
 * 一眼能看出点下去是“马上开播”而不是“进详情页”。
 */
@Composable
private fun ListeningModeCard(
    mode: ListeningMode,
    active: Boolean,
    starting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = LocalAppWindow.current.isTv
    val scheme = MaterialTheme.colorScheme
    val hasCover = mode.cover.isNotBlank()
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .tvCardFocus(RoundedCornerShape(24.dp))
            .clip(shape)
            .clickable(enabled = !starting, onClickLabel = "播放${mode.name}", onClick = onClick),
    ) {
        if (hasCover) {
            CoverBox(mode.cover, mode.name, Modifier.fillMaxSize(), RectangleShape, 48f)
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0.38f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.66f)),
                ),
            )
        } else {
            // 没有封面的模式（私人漫游这类）用主题色渐变 + 耳机图标，比放大字占位干净
            Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(listOf(scheme.surfaceContainerHighest, scheme.surfaceContainerLow)),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Headphones,
                    null,
                    // 往上让开底部标题，免得图标压字
                    Modifier.align(Alignment.Center).offset(y = if (tv) (-26).dp else (-18).dp)
                        .fillMaxWidth(0.3f).aspectRatio(1f),
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.32f),
                )
            }
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0.55f to Color.Transparent, 1f to scheme.surfaceContainerLowest.copy(alpha = 0.78f)),
                ),
            )
        }
        when {
            active -> Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.primary)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.GraphicEq, null, Modifier.size(13.dp), tint = scheme.onPrimary)
                Text("正在播放", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = scheme.onPrimary)
            }
            !starting -> Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(if (tv) 32.dp else 28.dp)
                    .clip(CircleShape)
                    .background(if (hasCover) Color.White.copy(alpha = 0.22f) else scheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    null,
                    Modifier.size(if (tv) 20.dp else 17.dp),
                    tint = if (hasCover) Color.White else scheme.primary,
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                mode.name,
                style = if (tv) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (hasCover) Color.White else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mode.subtitle.isNotBlank()) {
                Text(
                    mode.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasCover) Color.White.copy(alpha = 0.78f) else scheme.onSurfaceVariant,
                    maxLines = if (tv) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (starting) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.42f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 2.5.dp)
            }
        }
    }
}

/** 推荐页顶部的一步开播入口：浅色渐变底，和下面的歌单卡片明显分层。 */
@Composable
private fun QuickListenCard(starting: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    val content = scheme.onPrimaryContainer
    val window = LocalAppWindow.current
    Row(
        modifier
            .fillMaxWidth()
            // 高度跟着字号走：电视字号更大，写死高度会把副标题压掉
            .heightIn(min = if (window.isTv) 128.dp else if (window.isTablet) 104.dp else 92.dp)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)))
            .clickable(enabled = !starting, onClickLabel = "随便听听", onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (window.isTv) 60.dp else 48.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Headphones, null, Modifier.size(if (window.isTv) 32.dp else 25.dp), tint = content)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text("随便听听", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = content)
            Text(
                "按当前平台的听歌模式直接开播",
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (starting) {
            CircularProgressIndicator(Modifier.size(if (window.isTv) 32.dp else 26.dp), color = content, strokeWidth = 2.5.dp)
        } else {
            Box(
                Modifier.size(if (window.isTv) 56.dp else 44.dp).clip(CircleShape).background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, "开始播放", Modifier.size(if (window.isTv) 32.dp else 26.dp), tint = scheme.onPrimary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(playlists: PlaylistsUiState, actions: MusicActions, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(playlists.providerId, playlists.categoryGroup) { mutableStateOf(false) }
    val visible = remember(playlists.categories, playlists.categoryGroup) {
        visibleCategories(playlists.categories, playlists.categoryGroup)
    }
    val limit = if (LocalAppWindow.current.isPhone) 12 else 24
    val shown = if (expanded) visible else visible.take(limit)
    FlowRow(
        modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MusicChip("全部", playlists.selectedCategoryId.isBlank()) { actions.clearCategory() }
        shown.forEach { category ->
            MusicChip(category.name, playlists.selectedCategoryId == category.id) { actions.selectCategory(category) }
        }
        if (visible.size > limit) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (expanded) "收起" else "更多 ${visible.size - limit} 个")
            }
        }
    }
}

@Composable
private fun PlaylistGridRow(row: List<Playlist>, columns: Int, actions: MusicActions, modifier: Modifier = Modifier) {
    GridRow(row, columns, modifier) { playlist, itemModifier ->
        PlaylistCard(playlist, actions, itemModifier)
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist, actions: MusicActions, modifier: Modifier = Modifier, onFocused: () -> Unit = {}) {
    DiscoverCard(
        cover = playlist.cover,
        title = playlist.name,
        subtitle = listOf(
            playlist.creator,
            "${playlist.trackCount} 首".takeIf { playlist.trackCount > 0 },
        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
        onClick = { actions.openPlaylist(playlist) },
        modifier = modifier,
        badge = formatPlayCount(playlist.playCount),
        onFocused = onFocused,
    )
}

/** 编辑推荐：通栏封面，标题压在底部的表面色渐变上。 */
@Composable
private fun FeaturePlaylist(
    playlist: Playlist,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(28.dp)
    val caption = MaterialTheme.colorScheme.surfaceContainer
    val count = formatPlayCount(playlist.playCount)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        CoverBox(playlist.cover, playlist.name, Modifier.fillMaxSize(), RectangleShape, 64f)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to caption.copy(alpha = 0f), 1f to caption.copy(alpha = 0.94f))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                listOf("编辑推荐", count.takeIf { it.isNotBlank() }?.let { "$it 次播放" }, playlist.creator.takeIf { it.isNotBlank() })
                    .filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                playlist.name,
                Modifier.padding(top = 2.dp),
                style = if (height > 180.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------- 电视 ----------------

@Composable
private fun TvPlaylists(
    playlists: PlaylistsUiState,
    sources: List<ProviderInfo>,
    actions: MusicActions,
    onOpenSearch: () -> Unit,
) {
    val gutter = LocalAppWindow.current.gutter
    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("歌单", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(playlistsSubtitle(playlists), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = onOpenSearch, modifier = Modifier.tvFocusRing(CircleShape)) {
                Icon(Icons.Filled.Search, "搜索歌单")
            }
        }
        // 浏览方式和来源各占一行：电视字号大，挤在一行会把“听歌模式”和平台名都截掉
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (playlists.canCategorize || playlists.canListen) {
                ModeToggle(
                    playlists,
                    actions::setPlaylistMode,
                    Modifier.padding(start = gutter).width(if (playlists.canListen) 560.dp else 320.dp),
                )
            }
            ProviderChips(
                sources,
                playlists.providerId,
                actions::selectPlaylistSource,
                Modifier.weight(1f).padding(start = 16.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            when {
                playlists.categoryMode() -> TvCategories(playlists, actions)
                playlists.listeningMode() -> TvListening(playlists, actions)
                else -> TvRecommendations(playlists, sources, actions)
            }
        }
    }
}

@Composable
private fun TvRecommendations(playlists: PlaylistsUiState, sources: List<ProviderInfo>, actions: MusicActions) {
    val gutter = LocalAppWindow.current.gutter
    val items = playlists.recommendations
    val firstFocus = remember { FocusRequester() }
    TvDefaultFocus(firstFocus, key = items.isNotEmpty() || playlists.canListen)
    val other = sources.firstOrNull { it.id != playlists.providerId }
    when {
        items.isEmpty() && playlists.recommendationsLoading -> Column(Modifier.padding(horizontal = gutter, vertical = 16.dp)) {
            Skeleton(Modifier.fillMaxWidth().height(150.dp), RoundedCornerShape(28.dp))
            AppLoading(contained = true)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(6) { Skeleton(Modifier.size(150.dp), RoundedCornerShape(20.dp)) }
            }
        }
        items.isEmpty() && playlists.recommendationsError.isNotBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DiscoverErrorState(
                title = "推荐歌单没有加载出来",
                message = "${playlists.providerName}：${playlists.recommendationsError}",
                onRetry = actions::refreshPlaylists,
                secondaryLabel = other?.let { "换成${it.name}" },
                onSecondary = other?.let { { actions.selectPlaylistSource(it.id) } },
            )
        }
        items.isEmpty() -> Column(Modifier.fillMaxSize().padding(horizontal = gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            if (playlists.canListen) {
                QuickListenCard(
                    starting = playlists.quickListenStarting,
                    onClick = actions::quickListen,
                    modifier = Modifier.padding(top = 16.dp).focusRequester(firstFocus),
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    title = if (playlists.canRecommend) "这个平台暂时没有推荐歌单" else "这个平台没有推荐歌单",
                    message = if (playlists.canCategorize) "可以按分类浏览。" else "换一个来源看看。",
                    actionLabel = if (playlists.canCategorize) "按分类浏览" else "刷新",
                    onAction = {
                        if (playlists.canCategorize) actions.setPlaylistMode(PlaylistMode.Category) else actions.refreshPlaylists()
                    },
                )
            }
        }
        else -> {
            val lead = items.first()
            val rows = remember(items) { items.drop(1).chunked(10) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)) {
                if (playlists.canListen) {
                    item(key = "quickListen", contentType = "quickListen") {
                        QuickListenCard(
                            starting = playlists.quickListenStarting,
                            onClick = actions::quickListen,
                            modifier = Modifier
                                .padding(horizontal = gutter)
                                .padding(bottom = 12.dp)
                                .then(Modifier.focusRequester(firstFocus)),
                        )
                    }
                }
                item(key = "lead", contentType = "lead") {
                    FeaturePlaylist(
                        lead,
                        150.dp,
                        { actions.openPlaylist(lead) },
                        Modifier.padding(horizontal = gutter),
                        focus = if (rows.isEmpty() && !playlists.canListen) firstFocus else null,
                    )
                }
                itemsIndexed(
                    rows,
                    key = { _, row -> "row:${row.first().id}" },
                    contentType = { _, _ -> "playlistRow" },
                ) { rowIndex, row ->
                    Column(Modifier.padding(top = 16.dp)) {
                        Text(
                            if (rowIndex == 0) "为你推荐" else "更多推荐",
                            Modifier.padding(horizontal = gutter),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = gutter, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            itemsIndexed(
                                row,
                                key = { _, playlist -> playlist.id },
                                contentType = { _, _ -> "playlist" },
                            ) { index, playlist ->
                                PlaylistCard(
                                    playlist,
                                    actions,
                                    Modifier
                                        .width(150.dp)
                                        .then(
                                            if (rowIndex == 0 && index == 0 && !playlists.canListen) Modifier.focusRequester(firstFocus)
                                            else Modifier,
                                        ),
                                )
                            }
                        }
                    }
                }
                if (playlists.recommendationsError.isNotBlank()) {
                    item(key = "partial", contentType = "partial") {
                        Box(Modifier.padding(horizontal = gutter, vertical = 8.dp)) {
                            ErrorBlock(playlists.recommendationsError, actions::refreshPlaylists)
                        }
                    }
                }
            }
        }
    }
}

/** 电视听歌模式：左侧分组，右侧四列封面网格。没有翻页，模式总量不大。 */
@Composable
private fun TvListening(playlists: PlaylistsUiState, actions: MusicActions) {
    val gutter = LocalAppWindow.current.gutter
    val groups = listOf("全部") + playlists.listenGroups
    val selectedIndex = groups.indexOf(playlists.listenGroup).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    val selectedFocus = remember { FocusRequester() }
    TvDefaultFocus(selectedFocus, key = groups.size)
    Row(
        Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // 分组栏比分类页窄：听歌模式的分组名很短，多出来的宽度留给卡片
        LazyColumn(Modifier.width(240.dp).fillMaxHeight(), state = listState, contentPadding = PaddingValues(bottom = 48.dp)) {
            itemsIndexed(groups, key = { _, group -> "listenGroup:$group" }, contentType = { _, _ -> "listenGroup" }) { index, group ->
                ListRow(
                    title = group,
                    index = 0,
                    count = 1,
                    onClick = { actions.selectListenGroup(group) },
                    modifier = if (index == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier.padding(bottom = 2.dp),
                    selected = playlists.listenGroup == group,
                    showChevron = false,
                )
            }
        }
        TvListeningGrid(playlists, actions)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TvListeningGrid(playlists: PlaylistsUiState, actions: MusicActions) {
    val modes = playlists.visibleListenModes
    Column(Modifier.weight(1f).fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${playlists.listenGroup.ifBlank { "全部" }} · ${modes.size} 个模式",
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (playlists.listenLoading && modes.isNotEmpty()) LoadingIndicator(Modifier.size(40.dp))
        }
        Box(Modifier.weight(1f)) {
            when {
                modes.isEmpty() && playlists.listenLoading -> SkeletonCardGrid(3, rows = 2)
                modes.isEmpty() && playlists.listenError.isNotBlank() -> DiscoverErrorState(
                    title = "听歌模式没有加载出来",
                    message = "${playlists.providerName}：${playlists.listenError}",
                    onRetry = actions::refreshListeningModes,
                )
                modes.isEmpty() -> EmptyState(Icons.Filled.Headphones, "这个平台暂时没有听歌模式", "换一个来源看看。")
                else -> LazyVerticalGrid(
                    // 电视屏宽只有 960dp 左右，四列会把卡片压到放不下四字标题
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    itemsIndexed(
                        modes,
                        key = { _, mode -> "${mode.group}|${mode.id}" },
                        contentType = { _, _ -> "listenMode" },
                    ) { _, mode ->
                        ListeningModeCard(
                            mode = mode,
                            active = playlists.listenActive.isNotBlank() && playlists.listenActive == mode.id,
                            starting = playlists.listenStarting.isNotBlank() && playlists.listenStarting == mode.id,
                            onClick = { actions.playListeningMode(mode) },
                        )
                    }
                }
            }
        }
    }
}

/** 电视分类：左侧竖排分组和分类，右侧四列网格 + 翻页。默认焦点在当前分类上。 */
@Composable
private fun TvCategories(playlists: PlaylistsUiState, actions: MusicActions) {
    val gutter = LocalAppWindow.current.gutter
    val entries = remember(playlists.categories) { categoryEntries(playlists.categories) }
    val selectedIndex = entries.indexOfFirst {
        (it is CategoryEntry.All && playlists.selectedCategoryId.isBlank()) ||
            (it is CategoryEntry.Item && it.category.id == playlists.selectedCategoryId)
    }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    val selectedFocus = remember { FocusRequester() }
    TvDefaultFocus(selectedFocus, key = entries.size)
    Row(Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        LazyColumn(Modifier.width(280.dp).fillMaxHeight(), state = listState, contentPadding = PaddingValues(bottom = 48.dp)) {
            if (playlists.categories.isEmpty() && playlists.categoriesLoading) {
                item(key = "loading", contentType = "loading") { AppLoading(text = "正在读取分类…") }
            }
            if (playlists.categoriesError.isNotBlank()) {
                item(key = "error", contentType = "error") { ErrorBlock("分类列表没有加载出来", actions::refreshPlaylists) }
            }
            itemsIndexed(
                entries,
                key = { _, entry -> entry.key },
                contentType = { _, _ -> "categoryEntry" },
            ) { index, entry ->
                val focusModifier = if (index == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier
                when (entry) {
                    is CategoryEntry.All -> ListRow(
                        title = "全部",
                        index = 0,
                        count = 1,
                        onClick = actions::clearCategory,
                        modifier = focusModifier,
                        selected = playlists.selectedCategoryId.isBlank(),
                        showChevron = false,
                    )
                    is CategoryEntry.Header -> SectionLabel(entry.group)
                    is CategoryEntry.Item -> ListRow(
                        title = entry.category.name,
                        index = entry.index,
                        count = entry.count,
                        onClick = { actions.selectCategory(entry.category) },
                        modifier = focusModifier.padding(bottom = 2.dp),
                        selected = entry.category.id == playlists.selectedCategoryId,
                        showChevron = false,
                    )
                }
            }
        }
        TvCategoryGrid(playlists, actions)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TvCategoryGrid(playlists: PlaylistsUiState, actions: MusicActions) {
    val list = playlists.categoryPlaylists
    Column(Modifier.weight(1f).fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${playlists.selectedCategoryName.ifBlank { "全部歌单" }} · 第 ${playlists.categoryPage} 页",
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (playlists.categoryLoading && list.isNotEmpty()) LoadingIndicator(Modifier.size(40.dp))
        }
        Box(Modifier.weight(1f)) {
            when {
                list.isEmpty() && (playlists.categoryLoading || !playlists.categoryOpen) -> SkeletonCardGrid(4, rows = 2)
                list.isEmpty() && playlists.categoryError.isNotBlank() -> DiscoverErrorState(
                    title = "这个分类没有加载出来",
                    message = playlists.categoryError,
                    onRetry = actions::retryCategory,
                )
                list.isEmpty() -> EmptyState(Icons.Filled.Category, "这个分类下还没有歌单", "换一个分类，或者回到上一页。")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    itemsIndexed(
                        list,
                        key = { _, playlist -> playlist.id },
                        contentType = { _, _ -> "playlist" },
                    ) { _, playlist ->
                        PlaylistCard(playlist, actions)
                    }
                    if (playlists.categoryError.isNotBlank()) {
                        item(key = "pageError", contentType = "error", span = { GridItemSpan(maxLineSpan) }) {
                            ErrorBlock("第 ${playlists.categoryPage} 页没有加载出来：${playlists.categoryError}", actions::retryCategory)
                        }
                    }
                }
            }
        }
        // 翻页放在网格外：换页、翻到空页再翻回来时它一直在，焦点不会跟着网格一起被移除
        if (list.isNotEmpty() || playlists.categoryPage > 1 || playlists.categoryLoading) {
            TvPager(playlists, actions, Modifier.padding(bottom = 32.dp))
        }
    }
}

/** 电视保留翻页：遥控器上无限滚动容易迷失位置，也不好回到开头。 */
@Composable
private fun TvPager(playlists: PlaylistsUiState, actions: MusicActions, modifier: Modifier = Modifier) {
    val page = playlists.categoryPage
    // 按钮始终可聚焦，不能翻时只变淡、点了不响应：焦点所在的按钮一旦被禁用，遥控器焦点就丢了
    val canPrev = page > 1 && !playlists.categoryLoading
    val canNext = playlists.categoryPlaylists.isNotEmpty() && !playlists.categoryLoading
    Row(
        modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = { if (canPrev) actions.categoryPage(page - 1) },
            modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(CircleShape).alpha(if (canPrev) 1f else 0.38f),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
            Text("上一页")
        }
        Text(
            "第 $page 页",
            Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(
            onClick = { if (canNext) actions.categoryPage(page + 1) },
            modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(CircleShape).alpha(if (canNext) 1f else 0.38f),
        ) {
            Text("下一页")
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

private sealed interface CategoryEntry {
    val key: String

    data object All : CategoryEntry {
        override val key = "all"
    }

    data class Header(val group: String) : CategoryEntry {
        override val key = "group:$group"
    }

    data class Item(val category: PlaylistCategory, val index: Int, val count: Int) : CategoryEntry {
        override val key = "cat:${category.group}:${category.id}"
    }
}

private fun categoryEntries(categories: List<PlaylistCategory>): List<CategoryEntry> = buildList {
    add(CategoryEntry.All)
    categories.groupBy { it.group.ifBlank { "其他" } }.forEach { (group, items) ->
        add(CategoryEntry.Header(group))
        items.forEachIndexed { index, category -> add(CategoryEntry.Item(category, index, items.size)) }
    }
}
