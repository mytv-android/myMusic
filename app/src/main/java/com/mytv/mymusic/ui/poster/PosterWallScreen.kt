package com.mytv.mymusic.ui.poster

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.ListeningFeedUi
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.charts.SongAction
import com.mytv.mymusic.ui.charts.SongActionsSheet
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.player.AmbientClock
import com.mytv.mymusic.ui.player.rememberScreenReaderOn
import com.mytv.mymusic.ui.providerName
import com.mytv.mymusic.ui.queueMoveNextTarget
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 队列海报墙：播放队列铺成的墙（[PosterWall]）加上浮层——顶栏胶囊（返回 + 标题）、“定位”和设置。
 * 挂在 MyMusicApp 里主界面之上、播放页之下，外面套 PlayerTheme（深色，随当前封面取色、450ms 过渡）。
 *
 * - 手机 / 平板：胶囊在左上，“定位”48dp 圆钮和设置 56dp FAB 在右下，都让开系统栏；
 * - 电视：胶囊、定位、设置和时钟（“电视时钟”开着时）排在顶部 80dp 一排，大卡放在它下面；空闲 6 秒浮层淡出，时钟留着。
 *   墙是无限的，方向键在墙里永远有邻格，普通焦点搜索走不出去：墙上没有选中格时按上键到顶部一排
 *   （有选中格时先按返回清掉），在顶部一排按下键回到墙并选中离中心最近的那格；
 * - 返回：设置 / 歌曲操作面板自己先关 → 墙这一层（漂移唤醒、焦点交回墙、收起别的歌的大卡、清焦点）→ 离开海报墙；
 * - [active] 为 false（播放页盖在上面）时墙停下帧循环和跟拍、大卡里的动画也停，不拿焦点、不给读屏；
 *   墙的状态留在这里，关掉播放页回来镜头还在原处（期间切了歌就已经停在新歌上）；
 * - [focusReady] 播放页已经完全退场：电视上这时才把焦点交给墙（播放页的焦点组退场途中不放焦点出去）。
 *
 * 大卡“播放”走 seekTo（[MusicActions.playQueueIndex]），不换队列，听歌模式的续流不断；“下一首播放”只在队列里挪位置，
 * 挪过去的那首在新位置上接着放大。
 */
