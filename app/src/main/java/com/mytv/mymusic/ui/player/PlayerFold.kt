package com.mytv.mymusic.ui.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.adaptive.FoldMargin
import com.mytv.mymusic.ui.adaptive.FoldSplit
import com.mytv.mymusic.ui.adaptive.foldSplit
import com.mytv.mymusic.ui.components.Fold
import kotlin.math.roundToInt

/**
 * 播放页里一块区域被折痕切出的两段，坐标是这块区域自己的本地像素（见 [foldSplit]）。
 * [width]、[height] 是这块区域的大小，它铺在安全区（[insets]）里面。
 *
 * 不用 FoldAnchor 去量位置：播放页铺满整个窗口，静止时这块区域的左上角就在安全区的左上角。
 * 平板上打开播放页是从下方滑入，预测性返回时整页缩小下移，onGloballyPositioned 量到的位置都会逐帧变化，
 * 内容就会跟着折痕逐帧重排；按静止的位置算，过渡中整页连同排好的内容一起移动。
 * 没有分页的折痕时直接返回 null，不读安全区：没有折痕的设备上组合和测量都和原来一样。
 */
internal fun playerFoldSplit(
    fold: Fold?,
    insets: WindowInsets,
    density: Density,
    direction: LayoutDirection,
    width: Int,
    height: Int,
    minSegment: Float = 0f,
): FoldSplit? {
    if (fold == null || !fold.separating) return null
    val left = insets.getLeft(density, direction).toFloat()
    val top = insets.getTop(density).toFloat()
    val margin = with(density) { FoldMargin.toPx() }
    return foldSplit(Rect(left, top, left + width, top + height), fold, margin, minSegment)
}

/**
 * 双击收藏的心形放在哪：书本姿态放到左页（封面那一页）正中，桌面姿态放到上半屏正中，不压在折痕上；
 * 其余情况（含没有折痕）照旧在整页正中。折痕坐标是窗口像素，播放页铺满窗口，可以直接用。
 */
internal fun BoxScope.heartBurstPlacement(fold: Fold?, book: Boolean, tabletop: Boolean): Modifier = when {
    fold != null && book -> Modifier
        .align(Alignment.CenterStart)
        .offset { IntOffset((fold.bounds.left / 2f - HeartBurstSize.toPx() / 2f).roundToInt().coerceAtLeast(0), 0) }
    fold != null && tabletop -> Modifier
        .align(Alignment.TopCenter)
        .offset { IntOffset(0, (fold.bounds.top / 2f - HeartBurstSize.toPx() / 2f).roundToInt().coerceAtLeast(0)) }
    else -> Modifier.align(Alignment.Center)
}

/** 和 [HeartBurst] 的边长一致。 */
private val HeartBurstSize = 124.dp
