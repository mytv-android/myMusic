package com.mytv.mymusic.widget

import com.mytv.mymusic.ui.LyricPipeline
import com.mytv.mymusic.ui.poster.PosterLine
import com.mytv.mymusic.ui.poster.posterLines

/** 组件上显示的几句：上一句、当前句（带翻译）、下一句、再下一句。没有同步歌词时 [lines] 为空。 */
data class LyricWindow(
    val prev: String,
    val current: String,
    val translation: String,
    val next: String,
    val next2: String,
)

/**
 * 组件和“系统媒体条歌词”用同一套解析（[LyricPipeline]，按歌词文字设置拆双语、简转繁）和同一种并行规则
 * （[posterLines]：同一时间戳的翻译并到原文下面）。只按行换，不做逐字；罗马音组件上不显示，不转写。
 */
object WidgetLyrics {
    @Volatile
    private var cache: Pair<String, List<PosterLine>>? = null

    internal fun lines(snapshot: PlaybackSnapshot): List<PosterLine> {
        if (!snapshot.lyricsReady || snapshot.lyrics.isBlank()) return emptyList()
        val text = snapshot.lyricText.copy(autoRomanize = false)
        val key = snapshot.song.key + "#" + snapshot.lyrics.hashCode() + "#" + text.hashCode()
        cache?.let { if (it.first == key) return it.second }
        val lines = posterLines(LyricPipeline.prepare(snapshot.lyrics, text))
        cache = key to lines
        return lines
    }

    /** [now]（elapsedRealtime）那一刻的几句；没有同步歌词返回 null。 */
    fun window(snapshot: PlaybackSnapshot, now: Long): LyricWindow? {
        val lines = lines(snapshot)
        if (lines.isEmpty()) return null
        val position = snapshot.positionAt(now)
        val index = lines.indexOfLast { it.line.timeMs <= position }.coerceAtLeast(0)
        fun text(i: Int) = lines.getOrNull(i)?.line?.text?.trim().orEmpty()
        return LyricWindow(
            prev = text(index - 1),
            current = text(index),
            translation = lines[index].translation,
            next = text(index + 1),
            next2 = text(index + 2),
        )
    }

    /** 下一句开始的时刻（elapsedRealtime）；暂停、没有歌词或已经是最后一句时返回 null。 */
    fun nextChangeAt(snapshot: PlaybackSnapshot, now: Long): Long? {
        if (!snapshot.playing) return null
        val lines = lines(snapshot)
        if (lines.isEmpty()) return null
        val position = snapshot.positionAt(now)
        val next = lines.firstOrNull { it.line.timeMs > position } ?: return null
        return now + (next.line.timeMs - position)
    }

    /** 没有歌词时的那句话：纯音乐的歌词里通常写着“纯音乐，请欣赏”。 */
    fun emptyText(snapshot: PlaybackSnapshot): String =
        if ("纯音乐" in snapshot.lyrics) "纯音乐，请欣赏" else "暂时没有歌词"

    /**
     * 歌词区没有可显示的一句时的占位。从磁盘接上的队列要等开播解析地址时才取歌词；不写歌名，旁边已经有了。
     */
    fun placeholder(snapshot: PlaybackSnapshot): String = when {
        snapshot.lyricsReady -> emptyText(snapshot)
        !snapshot.live && !snapshot.playing -> "开始播放后显示歌词"
        else -> "正在取歌词…"
    }
}
