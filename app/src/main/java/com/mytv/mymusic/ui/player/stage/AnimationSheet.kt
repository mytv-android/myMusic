package com.mytv.mymusic.ui.player.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.LyricAutoSwitch
import com.mytv.mymusic.data.LyricStagePrefs
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TEMPO_RULE_OPEN_END
import com.mytv.mymusic.data.TempoRule
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentedGroup
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.player.ToolbarIconButton
import com.mytv.mymusic.ui.theme.LocalAppearance

/**
 * 歌词动画面板（全屏歌词页的 ✦、平板工具栏、电视更多菜单、设置 › 播放页都打开它）：
 * 模式缩略图（两列）→ 每首歌换动画（固定 / 随机 / 按速度，按速度时四档各选一个）→ 这首歌固定用它 → 频谱 → 当前模式的设置。
 * 设置表由模式声明生成：数字是 −/+ 步进行，选项是连接按钮组，开关是开关行；原模组没放进来的项数写在最后。
 *
 * @param song 正在放的歌；从设置页打开时为 null，不显示“这首歌固定用它”。
 * @param shown 正在显示（选中）的模式 id。
 * @param onPick 点了某个模式。
 * @param onPin “这首歌固定用它”开关；null 时不显示。
 */
@Composable
internal fun AnimationSheet(
    prefs: LyricStagePrefs,
    song: Song?,
    shown: String,
    actions: MusicActions,
    onPick: (String) -> Unit,
    onPin: ((Boolean) -> Unit)?,
    onDismiss: () -> Unit,
) {
    // 打开过一次面板，✦ 外面的呼吸提示就不再显示
    LaunchedEffect(Unit) {
        if (!prefs.hintSeen) actions.setLyricStage { it.copy(hintSeen = true) }
    }
    // 设备档位（模糊默认开不开）要在读设置表之前定下来
    ResolveStageDevice()
    val mode = LyricVisualizers.byId(shown)
    AdaptiveSheet(onDismiss = onDismiss, title = "歌词动画", subtitle = "只在全屏歌词页生效") {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            ModeGrid(prefs, song, mode.id, onPick)

            SectionLabel("每首歌换动画")
            ConnectedToggleGroup(
                options = LyricAutoSwitch.entries,
                selected = prefs.auto,
                onSelect = { value -> actions.setLyricStage { it.copy(auto = value) } },
                label = { it.label },
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            )
            SheetNote(autoNote(prefs.auto))
            if (prefs.auto == LyricAutoSwitch.Tempo) {
                Spacer(Modifier.size(8.dp))
                TempoRows(prefs.tempoRules) { index, id ->
                    actions.setLyricStage { p ->
                        p.copy(tempoRules = p.tempoRules.mapIndexed { i, rule -> if (i == index) rule.copy(mode = id) else rule })
                    }
                }
            }
            val pinned = song != null && prefs.pins.containsKey(song.key)
            if (onPin != null && song != null && (prefs.auto != LyricAutoSwitch.Fixed || pinned)) {
                Spacer(Modifier.size(8.dp))
                SwitchRow(
                    title = "这首歌固定用它",
                    checked = pinned,
                    onCheckedChange = onPin,
                    index = 0,
                    count = 1,
                    summary = "以后每次放这首歌都用“${mode.label}”，不再自动换",
                )
            }

            SectionLabel("叠加")
            SwitchRow(
                title = "频谱",
                checked = prefs.spectrum,
                onCheckedChange = { on -> actions.setLyricStage { it.copy(spectrum = on) } },
                index = 0,
                count = 1,
                summary = "全屏歌词页左下角显示 48 段频谱、响度和峰值频率；经典模式不显示",
            )

            ModeSettings(mode, prefs, actions)
        }
    }
}

/**
 * ✦：打开歌词动画面板，48dp，和收藏按钮同款。还没打开过面板时外面一圈主色呼吸（减少动态效果时是静止的一圈）。
 */
