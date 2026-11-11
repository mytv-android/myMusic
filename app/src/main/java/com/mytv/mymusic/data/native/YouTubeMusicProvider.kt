package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
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
import java.security.MessageDigest

private const val YTM_ID = "youtubemusic"
private const val MUSIC_ORIGIN = "https://music.youtube.com"
private const val INNERTUBE = "$MUSIC_ORIGIN/youtubei/v1"
private const val PLAYER_ORIGIN = "https://youtubei.googleapis.com/youtubei/v1"
private const val CLIENT_NAME = "WEB_REMIX"
private const val CLIENT_VERSION = "1.20241106.01.00"
private const val CLIENT_ID = "67"
private const val WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * visionOS 客户端是 2026 年少数仍然直接返回 googlevideo 直链、不要求 poToken
 * 也不需要解 signatureCipher/n 参数的 YouTube 客户端。音频 itag 140 是 m4a，
 * 兼容所有目标设备。
 */
private const val VISIONOS_CLIENT_NAME = "VISIONOS"
private const val VISIONOS_CLIENT_VERSION = "1.04"
private const val VISIONOS_CLIENT_ID = "101"
private const val VISIONOS_USER_AGENT =
    "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US)"

/** ytmusicapi get_search_params() 的歌曲过滤参数（base64，JSON 里不用再做 URL 编码）。 */
private const val SEARCH_PARAMS_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="

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
    note = "直连取流；私人歌单和账号资料需要登录 Cookie",
)

class YouTubeMusicProvider : BaseNativeProvider(YTM_INFO) {
    private val json = NativeHttp.json

    private data class StreamInfo(val url: String, val ext: String, val bitrate: Int, val at: Long)

    /** 直链有效期 6 小时，本机只缓存 20 分钟，避免切回旧队列时拿到过期地址。 */
    private val streamCache = mutableMapOf<String, StreamInfo>()
    private val streamLock = Any()

    /** visionOS 播放请求必须带同一个 visitorData；从任意 InnerTube 响应里取一次即可。 */
    @Volatile
    private var visitorData: String = ""
    private val visitorLock = Mutex()

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        if (keyword.isBlank()) return emptyList()
        val root = innerTube(
            "search",
            buildJsonObject {
                put("query", keyword)
                put("params", SEARCH_PARAMS_SONGS)
            },
            cookie,
        )
        return parseSearchTracks(root)
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val id = extractVideoId(link) ?: throw IllegalArgumentException("不是 YouTube Music 歌曲链接：$link")
        val details = visionPlayerDetails(id)
        return Song(
            id = id,
            name = details.str("title"),
            artist = details.str("author").removeSuffix(" - Topic"),
            duration = details["lengthSeconds"]?.jsonPrimitive?.intOrNull ?: 0,
            source = YTM_ID,
            cover = details.thumbnailUrl(),
            link = watchLink(id),
            ext = "m4a",
        )
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String =
        resolveStream(cookie, song.id).url

