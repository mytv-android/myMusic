package com.mytv.mymusic.ui.charts

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.material3.rememberDynamicColorScheme
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import com.mytv.mymusic.ui.ChartsUiState
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.CoverBox
import com.mytv.mymusic.ui.EmptyState
import com.mytv.mymusic.ui.ErrorBlock
import com.mytv.mymusic.ui.InfoNote
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicChip
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.PlaylistPickerSheet
import com.mytv.mymusic.ui.TrackActionsSheet
import com.mytv.mymusic.ui.components.AppLoading
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SelectionBar
import com.mytv.mymusic.ui.components.SelectionPredictiveBack
import com.mytv.mymusic.ui.components.SelectionState
import com.mytv.mymusic.ui.components.selectionBackPreview
import com.mytv.mymusic.ui.components.Skeleton
import com.mytv.mymusic.ui.components.SkeletonRows
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.tvCardFocus
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.relativeTime
import com.mytv.mymusic.ui.theme.LocalAppearance
import java.util.Locale

/** 轮播里放前几个榜单，其余进“更多榜单”网格，两处不重复。 */
private const val CAROUSEL_COUNT = 5

/**
 * 榜单。手机：大标题 + 平台 chip + 多宽度轮播 + 榜首 + 更多榜单；
 * 平板：轮播变宽，榜单曲目在左、更多榜单在右；电视：跟随焦点换封面和配色的 Hero + 横向卡片行。
 */
@Composable
fun ChartsScreen(
    state: MusicUiState,
    charts: ChartsUiState,
    actions: MusicActions,
    favorites: List<SavedSong> = emptyList(),
    playlists: List<LocalPlaylistSummary> = emptyList(),
    recommended: List<Playlist> = emptyList(),
    history: List<Song> = emptyList(),
) {
    val sources = state.providers.filter { it.topLists || it.hotSongs }
    var target by remember { mutableStateOf<SongAction?>(null) }
    val onMore: (Song, List<Song>) -> Unit = { song, queue -> target = SongAction(song, queue) }
    if (LocalAppWindow.current.isTv) {
        // 电视榜单首页是卡片流，没有歌曲行；批量选择在榜单详情页（CollectionScreen）里
        TvCharts(charts, sources, actions, recommended, history)
    } else {
        val selection = remember { SelectionState() }
        SelectionPredictiveBack(selection)
        PhoneCharts(state, charts, sources, actions, onMore, selection, playlists)
    }
    target?.let { SongActionsSheet(it, favorites, playlists, actions) { target = null } }
}

