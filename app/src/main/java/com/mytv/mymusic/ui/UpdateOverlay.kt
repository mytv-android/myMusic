package com.mytv.mymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.update.UpdateInfo
import com.mytv.mymusic.data.update.UpdatePhase
import com.mytv.mymusic.data.update.UpdatePrompt
import com.mytv.mymusic.data.update.UpdateUiState
import com.mytv.mymusic.data.update.formatReleaseDate
import com.mytv.mymusic.data.update.formatSize
import com.mytv.mymusic.ui.adaptive.DialogAvoidsFold
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.tvFocusRing
import kotlinx.coroutines.delay
import java.time.ZoneId

/** 浮层离屏幕左右边和底栏的距离。 */
private val PillMargin = 16.dp
/** 平板、折叠屏和电视上浮层的宽度；手机上撑满可用宽度。 */
private val PillWidth = 420.dp
/** 「暂不更新」后的提醒只短暂停留，之后可从关于页重新打开更新。 */
internal const val UPDATE_REMINDER_DURATION_MS = 5_000L

/**
 * 更新浮层。检查到新版本先由 [UpdatePromptDialog] 弹窗等用户确认，不再自动下载；用户点了「立即更新」
 * 才下载和校验，期间底部只浮一条进度条，应用照常可以播放、搜索、切页。
 *
 * 下载校验完由 ViewModel 自动拉起系统安装器（那一步是系统界面，应用绕不过去），
 * 只有安装被系统拦下、需要用户去授权“安装未知应用”时才出现要用户动手的卡片。
 * 下载失败时浮一条可重试的提示；弹窗里点了「暂不更新」则换成一条 5 秒后自动隐藏的提醒，重启进程会重新弹窗。
 */
@Composable
fun UpdateOverlay(
    update: UpdateUiState,
    prompt: UpdatePrompt,
    actions: MusicActions,
    progress: () -> Float,
    bottomPadding: Dp,
) {
    val info = update.info ?: return
    val failure = update.phase == UpdatePhase.Error && update.error.isNotBlank()
    // Ready 不在这里显示：校验完 ViewModel 立刻拉起系统安装器，拦下时走权限卡片，起不来时走失败提示
    val running = update.phase == UpdatePhase.Downloading || update.phase == UpdatePhase.Verifying
    var reminderVisible by remember(prompt, info.versionCode) { mutableStateOf(prompt == UpdatePrompt.Reminder) }
    LaunchedEffect(prompt, info.versionCode) {
        if (prompt == UpdatePrompt.Reminder) {
            delay(UPDATE_REMINDER_DURATION_MS)
            reminderVisible = false
        }
    }
    val reminder = prompt == UpdatePrompt.Reminder && reminderVisible
    if (!update.permissionRequired && !failure && !running && !reminder) return
    val window = LocalAppWindow.current
    // 只有手机是贴底整宽的一条；平板、折叠屏、电视都靠右下角，别横跨导航栏和播放窗格
    val strip = window.isPhone
    val tv = window.isTv
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(update.permissionRequired, tv) {
        if (update.permissionRequired && tv) runCatching { actionFocus.requestFocus() }
    }
    // 不拦截触摸：只有浮层本身占的位置能点到，其余手势照常落到下面的页面
    Box(Modifier.fillMaxSize()) {
        val slot = Modifier
            .align(if (strip) Alignment.BottomCenter else Alignment.BottomEnd)
            .padding(start = PillMargin, end = PillMargin, bottom = bottomPadding + PillMargin)
            .then(if (strip) Modifier.fillMaxWidth() else Modifier.width(PillWidth))
        when {
            update.permissionRequired -> InstallPermissionCard(info, actions, actionFocus, slot)
            failure -> UpdateFailurePill(update, actions, slot)
            reminder -> UpdateReminderPill(info, actions, slot)
            else -> UpdatePill(info, update, progress, slot)
        }
    }
}

/**
 * 弹窗里点了「暂不更新」后的提醒条：只说有新版本，点一下重新打开弹窗。
 * 5 秒后自动隐藏，之后仍可从关于页更新；重启进程后弹窗会照常再弹一次。
 */
