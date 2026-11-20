package com.mytv.mymusic.overlay

import com.mytv.mymusic.widget.PlaybackSnapshot
import com.mytv.mymusic.widget.WidgetLyrics

internal data class FloatingLyricsContent(
    val primary: String,
    val secondary: String,
    val playing: Boolean,
    val canControl: Boolean,
)

internal fun floatingLyricsContent(snapshot: PlaybackSnapshot?, now: Long): FloatingLyricsContent {
    if (snapshot == null) {
        return FloatingLyricsContent("myMusic 悬浮歌词", "还没有播放歌曲", playing = false, canControl = false)
    }
    val window = WidgetLyrics.window(snapshot, now)
    val primary = window?.current?.takeIf { it.isNotBlank() }
        ?: snapshot.song.title.ifBlank { "未命名歌曲" }
    val secondary = when {
        window == null -> WidgetLyrics.placeholder(snapshot)
        window.translation.isNotBlank() -> window.translation
        window.next.isNotBlank() -> window.next
        else -> snapshot.song.artist
    }
    return FloatingLyricsContent(primary, secondary, snapshot.playing, canControl = true)
}

internal data class FloatingLyricsPosition(val x: Int, val y: Int)

/** 整根悬浮条都留在屏幕里；悬浮条比屏幕还宽 / 高时贴左上，不出现负的可拖范围。 */
internal fun clampFloatingLyricsPosition(
    x: Int,
    y: Int,
    viewWidth: Int,
    viewHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): FloatingLyricsPosition {
    val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
    val maxY = (screenHeight - viewHeight).coerceAtLeast(0)
    return FloatingLyricsPosition(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
}
