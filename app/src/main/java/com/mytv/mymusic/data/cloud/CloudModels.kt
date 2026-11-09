package com.mytv.mymusic.data.cloud

import androidx.media3.datasource.DataSource
import com.mytv.mymusic.data.native.PlayableStream
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.Locale

/**
 * 三种云端位置。[id] 就是同步文件 v3 里 `remote.provider` 的取值（规格 3.2 / 9.1），
 * 不能改名：另一台设备靠它决定去哪里取这首歌。
 */
enum class CloudProvider(val id: String, val label: String, val canUpload: Boolean) {
    NeteaseCloud("netease_cloud", "网易云盘", true),

    /** 只读：上传协议没有公开闭环（规格 7.4），这次只做列表、已匹配的播放、歌词、删除。 */
    QqCloud("qq_cloud", "QQ 音乐云盘", false),
    WebDav("webdav", "WebDAV", true),
    ;

    companion object {
        fun of(id: String): CloudProvider? = entries.firstOrNull { it.id == id }
    }
}

/** `remote.status` 的取值。只有 ready 能播；missing / deleted 时保留歌单条目，只改可用性。 */
object CloudRefStatus {
    const val QUEUED = "queued"
    const val UPLOADING = "uploading"
    const val TRANSCODING = "transcoding"
    const val READY = "ready"
    const val FAILED = "failed"

    /** 云端 404：文件被删了或换了账号（规格 10.3）。 */
    const val MISSING = "missing"

    /** 用户在本应用里删了云端副本（规格 9.3）。 */
    const val DELETED = "deleted"
}

/**
 * 一首本地歌在云端的位置（规格 3.2）。前五个字段和同步 v3 的 `SyncRemote` 一一对应
 * （provider / id / playableId / status / uploadedAt），可以直接拷进同步文件；
 * fileName / fileSize 对应 `SyncTrack.fileName / fileSize`。其余字段只给本机的 `local_tracks` 用。
 *
 * 不放任何播放地址、账号或密码：地址是短时的，换设备就失效（规格 8.4 / 10.4）。
 */
@Serializable
data class CloudRef(
    /** [CloudProvider.id]：netease_cloud / qq_cloud / webdav。 */
    val provider: String,
    /**
     * 解析用的 ID：
     * - 网易：云盘条目的 `simpleSong.id`（开源实现播放、匹配、删除都传它）；拿不到时退回 `songId`；
     * - QQ：微云 `file_id`；
     * - WebDAV：相对服务器地址的路径，例如 `myMusic/music/9f/9f3a….flac`。
     */
    val id: String,
    /** 匹配到的曲库 ID（网易 songId / QQ songmid）。网易只在确认和 [id] 不同时才填（规格 3.2）。 */
    val playableId: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val status: String = CloudRefStatus.READY,
    /** ISO-8601 UTC，例如 `2026-11-09T11:59:00Z`；合并时较新的 remote 胜出（规格 9.3）。 */
    val uploadedAt: String = "",
    val ext: String = "",
    /** 整文件 MD5（网易上传要求）；WebDAV / QQ 为空。 */
    val md5: String = "",
    /** 网易云盘条目的 `songId`：`v1/cloud/get/byids` 查详情、轮询转码状态都用它。 */
    val cloudSongId: String = "",
    /** 上传时的本地身份 `sha256:…`；从云端列表反查时可能为空。 */
    val localId: String = "",
) {
    val cloudProvider: CloudProvider? get() = CloudProvider.of(provider)
    val isReady: Boolean get() = status == CloudRefStatus.READY

    /** [uploadedAt] 的毫秒值；格式不对时为 0。`local_tracks.remoteUpdatedAt` 用它。 */
    fun uploadedAtMillis(): Long = runCatching { Instant.parse(uploadedAt).toEpochMilli() }.getOrDefault(0L)

    companion object {
        fun timestamp(millis: Long): String = if (millis > 0) Instant.ofEpochMilli(millis).toString() else ""
    }
}

/** 容量。[total] ≤ 0 表示服务器没说（WebDAV 常见），这时不做容量预检。 */
data class CloudQuota(val used: Long, val total: Long) {
    val known: Boolean get() = total > 0

    /** 剩余空间；不知道总量时当成无限。 */
    val free: Long get() = if (known) (total - used).coerceAtLeast(0) else Long.MAX_VALUE

    /** 再放 [needed] 字节还差多少；0 表示放得下。 */
    fun shortfall(needed: Long): Long = if (!known) 0 else (needed - free).coerceAtLeast(0)
}

/** 云端条目对应的平台歌曲（拿封面、歌词、走现有取流用）。 */
data class CloudMatch(
    /** 现有 provider 的 id：netease / qq。 */
    val provider: String,
    val songId: String,
    /** QQ 的 songmid；网易为空。 */
    val mid: String = "",
) {
    /** 交给现有取流接口的 ID：QQ 用 mid，网易用 songId。 */
    val playableId: String get() = mid.ifBlank { songId }
}

