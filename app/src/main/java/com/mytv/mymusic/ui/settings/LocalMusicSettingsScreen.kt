package com.mytv.mymusic.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytv.mymusic.data.local.LOCAL_ORIGIN_MEDIA
import com.mytv.mymusic.data.local.LocalTitlePolicy
import com.mytv.mymusic.ui.InfoNote
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.ToneIcon
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.local.rememberLocalAccess
import com.mytv.mymusic.ui.local.rememberLocalLibrary

private val LocalTitlePolicy.label: String
    get() = when (this) {
        LocalTitlePolicy.File -> "文件"
        LocalTitlePolicy.FillGaps -> "只补空缺"
        LocalTitlePolicy.Match -> "匹配结果"
    }

/** 设置首页“本地音乐”那一行的当前值。 */
@Composable
internal fun rememberLocalSettingsSummary(): String {
    val library = rememberLocalLibrary() ?: return ""
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val index by library.index.collectAsStateWithLifecycle()
    val sources = buildList {
        if (prefs.mediaStore && library.hasAudioPermission()) add("系统媒体库")
        if (prefs.folders.isNotEmpty()) add("${prefs.folders.size} 个文件夹")
    }
    return when {
        sources.isEmpty() -> "还没开始扫描"
        else -> "${index.items.size} 首 · ${sources.joinToString("、")}" + if (prefs.autoMatch) " · 自动匹配" else ""
    }
}

/**
 * 设置 › 本地音乐：扫描范围（系统媒体库、文件夹、跳过短音频）、封面和歌词（自动匹配、标题和歌手、同名 .lrc）、
 * 播放（没有文件时用平台版本）。上传那一组等云盘接进来再加。全是现有的设置行。
 */
