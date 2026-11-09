package com.mytv.mymusic.ui.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.cloud.CloudEntry
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudQuota
import com.mytv.mymusic.data.cloud.CloudRefStatus
import com.mytv.mymusic.data.local.LOCAL_SOURCE
import com.mytv.mymusic.data.local.LocalIndex
import com.mytv.mymusic.data.local.LocalLibrary
import com.mytv.mymusic.ui.ConfirmDialog
import com.mytv.mymusic.ui.EmptyState
import com.mytv.mymusic.ui.InfoNote
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.PlatformBadge
import com.mytv.mymusic.ui.components.ProviderIcon
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.formatDuration
import com.mytv.mymusic.ui.library.CenteredEmpty
import com.mytv.mymusic.ui.library.DayLabel
import com.mytv.mymusic.ui.library.Tag
import com.mytv.mymusic.ui.library.withGutter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 云端视图里某个云端的列表（分页）：网易云盘卡片和单独的列表页共用。 */
@Stable
internal class CloudListState(val provider: CloudProvider) {
    var entries by mutableStateOf<List<CloudEntry>>(emptyList())
    var quota by mutableStateOf<CloudQuota?>(null)
    var total by mutableIntStateOf(-1)
    var hasMore by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var error by mutableStateOf("")
    private var cursor: String? = null

    /** 读一页；[reset] 时从第一页重来（换了账号、删了一首之后）。 */
    suspend fun load(app: MyMusicApp, reset: Boolean) {
        if (loading) return
        loading = true
        error = ""
        try {
            val page = withContext(Dispatchers.IO) {
                val storage = app.cloud.storage(provider)
                    ?: throw CloudException(com.mytv.mymusic.data.cloud.CloudErrorKind.NotLoggedIn, CloudMessages.notLoggedIn(provider), provider)
                storage.list(if (reset) null else cursor)
            }
            entries = if (reset) page.entries else (entries + page.entries).distinctBy { it.id }
            cursor = page.nextCursor
            hasMore = page.hasMore
            if (page.total >= 0) total = page.total
            page.quota?.let { quota = it }
            loaded = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: CloudException) {
            error = failure.message
        } catch (failure: Exception) {
            error = CloudMessages.CLOUD_UNREACHABLE
        } finally {
            loading = false
        }
    }

    fun drop(entry: CloudEntry) {
        entries = entries.filterNot { it.id == entry.id }
        if (total > 0) total -= 1
    }
}

@Composable
internal fun rememberCloudList(provider: CloudProvider, configured: Boolean): CloudListState {
    val context = LocalContext.current
    val state = remember(provider) { CloudListState(provider) }
    LaunchedEffect(provider, configured) {
        val app = context.applicationContext as? MyMusicApp ?: return@LaunchedEffect
        if (configured) {
            state.load(app, reset = true)
        } else {
            state.entries = emptyList()
            state.loaded = false
        }
    }
    return state
}

/** 云端条目对上的本地身份：WebDAV 的文件名就是指纹；网易 / QQ 看这台设备记下的云端位置（同一个 ID）。 */
internal class CloudLinks(index: LocalIndex) {
    private val byRemote: Map<Pair<String, String>, String> = buildMap {
        index.remotes.forEach { (localId, slots) ->
            slots.forEach { slot ->
                put(slot.provider to slot.remoteId, localId)
                if (slot.cloudSongId.isNotBlank()) put(slot.provider to "song:${slot.cloudSongId}", localId)
            }
        }
    }

    fun localId(entry: CloudEntry): String? =
        entry.localId.takeIf { it.isNotBlank() }
            ?: byRemote[entry.provider to entry.id]
            ?: entry.cloudSongId.takeIf { it.isNotBlank() }?.let { byRemote[entry.provider to "song:$it"] }
}

