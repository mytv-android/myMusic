package com.mytv.mymusic.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
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
import androidx.glance.text.TextAlign
import com.mytv.mymusic.R
import com.mytv.mymusic.data.Song
import java.util.Calendar

enum class ChartBp { Square, Card }

private val ChartBreakpoints = listOf(
    Breakpoint(ChartBp.Square, 0.dp, 0.dp),
    Breakpoint(ChartBp.Card, Cells.W4, 0.dp),
)

/** 选一张榜单，看前几名和排名变化；点某一行从这一首开始在后台放整张榜，点标题打开榜单页。 */
class ChartWidget : MusicWidget(WidgetKind.Charts) {
    @Composable
    override fun Content(appWidgetId: Int, palette: WidgetPalette, config: WidgetConfig) {
        val context = LocalContext.current
        ChartStore.version.collectAsState().value
        val providers by WidgetData.get(context).providers.collectAsState()
        val target = ChartStore.target(context, config)
        val snapshot = target?.let { ChartStore.read(context, it.source, it.id) }
        val sourceName = snapshot?.sourceName?.ifBlank { null } ?: providers.find { it.id == target?.source }?.name.orEmpty()
        ChartLayout(target, snapshot, sourceName, palette, config.background, appWidgetId, interactive = true)
    }

    @Composable
    override fun Preview(palette: WidgetPalette) {
        val sample = WidgetSamples.chart
        ChartLayout(ChartTarget(sample.source, sample.id, sample.name), sample, sample.sourceName, palette, WidgetBackground.Solid, 0, interactive = false)
    }
}

