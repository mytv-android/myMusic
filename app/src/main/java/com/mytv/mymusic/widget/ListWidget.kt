package com.mytv.mymusic.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.mytv.mymusic.R
import com.mytv.mymusic.data.Song

enum class ListBp { Square, Card }

private val ListBreakpoints = listOf(
    Breakpoint(ListBp.Square, 0.dp, 0.dp),
    Breakpoint(ListBp.Card, Cells.W4, 0.dp),
)

private const val MAX_ROWS = 50

/** 歌单组件的一张单：标题、首数、歌曲。 */
data class ListView(val source: WidgetListSource, val name: String, val songs: List<Song>, val count: Int)

/** 我喜欢、最近播放或一张自建歌单；点哪首从哪首开始放，正在播放的那一行标成主色。 */
class ListWidget : MusicWidget(WidgetKind.List) {
    @Composable
    override fun Content(appWidgetId: Int, palette: WidgetPalette, config: WidgetConfig) {
        val data = WidgetData.get(LocalContext.current)
        val state by data.now.collectAsState()
        val view = when (val source = config.list) {
            WidgetListSource.Favorites -> {
                val songs by data.favorites.collectAsState()
                ListView(source, "我喜欢的歌曲", songs, songs.size)
            }
            WidgetListSource.History -> {
                val songs by data.history.collectAsState()
                ListView(source, "最近播放", songs, songs.size)
            }
            is WidgetListSource.Local -> {
                val lists by data.localLists.collectAsState()
                val flow = remember(source.id) { data.localListSongs(source.id) }
                val songs by flow.collectAsState(emptyList())
                val info = lists.find { it.id == source.id }
                ListView(source, info?.name ?: "歌单", songs, info?.count ?: songs.size)
            }
        }
        ListLayout(view, state.playback?.song?.key, palette, config.background, appWidgetId, interactive = true)
    }

    @Composable
    override fun Preview(palette: WidgetPalette) {
        val songs = WidgetSamples.list
        ListLayout(ListView(WidgetListSource.Favorites, "我喜欢的歌曲", songs, 128), songs.first().key, palette, WidgetBackground.Solid, 0, interactive = false)
    }
}

@Composable
fun ListLayout(view: ListView, currentKey: String?, palette: WidgetPalette, background: WidgetBackground, appWidgetId: Int, interactive: Boolean) {
    val context = LocalContext.current
    val wide = pickBreakpoint(ListBreakpoints, LocalSize.current).key == ListBp.Card
    val cs = if (wide) 36.dp else 32.dp
    val openList: Action? = if (!interactive) null else WidgetActions.open(
        context,
        when (val source = view.source) {
            WidgetListSource.Favorites -> WidgetTarget.Favorites
            WidgetListSource.History -> WidgetTarget.History
            is WidgetListSource.Local -> WidgetTarget.LocalPlaylist(source.id)
        },
    )
    fun play(song: Song?, shuffle: Boolean = false): Action? =
        if (interactive) WidgetActions.playList(appWidgetId, chart = false, startKey = song?.key, shuffle = shuffle) else null

    Box(modifier = GlanceModifier.fillMaxSize().widgetBackground(palette, if (background == WidgetBackground.Clear) WidgetBackground.Glass else background)) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 4.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight().onClick(openList).describe("${view.name}，${view.count} 首，打开歌单")) {
                    WText(view.name, if (wide) 16.sp else 14.sp, palette.color { onSurface }, weight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WIcon(if (view.source == WidgetListSource.Favorites) R.drawable.ic_w_fav else R.drawable.ic_w_note, 12.dp, palette.color { onSurfaceVariant })
                        Spacer(GlanceModifier.width(6.dp))
                        WText("${view.count} 首", WType.meta, palette.color { onSurfaceVariant })
                    }
                }
                if (view.songs.isNotEmpty()) {
                    if (wide) {
                        Spacer(GlanceModifier.width(6.dp))
                        WIconButton(R.drawable.ic_w_shuffle, 40.dp, 20.dp, palette, play(null, shuffle = true), "随机播放")
                    }
                    Spacer(GlanceModifier.width(6.dp))
                    WPlayButton(false, if (wide) 40.dp else 36.dp, palette, play(null), description = "播放全部")
                }
            }
            Spacer(GlanceModifier.height(6.dp))
            if (view.songs.isEmpty()) {
                Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight().onClick(openList), verticalAlignment = Alignment.CenterVertically) {
                    WText("还没有歌曲", WType.rowTitle, palette.color { onSurface }, weight = FontWeight.Medium)
                    WText(
                        if (view.source == WidgetListSource.History) "放过的歌会出现在这里" else "在 App 里收藏几首，这里就会列出来",
                        WType.rowArtist, palette.color { onSurfaceVariant }, maxLines = 2,
                    )
                }
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    itemsIndexed(view.songs.take(MAX_ROWS), itemId = { i, _ -> i.toLong() }) { _, song ->
                        val now = song.key == currentKey
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().height(cs + 10.dp).onClick(play(song))
                                .describe("${song.title}，${song.artist}${if (now) "，正在播放" else ""}，播放"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WCover(song.cover, cs, WidgetShape.Rounded(if (wide) 10f else 8f), palette)
                            Spacer(GlanceModifier.width(10.dp))
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                WText(
                                    song.title, WType.rowTitle,
                                    if (now) palette.color { primary } else palette.color { onSurface },
                                    weight = if (now) FontWeight.Bold else FontWeight.Medium,
                                )
                                WText(song.artist, WType.rowArtist, palette.color { onSurfaceVariant })
                            }
                            if (now) WIcon(R.drawable.ic_w_bars, 16.dp, palette.color { primary })
                        }
                    }
                }
            }
        }
    }
}
