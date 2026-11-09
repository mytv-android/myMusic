package com.mytv.mymusic.playback

import com.mytv.mymusic.AppLog
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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
import com.mytv.mymusic.data.native.YUNTING_LIVE_HOST
import com.mytv.mymusic.widget.WidgetLinks
import com.mytv.mymusic.widget.WidgetTarget
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var equalizer: EqualizerController? = null
    private var player: ExoPlayer? = null
    private var crossfade: CrossfadeController? = null
    /** 两路播放器各自的过渡效果参数（低通 / 高通 / 回声），和音频线程共享。 */
    private val crossfadeParams = arrayOf(CrossfadeParams(), CrossfadeParams())
    private lateinit var mediaSourceFactory: MediaSource.Factory
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
        val drmInfoLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        // 本地歌的 WebDAV 副本要带认证和证书钉住：按地址把 WebDAV 服务器下面的请求交给它自己的客户端，
        // 其余原样走默认工厂。包在缓存里面，云端副本和在线歌一样进播放缓存，但不会存进“下载”
        val upstream = app?.cloud?.routing(DefaultDataSource.Factory(this)) ?: DefaultDataSource.Factory(this)
        val baseFactory = DefaultMediaSourceFactory(
                cache?.let {
                    val direct = upstream
                    LiveBypassDataSourceFactory(
                        cached = CacheDataSource.Factory().setCache(it).setUpstreamDataSourceFactory(direct),
                        direct = direct,
                        isLive = { uri ->
                            val host = uri.host.orEmpty()
                            // 本机文件（本地音乐的 content://、下载的 file://）直接读：
                            // 走缓存只会把文件再抄一份进播放缓存，还挤掉在线歌曲的缓存
                            uri.scheme == "content" || uri.scheme == "file" ||
                                host == YUNTING_LIVE_HOST || (host.endsWith(".radio.cn") && uri.path.orEmpty().endsWith(".m3u8"))
                        },
                    )
                } ?: upstream,
            ).setDrmSessionManagerProvider { mediaItem ->
                val drm = mediaItem.localConfiguration?.drmConfiguration
                if (drm?.scheme != C.WIDEVINE_UUID) return@setDrmSessionManagerProvider DrmSessionManager.DRM_UNSUPPORTED
                val uri = drm.licenseUri?.toString().orEmpty()
                val params = AppleWidevineDrmCallback.parseQuery(uri)
                AppLog.i(
                    "PlaybackDrm",
                    "建 Widevine session manager adamId=${params["adamId"]} pssh=${params["pssh"]?.length ?: 0}B",
                )
                if (drmInfoLogged.compareAndSet(false, true)) {
                    // 真机上 4003 的排查线索：L1 设备会要求硬件安全解码器。
                    // 每个属性单独取，某个属性不支持不影响其它几项。
                    val secureAac = AppleWidevineDrmCallback.hasSecureDecoder("audio/mp4a-latm")
                    runCatching {
                        val md = androidx.media3.exoplayer.drm.FrameworkMediaDrm.newInstance(C.WIDEVINE_UUID)
                        try {
                            fun prop(name: String) = runCatching {
                                md.getPropertyString(name)
                            }.getOrElse { "<${it.message?.take(40)}>" }
                            AppLog.i(
                                "PlaybackDrm",
                                "CDM vendor=${prop("vendor")} version=${prop("version")} level=${prop("securityLevel")} " +
                                    "安全AAC解码器=$secureAac",
                            )
                        } finally {
                            md.release()
                        }
                    }.onFailure { AppLog.w("PlaybackDrm", "CDM 信息读取失败: ${it.message}") }
                }
                synchronized(drmLock) {
                    drmManagers.getOrPut(uri) {
                        DefaultDrmSessionManager.Builder()
                            // 清单里两把 key 各保各的段：默认 key 保开头 30s 左右，轮换 key
                            // 保正片。过界时两把 key 要同时在，所以开多会话。
                            .setMultiSession(true)
                            // L1 设备的明文只交给安全解码器，详见 provider 的注释
                            .setUuidAndExoMediaDrmProvider(
                                C.WIDEVINE_UUID,
                                AppleWidevineDrmCallback.SECURE_IF_AVAILABLE_PROVIDER,
                            )
                            .build(
                            AppleWidevineDrmCallback(
                                cookieProvider = { AppleSession.cookie },
                                adamId = params["adamId"].orEmpty(),
                                pssh = params["pssh"].orEmpty(),
                                isLibrary = params["library"] == "1",
                            ),
                        )
                    }
                }
            }
        mediaSourceFactory = AppleIntroMediaSourceFactory(baseFactory)
        val primary = newPlayer(SOURCE_PRIMARY)
        primary.setAudioAttributes(MEDIA_ATTRIBUTES, true)
        player = primary
        equalizer = app?.equalizer
        equalizer?.let { controller -> attachEqualizer(primary, controller) }
        app?.sleepTimer?.attach(primary)
        app?.let {
            crossfade = CrossfadeController(it, ::newPlayer, crossfadeParams) { next, slot -> promoteTo(next, slot) }
                .also { controller -> controller.attach(primary) }
        }
        // 通知栏卡片、锁屏和系统媒体面板点一下要能回到播放页；不给内容意图的话点了没反应
        val openPlayer = PendingIntent.getActivity(
            this,
            OPEN_PLAYER_REQUEST,
            WidgetLinks.intent(this, WidgetTarget.Player),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, primary)
            .setCallback(ResumptionCallback())
            .setSessionActivity(openPlayer)
            .build()
    }

    /**
     * 每个播放器都按同一套配置建：随机顺序、节拍分析的换歌通知、缓存 / 换源 / DRM 都一致。
     * [source] 决定哪一路的音频可以喂给律动分析——同时有两路在解时只能认当前出声的那一路。
     */
    private fun newPlayer(source: Int): ExoPlayer {
        val app = application as? MyMusicApp
        val builder = ExoPlayer.Builder(this).setAudioAttributes(
            MEDIA_ATTRIBUTES,
            // 预载的下一首不抢音频焦点：两路都申请的话，后起的那一路会把正在放的那一路暂停。
            // 换主播放器时再在 [promoteTo] 里打开。
            false,
        ).setHandleAudioBecomingNoisy(true)
            // 息屏后在线播放也要保持网络和 CPU，避免 Wi-Fi 休眠导致卡住
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(mediaSourceFactory)
        app?.let {
            builder.setRenderersFactory(
                RhythmRenderersFactory(this, it.rhythmAnalyzer, source, crossfadeParams[source]),
            )
        }
        val created = builder.build()
        // 随机顺序跟着队列改动走：随机时“下一首播放”照样下一首放，解析完换地址也不会跳到别的歌
        created.setShuffleOrder(QueueShuffleOrder())
        created.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                // 打开随机时从正在放的这首重新打乱；不然它可能排在随机顺序中间，不循环时排在它前面的就轮不到了
                if (shuffleModeEnabled) {
                    created.setShuffleOrder(QueueShuffleOrder().cloneAndSet(created.mediaItemCount, created.currentMediaItemIndex))
                }
            }
        })
        app?.rhythmAnalyzer?.let { analyzer ->
            created.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // 点歌、上一首 / 下一首、换了队列：渲染器的偏移不变，音频这边看着和同一首里跳转一样，得从这里告诉节拍跟踪。
                    // 自动接下一首由输出段偏移判断；单曲循环本来就该留着速度。
                    // 预载的那一路也在建队列，别拿它的换歌去清正在播的那一路的速度。
                    if (analyzer.isActiveSource(source) &&
                        (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                    ) {
                        analyzer.onNewSong()
                    }
                }
            })
        }
        return created
    }

    /** 交叉淡化结束：会话整体换到新播放器，音效、睡眠定时和律动分析跟着换过去。 */
    private fun promoteTo(next: ExoPlayer, slot: Int) {
        val previous = player
        player = next
        next.setAudioAttributes(MEDIA_ATTRIBUTES, true)
        session?.setPlayer(next)
        (application as? MyMusicApp)?.rhythmAnalyzer?.useSource(slot)
        equalizer?.let { controller -> attachEqualizer(next, controller) }
        (application as? MyMusicApp)?.sleepTimer?.attach(next)
        previous?.release()
    }

    private fun attachEqualizer(player: ExoPlayer, controller: EqualizerController) {
        val sessionId = player.audioSessionId
        if (sessionId != C.AUDIO_SESSION_ID_UNSET) controller.attach(sessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) controller.attach(audioSessionId)
            }
        })
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
        crossfade?.release()
        crossfade = null
        (application as? MyMusicApp)?.sleepTimer?.detach()
        equalizer?.detach()
        equalizer = null
        (application as? MyMusicApp)?.rhythmAnalyzer?.setActive(false)
        session?.release()
        session = null
        player?.release()
        player = null
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
        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
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

