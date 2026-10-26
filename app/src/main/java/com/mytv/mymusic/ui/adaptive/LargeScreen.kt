package com.mytv.mymusic.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.aggregate.PlaybackMode
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.CoverBox
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.TopTab
import com.mytv.mymusic.ui.components.MorphIconButton
import com.mytv.mymusic.ui.components.PlayButtonSize
import com.mytv.mymusic.ui.components.PlayPauseButton
import com.mytv.mymusic.ui.components.SourceChip
import com.mytv.mymusic.ui.components.WavySeekBar
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.player.sleepLabel
import com.mytv.mymusic.ui.theme.LocalBlurPolicy
import com.mytv.mymusic.ui.theme.PlayerTheme
import com.mytv.mymusic.ui.theme.frosted

/** 平板导航栏：四个入口在上，设置在底部。FAB 菜单不和导航栏同屏，新建入口改在页面里用 Split button。 */
@Composable
fun TabletNavRail(selected: String, onSelect: (String) -> Unit, onSettings: () -> Unit) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight().width(96.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Spacer(Modifier.height(20.dp))
        TopTab.entries.forEach { tab ->
            val on = selected == tab.route
            NavigationRailItem(
                selected = on,
                onClick = { onSelect(tab.route) },
                icon = { Icon(if (on) tab.selectedIcon else tab.icon, null) },
                label = { Text(tab.label) },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        val on = selected == "settings"
        NavigationRailItem(
            selected = on,
            onClick = onSettings,
            icon = { Icon(if (on) Icons.Filled.Settings else Icons.Outlined.Settings, null) },
            label = { Text("设置") },
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

/** 底部播放条高度（不含系统导航栏），Snackbar 要让开这么多。 */
val BottomPlayerBarHeight = 88.dp

/**
 * 840–1199dp：底部播放条，把右侧空间还给内容。
 * [visible] 为 false 时只保留同高占位，不读取进度、不保留波浪/时间观察。
 */
@Composable
fun BottomPlayerBar(
    state: MusicUiState,
    position: State<Long>,
    actions: MusicActions,
    onOpenQueue: () -> Unit,
    visible: Boolean = true,
) {
    if (!visible) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(BottomPlayerBarHeight),
        )
        return
    }
    val song = state.current ?: return
    val blur = LocalBlurPolicy.current.enabled
    val container = MaterialTheme.colorScheme.surfaceContainer
    Row(
        Modifier
            .fillMaxWidth()
            .frosted(if (blur) container.copy(alpha = 0.85f) else container)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(BottomPlayerBarHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .widthIn(max = 280.dp)
                .weight(0.8f)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClickLabel = "打开播放页", onClick = actions::openPlayer)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cover(song.cover, song.title, 56.dp, corner = 14.dp)
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${song.artist} · ${state.currentSource?.providerName ?: song.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { actions.transport("previous") }) { Icon(Icons.Filled.SkipPrevious, "上一首") }
        PlayPauseButton(state.playing, { actions.transport("play") }, size = PlayButtonSize.Medium)
        IconButton(onClick = { actions.transport("next") }) { Icon(Icons.Filled.SkipNext, "下一首") }
        WavySeekBar(
            position = position,
            duration = state.duration,
            playing = state.playing,
            onSeek = actions::seek,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列") }
        IconButton(onClick = actions::openPlayer) { Icon(Icons.Filled.OpenInFull, "打开播放页") }
    }
}

/**
 * ≥1200dp：右侧常驻 380dp 播放窗格，使用播放页的深色 Vibrant 配色。
 * [visible] 为 false 时只保留同宽占位，不读取进度、不保留波浪/字体/倒计时观察。
 */
@Composable
fun NowPlayingPane(
    state: MusicUiState,
    position: State<Long>,
    favorites: List<SavedSong>,
    actions: MusicActions,
    onOpenQueue: () -> Unit,
    onOpenTimer: () -> Unit,
    sleep: SleepTimerState,
    visible: Boolean = true,
) {
    // 滚动状态放在提前返回之前：窗格隐藏再出现时仍停在原来的位置。
    val scrollState = rememberScrollState()
    if (!visible) {
        // 占位也使用播放页深色主题，但仍提前返回，不组合进度、队列等动态内容。
        // 占位没有可聚焦节点，这里也不主动 requestFocus，窗格恢复时不会抢走页面焦点。
        PlayerTheme {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(380.dp)
                    .statusBarsPadding()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .navigationBarsPadding(),
            )
        }
        return
    }
    PlayerTheme {
        Box(
            Modifier
                .fillMaxHeight()
                .width(380.dp)
                // 状态栏图标跟浏览页主题走（浅色主题下是深色图标），深色窗格不能铺到状态栏下面
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .navigationBarsPadding(),
        ) {
            val song = state.current
            if (song == null) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.MusicNote, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text("还没有在播放", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "从榜单、歌单或搜索结果开始，播放器会一直留在这里。",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                return@Box
            }
            val favorite = remember(favorites, song.key) { favorites.any { it.key == song.key } }
            Column(
                Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CoverBox(
                    song.cover,
                    song.title,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clickable(onClick = actions::openPlayer)
                        // 封面图本身不读，可点的封面要有名字
                        .semantics { contentDescription = "打开播放页" },
                    RoundedCornerShape(28.dp),
                    fontSize = 96f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    FilledTonalIconToggleButton(
                        checked = favorite,
                        onCheckedChange = { actions.favorite(song) },
                        modifier = Modifier.tvFocusRing(CircleShape),
                    ) {
                        Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (favorite) "取消收藏" else "收藏")
                    }
                }
                state.currentSource?.let { source ->
                    SourceChip(
                        providerName = source.providerName,
                        detail = source.qualityLabel.takeUnless { it == "音质未知" }.orEmpty(),
                        mode = modeLabel(state.aggregation.playbackMode),
                        onClick = {
                            state.currentTrack?.let { track -> actions.openSource(track, state.canonicalQueue.ifEmpty { listOf(track) }) }
                        },
                    )
                }
                WavySeekBar(position, state.duration, state.playing, actions::seek)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MorphIconButton(Icons.Filled.SkipPrevious, "上一首", { actions.transport("previous") }, size = 64.dp, iconSize = 30.dp)
                    PlayPauseButton(state.playing, { actions.transport("play") }, size = PlayButtonSize.Large)
                    MorphIconButton(Icons.Filled.SkipNext, "下一首", { actions.transport("next") }, size = 64.dp, iconSize = 30.dp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = onOpenTimer) {
                        Icon(Icons.Filled.Bedtime, if (sleep.active) "定时关闭：${sleepLabel(sleep)}" else "定时关闭", tint = if (sleep.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = actions::openPlayer) { Icon(Icons.Filled.OpenInFull, "全屏播放", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                val index = state.queue.indexOfFirst { it.key == song.key }
                val upcomingStart = index + 1
                val upcomingEnd = (upcomingStart + 5).coerceAtMost(state.queue.size)
                if (index >= 0 && upcomingStart < upcomingEnd) {
                    Text("接下来播放", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    for (queueIndex in upcomingStart until upcomingEnd) {
                        val next = state.queue[queueIndex]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { actions.playQueueIndex(queueIndex) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Cover(next.cover, next.title, 44.dp, corner = 10.dp)
                            Column(Modifier.weight(1f)) {
                                Text(next.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(next.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun modeLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.Auto -> "自动"
    PlaybackMode.Priority -> "优先"
    PlaybackMode.Pinned -> "固定"
}