@Composable
fun ChartLayout(
    target: ChartTarget?,
    snapshot: ChartSnapshot?,
    sourceName: String,
    palette: WidgetPalette,
    background: WidgetBackground,
    appWidgetId: Int,
    interactive: Boolean,
) {
    val context = LocalContext.current
    val bp = pickBreakpoint(ChartBreakpoints, LocalSize.current).key
    val songs = snapshot?.songs.orEmpty()
    val name = snapshot?.name?.ifBlank { null } ?: target?.name?.ifBlank { null } ?: "榜单"
    val openChart: Action? = when {
        !interactive -> null
        target == null -> WidgetActions.open(context, WidgetTarget.Charts)
        else -> WidgetActions.open(context, WidgetTarget.Chart(target.source, target.id, name))
    }
    val refresh: Action? = if (interactive) WidgetActions.refreshCharts else null
    fun playRow(song: Song?): Action? = if (interactive) WidgetActions.playList(appWidgetId, chart = true, startKey = song?.key) else null

    Box(modifier = GlanceModifier.fillMaxSize().widgetBackground(palette, if (background == WidgetBackground.Clear) WidgetBackground.Glass else background)) {
        if (songs.isEmpty()) {
            // 还没取到（刚添加、没网）：点一下重试
            val failed = snapshot != null && snapshot.offline
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(14.dp).onClick(refresh).describe(if (failed) "暂时连不上，点击重试" else "正在获取榜单"),
            ) {
                WText(name, 16.sp, palette.color { onSurface }, weight = FontWeight.Bold)
                Spacer(GlanceModifier.defaultWeight())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (failed) {
                        WIcon(R.drawable.ic_w_cloud_off, 16.dp, palette.color { onSurfaceVariant })
                        Spacer(GlanceModifier.width(6.dp))
                    }
                    WText(if (failed) "暂时连不上 · 点击重试" else "正在获取榜单…", WType.artist, palette.color { onSurfaceVariant })
                }
            }
            return@Box
        }
        when (bp) {
            ChartBp.Square -> Column(modifier = GlanceModifier.fillMaxSize().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 10.dp)) {
                Column(modifier = GlanceModifier.fillMaxWidth().onClick(openChart).describe("$name，打开榜单")) {
                    WText(name, WType.title, palette.color { onSurface }, weight = FontWeight.Bold)
                    ChartSubtitle(snapshot, sourceName, palette)
                }
                Spacer(GlanceModifier.height(10.dp))
                val first = songs.first()
                Row(
                    modifier = GlanceModifier.fillMaxWidth().onClick(playRow(first)).describe("第 1 名，${first.title}，${first.artist}，播放"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WCover(first.cover, 60.dp, WidgetShape.Arch, palette)
                    Spacer(GlanceModifier.width(10.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        WText("1", 20.sp, palette.color { primary }, weight = FontWeight.Bold)
                        WText(first.title, WType.rowTitle, palette.color { onSurface }, weight = FontWeight.Bold)
                        WText(first.artist, WType.rowArtist, palette.color { onSurfaceVariant })
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                songs.drop(1).take(2).forEachIndexed { i, song ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().height(22.dp).onClick(playRow(song)).describe("第 ${i + 2} 名，${song.title}，播放"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WText("${i + 2}", 13.sp, palette.color { primary }, GlanceModifier.width(16.dp), weight = FontWeight.Bold)
                        Spacer(GlanceModifier.width(6.dp))
                        WText(song.title, 12.5.sp, palette.color { onSurface }, GlanceModifier.defaultWeight(), weight = FontWeight.Medium)
                        DeltaBadge(RankDelta.of(song), palette)
                    }
                }
            }
            ChartBp.Card -> Column(modifier = GlanceModifier.fillMaxSize().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 4.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = GlanceModifier.defaultWeight().onClick(openChart).describe("$name，打开榜单")) {
                        WText(name, 16.sp, palette.color { onSurface }, weight = FontWeight.Bold)
                        ChartSubtitle(snapshot, sourceName, palette)
                    }
                    Spacer(GlanceModifier.width(8.dp))
                    WPlayButton(false, 40.dp, palette, playRow(null), description = "播放整张$name")
                }
                Spacer(GlanceModifier.height(6.dp))
                LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    itemsIndexed(songs, itemId = { i, _ -> i.toLong() }) { i, song ->
                        val n = i + 1
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().height(44.dp).onClick(playRow(song)).describe("第 $n 名，${song.title}，${song.artist}，播放"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WText(
                                "$n", if (n <= 3) 17.sp else 15.sp,
                                if (n <= 3) palette.color { primary } else palette.color { onSurfaceVariant },
                                GlanceModifier.width(22.dp), weight = FontWeight.Bold, align = TextAlign.Center,
                            )
                            Spacer(GlanceModifier.width(10.dp))
                            WCover(song.cover, 36.dp, if (n == 1) WidgetShape.Arch else WidgetShape.Rounded(10f), palette)
                            Spacer(GlanceModifier.width(10.dp))
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                WText(song.title, WType.rowTitle, palette.color { onSurface }, weight = FontWeight.Medium)
                                WText(song.artist, WType.rowArtist, palette.color { onSurfaceVariant })
                            }
                            DeltaBadge(RankDelta.of(song), palette)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartSubtitle(snapshot: ChartSnapshot?, sourceName: String, palette: WidgetPalette) {
    if (snapshot != null && snapshot.offline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WIcon(R.drawable.ic_w_cloud_off, 13.dp, palette.color { onSurfaceVariant })
            Spacer(GlanceModifier.width(6.dp))
            WText("离线 · ${updatedLabel(snapshot.updatedAt)}", WType.meta, palette.color { onSurfaceVariant })
        }
    } else {
        val updated = snapshot?.updatedAt?.takeIf { it > 0 }?.let(::updatedLabel).orEmpty()
        WText(listOf(sourceName, updated).filter { it.isNotBlank() }.joinToString(" · "), WType.meta, palette.color { onSurfaceVariant })
    }
}

/** “今天更新”“3 小时前更新”“昨天更新”“5 天前更新”。 */
fun updatedLabel(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return "还没更新"
    val hours = (now - at) / 3_600_000
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val sameDay = today.get(Calendar.YEAR) == then.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return when {
        hours < 1 && sameDay -> "刚刚更新"
        sameDay -> "$hours 小时前更新"
        hours < 48 -> "昨天更新"
        else -> "${hours / 24} 天前更新"
    }
}

/** 排名变化：升（主色箭头）、降、持平（—）、新上榜（tertiaryContainer 的“新”标）。没有数据就不显示。 */
@Composable
private fun DeltaBadge(delta: RankDelta?, palette: WidgetPalette) {
    when (delta) {
        null -> Unit
        RankDelta.New -> Box(
            modifier = GlanceModifier.tinted(R.drawable.widget_r8, palette.color { tertiaryContainer }).padding(horizontal = 6.dp, vertical = 1.dp)
                .describe("新上榜"),
        ) {
            WText("新", WType.time, palette.color { onTertiaryContainer }, weight = FontWeight.Medium)
        }
        RankDelta.Same -> WText("—", WType.time, palette.color { onSurfaceVariant }, GlanceModifier.width(30.dp).describe("排名不变"), align = TextAlign.End)
        is RankDelta.Up -> Row(modifier = GlanceModifier.width(30.dp).describe("上升 ${delta.by} 名"), horizontalAlignment = Alignment.End, verticalAlignment = Alignment.CenterVertically) {
            WIcon(R.drawable.ic_w_up, 16.dp, palette.color { primary })
            WText("${delta.by}", WType.time, palette.color { primary }, weight = FontWeight.Medium)
        }
        is RankDelta.Down -> Row(modifier = GlanceModifier.width(30.dp).describe("下降 ${delta.by} 名"), horizontalAlignment = Alignment.End, verticalAlignment = Alignment.CenterVertically) {
            WIcon(R.drawable.ic_w_down, 16.dp, palette.color { onSurfaceVariant })
            WText("${delta.by}", WType.time, palette.color { onSurfaceVariant }, weight = FontWeight.Medium)
        }
    }
}
