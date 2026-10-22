package com.mytv.mymusic.data.sync

import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

const val SCHEMA = "mymusic.library"
const val SCHEMA_VERSION = 1

@Serializable
data class SyncSnapshot(
    val schema: String = SCHEMA,
    val version: Int = SCHEMA_VERSION,
    val deviceId: String = "",
    val updatedAt: String = SyncTime.nowIso(),
    val playlists: List<SyncPlaylist> = emptyList(),
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

fun snapshotSignature(snapshot: SyncSnapshot): String = snapshot.playlists
    .sortedBy { it.id }
    .joinToString("\n") { "${it.id}:${playlistSignature(it)}" }

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
