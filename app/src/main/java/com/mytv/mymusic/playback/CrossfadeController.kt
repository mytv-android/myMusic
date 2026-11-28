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
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** 媒体项里带上完整时长（毫秒）：在线流常常没有 Content-Length，播放器直到快放完才知道时长。 */
internal const val MEDIA_EXTRA_DURATION_MS = "mymusic_duration_ms"

/** 媒体项里带上专辑（「来源:专辑 id」）：同一张专辑的下一轨不叠，直接无缝接。 */
internal const val MEDIA_EXTRA_ALBUM_KEY = "mymusic_album_key"

/** 媒体项里带上专辑里的轨号、碟号（没有就不带）。 */
internal const val MEDIA_EXTRA_TRACK = "mymusic_track"
internal const val MEDIA_EXTRA_DISC = "mymusic_disc"

/** 歌曲附加信息里的轨号 / 碟号（各平台的键名不一样，取第一个能读成正整数的）。 */
internal fun albumTrackNumber(extra: Map<String, String>, vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key -> extra[key]?.trim()?.substringBefore('/')?.toIntOrNull()?.takeIf { it > 0 } }

private fun String.toMode(): CrossfadeMode =
    CrossfadeMode.entries.firstOrNull { it.name == this } ?: CrossfadeMode.Automix

/**
 * 等功率交叉淡化的两条音量曲线：功率恒定，叠在一起时总响度不会在中间塌下去。智能过渡的低频交接、尾奏承接、切拍用它。
 * [t] 0 → 只有上一首，1 → 只有下一首。
 */
internal fun crossfadeGains(t: Float): Pair<Float, Float> {
    val p = t.coerceIn(0f, 1f)
    return cos(p * (PI / 2)).toFloat() to sin(p * (PI / 2)).toFloat()
}

/**
 * 交叉淡化（[AutomixMethod.Crossfade]）照 Apple Music 的两条曲线：上一首按指数曲线淡出（系数 10：
 * (10^(1−t) − 1) / 9），下一首按对数曲线淡入（系数 2：log₂(1 + t)）——Android 版 Apple Music 7.0.3
 * 的淡出 EXPONENTIAL、淡入 LOGARITHMIC 和这两个默认系数。上一首退得快、下一首起得快，
 * 两边在 t ≈ 0.33 时一样响（[APPLE_CROSSOVER]）。
 */
internal fun appleCrossfadeGains(t: Float): Pair<Float, Float> {
    val p = t.coerceIn(0f, 1f).toDouble()
    val out = (10.0.pow(1.0 - p) - 1.0) / 9.0
    val incoming = ln(1.0 + p) / ln(2.0)
    return out.toFloat().coerceIn(0f, 1f) to incoming.toFloat().coerceIn(0f, 1f)
}

/** [appleCrossfadeGains] 两边一样响（都是 0.41）的那一刻。 */
internal const val APPLE_CROSSOVER = 0.3288

