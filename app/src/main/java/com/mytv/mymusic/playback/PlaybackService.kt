package com.mytv.mymusic.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaButtonReceiver
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.R
import com.mytv.mymusic.widget.WidgetLinks
import com.mytv.mymusic.widget.WidgetTarget
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var equalizer: EqualizerController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val foregroundGuard = Runnable { ensureForegroundOrStop() }

    override fun onCreate() {
        super.onCreate()
        // 不设置的话媒体通知会用 Media3 自带的小图标（一个圈里的播放键），和品牌图标对不上
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_stat_mymusic) }
        )
        val app = application as? MyMusicApp
        val cache = app?.mediaCache
        val builder = ExoPlayer.Builder(this).setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        ).setHandleAudioBecomingNoisy(true)
            // 息屏后在线播放也要保持网络和 CPU，避免 Wi-Fi 休眠导致卡住
            .setWakeMode(C.WAKE_MODE_NETWORK)
        app?.let { builder.setRenderersFactory(RhythmRenderersFactory(this, it.rhythmAnalyzer)) }
        // 冷启动竞态：媒体按键 / 续播可以在设置流首帧到达前把服务拉起来，那时候
        // AppleSession 还是空的，授权会白跑一次。先主动取一次。
        app?.appScope?.let { AppleSession.refreshAsync(app.settings, it) }
        // Apple Music 整曲是 Widevine 加密的 HLS，交给设备自带的 CDM 解。
        // 只有带 DrmConfiguration 的 MediaItem 才走这套，其余源原样播放。
        //
        // 每个 MediaItem 单独建一个 session manager：adamId / PSSH 必须按曲目绑进回调里，
        // 而 ExoMediaDrm.KeyRequest.getLicenseServerUrl() 拿到的是 CDM 从 PSSH 解出来的默认
        // 授权地址（Apple 的 PSSH 里没有），是空的，搭在 licenseUri 上的参数到不了回调。
        // 按 licenseUri 缓存，换歌不重复建。
        val drmManagers = mutableMapOf<String, DrmSessionManager>()
        val drmLock = Any()
        builder.setMediaSourceFactory(
            DefaultMediaSourceFactory(
                cache?.let {
                    CacheDataSource.Factory().setCache(it).setUpstreamDataSourceFactory(DefaultDataSource.Factory(this))
                } ?: DefaultDataSource.Factory(this),
            ).setDrmSessionManagerProvider { mediaItem ->
                val drm = mediaItem.localConfiguration?.drmConfiguration
                if (drm?.scheme != C.WIDEVINE_UUID) return@setDrmSessionManagerProvider DrmSessionManager.DRM_UNSUPPORTED
                val uri = drm.licenseUri?.toString().orEmpty()
                val params = AppleWidevineDrmCallback.parseQuery(uri)
                synchronized(drmLock) {
                    drmManagers.getOrPut(uri) {
                        DefaultDrmSessionManager.Builder().build(
                            AppleWidevineDrmCallback(
                                cookieProvider = { AppleSession.cookie },
                                adamId = params["adamId"].orEmpty(),
                                pssh = params["pssh"].orEmpty(),
                                isLibrary = params["library"] == "1",
                            ),
                        )
                    }
                }
            },
        )
        val player = builder.build()
        // 随机顺序跟着队列改动走：随机时“下一首播放”照样下一首放，解析完换地址也不会跳到别的歌
        player.setShuffleOrder(QueueShuffleOrder())
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                // 打开随机时从正在放的这首重新打乱；不然它可能排在随机顺序中间，不循环时排在它前面的就轮不到了
                if (shuffleModeEnabled) {
                    player.setShuffleOrder(QueueShuffleOrder().cloneAndSet(player.mediaItemCount, player.currentMediaItemIndex))
                }
            }
        })
        app?.rhythmAnalyzer?.let { analyzer ->
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // 点歌、上一首 / 下一首、换了队列：渲染器的偏移不变，音频这边看着和同一首里跳转一样，得从这里告诉节拍跟踪。
                    // 自动接下一首由输出段偏移判断；单曲循环本来就该留着速度
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                    ) {
                        analyzer.onNewSong()
                    }
                }
            })
        }
        equalizer = app?.equalizer
        equalizer?.let { controller ->
            val sessionId = player.audioSessionId
            if (sessionId != C.AUDIO_SESSION_ID_UNSET) controller.attach(sessionId)
            player.addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) controller.attach(audioSessionId)
                }
            })
        }
        app?.sleepTimer?.attach(player)
        // 通知栏卡片、锁屏和系统媒体面板点一下要能回到播放页；不给内容意图的话点了没反应
        val openPlayer = PendingIntent.getActivity(
            this,
            OPEN_PLAYER_REQUEST,
            WidgetLinks.intent(this, WidgetTarget.Player),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(ResumptionCallback())
            .setSessionActivity(openPlayer)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        // 蓝牙耳机、系统媒体控件在 App 没运行时按播放：媒体按键接收器用 startForegroundService 拉起服务，
        // 系统要求几秒内进前台。继续播放要先解析地址，断网时可能放不起来，到时还没进前台就自己收场，不让系统崩掉进程
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON && Build.VERSION.SDK_INT >= 26 && !isPlaybackOngoing()) {
            handler.removeCallbacks(foregroundGuard)
            handler.postDelayed(foregroundGuard, FOREGROUND_GUARD_MS)
        }
        return result
    }

    private fun ensureForegroundOrStop() {
        if (isPlaybackOngoing()) return
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(GUARD_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(GUARD_CHANNEL, getString(R.string.playback_resume_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, GUARD_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mymusic)
            .setContentTitle(getString(R.string.playback_resume_failed))
            .setSilent(true)
            .build()
        runCatching {
            ServiceCompat.startForeground(
                this,
                GUARD_NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 从最近任务划掉：在放就接着放；没在放就停掉服务。ViewModel 是进程级的、一直连着服务，
     * 先让它放开控制器，不然服务被绑着停不下来，通知栏会一直挂着一个暂停的播放器。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val playing = session?.player?.isPlaying == true
        if (!isPlaybackOngoing() || !playing) {
            (application as? MyMusicApp)?.takeIf { it.hasMusicViewModel }?.musicViewModel?.releasePlayer()
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(foregroundGuard)
        (application as? MyMusicApp)?.sleepTimer?.detach()
        equalizer?.detach()
        equalizer = null
        (application as? MyMusicApp)?.rhythmAnalyzer?.setActive(false)
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    /** 播放器是空的时候（进程刚起来）收到播放：从 ViewModel 取回上次的队列，当前这首解析好地址再交给 Media3 开播。 */
    private inner class ResumptionCallback : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val app = application as? MyMusicApp
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException())
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            app.appScope.launch {
                val items = runCatching { app.musicViewModel.buildResumption(isForPlayback) }.getOrNull()
                if (items != null) future.set(items) else future.setException(IllegalStateException("没有可以继续播放的队列"))
            }
            return future
        }
    }

    private companion object {
        const val FOREGROUND_GUARD_MS = 8_000L
        const val GUARD_CHANNEL = "playback_resume"
        const val GUARD_NOTIFICATION_ID = 0x6d6d
        const val OPEN_PLAYER_REQUEST = 0x6d65
    }
}

/**
 * 系统媒体按键接收器（蓝牙耳机、Android 11+ 媒体控件的继续播放）。没有存下来的队列时没东西可放，
 * 就不拉起前台服务，免得服务起来了却进不了前台被系统判崩。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ResumeButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        val app = context.applicationContext as? MyMusicApp ?: return false
        return app.queueStore.hasSaved()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class RhythmRenderersFactory(
    context: Context,
    private val analyzer: RhythmAnalyzer,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = RhythmAudioSink(
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(
                arrayOf(
                    TeeAudioProcessor(analyzer.audioBufferSink),
                    VolumeGainAudioProcessor(),
                )
            )
            .build(),
        analyzer,
    )
}
