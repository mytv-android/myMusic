package com.mytv.mymusic.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudRef
import com.mytv.mymusic.data.cloud.CloudRefStatus
import com.mytv.mymusic.data.sync.SyncLocal
import com.mytv.mymusic.data.sync.SyncMatch
import com.mytv.mymusic.data.sync.SyncRefMerge
import com.mytv.mymusic.data.sync.SyncRemote
import com.mytv.mymusic.data.sync.SyncTime
import kotlinx.coroutines.flow.Flow

/**
 * 一首本地歌的“线索”（设计稿第 08 节）：文件名、大小、文件在哪台设备上、匹配结果。主键是本地身份 `sha256:…`。
 *
 * 为什么不直接写进 local_tracks：那张表一行就是这台设备上的一个文件，别的设备加进歌单的歌这里根本没有文件，
 * 可同步过来的线索还是要记下——解析（放云端副本 / 平台版本）、徽标（“只在 Pixel 8 上”）、
 * 在这台设备上换一个匹配，都靠这一行，离线和重启后也在。
 *
 * 匹配：这台设备有文件时以 local_tracks 为准，这里只是同步来的那一份；没有文件时用户在这里选的匹配也记在这里，
 * 随下次同步回到有文件的设备。
 */
@Entity(tableName = "local_refs")
data class LocalRefEntity(
    @PrimaryKey @ColumnInfo(name = "local_id") val localId: String,
    @ColumnInfo(name = "file_name") val fileName: String = "",
    @ColumnInfo(name = "file_size") val fileSize: Long = 0,
    /** 文件所在设备的名字，例如“Pixel 8”。 */
    val device: String = "",
    @ColumnInfo(name = "match_provider") val matchProvider: String = "",
    @ColumnInfo(name = "match_id") val matchId: String = "",
    @ColumnInfo(name = "match_confidence") val matchConfidence: Float = 0f,
    @ColumnInfo(name = "match_method") val matchMethod: String = "",
    @ColumnInfo(name = "matched_at") val matchedAt: Long = 0,
    /**
     * 匹配到的那首平台歌（Song JSON，不含地址）。在这台设备上手动选的才有；同步来的只有平台和 ID，
     * 封面、歌词、平台版本都按 ID 现取。
     */
    @ColumnInfo(name = "match_payload") val matchPayload: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
) {
    val key: String get() = "$LOCAL_SOURCE:$localId"

    /** 用户（在哪台设备上都行）点过「不匹配」：这里的 matchMethod 存的是同步文件里的写法。 */
    val rejected: Boolean get() = matchMethod == SyncMatch.METHOD_REJECTED
    val matched: Boolean get() = !rejected && matchProvider.isNotBlank() && matchId.isNotBlank()

    fun syncLocal(): SyncLocal = SyncLocal(localId, fileName, fileSize, device)

    fun syncMatch(): SyncMatch? = when {
        rejected -> SyncMatch.rejection(isoOrEmpty(matchedAt))
        !matched -> null
        else -> SyncMatch(
            provider = matchProvider,
            id = matchId,
            confidence = matchConfidence,
            method = matchMethod,
            matchedAt = isoOrEmpty(matchedAt),
        )
    }
}

/**
 * 一首本地歌的一份云端副本：同一首可以同时在网易云盘和 WebDAV 上，所以按（身份，位置）存一行。
 * 上传队列传完一首就写这里（[LocalLibrary.recordRemote]），同步过来的也写这里。
 *
 * [checkState] / [checkedAt] 是这台设备最近一次取流的结果（[LocalCloudCheck]），只在本机用，从不进同步文件：
 * 404 可能只是这台设备登录了另一个账号，不能让别的设备也当成“删了”。
 */
