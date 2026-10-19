package com.mytv.mymusic

import android.app.Application
import com.mytv.mymusic.data.AppDatabase
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.SettingsStore

class MyMusicApp : Application() {
    lateinit var repository: MusicRepository
    lateinit var settings: SettingsStore
    lateinit var database: AppDatabase

    override fun onCreate() {
        super.onCreate()
        repository = MusicRepository()
        settings = SettingsStore(this)
        database = AppDatabase.create(this)
    }
}
