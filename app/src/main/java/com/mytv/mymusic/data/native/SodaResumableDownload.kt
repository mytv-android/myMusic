package com.mytv.mymusic.data.native

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 续传判断的纯函数，逐条对齐 libresoda `resume.rs::decide_resume_action`
 * （AGPL-3.0，见 THIRD_PARTY_NOTICES.md）。ETag **不参与**判断——它只用来发
 * `If-Range`，续不续得了由「状态码 + 范围头 + 本地字节数 + 密钥指纹」决定。
 */
internal enum class SodaResumeAction {
    /** 服务端给了 200 全量：丢掉已下载的字节，从头写。 */
    Rewrite,

    /** 206 且正好接着本地字节：追加。 */
    Append,

    /** 要重来，但这次响应不能直接用（416 / 范围对不上）：再发一次不带 Range 的请求。 */
    Refetch,

    /** 416 + 未知范围且总长等于本地字节数：已经下完了。 */
    Complete,

    /** 没法继续，直接失败。 */
    Fail,
}

/** 解析出来的 `Content-Range: bytes <first>-<last>/<total>`。 */
internal data class SodaRangeInfo(val first: Long?, val last: Long?, val total: Long)

/**
 * 解析 `Content-Range`。只认 `bytes` 单位；`*` 表示未知范围（416 响应里那种“总长已知、范围未知”的形态）。
 * 闭合区间要求 `last >= first` 且 `total > last`。
 */
internal fun sodaParseContentRange(header: String?): SodaRangeInfo? {
    val text = header?.trim().orEmpty()
    if (text.isEmpty()) return null
    val parts = text.split(' ').filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    if (!parts[0].equals("bytes", ignoreCase = true)) return null
    val spec = parts[1]
    val total = spec.substringAfter('/', "").trim().toLongOrNull() ?: return null
    val range = spec.substringBefore('/')
    if (range.trim() == "*") return SodaRangeInfo(null, null, total)
    val first = range.substringBefore('-').trim().toLongOrNull() ?: return null
    val last = range.substringAfter('-').trim().toLongOrNull() ?: return null
    if (last < first || total <= last) return null
    return SodaRangeInfo(first, last, total)
}

/**
 * 一次范围请求之后的动作。顺序照上游，一条都别调：
 * 1. 200 → Rewrite（密钥变没变都不看，全量最权威）；
 * 2. 密钥变了 → Refetch（旧字节解不开，必须重下）；
 * 3. 416 + 未知范围（“范围未知、只给总长”的 Content-Range）且 N 同时等于本地字节与记录的总长 → Complete；
 * 4. 206 且首字节正好是本地长度、总长与记录一致 → Append；
 * 5. 其余 → Refetch。
 */
internal fun sodaDecideResume(
    status: Int,
    range: SodaRangeInfo?,
    localLen: Long,
    recordedTotal: Long,
    keyChanged: Boolean,
): SodaResumeAction = when {
    status == 200 -> SodaResumeAction.Rewrite
    keyChanged -> SodaResumeAction.Refetch
    status == 416 && range != null && range.first == null && range.last == null &&
        range.total == localLen && range.total == recordedTotal -> SodaResumeAction.Complete
    status == 206 && range != null && range.first == localLen && range.total == recordedTotal ->
        SodaResumeAction.Append
    else -> SodaResumeAction.Refetch
}

/**
 * `{目标}.part` / `{目标}.part.meta` 的命名规则：后缀拼在整个文件名后面，不动扩展名
 * （对齐上游 `staged_paths`）。
 */
internal class SodaPartPaths(private val target: File) {
    val part: File get() = File(target.parentFile, "${target.name}$PART_SUFFIX")
    val meta: File get() = File(target.parentFile, "${target.name}$META_SUFFIX")

    /**
     * 可信的「本地字节 ↔ 记录」配对：任何一项对不上就当没有记录。
     * 0 字节的 `.part` 刻意不算——否则 416 那种“总长 0”的响应 会把空文件当完整交付。
     */
    fun trustedRecord(keyFingerprint: String): SodaPartRecord? {
        val partFile = part
        if (!partFile.isFile) return null
        val localLen = partFile.length()
        if (localLen <= 0L) return null
        val record = readMeta() ?: return null
        if (record.writtenBytes != localLen) return null
        if (record.totalLen < localLen) return null
        if (record.keyFingerprint != keyFingerprint) return null
        return record
    }

