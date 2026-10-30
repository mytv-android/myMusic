package com.mytv.mymusic.ui.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.SegmentedGroup
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val PROJECT_URL = "https://github.com/mytv-android/myMusic"
private const val TELEGRAM_URL = "https://t.me/mytv_android_channel"

/**
 * 关于与更新：应用信息、新版本卡片（下载进度、校验、安装都在卡片里完成）、
 * 检查更新 / 隐私 / 许可 / 项目主页 / 群 / 复制诊断。
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
    progress: () -> Float = { update.progress },
) {
    val openUri = rememberOpenUri()
    val clipboard = LocalClipboard.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val info = update.info
    val showCard = info != null
    val isChecking = checking || update.phase == UpdatePhase.Checking

    fun check() {
        if (checking) return
        scope.launch {
            checking = true
            val result = actions.checkUpdatesNow()
            checking = false
            if (result.info == null && result.error.isBlank()) {
                snackbar.showSnackbar(result.message.ifBlank { "已是最新版本" })
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
        item(key = "head", contentType = "appHeader") { AppHeader(update.currentVersionName, update.currentVersionCode) }
        if (showCard && info != null) {
            item(key = "update", contentType = "updateCard") {
                UpdateCard(update, actions, progress)
            }
        }
        if (info == null && update.error.isNotBlank() && !isChecking) {
            item(key = "error", contentType = "error") {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ErrorBlock("检查更新失败：${update.error}", onRetry = ::check)
                    TextButton(onClick = actions::openReleasePage, modifier = Modifier.tvFocusRing(CircleShape)) {
                        Text("在浏览器打开发布页")
                    }
                }
            }
        }
        item(key = "rows", contentType = "settingsGroup") {
            SegmentedGroup(Modifier.padding(top = 12.dp)) {
                ListRow(
                    title = "检查更新",
                    index = 0,
                    count = 6,
                    onClick = ::check,
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
                    count = 6,
                    onClick = onPrivacy,
                    icon = Icons.Filled.PrivacyTip,
                    summary = "哪些数据留在本机，哪些会离开",
                )
                ListRow(
                    title = "开源许可",
                    index = 2,
                    count = 6,
                    onClick = onLicenses,
                    icon = Icons.Filled.Code,
                    summary = "AGPL-3.0 · music-lib 与第三方依赖",
                )
                ListRow(
                    title = "项目主页",
                    index = 3,
                    count = 6,
                    onClick = { openUri(PROJECT_URL) },
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    summary = "github.com/mytv-android/myMusic",
                    showChevron = false,
                )
                ListRow(
                    title = "Telegram 群组",
                    index = 4,
                    count = 6,
                    onClick = { openUri(TELEGRAM_URL) },
                    icon = Icons.Filled.Group,
                    summary = "t.me/mytv_android_channel",
                    showChevron = false,
                )
                ListRow(
                    title = "复制诊断信息",
                    index = 5,
                    count = 6,
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
        item(key = "sodam", contentType = "creditCard") {
            Box(Modifier.padding(top = 12.dp)) {
                SodamCredit(note = "汽水会员整曲的签名服务与协议来自 SodaM（AGPL-3.0）")
            }
        }
        item(key = "links", contentType = "linksCard") { LinksCard(openUri) }
        item(key = "note", contentType = "infoNote") {
            Box(Modifier.padding(top = 16.dp)) {
                InfoNote("Cookie、播放记录和下载文件只保存在本机。同步仅使用你选择的 Gist / Gitee 账号，写入自建歌单数据；应用没有集中式服务器。")
            }
        }
        item(key = "footer", contentType = "footer") {
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

/**
 * 主页与群二维码：本机用同一套编码器生成，不依赖外部图片服务。
 * 手机上点一下直接打开链接；电视上常常没有浏览器，这时用手机扫码。
 */
