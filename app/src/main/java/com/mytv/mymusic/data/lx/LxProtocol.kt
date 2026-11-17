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
    private val standardQualities = listOf("128k", "320k", "flac", "flac24bit")
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
            val qualities = if (key == "local") emptyList() else {
                source["qualitys"].stringList().filter { it in standardQualities }.distinct()
            }
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
        put("hash", song.extra["hash"].orEmpty().ifBlank { if (source == "kg") song.id else "" })
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
            "hires", "hi_res", "hi-res", "24bit", "sq", "zq", "zq24" -> listOf("flac24bit")
            else -> emptyList()
        }
        return preferred.filter { it in capability.qualities }
    }

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
                else -> 0
            },
            level = quality,
            extra = mapOf(
                "lx_source" to record.name,
                "lx_source_id" to record.id,
                "lx_quality" to quality,
            ),
        )
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
