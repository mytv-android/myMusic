package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder

private const val MHUB_ID = "mhub"
private const val MHUB_BASE = "https://music.op.wiki"

/**
 * music.op.wiki 是开源 MHub 的公开实例：服务端替客户端准备 Apple Music 等平台的
 * 完整音频，客户端只做搜索、任务轮询和字节读取。
 *
 * 服务端按 IP 限流（源码默认约每分钟一次下载），所以准备任务严格串行、同曲复用，
 * 不预取、不并发、不批量。
 */
private val MHUB_INFO = ProviderInfo(
    id = MHUB_ID,
    name = "music.op.wiki",
    search = true,
    download = true,
    lyrics = true,
    parseSong = true,
    playlistSongs = true,
    parsePlaylist = true,
    albumSongs = true,
    parseAlbum = true,
    loginRequired = false,
    note = "MHub 公共实例，匿名可用；音频逐首准备，曲库以 Apple Music 为主",
)

class MHubProvider : BaseNativeProvider(MHUB_INFO) {
    private val json = NativeHttp.json

    private data class Prepared(val url: String, val ext: String, val bitrate: Int, val at: Long)

    private val prepared = mutableMapOf<String, Prepared>()

    /** 一次只准备一首，避免撞上服务端的下载限流。 */
    private val prepareGate = Mutex()

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        if (keyword.isBlank()) return emptyList()
        val body = NativeHttp.get(
            "$MHUB_BASE/api/v1/search?platform=applemusic&q=${encode(keyword)}&limit=20",
            headers = mapOf("Accept" to "application/json"),
        )
        val items = runCatching { json.parseToJsonElement(body) as JsonArray }.getOrElse {
            throw IllegalStateException("music.op.wiki 搜索返回异常：${body.take(160)}")
        }
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id")
            if (id.isBlank()) return@mapNotNull null
            Song(
                id = id,
                name = obj.str("song"),
                artist = obj.str("singer"),
                source = MHUB_ID,
                cover = obj.str("cover"),
                link = "https://music.apple.com/song/$id",
                ext = "m4a",
                extra = mapOf("platform" to "applemusic"),
            )
        }
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val (platform, id) = detectLink(link)
        return when (platform) {
            "applemusic" -> appleSong(id)
            "youtubemusic" -> youtubeSong(id)
            else -> throw IllegalArgumentException("music.op.wiki 只解析 Apple Music 和 YouTube Music 链接")
        }
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String =
        prepare(song).url

    override suspend fun playableStream(cookie: String, song: Song, workDir: File): PlayableStream {
        val prepared = prepare(song)
        return PlayableStream(url = prepared.url, ext = prepared.ext, bitrate = prepared.bitrate)
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        val url = buildString {
            append(MHUB_BASE).append("/api/v1/lyrics")
            append("?platform=").append(platformOf(song))
            append("&track_id=").append(encode(song.id))
            if (song.name.isNotBlank()) append("&title=").append(encode(song.name))
            if (song.artist.isNotBlank()) append("&artist=").append(encode(song.artist))
            if (song.duration > 0) append("&duration=").append(song.duration)
        }
        val body = NativeHttp.get(url, headers = mapOf("Accept" to "application/json"))
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return ""
        if (root.str("available") == "false") return ""
        return root.str("plain")
    }

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult =
        resolveCollection(link, album = false)

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> =
        resolveCollection(decodeCollectionId(id), album = false).songs

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult =
        resolveCollection(link, album = true)

    override suspend fun albumSongs(cookie: String, id: String): List<Song> =
        resolveCollection(decodeCollectionId(id), album = true).songs

    // ---------------- 音乐准备 ----------------

    private suspend fun prepare(song: Song): Prepared {
        val platform = platformOf(song)
        val key = "$platform:${song.id}"
        if (song.id.isBlank()) throw IllegalArgumentException("music.op.wiki 曲目 ID 为空")
        synchronized(prepared) { prepared[key] }?.takeIf {
            System.currentTimeMillis() - it.at < PREPARED_TTL_MS
        }?.let { return it }

        return prepareGate.withLock {
            synchronized(prepared) { prepared[key] }?.takeIf {
                System.currentTimeMillis() - it.at < PREPARED_TTL_MS
            }?.let { return@withLock it }

            val task = createPlayback(platform, song)
            val result = awaitPlayback(task)
            synchronized(prepared) { prepared[key] = result }
            result
        }
    }

    private data class TaskHandle(val statusUrl: String, val audioUrl: String)

    private suspend fun createPlayback(platform: String, song: Song): TaskHandle {
        val query = song.id
        val body = buildJsonObject {
            put("platform", platform)
            put("query", query)
            if (!query.startsWith("http")) put("selected_id", song.id)
            put("quality", "high")
            put("include_cover", false)
            if (song.name.isNotBlank()) put("title", song.name)
            if (song.artist.isNotBlank()) put("artist", song.artist)
        }.toString()
        val response = runCatching {
            NativeHttp.postJson(
                "$MHUB_BASE/api/v1/playbacks",
                body,
                headers = mapOf("Accept" to "application/json"),
            )
        }.getOrElse { error ->
            val message = error.message.orEmpty()
            throw IllegalStateException(
                when {
                    "HTTP 429" in message -> "music.op.wiki 正在限流，请稍后再试"
                    "HTTP 5" in message -> "music.op.wiki 暂时不可用"
                    else -> message.ifBlank { "音源请求失败" }
                },
            )
        }
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrElse {
            throw IllegalStateException("music.op.wiki 音源准备失败：${response.body.take(160)}")
        }
        val playback = root["playback"] as? JsonObject ?: root
        if (playback.str("status") == "failed") {
            throw IllegalStateException(playback.str("error").ifBlank { "音源准备失败" })
        }
        val statusUrl = root.str("status_url").ifBlank {
            "/api/v1/playbacks/${playback.str("id")}"
        }
        return TaskHandle(absoluteUrl(statusUrl), absoluteUrl(playback.str("audio_url")))
    }

    private suspend fun awaitPlayback(task: TaskHandle): Prepared {
        val deadline = System.currentTimeMillis() + PREPARE_TIMEOUT_MS
        var lastUrl = task.audioUrl
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val body = try {
                NativeHttp.get(task.statusUrl, headers = mapOf("Accept" to "application/json"))
            } catch (error: Exception) {
                if ("HTTP 404" in error.message.orEmpty()) {
                    throw IllegalStateException("音源任务已失效，请重试")
                }
                continue
            }
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: continue
            val audio = root.str("audio_url").ifBlank { lastUrl }
            if (audio.isNotBlank()) lastUrl = absoluteUrl(audio)
            when (root.str("status")) {
                "completed" -> {
                    val url = absoluteUrl(root.str("audio_url").ifBlank { lastUrl })
                    if (url.isBlank()) throw IllegalStateException("音源已就绪但没有播放地址")
                    return Prepared(
                        url = url,
                        ext = extensionOf(root.str("content_type"), root.str("format")),
                        bitrate = (root["bitrate"]?.jsonPrimitive?.intOrNull ?: 0) / 1000,
                        at = System.currentTimeMillis(),
                    )
                }
                "failed" -> throw IllegalStateException(root.str("error").ifBlank { "音源准备失败" })
            }
        }
        throw IllegalStateException("音源准备超时，请稍后重试")
    }

    // ---------------- 歌单 / 专辑 ----------------

    private suspend fun resolveCollection(link: String, album: Boolean): CollectionResult {
        val (platform, id) = detectLink(link)
        val url = link.trim().takeIf { it.startsWith("http") } ?: id
        val body = NativeHttp.get(
            "$MHUB_BASE/api/v1/collections/resolve?platform=$platform&url=${encode(url)}",
            headers = mapOf("Accept" to "application/json"),
        )
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw IllegalStateException("music.op.wiki 歌单解析失败：${body.take(160)}")
        }
        val items = root["items"] as? JsonArray ?: JsonArray(emptyList())
        val songs = items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val songId = obj.str("id")
            if (songId.isBlank()) return@mapNotNull null
            Song(
                id = songId,
                name = obj.str("song"),
                artist = obj.str("singer"),
                source = MHUB_ID,
                cover = obj.str("cover"),
                ext = "m4a",
                extra = mapOf("platform" to platform),
            )
        }
        if (songs.isEmpty()) throw IllegalStateException("这个歌单是空的，或者 music.op.wiki 暂不支持它")
        val title = root.str("title").ifBlank { if (album) "Apple Music 专辑" else "Apple Music 歌单" }
        val playlist = Playlist(
            id = encodeCollectionId(platform, url),
            name = title,
            cover = songs.firstOrNull()?.cover.orEmpty(),
            trackCount = songs.size,
            source = MHUB_ID,
            link = url,
        )
        return CollectionResult(playlist, songs)
    }

    // ---------------- 元数据 ----------------

    /** Apple 的目录元数据沿用已有的 Apple 源去取（匿名 developer token），MHub 只负责音频。 */
    private suspend fun appleSong(id: String): Song {
        if (!id.all(Char::isDigit)) {
            throw IllegalArgumentException("这个 Apple Music ID 不在目录里，请直接用搜索选择歌曲")
        }
        // MHub 的 Apple 曲库可能来自不同商店，同一个 ID 并非每个区都能查到。
        val apple = AppleProvider()
        val parsed = listOf("cn", "hk", "tw", "us", "jp").firstNotNullOfOrNull { storefront ->
            runCatching { apple.parseSong("storefront=$storefront", id) }.getOrNull()
        } ?: throw IllegalArgumentException("找不到这首 Apple Music 歌曲，请直接用搜索选择")
        return parsed.copy(
            id = id,
            source = MHUB_ID,
            url = "",
            extra = mapOf("platform" to "applemusic"),
        )
    }

    private suspend fun youtubeSong(id: String): Song {
        val body = NativeHttp.get(
            "https://www.youtube.com/oembed?format=json&url=" +
                encode("https://music.youtube.com/watch?v=$id"),
            headers = mapOf("Accept" to "application/json"),
        )
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw IllegalStateException("YouTube Music 链接解析失败：${body.take(160)}")
        }
        return Song(
            id = id,
            name = root.str("title"),
            artist = root.str("author_name").removeSuffix(" - Topic"),
            source = MHUB_ID,
            cover = root.str("thumbnail_url"),
            link = "https://music.youtube.com/watch?v=$id",
            ext = "m4a",
            extra = mapOf("platform" to "youtubemusic"),
        )
    }

    // ---------------- 工具 ----------------

    private fun platformOf(song: Song): String =
        song.extra["platform"]?.takeIf { it.isNotBlank() } ?: "applemusic"

    internal fun detectLink(link: String): Pair<String, String> {
        val value = link.trim()
        APPLE_ID.find(value)?.groupValues?.get(1)?.let { return "applemusic" to it }
        APPLE_LINK.find(value)?.groupValues?.get(1)?.let { return "applemusic" to it }
        if (APPLE_COLLECTION.containsMatchIn(value)) return "applemusic" to value
        YT_ID.find(value)?.groupValues?.get(1)?.let { return "youtubemusic" to it }
        if (NETEASE_HOST.containsMatchIn(value)) return "netease" to value
        if (value.all(Char::isDigit) && value.isNotEmpty()) return "applemusic" to value
        throw IllegalArgumentException("无法从链接识别平台：$link")
    }

    private fun extensionOf(contentType: String, format: String): String {
        val type = contentType.lowercase()
        return when {
            "flac" in type -> "flac"
            "mp4" in type || "m4a" in type || "aac" in type -> "m4a"
            "mpeg" in type || "mp3" in type -> "mp3"
            format.isNotBlank() -> format.lowercase()
            else -> "m4a"
        }
    }

    private fun absoluteUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http") -> url
        url.startsWith("/") -> MHUB_BASE + url
        else -> "$MHUB_BASE/$url"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encodeCollectionId(platform: String, url: String): String = "$platform::$url"

    private fun decodeCollectionId(id: String): String {
        val platform = id.substringBefore("::")
        val url = id.substringAfter("::", id)
        return if (platform.isBlank() || url == id) url else url
    }

    private fun JsonObject?.str(key: String): String =
        ((this?.get(key) as? JsonPrimitive)?.contentOrNull).orEmpty()

    private companion object {
        val APPLE_ID = Regex("""[?&]i=(\d+)""")
        val APPLE_LINK = Regex("""/song/[^/]+/(\d+)""")
        val APPLE_COLLECTION = Regex("""/(?:album|playlist)/[^/]+/[A-Za-z0-9._-]+""")
        val YT_ID = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})""")
        val NETEASE_HOST = Regex("""https?://(?:y\.)?music\.163\.com/""")

        const val PREPARED_TTL_MS = 20L * 60 * 60 * 1000
        const val POLL_INTERVAL_MS = 2_000L
        const val PREPARE_TIMEOUT_MS = 150_000L
    }
}
