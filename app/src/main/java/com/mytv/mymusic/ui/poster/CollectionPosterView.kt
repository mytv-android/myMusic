package com.mytv.mymusic.ui.poster

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.material3.ktx.animateColorScheme
import com.materialkolor.material3.rememberDynamicColorScheme
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.CollectionKind
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.charts.TvDefaultFocus
import com.mytv.mymusic.ui.components.DeviceKind
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalOverlayPadding
import com.mytv.mymusic.ui.components.RequestDarkBars
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.player.AmbientClock
import com.mytv.mymusic.ui.theme.LocalAppearance
import com.mytv.mymusic.ui.theme.LocalSeedColor
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.max

/**
 * 详情页海报视图里的动作。播放类都不弹出播放页：队列换成这张歌单（和列表里点一行一样），
 * 开始播放的那首留在墙上长成正在播放的大卡。[playFrom] / [playNext] / [songActions] 的参数是这张歌单里的下标。
 */
internal class CollectionPosterOps(
    val playAll: () -> Unit,
    val shuffle: () -> Unit,
    val playFrom: (index: Int) -> Unit,
    val playNext: (index: Int) -> Unit,
    val songActions: (index: Int) -> Unit,
    val showList: () -> Unit,
    val back: () -> Unit,
)

/** 工具栏“随机”后面那一格：在线歌单是“收藏”（存成本地歌单，[on] 时主色实心），自建歌单是“编辑”（先切回列表）。 */
@Immutable
internal class PosterExtraAction(
    val icon: ImageVector,
    val description: String,
    val onClick: () -> Unit,
    val on: Boolean = false,
    val stateDescription: String? = null,
)

/**
 * 歌单 / 专辑 / 榜单 / 歌手 / 自建歌单详情的海报视图，和列表视图按 collection_view 切换。
 *
 * - 墙铺满整页，曲目就是这张歌单，序号是曲目序号；正在放的歌的 key 在这张歌单里就把它放大、跟着切歌走，
 *   不在（这张歌单没在放）就不放大任何格，镜头对准第一块；
 * - 原来的头部收成顶部胶囊（标题 +“歌单 · 网易云 · 30 首”）和底部浮动工具栏（播放全部 / 随机 / 收藏或编辑 / 定位 / 列表 / 设置）；
 *   电视上工具栏挪到顶部和胶囊同一排，底部留给大卡，焦点默认在“播放全部”，方向键下进墙；
 * - 配色：这张歌单没在放时取歌单封面的种子色，在放时跟着全局（正在播放的封面）走；
 * - 返回：先交给墙（弹回、焦点从卡里交回墙、收起别的大卡、清焦点），墙这层没事可做了才离开页面；
 * - 加载中墙上是同样版式的骨架格（占位格走微光），失败 / 空的时候 [message] 盖在墙中间。
 *
 * 多选和自建歌单的编辑只在列表视图里；长按格子、大卡的“更多”只弹单曲操作（[CollectionPosterOps.songActions]）。
 * [resetKey] 换了一张歌单（首数一样也算）时变，整面墙重排、重新入场；[holdAwake] 是页面自己开着的面板（单曲操作等），
 * 开着时浮层不淡出、墙不起漂。
 */
