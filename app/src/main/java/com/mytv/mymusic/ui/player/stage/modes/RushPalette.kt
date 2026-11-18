/*
 * Copyright (C) 2026  Shubham Gorai
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 2026-11-09 myMusic 改写：取自 Rush 7.2.1 的 Util.kt（getCardColors / getWaveColors / getHypnoticColors）
// 与 ui/Util.kt（generateGradientColors）。kmpalette 取色换成舞台的封面量化色（鲜艳 / 柔和按 HCT 算），
// 自定义取色器换成“播放页”配色；颜色过渡挪到 RushVisualizer 里，这里只剩纯函数。
// 2026-11-18 myMusic 改动：加回“自定义”配色，底色、字色取设置里的两个颜色（取色面板是 myMusic 自己画的，不用 Rush 的取色库）。
package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.materialkolor.ktx.blend
import com.materialkolor.ktx.darken
import com.materialkolor.ktx.lighten
import com.mytv.mymusic.ui.player.stage.StageColors

/** 一套 Rush 配色：整页底色、歌词色、律动背景的波浪色，和流光着色器的 6 个色。 */
@Immutable
internal class RushPalette(
    val background: Color,
    val content: Color,
    val wave: Color,
    val hypnotic: List<Color>,
)

/**
 * [choice] 对应的配色。鲜艳 / 柔和用舞台算好的封面底色（文字色已按 4.5:1 取黑白）；播放页直接用 PlayerTheme 的深色
 * surface 和 onSurface；自定义用设置里的 [customBackground]、[customText]，不跟封面变（切歌不过渡）。
 * 波浪色和流光的两端照 Rush 原式：两种封面色互相掺 25% 再提亮 / 压暗（自定义是底色掺 25% 字色），流光是底色往两边各拉开 2:1。
 */
internal fun rushPalette(
    colors: StageColors,
    choice: String,
    customBackground: Color = RUSH_CUSTOM_BACKGROUND,
    customText: Color = RUSH_CUSTOM_TEXT,
): RushPalette {
    val background: Color
    val content: Color
    val wave: Color
    when (choice) {
        RUSH_COLORS_CUSTOM -> {
            background = customBackground
            content = customText
            wave = customBackground.blend(customText, 0.25f)
        }
        RUSH_COLORS_MUTED -> {
            background = colors.coverMuted
            content = colors.onCoverMuted
            wave = colors.coverMuted.blend(colors.coverVibrant, 0.25f).darken(1.5f)
        }
        RUSH_COLORS_PLAYER -> {
            background = colors.surface
            content = colors.text
            wave = colors.surface.blend(colors.text, 0.25f)
        }
        else -> {
            background = colors.coverVibrant
            content = colors.onCoverVibrant
            wave = colors.coverVibrant.blend(colors.coverMuted, 0.25f).lighten(1.5f)
        }
    }
    return RushPalette(
        background = background,
        content = content,
        wave = wave,
        hypnotic = rushGradientColors(background.lighten(2f), background.darken(2f), RUSH_HYPNOTIC_COLORS),
    )
}

/** 两色之间按 sRGB 分量线性插 [steps] 个色（含两端），和 Rush 的 generateGradientColors 一样。 */
internal fun rushGradientColors(from: Color, to: Color, steps: Int): List<Color> = List(steps) { i ->
    val t = if (steps > 1) i / (steps - 1f) else 0f
    Color(
        red = (from.red * (1 - t) + to.red * t).coerceIn(0f, 1f),
        green = (from.green * (1 - t) + to.green * t).coerceIn(0f, 1f),
        blue = (from.blue * (1 - t) + to.blue * t).coerceIn(0f, 1f),
        alpha = (from.alpha * (1 - t) + to.alpha * t).coerceIn(0f, 1f),
    )
}

internal const val RUSH_COLORS_VIBRANT = "vibrant"
internal const val RUSH_COLORS_MUTED = "muted"
internal const val RUSH_COLORS_PLAYER = "player"
internal const val RUSH_COLORS_CUSTOM = "custom"

/** 自定义配色两个颜色的默认值（设置表的默认值必须固定；切到自定义时面板会先按封面种一个初值）。 */
internal const val RUSH_CUSTOM_BACKGROUND_HEX = "#2C5F5A"
internal const val RUSH_CUSTOM_TEXT_HEX = "#FFE9B8"
internal val RUSH_CUSTOM_BACKGROUND = Color(0xFF2C5F5A)
internal val RUSH_CUSTOM_TEXT = Color(0xFFFFE9B8)

/** 流光着色器的颜色数（着色器里的 MAX_COLORS 跟着它）。 */
internal const val RUSH_HYPNOTIC_COLORS = 6
