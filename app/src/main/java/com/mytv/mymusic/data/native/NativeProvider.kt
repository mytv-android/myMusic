package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.ListeningMode
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.QRCheckResult
import com.mytv.mymusic.data.QRCreateResult
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import java.io.File
import java.net.URI
import java.util.Locale

data class SongSearchPage(
    val songs: List<Song>,
    val page: Int,
    val hasMore: Boolean,
)

/** 解析出的播放流：地址之外带回真实音质，界面上的音质标签用它而不是搜索结果的估算值。 */
data class PlayableStream(
    val url: String,
    val ext: String = "",
    val bitrate: Int = 0,
    val level: String = "",
    /** 取流之后才知道只是试听片段（汽水会员曲只给出片段地址）；解析器按它继续尝试其他平台。 */
    val preview: Boolean = false,
    /** 取流响应里带回来的附加信息（响度 gain/peak 等），会合并进播放队列里的 Song.extra。 */
    val extra: Map<String, String> = emptyMap(),
)

/** 已登录账号的资料，用于账号卡片；拿不到就只显示“已登录”。 */
data class AccountInfo(val nickname: String = "", val avatar: String = "", val detail: String = "")

interface NativeProvider {
    val info: ProviderInfo
    val supportsSearchPagination: Boolean get() = false

    suspend fun searchSongs(cookie: String, keyword: String): List<Song> =
        throw UnsupportedOperationException("${info.name} 暂不支持搜索")

    suspend fun searchSongsPage(cookie: String, keyword: String, page: Int, limit: Int): SongSearchPage =
        SongSearchPage(searchSongs(cookie, keyword), page, false)

    suspend fun parseSong(cookie: String, link: String): Song =
        throw UnsupportedOperationException("${info.name} 暂不支持歌曲链接解析")

    suspend fun downloadUrl(cookie: String, song: Song): String =
        throw UnsupportedOperationException("${info.name} 暂不支持播放地址解析")

    /**
     * 交给播放器的地址。默认沿用 downloadUrl；需要下载后解密的平台（汽水加密流）
     * 会先把明文写到 [workDir] 并返回本地文件 URI。
     */
    suspend fun playableStream(cookie: String, song: Song, workDir: File): PlayableStream {
        val url = song.url.ifBlank { downloadUrl(cookie, song) }
        return PlayableStream(url = url, ext = song.ext.ifBlank { extensionFromUrl(url) }, bitrate = song.bitrate)
    }

    suspend fun lyrics(cookie: String, song: Song): String =
        throw UnsupportedOperationException("${info.name} 暂不支持歌词")

    suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> =
        throw UnsupportedOperationException("${info.name} 暂不支持歌单搜索")

    suspend fun playlistSongs(cookie: String, id: String): List<Song> =
        throw UnsupportedOperationException("${info.name} 暂不支持歌单歌曲")

    suspend fun parsePlaylist(cookie: String, link: String): CollectionResult =
        throw UnsupportedOperationException("${info.name} 暂不支持歌单链接解析")

    suspend fun recommendedPlaylists(cookie: String): List<Playlist> =
        throw UnsupportedOperationException("${info.name} 暂不支持推荐歌单")

    /** 听歌模式入口：场景 / 心情 / 风格驱动的推荐流，不是歌单。 */
    suspend fun listeningModes(cookie: String): List<ListeningMode> =
        throw UnsupportedOperationException("${info.name} 暂不支持听歌模式")

    /** 取某个听歌模式的一批歌曲；同一模式每次调用返回的歌曲不同。 */
    suspend fun listeningModeSongs(cookie: String, mode: ListeningMode): List<Song> =
        throw UnsupportedOperationException("${info.name} 暂不支持听歌模式")

    suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> =
        throw UnsupportedOperationException("${info.name} 暂不支持专辑搜索")

    suspend fun searchArtists(cookie: String, keyword: String): List<Playlist> =
        throw UnsupportedOperationException("${info.name} 暂不支持歌手搜索")

    /** 歌手的歌曲。搜索结果里带上的歌手条目用 Playlist 表示，id 即歌手 id。 */
    suspend fun artistSongs(cookie: String, id: String): List<Song> =
        throw UnsupportedOperationException("${info.name} 暂不支持歌手歌曲")

    suspend fun albumSongs(cookie: String, id: String): List<Song> =
        throw UnsupportedOperationException("${info.name} 暂不支持专辑歌曲")

    suspend fun parseAlbum(cookie: String, link: String): CollectionResult =
        throw UnsupportedOperationException("${info.name} 暂不支持专辑链接解析")

    suspend fun categories(cookie: String): List<PlaylistCategory> =
        throw UnsupportedOperationException("${info.name} 暂不支持歌单分类")

    suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> =
        throw UnsupportedOperationException("${info.name} 暂不支持分类歌单")

    suspend fun userPlaylists(cookie: String, page: Int, limit: Int): List<Playlist> =
        throw UnsupportedOperationException("${info.name} 暂不支持个人歌单")

    suspend fun topLists(cookie: String): List<TopList> =
        throw UnsupportedOperationException("${info.name} 暂不支持排行榜")

    suspend fun topListSongs(cookie: String, id: String): List<Song> =
        throw UnsupportedOperationException("${info.name} 暂不支持榜单歌曲")

    suspend fun hotSongs(cookie: String, limit: Int): List<Song> =
        throw UnsupportedOperationException("${info.name} 暂不支持热门单曲")

    suspend fun createLogin(type: String): QRCreateResult =
        throw UnsupportedOperationException("${info.name} 暂不支持扫码登录")

    suspend fun checkLogin(sessionId: String, key: String): QRCheckResult =
        throw UnsupportedOperationException("${info.name} 暂不支持扫码登录")

    /** 登录账号的昵称、头像、会员/等级；null 表示这个平台没有实现或资料不可用。 */
    suspend fun accountInfo(cookie: String): AccountInfo? = null

    /**
     * 这个歌单能不能用当前账号修改。null 表示平台没法判断（网络失败、未登录或没实现），
     * 调用方按“可以试”处理，写失败时再给原因。
     */
    suspend fun playlistWritable(cookie: String, id: String): Boolean? = null

    /** 往平台歌单里加歌；[songs] 必须已经是这个平台的曲目。 */
    suspend fun addPlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
        throw PlaylistWriteException("${info.name} 暂不支持修改歌单", PlaylistWriteFailure.Unsupported)
    }

    /** 从平台歌单里删歌；[songs] 必须已经是这个平台的曲目。 */
    suspend fun removePlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
        throw PlaylistWriteException("${info.name} 暂不支持修改歌单", PlaylistWriteFailure.Unsupported)
    }

    /** 把整张歌单按 [songs] 的新顺序写回平台；必须是歌单内的全部歌曲。 */
    suspend fun reorderPlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
        throw PlaylistWriteException("${info.name} 暂不支持调整歌单顺序", PlaylistWriteFailure.Unsupported)
    }
}

abstract class BaseNativeProvider(override val info: ProviderInfo) : NativeProvider

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")

/** 从播放地址路径里取音频扩展名；取不到或不是已知格式就返回空串。 */
internal fun extensionFromUrl(url: String): String {
    if (url.startsWith("file:")) {
        val name = runCatching { URI(url).path }.getOrNull().orEmpty().substringAfterLast('/')
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it in AUDIO_EXTENSIONS }.orEmpty()
    }
    val path = runCatching { URI(url).path }.getOrNull().orEmpty()
    return path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        .takeIf { it in AUDIO_EXTENSIONS }
        .orEmpty()
}
