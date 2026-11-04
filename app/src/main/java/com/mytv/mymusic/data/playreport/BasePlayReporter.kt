package com.mytv.mymusic.data.playreport

/**
 * 播放上报状态机：平台无关的部分。
 *
 * 播放器每 100ms 喂一帧（[onFrame]），子类只需要实现三个emit：
 * [onPlayStart] / [onPlayProgress] / [onPlayEnd]，把自己平台的报文发出去。
 *
 * 规则与上游 sodam `play_report.rs` 一致：
 *  - 单帧时长增量最多算 [MAX_TICK_MS]，系统睡眠 / 卡顿不会把几小时算成播放时长
 *  - 进度严格越过 [PROGRESS_THRESHOLD_MS] 才认「开播了」，每首只报一次
 *  - 播放→暂停的下降沿报一次结束，持续暂停不重复报
 *  - 播完的曲目不再报暂停；一首的结束只报一次
 *  - 切曲那一帧的时长记给上一首
 */
internal abstract class BasePlayReporter : PlayReporter {

    /** 当前这一首的跟踪状态。 */
    private class CurrentPlay(
        val trackId: String,
        var durationMs: Long,
        /** 这一首的 Song.extra，原样带给 emit。 */
        val extra: Map<String, String> = emptyMap(),
        var playedMs: Long = 0,
        var progressReported: Boolean = false,
        var finished: Boolean = false,
        /** 结束上报已发过；一首只报一次，避免自动接下一首和队列播完两头都发。 */
        var endReported: Boolean = false,
    )

    /** 一条已结束的播放记录，用于拼「最近播放」。 */
    private class PlayedRecord(val id: String, val durationMs: Long, val playedMs: Long)

    private var current: CurrentPlay? = null
    private val records = ArrayDeque<PlayedRecord>()
    private var lastObserveAt = 0L
    private var wasPlaying = false
    private var pendingReason: PlayEndReason? = null

    override fun onFrame(frame: PlayFrame) {
        val now = now()
        val deltaMs = if (lastObserveAt == 0L) 0L
        else (now - lastObserveAt).coerceIn(0L, MAX_TICK_MS)
        lastObserveAt = now

        val trackId = frame.trackId.trim()
        val trackChanged = current?.trackId != trackId
        if (trackChanged) {
            current?.let { previous ->
                // 本帧的时长属于上一首（它一直在播），不能记到新曲目头上
                if (wasPlaying) previous.playedMs += deltaMs
                val reason = pendingReason
                    ?: if (previous.finished) PlayEndReason.Finished else PlayEndReason.Next
                pendingReason = null
                finish(previous, reason)
            }
            current = if (trackId.isEmpty()) {
                null
            } else {
                onPlayStart(trackId, frame.durationMs, frame.extra)
                CurrentPlay(trackId, frame.durationMs, frame.extra)
            }
        }

        var progress: Triple<String, Long, Long>? = null
        current?.let { play ->
            if (frame.durationMs > 0) play.durationMs = frame.durationMs
            // 切曲那一帧上面已经把时长记给上一首了，这里不重复计
            if (frame.playing && !trackChanged) play.playedMs += deltaMs
            if (frame.playing && !play.progressReported && frame.positionMs > PROGRESS_THRESHOLD_MS) {
                play.progressReported = true
                progress = Triple(play.trackId, frame.positionMs, play.durationMs)
            }
            // 每帧的位置：只有需要按进度打点的平台才用（默认空实现，其他平台零开销）
            if (frame.playing) onPlayTick(play.trackId, frame.positionMs, play.durationMs, frame.extra)
            // 队列播完（不自动接下一首）时没有切歌事件，这里直接把最后一首报掉。
            // 报完保留这一首（标记为已结束）：之后进度循环还在喂同一首歌，
            // 不能每次帧都当成新开播再报一遍
            if (frame.finished && !play.finished) {
                play.finished = true
                finish(play, PlayEndReason.Finished)
            }
        }
        progress?.let { (id, position, duration) ->
            onPlayProgress(id, position, duration, frame.extra)
        }

        // 播放 → 暂停的下降沿：只发一次，持续暂停不重复发
        if (wasPlaying && !frame.playing) {
            current?.let { play ->
                if (!play.finished && play.playedMs > 0) finish(play, PlayEndReason.Pause)
            }
        }
        wasPlaying = frame.playing
    }

    override fun noteUserAction(reason: PlayEndReason) {
        pendingReason = reason
    }

    override fun playedMedia(limit: Int): List<PlayedMedia> {
        val out = mutableListOf<PlayedMedia>()
        current?.let { play ->
            if (play.playedMs > 0) {
                out += PlayedMedia(play.trackId, play.durationMs / 1000, play.playedMs)
            }
        }
        // 历史倒序：最近播的在最前，和官方 getMediaPlayed 一致
        for (index in records.indices.reversed()) {
            out += PlayedMedia(records[index].id, records[index].durationMs / 1000, records[index].playedMs)
        }
        return if (limit <= 0) out else out.take(limit)
    }

    override fun discard() {
        current = null
        records.clear()
        pendingReason = null
        wasPlaying = false
    }

    /** 入历史 + 发结束上报；一首只做一次。 */
    private fun finish(play: CurrentPlay, reason: PlayEndReason) {
        if (play.endReported) return
        play.endReported = true
        remember(play)
        onPlayEnd(play.trackId, play.playedMs, play.durationMs, reason, play.extra)
    }

    private fun remember(play: CurrentPlay) {
        // 一秒都没真听过的不入历史，也不值得回传
        if (play.playedMs <= 0) return
        records.addLast(PlayedRecord(play.trackId, play.durationMs, play.playedMs))
        while (records.size > MAX_RECORDS) records.removeFirst()
    }

    /** 开播。[durationMs] 是整曲时长，可能还没就绪（0）。 */
    protected abstract fun onPlayStart(trackId: String, durationMs: Long, extra: Map<String, String>)

    /** 首次拿到有效进度，每首只来一次。[positionMs] 是当前播放进度。 */
    protected abstract fun onPlayProgress(trackId: String, positionMs: Long, durationMs: Long, extra: Map<String, String>)

    /** 一首播放结束。[playedMs] 是实际播放毫秒（暂停不计），[reason] 见 [PlayEndReason]。 */
    protected abstract fun onPlayEnd(
        trackId: String,
        playedMs: Long,
        durationMs: Long,
        reason: PlayEndReason,
        extra: Map<String, String>,
    )

    /** 播放中每帧的位置，按 100ms 一次；只有要按进度打点的平台才需要实现。 */
    protected open fun onPlayTick(
        trackId: String,
        positionMs: Long,
        durationMs: Long,
        extra: Map<String, String>,
    ) = Unit

    /** 当前时刻；测试里换成可控制的假时钟。 */
    protected open fun now(): Long = System.currentTimeMillis()

    protected companion object {
        /** 单帧最大计时间隔（毫秒）。 */
        const val MAX_TICK_MS = 1_000L

        /** 首次进度上报阈值：进度严格大于它才算「真的开始放了」。 */
        const val PROGRESS_THRESHOLD_MS = 500L

        /** 保留的播放记录条数。 */
        const val MAX_RECORDS = 100
    }
}
