package com.mytv.mymusic.data.sync

import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.SavedSongTombstone
import com.mytv.mymusic.data.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

const val SCHEMA = "mymusic.library"
const val SCHEMA_VERSION = 2

@Serializable
data class SyncSnapshot(
    val schema: String = SCHEMA,
    val version: Int = SCHEMA_VERSION,
    val deviceId: String = "",
    val updatedAt: String = SyncTime.nowIso(),
    val playlists: List<SyncPlaylist> = emptyList(),
    val favorites: List<SyncFavorite> = emptyList(),
    val cookies: List<SyncCookie> = emptyList(),
)

@Serializable
data class SyncPlaylist(
    val id: String,
    val name: String,
    val createdAt: String = SyncTime.nowIso(),
    val updatedAt: String = SyncTime.nowIso(),
    val deletedAt: String? = null,
    val tracks: List<SyncTrack> = emptyList(),
)

@Serializable
data class SyncTrack(
    val key: String,
    val provider: String = "",
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSec: Int = 0,
    val cover: String = "",
) {
    fun toSong(): Song = Song(
        id = id,
        name = title,
        artist = artist,
        album = album,
        duration = durationSec,
        source = provider,
        cover = cover,
    )
}

/** 收藏歌曲；deletedAt 非空表示取消收藏的墓碑。 */
@Serializable
data class SyncFavorite(
    val key: String,
    val addedAt: String = SyncTime.nowIso(),
    val deletedAt: String? = null,
    val track: SyncTrack? = null,
)

/** 平台账号 Cookie；deletedAt 非空表示退出登录的墓碑。 */
@Serializable
data class SyncCookie(
    val provider: String,
    val value: String = "",
    val updatedAt: String = SyncTime.nowIso(),
    val deletedAt: String? = null,
)

fun SyncFavorite.changeTimeMillis(): Long = SyncTime.toMillis(deletedAt ?: addedAt)

fun SyncCookie.changeTimeMillis(): Long =
    maxOf(SyncTime.toMillis(updatedAt), SyncTime.toMillis(deletedAt))

object SyncJson {
    val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(snapshot: SyncSnapshot): String = format.encodeToString(snapshot)

    fun decode(raw: String): SyncSnapshot {
        val snapshot = runCatching { format.decodeFromString<SyncSnapshot>(raw) }
            .getOrElse { throw SyncException(SyncFailureKind.Corrupt, "同步文件无法读取，本机数据没有改动。") }
        if (snapshot.schema != SCHEMA) {
            throw SyncException(SyncFailureKind.Corrupt, "同步文件格式不受支持：${snapshot.schema}")
        }
        if (snapshot.version > SCHEMA_VERSION) {
            throw SyncException(SyncFailureKind.Corrupt, "同步文件版本高于当前应用，请先更新应用。")
        }
        return snapshot
    }

    fun encodePlaylist(playlist: SyncPlaylist?): String =
        if (playlist == null) "null" else format.encodeToString(playlist)

    fun decodePlaylist(raw: String): SyncPlaylist? =
        if (raw == "null") null else runCatching { format.decodeFromString<SyncPlaylist>(raw) }.getOrNull()
}

object SyncTime {
    fun nowIso(): String = Instant.ofEpochMilli(System.currentTimeMillis()).toString()

    fun toIso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    fun toMillis(value: String?): Long = runCatching { Instant.parse(value.orEmpty()).toEpochMilli() }.getOrDefault(0L)
}

