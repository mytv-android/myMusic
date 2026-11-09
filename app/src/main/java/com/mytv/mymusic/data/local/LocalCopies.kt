package com.mytv.mymusic.data.local

import com.mytv.mymusic.AppLog
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudRefStatus
import com.mytv.mymusic.data.cloud.CloudStorage
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** 一首本地歌在这台设备上会放哪一份（设计稿第 ⑨ 张对照表的每一行）。 */
enum class LocalCopyKind {
    /** 本机文件。 */
    Device,

    /** 云端副本：网易云盘 / WebDAV / QQ 音乐云盘。 */
    Cloud,

    /** 匹配到的平台版本（“用网易云的版本”）。 */
    Platform,

    /** 有云端副本，但这台设备现在取不到：连不上、要重新登录（空心点）或云端文件已删除（删除线）。 */
    CloudTrouble,

    /** 哪里都没有：“只在 Pixel 8 上”“文件不在了”。 */
    None,
}

/**
 * 徽标和副标题要的全部信息。界面按 [kind] 选字：本机 / 云端名 / 平台简称 / 本地，
 * 字形直接是 [status]（PlatformBadge 的实心点、空心点、删除线三种，不加新颜色）。
 */
data class LocalCopyState(
    val kind: LocalCopyKind,
    val status: Availability,
    /** [LocalCopyKind.Cloud] / [LocalCopyKind.CloudTrouble] 时是哪个云端。 */
    val provider: CloudProvider? = null,
    /** [LocalCopyKind.Platform] 时匹配到的平台（Song.source）。 */
    val platform: String = "",
    /** 副标题里替换歌手的那句；空就照常写歌手。 */
    val note: String = "",
    /** 文件所在设备的名字（同步来的）；不知道时为空。 */
    val device: String = "",
    /** 有匹配结果（平台版本开关关着时也算），面板里写“匹配到了…”。 */
    val matched: Boolean = false,
) {
    /** 点了也放不出来：不去播放，直接打开“只在 X 上”面板（设计稿第 ⑧ 张）。 */
    val blocked: Boolean get() = status == Availability.Unavailable

    /** 歌单里要灰掉的那种（`.sr.gone`）。 */
    val gone: Boolean get() = kind == LocalCopyKind.None
}

/**
 * 解析顺序和徽标的规则，纯函数，单元测试直接测：
 * 本机文件 → 云端副本（网易云盘 → WebDAV → QQ 云盘）→ 匹配到的平台版本（设置可关）→ 都没有。
 */
object LocalCopyRules {
    /**
     * 云端副本的尝试顺序：网易云盘地址最稳；WebDAV 是自己的服务器，可能在家里、外面连不上；
     * QQ 云盘只有匹配上曲库的才放得了，排最后。
     */
    val CLOUD_ORDER: List<CloudProvider> = listOf(CloudProvider.NeteaseCloud, CloudProvider.WebDav, CloudProvider.QqCloud)

    /** 文件不在这台设备上时，副标题和面板标题。 */
    fun elsewhere(device: String): String =
        if (device.isNotBlank()) "只在 $device 上" else "只在原来的设备上"

    /** 这台设备上次取流的结果对应的说明。 */
    fun checkReason(provider: CloudProvider, state: String): String = when (state) {
        LocalCloudCheck.MISSING -> CloudMessages.CLOUD_DELETED
        LocalCloudCheck.AUTH -> if (provider == CloudProvider.WebDav) CloudMessages.WEBDAV_UNAUTHORIZED else CloudMessages.notLoggedIn(provider)
        LocalCloudCheck.UNPLAYABLE ->
            if (provider == CloudProvider.QqCloud) CloudMessages.QQ_ORIGINAL_UNPLAYABLE else CloudMessages.NETEASE_UNPLAYABLE
        else -> CloudMessages.CLOUD_UNREACHABLE
    }

    /** 这份云端副本这台设备放不放得了：null 表示能试；否则是放不了的说明和字形。 */
    fun cloudProblem(
        slot: LocalRemoteEntity,
        provider: CloudProvider,
        configured: Set<CloudProvider>,
        now: Long,
    ): Pair<String, Availability>? = when {
        slot.status == CloudRefStatus.DELETED || slot.status == CloudRefStatus.MISSING ->
            CloudMessages.CLOUD_DELETED to Availability.Unavailable
        // 上传中 / 失败只在传它的那台设备上出现（不进同步文件），那台设备本来就有文件
        !slot.ready -> "云端副本还没传完" to Availability.PreviewOnly
        provider == CloudProvider.QqCloud && slot.playableId.isBlank() ->
            CloudMessages.QQ_ORIGINAL_UNPLAYABLE to Availability.Unavailable
        provider !in configured -> CloudMessages.notLoggedIn(provider) to Availability.PreviewOnly
        LocalCloudCheck.blocks(slot.checkState, slot.checkedAt, now) -> checkReason(provider, slot.checkState) to when (slot.checkState) {
            LocalCloudCheck.MISSING, LocalCloudCheck.UNPLAYABLE -> Availability.Unavailable
            else -> Availability.PreviewOnly
        }
        // 已经过了退避时间的“连不上”下次会重试；“删了”“要登录”没人处理前一直这样
        slot.checkState == LocalCloudCheck.MISSING -> CloudMessages.CLOUD_DELETED to Availability.Unavailable
        slot.checkState == LocalCloudCheck.AUTH -> checkReason(provider, slot.checkState) to Availability.PreviewOnly
        else -> null
    }

