package com.mytv.mymusic.widget

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
import com.mytv.mymusic.R
import com.mytv.mymusic.data.CoverShapeChoice

enum class LyricsBp { Line, Poster, Tall }

private val LyricsBreakpoints = listOf(
    Breakpoint(LyricsBp.Line, 0.dp, 0.dp),
    Breakpoint(LyricsBp.Poster, Cells.W3, Cells.H2),
    Breakpoint(LyricsBp.Tall, Cells.W4, Cells.H3),
)

/**
 * 歌词组件：当前一句放大、下一句变淡，有翻译时跟在当前句下面。只按行换（亮屏时推），不做逐字。
 * 底色是 primaryContainer；透明背景时文字固定白色加阴影。
 */
class LyricsWidget : MusicWidget(WidgetKind.Lyrics) {
    @Composable
    override fun Content(appWidgetId: Int, palette: WidgetPalette, config: WidgetConfig) {
        val data = WidgetData.get(LocalContext.current)
        val state by data.now.collectAsState()
        val lyricTick by data.lyricTick.collectAsState()
        val now = lyricTick.let { SystemClock.elapsedRealtime() }
        val playing = state.playback?.playing == true
        SideEffect { WidgetUpdater.track(appWidgetId, kind, progress = false, lyrics = playing) }
        LyricsLayout(state.playback, state.shape, now, palette, config, interactive = true)
    }

    @Composable
    override fun Preview(palette: WidgetPalette) {
        LyricsLayout(WidgetSamples.playback, CoverShapeChoice.Cookie12, SystemClock.elapsedRealtime(), palette, WidgetConfig(), interactive = false)
    }
}

/** 一行歌词的样式：透明背景时白字加阴影，否则是 onPrimaryContainer（淡的那几句 66%）。 */
private class LyricInk(val palette: WidgetPalette, val clear: Boolean) {
    val strong: ColorProvider = if (clear) ColorProvider(Color.White) else palette.color { onPrimaryContainer }
    val dim: ColorProvider = if (clear) ColorProvider(Color.White.copy(alpha = 0.8f)) else palette.color(alpha = 0.66f) { onPrimaryContainer }

    @Composable
    fun Line(text: String, size: TextUnit, bold: Boolean = false, faded: Boolean = true, maxLines: Int = 1, alpha: Float = 1f) {
        if (clear) {
            WShadowText(text, size, bold, maxLines, dim = faded)
        } else {
            val color = when {
                !faded -> strong
                alpha < 1f -> palette.color(alpha = 0.66f * alpha) { onPrimaryContainer }
                else -> dim
            }
            WText(text, size, color, GlanceModifier.fillMaxWidth(), weight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = maxLines)
        }
    }
}

