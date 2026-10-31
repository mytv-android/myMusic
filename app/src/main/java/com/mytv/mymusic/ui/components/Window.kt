package com.mytv.mymusic.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import com.mytv.mymusic.ui.theme.LayoutMode
import com.mytv.mymusic.ui.theme.isTelevision

/** 设备形态。平板按宽度细分导航和播放器形态，电视单独走遥控器交互。 */
enum class DeviceKind { Phone, Tablet, Tv }

/**
 * WindowManager 报来的折痕或铰链（FoldingFeature），见 MainActivity。
 * [bounds] 是窗口坐标（像素），和 Compose 的 positionInWindow 同一套；宽或高为 0 的折痕已经按 1px 算。
 * 只有窗口横跨整个显示区时才有：分屏、多窗口、折叠后的外屏都是 null。
 */
@Immutable
data class Fold(
    val bounds: Rect,
    /** orientation == VERTICAL：折痕竖着，把窗口分成左右两块。 */
    val vertical: Boolean,
    /** state == HALF_OPENED。 */
    val halfOpened: Boolean,
    /** isSeparating：半开的折痕，或任何会挡像素的铰链。 */
    val separating: Boolean,
    /** occlusionType == FULL：双屏的铰链，下面没有像素。 */
    val occluding: Boolean,
)

/**
 * 从折痕推出来的姿态。[Book] 和 [Tabletop] 都要求折痕把窗口分开（[Fold.separating]）：
 * 半开的折叠屏，或者横跨两块屏的双屏（铰链竖着算书本，横着算桌面）。
 */
enum class Posture { Normal, Flat, Book, Tabletop }

/**
 * 断点：<600 手机；600–839 导航栏改为侧栏；840–1199 底部播放条；≥1200 常驻 380dp 播放窗格。
 */
@Immutable
data class AppWindow(val kind: DeviceKind, val widthDp: Int, val heightDp: Int, val fold: Fold? = null) {
    val isPhone get() = kind == DeviceKind.Phone
    val isTablet get() = kind == DeviceKind.Tablet
    val isTv get() = kind == DeviceKind.Tv
    /**
     * 列表 + 详情双栏（设置、同步、详情页左右分栏）。横跨双屏、铰链横着（双横）时也算：
     * 铰链天然把窗口分成上下两块，列表放上屏、详情放下屏（见 FoldRow），不用等宽度够 840。
     */
    val twoPane get() = isTv || widthDp >= 840 || (isTablet && fold != null && fold.occluding && !fold.vertical)
    val showRail get() = !isPhone
    val showNowPlayingPane get() = isTablet && widthDp >= 1200
    val showBottomPlayerBar get() = isTablet && widthDp < 1200
    /** 内容区左右留白：手机 16dp，平板 24dp，电视 48dp（过扫描安全区）。 */
    val gutter: Dp get() = when (kind) {
        DeviceKind.Phone -> 16.dp
        DeviceKind.Tablet -> 24.dp
        DeviceKind.Tv -> 48.dp
    }
    val posture: Posture get() = when {
        fold == null || isTv -> Posture.Normal
        !fold.separating -> Posture.Flat
        fold.vertical -> Posture.Book
        else -> Posture.Tabletop
    }
    /** 矮窗口（折叠屏上下分屏后的小窗等）：手机播放页换紧凑排法。 */
    val short get() = isPhone && heightDp < 480
}

val LocalAppWindow = staticCompositionLocalOf { AppWindow(DeviceKind.Phone, 400, 800) }

/** MainActivity 收集到的折痕，[rememberAppWindow] 读它；没有 Activity 收集（小组件配置页、预览、测试）时为 null。 */
val LocalWindowFold = compositionLocalOf<Fold?> { null }

/**
 * 取第一条折痕。bounds 是窗口坐标（像素）；宽或高为 0 的折痕（不挡像素的折叠屏）按 1px 算，
 * 免得后面拿它切分时左右两块挨在一起分不出来。
 */