/**
 * Apple 整曲开头 30s 左右是另一把 key（默认 key），Media3 每份 HLS 清单只按一把 key 建
 * DRM 会话——两把混在一份清单里时整轨只用其中一把，另一段解成静音（实测）。Provider 因此
 * 把它单独写成一份清单，这里把两段拼成一个媒体项：播放位置、歌词、进度条仍然是一首歌。
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class AppleIntroMediaSourceFactory(
    private val base: MediaSource.Factory,
) : MediaSource.Factory {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val main = base.createMediaSource(mediaItem)
        val introUrl = mediaItem.mediaMetadata.extras
            ?.getString(AppleWidevineDrmCallback.EXTRA_DRM_INTRO_URL)
            ?.takeIf { it.isNotBlank() } ?: return main
        val drm = mediaItem.localConfiguration?.drmConfiguration ?: return main
        val adamId = AppleWidevineDrmCallback.parseQuery(drm.licenseUri?.toString().orEmpty())["adamId"].orEmpty()
        if (adamId.isBlank()) return main
        val introItem = mediaItem.buildUpon()
            .setUri(introUrl)
            // 去掉 extras，免得再拼一层
            .setMediaMetadata(MediaMetadata.Builder().build())
            .setDrmConfiguration(
                drm.buildUpon()
                    .setLicenseUri(
                        AppleWidevineDrmCallback.licenseUri(
                            adamId,
                            com.mytv.mymusic.data.native.AppleHlsManifest.defaultWidevineKeyUri(),
                        ),
                    )
                    .build(),
            )
            .build()
        return ConcatenatingMediaSource2.Builder()
            .add(base.createMediaSource(introItem))
            .add(main)
            .setMediaItem(mediaItem)
            .build()
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray = base.supportedTypes
}

@androidx.annotation.OptIn(UnstableApi::class)
private class RhythmRenderersFactory(
    context: Context,
    private val analyzer: RhythmAnalyzer,
    private val source: Int,
    private val crossfadeParams: CrossfadeParams,
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
                    TeeAudioProcessor(analyzer.sink(source)),
                    VolumeGainAudioProcessor(),
                    CrossfadeAudioProcessor(crossfadeParams),
                )
            )
            .build(),
        analyzer,
        source,
    )
}
