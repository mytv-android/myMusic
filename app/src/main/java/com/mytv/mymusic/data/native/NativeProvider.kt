package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
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

    suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> =
        throw UnsupportedOperationException("${info.name} 暂不支持专辑搜索")

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
