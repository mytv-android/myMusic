package com.mytv.mymusic

import android.app.Application
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.mytv.mymusic.data.AppDatabase
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.SettingsStore
import java.io.File

class MyMusicApp : Application() {
    lateinit var repository: MusicRepository
    lateinit var settings: SettingsStore
    lateinit var database: AppDatabase
    lateinit var mediaCache: SimpleCache

    override fun onCreate() {
        super.onCreate()
        repository = MusicRepository()
        settings = SettingsStore(this)
        database = AppDatabase.create(this)
        mediaCache = createMediaCache()
    }

    private fun createMediaCache(): SimpleCache {
        val directory = File(cacheDir, "media")
        return runCatching {
            SimpleCache(directory, LeastRecentlyUsedCacheEvictor(MAX_MEDIA_CACHE_BYTES))
        }.getOrElse {
            directory.deleteRecursively()
            SimpleCache(directory, LeastRecentlyUsedCacheEvictor(MAX_MEDIA_CACHE_BYTES))
        }
    }

    companion object {
        const val MAX_MEDIA_CACHE_BYTES = 512L * 1024 * 1024
    }
}
