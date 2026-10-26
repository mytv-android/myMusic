package com.mytv.mymusic.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.ParsedLyrics
import com.mytv.mymusic.ui.components.WavySeekBar

@Composable
internal fun WidePlayer(
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
