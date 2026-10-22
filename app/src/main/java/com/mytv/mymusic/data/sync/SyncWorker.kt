package com.mytv.mymusic.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mytv.mymusic.MyMusicApp

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? MyMusicApp ?: return Result.failure()
        val account = app.sync.state.value.account ?: return Result.success()
        if (!account.autoSync) return Result.success()
        return try {
            val success = app.sync.runSync(manual = false, notify = false)
            when {
                success -> Result.success()
                app.sync.state.value.status == SyncStatus.Error -> Result.retry()
                else -> Result.success()
            }
        } catch (error: Exception) {
            Result.retry()
        }
    }
}
