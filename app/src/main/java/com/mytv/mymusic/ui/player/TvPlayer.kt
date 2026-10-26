package com.mytv.mymusic.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.components.MorphIconButton
import com.mytv.mymusic.ui.components.PlayButtonSize
import com.mytv.mymusic.ui.components.PlayPauseButton
import com.mytv.mymusic.ui.components.WavySeekBar
import com.mytv.mymusic.ui.components.tvFocusRing

@Composable
internal fun TvPlayer(
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
