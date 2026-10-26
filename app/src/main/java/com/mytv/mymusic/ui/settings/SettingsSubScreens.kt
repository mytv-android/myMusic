package com.mytv.mymusic.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.mytv.mymusic.aggregate.MatchStrictness
import com.mytv.mymusic.aggregate.PlaybackMode
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.ui.Cover
import com.mytv.mymusic.ui.CoverBox
import com.mytv.mymusic.ui.InfoNote
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicChip
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PlayButtonSize
import com.mytv.mymusic.ui.components.PlayPauseButton
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SegmentedGroup
import com.mytv.mymusic.ui.components.SkeletonRows
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.formatBytes
import com.mytv.mymusic.ui.providerName
import com.mytv.mymusic.ui.theme.BlurMode
import com.mytv.mymusic.ui.theme.BrandSeed
import com.mytv.mymusic.ui.theme.ColorSource
import com.mytv.mymusic.ui.theme.CustomSeedSwatches
import com.mytv.mymusic.ui.theme.LayoutMode
import com.mytv.mymusic.ui.theme.LocalAppearance
import com.mytv.mymusic.ui.theme.SchemeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ======================= 外观 =======================

private val CONTRAST_LEVELS = listOf(0f, 0.5f, 1f)

private fun contrastLabel(value: Float): String = when {
    value >= 0.75f -> "高"
    value >= 0.25f -> "中"
    else -> "标准"
}

/**
 * 外观：预览卡片 + 主题 + 配色来源 + 风格 + 对比度 + 显示。
 * 配色来源手机两列、双栏一行四个；电视不提供纯黑背景（设计里电视固定深色）。
 */
@Composable
fun AppearanceSettingsScreen(state: MusicUiState, actions: MusicActions, onBack: () -> Unit, embedded: Boolean = false) {
    val window = LocalAppWindow.current
    val tv = window.isTv
    val appearance = state.appearance
    val groupWidth = Modifier.widthIn(max = 480.dp).fillMaxWidth()
    SettingsPage(SettingsSection.Appearance, embedded, onBack) {
        item(key = "preview", contentType = "appearancePreview") { AppearancePreview(state, actions) }
        item(key = "layoutHead", contentType = "sectionHeader") { SectionLabel("布局模式") }
        item(key = "layout", contentType = "layoutOptions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LayoutModeChips(appearance.layout) { actions.setLayoutMode(it) }
                SettingsNote(layoutModeHint(appearance.layout))
            }
        }
        // 电视固定深色，不提供浅色主题
        if (!tv) item(key = "themeHead", contentType = "sectionHeader") { SectionLabel("主题") }
        if (!tv) item(key = "theme", contentType = "toggleGroup") {
            ConnectedToggleGroup(
                options = listOf("system", "light", "dark"),
                selected = appearance.theme,
                onSelect = { actions.setTheme(it) },
                label = ::themeLabel,
                modifier = groupWidth,
                icon = {
                    when (it) {
                        "light" -> Icons.Filled.LightMode
                        "dark" -> Icons.Filled.DarkMode
                        else -> Icons.Filled.BrightnessAuto
                    }
                },
            )
        }
        item(key = "sourceHead", contentType = "sectionHeader") { SectionLabel("配色来源") }
        item(key = "source", contentType = "colorOptions") { ColorSourceOptions(state, actions, perRow = if (window.twoPane) 4 else 2) }
        if (appearance.colorSource == ColorSource.Custom) {
            item(key = "swatches", contentType = "swatches") { SeedSwatches(appearance.customSeed) { actions.setCustomSeed(it) } }
        }
        item(key = "styleHead", contentType = "sectionHeader") { SectionLabel("配色风格") }
        item(key = "browseStyle", contentType = "styleChips") { StyleChips("浏览页", appearance.browseStyle) { actions.setBrowseStyle(it) } }
        item(key = "playerStyle", contentType = "styleChips") { StyleChips("播放页（固定深色）", appearance.playerStyle) { actions.setPlayerStyle(it) } }
        item(key = "contrastHead", contentType = "sectionHeader") { SectionLabel("对比度") }
        item(key = "contrast", contentType = "toggleGroup") {
            ConnectedToggleGroup(
                options = CONTRAST_LEVELS,
                selected = CONTRAST_LEVELS.minBy { abs(it - appearance.contrast) },
                onSelect = { actions.setContrast(it) },
                label = ::contrastLabel,
                modifier = groupWidth,
            )
        }
        item(key = "displayHead", contentType = "sectionHeader") { SectionLabel("显示") }
        if (!tv) {
            item(key = "amoled", contentType = "switch") {
                SwitchRow(
                    title = "纯黑背景",
                    checked = appearance.amoled,
                    onCheckedChange = { actions.setAmoled(it) },
                    index = 0,
                    count = 2,
                    summary = "深色模式下使用纯黑，适合 OLED 屏幕",
                    icon = Icons.Filled.Contrast,
                )
            }
        }
        item(key = "motion", contentType = "switch") {
            SwitchRow(
                title = "减少动画",
                checked = appearance.reduceMotion,
                onCheckedChange = { actions.setReduceMotion(it) },
                index = if (tv) 0 else 1,
                count = if (tv) 1 else 2,
                summary = "关闭形变、波浪和页面转场",
                icon = Icons.Filled.Animation,
            )
        }
        item(key = "blurHead", contentType = "sectionHeader") { SectionLabel("模糊效果") }
        item(key = "blur", contentType = "toggleGroup") {
            ConnectedToggleGroup(
                options = BlurMode.entries,
                selected = appearance.blur,
                onSelect = { actions.setBlurMode(it) },
                label = { it.label },
                modifier = groupWidth,
            )
        }
        item(key = "blurNote", contentType = "note") {
            SettingsNote(
                if (tv) "电视在“自动”下不做实时模糊，迷你播放器和导航栏改用不透明底色，滑动更流畅。"
                else "自动：Android 12 以上且内存充足时，迷你播放器和导航栏用磨砂底；关闭后改用不透明底色。",
            )
        }
    }
}

