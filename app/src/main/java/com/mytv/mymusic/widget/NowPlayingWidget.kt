package com.mytv.mymusic.widget

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
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
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import com.mytv.mymusic.R
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.playback.SleepTimerState

/** 正在播放的七档：1×1、2×1、3×1 起、2×2、2×3、4×2、4×3 起。 */
enum class NowBp { Mini, Strip, Bar, Square, Tower, Card, Stage }

private val NowBreakpoints = listOf(
    Breakpoint(NowBp.Mini, 0.dp, 0.dp),
    Breakpoint(NowBp.Strip, Cells.W2, 0.dp),
    Breakpoint(NowBp.Bar, Cells.W3, 0.dp),
    Breakpoint(NowBp.Square, Cells.W2, Cells.H2),
    Breakpoint(NowBp.Tower, Cells.W2, Cells.H3),
    Breakpoint(NowBp.Card, Cells.W4, Cells.H2),
    Breakpoint(NowBp.Stage, Cells.W4, Cells.H3),
)

/** 一次组合用到的正在播放状态。[interactive] 为 false 时（选择器预览）不挂点击。 */
data class NowView(
    val playback: PlaybackSnapshot?,
    val shape: CoverShapeChoice,
    val favorite: Boolean,
    val sleep: SleepTimerState,
    val now: Long,
    val interactive: Boolean = true,
) {
    val playing: Boolean get() = playback?.playing == true

    /** 从磁盘接上、还没装进播放器的队列：显示“继续播放”。 */
    val resume: Boolean get() = playback != null && !playback.live && !playback.playing

    val position: Long get() = playback?.positionAt(now) ?: 0L
    val fraction: Float get() = playback?.durationMs?.takeIf { it > 0 }?.let { (position.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val coverShape: WidgetShape get() = if (playing) WidgetShape.Cover(shape) else WidgetShape.Paused

    val meta: String
        get() {
            val p = playback ?: return ""
            if (resume) return "继续播放 · 最近播放"
            return listOf(p.sourceName, p.quality).filter { it.isNotBlank() }.joinToString(" · ")
        }
}

class NowPlayingWidget : MusicWidget(WidgetKind.Now) {
    @Composable
    override fun Content(appWidgetId: Int, palette: WidgetPalette, config: WidgetConfig) {
        val data = WidgetData.get(LocalContext.current)
        val state by data.now.collectAsState()
        val progressTick by data.progressTick.collectAsState()
        val lyricTick by data.lyricTick.collectAsState()
        // 进度和歌词的节拍到了要重组；读一下这两个值就够了
        val now = progressTick.let { lyricTick.let { SystemClock.elapsedRealtime() } }
        val view = NowView(state.playback, state.shape, state.favorite, state.sleep, now)
        val bp = pickBreakpoint(NowBreakpoints, LocalSize.current).key
        // 每个尺寸各组合一份、各自登记会互相覆盖（小尺寸把大尺寸要的进度推送撤掉）：每份都按全部尺寸算
        val shown = widgetSizes().map { pickBreakpoint(NowBreakpoints, it).key }
        SideEffect {
            WidgetUpdater.track(
                appWidgetId, kind,
                progress = view.playing && shown.any { it in setOf(NowBp.Tower, NowBp.Card, NowBp.Stage) },
                lyrics = view.playing && NowBp.Stage in shown,
            )
        }
        NowLayout(view, bp, palette, config.background)
    }

    @Composable
    override fun Preview(palette: WidgetPalette) {
        val view = NowView(WidgetSamples.playback, CoverShapeChoice.Cookie12, favorite = true, sleep = SleepTimerState(), now = SystemClock.elapsedRealtime(), interactive = false)
        NowLayout(view, pickBreakpoint(NowBreakpoints, LocalSize.current).key, palette, WidgetBackground.Solid)
    }
}

@Composable
fun NowLayout(view: NowView, bp: NowBp, palette: WidgetPalette, background: WidgetBackground) {
    val size = LocalSize.current
    Box(modifier = GlanceModifier.fillMaxSize().widgetBackground(palette, if (background == WidgetBackground.Clear) WidgetBackground.Glass else background)) {
        if (view.playback == null) NowEmpty(bp, size.width, size.height, palette, view.interactive) else NowBody(view, bp, size.width, size.height, palette)
    }
}

@Composable
private fun NowEmpty(bp: NowBp, w: Dp, h: Dp, palette: WidgetPalette, interactive: Boolean) {
    val open = if (interactive) launchAppAction() else null
    val title = "还没有播放记录"
    when (bp) {
        NowBp.Card, NowBp.Stage -> Row(
            modifier = GlanceModifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WMarkTile(minOf(92.dp, h - 32.dp, w * 0.28f), palette)
            Spacer(GlanceModifier.width(16.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                WText(title, WType.title, palette.color { onSurface }, weight = FontWeight.Bold)
                Spacer(GlanceModifier.height(3.dp))
                WText("从榜单或搜索里挑一首，这里就会显示正在播放", WType.artist, palette.color { onSurfaceVariant }, maxLines = 2)
                Spacer(GlanceModifier.height(10.dp))
                WPill(R.drawable.ic_w_search, "打开 myMusic", palette, open)
            }
        }
        NowBp.Square, NowBp.Tower -> Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp).onClick(open).describe("还没有播放记录，打开 myMusic")) {
            WMarkTile(64.dp, palette)
            Spacer(GlanceModifier.defaultWeight())
            WText(title, WType.title, palette.color { onSurface }, weight = FontWeight.Bold)
            WText("点这里挑一首歌", WType.artist, palette.color { onSurfaceVariant })
        }
        else -> Row(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp).onClick(open).describe("还没有播放记录，打开 myMusic"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = if (w > 120.dp) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            WMarkTile(minOf(56.dp, h - 24.dp, w - 24.dp), palette)
            if (w > 120.dp) {
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    WText(title, WType.title, palette.color { onSurface }, weight = FontWeight.Bold)
                    WText("点这里挑一首歌", WType.artist, palette.color { onSurfaceVariant })
                }
            }
        }
    }
}

