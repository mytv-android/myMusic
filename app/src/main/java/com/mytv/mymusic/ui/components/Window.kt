package com.mytv.mymusic.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.theme.isTelevision

/** 设备形态。平板按宽度细分导航和播放器形态，电视单独走遥控器交互。 */
enum class DeviceKind { Phone, Tablet, Tv }

/**
 * 断点：<600 手机；600–839 导航栏改为侧栏；840–1199 底部播放条；≥1200 常驻 380dp 播放窗格。
 */
@Immutable
data class AppWindow(val kind: DeviceKind, val widthDp: Int, val heightDp: Int) {
    val isPhone get() = kind == DeviceKind.Phone
    val isTablet get() = kind == DeviceKind.Tablet
    val isTv get() = kind == DeviceKind.Tv
    /** 列表 + 详情双栏（设置、同步、详情页左右分栏）。 */
    val twoPane get() = isTv || widthDp >= 840
    val showRail get() = !isPhone
    val showNowPlayingPane get() = isTablet && widthDp >= 1200
    val showBottomPlayerBar get() = isTablet && widthDp < 1200
    /** 内容区左右留白：手机 16dp，平板 24dp，电视 48dp（过扫描安全区）。 */
    val gutter: Dp get() = when (kind) {
        DeviceKind.Phone -> 16.dp
        DeviceKind.Tablet -> 24.dp
        DeviceKind.Tv -> 48.dp
    }
}

val LocalAppWindow = staticCompositionLocalOf { AppWindow(DeviceKind.Phone, 400, 800) }

/** 全局 Snackbar：页面里的撤销提示都走这一个，避免叠在一起，位置也会让开底部浮层。 */
val LocalAppSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("LocalAppSnackbar 未提供") }

/** 浮在内容上的迷你播放器和导航栏占用的高度，列表把它加到 contentPadding 底部。 */
val LocalOverlayPadding = compositionLocalOf { PaddingValues(0.dp) }

@Composable
fun rememberAppWindow(): AppWindow {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val tv = remember(context) { context.isTelevision() }
    val width = configuration.screenWidthDp
    val height = configuration.screenHeightDp
    return remember(tv, width, height) {
        AppWindow(
            kind = when {
                tv -> DeviceKind.Tv
                width >= 600 -> DeviceKind.Tablet
                else -> DeviceKind.Phone
            },
            widthDp = width,
            heightDp = height,
        )
    }
}
