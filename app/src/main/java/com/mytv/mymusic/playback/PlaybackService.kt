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
import android.content.Context
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
        app?.let { builder.setRenderersFactory(RhythmRenderersFactory(this, it.rhythmAnalyzer)) }
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
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        (application as? MyMusicApp)?.sleepTimer?.detach()
        equalizer?.detach()
        equalizer = null
        (application as? MyMusicApp)?.rhythmAnalyzer?.setActive(false)
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
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
            .setAudioProcessors(arrayOf(TeeAudioProcessor(analyzer.audioBufferSink)))
            .build(),
        analyzer,
    )
}
