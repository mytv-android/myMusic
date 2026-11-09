package com.mytv.mymusic.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudQueueItem
import com.mytv.mymusic.data.cloud.CloudQueueState
import com.mytv.mymusic.data.cloud.CloudRefStatus
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadProgress
import com.mytv.mymusic.data.cloud.CloudUploadSource
import com.mytv.mymusic.data.cloud.CloudUploadStage
import com.mytv.mymusic.data.cloud.UploadPrecheck

/**
 * 上传队列落盘的一行（不放音频，只记“哪首、传到哪、现在什么状态、为什么失败”）。
 * 进程被杀、手机重启后按它把队列放回来，接着传；失败的也留着，界面上还能点重试。
 * 正在传的那一项记成排队：重启后从头（网易有曲库秒传，WebDAV 本来就不支持断点）。
 */
@Entity(tableName = "cloud_uploads", primaryKeys = ["local_id", "provider"])
data class LocalUploadEntity(
    @ColumnInfo(name = "local_id") val localId: String,
    /** [CloudProvider.id]：netease_cloud / webdav（QQ 云盘从不上传）。 */
    val provider: String,
    /** 队列里的先后，从 0 开始。 */
    val position: Int,
    /** [LocalUploadRows.QUEUED] / [LocalUploadRows.FAILED]。 */
    val state: String,
    /** [CloudErrorKind] 的名字；排队项上挂着暂停原因时也写在这里。 */
    @ColumnInfo(name = "error_kind") val errorKind: String = "",
    /** 给用户看的那句，原样放回界面。 */
    @ColumnInfo(name = "error_message") val errorMessage: String = "",
    @ColumnInfo(name = "error_code") val errorCode: Int = 0,
    val attempts: Int = 0,
)

@Dao
interface LocalUploadDao {
    @Query("SELECT * FROM cloud_uploads ORDER BY provider, position")
    suspend fun all(): List<LocalUploadEntity>

    @Query("DELETE FROM cloud_uploads WHERE provider = :provider")
    suspend fun clear(provider: String)

    @Upsert
    suspend fun upsert(rows: List<LocalUploadEntity>)

    /** 整个云端的队列一次换掉：队列本身就是有序的整体，逐行增删反而容易留下孤行。 */
    @Transaction
    suspend fun replace(provider: String, rows: List<LocalUploadEntity>) {
        clear(provider)
        if (rows.isNotEmpty()) upsert(rows)
    }
}

/** 队列 ⇄ 落盘行。纯函数，单元测试直接测往返。 */
object LocalUploadRows {
    const val QUEUED = "queued"
    const val FAILED = "failed"

    /** 传完的不存（已经写进 local_remotes 了）；正在传的记成排队。 */
    fun toRows(provider: CloudProvider, state: CloudQueueState): List<LocalUploadEntity> =
        state.items.filter { it.stage != CloudUploadStage.READY }.mapIndexed { position, item ->
            val error = item.error
            LocalUploadEntity(
                localId = item.localId,
                provider = provider.id,
                position = position,
                state = if (item.stage == CloudUploadStage.FAILED) FAILED else QUEUED,
                errorKind = error?.kind?.name.orEmpty(),
                errorMessage = error?.message.orEmpty(),
                errorCode = error?.code ?: 0,
                attempts = item.attempts,
            )
        }

    /**
     * 落盘行放回队列项。[source] 按身份找这台设备上的文件；找不到（文件删了、来源移出了扫描范围）的
     * 不悄悄丢掉，记成失败写明原因，用户看得到、能从队列里删掉。
     * 返回的第二项是队列要维持的暂停原因：排在最前面的排队项上挂着风控 / 登录失效这类原因。
     */
    fun restore(
        provider: CloudProvider,
        rows: List<LocalUploadEntity>,
        source: (String) -> Pair<CloudUploadSource, CloudUploadMeta>?,
        missing: (String) -> Pair<CloudUploadSource, CloudUploadMeta>,
    ): Pair<List<CloudQueueItem>, CloudException?> {
        val mine = rows.filter { it.provider == provider.id }.sortedBy { it.position }.distinctBy { it.localId }
        val items = mine.map { row ->
            val found = source(row.localId)
            val (src, meta) = found ?: missing(row.localId)
            val error = when {
                found == null -> CloudException(CloudErrorKind.InvalidFile, FILE_GONE, provider)
                row.errorKind.isBlank() -> null
                else -> CloudException(
                    kind = runCatching { CloudErrorKind.valueOf(row.errorKind) }.getOrDefault(CloudErrorKind.Server),
                    message = row.errorMessage.ifBlank { CloudMessages.CLOUD_UNREACHABLE },
                    provider = provider,
                    code = row.errorCode,
                )
            }
            val failed = found == null || row.state == FAILED
            CloudQueueItem(
                localId = row.localId,
                source = src,
                meta = meta,
                progress = CloudUploadProgress(
                    stage = if (failed) CloudUploadStage.FAILED else CloudUploadStage.QUEUED,
                    bytesDone = 0,
                    bytesTotal = src.size,
                    error = error,
                ),
                attempts = row.attempts,
            )
        }
        val pausedBy = items.firstOrNull { it.stage == CloudUploadStage.QUEUED }?.error?.takeIf { it.pausesQueue }
        return items to pausedBy
    }