fun buildSnapshot(
    playlists: List<PlaylistEntity>,
    entries: List<PlaylistEntryEntity>,
    deviceId: String,
    includeDeleted: Boolean = true,
    favorites: List<SavedSong> = emptyList(),
    favoriteTombstones: List<SavedSongTombstone> = emptyList(),
    cookies: Map<String, String> = emptyMap(),
    cookieUpdatedAt: Map<String, Long> = emptyMap(),
): SyncSnapshot {
    val entriesByPlaylist = entries.groupBy { it.playlistId }
    return SyncSnapshot(
        deviceId = deviceId,
        playlists = playlists
            .filter { includeDeleted || it.deletedAt == null }
            .map { playlist ->
                val tracks = if (playlist.deletedAt != null) emptyList() else {
                    entriesByPlaylist[playlist.id].orEmpty()
                        .sortedBy { it.position }
                        .mapNotNull { entry -> entry.toSyncTrack() }
                }
                SyncPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    createdAt = SyncTime.toIso(playlist.createdAt),
                    updatedAt = SyncTime.toIso(playlist.updatedAt),
                    deletedAt = playlist.deletedAt?.let(SyncTime::toIso),
                    tracks = tracks,
                )
            },
        favorites = favorites.mapNotNull { it.toSyncFavorite() } +
            favoriteTombstones
                .filter { tombstone -> favorites.none { it.key == tombstone.key } }
                .filter { includeDeleted }
                .map { tombstone ->
                    SyncFavorite(
                        key = tombstone.key,
                        addedAt = SyncTime.toIso(tombstone.deletedAt),
                        deletedAt = SyncTime.toIso(tombstone.deletedAt),
                    )
                },
        cookies = cookies.entries
            .map { (provider, value) ->
                SyncCookie(
                    provider = provider,
                    value = value,
                    updatedAt = SyncTime.toIso(cookieUpdatedAt[provider] ?: 0L),
                )
            } + cookieUpdatedAt
            .filterKeys { it !in cookies }
            .map { (provider, time) ->
                SyncCookie(
                    provider = provider,
                    value = "",
                    updatedAt = SyncTime.toIso(time),
                    deletedAt = SyncTime.toIso(time),
                )
            }
            .filter { includeDeleted },
    )
}

private fun SavedSong.toSyncFavorite(): SyncFavorite? {
    val song = runCatching { entryJson.decodeFromString<Song>(payload) }.getOrNull() ?: return null
    return SyncFavorite(
        key = key,
        addedAt = SyncTime.toIso(updatedAt),
        track = SyncTrack(
            key = song.key,
            provider = song.source,
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationSec = song.duration,
            cover = song.cover,
        ),
    )
}

private val entryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun PlaylistEntryEntity.toSyncTrack(): SyncTrack? {
    val song = runCatching { entryJson.decodeFromString<Song>(payload) }.getOrNull() ?: return null
    return SyncTrack(
        key = song.key,
        provider = song.source,
        id = song.id,
        title = song.title,
        artist = song.artist,
        album = song.album,
        durationSec = song.duration,
        cover = song.cover,
    )
}

fun SyncTrack.toSongStub(): Song = toSong()

fun playlistSignature(playlist: SyncPlaylist?): String {
    if (playlist == null) return "null"
    return buildString {
        append(playlist.name).append('|')
        append(playlist.deletedAt.orEmpty()).append('|')
        playlist.tracks.forEach { append(it.key).append(',').append(it.title).append(',').append(it.artist).append(';') }
    }
}

fun favoriteSignature(favorite: SyncFavorite?): String {
    if (favorite == null) return "null"
    return buildString {
        append(favorite.key).append('|')
        append(favorite.addedAt).append('|')
        append(favorite.deletedAt.orEmpty()).append('|')
        favorite.track?.let { append(it.title).append(',').append(it.artist) }
    }
}

fun cookieSignature(cookie: SyncCookie?): String {
    if (cookie == null) return "null"
    if (cookie.deletedAt != null || cookie.value.isBlank()) return "${cookie.provider}|deleted"
    return "${cookie.provider}|${sha256(cookie.value.toByteArray(Charsets.UTF_8))}"
}

fun snapshotSignature(snapshot: SyncSnapshot): String = buildString {
    append(snapshot.playlists.sortedBy { it.id }.joinToString("\n") { "${it.id}:${playlistSignature(it)}" })
    append("\n--favorites--\n")
    append(snapshot.favorites.sortedBy { it.key }.joinToString("\n") { "${it.key}:${favoriteSignature(it)}" })
    append("\n--cookies--\n")
    append(snapshot.cookies.sortedBy { it.provider }.joinToString("\n") { "${it.provider}:${cookieSignature(it)}" })
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
