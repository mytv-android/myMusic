package com.mytv.mymusic.data

import com.mytv.mymusic.data.lx.LxPlayback
import com.mytv.mymusic.data.lx.LxPlaybackResult
import com.mytv.mymusic.data.lx.LxSources
import com.mytv.mymusic.data.native.AccountInfo
import com.mytv.mymusic.data.native.AudioQuality
import com.mytv.mymusic.data.native.NativeProvider
import com.mytv.mymusic.data.native.NativeProviders
import com.mytv.mymusic.data.native.PlayableStream
import com.mytv.mymusic.data.native.SongSearchPage
import java.io.File

/** open 供测试替身覆写 playableStream，不发起真实网络请求。 */
open class MusicRepository(
    private val lxPlayback: LxPlayback = LxSources,
) {
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

    open suspend fun playableStream(
        song: Song,
        cookie: String,
        workDir: File,
        quality: AudioQuality = AudioQuality.Auto,
    ): PlayableStream = requireProvider(song.source).playableStream(cookie, song, workDir, quality)

    suspend fun downloadUrl(song: Song, cookie: String, quality: AudioQuality = AudioQuality.Auto): String =
        requireProvider(song.source).downloadUrl(cookie, song, quality)

    /** 丢掉平台上缓存的取流地址；下载撞到 403 以后要重新取一遍才有意义。 */
    fun invalidateStreamUrls(provider: String) {
        // 洛雪这类自定义源没有对应的 NativeProvider，跳过就行，别让重新取流因此失败
        NativeProviders.get(provider)?.invalidateStreamUrls()
    }

    /** 统一的洛雪播放候选：脚本按自定义音源顺序跨平台尝试，原生接口不在这里兜底。 */
    open suspend fun playableLxStream(songs: List<Song>, quality: AudioQuality): LxPlaybackResult? =
        lxPlayback.resolve(songs, quality.id)

    /** 当前账号在这个平台上实际能选的音质档，从高到低；空表表示只有“自动”。 */
    suspend fun availableQualityOptions(provider: String, cookie: String) =
        requireProvider(provider).availableQualityOptions(cookie)

    open fun supportsCustomSource(provider: String): Boolean = lxPlayback.supports(provider)
    open fun customSourceGeneration(): Long = lxPlayback.generation

    /** 洛雪上次取流失败的原因；换源面板和播放页的提示用。 */
    open fun customSourceFailure(): String = (lxPlayback as? LxSources)?.lastFailure.orEmpty()

    suspend fun lyrics(song: Song, cookie: String): String =
        requireProvider(song.source).lyrics(cookie, song)

    fun supportsComments(provider: String): Boolean =
        NativeProviders.get(provider)?.info?.comments == true

    suspend fun comments(song: Song, cookie: String, page: Int, limit: Int) =
        requireProvider(song.source).comments(cookie, song, page, limit)

    suspend fun searchPlaylists(provider: String, cookie: String, keyword: String): List<Playlist> =
        requireProvider(provider).searchPlaylists(cookie, keyword)

    suspend fun playlistSongs(provider: String, cookie: String, id: String): List<Song> =
        requireProvider(provider).playlistSongs(cookie, id)

    suspend fun parsePlaylist(provider: String, cookie: String, link: String): CollectionResult =
        requireProvider(provider).parsePlaylist(cookie, link)

    suspend fun recommended(provider: String, cookie: String): List<Playlist> =
        requireProvider(provider).recommendedPlaylists(cookie)

    suspend fun listeningModes(provider: String, cookie: String): List<ListeningMode> =
        requireProvider(provider).listeningModes(cookie)

    suspend fun listeningModeSongs(provider: String, cookie: String, mode: ListeningMode): List<Song> =
        requireProvider(provider).listeningModeSongs(cookie, mode)

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

    suspend fun artistDetail(provider: String, cookie: String, id: String): ArtistDetail =
        requireProvider(provider).artistDetail(cookie, id)

    suspend fun artistAlbums(provider: String, cookie: String, id: String, page: Int, limit: Int): ArtistAlbumPage =
        requireProvider(provider).artistAlbums(cookie, id, page, limit)

    suspend fun albumDetail(provider: String, cookie: String, id: String): AlbumDetail =
        requireProvider(provider).albumDetail(cookie, id)

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

    /** 放弃扫码登录：通知平台释放它占用的资源（隐藏 WebView、会话）。 */
    fun cancelLogin(sessionId: String) {
        NativeProviders.providerForSession(sessionId)?.cancelLogin(sessionId)
    }

    /** 登录账号资料；失败或平台没实现时返回 null。 */
    suspend fun accountInfo(provider: String, cookie: String): AccountInfo? =
        runCatching { requireProvider(provider).accountInfo(cookie) }.getOrNull()

    suspend fun playlistWritable(provider: String, cookie: String, id: String): Boolean? =
        requireProvider(provider).playlistWritable(cookie, id)

    suspend fun addPlaylistTracks(provider: String, cookie: String, playlistId: String, songs: List<Song>) =
        requireProvider(provider).addPlaylistTracks(cookie, playlistId, songs)

    suspend fun removePlaylistTracks(provider: String, cookie: String, playlistId: String, songs: List<Song>) =
        requireProvider(provider).removePlaylistTracks(cookie, playlistId, songs)

    suspend fun reorderPlaylistTracks(provider: String, cookie: String, playlistId: String, songs: List<Song>) =
        requireProvider(provider).reorderPlaylistTracks(cookie, playlistId, songs)

    /** 这个平台有没有账号侧收藏（汽水的「我喜欢的音乐」）；界面据此决定显不显示这一行。 */
    fun supportsCollect(provider: String): Boolean =
        NativeProviders.get(provider)?.supportsCollect() == true

    /** 收藏到平台账号。平台没实现时抛 [UnsupportedOperationException]，由界面兜成提示。 */
    suspend fun collectTrack(provider: String, cookie: String, song: Song) =
        requireProvider(provider).collectTrack(cookie, song)

    /** 从平台账号取消收藏。 */
    suspend fun uncollectTrack(provider: String, cookie: String, song: Song) =
        requireProvider(provider).uncollectTrack(cookie, song)

    fun providerInfo(provider: String): ProviderInfo? = NativeProviders.get(provider)?.info

    private fun requireProvider(id: String): NativeProvider =
        NativeProviders.get(id) ?: throw IllegalArgumentException("unsupported provider: $id")
}
