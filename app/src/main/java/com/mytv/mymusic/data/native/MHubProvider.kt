package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
import java.util.UUID

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
    recommendedPlaylists = true,
    playlistSongs = true,
    playlistCategories = true,
    parsePlaylist = true,
    albumSongs = true,
    parseAlbum = true,
    userPlaylists = true,
    passwordLogin = true,
    playlistWrite = true,
    playlistReorder = true,
    hotSongs = true,
    note = "多平台兜底源；登录后同步并修改自己的歌单（新建请到网站），音频逐首准备",
)

class MHubProvider : BaseNativeProvider(MHUB_INFO) {
    private val json = NativeHttp.json

    private data class Prepared(val url: String, val ext: String, val bitrate: Int, val at: Long)

    private val prepared = mutableMapOf<String, Prepared>()

    /** 一次只准备一首，避免撞上服务端的下载限流。 */
    private val prepareGate = Mutex()

    private data class Platform(val id: String, val label: String)

    private data class CachedSearch(val at: Long, val songs: List<Song>)

    /** 同一个关键词 10 分钟内不重复打服务端；搜索页重进、换源重试都复用它。 */
    private val searchCache = mutableMapOf<String, CachedSearch>()

    /** 多平台搜索串行排队，避免同时发出多路搜索撞限流。 */
    private val searchGate = Mutex()

    @Volatile
    private var platformCache: Pair<Long, List<Platform>>? = null

    /**
     * 搜的是 MHub 上的所有平台，未登录时它就是这些平台的兜底音源：
     * 结果按平台分别标注，聚合时和原生平台的同名曲目归到一组，原生没登录也能播。
     */
    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        if (keyword.isBlank()) return emptyList()
        cachedSearch(keyword)?.let { return it }

