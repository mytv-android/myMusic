package com.mytv.mymusic.widget

import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.mytv.mymusic.MyMusicApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.reflect.KClass

/** 六款组件。 */
enum class WidgetKind(val receiver: KClass<out GlanceAppWidgetReceiver>) {
    Now(NowPlayingWidgetReceiver::class),
    Lyrics(LyricsWidgetReceiver::class),
    Mode(ModeWidgetReceiver::class),
    Charts(ChartWidgetReceiver::class),
    List(ListWidgetReceiver::class),
    Tools(ToolsWidgetReceiver::class),
    ;

    fun widget(): GlanceAppWidget = when (this) {
        Now -> NowPlayingWidget()
        Lyrics -> LyricsWidget()
        Mode -> ModeWidget()
        Charts -> ChartWidget()
        List -> ListWidget()
        Tools -> ToolsWidget()
    }

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, receiver.java)) ?: IntArray(0)
}

/**
 * 组件全部由事件推送（updatePeriodMillis 为 0）。组件的 Glance 会话活着时（首次组合后约 45 秒）自己跟着 StateFlow
 * 重组推送；这里记下要刷哪几款，300ms 内的连续变化合并，只把会话已经超时关掉的那些重新拉起来，同一个变化不推两次。
 * 所以组件显示的东西都要从它在组合里 collect 的 StateFlow 来，时间类的刷新也是先改计数再拉起。
 *
 * 进度条：亮屏且在播放时每 10 秒推一次，只推显示进度条的那几个组件；灭屏立即停。
 * 歌词：亮屏且在播放时按行推，两次至少隔 1.5 秒。
 */
object WidgetUpdater {
    private const val COALESCE_MS = 300L
    private const val PROGRESS_MS = 10_000L
    private const val LYRIC_MIN_GAP_MS = 1_500L
    private const val LYRIC_LEAD_MS = 40L
    private const val MINUTE_MS = 60_000L

    private val started = AtomicBoolean(false)
    private val requests = Channel<WidgetKind>(Channel.UNLIMITED)

    // Glance 会话还开着的组件实例（appWidgetId → 开着的尺寸数）：响应式尺寸每种各组合一份，各自登记
    private val live = ConcurrentHashMap<Int, Int>()

    // 当前显示进度条 / 歌词的组件实例（appWidgetId → 哪一款），组件重组时登记
    private val progressIds = ConcurrentHashMap<Int, WidgetKind>()
    private val lyricIds = ConcurrentHashMap<Int, WidgetKind>()

    /** App 启动时调：桌面上有组件才开始监听；预览图不管有没有组件都要发布。 */
    fun start(app: MyMusicApp) {
        app.appScope.launch(Dispatchers.Default) {
            WidgetPreviews.publishIfNeeded(app)
            if (WidgetKind.entries.any { it.ids(app).isNotEmpty() }) ensureStarted(app)
        }
    }

