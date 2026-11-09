package com.mytv.mymusic.data.cloud.netease

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.cloud.CloudEntry
import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudPage
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudQuota
import com.mytv.mymusic.data.cloud.CloudRef
import com.mytv.mymusic.data.cloud.CloudStorage
import com.mytv.mymusic.data.cloud.CloudStream
import com.mytv.mymusic.data.cloud.CloudUploadCandidate
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadProgress
import com.mytv.mymusic.data.cloud.CloudUploadSource
import com.mytv.mymusic.data.cloud.CloudUploadStage
import com.mytv.mymusic.data.cloud.SourceChunkBody
import com.mytv.mymusic.data.cloud.UploadGate
import com.mytv.mymusic.data.cloud.UploadPrecheck
import com.mytv.mymusic.data.cloud.attempt
import com.mytv.mymusic.data.cloud.await
import com.mytv.mymusic.data.cloud.cloudBaseNameOf
import com.mytv.mymusic.data.cloud.str
import com.mytv.mymusic.data.native.NativeCrypto
import com.mytv.mymusic.data.native.NativeHttp
import com.mytv.mymusic.data.native.NativeProviders
import com.mytv.mymusic.data.native.NetEaseProvider
import com.mytv.mymusic.data.native.PlayableStream
import com.mytv.mymusic.data.native.extensionFromUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 网易云盘请求通道。path 一律写成 NCMApi 的 `/api/...`（规格 6.2），由实现换成 weapi / eapi 并加密。
 * 单独抽出来是为了测试：weapi 加密后看不出参数，测试直接断言加密前的参数表。
 */
interface NeteaseCloudTransport {
    /** 返回响应正文；HTTP 层失败抛 IOException。 */
    suspend fun weapi(path: String, params: JsonObject, cookie: String): String

    suspend fun eapi(path: String, params: JsonObject, cookie: String): String
}

/**
 * 正式通道：weapi 复用 [NetEaseProvider.weapiPost]；eapi 照现有取流（getEapiStream）的写法用
 * [NativeCrypto.encryptEApi]。和 ncmExtend 一样，网页 weapi 的 URL 上也带 `csrf_token`，
 * Cookie 补上 `os=pc`（ncmExtend / Alist 调云盘接口时都这么带）。不带随机 IP，见 weapiPost 的说明。
 */
class NeteaseCloudHttpTransport(
    private val provider: () -> NetEaseProvider = ::defaultNetEaseProvider,
    private val webBase: String = "https://music.163.com",
    private val eapiBase: String = "https://interface3.music.163.com",
) : NeteaseCloudTransport {
    override suspend fun weapi(path: String, params: JsonObject, cookie: String): String {
        val csrf = params.str("csrf_token")
        val url = webBase + path.replaceFirst("/api/", "/weapi/") + if (csrf.isNotBlank()) "?csrf_token=$csrf" else ""
        return provider().weapiPost(url, params, clientCookie(cookie), randomIp = false).body
    }

    override suspend fun eapi(path: String, params: JsonObject, cookie: String): String {
        val url = eapiBase + path.replaceFirst("/api/", "/eapi/")
        val payload = buildJsonObject {
            params.forEach { (key, value) -> put(key, value) }
            put("header", EAPI_HEADER)
        }.toString()
        return NativeHttp.postForm(
            url,
            mapOf("params" to NativeCrypto.encryptEApi(url, payload)),
            headers = mapOf("Referer" to REFERER),
            cookie = clientCookie(cookie),
        ).body
    }

    private fun clientCookie(cookie: String): String {
        val extra = buildList {
            if (cookieValue(cookie, "os").isBlank()) add("os=pc")
            if (cookieValue(cookie, "appver").isBlank()) add("appver=$PC_APPVER")
        }
        return (listOf(cookie.trim().trimEnd(';')).filter { it.isNotBlank() } + extra).joinToString("; ")
    }

    private companion object {
        const val REFERER = "https://music.163.com/"
        const val PC_APPVER = "3.1.22.204707"

        /** 和 NetEaseProvider.getEapiStream 一样的设备头：登录态靠 Cookie，这里只声明是 PC 端。 */
        const val EAPI_HEADER = """{"os":"pc","appver":"","osver":"","deviceId":"pyncm!","requestId":"12345678"}"""
    }
}

