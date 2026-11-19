package com.mytv.mymusic.data.lx

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.PlayableStream
import com.mytv.mymusic.data.native.extensionFromUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val MUSIC_ID_EXTRA_KEYS = setOf(
    "album_audio_id", "audio_id", "encode_album_audio_id", "content_id", "copyright_id",
    "resource_type", "media_mid", "album_mid", "vid", "rid", "file_id",
)

internal object LxProtocol {
    private val json = Json { ignoreUnknownKeys = true }
    /** 桌面版插件没有能力表，假定它认识这组常见档位；手机版脚本声明的档位不过这张表。 */
    private val standardQualities = listOf(
        "128k", "320k", "flac", "flac24bit",
        "32k", "64k", "192k", "256k", "500k", "640k", "sq", "ultra",
        "hires", "atmos", "atmos_plus", "master",
    )
    private val standardActions = listOf("musicUrl")
    private val localActions = listOf("musicUrl", "lyric", "pic")

    fun parseInit(data: String): Map<String, LxCapability> {
        val root = json.parseToJsonElement(data).jsonObject
        if (root["status"]?.jsonPrimitive?.contentOrNull != "true") {
            throw IllegalArgumentException(root["errorMessage"]?.jsonPrimitive?.contentOrNull ?: "脚本初始化失败")
        }
        val sources = root["info"]?.jsonObject?.get("sources")?.jsonObject
            ?: throw IllegalArgumentException("脚本没有返回 sources 能力表")
        val result = linkedMapOf<String, LxCapability>()
        for ((key, element) in sources) {
            if (key !in LxPlatform.labels) continue
            val source = element as? JsonObject ?: continue
            if (source["type"]?.jsonPrimitive?.contentOrNull != "music") continue
            val allowedActions = if (key == "local") localActions else standardActions
            val actions = source["actions"].stringList().filter { it in allowedActions }.distinct()
            // 档位不过滤：脚本声明什么就保留什么（lxserver 同款），选档与降档交给 preferredQualities/attemptQualities
            val qualities = if (key == "local") emptyList() else source["qualitys"].stringList().distinct()
            if (actions.isEmpty()) continue
            result[key] = LxCapability(
                name = source["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                actions = actions,
                qualities = qualities,
            )
        }
        require(result.isNotEmpty()) { "脚本没有声明可用的音乐平台" }
        return result
    }

    fun requestPayload(source: String, quality: String?, song: Song): JsonObject = buildJsonObject {
        put("source", source)
        put("action", "musicUrl")
        put("info", buildJsonObject {
            if (quality == null) put("type", JsonPrimitive(null)) else put("type", quality)
            put("musicInfo", musicInfo(source, song))
        })
    }

    fun musicInfo(source: String, song: Song): JsonObject = buildJsonObject {
        put("id", song.id)
        put("songmid", song.songMid(source))
        // 没有哈希就不要写这个键：脚本普遍用 `hash ?? songmid ?? id` 取值，空串会让它们直接判成缺 id
        val hash = song.extra["hash"].orEmpty().ifBlank { if (source == "kg") song.id else "" }
        if (hash.isNotBlank()) put("hash", hash)
        put("name", song.name)
        put("songname", song.name)
        put("singer", song.artist)
        put("artist", song.artist)
        put("album", song.album)
        put("albumname", song.album)
        put("album_id", song.albumId)
        put("duration", song.duration)
        put("interval", song.duration.toInterval())
        put("img", song.cover)
        put("cover", song.cover)
        put("source", source)
        song.extra.forEach { (key, value) ->
            if (key == "songmid" || key == "hash" || key.endsWith("_hash") || key in MUSIC_ID_EXTRA_KEYS) {
                put(key, value)
            }
        }
    }

    fun preferredQualities(capability: LxCapability, requested: String = "auto"): List<String> {
        val preferred = when (requested.trim().lowercase()) {
            "auto" -> listOf("320k", "128k", "flac", "flac24bit")
            "preview", "standard", "128", "128k", "m500", "pq" -> listOf("128k")
            "high", "320", "320k", "m800", "hq", "exhigh", "highest" -> listOf("320k")
            "lossless", "flac", "f000" -> listOf("flac")
            // 扩展档先按脚本声明的原生档位请求，取不到再顺着无损阶梯降，宁可降档也要让这首歌响
            "hires", "hi_res", "hi-res", "24bit", "sq", "zq", "zq24" -> listOf("hires", "flac24bit", "flac", "sq")
            "ultra", "640", "640k", "ogg", "o801" -> listOf("ultra", "hires", "flac24bit", "flac")
            "spatial", "sky", "jyeffect", "atmos", "q000", "dolby" -> listOf("atmos", "atmos_plus", "hires", "flac24bit", "flac")
            "master", "ai00", "zq32", "jymaster" -> listOf("master", "hires", "flac24bit", "flac")
            else -> emptyList()
        }
        return preferred.filter { it in capability.qualities }
    }

    /**
     * 单个脚本平台的尝试档位：锁的档位脚本没有就顺着降——偏好链 → 自动档链 → 脚本声明的
     * 全部档位，一个档都没声明就交 null 让脚本自己挑。宁可降档也要让这首歌响。
     */
    fun attemptQualities(capability: LxCapability, requested: String): List<String?> =
        preferredQualities(capability, requested)
            .ifEmpty { preferredQualities(capability, "auto") }
            .ifEmpty { capability.qualities }
            .ifEmpty { listOf<String?>(null) }

    fun stream(url: String, quality: String, record: LxSourceRecord): PlayableStream {
        require(url.length <= 2048 && (url.startsWith("https://") || url.startsWith("http://"))) {
            "脚本返回了无效的播放地址"
        }
        return PlayableStream(
            url = url,
            ext = extensionFromUrl(url),
            bitrate = when (quality) {
                "128k" -> 128
                "320k" -> 320
                "flac", "flac24bit" -> 1411
                // 标称码率，只用于展示：Hi-Res 24bit/96k、母带 24bit/192k、全景声 DD+ JOC
                "hires" -> 4608
                "master" -> 9216
                "atmos", "atmos_plus" -> 768
                "ultra" -> 640
                else -> quality.trimEnd('k').toIntOrNull() ?: 0
            },
            level = quality,
            extra = mapOf(
                "lx_source" to record.name,
                "lx_source_id" to record.id,
                "lx_quality" to quality,
            ),
        )
    }

    /** 桌面版插件没有平台概念：它按自己理解的一组字段去请求（酷我用的 id / name / singer / album）。 */
    fun desktopMusicItem(song: Song): JsonObject = buildJsonObject {
        put("id", song.extra["rid"].orEmpty().ifBlank { song.id })
        put("name", song.name)
        put("singer", song.artist)
        put("artist", song.artist)
        put("album", song.album)
        put("albumName", song.album)
        put("source", song.source)
        put("platform", song.source)
        put("hash", song.extra["hash"].orEmpty())
        put("duration", song.duration)
        put("cover", song.cover)
        put("img", song.cover)
        song.extra.forEach { (key, value) -> put(key, value) }
    }

    /**
     * 桌面版插件导出的是函数表，没有按平台声明能力。取流脚本一律当作「所有平台都能试」：
     * 真取不到就按失败回退，不能让它在换源面板里凭空消失。
     */
    fun parseDesktopCapabilities(descriptor: String): Map<String, LxCapability> {
        val root = runCatching { json.parseToJsonElement(descriptor).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("桌面版插件没有导出任何函数")
        val functions = (root["functions"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }
        require("getMediaSource" in functions) { "桌面版插件没有导出 getMediaSource，手机端目前只支持取流" }
        return LxPlatform.labels.keys.associateWith {
            LxCapability(name = "", actions = listOf("musicUrl"), qualities = standardQualities)
        }
    }

    private fun Song.songMid(source: String): String = when (source) {
        "mg" -> extra["copyright_id"].orEmpty().ifBlank { extra["songmid"].orEmpty() }.ifBlank { id.substringBefore('|') }
        "kg" -> extra["songmid"].orEmpty().ifBlank { extra["album_audio_id"].orEmpty() }.ifBlank { id }
        else -> extra["songmid"].orEmpty().ifBlank { id }
    }

    private fun JsonElement?.stringList(): List<String> = (this as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull
    }

    private fun Int.toInterval(): String = if (this <= 0) "" else "%02d:%02d".format(this / 60, this % 60)
}
