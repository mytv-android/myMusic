package com.mytv.mymusic.ui.player

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
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
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/**
 * 沉浸式播放页，固定深色 Vibrant 配色（由外层 PlayerTheme 提供）。
 * 手机：封面铺满上半屏并延伸到状态栏下；平板横屏：封面占左半边；电视：封面 + 歌词，空闲进入环境模式。
 */
@Composable
fun PlayerScreen(
    state: MusicUiState,
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
    val favorite = favorites.any { it.key == song.key }
    val lyrics = remember(state.lyrics) { LyricsParser.parse(state.lyrics) }
    val active = if (lyrics.synced) LyricsParser.activeLineIndex(lyrics.lines, state.position) else -1

    // 换源结果在音源 chip 下方停留 8 秒
    LaunchedEffect(state.playbackNotice) {
        if (state.playbackNotice.isNotBlank() && state.deepLink.isBlank()) {
            delay(8_000)
            actions.clearNotice()
        }
    }

    val ui = PlayerUi(
        state = state,
        song = song,
        favorite = favorite,
        sleep = sleep,
        lyrics = lyrics,
        active = active,
        actions = actions,
        idle = idle.idle,
        lyricsMode = lyricsMode,
        onLyricsMode = { lyricsMode = it },
        onClose = onClose,
        onOpenQueue = onOpenQueue,
        onOpenTimer = onOpenTimer,
        onFavoriteBurst = {
            if (!favorite) actions.favorite(song)
            burst++
        },
    )

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
        AmbientBackdrop(song.cover, state.playing)
        when {
            window.isTv -> TvPlayer(ui)
            window.widthDp >= 600 && window.widthDp > window.heightDp -> WidePlayer(ui)
            else -> PhonePlayer(ui)
        }
        HeartBurst(burst, Modifier.align(Alignment.Center))
    }
}

private class PlayerUi(
    val state: MusicUiState,
    val song: Song,
    val favorite: Boolean,
    val sleep: SleepTimerState,
    val lyrics: ParsedLyrics,
    val active: Int,
    val actions: MusicActions,
    val idle: Boolean,
    val lyricsMode: Boolean,
    val onLyricsMode: (Boolean) -> Unit,
    val onClose: () -> Unit,
    val onOpenQueue: () -> Unit,
    val onOpenTimer: () -> Unit,
    val onFavoriteBurst: () -> Unit,
) {
    fun openSource() {
        state.currentTrack?.let { track -> actions.openSource(track, state.canonicalQueue.ifEmpty { listOf(track) }) }
    }

    fun skip(direction: Int) = actions.transport(if (direction > 0) "next" else "previous")
}

// ---------- 手机 ----------

@Composable
private fun PhonePlayer(ui: PlayerUi) {
    if (ui.lyricsMode) {
        PhoneLyrics(ui)
        return
    }
    val chrome by animateFloatAsState(if (ui.idle) 0f else 1f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "chrome")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        PlayerArt(
            cover = ui.song.cover,
            title = ui.song.title,
            playing = ui.state.playing,
            horizontalFade = false,
            onSkip = ui::skip,
            onDoubleTap = ui.onFavoriteBurst,
            modifier = Modifier.sharedNowPlayingCover(RectangleShape).fillMaxWidth().height(maxHeight * 0.58f),
        )
        TopBar(ui, Modifier.align(Alignment.TopCenter).alpha(chrome).blockTouchesWhen(ui.idle))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 18.dp, bottom = 8.dp),
        ) {
            Meta(ui, titleSize = 30)
            LyricsPreview(ui, Modifier.padding(top = 12.dp))
            WavySeekBar(
                ui.state.position,
                ui.state.duration,
                ui.state.playing,
                ui.actions::seek,
                Modifier.padding(top = 14.dp, end = 6.dp),
            )
            Controls(ui, Modifier.fillMaxWidth().padding(top = 6.dp, end = 6.dp))
            Notice(ui)
            BottomRow(ui, Modifier.padding(top = 6.dp).alpha(chrome).blockTouchesWhen(ui.idle))
        }
    }
}

