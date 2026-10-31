package com.mytv.mymusic.widget

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.glance.unit.ColorProvider
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.material3.dynamicColorScheme
import com.mytv.mymusic.data.AppSettings
import com.mytv.mymusic.ui.theme.BrandSeed
import com.mytv.mymusic.ui.theme.ColorSource
import com.mytv.mymusic.ui.theme.LayoutMode
import com.mytv.mymusic.ui.toAppearance
import com.mytv.mymusic.ui.theme.wallpaperSeed
import androidx.glance.color.ColorProvider as DayNightColorProvider

/** 组件用到的配色角色，取自 App 同一套 MaterialKolor 配色（与原型里的 b / bh / o / ov … 一一对应）。 */
data class WidgetScheme(
    val surface: Int,
    val surfaceHigh: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val outlineVariant: Int,
    /** 透明底的白色图标当封面时垫的底（汽水的场景模式就是这种图）：primary 和 primaryContainer 里较深的那个。 */
    val artTile: Int,
) {
    companion object {
        fun of(scheme: ColorScheme) = WidgetScheme(
            surface = scheme.surfaceContainer.toArgb(),
            surfaceHigh = scheme.surfaceContainerHigh.toArgb(),
            onSurface = scheme.onSurface.toArgb(),
            onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
            primary = scheme.primary.toArgb(),
            onPrimary = scheme.onPrimary.toArgb(),
            primaryContainer = scheme.primaryContainer.toArgb(),
            onPrimaryContainer = scheme.onPrimaryContainer.toArgb(),
            secondaryContainer = scheme.secondaryContainer.toArgb(),
            onSecondaryContainer = scheme.onSecondaryContainer.toArgb(),
            tertiaryContainer = scheme.tertiaryContainer.toArgb(),
            onTertiaryContainer = scheme.onTertiaryContainer.toArgb(),
            outlineVariant = scheme.outlineVariant.toArgb(),
            artTile = minOf(scheme.primary, scheme.primaryContainer, compareBy { it.luminance() }).toArgb(),
        )
    }
}

/** 深浅怎么定：跟 App 的主题设置走。跟随系统时一份浅色一份深色，交给桌面按系统深浅挑。 */
enum class WidgetThemeMode { System, Light, Dark }

/**
 * 一次推送用的配色。[mode] 为跟随系统时颜色是 day / night 一对（Android 12+ 桌面自己切；
 * 12 以下推送时就按当时的深浅定了，[WidgetUpdater] 在系统切换深浅后重推）；固定深浅时只有一份。
 */
data class WidgetPalette(val light: WidgetScheme, val dark: WidgetScheme, val mode: WidgetThemeMode) {
    fun color(alpha: Float = 1f, role: WidgetScheme.() -> Int): ColorProvider = when (mode) {
        WidgetThemeMode.System -> DayNightColorProvider(day = Color(light.role()).copy(alpha = alpha), night = Color(dark.role()).copy(alpha = alpha))
        WidgetThemeMode.Light -> ColorProvider(Color(light.role()).copy(alpha = alpha))
        WidgetThemeMode.Dark -> ColorProvider(Color(dark.role()).copy(alpha = alpha))
    }

    /** 组件底色：实色或 72% 的毛玻璃（桌面壁纸透出来），透明只给歌词组件，由调用方自己处理。 */
    fun background(style: WidgetBackground, role: WidgetScheme.() -> Int = { surface }): ColorProvider =
        color(if (style == WidgetBackground.Glass) GLASS_ALPHA else 1f, role)

    companion object {
        const val GLASS_ALPHA = 0.72f
    }
}

/** 组件底色样式；[Clear] 只有歌词组件可选。 */
enum class WidgetBackground { Solid, Glass, Clear }

object WidgetColors {
    /**
     * 和 App 的 MyMusicTheme 取同一个种子色、配色风格、对比度和纯黑：封面取色用当前这首的种子
     * （还没取到时用上次的），系统壁纸、品牌色、自选色照设置来。从不用系统动态取色。
     */
    fun palette(context: Context, settings: AppSettings, coverSeed: Int): WidgetPalette {
        val appearance = settings.toAppearance()
        val seed = when (appearance.colorSource) {
            ColorSource.Cover -> coverSeed.takeIf { it != 0 }?.let(::Color)
                ?: appearance.lastSeed.takeIf { it != 0 }?.let(::Color)
                ?: BrandSeed
            ColorSource.Wallpaper -> context.wallpaperSeed() ?: BrandSeed
            ColorSource.Brand -> BrandSeed
            ColorSource.Custom -> Color(appearance.customSeed)
        }
        // 电视布局在 App 里固定深色，组件跟着
        val mode = when {
            appearance.layout == LayoutMode.Tv -> WidgetThemeMode.Dark
            appearance.theme == "dark" -> WidgetThemeMode.Dark
            appearance.theme == "light" -> WidgetThemeMode.Light
            else -> WidgetThemeMode.System
        }
        fun scheme(dark: Boolean) = WidgetScheme.of(
            dynamicColorScheme(
                seedColor = seed,
                isDark = dark,
                isAmoled = appearance.amoled,
                style = appearance.browseStyle.palette,
                contrastLevel = appearance.contrast.toDouble(),
                specVersion = ColorSpec.SpecVersion.SPEC_2025,
            ),
        )
        val light = if (mode == WidgetThemeMode.Dark) null else scheme(false)
        val dark = if (mode == WidgetThemeMode.Light) null else scheme(true)
        return WidgetPalette(light = light ?: dark!!, dark = dark ?: light!!, mode = mode)
    }
}
