package com.mytv.mymusic.data.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Authenticator
import okhttp3.Challenge
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

enum class WebDavErrorKind {
    Unauthorized,
    Forbidden,
    NotFound,
    MethodNotAllowed,
    Conflict,
    PreconditionFailed,
    Locked,
    PayloadTooLarge,
    InsufficientStorage,
    Http,
    UntrustedCertificate,

    /** 打开了“信任自签名证书”，服务器给的证书却和本机记下的指纹不一样（系统也不认）。 */
    CertificateChanged,
    Tls,
    Network,
    InvalidResponse,
    InvalidConfig,
}

/**
 * 继承 IOException：以后接 Media3 / 下载时，它能沿着 IO 异常的路径往上走。
 * [certificate] 只在 [WebDavErrorKind.CertificateChanged] 时有，是这次服务器给的那张证书。
 */
class WebDavException(
    val kind: WebDavErrorKind,
    message: String,
    val status: Int = 0,
    cause: Throwable? = null,
    val certificate: WebDavCertificate? = null,
) : IOException(message, cause)

/** PUT / DELETE 的前置条件：两台设备同时写同一个文件时靠它防覆盖。 */
sealed interface WebDavCondition {
    data object None : WebDavCondition

    /** 只在文件不存在时写（`If-None-Match: *`），用于第一次创建。 */
    data object IfNoneMatchAny : WebDavCondition

    /** 只在服务器上的版本还是 [etag] 时写，否则 412。 */
    data class IfMatch(val etag: String) : WebDavCondition

    companion object {
        /**
         * 弱 ETag（`W/"…"`）按 RFC 7232 永远不满足 If-Match 的强比较——Apache 在文件刚改完的
         * 那一秒里就会发弱 ETag，带上它只会每次 412。这种时候退回不带条件，由上层读回比较兜底。
         */
        fun ifMatch(etag: String?): WebDavCondition {
            val value = etag?.trim().orEmpty()
            return if (value.isEmpty() || value.startsWith("W/", ignoreCase = true)) None else IfMatch(value)
        }
    }
}

class WebDavContent(
    val bytes: ByteArray,
    val status: Int,
    val etag: String?,
    val lastModified: Long?,
    val contentType: String?,
    val contentRange: String?,
) {
    /** 206：服务器按 Range 只回了一部分。 */
    val partial: Boolean get() = status == 206

    fun text(): String = bytes.toString(Charsets.UTF_8)
}

data class WebDavPutResult(val etag: String?, val created: Boolean)

/**
 * 给 Media3 用的播放源：地址 + 认证头。Basic 认证时直接塞进
 * `DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)` 就能流式播放、拖动；
 * [needsClient] 为 true（Digest 认证或信任自签名证书）时请求头不够，要用
 * `OkHttpDataSource.Factory(client.http)`，认证和证书都在那个 OkHttpClient 里。
 * 地址是临时拼出来的，不要写进同步文件。
 */
data class WebDavStream(val url: String, val headers: Map<String, String>, val needsClient: Boolean)

enum class WebDavCheckStep(val label: String) {
    ReadDirectory("读取目录"),
    CreateDirectory("创建目录"),
    Write("写入测试文件"),
    ReadBack("读回测试文件"),
    Delete("删除测试文件"),
}

data class WebDavCheckResult(
    val failedStep: WebDavCheckStep? = null,
    val error: WebDavException? = null,
    val supportsRange: Boolean = false,
    val createdDirectory: Boolean = false,
    /** 目录里已经有 [WebDavClient.check] 要找的文件（同步时就是已有同步文件）。 */
    val foundFile: Boolean = false,
) {
    val ok: Boolean get() = failedStep == null
}

/**
 * 通用 WebDAV 客户端：同步文件和以后上传、流播的歌都走它。
 *
 * 所有路径都相对 [WebDavAccount.serverUrl]（`myMusic/mymusic-library.json`、
 * `myMusic/music/9f/9f3a….flac`），由 [WebDavPaths] 负责转义和拼接；目录要以 `/` 结尾。
 *
 * 每个账号一个 [OkHttpClient]：认证拦截器、Digest 状态和“信任自签名证书”的指纹校验都只挂在
 * 这一个客户端上，不会影响应用里其他请求。它从 [base] 派生，共用连接池和线程，但 OkHttp 按
 * 证书配置区分连接，按指纹放行的连接不会被别的客户端复用。
 */
