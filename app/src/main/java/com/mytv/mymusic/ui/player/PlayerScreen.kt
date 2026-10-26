package com.mytv.mymusic.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.LyricsParser
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.adaptive.modeLabel
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.MorphIconButton
import com.mytv.mymusic.ui.components.PlayButtonSize
import com.mytv.mymusic.ui.components.PlayPauseButton
import com.mytv.mymusic.ui.components.SourceChip
import com.mytv.mymusic.ui.components.WavySeekBar
import com.mytv.mymusic.ui.components.sharedNowPlayingCover
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 沉浸式播放页，固定深色 Vibrant 配色（由外层 PlayerTheme 提供）。
 * 手机：封面铺满上半屏并延伸到状态栏下；平板横屏：封面占左半边；电视：封面 + 歌词，空闲进入环境模式。
 */
@Composable
fun PlayerScreen(
    state: MusicUiState,
    position: State<Long>,
    favorites: List<SavedSong>,
    sleep: SleepTimerState,
    actions: MusicActions,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
) {
    val song = state.current ?: return
    val window = LocalAppWindow.current
    var lyricsMode by rememberSaveable { mutableStateOf(false) }
    var burst by remember { mutableIntStateOf(0) }
    // 预测性返回：手势过程中播放页缩小、下移、圆角变大，松手才真正收起
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { events ->
        try {
            events.collect { if (!lyricsMode) backProgress = it.progress }
            if (lyricsMode) lyricsMode = false else onClose()
        } catch (_: CancellationException) {
        } finally {
            backProgress = 0f
        }
    }
    val idle = rememberIdleState(state.playing)
    val favorite = remember(favorites, song.key) { favorites.any { it.key == song.key } }
    // 解析放在后台；状态不随原文重建，切歌时先保留上一份结果，等新原文解析完成再替换。
    val lyricsState = rememberParsedLyrics(state.lyrics)
    val lyrics = lyricsState.lyrics
    val lyricsLoading = lyricsState.loading
    // 进度每 500ms 变一次；派生状态只记录依赖，不在这里读取，当前行由歌词 leaf 消费
    val active = remember(lyrics, position) {
        derivedStateOf {
            if (lyrics.synced) LyricsParser.activeLineIndex(lyrics.lines, position.value) else -1
        }
    }

    // 换源结果在音源 chip 下方停留 8 秒
    LaunchedEffect(state.playbackNotice) {
        if (state.playbackNotice.isNotBlank() && state.deepLink.isBlank()) {
            delay(8_000)
            actions.clearNotice()
        }
    }

    val source = state.currentSource
    val sourceProvider = source?.providerName
    val sourceDetail = source?.qualityLabel?.takeUnless { it == "音质未知" }.orEmpty()
    val playbackMode = modeLabel(state.aggregation.playbackMode)
    val onSkip: (Int) -> Unit = { direction -> actions.transport(if (direction > 0) "next" else "previous") }
    val onOpenSource: () -> Unit = {
        state.currentTrack?.let { track -> actions.openSource(track, state.canonicalQueue.ifEmpty { listOf(track) }) }
    }
    val onFavoriteBurst: () -> Unit = {
        if (!favorite) actions.favorite(song)
        burst++
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1f - 0.08f * backProgress
                scaleX = scale
                scaleY = scale
                translationY = backProgress * 48.dp.toPx()
                shape = RoundedCornerShape(32.dp * backProgress)
                clip = backProgress > 0f
            }
            .background(MaterialTheme.colorScheme.surface)
            // 电视上焦点不能跑到播放页下面的页面里
            .then(if (window.isTv) Modifier.focusProperties { onExit = { cancelFocusChange() } }.focusGroup() else Modifier)
            .wakeOnInput(idle, consumeKeyWhenIdle = window.isTv),
    ) {
        LyricsBackdrop(
            cover = song.cover,
            playing = state.playing,
            mode = state.lyricsBackground,
            trackKey = song.key,
            wide = window.isTv || (window.widthDp >= 600 && window.widthDp > window.heightDp),
        )
        when {
            window.isTv -> TvPlayer(
                song = song,
                playing = state.playing,
                duration = state.duration,
                position = position,
                favorite = favorite,
                sleep = sleep,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                active = active,
                sourceProvider = sourceProvider,
                sourceDetail = sourceDetail,
                playbackMode = playbackMode,
                notice = state.playbackNotice,
                actions = actions,
                idle = idle,
                onOpenQueue = onOpenQueue,
                onOpenTimer = onOpenTimer,
                onOpenSource = onOpenSource,
            )
            window.widthDp >= 600 && window.widthDp > window.heightDp -> WidePlayer(
                song = song,
                playing = state.playing,
                duration = state.duration,
                position = position,
                favorite = favorite,
                sleep = sleep,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                active = active,
                sourceProvider = sourceProvider,
                sourceDetail = sourceDetail,
                playbackMode = playbackMode,
                notice = state.playbackNotice,
                actions = actions,
                idle = idle,
                lyricsMode = lyricsMode,
                onLyricsMode = { lyricsMode = it },
                onClose = onClose,
                onFavoriteBurst = onFavoriteBurst,
                onOpenSource = onOpenSource,
                onOpenQueue = onOpenQueue,
                onOpenTimer = onOpenTimer,
                onSkip = onSkip,
            )
            else -> PhonePlayer(
                song = song,
                playing = state.playing,
                duration = state.duration,
                position = position,
                favorite = favorite,
                sleep = sleep,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                active = active,
                sourceProvider = sourceProvider,
                sourceDetail = sourceDetail,
                playbackMode = playbackMode,
                notice = state.playbackNotice,
                deepLink = state.deepLink,
                actions = actions,
                idle = idle,
                lyricsMode = lyricsMode,
                onLyricsMode = { lyricsMode = it },
                onClose = onClose,
                onOpenQueue = onOpenQueue,
                onOpenTimer = onOpenTimer,
                onFavoriteBurst = onFavoriteBurst,
                onOpenSource = onOpenSource,
                onSkip = onSkip,
            )
        }
        HeartBurst(burst, Modifier.align(Alignment.Center))
    }
}

