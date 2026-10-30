package com.mytv.mymusic.playback

import com.mytv.mymusic.data.Song
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * 用平台返回的响度元数据算增益，不采样音频。
 * 网易云 / 酷狗 / QQ / 千千各自把原始字段放进 Song.extra，这里统一换算成 dB。
 */
object VolumeNormalization {
    const val TARGET_LUFS = -14f
    const val MAX_BOOST_DB = 6f
    const val MAX_CUT_DB = -20f
    const val PEAK_LIMIT = 0.98f

    fun gainDb(song: Song?): Float {
        val extra = song?.extra ?: return 0f
        return when (song.source) {
            "netease" -> {
                val closedGain = extra.float("netease_closed_gain")
                val closed = closedGain != null && abs(closedGain) > 0.05f
                val gain = if (closed) closedGain else extra.float("netease_gain")
                val peak = if (closed) extra.float("netease_closed_peak") else extra.float("netease_peak")
                effective(gain, peak)
            }
            "kugou" -> {
                val volume = extra.float("kugou_volume") ?: 0f
                val boost = extra.float("kugou_volume_gain") ?: 0f
                val gain = if (volume != 0f) TARGET_LUFS - volume + boost else boost
                effective(gain, extra.float("kugou_volume_peak"))
            }
            "qq" -> effective(extra.float("qq_gain"), extra.float("qq_peak"))
            "qianqian" -> {
                val mean = extra.float("qianqian_mean_volume") ?: 0f
                val gain = if (mean != 0f) {
                    TARGET_LUFS - mean
                } else {
                    extra.float("qianqian_replay_gain")
                }
                val maxDb = extra.float("qianqian_max_volume")
                val peak = if (mean != 0f && maxDb != null) 10f.pow(maxDb / 20f) else null
                effective(gain, peak)
            }
            else -> 0f
        }
    }

    internal fun effective(gain: Float?, peak: Float?): Float {
        if (gain == null || !gain.isFinite()) return 0f
        var value = gain.coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        if (value > 0f && peak != null && peak.isFinite() && peak > 0f) {
            val allowed = 20f * log10(PEAK_LIMIT / peak)
            if (allowed < value) value = allowed.coerceAtLeast(0f)
        }
        return if (abs(value) < 0.05f) 0f else value
    }

    private fun Map<String, String>.float(key: String): Float? = this[key]?.toFloatOrNull()
}