    const val FILE_GONE = "这台设备上找不到这个文件了，没法上传"
}

/** 一次“上传这几首”的分拣结果（设计稿第 ⑥ 张）。 */
data class UploadPlan(
    /** 真要进队列的。 */
    val upload: List<String>,
    /** 这个云端已经有能播的一份：不重复传（除非用户在云端副本那行明确点了“重新上传”）。 */
    val alreadyUploaded: List<String>,
    /** 这台设备上没有文件（别的设备加进歌单的、文件不在了的）：传不了。 */
    val notOnDevice: List<String>,
    /** 已经在队列里排着或正在传。 */
    val alreadyQueued: List<String>,
)

/** 上传面板里那几句话和“开始上传”能不能点。 */
data class UploadNotice(
    /** 非空时不能开始（容量不够、一首都传不了），写在面板里，不进队列。 */
    val blocking: String = "",
    /** 不挡开始、但要说清楚的：有几首超过 500 MB 会跳过、几首已经传过。 */
    val notes: List<String> = emptyList(),
)

/**
 * 上传的规则，单独拎出来好测：去重、预检的说明、默认位置、加进歌单时要不要提示。
 */
object LocalUploadRules {
    /** 这首在这个云端有一份能播的：状态 ready，且这台设备上次取流没发现它被删了。 */
    fun hasReadyCopy(remotes: List<LocalRemoteEntity>, provider: CloudProvider): Boolean =
        remotes.any { it.provider == provider.id && it.ready && it.checkState != LocalCloudCheck.MISSING }

    /** 任何一个云端有能播的一份：加进歌单时就不用提示了。 */
    fun hasAnyReadyCopy(remotes: List<LocalRemoteEntity>): Boolean =
        remotes.any { it.ready && it.checkState != LocalCloudCheck.MISSING }

    /**
     * 分拣：已经在这个云端的不再传（[force] 为 true 时是用户明确要求重新上传）、这台设备上没文件的传不了、
     * 已经排着的不重复排。顺序保持用户选的顺序，重复的身份只留一个。
     */
    fun plan(
        ids: List<String>,
        provider: CloudProvider,
        remotes: Map<String, List<LocalRemoteEntity>>,
        onDevice: (String) -> Boolean,
        queued: Set<String>,
        force: Boolean = false,
    ): UploadPlan {
        val upload = ArrayList<String>()
        val uploaded = ArrayList<String>()
        val absent = ArrayList<String>()
        val waiting = ArrayList<String>()
        for (id in ids.distinct()) {
            when {
                !onDevice(id) -> absent += id
                id in queued -> waiting += id
                !force && hasReadyCopy(remotes[id].orEmpty(), provider) -> uploaded += id
                else -> upload += id
            }
        }
        return UploadPlan(upload, uploaded, absent, waiting)
    }