/**
 * [rememberParsedLyrics] 返回的状态持有器：已解析歌词和当前原文是否仍在解析。
 * [loading] 在原文尚未解析完时立即为 true（包括解析任务刚切换的那一帧），不会闪过
 * “没有歌词”或把未同步原文里的时间戳当歌词显示。
 */
internal class ParsedLyricsState(val lyrics: ParsedLyrics, val loading: Boolean)

/** 切歌时保留上一份解析结果，取消旧任务后只发布当前原文的结果。 */
@Composable
internal fun rememberParsedLyrics(raw: String): ParsedLyricsState {
    val lyrics by androidx.compose.runtime.produceState(ParsedLyrics(emptyList(), "", false), raw) {
        val parsed = withContext(Dispatchers.Default) { LyricsParser.parse(raw) }
        ensureActive()
        value = parsed
    }
    // 纯派生值在本帧就反映新请求，不在组合中修改普通对象或快照状态。
    return ParsedLyricsState(lyrics, loading = raw.isNotBlank() && lyrics.raw != raw)
}

// ---------- 手机 ----------

@Composable
private fun PhonePlayer(
    song: Song,
    playing: Boolean,
    duration: Long,
    position: State<Long>,
    favorite: Boolean,
    sleep: SleepTimerState,
    lyrics: ParsedLyrics,
    lyricsLoading: Boolean,
    active: State<Int>,
    sourceProvider: String?,
    sourceDetail: String,
    playbackMode: String,
    notice: String,
    deepLink: String,
    actions: MusicActions,
    idle: IdleState,
    lyricsMode: Boolean,
    onLyricsMode: (Boolean) -> Unit,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    onFavoriteBurst: () -> Unit,
    onOpenSource: () -> Unit,
    onSkip: (Int) -> Unit,
) {
    if (lyricsMode) {
        PhoneLyrics(song, playing, duration, position, favorite, lyrics, lyricsLoading, active, actions, idle, onLyricsMode, onSkip)
        return
    }
    val chrome = rememberIdleAlpha(idle, MaterialTheme.motionScheme.defaultEffectsSpec())
    BoxWithConstraints(Modifier.fillMaxSize()) {
        PlayerArt(
            cover = song.cover,
            title = song.title,
            playing = playing,
            horizontalFade = false,
            onSkip = onSkip,
            onDoubleTap = onFavoriteBurst,
            modifier = Modifier.sharedNowPlayingCover(RectangleShape).fillMaxWidth().height(maxHeight * 0.58f),
        )
        TopBar(
            song = song,
            sourceProvider = sourceProvider,
            sleep = sleep,
            deepLink = deepLink,
            actions = actions,
            onClose = onClose,
            onOpenTimer = onOpenTimer,
            onLyricsMode = onLyricsMode,
            onOpenSource = onOpenSource,
            idle = idle,
            modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = chrome.value },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 18.dp, bottom = 8.dp),
        ) {
            Meta(song, favorite, actions, titleSize = 30)
            LyricsPreview(lyrics, active, onLyricsMode, Modifier.padding(top = 12.dp))
            WavySeekBar(
                position,
                duration,
                playing,
                actions::seek,
                Modifier.padding(top = 14.dp, end = 6.dp),
            )
            Controls(playing, actions, Modifier.fillMaxWidth().padding(top = 6.dp, end = 6.dp))
            Notice(notice)
            BottomRow(
                song = song,
                sourceProvider = sourceProvider,
                sourceDetail = sourceDetail,
                playbackMode = playbackMode,
                sleep = sleep,
                lyricsMode = lyricsMode,
                onOpenSource = onOpenSource,
                onOpenTimer = onOpenTimer,
                onOpenQueue = onOpenQueue,
                onLyricsMode = onLyricsMode,
                idle = idle,
                modifier = Modifier.padding(top = 6.dp).graphicsLayer { alpha = chrome.value },
            )
        }
    }
}

