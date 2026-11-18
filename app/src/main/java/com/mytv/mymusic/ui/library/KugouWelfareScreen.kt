package com.mytv.mymusic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.KugouWelfareUi
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.settings.SettingsNote

/**
 * 酷狗概念版「听歌领会员」：每天领一天的畅听 VIP，可选升级成概念版 VIP（音质更高）。
 * 概念版和普通酷狗音乐不是同一套账号，但本 App 的酷狗登录走的就是概念版 appid，
 * 所以直接用已登录的酷狗账号，不用再登一次。VIP 生效、到期和领取结果都以服务端为准
 * （见 [com.mytv.mymusic.data.native.KugouWelfare]），本机只听歌计时给个参考进度。
 */
@Composable
fun KugouWelfareScreen(
    kugou: KugouWelfareUi,
    autoClaim: Boolean,
    onAutoClaimChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onClaim: () -> Unit,
    onLogin: () -> Unit,
    onBack: () -> Unit,
) {
    val window = LocalAppWindow.current
    val claimFocus = remember { FocusRequester() }
    TvDefaultFocus(claimFocus)
    PageScaffold(
        title = "酷狗概念版听歌领会员",
        subtitle = kugouWelfareHeadline(kugou),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = onRefresh,
                enabled = !kugou.loading,
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
                !kugou.loggedIn -> item(key = "login", contentType = "card") {
                    KugouWelfareLoginCard(onLogin)
                }
                kugou.loading && kugou.info.status < 0 -> item(key = "loading", contentType = "card") {
                    KugouWelfareLoadingCard()
                }
                else -> {
                    item(key = "vip", contentType = "vipCard") {
                        KugouWelfareVipCard(kugou, claimFocus, onClaim)
                    }
                    item(key = "autoHead", contentType = "section") {
                        SectionLabel("自动领取")
                    }
                    item(key = "auto", contentType = "card") { KugouWelfareAutoCard(autoClaim, onAutoClaimChange) }
                    item(key = "note", contentType = "note") {
                        SettingsNote(
                            "领取和升级都打到酷狗概念版的官方活动接口上，会员直接发在当前登录的酷狗账号上，" +
                                "在酷狗概念版客户端里就能用。接口可能变更或限流，领不到时可以到官方客户端里手动领。",
                        )
                    }
                }
            }
            if (kugou.error.isNotBlank()) {
                item(key = "error", contentType = "note") {
                    SettingsNote("获取失败：${kugou.error}，点右上角刷新重试。")
                }
            }
        }
    }
    // 登录状态变化时重新拉一次；领取情况只有服务端算得准
    LaunchedEffect(kugou.loggedIn) { if (kugou.loggedIn) onRefresh() }
}

/** 「我的」页入口：酷狗概念版听歌领会员的状态摘要。 */
@Composable
internal fun KugouWelfareEntryCard(
    kugou: KugouWelfareUi,
    onOpen: () -> Unit,
    index: Int = 0,
    count: Int = 1,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    ListRow(
        title = "酷狗概念版听歌领会员",
        index = index,
        count = count,
        onClick = onOpen,
        modifier = modifier,
        icon = Icons.Filled.CardGiftcard,
        iconTone = when {
            !kugou.loggedIn -> IconTone.Secondary
            kugou.info.vipActive -> IconTone.Primary
            else -> IconTone.Tertiary
        },
        summary = kugouWelfareHeadline(kugou),
        trailing = when {
            !kugou.loggedIn -> null
            kugou.info.vipActive -> {
                { Tag("VIP 中", scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Filled.Check) }
            }
            else -> null
        },
    )
}

// ---------------- 卡片 ----------------

@Composable
private fun KugouWelfareVipCard(
    kugou: KugouWelfareUi,
    focusRequester: FocusRequester?,
    onClaim: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val info = kugou.info
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
                        tint = if (active) scheme.onPrimaryContainer else scheme.onPrimaryContainer,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(info.vipLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
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
                        kugou.todayClaimed -> "今天已经领过了，明天再来"
                        info.ok -> "今天这一份还没领"
                        else -> "暂时不能领取"
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Button(
                    onClick = onClaim,
                    enabled = !kugou.claiming && kugou.loggedIn && !kugou.todayClaimed,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                ) {
                    if (kugou.claiming) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                    } else {
                        Text(if (kugou.todayClaimed) "已领取" else "领取")
                    }
                }
            }
            // 本月领取天数只有服务端给了可解析的记录才显示，不猜
            if (info.monthClaimedDays >= 0) {
                Text(
                    "本月已领 ${info.monthClaimedDays} 天",
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                )
            }
            if (!info.ok && info.loggedIn) {
                Text(
                    "没读到概念版的会员状态，可能是登录态过期或接口变更，点右上角刷新重试。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = dim,
                )
            }
        }
    }
}

@Composable
private fun KugouWelfareAutoCard(autoClaim: Boolean, onAutoClaimChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Surface(shape = shape, color = scheme.surfaceContainerLow, modifier = tvCardFocus(shape)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("启动时自动领取", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "每天第一次打开 App 时领一次，同一天不重复请求。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoClaim,
                onCheckedChange = onAutoClaimChange,
                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
            )
        }
    }
}

@Composable
private fun KugouWelfareLoginCard(onLogin: () -> Unit) {
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
                    Text("先登录酷狗音乐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "领到的畅听 VIP 发在酷狗账号上，扫码登录后即可查看和领取。",
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
private fun KugouWelfareLoadingCard() {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text("正在读取酷狗概念版的会员状态…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ---------------- 工具 ----------------

@Composable
private fun tvCardFocus(shape: Shape): Modifier =
    if (LocalAppWindow.current.isTv) Modifier.tvFocusRing(shape, scale = 1.02f).focusable() else Modifier

/** 入口卡和页头的摘要：VIP 中 > 今日待领 > 已领 > 未登录。 */
internal fun kugouWelfareHeadline(kugou: KugouWelfareUi): String {
    val info = kugou.info
    return when {
        !kugou.loggedIn -> "扫码登录酷狗音乐后即可参与"
        kugou.loading && info.status < 0 -> "正在读取会员状态…"
        info.vipActive -> "${info.vipLabel}有效期至 ${formatVipDate(info.vipExpireAt)}"
        kugou.todayClaimed -> "今天已领取，明天再来"
        info.ok -> "今天这一份还没领"
        else -> "获取失败，点此重试"
    }
}
