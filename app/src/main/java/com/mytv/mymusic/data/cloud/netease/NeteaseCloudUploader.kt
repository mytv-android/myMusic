package com.mytv.mymusic.data.cloud.netease

import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudRef
import com.mytv.mymusic.data.cloud.CloudRefStatus
import com.mytv.mymusic.data.cloud.CloudUploadCandidate
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadProgress
import com.mytv.mymusic.data.cloud.CloudUploadSource
import com.mytv.mymusic.data.cloud.CloudUploadStage
import com.mytv.mymusic.data.cloud.UploadGate
import com.mytv.mymusic.data.cloud.UploadPrecheck
import com.mytv.mymusic.data.cloud.attempt
import com.mytv.mymusic.data.cloud.cloudBaseNameOf
import com.mytv.mymusic.data.cloud.cloudExtensionOf
import com.mytv.mymusic.data.native.NativeCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeHex
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 网易云盘上传状态机（规格 6.3 / 6.5）：
 *
 * ```
 * QUEUED → HASHING → CHECKING → UPLOADING → SUBMITTING → TRANSCODING → PUBLISHING → READY
 *                                                                             \→ FAILED(retryable?)
 * ```
 *
 * - 每个文件一个冷 Flow，最后一项一定是 READY 或 FAILED（取消除外）。收集方取消就停在当前这一片；
 *   断点（MD5、查重结果、令牌、NOS offset/context、已提交的 songId、转码 / 发布进度）按 localId
 *   记在内存里，同一进程里重试从断点接着走，不重算 MD5、不重传已确认的分片。
 * - 秒传（needUpload=false）跳过 lbs 和 NOS 直传，但令牌照样申请：info/v2 要 resourceId。
 * - 写接口（info/v2、pub/v2、match）超时先查 byids / 最近列表确认，确认没生效才重发。
 * - 每个文件开始前、每一片之前都过一次 [gate]（只在 Wi-Fi 下上传之类由集成层实现）。
 * - 串行和请求间隔由 [NeteaseCloudApi] 统一控制；队列、-460 暂停见 CloudUploadQueue。
 */