@Composable
internal fun CollectionPosterView(
    state: MusicUiState,
    actions: MusicActions,
    title: String,
    kindLabel: String,
    sourceLabel: String,
    cover: String,
    songs: List<Song>,
    loading: Boolean,
    playLabel: String,
    ops: CollectionPosterOps,
    sourceName: (Song) -> String,
    extra: PosterExtraAction? = null,
    message: (@Composable () -> Unit)? = null,
    resetKey: Any? = null,
    holdAwake: Boolean = false,
) {
    val window = LocalAppWindow.current
    val chrome = posterChromeFor(window.kind)
    val wall = rememberPosterWallState()
    val playFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val currentKey = state.current?.key
    val track = state.currentTrack
    val currentIndex = remember(songs, currentKey, track) {
        posterCurrentIndex(songs, currentKey, track?.sources?.map { it.song.key }.orEmpty())
    }
    val subtitle = posterSubtitle(kindLabel, sourceLabel, songs.size, loading, currentIndex)
    // 播放页或队列海报墙盖在上面时：墙停帧循环、不跟拍，大卡按“没落定”处理（歌词不组合、进度条波浪和花瓣停下）
    val shown = !state.playerOpen && !state.posterOpen
    // 切歌后新歌词取回来之前 state.lyrics 还是上一首的
    val lyricsReady = state.lyricsKey == currentKey
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val overlay = LocalOverlayPadding.current.calculateBottomPadding()
    val insetTop = chrome.insetTop(statusTop.value).dp
    val insetBottom = chrome.insetBottom(overlay.value).dp
    // 进度只交给大卡在绘制阶段读，这里不读值
    val position = remember(actions) { actions.position }.collectAsState()
    val pulse = rememberPosterPulse(
        playing = state.playing,
        mode = state.lyricsBackground,
        intensity = state.rhythmIntensity,
        rhythmOffsetMs = state.rhythmOffsetMs,
        visible = shown && currentIndex >= 0,
    )
    val pulseScale: () -> Float = remember(pulse) { { pulse.coverScale } }

    // 电视默认焦点在“播放全部”，和列表视图一致；墙不自己抢焦点（autoFocus = false）
    TvDefaultFocus(playFocus)
    // 先交给墙（弹回、交回焦点、收起别的大卡、清焦点），墙这层没事可做了才离开页面
    BackHandler(enabled = shown) {
        if (!wall.handleBack()) ops.back()
    }

    val tv = window.isTv
    val hasSongs = songs.isNotEmpty()
    val playing = state.playing
    val duration = state.duration
    val lyrics = if (lyricsReady) state.lyrics else ""
    val translation = state.poster.translation
    val lightsOut = state.poster.lightsOut
    val grown: @Composable BoxScope.(index: Int, current: Boolean, settled: Boolean) -> Unit = { index, current, settled ->
        val song = songs.getOrNull(index)
        if (song != null) {
            GrownPoster(
                song = song,
                number = index + 1,
                sourceName = sourceName(song),
                current = current,
                playing = playing,
                position = position,
                duration = duration,
                lyrics = if (current) lyrics else "",
                lyricsLoading = current && !lyricsReady,
                showTranslation = translation,
                settled = settled && shown,
                coverScale = if (current) pulseScale else NoPulse,
                onPlayPause = { actions.transport("play") },
                onPrevious = { actions.transport("previous") },
                onNext = { actions.transport("next") },
                onSeek = actions::seek,
                onOpenPlayer = actions::openPlayer,
                onPlay = {
                    ops.playFrom(index)
                    // 这张卡变成正在播放的大卡，“播放”钮没了：焦点交回墙，留在这格
                    if (tv) wall.requestWallFocus()
                },
                onPlayNext = { ops.playNext(index) },
                onMore = { ops.songActions(index) },
                focused = wall.grownFocused,
                primaryFocusRequester = cardFocus,
                coverPlaceholderKey = wall.grownCoverKey,
                lightsOut = lightsOut,
            )
        }
    }

    CollectionPosterTheme(cover, actions, followPlaying = currentIndex >= 0) {
        Box(Modifier.fillMaxSize().posterWallWake(wall)) {
            PosterWall(
                state = wall,
                songs = songs,
                currentIndex = currentIndex,
                feed = if (loading && songs.isEmpty()) LoadingFeed else null,
                prefs = state.poster,
                insetTop = insetTop,
                insetBottom = insetBottom,
                onLongPress = ops.songActions,
                grownContent = grown,
                onGrownConfirm = { _, current ->
                    // 正在播放的大卡：OK 播放 / 暂停；别的歌：焦点交进卡里，落在“播放”上
                    if (current) actions.transport("play") else runCatching { cardFocus.requestFocus() }
                },
                autoFocus = false,
                active = shown,
                // 电视：墙上没有选中格时上键回到顶排的“播放全部”，顶排按下键由下面的 onPreviewKeyEvent 交回墙
                chromeAbove = if (tv) playFocus else null,
                resetKey = resetKey,
                holdAwake = holdAwake || settingsOpen || state.sourcePickerTrack != null,
            )
            if (message != null) {
                Box(
                    Modifier.fillMaxSize().padding(top = insetTop, bottom = insetBottom),
                    contentAlignment = Alignment.Center,
                ) { message() }
            }
            // 电视空闲 6 秒浮层淡出（墙的 chromeVisible）；手机、平板一直显示
            val chromeAlpha = rememberPosterChromeAlpha(wall)
            val fade = Modifier.graphicsLayer { alpha = chromeAlpha.value }
            val toolbar: @Composable (Modifier) -> Unit = { modifier ->
                PosterToolbar(
                    chrome = chrome,
                    playLabel = playLabel,
                    canShuffle = hasSongs,
                    extra = extra,
                    canLocate = currentIndex >= 0,
                    locateHot = currentIndex >= 0 && !wall.currentGrown,
                    playFocus = playFocus,
                    onPlayAll = ops.playAll,
                    onShuffle = ops.shuffle,
                    onLocate = wall::locate,
                    onList = ops.showList,
                    onSettings = { settingsOpen = true },
                    modifier = modifier,
                )
            }
            if (chrome.toolbarOnTop) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = chrome.side.dp, end = chrome.side.dp, top = statusTop + chrome.top.dp)
                        .height(chrome.topRowHeight.dp)
                        // 墙铺满整屏，普通焦点搜索不把它当成“下方”：顶排里按下键直接交回墙。
                        // 墙上没歌（读取中、失败、空）时不拦，下键按普通规则落到中间提示的按钮上
                        .onPreviewKeyEvent { e ->
                            if (e.key != Key.DirectionDown || e.type != KeyEventType.KeyDown || !hasSongs) return@onPreviewKeyEvent false
                            wall.focusFromAbove()
                            true
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).then(fade),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(Modifier.weight(1f)) { TitlePill(title, subtitle, onBack = null) }
                        toolbar(Modifier)
                    }
                    // 时钟本来就是给空闲时看的，不跟着淡出
                    if (state.tvAmbientClock) {
                        Spacer(Modifier.width(20.dp))
                        AmbientClock(overImages = true)
                    }
                }
            } else {
                TitlePill(
                    title,
                    subtitle,
                    onBack = if (chrome.showBack) ops.back else null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = chrome.side.dp, end = chrome.side.dp, top = statusTop + chrome.top.dp)
                        .then(fade),
                )
                toolbar(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = chrome.side.dp, end = chrome.side.dp, bottom = overlay + chrome.bottom.dp)
                        .then(fade),
                )
            }
        }
        if (settingsOpen) {
            PosterSettingsSheet(state.poster, actions::setPosterPrefs) { settingsOpen = false }
        }
    }
}

