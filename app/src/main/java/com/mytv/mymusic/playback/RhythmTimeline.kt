package com.mytv.mymusic.playback

/**
 * 分析帧的排队区：分析线程按 pts 顺序写入，界面每帧取走 pts 不晚于“此刻听到的位置”的那些。
 *
 * 分析比耳朵早一个 AudioTrack 缓冲（约半秒），原来的 StateFlow 只留最新一帧，做不到“播到才用”。
 * 这里留最近 [capacity] 帧（64 帧约 2 秒），读的一方各自拿一个 [Cursor]，互不影响；
 * 读得太慢被覆盖的帧直接跳过。跳转时 [clear]，游标会从新的一段开始读。
 */
class RhythmTimeline(private val capacity: Int = DEFAULT_CAPACITY) {
    private val frames = arrayOfNulls<RhythmFrame>(capacity)

    // 下一帧的序号；最旧的一帧是 max(0, written - capacity)
    private var written = 0L
    private var generation = 0

    /** 读的一方的位置。 */
    class Cursor {
        internal var generation = -1
        internal var next = 0L
    }

    @Synchronized
    fun push(frame: RhythmFrame) {
        frames[(written % capacity).toInt()] = frame
        written++
    }

    @Synchronized
    fun clear() {
        generation++
        written = 0L
        frames.fill(null)
    }

    /**
     * 按顺序交出 pts ≤ [untilPtsUs] 的新帧，返回交出的帧数。[action] 在锁内调用，只做轻量的赋值。
     * 遇到第一帧还没播到的就停下，下次从它接着读。
     */
    @Synchronized
    fun drain(cursor: Cursor, untilPtsUs: Long, action: (RhythmFrame) -> Unit): Int {
        val oldest = maxOf(0L, written - capacity)
        if (cursor.generation != generation) {
            cursor.generation = generation
            cursor.next = oldest
        }
        if (cursor.next < oldest) cursor.next = oldest
        var count = 0
        while (cursor.next < written) {
            val frame = frames[(cursor.next % capacity).toInt()] ?: break
            if (frame.ptsUs > untilPtsUs) break
            action(frame)
            cursor.next++
            count++
        }
        return count
    }

    /** 已经分析好、还没播到的那一段有多长（微秒）；调试叠层显示“分析领先多少”。 */
    @Synchronized
    fun leadUs(audibleUs: Long): Long {
        if (written == 0L) return 0L
        val newest = frames[((written - 1) % capacity).toInt()] ?: return 0L
        return (newest.ptsUs - audibleUs).coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
