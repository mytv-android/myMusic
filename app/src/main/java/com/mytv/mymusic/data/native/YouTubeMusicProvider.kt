package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

private const val YTM_ID = "youtubemusic"
private const val MUSIC_ORIGIN = "https://music.youtube.com"
private const val INNERTUBE = "$MUSIC_ORIGIN/youtubei/v1"
private const val OP_BASE = "https://music.op.wiki"
private const val CLIENT_NAME = "WEB_REMIX"
private const val CLIENT_VERSION = "1.20241106.01.00"
private const val CLIENT_ID = "67"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * 音乐准备服务（music.op.wiki，开源 MHub 的公开实例）已经替客户端处理了
 * InnerTube 取流所需的签名与验证，所以这里只负责：搜索、准备任务轮询、
 * 用 InnerTube 浏览公开歌单/榜单/私人音乐库。
 *
 * 取流必须串行：服务端按 IP 限流（源码默认下载约 1 次/分钟），同一首歌只建一个
 * 任务，完成结果在本机复用 20 小时，绝不预取、不并行准备。
 */
private val YTM_INFO = ProviderInfo(
    id = YTM_ID,
    name = "YouTube Music",
    search = true,
    download = true,
    lyrics = true,
    parseSong = true,
    playlistSongs = true,
    parsePlaylist = true,
    userPlaylists = true,
    qrLogin = false,
    topLists = true,
    topListSongs = true,
    note = "音频由 music.op.wiki 准备，逐首串行；私人歌单需要登录 Cookie",
)

class YouTubeMusicProvider : BaseNativeProvider(YTM_INFO) {
    private val json = NativeHttp.json

    private data class Prepared(val url: String, val ext: String, val bitrate: Int, val at: Long)

    /** 播放任务准备结果；同一首歌反复播放、换源重试都复用这一份地址。 */
    private val prepared = mutableMapOf<String, Prepared>()

