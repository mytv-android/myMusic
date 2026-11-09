package com.mytv.mymusic.data.local

import com.mytv.mymusic.aggregate.MatchScore
import com.mytv.mymusic.data.Song
import kotlin.math.abs

/** 匹配的取舍规则，单独拎出来好测。 */
object LocalMatchRules {
    /** 按标签搜到的候选，TrackMatcher 打分到这个数才直接采纳（规格 5.1）。 */
    const val TAG_ACCEPT = 0.85f

    /** 识曲命中时的置信度：识曲只认音频，比标签搜索更可信；时长对不上（剪辑版、现场版）就降一档。 */
    const val AUDIO_CONFIDENCE = 0.9f
    const val AUDIO_CONFIDENCE_LOOSE = 0.7f
    private const val AUDIO_DURATION_TOLERANCE_SEC = 8

    /** 识曲取样的长度和默认起点；第二次“再听一次”从第 60 秒起换一段。 */
    const val SAMPLE_SECONDS = 3
    const val SAMPLE_START_MS = 30_000L
    const val SAMPLE_RETRY_START_MS = 60_000L

    fun acceptTag(score: MatchScore): Boolean = !score.rejected && score.total >= TAG_ACCEPT

    /** 识曲结果里挑一首：时长对得上的优先；都对不上就要第一首，但置信度降下来。 */
    fun pickAudio(candidates: List<Song>, localDurationSec: Int): Pair<Song, Float>? {
        if (candidates.isEmpty()) return null
        if (localDurationSec <= 0) return candidates.first() to AUDIO_CONFIDENCE
        val close = candidates.firstOrNull { it.duration > 0 && abs(it.duration - localDurationSec) <= AUDIO_DURATION_TOLERANCE_SEC }
        return if (close != null) close to AUDIO_CONFIDENCE else candidates.first() to AUDIO_CONFIDENCE_LOOSE
    }

    /**
     * 取样起点：默认第 30 秒（避开前奏的静音和口白）；歌太短就往前挪，保证能取满 3 秒。
     * 时长未知（0）时照原起点试，解码不到就由调用方退回从头取。
     */
    fun sampleStartMs(durationMs: Long, preferred: Long): Long {
        val need = SAMPLE_SECONDS * 1000L
        if (durationMs <= 0) return preferred
        if (durationMs <= need) return 0L
        return preferred.coerceAtMost(durationMs - need).coerceAtLeast(0L)
    }
}
