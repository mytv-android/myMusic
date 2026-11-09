package com.mytv.mymusic.ui.local

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.local.LOCAL_SOURCE
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.local.LocalCopyKind
import com.mytv.mymusic.data.local.LocalCopyState
import com.mytv.mymusic.data.local.matchTarget
import com.mytv.mymusic.data.local.LocalIndex
import com.mytv.mymusic.data.local.LocalLibrary
import com.mytv.mymusic.data.local.LocalMatchProgress
import com.mytv.mymusic.data.local.LocalScanPhase
import com.mytv.mymusic.data.local.LocalScanState
import com.mytv.mymusic.data.local.LocalUploadRules
import com.mytv.mymusic.data.local.LocalUploadState
import com.mytv.mymusic.ui.library.Tag
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.MaterialTheme
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.PlatformBadge
import com.mytv.mymusic.ui.providerMark
import com.mytv.mymusic.ui.providerName
import com.mytv.mymusic.ui.relativeTime

/** 本地音乐库是应用级的（扫描、匹配跟着进程走），界面直接从 Application 拿，和律动分析器一样。 */
@Composable
internal fun rememberLocalLibrary(): LocalLibrary? {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as? MyMusicApp)?.localLibrary }
}

/** 系统有没有文件夹选择器：电视上常常没有，没有就不显示“添加文件夹”。 */
@Composable
internal fun rememberCanPickFolder(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(context.packageManager) != null
        }.getOrDefault(false)
    }
}

/**
 * 「我的」那一行的说明：总数和已上传数（设计稿第 ① 张），扫描或匹配时带上进度；
 * 上传队列被风控 / 登录失效停下时写原因（设计稿第 09 节：“上传暂停：网易云要求重新验证”）。
 */
internal fun localEntrySummary(
    index: LocalIndex,
    scan: LocalScanState,
    matching: LocalMatchProgress,
    uploads: LocalUploadState = LocalUploadState(),
): String {
    val count = index.items.size
    uploads.paused?.let { (provider, reason) -> return LocalUploadRules.pausedText(provider, reason) }
    val uploaded = index.uploadedCount
    return when {
        scan.running && count == 0 -> "正在扫描…"
        scan.running -> "$count 首 · 扫描中…"
        matching.running -> "$count 首 · 正在匹配 ${matching.done}/${matching.total}"
        count == 0 -> "扫描手机里的歌，自动补封面和歌词"
        uploaded > 0 -> "$count 首 · 已上传 $uploaded"
        else -> "$count 首"
    }
}

/** 页头副标题：“N 首 · X 分钟前扫描”。 */
internal fun localPageSubtitle(index: LocalIndex, scan: LocalScanState, lastScanAt: Long): String {
    val count = index.items.size
    val scanned = when {
        scan.running -> "正在扫描"
        // “刚刚 / N 分钟前”直接接“扫描”；超过一周是“MM-dd”，中间留个空格
        lastScanAt > 0 -> relativeTime(lastScanAt).let {
            when {
                it.isBlank() -> ""
                it.last().isDigit() -> "$it 扫描"
                else -> "${it}扫描"
            }
        }
        else -> ""
    }
    return listOf("$count 首", scanned).filter { it.isNotBlank() }.joinToString(" · ")
}

internal fun scanProgressLabel(scan: LocalScanState): String = when (scan.phase) {
    LocalScanPhase.Listing -> "正在扫描 · 列出文件"
    LocalScanPhase.Hashing -> "正在扫描 · 已找到 ${scan.found} 首"
    LocalScanPhase.Tags -> "正在读取标签和封面 · ${scan.done}/${scan.total}"
    LocalScanPhase.Idle -> ""
}

/**
 * 「我的」里的“本地音乐”行，和“汽水天天送会员”并成一组（[index] / [count] 决定圆角）。
 * 说明里写总数，扫描中带上进度。
 */