    /**
     * 歌单、收藏、队列、本地音乐页里一首本地歌的徽标。和 [LocalLibrary.resolve] 同一个顺序，
     * 只是不碰网络：云端副本按“配好了没有、上次取流的结果”估计。
     */
    fun state(
        onDevice: Boolean,
        /** 这台设备扫到过、但文件现在读不到（删了、搬走、来源被关了）时的说明。 */
        missingReason: String,
        remotes: List<LocalRemoteEntity>,
        configured: Set<CloudProvider>,
        match: Song?,
        platformFallback: Boolean,
        device: String,
        now: Long,
    ): LocalCopyState {
        if (onDevice) return LocalCopyState(LocalCopyKind.Device, Availability.Playable, device = device, matched = match != null)
        var trouble: LocalCopyState? = null
        for (provider in CLOUD_ORDER) {
            val slot = remotes.firstOrNull { it.provider == provider.id } ?: continue
            val problem = cloudProblem(slot, provider, configured, now)
            if (problem == null) {
                return LocalCopyState(LocalCopyKind.Cloud, Availability.Playable, provider = provider, device = device, matched = match != null)
            }
            // 空心点（可能马上就好）比删除线更值得告诉用户
            val candidate = LocalCopyState(
                LocalCopyKind.CloudTrouble,
                problem.second,
                provider = provider,
                note = problem.first,
                device = device,
                matched = match != null,
            )
            if (trouble == null || trouble.status == Availability.Unavailable && candidate.status != Availability.Unavailable) trouble = candidate
        }
        if (match != null && platformFallback) {
            return LocalCopyState(LocalCopyKind.Platform, Availability.Playable, platform = match.source, device = device, matched = true)
        }
        trouble?.let { return it }
        val note = missingReason.ifBlank { elsewhere(device) }
        return LocalCopyState(LocalCopyKind.None, Availability.Unavailable, note = note, device = device, matched = match != null)
    }
}

/**
 * 解析链上的“云端副本”一步（P5）。按 [LocalCopyRules.CLOUD_ORDER] 逐个试：
 * - 404 → 记“云端文件已删除”，一小时内不再撞；位置记录和歌单条目都留着；
 * - 401 / 403 / 没登录 → 记“要重新登录”，十分钟内不拿同一个账号去撞；
 * - 断网、超时 → 记“暂时连不上”，30 秒后再试；
 * 这些结果只在本机（[LocalRemoteEntity.checkState]），从不写进同步文件。
 */
