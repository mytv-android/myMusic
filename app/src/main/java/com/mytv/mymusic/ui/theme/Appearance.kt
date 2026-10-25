package com.mytv.mymusic.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle

/** 品牌松绿，封面取色失败、没有壁纸取色时的兜底种子色。 */
val BrandSeed = Color(0xFF006B5F)

enum class ColorSource(val label: String, val hint: String) {
    Cover("跟随封面", "默认"),
    Wallpaper("系统壁纸", "Android 8.1+"),
    Brand("品牌松绿", "#006B5F"),
    Custom("自选颜色", "色板"),
}

enum class SchemeStyle(val label: String, val palette: PaletteStyle) {
    TonalSpot("柔和", PaletteStyle.TonalSpot),
    Vibrant("鲜明", PaletteStyle.Vibrant),
    Expressive("表现力", PaletteStyle.Expressive),
    Fidelity("保真", PaletteStyle.Fidelity),
}

enum class BlurMode(val label: String) {
    Auto("自动"),
    On("开"),
    Off("关"),
}

/** 布局模式：自动按设备判断，其余同时把屏幕方向锁到对应形态。 */
enum class LayoutMode(val label: String) {
    Auto("自动"),
    Phone("手机竖屏"),
    Tablet("平板横屏"),
    Tv("电视横屏"),
}

/** 外观设置的完整快照，主题层只读这一个对象。 */
@Immutable
data class Appearance(
    val theme: String = "system",
    val layout: LayoutMode = LayoutMode.Auto,
    val colorSource: ColorSource = ColorSource.Cover,
    val customSeed: Int = BrandSeed.toArgb(),
    val browseStyle: SchemeStyle = SchemeStyle.TonalSpot,
    val playerStyle: SchemeStyle = SchemeStyle.Vibrant,
    val contrast: Float = 0f,
    val amoled: Boolean = false,
    val blur: BlurMode = BlurMode.Auto,
    val reduceMotion: Boolean = false,
    val lastSeed: Int = 0,
) {
    fun isDark(system: Boolean): Boolean = when (theme) {
        "dark" -> true
        "light" -> false
        else -> system
    }
}

/** 自选颜色的色板：覆盖色相环，彩度足够让 HCT 生成有区分度的配色。 */
val CustomSeedSwatches = listOf(
    Color(0xFF006B5F), Color(0xFF1F6F78), Color(0xFF3A5BA0), Color(0xFF4B3F9E),
    Color(0xFF7D4BA0), Color(0xFFD9668F), Color(0xFFB8322A), Color(0xFFE8833A),
    Color(0xFFB08B47), Color(0xFF5E7D3A),
)
