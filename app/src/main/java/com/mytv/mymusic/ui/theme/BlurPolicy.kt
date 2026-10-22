package com.mytv.mymusic.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials

/** 实时模糊是否开启。电视和低内存设备在“自动”下关闭，改用不透明底色。 */
@Immutable
data class BlurPolicy(val enabled: Boolean)

val LocalBlurPolicy = staticCompositionLocalOf { BlurPolicy(enabled = false) }

/** 浏览页内容区的模糊来源；迷你播放器、导航栏和顶栏从这里取背景。 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun rememberBlurPolicy(mode: BlurMode): BlurPolicy {
    val context = LocalContext.current
    return remember(mode, context) {
        BlurPolicy(
            enabled = when (mode) {
                BlurMode.On -> true
                BlurMode.Off -> false
                BlurMode.Auto -> Build.VERSION.SDK_INT >= 31 && !context.isTelevision() && !context.isLowRam()
            },
        )
    }
}

/**
 * 有模糊来源且策略允许时用 Haze 磨砂，否则退回半透明到不透明的底色。
 * 每屏不超过 3 个模糊层：迷你播放器、导航栏、顶栏。
 */
@Composable
fun Modifier.frosted(color: Color, shape: Shape = RectangleShape, state: HazeState? = LocalHazeState.current): Modifier {
    val policy = LocalBlurPolicy.current
    return if (state != null && policy.enabled) {
        // 是否模糊由 BlurPolicy 决定（电视、低内存、Android 12 以下已排除），这里强制打开，不再走 Haze 的保守判断
        this.hazeBlur(HazeInput.Sources(state), style = HazeMaterials.regular(color).then { blurEnabled(true) })
    } else {
        this.background(color, shape)
    }
}

fun Context.isTelevision(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature("android.software.leanback")

private fun Context.isLowRam(): Boolean =
    (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
