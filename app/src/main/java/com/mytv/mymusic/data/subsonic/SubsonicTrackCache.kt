package com.mytv.mymusic.data.subsonic

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.mytv.mymusic.data.Song
import kotlinx.serialization.json.Json

@Entity(
    tableName = "subsonic_tracks",
    primaryKeys = ["account_id", "song_id"],
)
data class SubsonicTrackEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "album_id") val albumId: String,
    val duration: Int,
    val size: Long,
    val bitrate: Int,
    val suffix: String,
    @ColumnInfo(name = "cover_art") val coverArt: String,
    @ColumnInfo(name = "extra_json") val extraJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface SubsonicTrackDao {
    @Upsert
    suspend fun putAll(tracks: List<SubsonicTrackEntity>)

    @Query(
        """
        SELECT * FROM subsonic_tracks
        WHERE account_id = :accountId
          AND (title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%')
        ORDER BY title COLLATE NOCASE, artist COLLATE NOCASE
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun search(accountId: String, query: String, limit: Int, offset: Int): List<SubsonicTrackEntity>

    @Query("SELECT * FROM subsonic_tracks WHERE account_id = :accountId ORDER BY title COLLATE NOCASE LIMIT :limit OFFSET :offset")
    suspend fun list(accountId: String, limit: Int, offset: Int): List<SubsonicTrackEntity>

    @Query("DELETE FROM subsonic_tracks WHERE account_id = :accountId")
    suspend fun deleteAccount(accountId: String)

    @Query("DELETE FROM subsonic_tracks WHERE updated_at < :cutoff")
    suspend fun prune(cutoff: Long)
}

class SubsonicTrackCache(
    private val dao: SubsonicTrackDao,
    private val account: SubsonicAccount,
    private val covers: SubsonicCoverCache?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun putAll(songs: List<Song>) {
        if (songs.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.putAll(
            songs.map { song ->
                SubsonicTrackEntity(
                    accountId = account.id,
                    songId = song.id,
                    title = song.name,
                    artist = song.artist,
                    album = song.album,
                    albumId = song.albumId,
                    duration = song.duration,
                    size = song.size,
                    bitrate = song.bitrate,
                    suffix = song.ext,
                    coverArt = song.extra["subsonic_cover"].orEmpty(),
                    extraJson = json.encodeToString(song.extra),
                    updatedAt = now,
                )
            },
        )
    }

    suspend fun search(query: String, limit: Int, offset: Int): List<Song> =
        dao.search(account.id, query, limit, offset).map(::song)

    suspend fun list(limit: Int, offset: Int): List<Song> = dao.list(account.id, limit, offset).map(::song)

    private fun song(row: SubsonicTrackEntity): Song {
        val extra = runCatching {
            json.decodeFromString<Map<String, String>>(row.extraJson)
        }.getOrDefault(emptyMap()) + mapOf("subsonic_cover" to row.coverArt)
        return Song(
            id = row.songId,
            name = row.title,
            artist = row.artist,
            album = row.album,
            albumId = row.albumId,
            duration = row.duration,
            size = row.size,
            bitrate = row.bitrate,
            source = account.providerId,
            ext = row.suffix,
            cover = covers?.uri(account.id, row.coverArt).orEmpty(),
            extra = extra,
        )
    }
}
