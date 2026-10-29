package com.mytv.mymusic.playback

/**
 * 此刻耳朵听到的媒体位置（微秒）。
 *
 * 音频渲染线程每次向 AudioSink 要位置时（播放中约每 10ms 一次）记下一组（位置、nanoTime、速度、是否在播），
 * 界面线程每帧按自己的 nanoTime 往后推算，只读不锁。这个位置 ExoPlayer 已经扣掉了 AudioTrack 缓冲和设备上报的延迟，
 * 用的是渲染器时间轴（含播放列表各项的偏移），和分析帧的 [RhythmFrame.ptsUs] 是同一条轴，可以直接比较。
 */
class PlaybackClock {
    private class Snapshot(val positionUs: Long, val atNanos: Long, val speed: Float, val running: Boolean)

    @Volatile
    private var snapshot: Snapshot? = null

    // 下面两个只在音频渲染线程上写
    @Volatile
    private var running = false

    @Volatile
    private var speed = 1f

    /** AudioSink 报来的位置；还没开始出声（[UNSET]）时不记。 */
    fun onPosition(positionUs: Long, nowNanos: Long) {
        if (positionUs == UNSET) return
        snapshot = Snapshot(positionUs, nowNanos, speed, running)
    }

    /** 开始 / 暂停出声：从此刻推算出的位置起冻结或继续走。 */
    fun setRunning(value: Boolean, nowNanos: Long) {
        if (running == value) return
        val current = audibleUs(nowNanos)
        running = value
        if (current != UNSET) snapshot = Snapshot(current, nowNanos, speed, value)
    }

    /** 倍速：pts 和位置都是媒体时间，往后推算时按倍速走。 */
    fun setSpeed(value: Float, nowNanos: Long) {
        if (!(value > 0f) || value == speed) return
        val current = audibleUs(nowNanos)
        speed = value
        if (current != UNSET) snapshot = Snapshot(current, nowNanos, value, running)
    }

    /** 跳转、换输出：等 AudioSink 报来新位置之前不知道听到哪儿。 */
    fun clear() {
        snapshot = null
    }

    /**
     * 推算 [nowNanos] 时听到的位置，不知道时返回 [UNSET]。界面传的是帧时间（垂直同步时刻），可能比最近一次记录还早几毫秒，
     * 所以允许往回推一点；渲染线程停住（卡顿、缓冲）时最多往后推 [MAX_AHEAD_US]，不会一直跑下去。
     */
    fun audibleUs(nowNanos: Long): Long {
        val s = snapshot ?: return UNSET
        if (!s.running) return s.positionUs
        val elapsedUs = ((nowNanos - s.atNanos) / 1_000L).coerceIn(-MAX_BEHIND_US, MAX_AHEAD_US)
        return s.positionUs + (elapsedUs * s.speed).toLong()
    }

    /** 当前倍速（界面换算每分钟拍数用）。 */
    val playbackSpeed: Float get() = speed

    companion object {
        /** 与 AudioSink.CURRENT_POSITION_NOT_SET 相同。 */
        const val UNSET = Long.MIN_VALUE
        private const val MAX_AHEAD_US = 500_000L
        private const val MAX_BEHIND_US = 100_000L
    }
}