    /** 组件第一次出现（接收器收到更新、会话开始组合）时调；重复调用没有副作用。 */
    fun ensureStarted(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext as MyMusicApp
        val data = WidgetData.get(app)
        val scope = app.appScope

        scope.launch(Dispatchers.Default) {
            while (true) {
                val pending = mutableSetOf(requests.receive())
                delay(COALESCE_MS)
                while (true) pending += requests.tryReceive().getOrNull() ?: break
                for (kind in pending) refresh(app, kind)
            }
        }

        fun watch(flow: Flow<Any?>, vararg kinds: WidgetKind) {
            scope.launch { flow.distinctUntilChanged().collect { kinds.forEach(::request) } }
        }
        watch(data.now, WidgetKind.Now, WidgetKind.Lyrics)
        watch(data.now.map { it.playback?.let { p -> Triple(p.feedModeId, p.playing, p.song.key) } }, WidgetKind.Mode)
        watch(data.now.map { it.playback?.song?.key }, WidgetKind.List)
        watch(data.palette, *WidgetKind.entries.toTypedArray())
        watch(data.settings.map { it?.cookies }, WidgetKind.Mode, WidgetKind.Charts)
        watch(data.providers, WidgetKind.Mode, WidgetKind.Charts)
        // 登录或退出后，之前点出来的“要登录”记号作废
        scope.launch { data.settings.map { it?.cookies }.distinctUntilChanged().drop(1).collect { ModeStore.clearLoginRequired() } }
        // 漫游中切到的歌记下封面，空闲时的封面叠用
        scope.launch {
            data.playback.collect { p -> if (p != null && p.feedModeId.isNotBlank()) ModeStore.rememberCover(app, p.feedModeId, p.song.cover) }
        }
        watch(data.favorites, WidgetKind.List)
        watch(data.history, WidgetKind.List)
        watch(data.localLists, WidgetKind.List)
        for (kind in listOf(WidgetKind.Now, WidgetKind.Lyrics, WidgetKind.Charts, WidgetKind.List, WidgetKind.Mode)) {
            watch(WidgetImages.version(kind), kind)
        }
        watch(WidgetPrefs.version, *WidgetKind.entries.toTypedArray())
        watch(ChartStore.version, WidgetKind.Charts)
        watch(ModeStore.version, WidgetKind.Mode)

        // 亮屏后补一次：灭屏期间进度和歌词都没推
        scope.launch {
            data.screenOn.collect { on ->
                if (!on) return@collect
                data.progressTick.value++
                data.lyricTick.value = SystemClock.elapsedRealtime()
                listOf(WidgetKind.Now, WidgetKind.Lyrics).forEach(::request)
            }
        }

        val playingOnScreen = combine(data.screenOn, data.now.map { it.playback?.playing == true }) { on, playing -> on && playing }
            .distinctUntilChanged()
        scope.launch {
            playingOnScreen.collectLatest { active ->
                if (!active) return@collectLatest
                while (true) {
                    delay(PROGRESS_MS)
                    data.progressTick.value++
                    pushTracked(app, progressIds)
                }
            }
        }
        scope.launch {
            combine(data.screenOn, data.playback) { on, playback -> if (on) playback else null }.collectLatest { playback ->
                if (playback == null || !playback.playing) return@collectLatest
                var last = 0L
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    val at = WidgetLyrics.nextChangeAt(playback, now) ?: break
                    delay(max(at + LYRIC_LEAD_MS, last + LYRIC_MIN_GAP_MS) - now)
                    last = SystemClock.elapsedRealtime()
                    data.lyricTick.value = last
                    pushTracked(app, lyricIds)
                }
            }
        }
        // Android 6 没有倒数的 Chronometer：定时关闭的剩余时间只显示到分钟，每分钟推一次
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            scope.launch {
                data.sleep.map { it.active }.distinctUntilChanged().collectLatest { active ->
                    while (active) {
                        delay(MINUTE_MS)
                        data.progressTick.value++
                        request(WidgetKind.Now)
                    }
                }
            }
        }

        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                data.screenOn.value = intent.action != Intent.ACTION_SCREEN_OFF
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        Handler(Looper.getMainLooper()).post {
            ContextCompat.registerReceiver(app, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            data.screenOn.value = WidgetData.isInteractive(app)
            app.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                    if (night == data.night.value) return
                    data.night.value = night
                    // Android 12 起桌面按夜间模式自己挑 day / night 颜色；更早的系统颜色在推送时就定了
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) requestAll()
                }

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching {
                    WallpaperManager.getInstance(app).addOnColorsChangedListener(
                        { _, which -> if (which and WallpaperManager.FLAG_SYSTEM != 0) data.wallpaperVersion.value++ },
                        Handler(Looper.getMainLooper()),
                    )
                }
            }
        }
        if (WidgetKind.Charts.ids(app).isNotEmpty()) ChartWidgetWorker.schedule(app)
    }

    fun request(kind: WidgetKind) {
        requests.trySend(kind)
    }

    fun requestAll() = WidgetKind.entries.forEach(::request)

    /** 组件重组时登记：这个实例（所有尺寸合起来）现在显不显示进度条、歌词。 */
    fun track(appWidgetId: Int, kind: WidgetKind, progress: Boolean, lyrics: Boolean) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (progress) progressIds[appWidgetId] = kind else progressIds.remove(appWidgetId)
        if (lyrics) lyricIds[appWidgetId] = kind else lyricIds.remove(appWidgetId)
    }

    fun forget(appWidgetId: Int) {
        progressIds.remove(appWidgetId)
        lyricIds.remove(appWidgetId)
        live.remove(appWidgetId)
    }

    /** 组件的 Glance 会话开始 / 结束组合（MusicWidget 里的 DisposableEffect）。 */
    fun sessionStarted(appWidgetId: Int) {
        live.merge(appWidgetId, 1, Int::plus)
    }

    fun sessionEnded(appWidgetId: Int) {
        live.computeIfPresent(appWidgetId) { _, n -> (n - 1).takeIf { it > 0 } }
    }

    /** 刷新 [kind] 的组件：会话还开着的已经跟着 StateFlow 推过了，只拉起关掉的。 */
    private suspend fun refresh(context: Context, kind: WidgetKind) {
        val ids = kind.ids(context).filterNot(live::containsKey)
        if (ids.isEmpty()) return
        val manager = GlanceAppWidgetManager(context)
        val widget = kind.widget()
        for (id in ids) runCatching { widget.update(context, manager.getGlanceIdBy(id)) }
    }

    /** 进度、歌词的节拍：计数已经改了，开着的会话自己重组；关掉的拉起来。 */
    private suspend fun pushTracked(context: Context, ids: Map<Int, WidgetKind>) {
        if (ids.isEmpty()) return
        val manager = GlanceAppWidgetManager(context)
        for ((id, kind) in ids.entries.toList()) {
            if (live.containsKey(id)) continue
            runCatching { kind.widget().update(context, manager.getGlanceIdBy(id)) }.onFailure { forget(id) }
        }
    }
}
