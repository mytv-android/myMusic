package com.mytv.mymusic.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.mytv.mymusic.MyMusicApp

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var equalizer: EqualizerController? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as? MyMusicApp
        val cache = app?.mediaCache
        val builder = ExoPlayer.Builder(this).setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        ).setHandleAudioBecomingNoisy(true)
            // 息屏后在线播放也要保持网络和 CPU，避免 Wi-Fi 休眠导致卡住
            .setWakeMode(C.WAKE_MODE_NETWORK)
        if (cache != null) {
            builder.setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(this))
                )
            )
        }
        val player = builder.build()
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
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        (application as? MyMusicApp)?.sleepTimer?.detach()
        equalizer?.detach()
        equalizer = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
