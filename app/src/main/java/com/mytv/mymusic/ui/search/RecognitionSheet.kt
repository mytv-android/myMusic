package com.mytv.mymusic.ui.search

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.recognize.RecognitionPhase
import com.mytv.mymusic.recognize.RecognitionUiState
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.components.AdaptiveSheet

@Composable
fun RecognitionSheet(
    state: RecognitionUiState,
    providerNames: Map<String, String>,
    onPlay: (Song) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(
        onDismiss = onDismiss,
        title = "听歌识曲",
        subtitle = when (state.phase) {
            RecognitionPhase.Listening -> "正在聆听，请靠近音源"
            RecognitionPhase.Matching -> "正在识别…"
            RecognitionPhase.Done -> "找到 ${state.results.size} 个结果"
            RecognitionPhase.Error -> "没有听清"
            RecognitionPhase.Idle -> ""
        },
    ) {
        when (state.phase) {
            RecognitionPhase.Listening -> ListeningBody(state, onDismiss)
            RecognitionPhase.Matching -> MatchingBody(onDismiss)
            RecognitionPhase.Done -> ResultsBody(state.results, providerNames, onPlay, onRetry, onDismiss)
            RecognitionPhase.Error -> ErrorBody(state.message, onRetry, onDismiss)
            RecognitionPhase.Idle -> Unit
        }
    }
}

@Composable
private fun ListeningBody(state: RecognitionUiState, onDismiss: () -> Unit) {
    val level by animateFloatAsState(state.level, label = "recognitionLevel")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size((92 + 28 * level).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            )
            Box(
                Modifier
                    .size(82.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("正在聆听…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "剩余 ${state.secondsLeft.coerceAtLeast(0)} 秒",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDismiss) { Text("取消") }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MatchingBody(onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text("正在识别…", style = MaterialTheme.typography.titleMedium)
        Text(
            "网易云和酷狗两个通道同时查询",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onDismiss) { Text("取消") }
    }
}

@Composable
private fun ResultsBody(
    songs: List<Song>,
    providerNames: Map<String, String>,
    onPlay: (Song) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
        songs.forEachIndexed { index, song ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(song) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(url = song.cover, key = song.key, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    providerNames[song.source] ?: song.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (index != songs.lastIndex) {
                HorizontalDivider(Modifier.padding(start = 84.dp, end = 20.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            TextButton(onClick = onRetry) { Text("重新识别") }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text("没有识别到歌曲", style = MaterialTheme.typography.titleMedium)
        Text(
            message.ifBlank { "请靠近音源，在安静环境下再试一次" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) { Text("再试一次") }
        TextButton(onClick = onDismiss) { Text("关闭") }
    }
}
