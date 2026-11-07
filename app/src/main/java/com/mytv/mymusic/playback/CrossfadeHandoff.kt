package com.mytv.mymusic.playback

/**
 * 交叉淡化换主播放器时，MediaController 把这次换歌报成 REPEAT，而不是「自动接到下一首」。
 * 换主之前在这里记下正在结束的那首，ViewModel 收到换歌回调时把它当成自然播完，
 * 播放上报才不会把每一首过渡掉的歌都记成「换队列」（甚至会漏记）。
 */
object CrossfadeHandoff {
    @Volatile
    private var pendingKey: String? = null

    fun mark(finishedKey: String?) {
        pendingKey = finishedKey?.takeIf { it.isNotBlank() }
    }

    /** 只有恰好是换主前记下的那一首才算，拿到就清掉，避免影响后面的正常切歌。 */
    fun consume(finishedKey: String?): Boolean {
        val pending = pendingKey ?: return false
        if (finishedKey == null || pending != finishedKey) return false
        pendingKey = null
        return true
    }
}