@Composable
private fun PhoneLyrics(
    song: Song,
    playing: Boolean,
    duration: Long,
    position: State<Long>,
    favorite: Boolean,
    lyrics: ParsedLyrics,
    lyricsLoading: Boolean,
    active: State<Int>,
    actions: MusicActions,
    idle: IdleState,
    onLyricsMode: (Boolean) -> Unit,
    onSkip: (Int) -> Unit,
) {
    val chrome = rememberIdleAlpha(idle)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = { onLyricsMode(false) }) { Icon(Icons.Filled.KeyboardArrowDown, "回到封面", tint = MaterialTheme.colorScheme.onSurface) }
            // 旁边的“回到封面”按钮已经覆盖这个操作，小封面不再单独读一次
            Cover(song.cover, song.title, 48.dp, Modifier.clearAndSetSemantics {}.clickable { onLyricsMode(false) }, corner = 14.dp)
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FavoriteButton(song, favorite, actions)
        }
        LyricsList(lyrics, active, actions::seek, Modifier.weight(1f).padding(horizontal = 18.dp), loading = lyricsLoading, position = position, playing = playing)
        PhoneLyricsChrome(position, duration, playing, actions, idle, chrome, onSkip)
    }
}

/** 歌词页底部控制条：空闲时自己重组并拦截点按，唤醒的那一下不会点到看不见的按钮。 */
@Composable
private fun PhoneLyricsChrome(
    position: State<Long>,
    duration: Long,
    playing: Boolean,
    actions: MusicActions,
    idle: IdleState,
    chrome: State<Float>,
    onSkip: (Int) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).graphicsLayer { alpha = chrome.value }.blockTouchesWhen(idle.idle)) {
        WavySeekBar(position, duration, playing, actions::seek)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSkip(-1) }) { Icon(Icons.Filled.SkipPrevious, "上一首", tint = MaterialTheme.colorScheme.onSurface) }
            PlayPauseButton(playing, { actions.transport("play") }, size = PlayButtonSize.Medium)
            IconButton(onClick = { onSkip(1) }) { Icon(Icons.Filled.SkipNext, "下一首", tint = MaterialTheme.colorScheme.onSurface) }
        }
    }
}

// ---------- 平板横屏 ----------