/** 云端列表里的一行。 */
data class CloudEntry(
    val provider: String,
    /** 和 [CloudRef.id] 同义。 */
    val id: String,
    val fileName: String,
    val fileSize: Long = 0,
    val ext: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSec: Int = 0,
    /** kbps；不知道为 0。 */
    val bitrate: Int = 0,
    val cover: String = "",
    /** 加进云端的时间，毫秒。 */
    val addedAt: Long = 0,
    val md5: String = "",
    val match: CloudMatch? = null,
    /** false 时界面画删除线并写 [unplayableReason]（QQ 原文件）。 */
    val playable: Boolean = true,
    val unplayableReason: String = "",
    /** WebDAV：从文件名反推的本地身份 `sha256:…`，用来和本机的 local_tracks 对上。 */
    val localId: String = "",
    /** 同 [CloudRef.playableId]：QQ 是 songmid；网易只在确认和 [id] 不同时才有。 */
    val playableId: String = "",
    /** 同 [CloudRef.cloudSongId]：网易云盘条目的 songId。 */
    val cloudSongId: String = "",
    /** 各家额外字段：QQ 原始文件名、WebDAV ETag 等，排查用。 */
    val extra: Map<String, String> = emptyMap(),
) {
    fun toRef(): CloudRef = CloudRef(
        provider = provider,
        id = id,
        playableId = playableId,
        fileName = fileName,
        fileSize = fileSize,
        status = CloudRefStatus.READY,
        uploadedAt = CloudRef.timestamp(addedAt),
        ext = ext,
        md5 = md5,
        cloudSongId = cloudSongId,
        localId = localId,
    )
}

/** 一页列表。[nextCursor] 为 null 表示读完了；游标是不透明的字符串，原样传回 [CloudStorage.list]。 */
data class CloudPage(
    val entries: List<CloudEntry>,
    val nextCursor: String? = null,
    /** 云端总条数；不知道为 -1。 */
    val total: Int = -1,
    /** 网易列表顺带返回容量，省一次请求。 */
    val quota: CloudQuota? = null,
) {
    val hasMore: Boolean get() = nextCursor != null
}

/**
 * 能播的地址。[dataSourceFactory] 非空时必须用它播（WebDAV：认证、证书钉住都在那个 OkHttpClient 里）；
 * 为空时就是普通地址，现有 DataSource 能直接放。地址是临时的，不写进同步文件。
 */
data class CloudStream(
    val url: String,
    val ext: String = "",
    val bitrate: Int = 0,
    val level: String = "",
    /** 只拿到试听片段（规格 10.3 要防误判）；解析器照现有逻辑继续找别的源。 */
    val preview: Boolean = false,
    /** Basic 认证这类头；只有能用请求头表达认证时才有。 */
    val headers: Map<String, String> = emptyMap(),
    val dataSourceFactory: DataSource.Factory? = null,
    val extra: Map<String, String> = emptyMap(),
    /** 走的哪条路，排查用：platform / cloud_download / cloud_play_url / webdav / qq_vkey。 */
    val via: String = "",
    val contentLength: Long = -1,
) {
    fun toPlayableStream(): PlayableStream = PlayableStream(url, ext, bitrate, level, preview, extra)
}

/**
 * 要上传的文件。本地歌多半是 `content://`，所以不要求 java.io.File；集成时用
 * `ContentResolver.openInputStream` 实现 [open] 即可。
 */
interface CloudUploadSource {
    /** 带扩展名的文件名，例如 `雨季备忘.flac`。 */
    val fileName: String
    val size: Long

    /** 文件“版本”：断点只在版本没变时接着用。默认用大小，集成时最好带上修改时间。 */
    val version: String get() = size.toString()

    /** 从 [offset] 开始读；每次调用都返回新的流，调用方负责关闭。 */
    fun open(offset: Long = 0): InputStream

    companion object {
        fun of(file: File, name: String = file.name): CloudUploadSource = object : CloudUploadSource {
            override val fileName: String = name
            override val size: Long = file.length()
            override val version: String = "${file.length()}:${file.lastModified()}"
            override fun open(offset: Long): InputStream = FileInputStream(file).also { it.skipFully(offset) }
        }

        fun of(fileName: String, bytes: ByteArray): CloudUploadSource = object : CloudUploadSource {
            override val fileName: String = fileName
            override val size: Long = bytes.size.toLong()
            override fun open(offset: Long): InputStream =
                bytes.inputStream(offset.toInt(), (bytes.size - offset.toInt()).coerceAtLeast(0))
        }
    }
}

/** InputStream.skip 可能少跳，循环到位；读到结尾还没跳够说明文件比登记的短。 */
fun InputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        if (read() < 0) throw IOException("文件比登记的短")
        remaining--
    }
}

/** 上传时附带的歌曲信息。 */
data class CloudUploadMeta(
    /** 本地身份 `sha256:…`：WebDAV 路径、断点续传、上传后回写 local_tracks 都靠它。 */
    val localId: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** 为空时从文件名取。 */
    val ext: String = "",
    /** 本机已经匹配到的网易云曲库 ID；非空时上传完顺手做一次 user/song/match（可选，失败不算上传失败）。 */
    val neteaseMatchId: String = "",
)