/** 已匹配曲目走的现有通道：取流（`song/enhance/player/url`）和歌词（`song/lyric`）。 */
interface NeteasePlatform {
    suspend fun stream(cookie: String, songId: String): PlayableStream

    suspend fun lyrics(cookie: String, songId: String): String
}

/** 直接复用 [NetEaseProvider] 的取流和歌词，不另写一套。 */
object NeteaseProviderPlatform : NeteasePlatform {
    override suspend fun stream(cookie: String, songId: String): PlayableStream =
        defaultNetEaseProvider().playableStream(cookie, songOf(songId), File(System.getProperty("java.io.tmpdir") ?: "."))

    override suspend fun lyrics(cookie: String, songId: String): String =
        defaultNetEaseProvider().lyrics(cookie, songOf(songId))

    private fun songOf(id: String) = Song(id = id, source = "netease", extra = mapOf("song_id" to id))
}

internal fun defaultNetEaseProvider(): NetEaseProvider =
    NativeProviders.get("netease") as? NetEaseProvider ?: NetEaseProvider()

/**
 * 节奏和限制（规格 6.4、12）。测试把间隔调成毫秒级。
 */
data class NeteaseCloudConfig(
    /** 云盘接口之间至少隔这么久：并发 1、间隔 300–500ms。 */
    val requestIntervalMs: Long = 400,
    /** 读接口（列表、查重、状态）网络失败最多重试几次，指数退避。写接口不在这里重试。 */
    val maxRetries: Int = 2,
    val retryBackoffMs: Long = 1_000,
    /** 转码状态轮询间隔 3–5 秒；服务端给的 waitTime 夹在这个区间里。 */
    val statusPollMinMs: Long = 3_000,
    val statusPollMaxMs: Long = 5_000,
    /** 转码最长等 10 分钟。 */
    val transcodeTimeoutMs: Long = 10 * 60_000,
    /** 查不到转码状态（rep create failed 之后常见）最多容忍这么久。 */
    val missingStatusGraceMs: Long = 60_000,
    /** info/v2 回非 200（不含风控、未登录）时，在这个窗口里隔 [submitRetryDelayMs] 重提（照 ncmExtend）。 */
    val submitRetryWindowMs: Long = 60_000,
    val submitRetryDelayMs: Long = 1_000,
    /** NOS 分片大小，和 ncmExtend 一样 8MB。 */
    val chunkSize: Long = 8L * 1024 * 1024,
    /** 社区反馈超过 500MB 会被拒（规格 6.4），上传前拦下。 */
    val maxFileSize: Long = 500L * 1024 * 1024,
    val listPageSize: Int = 200,
    /** 写接口超时后翻最近多少条来确认是否已入库。 */
    val confirmScanSize: Int = 100,
    val lbsUrl: String = "https://wanproxy.127.net/lbs",
    val bucket: String = "jd-musicrep-privatecloud-audio-public",
)

/** 写接口超时或断线：请求可能已经到了服务端。调用方必须先查 byids / 列表确认，不能直接重发。 */
internal class NeteaseOutcomeUnknown(cause: IOException) : IOException(cause.message, cause)

/** NOS 直传回了非 2xx。 */
internal class NosHttpException(val code: Int, message: String) : IOException(message)

/**
 * 网易云音乐云盘（规格第 6 节）：列表、容量、详情、删除、匹配、歌词、播放、上传。
 *
 * - 所有 weapi / eapi 调用串行间隔 [NeteaseCloudConfig.requestIntervalMs]；
 * - 读接口网络失败有限重试；写接口（info/v2、pub/v2、del、match）超时一律抛
 *   [NeteaseOutcomeUnknown]，由调用方先确认再决定是否重发（规格 6.3 “写接口超时不要盲目重试”）；
 * - -460 / -462 抛 [CloudErrorKind.RiskControl]，301 抛 [CloudErrorKind.NotLoggedIn]。
 *
 * [cookie] 每次调用现取，集成时从 SettingsStore 的 cookies["netease"] 读。
 */
