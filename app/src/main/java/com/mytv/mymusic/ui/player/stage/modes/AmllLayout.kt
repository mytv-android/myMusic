/*
 * AMLL (Apple Music-like Lyrics), https://github.com/amll-dev/applemusic-like-lyrics
 * Copyright (C) the AMLL contributors (amll-dev)
 * Modified for myMusic on 2026-11-28 (Kotlin / Jetpack Compose rewrite); the changes are listed below.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 2026-11-28 myMusic 改写：取自 AMLL core 0.7.2（@4d8e0270）的 lyric-player/base/layout.ts（LayoutCalculator）、
// lyric-player/base/consts.ts（排版原因与策略）和 lyric-player/base/index.ts 的 calcLayout 里错峰延迟、模糊档位、透明度的算法。
// 行高前缀和、间奏三点插在挂的那组后面、滚动边界、视口外剔除（上下各 300 + 0.4 视口高）、错峰（从视口最上面那组起每组晚 50ms，
// 过了焦点组每组再 ÷1.05）、按距离的模糊档位照原样；改动：单位是 dp（原版的 CSS 像素），底栏不搬；模糊档位外面再乘面板的景深强度（0 / 0.5 / 1 / 1.5）。
// 2026-11-28 对照 Apple Music iOS 16.2 录屏改：焦点不再是中心对齐 35%，而是焦点的字顶对齐到调用方给的位置
// （屏高的 24.6%，13 个稳态帧都在 24.5–24.8%，和折几行无关；量的是字的墨迹上沿）；亮着的组不再乘 0.85（已唱的字就是 100%）。
// 错峰和距离模糊在同一段录屏里都量得到（当前句、下一句、下两句依次晚 ~50ms 出发；非当前句亮度一样、越远越糊），保留。
package com.mytv.mymusic.ui.player.stage.modes

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 为什么重排（LayoutReason）以及对应的策略：要不要错峰、要不要重新开始间奏三点、要不要直接摆到位（拖动跟手）。 */
internal enum class AmllLayoutReason(val disableStagger: Boolean, val resetInterlude: Boolean, val snapPosY: Boolean) {
    PlaybackTick(false, false, false),
    ContinuousScroll(true, false, true),
    DiscreteScroll(true, false, false),
    InteractionStart(true, false, false),
    Seek(true, true, false),
    RebuildView(true, true, false),
    Resize(true, false, false),
    ConfigChange(true, false, false),
}

/**
 * 排版计算（LayoutCalculator）：每组的高度做前缀和，焦点（一组或间奏三点）的字顶对齐到视口里的 alignTop 处，
 * 再按滚动量 [AmllLayout.commit] 出每组的目标位置。间奏三点挂在第 anchor 组后面，后面的组整体下移三点的高度。
 */
internal class AmllLayout {
    private var count = 0
    private var prefix = DoubleArray(1)
    private var total = 0.0

    var valid = false
        private set
    private var focalTop = 0.0
    private var alignY = 0.0
    private var interludeHeight = 0.0
    private var interludeAnchor = NO_ANCHOR

    /** [begin] 算出的滚动范围（dp）：往上最多 [minOffset]（≤ 0），往下最多 [maxOffset]（≥ 0）。 */
    var minOffset = 0.0
        private set
    var maxOffset = 0.0
        private set

    /** [commit] 的结果：每组的目标位置（视口坐标，dp）、是不是在视口（含预留）里，三点的位置。 */
    var ys = DoubleArray(0)
        private set
    var inViewport = BooleanArray(0)
        private set
    var hasInterlude = false
        private set
    var interludeY = 0.0
        private set

    fun setHeights(heights: DoubleArray) {
        count = heights.size
        if (prefix.size < count + 1) prefix = DoubleArray(count + 1)
        if (ys.size < count) {
            ys = DoubleArray(count)
            inViewport = BooleanArray(count)
        }
        var sum = 0.0
        prefix[0] = 0.0
        for (i in 0 until count) {
            sum += heights[i]
            prefix[i + 1] = sum
        }
        total = sum
    }

    fun height(index: Int): Double = if (index in 0 until count) prefix[index + 1] - prefix[index] else 0.0

    /**
     * 第一步（beginFrame）：焦点 [focusKind] / [focusIndex]（见 [AmllFocus]）在内容里的位置，算出滚动范围。
     * 焦点的字顶（一组的上边再往下 [lineInset]，三点的上边再往下 [interludeInset]）对齐到 [alignTop]。
     * [interludeAnchor] 为 null 时没有三点占位。
     */
    fun begin(
        containerHeight: Double,
        focusKind: Int,
        focusIndex: Int,
        interludeHeight: Double,
        interludeAnchor: Int?,
        alignTop: Double,
        lineInset: Double = 0.0,
        interludeInset: Double = 0.0,
    ) {
        this.interludeHeight = if (interludeAnchor != null) interludeHeight else 0.0
        this.interludeAnchor = interludeAnchor ?: NO_ANCHOR
        valid = count > 0 && !(focusKind == AMLL_FOCUS_LINE && (focusIndex < 0 || focusIndex >= count)) &&
            !(focusKind == AMLL_FOCUS_INTERLUDE && (focusIndex < -1 || focusIndex >= count - 1))
        if (!valid) {
            focalTop = 0.0
            alignY = 0.0
            minOffset = 0.0
            maxOffset = 0.0
            return
        }
        val inset: Double
        if (focusKind == AMLL_FOCUS_LINE) {
            focalTop = prefix[focusIndex]
            if (this.interludeAnchor != NO_ANCHOR && focusIndex > this.interludeAnchor) focalTop += this.interludeHeight
            inset = lineInset
        } else {
            focalTop = prefix[focusIndex + 1]
            inset = interludeInset
        }
        alignY = alignTop - inset
        minOffset = min(0.0, -focalTop)
        val base = -focalTop + alignY
        var content = total
        if (this.interludeAnchor != NO_ANCHOR) content += this.interludeHeight
        maxOffset = max(0.0, base + content - containerHeight / 2)
    }

