package com.mytv.mymusic.playback

import com.mytv.mymusic.data.AutomixAnalysisEntity
import com.mytv.mymusic.data.MusicDao
import java.util.concurrent.ConcurrentHashMap

/** 段落：从 [startMs] 开始的一段，能量 0..1（相对整首最响的一段）。 */
data class AutomixSection(val startMs: Long, val energy: Float)

/**
 * 切歌过渡需要的离线分析结果。拍网格复用界面上那套 [BeatGrid]，判断接法时和实时跟踪同一套坐标。
 * 一首歌只分析一次，结果按曲目 key 存在 Room 里；内存里留一份给播放侧同步读取。
 */
data class AutomixAnalysis(
    val songKey: String,
    val durationMs: Long,
    val beatGrid: BeatGrid,
    /** 整首的均方根响度（dBFS，满刻度 0）。 */
    val loudnessDb: Float,
    /** 0–11，-1 表示没算出来。 */
    val keyIndex: Int,
    val keyMinor: Boolean,
    val keyConfidence: Float,
    /** 第一个 / 最后一个有效声音的位置。 */
    val introMs: Long,
    val outroMs: Long,
    val sections: List<AutomixSection>,
    val updatedAt: Long,
) {
    val bpm: Double get() = beatGrid.bpm

    fun toEntity(): AutomixAnalysisEntity = AutomixAnalysisEntity(
        songKey = songKey,
        updatedAt = updatedAt,
        durationMs = durationMs,
        bpm = beatGrid.bpm,
        beatPeriodS = beatGrid.periodS,
        beatT0S = beatGrid.t0S,
        beatK0 = beatGrid.k0,
        beatConfidence = beatGrid.confidence,
        downbeat = beatGrid.downbeat,
        downbeatClear = beatGrid.downbeatClear,
        keyIndex = keyIndex,
        keyMinor = keyMinor,
        keyConfidence = keyConfidence,
        loudnessDb = loudnessDb,
        introMs = introMs,
        outroMs = outroMs,
        sections = sections.joinToString(",") { "${it.startMs}:${it.energy}" },
    )

    companion object {
        fun fromEntity(entity: AutomixAnalysisEntity): AutomixAnalysis = AutomixAnalysis(
            songKey = entity.songKey,
            durationMs = entity.durationMs,
            beatGrid = BeatGrid(
                periodS = entity.beatPeriodS,
                t0S = entity.beatT0S,
                k0 = entity.beatK0,
                confidence = entity.beatConfidence,
                downbeat = entity.downbeat,
                downbeatClear = entity.downbeatClear,
                strengthIndex = LongArray(0),
                strengthValue = FloatArray(0),
            ),
            loudnessDb = entity.loudnessDb,
            keyIndex = entity.keyIndex,
            keyMinor = entity.keyMinor,
            keyConfidence = entity.keyConfidence,
            introMs = entity.introMs,
            outroMs = entity.outroMs,
            sections = entity.sections.split(',')
                .mapNotNull { part ->
                    val pair = part.split(':')
                    val start = pair.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    val energy = pair.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
                    AutomixSection(start, energy)
                },
            updatedAt = entity.updatedAt,
        )

        /** 曲目 key + 时长对上才算这份结果还能用：换源到另一个版本（时长不同）要重新分析。 */
        fun matches(entity: AutomixAnalysisEntity?, songKey: String, durationMs: Long): Boolean =
            entity != null &&
                entity.songKey == songKey &&
                (durationMs <= 0L || entity.durationMs <= 0L || kotlin.math.abs(entity.durationMs - durationMs) <= MATCH_TOLERANCE_MS)

        private const val MATCH_TOLERANCE_MS = 2_000L
    }
}

/**
 * 分析结果的本机缓存：内存一份给播放侧在主线程上同步取，Room 一份跨进程启动保留。
 * 同一首曲目在不同来源下 key 不同，各存各的，换源到另一版本自然重新分析。
 */
class AutomixAnalysisStore(private val dao: MusicDao) {
    private val memory = ConcurrentHashMap<String, AutomixAnalysis>()

    fun cached(songKey: String): AutomixAnalysis? = memory[songKey]

    suspend fun get(songKey: String, durationMs: Long = 0L): AutomixAnalysis? {
        memory[songKey]?.let { if (AutomixAnalysis.matches(it.toEntity(), songKey, durationMs)) return it }
        val entity = runCatching { dao.automixAnalysis(songKey) }.getOrNull() ?: return null
        if (!AutomixAnalysis.matches(entity, songKey, durationMs)) return null
        val analysis = AutomixAnalysis.fromEntity(entity).also { memory[songKey] = it }
        return analysis
    }

    suspend fun put(analysis: AutomixAnalysis) {
        memory[analysis.songKey] = analysis
        runCatching {
            dao.saveAutomixAnalysis(analysis.toEntity())
            dao.pruneAutomixAnalysis(System.currentTimeMillis() - RETENTION_MS)
        }
    }

    private companion object {
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