/** 浮层跟着 [PosterWallState.chromeVisible] 淡入淡出；在效果里收集，只改图层透明度，不重组。 */
@Composable
private fun rememberPosterChromeAlpha(wall: PosterWallState): State<Float> {
    val alpha = remember { Animatable(1f) }
    val spec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val reduceMotion = LocalAppearance.current.reduceMotion
    LaunchedEffect(wall, alpha, spec, reduceMotion) {
        snapshotFlow { wall.chromeVisible }.collectLatest { visible ->
            val target = if (visible) 1f else 0f
            if (reduceMotion) alpha.snapTo(target) else alpha.animateTo(target, spec)
        }
    }
    return alpha.asState()
}

/** 加载中：墙按带子排出一串占位格并走微光，和听歌模式续取时一个样子；歌到了换成循环墙，重新入场。 */
private val LoadingFeed = PosterFeed(appending = true)

private val NoPulse: () -> Float = { 1f }

private const val PILL_HEIGHT = 48f
private const val INSET_GAP = 4f

/**
 * 海报视图浮层按设备的尺寸（dp）。手机、平板：左上胶囊 + 底部居中的浮动工具栏；电视：两者在顶部同一排，底部留给大卡。
 * [side] 浮层离左右边，[top] 顶排离状态栏下沿，[bottom] 底部工具栏离底部浮层（迷你播放器、导航栏），
 * [itemWidth] 工具栏图标钮宽（手机 40，其余 48），钮高都是 48。
 */
