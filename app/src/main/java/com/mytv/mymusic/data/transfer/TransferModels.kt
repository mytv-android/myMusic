package com.mytv.mymusic.data.transfer

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.sync.LOCAL_TRACK_PROVIDER
import com.mytv.mymusic.data.sync.SyncLocal
import com.mytv.mymusic.data.sync.SyncMatch
import com.mytv.mymusic.data.sync.SyncTrackRefs
import com.mytv.mymusic.data.sync.isWebUrl
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** myMusic 歌单文件（`<歌单名>.mymusic.json`）：发给别人用的，不带 Cookie、设置和本地歌的文件路径。 */
const val PLAYLIST_FILE_TYPE = "mymusic.playlists"
const val PLAYLIST_FILE_VERSION = 1
const val PLAYLIST_FILE_SUFFIX = ".mymusic.json"
const val PLAYLIST_FILE_MIME = "application/json"

const val KIND_PLAYLIST = "playlist"

/** 同步页多选导出时勾上的「我的收藏」；导入时默认合并进收藏。 */
const val KIND_FAVORITES = "favorites"

@Serializable
data class MyMusicPlaylistFile(
    val type: String = PLAYLIST_FILE_TYPE,
    val version: Int = PLAYLIST_FILE_VERSION,
    /** ISO-8601。 */
    val exportedAt: String = "",
    /** 「myMusic 1.0 (12345)」，只作排查。 */
    val app: String = "",
    val playlists: List<PortablePlaylist> = emptyList(),
)

@Serializable
data class PortablePlaylist(
    val name: String,
    val kind: String = KIND_PLAYLIST,
    val createdAt: String? = null,
    /** 原来绑定的平台歌单：导入时只作说明，不绑定。 */
    val source: PortableSource? = null,
    val tracks: List<PortableTrack> = emptyList(),
) {
    val favorites: Boolean get() = kind == KIND_FAVORITES
}

@Serializable
data class PortableSource(val provider: String, val id: String)

/**
 * 文件里的一首。比同步文件的 SyncTrack 多 [extra] / [albumId] / [link]：酷狗 audio_id、咪咕 copyright_id 这些
 * 取流要用的字段都在 extra 里，少了导入后有的歌放不了。播放地址、大小、码率不写（地址有时效，其余播放时重取）。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PortableTrack(
    val provider: String,
    val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val albumId: String = "",
    val durationSec: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cover: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val link: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val extra: Map<String, String> = emptyMap(),
    /** 只有本地歌：本地身份和文件大小，不写文件名、路径和设备名。 */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val local: SyncLocal? = null,
    /** 只有本地歌：匹配到的平台和 id。 */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val match: SyncMatch? = null,
) {
    val isLocal: Boolean get() = provider == LOCAL_TRACK_PROVIDER

    fun toSong(): Song = Song(
        id = id,
        name = title,
        artist = artist,
        album = album,
        albumId = albumId,
        duration = durationSec,
        source = provider,
        cover = cover,
        link = link,
        extra = extra,
    )

    companion object {
        /** [refs] 是本地歌的线索（`LocalSyncRefs.snapshotRefs`），平台歌传 null。 */
        fun of(song: Song, refs: SyncTrackRefs? = null): PortableTrack {
            val local = song.source == LOCAL_TRACK_PROVIDER
            // 本地歌用匹配到的平台封面（同 toSyncTrack）；内嵌封面是本机缓存路径，出不了设备
            val cover = if (local) refs?.cover?.takeIf(::isWebUrl) ?: song.cover.takeIf(::isWebUrl).orEmpty()
            else song.cover.takeIf(::isWebUrl).orEmpty()
            return PortableTrack(
                provider = song.source,
                id = song.id,
                title = song.name,
                artist = song.artist,
                album = song.album,
                albumId = song.albumId,
                durationSec = song.duration.coerceAtLeast(0),
                cover = cover,
                link = song.link.takeIf(::isWebUrl).orEmpty(),
                // 本地歌的 extra 是文件名，不写
                extra = if (local) emptyMap() else portableExtra(song.extra),
                local = if (local) SyncLocal(hash = refs?.local?.hash?.ifBlank { null } ?: song.id, fileSize = refs?.local?.fileSize ?: 0) else null,
                match = if (local) refs?.match?.takeUnless { it.rejected || it.provider.isBlank() || it.id.isBlank() } else null,
            )
        }
    }
}

/** extra 里只在这台设备上有意义的值（本机路径、content:// 地址）去掉，其余全留。 */
internal fun portableExtra(extra: Map<String, String>): Map<String, String> =
    extra.filterValues { value ->
        !value.startsWith("file:", ignoreCase = true) &&
            !value.startsWith("content:", ignoreCase = true) &&
            !value.startsWith("/")
    }

/** 导出前算好的数：[total] 是歌单里的条目数，[kept] 是去重、能解出来的，真正写进文件的首数。 */
data class ExportCounts(
    val total: Int,
    val kept: Int,
    val byProvider: Map<String, Int>,
    val local: Int,
)

data class ExportFile(
    val fileName: String,
    val mime: String,
    val bytes: ByteArray,
    val counts: ExportCounts,
) {
    override fun equals(other: Any?): Boolean =
        other is ExportFile && other.fileName == fileName && other.mime == mime && other.bytes.contentEquals(bytes) && other.counts == counts

    override fun hashCode(): Int = (fileName.hashCode() * 31 + mime.hashCode()) * 31 + bytes.contentHashCode()
}

/** 要导出的一个歌单；[playlistId] 是 [FAVORITES_ID] 时是「我的收藏」。[unreadable] 是存档坏了解不出来的条目。 */
data class ExportList(
    val playlistId: String,
    val name: String,
    val songs: List<Song>,
    val createdAt: Long = 0L,
    val source: PortableSource? = null,
    val unreadable: Int = 0,
) {
    val favorites: Boolean get() = playlistId == FAVORITES_ID

    companion object {
        const val FAVORITES_ID = "@favorites"
        const val FAVORITES_NAME = "我的收藏"
    }
}

/** 导出面板打开时算一次。 */
data class ExportPreview(
    val title: String,
    val lists: List<ExportList>,
    val counts: ExportCounts,
    val fileName: String,
)

/** 导入时导不了的原因。 */
enum class SkipReason(val label: String) {
    UnknownSource("本应用没有这个平台"),
    MissingId("缺少歌曲 id"),
    LocalNotFound("本机没找到同名本地歌"),
    BadFormat("格式不对"),
    Duplicate("重复"),
}
