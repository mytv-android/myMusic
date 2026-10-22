package com.mytv.mymusic.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.aggregate.MatchStrictness
import com.mytv.mymusic.aggregate.PlaybackMode
import com.mytv.mymusic.data.sync.SyncUiState
import com.mytv.mymusic.data.update.UpdateUiState
import com.mytv.mymusic.playback.EqPresets
import com.mytv.mymusic.playback.EqualizerUiState
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalOverlayPadding
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.SegmentedGroup
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.formatBytes
import com.mytv.mymusic.ui.providerName
import com.mytv.mymusic.ui.relativeTime

/** 设置列表每项下方显示的当前值，以及“关于”上的新版本标记。 */
@Immutable
internal data class SettingsOverview(
    val values: Map<SettingsSection, String>,
    val updateAvailable: Boolean,
)

internal fun settingsOverview(
    state: MusicUiState,
    sync: SyncUiState,
    update: UpdateUiState,
    equalizer: EqualizerUiState,
    cacheBytes: Long,
    downloadsBytes: Long,
): SettingsOverview {
    val appearance = state.appearance
    val searchable = state.providers.filter { it.search }.map { it.id }
    val enabled = state.aggregation.enabledOrDefault(searchable).size
    val eqLabel = EqPresets.byId(equalizer.presetId)?.label ?: "自定义"
    val logged = state.providers.filter { !state.cookies[it.id].isNullOrBlank() }
    val account = sync.account
    val info = update.info
    val updateAvailable = info != null && info.versionCode != state.skippedUpdateCode
    val version = "版本 ${update.currentVersionName.ifBlank { "1.0" }}（构建 ${update.currentVersionCode}）"
    return SettingsOverview(
        values = mapOf(
            SettingsSection.Appearance to "${appearance.colorSource.label} · ${appearance.browseStyle.label} · ${themeLabel(appearance.theme)}",
            SettingsSection.Playback to playbackModeSummary(state.aggregation.playbackMode) +
                if (state.aggregation.matchStrictness == MatchStrictness.Strict) " · 严格匹配" else "",
            SettingsSection.Equalizer to "$eqLabel · ${if (equalizer.enabled) "已开启" else "已关闭"}",
            SettingsSection.Search to "$enabled 个聚合平台 · 默认${providerName(state.providers, state.providerId)}",
            SettingsSection.Accounts to when {
                logged.isEmpty() -> "未登录任何平台"
                logged.size <= 2 -> "已登录 ${logged.joinToString("、") { it.name }}"
                else -> "已登录 ${logged.size} 个平台"
            },
            SettingsSection.Sync to if (account != null) {
                "${sync.providerLabel} · ${relativeTime(account.lastSyncedAt).ifBlank { "尚未同步" }}"
            } else {
                "未连接 · 本机优先"
            },
            SettingsSection.Storage to "缓存 ${formatBytes(cacheBytes)} · 下载 ${formatBytes(downloadsBytes)}",
            SettingsSection.About to if (updateAvailable) "$version · 发现新版本 ${info?.versionName.orEmpty()}（构建 ${info?.versionCode ?: 0}）" else version,
        ),
        updateAvailable = updateAvailable,
    )
}

internal fun themeLabel(theme: String): String = when (theme) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

private fun playbackModeSummary(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.Auto -> "自动换源"
    PlaybackMode.Priority -> "按优先平台"
    PlaybackMode.Pinned -> "固定平台"
}

