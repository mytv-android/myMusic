package com.mytv.mymusic.data.cloud.qq

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.cloud.CloudEntry
import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudJson
import com.mytv.mymusic.data.cloud.CloudMatch
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudPage
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudQuota
import com.mytv.mymusic.data.cloud.CloudRef
import com.mytv.mymusic.data.cloud.CloudStorage
import com.mytv.mymusic.data.cloud.CloudStream
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadProgress
import com.mytv.mymusic.data.cloud.CloudUploadSource
import com.mytv.mymusic.data.cloud.arr
import com.mytv.mymusic.data.cloud.attempt
import com.mytv.mymusic.data.cloud.bool
import com.mytv.mymusic.data.cloud.cloudBaseNameOf
import com.mytv.mymusic.data.cloud.cloudExtensionOf
import com.mytv.mymusic.data.cloud.firstLong
import com.mytv.mymusic.data.cloud.firstStr
import com.mytv.mymusic.data.cloud.has
import com.mytv.mymusic.data.cloud.int
import com.mytv.mymusic.data.cloud.long
import com.mytv.mymusic.data.cloud.obj
import com.mytv.mymusic.data.cloud.primitive
import com.mytv.mymusic.data.cloud.str
import com.mytv.mymusic.data.native.NativeHttp
import com.mytv.mymusic.data.native.NativeProviders
import com.mytv.mymusic.data.native.PlayableStream
import com.mytv.mymusic.data.native.QQProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.ByteString.Companion.decodeBase64
import java.io.File
import java.io.IOException

/** musicu 请求通道。抽出来是为了测试直接断言请求体。 */
fun interface QqCloudTransport {
    /** 返回响应正文；HTTP 层失败抛 IOException。 */
    suspend fun post(body: String, cookie: String): String
}

/** 正式通道：和 QQProvider 其他 musicu 调用一样走 [NativeHttp.postJson]。 */
class QqCloudHttpTransport(private val endpoint: String = MUSICU_API) : QqCloudTransport {
    override suspend fun post(body: String, cookie: String): String =
        NativeHttp.postJson(
            endpoint,
            body,
            headers = mapOf("Referer" to "https://y.qq.com/"),
            cookie = cookie,
            // 云盘请求带的是账号自己的登录态，不伪造来源 IP
            randomIp = false,
        ).body

    companion object {
        const val MUSICU_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    }
}

/** 已匹配曲目走的现有通道：登录态（uin / g_tk）、vkey 取流、GetPlayLyricInfo 歌词。 */
interface QqPlatform {
    /** (uin, g_tk)；没登录时 uin 为 "0"。 */
    fun login(cookie: String): Pair<String, Int>

    suspend fun stream(cookie: String, song: Song): PlayableStream

    suspend fun lyrics(cookie: String, song: Song): String
}

/** 直接复用 [QQProvider]，不另写 vkey 和歌词解密。 */
object QqProviderPlatform : QqPlatform {
    private fun provider(): QQProvider = NativeProviders.get("qq") as? QQProvider ?: QQProvider()

    override fun login(cookie: String): Pair<String, Int> = provider().qqLogin(cookie)

    override suspend fun stream(cookie: String, song: Song): PlayableStream =
        provider().playableStream(cookie, song, File(System.getProperty("java.io.tmpdir") ?: "."))

    override suspend fun lyrics(cookie: String, song: Song): String = provider().lyrics(cookie, song)
}

