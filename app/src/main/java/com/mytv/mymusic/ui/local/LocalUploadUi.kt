package com.mytv.mymusic.ui.local

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudUploadStage
import com.mytv.mymusic.data.cloud.UploadPrecheck
import com.mytv.mymusic.data.local.LocalIndex
import com.mytv.mymusic.data.local.LocalUploadEntry
import com.mytv.mymusic.data.local.LocalUploadRequest
import com.mytv.mymusic.data.local.LocalUploadRules
import com.mytv.mymusic.data.local.LocalUploadState
import com.mytv.mymusic.data.local.LocalUploads
import com.mytv.mymusic.data.webdav.WebDavAccountInfo
import com.mytv.mymusic.ui.InfoNote
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.ProviderIcon
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.library.DayLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 上传队列是应用级的（后台任务、进程重启都靠它），界面直接从 Application 拿。 */
@Composable
internal fun rememberLocalUploads(): LocalUploads? {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as? MyMusicApp)?.localUploads }
}

/** 同步里那份 WebDAV 账号信息（不含密码），上传面板和设置里写服务器地址用。 */
@Composable
internal fun rememberWebDavInfo(): WebDavAccountInfo? {
    val context = LocalContext.current
    val store = remember(context) { (context.applicationContext as? MyMusicApp)?.sync?.webDavAccounts } ?: return null
    val info by store.info.collectAsStateWithLifecycle()
    return info
}

/** “dav.jianguoyun.com · myMusic/music”：上传的歌放在同步目录下的 music/ 里。 */
internal fun webDavTargetLabel(info: WebDavAccountInfo): String {
    val directory = info.directory.trim('/').let { if (it.isBlank()) "music" else "$it/music" }
    return "${info.hostLabel} · $directory"
}

/**
 * 全局的上传面板：本地音乐页（单曲、多选、“上传…”）、歌曲信息的云端副本、加进歌单后的提示都只是
 * [LocalUploads.openSheet]，面板挂在 AppNavigation 里，哪一页打开都是同一个。
 */
@Composable
fun LocalUploadSheetHost(onOpenAccounts: () -> Unit, onConnectWebDav: () -> Unit) {
    val uploads = rememberLocalUploads() ?: return
    val request by uploads.request.collectAsStateWithLifecycle()
    val current = request ?: return
    LocalUploadSheet(
        request = current,
        uploads = uploads,
        onOpenAccounts = {
            uploads.closeSheet()
            onOpenAccounts()
        },
        onConnectWebDav = {
            uploads.closeSheet()
            onConnectWebDav()
        },
        onDismiss = uploads::closeSheet,
    )
}

/** 预检的结果：还在查、查到了、查不了（没登录、连不上）。 */
private sealed interface CheckState {
    data object Loading : CheckState
    data class Done(val result: UploadPrecheck) : CheckState
    data class Failed(val message: String) : CheckState
}

/**
 * 上传面板（设计稿第 ⑥ 张）：选位置（网易云盘 / WebDAV；QQ 云盘列出来但不能选），只在 Wi-Fi 下上传，
 * 说明，开始上传。点开始前先按容量和单文件大小查一遍，不够就写在这里，不进队列。
 */
