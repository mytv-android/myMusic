package com.mytv.mymusic.widget

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.mytv.mymusic.BackgroundWork
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.ListeningMode
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val storeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** 榜单组件显示的一张榜：前几十首和上次取到的时间。[failedAt] 晚于 [updatedAt] 表示最近一次刷新没连上（离线）。 */
@Serializable
data class ChartSnapshot(
    val source: String = "",
    val id: String = "",
    val name: String = "",
    val sourceName: String = "",
    val songs: List<Song> = emptyList(),
    val updatedAt: Long = 0L,
    val failedAt: Long = 0L,
) {
    val offline: Boolean get() = failedAt > updatedAt
}

/** 一首歌在榜上的变化，只有酷我给（extra 里的 is_new / rank_change / trend）。 */
sealed interface RankDelta {
    data object New : RankDelta
    data object Same : RankDelta
    data class Up(val by: Int) : RankDelta
    data class Down(val by: Int) : RankDelta

    companion object {
        fun of(song: Song): RankDelta? {
            if (song.extra["is_new"] == "1" || song.extra["is_new"] == "true") return New
            val change = song.extra["rank_change"]?.toIntOrNull()
                ?: song.extra["trend"]?.let { t -> t.drop(1).toIntOrNull()?.let { if (t.startsWith("d")) -it else it } }
                ?: return null
            return when {
                change > 0 -> Up(change)
                change < 0 -> Down(-change)
                else -> Same
            }
        }
    }
}

/**
 * 榜单组件的缓存：每张榜一个 JSON 文件（files/widget_charts）。WorkManager 每 12 小时刷一次，打开 App 时顺带刷；
 * 组件只读缓存，没网时显示离线和上次更新的时间。
 */
object ChartStore {
    private const val MAX_SONGS = 30
    val version = MutableStateFlow(0)
    private val memory = ConcurrentHashMap<String, ChartSnapshot>()
    private val lock = Mutex()

    private fun file(context: Context, source: String, id: String): File =
        File(File(context.filesDir, "widget_charts"), "${source}_${id.hashCode().toUInt()}.json")

    fun read(context: Context, source: String, id: String): ChartSnapshot? {
        if (source.isBlank() || id.isBlank()) return null
        val key = "$source/$id"
        memory[key]?.let { return it }
        val f = file(context, source, id)
        val snapshot = runCatching { if (f.isFile) storeJson.decodeFromString<ChartSnapshot>(f.readText()) else null }.getOrNull()
        snapshot?.let { memory[key] = it }
        return snapshot
    }

    /** 组件配置里的榜单；没配过时用榜单页当前的平台和它的第一张榜（取过一次就记下）。 */
    fun target(context: Context, config: WidgetConfig): ChartTarget? {
        if (config.chartSource.isNotBlank() && config.chartId.isNotBlank()) {
            return ChartTarget(config.chartSource, config.chartId, config.chartName)
        }
        val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
        val source = prefs.getString("defaultChart.source", null).orEmpty()
        val id = prefs.getString("defaultChart.id", null).orEmpty()
        if (source.isBlank() || id.isBlank()) return null
        return ChartTarget(source, id, prefs.getString("defaultChart.name", null).orEmpty())
    }

    /** 可以放进组件的平台：支持榜单和榜单歌曲，按音乐源顺序排，没启用的不出现。 */
    suspend fun providers(app: MyMusicApp): List<ProviderInfo> =
        runCatching { app.repository.providers() }.getOrDefault(emptyList())
            .filter { it.topLists && it.topListSongs }
            .bySourceOrder(app)

    suspend fun charts(app: MyMusicApp, source: String): List<TopList> {
        val cookie = app.settings.state.first().cookies[source].orEmpty()
        return app.repository.topLists(source, cookie)
    }

    /** 刷新桌面上所有榜单组件用到的榜；全部失败（多半是没网）返回 false。 */
    suspend fun refreshAll(app: MyMusicApp): Boolean {
        val ids = WidgetKind.Charts.ids(app).toList()
        val targets = ids.mapNotNull { target(app, WidgetPrefs.get(app, it)) }.toMutableSet()
        if (targets.isEmpty() || ids.any { WidgetPrefs.get(app, it).chartId.isBlank() }) {
            resolveDefault(app)?.let(targets::add)
        }
        var ok = targets.isEmpty()
        for (target in targets) ok = refresh(app, target) || ok
        return ok
    }

