package com.mytv.mymusic.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mytv.mymusic.aggregate.AggregationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore by preferencesDataStore("mymusic")

@Serializable
data class EqBandSetting(val freqHz: Int = 0, val gainDb: Float = 0f)

data class EqualizerSettings(
    val enabled: Boolean = false,
    val presetId: String = "flat",
    val customBands: List<EqBandSetting> = emptyList(),
)

class SettingsStore(private val context: Context) {
    private val providerKey = stringPreferencesKey("provider")
    private val chartsSourceKey = stringPreferencesKey("charts_source")
    private val playlistsSourceKey = stringPreferencesKey("playlists_source")
    private val themeKey = stringPreferencesKey("theme")
    private val cookiesKey = stringPreferencesKey("cookies")
    private val sodaSignerUrlKey = stringPreferencesKey("soda_signer_url")
    private val sodaSignerTokenKey = stringPreferencesKey("soda_signer_token")
    private val aggregationKey = stringPreferencesKey("aggregation")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    private val reduceMotionKey = booleanPreferencesKey("reduce_motion")
    private val showBottomNavKey = booleanPreferencesKey("show_bottom_nav")
    private val equalizerEnabledKey = booleanPreferencesKey("equalizer_enabled")
    private val equalizerPresetKey = stringPreferencesKey("equalizer_preset")
    private val equalizerBandsKey = stringPreferencesKey("equalizer_bands")
    private val searchHistoryKey = stringPreferencesKey("search_history")
    private val lastUpdateCheckKey = longPreferencesKey("last_update_check")
    private val skippedUpdateKey = longPreferencesKey("skipped_update_code")
    private val colorSourceKey = stringPreferencesKey("color_source")
    private val customSeedKey = intPreferencesKey("custom_seed")
    private val browseStyleKey = stringPreferencesKey("browse_style")
    private val playerStyleKey = stringPreferencesKey("player_style")
    private val contrastKey = floatPreferencesKey("contrast")
    private val amoledKey = booleanPreferencesKey("amoled")
    private val blurModeKey = stringPreferencesKey("blur_mode")
    private val lastSeedKey = intPreferencesKey("last_seed")
    private val sleepFadeKey = booleanPreferencesKey("sleep_fade")
    private val cacheLimitKey = intPreferencesKey("cache_limit_mb")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val state: Flow<AppSettings> = context.dataStore.data.map { values ->
        AppSettings(
            provider = values[providerKey] ?: "netease",
            chartsSource = values[chartsSourceKey] ?: "",
            playlistsSource = values[playlistsSourceKey] ?: "",
            theme = values[themeKey] ?: "system",
            cookies = runCatching { json.decodeFromString<Map<String, String>>(values[cookiesKey] ?: "{}") }
                .getOrDefault(emptyMap()),
            sodaSignerUrl = values[sodaSignerUrlKey] ?: "",
            sodaSignerToken = values[sodaSignerTokenKey] ?: "",
            aggregation = runCatching {
                json.decodeFromString<AggregationSettings>(values[aggregationKey] ?: "")
            }.getOrDefault(AggregationSettings()),
            dynamicColor = values[dynamicColorKey] ?: false,
            reduceMotion = values[reduceMotionKey] ?: false,
            showBottomNav = values[showBottomNavKey] ?: true,
            equalizer = EqualizerSettings(
                enabled = values[equalizerEnabledKey] ?: false,
                presetId = values[equalizerPresetKey] ?: "flat",
                customBands = runCatching {
                    json.decodeFromString<List<EqBandSetting>>(values[equalizerBandsKey] ?: "[]")
                }.getOrDefault(emptyList()),
            ),
            searchHistory = runCatching {
                json.decodeFromString<List<String>>(values[searchHistoryKey] ?: "[]")
            }.getOrDefault(emptyList()),
            lastUpdateCheck = values[lastUpdateCheckKey] ?: 0L,
            skippedUpdateCode = values[skippedUpdateKey] ?: 0L,
            // 旧版“系统取色”开关迁移为“系统壁纸”配色来源
            colorSource = values[colorSourceKey]
                ?: if (values[dynamicColorKey] == true) "Wallpaper" else "Cover",
            customSeed = values[customSeedKey] ?: 0xFF006B5F.toInt(),
            browseStyle = values[browseStyleKey] ?: "TonalSpot",
            playerStyle = values[playerStyleKey] ?: "Vibrant",
            contrast = values[contrastKey] ?: 0f,
            amoled = values[amoledKey] ?: false,
            blurMode = values[blurModeKey] ?: "Auto",
            lastSeed = values[lastSeedKey] ?: 0,
            sleepFade = values[sleepFadeKey] ?: true,
            cacheLimitMb = values[cacheLimitKey] ?: 0,
        )
    }

