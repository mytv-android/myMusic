package com.mytv.mymusic.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.mytv.mymusic.ui.components.Fold
import com.mytv.mymusic.ui.components.LocalAppWindow
import kotlin.math.roundToInt

/** 半开的折痕不挡像素，但弯着看不清：两侧各留这么宽不放字。双屏的铰链本身有宽度，不另加。 */
val FoldMargin = 16.dp

/**
 * 一块区域被折痕切成的两段，坐标是这块区域自己的本地像素。
 * [vertical] 为 true 时是左右两段（竖折痕），false 是上下两段（横折痕）。
 * 第一段（左 / 上）是 `[0, firstEnd)`，第二段（右 / 下）是 `[secondStart, size)`，中间是折痕加两侧的安全距离。
 */
@Immutable
data class FoldSplit(
    val vertical: Boolean,
    val firstEnd: Float,
    val secondStart: Float,
    /** 这块区域在切分方向上的总长（宽或高）。 */
    val size: Float,
    /** 折痕是双屏的铰链（下面没有像素），不只是一道弯。 */
    val occluding: Boolean,
) {
    val firstSize get() = firstEnd
    val secondSize get() = size - secondStart
}

/**
 * 照 codelab 的 getFeatureBoundsInWindow：折痕 bounds 先和 [self]（自己在窗口里的矩形，像素）求交，
 * 不相交就当没有，再换成 [self] 里的本地坐标。不挡像素的折痕两侧各让 [margin]，铰链按自己的宽度让。
 *
 * 返回 null 的情况：没有折痕；折痕不分页（展平的折叠屏，[Fold.separating] 为 false）；和 [self] 不相交；
 * 切出来哪一段比 [minSegment] 还短（折痕贴着边，分了也放不下东西）。
 */
fun foldSplit(self: Rect, fold: Fold?, margin: Float, minSegment: Float = 0f): FoldSplit? {
    if (fold == null || !fold.separating) return null
    val hit = self.intersect(fold.bounds)
    if (hit.width <= 0f || hit.height <= 0f) return null
    val pad = if (fold.occluding) 0f else margin
    val split = if (fold.vertical) {
        FoldSplit(true, hit.left - self.left - pad, hit.right - self.left + pad, self.width, fold.occluding)
    } else {
        FoldSplit(false, hit.top - self.top - pad, hit.bottom - self.top + pad, self.height, fold.occluding)
    }
    val min = maxOf(minSegment, 1f)
    if (split.firstSize < min || split.secondSize < min) return null
    return split
}

/**
 * 记下一块区域在窗口里的矩形（像素），交给 [foldSplit]。用 onGloballyPositioned 而不是 onPlaced：
 * 播放页滑进来、父级挪位置时自己不一定重新摆放，但窗口里的位置变了。
 */
@Stable
class FoldAnchor {
    var rect by mutableStateOf<Rect?>(null)
        private set

    fun update(coordinates: LayoutCoordinates) {
        val next = Rect(coordinates.positionInWindow(), coordinates.size.toSize())
        if (next != rect) rect = next
    }

    /** 折痕在这块区域上切出的两段；还没摆放过、或没有可用的折痕时为 null。读它的地方会跟着重组。 */
    fun split(fold: Fold?, margin: Float, minSegment: Float = 0f): FoldSplit? =
        rect?.let { foldSplit(it, fold, margin, minSegment) }
}

fun Modifier.foldAnchor(anchor: FoldAnchor): Modifier = onGloballyPositioned(anchor::update)

@Composable
fun rememberFoldAnchor(): FoldAnchor = remember { FoldAnchor() }

/**
 * 当前窗口的折痕在 [anchor] 上切出的两段，安全距离按 [FoldMargin]。读的是 [LocalAppWindow] 里的折痕，
 * 所以在 Dialog 里（另一个窗口，坐标对不上）不要用。
 */
@Composable
fun rememberFoldSplit(anchor: FoldAnchor, minSegment: Dp = 0.dp): FoldSplit? {
    val fold = LocalAppWindow.current.fold
    val density = LocalDensity.current
    return anchor.split(fold, with(density) { FoldMargin.toPx() }, with(density) { minSegment.toPx() })
}

/**
 * [FoldRow] 里的一栏是怎么排的：[stacked] 是双横时左栏放上屏、右栏放下屏，[first] 是左栏（上屏）。
 * 只有横跨双屏时才会叠起来，平常、书本姿态都是左右两栏。
 */
@Immutable
data class FoldPane(val stacked: Boolean, val first: Boolean)

/** [FoldRow] 外面为 null。 */
val LocalFoldPane = compositionLocalOf<FoldPane?> { null }

/**
 * 叠起来时上下留白要改：顶栏、状态栏在上屏，底部浮层（播放条、导航栏）在下屏，
 * 所以下屏那栏顶部、上屏那栏底部不用再让，只留 [gap]。不叠的时候原样返回。
 */
