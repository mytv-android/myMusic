package com.mytv.mymusic.ui.local

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.local.LOCAL_SOURCE
import com.mytv.mymusic.data.local.LocalAvailability
import com.mytv.mymusic.data.local.LocalIndex
import com.mytv.mymusic.data.local.LocalLibrary
import com.mytv.mymusic.data.local.LocalMatchProgress
import com.mytv.mymusic.data.local.LocalScanPhase
import com.mytv.mymusic.data.local.LocalScanState
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.PlatformBadge
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

/** 「我的」那一行的说明：总数，扫描或匹配时带上进度。 */
internal fun localEntrySummary(index: LocalIndex, scan: LocalScanState, matching: LocalMatchProgress): String {
    val count = index.items.size
    return when {
        scan.running && count == 0 -> "正在扫描…"
        scan.running -> "$count 首 · 扫描中…"
        matching.running -> "$count 首 · 正在匹配 ${matching.done}/${matching.total}"
        count == 0 -> "扫描手机里的歌，自动补封面和歌词"
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
    val summary = if (library == null) {
        ""
    } else {
        val local by library.index.collectAsStateWithLifecycle()
        val scan by library.scan.collectAsStateWithLifecycle()
        val matching by library.matching.collectAsStateWithLifecycle()
        localEntrySummary(local, scan, matching)
    }
    ListRow(
        title = "本地音乐",
        index = index,
        count = count,
        onClick = onOpen,
        modifier = modifier,
        icon = Icons.Filled.AudioFile,
        iconTone = IconTone.Secondary,
        summary = summary,
    )
}

/** 电视磁贴上那一行字。 */
@Composable
internal fun rememberLocalEntrySummary(): String {
    val library = rememberLocalLibrary() ?: return ""
    val local by library.index.collectAsStateWithLifecycle()
    val scan by library.scan.collectAsStateWithLifecycle()
    val matching by library.matching.collectAsStateWithLifecycle()
    return localEntrySummary(local, scan, matching)
}

/**
 * 歌单、收藏、最近播放里的本地歌：这台设备上有文件时换成本地音乐库现在的显示信息（内嵌封面、匹配后的标题），
 * 没有就原样返回。非本地歌直接返回。
 */
@Composable
internal fun rememberLocalDisplay(): (Song) -> Song {
    val library = rememberLocalLibrary() ?: return { it }
    val local by library.index.collectAsStateWithLifecycle()
    return remember(local) {
        { song ->
            if (song.source != LOCAL_SOURCE) song else local.byId[song.id]?.song ?: song
        }
    }
}

/** 本地歌在这台设备上的状态，画徽标用。非本地歌返回 null。 */
@Composable
internal fun rememberLocalAvailability(): (Song) -> LocalAvailability? {
    val library = rememberLocalLibrary() ?: return { null }
    val local by library.index.collectAsStateWithLifecycle()
    return remember(local) {
        { song -> if (song.source != LOCAL_SOURCE) null else local.availability(song.id) }
    }
}

/**
 * 本地歌的徽标，照 PlatformBadge 画：本机有文件是实心点“本机”；没有文件是删除线“本地”。
 * 读屏合并成一句。
 */
@Composable
internal fun LocalSongBadge(availability: LocalAvailability) {
    val (mark, status, spoken) = when (availability) {
        LocalAvailability.OnDevice -> Triple("本机", Availability.Playable, "本机文件")
        LocalAvailability.Missing -> Triple("本机", Availability.Unavailable, "本机文件不在了")
        LocalAvailability.Elsewhere -> Triple("本地", Availability.Unavailable, "只在原来的设备上")
    }
    Box(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
        PlatformBadge(mark, status)
    }
}

/** 本地歌在歌单里的副标题：这台设备上没有文件时写明在哪儿，不只是灰掉。 */
internal fun localSubtitle(song: Song, availability: LocalAvailability?): String = when (availability) {
    LocalAvailability.Elsewhere -> listOf(song.artist, "只在原来的设备上").filter { it.isNotBlank() }.joinToString(" · ")
    LocalAvailability.Missing -> listOf(song.artist, "文件不在了").filter { it.isNotBlank() }.joinToString(" · ")
    else -> song.subtitle
}
