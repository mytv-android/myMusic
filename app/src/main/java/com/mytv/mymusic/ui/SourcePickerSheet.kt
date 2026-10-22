package com.mytv.mymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.PlaybackMode
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.aggregate.availabilityOf
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SongRow
import com.mytv.mymusic.ui.components.StatusGlyph
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing

/**
 * 换源：选择这首歌从哪个平台播放。手机是底部面板，平板是侧边面板，电视是右侧面板（AdaptiveSheet）。
 * 当前音源打勾；可用性同时用文字和形状标注。常从深色播放页打开，所以只用 colorScheme 角色。
 */
@Composable
fun SourcePickerSheet(state: MusicUiState, actions: MusicActions) {
    val track = state.sourcePickerTrack ?: return
    val tv = LocalAppWindow.current.isTv
    val providerIds = buildList {
        addAll(state.aggregation.enabledProviders)
        addAll(track.sources.map { it.providerId })
        addAll(track.alternatives.map { it.providerId })
    }.distinct()
    val ordered = state.aggregation.priorityFor(providerIds)
    // 只有正在播放的就是这首歌时才有“当前音源”
    val currentId = state.currentSource?.providerId?.takeIf { state.currentTrack?.id == track.id }
    val pinnedId = state.aggregation.pinnedProvider?.takeIf { state.aggregation.playbackMode == PlaybackMode.Pinned }
    var rememberChoice by rememberSaveable(track.id) { mutableStateOf(false) }
    var splitMode by rememberSaveable(track.id) { mutableStateOf(false) }
    var showAlternatives by rememberSaveable(track.id) { mutableStateOf(false) }
    val busy = state.sourcePickerBusy
    val canSplit = track.sources.size > 1

    fun availability(source: TrackSource?): Availability? =
        source?.let { availabilityOf(it, state.cookies[it.providerId].orEmpty()) }

    // 电视默认焦点：下一个完整可播的音源，没有就落在第一个非当前音源
    val focusId = ordered.firstOrNull { id ->
        id != currentId && availability(track.sources.find { it.providerId == id }) == Availability.Playable
    } ?: ordered.firstOrNull { it != currentId } ?: ordered.firstOrNull()
    val focus = remember { FocusRequester() }

    AdaptiveSheet(
        onDismiss = actions::closeSource,
        title = "选择音源",
        subtitle = listOf(track.title, track.artistLabel).filter { it.isNotBlank() }.joinToString(" · "),
    ) {
        // 放在面板内容里：电视上面板是 Dialog，内容要等窗口显示、进场动画开始后才组合，在外面请求时行还没挂上
        LaunchedEffect(track.id, tv) {
            if (tv && focusId != null) {
                withFrameNanos { }
                runCatching { focus.requestFocus() }
            }
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (state.sourcePickerError.isNotBlank()) {
                PickerNote(state.sourcePickerError, error = true)
            }
            if (state.sourcePickerInfo.isNotBlank()) {
                PickerNote(state.sourcePickerInfo, error = false)
            }
            if (splitMode) {
                Text(
                    "点按要拆分出去的音源。拆分后它会作为独立版本出现在搜索结果里，只影响本机。",
                    Modifier.padding(start = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                ordered.forEachIndexed { index, providerId ->
                    val source = track.sources.find { it.providerId == providerId }
                    SourceRow(
                        providerName = providerName(state.providers, providerId),
                        source = source,
                        availability = availability(source),
                        duration = track.durationLabel,
                        current = providerId == currentId,
                        pinned = providerId == pinnedId,
                        busy = busy == providerId,
                        splitMode = splitMode,
                        index = index,
                        count = ordered.size,
                        // 检索中不禁用整列：禁用会让遥控器焦点丢失，检索失败后无处可回；改为忽略点按
                        enabled = !splitMode || (source != null && canSplit),
                        onClick = {
                            when {
                                busy.isNotBlank() -> Unit
                                splitMode -> if (source != null) {
                                    splitMode = false
                                    actions.splitSource(track, source)
                                }
                                // 点当前音源不重新播放，除非要顺便固定这个平台
                                providerId == currentId && !rememberChoice -> actions.closeSource()
                                else -> actions.chooseSource(providerId, rememberChoice)
                            }
                        },
                        modifier = if (providerId == focusId) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 现有逻辑记住的是全局“固定平台”模式，不是只对这首歌生效，文案按实际行为写
            SwitchRow(
                title = "以后固定用所选平台",
                summary = "播放模式改为固定平台，可在“播放与换源”中改回自动",
                checked = rememberChoice,
                onCheckedChange = { rememberChoice = it },
                index = 0,
                count = 1,
                enabled = !splitMode,
            )
            if (track.alternatives.isNotEmpty()) {
                ExpandRow(
                    label = "其他版本 · ${track.alternatives.size}",
                    summary = "Live、伴奏、重制版不会自动合并",
                    expanded = showAlternatives,
                    onToggle = { showAlternatives = !showAlternatives },
                )
                if (showAlternatives) {
                    Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                        track.alternatives.forEachIndexed { index, alternative ->
                            AlternativeRow(track, alternative, index, track.alternatives.size, actions)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canSplit) {
                    TextButton(
                        onClick = { splitMode = !splitMode },
                        modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.CallSplit, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (splitMode) "取消拆分" else "匹配有误？拆分音源")
                    }
                }
                TextButton(
                    onClick = {
                        actions.closeSource()
                        actions.openMerge(track)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(RoundedCornerShape(24.dp)),
                ) {
                    Icon(Icons.Filled.Merge, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("合并到…")
                }
            }
            Text(
                "换源是在目标平台重新检索同一录音，不会转移会员权益。找不到时可以看看“其他版本”，或在设置里调整匹配严格度。",
                Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 平台行：左侧平台标记（当前音源为 primary 实心），中间可用性 + 音质，右侧是对勾、标签或“播放”。 */
@Composable
private fun SourceRow(
    providerName: String,
    source: TrackSource?,
    availability: Availability?,
    duration: String,
    current: Boolean,
    pinned: Boolean,
    busy: Boolean,
    splitMode: Boolean,
    index: Int,
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = segmentShape(index, count)
    val container = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val variant = if (current) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val detail = when {
        source == null -> "未匹配 · 点按后在该平台检索同一版本"
        else -> listOf(
            when (availability) {
                Availability.Playable -> "完整"
                Availability.PreviewOnly -> "试听片段"
                Availability.VipOnly -> "VIP · 需要会员"
                Availability.Unavailable -> "没有版权"
                else -> "待检测"
            },
            source.qualityLabel.takeIf { it != "音质未知" }.orEmpty(),
            duration,
        ).filter { it.isNotBlank() }.joinToString(" · ")
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                // “已固定”在行内有文字标签，这里只补当前音源，避免读两遍
                stateDescription = if (current) "当前音源" else ""
            }
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                providerMark(providerName),
                // 平台简称只是头像，后面紧跟全称
                Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    providerName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pinned) SmallLabel("已固定", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (availability != null) StatusGlyph(availability, glyphColor(availability), 8.dp)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = variant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            busy -> LoadingIndicator(Modifier.size(32.dp))
            splitMode -> if (source != null && enabled) {
                Text("拆分", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            current -> Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
            availability == Availability.VipOnly -> SmallLabel("VIP", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            availability == Availability.PreviewOnly -> SmallLabel("试听", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text(
                if (source == null) "检索" else "播放",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun glyphColor(status: Availability): Color = when (status) {
    Availability.Playable -> MaterialTheme.colorScheme.primary
    Availability.PreviewOnly -> MaterialTheme.colorScheme.tertiary
    Availability.VipOnly -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun SmallLabel(text: String, container: Color, content: Color) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = content,
        maxLines = 1,
    )
}

@Composable
private fun PickerNote(text: String, error: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
        Icon(if (error) Icons.Filled.ErrorOutline else Icons.Filled.Info, null, tint = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun ExpandRow(label: String, summary: String, expanded: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp)
            .heightIn(min = 48.dp)
            .tvFocusRing(shape, scale = 1.02f)
            .clip(shape)
            .clickable(onClick = onToggle)
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlternativeRow(track: CanonicalTrack, source: TrackSource, index: Int, count: Int, actions: MusicActions) {
    SongRow(
        title = source.song.title,
        subtitle = listOf(source.song.artist, source.providerName, source.qualityLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · "),
        cover = source.song.cover,
        index = index,
        count = count,
        onClick = { actions.playAlternative(track, source) },
        trailing = {
            TextButton(
                onClick = { actions.mergeAlternative(track, source) },
                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
            ) { Text("合并") }
        },
        onLongClick = null,
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