@Composable
private fun NowBody(view: NowView, bp: NowBp, w: Dp, h: Dp, palette: WidgetPalette) {
    val context = LocalContext.current
    val p = view.playback ?: return
    val interactive = view.interactive
    val open = if (interactive) WidgetActions.open(context, WidgetTarget.Player) else null
    val actions = if (interactive) WidgetActions.transportActions(p.live, p.hasPrevious, p.hasNext) else TransportActions(null, null, null)
    val song = p.song
    val coverDescription = "${song.title}，${song.artist}，打开播放页"
    when (bp) {
        NowBp.Mini -> Box(
            modifier = GlanceModifier.fillMaxSize().onClick(actions.play).describe(if (view.playing) "暂停，正在播放 ${song.title}" else "播放 ${song.title}"),
            contentAlignment = Alignment.Center,
        ) {
            // 横屏桌面一格只有 50dp 高，跟着缩
            val box = minOf(68.dp, w - 4.dp, h - 8.dp).coerceAtLeast(36.dp)
            Box(modifier = GlanceModifier.size(box)) {
                WCover(song.cover, box * (60f / 68f), view.coverShape, palette)
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                    WPlayButton(view.playing, box * (26f / 68f), palette, action = null, ring = 2.5.dp, description = "")
                }
            }
        }
        NowBp.Strip -> Row(
            modifier = GlanceModifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val button = minOf(48.dp, h - 24.dp).coerceAtLeast(32.dp)
            WCover(song.cover, minOf(64.dp, h - 24.dp, w - 34.dp - button).coerceAtLeast(32.dp), view.coverShape, palette, GlanceModifier.onClick(open).describe(coverDescription))
            Spacer(GlanceModifier.defaultWeight())
            WPlayButton(view.playing, button, palette, actions.play)
        }
        NowBp.Bar -> {
            // 三格宽从 200dp 起：窄的时候只留播放键，宽到 300dp 再加上一首
            val wide = w >= 300.dp
            val tight = w < 240.dp
            val controls = minOf(44.dp, h - 24.dp).coerceAtLeast(32.dp)
            Row(modifier = GlanceModifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val cs = minOf(if (wide) 64.dp else if (tight) 48.dp else 56.dp, h - 24.dp).coerceAtLeast(32.dp)
                WCover(song.cover, cs, view.coverShape, palette, GlanceModifier.onClick(open).describe(coverDescription))
                Spacer(GlanceModifier.width(if (tight) 10.dp else 12.dp))
                Column(modifier = GlanceModifier.defaultWeight().onClick(open)) {
                    MetaRow(view, palette)
                    WText(song.title, WType.title, palette.color { onSurface }, weight = FontWeight.Bold)
                    WText(song.artist, WType.artist, palette.color { onSurfaceVariant })
                }
                Spacer(GlanceModifier.width(if (tight) 10.dp else 12.dp))
                if (tight) {
                    WPlayButton(view.playing, controls, palette, actions.play)
                } else {
                    WButtonGroup(view.playing, palette, actions, height = controls, prev = wide)
                }
            }
        }
        NowBp.Square -> Box(modifier = GlanceModifier.fillMaxSize()) {
            // 封面在左上，播放键压在封面右下角，歌名歌手贴底；格子窄或矮时封面跟着缩，给两行字让位
            val cs = minOf(112.dp, w - 42.dp, h - 72.dp).coerceAtLeast(56.dp)
            val k = cs.value / 112f
            val pb = minOf(48.dp, cs * 0.43f)
            val ring = 3.dp
            val box = pb + ring * 2
            Box(modifier = GlanceModifier.padding(start = 14.dp, top = 14.dp)) {
                WCover(song.cover, cs, view.coverShape, palette, GlanceModifier.onClick(open).describe(coverDescription))
            }
            Box(modifier = GlanceModifier.padding(start = 14.dp + cs - (4f * k).dp - box / 2, top = 14.dp + cs - (16f * k).dp - box / 2)) {
                WPlayButton(view.playing, pb, palette, actions.play, ring = ring)
            }
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, bottom = 13.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = GlanceModifier.fillMaxWidth().onClick(open)) {
                    WText(song.title, WType.title, palette.color { onSurface }, weight = FontWeight.Bold)
                    WText(if (view.resume) "继续播放 · ${song.artist}" else song.artist, WType.artist, palette.color { onSurfaceVariant })
                }
            }
        }
        NowBp.Tower -> {
            val cw = w - 28.dp
            // 封面以下的文字、进度和按钮组共约 170dp，矮的三格高时封面让出来
            val cs = minOf(116.dp, cw, h - 172.dp).coerceAtLeast(48.dp)
            Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    WCover(song.cover, cs, view.coverShape, palette, GlanceModifier.onClick(open).describe(coverDescription))
                }
                Spacer(GlanceModifier.height(10.dp))
                Column(modifier = GlanceModifier.fillMaxWidth().onClick(open)) {
                    MetaRow(view, palette)
                    WText(song.title, WType.title, palette.color { onSurface }, weight = FontWeight.Bold)
                    WText(song.artist, WType.artist, palette.color { onSurfaceVariant })
                }
                Spacer(GlanceModifier.defaultWeight())
                WWave(cw, view.fraction, view.playing, palette)
                Spacer(GlanceModifier.height(3.dp))
                WTimes(view.position, p.durationMs, view.playing, palette, cw)
                Spacer(GlanceModifier.height(8.dp))
                WButtonGroup(view.playing, palette, actions, height = 40.dp, width = cw, prev = false)
            }
        }
        NowBp.Card -> {
            // 两格高的桌面在 150~230dp 之间：矮于 190dp 时收紧边距和按钮、去掉时间行，按钮组总能完整露出来；
            // 放不下两行歌名时只给一行
            val compact = h < 190.dp
            val pad = if (compact) 12.dp else 16.dp
            val cs = minOf(140.dp, h - pad * 2, w - pad * 2 - 14.dp - 124.dp).coerceAtLeast(64.dp)
            val rw = w - pad * 2 - cs - 14.dp
            val titleLines = if (h >= 205.dp) 2 else 1
            Row(modifier = GlanceModifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                WCover(song.cover, cs, view.coverShape, palette, GlanceModifier.onClick(open).describe(coverDescription))
                Spacer(GlanceModifier.width(14.dp))
                Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WText(view.meta, WType.meta, palette.color { onSurfaceVariant }, GlanceModifier.defaultWeight().onClick(open))
                        TimerChip(view, palette)
                        FavoriteButton(view, palette, if (compact) 32.dp else 40.dp)
                    }
                    Column(modifier = GlanceModifier.fillMaxWidth().onClick(open)) {
                        WText(song.title, WType.title, palette.color { onSurface }, weight = FontWeight.Bold, maxLines = titleLines)
                        WText(song.artist, WType.artist, palette.color { onSurfaceVariant })
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    if (view.resume) {
                        WText("上次听到 ${elapsedText(view.position)}", WType.artist, palette.color { onSurfaceVariant })
                        Spacer(GlanceModifier.height(if (compact) 6.dp else 8.dp))
                    } else {
                        WWave(rw, view.fraction, view.playing, palette)
                        if (!compact) {
                            Spacer(GlanceModifier.height(3.dp))
                            WTimes(view.position, p.durationMs, view.playing, palette, rw)
                        }
                        Spacer(GlanceModifier.height(6.dp))
                    }
                    WButtonGroup(view.playing, palette, actions, height = if (compact) 36.dp else 40.dp, width = rw)
                }
            }
        }
        NowBp.Stage -> {
            val rw = w - 32.dp
            val lyrics = WidgetLyrics.window(p, view.now)
            val lyricsAction = if (interactive) WidgetActions.open(context, WidgetTarget.Lyrics) else null
            Column(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    WCover(song.cover, 64.dp, view.coverShape, palette, GlanceModifier.onClick(open).describe(coverDescription))
                    Spacer(GlanceModifier.width(12.dp))
                    Column(modifier = GlanceModifier.defaultWeight().onClick(open)) {
                        MetaRow(view, palette)
                        WText(song.title, WType.title, palette.color { onSurface }, weight = FontWeight.Bold)
                        WText(song.artist, WType.artist, palette.color { onSurfaceVariant })
                    }
                    FavoriteButton(view, palette)
                }
                Column(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(vertical = 8.dp).onClick(lyricsAction).describe("歌词，打开歌词视图"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (lyrics != null) {
                        WText(lyrics.current, 20.sp, palette.color { primary }, weight = FontWeight.Bold, maxLines = 2)
                        if (lyrics.translation.isNotBlank()) WText(lyrics.translation, 12.5.sp, palette.color { onSurfaceVariant })
                        if (lyrics.next.isNotBlank()) {
                            Spacer(GlanceModifier.height(4.dp))
                            WText(lyrics.next, 14.5.sp, palette.color { onSurfaceVariant })
                        }
                    } else {
                        WText(WidgetLyrics.placeholder(p), 16.sp, palette.color { onSurfaceVariant })
                    }
                }
                WWave(rw, view.fraction, view.playing, palette)
                Spacer(GlanceModifier.height(3.dp))
                WTimes(view.position, p.durationMs, view.playing, palette, rw)
                Spacer(GlanceModifier.height(8.dp))
                WButtonGroup(view.playing, palette, actions, height = 44.dp, width = rw)
            }
        }
    }
}