fun FoldPane?.top(value: Dp, gap: Dp = 16.dp): Dp = if (this != null && stacked && !first) gap else value

fun FoldPane?.bottom(value: Dp, gap: Dp = 16.dp): Dp = if (this != null && stacked && first) gap else value

/**
 * 列表 + 详情两栏。平常就是一行：左栏 [startWidth]，隔 [spacing]，右栏占满剩下的。
 *
 * - 书本姿态、双竖（竖折痕把它切开）：左栏收到折痕左缘再让 [FoldMargin]，右栏从折痕右缘再让 [FoldMargin] 开始。
 * - 双横（横着的铰链挡住像素）：左栏放上屏，右栏放下屏。
 * - 半开的桌面姿态（横折痕不挡像素）不动：列表照常滚过折痕。
 *
 * 切出来哪一栏比 [minPane] 还窄，就当没有折痕。两栏都会被撑满分到的宽高；每一栏能从 [LocalFoldPane] 知道自己怎么排的。
 * 位置靠 onGloballyPositioned 记下来，第一次摆放后才知道，所以有折痕时第一帧还是平常的排法。
 */
@Composable
fun FoldRow(
    startWidth: Dp,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    minPane: Dp = 160.dp,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    val fold = LocalAppWindow.current.fold
    val anchor = rememberFoldAnchor()
    val density = LocalDensity.current
    // 没有折痕时不读 anchor：普通平板不会因为第一次摆放多重组一次
    val split = if (fold == null) {
        null
    } else {
        anchor.split(fold, with(density) { FoldMargin.toPx() }, with(density) { minPane.toPx() })
            ?.takeIf { it.vertical || it.occluding }
    }
    val stacked = split != null && !split.vertical
    Layout(
        content = {
            // 叠起来时：上屏底下是铰链不是导航栏，下屏顶上没有状态栏。里面用 windowInsetsPadding 的地方就不会多让
            CompositionLocalProvider(LocalFoldPane provides FoldPane(stacked, first = true)) {
                Box(
                    if (stacked) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier,
                    propagateMinConstraints = true,
                ) { start() }
            }
            CompositionLocalProvider(LocalFoldPane provides FoldPane(stacked, first = false)) {
                Box(
                    if (stacked) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier,
                    propagateMinConstraints = true,
                ) { end() }
            }
        },
        modifier = modifier.foldAnchor(anchor),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val bounded = constraints.hasBoundedHeight
        if (split != null && !split.vertical && bounded) {
            // 双横：上屏、下屏
            val height = constraints.maxHeight
            val topH = split.firstEnd.roundToInt()
            val bottomY = split.secondStart.roundToInt()
            val top = measurables[0].measure(Constraints.fixed(width, topH))
            val bottom = measurables[1].measure(Constraints.fixed(width, height - bottomY))
            layout(width, height) {
                top.place(0, 0)
                bottom.place(0, bottomY)
            }
        } else {
            val startW: Int
            val endX: Int
            if (split != null && split.vertical) {
                startW = split.firstEnd.roundToInt()
                endX = split.secondStart.roundToInt()
            } else {
                startW = startWidth.roundToPx().coerceAtMost(width)
                endX = (startW + spacing.roundToPx()).coerceAtMost(width)
            }
            val childHeight = { w: Int ->
                Constraints(minWidth = w, maxWidth = w, minHeight = constraints.minHeight, maxHeight = constraints.maxHeight)
            }
            val first = measurables[0].measure(childHeight(startW))
            val second = measurables[1].measure(childHeight(width - endX))
            val height = if (bounded) constraints.maxHeight else maxOf(first.height, second.height)
            layout(width, height) {
                first.place(0, 0)
                second.place(endX, 0)
            }
        }
    }
}

/**
 * 上下两块：横折痕（桌面姿态、双横）把它切开时，[top] 收到折痕上缘再让 [FoldMargin]，[bottom] 从折痕下缘再让开始；
 * 没有横折痕时按 [topFraction] 分。需要有界的高度（一般是撑满的页面）。
 */
@Composable
fun FoldColumn(
    modifier: Modifier = Modifier,
    topFraction: Float = 0.5f,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    val fold = LocalAppWindow.current.fold
    val anchor = rememberFoldAnchor()
    Layout(
        content = {
            Box(propagateMinConstraints = true) { top() }
            Box(propagateMinConstraints = true) { bottom() }
        },
        modifier = modifier.foldAnchor(anchor),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val split = anchor.split(fold, FoldMargin.toPx())?.takeIf { !it.vertical }
        val topH = split?.firstEnd?.roundToInt() ?: (height * topFraction).roundToInt()
        val bottomY = split?.secondStart?.roundToInt() ?: topH
        val first = measurables[0].measure(Constraints.fixed(width, topH.coerceIn(0, height)))
        val second = measurables[1].measure(Constraints.fixed(width, (height - bottomY).coerceIn(0, height)))
        layout(width, height) {
            first.place(0, 0)
            second.place(0, bottomY)
        }
    }
}