@Composable
internal fun AnimationSheetButton(hint: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = LocalAppearance.current.reduceMotion
    val breath = if (hint && !reduceMotion) {
        rememberInfiniteTransition(label = "sheetHint").animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "sheetHintAlpha",
        )
    } else {
        null
    }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .tvFocusRing(CircleShape)
            .then(
                if (hint) {
                    Modifier.drawBehind {
                        val t = breath?.value ?: 1f
                        val stroke = 2.dp.toPx()
                        drawCircle(
                            scheme.primary.copy(alpha = 0.35f + 0.55f * t),
                            radius = size.minDimension / 2 - stroke / 2 - 2.dp.toPx() * (1f - t),
                            style = Stroke(stroke),
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Icon(Icons.Filled.AutoAwesome, "歌词动画", tint = scheme.onSurface)
    }
}

/** 平板、横屏工具栏里的 ✦。 */
@Composable
internal fun ToolbarAnimationButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    ToolbarIconButton(Icons.Filled.AutoAwesome, "歌词动画", onClick, modifier)

/** 模式卡片两列排：每张是静帧缩略图加名字，选中的描主色边、圆角变大、右上角打勾。 */
@Composable
private fun ModeGrid(prefs: LyricStagePrefs, song: Song?, selected: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LyricVisualizers.all.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { mode ->
                    ModeCard(mode, mode.id == selected, prefs, song, Modifier.weight(1f)) { onPick(mode.id) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModeCard(
    mode: LyricVisualizer,
    selected: Boolean,
    prefs: LyricStagePrefs,
    song: Song?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (selected) 22.dp else 14.dp)
    Column(
        modifier
            .tvFocusRing(shape)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .then(if (selected) Modifier.border(2.5.dp, scheme.primary, shape) else Modifier)
                .padding(if (selected) 4.dp else 0.dp)
                .clip(RoundedCornerShape(if (selected) 18.dp else 14.dp)),
        ) {
            StagePreview(mode, song, prefs, Modifier.fillMaxSize())
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = scheme.onPrimary)
                }
            }
        }
        Text(
            mode.label,
            Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 按速度的四档：每档一行，值是当前对应的模式，点开选。 */
@Composable
private fun TempoRows(rules: List<TempoRule>, onChange: (Int, String) -> Unit) {
    SegmentedGroup {
        rules.forEachIndexed { index, rule ->
            var open by remember { mutableStateOf(false) }
            Box {
                ListRow(
                    title = TEMPO_NAMES.getOrElse(index) { "第 ${index + 1} 档" },
                    index = index,
                    count = rules.size,
                    onClick = { open = true },
                    summary = tempoRange(rules, index),
                    value = LyricVisualizers.byId(rule.mode).label,
                )
                DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
                    LyricVisualizers.all.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            trailingIcon = if (mode.id == rule.mode) ({ Icon(Icons.Filled.Check, null) }) else null,
                            onClick = {
                                open = false
                                onChange(index, mode.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** “每分钟 90–120 拍”：左闭右开，上一档的上限是这一档的下限。 */
internal fun tempoRange(rules: List<TempoRule>, index: Int): String {
    val upper = rules[index].maxBpm
    val lower = rules.getOrNull(index - 1)?.maxBpm
    return when {
        lower == null -> "每分钟 $upper 拍以下"
        upper >= TEMPO_RULE_OPEN_END -> "每分钟 $lower 拍及以上"
        else -> "每分钟 $lower–$upper 拍"
    }
}

/** 当前模式的设置表、恢复默认、来源和没放进来的项数。 */
@Composable
private fun ModeSettings(mode: LyricVisualizer, prefs: LyricStagePrefs, actions: MusicActions) {
    val raw = prefs.pluginSettings[mode.id].orEmpty()
    val values = remember(mode, raw) { SettingValues(mode.settings, raw) }
    val write: (String, String) -> Unit = { key, value ->
        actions.setLyricStage { p ->
            val clean = sanitizeSettings(mode.settings, p.pluginSettings[mode.id].orEmpty() + (key to value))
            p.copy(pluginSettings = if (clean.isEmpty()) p.pluginSettings - mode.id else p.pluginSettings + (mode.id to clean))
        }
    }
    SectionLabel("“${mode.label}”的设置") {
        if (mode.settings.isNotEmpty()) {
            TextButton(
                onClick = { actions.setLyricStage { it.copy(pluginSettings = it.pluginSettings - mode.id) } },
                enabled = values.changed > 0,
                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
            ) { Text("恢复默认") }
        }
    }
    if (mode.settings.isEmpty()) {
        SheetNote(
            if (mode.id == ClassicVisualizer.id) "经典就是原来的整页歌词列表，点一句跳过去，没有可调的项。" else "这个动画没有可调的项。",
        )
    } else {
        SegmentedGroup {
            mode.settings.forEachIndexed { index, setting ->
                val count = mode.settings.size
                when (setting) {
                    is LyricSetting.Number -> NumberRow(setting, values.number(setting.key), index, count) { write(setting.key, it.toString()) }
                    is LyricSetting.Choice -> ChoiceRow(setting, values.choice(setting.key), index, count) { write(setting.key, it) }
                    is LyricSetting.Toggle -> SwitchRow(
                        title = setting.label,
                        checked = values.toggle(setting.key),
                        onCheckedChange = { write(setting.key, it.toString()) },
                        index = index,
                        count = count,
                        summary = setting.summary.orEmpty(),
                    )
                }
            }
        }
    }
    mode.origin?.let { origin ->
        val hidden = if (mode.hiddenSettings > 0) "原${origin.kind}另有 ${mode.hiddenSettings} 项保持默认值。" else ""
        SheetNote("移植自 ${origin.mod} ${origin.version}（${origin.author}，${origin.license}）。$hidden")
    }
}

/** 数字设置：名字在左，−、当前值、+ 在右；到头了按钮不禁用（电视上焦点不会跳走），只是不再变。 */
@Composable
private fun NumberRow(setting: LyricSetting.Number, value: Float, index: Int, count: Int, onChange: (Float) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(segmentShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(setting.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            setting.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        FilledTonalIconButton(
            onClick = { onChange(setting.clamp(value - setting.step)) },
            modifier = Modifier.tvFocusRing(CircleShape),
        ) { Icon(Icons.Filled.Remove, contentDescription = "${setting.label}减小") }
        Text(
            setting.format(value),
            Modifier.widthIn(min = 56.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        FilledTonalIconButton(
            onClick = { onChange(setting.clamp(value + setting.step)) },
            modifier = Modifier.tvFocusRing(CircleShape),
        ) { Icon(Icons.Filled.Add, contentDescription = "${setting.label}增大") }
    }
}

/** 选项设置：名字一行，下面连接按钮组。 */
@Composable
private fun ChoiceRow(setting: LyricSetting.Choice, value: String, index: Int, count: Int, onChange: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(segmentShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(setting.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            setting.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ConnectedToggleGroup(
            options = setting.options.map { it.first },
            selected = value,
            onSelect = onChange,
            label = { key -> setting.options.firstOrNull { it.first == key }?.second ?: key },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SheetNote(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal val LyricAutoSwitch.label: String
    get() = when (this) {
        LyricAutoSwitch.Fixed -> "固定"
        LyricAutoSwitch.Random -> "随机"
        LyricAutoSwitch.Tempo -> "按速度"
    }

private fun autoNote(auto: LyricAutoSwitch): String = when (auto) {
    LyricAutoSwitch.Fixed -> "所有歌都用上面选中的动画。"
    LyricAutoSwitch.Random -> "每首歌随机换一个，不和上一首重复；同一首歌再听还是它（记最近 2000 首）。在上面点了某个动画，这首歌就一直用它。"
    LyricAutoSwitch.Tempo -> "听出速度后，在两句之间的空当换成对应的动画，不会在一句唱到一半时换；测到的速度会记下来，下次一开始就换。在上面点了某个动画，这首歌就一直用它。"
}

private val TEMPO_NAMES = listOf("慢", "中", "快", "很快")
