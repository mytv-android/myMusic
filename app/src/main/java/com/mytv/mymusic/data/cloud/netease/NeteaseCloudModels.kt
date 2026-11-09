package com.mytv.mymusic.data.cloud.netease

import com.mytv.mymusic.data.cloud.CloudEntry
import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudJson
import com.mytv.mymusic.data.cloud.CloudMatch
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudQuota
import com.mytv.mymusic.data.cloud.arr
import com.mytv.mymusic.data.cloud.bool
import com.mytv.mymusic.data.cloud.cloudBaseNameOf
import com.mytv.mymusic.data.cloud.cloudExtensionOf
import com.mytv.mymusic.data.cloud.firstStr
import com.mytv.mymusic.data.cloud.has
import com.mytv.mymusic.data.cloud.int
import com.mytv.mymusic.data.cloud.long
import com.mytv.mymusic.data.cloud.obj
import com.mytv.mymusic.data.cloud.primitive
import com.mytv.mymusic.data.cloud.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 云盘列表 / byids 里的一条（规格 6.2 “云盘条目字段”）。
 * `songId` 是云盘条目 ID；`simpleSong.id` 是开源实现播放、匹配、删除时传的 ID，
 * 已匹配时就是曲库歌曲 ID。两者在未匹配时是否相同要真机确认（规格 14.1），所以两个都留着。
 */
data class NeteaseCloudItem(
    val songId: Long,
    val simpleId: Long,
    val fileName: String,
    val fileSize: Long,
    /** 列表里的码率，单位看服务端：大于 10000 的按 bps 处理。 */
    val bitrate: Int,
    val addTime: Long,
    /** matched / unmatched；老数据可能没有。 */
    val matchType: String,
    /** 未匹配时文件自带的元数据。 */
    val songName: String,
    val album: String,
    val artist: String,
    /** 列表接口不一定给；给了就拿来确认“是不是已经传过”。 */
    val md5: String,
    val simpleName: String,
    val simpleArtists: List<String>,
    val albumId: Long,
    val albumName: String,
    val picUrl: String,
    val durationMs: Long,
) {
    /** 优先看 matchType；没有时照 ncmExtend 用 `simpleSong.al.id > 0` 判断（规格 6.2 判据）。 */
    val matched: Boolean get() = if (matchType.isNotBlank()) matchType == "matched" else albumId > 0

    /** 解析用的条目 ID：`simpleSong.id`，拿不到时退回 `songId`（规格 3.2）。 */
    val resolveId: String get() = (if (simpleId > 0) simpleId else songId).toString()

    fun toEntry(): CloudEntry {
        val ext = cloudExtensionOf(fileName)
        val title = if (matched) simpleName else songName.ifBlank { simpleName }
        val artists = if (matched || artist.isBlank()) simpleArtists.joinToString("、") else artist
        return CloudEntry(
            provider = CloudProvider.NeteaseCloud.id,
            id = resolveId,
            fileName = fileName,
            fileSize = fileSize,
            ext = ext,
            title = title.ifBlank { cloudBaseNameOf(fileName) },
            artist = artists,
            album = if (matched) albumName else album.ifBlank { albumName },
            durationSec = (durationMs / 1000).toInt(),
            bitrate = if (bitrate > 10_000) bitrate / 1000 else bitrate,
            cover = picUrl,
            addedAt = addTime,
            md5 = md5,
            match = if (matched && simpleId > 0) CloudMatch("netease", simpleId.toString()) else null,
            playable = true,
            cloudSongId = songId.takeIf { it > 0 }?.toString().orEmpty(),
        )
    }
}

data class NeteaseCloudListPage(
    val items: List<NeteaseCloudItem>,
    val count: Int,
    /** 已用字节。 */
    val size: Long,
    /** 总容量字节；以它为准，不写死 5GB / 20GB（规格 6.4）。 */
    val maxSize: Long,
    val hasMore: Boolean,
) {
    val quota: CloudQuota get() = CloudQuota(size, maxSize)
}

data class NeteaseUploadCheck(val songId: String, val needUpload: Boolean)

data class NeteaseNosToken(val resourceId: String, val objectKey: String, val token: String)

/** NOS 每一片的回应：服务端确认收到的 offset 和续传用的 context。 */
data class NeteaseNosChunk(val offset: Long, val context: String)

data class NeteaseSubmitResult(val code: Int, val songId: String, val message: String) {
    val ok: Boolean get() = code == 200 && songId.isNotBlank() && songId != "0"

    /** 规格 6.3：`rep create failed` 不是最终失败，接着轮询转码状态。 */
    val repCreateFailed: Boolean get() = message.contains("rep create failed", ignoreCase = true)
}

