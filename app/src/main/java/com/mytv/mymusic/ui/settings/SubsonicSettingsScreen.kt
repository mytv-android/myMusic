package com.mytv.mymusic.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.subsonic.SubsonicAccount
import com.mytv.mymusic.data.subsonic.SubsonicAuthMode
import com.mytv.mymusic.data.sync.LocalNetworkAccess
import com.mytv.mymusic.data.webdav.WebDavCertificate
import com.mytv.mymusic.ui.ConfirmDialog
import com.mytv.mymusic.ui.ErrorBlock
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

@Composable
internal fun SubsonicSettingsScreen(
    state: MusicUiState,
    onBack: () -> Unit,
    embedded: Boolean,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? MyMusicApp ?: return
    val accounts = remember(state.cookies) { SubsonicAccount.fromCookies(state.cookies) }
    var editing by remember { mutableStateOf<SubsonicAccount?>(null) }
    var formOpen by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    SettingsTvFocus(firstFocus, formOpen)
    if (formOpen && editing != null) {
        SubsonicServerForm(
            original = editing,
            embedded = embedded,
            onBack = { formOpen = false },
            onSave = { account ->
                app.subsonic.save(account)
                formOpen = false
            },
            onDelete = if (editing in accounts) { account ->
                app.subsonic.remove(account)
                formOpen = false
            } else null,
            firstFocus = firstFocus,
        )
        return
    }
    SettingsPage(SettingsSection.Subsonic, embedded, onBack) {
        if (accounts.isNotEmpty()) {
            item(key = "servers", contentType = "serverList") {
                Column {
                    SectionLabel("我的服务器")
                    accounts.forEachIndexed { index, account ->
                        ListRow(
                            title = account.displayName,
                            summary = listOf(
                                account.serverType.replaceFirstChar(Char::uppercase).ifBlank { "Subsonic" },
                                account.baseUrl?.host.orEmpty(),
                                account.username,
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            index = index,
                            count = accounts.size,
                            onClick = {
                                editing = account
                                formOpen = true
                            },
                            leading = { ServerIcon() },
                        )
                    }
                }
            }
        } else {
            item(key = "empty", contentType = "empty") {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Icon(Icons.Filled.Dns, null, Modifier.size(44.dp))
                        Text(
                            "连接你的音乐库",
                            Modifier.padding(top = 20.dp),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Navidrome、Airsonic、Gonic 与其他 Subsonic 兼容服务",
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        item(key = "add", contentType = "action") {
            Button(
                onClick = {
                    editing = SubsonicAccount(id = UUID.randomUUID().toString())
                    formOpen = true
                },
                modifier = Modifier.fillMaxWidth().height(56.dp).then(
                    if (accounts.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                ).tvFocusRing(RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("添加服务器", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SubsonicServerForm(
    original: SubsonicAccount?,
    embedded: Boolean,
    onBack: () -> Unit,
    onSave: suspend (SubsonicAccount) -> Unit,
    onDelete: (suspend (SubsonicAccount) -> Unit)?,
    firstFocus: FocusRequester,
) {
    val existing = original?.takeIf { it.url.isNotBlank() }
    val context = LocalContext.current
    val app = context.applicationContext as MyMusicApp
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val window = LocalAppWindow.current
    var name by remember(original?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember(original?.id) { mutableStateOf(existing?.url.orEmpty()) }
    var username by remember(original?.id) { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember(original?.id) { mutableStateOf("") }
    var showPassword by remember(original?.id) { mutableStateOf(false) }
    var busy by remember(original?.id) { mutableStateOf(false) }
    var deleting by remember(original?.id) { mutableStateOf(false) }
    var error by remember(original?.id) { mutableStateOf("") }
    var connected by remember(original?.id) { mutableStateOf("") }
    var tested by remember(original?.id) { mutableStateOf<SubsonicAccount?>(null) }
    var pendingSave by remember(original?.id) { mutableStateOf<SubsonicAccount?>(null) }
    var confirmDelete by remember(original?.id) { mutableStateOf(false) }
    var certificate by remember(original?.id) { mutableStateOf<WebDavCertificate?>(null) }
    val shape = RoundedCornerShape(20.dp)

    fun accountForSave(): SubsonicAccount {
        val actualPassword = password.ifEmpty { existing?.password.orEmpty() }
        val base = existing ?: SubsonicAccount(id = original?.id ?: UUID.randomUUID().toString())
        val normalized = base.copy(name = name, url = url, username = username, password = actualPassword).normalized()
        val identityChanged = existing == null || normalized.url != existing.normalized().url ||
            normalized.username != existing.username || actualPassword != existing.password
        val probed = tested?.takeIf {
            it.url == normalized.url && it.username == normalized.username && it.password == normalized.password
        }
        return (probed ?: normalized).copy(name = name.trim()).let { account ->
            if (identityChanged && probed == null) {
                account.copy(
                    authMode = if (account.usesApiKey) SubsonicAuthMode.ApiKey else SubsonicAuthMode.Token,
                    serverType = "", serverVersion = "", apiVersion = "", openSubsonic = false,
                    extensions = emptyList(), streamRole = null, downloadRole = null,
                    playlistRole = null, scrobblingEnabled = null,
                )
            } else {
                account
            }
        }
    }

    fun validate(): String = when {
        normalizeInputUrl(url) == null -> "请输入有效的 http:// 或 https:// 服务器地址"
        password.ifEmpty { existing?.password.orEmpty() }.isBlank() -> "请输入密码或 Navidrome API key"
        username.isBlank() && !password.startsWith(SubsonicAccount.API_KEY_PREFIX) &&
            existing?.usesApiKey != true -> "请输入用户名；使用 API key 时可以留空"
        else -> ""
    }

    suspend fun test() {
        val problem = validate()
        if (problem.isNotBlank()) {
            error = problem
            return
        }
        val account = accountForSave()
        busy = true
        error = ""
        connected = ""
        try {
            val result = app.subsonic.testConnection(account)
            tested = result.account
            connected = result.serverLabel.ifBlank { "Subsonic 服务器" }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            val isTls = generateSequence(failure as Throwable?) { it.cause }.any { it is SSLException }
            val observed = if (isTls) app.subsonic.probeCertificate(account) else null
            if (observed != null) {
                certificate = observed
            } else {
                error = connectionError(failure)
            }
        } finally {
            busy = false
        }
    }

    val networkGate = rememberSubsonicNetworkGate()
    SettingsPage(
        SettingsSection.Subsonic,
        embedded,
        onBack,
        subtitle = existing?.displayName ?: "添加服务器",
    ) {
        item(key = "form", contentType = "form") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(if (window.isTv) 24.dp else 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it; connected = "" },
                            label = { Text("名称") },
                            placeholder = { Text("家里的 Navidrome") },
                            leadingIcon = { Icon(Icons.Filled.Dns, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
                        )
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it; connected = ""; error = "" },
                            label = { Text("服务器地址") },
                            placeholder = { Text("https://music.example.com") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it; connected = ""; error = "" },
                            label = { Text("用户名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; connected = ""; error = "" },
                            label = { Text(if (existing == null) "密码 / API key" else "新密码 / API key") },
                            placeholder = if (existing != null) ({ Text("保持不变") }) else null,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        if (showPassword) "隐藏密码" else "显示密码",
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (error.isNotBlank()) ErrorBlock(error)
                        if (connected.isNotBlank()) {
                            Surface(
                                shape = shape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CheckCircle, null)
                                    Spacer(Modifier.width(12.dp))
                                    Text("已连接 · $connected", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    val account = accountForSave()
                                    networkGate(account.baseUrl?.host.orEmpty()) { scope.launch { test() } }
                                },
                                enabled = !busy && !deleting,
                                modifier = Modifier.weight(1f).height(52.dp).tvFocusRing(shape),
                                shape = shape,
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("测试连接")
                                }
                            }
                            Button(
                                onClick = {
                                    val problem = validate()
                                    if (problem.isNotBlank()) error = problem else pendingSave = accountForSave()
                                },
                                enabled = !busy && !deleting,
                                modifier = Modifier.weight(1f).height(52.dp).tvFocusRing(shape),
                                shape = shape,
                            ) { Text("保存") }
                        }
                    }
                }
            }
        }
        if (existing != null && onDelete != null) {
            item(key = "delete", contentType = "action") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        enabled = !busy && !deleting,
                        modifier = Modifier.tvFocusRing(CircleShape),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("删除这台服务器") }
                }
            }
        }
    }

    pendingSave?.let { account ->
        ConfirmDialog(
            title = "保存${account.displayName}？",
            message = "服务器地址、用户名和密码会明文写入同步文件并同步到其他设备。自签证书指纹只保存在本机。",
            confirmLabel = "保存",
            onConfirm = {
                pendingSave = null
                scope.launch {
                    busy = true
                    try {
                        onSave(account)
                        snackbar.showSnackbar("已保存${account.displayName}")
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        error = connectionError(failure)
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { pendingSave = null },
        )
    }
    if (confirmDelete && existing != null && onDelete != null) {
        ConfirmDialog(
            title = "删除${existing.displayName}？",
            message = "账号会从这台设备和所有同步设备上移除。服务器上的歌曲、收藏与歌单不会被删除。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    deleting = true
                    try {
                        onDelete(existing)
                        snackbar.showSnackbar("已删除${existing.displayName}")
                    } finally {
                        deleting = false
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
    certificate?.let { observed ->
        val changed = existing?.let { app.subsonic.security.pinned(it) } != null
        AlertDialog(
            onDismissRequest = { certificate = null },
            title = { Text(if (changed) "服务器证书已更换" else "确认服务器证书") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        if (changed) "新证书与本机记录的指纹不同。核对无误后再继续连接。"
                        else "系统无法验证这张证书。请与服务器上的 SHA-256 指纹核对。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(observed.subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        observed.fingerprint,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val account = accountForSave()
                    app.subsonic.security.trust(account, observed)
                    certificate = null
                    scope.launch { test() }
                }) { Text("信任并继续") }
            },
            dismissButton = { TextButton(onClick = { certificate = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ServerIcon() {
    Box(
        Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Dns, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private fun normalizeInputUrl(raw: String) = com.mytv.mymusic.data.subsonic.normalizeServerUrl(raw)

private fun connectionError(error: Exception): String {
    val chain = generateSequence(error as Throwable?) { it.cause }.toList()
    return when {
        chain.any { it is UnknownHostException } -> "找不到服务器，请检查地址和当前网络"
        error.message?.contains("timeout", true) == true -> "连接服务器超时，请检查地址、端口和网络"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "无法连接服务器"
    }
}

@Composable
private fun rememberSubsonicNetworkGate(): (String, () -> Unit) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    return remember(launcher) {
        { host, action ->
            if (host.isBlank() || LocalNetworkAccess.isGranted(context)) {
                action()
            } else {
                scope.launch {
                    val local = withContext(Dispatchers.IO) { LocalNetworkAccess.isLocalNetworkHost(host) }
                    if (local) {
                        pending = action
                        launcher.launch(LocalNetworkAccess.PERMISSION)
                    } else {
                        action()
                    }
                }
            }
        }
    }
}
