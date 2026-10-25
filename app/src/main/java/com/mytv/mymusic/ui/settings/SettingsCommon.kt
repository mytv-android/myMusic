package com.mytv.mymusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalOverlayPadding
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.providerMark
import kotlinx.coroutines.launch

/** 设置分区。“开源许可 / 隐私说明”挂在“关于与更新”下面，双栏时左栏仍高亮“关于与更新”。 */
internal enum class SettingsSection(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tone: IconTone,
) {
    Appearance("settings/appearance", "外观", "布局、主题、配色来源、风格与对比度", Icons.Filled.Palette, IconTone.Primary),
    Playback("settings/playback", "播放与换源", "换源模式、平台优先级与匹配", Icons.Filled.SwapHoriz, IconTone.Primary),
    Equalizer("settings/equalizer", "均衡器", "本机播放音效，不影响下载文件", Icons.Filled.GraphicEq, IconTone.Primary),
    Search("settings/search", "搜索与浏览", "聚合平台与默认浏览来源", Icons.Filled.Search, IconTone.Primary),
    Accounts("settings/accounts", "平台账号", "扫码登录、Cookie 与退出", Icons.Filled.Person, IconTone.Secondary),
    Sync("settings/sync", "音乐库同步", "自建歌单的云端同步", Icons.Filled.Cloud, IconTone.Secondary),
    Storage("settings/storage", "存储", "缓存、下载与占用", Icons.Filled.Storage, IconTone.Tertiary),
    About("settings/about", "关于与更新", "版本、更新、许可与隐私", Icons.Filled.Info, IconTone.Tertiary),
    Licenses("settings/licenses", "开源许可", "应用、上游库与第三方依赖", Icons.Filled.Code, IconTone.Tertiary),
    Privacy("settings/privacy", "隐私说明", "哪些数据留在本机，哪些会离开", Icons.Filled.PrivacyTip, IconTone.Tertiary),
    ;

    val parent: SettingsSection? get() = if (this == Licenses || this == Privacy) About else null

    companion object {
        /** 设置首页 / 双栏左栏的三组。 */
        val groups = listOf(
            listOf(Appearance, Playback, Equalizer, Search),
            listOf(Accounts, Sync),
            listOf(Storage, About),
        )
    }
}

/**
 * 设置子页骨架。手机和 <840dp 平板是独立页面（Large app bar + 返回）；
 * 双栏时作为右栏内容，标题由双栏外壳画，这里只排列表，左右留白也由外壳给。
 */
@Composable
internal fun SettingsPage(
    section: SettingsSection,
    embedded: Boolean,
    onBack: () -> Unit,
    subtitle: String = section.subtitle,
    content: LazyListScope.() -> Unit,
) {
    if (embedded) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalOverlayPadding.current.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
            content = content,
        )
        return
    }
    val gutter = LocalAppWindow.current.gutter
    PageScaffold(title = section.title, subtitle = subtitle, onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = gutter,
                end = gutter,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
            content = content,
        )
    }
}

/** 列表下方的说明文字：不是卡片，左缩进与卡片内文字对齐。 */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 平台标记：圆形底上写平台简称，已登录 / 已启用时用 primary 标出。 */
@Composable
internal fun ProviderMark(name: String, size: Dp = 40.dp, active: Boolean = false) {
    val mark = providerMark(name)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            mark,
            // 平台简称只是头像，行里另有全称，读屏不念
            Modifier.clearAndSetSemantics {},
            style = if (mark.length > 2) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * 纯文字的分段卡片（许可、隐私说明）。电视上可以获得焦点，
 * 否则整页没有可聚焦元素，遥控器无法滚动。
 */
@Composable
internal fun TextCard(
    title: String,
    body: String,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    footer: @Composable (() -> Unit)? = null,
) {
    val shape = segmentShape(index, count)
    val tv = LocalAppWindow.current.isTv
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (tv && footer == null) Modifier.focusable() else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = titleColor)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        footer?.invoke()
    }
}

/** 打开外部链接。电视上常常没有浏览器，打不开时用 Snackbar 说明，而不是崩溃。 */
@Composable
internal fun rememberOpenUri(): (String) -> Unit {
    val handler = LocalUriHandler.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    return remember(handler, snackbar, scope) {
        { uri ->
            runCatching { handler.openUri(uri) }.onFailure {
                scope.launch { snackbar.showSnackbar("这台设备没有可以打开链接的应用") }
            }
        }
    }
}

/** 电视默认焦点：等一帧让列表排好版再请求，目标还没挂上时静默忽略。 */
@Composable
internal fun SettingsTvFocus(requester: FocusRequester, key: Any? = Unit) {
    if (!LocalAppWindow.current.isTv) return
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}
