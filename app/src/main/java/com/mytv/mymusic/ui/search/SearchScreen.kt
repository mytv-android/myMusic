@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.mytv.mymusic.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.aggregate.CanonicalCollection
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.CollectionSource
import com.mytv.mymusic.aggregate.ProviderSearchState
import com.mytv.mymusic.aggregate.ProviderStatus
import com.mytv.mymusic.aggregate.SearchStatus
import com.mytv.mymusic.aggregate.availabilityOf
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.SearchKind
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.CoverBox
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.SearchUiState
import com.mytv.mymusic.ui.TrackActionsSheet
import com.mytv.mymusic.ui.components.AppLoading
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalOverlayPadding
import com.mytv.mymusic.ui.components.PlatformBadges
import com.mytv.mymusic.ui.components.PlayButtonSize
import com.mytv.mymusic.ui.components.PlayPauseButton
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.Skeleton
import com.mytv.mymusic.ui.components.SkeletonRows
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvCardFocus
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.providerMark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val FailedStatuses = setOf(ProviderStatus.Timeout, ProviderStatus.Error, ProviderStatus.Skipped)
private val SearchKinds = listOf(SearchKind.Song, SearchKind.Playlist, SearchKind.Album)
private val UrlPattern = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val TwoPaneLeft = 320.dp
private val TvLeft = 300.dp

/** 搜索页各部分共用的数据和回调。 */
private class SearchCtx(
    val state: MusicUiState,
    val search: SearchUiState,
    val actions: MusicActions,
    val favoriteKeys: Set<String>,
    val playlists: List<LocalPlaylistSummary>,
    val openSettings: () -> Unit,
    val submit: (String) -> Unit,
    val removeHistory: (String) -> Unit,
    val clearHistory: () -> Unit,
    val openActions: (CanonicalTrack) -> Unit,
) {
    fun play(track: CanonicalTrack) = actions.playTrack(track, search.aggregateGroups)
    fun isCurrent(track: CanonicalTrack) = state.currentTrack?.id == track.id
    fun openSource(track: CanonicalTrack) = actions.openSource(track, search.aggregateGroups)
}

/**
 * 搜索：只在提交时请求。未提交或正在改关键词时显示“输入中”（最近搜索、链接解析、联想），
 * 提交后按类型显示聚合单曲或歌单 / 专辑结果。
 */
@Composable
fun SearchScreen(
    state: MusicUiState,
    search: SearchUiState,
    favorites: List<SavedSong>,
    playlists: List<LocalPlaylistSummary>,
    actions: MusicActions,
    onOpenSearchSettings: () -> Unit,
) {
    val window = LocalAppWindow.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var actionTrack by remember { mutableStateOf<CanonicalTrack?>(null) }
    val showHome = search.landing || editing

    // 清除 / 删除搜索记录可撤销：撤销时按原顺序写回快照
    fun undoable(message: String, apply: () -> Unit) {
        val snapshot = state.searchHistory
        apply()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = "撤销", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) actions.restoreHistory(snapshot)
        }
    }

    val ctx = SearchCtx(
        state = state,
        search = search,
        actions = actions,
        favoriteKeys = favorites.mapTo(HashSet()) { it.key },
        playlists = playlists,
        openSettings = onOpenSearchSettings,
        submit = { raw ->
            val text = raw.trim()
            if (text.isNotEmpty()) {
                keyboard?.hide()
                focusManager.clearFocus()
                if (actions.isLink(text)) {
                    // 链接直接解析成歌单 / 专辑 / 单曲，不进入搜索结果
                    actions.setSearchQuery(text)
                    actions.openLink(text)
                } else {
                    actions.submitSearch(text)
                    editing = false
                }
            }
        },
        removeHistory = { item -> undoable("已删除“$item”") { actions.removeHistory(item) } },
        clearHistory = { undoable("已清除搜索记录") { actions.clearHistory() } },
        openActions = { actionTrack = it },
    )

    BackHandler(enabled = editing && !search.landing) {
        editing = false
        focusManager.clearFocus()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (window.isTv) {
            TvSearch(ctx, showHome, onEdit = { editing = it })
        } else {
            TouchSearch(ctx, showHome, editing, onEdit = { editing = it })
        }
    }

    actionTrack?.let { track ->
        val song = preferredSong(track, state.cookies) ?: return@let
        val favorite = favoriteSong(track, ctx.favoriteKeys)
        TrackActionsSheet(
            song = song,
            favorite = favorite != null,
            playlists = playlists,
            onToggleFavorite = { actions.favorite(favorite ?: song) },
            onDownload = { actions.download(song) },
            onAddToPlaylist = { id -> actions.addSongToPlaylist(id, song) },
            onOpenSource = {
                actionTrack = null
                ctx.openSource(track)
            },
            onRemoveFromPlaylist = null,
            onDismiss = { actionTrack = null },
            onMerge = {
                actionTrack = null
                actions.openMerge(track)
            },
        )
    }
}

/* ======================== 手机 / 平板 ======================== */