/**
 * 云端条目显示的歌名。WebDAV 上的文件按内容身份命名（一串十六进制），不能直接给人看：对上了本地歌用本地歌的名字，
 * 这台设备上没有这首时用上传时记下的原文件名（云端副本或同步来的线索里有），都没有才用云端给的标题 / 文件名。
 *
 * 别的设备传上去、又没加进任何同步歌单的歌，这台设备上一点线索都没有（同步文件只带歌单里的歌，WebDAV 上只有文件），
 * 这时写“未命名”加指纹前 8 位：64 位十六进制占满一行谁也认不出，前 8 位足够把几首未命名的区分开。
 */
internal fun cloudEntryName(entry: CloudEntry, localId: String?, index: LocalIndex): String {
    if (localId != null) {
        index.byId[localId]?.let { return it.song.name }
        if (entry.title.isNotBlank()) return entry.title
        val original = index.remotes[localId].orEmpty().firstOrNull { it.fileName.isNotBlank() }?.fileName
            ?: index.refs[localId]?.fileName?.takeIf { it.isNotBlank() }
        if (original != null) return original.substringBeforeLast('.')
        val base = entry.fileName.substringBefore('.')
        if (localId.endsWith(base, ignoreCase = true) && base.length >= 32) return "未命名 · ${base.take(8)}"
    }
    return entry.title.ifBlank { entry.fileName.substringBeforeLast('.') }
}

/** 云端条目在这台设备上怎么放：对上了本地身份就按本地歌放（本机文件 → 云端副本 → 平台版本）；匹配上了就放平台那首。 */
internal fun cloudEntrySong(entry: CloudEntry, localId: String?, index: LocalIndex): Song? {
    if (localId != null) {
        index.byId[localId]?.let { return it.song }
        return Song(
            id = localId,
            source = LOCAL_SOURCE,
            name = cloudEntryName(entry, localId, index),
            artist = entry.artist,
            album = entry.album,
            duration = entry.durationSec,
            cover = entry.cover,
            // “文件名”给匹配时猜歌名用：用原文件名，不用 WebDAV 上的哈希名
            extra = mapOf(
                LocalLibrary.EXTRA_FILE_NAME to (
                    index.remotes[localId].orEmpty().firstOrNull { it.fileName.isNotBlank() }?.fileName
                        ?: index.refs[localId]?.fileName?.takeIf { it.isNotBlank() }
                        ?: entry.fileName
                    ),
            ),
        )
    }
    val match = entry.match ?: return null
    return Song(
        id = match.playableId,
        source = match.provider,
        name = entry.title.ifBlank { entry.fileName.substringBeforeLast('.') },
        artist = entry.artist,
        album = entry.album,
        duration = entry.durationSec,
        cover = entry.cover,
        extra = if (match.mid.isNotBlank()) mapOf("songmid" to match.mid) else emptyMap(),
    )
}

/**
 * 云端视图（设计稿第 ③ 张）：网易云盘一张卡（容量条、多少首这台手机上没有文件），WebDAV 和 QQ 云盘各一行
 * （点开是各自的列表），下面列“网易云盘里，本机没有的”。没登录 / 没配置时引导去平台账号或连接 WebDAV。
 */