    private fun readMeta(): SodaPartRecord? {
        val file = meta
        if (!file.isFile || file.length() == 0L || file.length() > MAX_META_BYTES) return null
        val root = runCatching { SodaJson.parse(file.readText()) }.getOrNull() ?: return null
        val total = jsonLong(root, "total_len") ?: return null
        val written = jsonLong(root, "written_bytes") ?: return null
        return SodaPartRecord(
            totalLen = total,
            writtenBytes = written,
            keyFingerprint = jsonText(root, "key_fingerprint"),
        )
    }

    /** 记录原子写：先写临时名再改名，避免断电留下半份 JSON。 */
    fun writeMeta(totalLen: Long, writtenBytes: Long, keyFingerprint: String) {
        val body = sodaPartMetaJson(totalLen, writtenBytes, keyFingerprint)
        val tmp = File(meta.parentFile, "${meta.name}$TMP_SUFFIX")
        runCatching {
            tmp.writeText(body)
            if (!tmp.renameTo(meta)) {
                tmp.delete()
                meta.writeText(body)
            }
        }
    }

    fun discard() {
        runCatching { part.delete() }
        runCatching { meta.delete() }
    }

    companion object {
        const val PART_SUFFIX = ".part"
        const val META_SUFFIX = ".part.meta"
        const val TMP_SUFFIX = ".tmp"
        const val MAX_META_BYTES = 64L * 1024L
    }
}

internal data class SodaPartRecord(
    val totalLen: Long,
    val writtenBytes: Long,
    val keyFingerprint: String,
)

/**
 * 一次续传下载的结果。对齐 libresoda 的 `DownloadReport`：
 * 接上偏移 / 本次传输 / 磁盘累计 / 总长 / 状态码。
 */
internal data class SodaDownloadReport(
    val resumedFrom: Long = 0L,
    val transferred: Long = 0L,
    val onDisk: Long = 0L,
    val totalLen: Long = 0L,
    val status: Int = 0,
)

/** 下载结果：报告 + 下全的加密字节（解密前的内容）。 */
internal data class SodaDownloadResult(
    val report: SodaDownloadReport,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is SodaDownloadResult && report == other.report && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * report.hashCode() + bytes.contentHashCode()
}

/**
 * 汽水加密流的续传下载。
 *
 * 无损整曲可能几十 MB，`SODA_STREAM_CLIENT` 的 180 秒总超时一次下不完；这里把加密字节
 * 先落到 `{目标}.part`，失败后从已有字节接着要（`Range: bytes=<偏移>-`，只发开区间），
 * 全部到位再解密。
 *
 * 与上游的两处刻意差异：
 * - 没搬锁文件：Android 上同一进程同时下同一首歌的概率极低，同名覆盖本身就是最后一次
 *   写入胜出，残留的 `.part` 由 `pruneCache` 按修改时间清；
 * - `If-Range` 不带 ETag：本地没有可信的服务端标识，带了只会让请求更容易被拒。
 */