/** `statuses[id]`：9 就绪，1 处理中；waitTime 是服务端建议的下次查询间隔（秒）。 */
data class NeteaseTranscodeStatus(val status: Int, val waitTimeSec: Int) {
    val ready: Boolean get() = status == STATUS_READY
    val processing: Boolean get() = status == STATUS_PROCESSING

    companion object {
        const val STATUS_READY = 9
        const val STATUS_PROCESSING = 1
    }
}

/** 云盘地址接口（`/api/cloud/dowonload`、`mobile/drive/file/playUrl`）回来的地址。 */
data class NeteaseCloudUrl(val url: String, val ext: String, val bitrate: Int, val size: Long)

/**
 * 网易云盘接口的响应解析。纯函数，测试直接喂 JSON。
 * 错误码：-460 / -462 是风控（“检测到您的网络环境存在风险”），301 是未登录。
 */
internal object NeteaseCloudParse {
    private val provider = CloudProvider.NeteaseCloud
    val RISK_CODES = setOf(-460, -462)
    val LOGIN_CODES = setOf(301)

    fun root(body: String): JsonObject = CloudJson.parseObject(body)
        ?: throw CloudException(CloudErrorKind.InvalidResponse, "网易云盘的回应看不懂", provider)

    fun messageOf(root: JsonObject): String = root.firstStr("msg", "message")

    /** 风控和未登录不管哪个接口都直接抛；其余错误码按 [allowed] 判断。 */
    fun checkCode(root: JsonObject, allowed: (Int) -> Boolean = { it == 200 }): Int {
        val code = root.int("code")
        when {
            code in RISK_CODES -> throw CloudException(CloudErrorKind.RiskControl, CloudMessages.RISK_CONTROL, provider, code)
            code in LOGIN_CODES -> throw CloudException(
                CloudErrorKind.NotLoggedIn,
                CloudMessages.notLoggedIn(provider),
                provider,
                code,
            )
            !allowed(code) -> throw CloudException(
                CloudErrorKind.Server,
                CloudMessages.server(provider, code) + messageOf(root).let { if (it.isBlank()) "" else "：$it" },
                provider,
                code,
            )
        }
        return code
    }

    fun ok(body: String, allowed: (Int) -> Boolean = { it == 200 }): JsonObject = root(body).also { checkCode(it, allowed) }

    fun listPage(body: String): NeteaseCloudListPage {
        val root = ok(body)
        val items = items(root.arr("data"))
        return NeteaseCloudListPage(
            items = items,
            count = root.int("count"),
            size = root.long("size"),
            maxSize = root.long("maxSize"),
            hasMore = root.bool("hasMore"),
        )
    }

    fun byIds(body: String): List<NeteaseCloudItem> = items(ok(body).arr("data"))

    fun items(array: JsonArray?): List<NeteaseCloudItem> =
        array.orEmpty().mapNotNull { (it as? JsonObject)?.let(::item) }

    fun item(obj: JsonObject): NeteaseCloudItem? {
        val simple = obj.obj("simpleSong")
        val songId = obj.long("songId")
        val simpleId = simple?.long("id") ?: 0
        if (songId <= 0 && simpleId <= 0) return null
        val al = simple?.obj("al")
        return NeteaseCloudItem(
            songId = songId,
            simpleId = simpleId,
            fileName = obj.str("fileName"),
            fileSize = obj.long("fileSize"),
            bitrate = obj.int("bitrate"),
            addTime = obj.long("addTime"),
            matchType = obj.str("matchType"),
            songName = obj.str("songName"),
            album = obj.str("album"),
            artist = obj.str("artist"),
            md5 = obj.str("md5"),
            simpleName = simple?.str("name").orEmpty(),
            simpleArtists = simple?.arr("ar").orEmpty()
                .mapNotNull { (it as? JsonObject)?.str("name")?.takeIf(String::isNotBlank) },
            albumId = al?.long("id") ?: 0,
            albumName = al?.str("name").orEmpty(),
            picUrl = al?.str("picUrl").orEmpty(),
            durationMs = simple?.long("dt") ?: 0,
        )
    }

    fun uploadCheck(body: String): NeteaseUploadCheck {
        val root = ok(body)
        val songId = root.str("songId")
        if (songId.isBlank()) throw CloudException(CloudErrorKind.InvalidResponse, "网易云盘查重没有返回 songId", provider)
        return NeteaseUploadCheck(songId = songId, needUpload = root.bool("needUpload"))
    }

