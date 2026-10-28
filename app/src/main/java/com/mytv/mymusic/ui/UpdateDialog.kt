package com.mytv.mymusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.update.UpdatePhase
import com.mytv.mymusic.data.update.UpdateUiState
import com.mytv.mymusic.data.update.formatSize
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.tvFocusRing

/**
 * 启动时发现新版本的弹窗：展示版本说明，下载中显示进度。
 * 下载并校验完成后由 ViewModel 直接拉起系统安装器或申请安装权限，不需要用户再点按钮。
 *
 * @param skippedCode 用户在“关于与更新”里选了“此版本不再提示”的构建号
 * @param suppressed 当前正停在“关于与更新”页面时不再叠一层弹窗
 */
@Composable
fun UpdateDialog(
    update: UpdateUiState,
    skippedCode: Long,
    suppressed: Boolean,
    actions: MusicActions,
    progress: () -> Float,
) {
    val info = update.info ?: return
    var dismissedCode by rememberSaveable { mutableLongStateOf(0L) }
    val busy = update.phase == UpdatePhase.Downloading || update.phase == UpdatePhase.Verifying
    val visible = !suppressed &&
        info.versionCode != skippedCode &&
        info.versionCode != dismissedCode &&
        update.phase != UpdatePhase.Idle &&
        update.phase != UpdatePhase.Checking
    if (!visible) return
    val tv = LocalAppWindow.current.isTv
    val confirmFocus = remember { FocusRequester() }
    LaunchedEffect(tv, update.phase) {
        if (tv && !busy) runCatching { confirmFocus.requestFocus() }
    }
    AlertDialog(
        // 下载中不让点外面或按返回关掉，避免进度消失；要停就点“取消下载”
        onDismissRequest = { if (!busy) dismissedCode = info.versionCode },
        title = {
            Text(
                "发现新版本 ${info.versionName}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "构建 ${info.versionCode} · ${formatSize(info.size)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotEmpty()) {
                    Column(
                        Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        info.notes.forEach { note ->
                            Text("· $note", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (busy || update.phase == UpdatePhase.Ready) {
                    UpdateDialogProgress(update.phase, update.phaseLabel, progress)
                }
                if (update.permissionRequired) {
                    Text(
                        "允许本应用安装未知来源的应用后会自动继续安装。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (update.error.isNotBlank()) {
                    Text(
                        update.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when {
                busy -> TextButton(
                    onClick = actions::cancelUpdateDownload,
                    modifier = Modifier.tvFocusRing(CircleShape),
                ) { Text("取消下载") }
                update.phase == UpdatePhase.Ready -> Button(
                    onClick = actions::installUpdate,
                    modifier = Modifier.focusRequester(confirmFocus).tvFocusRing(CircleShape),
                ) { Text("安装") }
                else -> Button(
                    onClick = actions::downloadUpdate,
                    modifier = Modifier.focusRequester(confirmFocus).tvFocusRing(CircleShape),
                ) { Text(if (update.phase == UpdatePhase.Error) "重试下载" else "下载并安装") }
            }
        },
        dismissButton = {
            when {
                busy -> Unit
                update.phase == UpdatePhase.Ready && update.permissionRequired -> TextButton(
                    onClick = actions::openInstallPermission,
                    modifier = Modifier.tvFocusRing(CircleShape),
                ) { Text("允许安装") }
                else -> TextButton(
                    onClick = { dismissedCode = info.versionCode },
                    modifier = Modifier.tvFocusRing(CircleShape),
                ) { Text("稍后") }
            }
        },
    )
}

@Composable
private fun UpdateDialogProgress(phase: UpdatePhase, label: String, progress: () -> Float) {
    val percent by remember(phase, progress) {
        derivedStateOf {
            if (phase == UpdatePhase.Downloading) (progress().coerceIn(0f, 1f) * 100).toInt() else 100
        }
    }
    Column(Modifier.padding(top = 4.dp)) {
        LinearWavyProgressIndicator(
            progress = { if (phase == UpdatePhase.Downloading) progress() else 1f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${label.ifBlank { if (phase == UpdatePhase.Ready) "校验通过，正在打开系统安装器" else "正在下载" }} $percent%",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
