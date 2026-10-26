package com.mytv.mymusic.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.IconToggleButtonShapes
import androidx.compose.material3.IconButtonDefaults
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.data.RhythmIntensity
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.SleepTimerState
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
    val pulse = rememberBackdropPulse()
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
        val wideBackdrop = window.isTv || (window.widthDp >= 600 && window.widthDp > window.heightDp)
        LyricsBackdrop(
            cover = song.cover,
            playing = state.playing,
            mode = state.lyricsBackground,
            intensity = state.rhythmIntensity,
            trackKey = song.key,
            wide = wideBackdrop,
            pulse = pulse,
            // 光环从封面中心散开：手机的形状封面中心大约在屏幕高度的 30% 处
            rippleOrigin = if (wideBackdrop) Offset(0.25f, 0.5f) else Offset(0.5f, 0.3f),
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
                pulse = pulse,
                coverShape = state.coverShape,
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

// ---------- 共用片段 ----------

/**
 * 空闲时控件淡出的透明度，只在图层里读。空闲状态在效果协程里收集：返回值的函数不是独立的重组作用域，
 * 在这里直接读 idle 会让调用它的整个播放页跟着空闲状态重组。
 */
@Composable
internal fun rememberIdleAlpha(idle: IdleState, spec: AnimationSpec<Float> = spring()): State<Float> {
    val alpha = remember { Animatable(1f) }
    val currentSpec by rememberUpdatedState(spec)
    LaunchedEffect(idle, alpha) {
        snapshotFlow { idle.idle }.collectLatest { alpha.animateTo(if (it) 0f else 1f, currentSpec) }
    }
    return alpha.asState()
}

@Composable
internal fun SleepChip(sleep: SleepTimerState, onOpenTimer: () -> Unit) {
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
internal fun MoreMenu(
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
internal fun Meta(song: Song, favorite: Boolean, actions: MusicActions, titleSize: Int) {
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
internal fun FavoriteButton(song: Song, favorite: Boolean, actions: MusicActions) {
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

/**
 * 进度条波幅倍数：只在律动“标准”档下随低频起伏（0.6..1，原型规定最大不超过 1 倍），其余情况固定 1。
 * 读的是每帧变化的低频，只能交给 WavySeekBar 的 amplitudeScale，在绘制阶段调用。
 */
internal fun pulseWaveScale(pulse: BackdropPulse): Float =
    if (pulse.reactive && pulse.intensity == RhythmIntensity.Standard) 0.6f + 0.4f * pulse.bass else 1f

/**
 * 收藏切换按钮（M3E）：48dp，未收藏是透明底的圆，收藏后变成 14dp 圆角的 primaryContainer 方块，
 * 圆角随快速空间弹簧过渡。读屏文案与原来的收藏按钮一致。
 */
@Composable
internal fun FavoriteToggle(song: Song, favorite: Boolean, actions: MusicActions, modifier: Modifier = Modifier) {
    IconToggleButton(
        checked = favorite,
        onCheckedChange = { actions.favorite(song) },
        shapes = IconToggleButtonShapes(
            shape = CircleShape,
            pressedShape = RoundedCornerShape(12.dp),
            checkedShape = RoundedCornerShape(14.dp),
        ),
        modifier = modifier
            .size(48.dp)
            .tvFocusRing(if (favorite) RoundedCornerShape(14.dp) else CircleShape)
            .semantics { stateDescription = if (favorite) "已收藏" else "未收藏" },
        colors = IconButtonDefaults.iconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏")
    }
}

/**
 * 音源胶囊：32dp 高、16dp 圆角，主色圆点 + 平台名 + “· 音质”，右侧小箭头，点开换源面板。
 * 读屏文案沿用音源 chip（含自动 / 固定模式），胶囊上不显示模式。
 */
@Composable
internal fun SourcePill(
    song: Song,
    sourceProvider: String?,
    sourceDetail: String,
    playbackMode: String,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val provider = sourceProvider ?: if (song.url.startsWith("file:")) "本机" else song.source
    Surface(
        onClick = onOpenSource,
        modifier = modifier
            .height(32.dp)
            .tvFocusRing(RoundedCornerShape(16.dp))
            .semantics { contentDescription = "音源：$provider $sourceDetail，$playbackMode。点按切换音源" },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 8.dp).clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Text(provider, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (sourceDetail.isNotBlank()) {
                Text(
                    "· $sourceDetail",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
        }
    }
}

/** 标准按钮组：上一首、128×96 宽播放键、下一首。 */
@Composable
internal fun Controls(playing: Boolean, actions: MusicActions, modifier: Modifier) {
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
internal fun Source(
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
internal fun Notice(notice: String) {
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
internal fun BottomRow(
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
internal fun Modifier.blockTouchesWhen(block: Boolean): Modifier = if (!block) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
