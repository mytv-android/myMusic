package com.mytv.mymusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun LyricsView(lyrics: String, positionMs: Long, television: Boolean) {
    if (lyrics.isBlank()) {
        Text("这个平台没有返回歌词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val parsed = remember(lyrics) { LyricsParser.parse(lyrics) }
    if (!parsed.synced) {
        Text(
            lyrics,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (television) 18 else 12,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    val listState = rememberLazyListState()
    val activeIndex = LyricsParser.activeLineIndex(parsed.lines, positionMs)
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.scrollToItem((activeIndex - 2).coerceAtLeast(0))
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = if (television) 340.dp else 230.dp),
        verticalArrangement = Arrangement.spacedBy(if (television) 10.dp else 6.dp),
    ) {
        itemsIndexed(parsed.lines) { index, line ->
            val active = index == activeIndex
            val baseStyle = when {
                television && active -> MaterialTheme.typography.headlineSmall
                television -> MaterialTheme.typography.titleMedium
                active -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyMedium
            }
            val baseColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            if (line.words.isNotEmpty()) {
                val text = buildAnnotatedString {
                    line.words.forEach { word ->
                        val sung = active && positionMs >= word.startMs
                        withStyle(
                            SpanStyle(
                                color = if (sung) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        ) {
                            append(word.text)
                        }
                    }
                }
                Text(
                    text = text,
                    style = baseStyle,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                )
            } else {
                Text(
                    text = line.text,
                    style = baseStyle,
                    color = baseColor,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                )
            }
        }
    }
}
