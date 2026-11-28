package com.mytv.mymusic.playback

import com.mytv.mymusic.data.CrossfadeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 同一张专辑连着放时的「接法」：不叠，交给播放器自己无缝接。 */
const val SAME_ALBUM_METHOD = "同一张专辑，直接接"

/** 过渡时长的读法：整秒写「6 秒」，不是整秒写「1.2 秒」。 */
fun transitionSecondsLabel(ms: Long): String =
    if (ms % 1000L == 0L) "${ms / 1000L} 秒" else "${"%.1f".format(ms / 1000f)} 秒"

/**
 * 上一次自动接歌的结果：用了哪种接法、多长，或者为什么退回直接切。
 * 播放服务在切完 / 放弃过渡时写，设置页读——参考文档里「上一次接歌」那一行。
 */
data class CrossfadeReport(
    val fromTitle: String,
    val toTitle: String,
    /** 例如「切拍」「等功率淡化」「同一张专辑，直接接」「直接切」。 */
    val method: String,
    val seconds: Float,
    /** 退回直接切的原因；没有退回时为 null。 */
    val fallbackReason: String? = null,
) {
    val headline: String
        get() = when {
            fallbackReason != null -> "已退回直接切"
            // 不叠的接法（同一张专辑）没有时长可写
            seconds <= 0f -> method
            else -> "$method · ${"%.1f".format(seconds)} 秒"
        }

    val detail: String
        get() = buildString {
            if (fromTitle.isNotBlank() || toTitle.isNotBlank()) {
                append('《').append(fromTitle).append("》→《").append(toTitle).append('》')
            }
            fallbackReason?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append(" · ")
                append(it)
            }
        }
}

/**
 * 正在淡化的这一次接歌：播放页据此画过渡动画。进度按 [startedAtMs] 和真实时间算，
 * 不另推帧——动画只是把已经定好的混音画出来。换页点之后它还在，画在新封面上，直到淡完。
 */
data class CrossfadeTransition(
    val fromTitle: String,
    val toTitle: String,
    val method: String,
    val durationMs: Long,
    /** [android.os.SystemClock.elapsedRealtime] 的起点。 */
    val startedAtMs: Long,
) {
    /** [t] 为当前 elapsedRealtime；0 到 1。 */
    fun progressAt(t: Long): Float =
        if (durationMs <= 0L) 1f else ((t - startedAtMs).toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * 下一次自动接歌已经定下的接法（下一首预载好之后才有）：播放队列「切歌过渡」那一行写「这次怎么接」。
 * [fromKey] 是正在放的那首（mediaId / Song.key），换了歌就作废。
 */
data class CrossfadeUpcoming(
    val fromKey: String,
    val toKey: String,
    /** 例如「切拍 · 1.2 秒」「同一张专辑，直接接」「直接切」。 */
    val label: String,
)

/** 播放队列里「切歌过渡」那一行：标题写方式，下面一行写这次会怎么接，[on] 是右边开关。 */
data class CrossfadeQueueLine(val title: String, val summary: String, val on: Boolean)

object CrossfadeStatus {
    private val _report = MutableStateFlow<CrossfadeReport?>(null)
    val report: StateFlow<CrossfadeReport?> = _report.asStateFlow()

    private val _transition = MutableStateFlow<CrossfadeTransition?>(null)
    val transition: StateFlow<CrossfadeTransition?> = _transition.asStateFlow()

    private val _upcoming = MutableStateFlow<CrossfadeUpcoming?>(null)
    val upcoming: StateFlow<CrossfadeUpcoming?> = _upcoming.asStateFlow()

    /** 被点掉的这次过渡（按起点记）：本次切歌内不再画。 */
    private val _dismissedAt = MutableStateFlow(-1L)
    val dismissedAt: StateFlow<Long> = _dismissedAt.asStateFlow()

    fun dismissActive() {
        _transition.value?.let { _dismissedAt.value = it.startedAtMs }
    }

    fun publish(report: CrossfadeReport) {
        _report.value = report
    }

    fun begin(transition: CrossfadeTransition) {
        _transition.value = transition
    }

    fun end() {
        _transition.value = null
    }

    /** 同样的内容不会重复发：每个检查周期都可以放心调。 */
    fun announce(upcoming: CrossfadeUpcoming?) {
        _upcoming.value = upcoming
    }

    /**
     * 队列那一行的文字。开关是「总开关开着且方式不是不过渡」；关着时写整首播完再切。
     * 下一首还没准备好写方式本身，准备好了（[upcoming] 对得上正在放的这首）写这一次的接法。
     */
    fun queueLine(
        enabled: Boolean,
        mode: CrossfadeMode,
        seconds: Int,
        upcoming: CrossfadeUpcoming?,
    ): CrossfadeQueueLine {
        if (!enabled || mode == CrossfadeMode.None) {
            return CrossfadeQueueLine(title = "切歌过渡", summary = "整首播完再直接切", on = false)
        }
        val summary = upcoming?.label ?: when (mode) {
            CrossfadeMode.Fade -> "${CrossfadeMode.Fade.label} · ${
                transitionSecondsLabel(
                    seconds.coerceIn(AutomixPlanner.FADE_MIN_SECONDS, AutomixPlanner.FADE_MAX_SECONDS) * 1000L,
                )
            }"
            else -> "${CrossfadeMode.Automix.label} · 按两首歌挑接法"
        }
        return CrossfadeQueueLine(title = mode.label, summary = summary, on = true)
    }
}

/**
 * 各首歌唱词的时间范围：ViewModel 取到（或预取到）歌词时登记，播放服务开始淡化时按 mediaId 取，挑换页点。
 * 只留最近几首；没有时间轴的歌词登记成 null，和没登记一样按「不知道」处理。
 */
object CrossfadeLyrics {
    private const val CAPACITY = 8
    private val spans = object : LinkedHashMap<String, LyricSpan?>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricSpan?>?): Boolean = size > CAPACITY
    }

    @Synchronized
    fun put(songKey: String, span: LyricSpan?) {
        if (songKey.isBlank()) return
        spans[songKey] = span
    }

    @Synchronized
    fun get(songKey: String): LyricSpan? = spans[songKey]
}
