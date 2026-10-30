package com.mytv.mymusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.playback.EqPreset
import com.mytv.mymusic.playback.EqPresets
import com.mytv.mymusic.playback.EqualizerAvailability
import com.mytv.mymusic.playback.EqualizerBand
import com.mytv.mymusic.playback.EqualizerUiState
import com.mytv.mymusic.ui.InfoNote
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicChip
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.tvFocusRing
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 均衡器：总开关 + 预设 + 竖向滑杆。双栏时滑杆加高到 220dp。
 * 遥控器和键盘：左右键在频段之间移动（两端再按会离开滑杆组），上下键每次调 1 dB。
 */
@Composable
private fun VolumeNormalizationCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("音量均衡", style = MaterialTheme.typography.titleMedium)
            Text(
                "用网易云、酷狗、QQ、千千提供的响度数据把不同歌曲的音量拉齐；没有数据的歌曲保持原样。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerScreen(
    state: MusicUiState,
    equalizer: EqualizerUiState,
    actions: MusicActions,
    onBack: () -> Unit,
    embedded: Boolean = false,
) {
    val window = LocalAppWindow.current
    val supported = equalizer.availability != EqualizerAvailability.Unsupported
    val buttonsFocus = remember { FocusRequester() }
    val presets: List<EqPreset> = EqPresets.all + EqPreset("custom", "自定义")
    SettingsPage(SettingsSection.Equalizer, embedded, onBack) {
        item(key = "status", contentType = "eqStatus") { EqualizerStatusCard(equalizer) { actions.setEqEnabled(it) } }
        item(key = "normalization", contentType = "eqStatus") {
            VolumeNormalizationCard(state.volumeNormalization) { actions.setVolumeNormalization(it) }
        }
        item(key = "presetHead", contentType = "sectionHeader") { SectionLabel("预设") }
        item(key = "presets", contentType = "chips") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { preset ->
                    MusicChip(preset.label, equalizer.presetId == preset.id) { actions.selectEqPreset(preset.id) }
                }
            }
        }
        item(key = "bandsHead", contentType = "sectionHeader") {
            SectionLabel("频段") {
                Text(rangeLabel(equalizer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "bands", contentType = "eqBands") {
            BandsPanel(
                bands = equalizer.bands,
                enabled = supported,
                trackHeight = if (window.twoPane) 220.dp else 188.dp,
                onLevel = { index, db -> actions.setEqBand(index, db) },
                exitNext = buttonsFocus,
            )
        }
        item(key = "buttons", contentType = "actions") {
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions::resetEq, modifier = Modifier.focusRequester(buttonsFocus).tvFocusRing()) { Text("恢复平直") }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = actions::saveEqCustom, modifier = Modifier.tvFocusRing()) { Text("保存为自定义") }
            }
        }
        if (window.isTv) item(key = "tvTip", contentType = "note") { SettingsNote("左右键选择频段，上下键调整增益；在最右侧频段再按右键，移到下方按钮。") }
        item(key = "note", contentType = "note") {
            SettingsNote("只作用于这台设备的播放输出，不修改音频文件，也不同步。切换歌曲或音源后保持当前设置。")
        }
        item(key = "warn", contentType = "infoNote") {
            Box(Modifier.padding(top = 8.dp)) {
                InfoNote("部分蓝牙耳机、USB 声卡或系统省电模式可能不支持第三方音效。此时会保留设置，但提示“当前输出未生效”。", "amber")
            }
        }
        state.current?.let { song ->
            item(key = "playing", contentType = "infoNote") {
                Box(Modifier.padding(top = 8.dp)) {
                    InfoNote(
                        "正在播放：${song.title} · ${state.currentSource?.providerName ?: song.source}" +
                            (state.currentSource?.qualityLabel?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        "green",
                    )
                }
            }
        }
    }
}

/** 状态卡片：整张卡片就是开关，读屏读作开关。 */
@Composable
private fun EqualizerStatusCard(equalizer: EqualizerUiState, onEnabled: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val supported = equalizer.availability != EqualizerAvailability.Unsupported
    val shape = RoundedCornerShape(28.dp)
    val status = when {
        equalizer.enabled && equalizer.effective -> "已开启 · 实时生效"
        equalizer.enabled && equalizer.availability == EqualizerAvailability.NoSession -> "已开启 · 播放后生效"
        equalizer.enabled -> "已开启 · 当前输出未生效"
        else -> "已关闭"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .background(if (equalizer.enabled) scheme.primaryContainer else scheme.surfaceContainerHigh)
            .toggleable(value = equalizer.enabled, enabled = supported, role = Role.Switch, onValueChange = onEnabled)
            .semantics { stateDescription = status }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val content = if (equalizer.enabled) scheme.onPrimaryContainer else scheme.onSurface
        // 可见标题是状态文字，读屏需要一个名字；状态改由开关的 stateDescription 读，避免“已关闭”读两遍
        Icon(Icons.Filled.Headphones, "均衡器", tint = content)
        Column(Modifier.weight(1f)) {
            Text(
                status,
                Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = content,
            )
            Text(
                when (equalizer.availability) {
                    EqualizerAvailability.Available -> "当前输出：${equalizer.outputLabel}"
                    EqualizerAvailability.NoSession -> "当前输出：${equalizer.outputLabel} · 开始播放后生效"
                    EqualizerAvailability.Unsupported -> "当前输出：${equalizer.outputLabel} · 不支持第三方音效"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.8f),
            )
            if (equalizer.error.isNotBlank()) {
                Text(equalizer.error, style = MaterialTheme.typography.bodyMedium, color = scheme.error)
            }
        }
        Switch(checked = equalizer.enabled, onCheckedChange = null, enabled = supported)
    }
}

