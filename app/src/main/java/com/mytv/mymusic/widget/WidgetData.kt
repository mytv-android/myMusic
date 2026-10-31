package com.mytv.mymusic.widget

import android.content.Context
import android.content.res.Configuration
import android.os.PowerManager
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.data.AppSettings
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.NowPlayingShape
import com.mytv.mymusic.playback.SavedPlayback
import com.mytv.mymusic.playback.SleepTimerState
import com.mytv.mymusic.ui.song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** 正在播放组件要的一切：当前歌曲和进度、形状、收藏、定时关闭。 */
data class NowState(
    val playback: PlaybackSnapshot?,
    val shape: CoverShapeChoice,
    val favorite: Boolean,
    val sleep: SleepTimerState,
)

/** 自建歌单的名字和首数（歌单组件的配置页、标题用）。 */
data class LocalListInfo(val id: String, val name: String, val count: Int)

/**
 * 桌面小组件的数据源，整个进程一份。播放状态来自 [WidgetHub]（ViewModel 发布）或磁盘上的队列；
 * 设置、收藏、最近播放、自建歌单直接读 DataStore 和 Room，组件不必等 ViewModel 建出来。
 */
class WidgetData private constructor(private val app: MyMusicApp) {
    private val scope = app.appScope
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val settings: StateFlow<AppSettings?> = app.settings.state.stateIn(scope, SharingStarted.Eagerly, null)

    private val disk = MutableStateFlow<PlaybackSnapshot?>(null)
    private val diskLoaded = MutableStateFlow(false)

    init {
        scope.launch { reloadDisk() }
    }

    /** 重读磁盘上的队列。只在 ViewModel 还没接手时有用。 */
    suspend fun reloadDisk() {
        val saved = app.queueStore.load()
        diskShape = saved?.let { it.current.key to it.cursor.shapeChoice }
        disk.value = saved?.let(::snapshotOf)
        diskLoaded.value = true
    }

    // 磁盘上那首的形状：ViewModel 还没恢复 NowPlayingShape 时组件也裁成上次的样子
    @Volatile
    private var diskShape: Pair<String, CoverShapeChoice?>? = null

    /** ViewModel 接手后只信它（null 就是真没有队列）；之前用 ViewModel 已发布的，再不行用磁盘上的。 */
    val playback: StateFlow<PlaybackSnapshot?> = combine(WidgetHub.playback, WidgetHub.authoritative, disk) { live, authoritative, saved ->
        if (authoritative) live else live ?: saved
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val favorites: StateFlow<List<Song>> = app.database.dao().songs("favorite")
        .map { list -> list.mapNotNull { runCatching { it.song(json) }.getOrNull() } }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val history: StateFlow<List<Song>> = app.database.dao().songs("history")
        .map { list -> list.take(HISTORY_LIMIT).mapNotNull { runCatching { it.song(json) }.getOrNull() } }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val localLists: StateFlow<List<LocalListInfo>> =
        combine(app.database.library().playlists(), app.database.library().entryCounts()) { lists, counts ->
            val total = counts.associate { it.playlistId to it.total }
            lists.map { LocalListInfo(it.id, it.name, total[it.id] ?: 0) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun localListSongs(id: String): Flow<List<Song>> = app.database.library().entries(id)
        .map { list -> list.mapNotNull { runCatching { json.decodeFromString<Song>(it.payload) }.getOrNull() } }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)

    val sleep: StateFlow<SleepTimerState> = app.sleepTimer.state

    /** 平台列表（本地常量，不联网）：听歌模式、榜单组件找平台名和能力。 */
    val providers: StateFlow<List<ProviderInfo>> = flow { emit(runCatching { app.repository.providers() }.getOrDefault(emptyList())) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 亮屏。灭屏时进度条和歌词都不推。 */
    val screenOn = MutableStateFlow(isInteractive(app))

    /** 系统深色模式；Android 12 以下颜色在推送时就定了，切换深浅后要按它重推。 */
    val night = MutableStateFlow(isNight(app))

    /** 换壁纸时加一（配色来源是系统壁纸时重新取色）。 */
    val wallpaperVersion = MutableStateFlow(0)

    /** 进度条的节拍：亮屏且在播放时每 10 秒加一，组件读它来重算进度。 */
    val progressTick = MutableStateFlow(0L)

    /** 歌词换行的节拍：亮屏且在播放时，每到下一句（两次至少隔 1.5 秒）更新成当时的 elapsedRealtime。 */
    val lyricTick = MutableStateFlow(0L)

    val now: StateFlow<NowState> = combine(playback, NowPlayingShape.current, favorites, sleep, settings) { playback, shape, favorites, sleep, settings ->
        NowState(
            playback = playback,
            shape = shapeFor(playback, shape, settings),
            favorite = playback != null && favorites.any { it.key == playback.song.key },
            sleep = sleep,
        )
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, NowState(null, CoverShapeChoice.Cookie12, false, SleepTimerState()))

    val palette: StateFlow<WidgetPalette?> = combine(settings, playback.map { it?.seed ?: 0 }.distinctUntilChanged(), wallpaperVersion) { settings, seed, _ ->
        settings?.let { WidgetColors.palette(app, it, seed) }
    }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    /** provideGlance 开始组合前等设置和播放状态就绪，免得第一帧闪一下空状态。 */
    suspend fun awaitReady() {
        withTimeoutOrNull(READY_TIMEOUT_MS) {
            settings.first { it != null }
            palette.first { it != null }
            combine(diskLoaded, WidgetHub.authoritative) { loaded, authoritative -> loaded || authoritative }.first { it }
        }
    }

    private fun shapeFor(playback: PlaybackSnapshot?, current: NowPlayingShape.Current, settings: AppSettings?): CoverShapeChoice {
        val key = playback?.song?.key
        if (key != null && current.songKey == key) return current.shape
        val saved = diskShape
        if (key != null && saved?.first == key) saved.second?.let { return it }
        return settings?.coverShape?.takeIf { it != CoverShapeChoice.Random } ?: CoverShapeChoice.Cookie12
    }

    private fun snapshotOf(saved: SavedPlayback): PlaybackSnapshot {
        val song = saved.current
        return PlaybackSnapshot(
            song = song,
            index = saved.index,
            queueSize = saved.songs.size,
            playing = false,
            live = false,
            positionMs = saved.cursor.positionMs,
            anchorAt = 0L,
            durationMs = saved.cursor.durationMs.takeIf { it > 0 } ?: song.duration * 1000L,
            hasPrevious = saved.songs.size > 1,
            hasNext = saved.index < saved.songs.lastIndex,
        )
    }

    companion object {
        private const val HISTORY_LIMIT = 50
        private const val READY_TIMEOUT_MS = 3_000L

        @Volatile
        private var instance: WidgetData? = null

        fun get(context: Context): WidgetData = instance ?: synchronized(this) {
            instance ?: WidgetData(context.applicationContext as MyMusicApp).also { instance = it }
        }

        fun isNight(context: Context): Boolean =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        fun isInteractive(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    }
}
