package com.mytv.mymusic.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 上一次自动接歌的结果：用了哪种接法、多长，或者为什么退回直接切。
 * 播放服务在切完 / 放弃过渡时写，设置页读——参考文档里「上一次接歌」那一行。
 */
data class CrossfadeReport(
    val fromTitle: String,
    val toTitle: String,
    /** 例如「等功率淡化」「直接切」；后续接了更多接法会换成「切拍」「低频交接」等。 */
    val method: String,
    val seconds: Float,
    /** 退回直接切的原因；没有退回时为 null。 */
    val fallbackReason: String? = null,
) {
    val headline: String
        get() = if (fallbackReason != null) "已退回直接切" else "$method · ${"%.1f".format(seconds)} 秒"

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
 * 不另推帧——动画只是把已经定好的混音画出来。
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

object CrossfadeStatus {
    private val _report = MutableStateFlow<CrossfadeReport?>(null)
    val report: StateFlow<CrossfadeReport?> = _report.asStateFlow()

    private val _transition = MutableStateFlow<CrossfadeTransition?>(null)
    val transition: StateFlow<CrossfadeTransition?> = _transition.asStateFlow()

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
}
