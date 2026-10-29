package com.mytv.mymusic.ui.poster

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.PosterPrefs
import com.mytv.mymusic.data.PosterTint
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SwitchRow

/**
 * 墙内设置：染色、关灯、序号、翻译、跟随，电视上再加空闲漂移。队列海报墙和详情页海报视图共用一份，改了立即生效。
 * 手机是底部面板，平板是侧板，电视是右侧面板（[AdaptiveSheet]）。
 */
@Composable
internal fun PosterSettingsSheet(
    prefs: PosterPrefs,
    onChange: ((PosterPrefs) -> PosterPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    val tv = LocalAppWindow.current.isTv
    AdaptiveSheet(onDismiss = onDismiss, title = "海报墙设置", subtitle = "队列和歌单详情共用，只影响本机") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SectionLabel("海报染色", Modifier.padding(horizontal = 24.dp))
            ConnectedToggleGroup(
                options = PosterTint.entries,
                selected = prefs.tint,
                onSelect = { tint -> onChange { it.copy(tint = tint) } },
                label = { it.label },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth(),
            )
            val rows = buildList<Triple<String, String, Pair<Boolean, (Boolean) -> Unit>>> {
                add(Triple("关灯", "除了正在播放的那首，其余格子都压暗", prefs.lightsOut to { v: Boolean -> onChange { it.copy(lightsOut = v) } }))
                add(Triple("显示序号", "格子左上角标出第几首（队列里的位置，歌单里的曲目序号）", prefs.showIndex to { v: Boolean -> onChange { it.copy(showIndex = v) } }))
                add(Triple("显示歌词翻译", "大卡里当前行下面跟一行翻译", prefs.translation to { v: Boolean -> onChange { it.copy(translation = v) } }))
                add(Triple("切歌时自动跟随", "镜头滑到新歌最近的那一份并放大", prefs.follow to { v: Boolean -> onChange { it.copy(follow = v) } }))
                if (tv) {
                    add(Triple("空闲时镜头漂移", "30 秒不操作，镜头缓慢漂移，减少电视残影", prefs.tvDrift to { v: Boolean -> onChange { it.copy(tvDrift = v) } }))
                }
            }
            SectionLabel("显示", Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                rows.forEachIndexed { index, (title, summary, value) ->
                    SwitchRow(
                        title = title,
                        checked = value.first,
                        onCheckedChange = value.second,
                        index = index,
                        count = rows.size,
                        summary = summary,
                    )
                }
            }
        }
    }
}

internal val PosterTint.label: String
    get() = when (this) {
        PosterTint.Off -> "关"
        PosterTint.Weak -> "弱"
        PosterTint.Medium -> "中"
        PosterTint.Strong -> "强"
    }
