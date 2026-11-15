package com.mytv.mymusic.ui.settings

import androidx.compose.runtime.Composable
import com.mytv.mymusic.lyricon.LyriconSubscriptionState

@Composable
internal fun LyriconPreviewCard(state: LyriconSubscriptionState) {
    val source = state.playerPackageName.ifBlank { state.providerPackageName }
    val title = when {
        state.timedOut -> "连接词幕超时"
        state.connecting -> "正在连接词幕…"
        !state.connected -> "等待词幕核心服务"
        source.isBlank() -> "已连接，等待活跃播放器"
        else -> "接收中 · $source"
    }
    val body = if (!state.connected || (state.song == null && state.plainText.isNullOrBlank())) {
        if (state.timedOut) "请检查词幕核心服务是否已安装并激活。连接失败不影响 myMusic 播放。"
        else "收到其他播放器的歌曲和歌词后会显示在这里。"
    } else {
        val song = state.song
        val line = state.currentLine
        buildList {
            song?.name?.takeIf { it.isNotBlank() }?.let(::add)
            song?.artist?.takeIf { it.isNotBlank() }?.let(::add)
            if (!state.plainText.isNullOrBlank()) {
                add(state.plainText)
            } else if (line != null) {
                add(line.text)
                if (state.displayRoma && line.romanization.isNotBlank()) add(line.romanization)
                if (state.displayTranslation && line.translation.isNotBlank()) add(line.translation)
            } else {
                add("等待歌词")
            }
        }.joinToString("\n")
    }
    TextCard(title = title, body = body, index = 0, count = 1)
}
