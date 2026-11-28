package com.mytv.mymusic.playback

/**
 * 播放器音量（设置 › 播放与换源里的「播放器音量」）。独立于系统音量：
 * 系统音量决定这一路音频最终被混音器放大多大，这里是先按这个比例把样本缩一遍，
 * 两者相乘，所以调小播放器音量不影响系统音量，也不会被系统音量覆盖。
 *
 * 放在音频处理链里（[VolumeGainAudioProcessor]）而不是 `ExoPlayer.setVolume`：
 * 那个位置已经被切歌过渡的淡化占用（交叉淡化用 0→1 的音量包络换主播放器）。
 * 处理链在淡化的下游，两者相乘，谁也不顶谁。
 */
object PlayerVolumeState {
    /** 满音量，等于不改动。 */
    const val FULL = 100

    /** 百分比，0..100；界面直接显示这个值。 */
    @Volatile
    var percent: Int = FULL
        private set

    /** 音频线程每帧读的缩放系数。 */
    @Volatile
    var fraction: Float = 1f
        private set

    fun update(percent: Int) {
        val clamped = percent.coerceIn(0, FULL)
        this.percent = clamped
        fraction = clamped / 100f
    }
}
