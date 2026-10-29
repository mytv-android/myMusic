package com.mytv.mymusic.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.RepeatMode
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.distinctKeys
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.formatTime
import kotlinx.coroutines.delay
import java.util.Locale

/** 剩余时间，每秒刷新。返回 State，让调用方只在真正显示倒计时的位置读值。 */
@Composable
fun rememberSleepRemaining(sleep: SleepTimerState): androidx.compose.runtime.State<Long> =
    produceState(sleep.remainingMs(), sleep) {
        while (sleep.active && !sleep.endOfTrack) {
            value = sleep.remainingMs()
            delay(1000)
        }
    }

fun sleepLabel(sleep: SleepTimerState): String = when {
    !sleep.active -> ""
    sleep.endOfTrack -> "本首结束"
    else -> formatClock(sleep.remainingMs())
}

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return if (total >= 3600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
    }
}

@Composable
fun QueueSheet(state: MusicUiState, actions: MusicActions, onDismiss: () -> Unit) {
    val currentIndex = state.queue.indexOfFirst { it.key == state.current?.key }
    val queueKeys = remember(state.queue) { distinctKeys(state.queue) { it.key } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    AdaptiveSheet(
        onDismiss = onDismiss,
        title = "播放队列",
        subtitle = if (state.queue.isEmpty()) "队列是空的" else "${state.queue.size} 首 · 正在播放第 ${currentIndex + 1} 首",
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val repeat = state.repeat
            // 一行要放下两个模式键、海报墙和清空：电视侧边面板和窄手机上只有三百来 dp，模式键收紧内边距，
            // 随机开没开看填充色（读屏读状态），文字不跟着变长
            FilledTonalButton(
                onClick = { actions.transport("shuffle") },
                modifier = Modifier.tvFocusRing().semantics { stateDescription = if (state.shuffle) "已开" else "已关" },
                colors = if (state.shuffle) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                contentPadding = QueueModePadding,
            ) {
                Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("随机", maxLines = 1)
            }
            FilledTonalButton(
                onClick = { actions.transport("repeat") },
                modifier = Modifier.tvFocusRing(),
                colors = if (repeat != RepeatMode.Off) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                contentPadding = QueueModePadding,
            ) {
                Icon(if (repeat == RepeatMode.One) Icons.Filled.RepeatOne else Icons.Filled.Repeat, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    when (repeat) {
                        RepeatMode.Off -> "顺序播放"
                        RepeatMode.All -> "列表循环"
                        RepeatMode.One -> "单曲循环"
                    },
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            // 海报墙的主入口：收起面板，把队列铺成墙
            IconButton(
                onClick = { onDismiss(); actions.openPosterWall() },
                modifier = Modifier.tvFocusRing(CircleShape),
                enabled = state.queue.isNotEmpty(),
            ) {
                Icon(Icons.Filled.GridView, "海报墙")
            }
            IconButton(
                onClick = { actions.clearQueue(); onDismiss() },
                modifier = Modifier.tvFocusRing(CircleShape),
                enabled = state.queue.isNotEmpty(),
            ) {
                Icon(Icons.Filled.DeleteSweep, "清空播放队列")
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 560.dp),
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(
                state.queue,
                key = { index, _ -> queueKeys[index] },
                contentType = { _, _ -> "queue" },
            ) { index, song ->
                val playing = index == currentIndex
                SongRow(
                    title = song.title,
                    subtitle = song.artist,
                    cover = song.cover,
                    index = index,
                    count = state.queue.size,
                    onClick = { actions.playQueueIndex(index) },
                    playing = playing,
                    trailing = {
                        if (playing) {
                            // “正在播放”由 SongRow 的状态读出，这里只是装饰
                            Icon(Icons.Filled.Equalizer, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        } else if (song.duration > 0) {
                            Text(formatTime(song.duration * 1000L), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
            }
        }
    }
}

/**
 * 定时关闭：15 / 30 / 45 / 60 分钟，或播完当前歌曲再停；结束前 30 秒淡出。
 * 计时在 PlaybackService 里，后台和锁屏都有效。
 */
@Composable
fun SleepTimerSheet(state: MusicUiState, sleep: SleepTimerState, actions: MusicActions, onDismiss: () -> Unit) {
    val options = listOf(15, 30, 45, 60)
    var minutes by rememberSaveable { mutableIntStateOf(30) }
    var afterCurrent by rememberSaveable { mutableIntStateOf(if (sleep.endOfTrack) 1 else 0) }
    val remaining = rememberSleepRemaining(sleep)
    // 倒计时每秒变一次：交给标题的 provider，只有标题文字重组
    val active = sleep.active
    AdaptiveSheet(
        onDismiss = onDismiss,
        title = "定时关闭",
        subtitle = if (active) "" else "到点前 30 秒音量逐渐变小，然后暂停",
        subtitleProvider = if (active) {
            { if (sleep.endOfTrack) "当前歌曲播完后暂停" else "还剩 ${formatClock(remaining.value)}，到点前 30 秒音量逐渐变小" }
        } else {
            null
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectedToggleGroup(
                options = options,
                selected = if (afterCurrent == 1) null else minutes,
                onSelect = { minutes = it; afterCurrent = 0 },
                label = { "$it 分钟" },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(
                    title = "播完当前歌曲再停止",
                    checked = afterCurrent == 1,
                    onCheckedChange = { afterCurrent = if (it) 1 else 0 },
                    index = 0,
                    count = 2,
                )
                SwitchRow(
                    title = "结束时淡出",
                    summary = "最后 30 秒逐渐降低音量",
                    checked = state.sleepFade,
                    onCheckedChange = actions::setSleepFade,
                    index = 1,
                    count = 2,
                    enabled = afterCurrent == 0,
                )
            }
            Button(
                onClick = {
                    if (afterCurrent == 1) actions.sleepAfterCurrent() else actions.startSleepTimer(minutes)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp).tvFocusRing(RoundedCornerShape(28.dp)),
            ) {
                Icon(Icons.Filled.Bedtime, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        afterCurrent == 1 -> "播完这首后停止"
                        sleep.active -> "重新定时 $minutes 分钟"
                        else -> "开始定时"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (sleep.active) {
                TextButton(
                    onClick = { actions.cancelSleepTimer(); onDismiss() },
                    modifier = Modifier.fillMaxWidth().tvFocusRing(),
                ) { Text("取消定时") }
            }
        }
    }
}

/** 播放队列顶上两个模式键的内边距：比默认的 24dp 收紧，一行放得下海报墙和清空。 */
private val QueueModePadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
