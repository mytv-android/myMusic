package com.mytv.mymusic.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mytv.mymusic.data.sync.SyncSetupServer
import com.mytv.mymusic.data.sync.SyncSetupSnapshot
import com.mytv.mymusic.data.sync.SyncSetupSubmission
import com.mytv.mymusic.data.sync.SyncSetupTheme
import com.mytv.mymusic.data.sync.SyncUiState
import com.mytv.mymusic.data.sync.SyncStatus
import com.mytv.mymusic.ui.ErrorBlock
import com.mytv.mymusic.ui.InfoNote
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.settings.QrCodeImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private sealed interface TvSetupSession {
    data object Starting : TvSetupSession
    data class Ready(val url: String) : TvSetupSession
    data class Error(val message: String) : TvSetupSession
    data object Expired : TvSetupSession
}

/** 电视扫码连接页。网页只改变配置入口，连接与合并仍由原有 SyncCoordinator 完成。 */
@Composable
internal fun TvSyncSetupContent(
    sync: SyncUiState,
    actions: MusicActions,
    provider: String,
    connected: Boolean,
    padding: PaddingValues,
    onSubmission: () -> Unit,
    onManualInput: () -> Unit,
    onDone: () -> Unit,
    infoContent: @Composable () -> Unit,
) {
    val window = LocalAppWindow.current
    val scope = rememberCoroutineScope()
    var generation by rememberSaveable { mutableIntStateOf(0) }
    val session = rememberTvSetupSession(
        generation = generation,
        provider = provider,
        sync = sync,
        theme = currentSyncSetupTheme(),
        onSubmit = { submission ->
            scope.launch {
                onSubmission()
                actions.connectSync(submission.provider, submission.token, submission.remote)
            }
        },
    )
    val primaryFocus = remember { FocusRequester() }
    TvDefaultFocus(primaryFocus, connected)

    Row(
        Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding())
            .padding(horizontal = window.gutter),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(
            Modifier
                .width(360.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Column(Modifier.padding(20.dp)) {
                    ShapeIcon(Icons.Filled.CloudUpload)
                    Text(
                        "手机填写，电视同步",
                        Modifier.padding(top = 14.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        "1. 手机与电视连接同一网络\n2. 扫码选择 GitHub Gist 或 Gitee\n3. 提交后按电视提示完成连接",
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(
                        onClick = onManualInput,
                        enabled = !connected && sync.status != SyncStatus.Syncing,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .then(if (!connected) Modifier.focusRequester(primaryFocus) else Modifier)
                            .tvFocusRing(RoundedCornerShape(24.dp)),
                    ) { Text("改用遥控器输入") }
                }
            }
            infoContent()
            InfoNote("网页面板只在本次配置期间开放；离开此页或电视进入后台即关闭。")
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (connected) {
                        TvSetupConnected(sync, primaryFocus, onDone)
                    } else {
                        Text(
                            "扫码在手机上配置",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "二维码 10 分钟内有效",
                            Modifier.padding(top = 4.dp, bottom = 18.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when (val current = session) {
                            TvSetupSession.Starting -> {
                                LoadingIndicator(Modifier.size(56.dp))
                                Text(
                                    "正在开启局域网配置…",
                                    Modifier.padding(top = 20.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            is TvSetupSession.Ready -> {
                                Surface(shape = RoundedCornerShape(22.dp), color = Color.White) {
                                    QrCodeImage(
                                        text = current.url,
                                        modifier = Modifier.size(300.dp).padding(10.dp),
                                        description = "扫描后在手机配置 Gist 或 Gitee 同步",
                                    )
                                }
                                Text(
                                    current.url.substringBefore("/s/"),
                                    Modifier.padding(top = 14.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "用系统相机或扫码工具扫描",
                                    Modifier.padding(top = 2.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            is TvSetupSession.Error -> {
                                ErrorBlock(current.message, onRetry = { generation++ })
                            }
                            TvSetupSession.Expired -> {
                                Text(
                                    "二维码已过期，旧页面不能再提交配置。",
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                )
                                Button(
                                    onClick = { generation++ },
                                    modifier = Modifier.padding(top = 16.dp).tvFocusRing(RoundedCornerShape(24.dp)),
                                ) { Text("重新生成二维码") }
                            }
                        }
                        when {
                            sync.status == SyncStatus.Syncing -> {
                                LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 20.dp))
                                Text(
                                    sync.phase.ifBlank { "正在验证手机提交的配置…" },
                                    Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            sync.initialMerge != null -> {
                                InfoNote("本机和云端都有歌单，请在电视上选择首次合并方式。", "amber")
                            }
                            sync.error.isNotBlank() -> {
                                Spacer(Modifier.height(16.dp))
                                ErrorBlock(sync.error)
                            }
                            session is TvSetupSession.Ready -> {
                                Text(
                                    "等待手机提交；已保存的令牌不会显示在网页中",
                                    Modifier.padding(top = 16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSetupConnected(sync: SyncUiState, focus: FocusRequester, onDone: () -> Unit) {
    ShapeIcon(Icons.Filled.CloudDone)
    Text(
        "已连接 ${sync.providerLabel}",
        Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
    )
    Text(
        "手机页面已收到结果。回到同步中心后可以立即同步或调整自动同步设置。",
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Button(
        onClick = onDone,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp)
            .focusRequester(focus)
            .tvFocusRing(RoundedCornerShape(28.dp)),
    ) { Text("完成") }
}

@Composable
private fun rememberTvSetupSession(
    generation: Int,
    provider: String,
    sync: SyncUiState,
    theme: SyncSetupTheme,
    onSubmit: (SyncSetupSubmission) -> Unit,
): TvSetupSession {
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
    val snapshot = rememberUpdatedState(SyncSetupSnapshot(provider, theme, sync))
    val submit = rememberUpdatedState(onSubmit)
    return produceState<TvSetupSession>(TvSetupSession.Starting, generation, resumed, context) {
        if (!resumed) return@produceState
        var server: SyncSetupServer? = null
        try {
            val started = withContext(Dispatchers.IO) {
                SyncSetupServer(
                    assets = { name ->
                        runCatching {
                            context.assets.open("sync-setup/$name").use { it.readBytes() }
                        }.getOrNull()
                    },
                    snapshot = { snapshot.value },
                    onSubmit = { submission -> submit.value(submission) },
                ).also { server = it }.startPanel()
            }
            value = TvSetupSession.Ready(started.url)
            launch {
                delay((started.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
                server?.stop()
                value = TvSetupSession.Expired
            }
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            value = TvSetupSession.Error(error.message ?: "无法开启局域网配置，请检查网络后重试")
        } finally {
            server?.stop()
        }
    }.value
}

@Composable
private fun currentSyncSetupTheme(): SyncSetupTheme {
    val colors = MaterialTheme.colorScheme
    return SyncSetupTheme(
        background = colors.background.cssHex(),
        surface = colors.surface.cssHex(),
        surfaceContainer = colors.surfaceContainer.cssHex(),
        surfaceContainerHigh = colors.surfaceContainerHigh.cssHex(),
        primary = colors.primary.cssHex(),
        onPrimary = colors.onPrimary.cssHex(),
        primaryContainer = colors.primaryContainer.cssHex(),
        onPrimaryContainer = colors.onPrimaryContainer.cssHex(),
        secondaryContainer = colors.secondaryContainer.cssHex(),
        onSecondaryContainer = colors.onSecondaryContainer.cssHex(),
        tertiaryContainer = colors.tertiaryContainer.cssHex(),
        onTertiaryContainer = colors.onTertiaryContainer.cssHex(),
        onSurface = colors.onSurface.cssHex(),
        onSurfaceVariant = colors.onSurfaceVariant.cssHex(),
        outline = colors.outline.cssHex(),
        outlineVariant = colors.outlineVariant.cssHex(),
        error = colors.error.cssHex(),
        onError = colors.onError.cssHex(),
        errorContainer = colors.errorContainer.cssHex(),
        onErrorContainer = colors.onErrorContainer.cssHex(),
    )
}

private fun Color.cssHex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)