@Immutable
internal data class PosterChrome(
    val toolbarOnTop: Boolean,
    val showBack: Boolean,
    val side: Float,
    val top: Float,
    val bottom: Float,
    val itemWidth: Float,
    val toolbarPadding: Float,
    val itemHeight: Float = 48f,
) {
    val toolbarHeight: Float get() = itemHeight + toolbarPadding * 2

    /** 顶排高度：电视上胶囊和工具栏并排、垂直居中，取高的那个。 */
    val topRowHeight: Float get() = if (toolbarOnTop) max(PILL_HEIGHT, toolbarHeight) else PILL_HEIGHT

    /** 墙的 insetTop：状态栏 + 顶排 + 4dp。电视 20 + 56 + 4 = 80，451dp 的大卡在 540dp 高里正好放下。 */
    fun insetTop(statusTop: Float): Float = statusTop + top + topRowHeight + INSET_GAP

    /** 墙的 insetBottom：底部浮层 + 工具栏 + 4dp；电视工具栏在顶上，底下不让。 */
    fun insetBottom(overlayBottom: Float): Float =
        if (toolbarOnTop) 0f else overlayBottom + bottom + toolbarHeight + INSET_GAP
}

internal fun posterChromeFor(kind: DeviceKind): PosterChrome = when (kind) {
    DeviceKind.Phone -> PosterChrome(toolbarOnTop = false, showBack = true, side = 12f, top = 8f, bottom = 12f, itemWidth = 40f, toolbarPadding = 6f)
    DeviceKind.Tablet -> PosterChrome(toolbarOnTop = false, showBack = true, side = 24f, top = 12f, bottom = 24f, itemWidth = 48f, toolbarPadding = 8f)
    // 电视没有返回钮（遥控器有返回键）；左边是导航栏，左右边距和队列海报墙的顶排一致
    DeviceKind.Tv -> PosterChrome(toolbarOnTop = true, showBack = false, side = 32f, top = 20f, bottom = 0f, itemWidth = 48f, toolbarPadding = 4f)
}

/** 胶囊副标题里的类型名。 */
internal fun collectionKindLabel(kind: CollectionKind): String = when (kind) {
    CollectionKind.Chart -> "榜单"
    CollectionKind.Playlist -> "歌单"
    CollectionKind.Album -> "专辑"
    CollectionKind.Artist -> "歌手"
}

internal const val LOCAL_PLAYLIST_LABEL = "自建歌单"

/**
 * 顶部胶囊的副标题：“歌单 · 网易云音乐 · 30 首”；正在放的歌在这张歌单里时再接“正在播放第 3 首”。
 * 还没读到歌时写“正在读取…”；空的段跳过。
 */