class WebDavClient(
    val account: WebDavAccount,
    private val base: OkHttpClient = defaultBase,
) {
    val baseUrl: HttpUrl = account.baseUrl
        ?: throw WebDavException(WebDavErrorKind.InvalidConfig, "服务器地址格式不对")

    private val auth = WebDavAuth(account.username, account.password)

    val http: OkHttpClient = base.newBuilder()
        .addInterceptor(auth)
        .authenticator(auth)
        .apply {
            if (account.trustSelfSigned) pinTrust(base, baseUrl.host, account.pinnedCertificate?.sha256.orEmpty().uppercase())
        }
        .build()

    fun url(path: String): HttpUrl = WebDavPaths.url(baseUrl, path)

    /** PROPFIND；Depth 1 时结果里不含目录自身。 */
    suspend fun list(path: String, depth: Int = 1): List<WebDavResource> {
        val items = propfind(collectionPath(path), depth)
        if (depth == 0) return items
        val self = WebDavPaths.segments(path)
        return items.filter { WebDavPaths.segments(it.path) != self }
    }

    /** PROPFIND Depth 0；不存在时返回 null。 */
    suspend fun stat(path: String): WebDavResource? = try {
        propfind(path, 0).firstOrNull()
    } catch (error: WebDavException) {
        if (error.kind == WebDavErrorKind.NotFound) null else throw error
    }

    /** 逐级 MKCOL。返回是否新建了目录。 */
    suspend fun ensureDirectory(path: String): Boolean {
        val parts = WebDavPaths.segments(path)
        if (parts.isEmpty()) {
            requireCollection("", stat("") ?: throw notFoundBase())
            return false
        }
        val full = collectionOf(parts)
        stat(full)?.let {
            requireCollection(full, it)
            return false
        }
        var missing = false
        for (index in 1..parts.size) {
            val level = collectionOf(parts.take(index))
            if (!missing) {
                // 最后一级上面刚查过，不再多发一次 PROPFIND
                val existing = if (index == parts.size) null else stat(level)
                if (existing != null) {
                    requireCollection(level, existing)
                    continue
                }
                // 第一层就不存在时先确认服务器地址本身对不对，不然用户只会看到看不懂的 409
                if (index == 1 && stat("") == null) throw notFoundBase()
                missing = true
            }
            if (!mkcol(level)) {
                // 405 说明这一级已经有东西。同名文件用带斜杠的地址查不到，去掉斜杠再看一眼，
                // 免得后面 PUT 回一个“上级目录不存在”的 409 把人带偏
                runCatching { stat(level.trimEnd('/')) }.getOrNull()?.let { requireCollection(level, it) }
            }
        }
        return true
    }

    /** 建单个目录；已存在（405）返回 false。父目录不存在时是 409。 */
    suspend fun mkcol(path: String): Boolean {
        val request = Request.Builder().url(url(collectionPath(path))).method("MKCOL", null).build()
        return execute(request) { response ->
            when (response.code) {
                201, 200, 204 -> true
                405 -> false
                else -> throw httpError(response)
            }
        }
    }

    suspend fun head(path: String): WebDavResource? {
        val request = Request.Builder().url(url(path)).head().build()
        return execute(request) { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw httpError(response)
                else -> WebDavResource(
                    path = path,
                    isCollection = WebDavPaths.isCollectionPath(path),
                    contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1,
                    etag = response.etag(),
                    contentType = response.header("Content-Type"),
                    lastModified = WebDavXml.parseHttpDate(response.header("Last-Modified")),
                )
            }
        }
    }

    /**
     * GET 整个文件或其中一段。[range] 的 last 为 Long.MAX_VALUE 时表示读到结尾（`bytes=100-`）。
     * 超过 [maxBytes] 直接报错，免得目录里放错一个大文件把内存撑爆。
     */
    suspend fun read(path: String, range: LongRange? = null, maxBytes: Long = DEFAULT_MAX_READ_BYTES): WebDavContent {
        val builder = Request.Builder().url(url(path)).get()
        range?.let { builder.header("Range", rangeHeader(it)) }
        return execute(builder.build()) { response ->
            if (response.code != 200 && response.code != 206) throw httpError(response)
            val source = response.body.source()
            // request(n) 为 true 说明还没到结尾就已经攒够了 maxBytes + 1 字节
            if (source.request(maxBytes + 1)) {
                throw WebDavException(WebDavErrorKind.InvalidResponse, "文件太大，超过 ${maxBytes / 1024} KB")
            }
            WebDavContent(
                bytes = source.readByteArray(),
                status = response.code,
                etag = response.etag(),
                lastModified = WebDavXml.parseHttpDate(response.header("Last-Modified")),
                contentType = response.header("Content-Type"),
                contentRange = response.header("Content-Range"),
            )
        }
    }

    /** 流式下载：[block] 拿到的 Response 状态已检查过（200 / 206），读完由这里关闭。 */
    suspend fun <T> download(path: String, range: LongRange? = null, block: (Response) -> T): T {
        val builder = Request.Builder().url(url(path)).get()
        range?.let { builder.header("Range", rangeHeader(it)) }
        return execute(builder.build()) { response ->
            if (response.code != 200 && response.code != 206) throw httpError(response)
            block(response)
        }
    }

    suspend fun put(path: String, body: RequestBody, condition: WebDavCondition = WebDavCondition.None): WebDavPutResult {
        val builder = Request.Builder().url(url(path)).put(body)
        applyCondition(builder, condition)
        return execute(builder.build()) { response ->
            if (!response.isSuccessful) throw httpError(response)
            WebDavPutResult(etag = response.etag(), created = response.code == 201)
        }
    }

    suspend fun putBytes(
        path: String,
        bytes: ByteArray,
        contentType: MediaType? = OCTET_STREAM,
        condition: WebDavCondition = WebDavCondition.None,
    ): WebDavPutResult = put(path, bytes.toRequestBody(contentType), condition)

    /** 大文件单次 PUT；File 的 RequestBody 自带 Content-Length，服务器能提前拒绝超限的上传。 */
    suspend fun putFile(
        path: String,
        file: File,
        contentType: MediaType? = OCTET_STREAM,
        condition: WebDavCondition = WebDavCondition.None,
    ): WebDavPutResult = put(path, file.asRequestBody(contentType), condition)

    /** 删除；本来就不存在时返回 false。 */
    suspend fun delete(path: String, condition: WebDavCondition = WebDavCondition.None): Boolean {
        val builder = Request.Builder().url(url(path)).delete()
        applyCondition(builder, condition)
        return execute(builder.build()) { response ->
            when {
                response.code == 404 -> false
                response.isSuccessful -> true
                else -> throw httpError(response)
            }
        }
    }

    suspend fun move(from: String, to: String, overwrite: Boolean = false) {
        val request = Request.Builder()
            .url(url(from))
            .method("MOVE", null)
            .header("Destination", url(to).toString())
            .header("Overwrite", if (overwrite) "T" else "F")
            .build()
        execute(request) { response -> if (!response.isSuccessful) throw httpError(response) }
    }

    /**
     * 容量（RFC 4331）：PROPFIND Depth 0 只要 quota-available-bytes / quota-used-bytes。
     * 服务器不支持时返回 null；路径不存在照常抛 NotFound。
     */
    suspend fun quota(path: String = ""): WebDavQuota? {
        val request = Request.Builder()
            .url(url(collectionPath(path)))
            .method("PROPFIND", QUOTA_BODY.toRequestBody(XML))
            .header("Depth", "0")
            .build()
        return execute(request) { response ->
            if (response.code != 207 && response.code != 200) throw httpError(response)
            WebDavXml.parseQuota(response.body.string())
        }
    }

    fun stream(path: String): WebDavStream = WebDavStream(
        url = url(path).toString(),
        headers = mapOf("Authorization" to Credentials.basic(account.username, account.password, Charsets.UTF_8)),
        needsClient = account.trustSelfSigned || auth.usesDigest,
    )

    /**
     * 测试连接：PROPFIND 目录（不存在就逐级 MKCOL），写一个小文件、读回、用 `Range: bytes=0-0`
     * 看能不能断点读，最后删掉。哪一步失败就停在哪一步，写过的测试文件尽量删掉。
     */
    suspend fun check(directory: String = account.normalizedDirectory, lookFor: String? = null): WebDavCheckResult {
        val dir = collectionPath(directory)
        var step = WebDavCheckStep.ReadDirectory
        var created = false
        var found = false
        var written: String? = null
        return try {
            val listing = try {
                list(dir, depth = 1)
            } catch (error: WebDavException) {
                if (error.kind != WebDavErrorKind.NotFound) throw error
                null
            }
            if (listing == null) {
                step = WebDavCheckStep.CreateDirectory
                created = ensureDirectory(dir)
            } else if (lookFor != null) {
                found = listing.any { !it.isCollection && it.name == lookFor }
            }
            step = WebDavCheckStep.Write
            val probe = WebDavPaths.join(dir, ".mymusic-probe-${randomHex(6)}.txt")
            val payload = "myMusic WebDAV 连接测试 ${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
            putBytes(probe, payload, TEXT, WebDavCondition.IfNoneMatchAny)
            written = probe
            step = WebDavCheckStep.ReadBack
            val back = read(probe, maxBytes = 64 * 1024)
            if (!back.bytes.contentEquals(payload)) {
                throw WebDavException(WebDavErrorKind.InvalidResponse, "读回的内容和写入的不一致")
            }
            // Range 只决定以后能不能边下边播，不支持也不算失败
            val ranged = runCatching { read(probe, 0L..0L, maxBytes = 64 * 1024) }.getOrNull()
            val supportsRange = ranged != null && ranged.partial && ranged.bytes.size == 1
            step = WebDavCheckStep.Delete
            delete(probe)
            written = null
            WebDavCheckResult(supportsRange = supportsRange, createdDirectory = created, foundFile = found)
        } catch (error: WebDavException) {
            WebDavCheckResult(failedStep = step, error = error, createdDirectory = created, foundFile = found)
        } finally {
            written?.let { runCatching { delete(it) } }
        }
    }

    /**
     * 只读服务器的叶子证书，不发 HTTP 请求、不带密码（见 [CertificateProbe]）。“信任自签名证书”
     * 打开时，测试连接和连接都先调它，决定这次钉哪一张，再用钉好的客户端去带密码访问。
     */
    suspend fun probeCertificate(): WebDavCertificate {
        if (!baseUrl.isHttps) throw WebDavException(WebDavErrorKind.InvalidConfig, "http 地址没有证书")
        val probe = CertificateProbe(base)
        // 不经过认证拦截器，也没有 Authorization：就算握手意外走完，发出去的也只是个空 HEAD
        val request = Request.Builder().url(baseUrl).head().build()
        return runInterruptible(Dispatchers.IO) {
            val failure = try {
                probe.client.newCall(request).execute().close()
                null
            } catch (error: IOException) {
                error
            }
            probe.result()
                ?: throw failure?.let(::mapIoError)
                    ?: WebDavException(WebDavErrorKind.Tls, "没有读到服务器证书")
        }
    }

    private suspend fun propfind(path: String, depth: Int): List<WebDavResource> {
        val request = Request.Builder()
            .url(url(path))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", depth.toString())
            .build()
        return execute(request) { response ->
            if (response.code != 207 && response.code != 200) throw httpError(response)
            WebDavXml.parseMultistatus(response.body.string(), response.request.url, baseUrl)
        }
    }

    private suspend fun <T> execute(request: Request, block: (Response) -> T): T = runInterruptible(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use(block)
        } catch (error: WebDavException) {
            throw error
        } catch (error: IOException) {
            throw mapIoError(error)
        }
    }

    private fun requireCollection(path: String, resource: WebDavResource) {
        if (!resource.isCollection) {
            throw WebDavException(WebDavErrorKind.Conflict, "“${path.trimEnd('/')}”是一个文件，不是目录", 409)
        }
    }

    private fun notFoundBase() = WebDavException(
        WebDavErrorKind.NotFound,
        "服务器上没有这个地址：${baseUrl.encodedPath}",
        404,
    )

    private fun applyCondition(builder: Request.Builder, condition: WebDavCondition) {
        when (condition) {
            WebDavCondition.None -> Unit
            WebDavCondition.IfNoneMatchAny -> builder.header("If-None-Match", "*")
            is WebDavCondition.IfMatch -> builder.header("If-Match", condition.etag)
        }
    }

    companion object {
        const val DEFAULT_MAX_READ_BYTES = 8L * 1024 * 1024
        val XML = "application/xml; charset=utf-8".toMediaType()
        val TEXT = "text/plain; charset=utf-8".toMediaType()
        val JSON = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><d:getcontenttype/><d:getlastmodified/><d:displayname/></d:prop></d:propfind>"""

        private const val QUOTA_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:quota-available-bytes/><d:quota-used-bytes/></d:prop></d:propfind>"""

        /** 同步用的超时；流播 / 上传要自己 newBuilder() 改长，别直接沿用（见规格 8.4）。 */
        val defaultBase: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .writeTimeout(40, TimeUnit.SECONDS)
                .build()
        }

        internal fun rangeHeader(range: LongRange): String =
            if (range.last == Long.MAX_VALUE) "bytes=${range.first}-" else "bytes=${range.first}-${range.last}"

        internal fun collectionPath(path: String): String =
            WebDavPaths.segments(path).let { if (it.isEmpty()) "" else it.joinToString("/") + "/" }

        private fun collectionOf(parts: List<String>) = parts.joinToString("/") + "/"

        internal fun httpError(response: Response): WebDavException {
            val code = response.code
            val kind = when (code) {
                401 -> WebDavErrorKind.Unauthorized
                403 -> WebDavErrorKind.Forbidden
                404, 410 -> WebDavErrorKind.NotFound
                405 -> WebDavErrorKind.MethodNotAllowed
                409 -> WebDavErrorKind.Conflict
                412 -> WebDavErrorKind.PreconditionFailed
                413 -> WebDavErrorKind.PayloadTooLarge
                423 -> WebDavErrorKind.Locked
                507 -> WebDavErrorKind.InsufficientStorage
                else -> WebDavErrorKind.Http
            }
            return WebDavException(kind, "${response.request.method} 返回 HTTP $code", code)
        }

        internal fun mapIoError(error: IOException): WebDavException {
            if (error is WebDavException) return error
            val related = relatedErrors(error)
            val mismatch = related.firstNotNullOfOrNull { it as? CertificatePinMismatch }
            val untrusted = related.firstOrNull {
                it is CertPathValidatorException || it is CertificateException || it is SSLPeerUnverifiedException
            }
            val tls = related.firstOrNull { it is SSLException }
            return when {
                mismatch != null -> WebDavException(
                    WebDavErrorKind.CertificateChanged,
                    "服务器证书和本机信任的指纹不一致",
                    cause = error,
                    certificate = mismatch.observed,
                )
                untrusted != null -> WebDavException(WebDavErrorKind.UntrustedCertificate, detailOf(untrusted), cause = error)
                tls != null -> WebDavException(WebDavErrorKind.Tls, detailOf(tls), cause = error)
                error is UnknownHostException -> WebDavException(WebDavErrorKind.Network, "找不到服务器 ${detailOf(error)}", cause = error)
                error is SocketTimeoutException -> WebDavException(WebDavErrorKind.Network, "连接超时", cause = error)
                else -> WebDavException(WebDavErrorKind.Network, detailOf(error), cause = error)
            }
        }

        /**
         * 异常本身、cause 链和 suppressed。OkHttp 一个主机名解析出多个地址（IPv6 + IPv4）时逐个去试，
         * 最后抛出来的是其中一次的错误，其余的挂在 suppressed 上：::1 拒绝连接会把 IPv4 上真正的
         * 证书错误盖住，只看 cause 链就会把“证书变了”报成“连不上”。
         */
        private fun relatedErrors(error: Throwable): List<Throwable> {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val queue = ArrayDeque<Throwable>().apply { add(error) }
            while (queue.isNotEmpty() && seen.size < 32) {
                val next = queue.removeFirst()
                if (!seen.add(next)) continue
                next.cause?.let(queue::addLast)
                next.suppressed.forEach(queue::addLast)
            }
            return seen.toList()
        }

        private fun detailOf(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

        private fun randomHex(bytes: Int): String =
            ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun Response.etag(): String? =
            (header("ETag") ?: header("OC-ETag"))?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * 认证：默认主动带 Basic（坚果云、Nextcloud、群晖都收）。服务器回 401 且要 Digest 时，
 * 按挑战算一次 Digest 重发，之后的请求直接带 Digest；同一个 nonce 再被拒（且没标 stale）
 * 就说明密码错了，返回 null 让 401 原样上去——不重试，免得把账号试锁。
 */
private class WebDavAuth(private val username: String, private val password: String) : Interceptor, Authenticator {
    @Volatile private var digest: DigestChallenge? = null
    private val nonceCount = AtomicInteger(0)
    private val random = SecureRandom()

    val usesDigest: Boolean get() = digest != null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        val challenge = digest
        val value = if (challenge != null) {
            digestHeader(challenge, request.method, request.url)
        } else {
            Credentials.basic(username, password, Charsets.UTF_8)
        }
        return chain.proceed(request.newBuilder().header("Authorization", value).build())
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (priorResponses(response) >= 2) return null
        val challenge = response.challenges()
            .firstOrNull { it.scheme.equals("Digest", ignoreCase = true) }
            ?.let(DigestChallenge::from)
            ?: return null
        val sent = response.request.header("Authorization").orEmpty()
        if (sent.startsWith("Digest", ignoreCase = true) && !challenge.stale &&
            sent.contains("nonce=\"${challenge.nonce}\"")
        ) {
            return null
        }
        digest = challenge
        nonceCount.set(0)
        return response.request.newBuilder()
            .header("Authorization", digestHeader(challenge, response.request.method, response.request.url))
            .build()
    }

    private fun priorResponses(response: Response): Int =
        generateSequence(response.priorResponse) { it.priorResponse }.count()

    private fun digestHeader(challenge: DigestChallenge, method: String, url: HttpUrl): String {
        val uri = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val algorithm = challenge.algorithm?.uppercase().orEmpty().ifBlank { "MD5" }
        val digestName = if (algorithm.startsWith("SHA-256")) "SHA-256" else "MD5"
        val hash = { value: String ->
            MessageDigest.getInstance(digestName).digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        val cnonce = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val nc = "%08x".format(nonceCount.incrementAndGet())
        var ha1 = hash("$username:${challenge.realm}:$password")
        if (algorithm.endsWith("-SESS")) ha1 = hash("$ha1:${challenge.nonce}:$cnonce")
        val ha2 = hash("$method:$uri")
        val qop = challenge.qop?.split(',')?.map { it.trim() }?.firstOrNull { it.equals("auth", ignoreCase = true) }
        val digestValue = if (qop != null) {
            hash("$ha1:${challenge.nonce}:$nc:$cnonce:$qop:$ha2")
        } else {
            hash("$ha1:${challenge.nonce}:$ha2")
        }
        return buildString {
            append("Digest username=\"").append(username.quoted()).append('"')
            append(", realm=\"").append(challenge.realm.quoted()).append('"')
            append(", nonce=\"").append(challenge.nonce).append('"')
            append(", uri=\"").append(uri).append('"')
            append(", response=\"").append(digestValue).append('"')
            challenge.algorithm?.let { append(", algorithm=").append(it) }
            challenge.opaque?.let { append(", opaque=\"").append(it).append('"') }
            if (qop != null) append(", qop=").append(qop).append(", nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"')
        }
    }

    private fun String.quoted(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}

private class DigestChallenge(
    val realm: String,
    val nonce: String,
    val opaque: String?,
    val qop: String?,
    val algorithm: String?,
    val stale: Boolean,
) {
    companion object {
        fun from(challenge: Challenge): DigestChallenge? {
            val params = challenge.authParams
            val nonce = params["nonce"] ?: return null
            return DigestChallenge(
                realm = params["realm"].orEmpty(),
                nonce = nonce,
                opaque = params["opaque"],
                qop = params["qop"],
                algorithm = params["algorithm"],
                stale = params["stale"].equals("true", ignoreCase = true),
            )
        }
    }
}
