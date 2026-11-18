package com.mytv.mymusic.ui.player.stage

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.data.LyricAutoSwitch
import com.mytv.mymusic.data.LyricStagePrefs
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.StagePreset
import com.mytv.mymusic.data.TEMPO_RULE_OPEN_END
import com.mytv.mymusic.data.TempoRule
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.adaptive.DialogAvoidsFold
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.FoldSide
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.LocalSheetClose
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentedGroup
import com.mytv.mymusic.ui.components.SheetDismissGuard
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.segmentShape
import com.mytv.mymusic.ui.components.tvFocusRing
import com.mytv.mymusic.ui.library.TvDefaultFocus
import com.mytv.mymusic.ui.player.ToolbarIconButton
import com.mytv.mymusic.ui.theme.LocalAppearance
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 播放页给面板的“这首歌”一行：正在显示 [shownId]，理由 [decision]，固定用的 [pinnedId]；
 * [remembered]、[bpm] 是记忆里这首的随机结果和速度（算保存后用哪个）；[waitingFor] 是想换、在等空当的那个。
 * [onPin] 写这首歌固定用哪个（null 取消固定），立刻生效、不进草稿。
 */
internal class SongRowState(
    val shownId: String,
    val decision: SongDecision,
    val pinnedId: String?,
    val remembered: String?,
    val bpm: Float?,
    val waitingFor: String?,
    val onPin: (String?) -> Unit,
) {
    val pinned: Boolean get() = pinnedId != null
}

/**
 * 歌词动画面板（全屏歌词页的 ✦、平板工具栏和专注栏、电视控制条、设置 › 播放页都打开它）。从上到下：
 * 这首歌正在用哪个、为什么（[songRow]，从设置页打开时没有）→ 每首歌换动画（固定 / 随机 / 按速度，按速度时四档跟在下面）
 * → 模式卡片（固定单选、随机多选组成随机池、按速度整块灰掉）→ 频谱 → 设置（下拉选改哪个模式；舞台不在旁边时顶上有大预览）
 * → 随机时“忘掉记住的结果”。
 *
 * 面板里的改动先进草稿 [draft]，底部浮出保存条，“保存并应用”一次写进设置并让这首歌马上按新设置换（[onApplied]，不等空当）；
 * 有改动时下滑、点外面、返回都先问。这首歌固定用哪个、忘掉记忆是点了就生效的。
 *
 * @param stageBeside 舞台露在面板旁边（平板、横屏、书本姿态、电视）：草稿直接画在舞台上，面板里不放大预览，遮罩调淡。
 * @param onApplied 保存后调，参数是这首歌保存后应该用的模式（随机换法还没记过这首时就用它）。
 */
