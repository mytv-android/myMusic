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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.R
import com.mytv.mymusic.data.ListeningMode

enum class ModeBp { Strip, Square }

private val ModeBreakpoints = listOf(
    Breakpoint(ModeBp.Strip, 0.dp, 0.dp),
    Breakpoint(ModeBp.Square, 0.dp, Cells.H2),
)

/** 听歌模式组件的三种状态：空闲、漫游中、要登录。 */
enum class ModeState { Idle, Roam, Login }

data class ModeView(
    val mode: ListeningMode?,
    val providerName: String,
    val state: ModeState,
    val playing: Boolean,
    val nowPlaying: String,
    val covers: List<String>,
)

/** 绑定一个平台的一个模式，点播放在后台开始漫游；漫游中显示当前歌曲和下一首按钮。未登录时换成“去登录”。 */
class ModeWidget : MusicWidget(WidgetKind.Mode) {
    @Composable
    override fun Content(appWidgetId: Int, palette: WidgetPalette, config: WidgetConfig) {
        val context = LocalContext.current
        val app = context.applicationContext as MyMusicApp
        val data = WidgetData.get(context)
        val state by data.now.collectAsState()
        val settings by data.settings.collectAsState()
        val providers by data.providers.collectAsState()
        ModeStore.version.collectAsState().value
        val cookies = settings?.cookies.orEmpty()
        val resolved = ModeStore.resolve(app, config, cookies, providers)
        val view = resolved?.let { (provider, mode) ->
            val playback = state.playback
            val roaming = playback != null && playback.feedModeId == mode.id
            ModeView(
                mode = mode,
                providerName = providers.find { it.id == provider }?.name ?: provider,
                state = when {
                    roaming -> ModeState.Roam
                    ModeStore.needsLogin(mode, cookies[provider].orEmpty()) -> ModeState.Login
                    else -> ModeState.Idle
                },
                playing = roaming && playback?.playing == true,
                nowPlaying = playback?.takeIf { roaming }?.let { "${it.song.title} · ${it.song.artist}" }.orEmpty(),
                covers = ModeStore.covers(context, mode.id),
            )
        }
        ModeLayout(view, palette, config.background, appWidgetId, interactive = true)
    }

    @Composable
    override fun Preview(palette: WidgetPalette) {
        val view = ModeView(WidgetSamples.mode, "网易云", ModeState.Idle, playing = false, nowPlaying = "", covers = emptyList())
        ModeLayout(view, palette, WidgetBackground.Solid, appWidgetId = 0, interactive = false)
    }
}