    suspend fun provider(value: String) = context.dataStore.edit { it[providerKey] = value }
    suspend fun chartsSource(value: String) = context.dataStore.edit { it[chartsSourceKey] = value }
    suspend fun playlistsSource(value: String) = context.dataStore.edit { it[playlistsSourceKey] = value }
    suspend fun theme(value: String) = context.dataStore.edit { it[themeKey] = value }
    suspend fun dynamicColor(value: Boolean) = context.dataStore.edit { it[dynamicColorKey] = value }
    suspend fun reduceMotion(value: Boolean) = context.dataStore.edit { it[reduceMotionKey] = value }
    suspend fun showBottomNav(value: Boolean) = context.dataStore.edit { it[showBottomNavKey] = value }
    suspend fun colorSource(value: String) = context.dataStore.edit { it[colorSourceKey] = value }
    suspend fun customSeed(value: Int) = context.dataStore.edit { it[customSeedKey] = value }
    suspend fun browseStyle(value: String) = context.dataStore.edit { it[browseStyleKey] = value }
    suspend fun playerStyle(value: String) = context.dataStore.edit { it[playerStyleKey] = value }
    suspend fun contrast(value: Float) = context.dataStore.edit { it[contrastKey] = value }
    suspend fun amoled(value: Boolean) = context.dataStore.edit { it[amoledKey] = value }
    suspend fun blurMode(value: String) = context.dataStore.edit { it[blurModeKey] = value }
    suspend fun lastSeed(value: Int) = context.dataStore.edit { it[lastSeedKey] = value }
    suspend fun sleepFade(value: Boolean) = context.dataStore.edit { it[sleepFadeKey] = value }
    suspend fun cacheLimitMb(value: Int) = context.dataStore.edit { it[cacheLimitKey] = value }

    suspend fun sodaSigner(url: String, token: String) = context.dataStore.edit {
        it[sodaSignerUrlKey] = url.trim()
        it[sodaSignerTokenKey] = token.trim()
    }

    suspend fun cookie(provider: String, cookie: String) = context.dataStore.edit {
        val current = runCatching {
            json.decodeFromString<Map<String, String>>(it[cookiesKey] ?: "{}")
        }.getOrDefault(emptyMap()).toMutableMap()
        if (cookie.isBlank()) current.remove(provider) else current[provider] = cookie
        it[cookiesKey] = json.encodeToString(current)
    }

    suspend fun saveAggregation(value: AggregationSettings) = context.dataStore.edit {
        it[aggregationKey] = json.encodeToString(value)
    }

    suspend fun saveEqualizer(value: EqualizerSettings) = context.dataStore.edit {
        it[equalizerEnabledKey] = value.enabled
        it[equalizerPresetKey] = value.presetId
        it[equalizerBandsKey] = json.encodeToString(value.customBands)
    }

    suspend fun addSearchHistory(query: String) = context.dataStore.edit {
        val value = query.trim()
        if (value.isBlank()) return@edit
        val current = runCatching {
            json.decodeFromString<List<String>>(it[searchHistoryKey] ?: "[]")
        }.getOrDefault(emptyList()).toMutableList()
        current.remove(value)
        current.add(0, value)
        it[searchHistoryKey] = json.encodeToString(current.take(20))
    }

    suspend fun removeSearchHistory(query: String) = context.dataStore.edit {
        val current = runCatching {
            json.decodeFromString<List<String>>(it[searchHistoryKey] ?: "[]")
        }.getOrDefault(emptyList()).filterNot { item -> item == query }
        it[searchHistoryKey] = json.encodeToString(current)
    }

    suspend fun clearSearchHistory() = context.dataStore.edit { it[searchHistoryKey] = "[]" }

    suspend fun lastUpdateCheck(value: Long) = context.dataStore.edit { it[lastUpdateCheckKey] = value }
    suspend fun skippedUpdateCode(value: Long) = context.dataStore.edit { it[skippedUpdateKey] = value }
}

data class AppSettings(
    val provider: String = "netease",
    val chartsSource: String = "",
    val playlistsSource: String = "",
    val theme: String = "system",
    val cookies: Map<String, String> = emptyMap(),
    /** 汽水签名器（sodam / libmssdk 的 /sign 协议），留空表示只用公开试听流。 */
    val sodaSignerUrl: String = "",
    val sodaSignerToken: String = "",
    val aggregation: AggregationSettings = AggregationSettings(),
    val dynamicColor: Boolean = false,
    val reduceMotion: Boolean = false,
    val showBottomNav: Boolean = true,
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val searchHistory: List<String> = emptyList(),
    val lastUpdateCheck: Long = 0L,
    val skippedUpdateCode: Long = 0L,
    val colorSource: String = "Cover",
    val customSeed: Int = 0xFF006B5F.toInt(),
    val browseStyle: String = "TonalSpot",
    val playerStyle: String = "Vibrant",
    val contrast: Float = 0f,
    val amoled: Boolean = false,
    val blurMode: String = "Auto",
    val lastSeed: Int = 0,
    val sleepFade: Boolean = true,
    /** 播放缓存上限（MB），0 表示按设备默认：电视 256，其他 512。 */
    val cacheLimitMb: Int = 0,
)

