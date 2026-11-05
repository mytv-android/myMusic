package com.mytv.mymusic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.ListenTaskState
import com.mytv.mymusic.data.ListenTasks
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.settings.SettingsNote
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 「天天听歌」福利中心：机制对齐汽水音乐的「VIP 天天送」——
 * 每天听歌达标领 VIP 天数，连续听歌奖励更大，天数随时激活续期。
 * 听歌时长由播放进度累计（暂停、拖进度条不计），账本只存本机。
 */
@Composable
fun WelfareScreen(
    listen: ListenTaskState,
    onClaim: (String, (Int) -> Unit) -> Unit,
    onActivate: () -> Unit,
    onBack: () -> Unit,
) {
    val window = LocalAppWindow.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalAppSnackbar.current
    val vipFocus = remember { FocusRequester() }
    TvDefaultFocus(vipFocus)
    val claim: (String) -> Unit = { task ->
        onClaim(task) { days ->
            scope.launch {
                snackbar.showSnackbar(if (days > 0) "已领取 $days 天 VIP" else "今天已经领过了")
            }
        }
    }
    PageScaffold(title = "福利中心", subtitle = welfareHeadline(listen), onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = padding.withGutter(horizontal = window.gutter, extraBottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            item(key = "vip", contentType = "vipCard") { WelfareVipCard(listen, onActivate, vipFocus) }
            item(key = "todayHead", contentType = "section") { SectionLabel("今日听歌") }
            item(key = "today", contentType = "card") { WelfareTodayCard(listen) }
            item(key = "taskHead", contentType = "section") {
                SectionLabel("听歌任务", trailing = { Text("每天 0 点刷新", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) })
            }
            item(key = "task15", contentType = "task") {
                WelfareDailyTask(
                    title = "每日听歌 15 分钟",
                    done = listen.firstDone,
                    claimed = listen.claimedDaily15 == today(),
                    seconds = listen.todaySeconds,
                    targetSeconds = ListenTasks.DAILY_FIRST_TARGET_SECONDS,
                    rewardDays = 1,
                    onClaim = { claim(ListenTasks.DAILY_15) },
                )
            }
            item(key = "task30", contentType = "task") {
                WelfareDailyTask(
                    title = "每日听歌 30 分钟",
                    done = listen.secondDone,
                    claimed = listen.claimedDaily30 == today(),
                    seconds = listen.todaySeconds,
                    targetSeconds = ListenTasks.DAILY_SECOND_TARGET_SECONDS,
                    rewardDays = 1,
                    onClaim = { claim(ListenTasks.DAILY_30) },
                )
            }
            item(key = "taskStreak", contentType = "task") {
                WelfareStreakTask(listen) { claim(ListenTasks.STREAK) }
            }
            item(key = "note", contentType = "note") {
                SettingsNote(
                    "听歌时长只按实际播放累计：暂停、拖动进度条都不算，切歌继续听会接着累计。" +
                        "任务进度和 VIP 天数只存在本机，换设备不互通。",
                )
            }
        }
    }
}

/** 「我的」页的福利中心入口：能领时把天数摆在最右边。 */
@Composable
internal fun WelfareEntryCard(listen: ListenTaskState, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val claimable = listen.claimableDays()
    ListRow(
        title = "福利中心",
        index = 0,
        count = 1,
        onClick = onOpen,
        modifier = Modifier.padding(top = 12.dp),
        icon = Icons.Filled.CardGiftcard,
        iconTone = if (claimable > 0 || listen.vipActive) IconTone.Primary else IconTone.Secondary,
        summary = welfareHeadline(listen),
        trailing = if (claimable > 0) {
            { Tag("$claimable 天", scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Filled.CardGiftcard) }
        } else null,
    )
}

// ---------------- VIP 卡片 ----------------

/**
 * 电视上每张卡片都要能落到焦点：一页全是说明、按钮还禁着时，
 * 遥控器上下键走不了焦点就翻不动页（和设置里的 InfoCard 一个做法）。
 */
@Composable
private fun tvCardFocus(shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (LocalAppWindow.current.isTv) Modifier.tvFocusRing(shape, scale = 1.02f).focusable() else Modifier

@Composable
private fun WelfareVipCard(listen: ListenTaskState, onActivate: () -> Unit, focusRequester: FocusRequester? = null) {
    val scheme = MaterialTheme.colorScheme
    val active = listen.vipActive
    val shape = RoundedCornerShape(28.dp)
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
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (active) scheme.onPrimaryContainer.copy(alpha = 0.12f) else scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.WorkspacePremium,
                        null,
                        Modifier.size(30.dp),
                        tint = scheme.onPrimaryContainer,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("VIP 会员", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(
                        if (active) "有效期至 ${formatVipDate(listen.vipUntil)}" else "还没有生效的 VIP",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (active) scheme.onPrimaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                    )
                }
                if (active) {
                    Tag(
                        "生效中",
                        scheme.onPrimaryContainer.copy(alpha = 0.12f),
                        scheme.onPrimaryContainer,
                        Icons.Filled.Check,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("天数余额 ${listen.vipDays} 天", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        if (listen.vipDays > 0) "激活后从当前到期时间往后续" else "完成下面的听歌任务就能领",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) scheme.onPrimaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onActivate,
                    enabled = listen.vipDays > 0,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                ) {
                    Text(if (active) "续期 ${listen.vipDays} 天" else "激活 ${listen.vipDays} 天")
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val dim = if (active) scheme.onPrimaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant
                val mark = if (active) scheme.onPrimaryContainer else scheme.primary
                val markBg = if (active) scheme.onPrimaryContainer.copy(alpha = 0.12f) else scheme.primaryContainer
                WelfarePerk("无损音源优先", "跨平台优先选无损 / 高码率音源", Icons.Filled.GraphicEq, dim, mark, markBg)
                WelfarePerk("VIP 专属色板", "外观 › 自选颜色里解锁 3 款金属调配色", Icons.Filled.Palette, dim, mark, markBg)
                WelfarePerk("VIP 徽章", "福利中心和「我的」页显示会员标识", Icons.Filled.WorkspacePremium, dim, mark, markBg)
            }
        }
    }
}

