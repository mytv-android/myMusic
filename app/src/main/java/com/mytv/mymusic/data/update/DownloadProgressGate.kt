package com.mytv.mymusic.data.update

/** 限制 UI 进度通知频率；完成与阶段转换由调用方保证最终值。 */
internal class DownloadProgressGate(private val intervalNanos: Long = 100_000_000L) {
    private var lastEmissionNanos: Long? = null

    fun shouldEmit(nowNanos: Long, complete: Boolean): Boolean {
        val last = lastEmissionNanos
        if (complete || last == null || nowNanos - last >= intervalNanos) {
            lastEmissionNanos = nowNanos
            return true
        }
        return false
    }
}
