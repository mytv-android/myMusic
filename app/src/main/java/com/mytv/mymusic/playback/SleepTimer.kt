package com.mytv.mymusic.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SleepTimerState(
    val active: Boolean = false,
    /** 结束时间（elapsedRealtime 无关，用墙钟即可，界面只显示剩余分秒）。 */
    val endAt: Long = 0L,
    val totalMs: Long = 0L,
    val endOfTrack: Boolean = false,
    val fade: Boolean = true,
) {
    fun remainingMs(now: Long = System.currentTimeMillis()): Long = (endAt - now).coerceAtLeast(0L)
}

/**
 * 睡眠定时。由 PlaybackService 绑定真实的 ExoPlayer，所以应用退到后台、锁屏后照样计时。
 * 结束前 30 秒线性降低音量，到点暂停并恢复音量。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SleepTimer(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()
    private var player: ExoPlayer? = null
    private var job: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && _state.value.endOfTrack) {
                player?.pauseAtEndOfMediaItems = false
                _state.value = SleepTimerState()
            }
        }
    }

    fun attach(player: ExoPlayer) {
        this.player = player
        player.addListener(listener)
    }

    fun detach() {
        cancel()
        player?.removeListener(listener)
        player = null
    }

    fun start(minutes: Int, fade: Boolean) {
        cancel()
        val total = minutes * 60_000L
        val endAt = System.currentTimeMillis() + total
        _state.value = SleepTimerState(active = true, endAt = endAt, totalMs = total, fade = fade)
        job = scope.launch {
            while (true) {
                val left = endAt - System.currentTimeMillis()
                if (left <= 0) break
                if (fade && left <= FADE_MS) player?.volume = (left.toFloat() / FADE_MS).coerceIn(0f, 1f)
                delay(if (left <= FADE_MS) 200L else 1000L)
            }
            player?.pause()
            player?.volume = 1f
            _state.value = SleepTimerState()
        }
    }

    /** 播完当前歌曲再停：交给 ExoPlayer 的 pauseAtEndOfMediaItems。 */
    fun stopAfterCurrent() {
        cancel()
        player?.pauseAtEndOfMediaItems = true
        _state.value = SleepTimerState(active = true, endOfTrack = true)
    }

    fun cancel() {
        job?.cancel()
        job = null
        player?.volume = 1f
        player?.pauseAtEndOfMediaItems = false
        _state.value = SleepTimerState()
    }

    private companion object {
        const val FADE_MS = 30_000L
    }
}