/** QQ 云盘列表的一条（规格 7.2）。 */
data class QqCloudItem(
    /** 微云 file_id（APK 内部名 weiYunFileId）。 */
    val fileId: String,
    /** base64 解出来的文件名；解不出时是原文。 */
    val fileName: String,
    val rawFileName: String,
    val fileSize: Long,
    /** 毫秒。 */
    val addedAt: Long,
    /** 1 = 已匹配曲库；2 = 原文件；其他按未匹配处理。 */
    val matched: Int,
    val songId: String,
    val songMid: String,
    val songName: String,
    val singers: List<String>,
    val albumMid: String,
    val albumName: String,
    val durationSec: Int,
) {
    /** 只有匹配到曲库、而且有 songmid 的才能按普通曲目取流（规格 7.3）。 */
    val playable: Boolean get() = matched == MATCHED && songMid.isNotBlank()

    val cover: String get() = if (albumMid.isNotBlank()) "$COVER_PREFIX$albumMid.jpg" else ""

    fun toEntry(): CloudEntry = CloudEntry(
        provider = CloudProvider.QqCloud.id,
        id = fileId,
        fileName = fileName,
        fileSize = fileSize,
        ext = cloudExtensionOf(fileName),
        title = songName.ifBlank { cloudBaseNameOf(fileName) },
        artist = singers.joinToString("、"),
        album = albumName,
        durationSec = durationSec,
        cover = cover,
        addedAt = addedAt,
        match = if (playable) CloudMatch("qq", songId, songMid) else null,
        playable = playable,
        unplayableReason = if (playable) "" else CloudMessages.QQ_ORIGINAL_UNPLAYABLE,
        playableId = if (playable) songMid else "",
        extra = buildMap {
            put("matched", matched.toString())
            if (songId.isNotBlank()) put("song_id", songId)
            if (rawFileName != fileName) put("raw_filename", rawFileName)
        },
    )

    companion object {
        const val MATCHED = 1
        const val ORIGINAL = 2
        const val COVER_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R300x300M000"
    }
}

data class QqCloudListPage(val items: List<QqCloudItem>, val total: Int, val hasMore: Boolean)

/** 解析。真实账号下的字段名还没闭环验证（规格 14 第 6 条），每个字段都留了候选名。 */
internal object QqCloudParse {
    private val provider = CloudProvider.QqCloud

    /** 取 `req_1` 的 data；外层或请求级 code 非 0 抛错。1000 是登录态失效。 */
    fun data(body: String, key: String = REQ_KEY): JsonObject {
        val root = CloudJson.parseObject(body)
            ?: throw CloudException(CloudErrorKind.InvalidResponse, "QQ 音乐云盘返回的内容看不懂", provider)
        checkCode(root.int("code"))
        val request = root.obj(key)
            ?: throw CloudException(CloudErrorKind.InvalidResponse, "QQ 音乐云盘返回的内容看不懂", provider)
        checkCode(request.int("code"))
        return request.obj("data") ?: JsonObject(emptyMap())
    }

    private fun checkCode(code: Int) {
        when (code) {
            0 -> Unit
            in LOGIN_CODES -> throw CloudException(CloudErrorKind.NotLoggedIn, CloudMessages.notLoggedIn(provider), provider, code)
            else -> throw CloudException(CloudErrorKind.Server, CloudMessages.server(provider, code), provider, code)
        }
    }

    fun listPage(body: String, pageNo: Int, pageSize: Int): QqCloudListPage {
        val data = data(body)
        val list = data.arr("info_list") ?: data.arr("list") ?: data.arr("audio_list")
        val items = list.orEmpty().mapNotNull { (it as? JsonObject)?.let(::item) }
        val total = data.int("total").takeIf { data.has("total") } ?: -1
        val hasMore = when {
            data["has_more"].primitive() != null -> data.bool("has_more")
            total >= 0 -> pageNo.toLong() * pageSize < total
            else -> items.size >= pageSize
        }
        return QqCloudListPage(items, total, hasMore && items.isNotEmpty())
    }

    fun item(obj: JsonObject): QqCloudItem? {
        val fileId = obj.firstStr("file_id", "fileid", "weiyun_file_id", "weiYunFileId", "fid")
        if (fileId.isBlank()) return null
        val raw = obj.firstStr("filename", "file_name", "name")
        val song = obj.obj("song_info") ?: obj.obj("songInfo") ?: JsonObject(emptyMap())
        val album = song.obj("album") ?: JsonObject(emptyMap())
        val singers = song.arr("singer").orEmpty().mapNotNull { element ->
            when (element) {
                is JsonObject -> element.firstStr("name", "title")
                else -> element.primitive()?.content
            }?.takeIf { it.isNotBlank() }
        }
        return QqCloudItem(
            fileId = fileId,
            fileName = decodeFileName(raw),
            rawFileName = raw,
            fileSize = obj.firstLong("filesize", "file_size", "size"),
            addedAt = timestampMillis(obj.firstLong("timestamp", "upload_time", "ctime", "mtime")),
            matched = matchedFlag(obj["matched"]),
            songId = song.str("id").takeIf { it != "0" }.orEmpty(),
            songMid = song.str("mid"),
            songName = song.firstStr("name", "title"),
            singers = singers,
            albumMid = album.firstStr("mid", "pmid").substringBefore('_'),
            albumName = album.firstStr("name", "title"),
            durationSec = song.int("interval"),
        )
    }