internal fun posterSubtitle(kind: String, source: String, count: Int, loading: Boolean, playingIndex: Int): String {
    val parts = mutableListOf(kind, source)
    when {
        count > 0 -> parts += "$count 首"
        loading -> parts += "正在读取…"
    }
    if (playingIndex in 0 until count) parts += "正在播放第 ${playingIndex + 1} 首"
    return parts.filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * 正在放的歌是这张歌单的第几首（墙的 currentIndex），不在这张歌单里返回 -1。按歌曲 key 找，不要求队列就是这张歌单；
 * 找不到再按当前曲目各平台音源的 key（[sourceKeys]）找：自动换源后正在放的是另一个平台的版本，仍算这一首。
 */
internal fun posterCurrentIndex(songs: List<Song>, currentKey: String?, sourceKeys: List<String>): Int {
    if (currentKey == null || songs.isEmpty()) return -1
    val direct = songs.indexOfFirst { it.key == currentKey }
    if (direct >= 0 || sourceKeys.isEmpty()) return direct
    return songs.indexOfFirst { it.key in sourceKeys }
}

/**
 * 海报视图的配色：和队列海报墙、播放页一样固定深色（播放页风格，参数同 PlayerTheme），不跟浅色主题走——
 * 浅色 surface 盖在封面上整面墙会发白。这张歌单没在放时用歌单封面的种子色，在放时跟正在播放的封面走；
 * 两者之间走全局同样的 450ms 过渡，墙在绘制阶段读，过渡期间不重组格子。系统栏图标同时变浅（[RequestDarkBars]）。
 */
@Composable
private fun CollectionPosterTheme(
    cover: String,
    actions: MusicActions,
    followPlaying: Boolean,
    content: @Composable () -> Unit,
) {
    var seed by remember(cover) { mutableStateOf<Int?>(null) }
    LaunchedEffect(cover) { seed = if (cover.isBlank()) null else actions.coverSeed(cover) }
    val appearance = LocalAppearance.current
    val coverSeed = seed
    RequestDarkBars(true)
    val target = rememberDynamicColorScheme(
        seedColor = coverSeed?.takeUnless { followPlaying }?.let(::Color) ?: LocalSeedColor.current,
        isDark = true,
        isAmoled = false,
        style = appearance.playerStyle.palette,
        contrastLevel = appearance.contrast.toDouble(),
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
    val scheme = if (appearance.reduceMotion) target else animateColorScheme(target, animationSpec = { tween(COLOR_TRANSITION_MS) })
    MaterialExpressiveTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

private const val COLOR_TRANSITION_MS = 450

/** 顶部胶囊：返回钮（电视没有）+ 标题和副标题，surfaceContainerHigh 86%。 */
@Composable
private fun TitlePill(title: String, subtitle: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = PILL_HEIGHT.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(start = if (onBack != null) 4.dp else 20.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.padding(vertical = 6.dp).semantics(mergeDescendants = true) { heading() }) {
                Text(
                    title.ifBlank { "详情" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * M3E 浮动工具栏：48dp 高的“播放全部”主按钮（primary），右边随机 / 收藏或编辑 / 定位 / 列表 / 设置。
 * “定位”在这张歌单没在放时置灰，正在放的那首没放大时高亮（primaryContainer 底）。
 */
@Composable
private fun PosterToolbar(
    chrome: PosterChrome,
    playLabel: String,
    canShuffle: Boolean,
    extra: PosterExtraAction?,
    canLocate: Boolean,
    locateHot: Boolean,
    playFocus: FocusRequester,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onLocate: () -> Unit,
    onList: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val width = chrome.itemWidth.dp
    val height = chrome.itemHeight.dp
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier.height(chrome.toolbarHeight.dp),
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
            toolbarContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = chrome.toolbarPadding.dp),
    ) {
        // 和列表视图一样不置灰（歌还没到时点了不做事），电视的默认焦点才落得上。
        // 360dp 的手机上整条约 328dp，放大字体放不下时主按钮的字省略，图标钮不被挤掉
        Button(
            onClick = onPlayAll,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .weight(1f, fill = false)
                .height(height)
                .focusRequester(playFocus)
                .tvFocusRing(CircleShape),
            contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(playLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(if (chrome.itemWidth < 48f) 2.dp else 6.dp))
        ToolButton(Icons.Filled.Shuffle, "随机播放", onShuffle, width, height, enabled = canShuffle)
        if (extra != null) {
            ToolButton(extra.icon, extra.description, extra.onClick, width, height, on = extra.on, state = extra.stateDescription)
        }
        ToolButton(Icons.Filled.MyLocation, "定位正在播放", onLocate, width, height, enabled = canLocate, selected = locateHot)
        ToolButton(Icons.AutoMirrored.Filled.ViewList, "切回列表视图", onList, width, height)
        ToolButton(Icons.Filled.Tune, "海报墙设置", onSettings, width, height)
    }
}

/** 工具栏图标钮：[on] 时图标主色（已收藏），[selected] 时 primaryContainer 底、16dp 圆角（“定位”高亮），停用时 38%。 */
@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    on: Boolean = false,
    selected: Boolean = false,
    state: String? = null,
) {
    val shape = if (selected) RoundedCornerShape(16.dp) else CircleShape
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(width, height)
            .tvFocusRing(shape)
            .semantics {
                contentDescription = description
                role = Role.Button
                if (state != null) stateDescription = state
            },
        shape = shape,
        color = if (selected) scheme.primaryContainer else Color.Transparent,
        contentColor = when {
            selected -> scheme.onPrimaryContainer
            on -> scheme.primary
            else -> scheme.onSurface
        },
    ) {
        Box(Modifier.alpha(if (enabled) 1f else 0.38f), contentAlignment = Alignment.Center) { Icon(icon, null) }
    }
}