        val platforms = platforms()
        val songs = mutableListOf<Song>()
        var rateLimited = false
        var lastError: Exception? = null
        val hit = searchGate.withLock {
            cachedSearch(keyword)?.let { return@withLock it }
            platforms.forEachIndexed { index, platform ->
                if (index > 0) delay(SEARCH_GAP_MS)
                try {
                    songs += searchPlatform(platform.id, keyword)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    lastError = error
                    if ("HTTP 429" in error.message.orEmpty()) rateLimited = true
                }
            }
            songs.ifEmpty { null }?.also { cacheSearch(keyword, it) }
        }
        if (hit != null) return hit
        if (rateLimited) throw IllegalStateException("music.op.wiki 搜索被限流，请稍后再试")
        lastError?.let { throw it }
        return emptyList()
    }

    private fun cachedSearch(keyword: String): List<Song>? =
        synchronized(searchCache) { searchCache[keyword] }
            ?.takeIf { System.currentTimeMillis() - it.at < SEARCH_TTL_MS }
            ?.songs

    private fun cacheSearch(keyword: String, songs: List<Song>) {
        synchronized(searchCache) {
            searchCache[keyword] = CachedSearch(System.currentTimeMillis(), songs)
        }
    }

    private suspend fun searchPlatform(platform: String, keyword: String): List<Song> {
        val body = NativeHttp.get(
            "$MHUB_BASE/api/v1/search?platform=$platform&q=${encode(keyword)}&limit=$SEARCH_LIMIT",
            headers = mapOf("Accept" to "application/json"),
        )
        val items = runCatching { json.parseToJsonElement(body) as JsonArray }.getOrElse {
            throw IllegalStateException("music.op.wiki 搜索返回异常：${body.take(160)}")
        }
        return items.mapNotNull { item ->
            (item as? JsonObject)?.let { searchItemSong(it, platform) }
        }
    }

    /** 平台列表以服务端实际启用的为准；拿不到就退回文档里最常见的几个。 */
    private suspend fun platforms(): List<Platform> {
        platformCache?.takeIf { System.currentTimeMillis() - it.first < PLATFORM_TTL_MS }?.let {
            return it.second
        }
        val body = runCatching {
            NativeHttp.get("$MHUB_BASE/api/v1/platforms", headers = mapOf("Accept" to "application/json"))
        }.getOrNull()
        val parsed = body?.let { text ->
            runCatching { json.parseToJsonElement(text) as JsonArray }.getOrNull()?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.str("id")
                id.takeIf { it.isNotBlank() }?.let { Platform(it, obj.str("label").ifBlank { it }) }
            }
        }.orEmpty()
        val result = parsed.ifEmpty { DEFAULT_PLATFORMS.map { Platform(it, it) } }
        platformCache = System.currentTimeMillis() to result
        return result
    }

    private fun songLink(platform: String, id: String): String = when (platform) {
        "applemusic" -> "https://music.apple.com/song/$id"
        "youtubemusic" -> "https://music.youtube.com/watch?v=$id"
        "netease" -> "https://music.163.com/song?id=$id"
        "tencent" -> "https://y.qq.com/n/ryqq/songDetail/$id"
        "bilibili" -> "https://www.bilibili.com/video/$id"
        else -> ""
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

    /** MHub 的推荐首页（目前只有 Apple Music）；卡片按 URL 再解析成歌曲。 */
    override suspend fun recommendedPlaylists(cookie: String): List<Playlist> {
        val body = NativeHttp.get(
            "$MHUB_BASE/api/v1/recommendations/home?platform=applemusic&limit=20",
            headers = mapOf("Accept" to "application/json"),
        )
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val items = root["playlists"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.str("url")
            val id = url.ifBlank { obj.str("recommendation_id") }
            if (id.isBlank()) return@mapNotNull null
            Playlist(
                id = RECOMMEND_PREFIX + id,
                name = obj.str("name"),
                cover = obj.str("cover"),
                creator = obj.str("curator"),
                description = obj.str("description"),
                source = MHUB_ID,
                link = url,
                extra = mapOf("platform" to "applemusic"),
            )
        }
    }

    /** 登录后把账号自己的歌单放进“歌单分类”，给用户一个能点进去、能绑定的入口。 */
    override suspend fun categories(cookie: String): List<PlaylistCategory> {
        if (cookie.isBlank()) return emptyList()
        return listOf(
            PlaylistCategory(id = MINE_CATEGORY, name = "我的歌单", group = "music.op.wiki", source = MHUB_ID),
        )
    }

    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        if (id != MINE_CATEGORY) return emptyList()
        return userPlaylists(cookie, page, limit)
    }

    override suspend fun hotSongs(cookie: String, limit: Int): List<Song> {
        val count = limit.coerceIn(1, 50)
        val body = NativeHttp.get(
            "$MHUB_BASE/api/v1/recommendations?platform=applemusic&limit=$count",
            headers = mapOf("Accept" to "application/json"),
        )
        val items = runCatching { json.parseToJsonElement(body) as JsonArray }.getOrNull() ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            searchItemSong(obj, obj.str("platform").ifBlank { "applemusic" })
        }
    }

    // ---------------- 账号 ----------------

    private val deviceId: String by lazy { UUID.randomUUID().toString() }

    override suspend fun passwordLogin(username: String, password: String): String {
        val body = buildJsonObject {
            put("username", username.trim())
            put("password", password)
            put("device_id", deviceId)
            put("device_name", "myMusic")
        }.toString()
        val response = runCatching {
            NativeHttp.postJson(
                "$MHUB_BASE/api/v1/auth/password/login",
                body,
                headers = clientHeaders(),
            )
        }.getOrElse { error ->
            val message = error.message.orEmpty()
            throw IllegalStateException(
                when {
                    "HTTP 401" in message -> "用户名或密码不正确"
                    "HTTP 429" in message -> "登录尝试过于频繁，请稍后再试"
                    else -> message.ifBlank { "登录失败" }
                },
            )
        }
        val token = response.cookies["mhub_session"].orEmpty()
        if (token.isBlank()) throw IllegalStateException("登录失败：服务端没有返回会话")
        return "mhub_session=$token"
    }

    override suspend fun accountInfo(cookie: String): AccountInfo? {
        if (cookie.isBlank()) return null
        val body = runCatching {
            NativeHttp.get("$MHUB_BASE/api/v1/auth/me", headers = clientHeaders(), cookie = cookie)
        }.getOrNull() ?: return null
        val user = runCatching {
            (json.parseToJsonElement(body).jsonObject["user"]) as? JsonObject
        }.getOrNull() ?: return null
        val name = user.str("username")
        if (name.isBlank()) return null
        return AccountInfo(nickname = name, detail = "music.op.wiki")
    }

    // ---------------- 自己的歌单（MHub 账号的 JSON 库） ----------------

    override suspend fun userPlaylists(cookie: String, page: Int, limit: Int): List<Playlist> {
        if (cookie.isBlank() || page > 1) return emptyList()
        val snapshot = librarySnapshot(cookie) ?: return emptyList()
        val items = snapshot["playlists"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id")
            if (id.isBlank()) return@mapNotNull null
            Playlist(
                id = id,
                name = obj.str("name"),
                cover = obj.str("cover"),
                trackCount = (obj["tracks"] as? JsonArray)?.size ?: 0,
                source = MHUB_ID,
                extra = mapOf("library" to "1"),
            )
        }
    }

    override suspend fun playlistWritable(cookie: String, id: String): Boolean? {
        if (cookie.isBlank()) return false
        val snapshot = librarySnapshot(cookie) ?: return null
        val items = snapshot["playlists"] as? JsonArray ?: return false
        return items.any { (it as? JsonObject)?.str("id") == id }
    }

    override suspend fun addPlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
        requireLogin(cookie)
        if (songs.isEmpty()) return
        val op = buildJsonObject {
            put("op", "append")
            put("id", playlistId)
            put("tracks", JsonArray(songs.map(::trackMetaJson)))
        }
        postLibraryOps(cookie, listOf(op))
    }

    override suspend fun removePlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
        requireLogin(cookie)
        if (songs.isEmpty()) return
        val ops = songs.map { song ->
            buildJsonObject {
                put("op", "remove")
                put("id", playlistId)
                put("key", trackKey(song))
            }
        }
        postLibraryOps(cookie, ops)
    }

    override suspend fun reorderPlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
        requireLogin(cookie)
        if (songs.size < 2) return
        var previous: String? = null
        val ops = songs.map { song ->
            val key = trackKey(song)
            buildJsonObject {
                put("op", "move")
                put("id", playlistId)
                put("key", key)
                if (previous == null) put("after", JsonNull) else put("after", previous)
            }.also { previous = key }
        }
        postLibraryOps(cookie, ops)
    }

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult =
        resolveCollection(link, album = false)

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> {
        if (id.startsWith(RECOMMEND_PREFIX)) {
            return resolveCollection(decodeCollectionId(id.removePrefix(RECOMMEND_PREFIX)), album = false).songs
        }
        libraryPlaylist(cookie, id)?.let { playlist ->
            return (playlist["tracks"] as? JsonArray).orEmpty().mapNotNull { element ->
                val track = element as? JsonObject ?: return@mapNotNull null
                val platform = track.str("platform").ifBlank { "applemusic" }
                val sourceId = track.str("source_id")
                if (sourceId.isBlank()) return@mapNotNull null
                Song(
                    id = sourceId,
                    name = track.str("title"),
                    artist = track.str("artist"),
                    source = MHUB_ID,
                    cover = track.str("cover"),
                    link = songLink(platform, sourceId),
                    ext = "m4a",
                    extra = mapOf("platform" to platform),
                )
            }
        }
        if (id.contains("::") || id.startsWith("http")) {
            return resolveCollection(decodeCollectionId(id), album = false).songs
        }
        throw IllegalStateException("找不到这个 music.op.wiki 歌单，登录状态可能已失效")
    }

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult =
        resolveCollection(link, album = true)

    override suspend fun albumSongs(cookie: String, id: String): List<Song> =
        resolveCollection(decodeCollectionId(id), album = true).songs

    private fun requireLogin(cookie: String) {
        if (cookie.isBlank()) {
            throw PlaylistWriteException("登录 music.op.wiki 后才能修改歌单", PlaylistWriteFailure.NotLoggedIn)
        }
    }

    private fun trackKey(song: Song): String =
        "${platformOf(song).lowercase()}:${song.id}"

    private fun trackMetaJson(song: Song): JsonObject = buildJsonObject {
        put("platform", platformOf(song))
        put("source_id", song.id)
        put("title", song.name)
        put("artist", song.artist)
        put("cover", song.cover)
    }

    private fun clientHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "x-mhub-client" to "mymusic",
    )

    private suspend fun librarySnapshot(cookie: String): JsonObject? = runCatching {
        val body = NativeHttp.get(
            "$MHUB_BASE/api/v1/sync/library/json",
            headers = clientHeaders(),
            cookie = cookie,
        )
        json.parseToJsonElement(body).jsonObject
    }.getOrNull()

    private suspend fun libraryPlaylist(cookie: String, id: String): JsonObject? {
        if (cookie.isBlank() || id.isBlank()) return null
        val snapshot = librarySnapshot(cookie) ?: return null
        val items = snapshot["playlists"] as? JsonArray ?: return null
        return items.firstOrNull { (it as? JsonObject)?.str("id") == id } as? JsonObject
    }

    private suspend fun postLibraryOps(cookie: String, ops: List<JsonObject>) {
        if (ops.isEmpty()) return
        ops.chunked(400).forEach { chunk ->
            val body = buildJsonObject { put("ops", JsonArray(chunk)) }.toString()
            val response = runCatching {
                NativeHttp.postJson(
                    "$MHUB_BASE/api/v1/sync/library/json",
                    body,
                    headers = clientHeaders(),
                    cookie = cookie,
                )
            }.getOrElse { error ->
                val message = error.message.orEmpty()
                throw PlaylistWriteException(
                    when {
                        "HTTP 401" in message || "HTTP 403" in message -> "登录状态已失效，请重新登录"
                        "HTTP 429" in message -> "操作过于频繁，请稍后再试"
                        else -> message.ifBlank { "修改歌单失败" }
                    },
                    if ("HTTP 401" in message || "HTTP 403" in message) {
                        PlaylistWriteFailure.NotLoggedIn
                    } else {
                        PlaylistWriteFailure.Other
                    },
                )
            }
            if (response.code !in 200..299) {
                throw PlaylistWriteException("修改歌单失败（HTTP ${response.code}）", PlaylistWriteFailure.Other)
            }
        }
    }

    private fun searchItemSong(obj: JsonObject, platform: String): Song? {
        val id = obj.str("id")
        if (id.isBlank()) return null
        return Song(
            id = id,
            name = obj.str("song"),
            artist = obj.str("singer"),
            source = MHUB_ID,
            cover = obj.str("cover"),
            link = songLink(platform, id),
            ext = "m4a",
            extra = mapOf("platform" to platform),
        )
    }

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
        if (APPLE_HOST.containsMatchIn(value) &&
            (value.contains("/playlist/") || value.contains("/album/"))
        ) {
            return "applemusic" to value
        }
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
        val APPLE_HOST = Regex("""https?://music\.apple\.com/""")
        val YT_ID = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})""")
        val NETEASE_HOST = Regex("""https?://(?:y\.)?music\.163\.com/""")

        const val PREPARED_TTL_MS = 20L * 60 * 60 * 1000
        const val POLL_INTERVAL_MS = 2_000L
        const val PREPARE_TIMEOUT_MS = 150_000L
        const val SEARCH_TTL_MS = 10L * 60 * 1000
        const val SEARCH_GAP_MS = 300L
        const val SEARCH_LIMIT = 10
        const val PLATFORM_TTL_MS = 6L * 60 * 60 * 1000
        const val RECOMMEND_PREFIX = "rec::"
        const val MINE_CATEGORY = "mine"
        val DEFAULT_PLATFORMS = listOf("applemusic", "netease", "tencent", "bilibili", "youtubemusic")
    }
}
