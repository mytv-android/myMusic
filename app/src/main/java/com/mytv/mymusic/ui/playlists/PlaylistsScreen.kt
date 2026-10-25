package com.mytv.mymusic.ui.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * 歌单：推荐 / 分类两种浏览方式。手机两列网格，平板四列且按钮组移到标题右侧；
 * 分类在手机和平板上滚到底自动追加下一页，电视保留翻页（左侧分类列表 + 右侧网格）。
 */
@Composable
fun PlaylistsScreen(
    state: MusicUiState,
    playlists: PlaylistsUiState,
    actions: MusicActions,
    onOpenSearch: () -> Unit,
    categoryEnd: Boolean = false,
) {
    val sources = state.providers.filter { it.recommendedPlaylists || it.playlistCategories }
    if (LocalAppWindow.current.isTv) {
        TvPlaylists(playlists, sources, actions, onOpenSearch)
    } else {
        PhonePlaylists(playlists, sources, actions, onOpenSearch, categoryEnd)
    }
}

private fun PlaylistsUiState.categoryMode() = mode == PlaylistMode.Category && canCategorize

private fun playlistsSubtitle(playlists: PlaylistsUiState): String = listOf(
    playlists.providerName,
    if (playlists.categoryMode()) playlists.selectedCategoryName.ifBlank { "分类" } else "为你推荐",
).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun ModeToggle(mode: PlaylistMode, onSelect: (PlaylistMode) -> Unit, modifier: Modifier = Modifier) {
    ConnectedToggleGroup(
        options = listOf(PlaylistMode.Recommend, PlaylistMode.Category),
        selected = mode,
        onSelect = onSelect,
        label = { if (it == PlaylistMode.Recommend) "推荐" else "分类" },
        modifier = modifier,
        icon = { if (it == PlaylistMode.Recommend) Icons.Filled.AutoAwesome else null },
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
    val listState = rememberLazyListState()
    PageScaffold(
        title = "歌单",
        subtitle = playlistsSubtitle(playlists),
        actions = {
            if (window.isTablet && playlists.canCategorize) {
                ModeToggle(playlists.mode, actions::setPlaylistMode, Modifier.width(240.dp))
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
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
                item(key = "sources") { ProviderChips(sources, playlists.providerId, actions::selectPlaylistSource) }
                if (!window.isTablet && playlists.canCategorize) {
                    item(key = "mode") {
                        ModeToggle(
                            playlists.mode,
                            actions::setPlaylistMode,
                            Modifier.padding(horizontal = gutter, vertical = 8.dp).fillMaxWidth(),
                        )
                    }
                }
                if (categoryMode) {
                    categoryContent(playlists, actions, columns, gutter, categoryEnd, categoryRows)
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
    when {
        !playlists.canRecommend -> item(key = "unsupported") {
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
        items.isEmpty() && playlists.recommendationsLoading -> item(key = "skeleton") {
            Column(Modifier.padding(horizontal = gutter)) {
                Skeleton(Modifier.padding(top = 20.dp).fillMaxWidth().height(featureHeight), RoundedCornerShape(28.dp))
                AppLoading(contained = true)
                SkeletonCardGrid(columns)
            }
        }
        items.isEmpty() && playlists.recommendationsError.isNotBlank() -> item(key = "error") {
            DiscoverErrorState(
                title = "推荐歌单没有加载出来",
                message = "${playlists.providerName}：${playlists.recommendationsError}",
                onRetry = actions::refreshPlaylists,
                secondaryLabel = other?.let { "换成${it.name}" },
                onSecondary = other?.let { { actions.selectPlaylistSource(it.id) } },
            )
        }
        items.isEmpty() -> item(key = "empty") {
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
            item(key = "leadLabel") {
                SectionLabel("编辑推荐", Modifier.padding(horizontal = gutter)) {
                    TextButton(onClick = actions::refreshPlaylists, modifier = Modifier.heightIn(min = 48.dp)) { Text("换一批") }
                }
            }
            item(key = "lead") {
                FeaturePlaylist(lead, featureHeight, { actions.openPlaylist(lead) }, Modifier.padding(horizontal = gutter))
            }
            val rest = items.drop(1)
            if (rest.isNotEmpty()) {
                item(key = "restLabel") { SectionLabel("为你推荐", Modifier.padding(horizontal = gutter)) }
                items(
                    restRows,
                    key = { row -> "rest:${row.first().id}" },
                    contentType = { "playlistRow" },
                ) { row ->
                    PlaylistGridRow(row, columns, actions, Modifier.padding(horizontal = gutter).padding(bottom = 16.dp))
                }
            }
            if (playlists.recommendationsError.isNotBlank()) {
                item(key = "partial") {
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
) {
    if (playlists.categories.isEmpty() && playlists.categoriesLoading) {
        item(key = "chipSkeleton") {
            Row(Modifier.padding(horizontal = gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(5) { Skeleton(Modifier.width(64.dp).height(32.dp), RoundedCornerShape(8.dp)) }
            }
        }
    }
    if (playlists.categoriesError.isNotBlank()) {
        item(key = "categoriesError") {
            Box(Modifier.padding(horizontal = gutter, vertical = 8.dp)) {
                ErrorBlock("分类列表没有加载出来：${playlists.categoriesError}", actions::refreshPlaylists)
            }
        }
    }
    if (playlists.categories.isNotEmpty()) {
        val groups = categoryGroups(playlists.categories)
        item(key = "groups") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = gutter, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups, key = { it }, contentType = { "group" }) { group ->
                    MusicChip(group, playlists.categoryGroup == group) { actions.setCategoryGroup(group) }
                }
            }
        }
        item(key = "categories") {
            CategoryChips(playlists, actions, Modifier.padding(horizontal = gutter))
        }
    }
    item(key = "categoryHead") {
        val selected = playlists.categories.find { it.id == playlists.selectedCategoryId }
        SectionLabel(playlists.selectedCategoryName.ifBlank { "全部歌单" }, Modifier.padding(horizontal = gutter)) {
            if (selected != null && selected.count > 0) {
                Text("共 ${selected.count} 个歌单", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val list = playlists.categoryPlaylists
    when {
        list.isEmpty() && (playlists.categoryLoading || !playlists.categoryOpen) -> item(key = "gridSkeleton") {
            SkeletonCardGrid(columns, rows = 2, modifier = Modifier.padding(horizontal = gutter))
        }
        list.isEmpty() && playlists.categoryError.isNotBlank() -> item(key = "gridError") {
            DiscoverErrorState(
                title = "这个分类没有加载出来",
                message = playlists.categoryError,
                onRetry = actions::loadMoreCategory,
            )
        }
        list.isEmpty() -> item(key = "gridEmpty") {
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
            item(key = "footer") {
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

private fun categoryGroups(categories: List<PlaylistCategory>): List<String> =
    listOf("全部") + categories.map { it.group }.filter { it.isNotBlank() && it != "全部" }.distinct()

/** 分类 chip：手机上默认只展示前 12 个，避免把网格挤到第二屏。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(playlists: PlaylistsUiState, actions: MusicActions, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(playlists.providerId, playlists.categoryGroup) { mutableStateOf(false) }
    val visible = playlists.categories.filter {
        playlists.categoryGroup == "全部" || it.group == playlists.categoryGroup || it.group.isBlank()
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
            if (playlists.canCategorize) {
                ModeToggle(playlists.mode, actions::setPlaylistMode, Modifier.width(300.dp))
            }
            IconButton(onClick = onOpenSearch, modifier = Modifier.tvFocusRing(CircleShape)) {
                Icon(Icons.Filled.Search, "搜索歌单")
            }
        }
        ProviderChips(sources, playlists.providerId, actions::selectPlaylistSource, Modifier.padding(top = 8.dp))
        Box(Modifier.weight(1f)) {
            if (playlists.categoryMode()) TvCategories(playlists, actions) else TvRecommendations(playlists, sources, actions)
        }
    }
}

@Composable
private fun TvRecommendations(playlists: PlaylistsUiState, sources: List<ProviderInfo>, actions: MusicActions) {
    val gutter = LocalAppWindow.current.gutter
    val items = playlists.recommendations
    val firstFocus = remember { FocusRequester() }
    TvDefaultFocus(firstFocus, key = items.isNotEmpty())
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
        items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
        else -> {
            val lead = items.first()
            val rows = remember(items) { items.drop(1).chunked(10) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)) {
                item(key = "lead") {
                    FeaturePlaylist(
                        lead,
                        150.dp,
                        { actions.openPlaylist(lead) },
                        Modifier.padding(horizontal = gutter),
                        focus = if (rows.isEmpty()) firstFocus else null,
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
                                        .then(if (rowIndex == 0 && index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                                )
                            }
                        }
                    }
                }
                if (playlists.recommendationsError.isNotBlank()) {
                    item(key = "partial") {
                        Box(Modifier.padding(horizontal = gutter, vertical = 8.dp)) {
                            ErrorBlock(playlists.recommendationsError, actions::refreshPlaylists)
                        }
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
                item(key = "loading") { AppLoading(text = "正在读取分类…") }
            }
            if (playlists.categoriesError.isNotBlank()) {
                item(key = "error") { ErrorBlock("分类列表没有加载出来", actions::refreshPlaylists) }
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
                        item(key = "pageError", span = { GridItemSpan(maxLineSpan) }) {
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
