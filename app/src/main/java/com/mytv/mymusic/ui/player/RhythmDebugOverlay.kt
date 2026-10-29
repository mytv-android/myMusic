package com.mytv.mymusic.ui.player

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.playback.PlaybackClock
import com.mytv.mymusic.playback.RhythmAnalyzer
import kotlinx.coroutines.delay
import java.util.Locale

/** 跟拍调试信息只在可调试的安装包（debug 构建）里能打开。 */
@Composable
internal fun rhythmDebugAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
}

/**
 * 跟拍调试叠层：每 100ms 读一次分析器（听到的位置、分析领先多少、此刻的拍子网格），左边的圆点按拍子包络亮。
 * 对着耳朵调跟拍校准、看网格锁没锁上时用。圆点在绘制阶段读 [BackdropPulse.beat]，不让叠层每帧重组。
 */
@Composable
internal fun RhythmDebugOverlay(pulse: BackdropPulse, offsetMs: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val analyzer = remember(context) { (context.applicationContext as? MyMusicApp)?.rhythmAnalyzer } ?: return
    var text by remember { mutableStateOf("") }
    LaunchedEffect(analyzer, offsetMs) {
        while (true) {
            text = rhythmDebugText(analyzer, offsetMs, System.nanoTime())
            delay(100)
        }
    }
    val dot = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).drawBehind { drawCircle(dot, alpha = (0.15f + 0.85f * pulse.beat).coerceIn(0f, 1f)) })
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

private fun rhythmDebugText(analyzer: RhythmAnalyzer, offsetMs: Int, nowNanos: Long): String {
    val audible = analyzer.clock.audibleUs(nowNanos)
    if (audible == PlaybackClock.UNSET) return "还没出声"
    val t = (audible - offsetMs * 1_000L) / 1e6
    val grid = analyzer.beats.gridAt(t)
    val lead = analyzer.timeline.leadUs(audible) / 1_000
    val bpm = if (grid.periodS > 0.0) String.format(Locale.ROOT, "%.1f BPM", grid.bpm) else "-- BPM"
    return String.format(Locale.ROOT, "%s  置信 %.2f  领先 %d ms  校准 %d ms  %.2f s", bpm, grid.confidence, lead, offsetMs, t)
}