internal fun LazyListScope.localCloudHeader(
    index: LocalIndex,
    netease: CloudListState,
    neteaseName: String,
    webDavLabel: String?,
    onOpenAccounts: () -> Unit,
    onConnectWebDav: () -> Unit,
    onOpenList: (CloudProvider) -> Unit,
) {
    item(key = "cloud:netease", contentType = "card") {
        NeteaseCloudCard(index, netease, neteaseName, onOpenAccounts)
    }
    item(key = "cloud:others", contentType = "rows") {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
            val webDavReady = CloudProvider.WebDav in index.clouds
            val webDavSlots = index.remotes.values.flatten().filter { it.provider == CloudProvider.WebDav.id && it.status == CloudRefStatus.READY }
            ListRow(
                title = "WebDAV",
                index = 0,
                count = 2,
                onClick = if (webDavReady) ({ onOpenList(CloudProvider.WebDav) }) else onConnectWebDav,
                icon = Icons.Filled.Cloud,
                summary = when {
                    !webDavReady -> "还没有配置 · 点这里连接"
                    else -> listOfNotNull(
                        webDavLabel,
                        "${webDavSlots.size} 首",
                        webDavSlots.sumOf { it.fileSize }.takeIf { it > 0 }?.let(CloudMessages::formatSize),
                    ).joinToString(" · ")
                },
            )
            val qqReady = CloudProvider.QqCloud in index.clouds
            ListRow(
                title = CloudProvider.QqCloud.label,
                index = 1,
                count = 2,
                onClick = if (qqReady) ({ onOpenList(CloudProvider.QqCloud) }) else onOpenAccounts,
                leading = { ProviderIcon(id = "qq", name = CloudProvider.QqCloud.label, size = 40.dp) },
                summary = if (qqReady) "只能读取，不能上传" else "还没登录 QQ 音乐 · 点这里去登录",
                trailing = if (qqReady) {
                    { Tag("只读", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    null
                },
            )
        }
    }
}

/** “网易云盘里，本机没有的”：卡片下面的列表，分页读，最后一行“加载更多”。 */
internal fun LazyListScope.localCloudNeteaseList(
    index: LocalIndex,
    netease: CloudListState,
    links: CloudLinks,
    playing: String?,
    onPlay: (CloudEntry, String?) -> Unit,
    onDelete: (CloudEntry, String?) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (CloudProvider.NeteaseCloud !in index.clouds) return
    val absent = netease.entries.filter { entry -> links.localId(entry)?.let { index.byId[it]?.onDevice } != true }
    if (absent.isEmpty() && !netease.hasMore) {
        if (netease.loaded) {
            item(key = "cloud:none", contentType = "empty") {
                Text(
                    if (netease.entries.isEmpty()) "网易云盘里还没有歌。" else "网易云盘里的歌这台设备上都有文件。",
                    Modifier.padding(start = 4.dp, top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    item(key = "cloud:absentHead", contentType = "header") { DayLabel("网易云盘里，本机没有的") }
    itemsIndexed(absent, key = { _, entry -> "cloud:n:${entry.id}" }, contentType = { _, _ -> "song" }) { position, entry ->
        val localId = links.localId(entry)
        CloudEntryRow(entry, localId, index, position, absent.size + if (netease.hasMore) 1 else 0, playing, onPlay, onDelete)
    }
    if (netease.hasMore) {
        item(key = "cloud:more", contentType = "more") {
            TextButton(
                onClick = onLoadMore,
                enabled = !netease.loading,
                modifier = Modifier.padding(top = 4.dp).tvFocusRing(RoundedCornerShape(20.dp)),
            ) { Text(if (netease.loading) "正在加载…" else "加载更多") }
        }
    }
}

@Composable
private fun NeteaseCloudCard(index: LocalIndex, netease: CloudListState, nickname: String, onOpenAccounts: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ready = CloudProvider.NeteaseCloud in index.clouds
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderIcon(id = "netease", name = CloudProvider.NeteaseCloud.label, size = 40.dp, active = ready)
                Column(Modifier.weight(1f)) {
                    Text(CloudProvider.NeteaseCloud.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (ready) listOf("已登录", nickname).filter { it.isNotBlank() }.joinToString(" · ") else "还没登录网易云音乐",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (!ready) {
                Text(
                    "登录后，本地歌可以传到你自己的网易云盘，换设备也能播。",
                    Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Button(onClick = onOpenAccounts, modifier = Modifier.padding(top = 10.dp).tvFocusRing(RoundedCornerShape(24.dp))) {
                    Text("去平台账号登录")
                }
                return@Column
            }
            val quota = netease.quota?.takeIf { it.known }
            if (quota != null) {
                Text(
                    "${CloudMessages.formatSize(quota.used)} / ${CloudMessages.formatSize(quota.total)}",
                    Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
                LinearProgressIndicator(
                    progress = { (quota.used.toDouble() / quota.total).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                )
            }
            val total = netease.total
            // 这台设备上有文件的那几首：本机记下的网易云盘位置里，文件现在就在这台设备上的
            val here = index.remotes.count { (localId, slots) ->
                index.byId[localId]?.onDevice == true && slots.any { it.provider == CloudProvider.NeteaseCloud.id && it.status == CloudRefStatus.READY }
            }
            val summary = when {
                netease.error.isNotBlank() -> netease.error
                total < 0 && netease.loading -> "正在读取…"
                total < 0 -> ""
                total == 0 -> "还没有歌"
                else -> "$total 首，其中 ${(total - here).coerceAtLeast(0)} 首这台手机上没有文件"
            }
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (netease.error.isNotBlank()) scheme.error else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 云端列表里的一行：徽标写云端和能不能放；QQ 原文件、没匹配又对不上本地的画删除线、写原因。 */
@Composable
private fun CloudEntryRow(
    entry: CloudEntry,
    localId: String?,
    index: LocalIndex,
    position: Int,
    count: Int,
    playing: String?,
    onPlay: (CloudEntry, String?) -> Unit,
    onDelete: (CloudEntry, String?) -> Unit,
) {
    val provider = CloudProvider.of(entry.provider) ?: CloudProvider.NeteaseCloud
    val song = cloudEntrySong(entry, localId, index)
    val playable = entry.playable && song != null
    val reason = when {
        !entry.playable -> entry.unplayableReason.ifBlank { "原文件暂不支持播放" }
        song == null -> "没匹配上，暂时放不了"
        else -> ""
    }
    // 对上了这台设备上的本地歌：歌手、封面也用本地歌的（WebDAV 条目本身没有这些）
    val local = localId?.let { index.byId[it] }?.song
    val duration = (local?.duration ?: entry.durationSec).takeIf { reason.isBlank() && it > 0 }?.let(::formatDuration).orEmpty()
    val artist = local?.artist ?: entry.artist
    val subtitle = listOf(
        artist,
        reason,
        duration,
        // 歌手、时长都不知道（别的设备传的、这台设备没线索）：至少写文件多大，几首未命名的好区分
        entry.fileSize.takeIf { artist.isBlank() && duration.isBlank() && it > 0 }?.let(CloudMessages::formatSize).orEmpty(),
    ).filter { it.isNotBlank() }.joinToString(" · ")
    var menu by remember { mutableStateOf(false) }
    Box {
        SongRow(
            title = cloudEntryName(entry, localId, index),
            subtitle = subtitle,
            cover = local?.cover?.takeIf { it.isNotBlank() } ?: entry.cover,
            index = position,
            count = count,
            onClick = { if (playable) onPlay(entry, localId) },
            playing = song != null && playing == song.key,
            dimmed = !playable,
            badges = {
                PlatformBadge(
                    if (provider == CloudProvider.QqCloud) "QQ 云盘" else provider.label,
                    if (playable) Availability.Playable else Availability.Unavailable,
                )
            },
            onMore = { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            DropdownMenuItem(
                text = { Text("从${if (provider == CloudProvider.WebDav) " WebDAV " else provider.label}删除", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    onDelete(entry, localId)
                },
            )
        }
    }
}

/**
 * 云端条目的播放和删除，云端视图和列表页共用。
 * - 播放：对上本地身份的按本地歌放（WebDAV 上别的设备传的、这台设备还没记下位置的，先记下再放）；
 *   只匹配上的放平台那首；都不是的放不了。
 * - 删除：对上本地身份且这台设备记着这份云端副本时走 deleteRemote（标成已删除、同步出去）；否则只删云端文件。
 */
@Stable
internal class CloudEntryActions(
    private val app: MyMusicApp,
    private val library: LocalLibrary,
    private val actions: MusicActions,
) {
    var confirm by mutableStateOf<Triple<CloudEntry, String?, CloudListState>?>(null)
    var error by mutableStateOf("")

    fun play(entry: CloudEntry, localId: String?, index: LocalIndex) {
        val song = cloudEntrySong(entry, localId, index) ?: return
        if (localId == null || index.byId[localId] != null) {
            actions.play(song, listOf(song))
            return
        }
        val provider = CloudProvider.of(entry.provider) ?: return
        library.launch {
            // 本机还不知道这份副本在哪（别的设备传的、同步还没带过来）：先记下，解析链才找得到它
            if (index.remotes[localId].orEmpty().none { it.provider == provider.id && it.ready }) {
                recordRemote(localId, entry.toRef())
            }
            withContext(Dispatchers.Main) { actions.play(song, listOf(song)) }
        }
    }

    suspend fun delete(entry: CloudEntry, localId: String?, list: CloudListState, index: LocalIndex) {
        val provider = CloudProvider.of(entry.provider) ?: return
        error = ""
        try {
            val slot = localId?.let { id -> index.remotes[id].orEmpty().firstOrNull { it.provider == provider.id && it.status != CloudRefStatus.DELETED } }
            if (localId != null && slot != null) {
                library.deleteRemote(localId, provider)
            } else {
                withContext(Dispatchers.IO) {
                    val storage = app.cloud.storage(provider)
                        ?: throw CloudException(com.mytv.mymusic.data.cloud.CloudErrorKind.NotLoggedIn, CloudMessages.notLoggedIn(provider), provider)
                    storage.delete(entry.toRef())
                }
            }
            list.drop(entry)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message?.takeIf { it.isNotBlank() } ?: "删除失败"
        }
    }
}

@Composable
internal fun rememberCloudEntryActions(library: LocalLibrary, actions: MusicActions): CloudEntryActions? {
    val context = LocalContext.current
    val app = context.applicationContext as? MyMusicApp ?: return null
    return remember(app, library, actions) { CloudEntryActions(app, library, actions) }
}

/** 删云端条目前的确认（设计稿第 ④ 张同一句话）。 */
@Composable
internal fun CloudDeleteConfirm(entryActions: CloudEntryActions, index: LocalIndex) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalAppSnackbar.current
    val (entry, localId, list) = entryActions.confirm ?: return
    val provider = CloudProvider.of(entry.provider) ?: return
    val where = if (provider == CloudProvider.WebDav) " WebDAV " else provider.label
    ConfirmDialog(
        title = "从${where}删除「${cloudEntryName(entry, localId, index)}」？",
        message = if (localId != null) {
            "只删云端这一份。本机文件和歌单里的这首都不受影响；其他设备会改用本机文件或平台版本播放。"
        } else {
            "删掉后${where}里就没有这个文件了。这台设备上的本地音乐不受影响。"
        },
        confirmLabel = "删除",
        danger = true,
        onConfirm = {
            scope.launch {
                entryActions.delete(entry, localId, list, index)
                if (entryActions.error.isNotBlank()) snackbar.showSnackbar(entryActions.error)
            }
        },
        onDismiss = { entryActions.confirm = null },
    )
}

/**
 * WebDAV / QQ 音乐云盘 / 网易云盘的完整列表（从云端视图那一行点进来）。QQ 云盘只读：
 * 没被 QQ 匹配上的原文件画删除线、写原因；匹配上的走现有取流放 QQ 那首。
 */
@Composable
fun LocalCloudListScreen(
    providerId: String,
    actions: MusicActions,
    playingKey: String?,
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onConnectWebDav: () -> Unit,
) {
    val provider = CloudProvider.of(providerId) ?: return
    val library = rememberLocalLibrary() ?: return
    val window = LocalAppWindow.current
    val index by library.index.collectAsStateWithLifecycle()
    val webDav = rememberWebDavInfo()
    val configured = provider in index.clouds
    val list = rememberCloudList(provider, configured)
    val links = remember(index) { CloudLinks(index) }
    val entryActions = rememberCloudEntryActions(library, actions) ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    PageScaffold(
        title = provider.label,
        subtitle = when {
            !configured -> ""
            provider == CloudProvider.WebDav -> webDav?.let(::webDavTargetLabel).orEmpty()
            provider == CloudProvider.QqCloud -> "只能读取，不能上传" + if (list.total >= 0) " · ${list.total} 首" else ""
            list.total >= 0 -> "${list.total} 首"
            else -> ""
        },
        onBack = onBack,
    ) { padding ->
        when {
            !configured -> CenteredEmpty(padding) {
                EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = if (provider == CloudProvider.WebDav) "还没有配置 WebDAV" else "还没登录",
                    message = when (provider) {
                        CloudProvider.WebDav -> "连接自己的 WebDAV 服务器后，本地歌可以传上去，换设备也能播。"
                        CloudProvider.QqCloud -> "登录 QQ 音乐后可以看云盘里的歌。QQ 音乐云盘只能读取，不能上传。"
                        CloudProvider.NeteaseCloud -> "登录网易云音乐后可以看云盘里的歌、把本地歌传上去。"
                    },
                    actionLabel = if (provider == CloudProvider.WebDav) "连接 WebDAV" else "去平台账号登录",
                    onAction = if (provider == CloudProvider.WebDav) onConnectWebDav else onOpenAccounts,
                )
            }
            !list.loaded && list.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            list.loaded && list.entries.isEmpty() -> CenteredEmpty(padding) {
                EmptyState(
                    icon = Icons.Filled.Cloud,
                    title = "${provider.label}里还没有歌",
                    message = if (provider.canUpload) "在本地音乐里选歌上传，传完会出现在这里。" else "在 QQ 音乐里上传到云盘的歌会出现在这里。",
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = padding.withGutter(window.gutter),
                verticalArrangement = Arrangement.spacedBy(SegmentGap),
            ) {
                if (list.error.isNotBlank()) {
                    item(key = "error", contentType = "note") {
                        Column {
                            InfoNote(list.error, "red")
                            TextButton(
                                onClick = { (context.applicationContext as? MyMusicApp)?.let { scope.launch { list.load(it, reset = !list.loaded) } } },
                                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                            ) { Text("重试") }
                        }
                    }
                }
                itemsIndexed(list.entries, key = { _, entry -> "entry:${entry.id}" }, contentType = { _, _ -> "song" }) { position, entry ->
                    val localId = links.localId(entry)
                    CloudEntryRow(
                        entry = entry,
                        localId = localId,
                        index = index,
                        position = position,
                        count = list.entries.size,
                        playing = playingKey,
                        onPlay = { e, id -> entryActions.play(e, id, index) },
                        onDelete = { e, id -> entryActions.confirm = Triple(e, id, list) },
                    )
                }
                if (list.hasMore) {
                    item(key = "more", contentType = "more") {
                        TextButton(
                            onClick = { (context.applicationContext as? MyMusicApp)?.let { scope.launch { list.load(it, reset = false) } } },
                            enabled = !list.loading,
                            modifier = Modifier.padding(top = 4.dp).tvFocusRing(RoundedCornerShape(20.dp)),
                        ) { Text(if (list.loading) "正在加载…" else "加载更多") }
                    }
                }
            }
        }
    }
    CloudDeleteConfirm(entryActions, index)
}

/** 本地音乐页里的“本机 / 云端”两个视图。 */
internal enum class LocalView(val label: String) { Device("本机"), Cloud("云端") }

/** 页顶的连接按钮组，两个视图共用。 */
@Composable
internal fun LocalViewToggle(view: LocalView, onSelect: (LocalView) -> Unit, modifier: Modifier = Modifier) {
    com.mytv.mymusic.ui.components.ConnectedToggleGroup(
        options = LocalView.entries,
        selected = view,
        onSelect = onSelect,
        label = { it.label },
        modifier = modifier.fillMaxWidth(),
    )
}

/** 网易云盘卡片里那个昵称：平台账号资料的缓存里有就写。 */
@Composable
internal fun rememberNeteaseNickname(): String {
    val context = LocalContext.current
    val app = context.applicationContext as? MyMusicApp ?: return ""
    val profiles by remember(app) { app.accountProfiles.profiles }.collectAsStateWithLifecycle(emptyMap())
    return profiles["netease"]?.nickname.orEmpty()
}