@Composable
private fun WidePlayer(
    song: Song,
    playing: Boolean,
    duration: Long,
    position: State<Long>,
    favorite: Boolean,
    sleep: SleepTimerState,
    lyrics: ParsedLyrics,
    lyricsLoading: Boolean,
    active: State<Int>,
    sourceProvider: String?,
    sourceDetail: String,
    playbackMode: String,
    notice: String,
    actions: MusicActions,
    idle: IdleState,
    lyricsMode: Boolean,
    onLyricsMode: (Boolean) -> Unit,
    onClose: () -> Unit,
    onFavoriteBurst: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    onSkip: (Int) -> Unit,
) {
    val chrome = rememberIdleAlpha(idle)
    // 淡出完成后才停呼吸：减少动画时封面仍保留组合，共享封面转场不受影响
    var artVisible by remember { mutableStateOf(!lyricsMode) }
    val artAlpha = animateFloatAsState(
        targetValue = if (lyricsMode) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "artAlpha",
        finishedListener = { target -> artVisible = target > 0f },
    )
    Row(Modifier.fillMaxSize()) {
        PlayerArt(
            cover = song.cover,
            title = song.title,
            playing = playing,
            horizontalFade = true,
            onSkip = onSkip,
            onDoubleTap = onFavoriteBurst,
            visible = artVisible,
            modifier = Modifier
                .weight(if (lyricsMode) 0.2f else 1f)
                .fillMaxHeight()
                .graphicsLayer { alpha = artAlpha.value },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 48.dp, top = 24.dp, bottom = 24.dp),
        ) {
            Meta(song, favorite, actions, titleSize = 36)
            Row(Modifier.padding(top = 12.dp)) {
                Source(song, sourceProvider, sourceDetail, playbackMode, onOpenSource)
            }
            Notice(notice)
            LyricsList(
                lyrics,
                active,
                actions::seek,
                Modifier.weight(1f).padding(vertical = 8.dp),
                large = lyricsMode,
                loading = lyricsLoading,
                position = position,
                playing = playing,
            )
            WavySeekBar(position, duration, playing, actions::seek)
            Controls(playing, actions, Modifier.fillMaxWidth().padding(top = 6.dp))
            BottomRow(
                song = song,
                sourceProvider = sourceProvider,
                sourceDetail = sourceDetail,
                playbackMode = playbackMode,
                sleep = sleep,
                lyricsMode = lyricsMode,
                onOpenSource = onOpenSource,
                onOpenTimer = onOpenTimer,
                onOpenQueue = onOpenQueue,
                onLyricsMode = onLyricsMode,
                idle = idle,
                modifier = Modifier.padding(top = 6.dp).graphicsLayer { alpha = chrome.value },
                showSource = false,
            )
        }
    }
    PlayerCloseButton(onClose, chrome, idle)
}

/** 空闲时淡出的收起按钮；点按拦截只在这个小组件里随空闲状态重组。 */
@Composable
private fun PlayerCloseButton(onClose: () -> Unit, chrome: State<Float>, idle: IdleState) {
    IconButton(
        onClick = onClose,
        modifier = Modifier.statusBarsPadding().padding(16.dp).graphicsLayer { alpha = chrome.value }.blockTouchesWhen(idle.idle),
    ) { Icon(Icons.Filled.KeyboardArrowDown, "收起播放页", tint = MaterialTheme.colorScheme.onSurface) }
}

// ---------- 电视 ----------