@Composable
internal fun LocalMusicEntryRow(onOpen: () -> Unit, index: Int, count: Int, modifier: Modifier = Modifier) {
    val library = rememberLocalLibrary()
    val uploadsHub = rememberLocalUploads()
    var progress: LocalUploadState? = null
    val summary = if (library == null) {
        ""
    } else {
        val local by library.index.collectAsStateWithLifecycle()
        val scan by library.scan.collectAsStateWithLifecycle()
        val matching by library.matching.collectAsStateWithLifecycle()
        val uploads = uploadsHub?.state?.collectAsStateWithLifecycle()?.value ?: LocalUploadState()
        progress = uploads
        localEntrySummary(local, scan, matching, uploads)
    }
    val busy = progress?.takeIf { it.busy && it.paused == null }
    ListRow(
        title = "本地音乐",
        index = index,
        count = count,
        onClick = onOpen,
        modifier = modifier,
        icon = Icons.Filled.AudioFile,
        iconTone = IconTone.Secondary,
        summary = summary,
        // 有上传任务时尾部挂进度标签“↑ 2 / 5”（设计稿第 ① 张）
        trailing = busy?.let { state ->
            {
                Tag(
                    "${state.current} / ${state.total}",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = Icons.Filled.CloudUpload,
                )
            }
        },
    )
}

/** 电视磁贴上那一行字。 */
@Composable
internal fun rememberLocalEntrySummary(): String {
    val library = rememberLocalLibrary() ?: return ""
    val uploadsHub = rememberLocalUploads()
    val local by library.index.collectAsStateWithLifecycle()
    val scan by library.scan.collectAsStateWithLifecycle()
    val matching by library.matching.collectAsStateWithLifecycle()
    val uploads = uploadsHub?.state?.collectAsStateWithLifecycle()?.value ?: LocalUploadState()
    val summary = localEntrySummary(local, scan, matching, uploads)
    return if (uploads.busy && uploads.paused == null) "$summary · 正在上传 ${uploads.current}/${uploads.total}" else summary
}

/**
 * 歌单、收藏、最近播放里的本地歌：这台设备上有文件时换成本地音乐库现在的显示信息（内嵌封面、匹配后的标题），
 * 没有文件但有匹配（同步来的，或在这台设备上换的）时补上匹配到的封面。非本地歌直接返回。
 */
@Composable
internal fun rememberLocalDisplay(): (Song) -> Song {
    val library = rememberLocalLibrary() ?: return { it }
    val local by library.index.collectAsStateWithLifecycle()
    return remember(local) {
        { song ->
            when {
                song.source != LOCAL_SOURCE -> song
                else -> local.byId[song.id]?.song ?: run {
                    val cover = local.matchOf(song.id)?.cover.orEmpty()
                    if (cover.isNotBlank() && cover != song.cover) song.copy(cover = cover) else song
                }
            }
        }
    }
}

/** 本地歌在这台设备上会放哪一份，画徽标、决定点按行为用。非本地歌返回 null。 */
@Composable
internal fun rememberLocalCopies(): (Song) -> LocalCopyState? {
    val library = rememberLocalLibrary() ?: return { null }
    val local by library.index.collectAsStateWithLifecycle()
    return remember(local) {
        { song -> if (song.source != LOCAL_SOURCE) null else local.copyState(song.id) }
    }
}

/** 徽标上的字：本机 / 网易云盘 / WebDAV / 平台简称 / 本地（设计稿第 ⑨ 张）。 */
internal fun localCopyMark(state: LocalCopyState): String = when (state.kind) {
    LocalCopyKind.Device -> "本机"
    LocalCopyKind.Cloud, LocalCopyKind.CloudTrouble -> state.provider?.label ?: "云端"
    LocalCopyKind.Platform -> providerMark(providerName(emptyList(), state.platform))
    LocalCopyKind.None -> "本地"
}

/**
 * 句子里夹的云端名。WebDAV、QQ 这类西文开头 / 结尾的名字和汉字之间留一个空格，
 * 和全应用“已同步到 WebDAV”的写法一致；纯中文名（网易云盘）照写。在句首用时自己 trimStart。
 */
internal fun cloudInText(provider: CloudProvider?): String {
    val label = provider?.label ?: "云端"
    val ascii = { ch: Char -> ch.code < 128 && ch.isLetterOrDigit() }
    return buildString {
        if (ascii(label.first())) append(' ')
        append(label)
        if (ascii(label.last())) append(' ')
    }
}

