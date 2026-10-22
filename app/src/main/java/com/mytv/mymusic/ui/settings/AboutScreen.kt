package com.mytv.mymusic.ui.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.update.UpdatePhase
import com.mytv.mymusic.data.update.UpdateUiState
import com.mytv.mymusic.data.update.formatSize
import com.mytv.mymusic.ui.ErrorBlock
import com.mytv.mymusic.ui.InfoNote
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.SegmentedGroup
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val PROJECT_URL = "https://github.com/mytv-android/myMusic"

/**
 * 关于与更新：应用信息、新版本卡片（下载进度、校验、安装都在卡片里完成）、
 * 检查更新 / 隐私 / 许可 / 项目主页 / 复制诊断。
 */
@Composable
fun AboutScreen(
    state: MusicUiState,
    update: UpdateUiState,
    actions: MusicActions,
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onPrivacy: () -> Unit,
    embedded: Boolean = false,
) {
    val openUri = rememberOpenUri()
    val clipboard = LocalClipboard.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    // 用户主动点“检查更新”时，连“此版本不再提示”跳过的版本也重新显示
    var revealSkipped by rememberSaveable { mutableStateOf(false) }
    val info = update.info
    val busy = update.phase == UpdatePhase.Downloading || update.phase == UpdatePhase.Verifying || update.phase == UpdatePhase.Ready
    val showCard = info != null && (info.versionCode != state.skippedUpdateCode || revealSkipped || busy)
    val isChecking = checking || update.phase == UpdatePhase.Checking

    fun check() {
        if (checking) return
        scope.launch {
            checking = true
            val result = actions.checkUpdatesNow()
            checking = false
            when {
                result.info != null -> revealSkipped = true
                result.error.isBlank() -> snackbar.showSnackbar(result.message.ifBlank { "已是最新版本" })
            }
        }
    }

    val checkSummary = when {
        isChecking -> "正在检查…"
        info != null -> "有新版本 ${info.versionName}（构建 ${info.versionCode}）"
        update.error.isNotBlank() -> "检查失败"
        update.message.isNotBlank() -> update.message
        else -> "下载后校验 SHA-256 和签名"
    }

    SettingsPage(SettingsSection.About, embedded, onBack, subtitle = "版本 ${update.currentVersionName.ifBlank { "1.0" }}（构建 ${update.currentVersionCode}）") {
        item(key = "head") { AppHeader(update) }
        if (showCard && info != null) {
            item(key = "update") {
                UpdateCard(update, actions, onLater = {
                    actions.skipUpdate()
                    revealSkipped = false
                })
            }
        }
        if (info == null && update.error.isNotBlank() && !isChecking) {
            item(key = "error") {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ErrorBlock("检查更新失败：${update.error}", onRetry = ::check)
                    TextButton(onClick = actions::openReleasePage, modifier = Modifier.tvFocusRing(CircleShape)) {
                        Text("在浏览器打开发布页")
                    }
                }
            }
        }
        item(key = "rows") {
            SegmentedGroup(Modifier.padding(top = 12.dp)) {
                ListRow(
                    title = "检查更新",
                    index = 0,
                    count = 5,
                    onClick = {
                        if (info != null) revealSkipped = true else check()
                    },
                    icon = Icons.Filled.Refresh,
                    summary = checkSummary,
                    showChevron = false,
                    trailing = if (isChecking) {
                        { LoadingIndicator(Modifier.size(32.dp)) }
                    } else {
                        null
                    },
                )
                ListRow(
                    title = "隐私说明",
                    index = 1,
                    count = 5,
                    onClick = onPrivacy,
                    icon = Icons.Filled.PrivacyTip,
                    summary = "哪些数据留在本机，哪些会离开",
                )
                ListRow(
                    title = "开源许可",
                    index = 2,
                    count = 5,
                    onClick = onLicenses,
                    icon = Icons.Filled.Code,
                    summary = "AGPL-3.0 · music-lib 与第三方依赖",
                )
                ListRow(
                    title = "项目主页",
                    index = 3,
                    count = 5,
                    onClick = { openUri(PROJECT_URL) },
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    summary = "github.com/mytv-android/myMusic",
                    showChevron = false,
                )
                ListRow(
                    title = "复制诊断信息",
                    index = 4,
                    count = 5,
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("myMusic 诊断信息", actions.diagnostics())))
                            snackbar.showSnackbar("诊断信息已复制")
                        }
                    },
                    icon = Icons.Filled.ContentCopy,
                    iconTone = IconTone.Tertiary,
                    summary = "不含 Cookie、令牌和完整播放地址",
                    showChevron = false,
                )
            }
        }
        item(key = "note") {
            Box(Modifier.padding(top = 16.dp)) {
                InfoNote("Cookie、播放记录和下载文件只保存在本机。同步仅使用你选择的 Gist / Gitee 账号，写入自建歌单数据；应用没有集中式服务器。")
            }
        }
        item(key = "footer") {
            Text(
                "平台层 music-lib / 7cc4467b\n学习项目，请遵守各音乐平台服务条款。",
                Modifier.fillMaxWidth().padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AppHeader(update: UpdateUiState) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "m",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text("myMusic", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.headlineSmall)
        Text(
            "版本 ${update.currentVersionName.ifBlank { "1.0" }}（构建 ${update.currentVersionCode}） · AGPL-3.0",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 新版本卡片：更新说明、下载进度、校验和安装都在这里；安装需要用户在系统对话框确认。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateCard(update: UpdateUiState, actions: MusicActions, onLater: () -> Unit) {
    val info = update.info ?: return
    val scheme = MaterialTheme.colorScheme
    val content = scheme.onTertiaryContainer
    val downloading = update.phase == UpdatePhase.Downloading || update.phase == UpdatePhase.Verifying
    val ready = update.phase == UpdatePhase.Ready
    val primaryColors = ButtonDefaults.buttonColors(containerColor = scheme.tertiary, contentColor = scheme.onTertiary)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.tertiaryContainer)
            .padding(20.dp),
    ) {
        Text("发现新版本 ${info.versionName}（构建 ${info.versionCode}）", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = content)
        Text(
            "${formatSize(info.size)} · ${formatDate(info.publishedAt)}",
            style = MaterialTheme.typography.bodyMedium,
            color = content.copy(alpha = 0.85f),
        )
        if (info.notes.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                info.notes.forEach { note ->
                    Text("· $note", style = MaterialTheme.typography.bodyMedium, color = content)
                }
            }
        }
        if (downloading || ready) {
            LinearWavyProgressIndicator(
                progress = { if (update.phase == UpdatePhase.Downloading) update.progress else 1f },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                color = content,
                trackColor = content.copy(alpha = 0.2f),
            )
            Text(
                "${update.phaseLabel.ifBlank { if (ready) "已下载" else "正在下载" }} ${(update.progress * 100).toInt()}% · 下载后校验 SHA-256 和签名",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.85f),
            )
        } else {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Verified, null, Modifier.size(18.dp), tint = content)
                Text(
                    "下载后校验 SHA-256、包名、版本号和签名，再打开系统安装器。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                )
            }
        }
        if (update.error.isNotBlank()) {
            Box(Modifier.padding(top = 10.dp)) { InfoNote(update.error, "red") }
        }
        if (update.permissionRequired) {
            Box(Modifier.padding(top = 10.dp)) { InfoNote("需要允许本应用安装未知来源的应用。", "amber") }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val textButton: @Composable (String, () -> Unit) -> Unit = { label, onClick ->
                TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(CircleShape)) {
                    Text(label, color = content)
                }
            }
            val mainButton: @Composable (String, () -> Unit) -> Unit = { label, onClick ->
                Button(onClick = onClick, colors = primaryColors, modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(CircleShape)) {
                    Text(label)
                }
            }
            when {
                downloading -> mainButton("取消下载", actions::cancelUpdateDownload)
                ready -> {
                    if (update.permissionRequired) textButton("允许安装", actions::openInstallPermission)
                    mainButton("打开系统安装器", actions::installUpdate)
                }
                update.phase == UpdatePhase.Error -> {
                    textButton("浏览器打开", actions::openReleasePage)
                    mainButton("重试下载", actions::downloadUpdate)
                }
                else -> {
                    textButton("此版本不再提示", onLater)
                    textButton("浏览器打开", actions::openReleasePage)
                    mainButton("下载并安装", actions::downloadUpdate)
                }
            }
        }
    }
}

private fun formatDate(value: String): String = runCatching {
    val instant = Instant.parse(value)
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(instant)
}.getOrDefault(value)