class NeteaseCloudUploader internal constructor(
    private val api: NeteaseCloudApi,
    private val gate: UploadGate,
) {
    private val checkpoints = ConcurrentHashMap<String, Checkpoint>()
    private val config: NeteaseCloudConfig get() = api.config

    fun upload(source: CloudUploadSource, meta: CloudUploadMeta): Flow<CloudUploadProgress> = channelFlow {
        val run = Run(source, meta, this)
        try {
            run.execute()
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudException) {
            send(run.failed(error))
        } catch (error: IOException) {
            send(run.failed(api.networkError(error)))
        } catch (error: Exception) {
            send(
                run.failed(
                    CloudException(
                        CloudErrorKind.InvalidResponse,
                        "上传到网易云盘时出错：${error.message ?: error.javaClass.simpleName}",
                        CloudProvider.NeteaseCloud,
                        cause = error,
                    ),
                ),
            )
        }
    }.buffer(Channel.UNLIMITED)

    /** 上传前预检：单文件 500MB、按 `maxSize - size` 算剩余容量（规格 12）。 */
    suspend fun precheck(items: List<CloudUploadCandidate>): UploadPrecheck =
        UploadPrecheck.plan(items, api.quota(), config.maxFileSize)

    /** 这个文件有没有可以接着用的断点。 */
    fun hasCheckpoint(localId: String): Boolean = checkpoints.containsKey(localId)

    /** 用户删掉排队项时丢掉断点。 */
    fun discard(localId: String) {
        checkpoints.remove(localId)
    }

    /** 断点。只在同一个文件版本（大小 + 修改时间）上复用。 */
    private class Checkpoint(val version: String) {
        @Volatile var md5: String? = null
        @Volatile var check: NeteaseUploadCheck? = null
        @Volatile var token: NeteaseNosToken? = null
        @Volatile var hosts: List<String>? = null
        @Volatile var hostIndex = 0
        @Volatile var offset = 0L
        @Volatile var context: String? = null
        @Volatile var uploaded = false
        @Volatile var base64Md5 = false
        @Volatile var cloudSongId: String? = null
        @Volatile var ready = false
        @Volatile var published = false
    }

    private inner class Run(
        private val source: CloudUploadSource,
        private val meta: CloudUploadMeta,
        private val out: ProducerScope<CloudUploadProgress>,
    ) {
        private val total = source.size
        private val ext = meta.ext.ifBlank { cloudExtensionOf(source.fileName) }
        private val key = meta.localId.ifBlank { source.fileName }
        private lateinit var cp: Checkpoint
        private var lastBytes = 0L

        private fun emit(stage: CloudUploadStage, done: Long = 0, instant: Boolean = false, ref: CloudRef? = null) {
            lastBytes = done
            out.trySend(CloudUploadProgress(stage, done, total, instant = instant, ref = ref))
        }

        fun failed(error: CloudException) =
            CloudUploadProgress(CloudUploadStage.FAILED, lastBytes, total, error = error)

        suspend fun execute() {
            emit(CloudUploadStage.QUEUED)
            gate.awaitAllowed()
            if (total <= 0) throw CloudException(CloudErrorKind.InvalidFile, CloudMessages.EMPTY_FILE, CloudProvider.NeteaseCloud)
            if (total > config.maxFileSize) {
                throw CloudException(
                    CloudErrorKind.FileTooLarge,
                    CloudMessages.fileTooLarge(config.maxFileSize),
                    CloudProvider.NeteaseCloud,
                )
            }
            api.requireCookie()
            cp = checkpoints[key]?.takeIf { it.version == source.version }
                ?: Checkpoint(source.version).also { checkpoints[key] = it }

            val md5 = cp.md5 ?: hash().also { cp.md5 = it }

            emit(CloudUploadStage.CHECKING)
            val check = cp.check ?: run {
                // 秒传也占容量（条目照样进云盘），所以放在查重前预检
                val shortfall = api.quota().shortfall(total)
                if (shortfall > 0) {
                    throw CloudException(
                        CloudErrorKind.QuotaExceeded,
                        CloudMessages.quotaExceeded(shortfall),
                        CloudProvider.NeteaseCloud,
                        shortfall = shortfall,
                    )
                }
                api.uploadCheck(md5, total, ext).also { cp.check = it }
            }
            // 无论是否秒传都要令牌：info/v2 要 resourceId（规格 6.3 第 3 步）
            if (cp.token == null) cp.token = api.allocToken(source.fileName, total, ext, md5)

            if (check.needUpload && !cp.uploaded) {
                uploadChunks(md5)
            } else {
                emit(CloudUploadStage.UPLOADING, total, instant = !check.needUpload)
            }
            cp.uploaded = true

            val songId = cp.cloudSongId ?: submit(md5, check).also { cp.cloudSongId = it }
            if (!cp.ready) {
                waitReady(songId)
                cp.ready = true
            }
            if (!cp.published) {
                publish(songId, md5)
                cp.published = true
            }
            val ref = finish(songId, md5)
            checkpoints.remove(key)
            emit(CloudUploadStage.READY, total, instant = !check.needUpload, ref = ref)
        }

        /** 整文件 MD5（十六进制）。读的字节数和登记的大小对不上，说明文件刚被改过。 */
        private suspend fun hash(): String = withContext(Dispatchers.IO) {
            emit(CloudUploadStage.HASHING, 0)
            val digest = MessageDigest.getInstance("MD5")
            var done = 0L
            source.open(0).use { input ->
                val buffer = ByteArray(HASH_BUFFER)
                var reported = 0L
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    done += read
                    if (done - reported >= HASH_REPORT_STEP) {
                        reported = done
                        emit(CloudUploadStage.HASHING, done)
                    }
                }
            }
            if (done != total) {
                throw CloudException(
                    CloudErrorKind.InvalidFile,
                    "文件在上传前被改过，请重新扫描后再传",
                    CloudProvider.NeteaseCloud,
                )
            }
            emit(CloudUploadStage.HASHING, total)
            NativeCrypto.bytesToHex(digest.digest())
        }

        /**
         * NOS 分片直传。以服务端回的 offset 为准推进；超时 / 断线先问 NOS 收到了多少再接着传；
         * 400 换 base64 的 Content-MD5 重传这一片；令牌过期（401/403）换令牌从头传一次。
         */
        private suspend fun uploadChunks(md5: String) {
            emit(CloudUploadStage.UPLOADING, cp.offset)
            val hosts = cp.hosts ?: api.uploadHosts().also { cp.hosts = it }
            val job = currentCoroutineContext()[Job]
            var failures = 0
            var tokenRefreshed = false
            while (cp.offset < total) {
                gate.awaitAllowed()
                currentCoroutineContext().ensureActive()
                val token = checkNotNull(cp.token)
                val offset = cp.offset
                val length = minOf(config.chunkSize, total - offset)
                val complete = offset + length >= total
                val host = hosts[cp.hostIndex % hosts.size]
                val contentMd5 = if (cp.base64Md5) md5.decodeHex().base64() else md5
                try {
                    val result = api.nosUploadChunk(
                        host = host,
                        token = token,
                        source = source,
                        offset = offset,
                        length = length,
                        complete = complete,
                        context = cp.context,
                        contentMd5 = contentMd5,
                        cancelled = { job?.isActive == false },
                    ) { written -> emit(CloudUploadStage.UPLOADING, offset + written) }
                    if (result.context.isNotBlank()) cp.context = result.context
                    cp.offset = if (result.offset > offset) result.offset.coerceAtMost(total) else offset + length
                    failures = 0
                    emit(CloudUploadStage.UPLOADING, cp.offset)
                } catch (error: NosHttpException) {
                    currentCoroutineContext().ensureActive()
                    when {
                        // 规格 6.3：Content-MD5 按 NCMApi 传十六进制，服务端 400 再改 base64
                        error.code == 400 && !cp.base64Md5 -> cp.base64Md5 = true
                        (error.code == 401 || error.code == 403) && !tokenRefreshed -> {
                            // 令牌过期：换新令牌从头传；resourceId 跟着换，info/v2 用新的
                            tokenRefreshed = true
                            cp.token = api.allocToken(source.fileName, total, ext, md5)
                            cp.offset = 0
                            cp.context = null
                        }
                        error.code >= 500 && failures < config.maxRetries -> {
                            failures++
                            delay(config.retryBackoffMs shl (failures - 1))
                            recoverOffset(host, token)
                        }
                        else -> throw CloudException(
                            CloudErrorKind.Server,
                            "上传到网易云盘失败（HTTP ${error.code}）",
                            CloudProvider.NeteaseCloud,
                            error.code,
                            cause = error,
                        )
                    }
                } catch (error: CloudException) {
                    throw error
                } catch (error: IOException) {
                    // 取消时 OkHttp 也抛 IOException，先让取消照常传出去
                    currentCoroutineContext().ensureActive()
                    if (failures >= config.maxRetries) throw api.networkError(error)
                    failures++
                    delay(config.retryBackoffMs shl (failures - 1))
                    // 超时 / 断线：服务端可能已经收下了这一片，先问清楚再接着传，不盲目重发
                    recoverOffset(host, token)
                }
            }
        }

        private suspend fun recoverOffset(host: String, token: NeteaseNosToken) {
            val context = cp.context
            val confirmed = context?.let { api.nosQueryOffset(host, token, it) }
            if (confirmed != null && confirmed in 0..total) {
                cp.offset = confirmed
            } else {
                // 第一片就断了（还没有 context）或查不到：只能从头传，顺便换个节点
                cp.offset = 0
                cp.context = null
                cp.hostIndex++
            }
        }

        /**
         * info/v2。非 200 时：`rep create failed` 按规格 6.3 不算失败，接着轮询转码状态；
         * 其他错误码照 ncmExtend 在一分钟窗口里每秒重提。超时先确认是否已入库再决定重发。
         */
        private suspend fun submit(md5: String, check: NeteaseUploadCheck): String {
            emit(CloudUploadStage.SUBMITTING, total)
            val title = meta.title.ifBlank { cloudBaseNameOf(source.fileName) }
            val album = meta.album.ifBlank { "未知专辑" }
            val artist = meta.artist.ifBlank { "未知艺术家" }
            val deadline = api.now() + config.submitRetryWindowMs
            var unknownRounds = 0
            while (true) {
                val token = checkNotNull(cp.token)
                val result = try {
                    api.submitInfo(md5, check.songId, source.fileName, title, album, artist, token.resourceId)
                } catch (unknown: NeteaseOutcomeUnknown) {
                    confirmSubmitted(md5, check.songId)?.let { return it }
                    if (unknownRounds++ >= 1) throw api.networkError(unknown.cause)
                    continue
                }
                if (result.ok) return result.songId
                if (result.repCreateFailed) {
                    return result.songId.takeIf { it.isNotBlank() && it != "0" } ?: check.songId
                }
                if (api.now() >= deadline) {
                    throw CloudException(
                        CloudErrorKind.Server,
                        "网易云盘没收下这个文件（${result.code}）" + result.message.let { if (it.isBlank()) "" else "：$it" },
                        CloudProvider.NeteaseCloud,
                        result.code,
                    )
                }
                delay(config.submitRetryDelayMs)
            }
        }

        private suspend fun confirmSubmitted(md5: String, checkSongId: String): String? {
            attempt { api.findEntry(checkSongId) }.getOrNull()?.let { return it.songId.toString() }
            attempt { api.findRecent(md5, source.fileName, total, checkSongId) }.getOrNull()
                ?.let { return it.songId.toString() }
            return null
        }

        /** 轮询转码：9 就绪、1 处理中，间隔 3–5 秒，最长 10 分钟；查不到状态最多容忍一分钟。 */
        private suspend fun waitReady(songId: String) {
            emit(CloudUploadStage.TRANSCODING, total)
            val start = api.now()
            while (true) {
                val status = api.transcodeStatus(songId)
                val elapsed = api.now() - start
                when {
                    status?.ready == true -> return
                    status == null -> if (elapsed >= config.missingStatusGraceMs) {
                        throw CloudException(
                            CloudErrorKind.TranscodeFailed,
                            "网易云盘查不到这个文件的处理状态，可以重试",
                            CloudProvider.NeteaseCloud,
                        )
                    }
                    status.processing -> Unit
                    else -> throw CloudException(
                        CloudErrorKind.TranscodeFailed,
                        CloudMessages.transcodeFailed(status.status),
                        CloudProvider.NeteaseCloud,
                        status.status,
                    )
                }
                if (elapsed >= config.transcodeTimeoutMs) {
                    throw CloudException(CloudErrorKind.TranscodeTimeout, CloudMessages.TRANSCODE_TIMEOUT, CloudProvider.NeteaseCloud)
                }
                val suggested = (status?.waitTimeSec ?: 0) * 1000L
                delay(suggested.coerceIn(config.statusPollMinMs, config.statusPollMaxMs))
            }
        }

        /** pub/v2。超时先看最近列表里有没有（发布后才出现在云盘列表），没有才重发一次。 */
        private suspend fun publish(songId: String, md5: String) {
            emit(CloudUploadStage.PUBLISHING, total)
            repeat(2) { round ->
                try {
                    api.publish(songId)
                    return
                } catch (unknown: NeteaseOutcomeUnknown) {
                    if (attempt { api.findRecent(md5, source.fileName, total, songId) }.getOrNull() != null) return
                    if (round == 1) throw api.networkError(unknown.cause)
                }
            }
        }

        /**
         * 收尾：查回条目拿 `simpleSong.id`（规格 3.2 的解析 ID），可选地做一次 user/song/match。
         * 匹配失败不算上传失败：文件已经在云盘里了，playableId 留空即可。
         */
        private suspend fun finish(songId: String, md5: String): CloudRef {
            var item = attempt { api.findEntry(songId) }.getOrNull()
                ?: attempt { api.findRecent(md5, source.fileName, total, songId) }.getOrNull()
            var playableId = ""
            val target = meta.neteaseMatchId.trim()
            if (target.isNotEmpty() && target != "0" && item?.simpleId?.toString() != target) {
                val base = CloudRef(
                    provider = CloudProvider.NeteaseCloud.id,
                    id = item?.resolveId ?: songId,
                    cloudSongId = songId,
                )
                val matched = attempt { api.matchConfirmed(base, target) }
                matched.getOrNull()?.let { item = it }
                if (matched.isSuccess) playableId = target
            }
            val id = item?.resolveId ?: songId
            return CloudRef(
                provider = CloudProvider.NeteaseCloud.id,
                id = id,
                playableId = playableId.takeIf { it != id }.orEmpty(),
                fileName = source.fileName,
                fileSize = total,
                status = CloudRefStatus.READY,
                uploadedAt = CloudRef.timestamp(api.now()),
                ext = ext,
                md5 = md5,
                cloudSongId = item?.songId?.takeIf { it > 0 }?.toString() ?: songId,
                localId = meta.localId,
            )
        }
    }

    private companion object {
        const val HASH_BUFFER = 256 * 1024
        const val HASH_REPORT_STEP = 4L * 1024 * 1024
    }
}