private fun layoutModeHint(mode: LayoutMode): String = when (mode) {
    LayoutMode.Auto -> "跟随设备：按窗口宽度和电视识别切换，屏幕方向保持系统默认。"
    LayoutMode.Phone -> "固定竖屏，使用底部导航和迷你播放器。"
    LayoutMode.Tablet -> "固定横屏，使用侧边导航；窗口够宽时内容分成两栏，并显示底部播放条或常驻播放窗格。"
    LayoutMode.Tv -> "固定横屏，使用遥控器焦点导航和大留白，配色固定深色。"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayoutModeChips(selected: LayoutMode, onSelect: (LayoutMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LayoutMode.entries.forEach { mode ->
            MusicChip(mode.label, mode == selected, description = "布局模式：${mode.label}") { onSelect(mode) }
        }
    }
}

/** 预览卡片：直接用当前配色角色画，有歌在放时显示它的封面和播放键。 */
@Composable
private fun AppearancePreview(state: MusicUiState, actions: MusicActions) {
    val scheme = MaterialTheme.colorScheme
    val song = state.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (song != null) {
            Cover(song.cover, song.title, 56.dp, corner = 16.dp)
        } else {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(scheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Palette, null, tint = scheme.onPrimaryContainer)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                song?.title ?: "myMusic",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("当前配色预览", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.primaryContainer).forEach { color ->
                    Box(Modifier.size(20.dp).clip(CircleShape).background(color))
                }
            }
        }
        if (song != null) {
            PlayPauseButton(playing = state.playing, onClick = { actions.transport("play") }, size = PlayButtonSize.Small)
        }
    }
}

