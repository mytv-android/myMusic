package com.mytv.mymusic.widget

import android.content.Context
import android.content.Intent
import com.mytv.mymusic.MainActivity
import com.mytv.mymusic.data.LyricTextPrefs
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 播放层交给桌面小组件的一份快照。进程里的 MusicViewModel 跟着播放器发布到 [WidgetHub.playback]；
 * 进程刚被组件拉起、ViewModel 还没接上队列时，组件读磁盘上的队列（[com.mytv.mymusic.playback.QueueStore]）。
 */
data class PlaybackSnapshot(
    val song: Song,
    val index: Int,
    val queueSize: Int,
    val playing: Boolean,
    /** 播放器里装着这份队列，上一首 / 下一首能直接用；false 是从磁盘接上的队列，点播放要先解析地址。 */
    val live: Boolean,
    /** 进度锚点：[anchorAt]（elapsedRealtime）那一刻听到 [positionMs]，播放中按时间往前推。 */
    val positionMs: Long,
    val anchorAt: Long,
    val durationMs: Long,
    val lyrics: String = "",
    /** 这首的歌词已经取回（可能是空串，即没有歌词）；false 时还在取。 */
    val lyricsReady: Boolean = false,
    val seed: Int = 0,
    val sourceName: String = "",
    val quality: String = "",
    /** 正在漫游的听歌模式 id，没有就是空串。 */
    val feedModeId: String = "",
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** 歌词文字设置（拆双语、简转繁），组件和全屏歌词页看到的是同一份文字。 */
    val lyricText: LyricTextPrefs = LyricTextPrefs(),
) {
    fun positionAt(now: Long): Long {
        if (!playing) return positionMs
        val moved = positionMs + (now - anchorAt).coerceAtLeast(0)
        return if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
    }
}

/** 进程级的组件数据入口。只放播放层推过来的东西；设置、收藏、榜单由 [WidgetData] 自己读。 */
object WidgetHub {
    /** ViewModel 发布的当前播放；null 且 [authoritative] 为 true 表示队列确实是空的。 */
    val playback = MutableStateFlow<PlaybackSnapshot?>(null)

    /** ViewModel 已经接上（或确认没有）磁盘上的队列。之前组件读磁盘，之后只信 [playback]。 */
    val authoritative = MutableStateFlow(false)
}

/** 组件点开 App 时要去的地方，写在启动 MainActivity 的 Intent 里。 */
sealed interface WidgetTarget {
    data object Player : WidgetTarget
    data object Lyrics : WidgetTarget
    data object Search : WidgetTarget
    data object Recognize : WidgetTarget
    data object Timer : WidgetTarget
    data object Login : WidgetTarget
    data object Favorites : WidgetTarget
    data object History : WidgetTarget
    data object Modes : WidgetTarget
    data object Charts : WidgetTarget
    data class Chart(val source: String, val id: String, val name: String) : WidgetTarget
    data class LocalPlaylist(val id: String) : WidgetTarget

    /** 本地音乐页：上传通知点进来看进度、取消和重试。 */
    data object LocalMusic : WidgetTarget
}

/** 搜索页要做的一次性动作：组件的搜索条聚焦输入框，识曲按钮直接开始听。 */
enum class SearchRequest { Focus, Recognize }

object WidgetLinks {
    private const val EXTRA_TARGET = "com.mytv.mymusic.widget.TARGET"
    private const val EXTRA_SOURCE = "com.mytv.mymusic.widget.SOURCE"
    private const val EXTRA_ID = "com.mytv.mymusic.widget.ID"
    private const val EXTRA_NAME = "com.mytv.mymusic.widget.NAME"

    fun intent(context: Context, target: WidgetTarget): Intent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val name = when (target) {
            WidgetTarget.Player -> "player"
            WidgetTarget.Lyrics -> "lyrics"
            WidgetTarget.Search -> "search"
            WidgetTarget.Recognize -> "recognize"
            WidgetTarget.Timer -> "timer"
            WidgetTarget.Login -> "login"
            WidgetTarget.Favorites -> "favorites"
            WidgetTarget.History -> "history"
            WidgetTarget.Modes -> "modes"
            WidgetTarget.Charts -> "charts"
            is WidgetTarget.Chart -> {
                intent.putExtra(EXTRA_SOURCE, target.source).putExtra(EXTRA_ID, target.id).putExtra(EXTRA_NAME, target.name)
                "chart"
            }
            is WidgetTarget.LocalPlaylist -> {
                intent.putExtra(EXTRA_ID, target.id)
                "localPlaylist"
            }
            WidgetTarget.LocalMusic -> "localMusic"
        }
        return intent.putExtra(EXTRA_TARGET, name)
    }

    fun parse(intent: Intent?): WidgetTarget? {
        val name = intent?.getStringExtra(EXTRA_TARGET) ?: return null
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        return when (name) {
            "player" -> WidgetTarget.Player
            "lyrics" -> WidgetTarget.Lyrics
            "search" -> WidgetTarget.Search
            "recognize" -> WidgetTarget.Recognize
            "timer" -> WidgetTarget.Timer
            "login" -> WidgetTarget.Login
            "favorites" -> WidgetTarget.Favorites
            "history" -> WidgetTarget.History
            "modes" -> WidgetTarget.Modes
            "charts" -> WidgetTarget.Charts
            "chart" -> if (id.isBlank()) WidgetTarget.Charts else WidgetTarget.Chart(
                source = intent.getStringExtra(EXTRA_SOURCE).orEmpty(),
                id = id,
                name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            )
            "localPlaylist" -> if (id.isBlank()) null else WidgetTarget.LocalPlaylist(id)
            "localMusic" -> WidgetTarget.LocalMusic
            else -> null
        }
    }

    /** 处理过的 Intent 去掉目标，旋转重建时 getIntent() 还是它，不能再跳一次。 */
    fun consume(intent: Intent?) {
        intent?.removeExtra(EXTRA_TARGET)
    }
}