    /** 滚动量夹到 [begin] 算出的范围里。 */
    fun clampOffset(offset: Double): Double = min(max(offset, min(minOffset, maxOffset)), max(minOffset, maxOffset))

    /** 第二步（commit）：按夹过的滚动量 [scrollOffset] 出每组的位置。 */
    fun commit(containerHeight: Double, scrollOffset: Double, overscan: Double = AMLL_OVERSCAN) {
        if (!valid) {
            for (i in 0 until count) {
                ys[i] = 0.0
                inViewport[i] = false
            }
            hasInterlude = false
            interludeY = 0.0
            return
        }
        val start = alignY - scrollOffset - focalTop
        val buffer = containerHeight * 0.4
        val top = -overscan - buffer
        val bottom = containerHeight + overscan + buffer
        for (i in 0 until count) {
            var y = start + prefix[i]
            val h = prefix[i + 1] - prefix[i]
            if (interludeAnchor != NO_ANCHOR && i > interludeAnchor) y += interludeHeight
            ys[i] = y
            inViewport[i] = y <= bottom && y + h >= top
        }
        hasInterlude = interludeAnchor != NO_ANCHOR
        interludeY = if (hasInterlude) start + prefix[interludeAnchor + 1] else 0.0
    }

    private companion object {
        const val NO_ANCHOR = Int.MIN_VALUE
    }
}

/**
 * 间奏三点挂在哪组后面（resolveInterludeAnchorIndex）：正在间奏里就挂它的那组；手势挂起时焦点冻在三点上，
 * 就还挂在冻住的那组后面；否则不占位。
 */
internal fun amllInterludeAnchor(interlude: AmllInterlude?, focus: AmllFocus): Int? = when {
    interlude != null -> interlude.anchor
    focus.kind == AMLL_FOCUS_INTERLUDE -> focus.index
    else -> null
}

/**
 * 错峰延迟（秒）：从上往下，每遇到一组底边没滚出视口上方的组，下一组就多晚 [base]（0.05 秒）出发；
 * 过了焦点组 [scrollTo] 之后这个间隔每组再乘 1/1.05。[disabled] 时全是 0（跳转、拖动、换歌）。
 */
internal fun amllStaggerDelays(ys: DoubleArray, heights: DoubleArray, count: Int, scrollTo: Int, disabled: Boolean): DoubleArray {
    val out = DoubleArray(count)
    var delay = 0.0
    var base = if (disabled) 0.0 else AMLL_STAGGER_SECONDS
    for (i in 0 until count) {
        out[i] = delay
        if (ys[i] + heights[i] >= 0.0) {
            delay += base
            if (i >= scrollTo) base *= 1 / 1.05
        }
    }
    return out
}

/**
 * 景深模糊（px，按 CSS 像素也就是 dp）：焦点上方第 n 组 (2 + n)、下方第 n 组（从最靠后的亮着的组 [latest] 数）(1 + n)，
 * 视口不宽于 1024 时乘 0.8，最多 5；视口外、亮着的组、手指拖过之后（直到回到自动对齐）都是 0。最后乘景深强度 [strength]。
 */
internal fun amllBlur(index: Int, focused: Boolean, inViewport: Boolean, scrollTo: Int, latest: Int, narrow: Boolean, touchScrolled: Boolean, strength: Float): Float {
    if (strength <= 0f || !inViewport || touchScrolled || focused) return 0f
    val distance = if (index < scrollTo) abs(scrollTo - index) + 1 else abs(index - latest)
    val level = (1 + distance).toFloat().let { if (narrow) it * 0.8f else it }
    return min(AMLL_MAX_BLUR, level) * strength
}

/** 一组的目标透明度：视口外 0，其它 1（亮着的组不再打折，Apple 已唱的字就是 100%）。 */
internal fun amllOpacity(inViewport: Boolean): Float = if (inViewport) 1f else 0f

/** 焦点的字顶（墨迹上沿）对齐到屏高的 24.6%（iPhone 录屏 13 个稳态帧：24.5–24.8%）。 */
internal const val AMLL_ANCHOR_TOP = 0.246

/** 视口上下额外保留的预渲染距离（dp）。 */
internal const val AMLL_OVERSCAN = 300.0
internal const val AMLL_STAGGER_SECONDS = 0.05
internal const val AMLL_MAX_BLUR = 5f

/** 视口宽度不超过这么多 dp 算窄视口（模糊打八折）。 */
internal const val AMLL_NARROW_DP = 1024f
