package com.mytv.mymusic.ui.player.stage.modes

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.ui.player.stage.LyricStageContext
import com.mytv.mymusic.ui.player.stage.LyricVisualizer
import com.mytv.mymusic.ui.player.stage.ModOrigin
import com.mytv.mymusic.ui.player.stage.StageChar
import com.mytv.mymusic.ui.player.stage.charTimings
import com.mytv.mymusic.ui.player.stage.fitText

/**
 * 虹光（Folia 官方样例 sample-aurora-visualizer 1.0.1，AGPL-3.0）：只显示当前句、居中；
 * 每个字唱到时按起点换色相（起点秒数对 4 取余 × 70°，唱的过程中再转 40°），亮度从 58% 升到 72%，
 * 正在唱的那一下带同色光晕、上抬 2px（按 64px 字号折算），还没唱到的字是 28% 白。
 * 逐字时间沿用 LyricWord；没有逐字的歌把句长均分给每个字。
 *
 * 整句按安全区宽度（左右各留 8%）排一次，取每个字的位置；每个字单独量一次字形，逐帧只换颜色、光晕和上抬。
 */
object AuroraVisualizer : LyricVisualizer {
    override val id = "aurora"
    override val label = "虹光"
    override val origin = ModOrigin("sample-aurora-visualizer", "1.0.1", "Folia", "AGPL-3.0")
    override val order = 10

    @Composable
    override fun Stage(ctx: LyricStageContext, modifier: Modifier) {
        val measurer = rememberTextMeasurer(cacheSize = 96)
        Spacer(
            modifier.drawWithCache {
                val line = ctx.currentLine
                val safe = ctx.display.safeRect(size)
                val fontPx = ctx.display.textSp.sp.toPx()
                val style = TextStyle(
                    fontSize = ctx.display.textSp.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.04.em,
                    lineHeight = 1.35.em,
                )
                val padX = safe.width * 0.08f
                val glyphs = ArrayList<AuroraGlyph>()
                var blockTop = 0f
                if (line != null && line.text.isNotBlank()) {
                    val layout = fitText(measurer, line.text, style, (safe.width - padX * 2).toInt(), maxLines = 3)
                    val fitted = layout.layoutInput.style
                    blockTop = safe.center.y - layout.size.height / 2f
                    // 一行放得下时排版宽度就是字宽（不是最大宽度），按自己的宽度居中
                    val blockLeft = safe.center.x - layout.size.width / 2f
                    var offset = 0
                    for (char in line.charTimings(spread = 1f)) {
                        val at = offset
                        offset += char.text.length
                        if (char.space) continue
                        val box = layout.getBoundingBox(at)
                        val glyph = measurer.measure(char.text, fitted.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Unspecified))
                        glyphs += AuroraGlyph(glyph, Offset(blockLeft + box.left, blockTop + box.top), char)
                    }
                }
                val scale = fontPx / BASE_FONT_PX
                onDrawBehind {
                    if (glyphs.isEmpty()) return@onDrawBehind
                    val ms = ctx.clock.ms
                    val still = ctx.display.reduceMotion
                    for (g in glyphs) {
                        val t = if (still) 1f else g.char.progress(ms)
                        val hue = (((g.char.startMs / 1000f) % 4f) * 70f + t * 40f) % 360f
                        val color = if (t <= 0f) ctx.colors.dim else Color.hsl(hue, 0.9f, (58f + t * 14f) / 100f)
                        val singing = t > 0f && t < 1f
                        val shadow = if (singing) {
                            Shadow(Color.hsl(hue, 0.9f, 0.65f, 0.65f), Offset.Zero, (8f + t * 14f) * scale)
                        } else {
                            // 不能传 null：同一个字形排版上次设过的光晕不会被 null 清掉
                            Shadow.None
                        }
                        val lift = if (singing) -2f * scale else 0f
                        drawText(g.layout, color, topLeft = g.at.copy(y = g.at.y + lift), shadow = shadow)
                    }
                }
            },
        )
    }
}

private class AuroraGlyph(val layout: TextLayoutResult, val at: Offset, val char: StageChar)

/** 原模组的字号（px）：光晕半径、上抬距离都按它折算。 */
private const val BASE_FONT_PX = 64f
