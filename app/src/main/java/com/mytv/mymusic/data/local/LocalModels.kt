package com.mytv.mymusic.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** 本地歌的 Song.source；Song.key 就是 `local:sha256:…`。 */
const val LOCAL_SOURCE = "local"

/** 匹配方式：按标签搜到 / 听歌识曲 / 用户在「换一个」里选的 / 自动没匹配上 / 用户点了「不匹配」。空表示还没试过。 */
object LocalMatchMethod {
    const val TAG = "tag"
    const val AUDIO = "audio"
    const val MANUAL = "manual"
    const val NONE = "none"
    const val REJECTED = "rejected"
}

/** 扫描来源：系统媒体库，或者某个 SAF 文件夹（存 tree uri）。 */
const val LOCAL_ORIGIN_MEDIA = "media"

/**
 * 一首本机音频。主键是首尾各 64KB + 文件大小的 SHA-256（见 [LocalIdentity]），换路径、换设备都不变；
 * uri 只是这台设备上现在去哪儿读它，从不写进 Song 持久化。
 *
 * 云端那几列（remote_*）这一版不用，先建好，等上传落地时不必再加迁移。
 */
@Entity(tableName = "local_tracks", indices = [Index(value = ["uri"], name = "idx_local_tracks_uri")])
data class LocalTrackEntity(
    @PrimaryKey @ColumnInfo(name = "local_id") val localId: String,
    val uri: String,
    /** [LOCAL_ORIGIN_MEDIA] 或文件夹的 tree uri；关掉媒体库、移除文件夹时按它把这些歌藏起来。 */
    val origin: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    /** 显示用的位置，例如 `Music/示例歌手/`；拿不到时为空。 */
    @ColumnInfo(name = "relative_path") val relativePath: String,
    /** 媒体库给的绝对路径（DATA 列），只在 Android 10 及以下拿来找旁边的 .lrc。 */
    @ColumnInfo(name = "data_path") val dataPath: String,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "duration_sec") val durationSec: Int,
    val size: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    val bitrate: Int,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int,
    @ColumnInfo(name = "bits_per_sample") val bitsPerSample: Int,
    /** 标签和内嵌封面已经用 MediaMetadataRetriever 读过；扫描只登记文件，标签放到第二遍慢慢读。 */
    @ColumnInfo(name = "tags_read") val tagsRead: Boolean,
    /** 内嵌封面缩小后的缓存（`file://`），没有内嵌图时为空。 */
    @ColumnInfo(name = "cover_path") val coverPath: String,
    /** 文件夹扫描时找到的同名 .lrc 的文档 uri。 */
    @ColumnInfo(name = "lrc_uri") val lrcUri: String,
    /** 这次扫描没找到它：先标记，不删记录，歌单条目也不动。 */
    val missing: Boolean,
    @ColumnInfo(name = "match_provider") val matchProvider: String,
    @ColumnInfo(name = "match_id") val matchId: String,
    @ColumnInfo(name = "match_confidence") val matchConfidence: Float,
    /** 见 [LocalMatchMethod]，空表示还没匹配过。 */
    @ColumnInfo(name = "match_method") val matchMethod: String,
    @ColumnInfo(name = "matched_at") val matchedAt: Long,
    /** 匹配到的那首平台歌（Song JSON，不含播放地址）：封面、歌词、平台版本兜底都靠它，免得再查一次。 */
    @ColumnInfo(name = "match_payload") val matchPayload: String,
    @ColumnInfo(name = "remote_provider") val remoteProvider: String,
    @ColumnInfo(name = "remote_id") val remoteId: String,
    @ColumnInfo(name = "remote_playable_id") val remotePlayableId: String,
    @ColumnInfo(name = "remote_status") val remoteStatus: String,
    @ColumnInfo(name = "remote_updated_at") val remoteUpdatedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    val key: String get() = "$LOCAL_SOURCE:$localId"
    val matched: Boolean get() = matchProvider.isNotBlank() && matchId.isNotBlank()
    /** 自动匹配过了（成功或失败）或者用户手动处理过，不再自动重试。 */
    val matchSettled: Boolean get() = matchMethod.isNotBlank()
    val extension: String get() = displayName.substringAfterLast('.', "").lowercase()
}

@Dao
interface LocalTrackDao {
    @Query("SELECT * FROM local_tracks")
    fun observeAll(): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks")
    suspend fun all(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_tracks WHERE local_id = :localId")
    suspend fun get(localId: String): LocalTrackEntity?

    @Query("SELECT * FROM local_tracks WHERE local_id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<LocalTrackEntity>

    @Upsert
    suspend fun upsert(rows: List<LocalTrackEntity>)

    @Query("UPDATE local_tracks SET missing = :missing, updated_at = :now WHERE local_id IN (:ids)")
    suspend fun setMissing(ids: List<String>, missing: Boolean, now: Long)

    @Query("DELETE FROM local_tracks WHERE missing = 1")
    suspend fun deleteMissing(): Int

    @Query("DELETE FROM local_tracks WHERE local_id = :localId")
    suspend fun delete(localId: String)
}

/** 标题和歌手：只看文件 / 文件标签是空的或明显是占位时才用匹配结果 / 有匹配就用匹配结果。 */
@Serializable
enum class LocalTitlePolicy { File, FillGaps, Match }

@Serializable
data class LocalFolder(val uri: String, val name: String)

/** 设置 › 本地音乐。整份存成一个 JSON 键，和 AggregationSettings 一样。 */
@Serializable
data class LocalMusicSettings(
    val mediaStore: Boolean = true,
    val folders: List<LocalFolder> = emptyList(),
    val skipShort: Boolean = true,
    val autoMatch: Boolean = true,
    val titlePolicy: LocalTitlePolicy = LocalTitlePolicy.FillGaps,
    val preferLrc: Boolean = true,
    /** 这台设备上没有文件时用匹配到的平台版本播放。 */
    val platformFallback: Boolean = true,
    /** 用户拒绝过读取音乐权限：空状态的主按钮换成“改为选择文件夹”。 */
    val permissionDenied: Boolean = false,
    /** 上次扫描完成的时间，页头写“X 分钟前扫描”。 */
    val lastScanAt: Long = 0L,
    /**
     * 默认上传到哪（[com.mytv.mymusic.data.cloud.CloudProvider.id]）。空表示没选过：登录了网易云就网易云盘，
     * 否则配了 WebDAV 就 WebDAV（见 LocalUploadRules.defaultTarget）。
     */
    val uploadTarget: String = "",
    /** 只在本机的歌加进已同步的歌单 / 收藏时提示一句“上传”。只提示，从不自动传。 */
    val uploadPrompt: Boolean = true,
    /** 只在 Wi-Fi（不按流量计费的网络）下上传；用移动网络时先排队。FLAC 一首三四十 MB。 */
    val uploadWifiOnly: Boolean = true,
)
