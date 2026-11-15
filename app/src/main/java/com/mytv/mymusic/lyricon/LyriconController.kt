package com.mytv.mymusic.lyricon

import android.content.Context
import android.os.Build
import io.github.proify.lyricon.lyric.model.Song as RemoteSong
import io.github.proify.lyricon.provider.LyriconFactory as ProviderFactory
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.LyriconFactory as SubscriberFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import kotlinx.coroutines.flow.StateFlow

/** 应用级 Lyricon 双端连接；Provider 只在主线程由 MusicViewModel 驱动。 */
internal class LyriconController(context: Context) {
    private val appContext = context.applicationContext
    private val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
    private val provider = ProviderFactory.createProvider(appContext).apply {
        autoSync = true
        // 与应用内逐字歌词使用同一档 100ms 时钟；SharedMemory 写入不涉及逐次 Binder 调用。
        player.setPositionUpdateInterval(POSITION_UPDATE_INTERVAL_MS)
    }
    private val subscriber = SubscriberFactory.createSubscriber(appContext)
    private val subscriptionStore = LyriconSubscriptionStore(supported)
    val subscription: StateFlow<LyriconSubscriptionState> = subscriptionStore.state

    private var destroyed = false
    private var providerEnabled = false
    private var providerRegistered = false
    private var subscriberEnabled = false
    private var subscriberRegistered = false
    private var hasPushedSnapshot = false
    private var currentSong: RemoteSong? = null
    private var pushedSong: RemoteSong? = null
    private var currentPosition = 0L
    private var currentPlaying = false
    private var pushedPlaying = false
    private var displayTranslation = true
    private var displayRoma = false
    private var pushedTranslation = true
    private var pushedRoma = false

    private val connectionListener = object : ConnectionListener {
        override fun onConnected(subscriber: LyriconSubscriber) = subscriptionStore.onConnected()
        override fun onReconnected(subscriber: LyriconSubscriber) = subscriptionStore.onConnected()
        override fun onDisconnected(subscriber: LyriconSubscriber) = subscriptionStore.onDisconnected()
        override fun onConnectTimeout(subscriber: LyriconSubscriber) = subscriptionStore.onDisconnected(timeout = true)
    }

    // 0.1.70 的文档给出了 SimpleActivePlayerListener，但 AAR 未包含该适配器，直接实现完整接口。
    private val activePlayerListener = object : ActivePlayerListener {
        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            subscriptionStore.onProvider(providerInfo?.providerPackageName, providerInfo?.playerPackageName)
        }

        override fun onSongChanged(song: RemoteSong?) {
            subscriptionStore.onSong(song?.let(LyriconMapper::fromRemoteSong))
        }

        override fun onReceiveText(text: String?) = subscriptionStore.onText(text)
        override fun onPlaybackStateChanged(isPlaying: Boolean) = subscriptionStore.onPlaying(isPlaying)
        override fun onPositionChanged(position: Long) = subscriptionStore.onPosition(position)
        override fun onSeekTo(position: Long) = subscriptionStore.onPosition(position, seek = true)
        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) =
            subscriptionStore.onDisplayTranslation(isDisplayTranslation)
        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = subscriptionStore.onDisplayRoma(isDisplayRoma)
    }

    init {
        subscriber.addConnectionListener(connectionListener)
        subscriber.subscribeActivePlayer(activePlayerListener)
    }

    fun update(update: LyriconProviderUpdate) {
        if (destroyed) return
        val previousSongId = currentSong?.id
        currentSong = update.song
        currentPlaying = update.playing && update.song != null
        displayTranslation = update.displayTranslation
        displayRoma = update.displayRoma
        updateSubscriber(update.subscriberEnabled)

        val wasEnabled = providerEnabled
        providerEnabled = update.providerEnabled && supported
        if (!providerEnabled) {
            if (wasEnabled) stopProvider()
            return
        }
        if (!providerRegistered) {
            // 只记注册请求已经发出；连接与超时重试由 SDK 管理，不能拿返回值当“已经显示”。
            providerRegistered = true
            provider.register()
        }
        pushProvider(force = !wasEnabled, seek = previousSongId != currentSong?.id)
    }

    fun updatePosition(positionMs: Long) {
        if (destroyed) return
        val position = positionMs.coerceAtLeast(0)
        if (position == currentPosition) return
        currentPosition = position
        if (providerEnabled && providerRegistered && hasPushedSnapshot && currentSong != null) {
            provider.player.setPosition(position)
        }
    }

    private fun pushProvider(force: Boolean, seek: Boolean) {
        if (!providerRegistered) return
        val first = force || !hasPushedSnapshot
        val songChanged = first || pushedSong != currentSong
        if (songChanged) provider.player.setSong(currentSong)
        if (currentSong != null && songChanged) {
            if (seek) provider.player.seekTo(currentPosition) else provider.player.setPosition(currentPosition)
        }
        if (first || pushedPlaying != currentPlaying) provider.player.setPlaybackState(currentPlaying)
        if (first || pushedTranslation != displayTranslation) {
            provider.player.setDisplayTranslation(displayTranslation)
        }
        if (first || pushedRoma != displayRoma) provider.player.setDisplayRoma(displayRoma)
        pushedSong = currentSong
        pushedPlaying = currentPlaying
        pushedTranslation = displayTranslation
        pushedRoma = displayRoma
        hasPushedSnapshot = true
    }

    private fun stopProvider() {
        if (providerRegistered) {
            provider.player.setSong(null)
            provider.unregister()
        }
        providerRegistered = false
        hasPushedSnapshot = false
        pushedSong = null
    }

    private fun updateSubscriber(enabled: Boolean) {
        val effective = enabled && supported
        if (effective == subscriberEnabled) return
        subscriberEnabled = effective
        subscriptionStore.setEnabled(effective)
        if (effective) {
            if (!subscriberRegistered) {
                subscriberRegistered = true
                subscriber.register()
            }
        } else if (subscriberRegistered) {
            subscriber.unregister()
            subscriberRegistered = false
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        stopProvider()
        subscriptionStore.setEnabled(false)
        if (subscriberRegistered) subscriber.unregister()
        subscriberRegistered = false
        subscriber.unsubscribeActivePlayer(activePlayerListener)
        subscriber.removeConnectionListener(connectionListener)
        subscriber.destroy()
        provider.destroy()
    }

    private companion object {
        const val POSITION_UPDATE_INTERVAL_MS = 100
    }
}