@Entity(tableName = "local_remotes", primaryKeys = ["local_id", "provider"])
data class LocalRemoteEntity(
    @ColumnInfo(name = "local_id") val localId: String,
    /** [CloudProvider.id]：netease_cloud / qq_cloud / webdav。 */
    val provider: String,
    @ColumnInfo(name = "remote_id") val remoteId: String,
    @ColumnInfo(name = "playable_id") val playableId: String = "",
    @ColumnInfo(name = "file_name") val fileName: String = "",
    @ColumnInfo(name = "file_size") val fileSize: Long = 0,
    /** [CloudRefStatus]。 */
    val status: String = CloudRefStatus.READY,
    /** ISO-8601，原样保存：合并时按它比新旧，往返不能改写法，否则每次同步都当成变了。 */
    @ColumnInfo(name = "uploaded_at") val uploadedAt: String = "",
    val ext: String = "",
    val md5: String = "",
    @ColumnInfo(name = "cloud_song_id") val cloudSongId: String = "",
    @ColumnInfo(name = "check_state") val checkState: String = LocalCloudCheck.OK,
    @ColumnInfo(name = "checked_at") val checkedAt: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
) {
    val cloudProvider: CloudProvider? get() = CloudProvider.of(provider)
    val ready: Boolean get() = status == CloudRefStatus.READY

    fun toCloudRef(): CloudRef = CloudRef(
        provider = provider,
        id = remoteId,
        playableId = playableId,
        fileName = fileName,
        fileSize = fileSize,
        status = status,
        uploadedAt = uploadedAt,
        ext = ext,
        md5 = md5,
        cloudSongId = cloudSongId,
        localId = localId,
    )

    /** 只有 ready / deleted 进同步文件。 */
    fun toSync(): SyncRemote? = if (status !in SyncRefMerge.SYNCED_STATUSES || remoteId.isBlank()) null else SyncRemote(
        provider = provider,
        id = remoteId,
        playableId = playableId,
        status = status,
        uploadedAt = uploadedAt,
    )

    companion object {
        fun of(localId: String, ref: CloudRef, now: Long): LocalRemoteEntity = LocalRemoteEntity(
            localId = localId,
            provider = ref.provider,
            remoteId = ref.id,
            playableId = ref.playableId,
            fileName = ref.fileName,
            fileSize = ref.fileSize,
            status = ref.status,
            uploadedAt = ref.uploadedAt,
            ext = ref.ext,
            md5 = ref.md5,
            cloudSongId = ref.cloudSongId,
            checkState = LocalCloudCheck.OK,
            checkedAt = 0,
            updatedAt = now,
        )
    }
}

/**
 * 这台设备上一次取云端副本的结果。解析时按 [blocks] 在一段时间内跳过已知不行的副本（不反复撞 404、
 * 不拿错密码去撞服务器）；界面按它画“云端文件已删除”“云端暂时连不上”。
 */
object LocalCloudCheck {
    const val OK = ""

    /** 404：云端文件不在了（被删，或这台设备登录的是另一个账号）。 */
    const val MISSING = "missing"

    /** 401 / 403 / 没登录：提示去重新登录，不清掉云端位置。 */
    const val AUTH = "auth"

    /** 断网、超时：短暂退避后再试。 */
    const val UNREACHABLE = "unreachable"

    /** 云端在，但取不到能放的地址（QQ 原文件、网易云盘地址接口都失败）。 */
    const val UNPLAYABLE = "unplayable"

    const val MISSING_MS = 60 * 60_000L
    const val AUTH_MS = 10 * 60_000L
    const val UNREACHABLE_MS = 30_000L
    const val UNPLAYABLE_MS = 60 * 60_000L

    /** 这次解析要不要跳过它：结果还在有效期里就跳过。 */
    fun blocks(state: String, checkedAt: Long, now: Long): Boolean {
        val window = when (state) {
            MISSING -> MISSING_MS
            AUTH -> AUTH_MS
            UNREACHABLE -> UNREACHABLE_MS
            UNPLAYABLE -> UNPLAYABLE_MS
            else -> return false
        }
        return now - checkedAt in 0 until window
    }
}

@Dao
interface LocalRefDao {
    @Query("SELECT * FROM local_refs")
    fun observeRefs(): Flow<List<LocalRefEntity>>

    @Query("SELECT * FROM local_remotes")
    fun observeRemotes(): Flow<List<LocalRemoteEntity>>

    @Query("SELECT * FROM local_refs WHERE local_id = :localId")
    suspend fun ref(localId: String): LocalRefEntity?

    @Query("SELECT * FROM local_refs WHERE local_id IN (:ids)")
    suspend fun refs(ids: List<String>): List<LocalRefEntity>

    @Query("SELECT * FROM local_remotes WHERE local_id = :localId")
    suspend fun remotes(localId: String): List<LocalRemoteEntity>

    @Query("SELECT * FROM local_remotes WHERE local_id IN (:ids)")
    suspend fun remotesOf(ids: List<String>): List<LocalRemoteEntity>

    @Query("SELECT * FROM local_remotes WHERE local_id = :localId AND provider = :provider")
    suspend fun remote(localId: String, provider: String): LocalRemoteEntity?

    @Upsert
    suspend fun upsertRefs(rows: List<LocalRefEntity>)

    @Upsert
    suspend fun upsertRemotes(rows: List<LocalRemoteEntity>)

    @Query("UPDATE local_remotes SET check_state = :state, checked_at = :at WHERE local_id = :localId AND provider = :provider")
    suspend fun setCheck(localId: String, provider: String, state: String, at: Long)

    /** 换了账号、改了密码：这台设备记下的“不行”都作废，下次播放重新试。 */
    @Query("UPDATE local_remotes SET check_state = '', checked_at = 0 WHERE provider = :provider AND check_state != ''")
    suspend fun clearChecks(provider: String)
}

internal fun isoOrEmpty(millis: Long): String = if (millis > 0) SyncTime.toIso(millis) else ""