@Composable
private fun UpdateReminderPill(info: UpdateInfo, actions: MusicActions, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .clickable(onClick = actions::reopenUpdatePrompt)
            .tvFocusRing(CircleShape)
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(scheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(20.dp), tint = scheme.onPrimary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "发现新版本 myMusic ${info.versionName}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "点一下打开更新",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = actions::reopenUpdatePrompt) { Text("更新") }
    }
}

/**
 * 启动（或复查）发现新版本时的更新弹窗。强制但不阻断：只有「立即更新」和「暂不更新」两个选择，
 * 下载交给用户点，装不装得起来也由用户定，车机上不会再自动下载完就弹一次失败的安装。
 */
@Composable
fun UpdatePromptDialog(info: UpdateInfo, actions: MusicActions) {
    val scheme = MaterialTheme.colorScheme
    val tv = LocalAppWindow.current.isTv
    val confirmFocus = remember { FocusRequester() }
    LaunchedEffect(tv) { if (tv) runCatching { confirmFocus.requestFocus() } }
    val zone = remember { ZoneId.systemDefault() }
    val release = remember(info.size, info.publishedAt, zone) {
        buildString {
            append(formatSize(info.size))
            if (info.publishedAt.isNotBlank()) append(" · ${formatReleaseDate(info.publishedAt, zone)}")
        }
    }
    AlertDialog(
        onDismissRequest = actions::postponeUpdate,
        modifier = Modifier.widthIn(max = 440.dp),
        title = {
            DialogAvoidsFold()
            Text("发现新版本 myMusic ${info.versionName}")
        },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(release, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                if (info.notes.isEmpty()) {
                    Text(
                        "下载后校验 SHA-256、包名和版本号，然后自动打开系统安装器。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    info.notes.forEach { note ->
                        Text("· $note", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = actions::downloadUpdate,
                modifier = Modifier.focusRequester(confirmFocus).tvFocusRing(RoundedCornerShape(20.dp)),
            ) { Text("立即更新") }
        },
        dismissButton = {
            TextButton(
                onClick = actions::postponeUpdate,
                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
            ) { Text("暂不更新") }
        },
    )
}

/** 下载中的胶囊：版本号、波浪进度和百分比；只做提示，不可聚焦，不打断当前页面。 */
@Composable
private fun UpdatePill(info: UpdateInfo, update: UpdateUiState, progress: () -> Float, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fraction by remember(update.phase, progress) {
        derivedStateOf { if (update.phase == UpdatePhase.Verifying) 1f else progress().coerceIn(0f, 1f) }
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(scheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(20.dp), tint = scheme.onPrimary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (update.phase == UpdatePhase.Verifying) "正在校验 myMusic ${info.versionName}" else "正在下载 myMusic ${info.versionName}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            "${(fraction * 100).toInt()}%",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
            maxLines = 1,
        )
    }
}

/** 下载失败的提示：说明原因，可以重试，也可以先收起继续用。 */
@Composable
private fun UpdateFailurePill(update: UpdateUiState, actions: MusicActions, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.errorContainer)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(22.dp), tint = scheme.onErrorContainer)
        Column(Modifier.weight(1f)) {
            Text(
                "更新没有完成",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onErrorContainer,
            )
            Text(
                update.error,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onErrorContainer.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = actions::downloadUpdate) {
            Text("重试", color = scheme.onErrorContainer, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = actions::dismissUpdate) {
            Icon(Icons.Filled.Close, "关闭更新提示", tint = scheme.onErrorContainer)
        }
    }
}

/** 安装被系统拦下：更新包已就绪，需要用户去系统设置里允许本应用安装未知来源的应用。 */
@Composable
private fun InstallPermissionCard(
    info: UpdateInfo,
    actions: MusicActions,
    actionFocus: FocusRequester,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(22.dp), tint = scheme.onPrimary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "还差一步",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                )
                Text(
                    "允许 myMusic 安装更新",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        Text(
            "myMusic ${info.versionName} 已下载并校验完成。Android 要求先允许本应用安装未知来源应用：" +
                "在系统设置里打开开关，返回应用后安装会自动继续。",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = actions::dismissUpdate) { Text("稍后") }
            Button(
                onClick = actions::openInstallPermission,
                modifier = Modifier.focusRequester(actionFocus).tvFocusRing(CircleShape),
            ) { Text("去系统设置") }
        }
    }
}