@Composable
internal fun LocalMusicSettingsScreen(onBack: () -> Unit, embedded: Boolean) {
    val library = rememberLocalLibrary() ?: return
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val index by library.index.collectAsStateWithLifecycle()
    val access = rememberLocalAccess(library, prefs)
    val first = remember { FocusRequester() }
    SettingsTvFocus(first)
    val perOrigin = remember(index) { index.items.groupingBy { it.row.origin }.eachCount() }
    val folders = prefs.folders
    val scanCount = 1 + folders.size + (if (access.canPickFolder) 1 else 0) + 1
    SettingsPage(SettingsSection.LocalMusic, embedded, onBack) {
        item(key = "scanHead", contentType = "sectionHeader") { SectionLabel("扫描") }
        item(key = "media", contentType = "switch") {
            val on = prefs.mediaStore && access.hasPermission
            SwitchRow(
                title = "系统媒体库",
                checked = on,
                onCheckedChange = { checked ->
                    // 打开时还没有权限就先要权限；权限结果回来会自动扫描
                    if (checked && !access.hasPermission) access.requestPermission()
                    library.launch { setMediaStore(checked) }
                },
                index = 0,
                count = scanCount,
                modifier = Modifier.focusRequester(first),
                summary = when {
                    !access.hasPermission -> "手机里所有音频；需要“音乐和音频”权限"
                    on -> "手机里所有音频 · ${perOrigin[LOCAL_ORIGIN_MEDIA] ?: 0} 首"
                    else -> "手机里所有音频"
                },
                icon = Icons.Filled.LibraryMusic,
            )
        }
        folders.forEachIndexed { at, folder ->
            item(key = "folder:${folder.uri}", contentType = "folder") {
                ListRow(
                    title = folder.name,
                    index = 1 + at,
                    count = scanCount,
                    onClick = null,
                    icon = Icons.Filled.Folder,
                    summary = "${perOrigin[folder.uri] ?: 0} 首",
                    showChevron = false,
                    trailing = {
                        IconButton(
                            onClick = { library.launch { removeFolder(folder.uri) } },
                            modifier = Modifier.tvFocusRing(CircleShape),
                        ) {
                            Icon(Icons.Filled.RemoveCircleOutline, "移除文件夹“${folder.name}”", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
            }
        }
        // 电视上通常没有系统文件夹选择器，检测不到就不显示
        if (access.canPickFolder) {
            item(key = "addFolder", contentType = "row") {
                ListRow(
                    title = "添加文件夹",
                    index = 1 + folders.size,
                    count = scanCount,
                    onClick = access.pickFolder,
                    icon = Icons.Filled.CreateNewFolder,
                    summary = "SD 卡、U 盘或媒体库没收录的目录",
                )
            }
        }
        item(key = "skipShort", contentType = "switch") {
            SwitchRow(
                title = "跳过 30 秒以下的音频",
                checked = prefs.skipShort,
                onCheckedChange = { value -> library.launch { updatePrefs { it.copy(skipShort = value) } } },
                index = scanCount - 1,
                count = scanCount,
                summary = "铃声、录音、提示音",
                icon = Icons.Filled.Timer,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            item(key = "lrcNote", contentType = "note") {
                SettingsNote("Android 11 起，系统媒体库里的歌读不到旁边的同名 .lrc；想用自己的 .lrc，把歌所在的文件夹加进来。")
            }
        }

        item(key = "matchHead", contentType = "sectionHeader") { SectionLabel("封面和歌词") }
        item(key = "autoMatch", contentType = "switch") {
            SwitchRow(
                title = "自动匹配",
                checked = prefs.autoMatch,
                onCheckedChange = { value -> library.launch { setAutoMatch(value) } },
                index = 0,
                count = 3,
                summary = "先按标签搜，再用 3 秒听歌识曲；文件不改",
                icon = Icons.Filled.AutoFixHigh,
            )
        }
        item(key = "titlePolicy", contentType = "toggleRow") {
            TitlePolicyRow(prefs.titlePolicy) { value -> library.launch { updatePrefs { it.copy(titlePolicy = value) } } }
        }
        item(key = "preferLrc", contentType = "switch") {
            SwitchRow(
                title = "同名 .lrc 优先",
                checked = prefs.preferLrc,
                onCheckedChange = { value -> library.launch { updatePrefs { it.copy(preferLrc = value) } } },
                index = 2,
                count = 3,
                summary = "歌曲旁边有同名歌词文件时用它",
                icon = Icons.Filled.Lyrics,
            )
        }

        item(key = "playHead", contentType = "sectionHeader") { SectionLabel("播放") }
        item(key = "fallback", contentType = "switch") {
            SwitchRow(
                title = "没有文件时用平台版本",
                checked = prefs.platformFallback,
                onCheckedChange = { value -> library.launch { updatePrefs { it.copy(platformFallback = value) } } },
                index = 0,
                count = 1,
                summary = "其它设备上的本地歌没上传时，用匹配到的那首播放",
                icon = Icons.Filled.SwapHoriz,
            )
        }
        item(key = "privacy", contentType = "note") {
            Box(Modifier.padding(top = 12.dp)) {
                InfoNote(
                    "扫描只读取文件信息和标签，不改文件，也不会上传。自动匹配时把歌名和歌手发给已启用的平台搜索；" +
                        "标签不够时在本机把 3 秒片段算成指纹，只把指纹发给网易云识曲，不发音频。",
                )
            }
        }
    }
}

/** “标题和歌手”：分段列表里的一行，下面挂三段连接按钮组（文件 / 只补空缺 / 匹配结果）。 */
@Composable
private fun TitlePolicyRow(selected: LocalTitlePolicy, onSelect: (LocalTitlePolicy) -> Unit) {
    val shape = segmentShape(1, 3)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToneIcon(Icons.Filled.Title, IconTone.Secondary)
            Column(Modifier.padding(start = 14.dp).semantics(mergeDescendants = true) {}) {
                Text("标题和歌手", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "文件标签和匹配结果不一致时显示哪个",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ConnectedToggleGroup(
            options = LocalTitlePolicy.entries,
            selected = selected,
            onSelect = onSelect,
            label = { it.label },
            modifier = Modifier.padding(top = 12.dp).widthIn(max = 480.dp).fillMaxWidth(),
        )
        Text(
            when (selected) {
                LocalTitlePolicy.File -> "只用文件自己的标签，没有标题时显示文件名。"
                LocalTitlePolicy.FillGaps -> "标签是空的或“Track 01 / 未知艺术家”这类占位时用匹配结果，像样的标签不覆盖。"
                LocalTitlePolicy.Match -> "匹配上了就整套用匹配结果的标题、歌手和专辑。"
            },
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
