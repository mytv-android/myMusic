package com.mytv.mymusic.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mytv.mymusic.AppLog
import com.mytv.mymusic.MyMusicApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 媒体项里带上完整时长（毫秒）：在线流常常没有 Content-Length，播放器直到快放完才知道时长。 */
internal const val MEDIA_EXTRA_DURATION_MS = "mymusic_duration_ms"

/**
 * 等功率交叉淡化的两条音量曲线：功率恒定，叠在一起时总响度不会在中间塌下去。
 * [t] 0 → 只有上一首，1 → 只有下一首。
 */
internal fun crossfadeGains(t: Float): Pair<Float, Float> {
    val p = t.coerceIn(0f, 1f)
    return cos(p * (PI / 2)).toFloat() to sin(p * (PI / 2)).toFloat()
}

/**
 * 自动接下一首时的交叉淡化：在原播放器还在放上一首结尾时，另起一个 ExoPlayer 预载并播放下一首，
 * 两路各按等功率曲线淡出 / 淡入；淡完把会话整体换到新播放器（见 [PlaybackService.promoteTo]）。
 *
 * 只处理自动衔接。手动切歌、直播、带 DRM 的整曲、队列编辑、暂停和睡眠定时都不接，直接取消或根本不开始。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class CrossfadeController(
    private val app: MyMusicApp,
    private val createPlayer: (Int) -> ExoPlayer,
    private val onPromote: (ExoPlayer, Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var settingsJob: Job? = null
    private var running = false

    private var primary: ExoPlayer? = null
    private var primarySlot = SOURCE_PRIMARY
    private var secondary: ExoPlayer? = null
    private var secondarySlot = SOURCE_SECONDARY
    private var preparedIndex = C.INDEX_UNSET

    private var fade: Fade? = null

    private var enabled = false
    private var seconds = 6

    /** 这一首已经试过、放弃过渡了：不要每 200ms 重新预载一次。 */
    private var skipForIndex = C.INDEX_UNSET

    /** 上一首因为条件不满足被跳过时的下标，只用来少打点日志。 */
    private var lastIneligible = C.INDEX_UNSET

    private class Fade(
        val player: ExoPlayer,
        val startAtMs: Long,
        val durationMs: Long,
        val index: Int,
    )

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { onTick() }.onFailure { AppLog.w("Crossfade", "检查失败：${it.message}") }
            // 淡化中 50ms 推一次音量，曲线听不出台阶；平时只是看一眼还差多久
            handler.postDelayed(this, if (fade != null) RAMP_MS else TICK_MS)
        }
    }

    fun attach(player: ExoPlayer) {
        primary = player
        observe(player)
        settingsJob = app.appScope.launch {
            app.settings.state
                .map { it.crossfadeEnabled to it.crossfadeSeconds }
                .distinctUntilChanged()
                .collect { (on, value) ->
                    enabled = on
                    seconds = value
                    if (!on) cancel("总开关关掉了")
                }
        }
        running = true
        handler.post(tick)
    }

    fun release() {
        running = false
        handler.removeCallbacksAndMessages(null)
        settingsJob?.cancel()
        settingsJob = null
        fade = null
        secondary?.release()
        secondary = null
        preparedIndex = C.INDEX_UNSET
        primary = null
    }

    private fun onTick() {
        val player = primary ?: return
        if (!enabled) return
        if (!player.isPlaying) {
            if (fade != null) cancel("主播放器暂停了")
            return
        }
        if (app.sleepTimer.state.value.active) {
            cancel("睡眠定时优先")
            return
        }
        val duration = durationOf(player)
        if (duration <= 0L) return
        val next = player.nextMediaItemIndex
        if (!player.hasNextMediaItem() || next == C.INDEX_UNSET || next == player.currentMediaItemIndex) return
        val remaining = duration - player.currentPosition
        if (remaining <= 0L) return

        val active = fade
        if (active != null) {
            advance(active)
            return
        }

        val leadMs = seconds * 1000L
        if (remaining > leadMs + PREPARE_AHEAD_MS) {
            releasePrepared()
            return
        }
        if (secondary == null || preparedIndex != next) {
            releasePrepared()
            if (!eligible(player, next)) return
            prepare(player, next)
            return
        }
        if (remaining > leadMs) return
        val playerReady = secondary?.playbackState == Player.STATE_READY
        val fadeMs = min(leadMs, remaining - TAIL_MARGIN_MS)
        if (!playerReady || fadeMs < MIN_FADE_MS) {
            // 预载来不及：这一首直接硬切，不再反复尝试
            skipForIndex = next
            cancel("下一首还没准备好")
            return
        }
        startFade(player, fadeMs)
    }

    /**
     * 播放器给的时长，拿不到就用媒体项里带的完整时长。在线 Progressive 流没有 Content-Length 时
     * `player.duration` 一直是 TIME_UNSET，靠它判断“还差几秒”永远不成立。
     */
    private fun durationOf(player: ExoPlayer): Long {
        val known = player.duration
        if (known != C.TIME_UNSET && known > 0L) return known
        return player.currentMediaItem?.mediaMetadata?.extras
            ?.getLong(MEDIA_EXTRA_DURATION_MS, 0L)
            ?.takeIf { it > 0L }
            ?: C.TIME_UNSET
    }

    private fun eligible(player: ExoPlayer, index: Int): Boolean {
        if (index == skipForIndex) return false
        val item = runCatching { player.getMediaItemAt(index) }.getOrNull() ?: return false
        val config = item.localConfiguration ?: return false
        val uri = config.uri
        val reason = when {
            uri.toString().isBlank() -> "下一首还没有地址（等解析）"
            // 加密整曲要另建一套 DRM 会话，P1 不接；直播流没有「下一首」的终点
            config.drmConfiguration != null -> "下一首是加密整曲"
            uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true) -> "下一首是直播流"
            else -> null
        }
        if (reason == null) return true
        if (lastIneligible != index) {
            lastIneligible = index
            AppLog.i("Crossfade", "跳过过渡：$reason")
        }
        return false
    }

    private fun prepare(player: ExoPlayer, index: Int) {
        val next = createPlayer(secondarySlot)
        val count = player.mediaItemCount
        val items = List(count) { player.getMediaItemAt(it) }
        next.repeatMode = player.repeatMode
        next.shuffleModeEnabled = player.shuffleModeEnabled
        next.setMediaItems(items, index, 0L)
        (player.shuffleOrder as? QueueShuffleOrder)?.let { order ->
            runCatching { next.setShuffleOrder(QueueShuffleOrder.of(order.order())) }
        }
        next.volume = 0f
        next.playWhenReady = false
        next.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (secondary === next) {
                    skipForIndex = index
                    cancel("下一首预载失败：${error.errorCodeName}")
                }
            }
        })
        next.prepare()
        secondary = next
        preparedIndex = index
        AppLog.i("Crossfade", "预载下一首 index=$index（${items.getOrNull(index)?.mediaId.orEmpty()}）")
    }

    private fun startFade(player: ExoPlayer, durationMs: Long) {
        val next = secondary ?: return
        next.volume = 0f
        next.play()
        fade = Fade(next, SystemClock.elapsedRealtime(), durationMs, player.currentMediaItemIndex)
        AppLog.i("Crossfade", "开始交叉淡化 ${durationMs}ms")
    }

    private fun advance(fade: Fade) {
        val player = primary ?: run { cancel("没有主播放器"); return }
        if (player.currentMediaItemIndex != fade.index) {
            cancel("已经切到下一首")
            return
        }
        if (fade.player.playbackState == Player.STATE_ENDED) {
            cancel("下一首提前放完")
            return
        }
        val t = ((SystemClock.elapsedRealtime() - fade.startAtMs).toFloat() / fade.durationMs).coerceIn(0f, 1f)
        val (out, incoming) = crossfadeGains(t)
        player.volume = out
        fade.player.volume = incoming
        if (t >= 1f) promote(fade.player)
    }

    private fun promote(next: ExoPlayer) {
        if (primary == null) return
        fade?.let { fading ->
            CrossfadeHandoff.mark(primary?.getMediaItemAt(fading.index)?.mediaId)
        }
        fade = null
        next.volume = 1f
        val newSlot = secondarySlot
        primary = next
        primarySlot = newSlot
        secondary = null
        secondarySlot = 1 - newSlot
        preparedIndex = C.INDEX_UNSET
        skipForIndex = C.INDEX_UNSET
        observe(next)
        onPromote(next, newSlot)
        AppLog.i("Crossfade", "切换到下一首 index=${next.currentMediaItemIndex}")
    }

    private fun releasePrepared() {
        val next = secondary ?: return
        secondary = null
        preparedIndex = C.INDEX_UNSET
        next.release()
    }

    private fun cancel(reason: String) {
        val fading = fade
        val next = secondary
        fade = null
        secondary = null
        preparedIndex = C.INDEX_UNSET
        if (fading != null) primary?.volume = 1f
        if (next != null) {
            next.pause()
            next.release()
        }
        if (fading != null || next != null) AppLog.i("Crossfade", "放弃过渡：$reason")
    }

    private fun observe(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (secondary != null) cancel("跳转 / 手动切歌")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val active = fade
                if (active != null) {
                    // 淡得慢了一点，原播放器自己先接到了下一首：别让它从 0 重放，直接换到已经放到位的新播放器
                    handler.post { fade?.let { promote(it.player) } }
                    return
                }
                if (secondary != null) cancel("换歌了")
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // 队列一改，预载的那一份就旧了（换地址、插歌、挪位都算）
                if (secondary != null) cancel("队列变了")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && fade != null) cancel("主播放器停了")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && fade != null) {
                    // 淡入这一路已经完整在放，主播放器也收尾了：直接换过去。
                    // 回调里不释放当前播放器，等这一轮事件走完再换
                    handler.post { fade?.let { promote(it.player) } }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (fade != null) cancel("主播放器出错")
            }
        })
    }

    private companion object {
        const val TICK_MS = 200L
        const val RAMP_MS = 50L

        /** 提前这么久预载下一首，缓冲慢的在线歌曲也来得及。 */
        const val PREPARE_AHEAD_MS = 12_000L
        const val MIN_FADE_MS = 1_500L

        /** 不把上一首最后这一点吃掉：淡完立刻换主，尾巴本来也已经听不见了。 */
        const val TAIL_MARGIN_MS = 250L
    }
}