    /** 服务端按 IP 限流，所有新建任务排成一队，一次只准备一首。 */
    private val prepareGate = Mutex()

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        if (keyword.isBlank()) return emptyList()
        val url = buildString {
            append(OP_BASE).append("/api/v1/search")
            append("?platform=").append(YTM_ID)
            append("&q=").append(URLEncoder.encode(keyword, "UTF-8"))
            append("&limit=").append(20)
        }
        val body = NativeHttp.get(url, headers = mapOf("Accept" to "application/json"))
        val items = runCatching { json.parseToJsonElement(body).jsonArray }.getOrElse {
            throw IllegalStateException("YouTube Music 搜索返回异常：${body.take(160)}")
        }
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.str("id")
            if (id.isBlank()) return@mapNotNull null
            Song(
                id = id,
                name = item.str("song"),
                artist = item.str("singer"),
                source = YTM_ID,
                cover = item.str("cover"),
                link = watchLink(id),
                ext = "m4a",
                extra = mapOf("backend" to "mhub"),
            )
        }
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val id = extractVideoId(link) ?: throw IllegalArgumentException("不是 YouTube Music 歌曲链接：$link")
        return fetchVideoInfo(id)
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String =
        prepare(song.id, song.name, song.artist).url

    override suspend fun playableStream(cookie: String, song: Song, workDir: File): PlayableStream {
        val prepared = prepare(song.id, song.name, song.artist)
        return PlayableStream(
            url = prepared.url,
            ext = prepared.ext,
            bitrate = prepared.bitrate,
            extra = mapOf("backend" to "mhub"),
        )
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        val url = buildString {
            append(OP_BASE).append("/api/v1/lyrics")
            append("?platform=").append(YTM_ID)
            append("&track_id=").append(URLEncoder.encode(song.id, "UTF-8"))
            if (song.name.isNotBlank()) append("&title=").append(URLEncoder.encode(song.name, "UTF-8"))
            if (song.artist.isNotBlank()) append("&artist=").append(URLEncoder.encode(song.artist, "UTF-8"))
            if (song.duration > 0) append("&duration=").append(song.duration)
        }
        val body = NativeHttp.get(url, headers = mapOf("Accept" to "application/json"))
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return ""
        if (root["available"]?.jsonPrimitive?.contentOrNull == "false") return ""
        return root.str("plain")
    }

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val id = extractPlaylistId(link)
            ?: throw IllegalArgumentException("不是 YouTube Music 歌单链接：$link")
        val browseId = if (id.startsWith("VL")) id else "VL$id"
        val detail = browsePlaylist(cookie, browseId)
        val songs = detail.songs.ifEmpty {
            throw IllegalStateException("这个歌单是空的，或者不是公开歌单")
        }
        return CollectionResult(
            item = Playlist(
                id = browseId.removePrefix("VL"),
                name = detail.name,
                cover = detail.cover,
                trackCount = songs.size,
                creator = detail.creator,
                source = YTM_ID,
                link = "https://music.youtube.com/playlist?list=${browseId.removePrefix("VL")}",
            ),
            songs = songs,
        )
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> {
        val browseId = if (id.startsWith("VL")) id else "VL$id"
        return browsePlaylist(cookie, browseId).songs
    }

    override suspend fun topLists(cookie: String): List<TopList> {
        val root = browse(cookie, browseId = "FEmusic_charts")
        return root.collect("musicTwoRowItemRenderer").mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val browse = item.path("navigationEndpoint", "browseEndpoint", "browseId").str()
            if (!browse.startsWith("VL")) return@mapNotNull null
            val name = item.path("title").runsText()
            if (name.isBlank()) return@mapNotNull null
            TopList(
                id = browse.removePrefix("VL"),
                name = name,
                cover = item.thumbnailUrl(),
                source = YTM_ID,
                link = "https://music.youtube.com/playlist?list=${browse.removePrefix("VL")}",
            )
        }.distinctBy { it.id }
    }

    override suspend fun topListSongs(cookie: String, id: String): List<Song> {
        val browseId = if (id.startsWith("VL")) id else "VL$id"
        return browsePlaylist(cookie, browseId).songs
    }

    override suspend fun userPlaylists(cookie: String, page: Int, limit: Int): List<Playlist> {
        if (cookie.isBlank() || page > 1) return emptyList()
        val root = browse(cookie, browseId = "FEmusic_library_landing")
        return root.collect("musicTwoRowItemRenderer").mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val browse = item.path("navigationEndpoint", "browseEndpoint", "browseId").str()
            if (!browse.startsWith("VL")) return@mapNotNull null
            val name = item.path("title").runsText()
            if (name.isBlank()) return@mapNotNull null
            Playlist(
                id = browse.removePrefix("VL"),
                name = name,
                cover = item.thumbnailUrl(),
                creator = item.path("subtitle").runsText().substringBefore(" • ").trim(),
                source = YTM_ID,
                link = "https://music.youtube.com/playlist?list=${browse.removePrefix("VL")}",
            )
        }.distinctBy { it.id }
    }

    override suspend fun accountInfo(cookie: String): AccountInfo? {
        if (cookie.isBlank()) return null
        val root = runCatching {
            innerTube("account/account_menu", buildJsonObject {}, cookie)
        }.getOrNull() ?: return null
        val name = root.collect("accountName").firstOrNull()?.runsText().orEmpty()
        val avatar = root.collect("accountPhoto").firstOrNull()?.thumbnailUrl().orEmpty()
        if (name.isBlank() && avatar.isBlank()) return null
        return AccountInfo(nickname = name, avatar = avatar, detail = "YouTube Music")
    }

    // ---------------- 音乐准备服务 ----------------

    private suspend fun prepare(id: String, name: String, artist: String): Prepared {
        if (id.isBlank()) throw IllegalArgumentException("YouTube Music 曲目 ID 为空")
        synchronized(prepared) { prepared[id] }?.takeIf {
            System.currentTimeMillis() - it.at < PREPARED_TTL_MS
        }?.let { return it }

        return prepareGate.withLock {
            synchronized(prepared) { prepared[id] }?.takeIf {
                System.currentTimeMillis() - it.at < PREPARED_TTL_MS
            }?.let { return@withLock it }

            val task = createPlayback(id, name, artist)
            val result = awaitPlayback(task)
            synchronized(prepared) { prepared[id] = result }
            result
        }
    }

    private data class TaskHandle(val statusUrl: String, val audioUrl: String)

    private suspend fun createPlayback(id: String, name: String, artist: String): TaskHandle {
        val body = buildJsonObject {
            put("platform", YTM_ID)
            put("query", id)
            put("selected_id", id)
            put("quality", "high")
            put("include_cover", false)
            if (name.isNotBlank()) put("title", name)
            if (artist.isNotBlank()) put("artist", artist)
        }.toString()
        val response = runCatching {
            NativeHttp.postJson(
                "$OP_BASE/api/v1/playbacks",
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
            throw IllegalStateException("YouTube Music 音源准备失败：${response.body.take(160)}")
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
            val status = root.str("status")
            val audio = root.str("audio_url").ifBlank { lastUrl }
            if (audio.isNotBlank()) lastUrl = absoluteUrl(audio)
            when (status) {
                "completed" -> {
                    val url = absoluteUrl(root.str("audio_url").ifBlank { lastUrl })
                    if (url.isBlank()) throw IllegalStateException("音源已就绪但没有播放地址")
                    val ext = extensionOf(root.str("content_type"), root.str("format"))
                    val bitrate = (root["bitrate"]?.jsonPrimitive?.intOrNull ?: 0) / 1000
                    return Prepared(url = url, ext = ext, bitrate = bitrate, at = System.currentTimeMillis())
                }
                "failed" -> throw IllegalStateException(root.str("error").ifBlank { "音源准备失败" })
            }
        }
        throw IllegalStateException("音源准备超时，请稍后重试")
    }

    // ---------------- InnerTube ----------------

    private suspend fun browse(cookie: String, browseId: String): JsonElement =
        innerTube("browse", buildJsonObject { put("browseId", browseId) }, cookie)

    private class PlaylistDetail(
        val name: String,
        val cover: String,
        val creator: String,
        val songs: List<Song>,
    )

    private suspend fun browsePlaylist(cookie: String, browseId: String): PlaylistDetail {
        val first = browse(cookie, browseId)
        val header = first.collect("musicDetailHeaderRenderer").firstOrNull()
            ?: first.collect("musicResponsiveHeaderRenderer").firstOrNull()
        val name = header?.path("title")?.runsText().orEmpty()
        val creator = headerCreator(header)
        val cover = header?.thumbnailUrl().orEmpty().ifBlank {
            first.collect("musicThumbnailRenderer").firstOrNull()?.thumbnailUrl().orEmpty()
        }
        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        songs += parseTracks(first).filter { seen.add(it.id) }
        var next = first.continuationToken()
        var pages = 1
        while (next != null && pages < MAX_PLAYLIST_PAGES) {
            val page = innerTube("browse", buildJsonObject { put("continuation", next) }, cookie)
            val added = parseTracks(page).filter { seen.add(it.id) }
            if (added.isEmpty()) break
            songs += added
            next = page.continuationToken()
            pages++
        }
        return PlaylistDetail(name = name.ifBlank { "YouTube Music 歌单" }, cover = cover, creator = creator, songs = songs)
    }

    /**
     * 头部副标题是「类型 • 作者 • 年份」这样的分段，第一段是 Playlist/Album 这类类型词。
     * 作者优先取带歌手页跳转的那一段，没有就跳过类型词取第一段。
     */
    private fun headerCreator(header: JsonElement?): String {
        val runs = (header as? JsonObject)?.path("subtitle")?.path("runs") as? JsonArray ?: return ""
        val pages = runs.map { run ->
            run.path(
                "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig", "pageType",
            ).str() to run.path("text").str()
        }
        pages.firstOrNull { it.first == "MUSIC_PAGE_TYPE_ARTIST" }?.second?.takeIf {
            it.isNotBlank()
        }?.let { return it }
        return pages.map { it.second }.map(String::trim)
            .firstOrNull { it.isNotBlank() && it !in HEADER_TYPE_WORDS }
            .orEmpty()
    }

    /** 歌单/榜单/专辑页共用：把 musicResponsiveListItemRenderer 转成曲目。 */
    internal fun parseTracks(root: JsonElement): List<Song> {
        val out = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        root.collect("musicResponsiveListItemRenderer").forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val videoId = item.path("playlistItemData", "videoId").str()
                .ifBlank { item.path("flexColumns").firstRunVideoId() }
            if (videoId.isBlank() || !seen.add(videoId)) return@forEach
            val columns = item["flexColumns"]?.jsonArray ?: JsonArray(emptyList())
            fun column(index: Int): String = columns.getOrNull(index)
                .path("musicResponsiveListItemFlexColumnRenderer", "text")
                .runsText()
            val title = column(0)
            if (title.isBlank()) return@forEach
            val artist = column(1)
            val album = column(2)
            out += Song(
                id = videoId,
                name = title,
                artist = artist,
                album = album,
                duration = durationOf(item),  // 时长只在固定列里，取不到就留 0
                source = YTM_ID,
                cover = item.thumbnailUrl(),
                link = watchLink(videoId),
                ext = "m4a",
                extra = mapOf("backend" to "mhub"),
            )
        }
        return out
    }

    private suspend fun fetchVideoInfo(id: String): Song {
        val body = NativeHttp.get(
            "https://www.youtube.com/oembed?format=json&url=" +
                URLEncoder.encode(watchLink(id), "UTF-8"),
            headers = mapOf("Accept" to "application/json"),
        )
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw IllegalStateException("YouTube Music 链接解析失败：${body.take(160)}")
        }
        return Song(
            id = id,
            name = root.str("title"),
            artist = root.str("author_name").removeSuffix(" - Topic"),
            source = YTM_ID,
            cover = root.str("thumbnail_url"),
            link = watchLink(id),
            ext = "m4a",
            extra = mapOf("backend" to "mhub"),
        )
    }

    private suspend fun innerTube(path: String, payload: JsonObject, cookie: String): JsonElement {
        val body = buildJsonObject {
            for ((key, value) in payload) put(key, value)
            put(
                "context",
                buildJsonObject {
                    put(
                        "client",
                        buildJsonObject {
                            put("clientName", CLIENT_NAME)
                            put("clientVersion", CLIENT_VERSION)
                            put("hl", "en")
                            put("gl", "US")
                        },
                    )
                },
            )
        }.toString()
        val response = NativeHttp.postJson(
            "$INNERTUBE/$path?prettyPrint=false&key=$INNERTUBE_KEY",
            body,
            headers = buildMap {
                put("X-Youtube-Client-Name", CLIENT_ID)
                put("X-Youtube-Client-Version", CLIENT_VERSION)
                put("X-Origin", MUSIC_ORIGIN)
                put("Origin", MUSIC_ORIGIN)
                put("Accept", "application/json")
                putAll(authHeaders(cookie))
            },
            cookie = cookie,
            userAgent = USER_AGENT,
        )
        return runCatching { json.parseToJsonElement(response.body) }.getOrElse {
            throw IllegalStateException("YouTube Music 接口返回异常：${response.body.take(160)}")
        }
    }

    /**
     * YouTube 的 Cookie 鉴权：用 SAPISID（或 __Secure-3PAPISID）对
     * `时间戳 空格 cookie 空格 origin` 做 SHA-1，放进 Authorization。
     * 只有公开内容时不要带这个头，带了反而可能被当作登录态校验。
     */
    internal fun authHeaders(cookie: String, timestamp: Long = System.currentTimeMillis() / 1000): Map<String, String> {
        if (cookie.isBlank()) return emptyMap()
        val jar = cookie.split(';').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.toMap()
        val sapisid = jar["SAPISID"] ?: jar["__Secure-3PAPISID"] ?: jar["__Secure-1PAPISID"] ?: return emptyMap()
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $sapisid $MUSIC_ORIGIN".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return mapOf(
            "Authorization" to "SAPISIDHASH ${timestamp}_$digest",
            "X-Goog-AuthUser" to "0",
        )
    }

    // ---------------- JSON 小工具 ----------------

    private fun JsonElement.collect(key: String): List<JsonElement> = when (this) {
        is JsonObject -> buildList {
            this@collect[key]?.let(::add)
            for ((name, value) in this@collect) if (name != key) addAll(value.collect(key))
        }
        is JsonArray -> flatMap { it.collect(key) }
        else -> emptyList()
    }

    private fun JsonElement?.path(vararg keys: String): JsonElement? {
        var current: JsonElement? = this
        for (key in keys) {
            current = (current as? JsonObject)?.get(key)
        }
        return current
    }

    private fun JsonElement?.str(key: String = ""): String {
        val target = if (key.isBlank()) this else (this as? JsonObject)?.get(key)
        return (target as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    private fun JsonElement?.runsText(): String {
        val target = this ?: return ""
        if (target is JsonPrimitive) return target.contentOrNull.orEmpty()
        val obj = target as? JsonObject ?: return ""
        obj["simpleText"]?.jsonPrimitive?.contentOrNull?.let { return it }
        val runs = obj["runs"] as? JsonArray ?: return ""
        return runs.joinToString("") { run ->
            (run as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    private fun JsonElement?.thumbnailUrl(): String {
        val thumbs = this?.collect("thumbnails")?.lastOrNull()?.jsonArray ?: return ""
        return thumbs.lastOrNull()?.jsonObject?.str("url").orEmpty()
    }

    private fun JsonElement.continuationToken(): String? =
        collect("continuationCommand").firstOrNull()
            ?.jsonObject?.str("token")?.takeIf { it.isNotBlank() }

    private fun JsonElement?.firstRunVideoId(): String {
        val runs = (this as? JsonArray)?.firstOrNull()
            ?.path("musicResponsiveListItemFlexColumnRenderer", "text", "runs") as? JsonArray
            ?: return ""
        return runs.firstNotNullOfOrNull { run ->
            run.path("navigationEndpoint", "watchEndpoint", "videoId").str().takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun durationOf(item: JsonObject): Int {
        val runs = mutableListOf<String>()
        fun walk(element: JsonElement?) {
            when (element) {
                is JsonObject -> {
                    if (element["text"] is JsonPrimitive) runs += element.str("text")
                    element.forEach { (_, value) -> walk(value) }
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(item["fixedColumns"])
        walk(item["flexColumns"])
        return runs.firstNotNullOfOrNull { text ->
            val match = CLOCK.find(text) ?: return@firstNotNullOfOrNull null
            val parts = match.groupValues[1].split(':').mapNotNull(String::toIntOrNull)
            when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> null
            }
        } ?: 0
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
        url.startsWith("/") -> OP_BASE + url
        else -> "$OP_BASE/$url"
    }

    private fun watchLink(id: String): String = "$MUSIC_ORIGIN/watch?v=$id"

    internal fun extractVideoId(link: String): String? {
        VIDEO_ID.find(link)?.groupValues?.get(1)?.let { return it }
        return link.trim().takeIf { VIDEO_ID_ONLY.matches(it) }
    }

    internal fun extractPlaylistId(link: String): String? {
        val list = PLAYLIST_ID.find(link)?.groupValues?.get(1)
        if (!list.isNullOrBlank()) return list
        return link.trim().removePrefix("VL").takeIf { PLAYLIST_ONLY.matches(it) }
    }

    private companion object {
        val VIDEO_ID = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})""")
        val VIDEO_ID_ONLY = Regex("""[A-Za-z0-9_-]{11}""")
        val PLAYLIST_ID = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
        val PLAYLIST_ONLY = Regex("""(?:PL|RD|OLAK5uy_|LM|WL)[A-Za-z0-9_-]*""")
        val CLOCK = Regex("""^(\d{1,2}:\d{2}(?::\d{2})?)$""")
        val HEADER_TYPE_WORDS = setOf("Playlist", "Album", "Single", "EP", "Song", "Auto playlist", "Mix")

        /** 服务端完成的音频按 24 小时缓存，本机提前一点过期，避免拿到旧 rev。 */
        const val PREPARED_TTL_MS = 20L * 60 * 60 * 1000
        const val POLL_INTERVAL_MS = 2_000L
        const val PREPARE_TIMEOUT_MS = 150_000L
        const val MAX_PLAYLIST_PAGES = 5

        /** WEB_REMIX 的公开 key，网页与 ytmusicapi 都在用；不是账号密钥。 */
        const val INNERTUBE_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    }
}
