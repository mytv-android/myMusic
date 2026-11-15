package com.mytv.mymusic.lyricon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LyriconSubscriptionState(
    val supported: Boolean = true,
    val enabled: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val timedOut: Boolean = false,
    val providerPackageName: String = "",
    val playerPackageName: String = "",
    val song: LyriconReceivedSong? = null,
    val plainText: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val displayTranslation: Boolean = true,
    val displayRoma: Boolean = false,
) {
    val currentLine: LyriconReceivedLine?
        get() {
            val lines = song?.lyrics.orEmpty()
            if (lines.isEmpty()) return null
            val index = lines.indexOfLast { it.beginMs <= positionMs }
            return lines.getOrNull(if (index < 0) 0 else index)
        }
}

/** SDK 回调分散在 Binder 与 Default 调度线程；状态合并统一在这里串行化。 */
internal class LyriconSubscriptionStore(supported: Boolean) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(LyriconSubscriptionState(supported = supported))
    val state: StateFlow<LyriconSubscriptionState> = mutableState.asStateFlow()

    fun setEnabled(enabled: Boolean) = update { current ->
        val effective = enabled && current.supported
        if (effective) {
            current.copy(enabled = true, connecting = !current.connected, timedOut = false)
        } else {
            LyriconSubscriptionState(supported = current.supported)
        }
    }

    fun onConnected() = update { current ->
        if (!current.enabled) current else current.copy(connecting = false, connected = true, timedOut = false)
    }

    fun onDisconnected(timeout: Boolean = false) = update { current ->
        if (!current.enabled) return@update current
        current.copy(
            connecting = false,
            connected = false,
            timedOut = timeout,
            providerPackageName = "",
            playerPackageName = "",
            song = null,
            plainText = null,
            playing = false,
            positionMs = 0,
        )
    }

    fun onProvider(providerPackageName: String?, playerPackageName: String?) = update { current ->
        if (!current.enabled) return@update current
        current.copy(
            providerPackageName = providerPackageName.orEmpty(),
            playerPackageName = playerPackageName.orEmpty(),
            song = null,
            plainText = null,
            playing = false,
            positionMs = 0,
        )
    }

    fun onSong(song: LyriconReceivedSong?) = update { current ->
        if (!current.enabled) return@update current
        val changed = current.song?.id != song?.id || song == null
        current.copy(
            song = song,
            plainText = if (song == null) current.plainText else null,
            positionMs = if (changed) 0 else current.positionMs,
        )
    }

    fun onText(text: String?) = update { current ->
        if (!current.enabled) current else current.copy(plainText = text)
    }

    fun onPlaying(playing: Boolean) = update { current ->
        if (!current.enabled) current else current.copy(playing = playing)
    }

    fun onPosition(positionMs: Long, seek: Boolean = false) = update { current ->
        if (!current.enabled) return@update current
        val position = positionMs.coerceAtLeast(0)
        // Subscriber SDK 每 16ms 读一次共享内存；设置预览不需要按帧重组，但 seek 和回退必须立即校准。
        if (!seek && position >= current.positionMs && position - current.positionMs < POSITION_PREVIEW_STEP_MS) {
            current
        } else {
            current.copy(positionMs = position)
        }
    }

    fun onDisplayTranslation(display: Boolean) = update { current ->
        if (!current.enabled) current else current.copy(displayTranslation = display)
    }

    fun onDisplayRoma(display: Boolean) = update { current ->
        if (!current.enabled) current else current.copy(displayRoma = display)
    }

    private inline fun update(transform: (LyriconSubscriptionState) -> LyriconSubscriptionState) {
        synchronized(lock) { mutableState.value = transform(mutableState.value) }
    }

    private companion object {
        const val POSITION_PREVIEW_STEP_MS = 200L
    }
}