@Composable
internal fun AnimationSheet(
    saved: LyricStagePrefs,
    draft: AnimationDraftState,
    song: Song?,
    songRow: SongRowState?,
    stageBeside: Boolean,
    actions: MusicActions,
    onApplied: (previewId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 打开过一次面板，✦ 外面的呼吸提示就不再显示
    LaunchedEffect(Unit) {
        if (!saved.hintSeen) actions.setLyricStage { it.copy(hintSeen = true) }
    }
    // 设备档位（模糊默认开不开）要在读设置表之前定下来
    ResolveStageDevice()
    val tv = LocalAppWindow.current.isTv
    val d = draft.draft
    val dirty = isDirty(saved, d)
    // 颜色项切到能看见时的初值（封面的鲜艳色等）、取色面板的“从封面取”都按这首歌的舞台配色算
    val stageColors = rememberStageColors(song?.cover.orEmpty())
    // 正在取色的颜色项（点了设置表里的色块）
    var picking by remember { mutableStateOf<ColorPick?>(null) }
    // 起名 / 改名对话框
    var naming by remember { mutableStateOf<PresetNaming?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 电视上带撤销的提示条把焦点借到“撤销”上（方向键走不到浮在底部的提示条），收起时焦点放到 Undo.focusAfter 给的地方
    var undoFocused by remember { mutableStateOf(false) }
    // 每张模式卡片的焦点请求器：删预设后落在基础模式卡片上，撤销后落回预设卡片
    val cardFocus = remember { HashMap<String, FocusRequester>() }
    val focusCard: (String) -> FocusRequester = { id -> cardFocus.getOrPut(id) { FocusRequester() } }
    // 面板是单独的窗口（平板、电视），外壳的提示条会被盖住：提示条放在面板里
    val toast: (String, Undo?) -> Unit = { text, undo ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            // 带撤销的留久一点（删预设、忘掉记忆），来得及点
            val result = snackbar.showSnackbar(
                text,
                actionLabel = if (undo != null) "撤销" else null,
                duration = if (undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            val refocus = undoFocused
            val undone = result == SnackbarResult.ActionPerformed
            if (undone) undo?.run?.invoke()
            val target = undo?.focusAfter(undone)
            // 用户自己走开了就不动；撤销放回来的东西要等它画出来、能聚焦了才接得住焦点
            if (refocus && target != null) {
                repeat(REFOCUS_TRIES) {
                    delay(REFOCUS_WAIT_MS)
                    if (runCatching { target.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return@launch
                }
            }
        }
    }
    AdaptiveSheet(
        onDismiss = onDismiss,
        title = "歌词动画",
        subtitle = if (tv) "遥控器：↑↓←→ 移动 · 确认选择 · 返回关闭" else "只在全屏歌词页生效",
        dismissGuard = SheetDismissGuard({ isDirty(saved, draft.draft) && !draft.saving }, { draft.asking = true }),
        foldSide = FoldSide.Start,
        // 舞台在旁边预览草稿：遮罩调淡，配色看得准
        scrimAlpha = if (stageBeside) 0.08f else 0.32f,
    ) {
        val close = LocalSheetClose.current
        val presetOps = PresetOps(saved, draft, actions, toast, focusCard) { naming = it }
        val save: () -> Unit = save@{
            val current = draft.draft
            if (!canSave(current) || draft.saving) return@save
            val applied = previewId(current, saved, songRow?.pinnedId, songRow?.remembered, songRow?.shownId, songRow?.bpm, editTarget = null)
            draft.saving = true
            draft.appliedId = if (songRow != null) applied else null
            actions.setLyricStage { it.withDraft(current) }
            onApplied(applied)
            val measuring = songRow != null && !songRow.pinned && current.auto == LyricAutoSwitch.Tempo && songRow.bpm == null
            toast(
                when {
                    songRow == null -> "已保存并应用"
                    measuring -> "已应用，听出这首的速度后换成对应的动画"
                    else -> "已应用，这首歌现在用「${current.modeOf(applied).label}」"
                },
                null,
            )
        }
        // 保存后等设置写回来、这首歌换上了，才算存完（超过 1.5 秒不再等）；对话框里点的保存，存完再关面板
        val shownNow = rememberUpdatedState(songRow?.shownId)
        LaunchedEffect(draft.saving, dirty) {
            if (!draft.saving || dirty) return@LaunchedEffect
            val target = draft.appliedId
            withTimeoutOrNull(SAVE_SETTLE_MS) { snapshotFlow { shownNow.value }.first { it == null || target == null || it == target } }
            draft.saving = false
            if (draft.closeAfterSave) {
                draft.closeAfterSave = false
                close()
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // 滚动区是一个焦点组：电视上保存条压在它上面时从保存条按 ↑ 进不去，保存条出来时滚动区让到它上面
                    .padding(bottom = if (tv && dirty) SAVE_BAR_HEIGHT else 0.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                if (songRow != null) {
                    SongModeRow(
                        row = songRow,
                        saved = saved,
                        song = song,
                        onPin = { id ->
                            songRow.onPin(id)
                            toast("这首歌固定用「${saved.modeOf(id).label}」，立刻生效", null)
                        },
                        onUnpin = {
                            songRow.onPin(null)
                            toast("已取消固定，这首歌跟着换法走", null)
                        },
                    )
                }

                SectionLabel("每首歌换动画")
                val autoFocus = remember { FocusRequester() }
                ConnectedToggleGroup(
                    options = LyricAutoSwitch.entries,
                    selected = d.auto,
                    onSelect = { value -> draft.update { it.copy(auto = value) } },
                    label = { it.label },
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    selectedFocus = autoFocus,
                )
                TvDefaultFocus(autoFocus)
                SheetNote(autoNote(d.auto))
                if (d.auto == LyricAutoSwitch.Tempo) {
                    Spacer(Modifier.size(8.dp))
                    TempoRows(d.tempoRules, d) { index, id ->
                        draft.update { p ->
                            p.copy(tempoRules = p.tempoRules.mapIndexed { i, rule -> if (i == index) rule.copy(mode = id) else rule })
                        }
                    }
                }

                val cards = d.cards()
                when (d.auto) {
                    LyricAutoSwitch.Fixed -> SectionLabel("所有歌都用")
                    LyricAutoSwitch.Random -> SectionLabel("从这些里随机 · ${cards.count { it.id in d.randomPool }}/${cards.size}") {
                        TextButton(
                            onClick = { draft.update { it.copy(randomPool = it.cards().map { mode -> mode.id }.toSet()) } },
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                        ) { Text("全选") }
                        TextButton(
                            onClick = { draft.update { it.copy(randomPool = emptySet()) } },
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                        ) { Text("清空") }
                    }
                    LyricAutoSwitch.Tempo -> SectionLabel("动画 · 由上面四档决定")
                }
                // 设置区改哪个：下拉里明确选过的；否则固定时是选中的，自动换时是这首歌正在显示的
                val target = draft.editTarget
                    ?: if (d.auto == LyricAutoSwitch.Fixed) d.visualizer else songRow?.shownId ?: d.visualizer
                ModeGrid(cards, d, song, songRow?.shownId, focusCard, presetMenu = { card -> presetOps.cardMenu(card, target) }) { id ->
                    draft.update { p ->
                        when (p.auto) {
                            LyricAutoSwitch.Fixed -> p.copy(visualizer = id)
                            LyricAutoSwitch.Random -> p.copy(randomPool = if (id in p.randomPool) p.randomPool - id else p.randomPool + id)
                            LyricAutoSwitch.Tempo -> p
                        }
                    }
                }
                if (d.auto == LyricAutoSwitch.Random) {
                    when (cards.count { it.id in d.randomPool }) {
                        0 -> SheetNote("至少选 1 个才能保存。", MaterialTheme.colorScheme.error)
                        1 -> SheetNote("只选了 1 个，等于固定用它。")
                    }
                }

                SectionLabel("叠加")
                SwitchRow(
                    title = "频谱",
                    checked = d.spectrum,
                    onCheckedChange = { on -> draft.update { it.copy(spectrum = on) } },
                    index = 0,
                    count = 1,
                    summary = "全屏歌词页左下角显示 48 段频谱、响度和峰值频率；经典模式不显示",
                )

                ModeSettings(
                    target = target,
                    draft = d,
                    previewPrefs = saved.withDraft(d),
                    song = song,
                    big = !stageBeside,
                    stageColors = stageColors,
                    onTarget = { draft.editTarget = it },
                    onWrite = { transform -> draft.update { p -> p.withSettings(target, transform(p.settingsFor(target))) } },
                    onPickColor = { setting ->
                        val values = SettingValues(d.modeOf(target).settings, d.settingsFor(target))
                        picking = ColorPick(target, setting.key, values.color(setting.key))
                    },
                    onShuffle = {
                        val schema = d.modeOf(target).settings
                        draft.update { p -> p.withSettings(target, shuffleSettings(schema, p.settingsFor(target), Random.Default)) }
                        toast("随机搭了一套，满意就「存成新预设」", null)
                    },
                    onSaveAs = { presetOps.askSaveAs(target) },
                    onOverwrite = { presetOps.overwrite(target, source = target) },
                    onDelete = { presetOps.delete(target) },
                )

                if (d.auto == LyricAutoSwitch.Random) ForgetRow(actions, toast)
                // 保存条浮在底部，最后一项要能滚到它上面
                Spacer(Modifier.height(84.dp))
            }
            SaveBar(
                visible = dirty,
                count = animationDiff(saved, d).size,
                enabled = canSave(d) && !draft.saving,
                onDiscard = { draft.discard(saved) },
                onSave = save,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (dirty) SAVE_BAR_HEIGHT else 0.dp)) { data ->
                val label = data.visuals.actionLabel
                if (tv && label != null) {
                    TvUndoSnackbar(data, label) { undoFocused = it }
                } else {
                    Snackbar(data)
                }
            }
        }

        naming?.let { request ->
            PresetNameDialog(request, onDismiss = { naming = null }) { name ->
                naming = null
                request.onConfirm(name)
            }
        }

        picking?.let { pick ->
            ColorPickerFor(
                pick = pick,
                draft = draft,
                saved = saved,
                stageColors = stageColors,
                stageBeside = stageBeside,
                actions = actions,
                onClose = { picking = null },
            )
        }

        if (draft.asking) {
            DiscardDialog(
                diff = animationDiff(saved, d),
                canSave = canSave(d),
                onSave = {
                    draft.asking = false
                    draft.closeAfterSave = true
                    save()
                },
                onDiscard = {
                    draft.asking = false
                    draft.discard(saved)
                    close()
                },
                onBack = { draft.asking = false },
            )
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

/**
 * “这首歌：黑胶唱片 / 上次听时抽到的，已记住”：左边小缩略图，右边按钮——没固定时“单独指定… / 改…”弹出全部卡片，
 * 选了就固定用它；固定了是“取消固定”。
 */
@Composable
private fun SongModeRow(
    row: SongRowState,
    saved: LyricStagePrefs,
    song: Song?,
    onPin: (String) -> Unit,
    onUnpin: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shown = saved.modeOf(row.shownId)
    val reason = when {
        row.pinned -> "已固定，以后放这首都用它"
        else -> when (val decision = row.decision) {
            is SongDecision.Pinned -> "已固定，以后放这首都用它"
            is SongDecision.Fixed -> "跟着下面的选择"
            is SongDecision.Remembered -> "上次听时抽到的，已记住"
            is SongDecision.Drawn -> "这次从池里抽到的"
            is SongDecision.Tempo ->
                "测得 ${decision.bpm.roundToInt()} 拍/分 → 落在「${TEMPO_NAMES.getOrElse(decision.tier) { "第 ${decision.tier + 1} 档" }}」"
            SongDecision.Measuring -> "正在听速度…"
            SongDecision.NoAnalyzer -> "听不出速度，先用正在显示的"
            SongDecision.Unknown -> "正在决定…"
        }
    }
    val waiting = row.waitingFor?.let { "（等空当换成「${saved.modeOf(it).label}」）" }.orEmpty()
    val texts: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            Text(
                "这首歌：${shown.label}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                reason + waiting,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
    val button: @Composable () -> Unit = {
        if (row.pinned) {
            FilledTonalButton(onClick = onUnpin, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("取消固定") }
        } else {
            var open by remember { mutableStateOf(false) }
            Box {
                FilledTonalButton(onClick = { open = true }, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                    Icon(Icons.Filled.PushPin, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (row.decision is SongDecision.Fixed) "单独指定…" else "改…")
                }
                ModeMenu(open, saved.cards(), current = row.shownId, onDismiss = { open = false }) { id ->
                    open = false
                    onPin(id)
                }
            }
        }
    }
    BoxWithConstraints(
        Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainer)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        val thumb = Modifier.size(56.dp, 38.dp).clip(RoundedCornerShape(10.dp))
        if (maxWidth >= SONG_ROW_INLINE_WIDTH) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StagePreview(shown, song, saved, thumb)
                texts(Modifier.weight(1f))
                button()
            }
        } else {
            // 窄面板（横屏手机的侧边面板扣掉导航栏后不到 330dp）：按钮挪到第二行，理由不会被挤成一字一行
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StagePreview(shown, song, saved, thumb)
                    texts(Modifier.weight(1f))
                }
                Box(Modifier.align(Alignment.End)) { button() }
            }
        }
    }
}

/** 预设卡片长按菜单的一项。 */
private class PresetMenuItem(val label: String, val icon: ImageVector, val danger: Boolean = false, val onClick: () -> Unit)

/** 起名对话框：[title]、[note] 是标题和说明，[initial] 是默认名，确定后调 [onConfirm]。 */
private class PresetNaming(val title: String, val note: String, val initial: String, val onConfirm: (String) -> Unit)

/**
 * 提示条上的“撤销”：[run] 是撤销要做的事。电视上焦点借给了“撤销”，提示条收起后放到 [focusAfter] 给的地方
 * （参数是撤没撤销；给 null 就不动）。
 */
private class Undo(val focusAfter: (undone: Boolean) -> FocusRequester?, val run: suspend () -> Unit)

/**
 * 预设的新建、改名、覆盖、删除：都是点了立即写进设置（不等“保存并应用”），草稿里同步做同样的改动，免得保存时又改回去。
 * [ask] 弹起名对话框，[toast] 是面板里的提示条（删除带撤销），[focusCard] 是卡片的焦点请求器（删除后电视上焦点落到哪张卡片）。
 */
private class PresetOps(
    private val saved: LyricStagePrefs,
    private val draft: AnimationDraftState,
    private val actions: MusicActions,
    private val toast: (String, Undo?) -> Unit,
    private val focusCard: (String) -> FocusRequester,
    private val ask: (PresetNaming) -> Unit,
) {
    private fun preset(id: String): StagePreset? = draft.draft.presetOf(id) ?: saved.presetOf(id)

    /** 设置区改的是 [target]：把它的草稿设置存成一张新卡片，目标那边的草稿改回已保存的，接着改新预设。 */
    fun askSaveAs(target: String) {
        val base = LyricVisualizers.byId(baseIdOf(target))
        val all = (saved.presets + draft.draft.presets).distinctBy { it.id }
        ask(
            PresetNaming("存成新预设", "会出现在模式卡片里，排在 ${base.label} 后面。", defaultPresetName(base.label, all)) { name ->
                val id = newPresetId(base.id, all.map { it.id }, Random.Default)
                val preset = StagePreset(id, base.id, name, sanitizeSettings(base.settings, draft.draft.settingsFor(target)))
                actions.setLyricStage { it.copy(presets = it.presets + preset) }
                draft.update { p -> p.copy(presets = p.presets + preset).withSettings(target, saved.settingsFor(target)) }
                draft.editTarget = id
                toast("已存为「${preset.name}」，在模式卡片里", null)
            },
        )
    }

    fun rename(id: String) {
        val preset = preset(id) ?: return
        ask(
            PresetNaming("重命名预设", "新名字马上生效。", preset.name) { name ->
                actions.setLyricStage { p -> p.copy(presets = p.presets.map { if (it.id == id) it.copy(name = name) else it }) }
                draft.update { p -> p.copy(presets = p.presets.map { if (it.id == id) it.copy(name = name) else it }) }
            },
        )
    }

    /** 用 [source] 那张卡片现在（草稿里）的设置覆盖预设 [id]，立即写。 */
    fun overwrite(id: String, source: String) {
        val preset = preset(id) ?: return
        val base = LyricVisualizers.byId(preset.base)
        val settings = sanitizeSettings(base.settings, draft.draft.settingsFor(source))
        actions.setLyricStage { p -> p.copy(presets = p.presets.map { if (it.id == id) it.copy(settings = settings) else it }) }
        draft.update { p -> p.copy(presets = p.presets.map { if (it.id == id) it.copy(settings = settings) else it }) }
        toast("已覆盖「${preset.name}」", null)
    }

    /** 删除预设 [id]：用到它的地方改回基础模式，提示条里能撤销（只改回那些现在还停在基础模式上的）。 */
    fun delete(id: String) {
        val preset = preset(id) ?: return
        val savedBefore = saved
        val draftBefore = draft.draft
        val draftPreset = draftBefore.presetOf(id) ?: preset
        val count = maxOf(savedBefore.withoutPreset(id).second, draftBefore.withoutPreset(id).second)
        actions.setLyricStage { it.withoutPreset(id).first }
        draft.update { it.withoutPreset(id).first }
        if (draft.editTarget == id) draft.editTarget = preset.base
        val baseLabel = LyricVisualizers.byId(preset.base).label
        val text = if (count > 0) "已删除「${preset.name}」，用到它的 $count 处改回 $baseLabel" else "已删除「${preset.name}」"
        toast(text, Undo(focusAfter = { undone -> focusCard(if (undone) id else preset.base) }) {
            actions.setLyricStage { it.withPresetRestored(preset, savedBefore) }
            draft.update { it.withPresetRestored(draftPreset, draftBefore) }
        })
    }

    /** 预设卡片的长按菜单。“用当前设置覆盖”：设置区正在改同一个模式（或它的预设）就用它的草稿设置，否则用基础模式的。 */
    fun cardMenu(card: PresetVisualizer, target: String): List<PresetMenuItem> {
        val source = if (baseIdOf(target) == card.base.id) target else card.base.id
        return listOf(
            PresetMenuItem("重命名", Icons.Filled.DriveFileRenameOutline) { rename(card.id) },
            PresetMenuItem("用当前设置覆盖", Icons.Filled.Save) { overwrite(card.id, source) },
            PresetMenuItem("删除", Icons.Filled.Delete, danger = true) { delete(card.id) },
        )
    }
}

/** 起名对话框：去掉首尾空格后不能空，最多 [PRESET_NAME_MAX] 个字。电视上焦点默认在“保存”。 */
@Composable
private fun PresetNameDialog(request: PresetNaming, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val tv = LocalAppWindow.current.isTv
    var field by remember { mutableStateOf(TextFieldValue(request.initial, TextRange(0, request.initial.length))) }
    val fieldFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val name = field.text.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            DialogAvoidsFold()
            Text(request.title)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(request.note)
                OutlinedTextField(
                    value = field,
                    onValueChange = { if (it.text.length <= PRESET_NAME_MAX) field = it },
                    singleLine = true,
                    label = { Text("名字") },
                    supportingText = { Text("${name.length}/$PRESET_NAME_MAX") },
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotEmpty(),
                modifier = Modifier.focusRequester(saveFocus).tvFocusRing(RoundedCornerShape(20.dp)),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("取消") }
        },
    )
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { (if (tv) saveFocus else fieldFocus).requestFocus() }
    }
}

/** 设置表里点了哪张卡片 [target] 的哪个颜色项 [key]，打开时是什么颜色 [initial]（“取消”改回它）。 */
private class ColorPick(val target: String, val key: String, val initial: Color)

/**
 * 给 [pick] 那一项开取色面板：改的颜色实时写进草稿。同一显示条件下的颜色项算一对（第一个是底色、第二个是字色），
 * 预览和对比度按这一对画；“完成”时把颜色记进“最近用过”（立即写，不进草稿）。
 */
@Composable
private fun ColorPickerFor(
    pick: ColorPick,
    draft: AnimationDraftState,
    saved: LyricStagePrefs,
    stageColors: StageColors,
    stageBeside: Boolean,
    actions: MusicActions,
    onClose: () -> Unit,
) {
    val mode = draft.draft.modeOf(pick.target)
    val values = SettingValues(mode.settings, draft.draft.settingsFor(pick.target))
    val setting = mode.settings.firstOrNull { it.key == pick.key } as? LyricSetting.Color
    if (setting == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val group = mode.settings.filterIsInstance<LyricSetting.Color>().filter { it.visibleWhen == setting.visibleWhen }
    val pair = group.size >= 2
    val backgroundKey = if (pair) group[0].key else setting.key
    val textKey = if (pair) group[1].key else null
    val write: (String, Color) -> Unit = { key, color ->
        draft.update { p -> p.withSettings(pick.target, p.settingsFor(pick.target) + (key to color.toHex())) }
    }
    // “Rush · 自定义配色”：模式名加上让它显示出来的那个选项
    val condition = setting.visibleWhen
    val choice = condition?.let { c -> mode.settings.firstOrNull { it.key == c.key } as? LyricSetting.Choice }
    val reason = choice?.let { c -> c.options.firstOrNull { it.first == values.choice(c.key) }?.second?.plus(c.label) }
    val background = values.color(backgroundKey)
    ColorPickerSheet(
        title = "${setting.label}色",
        subtitle = listOfNotNull(mode.label, reason).joinToString(" · "),
        initial = pick.initial,
        background = background,
        text = textKey?.let(values::color) ?: readableOn(background),
        editingText = setting.key == textKey,
        pairs = pair,
        coverSwatches = coverSwatches(stageColors),
        recent = saved.recentColors.mapNotNull(::parseHexColor),
        stageBeside = stageBeside,
        onChange = { write(setting.key, it) },
        onAutoText = { color -> textKey?.let { write(it, color) } },
        onDone = { color ->
            actions.setLyricStage { it.copy(recentColors = pushRecentColor(it.recentColors, color)) }
            onClose()
        },
        onCancel = onClose,
    )
}

/** “这首歌”一行宽度够这么多时按钮和文字排一行，不够时按钮挪到第二行。 */
private val SONG_ROW_INLINE_WIDTH = 330.dp

/** 列出全部卡片的下拉菜单，[current] 那个打勾。 */
@Composable
private fun ModeMenu(open: Boolean, cards: List<LyricVisualizer>, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    DropdownMenu(open, onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp)) {
        cards.forEach { mode ->
            DropdownMenuItem(
                text = { Text(mode.label) },
                trailingIcon = if (mode.id == current) ({ Icon(Icons.Filled.Check, null) }) else null,
                onClick = { onPick(mode.id) },
            )
        }
    }
}

private enum class CardMode { Fixed, Random, Tempo }

/**
 * 模式卡片两列排，含义跟着换法变：固定时单选（选中＝所有歌都用它）；随机时多选（选中的进随机池，右上角是勾或空心圈）；
 * 按速度时整块灰掉、不能点也不能聚焦（四档在上面选）。缩略图按草稿画；这首歌正在显示的那张左下角标“正在用”。
 */
@Composable
private fun ModeGrid(
    cards: List<LyricVisualizer>,
    draft: LyricStagePrefs,
    song: Song?,
    shownId: String?,
    focusCard: (String) -> FocusRequester,
    presetMenu: (PresetVisualizer) -> List<PresetMenuItem>,
    onClick: (String) -> Unit,
) {
    val mode = when (draft.auto) {
        LyricAutoSwitch.Fixed -> CardMode.Fixed
        LyricAutoSwitch.Random -> CardMode.Random
        LyricAutoSwitch.Tempo -> CardMode.Tempo
    }
    Column(
        Modifier.fillMaxWidth().then(if (mode == CardMode.Fixed) Modifier.selectableGroup() else Modifier),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { card ->
                    val selected = when (mode) {
                        CardMode.Fixed -> card.id == draft.visualizer
                        CardMode.Random -> card.id in draft.randomPool
                        CardMode.Tempo -> false
                    }
                    ModeCard(
                        card,
                        mode,
                        selected,
                        card.id == shownId,
                        draft,
                        song,
                        Modifier.weight(1f),
                        focusCard(card.id),
                        menu = if (card is PresetVisualizer && mode != CardMode.Tempo) ({ presetMenu(card) }) else null,
                    ) { onClick(card.id) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModeCard(
    card: LyricVisualizer,
    mode: CardMode,
    selected: Boolean,
    showing: Boolean,
    prefs: LyricStagePrefs,
    song: Song?,
    modifier: Modifier,
    focus: FocusRequester,
    menu: (() -> List<PresetMenuItem>)? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val outlined = selected && mode != CardMode.Tempo
    val shape = RoundedCornerShape(if (outlined) 22.dp else 14.dp)
    var menuOpen by remember { mutableStateOf(false) }
    // 预设卡片长按（电视上长按确认键）弹菜单：重命名、用当前设置覆盖、删除
    val longClick: (() -> Unit)? = if (menu != null) ({ menuOpen = true }) else null
    val interaction = when {
        mode == CardMode.Tempo ->
            // 按速度：只做预览，不能点，电视上也不能聚焦（从四档按 ↓ 直接跳到“叠加”）
            Modifier.focusProperties { canFocus = false }.semantics { disabled() }
        longClick != null -> Modifier
            .tvFocusRing(shape)
            .clip(shape)
            .semantics {
                if (mode == CardMode.Fixed) this.selected = selected else toggleableState = ToggleableState(selected)
            }
            .combinedClickable(
                role = if (mode == CardMode.Fixed) Role.RadioButton else Role.Checkbox,
                onLongClickLabel = "预设菜单",
                onLongClick = longClick,
                onClick = onClick,
            )
        mode == CardMode.Fixed -> Modifier.tvFocusRing(shape).clip(shape).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        else -> Modifier.tvFocusRing(shape).clip(shape).toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
    }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().focusRequester(focus).then(interaction).padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .then(
                        when {
                            mode == CardMode.Tempo -> Modifier.grayscale(0.38f)
                            mode == CardMode.Random && !selected -> Modifier.alpha(0.62f)
                            else -> Modifier
                        },
                    )
                    .then(if (outlined) Modifier.border(2.5.dp, scheme.primary, shape) else Modifier)
                    .padding(if (outlined) 4.dp else 0.dp)
                    .clip(RoundedCornerShape(if (outlined) 18.dp else 14.dp)),
            ) {
                StagePreview(card, song, prefs, Modifier.fillMaxSize())
                when {
                    mode == CardMode.Tempo -> Unit
                    selected -> Box(
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
                    // 随机池里没选的：空心白圈
                    mode == CardMode.Random -> Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                    )
                }
                if (showing) NowBadge(Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
            Row(
                Modifier.padding(start = 4.dp).then(if (mode == CardMode.Tempo) Modifier.alpha(0.45f) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    card.label,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (outlined) FontWeight.Bold else FontWeight.Medium,
                    color = if (outlined) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card is PresetVisualizer) PresetBadge()
            }
        }
        if (menu != null) {
            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }, shape = RoundedCornerShape(20.dp)) {
                menu().forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label, color = if (item.danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
                        leadingIcon = { Icon(item.icon, null, tint = if (item.danger) MaterialTheme.colorScheme.error else LocalContentColor.current) },
                        onClick = {
                            menuOpen = false
                            item.onClick()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 电视上带撤销的提示条：出现时焦点挪到“撤销”上，收起后由面板把焦点放回去（见 toast）。
 * 提示条压在滚动区上面，按 ↑ 走不回面板：↑ 就当“不撤销”，提示条收起、焦点回面板。
 * [onFocused] 报“撤销”是不是还拿着焦点，用户自己走开了就不动焦点。
 */
@Composable
private fun TvUndoSnackbar(data: SnackbarData, label: String, onFocused: (Boolean) -> Unit) {
    val actionFocus = remember { FocusRequester() }
    Snackbar(
        modifier = Modifier.padding(12.dp),
        action = {
            TextButton(
                onClick = { data.performAction() },
                modifier = Modifier
                    .focusRequester(actionFocus)
                    .onFocusChanged { onFocused(it.isFocused) }
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) data.dismiss()
                        true
                    }
                    .tvFocusRing(RoundedCornerShape(20.dp)),
                colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
            ) { Text(label) }
        },
    ) { Text(data.visuals.message) }
    LaunchedEffect(data) {
        delay(50)
        runCatching { actionFocus.requestFocus() }
    }
}

/** 预设卡片名字旁的“预设”角标。 */
@Composable
private fun PresetBadge() {
    Text(
        "预设",
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.tertiary)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        color = MaterialTheme.colorScheme.onTertiary,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/** 整块去色并压暗（按速度时的卡片）：离屏图层里套饱和度 0 的颜色矩阵，再乘 [alpha]。 */
private fun Modifier.grayscale(alpha: Float): Modifier = drawWithCache {
    val paint = Paint().apply {
        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        this.alpha = alpha
    }
    onDrawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            drawContent()
            canvas.restore()
        }
    }
}

/** 卡片左下角“正在用”：三根小柱加字。 */
@Composable
private fun NowBadge(modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(start = 5.dp, end = 7.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.height(9.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
            listOf(5.dp, 9.dp, 6.dp).forEach { h ->
                Box(Modifier.width(2.dp).height(h).clip(RoundedCornerShape(1.dp)).background(Color.White))
            }
        }
        Text("正在用", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** 按速度的四档：每档一行，值是当前对应的模式，点开选。 */
@Composable
private fun TempoRows(rules: List<TempoRule>, prefs: LyricStagePrefs, onChange: (Int, String) -> Unit) {
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
                    value = prefs.modeOf(rule.mode).label,
                )
                ModeMenu(open, prefs.cards(), current = rule.mode, onDismiss = { open = false }) { id ->
                    open = false
                    onChange(index, id)
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

/**
 * 设置区：标题旁的下拉换改哪个模式；舞台不在旁边时顶上是大预览（按草稿画）；设置表、恢复默认、来源和没放进来的项数。
 * 改的都是草稿。
 */
@Composable
private fun ModeSettings(
    target: String,
    draft: LyricStagePrefs,
    previewPrefs: LyricStagePrefs,
    song: Song?,
    big: Boolean,
    stageColors: StageColors,
    onTarget: (String) -> Unit,
    onWrite: ((Map<String, String>) -> Map<String, String>) -> Unit,
    onPickColor: (LyricSetting.Color) -> Unit,
    onShuffle: () -> Unit,
    onSaveAs: () -> Unit,
    onOverwrite: () -> Unit,
    onDelete: () -> Unit,
) {
    val mode = draft.modeOf(target)
    val raw = draft.settingsFor(target)
    val values = remember(mode, raw) { SettingValues(mode.settings, raw) }
    val write: (String, String) -> Unit = { key, value -> onWrite { it + (key to value) } }
    SectionLabel("设置") {
        var open by remember { mutableStateOf(false) }
        Box {
            AssistChip(
                onClick = { open = true },
                label = { Text(mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                modifier = Modifier.widthIn(max = 200.dp).tvFocusRing(RoundedCornerShape(8.dp)),
            )
            ModeMenu(open, draft.cards(), current = target, onDismiss = { open = false }) { id ->
                open = false
                onTarget(id)
            }
        }
    }
    if (mode.settings.isNotEmpty()) {
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // 随机搭配：只抽选项类（背景、配色、对齐……），字号、景深、自定义色保留
            if (canShuffleSettings(mode.settings)) {
                TextButton(onClick = onShuffle, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                    Icon(Icons.Filled.Casino, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("随机搭配")
                }
            }
            TextButton(
                onClick = { onWrite { emptyMap() } },
                enabled = values.changed > 0,
                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
            ) { Text("恢复默认") }
        }
    }
    if (big) {
        Box(
            Modifier
                .padding(bottom = 12.dp)
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .clip(RoundedCornerShape(20.dp)),
        ) {
            StagePreview(mode, song, previewPrefs, Modifier.fillMaxSize(), big = true)
            Text(
                "预览 · 草稿",
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    if (mode.settings.isEmpty()) {
        SheetNote(
            if (mode.id == ClassicVisualizer.id) "经典就是原来的整页歌词列表，点一句跳过去，没有可调的项。" else "这个动画没有可调的项。",
        )
    } else {
        // 先去掉此刻不显示的项（比如没选“自定义”时的色块）再排；挂在某个选项下面的色块和它算一行
        val rows = settingRows(mode.settings.filter(values::visible))
        SegmentedGroup {
            rows.forEachIndexed { index, row ->
                val count = rows.size
                when (val setting = row.head) {
                    null -> ColorSegment(row.colors, values, index, count, onPickColor)
                    is LyricSetting.Number -> NumberRow(setting, values.number(setting.key), index, count) { write(setting.key, it.toString()) }
                    is LyricSetting.Choice -> ChoiceRow(
                        setting,
                        values.choice(setting.key),
                        index,
                        count,
                        below = if (row.colors.isEmpty()) null else ({ ColorRow(row.colors, values, onPickColor) }),
                    ) { value ->
                        // 这一下让颜色项显示出来、它又还没有值：按舞台配色种一个，切过去画面不跳
                        onWrite { raw -> seedNewlyVisibleColors(mode.settings, raw, raw + (setting.key to value), stageColors) }
                    }
                    is LyricSetting.Toggle -> SwitchRow(
                        title = setting.label,
                        checked = values.toggle(setting.key),
                        onCheckedChange = { write(setting.key, it.toString()) },
                        index = index,
                        count = count,
                        summary = setting.summary.orEmpty(),
                    )
                    is LyricSetting.Slider -> SliderRow(setting, values.slider(setting.key), index, count) { write(setting.key, it.toString()) }
                    is LyricSetting.Color -> ColorSegment(listOf(setting), values, index, count, onPickColor)
                }
            }
        }
    }
    if (mode.settings.isNotEmpty()) {
        // 预设小节：存成新预设；改的是预设时还能覆盖、删除（都点了就写，不进草稿）
        val preset = mode as? PresetVisualizer
        SectionLabel(if (preset != null) "预设「${preset.name}」" else "预设")
        val count = if (preset != null) 3 else 1
        SegmentedGroup {
            ListRow(
                title = "存成新预设",
                index = 0,
                count = count,
                onClick = onSaveAs,
                summary = "用现在这套设置新建一张卡片",
                trailing = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            if (preset != null) {
                ListRow(
                    title = "用现在的设置覆盖「${preset.name}」",
                    index = 1,
                    count = count,
                    onClick = onOverwrite,
                    trailing = { Icon(Icons.Filled.Save, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                ListRow(
                    title = "删除这个预设",
                    index = 2,
                    count = count,
                    onClick = onDelete,
                    summary = "用到它的地方改回 ${preset.base.label}",
                    danger = true,
                    trailing = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }
    mode.origin?.let { origin ->
        val hidden = if (mode.hiddenSettings > 0) "原${origin.kind}另有 ${mode.hiddenSettings} 项保持默认值。" else ""
        SheetNote("移植自 ${origin.mod} ${origin.version}（${origin.author}，${origin.license}）。$hidden")
    }
}

/** 随机换法时最后一行：忘掉每首歌记住的随机结果，点了立刻执行，提示条里能撤销。 */
@Composable
private fun ForgetRow(actions: MusicActions, toast: (String, Undo?) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val count by produceState(-1, version) { value = actions.lyricMemoryModeCount() }
    val scope = rememberCoroutineScope()
    val rowFocus = remember { FocusRequester() }
    Spacer(Modifier.size(12.dp))
    ListRow(
        title = "忘掉记住的结果",
        index = 0,
        count = 1,
        modifier = Modifier.focusRequester(rowFocus),
        onClick = if (count > 0) {
            {
                scope.launch {
                    val modes = actions.forgetLyricModes()
                    version++
                    // 撤销后这一行重新能点，焦点回到它上面；不撤销它就点不了了，焦点不管
                    toast("已忘掉 ${modes.size} 首的记忆", Undo(focusAfter = { undone -> rowFocus.takeIf { undone } }) {
                        actions.restoreLyricModes(modes)
                        version++
                    })
                }
            }
        } else {
            null
        },
        summary = when {
            count < 0 -> " "
            count == 0 -> "还没有记住的歌"
            else -> "已记住 $count 首；清掉后这些歌也从上面选中的里重新抽"
        },
        showChevron = false,
    )
}

/** 有改动时从底部浮出的保存条：“N 处改动 还没保存”、放弃、保存并应用。 */
@Composable
private fun SaveBar(visible: Boolean, count: Int, enabled: Boolean, onDiscard: () -> Unit, onSave: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        SaveBarContent(count, enabled, onDiscard, onSave)
    }
}

@Composable
private fun SaveBarContent(count: Int, enabled: Boolean, onDiscard: () -> Unit, onSave: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.padding(12.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHighest,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 两行各自不折：横屏面板窄时一行放不下，中文会在「保存」中间断开。
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    "$count 处改动",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
                Text("还没保存", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
            TextButton(onClick = onDiscard, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("放弃") }
            Button(onClick = onSave, enabled = enabled, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("保存并应用") }
        }
    }
}

/**
 * 有改动时关面板：列出改了什么，“保存并应用”或“放弃改动”；点外面、按返回回到面板接着改。
 * 电视上焦点默认在“保存并应用”（保存不了时在“放弃改动”）。书本姿态下挪到右页。
 */
@Composable
private fun DiscardDialog(diff: List<String>, canSave: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, onBack: () -> Unit) {
    val tv = LocalAppWindow.current.isTv
    val saveFocus = remember { FocusRequester() }
    val discardFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onBack,
        title = {
            DialogAvoidsFold()
            Text("保存并应用这些改动？")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("不保存的话，面板里的改动会丢掉，歌词页保持原样。")
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    diff.take(DIFF_LINES).forEach { line ->
                        Text("· $line", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (diff.size > DIFF_LINES) Text("等 ${diff.size} 处", style = MaterialTheme.typography.bodyMedium)
                }
                if (!canSave) Text("随机池至少选 1 个才能保存", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.focusRequester(saveFocus).tvFocusRing(RoundedCornerShape(20.dp)),
            ) { Text("保存并应用") }
        },
        dismissButton = {
            TextButton(onClick = onDiscard, modifier = Modifier.focusRequester(discardFocus).tvFocusRing(RoundedCornerShape(20.dp))) {
                Text("放弃改动")
            }
        },
    )
    if (tv) {
        LaunchedEffect(Unit) {
            delay(120)
            runCatching { (if (canSave) saveFocus else discardFocus).requestFocus() }
        }
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

/** 选项设置：名字一行，下面连接按钮组；[below] 是挂在这一项下面的东西（比如“自定义”配色的两个色块）。 */
@Composable
private fun ChoiceRow(
    setting: LyricSetting.Choice,
    value: String,
    index: Int,
    count: Int,
    below: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
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
            // 四个以上的选项（Rush 配色加了“自定义”）在电视侧栏里会挤成“播…”，收窄内边距
            compact = setting.options.size >= 4,
        )
        below?.invoke()
    }
}

/**
 * 滑块设置：名字和当前值（[LyricSetting.Slider.format]）一行，下面是连续拖动的滑块，离刻度近了吸过去，
 * 刻度下面一排名字。电视上左右键每次 ±[TV_SLIDER_STEP]、按住加速，上下键换到别的行。系统版本不够时灰掉。
 */
@Composable
private fun SliderRow(setting: LyricSetting.Slider, value: Float, index: Int, count: Int, onChange: (Float) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tv = LocalAppWindow.current.isTv
    val enabled = Build.VERSION.SDK_INT >= setting.minSdk
    val range = setting.min..setting.max
    Column(
        Modifier
            .fillMaxWidth()
            .clip(segmentShape(index, count))
            .background(scheme.surfaceContainer)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Text(setting.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = scheme.onSurface)
        Text(
            setting.format(value),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        setting.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
        Slider(
            value = value,
            onValueChange = { onChange(setting.clamp(setting.snap(it))) },
            valueRange = range,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                // 遥控器一下 ±0.05，不吸附（吸附范围比一步大，会一直吸回原刻度）
                .then(if (tv) Modifier.tvSliderKeys(value, TV_SLIDER_STEP, range) { onChange(setting.clamp(it)) } else Modifier)
                .tvFocusRing(RoundedCornerShape(12.dp)),
        )
        if (setting.tickLabels.isNotEmpty()) TickLabels(setting, enabled)
    }
}

/** 滑块下面的刻度名：每个名字的中心对着它的刻度，两头的贴边。 */
@Composable
private fun TickLabels(setting: LyricSetting.Slider, enabled: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
    Layout(
        content = {
            setting.tickLabels.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1) }
        },
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { i, placeable ->
                val tick = setting.ticks.getOrNull(i) ?: return@forEachIndexed
                val fraction = ((tick - setting.min) / (setting.max - setting.min)).coerceIn(0f, 1f)
                val x = (fraction * width - placeable.width / 2f).roundToInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                placeable.place(x, 0)
            }
        }
    }
}

/**
 * 电视遥控器调滑块：左右键每次 ±[step]，按住不放时越按越快（最多 5 倍）；上下键换到上一行 / 下一行，
 * 不让滑块拿上下键改值（M3 的滑块会这样做）。
 */
@Composable
internal fun Modifier.tvSliderKeys(
    value: Float,
    step: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
): Modifier {
    val focus = LocalFocusManager.current
    val current = rememberUpdatedState(value)
    val change = rememberUpdatedState(onChange)
    return onPreviewKeyEvent { event ->
        val down = event.type == KeyEventType.KeyDown
        when (event.key) {
            Key.DirectionLeft, Key.DirectionRight -> {
                if (down) {
                    val boost = (1 + event.nativeKeyEvent.repeatCount / TV_SLIDER_REPEATS_PER_BOOST).coerceAtMost(TV_SLIDER_MAX_BOOST)
                    val sign = if (event.key == Key.DirectionRight) 1f else -1f
                    change.value((current.value + sign * step * boost).coerceIn(range.start, range.endInclusive))
                }
                true
            }
            Key.DirectionUp, Key.DirectionDown -> {
                if (down) focus.moveFocus(if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                true
            }
            else -> false
        }
    }
}

/**
 * 一排色块按钮（照 Rush 卡片颜色行）：底色就是这项的颜色，铅笔和名字按底色取黑白；点开取色面板。
 */
@Composable
private fun ColorRow(colors: List<LyricSetting.Color>, values: SettingValues, onPick: (LyricSetting.Color) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { setting ->
            val color = values.color(setting.key)
            val content = readableOn(color)
            val shape = RoundedCornerShape(24.dp)
            Row(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .tvFocusRing(shape)
                    .clip(shape)
                    .background(color)
                    .border(1.dp, content.copy(alpha = 0.2f), shape)
                    .clickable(role = Role.Button, onClickLabel = "改${setting.label}色") { onPick(setting) }
                    .semantics(mergeDescendants = true) { stateDescription = color.toHex() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Edit, null, Modifier.size(20.dp), tint = content)
                Spacer(Modifier.width(8.dp))
                Text(setting.label, color = content, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** 不挂在选项下面的色块单独一行。 */
@Composable
private fun ColorSegment(colors: List<LyricSetting.Color>, values: SettingValues, index: Int, count: Int, onPick: (LyricSetting.Color) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(segmentShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ColorRow(colors, values, onPick)
    }
}

@Composable
private fun SheetNote(text: String, color: Color = Color.Unspecified) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal val LyricAutoSwitch.label: String
    get() = when (this) {
        LyricAutoSwitch.Fixed -> "固定"
        LyricAutoSwitch.Random -> "随机"
        LyricAutoSwitch.Tempo -> "按速度"
    }

private fun autoNote(auto: LyricAutoSwitch): String = when (auto) {
    LyricAutoSwitch.Fixed -> "所有歌都用下面选中的动画。"
    LyricAutoSwitch.Random -> "听过的歌用上次的；没听过的从下面选中的里随机抽一个，不和上一首重复（记最近 2000 首）。"
    LyricAutoSwitch.Tempo -> "听出速度后按下面四档挑；测到的速度会记下来，下次一开始就是它。卡片这时只做预览，不能选。"
}

/** 对话框里最多列几处改动。 */
private const val DIFF_LINES = 6

/** 保存后等这首歌换上新模式最多等多久。 */
private const val SAVE_SETTLE_MS = 1_500L

/** 保存条（含外边距）的高度：提示条浮在它上面，电视上滚动区让出这么多。 */
private val SAVE_BAR_HEIGHT = 72.dp

/** 提示条收起后把焦点放回去：每隔多久试一次、最多试几次（撤销放回来的卡片、重新能点的行要等一会儿）。 */
private const val REFOCUS_WAIT_MS = 50L
private const val REFOCUS_TRIES = 20

/** 电视上滑块左右键一下走多少。 */
private const val TV_SLIDER_STEP = 0.05f

/** 按住方向键时每重复几次加一倍速，最多几倍。 */
internal const val TV_SLIDER_REPEATS_PER_BOOST = 4
internal const val TV_SLIDER_MAX_BOOST = 5