class LocalCloudStep(
    private val storage: (CloudProvider) -> CloudStorage?,
    private val record: suspend (localId: String, provider: String, state: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : LocalResolveStep {
    override suspend fun resolve(request: LocalResolveRequest): LocalPlayable? {
        if (request.skipCloud) return null
        val time = now()
        val localId = request.song.id
        for (provider in LocalCopyRules.CLOUD_ORDER) {
            val slot = request.remotes.firstOrNull { it.provider == provider.id } ?: continue
            if (slot.status == CloudRefStatus.DELETED || slot.status == CloudRefStatus.MISSING) {
                request.notes += CloudMessages.CLOUD_DELETED
                continue
            }
            if (!slot.ready) continue
            if (LocalCloudCheck.blocks(slot.checkState, slot.checkedAt, time)) {
                request.notes += LocalCopyRules.checkReason(provider, slot.checkState)
                continue
            }
            // QQ 云盘没匹配上曲库的原文件没有取流接口，不用发请求就知道放不了
            if (provider == CloudProvider.QqCloud && slot.playableId.isBlank()) {
                request.notes += CloudMessages.QQ_ORIGINAL_UNPLAYABLE
                continue
            }
            val store = storage(provider)
            if (store == null) {
                request.notes += CloudMessages.notLoggedIn(provider)
                continue
            }
            val ref = slot.toCloudRef()
            try {
                val stream = store.resolvePlayback(ref)
                if (slot.checkState != LocalCloudCheck.OK) record(localId, provider.id, LocalCloudCheck.OK)
                return LocalPlayable.Cloud(stream, ref, request.song)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: CloudException) {
                val state = checkStateOf(error.kind)
                record(localId, provider.id, state)
                request.notes += when (state) {
                    LocalCloudCheck.MISSING -> CloudMessages.CLOUD_DELETED
                    LocalCloudCheck.UNREACHABLE -> CloudMessages.CLOUD_UNREACHABLE
                    else -> error.message.ifBlank { LocalCopyRules.checkReason(provider, state) }
                }
            } catch (error: IOException) {
                record(localId, provider.id, LocalCloudCheck.UNREACHABLE)
                request.notes += CloudMessages.CLOUD_UNREACHABLE
            } catch (error: Exception) {
                AppLog.w("LocalCloud", "${provider.label} 取流出错：${error.javaClass.simpleName}: ${error.message}")
                record(localId, provider.id, LocalCloudCheck.UNREACHABLE)
                request.notes += CloudMessages.CLOUD_UNREACHABLE
            }
        }
        return null
    }

    companion object {
        fun checkStateOf(kind: CloudErrorKind): String = when (kind) {
            CloudErrorKind.NotFound -> LocalCloudCheck.MISSING
            CloudErrorKind.NotLoggedIn, CloudErrorKind.Unauthorized, CloudErrorKind.Forbidden,
            CloudErrorKind.RiskControl -> LocalCloudCheck.AUTH
            CloudErrorKind.Unplayable -> LocalCloudCheck.UNPLAYABLE
            else -> LocalCloudCheck.UNREACHABLE
        }
    }
}

/**
 * 解析链最后一步：匹配到的平台版本。这台设备有过文件就用 local_tracks 里的匹配，
 * 没有就用同步来的匹配（[LocalResolveRequest.refMatch]）；设置里关了“没有文件时用平台版本”就跳过。
 */
object LocalPlatformStep : LocalResolveStep {
    override suspend fun resolve(request: LocalResolveRequest): LocalPlayable? {
        if (!request.prefs.platformFallback) return null
        request.item?.match?.let { return LocalPlayable.Platform(it, request.row?.matchConfidence ?: 0f) }
        val match = request.refMatch ?: return null
        return LocalPlayable.Platform(match, request.ref?.matchConfidence ?: 0f)
    }
}

/** 依次跑解析链；都不行返回 null，失败原因留在 [LocalResolveRequest.notes] 里。 */
suspend fun runResolveChain(steps: List<LocalResolveStep>, request: LocalResolveRequest): LocalPlayable? {
    for (step in steps) step.resolve(request)?.let { return it }
    return null
}

/**
 * 「换一个匹配」面板的对象：可以是这台设备上的本地歌（有文件，能听歌识曲、能点“不匹配”），
 * 也可以是别的设备加进歌单、这台设备上没有文件的歌（只有歌单里的歌名歌手和同步来的线索）。
 * 在哪台设备上选的匹配都会随同步回到有文件的那台。
 */
data class LocalMatchTarget(
    val localId: String,
    val song: Song,
    val title: String,
    val artist: String,
    val fileName: String,
    val match: Song?,
    val matchProvider: String,
    val matchConfidence: Float,
    val matchMethod: String,
    /** 要读文件才能识曲。 */
    val canListen: Boolean,
    /** “不匹配”只对这台设备上扫到过的歌有意义（回到文件自己的标签和内嵌封面）。 */
    val canReject: Boolean,
)

fun LocalItem.matchTarget(): LocalMatchTarget = LocalMatchTarget(
    localId = localId,
    song = song,
    title = row.title,
    artist = row.artist,
    fileName = row.displayName,
    match = match,
    matchProvider = row.matchProvider,
    matchConfidence = row.matchConfidence,
    matchMethod = row.matchMethod,
    canListen = onDevice,
    canReject = true,
)

/** 歌单里的一首本地歌对应的匹配对象：这台设备扫到过就用 local_tracks，没有就用同步来的线索。 */
fun LocalIndex.matchTarget(song: Song): LocalMatchTarget {
    byId[song.id]?.let { return it.matchTarget() }
    val ref = refs[song.id]
    return LocalMatchTarget(
        localId = song.id,
        song = song,
        title = song.name,
        artist = song.artist,
        fileName = ref?.fileName.orEmpty().ifBlank { song.extra[LocalLibrary.EXTRA_FILE_NAME].orEmpty() },
        match = refMatches[song.id],
        matchProvider = ref?.matchProvider.orEmpty(),
        matchConfidence = ref?.matchConfidence ?: 0f,
        matchMethod = ref?.matchMethod.orEmpty(),
        canListen = false,
        canReject = false,
    )
}