    /** 文件名是 base64 编码的 UTF-8；解出来不像文本（或本来就不是 base64）时用原文。 */
    fun decodeFileName(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val decoded = text.decodeBase64()?.utf8() ?: return text
        val looksLikeText = decoded.isNotBlank() &&
            decoded.none { it == '�' || (it.isISOControl() && it != '\t') }
        return if (looksLikeText) decoded else text
    }

    /** 规格 7.2：timestamp 是秒；小于 1e11 的按秒处理，否则已经是毫秒。 */
    fun timestampMillis(value: Long): Long = when {
        value <= 0 -> 0
        value < 100_000_000_000L -> value * 1000
        else -> value
    }

    /** matched 可能是数字、字符串或布尔。true 当成 1。 */
    fun matchedFlag(element: JsonElement?): Int {
        val value = element.primitive() ?: return 0
        value.content.toIntOrNull()?.let { return it }
        return if (value.content.equals("true", ignoreCase = true)) QqCloudItem.MATCHED else 0
    }

    const val REQ_KEY = "req_1"
    val LOGIN_CODES = setOf(1000)
}

/**
 * QQ 音乐云盘（规格第 7 节），只读：列表、已匹配曲目播放、歌词、删除。
 *
 * - 上传协议没有公开闭环（规格 7.4），[upload] 恒抛 [CloudErrorKind.UploadUnsupported]；
 * - 原文件（matched=2）没有第三方能用的播放接口，[resolvePlayback] 报“QQ 云盘里的原文件放不了”；
 * - 容量要走微云的 DiskUserInfoGet（另一个域名、要 wyctoken 算的 g_tk），而且音乐侧是否共用配额没证据，
 *   这次不做，[quota] 返回 null。
 *
 * [cookie] 每次调用现取，集成时从 SettingsStore 的 cookies["qq"] 读。
 */