@Composable
private fun MetaRow(view: NowView, palette: WidgetPalette) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WText(view.meta, WType.meta, palette.color { onSurfaceVariant }, GlanceModifier.defaultWeight())
        TimerChip(view, palette)
    }
}

@Composable
private fun TimerChip(view: NowView, palette: WidgetPalette) {
    if (!view.sleep.active) return
    Spacer(GlanceModifier.width(6.dp))
    if (view.sleep.endOfTrack) {
        Row(
            modifier = GlanceModifier.tinted(R.drawable.widget_r10, palette.color { tertiaryContainer }).padding(start = 5.dp, top = 2.dp, end = 7.dp, bottom = 2.dp)
                .describe("定时关闭，这首放完停止"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WIcon(R.drawable.ic_w_bedtime, 12.dp, palette.color { onTertiaryContainer })
            Spacer(GlanceModifier.width(3.dp))
            WText("本首后", WType.time, palette.color { onTertiaryContainer }, weight = FontWeight.Medium)
        }
    } else {
        WTimerChip(view.sleep.remainingMs(), palette)
    }
}

@Composable
private fun FavoriteButton(view: NowView, palette: WidgetPalette, size: Dp = 40.dp) {
    val action: Action? = if (view.interactive) WidgetActions.favorite else null
    WIconButton(
        if (view.favorite) R.drawable.ic_w_fav else R.drawable.ic_w_fav_outline,
        size = size, iconSize = 20.dp, palette = palette, action = action,
        description = if (view.favorite) "取消收藏" else "收藏",
        tonal = false,
        tint = if (view.favorite) palette.color { primary } else palette.color { onSurfaceVariant },
    )
}