@Composable
private fun TvPlayer(
    song: Song,
    playing: Boolean,
    duration: Long,
    position: State<Long>,
    favorite: Boolean,
    sleep: SleepTimerState,
    lyrics: ParsedLyrics,
    lyricsLoading: Boolean,
    active: State<Int>,
    sourceProvider: String?,
    sourceDetail: String,
    playbackMode: String,
    notice: String,
    actions: MusicActions,
    idle: IdleState,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    val idleFocus = remember { FocusRequester() }
    val ambient = idle.idle
    // 环境模式下焦点交给一个不可见的占位节点，保证第一下按键能被唤醒逻辑收到；唤醒后回到播放键
    LaunchedEffect(ambient) { runCatching { if (ambient) idleFocus.requestFocus() else playFocus.requestFocus() } }
    // 只在环境模式下可聚焦，否则在最左侧控件或歌词上按左键，焦点会跳到这个看不见的节点
    Box(Modifier.size(1.dp).focusRequester(idleFocus).focusProperties { canFocus = ambient }.focusable())
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.46f).fillMaxHeight()) {
            PlayerArt(song.cover, song.title, playing, horizontalFade = true, onSkip = { direction -> actions.transport(if (direction > 0) "next" else "previous") }, onDoubleTap = {}, modifier = Modifier.fillMaxSize())
        }
        Column(
            Modifier
                .weight(0.54f)
                .fillMaxHeight()
                .padding(start = 24.dp, end = 64.dp, top = 48.dp, bottom = if (ambient) 48.dp else 180.dp),
        ) {
            Text(song.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            AnimatedVisibility(!ambient, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    Row(Modifier.padding(top = 16.dp)) {
                        Source(song, sourceProvider, sourceDetail, playbackMode, onOpenSource, focusable = false)
                    }
                    Notice(notice)
                }
            }
            LyricsList(lyrics, active, actions::seek, Modifier.weight(1f).padding(top = 16.dp), large = true, loading = lyricsLoading, position = position, playing = playing)
        }
    }
    // 环境模式下控制条只是淡出，不移出组合：焦点留在播放键上，按任意键先唤醒
    val barAlpha = animateFloatAsState(if (ambient) 0f else 1f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "tvBar")
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = barAlpha.value }) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
                    .padding(horizontal = 48.dp, vertical = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MorphIconButton(Icons.Filled.SkipPrevious, "上一首", { actions.transport("previous") }, size = 64.dp, iconSize = 30.dp)
                    PlayPauseButton(playing, { actions.transport("play") }, Modifier.focusRequester(playFocus), size = PlayButtonSize.Large)
                    MorphIconButton(Icons.Filled.SkipNext, "下一首", { actions.transport("next") }, size = 64.dp, iconSize = 30.dp)
                    Spacer(Modifier.weight(1f))
                    TvAction(Icons.Filled.Bedtime, if (sleep.active) "定时 ${sleepLabel(sleep)}" else "定时关闭", onOpenTimer, sleep.active)
                    TvAction(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏", { actions.favorite(song) }, favorite, state = if (favorite) "已收藏" else "未收藏")
                    TvAction(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", onOpenQueue)
                    TvAction(Icons.Filled.SwapHoriz, "换音源", onOpenSource)
                }
                WavySeekBar(position, duration, playing, actions::seek, Modifier.padding(top = 12.dp).tvFocusRing(RoundedCornerShape(12.dp), scale = 1f))
            }
        }
    }
}

@Composable
private fun TvAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    on: Boolean = false,
    state: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .tvFocusRing(CircleShape)
            .semantics {
                role = Role.Button
                state?.let { stateDescription = it }
            },
        shape = CircleShape,
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label) }
    }
}

// ---------- 共用片段 ----------

/** 空闲时控件淡出。读取 idle 的动画在这个小组件里，播放页主体不因空闲状态重组。 */
@Composable
private fun rememberIdleAlpha(idle: IdleState, spec: AnimationSpec<Float> = spring()): State<Float> =
    animateFloatAsState(if (idle.idle) 0f else 1f, spec, label = "chrome")

@Composable
private fun TopBar(
    song: Song,
    sourceProvider: String?,
    sleep: SleepTimerState,
    deepLink: String,
    actions: MusicActions,
    onClose: () -> Unit,
    onOpenTimer: () -> Unit,
    onLyricsMode: (Boolean) -> Unit,
    onOpenSource: () -> Unit,
    idle: IdleState,
    modifier: Modifier,
) {
    Row(
        modifier
            .blockTouchesWhen(idle.idle)
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), androidx.compose.ui.graphics.Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "收起播放页", tint = MaterialTheme.colorScheme.onSurface) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (sleep.active) {
                SleepChip(sleep, onOpenTimer)
            } else {
                Text(
                    "来自 ${sourceProvider ?: song.source}",
                    style = MaterialTheme.typography.labelLarge.copy(
                        shadow = androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), blurRadius = 8f),
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        MoreMenu(song, deepLink, actions, onOpenSource, onLyricsMode)
    }
}

@Composable
private fun SleepChip(sleep: SleepTimerState, onOpenTimer: () -> Unit) {
    val remaining = rememberSleepRemaining(sleep)
    Surface(
        onClick = onOpenTimer,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Filled.Bedtime, null, Modifier.size(16.dp))
            Text(
                if (sleep.endOfTrack) "本首结束" else formatClock(remaining.value),
                modifier = Modifier.semantics {
                    contentDescription = if (sleep.endOfTrack) "定时关闭：本首结束后停止" else "定时关闭：还剩 ${formatClock(remaining.value)}"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MoreMenu(
    song: Song,
    deepLink: String,
    actions: MusicActions,
    onOpenSource: () -> Unit,
    onLyricsMode: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurface) }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            DropdownMenuItem(text = { Text("全屏歌词") }, leadingIcon = { Icon(Icons.Filled.Lyrics, null) }, onClick = { open = false; onLyricsMode(true) })
            DropdownMenuItem(text = { Text("选择音源") }, leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) }, onClick = { open = false; onOpenSource() })
            DropdownMenuItem(text = { Text("下载到本机") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { open = false; actions.download(song) })
            if (deepLink.isNotBlank()) {
                DropdownMenuItem(text = { Text("在平台打开") }, onClick = { open = false; actions.openDeepLink() })
            }
        }
    }
}

