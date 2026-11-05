package com.mytv.mymusic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.native.SodaWelfareInfo
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.settings.SettingsNote
import com.mytv.mymusic.ui.SodaWelfareUi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 汽水音乐「天天送会员」：活动期内每天打开汽水 App 听歌就能领 1 天免费 VIP，
 * 直接领到用户的汽水账号上。任务阈值、领取情况和会员到期都以汽水服务端为准
 * （见 [com.mytv.mymusic.data.native.SodaWelfare]），本机只听歌计时给个参考进度。
 * 未登录汽水时引导去平台账号扫码。
 */
@Composable
fun WelfareScreen(
    soda: SodaWelfareUi,
    todayMinutes: Int,
    onRefresh: () -> Unit,
    onApply: () -> Unit,
    onLogin: () -> Unit,
    onBack: () -> Unit,
) {
    val window = LocalAppWindow.current
    val vipFocus = remember { FocusRequester() }
    TvDefaultFocus(vipFocus)
    PageScaffold(
        title = "汽水天天送会员",
        subtitle = welfareHeadline(soda, todayMinutes),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = onRefresh,
                enabled = !soda.loading,
                modifier = Modifier.tvFocusRing(CircleShape),
            ) {
                Icon(Icons.Filled.Refresh, "刷新")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = padding.withGutter(horizontal = window.gutter, extraBottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            when {
                !soda.loggedIn -> item(key = "login", contentType = "card") {
                    WelfareLoginCard(onLogin)
                }
                soda.loading && soda.info.statusCode < 0 -> item(key = "loading", contentType = "card") {
                    WelfareLoadingCard()
                }
                else -> {
                    item(key = "vip", contentType = "vipCard") {
                        WelfareVipCard(soda, vipFocus, onApply)
                    }
                    // 听歌时长任务只有一部分账号有，没配阈值的账号不显示这一段
                    if (soda.info.hasTasks) {
                        item(key = "taskHead", contentType = "section") {
                            SectionLabel("听歌任务", trailing = {
                                Text(
                                    "每天 0 点刷新",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            })
                        }
                        item(key = "tasks", contentType = "card") { WelfareTaskCard(soda, todayMinutes) }
                    }
                    item(key = "rules", contentType = "card") { WelfareRulesCard(soda.info) }
                    item(key = "note", contentType = "note") {
                        SettingsNote(
                            "听歌时长由汽水音乐服务端累计：在本 App 里听歌会上报播放记录，" +
                                "在汽水官方 App 里听的同样算。领到的免费 VIP 发在当前登录的汽水账号上，多端通用。",
                        )
                    }
                }
            }
            if (soda.error.isNotBlank()) {
                item(key = "error", contentType = "note") {
                    SettingsNote("获取失败：${soda.error}，点右上角刷新重试。")
                }
            }
        }
    }
    // 登录状态变化时重新拉一次；领取情况只有服务端算得准
    LaunchedEffect(soda.loggedIn) { if (soda.loggedIn) onRefresh() }
}

/** 「我的」页入口：汽水天天送会员的状态摘要。 */
@Composable
internal fun WelfareEntryCard(soda: SodaWelfareUi, todayMinutes: Int, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListRow(
        title = "汽水天天送会员",
        index = 0,
        count = 1,
        onClick = onOpen,
        modifier = Modifier.padding(top = 12.dp),
        icon = Icons.Filled.CardGiftcard,
        iconTone = when {
            !soda.loggedIn -> IconTone.Secondary
            soda.info.vipActive -> IconTone.Primary
            else -> IconTone.Tertiary
        },
        summary = welfareHeadline(soda, todayMinutes),
        trailing = when {
            !soda.loggedIn -> null
            soda.info.vipActive -> {
                { Tag("VIP 中", scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Filled.Check) }
            }
            else -> null
        },
    )
}

// ---------------- 卡片 ----------------

@Composable
private fun WelfareVipCard(
    soda: SodaWelfareUi,
    focusRequester: FocusRequester?,
    onApply: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val info = soda.info
    val active = info.vipActive
    val shape = RoundedCornerShape(28.dp)
    val dim = if (active) scheme.onPrimaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant
    Surface(
        shape = shape,
        color = if (active) scheme.primaryContainer else scheme.surfaceContainerLow,
        contentColor = if (active) scheme.onPrimaryContainer else scheme.onSurface,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(tvCardFocus(shape)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .background(if (active) scheme.onPrimaryContainer.copy(alpha = 0.12f) else scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.CardGiftcard,
                        null,
                        Modifier.size(28.dp),
                        tint = scheme.onPrimaryContainer,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("免费 VIP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(
                        if (active) "有效期至 ${formatVipDate(info.vipExpireAt)}" else "今天还没有免费 VIP",
                        style = MaterialTheme.typography.bodyMedium,
                        color = dim,
                    )
                }
                if (active) {
                    Tag("生效中", scheme.onPrimaryContainer.copy(alpha = 0.12f), scheme.onPrimaryContainer, Icons.Filled.Check)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        info.todayClaimed -> "今天已经领过了，明天再来"
                        info.claimable -> "今天这一份还没领"
                        !info.activityActive -> "活动不在期内"
                        else -> "暂时不能领取"
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Button(
                    onClick = onApply,
                    enabled = !soda.applying && info.claimable,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                ) {
                    if (soda.applying) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                    } else {
                        Text(if (info.todayClaimed) "已领取" else "领取")
                    }
                }
            }
            val period = info.activityPeriod
            if (period.isNotBlank()) {
                Text(
                    "活动期 $period · 已领 ${info.appliedCount} 天",
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                )
            }
            if (!info.activityActive && info.ok) {
                Text(
                    "汽水没有返回进行中的天天送活动，可能活动已结束或账号不在受邀范围。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = dim,
                )
            }
        }
    }
}