/**
 * 自动接下一首时的交叉淡化：在原播放器还在放上一首结尾时，另起一个 ExoPlayer 预载并播放下一首，
 * 两路各按接法的曲线淡出 / 淡入（交叉淡化是 Apple 的指数 / 对数曲线，智能过渡的几种接法是等功率）。
 * 到换页点（[AutomixPlanner.switchAtMs]：交叉点或下一首第一句前，
 * 但不早于上一首最后一句唱完）就把会话整体换到新播放器（见 [PlaybackService.promoteTo]），
 * 播放页、通知栏、状态栏歌词跟着换到下一首；上一首转成「尾巴」，按原曲线淡完再释放。
 *
 * 只处理自动衔接。手动切歌、直播、带 DRM 的整曲、队列编辑、暂停和睡眠定时都不接，直接取消或根本不开始；
 * 换页之后用户暂停、跳转、切歌就把尾巴收掉，下一首回到正常音量。
 * 同一张专辑的下一轨不叠，交给播放器自己无缝接（见 [AutomixPlanner.sameAlbum]）。
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

    /** 预载时定下的接法；分析结果没赶上就是交叉淡化。 */
    private var plan: TransitionPlan? = null

    private var enabled = false
    private var seconds = 6

    /** 交叉淡化时永远是 Apple 的曲线；智能过渡才看分析结果挑接法。 */
    private var mode = CrossfadeMode.Automix

    /** 这一首已经试过、放弃过渡了：不要每 200ms 重新预载一次。 */
    private var skipForIndex = C.INDEX_UNSET

    /** 上一首因为条件不满足被跳过时的下标，只用来少打点日志。 */
    private var lastIneligible = C.INDEX_UNSET

    /** 同一张专辑直接接的那一首（下标）和上一首的歌名：真的自然接过去了才记进「上一次接歌」。 */
    private var gaplessIndex = C.INDEX_UNSET
    private var gaplessFrom = ""

    /** 换页后新主播放器申请音频焦点的那一步：要等尾巴先放开，见 [requestFocusAfter]。 */
    private var pendingFocus: Runnable? = null

    /**
     * 一次淡化。换页前 [outgoing] 是主播放器、[incoming] 是预载的那一路；换页后（[switched]）
     * [incoming] 成了主播放器，[outgoing] 只是还在淡出的尾巴，淡完或被打断时释放（[tailReleased]）。
     */
    private class Fade(
        val outgoing: ExoPlayer,
        val outgoingSlot: Int,
        val incoming: ExoPlayer,
        val incomingSlot: Int,
        val startAtMs: Long,
        val durationMs: Long,
        val index: Int,
        val outgoingKey: String,
        val plan: TransitionPlan,
        val switchAtMs: Long,
        val report: CrossfadeReport,
    ) {
        var switched = false
        var tailReleased = false
        var speed = 1f
    }

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
                    // 队列那一行按新设置重写；下一次检查时再按新方式把这次的接法补上
                    CrossfadeStatus.announce(null)
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
        pendingFocus = null
        val fading = fade
        fade = null
        CrossfadeStatus.end()
        CrossfadeStatus.announce(null)
        // 换页前的上一首就是主播放器，由服务自己释放；换页后它只是尾巴，归这里
        if (fading != null && fading.switched) releaseTail(fading)
        secondary?.release()
        secondary = null
        preparedIndex = C.INDEX_UNSET
        primary = null
    }

    private fun onTick() {
        val player = primary ?: return
        // 换过页了：只管把尾巴按原曲线淡完，不管设置怎么变，也不预载再下一首（两路播放器都占着）
        fade?.takeIf { it.switched }?.let { active ->
            tickTail(active, player)
            return
        }
        // 不过渡：整首放完由播放器自己接下一首，不预载也不叠
        if (!enabled || mode == CrossfadeMode.None) return
        // nextMediaItemIndex 为手动切歌忽略单曲循环，自动过渡不能沿用它。
        if (player.repeatMode == Player.REPEAT_MODE_ONE) {
            if (secondary != null) cancel("单曲循环")
            return
        }
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
            CrossfadeStatus.announce(null)
            return
        }
        if (sameAlbum(player, next)) {
            // 整张专辑连着放：不叠，播放器自己无缝接
            releasePrepared()
            plan = null
            gaplessIndex = next
            gaplessFrom = currentTitle()
            announce(player, next, SAME_ALBUM_METHOD)
            return
        }
        gaplessIndex = C.INDEX_UNSET
        if (secondary == null || preparedIndex != next) {
            releasePrepared()
            if (!eligible(player, next)) return
            plan = candidate
            prepare(player, next, candidate?.incomingStartMs ?: 0L)
            return
        }
        // 预载和分析是并行的：到这里再取一次接法，分析刚赶上就直接用，不沿用预载时的判断
        val target = planFor(player, next) ?: plan ?: AutomixPlanner.fixedFade(seconds)
        val playerReady = secondary?.playbackState == Player.STATE_READY
        if (remaining > leadMs) {
            // 下一首已经能放了：队列那一行写这次会怎么接
            if (playerReady) announce(player, next, upcomingLabel(target))
            return
        }
        val wanted = min(target.fadeMs, remaining - target.endMarginMs)
        // 下限跟着设定走：交叉淡化可以短到 1 秒
        val minimum = if (target.method == AutomixMethod.BeatCut) BEAT_CUT_MIN_MS else min(MIN_FADE_MS, target.fadeMs / 2)
        if (!playerReady || wanted < minimum) {
            // 预载来不及：这一首直接硬切，不再反复尝试
            fallBack(player, next, "下一首还没准备好")
            return
        }
        if (target.incomingStartMs != preparedStartMs) {
            secondary?.seekTo(next, target.incomingStartMs)
            preparedStartMs = target.incomingStartMs
        }
        plan = target
        announce(player, next, upcomingLabel(target))
        startFade(player, target, wanted)
    }

    /** 当前两首都分析过就按分析挑接法；没有分析结果时交叉淡化。 */
    private fun planFor(player: ExoPlayer, nextIndex: Int): TransitionPlan? {
        if (mode == CrossfadeMode.None) return null
        if (mode == CrossfadeMode.Fade) return AutomixPlanner.fixedFade(seconds)
        val duration = durationOf(player)
        if (duration <= 0L) return null
        val currentKey = player.currentMediaItem?.mediaId.orEmpty()
        val nextKey = runCatching { player.getMediaItemAt(nextIndex).mediaId }.getOrNull().orEmpty()
        val outgoing = app.automixStore.cached(currentKey)
        val incoming = app.automixStore.cached(nextKey)
        return AutomixPlanner.plan(outgoing, incoming, seconds, duration)
    }

    /** 队列那一行写的接法：交叉淡化就是它自己和时长，智能过渡写挑中的接法。 */
    private fun upcomingLabel(target: TransitionPlan): String {
        val method = if (mode == CrossfadeMode.Fade) CrossfadeMode.Fade.label else target.method.label
        return "$method · ${transitionSecondsLabel(target.fadeMs)}"
    }

    private fun announce(player: ExoPlayer, nextIndex: Int, label: String) {
        val nextKey = runCatching { player.getMediaItemAt(nextIndex).mediaId }.getOrNull().orEmpty()
        CrossfadeStatus.announce(
            CrossfadeUpcoming(fromKey = player.currentMediaItem?.mediaId.orEmpty(), toKey = nextKey, label = label),
        )
    }

    /** 这一首放弃过渡、到时候直接切：记下来不再反复试，队列那一行也照实写。 */
    private fun fallBack(player: ExoPlayer, index: Int, reason: String) {
        skipForIndex = index
        cancel(reason)
        if (primary === player && player.nextMediaItemIndex == index) announce(player, index, "直接切")
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

    private fun sameAlbum(player: ExoPlayer, nextIndex: Int): Boolean {
        val current = player.currentMediaItem ?: return false
        val upcoming = runCatching { player.getMediaItemAt(nextIndex) }.getOrNull() ?: return false
        return AutomixPlanner.sameAlbum(
            albumKeyOf(current),
            albumKeyOf(upcoming),
            player.shuffleModeEnabled,
            AlbumSlot.of(current),
            AlbumSlot.of(upcoming),
        )
    }

    private fun albumKeyOf(item: MediaItem): String =
        item.mediaMetadata.extras?.getString(MEDIA_EXTRA_ALBUM_KEY).orEmpty()

    private fun eligible(player: ExoPlayer, index: Int): Boolean {
        if (index == skipForIndex) return false
        val item = runCatching { player.getMediaItemAt(index) }.getOrNull() ?: return false
        val config = item.localConfiguration ?: return false
        val uri = config.uri
        val pending = uri.toString().isBlank()
        val reason = when {
            pending -> "下一首还没有地址（等解析）"
            // 加密整曲要另建一套 DRM 会话，P1 不接；直播流没有「下一首」的终点
            config.drmConfiguration != null -> "下一首是加密整曲"
            uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true) -> "下一首是直播流"
            else -> null
        }
        if (reason == null) return true
        // 还在解析地址不算定局；加密整曲、直播是这一次一定直接切
        if (!pending) announce(player, index, "直接切 · $reason")
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
                    val owner = primary ?: return
                    fallBack(owner, index, "下一首预载失败：${error.errorCodeName}")
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
            "预载下一首 index=$index（$mediaId）从 ${startPositionMs}ms" + if (analysis != null) {
                "，已分析 ${"%.0f".format(analysis.bpm)} BPM · ${keyLabel(analysis.keyIndex, analysis.keyMinor)}"
            } else {
                "，还没有分析结果"
            } + "，接法 ${plan?.method?.label ?: AutomixMethod.Crossfade.label}",
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
        // 变速不在这里一下到位：advance 里按淡化进度分 8 步渐变（AutomixPlanner.tempoAt）
        next.play()
        val startedAt = SystemClock.elapsedRealtime()
        val outgoingKey = player.currentMediaItem?.mediaId.orEmpty()
        val incomingKey = next.currentMediaItem?.mediaId.orEmpty()
        val outgoingLyrics = CrossfadeLyrics.get(outgoingKey)
        val incomingLyrics = CrossfadeLyrics.get(incomingKey)
        val switchAt = AutomixPlanner.switchAtMs(plan, durationMs, player.currentPosition, outgoingLyrics, incomingLyrics)
        val fromTitle = currentTitle()
        val toTitle = next.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        fade = Fade(
            outgoing = player,
            outgoingSlot = primarySlot,
            incoming = next,
            incomingSlot = secondarySlot,
            startAtMs = startedAt,
            durationMs = durationMs,
            index = player.currentMediaItemIndex,
            outgoingKey = outgoingKey,
            plan = plan,
            switchAtMs = switchAt,
            report = CrossfadeReport(fromTitle, toTitle, plan.method.label, durationMs / 1000f),
        )
        // 固定交叉淡化不画动画，只有智能过渡才让播放页把这次接歌画出来；换页后接着画在新封面上，直到淡完
        if (mode == CrossfadeMode.Automix) CrossfadeStatus.begin(
            CrossfadeTransition(
                fromTitle = fromTitle,
                toTitle = toTitle,
                method = plan.method.label,
                durationMs = durationMs,
                startedAtMs = startedAt,
            ),
        )
        AppLog.i(
            "Crossfade",
            "开始${plan.method.label} ${durationMs}ms，${switchAt}ms 换页" +
                "（歌词${if (outgoingLyrics != null && incomingLyrics != null) "已知" else "不全，按交叉点"}）" +
                "，目标速度 ${"%.3f".format(plan.outgoingSpeed)}",
        )
    }

    /** 推一次音量、变速和滤波；到了换页点就换主播放器，淡完收尾。换页前后都走这里。 */
    private fun advance(fade: Fade) {
        if (!fade.switched) {
            val player = primary ?: run { cancel("没有主播放器"); return }
            if (player.currentMediaItemIndex != fade.index) {
                // 上一首比预计的早放完（在线流的时长可能差几秒），原播放器自己接到了下一首：
                // 直接换到已经放到位的新播放器，别让下一首从 0 再放一遍
                if (player.currentMediaItemIndex == fade.incoming.currentMediaItemIndex) {
                    switchTo(fade, keepTail = false)
                } else {
                    cancel("已经切到下一首")
                }
                return
            }
            if (fade.incoming.playbackState == Player.STATE_ENDED) {
                cancel("下一首提前放完")
                return
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - fade.startAtMs
        val t = (elapsed.toFloat() / fade.durationMs).coerceIn(0f, 1f)
        val (out, incoming) = if (fade.plan.method == AutomixMethod.Crossfade) appleCrossfadeGains(t) else crossfadeGains(t)
        if (!fade.tailReleased) {
            fade.outgoing.volume = out
            val speed = AutomixPlanner.tempoAt(t, fade.plan.outgoingSpeed)
            if (speed != fade.speed) {
                fade.speed = speed
                fade.outgoing.playbackParameters = PlaybackParameters(speed)
            }
        }
        fade.incoming.volume = incoming
        if (fade.plan.bassCutoffHz > 0f) {
            // 上一首低通往下压、下一首高通往上开，低频在中间交接
            val cutoff = fade.plan.bassCutoffHz
            if (!fade.tailReleased) {
                params[fade.outgoingSlot].lowPassHz =
                    (FILTER_MAX_HZ.toDouble() * (cutoff / FILTER_MAX_HZ).toDouble().pow(t.toDouble())).toFloat()
            }
            params[fade.incomingSlot].highPassHz =
                (FILTER_MIN_HZ.toDouble() * (cutoff / FILTER_MIN_HZ).toDouble().pow((1f - t).toDouble())).toFloat()
        }
        if (!fade.switched && (elapsed >= fade.switchAtMs || t >= 1f)) {
            // 淡完才到换页点：上一首已经没声了，不用留尾巴
            switchTo(fade, keepTail = t < 1f)
        }
        if (t >= 1f) finish(fade)
    }

    /** 换页后的检查：用户暂停就收掉尾巴；缓冲照样按墙钟推完。 */
    private fun tickTail(fade: Fade, player: ExoPlayer) {
        when {
            !player.playWhenReady -> stopTail(fade, "暂停了")
            app.sleepTimer.state.value.active -> stopTail(fade, "睡眠定时优先")
            else -> advance(fade)
        }
    }

    /**
     * 换页：下一首成为主播放器，会话、通知栏、律动、音效、睡眠定时跟着换过去。[keepTail] 时上一首接着按原曲线淡完，
     * 否则（淡完了、或者它自己已经接到了下一首）立刻释放。
     */
    private fun switchTo(fade: Fade, keepTail: Boolean) {
        if (fade.switched || primary !== fade.outgoing || this.fade !== fade) return
        fade.switched = true
        CrossfadeStatus.publish(fade.report)
        CrossfadeStatus.announce(null)
        CrossfadeHandoff.mark(fade.outgoingKey)
        plan = null
        val tail = fade.outgoing
        val next = fade.incoming
        // 先让尾巴放开音频焦点，再让新主播放器申请：反过来尾巴会收到焦点丢失，淡到一半被暂停
        runCatching { tail.setAudioAttributes(tail.audioAttributes, false) }
        if (keepTail) {
            // 尾巴放到头就停（在线流的时长可能报短），不能自己接着放下一首
            tail.pauseAtEndOfMediaItems = true
            tail.addListener(tailListener(fade))
        }
        primary = next
        primarySlot = fade.incomingSlot
        secondary = null
        secondarySlot = fade.outgoingSlot
        preparedIndex = C.INDEX_UNSET
        skipForIndex = C.INDEX_UNSET
        observe(next)
        onPromote(next, fade.incomingSlot)
        if (!keepTail) releaseTail(fade)
        requestFocusAfter(if (keepTail) tail else null, next)
        val elapsed = SystemClock.elapsedRealtime() - fade.startAtMs
        AppLog.i(
            "Crossfade",
            "换页到下一首 index=${next.currentMediaItemIndex}（淡化第 ${elapsed}ms）" + if (keepTail) "，上一首转成尾巴" else "",
        )
    }

    /**
     * 新主播放器申请音频焦点。尾巴放开焦点是在它自己的播放线程上做的：在它的消息队列里排一条，
     * 轮到了（说明前面那条放开焦点已经处理完）再申请，保证先放后要；尾巴先被释放时有超时兜底。
     */
    private fun requestFocusAfter(tail: ExoPlayer?, next: ExoPlayer) {
        val request = object : Runnable {
            override fun run() {
                if (pendingFocus !== this) return
                pendingFocus = null
                handler.removeCallbacks(this)
                if (primary === next) runCatching { next.setAudioAttributes(next.audioAttributes, true) }
            }
        }
        pendingFocus = request
        if (tail == null) {
            request.run()
            return
        }
        runCatching { tail.createMessage { _, _ -> request.run() }.setLooper(Looper.getMainLooper()).send() }
        handler.postDelayed(request, FOCUS_HANDOFF_TIMEOUT_MS)
    }

    /** 尾巴自己放到头、停了或出错：提前收掉，下一首的淡入照旧走完。 */
    private fun tailListener(fade: Fade) = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) return
            val tail = fade.outgoing
            if (!tail.playWhenReady || tail.playbackState == Player.STATE_ENDED) handler.post { releaseTail(fade) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            handler.post { releaseTail(fade) }
        }

        override fun onPlayerError(error: PlaybackException) {
            handler.post { releaseTail(fade) }
        }
    }

    private fun releaseTail(fade: Fade) {
        if (fade.tailReleased) return
        fade.tailReleased = true
        params[fade.outgoingSlot].clear()
        runCatching { fade.outgoing.release() }
    }

    /** 淡完：尾巴释放，下一首回到正常音量，滤波清掉，过渡动画收起。 */
    private fun finish(fade: Fade) {
        if (this.fade !== fade) return
        this.fade = null
        // 没换页就淡完不会走到这里（advance 先换页）；保险起见，主播放器绝不当尾巴释放
        if (fade.switched) releaseTail(fade)
        fade.incoming.volume = 1f
        params[fade.incomingSlot].clear()
        CrossfadeStatus.end()
    }

    /** 换页后被用户打断（暂停、跳转、切歌）：尾巴不再淡，直接收掉。 */
    private fun stopTail(fade: Fade, reason: String) {
        if (this.fade !== fade || !fade.switched) return
        if (!fade.tailReleased) AppLog.i("Crossfade", "收掉尾巴：$reason")
        finish(fade)
    }

    private fun releasePrepared() {
        val next = secondary ?: return
        secondary = null
        preparedIndex = C.INDEX_UNSET
        next.release()
    }

    /** 放弃还没换页的这次过渡；已经换过页的不回退，尾巴自己淡完。 */
    private fun cancel(reason: String) {
        if (fade?.switched == true) return
        val fading = fade
        val next = secondary
        fade = null
        secondary = null
        plan = null
        preparedIndex = C.INDEX_UNSET
        CrossfadeStatus.announce(null)
        if (fading != null) {
            CrossfadeStatus.end()
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
                    fromTitle = fading.report.fromTitle,
                    toTitle = fading.report.toTitle,
                    method = "直接切",
                    seconds = 0f,
                    fallbackReason = reason,
                ),
            )
        }
    }

    /** 主播放器的事件；换页后旧主播放器上的这个监听还挂着，只认当前主播放器。 */
    private fun observe(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (player !== primary) return
                val active = fade
                if (active != null && active.switched) {
                    // 换页后用户拖进度、切歌、移走这首：尾巴收掉。播放器内部的位置校正不算
                    if (reason != Player.DISCONTINUITY_REASON_INTERNAL &&
                        reason != Player.DISCONTINUITY_REASON_SILENCE_SKIP
                    ) {
                        stopTail(active, "跳转 / 手动切歌")
                    }
                    return
                }
                if (secondary != null) cancel("跳转 / 手动切歌")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player !== primary) return
                val active = fade
                if (active != null && active.switched) {
                    stopTail(active, "换歌了")
                    return
                }
                if (active != null) {
                    // 淡得慢了一点，原播放器自己先接到了下一首：别让它从 0 重放，直接换到已经放到位的新播放器。
                    // 它这会儿放的已经是下一首的开头，先静音，等这一轮事件走完再换
                    player.volume = 0f
                    handler.post { fade?.takeIf { !it.switched }?.let { switchTo(it, keepTail = false) } }
                    return
                }
                if (secondary != null) cancel("换歌了")
                // 同一张专辑直接接：真的自然接过去了才记「上一次接歌」
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && gaplessIndex != C.INDEX_UNSET &&
                    player.currentMediaItemIndex == gaplessIndex && enabled && mode != CrossfadeMode.None
                ) {
                    CrossfadeStatus.publish(
                        CrossfadeReport(
                            fromTitle = gaplessFrom,
                            toTitle = mediaItem?.mediaMetadata?.title?.toString().orEmpty(),
                            method = SAME_ALBUM_METHOD,
                            seconds = 0f,
                        ),
                    )
                    AppLog.i("Crossfade", "同一张专辑，直接接 index=$gaplessIndex")
                }
                gaplessIndex = C.INDEX_UNSET
                CrossfadeStatus.announce(null)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (player !== primary) return
                // 队列一改，预载的那一份就旧了（换地址、插歌、挪位都算）
                if (secondary != null) cancel("队列变了")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== primary) return
                val active = fade ?: return
                if (isPlaying) return
                if (active.switched) {
                    // 换页后用户按了暂停：尾巴跟着停；缓冲就等它回来
                    if (!player.playWhenReady) stopTail(active, "暂停了")
                    return
                }
                when {
                    // 上一首正好在这几秒里放完：下一首已经淡入了一部分，直接换过去。
                    // 取消的话，正常换歌会把下一首从 0 再放一遍，听感上是重来。
                    player.playbackState == Player.STATE_ENDED ->
                        handler.post { fade?.takeIf { !it.switched }?.let { switchTo(it, keepTail = false) } }
                    // 用户按了暂停：回到上一首，下次再淡
                    !player.playWhenReady -> cancel("主播放器暂停了")
                    // 只是缓冲或换段：等它自己回来，墙钟继续推
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== primary) return
                if (playbackState == Player.STATE_ENDED && fade?.switched == false) {
                    // 淡入这一路已经完整在放，主播放器也收尾了：直接换过去。
                    // 回调里不释放当前播放器，等这一轮事件走完再换
                    handler.post { fade?.takeIf { !it.switched }?.let { switchTo(it, keepTail = false) } }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== primary) return
                val active = fade ?: return
                if (active.switched) stopTail(active, "主播放器出错") else cancel("主播放器出错")
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

        /** 尾巴放开焦点的消息一直没轮到（比如它先被释放了）时，最多等这么久就让新主播放器申请。 */
        const val FOCUS_HANDOFF_TIMEOUT_MS = 300L
    }
}