    override suspend fun playableStream(cookie: String, song: Song, workDir: File): PlayableStream {
        val stream = resolveStream(cookie, song.id)
        return PlayableStream(
            url = stream.url,
            ext = stream.ext,
            bitrate = stream.bitrate,
        )
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.id.isBlank()) return ""
        val next = innerTube("next", buildJsonObject { put("videoId", song.id) }, cookie)
        val lyricsId = next.collect("browseId")
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it.startsWith("MPLYt") }
            ?: return ""
        val page = innerTube("browse", buildJsonObject { put("browseId", lyricsId) }, cookie)
        val description = page.collect("musicDescriptionShelfRenderer").firstOrNull()
            ?.path("description")
            ?: return ""
        return description.runsText()
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

    // ---------------- 直连取流 ----------------

    private suspend fun resolveStream(cookie: String, videoId: String): StreamInfo {
        if (videoId.isBlank()) throw IllegalArgumentException("YouTube Music 曲目 ID 为空")
        synchronized(streamLock) { streamCache[videoId] }?.takeIf {
            System.currentTimeMillis() - it.at < STREAM_TTL_MS
        }?.let { return it }

        val first = visionPlayer(videoId, refreshVisitor = false)
        val (status, player) = first
        val usable = if (status == "OK") player else {
            // visitorData 过期时 YouTube 会回 LOGIN_REQUIRED；换一份 visitor 再试一次。
            val retry = visionPlayer(videoId, refreshVisitor = true)
            if (retry.first != "OK") {
                throw IllegalStateException(
                    retry.second.playabilityReason().ifBlank { "YouTube 拒绝了这次取流，请登录后重试" },
                )
            }
            retry.second
        }
        val format = pickAudioFormat(usable)
            ?: throw IllegalStateException("这条视频没有可播放的音频流")
        val url = format.str("url")
        if (url.isBlank()) throw IllegalStateException("YouTube 没有返回音频直链")
        val mime = format.str("mimeType")
        val info = StreamInfo(
            url = url,
            ext = when {
                "mp4" in mime || "m4a" in mime -> "m4a"
                "webm" in mime -> "webm"
                else -> "m4a"
            },
            bitrate = (format["bitrate"]?.jsonPrimitive?.intOrNull ?: 0) / 1000,
            at = System.currentTimeMillis(),
        )
        synchronized(streamLock) { streamCache[videoId] = info }
        return info
    }

    /** 解析成元数据；复用同一次 visionOS 播放响应，不额外请求 player。 */
    private suspend fun visionPlayerDetails(videoId: String): JsonObject {
        val (status, player) = visionPlayer(videoId, refreshVisitor = false)
        if (status != "OK") {
            val retry = visionPlayer(videoId, refreshVisitor = true)
            if (retry.first != "OK") {
                throw IllegalStateException(
                    retry.second.playabilityReason().ifBlank { "YouTube 拒绝了这次请求" },
                )
            }
            return retry.second["videoDetails"] as? JsonObject
                ?: throw IllegalStateException("YouTube 没有返回视频信息")
        }
        return player["videoDetails"] as? JsonObject
            ?: throw IllegalStateException("YouTube 没有返回视频信息")
    }

    private suspend fun visionPlayer(videoId: String, refreshVisitor: Boolean): Pair<String, JsonObject> {
        val visitor = visitorData(refreshVisitor)
        val body = buildJsonObject {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", VISIONOS_CLIENT_NAME)
                    put("clientVersion", VISIONOS_CLIENT_VERSION)
                    put("clientScreen", "WATCH")
                    put("hl", "en")
                    put("gl", "US")
                    put("visitorData", visitor)
                })
                put("request", buildJsonObject {})
            })
        }.toString()
        val response = NativeHttp.postJson(
            "$PLAYER_ORIGIN/player?prettyPrint=false&id=$videoId",
            body,
            headers = mapOf(
                "X-Youtube-Client-Name" to VISIONOS_CLIENT_ID,
                "X-Youtube-Client-Version" to VISIONOS_CLIENT_VERSION,
                "X-Goog-Api-Format-Version" to "2",
                "Accept" to "application/json",
            ),
            userAgent = VISIONOS_USER_AGENT,
        )
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrElse {
            throw IllegalStateException("YouTube 播放接口返回异常：${response.body.take(160)}")
        }
        val status = root.path("playabilityStatus", "status").str()
        return status to root
    }

    /** 优先 m4a（itag 140）：目标设备都支持，WebM/Opus 只作兜底。 */
    private fun pickAudioFormat(player: JsonObject): JsonObject? {
        val formats = (player.path("streamingData") as? JsonObject)
            ?.get("adaptiveFormats") as? JsonArray ?: return null
        val audio = formats.mapNotNull { it as? JsonObject }.filter {
            it.str("mimeType").startsWith("audio/") && it.str("url").isNotBlank()
        }
        val order = listOf(140, 251, 139, 249, 250)
        return audio.firstOrNull { it["itag"]?.jsonPrimitive?.intOrNull == 140 }
            ?: audio.firstOrNull { it["itag"]?.jsonPrimitive?.intOrNull == 251 }
            ?: audio.firstOrNull { it["itag"]?.jsonPrimitive?.intOrNull in order }
            ?: audio.firstOrNull()
    }

    private suspend fun visitorData(refresh: Boolean): String {
        if (!refresh) {
            val cached = visitorData
            if (cached.isNotBlank()) return cached
        }
        return visitorLock.withLock {
            if (!refresh) {
                val cached = visitorData
                if (cached.isNotBlank()) return@withLock cached
            }
            val root = innerTube("browse", buildJsonObject { put("browseId", "FEmusic_home") }, "")
            val fresh = root.path("responseContext", "visitorData").str()
            if (fresh.isBlank()) throw IllegalStateException("拿不到 YouTube 的 visitorData，无法直连取流")
            visitorData = fresh
            fresh
        }
    }

    // ---------------- InnerTube 浏览 ----------------

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

    /** 歌单/榜单/专辑页：把 musicResponsiveListItemRenderer 转成曲目。 */
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
            out += Song(
                id = videoId,
                name = title,
                artist = column(1),
                album = column(2),
                duration = durationOf(item),
                source = YTM_ID,
                cover = item.thumbnailUrl(),
                link = watchLink(videoId),
                ext = "m4a",
            )
        }
        return out
    }

    /**
     * 搜索结果的两行结构：第一列歌名，第二列是「歌手 • 专辑 • 4:30」，
     * 第三列是播放次数。和歌单页的列含义不同，单独解析。
     */
    internal fun parseSearchTracks(root: JsonElement): List<Song> {
        val out = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        root.collect("musicResponsiveListItemRenderer").forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val videoId = item.path("playlistItemData", "videoId").str()
                .ifBlank { item.path("flexColumns").firstRunVideoId() }
            if (videoId.isBlank() || !seen.add(videoId)) return@forEach
            val columns = item["flexColumns"]?.jsonArray ?: JsonArray(emptyList())
            fun text(index: Int): String = columns.getOrNull(index)
                .path("musicResponsiveListItemFlexColumnRenderer", "text")
                .runsText()
            val title = text(0)
            if (title.isBlank()) return@forEach
            val segments = text(1).split("•").map(String::trim).filter { it.isNotBlank() }
            val artist = segments.firstOrNull().orEmpty()
            val album = segments.getOrNull(1)?.takeIf { CLOCK.find(it) == null } .orEmpty()
            val duration = segments.mapNotNull { segment ->
                val match = CLOCK.find(segment) ?: return@mapNotNull null
                clockSeconds(match.groupValues[1])
            }.firstOrNull() ?: 0
            out += Song(
                id = videoId,
                name = title,
                artist = artist,
                album = album,
                duration = duration,
                source = YTM_ID,
                cover = item.thumbnailUrl(),
                link = watchLink(videoId),
                ext = "m4a",
            )
        }
        return out
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
                    put("request", buildJsonObject {})
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
            userAgent = WEB_USER_AGENT,
        )
        return runCatching { json.parseToJsonElement(response.body) }.getOrElse {
            throw IllegalStateException("YouTube Music 接口返回异常：${response.body.take(160)}")
        }
    }

    /**
     * YouTube 的 Cookie 鉴权：用 SAPISID（或 __Secure-3PAPISID）对
     * `时间戳 空格 cookie 空格 origin` 做 SHA-1，放进 Authorization。
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
        val texts = mutableListOf<String>()
        fun walk(element: JsonElement?) {
            when (element) {
                is JsonObject -> {
                    if (element["text"] is JsonPrimitive) texts += element.str("text")
                    element.forEach { (_, value) -> walk(value) }
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(item["fixedColumns"])
        walk(item["flexColumns"])
        return texts.firstNotNullOfOrNull { text ->
            CLOCK.find(text)?.let { clockSeconds(it.groupValues[1]) }
        } ?: 0
    }

    private fun clockSeconds(clock: String): Int {
        val parts = clock.split(':').mapNotNull(String::toIntOrNull)
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private fun JsonObject.playabilityReason(): String =
        path("playabilityStatus", "reason").str().ifBlank {
            path("playabilityStatus", "errorScreen", "playerErrorMessageRenderer", "reason")
                .runsText()
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

        const val STREAM_TTL_MS = 20L * 60 * 1000
        const val MAX_PLAYLIST_PAGES = 5

        /** WEB_REMIX 的公开 key，网页与 ytmusicapi 都在用；不是账号密钥。 */
        const val INNERTUBE_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    }
}