    /**
     * 面板里的说明（设计稿第 09 节的文案）。[precheck] 为空表示还没查完（或这次没法查）。
     * 容量不够、一首都传不了时是 blocking：按钮不可点，也不进队列。
     */
    fun notice(provider: CloudProvider, plan: UploadPlan, precheck: UploadPrecheck?): UploadNotice {
        val notes = ArrayList<String>()
        val label = provider.label
        val inText = if (provider == CloudProvider.WebDav) " $label " else label
        if (plan.alreadyUploaded.isNotEmpty()) notes += "${plan.alreadyUploaded.size} 首已经在${inText}里，不重复上传"
        if (plan.alreadyQueued.isNotEmpty()) notes += "${plan.alreadyQueued.size} 首已经在上传队列里"
        if (plan.notOnDevice.isNotEmpty()) notes += "${plan.notOnDevice.size} 首这台设备上没有文件，传不了"
        val tooLarge = precheck?.tooLarge.orEmpty()
        if (tooLarge.isNotEmpty() && precheck?.accepted?.isNotEmpty() == true) notes += "有 ${tooLarge.size} 首超过 500 MB，会跳过"
        val blocking = when {
            plan.upload.isEmpty() && plan.alreadyUploaded.isNotEmpty() && plan.notOnDevice.isEmpty() && plan.alreadyQueued.isEmpty() ->
                "选中的歌都已经在${inText}里了"
            plan.upload.isEmpty() && plan.alreadyQueued.isNotEmpty() && plan.notOnDevice.isEmpty() -> "选中的歌都已经在上传队列里了"
            plan.upload.isEmpty() -> "没有能上传的歌"
            precheck == null -> ""
            precheck.accepted.isEmpty() && tooLarge.isNotEmpty() -> "选中的歌都超过 500 MB，${label}不收"
            precheck.shortfall > 0 -> "${label}空间不够，还差 ${CloudMessages.formatSize(precheck.shortfall)}"
            else -> ""
        }
        return UploadNotice(blocking, notes)
    }

    /**
     * 默认上传到哪：用户选过且这台设备上能用就用它；否则登录了网易云就网易云盘，配了 WebDAV 就 WebDAV；
     * 都没有时还是返回用户选的（或网易云盘），界面写“还没有可用的位置”。QQ 云盘只读，从不当默认。
     */
    fun defaultTarget(preferred: String, configured: Set<CloudProvider>): CloudProvider {
        val chosen = CloudProvider.of(preferred)?.takeIf { it.canUpload }
        if (chosen != null && chosen in configured) return chosen
        return when {
            CloudProvider.NeteaseCloud in configured -> CloudProvider.NeteaseCloud
            CloudProvider.WebDav in configured -> CloudProvider.WebDav
            else -> chosen ?: CloudProvider.NeteaseCloud
        }
    }

    /**
     * 加进歌单 / 收藏时要不要提示上传（设计稿第 ② 张底部）：设置开着、同步连着、这首是本地歌、
     * 这台设备上有文件（没文件就传不了）、哪个云端都还没有能播的一份。只提示，从不自动上传。
     * 返回要提示的那几首的身份。
     */
    fun promptFor(
        songs: List<Song>,
        enabled: Boolean,
        syncConnected: Boolean,
        onDevice: (String) -> Boolean,
        hasReadyCopy: (String) -> Boolean,
    ): List<String> {
        if (!enabled || !syncConnected) return emptyList()
        return songs.asSequence()
            .filter { it.source == LOCAL_SOURCE }
            .map { it.id }
            .distinct()
            .filter { onDevice(it) && !hasReadyCopy(it) }
            .toList()
    }

    /** 提示那一句：“已加入「通勤路上」。这首只在本机，换台设备放不了”。 */
    fun promptText(added: String, total: Int, localOnly: Int): String = when {
        total <= 1 -> "$added。这首只在本机，换台设备放不了"
        localOnly == total -> "$added。这 $localOnly 首只在本机，换台设备放不了"
        else -> "$added。其中 $localOnly 首只在本机，换台设备放不了"
    }

    /** 我的页那一行、页内“正在上传”标题的暂停说明（设计稿第 09 节）。 */
    fun pausedText(provider: CloudProvider, reason: CloudException?): String = when (reason?.kind) {
        CloudErrorKind.RiskControl -> CloudMessages.RISK_CONTROL_PAUSED
        CloudErrorKind.NotLoggedIn -> "上传暂停：${CloudMessages.notLoggedIn(provider)}"
        CloudErrorKind.Unauthorized -> "上传暂停：${CloudMessages.WEBDAV_UNAUTHORIZED}"
        null -> "上传已暂停"
        else -> "上传暂停：${reason.message}"
    }

    /** 已经传完一首的计数用：只算这个云端状态 ready 的。 */
    fun isUploaded(remotes: List<LocalRemoteEntity>): Boolean = remotes.any { it.status == CloudRefStatus.READY }
}