/** 读屏念的那一句。 */
internal fun localCopySpoken(state: LocalCopyState): String = when (state.kind) {
    LocalCopyKind.Device -> "本机文件"
    LocalCopyKind.Cloud -> "从${cloudInText(state.provider)}播放"
    LocalCopyKind.Platform -> "用${providerName(emptyList(), state.platform)}的版本播放"
    LocalCopyKind.CloudTrouble -> "${state.provider?.label ?: "云端"}：${state.note}"
    LocalCopyKind.None -> state.note
}

/**
 * 本地歌的徽标，照 PlatformBadge 的三种字形画：实心点是现在会放的那份，空心点是云端暂时取不到
 * （连不上、要重新登录），删除线是放不了。读屏合并成一句。
 */
@Composable
internal fun LocalSongBadge(state: LocalCopyState) {
    val spoken = localCopySpoken(state)
    Box(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
        PlatformBadge(localCopyMark(state), state.status)
    }
}

/**
 * 本地歌在歌单里的副标题：本机、云端副本照常写歌手；平台版本写“用网易云音乐的版本”，
 * 云端出问题、哪里都没有时写原因（“云端暂时连不上”“只在 Pixel 8 上”）。
 */
internal fun localSubtitle(song: Song, state: LocalCopyState?): String = when (state?.kind) {
    null, LocalCopyKind.Device, LocalCopyKind.Cloud -> song.subtitle
    LocalCopyKind.Platform -> "用${providerName(emptyList(), state.platform)}的版本"
    LocalCopyKind.CloudTrouble, LocalCopyKind.None -> state.note
}

/**
 * 歌单顶上的提示（设计稿第 ⑦ 张）：这台设备上放不了的本地歌有几首、在哪台设备上。没有就返回空串。
 */
internal fun localElsewhereNote(states: List<LocalCopyState>): String {
    val gone = states.filter { it.kind == LocalCopyKind.None }
    if (gone.isEmpty()) return ""
    val devices = gone.map { it.device }.distinct()
    val where = when {
        devices.size == 1 && devices.first().isNotBlank() -> "只在 ${devices.first()} 上"
        devices.all { it.isBlank() } -> "只在原来的设备上"
        else -> "只在其他设备上"
    }
    return "有 ${gone.size} 首$where。在那台设备上传到云盘后，这里就能播。"
}

/**
 * 点了放不了的本地歌（设计稿第 ⑧ 张）：不弹错误、不去播放，打开“只在 X 上”面板；
 * 面板里“找一首平台版本”接着打开换一个匹配。歌单、收藏、最近播放、队列、本地音乐页共用。
 */
@Stable
internal class LocalUnplayableHost {
    var target by mutableStateOf<Song?>(null)
    var rematch by mutableStateOf<Song?>(null)

    /** 放不了就打开面板并返回 true，调用方不再去播放。 */
    fun intercept(song: Song, state: LocalCopyState?): Boolean {
        if (state == null || !state.blocked) return false
        target = song
        return true
    }
}

@Composable
internal fun rememberLocalUnplayableHost(): LocalUnplayableHost = remember { LocalUnplayableHost() }

/**
 * 画 [host] 打开的面板。[removeLabel] / [removeSummary] / [onRemove] 是第二个动作，按所在页面给：
 * 歌单里是“从歌单移除 · 所有设备上都会移除”，收藏里是“取消收藏”；没有可移除的地方（队列、最近播放）就不给。
 */
@Composable
internal fun LocalUnplayableSheets(
    host: LocalUnplayableHost,
    removeLabel: String = "",
    removeSummary: String = "",
    onRemove: ((Song) -> Unit)? = null,
) {
    val library = rememberLocalLibrary() ?: return
    host.target?.let { song ->
        LocalUnplayableSheet(
            song = song,
            library = library,
            removeLabel = removeLabel,
            removeSummary = removeSummary,
            onRemove = onRemove?.let { remove -> { remove(song); host.target = null } },
            onFindPlatform = {
                host.target = null
                host.rematch = song
            },
            onDismiss = { host.target = null },
        )
    }
    host.rematch?.let { song ->
        val index by library.index.collectAsStateWithLifecycle()
        LocalRematchSheet(target = index.matchTarget(song), library = library, onDismiss = { host.rematch = null })
    }
}