class NeteaseCloudApi(
    private val cookie: suspend () -> String,
    private val transport: NeteaseCloudTransport = NeteaseCloudHttpTransport(),
    private val platform: NeteasePlatform = NeteaseProviderPlatform,
    val config: NeteaseCloudConfig = NeteaseCloudConfig(),
    private val nos: OkHttpClient = defaultNosClient,
    gate: UploadGate = UploadGate.Always,
    internal val now: () -> Long = System::currentTimeMillis,
) : CloudStorage {
    override val provider: CloudProvider = CloudProvider.NeteaseCloud

    /** 上传状态机；断点存在它里面，所以每个账号只建一个 api 实例。 */
    val uploader: NeteaseCloudUploader = NeteaseCloudUploader(this, gate)

    private val spacing = Mutex()
    private var nextSlot = 0L
    private val userIds = ConcurrentHashMap<String, Long>()

    // ---------------------------------------------------------------- 列表 / 容量 / 详情

    /** `v1/cloud/get`：一页条目 + 已用 / 总容量。 */
    suspend fun listPage(offset: Int, limit: Int): NeteaseCloudListPage =
        NeteaseCloudParse.listPage(
            read(
                "/api/v1/cloud/get",
                params {
                    put("limit", limit)
                    put("offset", offset)
                },
            ),
        )

    override suspend fun list(cursor: String?): CloudPage {
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = listPage(offset, config.listPageSize)
        val next = if (page.hasMore && page.items.isNotEmpty()) (offset + page.items.size).toString() else null
        return CloudPage(page.items.map { it.toEntry() }, next, page.count, page.quota)
    }

    override suspend fun quota(): CloudQuota = listPage(0, 1).quota

    override suspend fun precheck(items: List<CloudUploadCandidate>): UploadPrecheck = uploader.precheck(items)

    /** `v1/cloud/get/byids`：按云盘条目 ID 查详情。 */
    suspend fun byIds(ids: List<String>): List<NeteaseCloudItem> {
        val clean = ids.map { it.trim() }.filter { it.isNotEmpty() && it != "0" }.distinct()
        if (clean.isEmpty()) return emptyList()
        return NeteaseCloudParse.byIds(read("/api/v1/cloud/get/byids", params { put("songIds", idArray(clean)) }))
    }

    /** 按 songId / simpleSong.id 找一条；找不到返回 null（已删除或还没入库）。 */
    suspend fun findEntry(cloudSongId: String, resolveId: String = ""): NeteaseCloudItem? =
        byIds(listOf(cloudSongId, resolveId)).firstOrNull { item ->
            (cloudSongId.isNotBlank() && item.songId.toString() == cloudSongId) ||
                (resolveId.isNotBlank() && (item.resolveId == resolveId || item.songId.toString() == resolveId))
        }

    /** 翻最近加入的条目（列表按加入时间倒序）找同一个文件：确认写接口超时后是否已经入库用。 */
    suspend fun findRecent(md5: String, fileName: String, fileSize: Long, cloudSongId: String = ""): NeteaseCloudItem? =
        listPage(0, config.confirmScanSize).items.firstOrNull { item ->
            (md5.isNotBlank() && item.md5.equals(md5, ignoreCase = true)) ||
                (cloudSongId.isNotBlank() && item.songId.toString() == cloudSongId) ||
                (fileName.isNotBlank() && item.fileName == fileName && item.fileSize == fileSize)
        }

    /** 当前账号的 userId（`nuser/account/get` 的 profile.userId），按 Cookie 缓存。 */
    suspend fun userId(): Long {
        val key = NativeCrypto.md5Hex(requireCookie())
        userIds[key]?.let { return it }
        val id = NeteaseCloudParse.userId(read("/api/nuser/account/get", params { }))
        if (id <= 0) throw notLoggedIn()
        userIds[key] = id
        return id
    }

    // ---------------------------------------------------------------- 删除 / 匹配

    /** `cloud/del`，传云盘条目 ID（开源实现传 simpleSong.id）。超时先确认，不盲目重发。 */
    override suspend fun delete(ref: CloudRef) {
        val call: suspend () -> Unit = {
            NeteaseCloudParse.ok(write("/api/cloud/del", params { put("songIds", idArray(listOf(ref.id))) }))
        }
        try {
            call()
        } catch (unknown: NeteaseOutcomeUnknown) {
            if (findEntry(ref.cloudSongId, ref.id) == null) return
            try {
                call()
            } catch (again: NeteaseOutcomeUnknown) {
                throw networkError(again.cause)
            }
        } catch (error: CloudException) {
            // 本来就不在了也算删成功；风控 / 未登录照常往上抛
            if (error.kind != CloudErrorKind.Server || findEntry(ref.cloudSongId, ref.id) != null) throw error
        }
    }

    /**
     * `cloud/user/song/match`：把云盘条目 [entryId] 关联到曲库 [adjustSongId]；传 "0" 取消匹配。
     * 返回服务端给的新条目（matchData）。写接口：超时抛 [NeteaseOutcomeUnknown]，见 [matchConfirmed]。
     */
    suspend fun match(entryId: String, adjustSongId: String): NeteaseCloudItem? {
        val uid = userId()
        return NeteaseCloudParse.match(
            write(
                "/api/cloud/user/song/match",
                params {
                    put("userId", uid.toString())
                    put("songId", entryId)
                    put("adjustSongId", adjustSongId)
                },
            ),
        )
    }

    /** 带确认的匹配 / 取消匹配：超时后先 byids 看是否已经生效，没生效才重发一次。 */
    suspend fun matchConfirmed(ref: CloudRef, adjustSongId: String): NeteaseCloudItem? {
        repeat(2) { round ->
            try {
                return match(ref.id, adjustSongId)
            } catch (unknown: NeteaseOutcomeUnknown) {
                val current = findEntry(ref.cloudSongId, ref.id)
                val applied = when {
                    current == null -> false
                    adjustSongId == "0" -> !current.matched
                    else -> current.simpleId.toString() == adjustSongId
                }
                if (applied) return current
                if (round == 1) throw networkError(unknown.cause)
            }
        }
        return null
    }

    suspend fun unmatch(ref: CloudRef): NeteaseCloudItem? = matchConfirmed(ref, "0")

    // ---------------------------------------------------------------- 歌词

    /**
     * 先按曲库歌曲取（`song/lyric`，已匹配的条目 ID 就是曲库 ID），取不到再取云盘自定义歌词
     * （eapi `cloud/lyric/get`）。
     */
    override suspend fun lyrics(ref: CloudRef): String {
        val cookie = requireCookie()
        attempt { platform.lyrics(cookie, ref.playableId.ifBlank { ref.id }) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        return cloudLyric(ref.cloudSongId.ifBlank { ref.id }).ifBlank {
            throw CloudException(CloudErrorKind.NoLyrics, CloudMessages.NO_LYRICS, provider)
        }
    }

    /** 列表里已知匹配状态时用它：未匹配的直接取云盘歌词，省一次必然失败的请求。 */
    suspend fun lyrics(entry: CloudEntry): String {
        if (entry.match != null) return lyrics(entry.toRef())
        val cloud = cloudLyric(entry.cloudSongId.ifBlank { entry.id })
        if (cloud.isNotBlank()) return cloud
        throw CloudException(CloudErrorKind.NoLyrics, CloudMessages.NO_LYRICS, provider)
    }

    /** eapi `/api/cloud/lyric/get`（证据 B，规格 6.2）。 */
    suspend fun cloudLyric(songId: String): String {
        val uid = userId()
        return NeteaseCloudParse.cloudLyric(
            read(
                "/api/cloud/lyric/get",
                params {
                    put("userId", uid.toString())
                    put("songId", songId)
                    put("lv", -1)
                    put("kv", -1)
                },
                eapi = true,
            ),
        )
    }

    // ---------------------------------------------------------------- 播放

    /**
     * 规格 10.2：
     * 1. playableId（曲库 ID）走现有取流；
     * 2. 云盘条目 ID 也先试现有取流（Alist / ncmExtend 都这么播云盘歌）；
     * 3. 再试 eapi `/api/cloud/dowonload`（上游拼写如此）和 `/api/cloud/mobile/drive/file/playUrl`；
     * 都不行：条目已经不在了报 NotFound，网络问题报 Network，否则 Unplayable。
     * 只拿到试听片段时先记着，后面都失败才退回它（preview=true，解析器会继续找别的源）。
     */
    override suspend fun resolvePlayback(ref: CloudRef): CloudStream {
        val cookie = requireCookie()
        var preview: CloudStream? = null
        val failures = mutableListOf<Throwable>()
        for (id in listOf(ref.playableId, ref.id).filter { it.isNotBlank() }.distinct()) {
            val stream = attempt { platform.stream(cookie, id) }
                .onFailure { failures += it }
                .getOrNull() ?: continue
            val candidate = CloudStream(
                url = stream.url,
                ext = stream.ext.ifBlank { ref.ext },
                bitrate = stream.bitrate,
                level = stream.level,
                preview = stream.preview,
                extra = stream.extra,
                via = VIA_PLATFORM,
            )
            if (!stream.preview && stream.url.isNotBlank()) return candidate
            if (preview == null && stream.url.isNotBlank()) preview = candidate
        }
        val cloudId = ref.cloudSongId.ifBlank { ref.id }
        for ((path, via) in listOf(CLOUD_DOWNLOAD_PATH to VIA_CLOUD_DOWNLOAD, CLOUD_PLAY_URL_PATH to VIA_CLOUD_PLAY_URL)) {
            val found = attempt { NeteaseCloudParse.cloudUrl(read(path, params { put("songId", cloudId) }, eapi = true)) }
                .onFailure { error ->
                    if (error is CloudException && (error.kind == CloudErrorKind.RiskControl || error.kind == CloudErrorKind.NotLoggedIn)) throw error
                    failures += error
                }
                .getOrNull() ?: continue
            return CloudStream(
                url = found.url,
                ext = found.ext.ifBlank { extensionFromUrl(found.url) }.ifBlank { ref.ext },
                bitrate = found.bitrate,
                via = via,
                contentLength = found.size.takeIf { it > 0 } ?: -1,
            )
        }
        preview?.let { return it }
        val networkOnly = failures.isNotEmpty() && failures.all { it.isNetworkFailure() }
        if (networkOnly) throw networkError(failures.last())
        // 条目已经不在云盘里：让上层把 remote.status 标成 missing。查询本身失败时不下这个结论
        val lookup = attempt { findEntry(ref.cloudSongId, ref.id) }
        if (lookup.isSuccess && lookup.getOrNull() == null) {
            throw CloudException(CloudErrorKind.NotFound, CloudMessages.CLOUD_DELETED, provider)
        }
        throw CloudException(CloudErrorKind.Unplayable, CloudMessages.NETEASE_UNPLAYABLE, provider, cause = failures.lastOrNull())
    }

    // ---------------------------------------------------------------- 上传

    override suspend fun upload(
        source: CloudUploadSource,
        meta: CloudUploadMeta,
        progress: (CloudUploadProgress) -> Unit,
    ): CloudRef {
        var last: CloudUploadProgress? = null
        uploader.upload(source, meta).collect {
            last = it
            progress(it)
        }
        val result = last
        if (result?.stage == CloudUploadStage.READY && result.ref != null) return result.ref
        throw result?.error ?: CloudException(CloudErrorKind.Server, "上传没有完成", provider)
    }

    /** 第 2 步 `cloud/upload/check`：查重；needUpload=false 是秒传。读接口，可以重试。 */
    internal suspend fun uploadCheck(md5: String, length: Long, ext: String): NeteaseUploadCheck =
        NeteaseCloudParse.uploadCheck(
            read(
                "/api/cloud/upload/check",
                params {
                    put("songId", "0")
                    put("md5", md5)
                    put("length", length)
                    put("ext", ext)
                    put("version", 1)
                    put("bitrate", BITRATE)
                },
            ),
        )

    /**
     * 第 3 步 `nos/token/alloc`：秒传也要做，info/v2 要 resourceId。
     * 重复申请只会多一个没人用的令牌，不会动到云盘，所以按读接口重试。
     */
    internal suspend fun allocToken(fileName: String, length: Long, ext: String, md5: String): NeteaseNosToken =
        NeteaseCloudParse.token(
            read(
                "/api/nos/token/alloc",
                params {
                    put("filename", cloudBaseNameOf(fileName))
                    put("length", length)
                    put("ext", ext)
                    put("type", "audio")
                    put("bucket", config.bucket)
                    put("local", false)
                    put("nos_product", 3)
                    put("md5", md5)
                },
            ),
        )

    /** 第 4 步 lbs：拿上传节点。 */
    internal suspend fun uploadHosts(): List<String> {
        val url = config.lbsUrl.toHttpUrl().newBuilder()
            .addQueryParameter("version", "1.0")
            .addQueryParameter("bucketname", config.bucket)
            .build()
        return withReadRetries {
            nos.await(Request.Builder().url(url).get().build()).use { response ->
                if (!response.isSuccessful) throw NosHttpException(response.code, "lbs 返回 HTTP ${response.code}")
                NeteaseCloudParse.uploadHosts(response.body.string())
            }
        }
    }

    /**
     * 第 5 步 NOS 直传一片：`POST {host}/{bucket}/{objectKey}?offset=&complete=&version=1.0[&context=]`。
     * objectKey 作为一个路径段编码，`/` 变成 `%2F`（规格 6.3）。Content-MD5 默认传十六进制整文件 MD5，
     * 400 时调用方换 base64 再试。[onWritten] 报这一片已经写出的字节。
     */
    internal suspend fun nosUploadChunk(
        host: String,
        token: NeteaseNosToken,
        source: CloudUploadSource,
        offset: Long,
        length: Long,
        complete: Boolean,
        context: String?,
        contentMd5: String,
        cancelled: () -> Boolean = { false },
        onWritten: (Long) -> Unit = {},
    ): NeteaseNosChunk {
        val url = nosObjectUrl(host, token.objectKey).newBuilder()
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("complete", complete.toString())
            .addQueryParameter("version", "1.0")
            .apply { if (!context.isNullOrBlank()) addQueryParameter("context", context) }
            .build()
        val body = SourceChunkBody(source, offset, length, AUDIO_MPEG, cancelled, onWritten)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("x-nos-token", token.token)
            .header("Content-MD5", contentMd5)
            .build()
        return nos.await(request).use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw NosHttpException(response.code, "NOS 返回 HTTP ${response.code}")
            NeteaseCloudParse.nosChunk(text)
        }
    }

    /**
     * 问 NOS 这次续传服务端已经收到多少：`GET …?uploadContext&context=&version=1.0`。
     * 分片超时后用它决定从哪接着传，不盲目重发。查不到返回 null，调用方从头传。
     */
    internal suspend fun nosQueryOffset(host: String, token: NeteaseNosToken, context: String): Long? {
        val url = nosObjectUrl(host, token.objectKey).newBuilder()
            .addQueryParameter("uploadContext", null)
            .addQueryParameter("context", context)
            .addQueryParameter("version", "1.0")
            .build()
        return attempt {
            nos.await(Request.Builder().url(url).get().header("x-nos-token", token.token).build()).use { response ->
                if (response.isSuccessful) NeteaseCloudParse.nosOffset(response.body.string()) else null
            }
        }.getOrNull()
    }

    /** 第 6 步 `upload/cloud/info/v2`：提交入库。写接口。 */
    internal suspend fun submitInfo(
        md5: String,
        checkSongId: String,
        fileName: String,
        title: String,
        album: String,
        artist: String,
        resourceId: String,
    ): NeteaseSubmitResult = NeteaseCloudParse.submit(
        write(
            "/api/upload/cloud/info/v2",
            params {
                put("md5", md5)
                put("songid", checkSongId)
                put("filename", fileName)
                put("song", title)
                put("album", album)
                put("artist", artist)
                put("bitrate", BITRATE)
                put("resourceId", resourceId)
            },
        ),
    )

    /** 第 7 步 `v1/cloud/music/status`：9 就绪、1 处理中；查不到该条返回 null。 */
    internal suspend fun transcodeStatus(songId: String): NeteaseTranscodeStatus? =
        NeteaseCloudParse.status(read("/api/v1/cloud/music/status", params { put("songIds", idArray(listOf(songId))) }), songId)

    /** 第 8 步 `cloud/pub/v2`：200 / 201 都算成功。写接口。 */
    internal suspend fun publish(songId: String): Int =
        NeteaseCloudParse.publish(write("/api/cloud/pub/v2", params { put("songid", songId) }))

    // ---------------------------------------------------------------- 底层

    /** 登录态：没有 MUSIC_U 直接判未登录，不去撞 -460。 */
    internal suspend fun requireCookie(): String {
        val value = cookie().trim()
        if (cookieValue(value, "MUSIC_U").isBlank()) throw notLoggedIn()
        return value
    }

    /** 读接口：网络失败按指数退避重试 [NeteaseCloudConfig.maxRetries] 次；4xx 不重试。 */
    internal suspend fun read(path: String, params: JsonObject, eapi: Boolean = false): String {
        val cookie = requireCookie()
        val body = withCsrf(params, cookie)
        return withReadRetries {
            throttle()
            if (eapi) transport.eapi(path, body, cookie) else transport.weapi(path, body, cookie)
        }
    }

    /** 写接口：不重试。超时 / 断线 / 5xx 抛 [NeteaseOutcomeUnknown]，明确的 4xx 抛 Server。 */
    internal suspend fun write(path: String, params: JsonObject): String {
        val cookie = requireCookie()
        val body = withCsrf(params, cookie)
        throttle()
        return try {
            transport.weapi(path, body, cookie)
        } catch (error: CloudException) {
            throw error
        } catch (error: IOException) {
            val status = httpStatusOf(error)
            if (status in 400..499) {
                throw CloudException(CloudErrorKind.Server, CloudMessages.server(provider, status), provider, status, cause = error)
            }
            throw NeteaseOutcomeUnknown(error)
        }
    }

    private suspend fun <T> withReadRetries(block: suspend () -> T): T {
        var attemptIndex = 0
        while (true) {
            try {
                return block()
            } catch (error: CloudException) {
                throw error
            } catch (error: IOException) {
                val status = httpStatusOf(error)
                if (status in 400..499) {
                    throw CloudException(CloudErrorKind.Server, CloudMessages.server(provider, status), provider, status, cause = error)
                }
                if (attemptIndex >= config.maxRetries) throw networkError(error)
                delay(config.retryBackoffMs shl attemptIndex)
                attemptIndex++
            }
        }
    }

    /** 只排开始时间：两个调用的起点至少隔一个间隔，但不在锁里等对方的回应。 */
    private suspend fun throttle() {
        if (config.requestIntervalMs <= 0) return
        spacing.withLock {
            val wait = nextSlot - now()
            if (wait > 0) delay(wait)
            nextSlot = now() + config.requestIntervalMs
        }
    }

    private fun withCsrf(params: JsonObject, cookie: String): JsonObject =
        JsonObject(params + ("csrf_token" to JsonPrimitive(cookieValue(cookie, "__csrf"))))

    private fun nosObjectUrl(host: String, objectKey: String): HttpUrl =
        host.toHttpUrl().newBuilder()
            .addPathSegment(config.bucket)
            .addPathSegment(objectKey)
            .build()

    internal fun notLoggedIn() =
        CloudException(CloudErrorKind.NotLoggedIn, CloudMessages.notLoggedIn(provider), provider)

    internal fun networkError(cause: Throwable?) =
        CloudException(CloudErrorKind.Network, CloudMessages.CLOUD_UNREACHABLE, provider, cause = cause)

    companion object {
        const val CLOUD_DOWNLOAD_PATH = "/api/cloud/dowonload"
        const val CLOUD_PLAY_URL_PATH = "/api/cloud/mobile/drive/file/playUrl"
        const val VIA_PLATFORM = "platform"
        const val VIA_CLOUD_DOWNLOAD = "cloud_download"
        const val VIA_CLOUD_PLAY_URL = "cloud_play_url"

        /** 查重和提交都传 999000（NCMApi / Alist 的写法），服务端按实际文件算码率。 */
        private const val BITRATE = "999000"
        private val AUDIO_MPEG = "audio/mpeg".toMediaType()

        /** NOS 直传单独的超时：一片 8MB 在慢网上要写很久，不能用 NativeHttp 的 45 秒总超时。 */
        val defaultNosClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                // 分片 POST 不让 OkHttp 自己静默重发：超时后要先问服务端收到多少
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}

private inline fun params(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

/** 能转成数字的 ID 按数字传（ncmExtend 传的是数字），否则原样字符串。 */
private fun idArray(ids: List<String>): JsonArray =
    JsonArray(ids.map { id -> id.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(id) })

/** NativeHttp 把非 2xx 包成 `IOException("HTTP 503 …")`，从消息里认出状态码。 */
internal fun httpStatusOf(error: IOException): Int =
    Regex("""^HTTP (\d{3})""").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

private fun Throwable.isNetworkFailure(): Boolean =
    (this is CloudException && kind == CloudErrorKind.Network) ||
        (this is IOException && this !is CloudException && httpStatusOf(this) == 0)

internal fun cookieValue(cookie: String, key: String): String {
    for (part in cookie.split(';')) {
        val trimmed = part.trim()
        val index = trimmed.indexOf('=')
        if (index <= 0) continue
        if (trimmed.substring(0, index).trim() == key) return trimmed.substring(index + 1).trim()
    }
    return ""
}
