package com.mytv.mymusic

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.work.Configuration
import com.mytv.mymusic.data.AppDatabase
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.SettingsStore
import com.mytv.mymusic.data.native.NativeProviders
import com.mytv.mymusic.data.native.SodaPlayReporter
import com.mytv.mymusic.data.native.SodaProvider
import com.mytv.mymusic.data.playreport.KuwoPlayReporter
import com.mytv.mymusic.data.playreport.NetEasePlayReporter
import com.mytv.mymusic.data.playreport.PlayReportHub
import com.mytv.mymusic.data.playreport.QQPlayReporter
import com.mytv.mymusic.data.playreport.QianqianPlayReporter
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
     * 第一次取 WorkManager.getInstance 时才建， Glance 刷新组件、同步 Worker 都在解锁后。
     * 进程处于 DE 上下文或用户尚未解锁时不要取，见 [BackgroundWork]。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        val backgroundWork = BackgroundWork.available(this)
        if (!backgroundWork) {
            android.util.Log.w("MyMusicApp", "设备保护存储或未解锁上下文：跳过桌面组件与 WorkManager 后台调度")
        }
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
        // 播放上报：开关、落盘的设备指纹、平台 Cookie 都从设置里来，
        // 和签名器一样改设置即时生效，不需要重启
        var reportDeviceIdReady = false
        appScope.launch(Dispatchers.IO) {
            settings.state.collect { values ->
                PlayReportHub.setEnabled(values.playReportEnabled)
                PlayReportHub.updateCookies(values.cookies)
                SodaProvider.configureFallbackDeviceId(values.sodaReportDeviceId)
                // 第一次开启：先把设备指纹落盘，之后每次启动都用同一个
                if (values.playReportEnabled && values.sodaReportDeviceId.isBlank() && !reportDeviceIdReady) {
                    reportDeviceIdReady = true
                    SodaProvider.configureFallbackDeviceId(settings.sodaReportDeviceId())
                }
            }
        }
        val sodaProvider = NativeProviders.get(SodaProvider.SODA_PROVIDER_ID) as? SodaProvider
        if (sodaProvider != null) {
            PlayReportHub.register(
                SodaPlayReporter(
                    provider = sodaProvider,
                    cookie = { PlayReportHub.cookie(SodaProvider.SODA_PROVIDER_ID) },
                    deviceId = { sodaProvider.effectiveDeviceId() },
                ),
            )
        }
        // 其余平台：有公开上报接口的才注册，咪咕 / JOOX 没有，见 README
        PlayReportHub.register(NetEasePlayReporter { PlayReportHub.cookie("netease") })
        PlayReportHub.register(QQPlayReporter { PlayReportHub.cookie("qq") })
        PlayReportHub.register(KuwoPlayReporter { PlayReportHub.cookie("kuwo") })
        PlayReportHub.register(QianqianPlayReporter { PlayReportHub.cookie("qianqian") })
        // 屏保、息屏的状态：电视上这时候「离开应用」不是用户真的走了，音乐不能停
        ScreensaverState.start(this)
        // 桌面小组件：播放、设置、收藏、亮灭屏变化时合并刷新，电视上同样启用。
        // DE 上下文里 Glance 依赖的 WorkManager 起不来，直接不启动组件这条链路，主界面照常可用。
        if (backgroundWork) WidgetUpdater.start(this)
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
    }
}
