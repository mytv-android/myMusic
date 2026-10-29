package com.mytv.mymusic

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import com.mytv.mymusic.data.AppDatabase
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.SettingsStore
import com.mytv.mymusic.data.native.SodaProvider
import com.mytv.mymusic.data.sync.SecretStore
import com.mytv.mymusic.data.sync.SyncCoordinator
import com.mytv.mymusic.data.update.UpdateRepository
import com.mytv.mymusic.playback.AdjustableLruEvictor
import com.mytv.mymusic.playback.EqualizerController
import com.mytv.mymusic.playback.RhythmAnalyzer
import com.mytv.mymusic.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.mytv.mymusic.ui.theme.isTelevision
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class MyMusicApp : Application() {
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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sleepTimer by lazy { SleepTimer(appScope) }

    override fun onCreate() {
        super.onCreate()
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