@Composable
private fun PhoneLyrics(ui: PlayerUi) {
    val chrome by animateFloatAsState(if (ui.idle) 0f else 1f, label = "lyricsChrome")
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = { ui.onLyricsMode(false) }) { Icon(Icons.Filled.KeyboardArrowDown, "回到封面", tint = MaterialTheme.colorScheme.onSurface) }
            // 旁边的“回到封面”按钮已经覆盖这个操作，小封面不再单独读一次
            Cover(ui.song.cover, ui.song.title, 48.dp, Modifier.clearAndSetSemantics {}.clickable { ui.onLyricsMode(false) }, corner = 14.dp)
            Column(Modifier.weight(1f)) {
                Text(ui.song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ui.song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FavoriteButton(ui)
        }
        LyricsList(ui.lyrics, ui.active, ui.actions::seek, Modifier.weight(1f).padding(horizontal = 18.dp))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).alpha(chrome).blockTouchesWhen(ui.idle)) {
            WavySeekBar(ui.state.position, ui.state.duration, ui.state.playing, ui.actions::seek)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { ui.skip(-1) }) { Icon(Icons.Filled.SkipPrevious, "上一首", tint = MaterialTheme.colorScheme.onSurface) }
                PlayPauseButton(ui.state.playing, { ui.actions.transport("play") }, size = PlayButtonSize.Medium)
                IconButton(onClick = { ui.skip(1) }) { Icon(Icons.Filled.SkipNext, "下一首", tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

// ---------- 平板横屏 ----------

@Composable
private fun WidePlayer(ui: PlayerUi) {
    val chrome by animateFloatAsState(if (ui.idle) 0f else 1f, label = "wideChrome")
    val artAlpha by animateFloatAsState(if (ui.lyricsMode) 0f else 1f, MaterialTheme.motionScheme.slowEffectsSpec(), label = "artAlpha")
    Row(Modifier.fillMaxSize()) {
        PlayerArt(
            cover = ui.song.cover,
            title = ui.song.title,
            playing = ui.state.playing,
            horizontalFade = true,
            onSkip = ui::skip,
            onDoubleTap = ui.onFavoriteBurst,
            modifier = Modifier
                .weight(if (ui.lyricsMode) 0.2f else 1f)
                .fillMaxHeight()
                .graphicsLayer { alpha = artAlpha },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 48.dp, top = 24.dp, bottom = 24.dp),
        ) {
            Meta(ui, titleSize = 36)
            Row(Modifier.padding(top = 12.dp)) { Source(ui) }
            Notice(ui)
            LyricsList(
                ui.lyrics,
                ui.active,
                ui.actions::seek,
                Modifier.weight(1f).padding(vertical = 8.dp),
                large = ui.lyricsMode,
            )
            WavySeekBar(ui.state.position, ui.state.duration, ui.state.playing, ui.actions::seek)
            Controls(ui, Modifier.fillMaxWidth().padding(top = 6.dp))
            BottomRow(ui, Modifier.padding(top = 6.dp).alpha(chrome).blockTouchesWhen(ui.idle), showSource = false)
        }
    }
    IconButton(
        onClick = ui.onClose,
        modifier = Modifier.statusBarsPadding().padding(16.dp).alpha(chrome).blockTouchesWhen(ui.idle),
    ) { Icon(Icons.Filled.KeyboardArrowDown, "收起播放页", tint = MaterialTheme.colorScheme.onSurface) }
}

// ---------- 电视 ----------

@Composable
private fun TvPlayer(ui: PlayerUi) {
    val playFocus = remember { FocusRequester() }
    val idleFocus = remember { FocusRequester() }
    val ambient = ui.idle
    // 环境模式下焦点交给一个不可见的占位节点，保证第一下按键能被唤醒逻辑收到；唤醒后回到播放键
    LaunchedEffect(ambient) { runCatching { if (ambient) idleFocus.requestFocus() else playFocus.requestFocus() } }
    // 只在环境模式下可聚焦，否则在最左侧控件或歌词上按左键，焦点会跳到这个看不见的节点
    Box(Modifier.size(1.dp).focusRequester(idleFocus).focusProperties { canFocus = ambient }.focusable())
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.46f).fillMaxHeight()) {
            PlayerArt(ui.song.cover, ui.song.title, ui.state.playing, horizontalFade = true, onSkip = ui::skip, onDoubleTap = {}, modifier = Modifier.fillMaxSize())
        }
        Column(
            Modifier
                .weight(0.54f)
                .fillMaxHeight()
                .padding(start = 24.dp, end = 64.dp, top = 48.dp, bottom = if (ambient) 48.dp else 180.dp),
        ) {
            Text(ui.song.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ui.song.artist, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            AnimatedVisibility(!ambient, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    Row(Modifier.padding(top = 16.dp)) { Source(ui, focusable = false) }
                    Notice(ui)
                }
            }
            LyricsList(ui.lyrics, ui.active, ui.actions::seek, Modifier.weight(1f).padding(top = 16.dp), large = true)
        }
    }
    // 环境模式下控制条只是淡出，不移出组合：焦点留在播放键上，按任意键先唤醒
    val barAlpha by animateFloatAsState(if (ambient) 0f else 1f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "tvBar")
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = barAlpha }) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
                    .padding(horizontal = 48.dp, vertical = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MorphIconButton(Icons.Filled.SkipPrevious, "上一首", { ui.skip(-1) }, size = 64.dp, iconSize = 30.dp)
                    PlayPauseButton(ui.state.playing, { ui.actions.transport("play") }, Modifier.focusRequester(playFocus), size = PlayButtonSize.Large)
                    MorphIconButton(Icons.Filled.SkipNext, "下一首", { ui.skip(1) }, size = 64.dp, iconSize = 30.dp)
                    Spacer(Modifier.weight(1f))
                    TvAction(Icons.Filled.Bedtime, if (ui.sleep.active) "定时 ${sleepLabel(ui.sleep)}" else "定时关闭", ui.onOpenTimer, ui.sleep.active)
                    TvAction(if (ui.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏", { ui.actions.favorite(ui.song) }, ui.favorite, state = if (ui.favorite) "已收藏" else "未收藏")
                    TvAction(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", ui.onOpenQueue)
                    TvAction(Icons.Filled.SwapHoriz, "换音源", ui::openSource)
                }
                WavySeekBar(ui.state.position, ui.state.duration, ui.state.playing, ui.actions::seek, Modifier.padding(top = 12.dp).tvFocusRing(RoundedCornerShape(12.dp), scale = 1f))
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

@Composable
private fun TopBar(ui: PlayerUi, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), androidx.compose.ui.graphics.Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = ui.onClose) { Icon(Icons.Filled.KeyboardArrowDown, "收起播放页", tint = MaterialTheme.colorScheme.onSurface) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (ui.sleep.active) {
                SleepChip(ui)
            } else {
                Text(
                    "来自 ${ui.state.currentSource?.providerName ?: ui.song.source}",
                    style = MaterialTheme.typography.labelLarge.copy(
                        shadow = androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), blurRadius = 8f),
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        MoreMenu(ui)
    }
}

@Composable
private fun SleepChip(ui: PlayerUi) {
    val remaining = rememberSleepRemaining(ui.sleep)
    Surface(
        onClick = ui.onOpenTimer,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Filled.Bedtime, null, Modifier.size(16.dp))
            Text(
                if (ui.sleep.endOfTrack) "本首结束" else formatClock(remaining),
                modifier = Modifier.semantics {
                    contentDescription = if (ui.sleep.endOfTrack) "定时关闭：本首结束后停止" else "定时关闭：还剩 ${formatClock(remaining)}"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MoreMenu(ui: PlayerUi) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurface) }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            DropdownMenuItem(text = { Text("全屏歌词") }, leadingIcon = { Icon(Icons.Filled.Lyrics, null) }, onClick = { open = false; ui.onLyricsMode(true) })
            DropdownMenuItem(text = { Text("选择音源") }, leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) }, onClick = { open = false; ui.openSource() })
            DropdownMenuItem(text = { Text("下载到本机") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { open = false; ui.actions.download(ui.song) })
            if (ui.state.deepLink.isNotBlank()) {
                DropdownMenuItem(text = { Text("在平台打开") }, onClick = { open = false; ui.actions.openDeepLink() })
            }
        }
    }
}

