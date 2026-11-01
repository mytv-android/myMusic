package com.mytv.mymusic

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.UserManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.work.Configuration
import com.mytv.mymusic.data.AppDatabase
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.SettingsStore
import com.mytv.mymusic.data.native.SodaProvider
import com.mytv.mymusic.data.sync.SecretStore
import com.mytv.mymusic.data.sync.SyncCoordinator
import com.mytv.mymusic.data.update.UpdateRepository
import com.mytv.mymusic.playback.AdjustableLruEvictor
import com.mytv.mymusic.playback.EqualizerController
import com.mytv.mymusic.playback.QueueStore
import com.mytv.mymusic.playback.RhythmAnalyzer
import com.mytv.mymusic.playback.ScreensaverState
import com.mytv.mymusic.playback.SleepTimer
import com.mytv.mymusic.ui.MusicViewModel
import com.mytv.mymusic.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.mytv.mymusic.ui.theme.isTelevision
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class MyMusicApp : Application(), ViewModelStoreOwner, Configuration.Provider {
    lateinit var repository: MusicRepository
    lateinit var settings: SettingsStore
    lateinit var database: AppDatabase
    lateinit var mediaCache: SimpleCache
    lateinit var cacheEvictor: AdjustableLruEvictor
    lateinit var secretStore: SecretStore
    lateinit var equalizer: EqualizerController
    lateinit var rhythmAnalyzer: RhythmAnalyzer
    lateinit var sync: SyncCoordinator
    lateinit var update: UpdateRepository
    lateinit var queueStore: QueueStore

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sleepTimer by lazy { SleepTimer(appScope) }

    /**
     * 电视上没有桌面小组件：启动时把组件持久禁用，界面和播放层里的小组件代码路径也在入口处短路，
     * 系统不会拉起组件，Glance 和它依赖的 WorkManager 也就不会被加载。
     */
    val widgetsEnabled: Boolean by lazy { !isTelevision() }

    override val viewModelStore = ViewModelStore()

    /**
     * 整个进程共用的播放 ViewModel：界面、桌面小组件、播放服务（继续播放）都用它，界面关掉后组件仍能控制播放器、
     * 解析下一首。MediaController 绑在创建它的线程的 Looper 上，所以只能在主线程第一次取。
     */
    private val musicViewModelLazy = lazy {
        check(Looper.myLooper() == Looper.getMainLooper()) { "musicViewModel 只能在主线程创建" }
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[MusicViewModel::class.java]
    }
    val musicViewModel: MusicViewModel by musicViewModelLazy

    /** ViewModel 已经建过了；播放服务收尾时只在它存在时通知，不为此新建一个。 */
    val hasMusicViewModel: Boolean get() = musicViewModelLazy.isInitialized()

    /**
     * WorkManager 改由这里按需初始化（清单里已去掉 androidx.startup 的自动初始化）：
     * 解锁后第一次取 WorkManager.getInstance 时才建， Glance 刷新组件、同步 Worker 都在解锁后。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        // 解锁前被拉起（开机自启、桌面组件、媒体键、其它应用或 adb）的进程做不了任何有用的事：
        // 凭据加密存储里的设置、数据库、SecretStore、媒体缓存全都读不到，Glance 首次刷新组件时
        // WorkManager.getInstance 还会抛「Cannot initialize WorkManager in direct boot mode」。
        // 直接结束进程，等用户解锁后由系统重新创建。
        // isDeviceProtectedStorage：进程拿的是设备加密（DE）上下文，解锁后也不会换成 CE；
        // isUserUnlocked：系统在解锁前强行建进程时给的是 CE 上下文，isDeviceProtectedStorage 反而是 false，
        //   只有这一项能发现（实测：给组件加 directBootAware 强行拉起，崩的就是 SecretStore 而不是 WorkManager）。
        val userManager = getSystemService(UserManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            (isDeviceProtectedStorage || userManager?.isUserUnlocked == false)
        ) {
            Process.killProcess(Process.myPid())
            return
        }
        super.onCreate()
        disableWidgetComponentsOnTelevision()
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) = ForegroundActivity.set(activity)
            override fun onActivityPaused(activity: android.app.Activity) = ForegroundActivity.clear(activity)
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        repository = MusicRepository()
        settings = SettingsStore(this)
        database = AppDatabase.create(this)
        cacheEvictor = AdjustableLruEvictor(defaultCacheLimitMb() * MB)
        mediaCache = createMediaCache()
        appScope.launch(Dispatchers.IO) {
            settings.state.map { it.cacheLimitMb }.distinctUntilChanged().collect { mb ->
                cacheEvictor.updateMaxBytes((if (mb > 0) mb else defaultCacheLimitMb()) * MB)
            }
        }
        appScope.launch(Dispatchers.IO) {
            settings.state
                .map { Triple(it.sodaSignerUrl, it.sodaSignerToken, it.sodaSignerEnabled) }
                .distinctUntilChanged()
                .collect { (url, token, enabled) -> SodaProvider.configureSigner(url, token, enabled) }
        }
        secretStore = SecretStore(this)
        equalizer = EqualizerController(this, settings)
        rhythmAnalyzer = RhythmAnalyzer()
        sync = SyncCoordinator(
            context = this,
            db = database,
            libraryDao = database.library(),
            syncDao = database.sync(),
            secretStore = secretStore,
            settings = settings,
            scope = appScope,
        )
        update = UpdateRepository(this, settings)
        queueStore = QueueStore(this)
        // 屏保、息屏的状态：电视上这时候「离开应用」不是用户真的走了，音乐不能停
        ScreensaverState.start(this)
        // 桌面小组件：播放、设置、收藏、亮灭屏变化时合并刷新（手机和平板才有桌面组件）
        if (widgetsEnabled) WidgetUpdater.start(this)
    }

    /**
     * 电视上把小组件组件持久禁用。清单里的 @bool/widgets_enabled 只是保险：实测装 APK 时
     * 系统解析清单不看 uiMode，values-television 到不了，组件照样是启用状态，所以这里是主力。
     */
    private fun disableWidgetComponentsOnTelevision() {
        if (!isTelevision()) return
        val pm = packageManager
        for (name in WIDGET_COMPONENTS) {
            val component = ComponentName(this, name)
            if (pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) continue
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun createMediaCache(): SimpleCache {
        val directory = File(cacheDir, "media")
        return runCatching {
            SimpleCache(directory, cacheEvictor)
        }.getOrElse {
            directory.deleteRecursively()
            SimpleCache(directory, cacheEvictor)
        }
    }

    /** 电视盒子存储小，默认 256 MB；其他设备 512 MB。 */
    fun defaultCacheLimitMb(): Int = if (isTelevision()) 256 else 512

    companion object {
        private const val MB = 1024L * 1024

        /** 清单里带 @bool/widgets_enabled 的组件；只写类名，避免在电视上加载小组件代码。 */
        private val WIDGET_COMPONENTS = listOf(
            "com.mytv.mymusic.widget.NowPlayingWidgetReceiver",
            "com.mytv.mymusic.widget.LyricsWidgetReceiver",
            "com.mytv.mymusic.widget.ModeWidgetReceiver",
            "com.mytv.mymusic.widget.ChartWidgetReceiver",
            "com.mytv.mymusic.widget.ListWidgetReceiver",
            "com.mytv.mymusic.widget.ToolsWidgetReceiver",
            "com.mytv.mymusic.widget.WidgetConfigActivity",
        )
    }
}
