/*
 * Copyright (C) 2026  Shubham Gorai
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 2026-11-09 myMusic 改写：取自 Rush 7.2.1 的 BaseSyncedLyrics.kt 与 SyllableSyncedLyrics.kt / LineSyncedLyrics.kt 的列表部分。
// 当前句停在 1/3、其余句缩到 0.8 并按距离模糊、拖动暂停 3 秒、距离超过 10 句直接跳、点一句跳过去照原样；
// 改动：列表排在舞台安全区里（最宽 720dp）、上下两端渐隐；锚点用上下留白 + 量出来的误差对齐，不靠每项高度表；
// 间奏三点按句间空档合成（myMusic 的解析器不保留空行）；换歌后新歌词到之前只显示三点；减少动画时整页静止。
package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.ui.player.lyricAnchorError
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.StageDisplay
import com.mytv.mymusic.ui.player.stage.StageLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 列表里的一行：一句歌词，或一段间奏三点（[line] 为 null）。[nextMs] 是下一行开始，算三点和整句进度用；
 * [placeholder] 是等歌词时那一行三点，不走进度。
 */
@Immutable
internal class RushRow(
    val key: String,
    val startMs: Long,
    val nextMs: Long?,
    val line: StageLine?,
    val placeholder: Boolean = false,
)

/**
 * 句子排成列表行。Rush 的歌词里空行就是间奏，myMusic 的解析器丢掉了空行，这里按空档补回来：
 * 第一句在 1 秒以后开始时前面放一行三点（开场），一句唱完到下一句开始隔了 5 秒以上，中间放一行三点。
 * 没有句子（等歌词）时只有一行占位三点。
 */
internal fun rushRows(lines: List<StageLine>): List<RushRow> {
    if (lines.isEmpty()) return listOf(RushRow("wait", 0L, null, null, placeholder = true))
    val out = ArrayList<RushRow>(lines.size + 4)
    val first = lines[0]
    if (first.startMs >= RUSH_INTRO_MIN_MS) out += RushRow("intro", 0L, first.startMs, null)
    lines.forEachIndexed { i, line ->
        val next = lines.getOrNull(i + 1)
        if (next != null && next.startMs - line.endMs >= RUSH_INTERLUDE_MIN_MS) {
            out += RushRow("l$i", line.startMs, line.endMs, line)
            out += RushRow("gap$i", line.endMs, next.startMs, null)
        } else {
            out += RushRow("l$i", line.startMs, next?.startMs, line)
        }
    }
    return out
}

/** [ms] 时的当前行：开始时间不晚于 [ms] 的最后一行，第一行之前算第一行（Rush 的 getCurrentLyricIndex）。 */
internal fun rushCurrentRow(rows: List<RushRow>, ms: Long): Int {
    var lo = 0
    var hi = rows.lastIndex
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (rows[mid].startMs <= ms) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found.coerceAtLeast(0)
}

/** 景深模糊（dp，按 28sp 计）：每离当前句一行多 3dp，最多 10dp；关掉或手动滚动时为 0。 */
internal fun rushBlurDp(distance: Int, enabled: Boolean, manual: Boolean): Float =
    if (!enabled || manual) 0f else (abs(distance) * RUSH_BLUR_STEP_DP).coerceAtMost(RUSH_BLUR_MAX_DP)

/** 自动滚动这一下要不要直接跳：还没摆过、减少动画、视口高度变了，或离第一个可见行超过 10 行。 */
internal fun rushShouldSnap(target: Int, firstVisible: Int, placed: Boolean, reduceMotion: Boolean, resized: Boolean): Boolean =
    !placed || reduceMotion || resized || abs(target - firstVisible) > RUSH_SNAP_ROWS

/** 列表的上下留白：上面 1/3、下面 2/3 视口高，第一行和最后一行也能停到 1/3 处。 */
internal fun rushAnchorPadding(viewport: Float): Pair<Float, Float> = viewport * RUSH_ANCHOR to viewport * (1f - RUSH_ANCHOR)

/** 当前设置的样子（组合阶段读一次）。 */
@Immutable
internal class RushLook(
    val background: String,
    val rhythm: String,
    val colors: String,
    val align: RushAlign,
    val size: Float,
    val blur: Boolean,
    val expressive: Boolean,
)

/**
 * Rush 的歌词列表，排在舞台安全区里：宽度最多 720dp、靠起始边，上下两端渐隐。
 * 每行按离当前行的距离缩放、模糊、调透明度（换行时各动画一次）；只有当前行的词每帧重画。
 * 拖动时暂停跟随、去掉模糊，松手 3 秒后滚回当前行；点一句从这句开头播放（[StageDisplay.touch] 时）。
 */