@Composable
private fun LocalUploadSheet(
    request: LocalUploadRequest,
    uploads: LocalUploads,
    onOpenAccounts: () -> Unit,
    onConnectWebDav: () -> Unit,
    onDismiss: () -> Unit,
) {
    val library = rememberLocalLibrary() ?: return
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val index by library.index.collectAsStateWithLifecycle()
    val prefs by library.prefs.collectAsStateWithLifecycle()
    val queue by uploads.state.collectAsStateWithLifecycle()
    val webDav = rememberWebDavInfo()
    val clouds = index.clouds
    var provider by remember(request) {
        mutableStateOf(request.provider?.takeIf { it.canUpload } ?: LocalUploadRules.defaultTarget(prefs.uploadTarget, clouds))
    }
    val checks = remember(request) { mutableStateMapOf<CloudProvider, CheckState>() }
    var starting by remember { mutableStateOf(false) }
    // 两个能上传的云端各查一次：没选中的那行也要写剩多少空间
    val targets = listOf(CloudProvider.NeteaseCloud, CloudProvider.WebDav)
    targets.forEach { target ->
        val configured = target in clouds
        val candidates = remember(request, configured, index.remotes) { uploads.plan(request.ids, target, request.force).upload }
        LaunchedEffect(request, target, configured, candidates) {
            if (!configured || candidates.isEmpty()) {
                checks.remove(target)
                return@LaunchedEffect
            }
            checks[target] = CheckState.Loading
            checks[target] = try {
                CheckState.Done(uploads.precheck(target, candidates))
            } catch (error: CancellationException) {
                throw error
            } catch (error: CloudException) {
                CheckState.Failed(error.message)
            } catch (error: Exception) {
                CheckState.Failed(CloudMessages.CLOUD_UNREACHABLE)
            }
        }
    }
    val plan = remember(request, provider, index, queue) { uploads.plan(request.ids, provider, request.force) }
    val check = checks[provider]
    val precheck = (check as? CheckState.Done)?.result
    val notice = LocalUploadRules.notice(provider, plan, precheck)
    val configured = provider in clouds
    val accepted = precheck?.accepted?.toSet()
    val toUpload = plan.upload.filter { accepted == null || it in accepted }
    val bytes = request.ids.distinct().sumOf { index.byId[it]?.row?.size ?: 0L }
    val canStart = configured && !starting && check is CheckState.Done && notice.blocking.isEmpty() && toUpload.isNotEmpty()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    AdaptiveSheet(
        onDismiss = onDismiss,
        title = if (request.force) "重新上传 ${request.ids.distinct().size} 首" else "上传 ${request.ids.distinct().size} 首",
        subtitle = "共 ${CloudMessages.formatSize(bytes)}，一首一首传，可以随时取消",
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            val rows = listOf(CloudProvider.NeteaseCloud, CloudProvider.WebDav, CloudProvider.QqCloud)
            rows.forEachIndexed { at, target ->
                val ready = target in clouds
                val selected = target == provider && target.canUpload
                val summary = when {
                    !target.canUpload -> CloudMessages.QQ_UPLOAD_UNSUPPORTED.removePrefix("QQ 音乐云盘")
                    target == CloudProvider.NeteaseCloud && !ready -> "还没登录网易云 · 点这里去登录"
                    target == CloudProvider.WebDav && !ready -> "还没有配置 · 点这里连接 WebDAV"
                    else -> targetSummary(target, checks[target], webDav)
                }
                ListRow(
                    title = target.label,
                    index = at,
                    count = rows.size,
                    onClick = when {
                        !target.canUpload -> null
                        !ready && target == CloudProvider.NeteaseCloud -> onOpenAccounts
                        !ready -> onConnectWebDav
                        else -> ({ provider = target })
                    },
                    // 单选列表：每行都读出选中 / 未选中；QQ 云盘只读，列出来但不可选
                    modifier = Modifier
                        .then(if (target.canUpload) Modifier else Modifier.alpha(0.6f))
                        .semantics {
                            role = Role.RadioButton
                            this.selected = selected
                        },
                    summary = summary,
                    selected = selected,
                    showChevron = false,
                    leading = {
                        if (target == CloudProvider.WebDav) {
                            Box(Modifier.size(36.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            ProviderIcon(id = target.id, name = target.label, size = 36.dp, active = selected)
                        }
                    },
                    trailing = when {
                        selected -> ({ Icon(Icons.Filled.Check, null) })
                        target.canUpload && !ready -> ({
                            Text(
                                if (target == CloudProvider.NeteaseCloud) "去登录" else "去连接",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        })
                        else -> null
                    },
                )
            }
            Box(Modifier.padding(top = 10.dp)) {
                SwitchRow(
                    title = "只在 Wi-Fi 下上传",
                    checked = prefs.uploadWifiOnly,
                    onCheckedChange = { value -> library.launch { updatePrefs { it.copy(uploadWifiOnly = value) } } },
                    index = 0,
                    count = 1,
                    summary = "用移动网络时先排队",
                    icon = Icons.Filled.Wifi,
                )
            }
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 一首都不会传（都传过了、都在排队、都没文件）时不说“传完后能播这几首”，只留下面那句为什么
                if (toUpload.isNotEmpty()) {
                    InfoNote("传的是原文件，不转码。传完后，同步过的歌单在其它设备上能直接播这 ${toUpload.size} 首。单个文件超过 500 MB 的会跳过。")
                }
                notice.notes.forEach { InfoNote(it) }
                when {
                    !configured -> InfoNote(
                        if (provider == CloudProvider.NeteaseCloud) "先登录网易云音乐，或者换成 WebDAV。" else "先连接 WebDAV，或者换成网易云盘。",
                        "amber",
                    )
                    check is CheckState.Failed && notice.blocking.isEmpty() -> InfoNote(check.message, "red")
                    notice.blocking.isNotEmpty() -> InfoNote(notice.blocking, "red")
                }
            }
            Button(
                onClick = {
                    starting = true
                    // 通知只用来看进度，不给也照样传；Android 13 起要单独申请
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        runCatching { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    }
                    val target = provider
                    scope.launch {
                        val added = uploads.enqueue(target, toUpload)
                        onDismiss()
                        val waitWifi = prefs.uploadWifiOnly && LocalUploadNetwork.metered(context)
                        snackbar.showSnackbar(
                            when {
                                added == 0 -> "这些歌已经在上传队列里了"
                                waitWifi -> "已排上 $added 首，连上 Wi-Fi 后开始传"
                                else -> "开始上传 $added 首到${if (target == CloudProvider.WebDav) " WebDAV" else target.label}"
                            },
                        )
                    }
                },
                enabled = canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .heightIn(min = 52.dp)
                    .tvFocusRing(RoundedCornerShape(26.dp)),
            ) {
                if (check is CheckState.Loading && configured) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Text("正在检查空间…", Modifier.padding(start = 8.dp))
                } else {
                    Text(if (request.force) "重新上传" else "开始上传")
                }
            }
        }
    }
}