@Composable
private fun ColorSourceOptions(state: MusicUiState, actions: MusicActions, perRow: Int) {
    val appearance = state.appearance
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorSource.entries.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { source ->
                    ColorSourceCard(
                        source = source,
                        selected = appearance.colorSource == source,
                        // 系统壁纸取色需要 Android 8.1（API 27）的 WallpaperColors
                        enabled = source != ColorSource.Wallpaper || Build.VERSION.SDK_INT >= 27,
                        hint = colorSourceHint(source),
                        onClick = { actions.setColorSource(source) },
                        modifier = Modifier.weight(1f),
                    ) { sampleModifier -> ColorSourceSample(source, state, sampleModifier) }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun colorSourceHint(source: ColorSource): String = when {
    source != ColorSource.Wallpaper -> source.hint
    Build.VERSION.SDK_INT >= 31 -> "系统强调色"
    Build.VERSION.SDK_INT >= 27 -> "壁纸主色"
    else -> "需要 Android 8.1+"
}

@Composable
private fun ColorSourceCard(
    source: ColorSource,
    selected: Boolean,
    enabled: Boolean,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sample: @Composable (Modifier) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = LocalAppearance.current.reduceMotion
    val corner = animateDpAsState(
        if (selected) 24.dp else 20.dp,
        if (reduceMotion) snap() else MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "sourceCorner",
    )
    val ringShape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .tvFocusRing(ringShape)
            .graphicsLayer {
                shape = RoundedCornerShape(corner.value)
                clip = true
            }
            .background(if (selected) scheme.surfaceContainerHigh else scheme.surfaceContainer)
            .drawWithContent {
                drawContent()
                if (selected) {
                    drawOutline(
                        RoundedCornerShape(corner.value).createOutline(size, layoutDirection, this),
                        scheme.primary,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else 0.48f)
            .padding(10.dp),
    ) {
        sample(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                source.label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (selected) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = scheme.primary)
        }
        // 色值（#006B5F）对读屏没有意义，名字已经说明是哪种颜色
        Text(
            hint,
            if (hint.startsWith("#")) Modifier.clearAndSetSemantics {} else Modifier,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColorSourceSample(source: ColorSource, state: MusicUiState, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    when (source) {
        ColorSource.Cover -> {
            val song = state.current
            if (song != null && song.cover.isNotBlank()) {
                CoverBox(song.cover, song.title, modifier, RoundedCornerShape(12.dp))
            } else {
                Box(modifier.background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.secondaryContainer))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MusicNote, null, tint = scheme.onPrimaryContainer)
                }
            }
        }
        ColorSource.Wallpaper -> Box(modifier.background(Brush.linearGradient(listOf(scheme.tertiaryContainer, scheme.secondaryContainer))))
        // 样本就是种子色本身
        ColorSource.Brand -> Box(modifier.background(BrandSeed))
        ColorSource.Custom -> Box(modifier.background(Brush.horizontalGradient(CustomSeedSwatches)))
    }
}

/** 自选颜色的色板。选中项色块缩小并加一圈描边，不只靠颜色区分。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeedSwatches(selected: Int, onSelect: (Int) -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CustomSeedSwatches.forEachIndexed { index, color ->
            val argb = color.toArgb()
            val isSelected = argb == selected
            Box(
                Modifier
                    .size(48.dp)
                    .tvFocusRing(CircleShape)
                    .clip(CircleShape)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(argb) })
                    .semantics { contentDescription = "色板 ${index + 1}" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (isSelected) 30.dp else 38.dp).clip(CircleShape).background(color))
                if (isSelected) Box(Modifier.size(42.dp).border(3.dp, ring, CircleShape))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleChips(label: String, selected: SchemeStyle, onSelect: (SchemeStyle) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.padding(start = 4.dp, top = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SchemeStyle.entries.forEach { style ->
                MusicChip(style.label, style == selected, description = "${label.substringBefore("（")}风格：${style.label}") { onSelect(style) }
            }
        }
    }
}

// ======================= 播放与换源 =======================

/** 播放与换源：换源模式按钮组、平台优先级、匹配选项。电视上优先级用“OK 拿起、上下移动”。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackSettingsScreen(state: MusicUiState, actions: MusicActions, onBack: () -> Unit, embedded: Boolean = false) {
    val tv = LocalAppWindow.current.isTv
    val aggregation = state.aggregation
    val searchable = state.providers.filter { it.search }
    val priority = aggregation.priorityFor(searchable.map { it.id })
    SettingsPage(SettingsSection.Playback, embedded, onBack) {
        item(key = "modeHead", contentType = "sectionHeader") { SectionLabel("换源模式") }
        item(key = "mode", contentType = "toggleGroup") {
            ConnectedToggleGroup(
                options = PlaybackMode.entries,
                selected = aggregation.playbackMode,
                onSelect = { actions.setPlaybackMode(it) },
                label = {
                    when (it) {
                        PlaybackMode.Auto -> "自动"
                        PlaybackMode.Priority -> "优先平台"
                        PlaybackMode.Pinned -> "固定平台"
                    }
                },
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            )
        }
        item(key = "modeNote", contentType = "note") {
            SettingsNote(
                when (aggregation.playbackMode) {
                    PlaybackMode.Auto -> "完整音源优先，其次按下面的平台顺序和码率选择。当前音源失败时自动换到下一个，播放页的音源标签会说明。"
                    PlaybackMode.Priority -> "先尝试排在前面的平台，不可用时再依次向后回退。"
                    PlaybackMode.Pinned -> "优先使用指定平台；它没有返回地址时仍会回退，并提示实际播放源。"
                },
            )
        }
        if (aggregation.playbackMode == PlaybackMode.Pinned) {
            item(key = "pinHead", contentType = "sectionHeader") { SectionLabel("固定平台") }
            item(key = "pin", contentType = "chips") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    searchable.forEach { provider ->
                        MusicChip(provider.name, aggregation.pinnedProvider == provider.id) { actions.setPinnedProvider(provider.id) }
                    }
                }
            }
        }
        item(key = "priorityHead", contentType = "sectionHeader") {
            SectionLabel("平台优先级") {
                Text("越靠前越先尝试", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "priority", contentType = "priorityList") {
            if (priority.isEmpty()) {
                SkeletonRows(4)
            } else {
                PriorityList(priority, state.providers) { id, delta -> actions.movePriority(id, delta) }
            }
        }
        if (tv) item(key = "priorityTip", contentType = "note") { SettingsNote("按 OK 拿起平台，用上下键移动，再按 OK 放下。") }
        item(key = "matchHead", contentType = "sectionHeader") { SectionLabel("匹配") }
        item(key = "strict", contentType = "switch") {
            SwitchRow(
                title = "严格匹配",
                checked = aggregation.matchStrictness == MatchStrictness.Strict,
                onCheckedChange = { actions.setStrictMatch(it) },
                index = 0,
                count = 3,
                summary = "降低 Live、伴奏、重制版被误合并的概率",
                icon = Icons.Filled.Shield,
            )
        }
        item(key = "preview", contentType = "switch") {
            SwitchRow(
                title = "允许试听片段",
                checked = aggregation.allowPreview,
                onCheckedChange = { actions.setAllowPreview(it) },
                index = 1,
                count = 3,
                summary = "没有完整音源时，播放平台返回的试听片段",
                icon = Icons.Filled.MusicNote,
            )
        }
        item(key = "clearMatch", contentType = "settingRow") {
            ListRow(
                title = "清除匹配缓存",
                index = 2,
                count = 3,
                onClick = null,
                icon = Icons.Filled.CleaningServices,
                summary = "下次换源重新检索同一录音；拆分与手动合并不受影响",
                showChevron = false,
                trailing = {
                    TextButton(onClick = actions::clearMatchCache, modifier = Modifier.tvFocusRing(CircleShape)) { Text("清除") }
                },
            )
        }
        item(key = "note", contentType = "note") {
            Box(Modifier.padding(top = 16.dp)) {
                InfoNote("自动换源与手动换源都使用这里的规则。播放器里始终显示当前实际播放源。")
            }
        }
    }
}

@Composable
private fun PriorityList(priority: List<String>, providers: List<ProviderInfo>, onMove: (String, Int) -> Unit) {
    val tv = LocalAppWindow.current.isTv
    var moving by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = moving != null) { moving = null }
    SegmentedGroup {
        priority.forEachIndexed { index, id ->
            // key 保证移动后还是同一个节点，电视上焦点跟着这一行走
            key(id) {
                PriorityRow(
                    rank = index + 1,
                    name = providerName(providers, id),
                    index = index,
                    count = priority.size,
                    tv = tv,
                    moving = moving == id,
                    onToggleMove = { moving = if (moving == id) null else id },
                    onMove = { delta -> onMove(id, delta) },
                )
            }
        }
    }
}

@Composable
private fun PriorityRow(
    rank: Int,
    name: String,
    index: Int,
    count: Int,
    tv: Boolean,
    moving: Boolean,
    onToggleMove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = if (moving) RoundedCornerShape(16.dp) else segmentShape(index, count)
    val tvControls = if (tv) {
        Modifier
            .onPreviewKeyEvent { event ->
                if (!moving || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        if (index > 0) onMove(-1)
                        true
                    }
                    Key.DirectionDown -> {
                        if (index < count - 1) onMove(1)
                        true
                    }
                    // 拿起时左右键不离开这一行
                    Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            }
            .clickable(onClickLabel = if (moving) "放下" else "拿起", onClick = onToggleMove)
            .semantics { stateDescription = if (moving) "正在移动" else "第 $rank 位" }
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (tv) Modifier.tvFocusRing(shape, scale = 1.02f) else Modifier)
            .clip(shape)
            .background(if (moving) scheme.secondaryContainer else scheme.surfaceContainerLow)
            .then(tvControls)
            .padding(start = 8.dp, end = if (tv) 16.dp else 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            // 电视上名次已由整行的“第 N 位”读出
            Modifier.width(32.dp).then(if (tv) Modifier.clearAndSetSemantics {} else Modifier),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (moving) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
        ProviderMark(name, 32.dp)
        Text(
            name,
            Modifier.weight(1f).padding(start = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (moving) scheme.onSecondaryContainer else scheme.onSurface,
        )
        if (tv) {
            Icon(
                if (moving) Icons.Filled.SwapVert else Icons.Filled.DragHandle,
                contentDescription = null,
                tint = if (moving) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            )
        } else {
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, "上移$name")
            }
            IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                Icon(Icons.Filled.KeyboardArrowDown, "下移$name")
            }
        }
    }
}

// ======================= 搜索与浏览 =======================

/** 搜索与浏览：默认浏览来源（点开选择）+ 每个平台是否参与聚合搜索。 */
@Composable
fun SearchBrowseSettingsScreen(state: MusicUiState, actions: MusicActions, onBack: () -> Unit, embedded: Boolean = false) {
    val searchable = state.providers.filter { it.search }
    val enabledIds = state.aggregation.enabledOrDefault(searchable.map { it.id })
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val currentName = providerName(state.providers, state.providerId)
    SettingsPage(SettingsSection.Search, embedded, onBack) {
        item(key = "browseHead", contentType = "sectionHeader") { SectionLabel("默认浏览来源") }
        item(key = "browse", contentType = "settingRow") {
            ListRow(
                title = currentName,
                index = 0,
                count = 1,
                onClick = { pickerOpen = true },
                leading = { ProviderMark(currentName, 40.dp, active = true) },
                summary = "榜单、歌单页打开时的来源；不影响播放换源",
            )
        }
        item(key = "aggHead", contentType = "sectionHeader") {
            SectionLabel("参与聚合搜索的平台") {
                Text("已启用 ${enabledIds.size} 个", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (searchable.isEmpty()) {
            item(key = "aggLoading", contentType = "skeleton") { SkeletonRows(6) }
        } else {
            itemsIndexed(
                searchable,
                key = { _, provider -> "agg:${provider.id}" },
                contentType = { _, _ -> "provider" },
            ) { index, provider ->
                val on = provider.id in enabledIds
                ListRow(
                    title = provider.name,
                    index = index,
                    count = searchable.size,
                    onClick = { actions.toggleAggregateProvider(provider.id) },
                    modifier = Modifier.semantics {
                        role = Role.Switch
                        stateDescription = if (on) "已启用" else "已关闭"
                    },
                    leading = { ProviderMark(provider.name, 36.dp, active = on) },
                    summary = capabilities(provider),
                    showChevron = false,
                    trailing = { Switch(checked = on, onCheckedChange = null) },
                )
            }
        }
        item(key = "note", contentType = "note") { SettingsNote("平台越多结果越全，搜索和匹配也可能更慢。关闭的平台不参与聚合搜索。") }
    }
    if (pickerOpen) {
        BrowseSourceSheet(state, actions, onDismiss = { pickerOpen = false })
    }
}

private fun capabilities(provider: ProviderInfo): String {
    val caps = listOfNotNull(
        "单曲".takeIf { provider.search },
        "歌单".takeIf { provider.searchPlaylist },
        "专辑".takeIf { provider.searchAlbum },
        "榜单".takeIf { provider.topLists },
        "歌词".takeIf { provider.lyrics },
    ).joinToString(" · ")
    return listOf(caps, provider.note).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun BrowseSourceSheet(state: MusicUiState, actions: MusicActions, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    SettingsTvFocus(focus)
    AdaptiveSheet(onDismiss = onDismiss, title = "默认浏览来源", subtitle = "榜单、歌单页打开时先显示这个平台") {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            state.providers.forEachIndexed { index, provider ->
                val selected = provider.id == state.providerId
                ListRow(
                    title = provider.name,
                    index = index,
                    count = state.providers.size,
                    onClick = {
                        actions.selectProvider(provider.id)
                        onDismiss()
                    },
                    // 单选列表：每行都读出选中 / 未选中
                    modifier = (if (selected) Modifier.focusRequester(focus) else Modifier).semantics {
                        role = Role.RadioButton
                        this.selected = selected
                    },
                    leading = { ProviderMark(provider.name, 36.dp, active = selected) },
                    selected = selected,
                    showChevron = false,
                    trailing = if (selected) {
                        { Icon(Icons.Filled.Check, null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

// ======================= 平台账号 =======================

/**
 * 平台账号：每个平台一行，已登录用 primary 标出，未登录给出登录按钮。
 * 电视上只提供扫码登录，不让用户在电视上输入 Cookie。
 */
@Composable
fun AccountsSettingsScreen(state: MusicUiState, actions: MusicActions, onBack: () -> Unit, embedded: Boolean = false) {
    val tv = LocalAppWindow.current.isTv
    LaunchedEffect(Unit) { actions.syncIfStaleOnScreen() }
    var cookieFor by remember { mutableStateOf<ProviderInfo?>(null) }
    var qrFor by remember { mutableStateOf<ProviderInfo?>(null) }
    var signerOpen by remember { mutableStateOf(false) }
    val providers = state.providers
    val loggedCount = providers.count { !state.cookies[it.id].isNullOrBlank() }
    SettingsPage(
        SettingsSection.Accounts,
        embedded,
        onBack,
        subtitle = if (loggedCount > 0) "已登录 $loggedCount 个平台" else SettingsSection.Accounts.subtitle,
    ) {
        item(key = "intro", contentType = "note") {
            SettingsNote(
                if (tv) "各平台账号相互独立。电视上用手机扫码登录，Cookie 保存在本机；连接同步后会以明文写入私密同步文件。"
                else "各平台账号相互独立。Cookie 用于换源和 VIP 检测；连接同步后会以明文写入私密同步文件，请勿公开文件链接。点按平台可以粘贴或更新 Cookie。",
                Modifier.padding(bottom = 8.dp),
            )
        }
        if (providers.isEmpty()) {
            item(key = "loading", contentType = "skeleton") { SkeletonRows(5) }
        } else {
            itemsIndexed(
                providers,
                key = { _, provider -> "account:${provider.id}" },
                contentType = { _, _ -> "provider" },
            ) { index, provider ->
                AccountRow(
                    provider = provider,
                    loggedIn = !state.cookies[provider.id].isNullOrBlank(),
                    index = index,
                    count = providers.size,
                    tv = tv,
                    onQr = { qrFor = provider },
                    onCookie = { cookieFor = provider },
                    onLogout = { actions.logout(provider.id) },
                )
            }
        }
        if (providers.any { it.id == "soda" }) {
            item(key = "soda_signer", contentType = "signerGroup") {
                Column(Modifier.padding(top = 12.dp)) {
                    SectionLabel("汽水播放")
                    ListRow(
                        title = "汽水播放签名器",
                        index = 0,
                        count = 1,
                        onClick = { signerOpen = true },
                        icon = Icons.Filled.Shield,
                        summary = if (state.sodaSignerUrl.isBlank()) {
                            "未配置 · 会员整曲只会返回试听片段"
                        } else {
                            "已配置 · ${signerLabel(state.sodaSignerUrl)}"
                        },
                    )
                }
            }
        }
        item(key = "note", contentType = "note") {
            Box(Modifier.padding(top = 16.dp)) {
                InfoNote(
                    if (tv) "不支持扫码的平台请在手机或平板上登录。"
                    else "能否扫码以平台接口为准；不支持扫码的平台可以粘贴 Cookie。",
                )
            }
        }
    }
    if (signerOpen) {
        SodaSignerDialog(
            initialUrl = state.sodaSignerUrl,
            initialToken = state.sodaSignerToken,
            onSave = { url, token ->
                actions.saveSodaSigner(url, token)
                signerOpen = false
            },
            onDismiss = { signerOpen = false },
        )
    }
    qrFor?.let { provider ->
        QrLoginPanel(
            provider = provider,
            state = state,
            actions = actions,
            onCookie = if (tv) null else ({
                actions.cancelLogin()
                qrFor = null
                cookieFor = provider
            }),
            onDismiss = {
                actions.cancelLogin()
                qrFor = null
            },
        )
    }
    cookieFor?.let { provider ->
        CookieDialog(
            provider = provider,
            initial = state.cookies[provider.id].orEmpty(),
            onSave = { value ->
                actions.saveCookie(provider.id, value)
                cookieFor = null
            },
            onDismiss = { cookieFor = null },
        )
    }
}

@Composable
private fun AccountRow(
    provider: ProviderInfo,
    loggedIn: Boolean,
    index: Int,
    count: Int,
    tv: Boolean,
    onQr: () -> Unit,
    onCookie: () -> Unit,
    onLogout: () -> Unit,
) {
    ListRow(
        title = provider.name,
        index = index,
        count = count,
        onClick = if (tv) null else onCookie,
        leading = { ProviderMark(provider.name, 40.dp, active = loggedIn) },
        summary = when {
            loggedIn -> "已登录"
            provider.qrLogin -> "未登录"
            tv -> "未登录 · 请在手机上登录"
            else -> "未登录 · 仅支持 Cookie"
        },
        showChevron = false,
        trailing = {
            when {
                loggedIn -> TextButton(onClick = onLogout, modifier = Modifier.tvFocusRing(CircleShape)) {
                    Text("退出", Modifier.semantics { contentDescription = "退出${provider.name}" }, color = MaterialTheme.colorScheme.error)
                }
                provider.qrLogin -> FilledTonalButton(onClick = onQr, modifier = Modifier.tvFocusRing(CircleShape)) {
                    Icon(Icons.Filled.QrCode2, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("登录", Modifier.semantics { contentDescription = "扫码登录${provider.name}" })
                }
                !tv -> FilledTonalButton(onClick = onCookie) { Text("Cookie", Modifier.semantics { contentDescription = "用 Cookie 登录${provider.name}" }) }
            }
        },
    )
}

/** 扫码登录：手机是底部面板，平板和电视是居中对话框（电视二维码放大，方便在沙发上扫）。 */
@Composable
private fun QrLoginPanel(
    provider: ProviderInfo,
    state: MusicUiState,
    actions: MusicActions,
    onCookie: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val window = LocalAppWindow.current
    val type = ""
    SideEffect(provider.id, actions) { actions.startQrLogin(provider.id, type) }
    // 面板不经 onDismiss 离开组合时（旋转屏幕、窗口尺寸变化，qrFor 不会保存）也要停掉轮询
    DisposableEffect(provider.id) { onDispose { actions.cancelLogin() } }
    // 登录成功后 Cookie 会变，变了就关闭
    val initialCookie = remember(provider.id) { state.cookies[provider.id].orEmpty() }
    val cookie = state.cookies[provider.id].orEmpty()
    SideEffect(provider.id, cookie, initialCookie) {
        if (cookie.isNotBlank() && cookie != initialCookie) onDismiss()
    }
    val title = "扫码登录${provider.name}"
    val subtitle = "用${provider.name} App 扫描二维码，并在手机上确认登录"
    val refresh = { actions.startQrLogin(provider.id, type) }
    if (window.isPhone) {
        AdaptiveSheet(onDismiss = onDismiss, title = title, subtitle = subtitle) {
            QrLoginBody(provider, state, refresh, onCookie)
        }
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = if (window.isTv) 520.dp else 440.dp),
        ) {
            Column(Modifier.padding(vertical = 24.dp)) {
                Text(
                    title,
                    Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    subtitle,
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QrLoginBody(provider, state, refresh, onCookie)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QrLoginBody(
    provider: ProviderInfo,
    state: MusicUiState,
    onRefresh: () -> Unit,
    onCookie: (() -> Unit)?,
) {
    val window = LocalAppWindow.current
    val active = state.loginProviderId == provider.id
    val login = if (active) state.login else null
    val status = if (active) state.loginStatus else ""
    val ended = status == "二维码已过期" || status.contains("失败")
    val qrSize = when {
        window.isTv -> 300.dp
        window.isPhone -> 220.dp
        else -> 240.dp
    }
    val refreshFocus = remember { FocusRequester() }
    SettingsTvFocus(refreshFocus, ended)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(qrSize)
                .clip(RoundedCornerShape(24.dp))
                // 二维码需要浅底深码，扫码器才认，不跟随主题
                .background(Color.White)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val image = login?.session?.imageUrl.orEmpty()
            val url = login?.session?.url.orEmpty()
            when {
                image.startsWith("data:") -> {
                    // 平台直接返回的二维码图片：解码放后台线程，放大时用最近邻，保持边缘清晰
                    val bitmap by produceState<ImageBitmap?>(initialValue = null, image) {
                        value = withContext(Dispatchers.Default) {
                            runCatching {
                                val bytes = android.util.Base64.decode(image.substringAfter(','), android.util.Base64.DEFAULT)
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    val decoded = bitmap
                    if (decoded != null) {
                        androidx.compose.foundation.Image(
                            decoded,
                            "登录二维码",
                            Modifier.fillMaxSize(),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                        )
                    }
                }
                image.isNotBlank() -> AsyncImage(model = image, contentDescription = "登录二维码", modifier = Modifier.fillMaxSize())
                url.isNotBlank() -> QrCodeImage(url, Modifier.fillMaxSize())
                login == null && !ended -> LoadingIndicator(Modifier.size(56.dp))
                else -> Icon(Icons.Filled.ErrorOutline, "没有二维码", Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
            }
            if (ended && login != null) {
                // 过期或失败后盖住旧二维码，免得被误扫
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (status == "二维码已过期") "已过期" else "已失效",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Row(
            Modifier.padding(top = 12.dp).heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!ended) LoadingIndicator(Modifier.size(28.dp))
            Text(
                status.ifBlank { "正在获取二维码…" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (ended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (onCookie != null) {
                TextButton(onClick = onCookie) { Text("改用 Cookie 登录") }
            }
            if (ended) {
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.focusRequester(refreshFocus).heightIn(min = 48.dp).tvFocusRing(CircleShape),
                ) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("刷新二维码")
                }
            } else {
                TextButton(
                    onClick = onRefresh,
                    modifier = Modifier.focusRequester(refreshFocus).heightIn(min = 48.dp).tvFocusRing(CircleShape),
                ) { Text("刷新二维码") }
            }
        }
    }
}

@Composable
private fun CookieDialog(provider: ProviderInfo, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(provider.id) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${provider.name} Cookie") },
        text = {
            Column {
                Text(
                    "只保存在本机，换源与 VIP 检测会使用它。留空保存等于退出登录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 100.dp),
                    placeholder = { Text("粘贴 Cookie") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun signerLabel(url: String): String {
    val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return url
    val host = parsed.host ?: return url
    return if (parsed.port > 0) "$host:${parsed.port}" else host
}

/**
 * 汽水签名器：协议与 sodam / libresoda / libmssdk 的 POST /sign 一致，
 * 签名器需要看到即将发出的 URL、body 和全部请求头。
 */
@Composable
private fun SodaSignerDialog(
    initialUrl: String,
    initialToken: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("汽水播放签名器") },
        text = {
            Column {
                Text(
                    "会员整曲要逐请求的 x-helios / x-medusa 签名，只能由官方客户端组件生成。" +
                        "在一台电脑（Windows / macOS / Linux）上运行 sodam / libmssdk 的签名服务，" +
                        "手机或电视通过局域网访问，把 POST /sign 地址填在这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = { Text("签名服务地址") },
                    placeholder = { Text("http://192.168.1.10:8899/sign") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Token（可选）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Text(
                    "签名器会看到这次请求的完整请求头（含 Cookie），只填写自己信任的实例。留空保存表示关闭。",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url, token) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ======================= 存储 =======================

/** 存储：总占用大字 + 分段占用条 + 分项清除。清除缓存不影响歌单、收藏和下载，不需要二次确认。 */
@Composable
fun StorageSettingsScreen(
    cacheBytes: Long,
    downloadsBytes: Long,
    downloadsCount: Int,
    onClearCache: () -> Unit,
    onClearMatch: () -> Unit,
    cacheLimitMb: Int,
    onCacheLimit: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
    onBack: () -> Unit,
    embedded: Boolean = false,
) {
    SettingsPage(SettingsSection.Storage, embedded, onBack, subtitle = "应用共占用 ${formatBytes(cacheBytes + downloadsBytes)}") {
        item(key = "usage", contentType = "storageUsage") { StorageUsageCard(cacheBytes, downloadsBytes) }
        item(key = "limitHead", contentType = "sectionHeader") { SectionLabel("播放缓存上限") }
        item(key = "limit", contentType = "cacheLimit") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectedToggleGroup(
                    options = listOf(128, 256, 512, 1024),
                    selected = cacheLimitMb,
                    onSelect = onCacheLimit,
                    label = { if (it >= 1024) "${it / 1024} GB" else "$it MB" },
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                )
                SettingsNote(
                    if (LocalAppWindow.current.isTv) "电视盒子存储空间小，默认 256 MB。超过上限时先删最久没播放的缓存。"
                    else "默认 512 MB。超过上限时先删最久没播放的缓存；调低后立即生效。",
                )
            }
        }
        item(key = "rows", contentType = "settingsGroup") {
            SegmentedGroup(Modifier.padding(top = 12.dp)) {
                ListRow(
                    title = "播放缓存",
                    index = 0,
                    count = 3,
                    onClick = null,
                    icon = Icons.Filled.History,
                    iconTone = IconTone.Tertiary,
                    summary = "${formatBytes(cacheBytes)} · 清除后播放时会重新缓存",
                    showChevron = false,
                    trailing = {
                        TextButton(onClick = onClearCache, enabled = cacheBytes > 0, modifier = Modifier.tvFocusRing(CircleShape)) { Text("清除") }
                    },
                )
                ListRow(
                    title = "下载文件",
                    index = 1,
                    count = 3,
                    onClick = onOpenDownloads,
                    icon = Icons.Filled.Download,
                    iconTone = IconTone.Primary,
                    summary = "$downloadsCount 首 · ${formatBytes(downloadsBytes)}",
                    value = "管理",
                )
                ListRow(
                    title = "匹配缓存",
                    index = 2,
                    count = 3,
                    onClick = null,
                    icon = Icons.Filled.CleaningServices,
                    summary = "清除后下次换源重新匹配；拆分与手动合并不受影响",
                    showChevron = false,
                    trailing = {
                        TextButton(onClick = onClearMatch, modifier = Modifier.tvFocusRing(CircleShape)) { Text("清除") }
                    },
                )
            }
        }
        item(key = "note", contentType = "note") { SettingsNote("清除缓存不会影响歌单、收藏和下载文件。下载文件是唯一的离线音源，请在“下载”里管理。") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageUsageCard(cacheBytes: Long, downloadsBytes: Long) {
    val scheme = MaterialTheme.colorScheme
    val total = cacheBytes + downloadsBytes
    val parts = listOf(
        Triple("下载", downloadsBytes, scheme.primary),
        Triple("播放缓存", cacheBytes, scheme.tertiary),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(20.dp),
    ) {
        Text("应用共占用", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Text(formatBytes(total), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
        Row(
            Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(8.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val visible = parts.filter { it.second > 0 }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().background(scheme.surfaceVariant))
            } else {
                visible.forEach { (_, bytes, color) ->
                    // 很小的一项也留出能看见的宽度
                    Box(
                        Modifier
                            .weight(maxOf(bytes.toFloat(), total * 0.02f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(color),
                    )
                }
            }
        }
        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            parts.forEach { (label, bytes, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                    Text("$label ${formatBytes(bytes)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ======================= 开源许可 / 隐私说明 =======================

private const val LICENSE_URL = "https://github.com/mytv-android/myMusic/blob/main/LICENSE"

@Composable
fun LicensesScreen(onBack: () -> Unit, embedded: Boolean = false) {
    val openUri = rememberOpenUri()
    val thirdParty = listOf(
        "music-lib" to "平台能力来源。当前纳入 commit 7cc4467，保留 Go 源码用于移植参考与许可证说明。",
        "music-lib / Kotlin 移植" to "使用 OkHttp、原生加密工具与 Kotlin 实现，不引入 GoMobile、AAR 或额外后端。",
        "AndroidX、Media3、Coil、Room、WorkManager" to "均按其 Apache-2.0 或对应开源许可使用；完整列表随应用和仓库提供。",
        "二维码生成" to "扫码登录的二维码按 Nayuki QR Code generator（MIT）的算法在本机生成。",
        "MaterialKolor、Haze" to "动态配色（MIT）与模糊效果（Apache-2.0）。",
        "YesPlayMusic" to "歌词背景“律动”的分频段自适应增益与频谱通量鼓点检测参考其 MIT 实现；音频分析改由本机 ExoPlayer PCM 完成。",
        "Roboto Flex 字体" to "拉丁字母和数字使用 Roboto Flex（SIL Open Font License 1.1），应用内为只含字重轴和拉丁字符的子集。",
    )
    SettingsPage(SettingsSection.Licenses, embedded, onBack) {
        item(key = "app", contentType = "licenseCard") {
            TextCard(
                title = "GNU Affero General Public License v3.0",
                body = "myMusic 的应用代码与移植的平台层均以 AGPL-3.0 发布。",
                index = 0,
                count = 1,
                footer = {
                    TextButton(onClick = { openUri(LICENSE_URL) }, modifier = Modifier.tvFocusRing(CircleShape)) {
                        Text("查看完整文本")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                    }
                },
            )
        }
        item(key = "thirdHead", contentType = "sectionHeader") { SectionLabel("上游与第三方") }
        itemsIndexed(
            thirdParty,
            key = { _, item -> "license:${item.first}" },
            contentType = { _, _ -> "license" },
        ) { index, (title, body) ->
            TextCard(title, body, index, thirdParty.size)
        }
        item(key = "note", contentType = "note") {
            Box(Modifier.padding(top = 16.dp)) {
                InfoNote("如果二次分发，请保留许可证、版权声明与修改说明，并遵守 AGPL-3.0 的源码提供义务。")
            }
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit, embedded: Boolean = false) {
    val blocks = listOf(
        "留在本机" to "最近播放、下载文件、播放进度和匹配缓存只保存在设备上。同步令牌使用 Android Keystore 加密，只保存在本机。",
        "歌词背景音频分析" to "“律动”只分析 ExoPlayer 已解码、正在输出的 PCM，不读取麦克风，不申请录音权限，也不保存或上传音频及频谱。暂停或离开播放页后停止分析。",
        "写入你选择的云端" to "启用同步后，自建歌单、收藏和平台账号 Cookie 会写入你的 GitHub Gist 或 Gitee 代码片段。Cookie 以明文保存，请勿公开文件链接；同步文件不包含同步令牌、播放地址和下载文件。",
        "网络请求" to "应用直接请求音乐平台上游接口、更新清单和同步服务，没有 myMusic 自建中转服务器。配置汽水签名器后，取流请求会额外发给你填写的签名服务。第三方仍会看到其服务所需的常规请求信息。扫码登录的二维码在本机生成。",
        "诊断信息" to "复制诊断只包含版本、设备型号、Android 版本、启用平台和最近错误；不包含 Cookie、令牌或完整播放 URL。",
    )
    SettingsPage(SettingsSection.Privacy, embedded, onBack) {
        itemsIndexed(
            blocks,
            key = { _, item -> "privacy:${item.first}" },
            contentType = { _, _ -> "privacy" },
        ) { index, (title, body) ->
            TextCard(title, body, index, blocks.size, titleColor = MaterialTheme.colorScheme.primary)
        }
        item(key = "note", contentType = "note") {
            Box(Modifier.padding(top = 16.dp)) {
                InfoNote("退出平台账号或断开同步会清除对应的本机凭据；卸载应用会删除应用目录内的所有本地数据。")
            }
        }
    }
}
