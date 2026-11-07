package com.mytv.mymusic.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mytv.mymusic.AppLog
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.CrossfadeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** 媒体项里带上完整时长（毫秒）：在线流常常没有 Content-Length，播放器直到快放完才知道时长。 */
internal const val MEDIA_EXTRA_DURATION_MS = "mymusic_duration_ms"

private fun String.toMode(): CrossfadeMode =
    CrossfadeMode.entries.firstOrNull { it.name == this } ?: CrossfadeMode.Automix

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
    private val params: Array<CrossfadeParams>,
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
    private var preparedStartMs = 0L

    private var fade: Fade? = null

    /** 预载时定下的接法；分析结果没赶上就是等功率淡化。 */
    private var plan: TransitionPlan? = null

    private var enabled = false
    private var seconds = 6

    /** 交叉淡化时永远等功率；智能过渡才看分析结果挑接法。 */
    private var mode = CrossfadeMode.Automix

    /** 这一首已经试过、放弃过渡了：不要每 200ms 重新预载一次。 */
    private var skipForIndex = C.INDEX_UNSET

    /** 上一首因为条件不满足被跳过时的下标，只用来少打点日志。 */
    private var lastIneligible = C.INDEX_UNSET

    private class Fade(
        val player: ExoPlayer,
        val startAtMs: Long,
        val durationMs: Long,
        val index: Int,
        val plan: TransitionPlan,
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
                .map { Triple(it.crossfadeEnabled, it.crossfadeSeconds, it.crossfadeMode.toMode()) }
                .distinctUntilChanged()
                .collect { (on, value, nextMode) ->
                    val changed = nextMode != mode
                    enabled = on
                    seconds = value
                    mode = nextMode
                    when {
                        !on -> cancel("总开关关掉了")
                        changed && (fade != null || secondary != null) -> cancel("方式换了")
                    }
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
        CrossfadeStatus.end()
        secondary?.release()
        secondary = null
        preparedIndex = C.INDEX_UNSET
        primary = null
    }

    private fun onTick() {
        val player = primary ?: return
        // 不过渡：整首放完由播放器自己接下一首，不预载也不叠
        if (!enabled || mode == CrossfadeMode.None) return
        if (!player.isPlaying) {
            val active = fade
            if (active == null) return
            // 淡着淡着缓冲 / 放完了：只要不是用户按了暂停就接着推完，别把下一首从 0 再放一遍
            if (player.playWhenReady) {
                advance(active)
            } else {
                cancel("主播放器暂停了")
            }
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

        val candidate = planFor(player, next)
        val leadMs = candidate?.startAtRemainingMs ?: (seconds * 1000L)
        if (remaining > leadMs + PREPARE_AHEAD_MS) {
            releasePrepared()
            plan = null
            return
        }
        if (secondary == null || preparedIndex != next) {
            releasePrepared()
            if (!eligible(player, next)) return
            plan = candidate
            prepare(player, next, candidate?.incomingStartMs ?: 0L)
            return
        }
        if (remaining > leadMs) return
        // 预载和分析是并行的：到这里再取一次接法，分析刚赶上就直接用，不沿用预载时的判断
        val current = planFor(player, next) ?: plan
        val playerReady = secondary?.playbackState == Player.STATE_READY
        val wanted = min(current?.fadeMs ?: leadMs, remaining - TAIL_MARGIN_MS)
        val minimum = if (current?.method == AutomixMethod.BeatCut) BEAT_CUT_MIN_MS else MIN_FADE_MS
        if (!playerReady || wanted < minimum) {
            // 预载来不及：这一首直接硬切，不再反复尝试
            skipForIndex = next
            cancel("下一首还没准备好")
            return
        }
        val target = current ?: equalPowerPlan()
        if (target.incomingStartMs != preparedStartMs) {
            secondary?.seekTo(next, target.incomingStartMs)
            preparedStartMs = target.incomingStartMs
        }
        plan = target
        startFade(player, target, wanted)
    }

    /** 当前两首都分析过就按分析挑接法；没有分析结果时等功率淡化。 */
    private fun planFor(player: ExoPlayer, nextIndex: Int): TransitionPlan? {
        if (mode == CrossfadeMode.None) return null
        if (mode == CrossfadeMode.Fade) return equalPowerPlan()
        val duration = durationOf(player)
        if (duration <= 0L) return null
        val currentKey = player.currentMediaItem?.mediaId.orEmpty()
        val nextKey = runCatching { player.getMediaItemAt(nextIndex).mediaId }.getOrNull().orEmpty()
        val outgoing = app.automixStore.cached(currentKey)
        val incoming = app.automixStore.cached(nextKey)
        if (outgoing == null && incoming == null) return equalPowerPlan()
        return AutomixPlanner.plan(outgoing, incoming, seconds, duration)
    }

    private fun equalPowerPlan(): TransitionPlan = TransitionPlan(
        method = AutomixMethod.EqualPowerFade,
        startAtRemainingMs = seconds * 1000L,
        fadeMs = seconds * 1000L,
        incomingStartMs = 0L,
        outgoingSpeed = 1f,
        bassCutoffHz = 0f,
        echoMix = 0f,
    )

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
            CrossfadeStatus.publish(
                CrossfadeReport(
                    fromTitle = currentTitle(),
                    toTitle = item.mediaMetadata.title?.toString().orEmpty(),
                    method = "直接切",
                    seconds = 0f,
                    fallbackReason = reason,
                ),
            )
        }
        return false
    }

    private fun currentTitle(): String =
        primary?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()

    private fun prepare(player: ExoPlayer, index: Int, startPositionMs: Long) {
        val next = createPlayer(secondarySlot)
        val count = player.mediaItemCount
        val items = List(count) { player.getMediaItemAt(it) }
        next.repeatMode = player.repeatMode
        next.shuffleModeEnabled = player.shuffleModeEnabled
        next.setMediaItems(items, index, startPositionMs)
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
        preparedStartMs = startPositionMs
        val mediaId = items.getOrNull(index)?.mediaId.orEmpty()
        val analysis = app.automixStore.cached(mediaId)
        AppLog.i(
            "Crossfade",
            "预载下一首 index=$index（$mediaId）" + if (analysis != null) {
                "，已分析 ${"%.0f".format(analysis.bpm)} BPM · ${keyLabel(analysis.keyIndex, analysis.keyMinor)}"
            } else {
                "，还没有分析结果"
            } + "，接法 ${plan?.method?.label ?: "等功率淡化"}",
        )
    }

    private fun startFade(player: ExoPlayer, plan: TransitionPlan, durationMs: Long) {
        val next = secondary ?: return
        next.volume = 0f
        if (plan.bassCutoffHz > 0f) {
            params[primarySlot].lowPassHz = FILTER_MAX_HZ
            params[secondarySlot].highPassHz = plan.bassCutoffHz
        }
        if (plan.echoMix > 0f) params[primarySlot].echoMix = plan.echoMix
        if (plan.outgoingSpeed != 1f) player.playbackParameters = PlaybackParameters(plan.outgoingSpeed)
        next.play()
        val startedAt = SystemClock.elapsedRealtime()
        fade = Fade(next, startedAt, durationMs, player.currentMediaItemIndex, plan)
        // 固定交叉淡化不画动画，只有智能过渡才让播放页把这次接歌画出来
        if (mode == CrossfadeMode.Automix) CrossfadeStatus.begin(
            CrossfadeTransition(
                fromTitle = currentTitle(),
                toTitle = next.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty(),
                method = plan.method.label,
                durationMs = durationMs,
                startedAtMs = startedAt,
            ),
        )
        AppLog.i("Crossfade", "开始${plan.method.label} ${durationMs}ms，原速 ${"%.3f".format(plan.outgoingSpeed)}")
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
        if (fade.plan.bassCutoffHz > 0f) {
            // 上一首低通往下压、下一首高通往上开，低频在中间交接
            val cutoff = fade.plan.bassCutoffHz
            params[primarySlot].lowPassHz =
                (FILTER_MAX_HZ.toDouble() * (cutoff / FILTER_MAX_HZ).toDouble().pow(t.toDouble())).toFloat()
            params[secondarySlot].highPassHz =
                (FILTER_MIN_HZ.toDouble() * (cutoff / FILTER_MIN_HZ).toDouble().pow((1f - t).toDouble())).toFloat()
        }
        if (t >= 1f) {
            CrossfadeStatus.publish(
                CrossfadeReport(
                    fromTitle = currentTitle(),
                    toTitle = fade.player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty(),
                    method = fade.plan.method.label,
                    seconds = fade.durationMs / 1000f,
                ),
            )
            promote(fade.player)
        }
    }

    private fun promote(next: ExoPlayer) {
        if (primary == null) return
        CrossfadeStatus.end()
        fade?.let { fading ->
            CrossfadeHandoff.mark(primary?.getMediaItemAt(fading.index)?.mediaId)
        }
        fade = null
        plan = null
        next.playbackParameters = PlaybackParameters(1f, 1f)
        next.volume = 1f
        params[primarySlot].clear()
        params[secondarySlot].clear()
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
        val fromTitle = if (fading != null) currentTitle() else ""
        val toTitle = fading?.player?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        fade = null
        secondary = null
        plan = null
        preparedIndex = C.INDEX_UNSET
        if (fading != null) CrossfadeStatus.end()
        if (fading != null) {
            primary?.volume = 1f
            primary?.playbackParameters = PlaybackParameters(1f, 1f)
            params.forEach { it.clear() }
        }
        if (next != null) {
            next.pause()
            next.release()
        }
        if (fading != null || next != null) AppLog.i("Crossfade", "放弃过渡：$reason")
        if (fading != null) {
            CrossfadeStatus.publish(
                CrossfadeReport(
                    fromTitle = fromTitle,
                    toTitle = toTitle,
                    method = "直接切",
                    seconds = 0f,
                    fallbackReason = reason,
                ),
            )
        }
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
                if (isPlaying || fade == null) return
                when {
                    // 上一首正好在这几秒里放完：下一首已经淡入了一部分，直接换过去。
                    // 取消的话，正常换歌会把下一首从 0 再放一遍，听感上是重来。
                    player.playbackState == Player.STATE_ENDED ->
                        handler.post { fade?.let { promote(it.player) } }
                    // 用户按了暂停：回到上一首，下次再淡
                    !player.playWhenReady -> cancel("主播放器暂停了")
                    // 只是缓冲或换段：等它自己回来，墙钟继续推
                }
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
        const val MIN_FADE_MS = 1_200L

        /** 切拍只叠很短一下，下限比普通淡化低。 */
        const val BEAT_CUT_MIN_MS = 400L

        const val FILTER_MAX_HZ = 20_000f
        const val FILTER_MIN_HZ = 20f

        /** 不把上一首最后这一点吃掉：淡完立刻换主，尾巴本来也已经听不见了。 */
        const val TAIL_MARGIN_MS = 250L
    }
}