/** 云端那行的说明：网易云盘写剩多少、能秒传；WebDAV 写服务器和目录；空间不够写还差多少。 */
private fun targetSummary(target: CloudProvider, check: CheckState?, webDav: WebDavAccountInfo?): String {
    val result = (check as? CheckState.Done)?.result
    val quota = result?.quota?.takeIf { it.known }
    return when (target) {
        CloudProvider.NeteaseCloud -> when {
            check is CheckState.Loading -> "正在查容量…"
            check is CheckState.Failed -> check.message
            result != null && result.shortfall > 0 -> "还差 ${CloudMessages.formatSize(result.shortfall)}"
            quota != null -> "剩 ${CloudMessages.formatSize(quota.free)} · 曲库里有的歌直接秒传"
            else -> "曲库里有的歌直接秒传"
        }
        CloudProvider.WebDav -> {
            val base = webDav?.let(::webDavTargetLabel) ?: CloudProvider.WebDav.label
            when {
                check is CheckState.Failed -> "$base · ${check.message}"
                result != null && result.shortfall > 0 -> "$base · 还差 ${CloudMessages.formatSize(result.shortfall)}"
                quota != null -> "$base · 剩 ${CloudMessages.formatSize(quota.free)}"
                else -> base
            }
        }
        CloudProvider.QqCloud -> CloudMessages.QQ_UPLOAD_UNSUPPORTED
    }
}

/** 现在连的是不是按流量计费的网络（移动网络、热点）：只在 Wi-Fi 下上传时，这种网络上先排队。 */
internal object LocalUploadNetwork {
    fun metered(context: android.content.Context): Boolean = runCatching {
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
    }.getOrDefault(false)
}

/**
 * 本地音乐页里“正在上传 · 2 / 5”那一段（设计稿第 ② 张）：正在传的那首带进度和取消；下一首写
 * “等待上传 · 还有 N 首”；失败的每首一行带重试；整个队列停下时写原因和“继续”，风控的说明会先等一会儿。
 */
