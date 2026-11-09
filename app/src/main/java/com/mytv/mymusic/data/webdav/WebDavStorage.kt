package com.mytv.mymusic.data.webdav

import com.mytv.mymusic.data.cloud.CloudEntry
import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudPage
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudQuota
import com.mytv.mymusic.data.cloud.CloudRef
import com.mytv.mymusic.data.cloud.CloudRefStatus
import com.mytv.mymusic.data.cloud.CloudStorage
import com.mytv.mymusic.data.cloud.CloudStream
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadProgress
import com.mytv.mymusic.data.cloud.CloudUploadSource
import com.mytv.mymusic.data.cloud.CloudUploadStage
import com.mytv.mymusic.data.cloud.SourceChunkBody
import com.mytv.mymusic.data.cloud.UploadGate
import com.mytv.mymusic.data.cloud.attempt
import com.mytv.mymusic.data.cloud.await
import com.mytv.mymusic.data.cloud.cloudExtensionOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * WebDAV 当云端（规格第 8 节）：歌放在 `<目录>/music/<localId 前两位>/<localId>.<ext>`，
 * 例如 `myMusic/music/9f/9f3a….flac`（localId 去掉 `sha256:` 前缀）。按内容身份命名，
 * 同一首在哪台设备上传都落在同一个位置，另一台设备照路径直接取。
 *
 * - 上传：先 HEAD，同样大小的已经在了就跳过；逐级 MKCOL；PUT 到同目录的临时名
 *   （`If-None-Match: *`），传完再 `MOVE`（`Overwrite: F`）成正式名——传到一半断了也不会
 *   留下一个看起来完整、其实是半截的正式文件。服务器不支持 MOVE 时退回直接 PUT 正式名。
 * - 播放：[resolvePlayback] 返回带 [WebDavDataSource.Factory] 的流，认证、证书钉住、Range 都在里面。
 * - 列表：按两位桶目录翻页，游标是下一个桶名。
 */
