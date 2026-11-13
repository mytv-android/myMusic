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
import com.mytv.mymusic.ui.local.rememberWebDavInfo
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudQuota
import com.mytv.mymusic.data.local.LocalUploadRules
import com.mytv.mymusic.data.sync.WebDavSyncProvider
import com.mytv.mymusic.data.webdav.WebDavAccountInfo
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.ProviderIcon
import com.mytv.mymusic.ui.components.SegmentGap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * 设置 › 本地音乐（设计稿第 ⑩ 张）：扫描范围（系统媒体库、文件夹、跳过短音频）、封面和歌词（自动匹配、
 * 标题和歌手、同名 .lrc）、上传（默认位置、WebDAV 服务器、加进歌单时提醒、只在 Wi-Fi 下）、
 * 播放（没有文件时用平台版本）。全是现有的设置行。
 *
 * [onOpenAccounts] 去平台账号登录网易云；[onConnectWebDav] 去配 WebDAV（同步在用 WebDAV 时是同步那张表单）。
 */
@Composable
internal fun LocalMusicSettingsScreen(
    onBack: () -> Unit,
    embedded: Boolean,
    onOpenAccounts: () -> Unit = {},
    onConnectWebDav: () -> Unit = {},
) {
    val library = rememberLocalLibrary() ?: return
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val index by library.index.collectAsStateWithLifecycle()
    val webDav = rememberWebDavInfo()
    val syncUsesWebDav = rememberSyncUsesWebDav()
    val target = LocalUploadRules.defaultTarget(prefs.uploadTarget, index.clouds)
    val neteaseQuota = rememberNeteaseQuota(CloudProvider.NeteaseCloud in index.clouds)
    var pickTarget by remember { mutableStateOf(false) }
    val access = rememberLocalAccess(library, prefs)
    val first = remember { FocusRequester() }
    if (!embedded) SettingsTvFocus(first)
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

        item(key = "uploadHead", contentType = "sectionHeader") { SectionLabel("上传") }
        item(key = "uploadTarget", contentType = "row") {
            val usable = target in index.clouds
            ListRow(
                title = "默认上传到",
                index = 0,
                count = 4,
                onClick = { pickTarget = true },
                leading = { UploadTargetIcon(target.takeIf { usable }) },
                summary = when {
                    !usable -> "还没有可用的位置"
                    target == CloudProvider.NeteaseCloud -> listOfNotNull(
                        target.label,
                        neteaseQuota?.let { "已用 ${CloudMessages.formatSize(it.used)} / ${CloudMessages.formatSize(it.total)}" },
                    ).joinToString(" · ")
                    else -> webDav?.let { "WebDAV · ${it.hostLabel}" } ?: "WebDAV"
                },
            )
        }
        item(key = "uploadWebDav", contentType = "row") {
            ListRow(
                title = "WebDAV 服务器",
                index = 1,
                count = 4,
                onClick = onConnectWebDav,
                icon = Icons.Filled.Cloud,
                summary = when {
                    webDav == null || !webDav.hasPassword -> "还没有配置 · 坚果云、Nextcloud、群晖或自己的服务器"
                    syncUsesWebDav -> "${webDav.hostLabel} · 和音乐库同步共用"
                    else -> "${webDav.hostLabel} · 放上传的歌"
                },
            )
        }
        item(key = "uploadPrompt", contentType = "switch") {
            SwitchRow(
                title = "加进歌单时提醒上传",
                checked = prefs.uploadPrompt,
                onCheckedChange = { value -> library.launch { updatePrefs { it.copy(uploadPrompt = value) } } },
                index = 2,
                count = 4,
                summary = "只在本机的歌加进已同步的歌单时提示",
                icon = Icons.Filled.NotificationsActive,
            )
        }
        item(key = "uploadWifi", contentType = "switch") {
            SwitchRow(
                title = "只在 Wi-Fi 下上传",
                checked = prefs.uploadWifiOnly,
                onCheckedChange = { value -> library.launch { updatePrefs { it.copy(uploadWifiOnly = value) } } },
                index = 3,
                count = 4,
                summary = "用移动网络时先排队",
                icon = Icons.Filled.Wifi,
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
                    "扫描只读取文件信息和标签，不改文件，也不会自己上传。自动匹配时把歌名和歌手发给已启用的平台搜索；" +
                        "标签不够时在本机把 3 秒片段算成指纹，只把指纹发给网易云识曲，不发音频。" +
                        "只有你点了上传，文件才会传到你选的网易云盘或 WebDAV。",
                )
            }
        }
    }
    if (pickTarget) {
        UploadTargetSheet(
            selected = target,
            configured = index.clouds,
            webDav = webDav,
            onSelect = { value ->
                pickTarget = false
                library.launch { updatePrefs { it.copy(uploadTarget = value.id) } }
            },
            onOpenAccounts = {
                pickTarget = false
                onOpenAccounts()
            },
            onConnectWebDav = {
                pickTarget = false
                onConnectWebDav()
            },
            onDismiss = { pickTarget = false },
        )
    }
}

