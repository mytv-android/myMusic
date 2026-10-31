package com.mytv.mymusic.ui.poster

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.adaptive.FoldSplit
import com.mytv.mymusic.ui.adaptive.rememberFoldSplit
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.Posture

/**
 * 墙这一次的版式：格距 [metrics]，以及有分页的折痕时大卡要落进去的那一段 [segment]（墙本地 dp）。
 * [leftPage] 是竖折痕时左页的宽（dp）：左上角的胶囊收在左页里，不压折痕；没有竖折痕时为 null。
 */
@Immutable
internal data class WallSpec(
    val metrics: WallMetrics,
    val segment: WallSegment? = null,
    val leftPage: Float? = null,
    /**
     * 书本 / 桌面姿态下墙还没摆放过，不知道折痕落在哪：只有打开墙的第一帧。这一帧墙先不建，
     * 免得按整屏建好、下一帧又按那一段重建（入场动画重来、大卡从正中跳到右页）。
     */
    val pending: Boolean = false,
)

/**
 * 折痕切出的两段里大卡放哪一段（[split] 是墙本地像素，结果换成 dp）：书本 / 双竖放右页，
 * 桌面 / 双横放上半屏——和播放页歌词在右、平板侧边面板在右一致，上半屏是立着、看的那一面。
 * 那一段放不下手机档的大卡、另一段放得下时换另一段；两段都放不下当没有折痕（照旧居中整屏）。
 */
internal fun cardSegment(split: FoldSplit?, density: Float): WallSegment? {
    if (split == null || density <= 0f) return null
    val first = WallSegment(split.vertical, 0f, split.firstEnd / density)
    val second = WallSegment(split.vertical, split.secondStart / density, split.size / density)
    val preferred = if (split.vertical) second else first
    val other = if (split.vertical) first else second
    val min = WallMetrics.Phone.grownSize
    return when {
        preferred.size >= min -> preferred
        other.size >= min -> other
        else -> null
    }
}

/**
 * 墙的格距和大卡那一段。没有分页的折痕（[split] 为 null：手机、平板、展平的折叠屏）和电视都照旧按整个窗口取格距、不分段。
 * 有折痕时格距按大卡那一段的尺寸选：书本取右页宽 × 窗口高，桌面取窗口宽 × 上半屏高——
 * Pixel Fold 竖握半开的上半屏只有约 404dp 高，整屏算是 ShortTablet，按上半屏就是手机档，大卡放得进去。
 */
internal fun wallSpecFor(isTv: Boolean, widthDp: Int, heightDp: Int, split: FoldSplit?, density: Float): WallSpec {
    val seg = if (isTv) null else cardSegment(split, density)
    if (seg == null) return WallSpec(wallMetricsFor(isTv, widthDp, heightDp))
    val metrics = if (seg.vertical) {
        wallMetricsFor(false, seg.size.toInt(), heightDp)
    } else {
        wallMetricsFor(false, widthDp, seg.size.toInt())
    }
    val leftPage = if (split != null && split.vertical) split.firstEnd / density else null
    return WallSpec(metrics, seg, leftPage)
}

/**
 * 墙的版式（见 [wallSpecFor]），折痕按 [PosterWallState.foldAnchor] 切。只有书本 / 桌面姿态（含双屏）才读锚点，
 * 普通窗口、展平的折叠屏、电视不读，和以前完全一样。PosterWall 和页面各调一次，输入相同，结果一致。
 */
@Composable
internal fun rememberWallSpec(state: PosterWallState): WallSpec {
    val window = LocalAppWindow.current
    val density = LocalDensity.current.density
    val folded = window.posture == Posture.Book || window.posture == Posture.Tabletop
    val split = if (folded) rememberFoldSplit(state.foldAnchor) else null
    val pending = folded && state.foldAnchor.rect == null
    return remember(window.isTv, window.widthDp, window.heightDp, split, density, pending) {
        wallSpecFor(window.isTv, window.widthDp, window.heightDp, split, density).copy(pending = pending)
    }
}

/** 墙这一次用的格距，PosterWall 交给格子里的大卡（GrownPoster 按它取卡片尺寸和字号）；墙外为 null。 */
internal val LocalWallMetrics = staticCompositionLocalOf<WallMetrics?> { null }

/**
 * 把一个按整墙排的浮层收进 [segment] 那一段：竖的从左边让到那一段起点、宽度取那一段，横的同理取高度。
 * 后面接 fillMaxSize / wrapContent 等；[segment] 为 null 时不变。
 */
internal fun Modifier.inWallSegment(segment: WallSegment?): Modifier = when {
    segment == null -> this
    segment.vertical -> this.padding(start = segment.start.dp).width(segment.size.dp)
    else -> this.padding(top = segment.start.dp).height(segment.size.dp)
}
