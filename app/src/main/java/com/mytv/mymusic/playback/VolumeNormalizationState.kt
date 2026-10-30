package com.mytv.mymusic.playback

/** 播放线程只读这里；ViewModel 在切歌或切换设置时写入目标增益。 */
object VolumeNormalizationState {
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var gainDb: Float = 0f
        private set

    /** 换歌时递增，处理器据此直接跳到新目标，不把上一首的增益带过来。 */
    @Volatile
    var generation: Long = 0L
        private set

    private var lastKey: String? = null

    @Synchronized
    fun update(enabled: Boolean, gainDb: Float, key: String?) {
        this.enabled = enabled
        this.gainDb = gainDb
        if (key != lastKey) {
            lastKey = key
            generation++
        }
    }
}