class WebDavStorage(
    val client: WebDavClient,
    /** 用户设的目录（`myMusic/`）；歌放在它下面的 `music/`。 */
    directory: String = client.account.normalizedDirectory,
    private val gate: UploadGate = UploadGate.Always,
    /** 一页至少攒这么多条（按整桶取，可能略多）。 */
    private val pageSize: Int = 200,
    /** 服务器不认 Range、只能从头读时回调（参数是地址），界面提示一次即可。 */
    private val onRangeIgnored: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : CloudStorage {
    override val provider: CloudProvider = CloudProvider.WebDav

    /** `myMusic/music/`。 */
    val musicDirectory: String = WebDavPaths.join(WebDavPaths.normalizeDirectory(directory), "$MUSIC_DIR/")
    private val rootDirectory: String = WebDavPaths.normalizeDirectory(directory)

    /** 流播用：比同步的 20/40 秒宽松，不设总超时（一首 FLAC 慢慢下）。规格 8.4。 */
    val streamClient: OkHttpClient by lazy {
        client.http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /** 上传用：单次 PUT 整个文件，写超时放宽；服务器收完大文件后可能要一会儿才回应。 */
    private val uploadClient: OkHttpClient by lazy {
        client.http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /** 这台服务器不支持 MOVE（405 / 501 / 反代下的 502）之后就不再先传临时名。 */
    @Volatile private var moveSupported = true

    val dataSourceFactory: WebDavDataSource.Factory by lazy { WebDavDataSource.Factory(streamClient, onRangeIgnored) }

    // ---------------------------------------------------------------- 路径

    /** 一首歌的正式路径。localId 不合法（空、没有可用字符）时抛 InvalidFile。 */
    fun pathFor(localId: String, ext: String): String {
        val key = fileKey(localId)
        if (key.isEmpty()) {
            throw CloudException(CloudErrorKind.InvalidFile, "这首歌还没有本地身份，没法放到 WebDAV", provider)
        }
        val cleanExt = cleanExtension(ext)
        val name = if (cleanExt.isEmpty()) key else "$key.$cleanExt"
        return WebDavPaths.join(musicDirectory, bucketOf(key) + "/", name)
    }

    /** 桶目录：`myMusic/music/9f/`。 */
    fun bucketPath(localId: String): String = WebDavPaths.join(musicDirectory, bucketOf(fileKey(localId)) + "/")

    // ---------------------------------------------------------------- 列表 / 容量 / 删除

    override suspend fun list(cursor: String?): CloudPage {
        val buckets = try {
            client.list(musicDirectory).filter { it.isCollection }.map { it.name }.filter { it.isNotEmpty() }.sorted()
        } catch (error: WebDavException) {
            // 还没传过歌：music 目录不存在就是空列表
            if (error.kind == WebDavErrorKind.NotFound) return CloudPage(emptyList())
            throw toCloud(error)
        }
        var index = if (cursor == null) 0 else buckets.indexOfFirst { it >= cursor }.let { if (it < 0) buckets.size else it }
        val entries = mutableListOf<CloudEntry>()
        while (index < buckets.size && entries.size < pageSize) {
            val files = try {
                client.list(WebDavPaths.join(musicDirectory, buckets[index] + "/"))
            } catch (error: WebDavException) {
                // 翻页之间桶被别的设备删了：跳过
                if (error.kind == WebDavErrorKind.NotFound) emptyList() else throw toCloud(error)
            }
            entries += files.filter { !it.isCollection && !it.name.startsWith(".") }.map(::entryOf)
            index++
        }
        return CloudPage(entries, buckets.getOrNull(index))
    }

    /** RFC 4331 容量；服务器不给（或不支持这个 PROPFIND）时返回 null，上传前就不做容量预检。 */
    override suspend fun quota(): CloudQuota? {
        val quota = try {
            try {
                client.quota(rootDirectory)
            } catch (error: WebDavException) {
                // 目录还没建：退到服务器地址本身
                if (error.kind == WebDavErrorKind.NotFound) client.quota("") else throw error
            }
        } catch (error: WebDavException) {
            // 401 要让用户知道；其余（405、看不懂的 XML、网络）当成“服务器不告诉”
            if (error.kind == WebDavErrorKind.Unauthorized) throw toCloud(error)
            return null
        } ?: return null
        val used = quota.usedBytes.coerceAtLeast(0)
        val total = if (quota.availableBytes >= 0) used + quota.availableBytes else 0
        return CloudQuota(used, total)
    }

    /** 本来就不在也算删成功（规格：删云端副本幂等）。 */
    override suspend fun delete(ref: CloudRef) {
        call { client.delete(ref.id) }
    }

    // ---------------------------------------------------------------- 播放

    /**
     * 先找 ref.id，再按 localId 推一次路径（用户改过目录、或另一台设备用的是旧路径）。都没有报 NotFound，
     * 上层把 remote.status 置成 missing。
     */
    override suspend fun resolvePlayback(ref: CloudRef): CloudStream {
        val candidates = buildList {
            if (ref.id.isNotBlank()) add(ref.id)
            if (ref.localId.isNotBlank()) {
                val ext = ref.ext.ifBlank { cloudExtensionOf(ref.id).ifBlank { cloudExtensionOf(ref.fileName) } }
                attempt { pathFor(ref.localId, ext) }.getOrNull()?.let(::add)
            }
        }.distinct()
        for (path in candidates) {
            val found = exists(path) ?: continue
            return CloudStream(
                url = client.url(path).toString(),
                ext = ref.ext.ifBlank { cloudExtensionOf(path) },
                dataSourceFactory = dataSourceFactory,
                extra = buildMap { found.etag?.let { put("etag", it) } },
                via = VIA,
                contentLength = found.contentLength,
            )
        }
        throw CloudException(CloudErrorKind.NotFound, CloudMessages.CLOUD_DELETED, provider)
    }

    /** HEAD；个别服务器不让 HEAD（405），退到 PROPFIND Depth 0。 */
    private suspend fun exists(path: String): WebDavResource? = try {
        client.head(path)
    } catch (error: WebDavException) {
        if (error.kind == WebDavErrorKind.MethodNotAllowed) call { client.stat(path) } else throw toCloud(error)
    }

    // ---------------------------------------------------------------- 上传

    override suspend fun upload(
        source: CloudUploadSource,
        meta: CloudUploadMeta,
        progress: (CloudUploadProgress) -> Unit,
    ): CloudRef {
        val total = source.size
        progress(CloudUploadProgress(CloudUploadStage.QUEUED, 0, total))
        gate.awaitAllowed()
        if (total <= 0) throw CloudException(CloudErrorKind.InvalidFile, CloudMessages.EMPTY_FILE, provider)
        val ext = cleanExtension(meta.ext).ifBlank { cloudExtensionOf(source.fileName) }
        val target = pathFor(meta.localId, ext)

        progress(CloudUploadProgress(CloudUploadStage.CHECKING, 0, total))
        exists(target)?.let { existing ->
            if (sameSize(existing, target, total)) {
                val ref = refFor(target, source, meta, ext)
                progress(CloudUploadProgress(CloudUploadStage.READY, total, total, skipped = true, ref = ref))
                return ref
            }
            throw conflict()
        }
        call { client.ensureDirectory(bucketPath(meta.localId)) }

        gate.awaitAllowed()
        progress(CloudUploadProgress(CloudUploadStage.UPLOADING, 0, total))
        val report: (Long) -> Unit = { written -> progress(CloudUploadProgress(CloudUploadStage.UPLOADING, written, total)) }
        if (moveSupported) {
            val temp = tempPath(target, ext)
            var leftover = false
            try {
                leftover = true
                putSource(temp, source, ext, report)
                progress(CloudUploadProgress(CloudUploadStage.SUBMITTING, total, total))
                when (moveIntoPlace(temp, target, total)) {
                    MoveResult.Moved -> leftover = false
                    MoveResult.AlreadyThere -> Unit
                    MoveResult.Unsupported -> {
                        moveSupported = false
                        attempt { client.delete(temp) }
                        leftover = false
                        progress(CloudUploadProgress(CloudUploadStage.UPLOADING, 0, total))
                        putDirect(target, source, ext, total, report)
                    }
                }
            } finally {
                // 失败 / 取消 / 正式名已有同样的文件：临时文件删掉，不留垃圾
                if (leftover) withContext(NonCancellable) { attempt { client.delete(temp) } }
            }
        } else {
            putDirect(target, source, ext, total, report)
        }
        progress(CloudUploadProgress(CloudUploadStage.SUBMITTING, total, total))
        val ref = refFor(target, source, meta, ext)
        progress(CloudUploadProgress(CloudUploadStage.READY, total, total, ref = ref))
        return ref
    }

    private enum class MoveResult { Moved, AlreadyThere, Unsupported }

    /** MOVE 临时名 → 正式名，不覆盖。412 说明正式名已经有了（另一台设备刚传完同一首），比大小。 */
    private suspend fun moveIntoPlace(temp: String, target: String, total: Long): MoveResult = try {
        client.move(temp, target, overwrite = false)
        MoveResult.Moved
    } catch (error: WebDavException) {
        when {
            error.kind == WebDavErrorKind.PreconditionFailed -> {
                val existing = exists(target) ?: throw toCloud(error)
                if (!sameSize(existing, target, total)) throw conflict()
                MoveResult.AlreadyThere
            }
            error.status == 405 || error.status == 501 || error.status == 502 -> MoveResult.Unsupported
            else -> throw toCloud(error)
        }
    }

    /** 直接 PUT 正式名（`If-None-Match: *`）；412 时同样比大小。 */
    private suspend fun putDirect(target: String, source: CloudUploadSource, ext: String, total: Long, report: (Long) -> Unit) {
        try {
            putSource(target, source, ext, report)
        } catch (error: CloudException) {
            if (error.kind != CloudErrorKind.Conflict || error.code != 412) throw error
            val existing = exists(target) ?: throw error
            if (!sameSize(existing, target, total)) throw conflict()
        }
    }

    /**
     * 单次 PUT 整个文件，带 `If-None-Match: *`。用可取消的 [await]：协程取消时连接立刻断开，
     * 不会像阻塞写那样等到整首传完。WebDAV 不支持断点续传（规格 8.5），失败就整首重传。
     */
    private suspend fun putSource(path: String, source: CloudUploadSource, ext: String, report: (Long) -> Unit) {
        val job = currentCoroutineContext()[Job]
        val body = SourceChunkBody(source, 0, source.size, mediaTypeOf(ext), cancelled = { job?.isActive == false }, onWritten = report)
        val request = Request.Builder()
            .url(client.url(path))
            .put(body)
            .header("If-None-Match", "*")
            .build()
        val response = try {
            uploadClient.await(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw toCloud(WebDavClient.mapIoError(error))
        }
        response.use {
            if (!it.isSuccessful) throw toCloud(WebDavClient.httpError(it))
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun refFor(target: String, source: CloudUploadSource, meta: CloudUploadMeta, ext: String) = CloudRef(
        provider = provider.id,
        id = target,
        fileName = source.fileName,
        fileSize = source.size,
        status = CloudRefStatus.READY,
        uploadedAt = CloudRef.timestamp(now()),
        ext = ext,
        localId = meta.localId,
    )

    /** HEAD 没给 Content-Length（分块回应）时再用 PROPFIND 拿大小。 */
    private suspend fun sameSize(existing: WebDavResource, path: String, total: Long): Boolean {
        val length = existing.contentLength.takeIf { it >= 0 }
            ?: call { client.stat(path) }?.contentLength
            ?: -1
        return length == total
    }

    private fun entryOf(resource: WebDavResource): CloudEntry {
        val name = resource.name
        val base = name.substringBefore('.')
        return CloudEntry(
            provider = provider.id,
            id = resource.path,
            fileName = name,
            fileSize = resource.contentLength.coerceAtLeast(0),
            ext = cloudExtensionOf(name),
            addedAt = resource.lastModified ?: 0,
            localId = if (SHA256_HEX.matches(base)) LOCAL_ID_PREFIX + base.lowercase() else "",
            extra = buildMap { resource.etag?.let { put("etag", it) } },
        )
    }

    private fun tempPath(target: String, ext: String): String {
        val dir = target.substringBeforeLast('/', "")
        val key = target.substringAfterLast('/').substringBefore('.')
        val suffix = ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val name = ".$key${if (ext.isEmpty()) "" else ".$ext"}.$suffix.tmp"
        return if (dir.isEmpty()) name else "$dir/$name"
    }

    private fun conflict() = CloudException(
        CloudErrorKind.Conflict,
        "WebDAV 上同一位置已经有一个大小不同的文件，没有覆盖",
        provider,
        409,
    )

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: WebDavException) {
        throw toCloud(error)
    }

    /** WebDavException → CloudException。[CloudException.code] 保留 HTTP 状态码。 */
    internal fun toCloud(error: WebDavException): CloudException {
        val kind = when (error.kind) {
            WebDavErrorKind.Unauthorized -> CloudErrorKind.Unauthorized
            // 证书变了 / 不受信任：后面的文件也一样会失败，和 401 一样让队列停下等用户处理
            WebDavErrorKind.UntrustedCertificate, WebDavErrorKind.CertificateChanged -> CloudErrorKind.Unauthorized
            WebDavErrorKind.Forbidden -> CloudErrorKind.Forbidden
            WebDavErrorKind.NotFound -> CloudErrorKind.NotFound
            WebDavErrorKind.Conflict, WebDavErrorKind.PreconditionFailed -> CloudErrorKind.Conflict
            WebDavErrorKind.PayloadTooLarge -> CloudErrorKind.FileTooLarge
            WebDavErrorKind.InsufficientStorage -> CloudErrorKind.QuotaExceeded
            WebDavErrorKind.Network, WebDavErrorKind.Tls -> CloudErrorKind.Network
            WebDavErrorKind.InvalidResponse -> CloudErrorKind.InvalidResponse
            WebDavErrorKind.InvalidConfig -> CloudErrorKind.NotLoggedIn
            WebDavErrorKind.MethodNotAllowed, WebDavErrorKind.Locked, WebDavErrorKind.Http -> CloudErrorKind.Server
        }
        val message = when (error.kind) {
            WebDavErrorKind.Unauthorized -> CloudMessages.WEBDAV_UNAUTHORIZED
            WebDavErrorKind.NotFound -> CloudMessages.CLOUD_DELETED
            WebDavErrorKind.Network -> CloudMessages.CLOUD_UNREACHABLE
            else -> WebDavMessages.describe(error, client.account)
        }
        return CloudException(kind, message, provider, error.status, cause = error)
    }

    companion object {
        const val MUSIC_DIR = "music"
        const val VIA = "webdav"
        private const val LOCAL_ID_PREFIX = "sha256:"
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
        private val random = SecureRandom()

        /** localId 去掉 `sha256:`，只留字母数字、`-`、`_`，小写。 */
        fun fileKey(localId: String): String =
            localId.trim().removePrefix(LOCAL_ID_PREFIX).lowercase()
                .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

        fun bucketOf(key: String): String = key.take(2).padEnd(2, '0')

        /** `FLAC` / `.flac` → `flac`；和 [cloudExtensionOf] 同样只留字母数字、最多 8 个字符。 */
        private fun cleanExtension(ext: String): String =
            ext.trim().removePrefix(".").lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(8)

        private fun mediaTypeOf(ext: String): MediaType = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac", "alac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "ape" -> "audio/ape"
            else -> "application/octet-stream"
        }.toMediaType()
    }
}
