package com.mytv.mymusic.data.playreport

import com.mytv.mymusic.data.native.NativeProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 一帧播放快照。播放器每 100ms 喂一次（见 MusicViewModel.startProgress），
 * 由各平台自己的上报实现判断该发什么。
 */
data class PlayFrame(
    val trackId: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** 这一首已经自然播完（播放器自动接下一首），不是用户切走的。 */
    val finished: Boolean,
    /**
     * 平台另外需要的标识，直接来自 Song.extra。
     * 例如 QQ 的上报要数字 song_id（`extra["song_id"]`），而 [trackId] 是 songmid。
     */
    val extra: Map<String, String> = emptyMap(),
)

/**
 * 一首播放结束的原因。各平台映射成自己的结束状态字面量，
 * 例如汽水是 finished / pause / next / previous / change_queue。
 */
enum class PlayEndReason { Finished, Pause, Next, Previous, ChangeQueue }

/** 一条已播记录：推荐流请求里回传「最近放了什么、放了多久」用。 */
data class PlayedMedia(val id: String, val durationSeconds: Long, val playedMs: Long)

/**
 * 单个平台的播放上报。实现里自己持有状态机、设备标识和网络细节。
 * 所有回调都在 IO 线程上被调用，失败自己吞掉，绝不影响播放。
 */
interface PlayReporter {
    val providerId: String

    /** 播放器每 100ms 喂一帧。 */
    fun onFrame(frame: PlayFrame)

    /**
     * 用户主动换歌 / 换队列，在动作生效前调用，决定上一首以什么状态结束。
     * 只有 [PlayEndReason.Next] / [PlayEndReason.Previous] / [PlayEndReason.ChangeQueue] 会出现。
     */
    fun noteUserAction(reason: PlayEndReason)

    /** 最近播放，最近的在最前；[limit] <= 0 表示不限制。 */
    fun playedMedia(limit: Int = 0): List<PlayedMedia>

    /** 这一条在上报设置页里显示的说明：上报到哪儿、有什么用。 */
    fun reportSummary(): String = ""

    /** 登录用户 id 就绪时补上；埋点据此区分登录态。没有账号概念的平台可以忽略。 */
    fun setUserId(userId: String) {}

    /** 关掉上报时丢掉没发完的半条记录，不补报。 */
    fun discard() {}
}

/**
 * 播放上报路由。设置里的总开关关掉时一个请求都不发；各平台的实现按 provider id 注册。
 *
 * 网络调用全部在自己的 IO scope 里做，主线程只做状态推演。
 */
object PlayReportHub {
    /** 推荐流请求里最多回传多少条最近播放（官方 getMediaPlayed 的 MAX_COUNT）。 */
    const val PLAYED_MEDIA_LIMIT = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reporters = ConcurrentHashMap<String, PlayReporter>()
    private val cookies = ConcurrentHashMap<String, String>()

    @Volatile
    private var enabled = true

    fun register(reporter: PlayReporter) {
        reporters[reporter.providerId] = reporter
    }

    fun reporter(providerId: String): PlayReporter? = reporters[providerId]

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) reporters.values.forEach { it.discard() }
    }

    fun updateCookies(values: Map<String, String>) {
        cookies.clear()
        cookies.putAll(values)
    }

    fun cookie(providerId: String): String = cookies[providerId].orEmpty()

    /** 播放器每 100ms 喂一帧；只路由给当前音源所属的平台。 */
    fun frame(providerId: String, frame: PlayFrame) {
        if (!enabled) return
        reporters[providerId]?.onFrame(frame)
    }

    /** 用户主动换歌 / 换队列。 */
    fun userAction(providerId: String, reason: PlayEndReason) {
        if (!enabled) return
        reporters[providerId]?.noteUserAction(reason)
    }

    /**
     * 最近播放的 JSON 数组，直接塞进推荐流请求体；没有记录时是 `[]`。
     * [providerId] 是发请求的那个平台——口味数据只回传给同一个平台。
     */
    fun playedMediaJson(providerId: String, limit: Int = PLAYED_MEDIA_LIMIT): String {
        if (!enabled) return "[]"
        val media = reporters[providerId]?.playedMedia(limit).orEmpty().take(limit)
        if (media.isEmpty()) return "[]"
        return buildString {
            append('[')
            media.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append("{\"media_id\":\"")
                append(item.id)
                append("\",\"type\":\"track\",\"duration\":")
                append(item.durationSeconds.coerceAtLeast(0))
                append(",\"played_mills\":")
                append(item.playedMs)
                append('}')
            }
            append(']')
        }
    }

    /** 已注册上报实现的平台；设置页用它说明哪些平台会回传播放记录。 */
    fun reportingProviders(): List<String> = reporters.keys.sorted()

    internal fun launch(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /** 供设置页 / 调试用：某个平台当前攒了多少条最近播放。 */
    fun playedCount(providerId: String): Int =
        reporters[providerId]?.playedMedia(0)?.size ?: 0

    /** 平台 id → 显示名，设置页用。 */
    fun displayName(providerId: String): String =
        NativeProviders.infos().firstOrNull { it.id == providerId }?.name ?: providerId
}
