package com.mytv.mymusic.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.ListeningMode
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.TopList
import com.mytv.mymusic.ui.components.ConnectedToggleGroup
import com.mytv.mymusic.ui.components.IconTone
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.components.PageScaffold
import com.mytv.mymusic.ui.components.SectionLabel
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.components.SwitchRow
import com.mytv.mymusic.ui.components.rememberAppWindow
import com.mytv.mymusic.ui.theme.MyMusicTheme
import kotlinx.coroutines.CancellationException

/**
 * 小组件配置页：添加时可以跳过（12 起系统直接跳过；更早的系统按返回也算用默认值），之后长按组件重新进来。
 * 每次选择立即保存并推给组件，和 App 的设置页一样没有保存按钮；底部“完成”只是关掉页面。
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // 只配置自己的组件：别的应用拿任意 id 拉起这个页面时直接退出
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
        val kind = WidgetKind.entries.find { it.receiver.java.name == info?.provider?.className }
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider?.packageName != packageName || kind == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        // 先给成功：添加时直接返回就按默认值放上桌面
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        WidgetUpdater.ensureStarted(this)

        val model = (application as MyMusicApp).musicViewModel
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsState()
            val dark = state.appearance.isDark(isSystemInDarkTheme())
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            }
            MyMusicTheme(
                appearance = state.appearance,
                coverSeed = state.coverSeed.takeIf { it != 0 }?.let { androidx.compose.ui.graphics.Color(it) },
            ) {
                CompositionLocalProvider(LocalAppWindow provides rememberAppWindow()) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        WidgetConfigScreen(appWidgetId, kind, onDone = ::finish)
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigScreen(appWidgetId: Int, kind: WidgetKind, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MyMusicApp
    var config by remember { mutableStateOf(WidgetPrefs.get(context, appWidgetId)) }
    fun save(next: WidgetConfig) {
        config = next
        WidgetPrefs.put(context, appWidgetId, next)
        WidgetUpdater.request(kind)
    }
    val gutter = LocalAppWindow.current.gutter
    PageScaffold(title = kindName(kind), subtitle = "小组件设置") { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = gutter, end = gutter, top = padding.calculateTopPadding(), bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(SegmentGap),
            ) {
                backgroundSection(kind, config) { save(config.copy(background = it)) }
                when (kind) {
                    WidgetKind.Lyrics -> item("translation") {
                        SwitchRow(
                            title = "显示翻译",
                            summary = "有翻译的歌，翻译显示在当前一句下面",
                            checked = config.translation,
                            onCheckedChange = { save(config.copy(translation = it)) },
                            index = 0,
                            count = 1,
                            icon = Icons.Filled.Lyrics,
                        )
                    }
                    WidgetKind.List -> listSection(app, config) { save(config.copy(list = it)) }
                    WidgetKind.Charts -> item("charts") {
                        ChartPicker(app, config) { target ->
                            save(config.copy(chartSource = target?.source.orEmpty(), chartId = target?.id.orEmpty(), chartName = target?.name.orEmpty()))
                            // 换了榜：没缓存的话马上取一次
                            ChartWidgetWorker.refreshNow(context)
                        }
                    }
                    WidgetKind.Mode -> item("modes") {
                        ModePicker(app, config) { provider, mode -> save(config.copy(modeProvider = provider, mode = mode)) }
                    }
                    else -> Unit
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = gutter, vertical = 16.dp),
            ) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("完成") }
            }
        }
    }
}

private fun kindName(kind: WidgetKind): String = when (kind) {
    WidgetKind.Now -> "正在播放"
    WidgetKind.Lyrics -> "歌词"
    WidgetKind.Mode -> "听歌模式"
    WidgetKind.Charts -> "榜单"
    WidgetKind.List -> "歌单"
    WidgetKind.Tools -> "快捷入口"
}

/** 背景：实色、半透明（72%）；透明只给歌词（白字加阴影）。 */
private fun LazyListScope.backgroundSection(kind: WidgetKind, config: WidgetConfig, onChange: (WidgetBackground) -> Unit) {
    item("bgLabel") { SectionLabel("背景") }
    item("bg") {
        val options = if (kind == WidgetKind.Lyrics) WidgetBackground.entries else listOf(WidgetBackground.Solid, WidgetBackground.Glass)
        val selected = config.background.takeIf { it in options } ?: WidgetBackground.Glass
        ConnectedToggleGroup(
            options = options,
            selected = selected,
            onSelect = onChange,
            label = {
                when (it) {
                    WidgetBackground.Solid -> "实色"
                    WidgetBackground.Glass -> "半透明"
                    WidgetBackground.Clear -> "透明"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item("bgNote") {
        val note = when {
            kind == WidgetKind.Lyrics && config.background == WidgetBackground.Clear -> "透明时文字是白色加阴影，适合深色或照片壁纸。"
            else -> "颜色和深浅跟 App 的外观设置走。"
        }
        ConfigNote(note)
    }
}

/** 歌单：我喜欢、最近播放和自建歌单。 */
private fun LazyListScope.listSection(app: MyMusicApp, config: WidgetConfig, onChange: (WidgetListSource) -> Unit) {
    item("listLabel") { SectionLabel("显示哪一张") }
    item("lists") {
        val lists by WidgetData.get(app).localLists.collectAsState()
        val rows = listOf(
            Triple<WidgetListSource, String, String>(WidgetListSource.Favorites, "我喜欢的歌曲", ""),
            Triple(WidgetListSource.History, "最近播放", ""),
        ) + lists.map { Triple(WidgetListSource.Local(it.id), it.name, "${it.count} 首") }
        Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
            rows.forEachIndexed { i, (source, name, summary) ->
                PickRow(
                    title = name,
                    summary = summary,
                    selected = config.list == source,
                    index = i,
                    count = rows.size,
                    icon = when (source) {
                        WidgetListSource.Favorites -> Icons.Filled.Favorite
                        WidgetListSource.History -> Icons.Filled.History
                        is WidgetListSource.Local -> Icons.AutoMirrored.Filled.QueueMusic
                    },
                    onClick = { onChange(source) },
                )
            }
        }
    }
}

/** 榜单：先选平台，再选榜；“自动”用榜单页当前平台的第一张榜。 */
@Composable
private fun ChartPicker(app: MyMusicApp, config: WidgetConfig, onChange: (ChartTarget?) -> Unit) {
    var providers by remember { mutableStateOf<List<ProviderInfo>?>(null) }
    LaunchedEffect(Unit) { providers = ChartStore.providers(app) }
    var source by remember { mutableStateOf(config.chartSource) }
    LaunchedEffect(providers) {
        if (source.isBlank()) source = providers?.firstOrNull()?.id.orEmpty()
    }
    var charts by remember { mutableStateOf<Result<List<TopList>>?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(source, attempt) {
        if (source.isBlank()) return@LaunchedEffect
        charts = null
        charts = try {
            Result.success(ChartStore.charts(app, source))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
        SectionLabel("显示哪张榜")
        PickRow(
            title = "自动",
            summary = "榜单页当前平台的第一张榜",
            selected = config.chartId.isBlank(),
            index = 0,
            count = 1,
            icon = Icons.Filled.AutoAwesome,
            onClick = { onChange(null) },
        )
        ProviderChips(providers, source) { source = it }
        when (val result = charts) {
            null -> ConfigNote(if (providers?.isEmpty() == true) "没有支持榜单的平台。" else "正在获取榜单…")
            else -> result.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) ConfigNote("这个平台暂时没有榜单。")
                    list.forEachIndexed { i, chart ->
                        PickRow(
                            title = chart.name,
                            summary = chart.updateFrequency,
                            selected = config.chartSource == source && config.chartId == chart.id,
                            index = i,
                            count = list.size,
                            icon = Icons.Filled.Leaderboard,
                            onClick = { onChange(ChartTarget(source, chart.id, chart.name)) },
                        )
                    }
                },
                onFailure = { RetryRow { attempt++ } },
            )
        }
    }
}

/** 听歌模式：先选平台，再选模式；“自动”用第一个已登录平台的第一个模式。没登录时要登录的模式标出来。 */
@Composable
private fun ModePicker(app: MyMusicApp, config: WidgetConfig, onChange: (String, ListeningMode?) -> Unit) {
    val settings by WidgetData.get(app).settings.collectAsState()
    var providers by remember { mutableStateOf<List<ProviderInfo>?>(null) }
    LaunchedEffect(Unit) { providers = ModeStore.providers(app) }
    var source by remember { mutableStateOf(config.modeProvider) }
    LaunchedEffect(providers) {
        if (source.isBlank()) {
            val list = providers.orEmpty()
            source = (list.firstOrNull { settings?.cookies?.get(it.id).orEmpty().isNotBlank() } ?: list.firstOrNull())?.id.orEmpty()
        }
    }
    var modes by remember { mutableStateOf<Result<List<ListeningMode>>?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(source, attempt) {
        if (source.isBlank()) return@LaunchedEffect
        modes = ModeStore.cachedModes(app, source)?.let { Result.success(it) }
        // 有缓存先显示，再在后台刷新一次
        val fresh = try {
            Result.success(ModeStore.loadModes(app, source))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (fresh.isSuccess || modes == null) modes = fresh
    }
    val cookie = settings?.cookies?.get(source).orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
        SectionLabel("放哪个模式")
        PickRow(
            title = "自动",
            summary = "第一个已登录平台的第一个模式",
            selected = config.mode == null,
            index = 0,
            count = 1,
            icon = Icons.Filled.AutoAwesome,
            onClick = { onChange("", null) },
        )
        ProviderChips(providers, source) { source = it }
        when (val result = modes) {
            null -> ConfigNote(if (providers?.isEmpty() == true) "没有支持听歌模式的平台。" else "正在获取模式…")
            else -> result.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) ConfigNote("这个平台暂时没有听歌模式。")
                    list.forEachIndexed { i, mode ->
                        val login = ModeStore.needsLogin(mode, cookie)
                        PickRow(
                            title = mode.name,
                            // 有的平台分组名就是模式名（汽水的“推荐”），不重复写
                            summary = listOf(mode.group.takeIf { it != mode.name }.orEmpty(), if (login) "需要登录" else "")
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            selected = config.modeProvider == source && config.mode?.id == mode.id,
                            index = i,
                            count = list.size,
                            icon = Icons.Filled.Headphones,
                            onClick = { onChange(source, mode) },
                        )
                    }
                },
                onFailure = { RetryRow { attempt++ } },
            )
        }
    }
}

@Composable
private fun ProviderChips(providers: List<ProviderInfo>?, selected: String, onSelect: (String) -> Unit) {
    val list = providers.orEmpty()
    if (list.size <= 1) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        list.forEach { p ->
            FilterChip(
                selected = p.id == selected,
                onClick = { onSelect(p.id) },
                label = { Text(p.name) },
                leadingIcon = if (p.id == selected) ({ Icon(Icons.Filled.Check, null) }) else null,
            )
        }
    }
}

@Composable
private fun PickRow(
    title: String,
    summary: String,
    selected: Boolean,
    index: Int,
    count: Int,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ListRow(
        title = title,
        index = index,
        count = count,
        onClick = onClick,
        icon = icon,
        iconTone = if (selected) IconTone.Primary else IconTone.Secondary,
        summary = summary,
        selected = selected,
        showChevron = false,
        trailing = if (selected) ({ Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }) else null,
    )
}

@Composable
private fun RetryRow(onRetry: () -> Unit) {
    ListRow(
        title = "暂时连不上",
        index = 0,
        count = 1,
        onClick = onRetry,
        icon = Icons.Filled.CloudOff,
        summary = "点这里重试",
        showChevron = false,
    )
}

@Composable
private fun ConfigNote(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