@Composable
private fun LinksCard(openUri: (String) -> Unit) {
    val window = LocalAppWindow.current
    // 上限按设备给，实际尺寸跟着格子宽度收，窄屏上也不会顶出格外
    val qrSize = when {
        window.isTv -> 240.dp
        window.isPhone -> 150.dp
        else -> 200.dp
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
    ) {
        Text("扫码访问", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "点按打开链接，或用手机扫二维码。",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 两个格子按二维码上限收窄，宽屏上不会各自摊成一片空白
        Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.widthIn(max = qrSize * 2 + 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LinkQrCell("项目主页", "github.com/mytv-android/myMusic", PROJECT_URL, qrSize, Modifier.weight(1f), openUri)
                LinkQrCell("Telegram 群组", "t.me/mytv_android_channel", TELEGRAM_URL, qrSize, Modifier.weight(1f), openUri)
            }
        }
    }
}

/** 单个二维码格子：浅底深码，扫码器才认，所以不跟随主题配色。 */
@Composable
private fun LinkQrCell(
    title: String,
    host: String,
    url: String,
    qrSize: Dp,
    modifier: Modifier = Modifier,
    openUri: (String) -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .tvFocusRing(shape, scale = 1.02f)
            .clickable { openUri(url) }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .sizeIn(maxWidth = qrSize, maxHeight = qrSize)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            QrCodeImage(url, Modifier.fillMaxSize(), description = "$title 二维码")
        }
        Text(
            title,
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            host,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AppHeader(versionName: String, versionCode: Long) {
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
            "版本 ${versionName.ifBlank { "1.0" }}（构建 $versionCode） · AGPL-3.0",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 新版本卡片：更新说明、下载进度、校验和安装都在这里；下载和安装自动进行，系统对话框确认无法省略。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateCard(update: UpdateUiState, actions: MusicActions, progress: () -> Float) {
    val info = update.info ?: return
    val scheme = MaterialTheme.colorScheme
    val content = scheme.onTertiaryContainer
    val downloading = update.phase == UpdatePhase.Downloading || update.phase == UpdatePhase.Verifying
    val ready = update.phase == UpdatePhase.Ready
    val primaryColors = ButtonDefaults.buttonColors(containerColor = scheme.tertiary, contentColor = scheme.onTertiary)
    val zone = ZoneId.systemDefault()
    val releaseSummary = remember(info.size, info.publishedAt, zone) {
        "${formatSize(info.size)} · ${formatDate(info.publishedAt, zone)}"
    }
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
            releaseSummary,
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
            UpdateDownloadProgress(update.phase, update.phaseLabel, progress, content)
        } else {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Verified, null, Modifier.size(18.dp), tint = content)
                Text(
                    "下载后自动校验 SHA-256、包名、版本号和签名，然后自动打开系统安装器。",
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
        if (!downloading) {
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
                    ready -> {
                        if (update.permissionRequired) textButton("允许安装", actions::openInstallPermission)
                        mainButton("重新打开安装器", actions::installUpdate)
                    }
                    update.phase == UpdatePhase.Error -> {
                        textButton("浏览器打开", actions::openReleasePage)
                        mainButton("重试下载", actions::downloadUpdate)
                    }
                    else -> mainButton("立即下载", actions::downloadUpdate)
                }
            }
        }
    }
}

private fun formatDate(value: String, zone: ZoneId): String = runCatching {
    DateTimeFormatter.ISO_LOCAL_DATE.withZone(zone).format(Instant.parse(value))
}.getOrDefault(value)

/** 只有进度绘制和百分比文字观察下载数值，版本说明与按钮不跟着刷新。 */
@Composable
private fun UpdateDownloadProgress(phase: UpdatePhase, label: String, progress: () -> Float, color: Color) {
    LinearWavyProgressIndicator(
        progress = { if (phase == UpdatePhase.Downloading) progress() else 1f },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        color = color,
        trackColor = color.copy(alpha = 0.2f),
    )
    UpdateProgressLabel(phase, label, progress, color)
}

@Composable
private fun UpdateProgressLabel(phase: UpdatePhase, label: String, progress: () -> Float, color: Color) {
    val percent by remember(phase, progress) {
        derivedStateOf { if (phase == UpdatePhase.Downloading) (progress().coerceIn(0f, 1f) * 100).toInt() else 100 }
    }
    Text(
        "${label.ifBlank { if (phase == UpdatePhase.Ready) "已下载" else "正在下载" }} $percent% · 下载后校验 SHA-256 和签名",
        Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = color.copy(alpha = 0.85f),
    )
}