@Composable
internal fun RushLyricList(ctx: LyricStageContext, look: RushLook, content: State<Color>, modifier: Modifier = Modifier) {
    val display = ctx.display
    val reduceMotion = display.reduceMotion
    val rows = remember(ctx.lines) { rushRows(ctx.lines) }
    val wordTimed = remember(ctx.lines) { ctx.lines.any { it.words.isNotEmpty() } }
    val fontSp = display.textSp * RUSH_TEXT_RATIO * look.size
    val unit = fontSp / RUSH_BASE_FONT_SP
    val typography = MaterialTheme.typography.bodyLarge
    val styles = remember(typography, fontSp, look.expressive, look.align) {
        val lineHeight = fontSp * RUSH_LINE_HEIGHT_RATIO
        val base = typography.copy(
            fontSize = fontSp.sp,
            lineHeight = lineHeight.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
            textMotion = TextMotion.Animated,
        )
        RushStyles(
            base = base,
            subtitle = base.copy(fontSize = (fontSp * 0.75f).sp, lineHeight = (lineHeight * 0.75f).sp, fontWeight = FontWeight.Normal),
            expressive = look.expressive,
            align = look.align,
            unit = unit,
        )
    }
    val motion = MaterialTheme.motionScheme
    val curves = remember(motion) { RushWordCurves(motion.fastSpatialSpec(), motion.fastEffectsSpec()) }
    val current = remember(rows, ctx.clock) { derivedStateOf { rushCurrentRow(rows, ctx.clock.ms) } }
    // 起始位置直接放在当前行附近，第一帧不会从头滑过来；这里读时钟不能被组合记下（否则每帧重组）
    val list = remember(rows) {
        LazyListState(firstVisibleItemIndex = Snapshot.withoutReadObservation { rushCurrentRow(rows, ctx.clock.ms) })
    }
    val manual = remember(rows) { mutableStateOf(false) }
    RushAutoScroll(list, current, manual, reduceMotion)

    val padding = remember { RushAnchorPadding() }
    val spacing = with(LocalDensity.current) { (fontSp * RUSH_LINE_HEIGHT_RATIO / 2f).sp.toDp() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val arrangement = when (look.align) {
        RushAlign.Start -> Arrangement.Start
        RushAlign.Center -> Arrangement.Center
        RushAlign.End -> Arrangement.End
    }
    val origin = when (look.align) {
        RushAlign.Start -> TransformOrigin(if (rtl) 1f else 0f, 0.5f)
        RushAlign.Center -> TransformOrigin(0.5f, 0.5f)
        RushAlign.End -> TransformOrigin(if (rtl) 0f else 1f, 0.5f)
    }
    val blur = look.blur && android.os.Build.VERSION.SDK_INT >= 31
    val shape = MaterialTheme.shapes.medium
    val haptics = LocalHapticFeedback.current
    LazyColumn(
        state = list,
        modifier = modifier
            .fillMaxSize()
            .rushSafeArea(display, padding)
            .rushEdgeFade(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(spacing),
        userScrollEnabled = display.touch,
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, row -> if (row.line == null) 0 else 1 }) { index, row ->
            // 每行只订阅自己离当前行的远近（夹到模糊不再变的 4 行）：换行时只有附近几行重组
            val relation by remember(index, current) {
                derivedStateOf { (index - current.value).coerceIn(-RUSH_RELATION_REACH, RUSH_RELATION_REACH) }
            }
            val phase = when {
                relation < 0 -> RushPhase.Past
                relation == 0 -> RushPhase.Current
                else -> RushPhase.Future
            }
            val spatial: AnimationSpec<Float> = if (reduceMotion) snap() else motion.fastSpatialSpec()
            val effects: AnimationSpec<Float> = if (reduceMotion) snap() else motion.fastEffectsSpec()
            val scale = animateFloatAsState(if (relation == 0) 1f else RUSH_OTHER_SCALE, spatial, label = "rushScale")
            val blurDp = animateFloatAsState(rushBlurDp(relation, blur, manual.value), effects, label = "rushBlur")
            val underAlpha = animateFloatAsState(if (relation == 0) RUSH_CURRENT_UNDER_ALPHA else RUSH_OTHER_UNDER_ALPHA, effects, label = "rushUnder")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
                Box(
                    Modifier
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            transformOrigin = origin
                            renderEffect = if (blur) RushBlur.of(blurDp.value.dp.toPx() * unit) else null
                        }
                        .clip(shape)
                        .then(
                            if (display.touch) {
                                Modifier.clickable(onClickLabel = "从这一句播放") {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    manual.value = false
                                    ctx.seek(row.startMs)
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    val line = row.line
                    if (line == null) {
                        RushDots(row.startMs, row.nextMs, phase, live = !row.placeholder && !reduceMotion, ctx.clock, content, unit)
                    } else {
                        val tokens = remember(line, wordTimed) { rushTokens(line, wordTimed) }
                        RushLine(
                            tokens = tokens,
                            subtitle = line.subtitle(ctx.subline),
                            phase = phase,
                            clock = ctx.clock,
                            styles = styles,
                            curves = curves,
                            content = content,
                            underAlpha = underAlpha,
                            glow = blur,
                            reduceMotion = reduceMotion,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 自动跟随：当前行或视口高度变了就把当前行中心对到 1/3 处。用户拖动（或滚轮、惯性）时 [manual] 置上，
 * 停手 3 秒后清掉、再滚回来；距离太远、刚换歌词、减少动画时直接跳。
 */
@Composable
private fun RushAutoScroll(list: LazyListState, current: State<Int>, manual: MutableState<Boolean>, reduceMotion: Boolean) {
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var auto by remember(list) { mutableStateOf(false) }
    val userScrolling = dragged || (list.isScrollInProgress && !auto)
    LaunchedEffect(userScrolling) {
        if (userScrolling) {
            manual.value = true
        } else if (manual.value) {
            delay(RUSH_RESUME_MS)
            manual.value = false
        }
    }
    val memo = remember(list) { RushScrollMemo() }
    val paused = manual.value
    LaunchedEffect(list, paused, reduceMotion) {
        if (paused) return@LaunchedEffect
        snapshotFlow { current.value to list.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .collectLatest { (target, height) ->
                if (height <= 0) return@collectLatest
                val snapTo = rushShouldSnap(target, list.firstVisibleItemIndex, memo.placed, reduceMotion, memo.height != 0 && memo.height != height)
                memo.height = height
                auto = true
                try {
                    list.rushScrollTo(target, snapTo, spec)
                } finally {
                    auto = false
                }
                memo.placed = true
            }
    }
}

private class RushScrollMemo {
    var placed = false
    var height = 0
}

/** 把第 [index] 行的中心移到 1/3 处；[snap] 时直接定位。 */
private suspend fun LazyListState.rushScrollTo(index: Int, snap: Boolean, spec: AnimationSpec<Float>) {
    if (snap) {
        // 直接定位先跳一次：强制重新测量，布局信息才是这一份歌词的
        scrollToItem(index)
        anchorError(index)?.let { scrollBy(it) }
        return
    }
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) animateScrollToItem(index)
    anchorError(index)?.let { if (abs(it) >= 0.5f) animateScrollBy(it, spec) }
}

private fun LazyListState.anchorError(index: Int): Float? {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    return lyricAnchorError(item.offset, item.size, info.viewportStartOffset, info.viewportSize.height, RUSH_ANCHOR)
}

/**
 * 上下留白按视口高度分：上 1/3、下 2/3。[height] 由 [rushSafeArea] 在测量 LazyColumn 之前写入，
 * 不必是快照状态：高度变了 LazyColumn 本来就要重新测量。
 */
@Stable
private class RushAnchorPadding : PaddingValues {
    var height: Dp = 0.dp

    override fun calculateTopPadding(): Dp = height * RUSH_ANCHOR
    override fun calculateBottomPadding(): Dp = height * (1f - RUSH_ANCHOR)
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
}

/** 列表放进安全区：安全区在测量阶段才知道，这里只在布局阶段读；宽度最多 720dp，靠起始边。 */
private fun Modifier.rushSafeArea(display: StageDisplay, padding: RushAnchorPadding): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val rect = display.safeRect(Size(width.toFloat(), height.toFloat()))
    val listWidth = min(rect.width, RUSH_MAX_WIDTH.toPx()).roundToInt().coerceAtLeast(0)
    val listHeight = rect.height.roundToInt().coerceAtLeast(0)
    padding.height = listHeight.toDp()
    val placeable = measurable.measure(Constraints.fixed(listWidth, listHeight))
    layout(width, height) { placeable.placeRelative(rect.left.roundToInt(), rect.top.roundToInt()) }
}

/** 上下两端渐隐（上 44dp、下 60dp，矮的时候按高度收），离屏合成一次再用 DstIn 擦。 */
private fun Modifier.rushEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val h = size.height
        val top = if (h > 0f) min(RUSH_FADE_TOP.toPx(), h * 0.12f) / h else 0f
        val bottom = if (h > 0f) min(RUSH_FADE_BOTTOM.toPx(), h * 0.16f) / h else 0f
        val mask = Brush.verticalGradient(0f to Color.Transparent, top to Color.Black, 1f - bottom to Color.Black, 1f to Color.Transparent)
        onDrawWithContent {
            drawContent()
            if (h > 0f) drawRect(mask, blendMode = BlendMode.DstIn)
        }
    }

/** 字号：宿主基准 × 0.93（手机 30sp → 28sp，Rush 的默认字号）。 */
internal const val RUSH_TEXT_RATIO = 0.93f
/** 行高 32 / 字号 28（Rush 默认）。 */
internal const val RUSH_LINE_HEIGHT_RATIO = 32f / 28f
internal const val RUSH_ANCHOR = 1f / 3f
internal const val RUSH_INTRO_MIN_MS = 1_000L
internal const val RUSH_INTERLUDE_MIN_MS = 5_000L
internal const val RUSH_SNAP_ROWS = 10
internal const val RUSH_RESUME_MS = 3_000L
internal const val RUSH_BLUR_STEP_DP = 3f
internal const val RUSH_BLUR_MAX_DP = 10f
private const val RUSH_RELATION_REACH = 4
private const val RUSH_OTHER_SCALE = 0.8f
private const val RUSH_CURRENT_UNDER_ALPHA = 0.5f
private const val RUSH_OTHER_UNDER_ALPHA = 0.2f
private val RUSH_MAX_WIDTH = 720.dp
private val RUSH_FADE_TOP = 44.dp
private val RUSH_FADE_BOTTOM = 60.dp