/** 行首的位置图标：网易云盘用平台图标，WebDAV 用云，还没有可用的位置时是空心云。 */
@Composable
private fun UploadTargetIcon(target: CloudProvider?) {
    when (target) {
        null -> ToneIcon(Icons.Filled.CloudOff, IconTone.Secondary)
        CloudProvider.WebDav -> ToneIcon(Icons.Filled.Cloud, IconTone.Secondary)
        else -> ProviderIcon(id = "netease", name = target.label, size = 40.dp)
    }
}

/**
 * “默认上传到”的单选面板：网易云盘、WebDAV 能选；QQ 音乐云盘列出来但不可选（只能读取）。
 * 没登录 / 没配置的那行点了去登录 / 去连接（设计稿第 05 节“默认上传到”）。
 */
@Composable
private fun UploadTargetSheet(
    selected: CloudProvider,
    configured: Set<CloudProvider>,
    webDav: WebDavAccountInfo?,
    onSelect: (CloudProvider) -> Unit,
    onOpenAccounts: () -> Unit,
    onConnectWebDav: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    SettingsTvFocus(focus)
    AdaptiveSheet(onDismiss = onDismiss, title = "默认上传到", subtitle = "上传面板里也能临时换") {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            val rows = listOf(CloudProvider.NeteaseCloud, CloudProvider.WebDav, CloudProvider.QqCloud)
            rows.forEachIndexed { at, provider ->
                val ready = provider in configured
                val on = provider == selected && ready
                ListRow(
                    title = provider.label,
                    index = at,
                    count = rows.size,
                    onClick = when {
                        !provider.canUpload -> null
                        !ready && provider == CloudProvider.NeteaseCloud -> onOpenAccounts
                        !ready -> onConnectWebDav
                        else -> ({ onSelect(provider) })
                    },
                    modifier = (if (at == 0) Modifier.focusRequester(focus) else Modifier)
                        .then(if (provider.canUpload) Modifier else Modifier.alpha(0.6f))
                        .semantics {
                            role = Role.RadioButton
                            this.selected = on
                        },
                    summary = when {
                        !provider.canUpload -> "只能读取"
                        provider == CloudProvider.NeteaseCloud && !ready -> "还没登录网易云 · 点这里去登录"
                        provider == CloudProvider.WebDav && !ready -> "还没有配置 · 点这里连接 WebDAV"
                        provider == CloudProvider.WebDav -> webDav?.hostLabel.orEmpty()
                        else -> "曲库里有的歌直接秒传"
                    },
                    selected = on,
                    showChevron = false,
                    leading = {
                        if (provider == CloudProvider.WebDav) ToneIcon(Icons.Filled.Cloud, IconTone.Secondary)
                        else ProviderIcon(id = provider.id, name = provider.label, size = 40.dp, active = on)
                    },
                    trailing = if (on) ({ Icon(Icons.Filled.Check, null) }) else null,
                )
            }
        }
    }
}

/** 同步是不是正连着 WebDAV：是的话 WebDAV 服务器那行写“和音乐库同步共用”，点进去是同步那张表单。 */
@Composable
private fun rememberSyncUsesWebDav(): Boolean {
    val context = LocalContext.current
    val app = context.applicationContext as? MyMusicApp ?: return false
    val sync by app.sync.state.collectAsStateWithLifecycle()
    return sync.account?.provider == WebDavSyncProvider.ID
}

/** 网易云盘的已用 / 总容量：登录了才查一次（只读列表接口，顺带返回容量）；查不到就不写。 */
@Composable
private fun rememberNeteaseQuota(loggedIn: Boolean): CloudQuota? {
    val context = LocalContext.current
    var quota by remember { mutableStateOf<CloudQuota?>(null) }
    LaunchedEffect(loggedIn) {
        val app = context.applicationContext as? MyMusicApp ?: return@LaunchedEffect
        quota = if (!loggedIn) null else runCatching { withContext(Dispatchers.IO) { app.cloud.netease.quota() } }
            .getOrNull()?.takeIf { it.known }
    }
    return quota
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
