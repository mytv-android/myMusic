package com.mytv.mymusic.ui.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.DialogWindowProvider
import com.mytv.mymusic.ui.components.Fold
import com.mytv.mymusic.ui.components.LocalAppWindow
import kotlin.math.roundToInt

/*
 * 弹窗（Dialog）是另一个窗口，坐标和宿主窗口不是一套，[FoldRow] 那种 positionInWindow 的算法在里面对不上。
 * 这里统一换成屏幕坐标：折痕 bounds 加上宿主窗口在屏幕上的位置，弹窗里的区域用 positionOnScreen。
 */

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** 宿主 Activity 的窗口（装着 [Fold] 坐标系的那个）的根视图；弹窗里的 context 也能找到它。 */
private fun Context.hostDecor(): View? = activity()?.window?.decorView

/** 折痕换成屏幕坐标。 */
private fun Fold.onScreen(host: View?): Fold {
    if (host == null) return this
    val at = IntArray(2)
    host.getLocationOnScreen(at)
    if (at[0] == 0 && at[1] == 0) return this
    return copy(bounds = bounds.translate(Offset(at[0].toFloat(), at[1].toFloat())))
}

/** 记下弹窗里一块区域在屏幕上的矩形，交给 [rememberDialogFoldSplit]。 */
@Stable
class DialogFoldAnchor {
    var rect by mutableStateOf<Rect?>(null)
        private set

    fun update(coordinates: LayoutCoordinates) {
        val next = Rect(coordinates.positionOnScreen(), coordinates.size.toSize())
        if (next != rect) rect = next
    }
}

fun Modifier.dialogFoldAnchor(anchor: DialogFoldAnchor): Modifier = onGloballyPositioned(anchor::update)

/** 宿主窗口的折痕在弹窗里 [anchor] 那块区域上切出的两段（本地像素）；规则同 [foldSplit]。 */
@Composable
fun rememberDialogFoldSplit(anchor: DialogFoldAnchor): FoldSplit? {
    val fold = LocalAppWindow.current.fold ?: return null
    val rect = anchor.rect ?: return null
    val host = LocalContext.current.hostDecor()
    val margin = with(LocalDensity.current) { FoldMargin.toPx() }
    return foldSplit(rect, fold.onScreen(host), margin)
}

/** 弹窗收窄到右页时，离折痕和屏幕边各留的距离。 */
private val DialogPageGutter = 24.dp

/**
 * 居中弹窗避开折痕：书本 / 双竖时挪到右页正中，桌面 / 双横时挪到上半屏正中；比那一页宽就收窄到那一页（两侧留白）。
 * 在 Dialog（或 AlertDialog 某个槽位）的内容里调用：要拿到弹窗自己的窗口。没有分页的折痕时恢复居中。
 *
 * 弹窗窗口默认居中（Gravity.CENTER），WindowManager 的 x / y 就是相对正中的偏移。
 */
@Composable
fun DialogAvoidsFold() {
    val fold = LocalAppWindow.current.fold?.takeIf { it.separating }
    val view = LocalView.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window ?: return
    val host = LocalContext.current.hostDecor() ?: return
    val margin = with(LocalDensity.current) { FoldMargin.toPx() }
    val gutter = with(LocalDensity.current) { DialogPageGutter.toPx() }
    val root = remember(dialogWindow) { dialogWindow.decorView }
    // 收窄前的窗口宽度（一般是 WRAP_CONTENT），折痕消失（展平、折起）时还原
    val original = remember(dialogWindow) { intArrayOf(dialogWindow.attributes.width) }
    DisposableEffect(fold, dialogWindow, host) {
        // 收窄过的宽度；弹窗自己管窗口大小的（usePlatformDefaultWidth = false，每次布局都按内容重设）
        // 会把它改回去，那种就不再收窄，免得两边来回改个不停
        var forced = -1
        var selfSized = false
        fun place() {
            val attrs = dialogWindow.attributes
            var x = 0
            var y = 0
            var width = original[0]
            if (forced > 0 && root.width != forced) selfSized = true
            if (fold != null && host.width > 0 && host.height > 0) {
                val pad = if (fold.occluding) 0f else margin
                if (fold.vertical) {
                    // 右页：折痕右缘（加安全距离）到宿主窗口右边；收窄时两侧再各留 [DialogPageGutter]，不贴屏幕边
                    val left = fold.bounds.right + pad
                    val pageWidth = host.width - left
                    if (pageWidth > 0f) {
                        x = (left + pageWidth / 2f - host.width / 2f).roundToInt()
                        val fit = pageWidth - 2 * gutter
                        if (!selfSized && fit > 0f && (forced > 0 || root.width > fit)) {
                            width = fit.roundToInt()
                            forced = width
                        }
                    }
                } else {
                    // 上半屏：宿主窗口顶到折痕上缘（减安全距离）。比上半屏高时贴着状态栏下面放，尽量少压折痕
                    val bottom = fold.bounds.top - pad
                    if (bottom > 0f) {
                        val center = if (root.height > 0 && root.height > bottom) {
                            val top = androidx.core.view.ViewCompat.getRootWindowInsets(host)
                                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: 0
                            top + root.height / 2f
                        } else {
                            bottom / 2f
                        }
                        y = (center - host.height / 2f).roundToInt()
                    }
                }
            }
            if (selfSized) width = attrs.width
            if (attrs.x == x && attrs.y == y && attrs.width == width && attrs.gravity == Gravity.CENTER) return
            attrs.gravity = Gravity.CENTER
            attrs.x = x
            attrs.y = y
            attrs.width = width
            dialogWindow.attributes = attrs
        }
        // 第一次进来时弹窗还没量过：等它布局完（以及之后内容变高变宽）再挪一次
        val listener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) place()
        }
        root.addOnLayoutChangeListener(listener)
        place()
        onDispose { root.removeOnLayoutChangeListener(listener) }
    }
}