@Composable
internal fun PosterWallScreen(
    state: MusicUiState,
    position: State<Long>,
    favorites: List<SavedSong>,
    playlists: List<LocalPlaylistSummary>,
    actions: MusicActions,
    active: Boolean,
    focusReady: () -> Boolean,
    onOpenQueue: () -> Unit,
) {
    val current = state.current ?: return
    val window = LocalAppWindow.current
    val tv = window.isTv
    val songs = state.queue
    val currentKey = current.key
    val currentIndex = remember(songs, currentKey) { songs.indexOfFirst { it.key == currentKey } }
    val feed = state.listeningFeed?.takeIf { it.owns(current) }
    val wall = rememberPosterWallState()
    val chromeFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var actionSong by remember { mutableStateOf<Song?>(null) }
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val screenReader = rememberScreenReaderOn()
    val pulse = rememberPosterPulse(
        playing = state.playing,
        mode = state.lyricsBackground,
        intensity = state.rhythmIntensity,
        rhythmOffsetMs = state.rhythmOffsetMs,
        visible = active,
    )
    val pulseScale = remember(pulse) { { pulse.coverScale } }
    // 切歌后新歌词取回来之前 state.lyrics 还是上一首的
    val lyricsReady = state.lyricsKey == currentKey
    val metrics = wallMetricsFor(tv, window.widthDp, window.heightDp)
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val (insetTop, insetBottom) = wallInsets(metrics, insets.calculateTopPadding(), insets.calculateBottomPadding(), window.heightDp)

    BackHandler(enabled = active) {
        if (!wall.handleBack()) actions.closePosterWall()
    }
    if (tv) {
        LaunchedEffect(wall, active) {
            if (!active) return@LaunchedEffect
            snapshotFlow { focusReady() }.first { it }
            wall.requestWallFocus()
        }
    }

    fun toast(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    val playNext: (Int) -> Unit = { index ->
        val song = songs.getOrNull(index)
        val target = queueMoveNextTarget(index, currentIndex, songs.size)
        when {
            song == null -> Unit
            target == null -> toast("「${song.title}」已经是下一首")
            else -> {
                actions.moveQueueItemNext(index)
                // 槽位按序号排：挪完这首坐到 target 那一位，在新位置上接着放大
                wall.growSong(target)
                toast("「${song.title}」将在下一首播放")
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // 播放页盖在上面时，墙不让读屏读到、也不让遥控器焦点进入
            .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
            .focusProperties { onEnter = { if (!active) cancelFocusChange() } }
            .focusGroup()
            .posterWallWake(wall),
    ) {
        PosterWall(
            state = wall,
            songs = songs,
            currentIndex = currentIndex,
            feed = feed?.let { PosterFeed(it.appending) },
            prefs = state.poster,
            insetTop = insetTop,
            insetBottom = insetBottom,
            onLongPress = { index -> songs.getOrNull(index)?.let { actionSong = it } },
            onGrownConfirm = { _, isCurrent ->
                // 正在播放的卡：OK 就是播放 / 暂停；别的歌：焦点交进卡里，再选“播放 / 下一首播放 / 更多”
                if (isCurrent) actions.transport("play") else runCatching { primaryFocus.requestFocus() }
            },
            autoFocus = false,
            active = active,
            chromeAbove = if (tv) chromeFocus else null,
            resetKey = state.queueId,
            currentKey = currentKey,
            holdAwake = settingsOpen || actionSong != null || state.sourcePickerTrack != null,
            grownContent = { index, isCurrent, settled ->
                val song = songs.getOrNull(index)
                if (song != null) {
                    GrownPoster(
                        song = song,
                        number = index + 1,
                        sourceName = providerName(state.providers, song.source),
                        current = isCurrent,
                        playing = state.playing,
                        position = position,
                        duration = state.duration,
                        lyrics = if (isCurrent && lyricsReady) state.lyrics else "",
                        showTranslation = state.poster.translation,
                        // 被播放页盖着时当作没落定：歌词不组合，跳动的条、花瓣旋转和波浪都停
                        settled = settled && active,
                        coverScale = if (isCurrent) pulseScale else NoPulse,
                        onPlayPause = { actions.transport("play") },
                        onPrevious = { actions.transport("previous") },
                        onNext = { actions.transport("next") },
                        onSeek = actions::seek,
                        onOpenPlayer = actions::openPlayer,
                        onPlay = {
                            actions.playQueueIndex(index)
                            // 这张卡马上换成正在播放的样子，焦点所在的“播放”跟着没了：交回墙，焦点环留在这张卡上
                            if (tv) wall.requestWallFocus()
                        },
                        onPlayNext = { playNext(index) },
                        onMore = { actionSong = song },
                        focused = wall.grownFocused,
                        primaryFocusRequester = primaryFocus,
                        coverPlaceholderKey = wall.grownCoverKey,
                        lyricsLoading = isCurrent && !lyricsReady,
                        lightsOut = state.poster.lightsOut,
                    )
                }
            },
        )
        val title = feed?.name ?: "播放队列"
        val subtitle = wallSubtitle(feed, songs.size, currentIndex)
        if (tv) {
            TvTopRow(
                wall = wall,
                title = title,
                subtitle = subtitle,
                chromeFocus = chromeFocus,
                showClock = state.tvAmbientClock,
                screenReader = screenReader,
                onBack = actions::closePosterWall,
                onSettings = { settingsOpen = true },
                onOpenQueue = onOpenQueue,
            )
        } else {
            TouchChrome(
                wall = wall,
                metrics = metrics,
                title = title,
                subtitle = subtitle,
                screenReader = screenReader,
                onBack = actions::closePosterWall,
                onSettings = { settingsOpen = true },
                onOpenQueue = onOpenQueue,
            )
        }
    }

    if (settingsOpen) {
        PosterSettingsSheet(state.poster, actions::setPosterPrefs) { settingsOpen = false }
    }
    actionSong?.let { song ->
        SongActionsSheet(SongAction(song, songs), favorites, playlists, actions) { actionSong = null }
    }
}

private val NoPulse: () -> Float = { 1f }

/**
 * 胶囊副标题。队列：“30 首 · 正在播放第 3 首”。听歌模式不写总首数（一直在变）：
 * “听歌模式 · 网易云音乐 · 已听 12 首 · 后面还有 5 首”，续取中再加“正在续取…”。
 */
internal fun wallSubtitle(feed: ListeningFeedUi?, count: Int, currentIndex: Int): String {
    if (feed == null) return if (currentIndex >= 0) "$count 首 · 正在播放第 ${currentIndex + 1} 首" else "$count 首"
    val heard = currentIndex.coerceAtLeast(0)
    val left = (count - heard - 1).coerceAtLeast(0)
    return buildString {
        append("听歌模式")
        if (feed.providerName.isNotBlank()) append(" · ").append(feed.providerName)
        append(" · 已听 ").append(heard).append(" 首 · 后面还有 ").append(left).append(" 首")
        if (feed.appending) append(" · 正在续取…")
    }
}

/**
 * 墙的浮层让出多少高度（大卡居中在两者之间），对照原型：手机顶上让到胶囊下沿（状态栏 + 8 + 48），底下让到按钮上沿再留 6dp；
 * 平板一块约一屏，浮层压在墙的四角，不让；电视让出顶部 80dp 一排。
 * 横屏手机这样让完放不下 332dp 的大卡，就都不让（浮层在角上，压不到居中的大卡）。
 */
internal fun wallInsets(metrics: WallMetrics, safeTop: Dp, safeBottom: Dp, heightDp: Int): Pair<Dp, Dp> = when (metrics) {
    WallMetrics.Tv -> TvTopRowHeight to 0.dp
    WallMetrics.Tablet -> 0.dp to 0.dp
    WallMetrics.Phone -> {
        val top = safeTop + PhonePillTop + PillHeight
        val bottom = safeBottom + PosterWallBottomChrome
        if (heightDp.dp - top - bottom >= metrics.grownSize.dp) top to bottom else 0.dp to 0.dp
    }
}

/** 电视顶部一排的高度，大卡在它下面。 */
internal val TvTopRowHeight = 80.dp

/** 手机 / 平板右下角按钮占的高度（按钮离底 30dp + 56dp FAB + 6dp），提示条要让开它。 */
internal val PosterWallBottomChrome = 92.dp

private val PillHeight = 48.dp
private val PhonePillTop = 8.dp
private val PillShape = RoundedCornerShape(24.dp)
private val ChromeShadow = 6.dp
private const val ChromeFadeMs = 500
private const val ChromeScrimAlpha = 0.86f

/** 手机 / 平板：胶囊在左上，定位和设置在右下（平板留得更宽）。读屏开着时胶囊下面多一个“以列表浏览”。 */
@Composable
private fun TouchChrome(
    wall: PosterWallState,
    metrics: WallMetrics,
    title: String,
    subtitle: String,
    screenReader: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val phone = metrics == WallMetrics.Phone
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = if (phone) 12.dp else 24.dp, top = if (phone) PhonePillTop else 20.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WallTitlePill(title, subtitle, onBack)
            if (screenReader) ListEntry(onOpenQueue)
        }
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = if (phone) 16.dp else 36.dp, bottom = if (phone) 30.dp else 28.dp),
            horizontalArrangement = Arrangement.spacedBy(if (phone) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocateButton(wall)
            FloatingActionButton(
                onClick = onSettings,
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = ChromeShadow),
            ) { Icon(Icons.Filled.Tune, "海报墙设置") }
        }
    }
}