/** 上传状态机（规格 6.5）。WebDAV 只用其中几步：QUEUED → CHECKING → UPLOADING → SUBMITTING → READY。 */
enum class CloudUploadStage {
    QUEUED,
    HASHING,
    CHECKING,
    UPLOADING,
    SUBMITTING,
    TRANSCODING,
    PUBLISHING,
    READY,
    FAILED,
    ;

    val terminal: Boolean get() = this == READY || this == FAILED
}

data class CloudUploadProgress(
    val stage: CloudUploadStage,
    /** 当前阶段的进度：HASHING 是读过的字节，UPLOADING 是服务端确认收下的字节。 */
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    /** 网易秒传：曲库里已有同一文件，没传字节。 */
    val instant: Boolean = false,
    /** WebDAV：服务器上已有同样大小的同一文件，跳过。 */
    val skipped: Boolean = false,
    /** READY 时的云端位置。 */
    val ref: CloudRef? = null,
    /** FAILED 时的原因；暂停队列时也挂在排队项上，界面写原因。 */
    val error: CloudException? = null,
) {
    val fraction: Float
        get() = if (bytesTotal <= 0) 0f else (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat()
}

/**
 * “现在能不能继续传”。只在 Wi-Fi 下上传、用户手动暂停这类策略由集成层实现：
 * 不允许时挂起，允许了再返回。上传器在每个文件开始前和每一片之前都会问一次。
 */
fun interface UploadGate {
    suspend fun awaitAllowed()

    companion object {
        val Always: UploadGate = UploadGate { }
    }
}

/** 上传前预检的输入：本地身份 + 文件大小。 */
data class CloudUploadCandidate(val id: String, val size: Long)

/**
 * 上传前预检（规格 12）：单文件超限的跳过，其余按剩余容量算；不够就整批不进队列，
 * 界面写“还差 [shortfall]”。
 */
data class UploadPrecheck(
    val accepted: List<String>,
    val tooLarge: List<String>,
    val totalBytes: Long,
    val quota: CloudQuota?,
    val shortfall: Long,
) {
    val canStart: Boolean get() = shortfall == 0L && accepted.isNotEmpty()

    companion object {
        fun plan(items: List<CloudUploadCandidate>, quota: CloudQuota?, maxFileSize: Long?): UploadPrecheck {
            val (tooLarge, accepted) = items.partition { maxFileSize != null && it.size > maxFileSize }
            val total = accepted.sumOf { it.size }
            return UploadPrecheck(
                accepted = accepted.map { it.id },
                tooLarge = tooLarge.map { it.id },
                totalBytes = total,
                quota = quota,
                shortfall = quota?.shortfall(total) ?: 0L,
            )
        }
    }
}

/**
 * 三种云端共用的接口。实现：[com.mytv.mymusic.data.cloud.netease.NeteaseCloudApi]、
 * [com.mytv.mymusic.data.cloud.qq.QqCloudApi]、[com.mytv.mymusic.data.webdav.WebDavStorage]。
 * 所有失败都抛 [CloudException]，message 就是给用户看的中文。
 */
interface CloudStorage {
    val provider: CloudProvider

    /** 一页列表；[cursor] 传上一页的 [CloudPage.nextCursor]，第一页传 null。 */
    suspend fun list(cursor: String? = null): CloudPage

    /** 容量；服务器不告诉时返回 null。 */
    suspend fun quota(): CloudQuota?

    /** 解析播放地址。云端已删抛 [CloudErrorKind.NotFound]，调用方把 remote.status 置成 missing。 */
    suspend fun resolvePlayback(ref: CloudRef): CloudStream

    /** 删云端副本；本来就不存在也算成功。 */
    suspend fun delete(ref: CloudRef)

    /** 上传一个文件；失败抛 [CloudException]。QQ 恒抛 [CloudErrorKind.UploadUnsupported]。 */
    suspend fun upload(
        source: CloudUploadSource,
        meta: CloudUploadMeta,
        progress: (CloudUploadProgress) -> Unit = {},
    ): CloudRef

    /** 歌词（LRC）；没有歌词的存储（WebDAV）抛 [CloudErrorKind.Unsupported]，歌词改按匹配结果取。 */
    suspend fun lyrics(ref: CloudRef): String =
        throw CloudException(CloudErrorKind.Unsupported, "${provider.label}没有歌词，按匹配到的平台歌曲取", provider)

    /** 上传前预检；不知道容量时只拦单文件大小。 */
    suspend fun precheck(items: List<CloudUploadCandidate>): UploadPrecheck =
        UploadPrecheck.plan(items, attempt { quota() }.getOrNull(), null)
}

/** 从文件名取扩展名（小写、不带点）；没有扩展名返回空串。 */
fun cloudExtensionOf(fileName: String): String {
    val name = fileName.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return ""
    return name.substring(dot + 1).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(8)
}

/** 去掉扩展名的文件名。 */
fun cloudBaseNameOf(fileName: String): String {
    val name = fileName.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}