fun WindowLayoutInfo.toFold(): Fold? {
    val feature = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() ?: return null
    val b = feature.bounds
    val vertical = feature.orientation == FoldingFeature.Orientation.VERTICAL
    val right = if (vertical) maxOf(b.right, b.left + 1) else b.right
    val bottom = if (vertical) b.bottom else maxOf(b.bottom, b.top + 1)
    return Fold(
        bounds = Rect(b.left.toFloat(), b.top.toFloat(), right.toFloat(), bottom.toFloat()),
        vertical = vertical,
        halfOpened = feature.state == FoldingFeature.State.HALF_OPENED,
        separating = feature.isSeparating,
        occluding = feature.occlusionType == FoldingFeature.OcclusionType.FULL,
    )
}

/** 全局 Snackbar：页面里的撤销提示都走这一个，避免叠在一起，位置也会让开底部浮层。 */
val LocalAppSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("LocalAppSnackbar 未提供") }

/** 浮在内容上的迷你播放器和导航栏占用的高度，列表把它加到 contentPadding 底部。 */
val LocalOverlayPadding = compositionLocalOf { PaddingValues(0.dp) }

/** 正在显示的深色沉浸页面数（详情页的海报视图）：大于 0 时系统栏图标变浅，见 MainActivity。 */
val LocalDarkBarsRequests = staticCompositionLocalOf<MutableIntState> { mutableIntStateOf(0) }

/** [active] 时请求系统栏用浅色图标（页面本身是深色的），不再需要或离开组合时撤回。 */
@Composable
fun RequestDarkBars(active: Boolean) {
    val requests = LocalDarkBarsRequests.current
    DisposableEffect(requests, active) {
        if (active) requests.intValue++
        onDispose { if (active) requests.intValue-- }
    }
}

/**
 * 整页固定深色的页面（详情页的海报视图）的配色，交给外壳：手机底栏、平板导航栏和底部播放条换成它，
 * 不在深色的墙旁边露一块浅色。最后登记的页面生效；页面离开组合时只撤回自己登记的那份。
 */
@Stable
class PageChromeScheme {
    private var owner: Any? = null
    var scheme by mutableStateOf<ColorScheme?>(null)
        private set

    fun set(owner: Any, scheme: ColorScheme) {
        this.owner = owner
        this.scheme = scheme
    }

    fun clear(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        scheme = null
    }
}

/** 外壳提供（AppNavigation）；没有外壳（预览、测试）时为 null，[ShareChromeScheme] 什么也不做。 */
val LocalPageChromeScheme = staticCompositionLocalOf<PageChromeScheme?> { null }

/** 页面把自己的 [scheme] 交给外壳（见 [PageChromeScheme]），离开组合时撤回。 */
@Composable
fun ShareChromeScheme(scheme: ColorScheme) {
    val holder = LocalPageChromeScheme.current ?: return
    val owner = remember { Any() }
    SideEffect { holder.set(owner, scheme) }
    DisposableEffect(holder) { onDispose { holder.clear(owner) } }
}

@Composable
fun rememberAppWindow(layout: LayoutMode = LayoutMode.Auto): AppWindow {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val tv = remember(context) { context.isTelevision() }
    val width = configuration.screenWidthDp
    val height = configuration.screenHeightDp
    val fold = LocalWindowFold.current
    return remember(layout, tv, width, height, fold) {
        AppWindow(
            kind = when (layout) {
                LayoutMode.Phone -> DeviceKind.Phone
                LayoutMode.Tablet -> DeviceKind.Tablet
                LayoutMode.Tv -> DeviceKind.Tv
                LayoutMode.Auto -> when {
                    tv -> DeviceKind.Tv
                    width >= 600 -> DeviceKind.Tablet
                    else -> DeviceKind.Phone
                }
            },
            widthDp = width,
            heightDp = height,
            fold = fold,
        )
    }
}