/**
 * 电视顶部一排：左边胶囊，右边定位、设置（都是 48dp 圆钮），最右是时钟。空闲时除了时钟都淡出（时钟本来就是给空闲时看的）。
 * 这一排里按下键回到墙（[PosterWallState.focusFromAbove]）；从墙按上键进来落在“定位”上（[chromeFocus]）。
 */
@Composable
private fun TvTopRow(
    wall: PosterWallState,
    title: String,
    subtitle: String,
    chromeFocus: FocusRequester,
    showClock: Boolean,
    screenReader: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val alpha = rememberChromeAlpha(wall)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 36.dp, top = 24.dp)
            .heightIn(min = PillHeight)
            .onPreviewKeyEvent { e ->
                if (e.key != Key.DirectionDown || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                wall.focusFromAbove()
                true
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).graphicsLayer { this.alpha = alpha.value },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WallTitlePill(title, subtitle, onBack, Modifier.weight(1f, fill = false))
            if (screenReader) ListEntry(onOpenQueue)
        }
        Row(
            Modifier.padding(start = 16.dp).graphicsLayer { this.alpha = alpha.value },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocateButton(wall, Modifier.focusRequester(chromeFocus))
            RoundChromeButton(Icons.Filled.Tune, "海报墙设置", onSettings)
        }
        if (showClock) {
            Spacer(Modifier.width(20.dp))
            AmbientClock(overImages = true)
        }
    }
}