// 同一首歌可以同时在“喜欢”和“最近播放”里，所以主键是 (key, bucket)
@Entity(tableName = "saved_songs", primaryKeys = ["key", "bucket"])
data class SavedSong(val key: String, val bucket: String, val payload: String, val updatedAt: Long)

@Entity(tableName = "downloads")
data class DownloadRecord(@PrimaryKey val key: String, val payload: String, val path: String, val state: String, val updatedAt: Long)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val dirty: Boolean = true,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlist_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlist_id"], name = "idx_entries_playlist")],
)
data class PlaylistEntryEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val position: Int,
    @ColumnInfo(name = "song_key") val songKey: String,
    val payload: String,
)

@Entity(tableName = "sync_accounts")
data class SyncAccountEntity(
    @PrimaryKey val provider: String,
    @ColumnInfo(name = "remote_id") val remoteId: String = "",
    @ColumnInfo(name = "file_name") val fileName: String = "mymusic-library.json",
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long = 0L,
    @ColumnInfo(name = "remote_revision") val remoteRevision: String = "",
    @ColumnInfo(name = "auto_sync") val autoSync: Boolean = true,
    @ColumnInfo(name = "wifi_only") val wifiOnly: Boolean = false,
    @ColumnInfo(name = "remote_url") val remoteUrl: String = "",
    @ColumnInfo(name = "account_label") val accountLabel: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)