@Composable
private fun BandsPanel(
    bands: List<EqualizerBand>,
    enabled: Boolean,
    trackHeight: Dp,
    onLevel: (Int, Float) -> Unit,
    exitNext: FocusRequester? = null,
) {
    val scroll = bands.size > 5
    val requesters = remember(bands.size) { List(bands.size) { FocusRequester() } }
    Box(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        if (bands.isEmpty()) {
            Text(
                "开始播放后显示当前输出可调的频段",
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Box
        }
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = if (scroll) Arrangement.spacedBy(6.dp) else Arrangement.SpaceEvenly,
        ) {
            bands.forEachIndexed { index, band ->
                BandSlider(
                    band = band,
                    enabled = enabled,
                    trackHeight = trackHeight,
                    focusRequester = requesters[index],
                    onLevel = { db -> onLevel(index, db) },
                    onFocusPrev = requesters.getOrNull(index - 1)?.let { prev -> { prev.requestFocus() } },
                    onFocusNext = (requesters.getOrNull(index + 1) ?: exitNext)?.let { next -> { runCatching { next.requestFocus() } } },
                    modifier = if (scroll) Modifier.width(56.dp) else Modifier.weight(1f),
                )
            }
        }
    }
}

/** 竖向滑杆：底色 secondaryContainer，从底部往上填 primary，顶端一条短横线是把手。 */
@Composable
private fun BandSlider(
    band: EqualizerBand,
    enabled: Boolean,
    trackHeight: Dp,
    focusRequester: FocusRequester,
    onLevel: (Float) -> Unit,
    onFocusPrev: (() -> Unit)?,
    onFocusNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var heightPx by remember { mutableFloatStateOf(1f) }
    val latestBand by rememberUpdatedState(band)
    val latestOnLevel by rememberUpdatedState(onLevel)
    val fraction = ((band.levelDb - band.minDb) / (band.maxDb - band.minDb)).coerceIn(0f, 1f)
    val fill = trackHeight * fraction
    val shape = RoundedCornerShape(16.dp)
    fun set(db: Float) = latestOnLevel(db.coerceIn(latestBand.minDb, latestBand.maxDb))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(formatDb(band.levelDb), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Box(
            Modifier
                .width(40.dp)
                .height(trackHeight)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = formatFreq(band.freqHz)
                    stateDescription = "${formatDb(band.levelDb)} 分贝"
                    progressBarRangeInfo = ProgressBarRangeInfo(current = band.levelDb, range = band.minDb..band.maxDb)
                    if (enabled) {
                        setProgress { value ->
                            set(value)
                            true
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            set(band.levelDb + 1f)
                            true
                        }
                        Key.DirectionDown -> {
                            set(band.levelDb - 1f)
                            true
                        }
                        // 两端不拦截，让焦点可以离开滑杆组（电视上回到左侧设置列表）
                        Key.DirectionLeft -> onFocusPrev?.let { it(); true } ?: false
                        Key.DirectionRight -> onFocusNext?.let { it(); true } ?: false
                        else -> false
                    }
                }
                .focusable(enabled)
                .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(band.band, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset -> set(levelFromY(offset.y, heightPx, latestBand)) }
                }
                .pointerInput(band.band, enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        set(levelFromY(change.position.y, heightPx, latestBand))
                    }
                }
                .then(if (focused) Modifier.border(3.dp, scheme.primary, shape) else Modifier)
                .clip(shape)
                .background(scheme.secondaryContainer),
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(fill)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                    .background(scheme.primary),
            )
            // 填充太矮时把手画在底色上，换成 primary 才看得见
            val onFill = fill >= 14.dp
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (onFill) fill - 10.dp else 4.dp, start = 6.dp, end = 6.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (onFill) scheme.onPrimary else scheme.primary),
            )
        }
        Text(formatFreq(band.freqHz), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun levelFromY(y: Float, height: Float, band: EqualizerBand): Float {
    val ratio = 1f - (y / height).coerceIn(0f, 1f)
    return band.minDb + ratio * (band.maxDb - band.minDb)
}

private fun formatDb(value: Float): String {
    val rounded = (value * 2).roundToInt() / 2f
    return when {
        rounded > 0.001f -> "+" + trim(rounded)
        rounded < -0.001f -> trim(rounded)
        else -> "0"
    }
}

private fun trim(value: Float): String =
    if (value == value.roundToInt().toFloat()) value.roundToInt().toString()
    else String.format(Locale.ROOT, "%.1f", value)

private fun formatFreq(freq: Int): String =
    if (freq >= 1000) String.format(Locale.ROOT, "%.1f kHz", freq / 1000f).replace(".0 kHz", " kHz")
    else "$freq Hz"

private fun rangeLabel(equalizer: EqualizerUiState): String {
    val band = equalizer.bands.firstOrNull() ?: return "-10 至 +10 dB"
    return "${formatDb(band.minDb)} 至 ${formatDb(band.maxDb)} dB"
}