/** 浮层跟着 [PosterWallState.chromeVisible] 淡入淡出（电视空闲 6 秒）；在效果里收集，只改图层透明度，不重组。 */
@Composable
private fun rememberChromeAlpha(wall: PosterWallState): State<Float> {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(wall, alpha) {
        snapshotFlow { wall.chromeVisible }.collectLatest { alpha.animateTo(if (it) 1f else 0f, tween(ChromeFadeMs)) }
    }
    return alpha.asState()
}

/** 顶栏胶囊：48dp 高（大字号时跟着字长高，不裁字）、surfaceContainerHigh 86%，左边返回（离开海报墙），右边标题和一行说明。 */
@Composable
private fun WallTitlePill(title: String, subtitle: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = PillHeight),
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = ChromeScrimAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = ChromeShadow,
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 18.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp).tvFocusRing(CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭海报墙")
            }
            Column {
                Text(
                    title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** “定位正在播放”：正在播放的那首没放大时高亮成主色。高亮只在这个小组件里读墙的状态。 */
@Composable
private fun LocateButton(wall: PosterWallState, modifier: Modifier = Modifier) {
    val hot = wall.hasCurrent && !wall.currentGrown
    val scheme = MaterialTheme.colorScheme
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<androidx.compose.ui.graphics.Color>()
    val container by animateColorAsState(
        if (hot) scheme.primary else scheme.surfaceContainerHigh.copy(alpha = ChromeScrimAlpha),
        effects,
        label = "posterLocate",
    )
    val content by animateColorAsState(if (hot) scheme.onPrimary else scheme.onSurface, effects, label = "posterLocateIcon")
    Surface(
        onClick = wall::locate,
        modifier = modifier.size(48.dp).tvFocusRing(CircleShape),
        shape = CircleShape,
        color = container,
        contentColor = content,
        shadowElevation = ChromeShadow,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.MyLocation, "定位正在播放") }
    }
}

/** 电视顶部一排的 48dp 圆钮（设置）。 */
@Composable
private fun RoundChromeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp).tvFocusRing(CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = ChromeScrimAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = ChromeShadow,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label) }
    }
}

/** 读屏开着时的“以列表浏览”：直接打开原来的队列面板。 */
@Composable
private fun ListEntry(onOpenQueue: () -> Unit) {
    FilledTonalButton(onClick = onOpenQueue, modifier = Modifier.tvFocusRing(CircleShape)) {
        Icon(Icons.AutoMirrored.Filled.ViewList, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("以列表浏览")
    }
}
