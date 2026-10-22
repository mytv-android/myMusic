package com.mytv.mymusic.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProviderInfo(
    @SerialName("ID") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Search") val search: Boolean = false,
    @SerialName("Download") val download: Boolean = false,
    @SerialName("Lyrics") val lyrics: Boolean = false,
    @SerialName("ParseSong") val parseSong: Boolean = false,
    @SerialName("SearchPlaylist") val searchPlaylist: Boolean = false,
    @SerialName("RecommendedPlaylists") val recommendedPlaylists: Boolean = false,
    @SerialName("PlaylistSongs") val playlistSongs: Boolean = false,
    @SerialName("ParsePlaylist") val parsePlaylist: Boolean = false,
    @SerialName("SearchAlbum") val searchAlbum: Boolean = false,
    @SerialName("AlbumSongs") val albumSongs: Boolean = false,
    @SerialName("ParseAlbum") val parseAlbum: Boolean = false,
    @SerialName("UserPlaylists") val userPlaylists: Boolean = false,
    @SerialName("QRLogin") val qrLogin: Boolean = false,
    @SerialName("PlaylistCategories") val playlistCategories: Boolean = false,
    @SerialName("TopLists") val topLists: Boolean = false,
    @SerialName("TopListSongs") val topListSongs: Boolean = false,
    @SerialName("HotSongs") val hotSongs: Boolean = false,
    @SerialName("Unstable") val unstable: String = "",
    @SerialName("Note") val note: String = "",
)

@Serializable
data class Song(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val album: String = "",
    @SerialName("album_id") val albumId: String = "",
    val duration: Int = 0,
    val size: Long = 0,
    val bitrate: Int = 0,
    val source: String = "",
    val url: String = "",
    val ext: String = "",
    val cover: String = "",
    val link: String = "",
    val extra: Map<String, String> = emptyMap(),
    @SerialName("is_invalid") val isInvalid: Boolean = false,
    @SerialName("is_vip") val isVip: Boolean = false,
) {
    val key get() = "$source:$id"
    val title get() = name.ifBlank { "未命名歌曲" }
    val subtitle get() = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
}

@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val cover: String = "",
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("play_count") val playCount: Long = 0,
    val creator: String = "",
    val description: String = "",
    val source: String = "",
    val link: String = "",
    val extra: Map<String, String> = emptyMap(),
)

@Serializable
data class TopList(
    val id: String = "",
    val name: String = "",
    val cover: String = "",
    val description: String = "",
    @SerialName("update_frequency") val updateFrequency: String = "",
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("play_count") val playCount: Long = 0,
    val source: String = "",
    val link: String = "",
    val extra: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class PlaylistCategory(
    val id: String = "",
    val name: String = "",
    val group: String = "",
    val source: String = "",
    val count: Int = 0,
    val hot: Boolean = false,
)

@Serializable
data class CollectionResult(val item: Playlist = Playlist(), val songs: List<Song> = emptyList())

@Serializable
data class QRSession(val key: String = "", val url: String = "", @SerialName("image_url") val imageUrl: String = "")

@Serializable
data class QRCreateResult(@SerialName("session_id") val sessionId: String = "", val session: QRSession = QRSession())

@Serializable
data class QRCheckResult(val status: String = "", val message: String = "", val cookie: String = "")

enum class RepeatMode { Off, All, One }
enum class SearchKind(val label: String) { Song("单曲"), Playlist("歌单"), Album("专辑"), Link("链接") }