    fun token(body: String): NeteaseNosToken {
        val result = ok(body).obj("result")
        val token = NeteaseNosToken(
            resourceId = result?.str("resourceId").orEmpty(),
            objectKey = result?.str("objectKey").orEmpty(),
            token = result?.str("token").orEmpty(),
        )
        if (token.resourceId.isBlank() || token.objectKey.isBlank() || token.token.isBlank()) {
            throw CloudException(CloudErrorKind.InvalidResponse, "网易云盘没有给上传令牌", provider)
        }
        return token
    }

    /** `lbs.upload[]`。https 放前面：令牌在请求头里，能加密就别明文传。 */
    fun uploadHosts(body: String): List<String> {
        val root = root(body)
        val hosts = root.arr("upload").orEmpty().mapNotNull { it.primitive()?.content?.trim()?.trimEnd('/') }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
        if (hosts.isEmpty()) throw CloudException(CloudErrorKind.InvalidResponse, "没拿到网易云盘的上传节点", provider)
        return hosts.sortedBy { if (it.startsWith("https://")) 0 else 1 }
    }

    fun nosChunk(body: String): NeteaseNosChunk {
        val root = root(body)
        return NeteaseNosChunk(offset = root.long("offset"), context = root.str("context"))
    }

    fun nosOffset(body: String): Long? {
        val root = CloudJson.parseObject(body) ?: return null
        return if (root.has("offset")) root.long("offset") else null
    }

    /** info/v2 不按 200 判死：非 200 要交给上传器决定是继续轮询还是重提。 */
    fun submit(body: String): NeteaseSubmitResult {
        val root = root(body)
        val code = checkCode(root, allowed = { true })
        return NeteaseSubmitResult(code = code, songId = root.str("songId"), message = messageOf(root))
    }

    /** `statuses` 正常是以 songId 为键的对象；兼容数组写法，只有一项时不挑键。 */
    fun status(body: String, songId: String): NeteaseTranscodeStatus? {
        val root = ok(body)
        val statuses = root["statuses"]
        val entry: JsonObject? = when (statuses) {
            is JsonObject -> statuses.obj(songId) ?: statuses.values.singleOrNull() as? JsonObject
            is JsonArray -> statuses.mapNotNull { it as? JsonObject }
                .let { list -> list.firstOrNull { it.str("songId") == songId } ?: list.singleOrNull() }
            else -> null
        }
        entry ?: return null
        if (!entry.has("status")) return null
        return NeteaseTranscodeStatus(status = entry.int("status"), waitTimeSec = entry.int("waitTime"))
    }

    fun publish(body: String): Int = checkCode(root(body), allowed = { it == 200 || it == 201 })

    /** user/song/match 回来的 matchData 就是一条更新后的云盘条目。 */
    fun match(body: String): NeteaseCloudItem? = ok(body).obj("matchData")?.let(::item)

    fun userId(body: String): Long {
        val root = ok(body)
        val fromProfile = root.obj("profile")?.long("userId") ?: 0
        val fromAccount = root.obj("account")?.long("id") ?: 0
        return fromProfile.takeIf { it > 0 } ?: fromAccount
    }

    /** 云盘地址：`data.url` / `data[0].url` / 根上的 `url`，字段名有几种写法，挨个试。 */
    fun cloudUrl(body: String): NeteaseCloudUrl? {
        val root = ok(body)
        val candidates = buildList {
            when (val data = root["data"]) {
                is JsonObject -> add(data)
                is JsonArray -> data.forEach { (it as? JsonObject)?.let(::add) }
                else -> Unit
            }
            add(root)
        }
        for (candidate in candidates) {
            val url = candidate.firstStr("url", "downloadUrl", "playUrl")
            if (url.startsWith("http")) {
                val br = candidate.int("br")
                return NeteaseCloudUrl(
                    url = url,
                    ext = candidate.firstStr("type", "ext").lowercase(),
                    bitrate = if (br > 10_000) br / 1000 else br,
                    size = candidate.long("size"),
                )
            }
        }
        return null
    }

    /** 云盘自定义歌词：`lrc` 可能是字符串，也可能是 `{lyric}`，可能在根上，也可能包在 `data` 里。 */
    fun cloudLyric(body: String): String {
        val root = ok(body)
        for (holder in listOfNotNull(root, root.obj("data"))) {
            for (key in listOf("lrc", "lyric")) {
                val text = when (val value = holder[key]) {
                    is JsonObject -> value.str("lyric")
                    else -> value.primitive()?.content.orEmpty()
                }
                if (text.isNotBlank()) return text.trim()
            }
        }
        return ""
    }
}
