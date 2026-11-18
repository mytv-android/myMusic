package com.mytv.mymusic.ui.player.share

import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.R

/**
 * 分享卡片的字体：包里的 Roboto Flex 按字重 / 字宽 / 斜度三个轴取实例。
 *
 * - 按三元组缓存：Compose 按 Font 对象认缓存，每次重组新建 FontFamily 会反复建 Typeface（乱排卡一句十来个词）。
 * - Android 8.0 以下不支持可变字体的轴：只声明常规字重，让系统合成粗体（同 Theme.kt），卡片照常出图。
 * - 字体只含拉丁字符。中文、日文、韩文由系统回落字体（通常是 Noto Sans CJK）画，轴只作用在我们这份 Typeface 上，
 *   回落字体不吃字宽和斜度：竖排卡的中文不斜、乱排的中文词不变宽窄、粗糙卡的中文是正常宽度。字重按最近的系统字重挑，
 *   请求 ≥ 600 而系统只有常规时会合成粗体，所以中文实际只有「常规」「粗」两档。
 */
internal object ShareFonts {
    private val cache = HashMap<Triple<Int, Int, Int>, FontFamily>()

    fun flex(weight: Int, width: Int = 100, slant: Int = 0): FontFamily = synchronized(cache) {
        cache.getOrPut(Triple(weight, width, slant)) {
            if (Build.VERSION.SDK_INT >= 26) {
                FontFamily(
                    Font(
                        R.font.roboto_flex,
                        FontWeight(weight),
                        variationSettings = FontVariation.Settings(
                            FontVariation.weight(weight),
                            FontVariation.width(width.toFloat()),
                            FontVariation.slant(slant.toFloat()),
                        ),
                    ),
                )
            } else {
                FontFamily(Font(R.font.roboto_flex, FontWeight.Normal))
            }
        }
    }

    /**
     * 卡片文字样式：字号、行高按 px 给（卡片在 1px = 1dp = 1sp 的局部密度里排版），字距 0，不继承主题的字体设置。
     * 行高摆法用 M3 字体样式自带的居中、不裁（Rush 的文字都是从 M3 样式 copy 出来的）。
     */
    fun style(weight: Int, size: Int, line: Int, color: Color, width: Int = 100, slant: Int = 0): TextStyle = TextStyle(
        fontFamily = flex(weight, width, slant),
        fontWeight = FontWeight(weight),
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = 0.sp,
        color = color,
        fontSynthesis = if (Build.VERSION.SDK_INT >= 26) FontSynthesis.None else FontSynthesis.Weight,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}