internal class SodaResumableDownloader(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val maxAttempts: Int = 3,
) {
    /**
     * 把 [url] 的内容下全并返回字节。[keyFingerprint] 变了（换签名 / 换档）就丢弃旧字节重下。
     * [onProgress] 回传（已落盘字节, 本次新增），调用方可以驱动界面进度。
     */
    suspend fun download(
        url: String,
        target: File,
        keyFingerprint: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): SodaDownloadResult {
        val paths = SodaPartPaths(target)
        val record = paths.trustedRecord(keyFingerprint)
        var offset = record?.writtenBytes ?: 0L
        var totalLen = record?.totalLen ?: 0L
        var transferred = 0L
        var status = 0
        var attempts = 0
        var done = false

        while (attempts < maxAttempts && !done) {
            attempts++
            val response = fetchRange(url, offset)
            status = response.code
            val range = sodaParseContentRange(response.header("Content-Range"))
            when (sodaDecideResume(status, range, offset, totalLen, keyChanged = false)) {
                SodaResumeAction.Rewrite -> {
                    paths.discard()
                    offset = 0L
                    totalLen = 0L
                    transferred += pump(response, paths.part, append = false, onProgress)
                    totalLen = range?.total ?: transferred
                    paths.writeMeta(totalLen, transferred, keyFingerprint)
                    offset = transferred
                    // 200 是整份，读到 EOF 就是下完了
                    done = true
                }
                SodaResumeAction.Append -> {
                    val written = pump(response, paths.part, append = true, onProgress)
                    transferred += written
                    totalLen = range?.total ?: totalLen
                    paths.writeMeta(totalLen, offset + written, keyFingerprint)
                    offset += written
                    done = totalLen in 1..offset
                }
                SodaResumeAction.Refetch -> {
                    // 服务端不认这个偏移：丢掉重来，且这次不带 Range（上游同款）
                    paths.discard()
                    offset = 0L
                    totalLen = 0L
                    val again = fetchRange(url, 0L, withRange = false)
                    status = again.code
                    if (again.code != 200 && again.code != 206) {
                        throw IOException("soda audio download failed: HTTP $status")
                    }
                    val written = pump(again, paths.part, append = false, onProgress)
                    transferred += written
                    totalLen = sodaParseContentRange(again.header("Content-Range"))?.total ?: written
                    paths.writeMeta(totalLen, written, keyFingerprint)
                    offset = written
                    done = true
                }
                SodaResumeAction.Complete -> {
                    val bytes = paths.part.takeIf { it.isFile }?.readBytes() ?: ByteArray(0)
                    paths.discard()
                    return SodaDownloadResult(
                        SodaDownloadReport(
                            resumedFrom = offset,
                            transferred = 0L,
                            onDisk = offset,
                            totalLen = offset,
                            status = status,
                        ),
                        bytes,
                    )
                }
                SodaResumeAction.Fail -> throw IOException("soda audio download failed: HTTP $status")
            }
        }

        val bytes = paths.part.takeIf { it.isFile }?.readBytes() ?: ByteArray(0)
        if (!done || (totalLen > 0 && bytes.size.toLong() != totalLen)) {
            // 试完了还差一截：留着 .part 下次接着用，不假装下完
            throw IOException("soda audio download incomplete: ${bytes.size}/$totalLen")
        }
        paths.discard()
        return SodaDownloadResult(
            SodaDownloadReport(
                resumedFrom = if (transferred > 0) offset - transferred else 0L,
                transferred = transferred,
                onDisk = bytes.size.toLong(),
                totalLen = totalLen,
                status = status,
            ),
            bytes,
        )
    }

    /** 一次范围请求：只发开区间 `bytes=<offset>-`，4xx/5xx 原样返回不抛。 */
    private suspend fun fetchRange(url: String, offset: Long, withRange: Boolean = true): Response {
        val builder = Request.Builder().url(url).get().header("User-Agent", userAgent)
        if (withRange) builder.header("Range", "bytes=$offset-")
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                }
            })
        }
    }

    /** 把响应体写进 `.part`；返回写入字节数。响应体由本方法关闭。 */
    private fun pump(
        response: Response,
        part: File,
        append: Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Long = response.use { raw ->
        val source = raw.body ?: throw IOException("soda audio download failed: empty body")
        part.parentFile?.mkdirs()
        RandomAccessFile(part, "rw").use { file ->
            if (!append) file.setLength(0L)
            file.seek(if (append) file.length() else 0L)
            val buffer = ByteArray(CHUNK)
            var written = 0L
            source.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    file.write(buffer, 0, read)
                    written += read
                    onProgress(file.length(), written)
                }
            }
            file.fd.sync()
            written
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}

/** `.part.meta` 的内容：四个字段，顺序固定，方便人看也方便比对。 */
internal fun sodaPartMetaJson(totalLen: Long, writtenBytes: Long, keyFingerprint: String): String =
    """{"total_len":$totalLen,"etag":"","key_fingerprint":"${sodaJsonEscape(keyFingerprint)}","written_bytes":$writtenBytes}"""

internal fun sodaJsonEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
