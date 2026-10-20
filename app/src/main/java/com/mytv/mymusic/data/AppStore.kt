package com.mytv.mymusic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mytv.mymusic.aggregate.AggregationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("mymusic")

class SettingsStore(private val context: Context) {
    private val provider = stringPreferencesKey("provider")
    private val theme = stringPreferencesKey("theme")
    private val cookies = stringPreferencesKey("cookies")
    private val aggregation = stringPreferencesKey("aggregation")

    val state: Flow<AppSettings> = context.dataStore.data.map {
        AppSettings(
            provider = it[provider] ?: "netease",
            theme = it[theme] ?: "system",
            cookies = Json.decodeFromString(it[cookies] ?: "{}"),
            aggregation = runCatching {
                Json.decodeFromString<AggregationSettings>(it[aggregation] ?: "")
            }.getOrDefault(AggregationSettings()),
        )
    }

    suspend fun provider(value: String) = context.dataStore.edit { it[provider] = value }
    suspend fun theme(value: String) = context.dataStore.edit { it[theme] = value }
    suspend fun cookie(provider: String, cookie: String) = context.dataStore.edit {
        val current = Json.decodeFromString<Map<String, String>>(it[cookies] ?: "{}").toMutableMap()
        if (cookie.isBlank()) current.remove(provider) else current[provider] = cookie
        it[cookies] = Json.encodeToString(current)
    }
    suspend fun saveAggregation(value: AggregationSettings) = context.dataStore.edit {
        it[aggregation] = Json.encodeToString(value)
    }
}

data class AppSettings(
    val provider: String,
    val theme: String,
    val cookies: Map<String, String>,
    val aggregation: AggregationSettings = AggregationSettings(),
)

@Entity(tableName = "saved_songs")
data class SavedSong(@PrimaryKey val key: String, val bucket: String, val payload: String, val updatedAt: Long)

@Entity(tableName = "local_playlists")
data class LocalPlaylist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val songs: String)

@Entity(tableName = "downloads")
data class DownloadRecord(@PrimaryKey val key: String, val payload: String, val path: String, val state: String, val updatedAt: Long)

@Entity(tableName = "canonical_tracks")
data class CanonicalTrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artists: String,
    val album: String,
    @ColumnInfo(name = "duration_sec") val durationSec: Int,
    val version: String,
    val cover: String,
    val confidence: Float,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "canonical_sources",
    foreignKeys = [
        ForeignKey(
            entity = CanonicalTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonical_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["canonical_id"], name = "idx_sources_canonical"),
        Index(value = ["provider_id"], name = "idx_sources_provider"),
    ],
)
data class CanonicalSourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    val payload: String,
    @ColumnInfo(name = "match_score")
    val matchScore: Float,
    @ColumnInfo(name = "match_method")
    val matchMethod: String,
    val availability: String,
    @ColumnInfo(name = "is_preview")
    val isPreview: Boolean,
    @ColumnInfo(name = "checked_at")
    val checkedAt: Long,
)

@Entity(tableName = "match_cache", primaryKeys = ["source_key", "target_provider"])
data class MatchCacheEntity(
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "target_provider")
    val targetProvider: String,
    @ColumnInfo(name = "target_key")
    val targetKey: String,
    val score: Float,
    val payload: String,
    @ColumnInfo(name = "checked_at")
    val checkedAt: Long,
)

@Entity(tableName = "track_overrides")
data class TrackOverrideEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String,
    val action: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

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

@Dao
interface AggregateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveTrack(track: CanonicalTrackEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSources(sources: List<CanonicalSourceEntity>)
    @Query("SELECT * FROM canonical_sources WHERE canonical_id = :canonicalId ORDER BY provider_id")
    suspend fun sources(canonicalId: String): List<CanonicalSourceEntity>
    @Query("SELECT * FROM canonical_sources WHERE source_key = :sourceKey LIMIT 1")
    suspend fun source(sourceKey: String): CanonicalSourceEntity?
    @Query("SELECT * FROM match_cache WHERE source_key = :sourceKey AND target_provider = :targetProvider LIMIT 1")
    suspend fun match(sourceKey: String, targetProvider: String): MatchCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMatch(entry: MatchCacheEntity)
    @Query("DELETE FROM match_cache") suspend fun clearMatches()
    @Query("DELETE FROM match_cache WHERE checked_at < :cutoff") suspend fun pruneMatches(cutoff: Long)
    @Query("DELETE FROM canonical_tracks WHERE updated_at < :cutoff") suspend fun pruneTracks(cutoff: Long)
    @Query("SELECT * FROM track_overrides") suspend fun overrides(): List<TrackOverrideEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveOverride(entry: TrackOverrideEntity)
    @Query("DELETE FROM track_overrides") suspend fun clearOverrides()
}

@Database(
    entities = [
        SavedSong::class,
        LocalPlaylist::class,
        DownloadRecord::class,
        CanonicalTrackEntity::class,
        CanonicalSourceEntity::class,
        MatchCacheEntity::class,
        TrackOverrideEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao
    abstract fun aggregate(): AggregateDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `canonical_tracks` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artists` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `duration_sec` INTEGER NOT NULL,
                        `version` TEXT NOT NULL,
                        `cover` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `canonical_sources` (
                        `source_key` TEXT NOT NULL,
                        `canonical_id` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `match_score` REAL NOT NULL,
                        `match_method` TEXT NOT NULL,
                        `availability` TEXT NOT NULL,
                        `is_preview` INTEGER NOT NULL,
                        `checked_at` INTEGER NOT NULL,
                        PRIMARY KEY(`source_key`),
                        FOREIGN KEY(`canonical_id`) REFERENCES `canonical_tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sources_canonical` ON `canonical_sources` (`canonical_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sources_provider` ON `canonical_sources` (`provider_id`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `match_cache` (
                        `source_key` TEXT NOT NULL,
                        `target_provider` TEXT NOT NULL,
                        `target_key` TEXT NOT NULL,
                        `score` REAL NOT NULL,
                        `payload` TEXT NOT NULL,
                        `checked_at` INTEGER NOT NULL,
                        PRIMARY KEY(`source_key`, `target_provider`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `track_overrides` (
                        `source_key` TEXT NOT NULL,
                        `canonical_id` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`source_key`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "mymusic.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }
}
