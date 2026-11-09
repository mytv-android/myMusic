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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudRefStatus
import com.mytv.mymusic.data.local.LocalCandidate
import com.mytv.mymusic.data.local.LocalCopyKind
import com.mytv.mymusic.data.local.LocalCopyRules
import com.mytv.mymusic.data.local.LocalMatchTarget
import com.mytv.mymusic.data.local.LocalRemoteEntity
import com.mytv.mymusic.data.local.matchTarget
import com.mytv.mymusic.data.sync.SyncTime
import com.mytv.mymusic.ui.ConfirmDialog
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
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.ProviderIcon
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
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
    val syncedMatch = match?.extra?.get(LocalLibrary.EXTRA_SYNCED_MATCH) == "1"
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
                // 别的设备同步来的匹配只知道平台和 ID，歌名是歌单里的那个，不能冒充平台上的歌名
                title = when {
                    match == null -> "没匹配"
                    syncedMatch -> "${providerName(emptyList(), row.matchProvider)}的版本"
                    else -> "${providerName(emptyList(), row.matchProvider)} · ${match.name}"
                },
                index = 0,
                count = 2,
                onClick = onRematch,
                modifier = Modifier.focusRequester(focus),
                leading = { Cover(match?.cover?.ifBlank { null } ?: song.cover, song.title, 40.dp, corner = 10.dp) },
                // 手动选的就是用户认定的那首（记满分，好在同步时盖过自动匹配），相似度没有意义，不写百分比
                summary = when {
                    syncedMatch -> "${matchMethodLabel(row.matchMethod)} · 在另一台设备上选的"
                    match != null && row.matchMethod != LocalMatchMethod.MANUAL && row.matchConfidence < 1f ->
                        "${matchMethodLabel(row.matchMethod)} · ${percent(row.matchConfidence)}"
                    else -> matchMethodLabel(row.matchMethod)
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

@Composable
internal fun LocalRematchSheet(item: LocalItem, library: LocalLibrary, onDismiss: () -> Unit) =
    LocalRematchSheet(item.matchTarget(), library, onDismiss)

/**
 * 换一个匹配：搜索框预填文件标签或文件名，按已启用的平台搜；百分比是现有换源匹配的打分。
 * “再听一次歌识曲”换一段（第 60 秒起）重新识别。“不匹配”清掉匹配，回到文件自己的标签和内嵌封面。
 * 只改应用里显示的封面、歌词和标题，文件不动。
 *
 * 这台设备上没有文件的歌（第 ⑧ 张“找一首平台版本”）也用它：按歌单里的歌名和同步来的文件名搜，
 * 没有识曲、没有“不匹配”；选好的匹配记进线索，随同步回到有文件的设备。
 */
@Composable
internal fun LocalRematchSheet(target: LocalMatchTarget, library: LocalLibrary, onDismiss: () -> Unit) {
    val localId = target.localId
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }
    TvDefaultFocus(searchFocus)
    var query by remember(localId) { mutableStateOf(LocalTags.rematchQuery(target.title, target.artist, target.fileName, target.match)) }
    var providers by remember { mutableStateOf<List<String>>(emptyList()) }
    var provider by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<LocalCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    // 现在的匹配放在第一位并默认选中；选别的才能“使用这个”
    val current = remember(target.match, target.matchProvider) {
        target.match?.let { LocalCandidate(it, target.matchProvider.ifBlank { it.source }, target.matchConfidence, target.matchMethod) }
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
        val providerId = provider ?: return
        val text = query
        run("正在搜索…") { library.search(localId, text, providerId, target.song) }
    }

    LaunchedEffect(localId) {
        providers = library.matcher.searchProviders()
        provider = providers.firstOrNull { it == target.matchProvider } ?: providers.firstOrNull()
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
            if (target.canListen) {
                Spacer(Modifier.size(8.dp))
                ListRow(
                    title = "再听一次歌识曲",
                    index = 0,
                    count = 1,
                    onClick = {
                        run("正在听…") { library.listenAgain(localId) }
                    },
                    icon = Icons.Filled.Hearing,
                    summary = "换一段（第 60 秒起）重新识别",
                    showChevron = false,
                )
            } else {
                Spacer(Modifier.size(8.dp))
                InfoNote("这台设备上没有这个文件：选好的匹配会同步到其他设备，有文件的那台也会用它的封面和歌词。")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (target.canReject) {
                OutlinedButton(
                    onClick = {
                        library.launch { rejectMatch(localId) }
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
                ) { Text("不匹配") }
            }
            val choice = shown.firstOrNull { it.song.key == picked }
            Button(
                onClick = {
                    if (choice != null) library.launch { applyMatch(localId, choice) }
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

/**
 * 点了放不了的本地歌（设计稿第 ⑧ 张）：标题写文件在哪（“只在 Pixel 8 上”），说明怎么才能在这里播，
 * 下面两条能做的事：找一首平台版本（匹配会同步回去）、从歌单移除（所有设备上都会移除）。不弹错误。
 */
@Composable
internal fun LocalUnplayableSheet(
    song: Song,
    library: LocalLibrary,
    removeLabel: String,
    removeSummary: String,
    onRemove: (() -> Unit)?,
    onFindPlatform: () -> Unit,
    onDismiss: () -> Unit,
) {
    val index by library.index.collectAsStateWithLifecycle()
    val state = index.copyState(song.id)
    val ref = index.refs[song.id]
    val row = index.byId[song.id]?.row
    val fileName = ref?.fileName.orEmpty().ifBlank { row?.displayName.orEmpty() }
        .ifBlank { song.extra[LocalLibrary.EXTRA_FILE_NAME].orEmpty() }
    val size = ref?.fileSize?.takeIf { it > 0 } ?: row?.size ?: 0L
    val match = index.matchOf(song.id)
    val matchText = when {
        match != null && !index.platformFallback -> "匹配到了${providerName(emptyList(), match.source)}的版本（「没有文件时用平台版本」关着）"
        match != null -> "匹配到了${providerName(emptyList(), match.source)}的版本"
        else -> "没有匹配到平台版本"
    }
    val device = state.device
    val explanation = when {
        state.kind == LocalCopyKind.CloudTrouble ->
            "${cloudInText(state.provider).trimStart()}里的副本现在放不了：${state.note}。在有文件的设备上重新上传，或者找一首平台版本。"
        row != null -> "这台设备上的文件${state.note.ifBlank { "不在了" }.removePrefix("文件")}。重新扫描能找回搬走的文件；歌单里的这首不受影响。"
        device.isNotBlank() -> "文件在 $device 的本地音乐里，还没有上传。在那台设备上打开「我的 › 本地音乐」上传它，这里会自动变成可以播放。"
        else -> "文件在原来那台设备的本地音乐里，这台设备上没有。在那台设备上传到云盘后，这里会自动变成可以播放。"
    }
    val focus = remember { FocusRequester() }
    TvDefaultFocus(focus)
    AdaptiveSheet(
        onDismiss = onDismiss,
        title = state.note.ifBlank { LocalCopyRules.elsewhere(device) },
        subtitle = listOf(
            fileName.ifBlank { song.title },
            if (size > 0) formatBytes(size) else "",
            matchText,
        ).filter { it.isNotBlank() }.joinToString(" · "),
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            InfoNote(explanation)
            Spacer(Modifier.size(8.dp))
            val count = if (onRemove != null) 2 else 1
            ListRow(
                title = "找一首平台版本",
                index = 0,
                count = count,
                onClick = onFindPlatform,
                modifier = Modifier.focusRequester(focus),
                icon = Icons.Filled.Search,
                summary = if (device.isNotBlank()) {
                    "按文件名在已启用的平台里搜，匹配会同步回 $device"
                } else {
                    "按文件名在已启用的平台里搜，匹配会同步到其他设备"
                },
            )
            if (onRemove != null) {
                ListRow(
                    title = removeLabel,
                    index = 1,
                    count = count,
                    onClick = onRemove,
                    icon = Icons.Filled.Delete,
                    iconTone = IconTone.Error,
                    danger = true,
                    summary = removeSummary,
                    showChevron = false,
                )
            }
        }
    }
}

/**
 * 歌曲信息里的「云端副本」段（设计稿第 ④ 张）：每个云端一行，写状态、大小、上传日期；⋮ 里是
 * “从网易云盘删除 / 从 WebDAV 删除”，删前确认。删云端只把这份标成已删除，不碰本机文件，也不动歌单。
 *
 * [onUpload] 留给上传面板（第 ⑥ 张）：传了就在还没传过的云端那行显示“上传”，现在没有上传就为空。
 * 没有任何副本、也没有上传入口时返回 null，整段不画。
 */
internal fun localCloudCopiesSection(
    localId: String,
    remotes: List<LocalRemoteEntity>,
    library: LocalLibrary,
    onUpload: ((CloudProvider) -> Unit)? = null,
): (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? {
    if (remotes.isEmpty() && onUpload == null) return null
    return { LocalCloudCopies(localId, library, onUpload) }
}

@Composable
private fun LocalCloudCopies(localId: String, library: LocalLibrary, onUpload: ((CloudProvider) -> Unit)?) {
    val index by library.index.collectAsStateWithLifecycle()
    val slots = index.remotes[localId].orEmpty()
    val providers = LocalCopyRules.CLOUD_ORDER.filter { provider ->
        slots.any { it.provider == provider.id } || (onUpload != null && provider.canUpload)
    }
    var confirm by remember { mutableStateOf<CloudProvider?>(null) }
    var error by remember { mutableStateOf("") }
    providers.forEachIndexed { at, provider ->
        val slot = slots.firstOrNull { it.provider == provider.id }
        var menu by remember(provider) { mutableStateOf(false) }
        ListRow(
            title = provider.label,
            index = at,
            count = providers.size,
            onClick = null,
            // 设计稿第 ④ 张：行首是云端的平台头像（网易云盘 / QQ 云盘用平台图标）；WebDAV 没有平台图标，用云
            icon = if (provider == CloudProvider.WebDav) Icons.Filled.Cloud else null,
            leading = if (provider == CloudProvider.WebDav) null else {
                { ProviderIcon(id = provider.id, name = provider.label, size = 40.dp) }
            },
            summary = if (slot == null) "还没上传" else cloudCopySummary(slot, provider, index.clouds),
            showChevron = false,
            trailing = {
                when {
                    slot == null && onUpload != null -> TextButton(
                        onClick = { onUpload(provider) },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                    ) { Text("上传") }
                    slot != null && slot.status != CloudRefStatus.DELETED -> Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.tvFocusRing(androidx.compose.foundation.shape.CircleShape)) {
                            Icon(Icons.Filled.MoreVert, "${cloudInText(provider).trimStart()}副本的更多操作")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("从${cloudInText(provider)}删除", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    confirm = provider
                                },
                            )
                        }
                    }
                }
            },
        )
    }
    if (error.isNotBlank()) InfoNote(error, "red")
    confirm?.let { provider ->
        ConfirmDialog(
            title = "从${cloudInText(provider)}删除？",
            message = "只删云端这一份。本机文件和歌单里的这首都不受影响；其他设备会改用本机文件或平台版本播放。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                error = ""
                library.launch {
                    try {
                        deleteRemote(localId, provider)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        error = failure.message?.takeIf { it.isNotBlank() } ?: "删除失败"
                    }
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

/** “可以播放 · 31 MB · 11 月 8 日上传”；放不了时写原因。 */
internal fun cloudCopySummary(slot: LocalRemoteEntity, provider: CloudProvider, configured: Set<CloudProvider>): String {
    val status = when {
        slot.status == CloudRefStatus.DELETED -> "已从云端删除"
        else -> LocalCopyRules.cloudProblem(slot, provider, configured, System.currentTimeMillis())?.first ?: "可以播放"
    }
    val uploaded = SyncTime.toMillis(slot.uploadedAt).takeIf { it > 0 }?.let {
        SimpleDateFormat("M 月 d 日", Locale.CHINA).format(Date(it)) + "上传"
    }.orEmpty()
    return listOf(status, if (slot.fileSize > 0) formatBytes(slot.fileSize) else "", uploaded)
        .filter { it.isNotBlank() }.joinToString(" · ")
}