@Composable
private fun TouchSearch(ctx: SearchCtx, showHome: Boolean, editing: Boolean, onEdit: (Boolean) -> Unit) {
    val window = LocalAppWindow.current
    val search = ctx.search
    val focusManager = LocalFocusManager.current
    val fieldFocus = remember { FocusRequester() }
    // 平板有结果时，输入中改成搜索框下方的 Docked search view，叠在结果上
    val docked = window.isTablet && editing && !search.landing
    val narrow = if (window.isPhone) Modifier.fillMaxWidth() else Modifier.widthIn(max = 640.dp).fillMaxWidth()

    LaunchedEffect(Unit) {
        if (search.landing) {
            withFrameNanos { }
            runCatching { fieldFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = window.gutter)
                .padding(top = 12.dp, bottom = 4.dp),
        ) {
            SearchField(
                value = search.query,
                onValueChange = ctx.actions::setSearchQuery,
                onSubmit = { ctx.submit(search.query) },
                onClear = if (search.query.isNotEmpty() || search.hasQuery) {
                    {
                        ctx.actions.clearSearch()
                        onEdit(false)
                        runCatching { fieldFocus.requestFocus() }
                    }
                } else {
                    null
                },
                onBack = if (editing && !search.landing) {
                    {
                        onEdit(false)
                        focusManager.clearFocus()
                    }
                } else {
                    null
                },
                focusRequester = fieldFocus,
                onFocused = { if (!search.landing) onEdit(true) },
                modifier = narrow,
            )
            if (!docked) {
                KindToggle(ctx, Modifier.padding(top = 10.dp).then(if (window.isPhone) Modifier.fillMaxWidth() else Modifier.widthIn(max = 420.dp).fillMaxWidth()))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // 按内容区实际宽度分栏：≥1200dp 时右侧有播放窗格，内容区会比窗口窄很多
            val twoPane = !window.isPhone && maxWidth >= 760.dp
            val listWidth = maxWidth - window.gutter * 2
            when {
                showHome && !docked -> HomePage(ctx, narrow)
                search.kind == SearchKind.Song && twoPane -> SongResultsTwoPane(ctx, listWidth - TwoPaneLeft - 24.dp)
                else -> ResultsList(ctx, listWidth)
            }
            if (docked) {
                DockedPanel(ctx) {
                    onEdit(false)
                    focusManager.clearFocus()
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: (() -> Unit)?,
    onBack: (() -> Unit)?,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = LocalAppWindow.current.isTv
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .heightIn(min = 56.dp)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (focused && !tv) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回结果") }
        } else {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 14.dp)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            "歌曲、歌单、专辑，或粘贴链接",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (onClear != null) {
            IconButton(onClick = onClear, modifier = Modifier.tvFocusRing(CircleShape)) {
                Icon(Icons.Filled.Close, "清除搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KindToggle(ctx: SearchCtx, modifier: Modifier = Modifier) {
    ConnectedToggleGroup(
        options = SearchKinds,
        selected = ctx.search.kind,
        onSelect = ctx.actions::setSearchKind,
        label = { it.label },
        modifier = modifier,
    )
}

@Composable
private fun HomePage(ctx: SearchCtx, narrow: Modifier) {
    val window = LocalAppWindow.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = window.gutter)
            .padding(top = 4.dp, bottom = LocalOverlayPadding.current.calculateBottomPadding() + 24.dp),
    ) {
        if (window.isPhone) {
            HomeContent(ctx, narrow, readClipboard = true)
        } else {
            // 平板：与 Docked search view 同样的面板，宽度跟搜索框一致
            Surface(narrow, shape = DockedShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                HomeContent(ctx, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), readClipboard = true)
            }
        }
    }
}

private val DockedShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 28.dp, bottomEnd = 28.dp)

/** 平板：Docked search view，搜索框下的下拉面板；点外面收起。 */
@Composable
private fun DockedPanel(ctx: SearchCtx, onDismiss: () -> Unit) {
    val window = LocalAppWindow.current
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        Surface(
            modifier = Modifier
                .padding(horizontal = window.gutter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            shape = DockedShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                KindToggle(ctx, Modifier.fillMaxWidth())
                HomeContent(ctx, Modifier.fillMaxWidth(), readClipboard = true)
            }
        }
    }
}

/** 输入中：链接解析建议、联想、最近搜索、参与聚合的平台。 */
@Composable
private fun HomeContent(ctx: SearchCtx, modifier: Modifier, readClipboard: Boolean, big: Boolean = false) {
    val clip = rememberClipboardLink(readClipboard, ctx.actions::isLink)
    var dismissedClip by rememberSaveable { mutableStateOf("") }
    val query = ctx.search.query.trim()
    val typedLink = query.takeIf { it.isNotEmpty() && ctx.actions.isLink(it) }
    Column(modifier) {
        when {
            typedLink != null -> LinkCard(
                title = linkPlatform(typedLink).let { if (it.isBlank()) "解析这个链接" else "解析$it 链接" },
                url = extractUrl(typedLink),
                busy = ctx.state.loading,
                onParse = { ctx.actions.openLink(typedLink) },
                onDismiss = null,
            )
            clip.isNotEmpty() && clip != dismissedClip -> LinkCard(
                title = linkPlatform(clip).let { if (it.isBlank()) "剪贴板里有一个平台链接" else "剪贴板里有一个$it 链接" },
                url = extractUrl(clip),
                busy = ctx.state.loading,
                onParse = {
                    ctx.actions.setSearchQuery(clip)
                    ctx.actions.openLink(clip)
                },
                onDismiss = { dismissedClip = clip },
            )
        }
        if (query.isNotEmpty() && typedLink == null) {
            Suggestions(ctx, query)
        }
        HistorySection(ctx, big)
        PlatformsNote(ctx)
    }
}

@Composable
private fun rememberClipboardLink(enabled: Boolean, isLink: (String) -> Boolean): String {
    val clipboard = LocalClipboard.current
    var link by remember { mutableStateOf("") }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val text = runCatching {
            clipboard.getClipEntry()?.clipData?.let { data ->
                if (data.itemCount > 0) data.getItemAt(0).text?.toString() else null
            }
        }.getOrNull().orEmpty().trim()
        link = if (text.isNotEmpty() && isLink(text)) text else ""
    }
    return link
}

@Composable
private fun LinkCard(title: String, url: String, busy: Boolean, onParse: () -> Unit, onDismiss: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Link, null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                LoadingIndicator(Modifier.size(40.dp))
            } else {
                Button(onClick = onParse, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("解析") }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.tvFocusRing(CircleShape)) { Icon(Icons.Filled.Close, "忽略这个链接") }
            }
        }
    }
}

