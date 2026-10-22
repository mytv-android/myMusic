package com.mytv.mymusic.ui.theme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import com.mytv.mymusic.R
import androidx.compose.ui.text.font.FontWeight
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.material3.ktx.animateColorScheme
import com.materialkolor.material3.rememberDynamicColorScheme

val LocalAppearance = staticCompositionLocalOf { Appearance() }

/** 当前生效的种子色，播放页用它生成自己的深色 Vibrant 配色。 */
val LocalSeedColor = staticCompositionLocalOf { BrandSeed }

/**
 * Roboto Flex：只保留字重轴、只含拉丁字符的可变字体（约 94KB），中文由系统字体回退显示。
 * Android 8.0 以下不支持可变字体的轴，只声明常规字重，让 Compose 合成粗体，否则所有字重都会显示成常规。
 */
private val RobotoFlex: FontFamily = if (Build.VERSION.SDK_INT >= 26) {
    FontFamily(
        listOf(300, 400, 500, 600, 700, 800, 900).map { weight ->
            Font(
                R.font.roboto_flex,
                FontWeight(weight),
                variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
            )
        },
    )
} else {
    FontFamily(Font(R.font.roboto_flex, FontWeight.Normal))
}

/** M3 基线字号；强调只改字重，不改字号。标签类文字用等宽数字，时间和计数不跳动。 */
val AppTypography = typographyWith(RobotoFlex)

private fun typographyWith(family: FontFamily): Typography {
    val base = Typography()
    return base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = family),
    displayLargeEmphasized = base.displayLargeEmphasized.copy(fontFamily = family),
    displayMedium = base.displayMedium.copy(fontFamily = family),
    displayMediumEmphasized = base.displayMediumEmphasized.copy(fontFamily = family),
    displaySmall = base.displaySmall.copy(fontFamily = family, fontWeight = FontWeight.Bold),
    displaySmallEmphasized = base.displaySmallEmphasized.copy(fontFamily = family),
    headlineLarge = base.headlineLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold),
    headlineLargeEmphasized = base.headlineLargeEmphasized.copy(fontFamily = family),
    headlineMedium = base.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.Bold),
    headlineMediumEmphasized = base.headlineMediumEmphasized.copy(fontFamily = family),
    headlineSmall = base.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.Bold),
    headlineSmallEmphasized = base.headlineSmallEmphasized.copy(fontFamily = family),
    titleLarge = base.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
    titleLargeEmphasized = base.titleLargeEmphasized.copy(fontFamily = family),
    titleMedium = base.titleMedium.copy(fontFamily = family),
    titleMediumEmphasized = base.titleMediumEmphasized.copy(fontFamily = family),
    titleSmall = base.titleSmall.copy(fontFamily = family),
    titleSmallEmphasized = base.titleSmallEmphasized.copy(fontFamily = family),
    bodyLarge = base.bodyLarge.copy(fontFamily = family),
    bodyLargeEmphasized = base.bodyLargeEmphasized.copy(fontFamily = family),
    bodyMedium = base.bodyMedium.copy(fontFamily = family),
    bodyMediumEmphasized = base.bodyMediumEmphasized.copy(fontFamily = family),
    bodySmall = base.bodySmall.copy(fontFamily = family),
    bodySmallEmphasized = base.bodySmallEmphasized.copy(fontFamily = family),
    labelLarge = base.labelLarge.copy(fontFamily = family, fontFeatureSettings = "tnum"),
    labelLargeEmphasized = base.labelLargeEmphasized.copy(fontFamily = family),
    labelMedium = base.labelMedium.copy(fontFamily = family, fontFeatureSettings = "tnum"),
    labelMediumEmphasized = base.labelMediumEmphasized.copy(fontFamily = family),
    labelSmall = base.labelSmall.copy(fontFamily = family, fontFeatureSettings = "tnum"),
    labelSmallEmphasized = base.labelSmallEmphasized.copy(fontFamily = family),
    )
}

private const val COLOR_TRANSITION_MS = 450

@Composable
fun MyMusicTheme(appearance: Appearance, coverSeed: Color?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // 电视固定深色：客厅环境暗，浅色界面刺眼
    val tv = remember(context) { context.isTelevision() }
    val dark = tv || appearance.isDark(isSystemInDarkTheme())
    val seed = when (appearance.colorSource) {
        ColorSource.Cover -> coverSeed ?: appearance.lastSeed.takeIf { it != 0 }?.let(::Color) ?: BrandSeed
        ColorSource.Wallpaper -> remember(context) { context.wallpaperSeed() } ?: BrandSeed
        ColorSource.Brand -> BrandSeed
        ColorSource.Custom -> Color(appearance.customSeed)
    }
    val scheme = rememberDynamicColorScheme(
        seedColor = seed,
        isDark = dark,
        isAmoled = appearance.amoled,
        style = appearance.browseStyle.palette,
        contrastLevel = appearance.contrast.toDouble(),
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
    CompositionLocalProvider(
        LocalAppearance provides appearance,
        LocalSeedColor provides seed,
        LocalBlurPolicy provides rememberBlurPolicy(appearance.blur),
    ) {
        MaterialExpressiveTheme(
            colorScheme = animated(scheme, appearance.reduceMotion),
            motionScheme = if (appearance.reduceMotion) MotionScheme.standard() else MotionScheme.expressive(),
            typography = AppTypography,
        ) { OnSurfaceContent(content) }
    }
}

/** 播放页固定深色，用同一个种子色生成彩度更高的 Vibrant 配色。 */
@Composable
fun PlayerTheme(content: @Composable () -> Unit) {
    val appearance = LocalAppearance.current
    val scheme = rememberDynamicColorScheme(
        seedColor = LocalSeedColor.current,
        isDark = true,
        isAmoled = false,
        style = appearance.playerStyle.palette,
        contrastLevel = appearance.contrast.toDouble(),
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
    MaterialExpressiveTheme(
        colorScheme = animated(scheme, appearance.reduceMotion),
        motionScheme = if (appearance.reduceMotion) MotionScheme.standard() else MotionScheme.expressive(),
        typography = AppTypography,
    ) { OnSurfaceContent(content) }
}

/** 没有 Surface 包着的文字和图标默认用 onSurface，而不是 Compose 的默认黑色（深色主题下会看不见）。 */
@Composable
private fun OnSurfaceContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
}

@Composable
private fun animated(scheme: ColorScheme, reduceMotion: Boolean): ColorScheme =
    if (reduceMotion) scheme else animateColorScheme(scheme, animationSpec = { tween(COLOR_TRANSITION_MS) })

/** 壁纸取色：Android 12+ 读系统强调色，8.1+ 读壁纸主色。 */
private fun Context.wallpaperSeed(): Color? = runCatching {
    when {
        Build.VERSION.SDK_INT >= 31 -> Color(getColor(android.R.color.system_accent1_500))
        Build.VERSION.SDK_INT >= 27 -> WallpaperManager.getInstance(this)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor
            ?.toArgb()
            ?.let(::Color)
        else -> null
    }
}.getOrNull()