@Composable
private fun Meta(song: Song, favorite: Boolean, actions: MusicActions, titleSize: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = titleSize.sp, lineHeight = (titleSize * 1.18f).sp),
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FavoriteButton(song, favorite, actions)
    }
}

@Composable
private fun FavoriteButton(song: Song, favorite: Boolean, actions: MusicActions) {
    IconToggleButton(
        checked = favorite,
        onCheckedChange = { actions.favorite(song) },
        modifier = Modifier
            .tvFocusRing(CircleShape)
            .semantics { stateDescription = if (favorite) "已收藏" else "未收藏" },
    ) {
        Icon(
            if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            "收藏",
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 歌名下常驻两行歌词：当前行和下一行。点按进入全屏歌词。只在歌词 leaf 读取当前行。 */
@Composable
private fun LyricsPreview(lyrics: ParsedLyrics, active: State<Int>, onLyricsMode: (Boolean) -> Unit, modifier: Modifier) {
    if (!lyrics.synced) return
    val activeIndex = active.value
    if (activeIndex < 0) return
    val current = lyrics.lines.getOrNull(activeIndex)?.text.orEmpty()
    val next = lyrics.lines.getOrNull(activeIndex + 1)?.text.orEmpty()
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "打开全屏歌词") { onLyricsMode(true) },
    ) {
        Text(current, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (next.isNotBlank()) {
            Text(next, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 标准按钮组：上一首、128×96 宽播放键、下一首。 */
@Composable
private fun Controls(playing: Boolean, actions: MusicActions, modifier: Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MorphIconButton(Icons.Filled.SkipPrevious, "上一首", { actions.transport("previous") })
        PlayPauseButton(playing, { actions.transport("play") }, size = PlayButtonSize.Hero)
        MorphIconButton(Icons.Filled.SkipNext, "下一首", { actions.transport("next") })
    }
}

@Composable
private fun Source(
    song: Song,
    sourceProvider: String?,
    sourceDetail: String,
    playbackMode: String,
    onOpenSource: () -> Unit,
    focusable: Boolean = true,
) {
    SourceChip(
        providerName = sourceProvider ?: if (song.url.startsWith("file:")) "本机" else song.source,
        detail = sourceDetail,
        mode = playbackMode,
        onClick = { if (focusable) onOpenSource() },
        // 电视上换源走底部的按钮，这里只展示；不能聚焦，否则多出一个按了没反应的焦点位
        modifier = if (focusable) Modifier else Modifier.focusProperties { canFocus = false },
    )
}

@Composable
private fun Notice(notice: String) {
    if (notice.isBlank()) return
    Text(
        notice,
        Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun BottomRow(
    song: Song,
    sourceProvider: String?,
    sourceDetail: String,
    playbackMode: String,
    sleep: SleepTimerState,
    lyricsMode: Boolean,
    onOpenSource: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenQueue: () -> Unit,
    onLyricsMode: (Boolean) -> Unit,
    idle: IdleState,
    modifier: Modifier,
    showSource: Boolean = true,
) {
    Row(modifier.blockTouchesWhen(idle.idle).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showSource) Source(song, sourceProvider, sourceDetail, playbackMode, onOpenSource)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenTimer) {
            Icon(
                Icons.Filled.Bedtime,
                if (sleep.active) "定时关闭：${sleepLabel(sleep)}" else "定时关闭",
                tint = if (sleep.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", tint = MaterialTheme.colorScheme.onSurface) }
        if (!showSource) {
            IconButton(onClick = { onLyricsMode(!lyricsMode) }) {
                Icon(Icons.Filled.Lyrics, if (lyricsMode) "退出全屏歌词" else "全屏歌词", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** 控件淡出后不响应点按：唤醒的那一下只用来显示控件，不会误触看不见的按钮。 */
private fun Modifier.blockTouchesWhen(block: Boolean): Modifier = if (!block) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