/** 联想：只用本机搜索记录匹配，不额外请求平台。 */
@Composable
private fun Suggestions(ctx: SearchCtx, query: String) {
    val matches = ctx.state.searchHistory
        .filter { it != query && it.contains(query, ignoreCase = true) }
        .take(5)
    val rows = listOf(query) + matches
    SectionLabel("联想")
    Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
        rows.forEachIndexed { index, text ->
            val shape = segmentShape(index, rows.size)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .tvFocusRing(shape, scale = 1.02f)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { ctx.submit(text) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    if (index == 0) Icons.Filled.Search else Icons.Filled.History,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (index == 0) "搜索“$text”" else text,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HistorySection(ctx: SearchCtx, big: Boolean) {
    val history = ctx.state.searchHistory
    SectionLabel("最近搜索") {
        if (history.isNotEmpty()) {
            TextButton(onClick = ctx.clearHistory, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                Text("清除", Modifier.semantics { contentDescription = "清除搜索记录" })
            }
        }
    }
    if (history.isEmpty()) {
        Text(
            "搜索记录只保存在本机，不上传，也不参与歌单同步。",
            Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            history.forEach { item ->
                HistoryChip(item, big, onClick = { ctx.submit(item) }, onRemove = { ctx.removeHistory(item) })
            }
        }
    }
}

@Composable
private fun HistoryChip(text: String, big: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    val shape = RoundedCornerShape(if (big) 16.dp else 12.dp)
    Row(
        Modifier
            .heightIn(min = if (big) 56.dp else 40.dp)
            .tvFocusRing(shape)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = if (onRemove != null) 0.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            Modifier.widthIn(max = 220.dp),
            style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp).tvFocusRing(CircleShape)) {
                Icon(Icons.Filled.Close, "删除搜索记录“$text”", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlatformsNote(ctx: SearchCtx) {
    val providers = kindProviders(ctx.state, SearchKind.Song)
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                if (providers.isEmpty()) "还没有启用参与聚合的平台" else "同时搜索 ${providers.size} 个平台",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                providers.joinToString("、") { it.name }.ifBlank { "到“搜索与浏览”里打开至少一个平台。" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = ctx.openSettings, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
            Text("调整", Modifier.semantics { contentDescription = "调整聚合平台" })
        }
    }
    Text(
        "粘贴网易云、QQ 音乐等平台的分享链接，可以直接打开歌单、专辑或单曲。试听、VIP 和没有版权会逐条标出。",
        Modifier.padding(start = 4.dp, top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/* ---------------- 结果 ---------------- */

@Composable
private fun ResultsList(ctx: SearchCtx, rowWidth: Dp) {
    val window = LocalAppWindow.current
    var altExpanded by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = window.gutter,
            end = window.gutter,
            top = 4.dp,
            bottom = LocalOverlayPadding.current.calculateBottomPadding() + 24.dp,
        ),
    ) {
        if (ctx.search.kind == SearchKind.Song) {
            songItems(ctx, rowWidth, bestInline = true, altExpanded = altExpanded, onToggleAlt = { altExpanded = !altExpanded })
            if (window.isPhone && !ctx.search.loading && ctx.state.searchHistory.isNotEmpty()) {
                item("history") { Column { HistorySection(ctx, big = false) } }
            }
        } else {
            collectionItems(ctx)
        }
    }
}

/** 平板内容区 ≥760dp：最佳匹配占左栏，结果列表占右栏。 */
@Composable
private fun SongResultsTwoPane(ctx: SearchCtx, rowWidth: Dp) {
    val window = LocalAppWindow.current
    val bottom = LocalOverlayPadding.current.calculateBottomPadding() + 24.dp
    val groups = ctx.search.aggregateGroups
    var altExpanded by rememberSaveable { mutableStateOf(false) }
    val showSkeleton = rememberDelayed(groups.isEmpty() && ctx.search.loading)
    Row(Modifier.fillMaxSize().padding(horizontal = window.gutter), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        if (groups.isNotEmpty() || showSkeleton) {
            Column(
                Modifier
                    .width(TwoPaneLeft)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = bottom),
            ) {
                if (groups.isNotEmpty()) BestMatchCard(ctx, groups.first(), vertical = true) else BestSkeleton(vertical = true)
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottom),
        ) {
            songItems(ctx, rowWidth, bestInline = false, altExpanded = altExpanded, onToggleAlt = { altExpanded = !altExpanded })
        }
    }
}

/**
 * 聚合单曲结果。bestInline 为真时最佳匹配放在列表里（手机、窄平板）；否则由调用方放在左栏。
 * 列表从第二条开始，第一条总是最佳匹配。
 */
private fun LazyListScope.songItems(
    ctx: SearchCtx,
    rowWidth: Dp,
    bestInline: Boolean,
    altExpanded: Boolean,
    onToggleAlt: () -> Unit,
    statusInList: Boolean = true,
    firstFocus: FocusRequester? = null,
    stateFocus: FocusRequester? = null,
) {
    val search = ctx.search
    val groups = search.aggregateGroups
    if (statusInList) item("status") { StatusArea(ctx) }
    if (groups.isEmpty()) {
        if (search.loading) {
            item("skeleton") { LoadingSkeleton(withBest = bestInline) }
        } else {
            item("empty") { SongEmptyState(ctx, stateFocus) }
        }
        return
    }
    if (bestInline) {
        item("best") { BestMatchCard(ctx, groups.first(), vertical = false, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)) }
    }
    val rows = groups.drop(1)
    val rowKeys = distinctKeys(rows) { it.id }
    itemsIndexed(rows, key = { index, _ -> rowKeys[index] }) { index, track ->
        SearchSongRow(
            ctx,
            track,
            index,
            rows.size,
            rowWidth,
            Modifier
                .padding(top = if (index == 0) 0.dp else SegmentGap)
                .then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
        )
    }
    if (search.hasMore) item("more") { LoadMore(ctx) }
    val alternatives = groups.flatMap { track -> track.alternatives.map { track to it } }
        .distinctBy { (_, source) -> source.song.key }
    if (alternatives.isNotEmpty()) {
        item("alt-head") { AlternativesHeader(alternatives.size, altExpanded, onToggleAlt) }
        if (altExpanded) {
            itemsIndexed(alternatives, key = { _, pair -> "alt:${pair.second.song.key}" }) { index, (track, source) ->
                SongRow(
                    title = source.song.title,
                    subtitle = listOf(source.song.artist, source.providerName, source.qualityLabel)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    cover = source.song.cover,
                    index = index,
                    count = alternatives.size,
                    onClick = { ctx.actions.playAlternative(track, source) },
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else SegmentGap),
                    trailing = {
                        TextButton(
                            onClick = { ctx.actions.mergeAlternative(track, source) },
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                        ) { Text("合并") }
                    },
                    onLongClick = null,
                )
            }
        }
    }
}

@Composable
private fun AlternativesHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 6.dp)
            .heightIn(min = 48.dp)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .clickable(onClick = onToggle)
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("其他版本 · $count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Live、伴奏、重制版不会自动合并",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 结果行。列表够宽（≥560dp）时平台徽标放在行尾，否则放在第二行歌手名前面，避免挤掉歌名。
 * 平板的“更多”是锚定菜单，手机和电视打开操作面板。
 */
@Composable
private fun SearchSongRow(
    ctx: SearchCtx,
    track: CanonicalTrack,
    index: Int,
    count: Int,
    rowWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val window = LocalAppWindow.current
    val cookies = ctx.state.cookies
    var menuOpen by remember { mutableStateOf(false) }
    val wide = rowWidth >= 560.dp
    val inlineMax = if (rowWidth >= 420.dp) 4 else 3
    val badges: (@Composable RowScope.() -> Unit)? = if (wide) {
        null
    } else {
        { PlatformBadges(track, cookies, max = inlineMax) }
    }
    val trailing: (@Composable RowScope.() -> Unit)? = if (!wide && !window.isTablet) {
        null
    } else {
        {
            if (wide) PlatformBadges(track, cookies, max = 5, modifier = Modifier.padding(end = 4.dp))
            if (window.isTablet) MoreActionButton(ctx, track, menuOpen, { menuOpen = it })
        }
    }
    SongRow(
        title = track.title,
        subtitle = if (wide) {
            listOf(track.subtitle, track.durationLabel).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            track.artistLabel
        },
        cover = track.cover,
        index = index,
        count = count,
        onClick = { ctx.play(track) },
        modifier = modifier.onMenuKey { ctx.openActions(track) },
        playing = ctx.isCurrent(track),
        badges = badges,
        trailing = trailing,
        onMore = if (window.isTablet) null else ({ ctx.openActions(track) }),
        onLongClick = if (window.isTablet) ({ menuOpen = true }) else ({ ctx.openActions(track) }),
    )
}

/** “更多”：平板是锚定在按钮旁的菜单，手机和电视打开歌曲操作面板。 */
@Composable
private fun MoreActionButton(
    ctx: SearchCtx,
    track: CanonicalTrack,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val tablet = LocalAppWindow.current.isTablet
    Box {
        IconButton(
            onClick = { if (tablet) onExpandedChange(true) else ctx.openActions(track) },
            modifier = Modifier.tvFocusRing(CircleShape),
        ) {
            Icon(Icons.Filled.MoreVert, "更多操作：${track.title}")
        }
        if (tablet) TrackMenu(ctx, track, expanded) { onExpandedChange(false) }
    }
}

@Composable
private fun TrackMenu(ctx: SearchCtx, track: CanonicalTrack, expanded: Boolean, onDismiss: () -> Unit) {
    val song = preferredSong(track, ctx.state.cookies) ?: return
    val favorite = favoriteSong(track, ctx.favoriteKeys)
    var pickPlaylist by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp)) {
        if (!pickPlaylist) {
            MenuItem("播放", Icons.Filled.PlayArrow) { onDismiss(); ctx.play(track) }
            MenuItem(if (favorite != null) "取消收藏" else "收藏", if (favorite != null) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) {
                onDismiss()
                ctx.actions.favorite(favorite ?: song)
            }
            DropdownMenuItem(
                text = { Text("加入歌单") },
                leadingIcon = { Icon(Icons.Filled.LibraryAdd, null) },
                trailingIcon = { Icon(Icons.Filled.ChevronRight, null) },
                enabled = ctx.playlists.isNotEmpty(),
                onClick = { pickPlaylist = true },
            )
            MenuItem("下载", Icons.Filled.Download) { onDismiss(); ctx.actions.download(song) }
            HorizontalDivider()
            MenuItem("换源", Icons.Filled.SwapHoriz) { onDismiss(); ctx.openSource(track) }
            MenuItem("合并到…", Icons.Filled.Merge) { onDismiss(); ctx.actions.openMerge(track) }
        } else {
            MenuItem("返回", Icons.AutoMirrored.Filled.ArrowBack) { pickPlaylist = false }
            HorizontalDivider()
            ctx.playlists.forEach { playlist ->
                DropdownMenuItem(
                    text = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Text("${playlist.trackCount} 首", style = MaterialTheme.typography.labelMedium) },
                    onClick = {
                        onDismiss()
                        ctx.actions.addSongToPlaylist(playlist.id, song)
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** 最佳匹配：primary 容器。手机横排（封面 96dp），平板 / 电视左栏竖排。 */
@Composable
private fun BestMatchCard(
    ctx: SearchCtx,
    track: CanonicalTrack,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val window = LocalAppWindow.current
    val shape = RoundedCornerShape(32.dp)
    val isCurrent = ctx.isCurrent(track)
    var menuOpen by remember { mutableStateOf(false) }
    val play: () -> Unit = {
        if (isCurrent) ctx.actions.transport("play") else ctx.play(track)
    }
    Box(
        modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .combinedClickable(
                onClickLabel = "播放",
                onClick = { ctx.play(track) },
                onLongClickLabel = "更多操作",
                onLongClick = { if (window.isTablet) menuOpen = true else ctx.openActions(track) },
            )
            .onMenuKey { ctx.openActions(track) },
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) {
            val playButton = @Composable {
                PlayPauseButton(
                    playing = isCurrent && ctx.state.playing,
                    onClick = play,
                    size = if (vertical) PlayButtonSize.Medium else PlayButtonSize.Small,
                )
            }
            if (vertical) {
                Column(Modifier.padding(16.dp)) {
                    CoverBox(
                        track.cover,
                        track.title,
                        Modifier
                            .then(if (window.isTv) Modifier.size(180.dp) else Modifier.fillMaxWidth().aspectRatio(1f)),
                        RoundedCornerShape(28.dp),
                        fontSize = 56f,
                    )
                    Spacer(Modifier.height(14.dp))
                    BestText(track, large = true)
                    PlatformBadges(track, ctx.state.cookies, max = 5, modifier = Modifier.padding(top = 10.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MoreActionButton(ctx, track, menuOpen) { menuOpen = it }
                        playButton()
                    }
                }
            } else {
                Row(
                    Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(track.cover, track.title, 96.dp, corner = 28.dp)
                    Column(Modifier.weight(1f)) {
                        BestText(track, large = false)
                        PlatformBadges(track, ctx.state.cookies, max = 3, modifier = Modifier.padding(top = 8.dp))
                    }
                    Column(
                        Modifier.height(96.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MoreActionButton(ctx, track, menuOpen) { menuOpen = it }
                        playButton()
                    }
                }
            }
        }
    }
}

@Composable
private fun BestText(track: CanonicalTrack, large: Boolean) {
    val color = LocalContentColor.current
    Text("最佳匹配", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color.copy(alpha = 0.8f))
    Text(
        track.title,
        style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Black,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        listOf(track.subtitle, track.durationLabel).filter { it.isNotBlank() }.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = color.copy(alpha = 0.8f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LoadMore(ctx: SearchCtx) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        // 电视加载中也保留按钮（searchMore 自己会忽略重复点按）：换成指示器会把遥控器焦点一起移除
        if (ctx.search.loading && !LocalAppWindow.current.isTv) {
            LoadingIndicator(Modifier.size(48.dp))
        } else {
            FilledTonalButton(
                onClick = ctx.actions::searchMore,
                modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
            ) { Text(if (ctx.search.loading) "正在加载…" else "加载更多") }
        }
    }
}

/* ---------------- 平台状态 ---------------- */

private class ProviderLine(val name: String, val state: ProviderSearchState?)

private class ProviderSummary(val lines: List<ProviderLine>) {
    val total get() = lines.size
    val returned get() = lines.count { it.state != null && it.state.status != ProviderStatus.Loading }
    val failed get() = lines.mapNotNull { it.state }.filter { it.status in FailedStatuses }
}

private fun kindProviders(state: MusicUiState, kind: SearchKind): List<ProviderInfo> {
    val supports = state.providers.filter {
        when (kind) {
            SearchKind.Album -> it.searchAlbum
            SearchKind.Playlist -> it.searchPlaylist
            else -> it.search
        }
    }
    val enabled = state.aggregation.enabledOrDefault(supports.map { it.id })
    return supports.filter { it.id in enabled }
}

private fun summarize(ctx: SearchCtx): ProviderSummary {
    val kind = ctx.search.kind
    val states = if (kind == SearchKind.Song) ctx.search.providerStates else ctx.search.collectionProviderStates
    val providers = kindProviders(ctx.state, kind)
    val lines = providers.map { ProviderLine(it.name, states[it.id]) } +
        states.values.filter { s -> providers.none { it.id == s.providerId } }.map { ProviderLine(it.providerName, it) }
    return ProviderSummary(lines)
}

private fun statusLabel(state: ProviderSearchState?, kind: SearchKind): String = when (state?.status) {
    null, ProviderStatus.Loading -> "搜索中"
    ProviderStatus.Success -> {
        val count = state?.count ?: 0
        if (count == 0) "无结果" else "$count ${if (kind == SearchKind.Song) "首" else "个"}"
    }
    ProviderStatus.Timeout -> "超时"
    ProviderStatus.Error -> "出错"
    ProviderStatus.Skipped -> "暂时跳过"
    ProviderStatus.Unsupported -> "不支持"
}

private fun failedText(failed: List<ProviderSearchState>, kind: SearchKind): String =
    failed.joinToString("，") { it.providerName + statusLabel(it, kind) }

/** 结果上方：进行中 / 全部返回时是状态条；有平台失败且已结束时换成“只重试失败的”提示。 */
@Composable
private fun StatusArea(ctx: SearchCtx, retryFocus: FocusRequester? = null, beforeRetry: () -> Unit = {}) {
    val search = ctx.search
    val summary = summarize(ctx)
    if (summary.total == 0 && !search.loading) return
    val song = search.kind == SearchKind.Song
    val hasResults = if (song) search.aggregateGroups.isNotEmpty() else search.aggregateCollections.isNotEmpty()
    val allFailed = summary.failed.isNotEmpty() && summary.failed.size >= summary.lines.count { it.state != null }
    if (!search.loading && summary.failed.isNotEmpty() && (hasResults || !allFailed)) {
        PartialFailureCard(ctx, summary.failed, retryFocus, beforeRetry)
    } else {
        StatusStrip(summary, search.loading, search.kind)
    }
}

@Composable
private fun StatusStrip(summary: ProviderSummary, loading: Boolean, kind: SearchKind) {
    val window = LocalAppWindow.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val showDetails = !window.isPhone || expanded
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .then(if (window.isPhone) Modifier.clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded } else Modifier)
                .semantics { if (window.isPhone) stateDescription = if (expanded) "已展开各平台状态" else "已收起各平台状态" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                } else {
                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(
                    if (loading) "正在搜索 · ${summary.returned}/${summary.total} 个平台已返回"
                    else "${summary.total} 个平台 · ${if (summary.returned >= summary.total) "全部" else "${summary.returned} 个"}返回",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                failedText(summary.failed, kind),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (window.isPhone) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showDetails) {
            FlowRow(
                Modifier.padding(top = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                summary.lines.forEach { line -> ProviderStateChip(line, kind) }
            }
        }
    }
}

/** 单个平台的状态：图标 + 文字，不只靠颜色。 */
@Composable
private fun ProviderStateChip(line: ProviderLine, kind: SearchKind) {
    val status = line.state?.status
    val failed = status != null && status in FailedStatuses
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tint = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
        when (status) {
            null, ProviderStatus.Loading -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            ProviderStatus.Success -> Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = tint)
            ProviderStatus.Timeout -> Icon(Icons.Filled.Schedule, null, Modifier.size(14.dp), tint = tint)
            ProviderStatus.Error -> Icon(Icons.Filled.ErrorOutline, null, Modifier.size(14.dp), tint = tint)
            ProviderStatus.Skipped, ProviderStatus.Unsupported -> Icon(Icons.Filled.Block, null, Modifier.size(14.dp), tint = tint)
        }
        Text(
            "${line.name} · ${statusLabel(line.state, kind)}",
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PartialFailureCard(
    ctx: SearchCtx,
    failed: List<ProviderSearchState>,
    retryFocus: FocusRequester?,
    beforeRetry: () -> Unit,
) {
    val song = ctx.search.kind == SearchKind.Song
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("${failed.size} 个平台没有返回结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${failedText(failed, ctx.search.kind)}。空结果不代表全网没有。",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = {
                        beforeRetry()
                        ctx.actions.retryFailedSearch()
                    },
                    enabled = !ctx.search.loading,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .then(if (retryFocus != null) Modifier.focusRequester(retryFocus) else Modifier)
                        .tvFocusRing(RoundedCornerShape(24.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (song) "重试这 ${failed.size} 个" else "重新搜索")
                }
                TextButton(
                    onClick = ctx.openSettings,
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                ) { Text("检查聚合平台") }
            }
        }
    }
}

/* ---------------- 加载 / 空 / 失败 ---------------- */

/** 超过 200ms 才显示加载，避免一闪而过。 */
@Composable
private fun rememberDelayed(active: Boolean, delayMs: Long = 200): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(delayMs)
            visible = true
        } else {
            visible = false
        }
    }
    return active && visible
}

@Composable
private fun LoadingSkeleton(withBest: Boolean) {
    if (!rememberDelayed(true)) {
        Spacer(Modifier.height(1.dp))
        return
    }
    Column(Modifier.fillMaxWidth()) {
        if (withBest) BestSkeleton(vertical = false)
        AppLoading(contained = true)
        SkeletonRows(6)
    }
}

@Composable
private fun BestSkeleton(vertical: Boolean) {
    val shape = RoundedCornerShape(32.dp)
    if (vertical) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Skeleton(Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(28.dp))
            Skeleton(Modifier.fillMaxWidth(0.3f).height(12.dp), CircleShape)
            Skeleton(Modifier.fillMaxWidth(0.7f).height(24.dp), CircleShape)
            Skeleton(Modifier.fillMaxWidth(0.5f).height(14.dp), CircleShape)
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Skeleton(Modifier.size(96.dp), RoundedCornerShape(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Skeleton(Modifier.fillMaxWidth(0.3f).height(12.dp), CircleShape)
                Skeleton(Modifier.fillMaxWidth(0.7f).height(22.dp), CircleShape)
                Skeleton(Modifier.fillMaxWidth(0.5f).height(14.dp), CircleShape)
            }
        }
    }
}

@Composable
private fun SongEmptyState(ctx: SearchCtx, focus: FocusRequester?) {
    val search = ctx.search
    val failed = summarize(ctx).failed
    val retry = { ctx.actions.submitSearch(search.submittedQuery) }
    when {
        search.error.isNotBlank() -> StateBlock(
            icon = Icons.Filled.CloudOff,
            title = "这次没能搜索",
            message = search.error,
            error = true,
            primary = "检查聚合平台" to ctx.openSettings,
            secondary = "重试" to retry,
            focus = focus,
        )
        search.aggregateStatus == SearchStatus.Failed -> StateBlock(
            icon = Icons.Filled.WifiOff,
            title = "平台都没有返回结果",
            message = buildString {
                if (failed.isNotEmpty()) append("${failedText(failed, SearchKind.Song)}。")
                append("可能是网络问题，也可能是平台接口暂时不可用。")
            },
            error = true,
            primary = "重试" to retry,
            secondary = "检查聚合平台" to ctx.openSettings,
            focus = focus,
        )
        search.aggregateStatus == SearchStatus.Empty -> StateBlock(
            icon = Icons.Filled.SearchOff,
            title = "没有找到这首歌",
            message = if (failed.isNotEmpty()) {
                "已返回的平台里没有匹配结果，另外${failedText(failed, SearchKind.Song)}，可以单独重试。"
            } else {
                "试试歌曲名加歌手，或换一个关键词。"
            },
            error = false,
            primary = "检查聚合平台" to ctx.openSettings,
            secondary = null,
            focus = focus,
        )
    }
}

@Composable
private fun CollectionEmptyState(ctx: SearchCtx, focus: FocusRequester?) {
    val search = ctx.search
    val label = if (search.kind == SearchKind.Album) "专辑" else "歌单"
    val retry = { ctx.actions.submitSearch(search.submittedQuery) }
    when {
        search.error.isNotBlank() -> StateBlock(
            icon = Icons.Filled.CloudOff,
            title = "这次没能搜索$label",
            message = search.error,
            error = true,
            primary = "检查聚合平台" to ctx.openSettings,
            secondary = "重试" to retry,
            focus = focus,
        )
        search.collectionStatus == SearchStatus.Failed -> StateBlock(
            icon = Icons.Filled.WifiOff,
            title = "平台都没有返回$label",
            message = "可能是网络问题，也可能是平台接口暂时不可用。",
            error = true,
            primary = "重试" to retry,
            secondary = "检查聚合平台" to ctx.openSettings,
            focus = focus,
        )
        search.collectionStatus == SearchStatus.Empty -> StateBlock(
            icon = Icons.Filled.SearchOff,
            title = "没有找到相关$label",
            message = "换一个关键词，或检查参与聚合的平台。",
            error = false,
            primary = "检查聚合平台" to ctx.openSettings,
            secondary = null,
            focus = focus,
        )
    }
}

/** 空 / 失败：装饰形状 + 一句说明 + 明确的下一步。失败用 error 容器色，并配图标和文字。 */
@Composable
private fun StateBlock(
    icon: ImageVector,
    title: String,
    message: String,
    error: Boolean,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>?,
    focus: FocusRequester?,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(132.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                Modifier.size(52.dp),
                tint = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
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
            Modifier.padding(top = 8.dp).widthIn(max = 420.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FlowRow(
            Modifier.padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = primary.second,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                    .tvFocusRing(RoundedCornerShape(24.dp)),
            ) {
                if (primary.first == "重试") {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(primary.first)
            }
            if (secondary != null) {
                FilledTonalButton(
                    onClick = secondary.second,
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
                ) { Text(secondary.first) }
            }
        }
    }
}

/* ---------------- 歌单 / 专辑结果 ---------------- */

private fun LazyListScope.collectionItems(ctx: SearchCtx, stateFocus: FocusRequester? = null) {
    val search = ctx.search
    val items = search.aggregateCollections
    item("status") { StatusArea(ctx) }
    if (items.isEmpty()) {
        if (search.loading) {
            item("skeleton") {
                if (rememberDelayed(true)) SkeletonRows(6) else Spacer(Modifier.height(1.dp))
            }
        } else {
            item("empty") { CollectionEmptyState(ctx, stateFocus) }
        }
        return
    }
    val keys = distinctKeys(items) { it.id }
    itemsIndexed(items, key = { index, _ -> keys[index] }) { index, item ->
        CollectionRow(ctx, item, index, items.size, Modifier.padding(top = if (index == 0) 4.dp else SegmentGap))
    }
    item("note") { CollectionNote(search.kind) }
}

@Composable
private fun CollectionNote(kind: SearchKind) {
    Text(
        if (kind == SearchKind.Album) {
            "按名称、歌手和曲目数把各平台的同一张专辑放在一起，点平台名打开对应版本。"
        } else {
            "歌单只按同名推荐，不合并不同平台的歌单内容。"
        },
        Modifier.padding(start = 4.dp, top = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 专辑 / 歌单分组：手机平台按钮在第二行，平板与名称同一行。 */
@Composable
private fun CollectionRow(ctx: SearchCtx, item: CanonicalCollection, index: Int, count: Int, modifier: Modifier = Modifier) {
    val window = LocalAppWindow.current
    val album = ctx.search.kind == SearchKind.Album
    val shape = segmentShape(index, count)
    val first = item.sources.firstOrNull()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(enabled = first != null) { first?.let { ctx.actions.openPlaylist(it.playlist, album) } }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(item.cover, item.name, 60.dp, corner = 12.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                collectionSubtitle(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (window.isPhone) CollectionSources(ctx, item, Modifier.padding(top = 8.dp))
        }
        if (!window.isPhone) CollectionSources(ctx, item, Modifier.widthIn(max = 360.dp))
    }
}

private fun collectionSubtitle(item: CanonicalCollection): String = listOf(
    item.creator,
    if (item.trackCount > 0) "${item.trackCount} 首" else "",
    "${item.sources.map { it.providerId }.distinct().size} 个平台",
).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun CollectionSources(ctx: SearchCtx, item: CanonicalCollection, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item.sources.forEach { source -> SourcePill(ctx, source) }
    }
}

/** 平台按钮：平台名 + 该平台版本的曲目数，点按打开这个平台的版本。 */
@Composable
private fun SourcePill(ctx: SearchCtx, source: CollectionSource, focusRequester: FocusRequester? = null) {
    val album = ctx.search.kind == SearchKind.Album
    val shape = RoundedCornerShape(8.dp)
    val count = source.playlist.trackCount
    Surface(
        onClick = { ctx.actions.openPlaylist(source.playlist, album) },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusRing(shape)
            .semantics { contentDescription = "在${source.providerName}打开" + if (count > 0) "，$count 首" else "" },
    ) {
        // 按钮已有完整描述，里面的简称和曲目数不再单独读
        Row(
            Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp).clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(providerMark(source.providerName), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (count > 0) {
                Text("$count 首", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/* ======================== 电视 ======================== */

@Composable
private fun TvSearch(ctx: SearchCtx, showHome: Boolean, onEdit: (Boolean) -> Unit) {
    val window = LocalAppWindow.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 28.dp, end = window.gutter, top = 32.dp)) {
        when {
            showHome -> TvSearchHome(ctx)
            ctx.search.kind == SearchKind.Song -> TvSongResults(ctx, onEdit, rowWidth = maxWidth - TvLeft - 32.dp)
            else -> TvCollectionResults(ctx, onEdit)
        }
    }
}

/** 电视输入：左侧输入框 + 6 列屏幕键盘（焦点在键上），右侧最近搜索大 chip。 */
@Composable
private fun TvSearchHome(ctx: SearchCtx) {
    val firstKey = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstKey.requestFocus() }
    }
    val query = ctx.search.query
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(
            Modifier
                .width(320.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 48.dp),
        ) {
            SearchField(
                value = query,
                onValueChange = ctx.actions::setSearchQuery,
                onSubmit = { ctx.submit(query) },
                onClear = null,
                onBack = null,
                focusRequester = fieldFocus,
                onFocused = {},
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            TvKeyboard(
                query = query,
                onQueryChange = ctx.actions::setSearchQuery,
                onSubmit = { ctx.submit(query) },
                firstKey = firstKey,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 48.dp),
        ) {
            Text("搜索", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            KindToggle(ctx, Modifier.padding(top = 12.dp).widthIn(max = 420.dp).fillMaxWidth())
            HomeContent(ctx, Modifier.fillMaxWidth(), readClipboard = false, big = true)
            Text(
                "选中输入框按确认键，可以用系统键盘、遥控器语音，或手机上的 Google TV 应用输入中文。",
                Modifier.padding(start = 4.dp, top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TvKeyboard(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit, firstKey: FocusRequester) {
    val keys = "abcdefghijklmnopqrstuvwxyz0123456789".toList()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(6).forEachIndexed { row, chars ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chars.forEachIndexed { column, char ->
                    TvKey(
                        label = char.toString(),
                        modifier = Modifier
                            .weight(1f)
                            .then(if (row == 0 && column == 0) Modifier.focusRequester(firstKey) else Modifier),
                    ) { onQueryChange(query + char) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TvKey(label = "空格", modifier = Modifier.weight(2f)) { onQueryChange("$query ") }
            TvKey(icon = Icons.AutoMirrored.Filled.Backspace, description = "退格", modifier = Modifier.weight(1f)) {
                onQueryChange(query.dropLast(1))
            }
            TvKey(icon = Icons.Filled.Close, description = "清空", modifier = Modifier.weight(1f)) { onQueryChange("") }
        }
        Button(
            onClick = onSubmit,
            enabled = query.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
        ) {
            Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("搜索")
        }
    }
}

@Composable
private fun TvKey(
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    description: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp).tvFocusRing(shape, scale = 1.1f),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, description, Modifier.size(20.dp))
            } else if (label != null) {
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** 电视上的搜索词：按确认键回到输入。 */
@Composable
private fun TvQueryBar(query: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = 56.dp).tvFocusRing(shape, scale = 1.02f).semantics { contentDescription = "修改搜索词：$query" },
    ) {
        Row(Modifier.padding(horizontal = 16.dp).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(query, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 电视单曲结果：左栏搜索词和最佳匹配，右栏结果；默认焦点在第一条结果。 */
@Composable
private fun TvSongResults(ctx: SearchCtx, onEdit: (Boolean) -> Unit, rowWidth: Dp) {
    val search = ctx.search
    val groups = search.aggregateGroups
    val target = remember { FocusRequester() }
    val bestFocus = remember { FocusRequester() }
    var altExpanded by rememberSaveable { mutableStateOf(false) }
    var focusedQuery by rememberSaveable { mutableStateOf("") }
    val ready = groups.isNotEmpty() || !search.loading
    LaunchedEffect(search.submittedQuery, ready) {
        // 重试 / 重新搜索时结果整块换成骨架，原来的焦点跟着没了，加载完要重新给
        if (!ready) focusedQuery = ""
        if (ready && focusedQuery != search.submittedQuery) {
            withFrameNanos { }
            runCatching { target.requestFocus() }
            focusedQuery = search.submittedQuery
        }
    }
    val showSkeleton = rememberDelayed(groups.isEmpty() && search.loading)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(
            Modifier
                .width(TvLeft)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 48.dp),
        ) {
            TvQueryBar(search.submittedQuery, onClick = { onEdit(true) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            when {
                groups.isNotEmpty() -> BestMatchCard(
                    ctx,
                    groups.first(),
                    vertical = true,
                    modifier = Modifier.focusRequester(bestFocus),
                    focusRequester = if (groups.size == 1) target else null,
                )
                showSkeleton -> BestSkeleton(vertical = true)
            }
            // 重试后失败卡片会被状态条替换，先把焦点交给上面的最佳匹配，免得跟着卡片一起丢
            StatusArea(ctx, beforeRetry = { runCatching { bestFocus.requestFocus() } })
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp, start = 4.dp, end = 4.dp),
        ) {
            item("kinds") { KindToggle(ctx, Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(bottom = 12.dp)) }
            songItems(
                ctx,
                rowWidth,
                bestInline = false,
                altExpanded = altExpanded,
                onToggleAlt = { altExpanded = !altExpanded },
                statusInList = false,
                firstFocus = if (groups.size > 1) target else null,
                stateFocus = if (groups.isEmpty()) target else null,
            )
        }
    }
}

/** 电视专辑 / 歌单结果：横向卡片行，焦点所在卡片的平台显示在下方。 */
@Composable
private fun TvCollectionResults(ctx: SearchCtx, onEdit: (Boolean) -> Unit) {
    val search = ctx.search
    val items = search.aggregateCollections
    val target = remember { FocusRequester() }
    var focusedQuery by rememberSaveable { mutableStateOf("") }
    var focusedId by remember { mutableStateOf("") }
    val ready = items.isNotEmpty() || !search.loading
    LaunchedEffect(search.submittedQuery, search.kind, ready) {
        val key = "${search.kind}:${search.submittedQuery}"
        // 重新搜索会先清空结果，焦点跟着没了，加载完要重新给
        if (!ready) focusedQuery = ""
        if (ready && focusedQuery != key) {
            withFrameNanos { }
            runCatching { target.requestFocus() }
            focusedQuery = key
        }
    }
    val album = search.kind == SearchKind.Album
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TvQueryBar(search.submittedQuery, onClick = { onEdit(true) }, modifier = Modifier.weight(1f))
            KindToggle(ctx, Modifier.width(360.dp))
        }
        Spacer(Modifier.height(8.dp))
        StatusArea(ctx)
        when {
            items.isEmpty() && search.loading -> if (rememberDelayed(true)) {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    repeat(6) { Skeleton(Modifier.size(160.dp), RoundedCornerShape(20.dp)) }
                }
            }
            items.isEmpty() -> CollectionEmptyState(ctx, target)
            else -> {
                val focused = items.find { it.id == focusedId } ?: items.first()
                val keys = distinctKeys(items) { it.id }
                LazyRow(
                    contentPadding = PaddingValues(vertical = 20.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    itemsIndexed(items, key = { index, _ -> keys[index] }) { index, item ->
                        TvCollectionCard(
                            item,
                            album,
                            onFocused = { focusedId = item.id },
                            onOpen = { item.sources.firstOrNull()?.let { ctx.actions.openPlaylist(it.playlist, album) } },
                            modifier = if (index == 0) Modifier.focusRequester(target) else Modifier,
                        )
                    }
                }
                Text(
                    "${focused.name} · ${collectionSubtitle(focused)}，可在这些平台打开：",
                    Modifier.padding(start = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    focused.sources.forEach { source -> SourcePill(ctx, source) }
                }
                CollectionNote(search.kind)
            }
        }
    }
}

@Composable
private fun TvCollectionCard(
    item: CanonicalCollection,
    album: Boolean,
    onFocused: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .width(176.dp)
            .tvCardFocus(shape, onFocused)
            .clip(shape)
            .clickable(onClick = onOpen)
            // 放在 clickable 之后：保留点击，同时不再把下面的标题、作者重复读一遍
            .clearAndSetSemantics { contentDescription = "${if (album) "专辑" else "歌单"}：${item.name}，${collectionSubtitle(item)}" }
            .padding(8.dp),
    ) {
        CoverBox(item.cover, item.name, Modifier.size(160.dp), RoundedCornerShape(20.dp), fontSize = 48f)
        Text(
            item.name,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.creator.ifBlank { "${item.trackCount} 首" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ======================== 工具 ======================== */

/** 列表 key：id 可能重复（拆分出的版本、同名同歌手的专辑），后出现的加序号，否则 Lazy 列表会因重复 key 崩溃。 */
private fun <T> distinctKeys(items: List<T>, id: (T) -> String): List<String> {
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val base = id(item)
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        if (count == 1) base else "$base#$count"
    }
}

/** 电视遥控器菜单键打开歌曲操作。 */
private fun Modifier.onMenuKey(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
        action()
        true
    } else {
        false
    }
}

/** 收藏、下载、加歌单用的那一个音源：优先完整可播的平台。 */
private fun preferredSong(track: CanonicalTrack, cookies: Map<String, String>): Song? =
    track.sources.firstOrNull { availabilityOf(it, cookies[it.providerId].orEmpty()) == Availability.Playable }?.song
        ?: track.sources.firstOrNull()?.song

/** 已收藏的那个音源；取消收藏时要用它的 key。 */
private fun favoriteSong(track: CanonicalTrack, favoriteKeys: Set<String>): Song? =
    track.sources.firstOrNull { it.song.key in favoriteKeys }?.song

private fun extractUrl(text: String): String = UrlPattern.find(text)?.value ?: text

private fun linkPlatform(text: String): String {
    val lower = text.lowercase()
    return when {
        "163.com" in lower || "163cn.tv" in lower -> "网易云音乐"
        "qq.com" in lower -> "QQ 音乐"
        "kugou" in lower -> "酷狗音乐"
        "kuwo" in lower -> "酷我音乐"
        "migu" in lower -> "咪咕音乐"
        "bilibili" in lower || "b23.tv" in lower -> "Bilibili"
        "apple.com" in lower -> "Apple Music"
        else -> ""
    }
}
