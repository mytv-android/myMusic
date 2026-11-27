package com.mytv.mymusic.data

import com.mytv.mymusic.data.native.NativeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
enum class FavoriteSyncMode { Default, Playlist, LocalOnly }

@Serializable
data class FavoriteSyncPreference(
    val mode: FavoriteSyncMode = FavoriteSyncMode.Default,
    val playlistId: String = "",
    val playlistName: String = "",
    val credentialFingerprint: String = "",
) {
    fun matches(cookie: String): Boolean =
        cookie.isNotBlank() && credentialFingerprint == favoriteCredentialFingerprint(cookie)
}

internal fun favoriteCredentialFingerprint(cookie: String): String =
    MessageDigest.getInstance("SHA-256").digest(cookie.toByteArray()).toHexString()

internal class FavoriteSyncIssue(message: String) : IllegalStateException(message)

internal fun NativeProvider.canChooseFavoritePlaylist(): Boolean = info.userPlaylists && info.playlistWrite

internal suspend fun NativeProvider.favoriteSyncPlaylists(cookie: String): List<Playlist> {
    val candidates = linkedMapOf<String, Playlist>()
    val pageSize = 100
    var page = 1
    while (true) {
        val batch = userPlaylists(cookie, page++, pageSize)
        val previousSize = candidates.size
        batch.filter { it.source == info.id && it.id.isNotBlank() && it.name.isNotBlank() }
            .forEach { candidates.putIfAbsent(it.id, it) }
        if (batch.size < pageSize || candidates.size == previousSize) break
    }
    return candidates.values.filter { playlist ->
        playlistWritableFromAccountList(playlist) ?: (playlistWritable(cookie, playlist.id) == true)
    }
}

internal class FavoriteSyncWrite(
    val provider: NativeProvider,
    val cookie: String,
    private val preference: FavoriteSyncPreference,
) {
    suspend fun apply(song: Song, add: Boolean, accountIsCurrent: suspend () -> Boolean): String? {
        require(song.source == provider.info.id && song.id.isNotBlank() && !song.isLive)
        if (!accountIsCurrent()) throw FavoriteSyncIssue("账号有变化，请检查收藏同步设置")
        if (cookie.isBlank()) throw FavoriteSyncIssue("请先登录${provider.info.name}")
        if (preference.mode == FavoriteSyncMode.Default) {
            return if (add) provider.collectTrack(cookie, song) else provider.uncollectTrack(cookie, song)
        }

        if (!preference.matches(cookie) || !provider.canChooseFavoritePlaylist() || preference.playlistId.isBlank()) {
            throw FavoriteSyncIssue("请在收藏同步设置中重新选择歌单")
        }
        val id = preference.playlistId
        when (provider.playlistWritable(cookie, id)) {
            false -> throw FavoriteSyncIssue("目标歌单已不可修改，请重新选择")
            null -> throw FavoriteSyncIssue("无法确认目标歌单可写，请检查网络和登录")
            true -> Unit
        }
        // 检查权限要联网，返回时账号可能已经切换。
        if (!accountIsCurrent()) throw FavoriteSyncIssue("账号有变化，请重新选择收藏歌单")
        if (add) provider.addPlaylistTracks(cookie, id, listOf(song))
        else provider.removePlaylistTracks(cookie, id, listOf(song))
        return id
    }

    companion object {
        fun forSong(song: Song, provider: NativeProvider?, cookie: String, preference: FavoriteSyncPreference): FavoriteSyncWrite? {
            if (provider == null || song.source != provider.info.id || song.id.isBlank() || song.isLive) return null
            return when (preference.mode) {
                FavoriteSyncMode.LocalOnly -> null
                FavoriteSyncMode.Default -> if (provider.supportsCollect()) FavoriteSyncWrite(provider, cookie, preference) else null
                FavoriteSyncMode.Playlist -> FavoriteSyncWrite(provider, cookie, preference)
            }
        }
    }
}

/** 由 ViewModel 主线程调用，连续操作同一首歌时按本机操作的顺序写平台。 */
internal class FavoriteWriteQueue(private val scope: CoroutineScope) {
    private val pending = mutableMapOf<String, Job>()

    fun enqueue(songKey: String, write: suspend () -> Unit): Job {
        val previous = pending[songKey]
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                write()
            } finally {
                if (pending[songKey] === coroutineContext[Job]) pending.remove(songKey)
            }
        }
        pending[songKey] = job
        job.start()
        return job
    }
}
