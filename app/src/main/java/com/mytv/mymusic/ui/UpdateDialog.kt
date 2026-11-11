package com.mytv.mymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.update.UpdatePhase
import com.mytv.mymusic.data.update.UpdateUiState
import com.mytv.mymusic.data.update.formatSize
import com.mytv.mymusic.ui.adaptive.DialogAvoidsFold
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.tvFocusRing

/** 更新说明最多列几条，其余折成一句总数；和 myHotpot 的更新卡片一致。 */
private const val UPDATE_NOTE_LIMIT = 6

/**
 * 强制更新弹窗：检测到新版本就弹出，不能点“稍后”、点外面或按返回关掉。
 * 下载和校验由 ViewModel 自动开始，校验通过后自动拉起系统安装器；
 * 装上新版本之前这个弹窗一直挡在应用上，下载期间同样不能用应用。
 *
 * 版式参考 myHotpot 的更新卡片： eyebrow + 大版本号、旧版本划掉到新版本、
 * 菱形项目符号的更新说明、波浪进度条和百分比；按钮只在需要用户动手时出现。
 */
@Composable
fun UpdateDialog(
    update: UpdateUiState,
    actions: MusicActions,
    progress: () -> Float,
) {
    val info = update.info ?: return
    val visible = update.phase != UpdatePhase.Idle && update.phase != UpdatePhase.Checking
    if (!visible) return
    val busy = update.phase == UpdatePhase.Downloading || update.phase == UpdatePhase.Verifying
    val ready = update.phase == UpdatePhase.Ready
    val tv = LocalAppWindow.current.isTv
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(tv, update.phase, update.permissionRequired) {
        if (tv) runCatching { actionFocus.requestFocus() }
    }
    AlertDialog(
        // 强制更新：点外面、按返回都不响应，只有装上新版本才会消失
        onDismissRequest = {},
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        title = {
            DialogAvoidsFold()
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "发现新版本",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "myMusic ${info.versionName}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VersionJump(update)
                if (info.notes.isNotEmpty()) {
                    ReleaseNotes(info.notes)
                }
                if (busy || ready) {
                    UpdateDialogProgress(update.phase, update.phaseLabel, progress)
                }
                Text(
                    when {
                        ready -> "校验通过，正在打开系统安装器。没有出现的话，点下面的按钮重新打开。"
                        busy -> "更新完成前无法继续使用应用。"
                        else -> "此更新必须安装，下载会自动开始，完成前无法继续使用应用。"
                    },
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
                ready -> Button(
                    onClick = actions::installUpdate,
                    modifier = Modifier.focusRequester(actionFocus).tvFocusRing(CircleShape),
                ) { Text("重新打开安装器") }
                else -> Unit
            }
        },
        dismissButton = {},
    )
}

/** 旧版本划掉、箭头、新版本放大；包大小和构建号在下面一行。 */
@Composable
private fun VersionJump(update: UpdateUiState) {
    val info = update.info ?: return
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                update.currentVersionName.ifBlank { "当前版本" },
                style = MaterialTheme.typography.titleMedium.copy(textDecoration = TextDecoration.LineThrough),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = scheme.onSurfaceVariant,
            )
            Text(
                info.versionName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = scheme.primary,
                maxLines = 1,
            )
        }
        Text(
            "构建 ${info.versionCode} · ${formatSize(info.size)}",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** 更新说明：最多 [UPDATE_NOTE_LIMIT] 条，超出折成一句总数；内容多时自身滚动。 */
@Composable
private fun ReleaseNotes(notes: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        notes.take(UPDATE_NOTE_LIMIT).forEach { note ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .padding(top = 7.dp)
                        .size(6.dp)
                        .rotate(45f)
                        .background(scheme.primary, RoundedCornerShape(25)),
                )
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
            }
        }
        if (notes.size > UPDATE_NOTE_LIMIT) {
            Text(
                "等 ${notes.size} 项更新",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateDialogProgress(phase: UpdatePhase, label: String, progress: () -> Float) {
    val percent by remember(phase, progress) {
        derivedStateOf {
            if (phase == UpdatePhase.Downloading) (progress().coerceIn(0f, 1f) * 100).toInt() else 100
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearWavyProgressIndicator(
            progress = { if (phase == UpdatePhase.Downloading) progress() else 1f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${label.ifBlank { if (phase == UpdatePhase.Ready) "校验通过，正在打开系统安装器" else "正在下载" }} $percent%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