@Composable
fun LyricsLayout(
    playback: PlaybackSnapshot?,
    shape: CoverShapeChoice,
    now: Long,
    palette: WidgetPalette,
    config: WidgetConfig,
    interactive: Boolean,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val bp = pickBreakpoint(LyricsBreakpoints, size).key
    val clear = config.background == WidgetBackground.Clear
    val ink = LyricInk(palette, clear)
    val lyricsAction: Action? = when {
        !interactive -> null
        playback == null -> launchAppAction()
        else -> WidgetActions.open(context, WidgetTarget.Lyrics)
    }
    Box(
        modifier = GlanceModifier.fillMaxSize().widgetBackground(palette, config.background) { primaryContainer }
            .onClick(lyricsAction).describe(if (playback == null) "还没有播放记录，打开 myMusic" else "歌词，打开歌词视图"),
    ) {
        val window = playback?.let { WidgetLyrics.window(it, now) }
        val translation = window?.translation?.takeIf { config.translation }.orEmpty()
        val none: String? = when {
            playback == null -> "还没有播放记录"
            window != null -> null
            else -> WidgetLyrics.placeholder(playback)
        }
        val songLine = playback?.let { "${it.song.title} · ${it.song.artist}" } ?: "点这里挑一首歌"
        when (bp) {
            LyricsBp.Line -> Row(
                modifier = GlanceModifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val cs = minOf(52.dp, size.height - 24.dp).coerceAtLeast(32.dp)
                if (playback != null) {
                    WCover(playback.song.cover, cs, if (playback.playing) WidgetShape.Cover(shape) else WidgetShape.Paused, palette)
                } else {
                    WMarkTile(cs, palette)
                }
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    if (none != null) {
                        ink.Line(none, 16.sp, bold = true, faded = false)
                        Spacer(GlanceModifier.height(2.dp))
                        ink.Line(songLine, 12.5.sp)
                    } else {
                        ink.Line(window!!.current, 17.sp, bold = true, faded = false)
                        Spacer(GlanceModifier.height(2.dp))
                        ink.Line(window.next, 13.sp)
                    }
                }
            }
            LyricsBp.Poster -> {
                val big = if (size.width >= 300.dp) 26f else 22f
                // 矮的两格高放不下两行大字加下一句：当前句只给一行
                val lines = if (size.height >= 185.dp) 2 else 1
                Column(modifier = GlanceModifier.fillMaxSize().padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 16.dp)) {
                    LyricsHead(playback, songLine, ink, palette, interactive)
                    Spacer(GlanceModifier.defaultWeight())
                    if (none != null) {
                        ink.Line(none, (big - 2).sp, bold = true, faded = false, maxLines = lines)
                    } else {
                        ink.Line(window!!.current, big.sp, bold = true, faded = false, maxLines = lines)
                        if (translation.isNotBlank()) {
                            Spacer(GlanceModifier.height(3.dp))
                            ink.Line(translation, 12.5.sp)
                        }
                        if (window.next.isNotBlank()) {
                            Spacer(GlanceModifier.height(8.dp))
                            ink.Line(window.next, 15.sp)
                        }
                    }
                }
            }
            LyricsBp.Tall -> Column(modifier = GlanceModifier.fillMaxSize().padding(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 18.dp)) {
                LyricsHead(playback, songLine, ink, palette, interactive)
                Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    if (none != null) {
                        ink.Line(none, 24.sp, bold = true, faded = false, maxLines = 2)
                    } else {
                        if (window!!.prev.isNotBlank()) ink.Line(window.prev, 15.sp)
                        Spacer(GlanceModifier.height(10.dp))
                        ink.Line(window.current, 28.sp, bold = true, faded = false, maxLines = 2)
                        if (translation.isNotBlank()) {
                            Spacer(GlanceModifier.height(4.dp))
                            ink.Line(translation, 13.sp)
                        }
                        if (window.next.isNotBlank()) {
                            Spacer(GlanceModifier.height(12.dp))
                            ink.Line(window.next, 16.sp)
                        }
                        if (window.next2.isNotBlank()) {
                            Spacer(GlanceModifier.height(6.dp))
                            ink.Line(window.next2, 15.sp, alpha = 0.7f)
                        }
                    }
                }
            }
        }
    }
}

/** 海报和长卷顶上一行：音符、歌名 · 歌手，右边播放 / 暂停。 */
@Composable
private fun LyricsHead(playback: PlaybackSnapshot?, songLine: String, ink: LyricInk, palette: WidgetPalette, interactive: Boolean) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WIcon(R.drawable.ic_w_note, 14.dp, ink.dim)
        Spacer(GlanceModifier.width(6.dp))
        Box(modifier = GlanceModifier.defaultWeight()) {
            if (ink.clear) WShadowText(songLine, WType.meta, bold = false, maxLines = 1, dim = true)
            else WText(songLine, WType.meta, ink.dim)
        }
        if (playback != null) {
            val playing = playback.playing
            WIconButton(
                if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play,
                size = 40.dp, iconSize = 22.dp, palette = palette,
                action = if (interactive) WidgetActions.play else null,
                description = if (playing) "暂停，正在播放" else "播放，已暂停",
                tonal = false, tint = ink.strong,
            )
        }
    }
}
