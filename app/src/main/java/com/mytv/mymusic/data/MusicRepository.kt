package com.mytv.mymusic.data

import com.mytv.mymusic.data.native.AccountInfo
import com.mytv.mymusic.data.native.NativeProvider
import com.mytv.mymusic.data.native.NativeProviders
import com.mytv.mymusic.data.native.PlayableStream
import com.mytv.mymusic.data.native.SongSearchPage
import java.io.File

class MusicRepository {
    suspend fun providers(): List<ProviderInfo> = NativeProviders.infos()

    suspend fun searchSongs(provider: String, cookie: String, keyword: String): List<Song> =
        requireProvider(provider).searchSongs(cookie, keyword)

    suspend fun searchSongsPage(
        provider: String,
        cookie: String,
        keyword: String,
        page: Int,
        limit: Int,
    ): SongSearchPage = requireProvider(provider).searchSongsPage(cookie, keyword, page, limit)

    fun supportsSearchPagination(provider: String): Boolean =
        NativeProviders.get(provider)?.supportsSearchPagination == true

    suspend fun parseSong(provider: String, cookie: String, link: String): Song =
        requireProvider(provider).parseSong(cookie, link)

    suspend fun playableStream(song: Song, cookie: String, workDir: File): PlayableStream =
        requireProvider(song.source).playableStream(cookie, song, workDir)

    suspend fun lyrics(song: Song, cookie: String): String =
        requireProvider(song.source).lyrics(cookie, song)

    suspend fun searchPlaylists(provider: String, cookie: String, keyword: String): List<Playlist> =
        requireProvider(provider).searchPlaylists(cookie, keyword)

    suspend fun playlistSongs(provider: String, cookie: String, id: String): List<Song> =
        requireProvider(provider).playlistSongs(cookie, id)

    suspend fun parsePlaylist(provider: String, cookie: String, link: String): CollectionResult =
        requireProvider(provider).parsePlaylist(cookie, link)

    suspend fun recommended(provider: String, cookie: String): List<Playlist> =
        requireProvider(provider).recommendedPlaylists(cookie)

    suspend fun searchAlbums(provider: String, cookie: String, keyword: String): List<Playlist> =
        requireProvider(provider).searchAlbums(cookie, keyword)

    suspend fun searchArtists(provider: String, cookie: String, keyword: String): List<Playlist> =
        requireProvider(provider).searchArtists(cookie, keyword)

    suspend fun artistSongs(provider: String, cookie: String, id: String): List<Song> =
        requireProvider(provider).artistSongs(cookie, id)

    suspend fun albumSongs(provider: String, cookie: String, id: String): List<Song> =
        requireProvider(provider).albumSongs(cookie, id)

    suspend fun parseAlbum(provider: String, cookie: String, link: String): CollectionResult =
        requireProvider(provider).parseAlbum(cookie, link)

    suspend fun categories(provider: String, cookie: String): List<PlaylistCategory> =
        requireProvider(provider).categories(cookie)

    suspend fun categoryPlaylists(provider: String, cookie: String, id: String, page: Int, limit: Int): List<Playlist> =
        requireProvider(provider).categoryPlaylists(cookie, id, page, limit)

    suspend fun userPlaylists(provider: String, cookie: String, page: Int, limit: Int): List<Playlist> =
        requireProvider(provider).userPlaylists(cookie, page, limit)

    suspend fun topLists(provider: String, cookie: String): List<TopList> =
        requireProvider(provider).topLists(cookie)

    suspend fun topListSongs(provider: String, cookie: String, id: String): List<Song> =
        requireProvider(provider).topListSongs(cookie, id)

    suspend fun hotSongs(provider: String, cookie: String, limit: Int): List<Song> =
        requireProvider(provider).hotSongs(cookie, limit)

    suspend fun createLogin(provider: String, type: String): QRCreateResult =
        requireProvider(provider).createLogin(type)

    suspend fun checkLogin(sessionId: String, key: String): QRCheckResult {
        val provider = NativeProviders.providerForSession(sessionId)
            ?: throw IllegalArgumentException("unknown login session: $sessionId")
        return provider.checkLogin(sessionId, key)
    }

    /** 登录账号资料；失败或平台没实现时返回 null。 */
    suspend fun accountInfo(provider: String, cookie: String): AccountInfo? =
        runCatching { requireProvider(provider).accountInfo(cookie) }.getOrNull()

    private fun requireProvider(id: String): NativeProvider =
        NativeProviders.get(id) ?: throw IllegalArgumentException("unsupported provider: $id")
}