@Composable
private fun WelfareTaskCard(soda: SodaWelfareUi, todayMinutes: Int) {
    val scheme = MaterialTheme.colorScheme
    val info = soda.info
    val minutes = info.taskMinutes
    val shape = RoundedCornerShape(20.dp)
    Surface(shape = shape, color = scheme.surfaceContainerLow, modifier = tvCardFocus(shape)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            minutes.forEachIndexed { index, target ->
                val done = index < info.tasksFinished
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape)
                            .background(if (done) scheme.primary else scheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) {
                            Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = scheme.onPrimary)
                        } else {
                            Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "听满 $target 分钟",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (done) "已完成" else "本机已听 ${todayMinutes.coerceAtMost(target)} / $target 分钟",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                if (index != minutes.lastIndex) {
                    LinearProgressIndicator(
                        progress = { (todayMinutes.toFloat() / target).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        trackColor = scheme.surfaceContainerHighest,
                    )
                }
            }
        }
    }
}

/** 活动规则：服务端原文，默认收起。 */
@Composable
private fun WelfareRulesCard(info: SodaWelfareInfo) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = shape, color = scheme.surfaceContainerLow, modifier = tvCardFocus(shape)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .tvFocusRing(shape)
                    .focusable()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("活动规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "每天打开汽水 App 听歌即可获得 1 天免费 VIP",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                )
            }
            if (expanded && info.rules.isNotBlank()) {
                Text(
                    info.rulesTitle.ifBlank { "VIP天天送活动规则" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    info.rules,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WelfareLoginCard(onLogin: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Surface(shape = shape, color = scheme.surfaceContainerLow, modifier = tvCardFocus(shape)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.QrCode2, null, Modifier.size(26.dp), tint = scheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text("先登录汽水音乐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "免费 VIP 发在汽水账号上，扫码登录后即可查看和领取。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onLogin, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                Text("去扫码登录")
            }
        }
    }
}

@Composable
private fun WelfareLoadingCard() {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text("正在读取汽水的任务状态…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ---------------- 工具 ----------------

@Composable
private fun tvCardFocus(shape: Shape): Modifier =
    if (LocalAppWindow.current.isTv) Modifier.tvFocusRing(shape, scale = 1.02f).focusable() else Modifier

/** 入口卡和页头的摘要：VIP 中 > 今日待领 > 已领 > 未登录。 */
internal fun welfareHeadline(soda: SodaWelfareUi, todayMinutes: Int = 0): String {
    val info = soda.info
    return when {
        !soda.loggedIn -> "扫码登录汽水音乐后即可参与"
        soda.loading && info.statusCode < 0 -> "正在读取任务状态…"
        info.vipActive -> "免费 VIP 有效期至 ${formatVipDate(info.vipExpireAt)}"
        info.todayClaimed -> "今天已领取，明天再来"
        info.claimable -> "今天这一份还没领"
        info.ok && info.hasTasks && !info.allTasksDone ->
            "今日任务 ${info.tasksFinished} / ${info.tasksTotal}，本机已听 $todayMinutes 分钟"
        info.ok -> "暂时没有可领的免费 VIP"
        else -> "获取失败，点此重试"
    }
}

private val vipDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

internal fun formatVipDate(ms: Long): String =
    vipDateFormatter.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
