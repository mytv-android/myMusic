package com.mytv.mymusic.ui.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.sync.SyncUiState
import com.mytv.mymusic.data.sync.WebDavSyncProvider
import com.mytv.mymusic.ui.ConfirmDialog
import com.mytv.mymusic.ui.InfoNote
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.library.TvDefaultFocus
import com.mytv.mymusic.ui.library.WebDavForm
import com.mytv.mymusic.ui.library.WebDavSaveAction
import com.mytv.mymusic.ui.library.rememberWebDavFormState
import com.mytv.mymusic.ui.library.withGutter
import kotlinx.coroutines.launch

/** 只给上传用的 WebDAV 账号（设置 › 本地音乐 › WebDAV 服务器）。 */
const val LOCAL_WEBDAV_ROUTE = "localMusic/webdav"

/**
 * 连接 WebDAV 用来放歌（设计稿第 ⑩ 张）：和“音乐库同步 › WebDAV”是同一个表单、同一份账号，只是主按钮
 * 是“保存”，不碰同步位置。同步已经在用 WebDAV 时这里不重复配，直接去同步那边改。
 */
@Composable
fun LocalWebDavScreen(
    sync: SyncUiState,
    actions: MusicActions,
    onBack: () -> Unit,
    onOpenSyncWebDav: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? MyMusicApp ?: return
    val window = LocalAppWindow.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val form = rememberWebDavFormState(sync.webDav)
    val first = remember { FocusRequester() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    val syncUsesWebDav = sync.account?.provider == WebDavSyncProvider.ID
    val saved = sync.webDav
    TvDefaultFocus(first, syncUsesWebDav)
    PageScaffold(
        title = "WebDAV 服务器",
        subtitle = saved?.let(::webDavTargetLabel) ?: "放上传的本地歌",
        onBack = onBack,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = padding.withGutter(window.gutter),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (syncUsesWebDav) {
                item(key = "shared", contentType = "note") {
                    Column {
                        InfoNote("音乐库同步正在用这个 WebDAV 账号，上传的歌也放在同一个目录下。要改地址或密码，到音乐库同步里改。")
                        TextButton(
                            onClick = onOpenSyncWebDav,
                            modifier = Modifier.padding(top = 4.dp).tvFocusRing(RoundedCornerShape(20.dp)),
                        ) { Text("去音乐库同步") }
                    }
                }
                return@LazyColumn
            }
            item(key = "what", contentType = "note") {
                InfoNote(
                    "上传的歌放在这个目录下的 music/ 里，按文件指纹命名。WebDAV 密码用 Android Keystore 加密，只存在本机，" +
                        "不进同步文件。以后在音乐库同步里选 WebDAV，会直接用这个账号。",
                )
            }
            if (window.isTv) {
                item(key = "tv", contentType = "note") {
                    InfoNote("电视上输入密码不方便：也可以在音乐库同步里选 WebDAV，用手机扫码填好，是同一个账号。")
                }
            }
            item(key = "form", contentType = "form") {
                Column {
                    WebDavForm(
                        form = form,
                        sync = sync,
                        actions = actions,
                        firstFocus = first,
                        onConnectAttempt = {},
                        save = WebDavSaveAction(busy = saving, error = error) { account ->
                            saving = true
                            error = ""
                            scope.launch {
                                val message = app.sync.saveWebDavStorage(account)
                                saving = false
                                if (message.isBlank()) {
                                    // 保存后有排在 WebDAV 上、因为没配置而停下的上传：接着传
                                    app.localUploads.resume(com.mytv.mymusic.data.cloud.CloudProvider.WebDav)
                                    snackbar.showSnackbar("WebDAV 已保存，上传的歌会放在这里")
                                    onBack()
                                } else {
                                    error = message
                                }
                            }
                        },
                    )
                }
            }
            if (saved != null) {
                item(key = "remove", contentType = "action") {
                    Box(Modifier.padding(top = 8.dp)) {
                        TextButton(
                            onClick = { confirmRemove = true },
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                        ) { Text("删除这个 WebDAV 账号", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "删除 WebDAV 账号？",
            message = "只删这台设备上保存的地址和密码。已经传上去的歌还在服务器上，歌单里的这些歌在其它设备上照常能播。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                scope.launch {
                    app.sync.removeWebDavStorage()
                    snackbar.showSnackbar("已删除 WebDAV 账号")
                    onBack()
                }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}
