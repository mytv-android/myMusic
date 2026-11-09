package com.mytv.mymusic.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.local.LocalCandidate
import com.mytv.mymusic.data.local.LocalIdentity
import com.mytv.mymusic.data.local.LocalItem
import com.mytv.mymusic.data.local.LocalLibrary
import com.mytv.mymusic.data.local.LocalMatchMethod
import com.mytv.mymusic.data.local.LocalMatchNetworkException
import com.mytv.mymusic.data.local.LocalTags
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.InfoNote
import com.mytv.mymusic.ui.MusicChip
import com.mytv.mymusic.ui.formatBytes
import com.mytv.mymusic.ui.formatDuration
import com.mytv.mymusic.ui.library.TvDefaultFocus
import com.mytv.mymusic.ui.providerName
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun matchMethodLabel(method: String): String = when (method) {
    LocalMatchMethod.TAG -> "按标签匹配"
    LocalMatchMethod.AUDIO -> "听歌识曲匹配"
    LocalMatchMethod.MANUAL -> "手动选择"
    LocalMatchMethod.NONE -> "自动匹配没找到"
    LocalMatchMethod.REJECTED -> "已设为不匹配"
    else -> "等待匹配"
}

private fun percent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100).toInt()}%"

/** “FLAC · 24 bit / 48 kHz · 4:12”，拿不到的项不写。 */
internal fun formatLine(item: LocalItem): String {
    val row = item.row
    val quality = when {
        row.bitsPerSample > 0 && row.sampleRate > 0 ->
            "${row.bitsPerSample} bit / ${String.format(Locale.ROOT, "%.4g", row.sampleRate / 1000.0).trimEnd('0').trimEnd('.')} kHz"
        row.sampleRate > 0 -> "${String.format(Locale.ROOT, "%.4g", row.sampleRate / 1000.0).trimEnd('0').trimEnd('.')} kHz"
        row.bitrate > 0 -> "${row.bitrate / 1000} kbps"
        else -> ""
    }
    return listOf(row.extension.uppercase(Locale.ROOT), quality, formatDuration(row.durationSec))
        .filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * ⋮ 歌曲信息：匹配结果（可以换一个）、歌词用的是哪份、文件的位置格式大小指纹，底部“加入歌单 / 下一首播放”。
 * [cloudCopies] 是云端副本那一段的位置，上传落地后从这里接进来；现在为空就不画。
 */
@Composable
internal fun LocalTrackInfoSheet(
    item: LocalItem,
    library: LocalLibrary,
    preferLrc: Boolean,
    onRematch: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit,
    cloudCopies: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val row = item.row
    val song = item.song
    val match = item.match
    val focus = remember { FocusRequester() }
    TvDefaultFocus(focus)
    val hasLrc by produceState(initialValue = row.lrcUri.isNotBlank(), row.localId, row.lrcUri, row.dataPath) {
        value = withContext(Dispatchers.IO) { library.hasSidecar(row) }
    }
    AdaptiveSheet(
        onDismiss = onDismiss,
        title = song.title,
        subtitle = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
    ) {
        // 中间可滚动、底部按钮固定：小屏或候选很多时“加入歌单 / 使用这个”也不会被挤出屏幕
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            SectionLabel("封面和歌词")
            ListRow(
                title = if (match != null) "${providerName(emptyList(), row.matchProvider)} · ${match.name}" else "没匹配",
                index = 0,
                count = 2,
                onClick = onRematch,
                modifier = Modifier.focusRequester(focus),
                leading = { Cover(match?.cover?.ifBlank { null } ?: song.cover, song.title, 40.dp, corner = 10.dp) },
                // 手动选的就是用户认定的那首，相似度没有意义，不写百分比
                summary = if (match != null && row.matchMethod != LocalMatchMethod.MANUAL) {
                    "${matchMethodLabel(row.matchMethod)} · ${percent(row.matchConfidence)}"
                } else {
                    matchMethodLabel(row.matchMethod)
                },
                showChevron = false,
                trailing = {
                    TextButton(onClick = onRematch, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("换一个") }
                },
            )
            ListRow(
                title = "歌词",
                index = 1,
                count = 2,
                onClick = null,
                icon = Icons.Filled.Lyrics,
                summary = when {
                    hasLrc && preferLrc -> "用文件旁的同名 .lrc"
                    match != null && hasLrc -> "用${providerName(emptyList(), row.matchProvider)}的歌词；文件旁有同名 .lrc"
                    match != null -> "用${providerName(emptyList(), row.matchProvider)}的歌词"
                    hasLrc -> "用文件旁的同名 .lrc"
                    else -> "没有歌词：匹配上或在文件旁放同名 .lrc 就有"
                },
                showChevron = false,
            )
            if (cloudCopies != null) {
                SectionLabel("云端副本")
                cloudCopies()
            }
            SectionLabel("文件")
            val fileRows = listOf(
                Triple(Icons.Filled.Folder, "位置", (row.relativePath + row.displayName).ifBlank { row.displayName }),
                Triple(Icons.Filled.GraphicEq, "格式", formatLine(item).ifBlank { "未知" }),
                Triple(Icons.Filled.Storage, "大小", formatBytes(row.size)),
                Triple(Icons.Filled.Fingerprint, "指纹", LocalIdentity.short(row.localId)),
            )
            fileRows.forEachIndexed { at, (icon, label, value) ->
                ListRow(
                    title = label,
                    index = at,
                    count = fileRows.size,
                    onClick = null,
                    icon = icon,
                    summary = value,
                    showChevron = false,
                )
            }
            if (row.missing) {
                Spacer(Modifier.size(8.dp))
                InfoNote("文件不在了：可能被删了或搬走了。重新扫描能找回搬走的文件；歌单里的这首不受影响。", "amber")
                TextButton(
                    onClick = {
                        library.launch { forget(row.localId) }
                        onDismiss()
                    },
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                ) {
                    Text("从本地音乐移除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onAddToPlaylist,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
            ) { Text("加入歌单") }
            Button(
                onClick = onPlayNext,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
            ) { Text("下一首播放") }
        }
    }
}

/**
 * 换一个匹配：搜索框预填文件标签或文件名，按已启用的平台搜；百分比是现有换源匹配的打分。
 * “再听一次歌识曲”换一段（第 60 秒起）重新识别。“不匹配”清掉匹配，回到文件自己的标签和内嵌封面。
 * 只改应用里显示的封面、歌词和标题，文件不动。
 */
@Composable
internal fun LocalRematchSheet(item: LocalItem, library: LocalLibrary, onDismiss: () -> Unit) {
    val row = item.row
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }
    TvDefaultFocus(searchFocus)
    var query by remember(row.localId) { mutableStateOf(LocalTags.rematchQuery(row.title, row.artist, row.displayName, item.match)) }
    var providers by remember { mutableStateOf<List<String>>(emptyList()) }
    var provider by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<LocalCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    // 现在的匹配放在第一位并默认选中；选别的才能“使用这个”
    val current = remember(row.matchPayload) {
        item.match?.let { LocalCandidate(it, row.matchProvider, row.matchConfidence, row.matchMethod) }
    }
    var picked by remember { mutableStateOf(current?.song?.key) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun run(label: String, block: suspend () -> List<LocalCandidate>) {
        job?.cancel()
        loading = label
        error = ""
        job = scope.launch {
            try {
                results = block()
                if (results.isEmpty()) error = "没有找到结果"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: LocalMatchNetworkException) {
                error = "网络连不上，稍后再试"
            } catch (failure: Exception) {
                error = failure.message?.takeIf { it.isNotBlank() } ?: "搜索失败"
            } finally {
                loading = ""
            }
        }
    }

    fun search() {
        val target = provider ?: return
        val text = query
        run("正在搜索…") { library.search(row.localId, text, target) }
    }

    LaunchedEffect(row.localId) {
        providers = library.matcher.searchProviders()
        provider = providers.firstOrNull { it == row.matchProvider } ?: providers.firstOrNull()
        if (query.isNotBlank()) search()
    }

    val shown = remember(results, current) {
        (listOfNotNull(current) + results).distinctBy { it.song.key }
    }
    AdaptiveSheet(
        onDismiss = onDismiss,
        title = "换一个匹配",
        subtitle = "只改应用里显示的封面、歌词和标题，文件不动",
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                singleLine = true,
                label = { Text("搜索") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            if (providers.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { id ->
                        MusicChip(
                            text = providerName(emptyList(), id),
                            selected = id == provider,
                            onClick = {
                                provider = id
                                search()
                            },
                        )
                    }
                }
            }
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            when {
                loading.isNotBlank() -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(loading, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error.isNotBlank() && shown.isEmpty() -> Text(
                    error,
                    Modifier.padding(4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            shown.forEachIndexed { at, candidate ->
                CandidateRow(
                    candidate = candidate,
                    index = at,
                    count = shown.size,
                    selected = picked == candidate.song.key,
                    current = candidate.song.key == current?.song?.key,
                    onSelect = { picked = candidate.song.key },
                )
            }
            if (error.isNotBlank() && shown.isNotEmpty() && loading.isBlank()) {
                Text(error, Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(8.dp))
            ListRow(
                title = "再听一次歌识曲",
                index = 0,
                count = 1,
                onClick = {
                    run("正在听…") { library.listenAgain(row.localId) }
                },
                icon = Icons.Filled.Hearing,
                summary = "换一段（第 60 秒起）重新识别",
                showChevron = false,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    library.launch { rejectMatch(row.localId) }
                    onDismiss()
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
            ) { Text("不匹配") }
            val choice = shown.firstOrNull { it.song.key == picked }
            Button(
                onClick = {
                    if (choice != null) library.launch { applyMatch(row.localId, choice) }
                    onDismiss()
                },
                enabled = choice != null && choice.song.key != current?.song?.key,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
            ) { Text("使用这个") }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: LocalCandidate,
    index: Int,
    count: Int,
    selected: Boolean,
    current: Boolean,
    onSelect: () -> Unit,
) {
    val song = candidate.song
    val scheme = MaterialTheme.colorScheme
    val shape = segmentShape(index, count)
    val detail = listOf(song.artist, song.album, formatDuration(song.duration)).filter { it.isNotBlank() }.joinToString(" · ")
    val score = percent(candidate.score)
    val spoken = listOf(
        song.title,
        detail,
        providerName(emptyList(), candidate.providerId),
        "相似度 $score",
        if (current) "当前匹配" else "",
    ).filter { it.isNotBlank() }.joinToString("，")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .tvFocusRing(shape)
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow, shape)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .semantics { contentDescription = spoken }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.clearAndSetSemantics {}) { Cover(song.cover, song.title, 44.dp, corner = 10.dp) }
        Column(Modifier.weight(1f).clearAndSetSemantics {}) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(providerName(emptyList(), candidate.providerId), detail, if (current) "当前" else "").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            score,
            Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (candidate.score >= 0.85f) scheme.primary else scheme.onSurfaceVariant,
        )
        RadioButton(selected = selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
    }
}
