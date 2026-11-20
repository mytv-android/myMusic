package com.mytv.mymusic.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.SongComment
import com.mytv.mymusic.ui.ErrorBlock
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 歌曲评论只读面板：热门 + 最新；不请求发布、点赞或删除能力。 */
@Composable
internal fun CommentsSheet(song: Song, actions: MusicActions, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var hot by remember(song.key) { mutableStateOf<List<SongComment>>(emptyList()) }
    var comments by remember(song.key) { mutableStateOf<List<SongComment>>(emptyList()) }
    var total by remember(song.key) { mutableLongStateOf(0L) }
    var page by remember(song.key) { mutableIntStateOf(0) }
    var hasMore by remember(song.key) { mutableStateOf(false) }
    var loading by remember(song.key) { mutableStateOf(false) }
    var error by remember(song.key) { mutableStateOf("") }

    fun load(next: Int) {
        if (loading) return
        loading = true
        if (next == 1) error = ""
        scope.launch {
            try {
                val result = actions.comments(song, next)
                hot = if (next == 1) result.hotComments else hot
                comments = if (next == 1) result.comments else (comments + result.comments).distinctBy { it.id }
                total = result.total
                page = next
                hasMore = result.hasMore && result.comments.isNotEmpty()
                error = ""
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: "评论读取失败"
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(song.key) { load(1) }

    AdaptiveSheet(
        onDismiss = onDismiss,
        title = "歌曲评论",
        subtitle = song.title + if (total > 0) " · 共 ${formatPlayCount(total)} 条" else "",
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 560.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hot.isNotEmpty()) {
                item(key = "hotHead", contentType = "header") { CommentHeader("热门评论") }
                items(hot, key = { "hot:${it.id}" }, contentType = { "comment" }) { CommentRow(it) }
                item(key = "latestHead", contentType = "header") { CommentHeader("最新评论") }
            }
            items(comments, key = { "latest:${it.id}" }, contentType = { "comment" }) { CommentRow(it) }
            if (error.isNotBlank()) {
                item(key = "error", contentType = "error") {
                    ErrorBlock(error, onRetry = { load(if (page == 0) 1 else page + 1) })
                }
            }
            if (loading) {
                item(key = "loading", contentType = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                }
            }
            if (!loading && error.isBlank() && hot.isEmpty() && comments.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    Text(
                        "这首歌暂时没有评论",
                        Modifier.fillMaxWidth().padding(32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hasMore && !loading && error.isBlank()) {
                item(key = "more", contentType = "more") {
                    TextButton(
                        onClick = { load(page + 1) },
                        modifier = Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(20.dp)),
                    ) { Text("加载更多") }
                }
            }
        }
    }
}

@Composable
private fun CommentHeader(text: String) {
    Text(
        text,
        Modifier.padding(top = 8.dp, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun CommentRow(comment: SongComment) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (comment.avatar.isBlank()) {
                Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                AsyncImage(
                    model = comment.avatar,
                    contentDescription = "${comment.userName}的头像",
                    modifier = Modifier.fillMaxWidth().size(40.dp),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(comment.userName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            val meta = listOf(commentTime(comment), comment.location).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(comment.content, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ThumbUp,
                    contentDescription = if (comment.liked) "已赞" else "点赞数",
                    modifier = Modifier.size(16.dp),
                    tint = if (comment.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    if (comment.likedCount > 0) formatPlayCount(comment.likedCount) else "赞",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun commentTime(comment: SongComment): String {
    if (comment.timeText.isNotBlank()) return comment.timeText
    if (comment.time <= 0) return ""
    return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(comment.time)) }.getOrDefault("")
}
