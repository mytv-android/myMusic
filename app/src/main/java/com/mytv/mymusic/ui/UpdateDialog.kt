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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * 强制更新弹窗：检测到新版本就弹出，不能点“稍后”、点外面或按返回关掉。
 * 下载和校验由 ViewModel 自动开始，校验通过后自动拉起系统安装器；
 * 装上新版本之前这个弹窗一直挡在应用上。
 *
 * @param suppressed 当前正停在“关于与更新”页面时不叠弹窗，由页面里的更新卡片走同样流程
 */
@Composable
fun UpdateDialog(
    update: UpdateUiState,
    suppressed: Boolean,
    actions: MusicActions,
    progress: () -> Float,
) {
    val info = update.info ?: return
    val busy = update.phase == UpdatePhase.Downloading || update.phase == UpdatePhase.Verifying
    val visible = !suppressed &&
        update.phase != UpdatePhase.Idle &&
        update.phase != UpdatePhase.Checking
    if (!visible) return
    val tv = LocalAppWindow.current.isTv
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(tv, update.phase, update.permissionRequired) {
        if (tv) runCatching { actionFocus.requestFocus() }
    }
    AlertDialog(
        // 强制更新：点外面、按返回都不响应，只有装上新版本才会消失
        onDismissRequest = {},
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
                Text(
                    if (busy) "更新完成前无法继续使用应用。" else "此更新必须安装，校验通过后会自动打开系统安装器。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                update.permissionRequired -> Button(
                    onClick = actions::openInstallPermission,
                    modifier = Modifier.focusRequester(actionFocus).tvFocusRing(CircleShape),
                ) { Text("允许安装") }
                update.phase == UpdatePhase.Error -> Button(
                    onClick = actions::downloadUpdate,
                    modifier = Modifier.focusRequester(actionFocus).tvFocusRing(CircleShape),
                ) { Text("重试下载") }
                update.phase == UpdatePhase.Ready -> Button(
                    onClick = actions::installUpdate,
                    modifier = Modifier.focusRequester(actionFocus).tvFocusRing(CircleShape),
                ) { Text("重新打开安装器") }
                else -> Unit
            }
        },
        dismissButton = {},
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