@Composable
private fun Meta(ui: PlayerUi, titleSize: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(
                ui.song.title,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = titleSize.sp, lineHeight = (titleSize * 1.18f).sp),
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ui.song.artist,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FavoriteButton(ui)
    }
}

@Composable
private fun FavoriteButton(ui: PlayerUi) {
    IconToggleButton(
        checked = ui.favorite,
        onCheckedChange = { ui.actions.favorite(ui.song) },
        modifier = Modifier
            .tvFocusRing(CircleShape)
            .semantics { stateDescription = if (ui.favorite) "已收藏" else "未收藏" },
    ) {
        Icon(
            if (ui.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            "收藏",
            tint = if (ui.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 歌名下常驻两行歌词：当前行和下一行。点按进入全屏歌词。 */
@Composable
private fun LyricsPreview(ui: PlayerUi, modifier: Modifier) {
    if (!ui.lyrics.synced || ui.active < 0) return
    val current = ui.lyrics.lines.getOrNull(ui.active)?.text.orEmpty()
    val next = ui.lyrics.lines.getOrNull(ui.active + 1)?.text.orEmpty()
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "打开全屏歌词") { ui.onLyricsMode(true) },
    ) {
        Text(current, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (next.isNotBlank()) {
            Text(next, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 标准按钮组：上一首、128×96 宽播放键、下一首。 */
@Composable
private fun Controls(ui: PlayerUi, modifier: Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MorphIconButton(Icons.Filled.SkipPrevious, "上一首", { ui.skip(-1) })
        PlayPauseButton(ui.state.playing, { ui.actions.transport("play") }, size = PlayButtonSize.Hero)
        MorphIconButton(Icons.Filled.SkipNext, "下一首", { ui.skip(1) })
    }
}

@Composable
private fun Source(ui: PlayerUi, focusable: Boolean = true) {
    val source = ui.state.currentSource
    SourceChip(
        providerName = source?.providerName ?: if (ui.song.url.startsWith("file:")) "本机" else ui.song.source,
        detail = source?.qualityLabel?.takeUnless { it == "音质未知" }.orEmpty(),
        mode = modeLabel(ui.state.aggregation.playbackMode),
        onClick = { if (focusable) ui.openSource() },
        // 电视上换源走底部的按钮，这里只展示；不能聚焦，否则多出一个按了没反应的焦点位
        modifier = if (focusable) Modifier else Modifier.focusProperties { canFocus = false },
    )
}

@Composable
private fun Notice(ui: PlayerUi) {
    val notice = ui.state.playbackNotice
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
private fun BottomRow(ui: PlayerUi, modifier: Modifier, showSource: Boolean = true) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showSource) Source(ui)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = ui.onOpenTimer) {
            Icon(
                Icons.Filled.Bedtime,
                if (ui.sleep.active) "定时关闭：${sleepLabel(ui.sleep)}" else "定时关闭",
                tint = if (ui.sleep.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = ui.onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", tint = MaterialTheme.colorScheme.onSurface) }
        if (!showSource) {
            IconButton(onClick = { ui.onLyricsMode(!ui.lyricsMode) }) {
                Icon(Icons.Filled.Lyrics, if (ui.lyricsMode) "退出全屏歌词" else "全屏歌词", tint = MaterialTheme.colorScheme.onSurface)
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