@Composable
private fun WelfarePerk(
    title: String,
    summary: String,
    icon: ImageVector,
    dim: Color,
    mark: Color,
    markBg: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(markBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = mark)
        }
        Icon(icon, null, Modifier.size(18.dp), tint = dim)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = dim)
        }
    }
}

// ---------------- 今日进度 ----------------

@Composable
private fun WelfareTodayCard(listen: ListenTaskState) {
    val fraction = (listen.todaySeconds.toFloat() / ListenTasks.DAILY_SECOND_TARGET_SECONDS).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(20.dp)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = tvCardFocus(shape)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日已听 ${listen.todayMinutes} 分钟",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "满 30 分钟领 2 天",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

// ---------------- 任务 ----------------

@Composable
private fun WelfareDailyTask(
    title: String,
    done: Boolean,
    claimed: Boolean,
    seconds: Int,
    targetSeconds: Int,
    rewardDays: Int,
    onClaim: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val listened = seconds.coerceAtMost(targetSeconds)
    val remain = ((targetSeconds - seconds).coerceAtLeast(0) + 59) / 60
    val shape = RoundedCornerShape(20.dp)
    Surface(shape = shape, color = scheme.surfaceContainerLow, modifier = tvCardFocus(shape)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "奖励 $rewardDays 天 VIP",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                WelfareTaskAction(done = done, claimed = claimed, remain = "还差 $remain 分钟", onClaim = onClaim)
            }
            if (!claimed) {
                LinearProgressIndicator(
                    progress = { listened.toFloat() / targetSeconds },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    trackColor = scheme.surfaceContainerHighest,
                )
                Text(
                    "已听 ${listened / 60} / ${targetSeconds / 60} 分钟" + if (done) " · 已达标" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WelfareStreakTask(listen: ListenTaskState, onClaim: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val streak = listen.effectiveStreak()
    val ready = streak >= ListenTasks.STREAK_TARGET_DAYS
    val shape = RoundedCornerShape(20.dp)
    Surface(shape = shape, color = scheme.surfaceContainerLow, modifier = tvCardFocus(shape)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("连续听歌 7 天", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "奖励 ${ListenTasks.STREAK_REWARD_DAYS} 天 VIP，断一天重新计",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                // 领完连续天数归零重新累计，不需要「已领取」态：差几天一直显示差距
                WelfareTaskAction(
                    done = ready,
                    claimed = false,
                    remain = "连续 $streak / ${ListenTasks.STREAK_TARGET_DAYS} 天",
                    onClaim = onClaim,
                )
            }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(ListenTasks.STREAK_TARGET_DAYS) { index ->
                        val filled = index < streak
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (filled) scheme.primary else scheme.surfaceContainerHighest)
                                .then(
                                    if (index == ListenTasks.STREAK_TARGET_DAYS - 1 && !filled) {
                                        Modifier.border(1.dp, scheme.outlineVariant, CircleShape)
                                    } else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (filled) {
                                Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = scheme.onPrimary)
                            }
                        }
                    }
                }
        }
    }
}

/** 任务右侧：未达标显示差距，达标显示「领取」，领过显示「已领取」。 */
@Composable
private fun WelfareTaskAction(done: Boolean, claimed: Boolean, remain: String, onClaim: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    when {
        claimed -> Tag("已领取", scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Filled.Check)
        done -> Button(onClick = onClaim, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("领取") }
        else -> Text(remain, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

// ---------------- 工具 ----------------

private fun today(): String = java.time.LocalDate.now().toString()

/** 今天还能领多少天：两个每日任务加连续奖励。 */
internal fun ListenTaskState.claimableDays(): Int {
    var days = 0
    if (firstDone && claimedDaily15 != today()) days += 1
    if (secondDone && claimedDaily30 != today()) days += 1
    if (effectiveStreak() >= ListenTasks.STREAK_TARGET_DAYS) days += ListenTasks.STREAK_REWARD_DAYS
    return days
}

/** 入口卡和页头的摘要：能领 > 会员中 > 今日进度。 */
internal fun welfareHeadline(listen: ListenTaskState): String = when {
    listen.claimableDays() > 0 -> "今日任务可领 ${listen.claimableDays()} 天 VIP"
    listen.vipActive -> "VIP 有效期至 ${formatVipDate(listen.vipUntil)}"
    listen.firstDone -> "今日听歌达标，明天再来领连续奖励"
    else -> "今日已听 ${listen.todayMinutes} 分钟，听满 15 分钟领 1 天 VIP"
}

private val vipDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

private fun formatVipDate(ms: Long): String =
    vipDateFormatter.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
