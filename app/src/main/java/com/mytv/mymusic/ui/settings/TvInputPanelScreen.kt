package com.mytv.mymusic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.lx.LxSources
import com.mytv.mymusic.data.sync.SyncSetupPhase
import com.mytv.mymusic.data.sync.SyncSetupSubmission
import com.mytv.mymusic.data.sync.TvInputPanelHandlers
import com.mytv.mymusic.data.sync.TvInputPanelServer
import com.mytv.mymusic.data.sync.TvInputPanelSnapshot
import com.mytv.mymusic.data.sync.TvInputPanelStatus
import com.mytv.mymusic.data.sync.TvInputTarget
import com.mytv.mymusic.data.sync.TvInputWriteMode
import com.mytv.mymusic.data.sync.WebDavSyncProvider
import com.mytv.mymusic.ui.AppNav
import com.mytv.mymusic.ui.ErrorBlock
import com.mytv.mymusic.ui.adaptive.DialogAvoidsFold
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.library.InitialMergeDialog
import com.mytv.mymusic.ui.library.LocalNetworkHint
import com.mytv.mymusic.ui.library.currentSyncSetupTheme
import com.mytv.mymusic.widget.WidgetTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

private sealed interface TvInputPanelUi {
    data object Starting : TvInputPanelUi
    data class Ready(val url: String, val server: TvInputPanelServer) : TvInputPanelUi
    data class Error(val message: String) : TvInputPanelUi
    data object Expired : TvInputPanelUi
}

@Composable
internal fun TvInputPanelScreen(app: AppNav, onBack: () -> Unit, embedded: Boolean) {
    val state by app.model.state.collectAsState()
    val sync by app.model.syncState.collectAsState()
    val context = LocalContext.current.applicationContext
    val musicApp = context as MyMusicApp
    val scope = rememberCoroutineScope()
    val serverRef = remember { AtomicReference<TvInputPanelServer?>(null) }
    var generation by rememberSaveable { mutableIntStateOf(0) }
    val snapshot = TvInputPanelSnapshot(
        theme = currentSyncSetupTheme(),
        syncProvider = sync.account?.provider?.takeIf { it in TvInputPanelServer.PROVIDERS } ?: "gist",
        sync = sync,
        signerUrl = state.sodaSignerUrl,
        signerEnabled = state.sodaSignerEnabled,
    )
    val handlers = remember(app, scope, musicApp, serverRef) {
        TvInputPanelHandlers(
            search = { text, operationId ->
                scope.launch {
                    app.actions.submitSearch(text)
                    app.model.openFromWidget(WidgetTarget.Search)
                    serverRef.get()?.finish(operationId)
                }
            },
            source = { url, operationId ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val record = LxSources.importOnline(url)
                        serverRef.get()?.finishSource(operationId, record)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        serverRef.get()?.finish(operationId, error.message ?: "下载或校验失败，未保存")
                    }
                }
            },
            sourceScript = { script, operationId ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val record = LxSources.importLocal(script)
                        serverRef.get()?.finishSource(operationId, record)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        serverRef.get()?.finish(operationId, error.message ?: "脚本校验失败，未保存")
                    }
                }
            },
            sync = { submission -> scope.launch { connectFromPanel(app, submission) } },
            testWebDav = { account, _ -> scope.launch { app.actions.testWebDav(account) } },
            saveStorage = { account, operationId ->
                scope.launch {
                    val error = musicApp.sync.saveWebDavStorage(account)
                    if (error.isBlank()) musicApp.localUploads.resume(CloudProvider.WebDav)
                    serverRef.get()?.finish(operationId, error)
                }
            },
            signer = { submission, operationId ->
                scope.launch {
                    app.actions.saveSodaSigner(submission.url, submission.token, submission.enabled)
                    serverRef.get()?.finish(operationId)
                }
            },
        )
    }
    val session = rememberTvInputPanelSession(generation, snapshot, handlers, serverRef)
    val status = rememberTvInputStatus(session)
    SettingsPage(SettingsSection.InputPanel, embedded, onBack) {
        item(key = "inputPanel", contentType = "inputPanel") {
            TvInputPanelBody(
                session = session,
                status = status,
                onRetry = { generation++ },
            )
        }
    }
    sync.initialMerge?.let { preview ->
        InitialMergeDialog(preview, sync, app.actions, dismissible = true)
    }
}

private suspend fun connectFromPanel(app: AppNav, submission: SyncSetupSubmission) {
    val webDav = submission.webDav
    if (webDav != null) app.actions.connectWebDav(webDav)
    else app.actions.connectSync(submission.provider, submission.token, submission.remote)
}

@Composable
private fun TvInputPanelBody(
    session: TvInputPanelUi,
    status: TvInputPanelStatus,
    onRetry: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text(
                        "手机输入，电视接收",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        "文字、在线音源和常用设置，都能用手机填好后发送到这台电视。",
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            LocalNetworkHint()
            Text(
                "面板只在使用期间开放；离开此页或电视进入后台即关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.weight(1.2f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("扫码在手机上输入", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "二维码 10 分钟内有效",
                    Modifier.padding(top = 3.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TvInputSessionContent(session, status, onRetry)
            }
        }
    }
}