class QqCloudApi(
    private val cookie: suspend () -> String,
    private val transport: QqCloudTransport = QqCloudHttpTransport(),
    private val platform: QqPlatform = QqProviderPlatform,
    val pageSize: Int = 2000,
    private val requestIntervalMs: Long = 400,
    private val maxRetries: Int = 2,
    private val retryBackoffMs: Long = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
) : CloudStorage {
    override val provider: CloudProvider = CloudProvider.QqCloud

    private val spacing = Mutex()
    private var nextSlot = 0L

    /** `GetMatchAudio` 一页（page_no 从 1 起）。 */
    suspend fun listPage(pageNo: Int): QqCloudListPage {
        val auth = session()
        val body = request(auth, READ_MODULE, "GetMatchAudio") {
            put("uin", auth.uinElement)
            put("file_id", "")
            put("page_no", pageNo)
            put("page_size", pageSize)
        }
        return QqCloudParse.listPage(read(body, auth.cookie), pageNo, pageSize)
    }

    /** 游标就是下一页的 page_no。 */
    override suspend fun list(cursor: String?): CloudPage {
        val pageNo = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val page = listPage(pageNo)
        return CloudPage(
            entries = page.items.map { it.toEntry() },
            nextCursor = if (page.hasMore) (pageNo + 1).toString() else null,
            total = page.total,
        )
    }

    /** 翻完所有页找一条；删除超时后确认用。 */
    suspend fun find(fileId: String): QqCloudItem? {
        var pageNo = 1
        while (true) {
            val page = listPage(pageNo)
            page.items.firstOrNull { it.fileId == fileId }?.let { return it }
            if (!page.hasMore || pageNo >= MAX_PAGES) return null
            pageNo++
        }
    }

    override suspend fun quota(): CloudQuota? = null

    /**
     * 已匹配：用 songmid 走现有 vkey 取流（规格 7.3）。原文件或没有 mid：报放不了。
     * 拿不到地址时如果条目已经不在云盘里，报 NotFound 让上层把 remote 标成 missing。
     */
    override suspend fun resolvePlayback(ref: CloudRef): CloudStream {
        val mid = ref.playableId.trim()
        if (mid.isEmpty()) {
            throw CloudException(CloudErrorKind.Unplayable, CloudMessages.QQ_ORIGINAL_UNPLAYABLE, provider)
        }
        val auth = session()
        val result = attempt { platform.stream(auth.cookie, songOf(mid)) }
        result.getOrNull()?.takeIf { it.url.isNotBlank() }?.let { stream ->
            return CloudStream(
                url = stream.url,
                ext = stream.ext.ifBlank { ref.ext },
                bitrate = stream.bitrate,
                level = stream.level,
                preview = stream.preview,
                extra = stream.extra,
                via = VIA_VKEY,
            )
        }
        val error = result.exceptionOrNull()
        if (error is IOException && error !is CloudException && !error.message.orEmpty().startsWith("HTTP ")) {
            throw networkError(error)
        }
        val lookup = attempt { find(ref.id) }
        if (lookup.isSuccess && lookup.getOrNull() == null) {
            throw CloudException(CloudErrorKind.NotFound, CloudMessages.CLOUD_DELETED, provider)
        }
        throw CloudException(CloudErrorKind.Unplayable, "QQ 音乐暂时取不到这首的播放地址", provider, cause = error)
    }

    /** 歌词走 GetPlayLyricInfo（QQProvider.lyrics），按匹配到的 songmid 取。 */
    override suspend fun lyrics(ref: CloudRef): String {
        val mid = ref.playableId.trim()
        if (mid.isEmpty()) throw CloudException(CloudErrorKind.NoLyrics, CloudMessages.NO_LYRICS, provider)
        return lyricsFor(songOf(mid))
    }

    /** 有列表条目时带上 songID / 歌名 / 时长，QQProvider 就不用再查一次歌曲详情。 */
    suspend fun lyrics(entry: CloudEntry): String {
        val match = entry.match ?: throw CloudException(CloudErrorKind.NoLyrics, CloudMessages.NO_LYRICS, provider)
        return lyricsFor(
            Song(
                id = match.mid.ifBlank { match.songId },
                name = entry.title,
                artist = entry.artist,
                album = entry.album,
                duration = entry.durationSec,
                source = "qq",
                extra = buildMap {
                    if (match.mid.isNotBlank()) put("songmid", match.mid)
                    if (match.songId.isNotBlank()) put("song_id", match.songId)
                },
            ),
        )
    }

    private suspend fun lyricsFor(song: Song): String {
        val auth = session()
        val result = attempt { platform.lyrics(auth.cookie, song) }
        result.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        throw CloudException(CloudErrorKind.NoLyrics, CloudMessages.NO_LYRICS, provider, cause = result.exceptionOrNull())
    }

    /**
     * `WeiyunMusicWrite.DeleteAudio`，参数按 APK 内部名推测为 `file_id`（规格 7.2，证据 B，需要真机确认）。
     * 写接口：超时 / 断线先翻列表确认，已经不在了就算成功，还在才重发一次。
     */
    override suspend fun delete(ref: CloudRef) {
        val auth = session()
        val body = request(auth, WRITE_MODULE, "DeleteAudio") {
            put("uin", auth.uinElement)
            put("file_id", ref.id)
        }
        repeat(2) { round ->
            try {
                QqCloudParse.data(write(body, auth.cookie))
                return
            } catch (unknown: OutcomeUnknown) {
                val lookup = attempt { find(ref.id) }
                if (lookup.isSuccess && lookup.getOrNull() == null) return
                if (round == 1 || lookup.isFailure) throw networkError(unknown.cause)
            }
        }
    }

    override suspend fun upload(
        source: CloudUploadSource,
        meta: CloudUploadMeta,
        progress: (CloudUploadProgress) -> Unit,
    ): CloudRef = throw CloudException(CloudErrorKind.UploadUnsupported, CloudMessages.QQ_UPLOAD_UNSUPPORTED, provider)

    // ---------------------------------------------------------------- 底层

    private data class Session(val cookie: String, val uin: String, val gtk: Int) {
        /** QQ 号能转数字就按数字传（和 QQProvider 其他 musicu 调用一致）。 */
        val uinElement: JsonPrimitive get() = uin.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(uin)
    }

    /** 没有 uin 就是没登录，不去撞接口。 */
    private suspend fun session(): Session {
        val value = cookie().trim()
        val (uin, gtk) = platform.login(value)
        if (value.isEmpty() || uin.isBlank() || uin == "0") {
            throw CloudException(CloudErrorKind.NotLoggedIn, CloudMessages.notLoggedIn(provider), provider)
        }
        return Session(value, uin, gtk)
    }

    /** 规格 7.2 的 comm：ct 24 / cv 4747474 / yqq.json，带 uin 和两个 g_tk。 */
    private fun request(
        auth: Session,
        module: String,
        method: String,
        param: JsonObjectBuilder.() -> Unit,
    ): String = buildJsonObject {
        putJsonObject("comm") {
            put("ct", 24)
            put("cv", 4747474)
            put("format", "json")
            put("platform", "yqq.json")
            put("needNewCode", 1)
            put("uin", auth.uinElement)
            put("g_tk", auth.gtk)
            put("g_tk_new_20200303", auth.gtk)
        }
        putJsonObject(QqCloudParse.REQ_KEY) {
            put("module", module)
            put("method", method)
            putJsonObject("param", param)
        }
    }.toString()

    private suspend fun read(body: String, cookie: String): String {
        var attemptIndex = 0
        while (true) {
            try {
                throttle()
                return transport.post(body, cookie)
            } catch (error: CloudException) {
                throw error
            } catch (error: IOException) {
                val status = httpStatus(error)
                if (status in 400..499) {
                    throw CloudException(CloudErrorKind.Server, CloudMessages.server(provider, status), provider, status, cause = error)
                }
                if (attemptIndex >= maxRetries) throw networkError(error)
                delay(retryBackoffMs shl attemptIndex)
                attemptIndex++
            }
        }
    }

    private suspend fun write(body: String, cookie: String): String {
        throttle()
        return try {
            transport.post(body, cookie)
        } catch (error: CloudException) {
            throw error
        } catch (error: IOException) {
            val status = httpStatus(error)
            if (status in 400..499) {
                throw CloudException(CloudErrorKind.Server, CloudMessages.server(provider, status), provider, status, cause = error)
            }
            throw OutcomeUnknown(error)
        }
    }

    private suspend fun throttle() {
        if (requestIntervalMs <= 0) return
        spacing.withLock {
            val wait = nextSlot - now()
            if (wait > 0) delay(wait)
            nextSlot = now() + requestIntervalMs
        }
    }

    private fun networkError(cause: Throwable?) =
        CloudException(CloudErrorKind.Network, CloudMessages.CLOUD_UNREACHABLE, provider, cause = cause)

    private fun httpStatus(error: IOException): Int =
        Regex("""^HTTP (\d{3})""").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** 写接口超时：请求可能已经生效，必须先确认。 */
    private class OutcomeUnknown(cause: IOException) : IOException(cause.message, cause)

    private fun songOf(mid: String) = Song(id = mid, source = "qq", extra = mapOf("songmid" to mid))

    companion object {
        const val READ_MODULE = "music.WeiyunMusic.WeiyunMusicRead"
        const val WRITE_MODULE = "music.WeiyunMusic.WeiyunMusicWrite"
        const val VIA_VKEY = "qq_vkey"

        /** 2000 一页，50 页就是十万首，够了；防止 has_more 一直为真时死循环。 */
        private const val MAX_PAGES = 50
    }
}
