package com.mytv.mymusic.ui.player.stage

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 宿主副行：当前句的翻译或罗马音（按“歌词下面那一行”的设置），居中排在安全区底部往上 14dp 处，再让出 [lift]。
 * 换句时交叉淡入淡出；只随换句重组，位置在布局阶段按安全区算。
 */
@Composable
internal fun HostSubtitle(ctx: LyricStageContext, lift: Dp) {
    val text = ctx.currentLine?.subtitle(ctx.subline).orEmpty()
    val insets = ctx.display.insets
    val size = ctx.display.textSp * SUBTITLE_SCALE
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontSize = size.sp,
        lineHeight = (size * 1.35f).sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Crossfade(text, Modifier.fillMaxSize(), MaterialTheme.motionScheme.defaultEffectsSpec(), label = "hostSubtitle") { shown ->
        if (shown.isBlank()) return@Crossfade
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            Text(
                shown,
                style = style,
                color = color,
                maxLines = 3,
                modifier = Modifier.layout { measurable, constraints ->
                    val side = 16.dp.roundToPx()
                    val left = insets.start.roundToInt() + side
                    val available = (constraints.maxWidth - left - insets.end.roundToInt() - side).coerceAtLeast(0)
                    val placeable = measurable.measure(Constraints(maxWidth = available))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val bottom = constraints.maxHeight - insets.bottom - 14.dp.toPx() - lift.toPx()
                        placeable.place(left + (available - placeable.width) / 2, (bottom - placeable.height).roundToInt())
                    }
                },
            )
        }
    }
}

/**
 * 频谱图层（spectrum-visualizer 的柱状图，按说明自己画）：安全区左下角一张 184×60dp 的小卡，
 * 48 段柱子按低、中、高三组上色，没有信号时只画 2dp 的底座；下面一行读数是功率（dB）和峰值频率，每 120ms 换一次。
 * 只在绘制阶段读时钟和律动，不重组。
 */
@Composable
internal fun SpectrumLayer(audio: StageAudio, clock: StageClock, insets: StageInsets) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = SpectrumLabel) }
    val readout = remember { SpectrumReadout() }
    Spacer(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .drawWithCache {
                val cardW = 184.dp.toPx()
                val cardH = 60.dp.toPx()
                val pad = 10.dp.toPx()
                val radius = CornerRadius(12.dp.toPx())
                val gap = 1.dp.toPx()
                onDrawBehind {
                    val ms = clock.ms
                    val left = insets.start
                    val top = size.height - insets.bottom - cardH - 8.dp.toPx()
                    drawRoundRect(SpectrumCard, Offset(left, top), Size(cardW, cardH), radius)
                    val barsTop = top + pad
                    val barsH = cardH - pad * 2 - 14.dp.toPx()
                    val barW = (cardW - pad * 2) / audio.bands.size
                    val live = audio.live
                    for (i in audio.bands.indices) {
                        val x = left + pad + i * barW
                        val v = if (live) audio.bands[i] else 0f
                        val h = if (live) (v * barsH).coerceAtLeast(2f) else 2f
                        drawRect(barColor(i, live), Offset(x, barsTop + barsH - h), Size((barW - gap).coerceAtLeast(1f), h))
                    }
                    readout.update(ms, audio)
                    val y = barsTop + barsH + 3.dp.toPx()
                    drawText(measurer, readout.power, Offset(left + pad, y), labelStyle)
                    val peak = measurer.measure(readout.peak, labelStyle)
                    drawText(peak, topLeft = Offset(left + cardW - pad - peak.size.width, y))
                }
            },
    )
}

/** 读数每 120ms 换一次，和原组件一样；数字跳得太快看不清。 */
private class SpectrumReadout {
    var power = "— dB"
        private set
    var peak = "峰值 — Hz"
        private set
    private var at = Long.MIN_VALUE

    fun update(ms: Long, audio: StageAudio) {
        if (at != Long.MIN_VALUE && kotlin.math.abs(ms - at) < 120) return
        at = ms
        if (!audio.live) {
            power = "— dB"
            peak = "峰值 — Hz"
            return
        }
        power = "${(-60 + 60 * audio.power.coerceIn(0f, 1f)).roundToInt()} dB"
        val hz = audio.peakHz
        peak = "峰值 " + when {
            hz <= 0f -> "—"
            hz >= 1000f -> String.format(java.util.Locale.ROOT, "%.1fk", hz / 1000f)
            else -> hz.roundToInt().toString()
        } + " Hz"
    }
}

private fun barColor(i: Int, on: Boolean): Color = when {
    i < 12 -> if (on) SpectrumLow else SpectrumLowOff
    i < 30 -> if (on) SpectrumMid else SpectrumMidOff
    else -> if (on) SpectrumHigh else SpectrumHighOff
}

private const val SUBTITLE_SCALE = 0.55f
private val SpectrumCard = Color(0x47000000)
private val SpectrumLabel = Color(0x9EE6EADF)
// 柱色和原组件一致
private val SpectrumLow = Color(0xFFD8B975)
private val SpectrumMid = Color(0xFFC3CF87)
private val SpectrumHigh = Color(0xFF95B1A1)
private val SpectrumLowOff = Color(0xFF3D3A2F)
private val SpectrumMidOff = Color(0xFF373D2D)
private val SpectrumHighOff = Color(0xFF2C3833)