@Entity(tableName = "sync_base")
data class SyncBaseEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    val snapshot: String,
    @ColumnInfo(name = "remote_revision") val remoteRevision: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "local_snapshot") val localSnapshot: String,
    @ColumnInfo(name = "remote_snapshot") val remoteSnapshot: String,
    @ColumnInfo(name = "base_snapshot") val baseSnapshot: String,
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
)

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
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC") fun downloads(): Flow<List<DownloadRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDownload(record: DownloadRecord)
    @Query("DELETE FROM downloads WHERE key = :key") suspend fun deleteDownload(key: String)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM playlists WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun playlists(): Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlists WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    suspend fun playlistsOnce(): List<PlaylistEntity>
    @Query("SELECT * FROM playlists")
    suspend fun allPlaylistsOnce(): List<PlaylistEntity>
    @Query("SELECT * FROM playlists WHERE id = :id")
    fun playlist(id: String): Flow<PlaylistEntity?>
    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlistOnce(id: String): PlaylistEntity?
    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :id ORDER BY position")
    fun entries(id: String): Flow<List<PlaylistEntryEntity>>
    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :id ORDER BY position")
    suspend fun entriesOnce(id: String): List<PlaylistEntryEntity>
    @Query("SELECT * FROM playlist_entries ORDER BY playlist_id, position")
    suspend fun allEntriesOnce(): List<PlaylistEntryEntity>
    @Query("SELECT playlist_id, COUNT(*) AS total FROM playlist_entries GROUP BY playlist_id")
    fun entryCounts(): Flow<List<PlaylistEntryCount>>
    @Query("SELECT COUNT(*) FROM playlist_entries")
    suspend fun entryCount(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPlaylist(playlist: PlaylistEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEntries(entries: List<PlaylistEntryEntity>)
    @Query("DELETE FROM playlist_entries WHERE playlist_id = :id") suspend fun clearEntries(id: String)
    @Query("UPDATE playlists SET name = :name, updated_at = :time, dirty = 1 WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String, time: Long)
    @Query("UPDATE playlists SET dirty = 1, updated_at = :time WHERE id = :id")
    suspend fun markDirty(id: String, time: Long)
    @Query("UPDATE playlists SET dirty = :dirty, updated_at = :time WHERE id = :id")
    suspend fun markClean(id: String, time: Long, dirty: Boolean = false)
    @Query("UPDATE playlists SET deleted_at = :time, updated_at = :time, dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: String)
    @Query("DELETE FROM playlists WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun pruneDeleted(cutoff: Long)
    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Transaction
    suspend fun replaceEntries(id: String, entries: List<PlaylistEntryEntity>) {
        clearEntries(id)
        if (entries.isNotEmpty()) putEntries(entries)
    }
}

data class PlaylistEntryCount(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val total: Int,
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_accounts ORDER BY updated_at DESC")
    fun accounts(): Flow<List<SyncAccountEntity>>
    @Query("SELECT * FROM sync_accounts LIMIT 1")
    suspend fun accountOnce(): SyncAccountEntity?
    @Query("SELECT * FROM sync_accounts WHERE provider = :provider")
    suspend fun account(provider: String): SyncAccountEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccount(account: SyncAccountEntity)
    @Query("DELETE FROM sync_accounts") suspend fun clearAccounts()
    @Query("SELECT * FROM sync_base WHERE account_id = :id")
    suspend fun base(id: String): SyncBaseEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBase(base: SyncBaseEntity)
    @Query("DELETE FROM sync_base") suspend fun clearBases()
    @Query("SELECT * FROM sync_conflicts ORDER BY detected_at DESC")
    fun conflicts(): Flow<List<SyncConflictEntity>>
    @Query("SELECT * FROM sync_conflicts ORDER BY detected_at DESC")
    suspend fun conflictsOnce(): List<SyncConflictEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConflict(conflict: SyncConflictEntity)
    @Query("DELETE FROM sync_conflicts WHERE playlist_id = :id") suspend fun deleteConflict(id: String)
    @Query("DELETE FROM sync_conflicts") suspend fun clearConflicts()
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
        DownloadRecord::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        SyncAccountEntity::class,
        SyncBaseEntity::class,
        SyncConflictEntity::class,
        CanonicalTrackEntity::class,
        CanonicalSourceEntity::class,
        MatchCacheEntity::class,
        TrackOverrideEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao
    abstract fun library(): LibraryDao
    abstract fun sync(): SyncDao
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `dirty` INTEGER NOT NULL,
                        `deleted_at` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_entries` (
                        `playlist_id` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `song_key` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        PRIMARY KEY(`playlist_id`, `position`),
                        FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_entries_playlist` ON `playlist_entries` (`playlist_id`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_accounts` (
                        `provider` TEXT NOT NULL,
                        `remote_id` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `last_synced_at` INTEGER NOT NULL,
                        `remote_revision` TEXT NOT NULL,
                        `auto_sync` INTEGER NOT NULL,
                        `wifi_only` INTEGER NOT NULL,
                        `remote_url` TEXT NOT NULL,
                        `account_label` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`provider`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_base` (
                        `account_id` TEXT NOT NULL,
                        `snapshot` TEXT NOT NULL,
                        `remote_revision` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_conflicts` (
                        `playlist_id` TEXT NOT NULL,
                        `local_snapshot` TEXT NOT NULL,
                        `remote_snapshot` TEXT NOT NULL,
                        `base_snapshot` TEXT NOT NULL,
                        `detected_at` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_id`)
                    )
                    """.trimIndent()
                )

                val json = Json { ignoreUnknownKeys = true }
                val now = System.currentTimeMillis()
                val cursor = db.query("SELECT id, name, songs FROM local_playlists ORDER BY id")
                cursor.use {
                    while (it.moveToNext()) {
                        val name = it.getString(1).ifBlank { "未命名歌单" }
                        val songs = runCatching {
                            json.decodeFromString<List<Song>>(it.getString(2) ?: "[]")
                        }.getOrDefault(emptyList())
                        val id = UUID.randomUUID().toString()
                        db.execSQL(
                            "INSERT INTO playlists (id, name, created_at, updated_at, dirty, deleted_at) VALUES (?, ?, ?, ?, 1, NULL)",
                            arrayOf<Any?>(id, name, now, now),
                        )
                        songs.forEachIndexed { index, song ->
                            db.execSQL(
                                "INSERT INTO playlist_entries (playlist_id, position, song_key, payload) VALUES (?, ?, ?, ?)",
                                arrayOf<Any?>(id, index, song.key, json.encodeToString(song)),
                            )
                        }
                    }
                }
            }
        }

        /** saved_songs 原来只用 key 做主键，写最近播放会把同一首歌的“喜欢”记录替换掉。 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_songs_new` (
                        `key` TEXT NOT NULL,
                        `bucket` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`, `bucket`)
                    )
                    """.trimIndent()
                )
                db.execSQL("INSERT OR REPLACE INTO `saved_songs_new` (`key`, `bucket`, `payload`, `updatedAt`) SELECT `key`, `bucket`, `payload`, `updatedAt` FROM `saved_songs`")
                db.execSQL("DROP TABLE `saved_songs`")
                db.execSQL("ALTER TABLE `saved_songs_new` RENAME TO `saved_songs`")
            }
        }

        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "mymusic.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }
}