@Composable
fun ModeLayout(view: ModeView?, palette: WidgetPalette, background: WidgetBackground, appWidgetId: Int, interactive: Boolean) {
    val context = LocalContext.current
    val bp = pickBreakpoint(ModeBreakpoints, LocalSize.current).key
    val openMode: Action? = if (interactive) WidgetActions.open(context, WidgetTarget.Modes) else null
    val login: Action? = if (interactive) WidgetActions.open(context, WidgetTarget.Login) else null
    val play: Action? = when {
        !interactive -> null
        view?.state == ModeState.Roam -> WidgetActions.play
        else -> WidgetActions.playMode(appWidgetId)
    }
    Box(modifier = GlanceModifier.fillMaxSize().widgetBackground(palette, if (background == WidgetBackground.Clear) WidgetBackground.Glass else background)) {
        val mode = view?.mode
        if (view == null || mode == null) {
            // 模式列表还没取到（或没有支持听歌模式的平台）：点开听歌模式页自己挑
            Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp).onClick(openMode).describe("听歌模式，打开听歌模式页"), verticalAlignment = Alignment.CenterVertically) {
                WText("听歌模式", 16.sp, palette.color { onSurface }, weight = FontWeight.Bold)
                WText("点这里挑一个模式", WType.meta, palette.color { onSurfaceVariant })
            }
            return@Box
        }
        val headText = "${view.providerName} · ${mode.group.ifBlank { "听歌模式" }}"
        when (bp) {
            ModeBp.Strip -> Row(
                modifier = GlanceModifier.fillMaxSize().padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight().onClick(openMode).describe("${mode.name}，打开听歌模式页")) {
                    WText(mode.name, 16.sp, palette.color { onSurface }, weight = FontWeight.Bold)
                    when (view.state) {
                        ModeState.Login -> WText("需要登录", WType.meta, palette.color { onSurfaceVariant })
                        ModeState.Roam -> RoamingLabel(palette)
                        ModeState.Idle -> WText(mode.group, WType.meta, palette.color { onSurfaceVariant })
                    }
                }
                Spacer(GlanceModifier.width(10.dp))
                val button = minOf(48.dp, LocalSize.current.height - 24.dp).coerceAtLeast(32.dp)
                if (view.state == ModeState.Login) {
                    WIconButton(R.drawable.ic_w_login, button, 22.dp, palette, login, "去登录")
                } else {
                    WPlayButton(view.playing, button, palette, play, description = if (view.playing) "暂停漫游" else "开始漫游 ${mode.name}")
                }
            }
            ModeBp.Square -> Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
                // 矮的两格高（不到 180dp）放不下顶上一行和两行说明：顶行收掉、说明只留一行，底部按钮总能露出来
                val short = LocalSize.current.height < 180.dp
                if (!short) {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = GlanceModifier.size(32.dp).tinted(R.drawable.widget_oval, palette.color { tertiaryContainer }), contentAlignment = Alignment.Center) {
                            WIcon(R.drawable.ic_w_headphones, 18.dp, palette.color { onTertiaryContainer })
                        }
                        Spacer(GlanceModifier.width(8.dp))
                        Box(modifier = GlanceModifier.defaultWeight()) {
                            if (view.state == ModeState.Roam) RoamingLabel(palette) else WText(headText, WType.meta, palette.color { onSurfaceVariant })
                        }
                    }
                    Spacer(GlanceModifier.height(12.dp))
                }
                Column(modifier = GlanceModifier.fillMaxWidth().onClick(openMode).describe("${mode.name}，打开听歌模式页")) {
                    WText(mode.name, 21.sp, palette.color { onSurface }, weight = FontWeight.Bold)
                    Spacer(GlanceModifier.height(2.dp))
                    val sub = when (view.state) {
                        ModeState.Roam -> view.nowPlaying
                        ModeState.Login -> "登录${view.providerName}后才能漫游"
                        ModeState.Idle -> mode.subtitle
                    }
                    if (short && view.state == ModeState.Roam) RoamingLabel(palette)
                    else if (sub.isNotBlank()) WText(sub, WType.artist, palette.color { onSurfaceVariant }, maxLines = if (short) 1 else 2)
                }
                Spacer(GlanceModifier.defaultWeight())
                when (view.state) {
                    ModeState.Login -> WPill(R.drawable.ic_w_login, "去登录", palette, login, GlanceModifier.fillMaxWidth())
                    ModeState.Roam -> Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(GlanceModifier.defaultWeight())
                        WIconButton(R.drawable.ic_w_next, 44.dp, 22.dp, palette, if (interactive) WidgetActions.transport("next") else null, "下一首")
                        Spacer(GlanceModifier.width(8.dp))
                        WPlayButton(view.playing, 48.dp, palette, play)
                    }
                    ModeState.Idle -> Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CoverStack(view.covers.ifEmpty { listOf(mode.cover) }.filter { it.isNotBlank() }.take(2), palette)
                        Spacer(GlanceModifier.defaultWeight())
                        WPlayButton(false, 48.dp, palette, play, description = "开始漫游 ${mode.name}")
                    }
                }
            }
        }
    }
}

@Composable
private fun RoamingLabel(palette: WidgetPalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WIcon(R.drawable.ic_w_bars, 14.dp, palette.color { primary })
        Spacer(GlanceModifier.width(4.dp))
        WText("漫游中", WType.meta, palette.color { primary })
    }
}

/** 空闲时左下角的封面叠：最近在这个模式里放过的两张，后一张压住前一张 14dp，带一圈底色描边。 */
@Composable
private fun CoverStack(covers: List<String>, palette: WidgetPalette) {
    if (covers.isEmpty()) return
    val ring = 2.5.dp
    Box(modifier = GlanceModifier.width((40 + 26 * (covers.size - 1)).dp + ring * 2).height(40.dp + ring * 2)) {
        covers.forEachIndexed { i, url ->
            Box(modifier = GlanceModifier.padding(start = (26 * i).dp)) {
                Box(
                    modifier = GlanceModifier.size(40.dp + ring * 2).tinted(R.drawable.widget_r16, palette.color { surface }),
                    contentAlignment = Alignment.Center,
                ) {
                    WCover(url, 40.dp, WidgetShape.Rounded(12f), palette)
                }
            }
        }
    }
}