@Composable
private fun PhoneCharts(
    state: MusicUiState,
    charts: ChartsUiState,
    sources: List<ProviderInfo>,
    actions: MusicActions,
    onMore: (Song, List<Song>) -> Unit,
    selection: SelectionState,
    playlists: List<LocalPlaylistSummary>,
) {
    val window = LocalAppWindow.current
    val gutter = window.gutter
    val playingKey = state.current?.key
    // 主推榜单 + 平台热歌合并成可选池，按 key 去重（同一首可能两边都有）
    val pool = remember(charts.featuredSongs, charts.hotSongs) {
        (charts.featuredSongs + charts.hotSongs).distinctBy { it.key }
    }
    var pickerOpen by remember { mutableStateOf(false) }
    PageScaffold(
        title = "榜单",
        subtitle = chartsSubtitle(charts),
        actions = {
            IconButton(
                onClick = { selection.start() },
                enabled = pool.isNotEmpty(),
                modifier = Modifier.tvFocusRing(CircleShape),
            ) { Icon(Icons.Filled.DoneAll, "批量选择歌曲") }
            IconButton(onClick = actions::refreshCharts) { Icon(Icons.Filled.Refresh, "刷新榜单") }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            val columns = if (maxWidth >= 480.dp) 3 else 2
            val featured = charts.charts.firstOrNull()
            val carousel = charts.charts.take(CAROUSEL_COUNT)
            val more = charts.charts.drop(CAROUSEL_COUNT)
            val moreRows = remember(charts.charts, columns) { charts.charts.drop(CAROUSEL_COUNT).chunked(columns) }
            val empty = charts.charts.isEmpty() && charts.hotSongs.isEmpty()
            val other = sources.firstOrNull { it.id != charts.providerId }
            val featuredSongs = charts.featuredSongs
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item(key = "sources", contentType = "sources") { ProviderChips(sources, charts.providerId, actions::selectChartSource) }
                when {
                    empty && charts.loading -> item(key = "skeleton", contentType = "skeleton") { ChartsSkeleton(window.isTablet) }
                    empty && charts.error.isNotBlank() -> item(key = "error", contentType = "error") {
                        DiscoverErrorState(
                            title = "榜单没有加载出来",
                            message = "${charts.providerName}：${charts.error}\n可能是网络问题，也可能是平台接口暂时不可用。",
                            onRetry = actions::retryCharts,
                            secondaryLabel = other?.let { "换成${it.name}" },
                            onSecondary = other?.let { { actions.selectChartSource(it.id) } },
                        )
                    }
                    empty -> item(key = "empty", contentType = "empty") {
                        EmptyState(
                            icon = Icons.Filled.Storefront,
                            title = if (charts.supported) "暂时没有榜单内容" else "这个平台没有提供排行榜",
                            message = "换一个来源，或稍后再试。榜单来源只决定内容从哪里读，不影响播放时的自动换源。",
                            actionLabel = if (other != null) "换成${other.name}" else "刷新",
                            onAction = { if (other != null) actions.selectChartSource(other.id) else actions.refreshCharts() },
                        )
                    }
                    else -> {
                        if (selection.active) {
                            item(key = "selbar", contentType = "selbar") {
                                SelectionBar(
                                    count = selection.selected.size,
                                    allSelected = pool.isNotEmpty() && selection.selected.containsAll(pool.map { it.key }),
                                    onToggleAll = { selection.toggleAll(pool.map { it.key }) },
                                    onPlay = {
                                        val picked = pool.filter { it.key in selection.selected }
                                        if (picked.isNotEmpty()) actions.play(picked.first(), picked)
                                    },
                                    onAddTo = { pickerOpen = true },
                                    onExit = { selection.exit() },
                                    modifier = Modifier.padding(horizontal = gutter).padding(bottom = 8.dp).selectionBackPreview(selection),
                                )
                            }
                        }
                        if (!charts.supported) {
                            item(key = "note", contentType = "note") {
                                Box(Modifier.padding(horizontal = gutter, vertical = 4.dp)) {
                                    InfoNote("这个平台没有排行榜，下面只显示平台热歌。", "amber")
                                }
                            }
                        }
                        if (carousel.isNotEmpty()) {
                            item(key = "carousel", contentType = "carousel") {
                                ChartCarousel(
                                    charts = carousel,
                                    provider = charts.providerName,
                                    large = window.isTablet,
                                    onOpen = actions::openChart,
                                    onPlay = actions::playChart,
                                )
                            }
                        }
                        val hasFeatured = featured != null && featuredSongs.isNotEmpty()
                        if (wide && (hasFeatured || more.isNotEmpty())) {
                            item(key = "two", contentType = "twoPane") {
                                Row(
                                    Modifier.padding(horizontal = gutter),
                                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                                ) {
                                    if (hasFeatured) {
                                        Column(Modifier.weight(1.15f)) {
                                            FeaturedChart(featured!!, featuredSongs, playingKey, actions, onMore, selection)
                                        }
                                    }
                                    if (more.isNotEmpty()) {
                                        Column(Modifier.weight(1f)) {
                                            SectionLabel("更多榜单")
                                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                                more.chunked(2).forEach { row ->
                                                    ChartCardRow(row, 2, charts.providerName, actions::openChart)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            if (hasFeatured) {
                                item(key = "featured", contentType = "featured") {
                                    Column(Modifier.padding(horizontal = gutter)) {
                                        FeaturedChart(featured!!, featuredSongs, playingKey, actions, onMore, selection)
                                    }
                                }
                            }
                            if (more.isNotEmpty()) {
                                item(key = "moreLabel", contentType = "label") { SectionLabel("更多榜单", Modifier.padding(horizontal = gutter)) }
                                items(
                                    moreRows,
                                    key = { row -> "charts:${row.first().id}" },
                                    contentType = { "chartRow" },
                                ) { row ->
                                    ChartCardRow(
                                        row,
                                        columns,
                                        charts.providerName,
                                        actions::openChart,
                                        Modifier.padding(horizontal = gutter).padding(bottom = 16.dp),
                                    )
                                }
                            }
                        }
                        if (charts.hotSongs.isNotEmpty()) {
                            item(key = "hot", contentType = "hot") {
                                Column(Modifier.padding(horizontal = gutter)) {
                                    SectionLabel("平台热歌") {
                                        Text(
                                            charts.providerName,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                                        charts.hotSongs.forEachIndexed { index, song ->
                                            val picking = selection.active
                                            val isSelected = picking && song.key in selection.selected
                                            val rowMore: (() -> Unit)? = if (picking) {
                                                null
                                            } else {
                                                { onMore(song, charts.hotSongs) }
                                            }
                                            val rowLongPress: (() -> Unit)? = if (picking) {
                                                null
                                            } else {
                                                { selection.enter(song.key) }
                                            }
                                            SongRow(
                                                title = song.title,
                                                subtitle = song.subtitle,
                                                cover = song.cover,
                                                index = index,
                                                count = charts.hotSongs.size,
                                                rank = index + 1,
                                                playing = playingKey == song.key,
                                                container = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                                onClick = { if (picking) selection.toggle(song.key) else actions.play(song, charts.hotSongs) },
                                                trailing = if (picking) {
                                                    {
                                                        Icon(
                                                            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                                            contentDescription = if (isSelected) "取消选择“${song.title}”" else "选择“${song.title}”",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                                onMore = rowMore,
                                                onLongClick = rowLongPress,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (charts.error.isNotBlank()) {
                            item(key = "partial", contentType = "partial") {
                                Box(Modifier.padding(horizontal = gutter, vertical = 12.dp)) {
                                    ErrorBlock("部分内容没有加载出来：${charts.error}", actions::retryCharts)
                                }
                            }
                        }
                        if (charts.loading) item(key = "more-loading", contentType = "loading") { AppLoading() }
                    }
                }
            }
        }
    }
    if (pickerOpen) {
        PlaylistPickerSheet(
            playlists = playlists,
            onPick = { id ->
                actions.addSongsToPlaylist(id, pool.filter { it.key in selection.selected })
                selection.exit()
            },
            onCreate = { name ->
                actions.addSongsToNewPlaylist(name, pool.filter { it.key in selection.selected })
                selection.exit()
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

private fun chartsSubtitle(charts: ChartsUiState): String {
    val provider = charts.providerName.ifBlank { "榜单来源" }
    return when {
        charts.loading -> "$provider · 正在读取…"
        charts.loadedAt > 0 -> "$provider · ${relativeTime(charts.loadedAt)}更新"
        else -> provider
    }
}

/** 多宽度轮播：大项显示榜单名和播放键，缩小时文字随宽度淡出，避免挤在窄条里。 */
@Composable
private fun ChartCarousel(
    charts: List<TopList>,
    provider: String,
    large: Boolean,
    onOpen: (TopList) -> Unit,
    onPlay: (TopList) -> Unit,
) {
    val gutter = LocalAppWindow.current.gutter
    val caption = MaterialTheme.colorScheme.surfaceContainer
    HorizontalMultiBrowseCarousel(
        state = rememberCarouselState { charts.size },
        preferredItemWidth = if (large) 400.dp else 200.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .height(if (large) 248.dp else 212.dp),
        itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = gutter),
    ) { index ->
        val chart = charts[index]
        val info = carouselItemDrawInfo
        // 只在布尔值翻转时失效组合；直接读 info.size 会让轮播滚动时每帧重组
        val showPlay by remember(info) { derivedStateOf { info.size > info.maxSize * 0.7f } }
        Box(
            Modifier
                .fillMaxHeight()
                .maskClip(RoundedCornerShape(28.dp))
                // 不另设 contentDescription：轮播项的描述会落在子节点上，和下面的标题文字一起被读两遍
                .clickable { onOpen(chart) },
        ) {
            CoverBox(chart.cover, chart.name, Modifier.fillMaxSize(), RectangleShape, 56f)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = captionAlpha(info.size, info.minSize, info.maxSize) }
                    .background(Brush.verticalGradient(0.4f to caption.copy(alpha = 0f), 1f to caption.copy(alpha = 0.94f))),
            )
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = captionAlpha(info.size, info.minSize, info.maxSize) }
                    .padding(start = 16.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        chart.name,
                        style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val detail = chart.updateFrequency.ifBlank { provider }
                    if (detail.isNotBlank()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                // 只有大项才能点播放键，窄条上点到的一律是“打开榜单”
                if (showPlay) {
                    FilledIconButton(onClick = { onPlay(chart) }) {
                        Icon(Icons.Filled.PlayArrow, "播放${chart.name}")
                    }
                }
            }
        }
    }
}

private fun captionAlpha(size: Float, min: Float, max: Float): Float {
    if (max <= min) return 1f
    return ((size / max - 0.55f) / 0.45f).coerceIn(0f, 1f)
}

/** 主推榜单：榜首放进 tertiary 容器，后面几首接成分段列表。 */
@Composable
private fun FeaturedChart(
    chart: TopList,
    songs: List<Song>,
    playingKey: String?,
    actions: MusicActions,
    onMore: (Song, List<Song>) -> Unit,
    selection: SelectionState,
) {
    val picking = selection.active
    SectionLabel(chart.name.ifBlank { "榜首" }) {
        FilledTonalButton(onClick = { actions.playChart(chart) }, modifier = Modifier.heightIn(min = 40.dp)) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("播放全部")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
        val first = songs.first()
        ChartSpotlight(
            song = first,
            single = songs.size == 1,
            selecting = picking,
            selected = first.key in selection.selected,
            onPlay = { if (picking) selection.toggle(first.key) else actions.play(first, songs) },
            onMore = if (picking) null else ({ onMore(first, songs) }),
        )
        songs.drop(1).forEachIndexed { offset, song ->
            val isSelected = song.key in selection.selected
            val rowMore: (() -> Unit)? = if (picking) {
                null
            } else {
                { onMore(song, songs) }
            }
            val rowLongPress: (() -> Unit)? = if (picking) {
                null
            } else {
                { selection.enter(song.key) }
            }
            SongRow(
                title = song.title,
                subtitle = song.subtitle,
                cover = song.cover,
                index = offset + 1,
                count = songs.size,
                rank = offset + 2,
                playing = playingKey == song.key,
                container = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                onClick = { if (picking) selection.toggle(song.key) else actions.play(song, songs) },
                trailing = if (picking) {
                    {
                        Icon(
                            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "取消选择“${song.title}”" else "选择“${song.title}”",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    null
                },
                onMore = rowMore,
                onLongClick = rowLongPress,
            )
        }
    }
    TextButton(onClick = { actions.openChart(chart) }, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
        Text("查看完整榜单")
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChartSpotlight(
    song: Song,
    single: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onPlay: () -> Unit,
    onMore: (() -> Unit)?,
) {
    val shape = if (single) RoundedCornerShape(28.dp) else RoundedCornerShape(28.dp, 28.dp, 4.dp, 4.dp)
    val content = MaterialTheme.colorScheme.onTertiaryContainer
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 124.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onPlay, onLongClick = onMore, onLongClickLabel = "更多操作")
            .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.semantics { contentDescription = "第 1 名" }) {
            Text("NO.", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = content.copy(alpha = 0.75f))
            Text("1", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = content)
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (song.subtitle.isNotBlank()) {
                Text(song.subtitle, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.padding(top = 6.dp),
                colors = if (selecting) {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    )
                },
            ) {
                Icon(
                    when {
                        !selecting -> Icons.Filled.PlayArrow
                        selected -> Icons.Filled.CheckCircle
                        else -> Icons.Filled.RadioButtonUnchecked
                    },
                    contentDescription = when {
                        !selecting -> "播放${song.title}"
                        selected -> "取消选择“${song.title}”"
                        else -> "选择“${song.title}”"
                    },
                )
            }
        }
        Cover(song.cover, song.title, 92.dp, shape = MaterialShapes.Cookie12Sided.toShape())
    }
}

@Composable
private fun ChartCardRow(
    row: List<TopList>,
    columns: Int,
    provider: String,
    onOpen: (TopList) -> Unit,
    modifier: Modifier = Modifier,
) {
    GridRow(row, columns, modifier) { chart, itemModifier ->
        DiscoverCard(
            cover = chart.cover,
            title = chart.name,
            subtitle = chart.updateFrequency.ifBlank { provider },
            onClick = { onOpen(chart) },
            modifier = itemModifier,
            coverShape = MaterialShapes.Arch.toShape(),
            aspect = 1.12f,
        )
    }
}

@Composable
private fun ChartsSkeleton(large: Boolean) {
    val gutter = LocalAppWindow.current.gutter
    Column(Modifier.padding(horizontal = gutter)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).height(if (large) 248.dp else 212.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shape = RoundedCornerShape(28.dp)
            Skeleton(Modifier.weight(if (large) 4f else 6f).fillMaxHeight(), shape)
            Skeleton(Modifier.weight(if (large) 2f else 2.4f).fillMaxHeight(), shape)
            Skeleton(Modifier.weight(1f).fillMaxHeight(), shape)
        }
        AppLoading(contained = true)
        SkeletonRows(4)
    }
}

// ---------------- 电视 ----------------

/** Hero 展示的对象：榜单、歌单或单曲。按键行为随类型不同，Hero 只负责展示。 */
internal class TvHeroItem(
    val key: String,
    val cover: String,
    val title: String,
    val meta: String,
    val description: String,
    val playLabel: String,
    val onPlay: () -> Unit,
    val secondaryLabel: String,
    val onSecondary: () -> Unit,
    val onClick: () -> Unit,
    val arch: Boolean = false,
)

@Composable
private fun TvCharts(
    charts: ChartsUiState,
    sources: List<ProviderInfo>,
    actions: MusicActions,
    recommended: List<Playlist>,
    history: List<Song>,
) {
    val provider = charts.providerName
    // Hero 行只按真实数据缓存；焦点变化不再重建文字、回调和列表。
    val chartItems = remember(charts.charts, provider, actions) {
        buildChartHeroItems(charts.charts, provider, actions)
    }
    val playlistItems = remember(recommended, actions) {
        buildPlaylistHeroItems(recommended, actions)
    }
    val hotItems = remember(charts.hotSongs, provider, actions) {
        buildSongHeroItems("hot", charts.hotSongs, "$provider 热歌", actions)
    }
    val historyItems = remember(history, actions) {
        buildSongHeroItems("history", history, "最近播放", actions)
    }

    val focusedKey = remember(charts.providerId) { mutableStateOf<String?>(null) }
    val onHeroFocused: (TvHeroItem) -> Unit = remember(focusedKey) { { item -> focusedKey.value = item.key } }
    // 只保存 key，按最新列表重新 lookup：刷新后仍指向同一个榜单但使用新数据和回调，不会留旧对象。
    val hero = remember(focusedKey.value, chartItems, playlistItems, hotItems, historyItems) {
        findFocusedHero(
            focusedKey = focusedKey.value,
            groups = listOf(chartItems, playlistItems, hotItems, historyItems),
            fallbackGroups = listOf(chartItems, hotItems),
            keyOf = { it.key },
        )
    }
    val playFocus = remember { FocusRequester() }
    val empty = charts.charts.isEmpty() && charts.hotSongs.isEmpty()
    // 按 Hero 是否真的显示来请求：刷新时 focused 还在但整页换成骨架，焦点已丢，加载完要重新给
    TvDefaultFocus(playFocus, key = !empty && hero != null)
    val gutter = LocalAppWindow.current.gutter
    val other = sources.firstOrNull { it.id != charts.providerId }

    Column(Modifier.fillMaxSize()) {
        when {
            empty && charts.loading -> TvChartsSkeleton()
            empty && charts.error.isNotBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DiscoverErrorState(
                    title = "榜单没有加载出来",
                    message = "${charts.providerName}：${charts.error}",
                    onRetry = actions::retryCharts,
                    modifier = Modifier.widthIn(max = 560.dp),
                    secondaryLabel = other?.let { "换成${it.name}" },
                    onSecondary = other?.let { { actions.selectChartSource(it.id) } },
                )
            }
            empty || hero == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.Storefront,
                    title = "这个平台没有提供排行榜",
                    message = "换一个来源，或稍后再试。",
                    actionLabel = if (other != null) "换成${other.name}" else "刷新",
                    onAction = { if (other != null) actions.selectChartSource(other.id) else actions.refreshCharts() },
                )
            }
            else -> {
                TvHero(hero, actions, playFocus)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 48.dp)) {
                    if (chartItems.isNotEmpty()) item(key = "charts", contentType = "chartRow") { TvCardRow("热门榜单", chartItems, onHeroFocused) }
                    if (playlistItems.isNotEmpty()) item(key = "rec", contentType = "playlistRow") { TvCardRow("为你推荐", playlistItems, onHeroFocused) }
                    if (hotItems.isNotEmpty()) item(key = "hot", contentType = "songRow") { TvCardRow("平台热歌", hotItems, onHeroFocused) }
                    if (historyItems.isNotEmpty()) item(key = "history", contentType = "songRow") { TvCardRow("最近播放", historyItems, onHeroFocused) }
                    if (charts.error.isNotBlank()) {
                        item(key = "partial", contentType = "partial") {
                            Box(Modifier.padding(horizontal = gutter, vertical = 8.dp)) {
                                ErrorBlock("部分内容没有加载出来：${charts.error}", actions::retryCharts)
                            }
                        }
                    }
                    item(key = "sources", contentType = "sources") {
                        Column(Modifier.padding(top = 12.dp)) {
                            Row(Modifier.padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
                                Text("榜单来源", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                IconButton(onClick = actions::refreshCharts, modifier = Modifier.tvFocusRing(CircleShape)) {
                                    Icon(Icons.Filled.Refresh, "刷新榜单")
                                }
                            }
                            ProviderChips(sources, charts.providerId, actions::selectChartSource)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 仅用于 androidTest 的隔离电视榜单：直接挂 [TvCharts]，外部提供数据，并用 LocalAppWindow 切到电视。
 * 可验证焦点 key 在数据刷新后仍按最新列表 lookup，以及焦点移动不会重建 Hero 列表。
 */
@Composable
internal fun TvChartsTestHost(
    charts: ChartsUiState,
    actions: MusicActions,
    sources: List<ProviderInfo> = emptyList(),
    recommended: List<Playlist> = emptyList(),
    history: List<Song> = emptyList(),
) {
    TvCharts(charts, sources, actions, recommended, history)
}

@Composable
private fun TvHero(item: TvHeroItem, actions: MusicActions, playFocus: FocusRequester) {
    val gutter = LocalAppWindow.current.gutter
    val reduce = LocalAppearance.current.reduceMotion
    val surface = MaterialTheme.colorScheme.surface
    val tint = rememberCoverTint(item.cover, actions)
    Box(Modifier.fillMaxWidth().height(280.dp)) {
        Crossfade(
            targetState = item.cover,
            modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.66f).fillMaxHeight(),
            animationSpec = if (reduce) snap() else MaterialTheme.motionScheme.slowEffectsSpec(),
            label = "heroCover",
        ) { url ->
            if (url.isNotBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.85f },
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Box(
            Modifier.matchParentSize().drawWithCache {
                // 保留原来的单层颜色插值；拆成两层 SrcOver 会改变过渡带透明度。
                // 动画色只在绘制阶段读取，停止变化后复用渐变和 shader。
                var lastTint = Color.Unspecified
                var horizontal: Brush? = null
                val bottomFade = Brush.verticalGradient(0.55f to Color.Transparent, 1f to surface)
                onDrawBehind {
                    val color = tint.value
                    if (color != lastTint || horizontal == null) {
                        horizontal = Brush.horizontalGradient(
                            0f to surface,
                            0.34f to surface,
                            0.72f to color.copy(alpha = 0.55f),
                            1f to color.copy(alpha = 0f),
                        )
                        lastTint = color
                    }
                    drawRect(horizontal!!)
                    drawRect(bottomFade)
                }
            },
        )
        Column(Modifier.padding(start = gutter, end = gutter, top = 40.dp).widthIn(max = 560.dp)) {
            Text(item.meta, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.title,
                Modifier.padding(vertical = 6.dp),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = item.onPlay,
                    modifier = Modifier.focusRequester(playFocus).heightIn(min = 48.dp).tvFocusRing(CircleShape),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(item.playLabel, Modifier.semantics { contentDescription = "${item.playLabel}：${item.title}" })
                }
                FilledTonalButton(onClick = item.onSecondary, modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(CircleShape)) {
                    if (item.secondaryLabel == "选择音源") {
                        Icon(Icons.Filled.SwapHoriz, null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(item.secondaryLabel)
                }
            }
        }
    }
}

/** 电视 Hero 的缓存准备入口：榜单列表只随数据和 actions 重建，焦点变化不会重跑。 */
internal fun buildChartHeroItems(charts: List<TopList>, provider: String, actions: MusicActions): List<TvHeroItem> =
    charts.map { chart ->
        TvHeroItem(
            key = "chart:${chart.id}",
            cover = chart.cover,
            title = chart.name,
            meta = listOf(provider, chart.updateFrequency, "${chart.trackCount} 首".takeIf { chart.trackCount > 0 })
                .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
            description = chart.description.ifBlank { "按你的音源策略播放，失败时自动换源。" },
            playLabel = "播放全部",
            onPlay = { actions.playChart(chart) },
            secondaryLabel = "查看榜单",
            onSecondary = { actions.openChart(chart) },
            onClick = { actions.openChart(chart) },
            arch = true,
        )
    }

/** 电视 Hero 的推荐歌单缓存准备入口。 */
internal fun buildPlaylistHeroItems(recommended: List<Playlist>, actions: MusicActions): List<TvHeroItem> =
    recommended.map { playlist ->
        TvHeroItem(
            key = "pl:${playlist.source}:${playlist.id}",
            cover = playlist.cover,
            title = playlist.name,
            meta = listOf("歌单", formatPlayCount(playlist.playCount).takeIf { it.isNotBlank() }?.let { "$it 次播放" }, playlist.creator)
                .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
            description = playlist.description.ifBlank { "来自歌单页的推荐。" },
            playLabel = "播放全部",
            onPlay = { actions.playPlaylist(playlist) },
            secondaryLabel = "打开歌单",
            onSecondary = { actions.openPlaylist(playlist) },
            onClick = { actions.openPlaylist(playlist) },
        )
    }

/** 电视 Hero 的单曲缓存准备入口（热歌与最近播放共用）。 */
internal fun buildSongHeroItems(
    prefix: String,
    songs: List<Song>,
    meta: String,
    actions: MusicActions,
): List<TvHeroItem> = songs.mapIndexed { index, song ->
    TvHeroItem(
        key = "$prefix:${song.key}:$index",
        cover = song.cover,
        title = song.title,
        meta = listOf(meta, song.subtitle).filter { it.isNotBlank() }.joinToString(" · "),
        description = "确定键播放；也可以先选择从哪个平台播放。",
        playLabel = "播放",
        onPlay = { actions.play(song, songs) },
        secondaryLabel = "选择音源",
        onSecondary = { actions.openSourceForSong(song, songs) },
        onClick = { actions.play(song, songs) },
    )
}

@Composable
private fun TvCardRow(title: String, items: List<TvHeroItem>, onFocused: (TvHeroItem) -> Unit) {
    val gutter = LocalAppWindow.current.gutter
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, Modifier.padding(horizontal = gutter).semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(items, key = { it.key }, contentType = { "tvHero" }) { item ->
                DiscoverCard(
                    cover = item.cover,
                    title = item.title,
                    subtitle = "",
                    onClick = item.onClick,
                    modifier = Modifier.width(140.dp),
                    // 电视卡片只显示标题，读屏补上歌手 / 来源，否则只听到“宝船”不知道是谁的
                    spokenTitle = listOf(item.title, item.meta).filter { it.isNotBlank() }.joinToString("，"),
                    coverShape = if (item.arch) MaterialShapes.Arch.toShape() else RoundedCornerShape(20.dp),
                    aspect = if (item.arch) 1.12f else 1f,
                    onFocused = { onFocused(item) },
                )
            }
        }
    }
}

@Composable
private fun TvChartsSkeleton() {
    val gutter = LocalAppWindow.current.gutter
    Column(Modifier.fillMaxSize().padding(horizontal = gutter, vertical = 40.dp)) {
        Skeleton(Modifier.fillMaxWidth(0.4f).height(28.dp), CircleShape)
        Skeleton(Modifier.padding(top = 12.dp).fillMaxWidth(0.25f).height(14.dp), CircleShape)
        AppLoading(contained = true)
        repeat(2) {
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(6) { Skeleton(Modifier.size(140.dp), RoundedCornerShape(20.dp)) }
            }
        }
    }
}

// ---------------- 发现页共用的小部件（歌单页、详情页也用） ----------------

/** 长按或“更多”打开的歌曲操作，带上当前列表作为换源时的队列。 */
internal class SongAction(val song: Song, val queue: List<Song>)

@Composable
internal fun SongActionsSheet(
    target: SongAction,
    favorites: List<SavedSong>,
    playlists: List<LocalPlaylistSummary>,
    actions: MusicActions,
    onDismiss: () -> Unit,
) {
    TrackActionsSheet(
        song = target.song,
        favorite = favorites.any { it.key == target.song.key },
        playlists = playlists,
        onToggleFavorite = { actions.favorite(target.song) },
        onDownload = { actions.download(target.song) },
        onAddToPlaylist = { id -> actions.addSongToPlaylist(id, target.song) },
        onOpenSource = {
            onDismiss()
            actions.openSourceForSong(target.song, target.queue)
        },
        onRemoveFromPlaylist = null,
        onDismiss = onDismiss,
    )
}

/** 平台 chip 行：横向滚动，左右贴边留出 gutter；进入时把选中项滚到可见处。 */
@Composable
internal fun ProviderChips(
    sources: List<ProviderInfo>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty()) return
    val gutter = LocalAppWindow.current.gutter
    val selectedIndex = sources.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 1).coerceAtLeast(0))
    LazyRow(
        modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sources, key = { it.id }, contentType = { "provider" }) { provider ->
            MusicChip(provider.name, provider.id == selectedId) { onSelect(provider.id) }
        }
    }
}

/** 网格的一行：不足的列用空位补齐，卡片宽度保持一致。 */
@Composable
internal fun <T> GridRow(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    gap: Dp = 12.dp,
    content: @Composable (T, Modifier) -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        items.forEach { content(it, Modifier.weight(1f)) }
        repeat((columns - items.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * 封面卡片：歌单、榜单、专辑通用。badge 是压在封面左上角的播放量标签。
 * 电视上放大并描边（tvCardFocus），获得焦点时回调 onFocused（Hero 跟随焦点）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiscoverCard(
    cover: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverShape: Shape = RoundedCornerShape(20.dp),
    aspect: Float = 1f,
    badge: String = "",
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    spokenTitle: String = "",
) {
    val tv = LocalAppWindow.current.isTv
    Column(
        modifier
            .tvCardFocus(RoundedCornerShape(24.dp), onFocused)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f / aspect)) {
            CoverBox(cover, title, Modifier.fillMaxSize(), coverShape, 40f)
            if (badge.isNotBlank()) {
                PlayCountTag(badge, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
        }
        Text(
            title,
            Modifier
                .padding(top = 8.dp, start = 2.dp, end = 2.dp)
                .then(if (spokenTitle.isNotBlank()) Modifier.semantics { contentDescription = spokenTitle } else Modifier),
            style = if (tv) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (tv) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 封面上的播放量：半透明表面色底，不用实时模糊（每屏模糊层有上限）。 */
@Composable
internal fun PlayCountTag(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = 22.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = "$text 次播放" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 卡片网格的骨架，按最终布局排列，加载完不跳动。 */
@Composable
internal fun SkeletonCardGrid(columns: Int, rows: Int = 2, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(rows) {
            GridRow((0 until columns).toList(), columns) { _, itemModifier ->
                Column(itemModifier) {
                    Skeleton(Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(20.dp))
                    Skeleton(Modifier.padding(top = 10.dp).fillMaxWidth(0.8f).height(14.dp), CircleShape)
                }
            }
        }
    }
}

/** 整页加载失败：说明原因，给重试和换来源两条出路。电视默认焦点在“重试”。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoverErrorState(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val retryFocus = remember { FocusRequester() }
    TvDefaultFocus(retryFocus)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(132.dp)
                .clip(MaterialShapes.Clover4Leaf.toShape())
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.CloudOff, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
        }
        Text(
            title,
            Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            message,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FlowRow(
            Modifier.padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus).heightIn(min = 48.dp).tvFocusRing(CircleShape)) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("重试")
            }
            if (secondaryLabel != null && onSecondary != null) {
                FilledTonalButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(CircleShape)) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}

/** 电视默认焦点：等一帧让懒加载的项排好版再请求，未挂上时静默忽略。 */
@Composable
internal fun TvDefaultFocus(requester: FocusRequester, key: Any? = Unit) {
    if (!LocalAppWindow.current.isTv) return
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/**
 * 封面色：用封面种子色生成与当前明暗一致的配色，取 primaryContainer；没有封面或取色失败时用全局配色。
 * 只用于详情页头部渐变和电视 Hero，其余界面仍是全局配色。
 */
@Composable
internal fun rememberCoverTint(url: String, actions: MusicActions): State<Color> {
    var seed by remember(url) { mutableStateOf<Int?>(null) }
    LaunchedEffect(url) { seed = if (url.isBlank()) null else actions.coverSeed(url) }
    val fallback = MaterialTheme.colorScheme.primaryContainer
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val appearance = LocalAppearance.current
    val scheme = rememberDynamicColorScheme(
        seedColor = seed?.let(::Color) ?: fallback,
        isDark = dark,
        isAmoled = false,
        style = PaletteStyle.TonalSpot,
        contrastLevel = appearance.contrast.toDouble(),
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
    val target = if (seed != null) scheme.primaryContainer else fallback
    // 返回 State：调用方在绘制阶段读值，配色过渡时不会把整页重组一遍
    return animateColorAsState(
        target,
        if (appearance.reduceMotion) snap() else MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "coverTint",
    )
}

/** 播放量：过亿 / 过万用中文单位。 */
internal fun formatPlayCount(count: Long): String = when {
    count >= 100_000_000L -> String.format(Locale.ROOT, "%.1f 亿", count / 100_000_000.0).replace(".0 ", " ")
    count >= 10_000L -> String.format(Locale.ROOT, "%,d 万", count / 10_000)
    count > 0 -> count.toString()
    else -> ""
}