    suspend fun refresh(app: MyMusicApp, target: ChartTarget): Boolean = lock.withLock {
        val previous = read(app, target.source, target.id)
        val providers = providers(app)
        val sourceName = providers.find { it.id == target.source }?.name ?: previous?.sourceName.orEmpty()
        val cookie = app.settings.state.first().cookies[target.source].orEmpty()
        val songs = try {
            app.repository.topListSongs(target.source, cookie, target.id).take(MAX_SONGS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        val next = if (!songs.isNullOrEmpty()) {
            ChartSnapshot(target.source, target.id, target.name.ifBlank { previous?.name.orEmpty() }, sourceName, songs, updatedAt = now)
        } else {
            (previous ?: ChartSnapshot(target.source, target.id, target.name, sourceName)).copy(failedAt = now)
        }
        write(app, next)
        !songs.isNullOrEmpty()
    }

    private suspend fun resolveDefault(app: MyMusicApp): ChartTarget? {
        val settings = app.settings.state.first()
        val providers = providers(app)
        val provider = providers.find { it.id == settings.chartsSource } ?: providers.find { it.id == settings.provider } ?: providers.firstOrNull()
            ?: return null
        val chart = runCatching { charts(app, provider.id).firstOrNull() }.getOrNull() ?: return null
        app.getSharedPreferences("widgets", Context.MODE_PRIVATE).edit {
            putString("defaultChart.source", provider.id)
            putString("defaultChart.id", chart.id)
            putString("defaultChart.name", chart.name)
        }
        return ChartTarget(provider.id, chart.id, chart.name)
    }

    private suspend fun write(context: Context, snapshot: ChartSnapshot) = withContext(Dispatchers.IO) {
        memory["${snapshot.source}/${snapshot.id}"] = snapshot
        runCatching {
            val f = file(context, snapshot.source, snapshot.id)
            f.parentFile?.mkdirs()
            val temp = File(f.parentFile, f.name + ".tmp")
            temp.writeText(storeJson.encodeToString(ChartSnapshot.serializer(), snapshot))
            if (!temp.renameTo(f)) {
                f.delete()
                temp.renameTo(f)
            }
        }
        version.value++
    }
}

data class ChartTarget(val source: String, val id: String, val name: String)

/** 榜单组件的定时刷新：每 12 小时一次、要联网。Glance 本身就依赖 WorkManager，不新增依赖。 */
class ChartWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MyMusicApp
        if (WidgetKind.Charts.ids(app).isEmpty()) return Result.success()
        ChartStore.refreshAll(app)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "widget-charts"
        private const val NOW = "widget-charts-now"
        private const val OPEN_REFRESH_MS = 30 * 60_000L

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ChartWidgetWorker>(12, TimeUnit.HOURS).setConstraints(network).build()
            BackgroundWork.get(context)?.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            val workManager = BackgroundWork.get(context) ?: return
            workManager.cancelUniqueWork(PERIODIC)
            workManager.cancelUniqueWork(NOW)
        }

        /** 马上刷一次（组件刚添加、点了重试、配置改了）。没网时等联网再跑。 */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ChartWidgetWorker>().setConstraints(network).build()
            BackgroundWork.get(context)?.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /** 打开 App 时顺带刷新：桌面上有榜单组件、且上次刷新已经过了半小时。 */
        fun refreshOnOpen(context: Context) {
            val app = context.applicationContext as MyMusicApp
            app.appScope.launch(Dispatchers.Default) {
                val ids = WidgetKind.Charts.ids(app)
                if (ids.isEmpty()) return@launch
                val now = System.currentTimeMillis()
                val stale = ids.any { id ->
                    val target = ChartStore.target(app, WidgetPrefs.get(app, id)) ?: return@any true
                    val snapshot = ChartStore.read(app, target.source, target.id) ?: return@any true
                    now - snapshot.updatedAt > OPEN_REFRESH_MS
                }
                if (stale) refreshNow(app)
            }
        }
    }
}

/**
 * 听歌模式组件用的缓存：每个平台的模式列表（配置页和默认模式用）、每个模式最近放过的几张封面（空闲时的封面叠）、
 * 以及“这个模式点了说要登录”的记号。
 */