@Composable
private fun TvInputSessionContent(
    session: TvInputPanelUi,
    status: TvInputPanelStatus,
    onRetry: () -> Unit,
) {
    when (session) {
        TvInputPanelUi.Starting -> LoadingIndicator(Modifier.size(52.dp).padding(20.dp))
        is TvInputPanelUi.Error -> ErrorBlock(session.message, onRetry = onRetry)
        TvInputPanelUi.Expired -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("二维码已过期", color = MaterialTheme.colorScheme.error)
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = 14.dp).tvFocusRing(),
            ) { Text("重新生成二维码") }
        }
        is TvInputPanelUi.Ready -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = Color.White) {
                QrCodeImage(
                    text = session.url,
                    modifier = Modifier.size(220.dp).padding(10.dp),
                    description = "扫描后用手机向电视输入",
                )
            }
            Text(
                session.url.substringBefore("/s/").removePrefix("http://"),
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (status.phase == SyncSetupPhase.Idle) "等待手机操作" else status.title,
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (status.phase == SyncSetupPhase.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 字段级入口：原表单继续留在组合里，网页只拿到字段标签和是否有内容，不回传原文。 */
@Composable
internal fun TvFieldInputDialog(
    targetId: String,
    label: String,
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val serverRef = remember { AtomicReference<TvInputPanelServer?>(null) }
    var generation by rememberSaveable { mutableIntStateOf(0) }
    val currentValue by rememberUpdatedState(value)
    val currentBusy by rememberUpdatedState(busy)
    val currentChange by rememberUpdatedState(onValueChange)
    val currentSubmit by rememberUpdatedState(onSubmit)
    val snapshot = TvInputPanelSnapshot(
        theme = currentSyncSetupTheme(),
        target = TvInputTarget(
            id = targetId,
            label = label,
            hasValue = value.isNotEmpty(),
            available = !busy,
            supportsEnter = onSubmit != null,
        ),
        allowSearch = false,
        allowSettings = false,
    )
    val handlers = remember(scope, serverRef) {
        TvInputPanelHandlers(
            write = { write, operationId ->
                scope.launch {
                    if (currentBusy || write.targetId != targetId) {
                        serverRef.get()?.finish(operationId, "电视已离开这个输入框，请重新扫码")
                        return@launch
                    }
                    val next = if (write.mode == TvInputWriteMode.Append) currentValue + write.text else write.text
                    currentChange(next)
                    if (write.submit) currentSubmit?.invoke(next)
                    serverRef.get()?.finish(operationId)
                }
            },
        )
    }
    val session = rememberTvInputPanelSession(generation, snapshot, handlers, serverRef)
    val status = rememberTvInputStatus(session)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogAvoidsFold()
        Surface(
            modifier = Modifier.width(460.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("用手机输入", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    label,
                    Modifier.padding(top = 4.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TvInputSessionContent(session, status) { generation++ }
                LocalNetworkHint(Modifier.padding(top = 12.dp))
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp).tvFocusRing(),
                ) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun rememberTvInputStatus(session: TvInputPanelUi): TvInputPanelStatus {
    var status by remember(session) { mutableStateOf(TvInputPanelStatus()) }
    LaunchedEffect(session) {
        if (session !is TvInputPanelUi.Ready) return@LaunchedEffect
        while (true) {
            status = session.server.status()
            delay(500)
        }
    }
    return status
}

/** 服务只在页面 RESUMED 时存在；墙上时钟过期与协程 delay 分开校准。 */
@Composable
private fun rememberTvInputPanelSession(
    generation: Int,
    snapshot: TvInputPanelSnapshot,
    handlers: TvInputPanelHandlers,
    serverRef: AtomicReference<TvInputPanelServer?>,
): TvInputPanelUi {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val currentSnapshot = rememberUpdatedState(snapshot)
    val currentHandlers = rememberUpdatedState(handlers)
    return produceState<TvInputPanelUi>(TvInputPanelUi.Starting, generation, resumed, context) {
        value = TvInputPanelUi.Starting
        serverRef.set(null)
        if (!resumed) return@produceState
        var server: TvInputPanelServer? = null
        try {
            val started = withContext(Dispatchers.IO) {
                TvInputPanelServer(
                    assets = { name ->
                        runCatching { context.assets.open("tv-input/$name").use { it.readBytes() } }.getOrNull()
                    },
                    snapshot = { currentSnapshot.value },
                    handlers = TvInputPanelHandlers(
                        write = { write, id -> currentHandlers.value.write(write, id) },
                        search = { text, id -> currentHandlers.value.search(text, id) },
                        source = { url, id -> currentHandlers.value.source(url, id) },
                        sourceScript = { script, id -> currentHandlers.value.sourceScript(script, id) },
                        sync = { submission -> currentHandlers.value.sync(submission) },
                        testWebDav = { account, id -> currentHandlers.value.testWebDav(account, id) },
                        saveStorage = { account, id -> currentHandlers.value.saveStorage(account, id) },
                        signer = { submission, id -> currentHandlers.value.signer(submission, id) },
                    ),
                ).also {
                    server = it
                    serverRef.set(it)
                }.startPanel()
            }
            value = TvInputPanelUi.Ready(started.url, server!!)
            launch {
                while (true) {
                    val left = started.expiresAt - System.currentTimeMillis()
                    if (left <= 0) break
                    delay(left.coerceAtMost(EXPIRY_CHECK_MILLIS))
                }
                server?.stop()
                value = TvInputPanelUi.Expired
            }
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            value = TvInputPanelUi.Error(error.message ?: "无法开启局域网输入面板，请检查网络后重试")
        } finally {
            server?.stop()
            serverRef.set(null)
        }
    }.value
}

private const val EXPIRY_CHECK_MILLIS = 30_000L