internal fun LazyListScope.localUploadSection(
    state: LocalUploadState,
    index: LocalIndex,
    wifiOnly: Boolean,
    metered: Boolean,
    uploads: LocalUploads,
) {
    if (!state.busy && state.failed.isEmpty()) return
    item(key = "upload:head", contentType = "header") {
        // 标题照实写：停着（风控 / 密码不对）、在等（Wi-Fi、退避、后台任务还没起来）和真的在传分开说
        val progress = "${state.current.coerceAtLeast(if (state.active.isEmpty()) 0 else 1)} / ${state.total}"
        DayLabel(
            when {
                state.busy && state.paused != null -> "上传已暂停 · $progress"
                state.busy && state.active.isEmpty() -> "等待上传 · $progress"
                state.busy -> "正在上传 · $progress"
                else -> "上传失败 · ${state.failed.size} 首"
            },
        )
    }
    state.paused?.let { (provider, reason) ->
        item(key = "upload:paused", contentType = "note") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoNote(pausedExplanation(provider, reason), "amber")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { uploads.resume(provider) },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                    ) { Text("继续") }
                    TextButton(
                        onClick = { uploads.removeAll(provider) },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                    ) { Text("都不传了") }
                }
            }
        }
    }
    if (state.busy && state.active.isEmpty() && state.paused == null) {
        item(key = "upload:waiting", contentType = "note") {
            val waitingText = when {
                state.backoffUntil > System.currentTimeMillis() ->
                    "网易云要求放慢，${backoffMinutes(state.backoffUntil)} 后自动接着传。连续触发时会等得更久，最长 30 分钟。"
                wifiOnly && metered -> "现在用的是移动网络，连上 Wi-Fi 后自动开始传。可以在设置 › 本地音乐里关掉“只在 Wi-Fi 下上传”。"
                else -> "马上开始传…"
            }
            InfoNote(waitingText)
        }
    }
    val active = state.active
    val waiting = state.waiting
    val failed = state.failed
    // 一段分段列表：正在传的、下一首（带“还有 N 首”）、失败的
    val rows = buildList<Pair<String, LocalUploadEntry>> {
        active.forEach { add("active" to it) }
        waiting.firstOrNull()?.let { add("next" to it) }
        failed.forEach { add("failed" to it) }
    }
    rows.forEachIndexed { at, (kind, entry) ->
        item(key = "upload:$kind:${entry.provider.id}:${entry.localId}", contentType = "uploadRow") {
            val item = entry.item
            val song = index.byId[entry.localId]?.song
            val title = song?.title ?: item.meta.title.ifBlank { item.source.fileName }
            val subtitle = when (kind) {
                "active" -> activeSubtitle(entry)
                "next" -> if (waiting.size > 1) "等待上传 · 还有 ${waiting.size} 首" else "等待上传"
                else -> "上传失败 · ${item.error?.message ?: CloudMessages.CLOUD_UNREACHABLE}"
            }
            SongRow(
                title = title,
                subtitle = subtitle,
                cover = song?.cover.orEmpty(),
                index = at,
                count = rows.size,
                onClick = {},
                badges = null,
                trailing = {
                    when (kind) {
                        "active" -> {
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                val fraction = item.progress.fraction
                                if (item.stage == CloudUploadStage.UPLOADING && fraction > 0f) {
                                    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
                                } else {
                                    CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp)
                                }
                                IconButton(
                                    onClick = { uploads.cancel(entry.provider, entry.localId) },
                                    modifier = Modifier.tvFocusRing(CircleShape),
                                ) { Icon(Icons.Filled.Close, "取消上传“$title”") }
                            }
                        }
                        "next" -> IconButton(
                            onClick = { uploads.remove(entry.provider, entry.localId) },
                            modifier = Modifier.tvFocusRing(CircleShape),
                        ) { Icon(Icons.Filled.Close, "不传“$title”了") }
                        else -> Row {
                            if (item.error?.retryable != false && item.error?.kind != CloudErrorKind.InvalidFile) {
                                IconButton(
                                    onClick = { uploads.retry(entry.provider, entry.localId) },
                                    modifier = Modifier.tvFocusRing(CircleShape),
                                ) { Icon(Icons.Filled.Refresh, "重试上传“$title”") }
                            }
                            IconButton(
                                onClick = { uploads.remove(entry.provider, entry.localId) },
                                modifier = Modifier.tvFocusRing(CircleShape),
                            ) { Icon(Icons.Filled.Close, "从上传队列里拿掉“$title”") }
                        }
                    }
                },
            )
        }
    }
}

/** “网易云盘 · 18.2 / 32.4 MB”；秒传、跳过、转码这些阶段写成人话。 */
private fun activeSubtitle(entry: LocalUploadEntry): String {
    val progress = entry.item.progress
    val label = entry.provider.label
    val total = entry.item.source.size
    return when (progress.stage) {
        CloudUploadStage.QUEUED -> "$label · 准备上传"
        CloudUploadStage.HASHING -> "$label · 正在读文件"
        CloudUploadStage.CHECKING -> "$label · 正在检查云端有没有"
        CloudUploadStage.UPLOADING -> "$label · ${formatMb(progress.bytesDone)} / ${formatMb(total)} MB"
        CloudUploadStage.SUBMITTING -> "$label · 正在提交"
        CloudUploadStage.TRANSCODING -> "$label · 云盘正在处理"
        CloudUploadStage.PUBLISHING -> "$label · 正在发布"
        CloudUploadStage.READY -> if (progress.instant) "$label · 秒传完成" else "$label · 传完了"
        CloudUploadStage.FAILED -> "$label · 上传失败"
    }
}

private fun formatMb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0)

private fun backoffMinutes(until: Long): String {
    val minutes = ((until - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0) + 1
    return "约 $minutes 分钟"
}

/** 暂停的说明：风控要说清楚点继续之后还会先等一会儿，等的时间越来越长，免得用户以为点了没反应。 */
internal fun pausedExplanation(provider: CloudProvider, reason: CloudException?): String {
    val head = LocalUploadRules.pausedText(provider, reason)
    return when (reason?.kind) {
        CloudErrorKind.RiskControl ->
            "$head。可以先在网易云音乐里完成验证；点“继续”后会先等 1 分钟再传，连续触发时等得更久（最长 30 分钟）。"
        CloudErrorKind.NotLoggedIn ->
            if (provider == CloudProvider.WebDav) "$head。连接 WebDAV 后点“继续”。" else "$head。重新登录后点“继续”。"
        CloudErrorKind.Unauthorized -> "$head。在设置 › 本地音乐里改好密码后点“继续”。"
        else -> head
    }
}