/** 手机（和 <840dp 平板）的设置首页：三组分段列表，每项下方是当前值，点进各自的子页。 */
@Composable
internal fun SettingsHomeScreen(
    overview: SettingsOverview,
    onOpen: (SettingsSection) -> Unit,
    onBack: (() -> Unit)?,
) {
    val gutter = LocalAppWindow.current.gutter
    PageScaffold(title = "设置", subtitle = "设置即改即存，不需要保存按钮", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = gutter,
                end = gutter,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding(),
            ),
        ) {
            item(key = "groups") { SettingsGroups(overview, selected = null, twoPane = false, onOpen = onOpen) }
            item(key = "note") {
                SettingsNote("Cookie 与同步令牌只保存在本机，不会进入日志或同步文件。", Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * ≥840dp 平板和电视：左边设置列表、右边当前分区，不跳页。
 * 电视上焦点在左栏移动时右栏跟着换；在右栏按返回键先回到左栏。
 */
@Composable
internal fun SettingsTwoPane(
    overview: SettingsOverview,
    initial: SettingsSection,
    detail: @Composable (section: SettingsSection, open: (SettingsSection) -> Unit) -> Unit,
) {
    val window = LocalAppWindow.current
    val tv = window.isTv
    var selected by rememberSaveable(initial) { mutableStateOf(initial) }
    val listed = selected.parent ?: selected
    val requesters = remember { SettingsSection.groups.flatten().associateWith { FocusRequester() } }
    val backFocus = remember { FocusRequester() }
    var detailFocused by remember { mutableStateOf(false) }
    val bottom = LocalOverlayPadding.current.calculateBottomPadding()

    fun focusList(section: SettingsSection) {
        if (tv) runCatching { requesters.getValue(section).requestFocus() }
    }

    // 电视：进来时焦点落在选中项上（直接打开设置时就是第一项“外观”）
    if (tv) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focusList(listed)
        }
    }
    // 打开“开源许可 / 隐私说明”后原来获得焦点的行已经不在了，把焦点交给右栏的返回键
    LaunchedEffect(selected) {
        if (tv && selected.parent != null) {
            withFrameNanos { }
            runCatching { backFocus.requestFocus() }
        }
    }
    BackHandler(enabled = selected.parent != null) {
        val parent = selected.parent ?: return@BackHandler
        selected = parent
        focusList(parent)
    }
    BackHandler(enabled = tv && detailFocused && selected.parent == null) { focusList(listed) }

    Row(Modifier.fillMaxSize().then(if (tv) Modifier else Modifier.statusBarsPadding())) {
        Column(
            Modifier
                .width(if (tv) 320.dp else 380.dp)
                .fillMaxHeight()
                // 从右栏按左键回来时回到刚才的选中项，而不是同一高度的别的项（电视上那会切走右栏）
                .focusRestorer()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = if (tv) 28.dp else 24.dp,
                    end = 16.dp,
                    top = if (tv) 36.dp else 24.dp,
                    bottom = bottom + 24.dp,
                ),
        ) {
            Text(
                "设置",
                Modifier.padding(start = 4.dp, bottom = 16.dp),
                style = if (tv) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
            )
            SettingsGroups(
                overview = overview,
                selected = listed,
                twoPane = true,
                onOpen = { selected = it },
                itemModifier = { section ->
                    Modifier
                        .focusRequester(requesters.getValue(section))
                        .onFocusChanged { if (tv && it.isFocused && listed != section) selected = section }
                },
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(
                    if (tv) {
                        Modifier.padding(start = 8.dp, end = 48.dp, top = 36.dp)
                    } else {
                        Modifier
                            .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(start = 32.dp, end = 32.dp, top = 24.dp)
                    },
                )
                .onFocusChanged { detailFocused = it.hasFocus },
        ) {
            DetailHeader(
                section = selected,
                onBack = selected.parent?.let { parent -> { selected = parent; focusList(parent) } },
                backFocus = backFocus,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(selected) { detail(selected) { selected = it } }
            }
        }
    }
}

@Composable
private fun DetailHeader(section: SettingsSection, onBack: (() -> Unit)?, backFocus: FocusRequester) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.focusRequester(backFocus).tvFocusRing(CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回${section.parent?.title.orEmpty()}")
            }
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                section.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                section.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 三组分段列表。双栏时选中项高亮、不显示箭头。 */
@Composable
private fun SettingsGroups(
    overview: SettingsOverview,
    selected: SettingsSection?,
    twoPane: Boolean,
    onOpen: (SettingsSection) -> Unit,
    itemModifier: (SettingsSection) -> Modifier = { Modifier },
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSection.groups.forEach { group ->
            SegmentedGroup {
                group.forEachIndexed { index, section ->
                    val badge = section == SettingsSection.About && overview.updateAvailable
                    ListRow(
                        title = section.title,
                        index = index,
                        count = group.size,
                        onClick = { onOpen(section) },
                        modifier = itemModifier(section),
                        icon = section.icon,
                        iconTone = section.tone,
                        summary = overview.values[section].orEmpty(),
                        selected = section == selected,
                        showChevron = !twoPane,
                        trailing = if (badge) {
                            {
                                NewBadge()
                                if (!twoPane) Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NewBadge() {
    Box(
        Modifier
            .heightIn(min = 20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("新", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onError)
    }
}
