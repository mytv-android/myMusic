package com.mytv.mymusic.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.LyricsBackgroundMode
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.SectionLabel

internal val LyricsBackgroundMode.label: String
    get() = when (this) {
        LyricsBackgroundMode.Off -> "关闭"
        LyricsBackgroundMode.Ambient -> "动态"
        LyricsBackgroundMode.Reactive -> "律动"
    }

@Composable
fun LyricsSettingsScreen(
    state: MusicUiState,
    actions: MusicActions,
    onBack: () -> Unit,
    embedded: Boolean = false,
) {
    val mode = state.lyricsBackground
    SettingsPage(SettingsSection.Lyrics, embedded, onBack) {
        item(key = "backgroundHead", contentType = "sectionHeader") { SectionLabel("显示歌词背景") }
        item(key = "background", contentType = "lyricsOptions") {
            Column {
                ConnectedToggleGroup(
                    options = LyricsBackgroundMode.entries,
                    selected = mode,
                    onSelect = actions::setLyricsBackground,
                    label = { it.label },
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                )
                SettingsNote(
                    when (mode) {
                        LyricsBackgroundMode.Off -> "播放页使用纯色背景，封面和歌词内容不受影响。"
                        LyricsBackgroundMode.Ambient -> "模糊封面随播放缓慢流动，与原来的播放页一致。"
                        LyricsBackgroundMode.Reactive -> "低频、中频和高频光斑随音乐能量变化，鼓点触发短促的缩放与辉光。"
                    },
                )
            }
        }
        if (mode == LyricsBackgroundMode.Reactive) {
            item(key = "reactiveNote", contentType = "note") {
                SettingsNote(
                    "律动直接分析本机正在播放的音频，不申请麦克风权限，也不保存或上传音频。" +
                        "暂停、离开播放页或开启“减少动画”时停止分析；重新播放后会重新适应当前歌曲的动态范围。",
                )
            }
        }
    }
}
