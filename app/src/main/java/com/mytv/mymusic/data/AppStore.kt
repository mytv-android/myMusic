package com.mytv.mymusic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("mymusic")

class SettingsStore(private val context: Context) {
    private val provider = stringPreferencesKey("provider")
    private val theme = stringPreferencesKey("theme")
    private val cookies = stringPreferencesKey("cookies")
    val state: Flow<AppSettings> = context.dataStore.data.map {
        AppSettings(
            provider = it[provider] ?: "netease",
            theme = it[theme] ?: "system",
            cookies = Json.decodeFromString(it[cookies] ?: "{}"),
        )
    }
    suspend fun provider(value: String) = context.dataStore.edit { it[provider] = value }
    suspend fun theme(value: String) = context.dataStore.edit { it[theme] = value }
    suspend fun cookie(provider: String, cookie: String) = context.dataStore.edit {
        val current = Json.decodeFromString<Map<String, String>>(it[cookies] ?: "{}").toMutableMap()
        if (cookie.isBlank()) current.remove(provider) else current[provider] = cookie
        it[cookies] = Json.encodeToString(current)
    }
}

data class AppSettings(val provider: String, val theme: String, val cookies: Map<String, String>)

@Entity(tableName = "saved_songs")
data class SavedSong(@PrimaryKey val key: String, val bucket: String, val payload: String, val updatedAt: Long)

@Entity(tableName = "local_playlists")
data class LocalPlaylist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val songs: String)

@Entity(tableName = "downloads")
data class DownloadRecord(@PrimaryKey val key: String, val payload: String, val path: String, val state: String, val updatedAt: Long)

@Dao
interface MusicDao {
    @Query("SELECT * FROM saved_songs WHERE bucket = :bucket ORDER BY updatedAt DESC")
    fun songs(bucket: String): Flow<List<SavedSong>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(song: SavedSong)
    @Query("DELETE FROM saved_songs WHERE key = :key AND bucket = :bucket") suspend fun remove(bucket: String, key: String)
    @Query("SELECT * FROM local_playlists ORDER BY id DESC") fun playlists(): Flow<List<LocalPlaylist>>
    @Insert suspend fun insertPlaylist(playlist: LocalPlaylist): Long
    @Query("UPDATE local_playlists SET songs = :songs WHERE id = :id") suspend fun updatePlaylist(id: Long, songs: String)
    @Query("DELETE FROM local_playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC") fun downloads(): Flow<List<DownloadRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDownload(record: DownloadRecord)
    @Query("DELETE FROM downloads WHERE key = :key") suspend fun deleteDownload(key: String)
}

@Database(entities = [SavedSong::class, LocalPlaylist::class, DownloadRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "mymusic.db").build()
    }
}
