package com.mytv.mymusic.data.local

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import com.mytv.mymusic.AppLog
import com.mytv.mymusic.BackgroundWork
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.R
import com.mytv.mymusic.data.cloud.CloudUploadStage
import com.mytv.mymusic.widget.WidgetLinks
import com.mytv.mymusic.widget.WidgetTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 上传的后台任务：WorkManager 的长任务，带一条 dataSync 类型的前台通知（Android 14 起前台服务要声明类型，
 * 清单里给 SystemForegroundService 标了 dataSync，也申请了 FOREGROUND_SERVICE_DATA_SYNC）。
 *
 * - 网络约束：只在 Wi-Fi 下上传开着时要求不计流量的网络，否则只要有网；网络不合要求时系统停掉任务，
 *   正在传的那首排回队首，网络回来后任务重新起来接着传。
 * - 应用在后台时系统可能不让起前台服务（Android 12 起），这时不硬起，当普通后台任务跑完这一段。
 * - 没有能跑的（传完了、队列被风控暂停了）就结束，不空挂着前台服务。
 */
class LocalUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var foreground = true

    override suspend fun doWork(): Result {
        val app = applicationContext as? MyMusicApp ?: return Result.success()
        val uploads = app.localUploads
        uploads.awaitRestored()
        if (!uploads.hasRunnable()) return Result.success()
        ensureChannel(applicationContext)
        var shown: Content? = null
        try {
            uploads.runInWorker { state ->
                val content = Content.of(state)
                if (content != shown) {
                    shown = content
                    show(content)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.w(TAG, "上传任务出错：${error.message}")
        } finally {
            // 前台服务的通知由 WorkManager 收；没起成前台服务时是自己发的普通通知，任务结束要自己撤
            if (!foreground) runCatching { NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID) }
        }
        // 永远算成功：失败的项在队列里记着原因，等用户点重试，不靠 WorkManager 退避重跑
        return Result.success()
    }

    private suspend fun show(content: Content) {
        val notification = build(applicationContext, content)
        if (foreground) {
            try {
                setForeground(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        ForegroundInfo(NOTIFICATION_ID, notification)
                    },
                )
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 后台被拉起时系统不让起前台服务（ForegroundServiceStartNotAllowedException）：当普通任务跑
                foreground = false
                AppLog.i(TAG, "前台服务起不来，按普通后台任务继续：${error.javaClass.simpleName}")
            }
        }
        if (canNotify(applicationContext)) {
            runCatching { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification) }
        }
    }

    /** 通知上写的东西；只在这些变了时才更新通知，进度按整数百分比算，免得每一片都刷一次。 */
    private data class Content(val title: String, val text: String, val percent: Int, val indeterminate: Boolean) {
        companion object {
            fun of(state: LocalUploadState): Content {
                val active = state.active.firstOrNull()
                val total = state.total.coerceAtLeast(1)
                val title = when {
                    active != null -> "正在上传 ${state.current} / $total"
                    state.backoffUntil > System.currentTimeMillis() -> "上传稍等一会儿"
                    else -> "准备上传 · 还有 ${state.pendingCount} 首"
                }
                val text = when {
                    active != null -> "${active.item.meta.title.ifBlank { active.item.source.fileName }} · ${active.provider.label}"
                    state.backoffUntil > System.currentTimeMillis() -> "网易云要求放慢，过一会儿自动接着传"
                    else -> "传的是原文件，一首一首传"
                }
                val stage = active?.item?.stage
                val percent = ((active?.item?.progress?.fraction ?: 0f) * 100).toInt()
                return Content(title, text, percent, active == null || stage != CloudUploadStage.UPLOADING)
            }
        }
    }

    companion object {
        private const val TAG = "LocalUploadWorker"
        private const val UNIQUE = "local-upload"
        private const val CHANNEL = "local_upload"
        private const val NOTIFICATION_ID = 4107
        private const val OPEN_REQUEST = 4107

        /** MyMusicApp 交给 [LocalUploads.runner] 的实现。 */
        fun runner(context: Context, scope: CoroutineScope): LocalUploadRunner = LocalUploadRunner { wifiOnly, replace ->
            val app = context.applicationContext
            val manager = BackgroundWork.get(app) ?: return@LocalUploadRunner
            val request = OneTimeWorkRequestBuilder<LocalUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            if (replace) {
                manager.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
                return@LocalUploadRunner
            }
            scope.launch {
                // 已经有一个在等网络的就不再排：排上去也只是在它后面空跑一趟
                val waiting = runCatching { manager.getWorkInfosForUniqueWorkFlow(UNIQUE).first() }.getOrDefault(emptyList())
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                if (!waiting) manager.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.local_upload_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.local_upload_channel_description)
                    setShowBadge(false)
                },
            )
        }

        private fun build(context: Context, content: Content) = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mymusic)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, content.percent, content.indeterminate)
            // 点通知回到本地音乐页，那里能看进度、取消和重试
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    OPEN_REQUEST,
                    WidgetLinks.intent(context, WidgetTarget.LocalMusic),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        private fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