object ModeStore {
    val version = MutableStateFlow(0)
    private val modes = ConcurrentHashMap<String, List<ListeningMode>>()
    private val loginFailed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private const val FILE = "widget_modes"
    private const val COVERS = 3

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun cachedModes(context: Context, provider: String): List<ListeningMode>? {
        modes[provider]?.let { return it }
        val text = prefs(context).getString("modes.$provider", null) ?: return null
        val list = runCatching { storeJson.decodeFromString(ListSerializer(ListeningMode.serializer()), text) }.getOrNull() ?: return null
        modes[provider] = list
        return list
    }

    suspend fun loadModes(app: MyMusicApp, provider: String): List<ListeningMode> {
        val cookie = app.settings.state.first().cookies[provider].orEmpty()
        val list = app.repository.listeningModes(provider, cookie)
        modes[provider] = list
        prefs(app).edit { putString("modes.$provider", storeJson.encodeToString(ListSerializer(ListeningMode.serializer()), list)) }
        version.value++
        return list
    }

    /** 支持听歌模式的平台，按音乐源顺序排，没启用的不出现。 */
    suspend fun providers(app: MyMusicApp): List<ProviderInfo> =
        runCatching { app.repository.providers() }.getOrDefault(emptyList())
            .filter { it.listeningModes }
            .bySourceOrder(app)

    /**
     * 组件绑定的模式；没配过时用第一个已登录平台的第一个模式，都没登录就用第一个平台的。
     * 缓存里没有模式列表时返回 null，并在后台去取（取到后 [version] 加一）。
     */
    fun resolve(app: MyMusicApp, config: WidgetConfig, cookies: Map<String, String>, providers: List<ProviderInfo>): Pair<String, ListeningMode>? {
        val mode = config.mode
        if (mode != null && config.modeProvider.isNotBlank()) return config.modeProvider to mode
        val candidates = providers.filter { it.listeningModes }
        val ordered = candidates.filter { cookies[it.id].orEmpty().isNotBlank() } + candidates.filter { cookies[it.id].orEmpty().isBlank() }
        for (provider in ordered) {
            val list = cachedModes(app, provider.id)
            if (list == null) {
                app.appScope.launch(Dispatchers.IO) { runCatching { loadModes(app, provider.id) } }
                return null
            }
            list.firstOrNull()?.let { return provider.id to it }
        }
        return null
    }

    /** 没登录时放不了的模式：私人漫游和平台标了要登录的；组件上点过一次、错误里说要登录的也算。 */
    fun needsLogin(mode: ListeningMode, cookie: String): Boolean {
        if (cookie.isNotBlank()) return false
        return mode.group == "私人漫游" || mode.extra["need_login"] == "true" || mode.id in loginFailed
    }

    fun markLoginRequired(modeId: String) {
        if (loginFailed.add(modeId)) version.value++
    }

    fun clearLoginRequired() {
        if (loginFailed.isEmpty()) return
        loginFailed.clear()
        version.value++
    }

    fun covers(context: Context, modeId: String): List<String> =
        prefs(context).getString("covers.$modeId", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    /** 漫游中切到的歌：记下封面，最近的在前，最多三张。 */
    fun rememberCover(context: Context, modeId: String, cover: String) {
        if (modeId.isBlank() || cover.isBlank()) return
        val list = covers(context, modeId)
        if (list.firstOrNull() == cover) return
        val next = (listOf(cover) + list.filter { it != cover }).take(COVERS)
        prefs(context).edit { putString("covers.$modeId", next.joinToString("\n")) }
    }
}

/**
 * 组件用的平台列表：按设置里的音乐源顺序排，没启用的平台不出现——用户在「音乐源」里关掉的源，
 * 不该再由组件默认选中。已经配过的组件不受影响：它们存的是明确的平台 id。
 */
private suspend fun List<ProviderInfo>.bySourceOrder(app: MyMusicApp): List<ProviderInfo> {
    val settings = app.settings.state.first()
    val order = settings.aggregation.priorityFor(map { it.id }).withIndex()
        .associate { (index, id) -> id to index }
    val enabled = settings.aggregation.enabledProviders.toSet()
    return sortedBy { order[it.id] ?: Int.MAX_VALUE }.filter { it.id in enabled }
}
