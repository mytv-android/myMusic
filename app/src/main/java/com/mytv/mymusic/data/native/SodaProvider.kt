package com.mytv.mymusic.data.native

import android.net.Uri
import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.ListeningMode
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.QRCheckResult
import com.mytv.mymusic.data.QRCreateResult
import com.mytv.mymusic.data.QRSession
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val SODA_INFO = ProviderInfo(
    id = "soda",
    name = "汽水音乐",
    search = true,
    download = true,
    lyrics = true,
    parseSong = true,
    searchPlaylist = true,
    recommendedPlaylists = false,
    playlistSongs = true,
    parsePlaylist = true,
    searchAlbum = true,
    searchArtist = true,
    albumSongs = true,
    parseAlbum = true,
    userPlaylists = true,
    qrLogin = true,
    playlistCategories = true,
    listeningModes = true,
    topLists = true,
    topListSongs = true,
    hotSongs = true,
)

class SodaProvider : BaseNativeProvider(SODA_INFO) {

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val body = fetchAndroidSearch(cookie, "track", keyword, 1, ANDROID_SEARCH_PAGE_SIZE)
        val resp = decodeSodaJson<SodaSearchResponse>(body, "soda search json parse error")
        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        resp.resultGroups.forEach { group ->
            group.data.forEach { item ->
                val track = item.entity.track
                if (track.id.isEmpty() || !seen.add(track.id)) return@forEach
                songs += buildSongFromTrack(track)
            }
        }
        return songs
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val trackId = runCatching { extractTrackId(cookie, link) }.getOrNull()
        if (trackId.isNullOrBlank()) throw IllegalArgumentException("invalid soda link")
        return fetchSongDetail(cookie, trackId)
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.source.isNotEmpty() && song.source != SODA_SOURCE) {
            throw IllegalArgumentException("source mismatch")
        }
        val trackId = songTrackId(song)
        val cached = cachedDownloadInfo(song)
        if (trackId.isNotEmpty()) {
            val info = try {
                resolveDownloadInfo(cookie, trackId, null)
            } catch (error: Exception) {
                if (cached != null && cached.url.isNotEmpty()) return downloadInfoUrl(cached)
                throw error
            }
            return downloadInfoUrl(info)
        }
        if (cached != null && cached.url.isNotEmpty()) return downloadInfoUrl(cached)
        throw IllegalStateException("track id is empty")
    }

    /**
     * 播放地址。加密流（带 play_auth）没法直接交给 ExoPlayer，先下载并按 CENC
     * 解密到缓存文件，返回本地 URI；明文流仍返回原始地址。
     */
    override suspend fun playableStream(cookie: String, song: Song, workDir: File): PlayableStream {
        if (song.source.isNotEmpty() && song.source != SODA_SOURCE) {
            throw IllegalArgumentException("source mismatch")
        }
        val trackId = songTrackId(song)
        if (trackId.isEmpty()) throw IllegalStateException("track id is empty")
        findCachedFile(trackId, workDir)?.let {
            return PlayableStream(Uri.fromFile(it).toString(), ext = it.extension.lowercase(), bitrate = song.bitrate)
        }
        val info = resolveDownloadInfo(cookie, trackId, null)
        if (info.url.trim().isEmpty()) throw IllegalStateException("player info url not found")
        val url = if (info.playAuth.trim().isEmpty()) downloadInfoUrl(info) else decryptToCache(trackId, info, workDir)
        return PlayableStream(
            url = url,
            ext = info.format.ifBlank { extensionFromUrl(url) }.ifBlank { song.ext },
            bitrate = info.bitrate.takeIf { it > 0 } ?: song.bitrate,
            level = info.quality,
        )
    }

    override suspend fun accountInfo(cookie: String): AccountInfo? {
        if (cookie.isBlank()) return null
        val me = runCatching { fetchPcMe(cookie) }.getOrNull() ?: return null
        val nickname = firstNonEmpty(me.myInfo.nickname, me.myInfo.publicName)
        if (nickname.isBlank()) return null
        return AccountInfo(
            nickname = nickname,
            avatar = buildImageUrl(me.myInfo.avatar, ""),
        )
    }

    // ---------------------------------------------------------------------
    // 扫码登录：隐藏 WebView 签名页（SodaQrSigner）+ qr_login.rs 状态机
    // ---------------------------------------------------------------------

    private val loginSessions = java.util.concurrent.ConcurrentHashMap<String, SodaLoginSession>()

    private class SodaLoginSession(
        val session: SodaQrSession,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var consecutiveErrors: Int = 0,
    )

    override suspend fun createLogin(type: String): QRCreateResult {
        val gateway: SodaQrGateway = SodaQrSigner
        gateway.close() // 新二维码 = 新签名页会话：设备身份、cookie、localStorage 全部重来
        pruneLoginSessions()
        val session = SodaQrSession()
        val created = try {
            SodaQrLogin.create(gateway, session)
        } catch (error: CancellationException) {
            gateway.close()
            throw error
        } catch (error: Exception) {
            gateway.close()
            throw IllegalStateException("汽水二维码获取失败：${error.message ?: "网络错误"}", error)
        }
        val sessionId = NativeProviders.newSessionId(info.id)
        loginSessions[sessionId] = SodaLoginSession(session)
        return QRCreateResult(
            sessionId = sessionId,
            session = QRSession(
                key = session.token,
                // 二维码内容必须是 qrcode_index_url 原样；服务端自带的图片可能编码改写地址，不用
                url = created.scanUrl,
                imageUrl = "",
            ),
        )
    }

    override suspend fun checkLogin(sessionId: String, key: String): QRCheckResult {
        val entry = loginSessions[sessionId]
            ?: return QRCheckResult(status = "failed", message = "登录会话已过期，请重新生成二维码", cookie = "")
        pruneLoginSessions()
        val gateway: SodaQrGateway = SodaQrSigner
        val result = try {
            SodaQrLogin.check(gateway, entry.session)
        } catch (error: CancellationException) {
            gateway.close()
            throw error
        } catch (error: Exception) {
            // 单次失败多半可重试；连着失败说明签名页出问题了，别空转到过期
            entry.consecutiveErrors += 1
            if (entry.consecutiveErrors >= 3) {
                return QRCheckResult(status = "failed", message = "汽水登录轮询失败：${error.message ?: "未知错误"}", cookie = "")
            }
            return QRCheckResult(status = "waiting", message = "汽水正在确认登录，请稍候…", cookie = "")
        }
        entry.consecutiveErrors = 0
        if (result.status == "success" || result.status == "expired" || result.status == "failed") {
            loginSessions.remove(sessionId)
            gateway.close()
        }
        return result
    }

    private fun pruneLoginSessions() {
        val now = System.currentTimeMillis()
        loginSessions.entries.removeIf { now - it.value.createdAt > LOGIN_SESSION_TTL_MS }
    }

    private fun findCachedFile(trackId: String, workDir: File): File? {
        val dir = File(workDir, SODA_CACHE_DIR)
        if (!dir.isDirectory) return null
        return dir.listFiles()?.firstOrNull {
            it.isFile &&
                it.length() > 0 &&
                it.name.startsWith("soda-$trackId-") &&
                !it.name.contains("-preview-")
        }?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != SODA_SOURCE) throw IllegalArgumentException("source mismatch")
        val extraTrackId = song.extra["track_id"].orEmpty()
        val trackId = if (extraTrackId.isNotEmpty()) extraTrackId else song.id
        val resp = try {
            fetchWebTrackV2(cookie, trackId)
        } catch (error: Exception) {
            throw IllegalStateException("failed to fetch lyric API: ${error.message}", error)
        }
        if (resp.lyric.content.isEmpty()) return ""
        return parseSodaLyric(resp.lyric.content)
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val body = fetchAndroidSearch(cookie, "playlist", keyword, 1, ANDROID_SEARCH_PAGE_SIZE)
        val resp = decodeSodaJson<SodaSearchResponse>(body, "soda playlist json parse error")
        val playlists = mutableListOf<Playlist>()
        resp.resultGroups.forEach { group ->
            group.data.forEach { item ->
                val playlist = item.entity.playlist
                if (playlist.id.isEmpty()) return@forEach
                val creator = playlist.owner.publicName.ifEmpty { playlist.owner.nickname }
                playlists += Playlist(
                    source = SODA_SOURCE,
                    id = playlist.id,
                    name = playlist.title,
                    cover = buildImageUrl(playlist.urlCover, "~c5_300x300.jpg"),
                    trackCount = playlist.countTracks,
                    creator = creator,
                    description = playlist.desc,
                    link = "https://www.qishui.com/playlist/${playlist.id}",
                )
            }
        }
        return playlists
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> =
        fetchPlaylistDetail(cookie, id).second

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val playlistId = runCatching { extractPlaylistId(cookie, link) }.getOrNull()
        if (playlistId.isNullOrBlank()) throw IllegalArgumentException("invalid soda playlist link")
        val (playlist, songs) = fetchPlaylistDetail(cookie, playlistId)
        return CollectionResult(playlist, songs)
    }

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val body = fetchAndroidSearch(cookie, "album", keyword, 1, ANDROID_SEARCH_PAGE_SIZE)
        val resp = decodeSodaJson<SodaSearchResponse>(body, "soda album search json parse error")
        val albums = mutableListOf<Playlist>()
        resp.resultGroups.forEach { group ->
            group.data.forEach { item ->
                val album = item.entity.album
                if (album.id.isEmpty()) return@forEach
                val extra = mutableMapOf("album_id" to album.id)
                if (album.releaseDate > 0) extra["release_date"] = album.releaseDate.toString()
                albums += Playlist(
                    source = SODA_SOURCE,
                    id = album.id,
                    name = album.name,
                    cover = buildImageUrl(album.urlCover, "~c5_300x300.jpg"),
                    trackCount = album.countTracks,
                    creator = joinArtists(album.artists),
                    description = album.company.trim(),
                    link = sodaAlbumLink(album.id),
                    extra = extra,
                )
            }
        }
        return albums
    }

    override suspend fun searchArtists(cookie: String, keyword: String): List<Playlist> {
        val body = fetchAndroidSearch(cookie, "artist", keyword, 1, ANDROID_SEARCH_PAGE_SIZE)
        val resp = decodeSodaJson<SodaSearchResponse>(body, "soda artist search json parse error")
        val artists = mutableListOf<Playlist>()
        resp.resultGroups.forEach { group ->
            group.data.forEach { item ->
                val artist = item.entity.artist
                if (artist.id.isEmpty()) return@forEach
                artists += Playlist(
                    source = SODA_SOURCE,
                    id = artist.id,
                    name = artist.name,
                    cover = buildImageUrl(artist.urlAvatar, ""),
                    trackCount = artist.countTracks,
                    creator = "",
                    description = "",
                    link = "https://www.qishui.com/artist/${artist.id}",
                    extra = mapOf("type" to "artist"),
                )
            }
        }
        return artists
    }

    override suspend fun albumSongs(cookie: String, id: String): List<Song> =
        fetchAlbumDetail(cookie, id).second

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult {
        val albumId = extractAlbumId(link)
        if (albumId.isEmpty()) throw IllegalArgumentException("invalid soda album link")
        val (album, songs) = fetchAlbumDetail(cookie, albumId)
        return CollectionResult(album, songs)
    }

    override suspend fun categories(cookie: String): List<PlaylistCategory> =
        SODA_CATEGORY_TAGS.map { tag ->
            PlaylistCategory(
                id = tag.id.toString(),
                name = tag.name,
                group = tag.group,
                source = SODA_SOURCE,
            )
        }

    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        val channel = categoryChannel(id)
        val safePage = page.coerceAtLeast(1)
        val safeLimit = if (limit < 1) CATEGORY_PLAYLIST_DEFAULT_LIMIT else limit
        val offset = (safePage - 1) * safeLimit
        val items = fetchCategoryPlaylistItems(cookie, channel, categoryPlaylistRounds(offset, safeLimit))
        if (offset >= items.size) return emptyList()
        val end = minOf(offset + safeLimit, items.size)
        return items.subList(offset, end).mapNotNull { item ->
            buildCategoryPlaylist(item, channel).takeIf { it.id.isNotEmpty() }
        }
    }

    override suspend fun userPlaylists(cookie: String, page: Int, limit: Int): List<Playlist> {
        if (cookie.trim().isEmpty()) throw IllegalStateException("soda user playlists require cookie")
        val safePage = page.coerceAtLeast(1)
        var safeLimit = limit
        if (safeLimit <= 0) safeLimit = 30
        if (safeLimit > 100) safeLimit = 100

        val me = fetchPcMe(cookie)
        val userId = me.myInfo.id.trim()
        if (userId.isEmpty()) throw IllegalStateException("soda user playlists require logged-in user id")

        val targetCount = safePage * safeLimit
        var requestCount = targetCount
        if (requestCount < 50) requestCount = 50
        if (requestCount > 100) requestCount = 100

        var cursor = ""
        val seenCursors = mutableSetOf<String>()
        val seenPlaylists = mutableSetOf<String>()
        val playlists = mutableListOf<Playlist>()
        var attempts = 0
        while (attempts < 20 && playlists.size < targetCount) {
            val resp = fetchUserPlaylistPage(cookie, userId, cursor, requestCount)
            resp.playlists.forEach { item ->
                val playlist = buildPlaylistFromUserItem(item, userId, me.myInfo.nickname)
                if (playlist.id.isEmpty() || !seenPlaylists.add(playlist.id)) return@forEach
                playlists += playlist
            }
            val nextCursor = resp.nextCursor.trim()
            if (nextCursor.isEmpty() || nextCursor == cursor || seenCursors.contains(nextCursor)) break
            if (!resp.hasMore && resp.playlists.size < requestCount) break
            seenCursors += nextCursor
            cursor = nextCursor
            attempts++
        }

        val start = (safePage - 1) * safeLimit
        if (start >= playlists.size) return emptyList()
        val end = minOf(start + safeLimit, playlists.size)
        return playlists.subList(start, end)
    }

    override suspend fun topLists(cookie: String): List<TopList> {
        val topLists = builtinTopLists().toMutableList()
        topLists += fetchRecommendTopLists(cookie)
        return topLists
    }

    override suspend fun topListSongs(cookie: String, id: String): List<Song> =
        fetchTopListSongs(cookie, id, 0)

    override suspend fun hotSongs(cookie: String, limit: Int): List<Song> =
        fetchTopListSongs(cookie, HOT_SONG_TOP_LIST_ID, normalizeHotSongLimit(limit))

    /**
     * 听歌模式：`feed_mode_block` 是一组场景（官方 PC 端 45 个），
     * `feed_mode_bar` 里是「推荐」这类偏好模式。两部分拼成一个列表，用 group 区分。
     */
    override suspend fun listeningModes(cookie: String): List<ListeningMode> {
        val body = NativeHttp.postJson(
            "$FEED_MODE_API?${buildQuery(pcAppParams())}",
            "{}",
            headers = pcHeaders(),
            cookie = cookie,
            userAgent = PC_APP_USER_AGENT,
        ).body
        val resp = decodeSodaJson<SodaFeedModeResponse>(body, "soda feed mode json parse error")
        if (resp.statusCode != 0) {
            val message = resp.statusInfo.statusMsg.trim()
            throw IllegalStateException(
                if (message.isBlank()) "汽水听歌模式读取失败" else "汽水听歌模式读取失败：$message",
            )
        }
        val modes = mutableListOf<ListeningMode>()
        val seen = mutableSetOf<String>()
        // 偏好模式（推荐）排在最前，和客户端的模式条一致
        resp.feedModeBar.feedMode.forEach { entry ->
            if (entry.type != "preference_mode") return@forEach
            val preference = entry.entity.preferenceMode.mode.trim()
            if (preference.isEmpty() || !seen.add("pref:$preference")) return@forEach
            modes += ListeningMode(
                id = "pref:$preference",
                name = entry.text.trim().ifBlank { "推荐" },
                subtitle = "按你的口味推荐",
                cover = modeCover(entry.urlInfo),
                source = info.id,
                group = "推荐",
                extra = mapOf("preference_mode" to preference),
            )
        }
        resp.feedModeBlock.forEach { block ->
            val groupName = block.title.trim().ifBlank { "场景音乐" }
            block.feedMode.forEach { entry ->
                val scene = entry.entity.sceneMode
                val name = entry.text.trim()
                if (scene.sceneModeId < 0 || name.isEmpty()) return@forEach
                val key = "scene:${scene.sceneModeId}"
                if (!seen.add(key)) return@forEach
                modes += ListeningMode(
                    id = key,
                    name = name,
                    cover = modeCover(entry.urlInfo),
                    source = info.id,
                    group = groupName,
                    extra = buildMap {
                        put("scene_mode_id", scene.sceneModeId.toString())
                        if (scene.subQueueType.isNotBlank()) put("sub_queue_type", scene.subQueueType)
                    },
                )
            }
        }
        if (modes.isEmpty()) throw IllegalStateException("汽水暂时没有可用的听歌模式")
        return modes
    }

    /** 模式图标：没有 uri 的条目（偏好模式）只有图片域名，不能当封面用。 */
    private fun modeCover(image: SodaImage): String =
        if (image.uri.trim().isEmpty()) "" else buildImageUrl(image, "")

    /** 场景队列：`feed/song-tab` 带 `feed_preference`，服务端每次给一小批。 */
    override suspend fun listeningModeSongs(cookie: String, mode: ListeningMode): List<Song> {
        val sceneId = mode.extra["scene_mode_id"]?.trim()?.toLongOrNull()
        val preference = mode.extra["preference_mode"]?.trim().orEmpty()
        if (sceneId == null && preference.isEmpty()) {
            throw IllegalArgumentException("invalid soda listening mode: ${mode.id}")
        }
        val payload = buildString {
            append("{")
            append("\"played_media\":[],")
            append("\"is_first_request\":true,")
            append("\"is_did_first_request\":true,")
            append("\"feed_counts\":{\"mix_session_count\":1},")
            append("\"feed_preference\":")
            if (sceneId != null) {
                append("{\"scene_mode_id\":")
                append(sceneId)
                append("}")
            } else {
                append("{\"preference_mode\":")
                append(JsonPrimitive(preference).toString())
                append("}")
            }
            append(",\"count\":")
            append(SODA_FEED_BATCH)
            append("}")
        }
        val body = NativeHttp.postJson(
            "$FEED_SONG_TAB_API?${buildQuery(pcAppParams())}",
            payload,
            headers = pcHeaders(),
            cookie = cookie,
            userAgent = PC_APP_USER_AGENT,
        ).body
        val resp = decodeSodaJson<SodaFeedTabResponse>(body, "soda feed song tab json parse error")
        val songs = resp.items
            .filter { it.type == "track" }
            .map { buildSongFromTrack(it.entity.trackWrapper.track) }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        if (songs.isEmpty()) throw IllegalStateException("「${mode.name}」暂时没有歌曲")
        return songs
    }

    // ---------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------

    private suspend fun fetchAndroidSearch(
        cookie: String,
        searchType: String,
        keyword: String,
        page: Int,
        pageSize: Int,
    ): String {
        val headers = mapOf(
            "User-Agent" to ANDROID_SEARCH_USER_AGENT,
            "content-type" to "application/json; charset=UTF-8",
        )
        return rawGet(androidSearchUrl(searchType, keyword, page, pageSize), headers, cookie).body
    }

    private fun androidSearchUrl(searchType: String, keyword: String, page: Int, pageSize: Int): String {
        val safePage = if (page < 1) 1 else page
        val safePageSize = if (pageSize <= 0) ANDROID_SEARCH_PAGE_SIZE else pageSize
        val params = linkedMapOf(
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "cdid" to "46556f98-1720-4248-83da-62b74b60b46a",
            "channel" to "xiaomi_8478_64",
            "aid" to "8478",
            "app_name" to "luna",
            "version_code" to "100198030",
            "version_name" to "19.8.0",
            "manifest_version_code" to "100198030",
            "update_version_code" to "100198030",
            "resolution" to "1080*1920",
            "dpi" to "480",
            "device_type" to "ABR-AL80",
            "device_brand" to "HUAWEI",
            "language" to "zh",
            "os_api" to "35",
            "os_version" to "15",
            "ac" to "wifi",
            "device_model" to "ABR-AL80",
            "save_power" to "0",
            "font_size" to "1.00",
            "luna_first_launch_apk_type" to "normal_apk",
            "diversion_channel_name" to "xiaomi_8478_64",
            "is_car_play" to "0",
            "battery" to "0.99",
            "network_speed" to "10156",
            "hybrid_version_code" to "100198030",
            "tz_name" to "Asia/Shanghai",
            "tz_offset" to "28800",
            "luna_register_time" to "1784311292",
            "diversion_category_level_two" to "Xiaomi%E5%95%86%E5%BA%97-%E8%87%AA%E7%84%B6",
            "package" to "com.luna.music",
            "charge" to "0",
            "luna_apk_type" to "normal_apk",
            "output_device_type" to "Phone",
            "volume" to "1.00",
            "brightness" to "0.08",
            "need_personal_recommend" to "1",
            "is_teen_mode" to "0",
            "sim_region" to "cn",
            "diversion_category_level_one" to "%E5%8E%82%E5%95%86%E5%95%86%E5%BA%97-%E8%87%AA%E7%84%B6",
            "android_device_type" to "default",
            "iid" to "2204957404569386",
            "device_id" to "2204957404565290",
            "_rticket" to System.currentTimeMillis().toString(),
        )
        params["q"] = keyword
        params["cursor"] = ((safePage - 1) * safePageSize).toString()
        params["count"] = safePageSize.toString()
        params["aid"] = "386088"
        return "$ANDROID_API_BASE/search/$searchType?${buildQuery(params)}"
    }

    private fun pcAppParams(): MutableMap<String, String> {
        val now = System.currentTimeMillis()
        val deviceId = now.toString()
        val iid = (now + 1).toString()
        return mutableMapOf(
            "aid" to "386088",
            "app_name" to "luna_pc",
            "region" to "cn",
            "geo_region" to "cn",
            "os_region" to "cn",
            "sim_region" to "",
            "device_id" to deviceId,
            "cdid" to "",
            "iid" to iid,
            "version_name" to "3.3.0",
            "version_code" to "30030000",
            "channel" to "official",
            "build_mode" to "master",
            "network_carrier" to "",
            "ac" to "wifi",
            "tz_name" to "Asia/Shanghai",
            "resolution" to "",
            "device_platform" to "windows",
            "device_type" to "Windows",
            "os_version" to "Windows 11",
            "fp" to deviceId,
        )
    }

    private fun pcHeaders(): Map<String, String> = mapOf(
        "x-luna-background-type" to "foreground",
        "x-luna-is-background-req" to "0",
        "x-luna-is-local-user" to "1",
    )

    private suspend fun rawGet(
        url: String,
        headers: Map<String, String>,
        cookie: String,
    ): RawGetResponse = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> builder.header(key, value) }
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        val call = SODA_RAW_CLIENT.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                    if (it.code != 200) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IOException("http request failed: status ${it.code}"))
                        }
                        return
                    }
                    if (continuation.isActive) {
                        continuation.resume(RawGetResponse(it.request.url.toString(), body))
                    }
                }
            }
        })
    }

    // ---------------------------------------------------------------------
    // Song detail / stream resolution
    // ---------------------------------------------------------------------

    private suspend fun fetchSongDetail(cookie: String, trackId: String): Song {
        val resp = fetchWebTrackV2(cookie, trackId)
        val track = resp.primaryTrack()
        if (track.id.isEmpty()) throw IllegalStateException("track info not found")
        var song = buildSongFromTrack(track)
        runCatching { resolveDownloadInfo(cookie, track.id, resp) }.getOrNull()?.let { info ->
            song = applyDownloadInfo(song, info)
        }
        return song
    }

    private suspend fun fetchWebTrackV2(cookie: String, trackId: String): SodaTrackV2Response {
        val body = try {
            NativeHttp.get(webTrackV2Url(trackId), cookie = cookie, userAgent = SODA_USER_AGENT)
        } catch (error: Exception) {
            return try {
                fetchSeoTrackData(cookie, trackId)
            } catch (seoError: Exception) {
                throw IllegalStateException(
                    "soda track_v2 failed: ${error.message} (seo fallback: ${seoError.message})",
                    error,
                )
            }
        }
        return try {
            parseTrackV2Response(body)
        } catch (parseError: Exception) {
            try {
                fetchSeoTrackData(cookie, trackId)
            } catch (seoError: Exception) {
                throw IllegalStateException(
                    "soda track_v2 parse failed: ${parseError.message} (seo fallback: ${seoError.message})",
                    parseError,
                )
            }
        }
    }

    private fun parseTrackV2Response(body: String): SodaTrackV2Response {
        val resp = decodeSodaJson<SodaTrackV2Response>(body, "soda track_v2 json parse error")
        if (resp.statusCode != 0) {
            val message = resp.statusInfo.statusMsg.trim().ifEmpty { "unknown error" }
            throw IllegalStateException(
                "soda track_v2 api error: status_code=${resp.statusCode} status_msg=$message"
            )
        }
        return resp
    }

    private suspend fun fetchSeoTrackData(cookie: String, trackId: String): SodaTrackV2Response {
        val body = NativeHttp.get(seoTrackUrl(trackId), cookie = cookie, userAgent = SODA_USER_AGENT)
        val seo = decodeSodaJson<SodaSeoTrackResponse>(body, "soda seo_track json parse error")
        if (seo.statusCode != 0) {
            val message = seo.statusInfo.statusMsg.trim().ifEmpty { "unknown error" }
            throw IllegalStateException(
                "soda seo_track api error: status_code=${seo.statusCode} status_msg=$message"
            )
        }
        val seoTrack = seo.seoTrack ?: SodaSeoTrack()
        val track = seoTrack.track
        val seoLyric = seoTrack.lyric.content
        val resp = SodaTrackV2Response(
            statusCode = 0,
            track = track,
            trackInfo = track,
            trackPlayer = seo.trackPlayer ?: SodaTrackPlayer(),
            lyric = SodaLyric(
                content = if (seoLyric.trim().isNotEmpty()) seoLyric else seo.lyric?.content.orEmpty()
            ),
        )
        if (resp.track.id.trim().isEmpty()) throw IllegalStateException("soda seo_track missing track id")
        return resp
    }

    private suspend fun resolveDownloadInfo(
        cookie: String,
        trackId: String,
        webResp: SodaTrackV2Response?,
    ): DownloadInfo {
        val id = trackId.trim()
        if (id.isEmpty()) throw IllegalArgumentException("track id is empty")
        val response = webResp ?: fetchWebTrackV2(cookie, id)
        val track = response.primaryTrack()
        var fullDuration = trackDurationSeconds(track)
        var isVipTrack = track.labelInfo.isVip()

        var lastError: Exception? = null
        var webInfo = bestFromVideoModel(response.trackPlayer.videoModel)
        if ((webInfo == null || downloadInfoIsPreview(webInfo, fullDuration)) &&
            response.trackPlayer.urlPlayerInfo.isNotEmpty()
        ) {
            val previous = webInfo
            try {
                webInfo = fetchPlayerInfo(cookie, response.trackPlayer.urlPlayerInfo)
            } catch (error: Exception) {
                lastError = error
                // Keep an already-obtained preview stream so member tracks remain playable.
                webInfo = previous
            }
        }
        val webIsPreview = downloadInfoIsPreview(webInfo, fullDuration)
        val webQuality = webInfo?.let { qualityRank(it.quality, it.format, it.bitrate) } ?: 0

        // 整曲流走 App 端点（POST /luna/pc/track_v2），逐请求签名头 x-helios/x-medusa
        // 由外部签名器生成；默认连 SodaM 的公共签名服务，关闭签名器时保持只走公开 web/SEO 端点。
        if (isSignerConfigured() && cookie.isNotBlank()) {
            val skipPc = webInfo != null && !isVipTrack && !webIsPreview && webQuality >= 60
            val shouldTryPc = !skipPc && (isVipTrack || webIsPreview || webQuality < 100)
            if (shouldTryPc) {
                try {
                    val pcResp = fetchPcTrackV2Signed(cookie, id)
                    val pcTrack = pcResp.primaryTrack()
                    if (fullDuration == 0) fullDuration = trackDurationSeconds(pcTrack)
                    if (pcTrack.labelInfo.isVip()) isVipTrack = true

                    val pcVideo = bestFromVideoModel(pcResp.trackPlayer.videoModel)
                    if (pcVideo != null && !downloadInfoIsPreview(pcVideo, fullDuration)) {
                        return pcVideo.copy(preview = false)
                    }
                    if (pcResp.trackPlayer.urlPlayerInfo.isNotEmpty()) {
                        val pcInfo = fetchPlayerInfo(cookie, pcResp.trackPlayer.urlPlayerInfo)
                        if (!downloadInfoIsPreview(pcInfo, fullDuration)) {
                            return pcInfo.copy(preview = false)
                        }
                        lastError = IllegalStateException("soda pc track_v2 returned preview stream")
                    } else {
                        lastError = IllegalStateException("soda pc track_v2 missing player info url")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    lastError = error
                }
            }
        }

        if (webInfo != null && webInfo.url.isNotEmpty()) {
            return webInfo.copy(preview = webIsPreview)
        }

        if (isVipTrack) {
            val reason = lastError?.message ?: "未获取到公开播放地址"
            throw IllegalStateException("$VIP_SIGNER_REQUIRED_MESSAGE：$reason")
        }
        if (lastError != null) throw lastError
        throw IllegalStateException("player info url not found")
    }

    private suspend fun fetchPlayerInfo(cookie: String, playerInfoUrl: String): DownloadInfo {
        val body = NativeHttp.get(playerInfoUrl, cookie = cookie, userAgent = SODA_USER_AGENT)
        val resp = decodeSodaJson<SodaPlayerInfoResponse>(body, "parse play info response error")
        val list = resp.result.data.playInfoList
        if (list.isEmpty()) {
            val message = resp.responseMetadata.error.message
            if (message.isNotEmpty()) throw IllegalStateException(message)
            throw IllegalStateException("no audio stream found")
        }
        val best = bestPlayerInfo(list) ?: throw IllegalStateException("invalid download url")
        var downloadUrl = best.mainPlayUrl
        if (downloadUrl.isEmpty()) downloadUrl = best.backupPlayUrl
        if (downloadUrl.isEmpty()) throw IllegalStateException("invalid download url")
        return DownloadInfo(
            url = downloadUrl,
            playAuth = best.playAuth,
            format = best.format,
            size = best.size,
            duration = best.duration,
            bitrate = best.bitrate,
            quality = best.quality,
        )
    }

    // ---------------------------------------------------------------------
    // 签名器：协议与 sodam / libresoda / libmssdk 的 POST /sign 一致，
    // 默认使用 SodaM 的公共签名服务（见 DEFAULT_SIGNER_URL）
    // ---------------------------------------------------------------------

    /**
     * 请求签名服务换 x-helios / x-medusa。签名覆盖 URL + 全部请求头 + body 字节，
     * 所以传给签名器的必须是即将原样发出的那一份。
     */
    private suspend fun signRequest(
        requestUrl: String,
        body: String,
        headers: Map<String, String>,
    ): SignedRequest {
        val endpoint = effectiveSignerUrl()
        if (endpoint.isEmpty()) throw IllegalStateException(VIP_SIGNER_REQUIRED_MESSAGE)
        val payload = buildJsonObject {
            put("url", requestUrl)
            put("method", "POST")
            put("body", body)
            put("ts_ms", System.currentTimeMillis())
            putJsonObject("headers") {
                headers.forEach { (key, value) -> put(key, value) }
            }
        }.toString()
        val signerHeaders = linkedMapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "application/json",
        )
        val token = effectiveSignerToken()
        if (token.isNotBlank()) signerHeaders["Authorization"] = "Bearer $token"
        val response = try {
            NativeHttp.postJson(endpoint, payload, signerHeaders, userAgent = SODA_USER_AGENT)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("汽水签名器请求失败：${error.message}", error)
        }
        return parseSignerResponse(response.body, requestUrl)
    }

    private fun parseSignerResponse(raw: String, requestUrl: String): SignedRequest {
        val root = runCatching { SODA_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw IllegalStateException("汽水签名器返回的不是 JSON")
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull
        if (ok == false) {
            var message = jsonString(root, "error").ifEmpty { "unknown error" }
            if (message.contains("unauthorized", ignoreCase = true)) {
                message += "（签名服务要求鉴权：在设置里填写签名器 Token）"
            }
            throw IllegalStateException("汽水签名器错误：$message")
        }

        val headers = linkedMapOf<String, String>()
        (root["headers"] as? JsonObject)?.forEach { (key, element) ->
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (key.isNotBlank() && text.isNotEmpty()) headers[key] = text
        }
        listOf("X-Helios", "x-helios", "X_Helios").forEach { alias ->
            jsonString(root, alias).takeIf { it.isNotEmpty() }?.let { headers["x-helios"] = it }
        }
        listOf("X-Medusa", "x-medusa", "X_Medusa").forEach { alias ->
            jsonString(root, alias).takeIf { it.isNotEmpty() }?.let { headers["x-medusa"] = it }
        }
        val msToken = jsonString(root, "ms_token")
        val aBogus = jsonString(root, "a_bogus")
        if (headers.isEmpty() && msToken.isEmpty() && aBogus.isEmpty()) {
            throw IllegalStateException("汽水签名器没有返回签名头")
        }
        return SignedRequest(appendSignerQuery(requestUrl, msToken, aBogus), headers)
    }

    private fun appendSignerQuery(url: String, msToken: String, aBogus: String): String {
        if (msToken.isEmpty() && aBogus.isEmpty()) return url
        val builder = url.toHttpUrlOrNull()?.newBuilder() ?: return url
        if (msToken.isNotEmpty()) builder.setQueryParameter("msToken", msToken)
        if (aBogus.isNotEmpty()) builder.setQueryParameter("a_bogus", aBogus)
        return builder.build().toString()
    }

    /** 签名器 /config：设备指纹必须与签名侧一致，整曲签名才会被服务端接受。 */
    private suspend fun ensureSignerCredentials(): SodaAppCredentials {
        signerCredentials?.let { return it }
        var deviceId = ""
        var iid = ""
        var fp = ""
        try {
            val headers = linkedMapOf("Accept" to "application/json")
            val token = effectiveSignerToken()
            if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"
            val body = NativeHttp.get(signerConfigUrl(), headers, userAgent = SODA_USER_AGENT)
            val root = SODA_JSON.parseToJsonElement(body) as? JsonObject
            if (root != null) {
                val identity = root["identity"] as? JsonObject
                val identityDevice = identity?.let { jsonString(it, "device_id") }.orEmpty()
                val topDevice = jsonString(root, "device_id")
                deviceId = identityDevice.ifEmpty { if (topDevice.contains("*")) "" else topDevice }
                iid = identity?.let { jsonString(it, "iid") }.orEmpty()
                fp = identity?.let { jsonString(it, "fp") }.orEmpty()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // /config 不是所有签名器都提供；缺设备身份时用本地生成值
        }
        if (deviceId.isEmpty()) {
            val now = System.currentTimeMillis()
            deviceId = now.toString()
            iid = (now + 1).toString()
            fp = deviceId
        }
        if (iid.isEmpty()) iid = (System.currentTimeMillis() + 1).toString()
        if (fp.isEmpty()) fp = deviceId
        return SodaAppCredentials(deviceId = deviceId, iid = iid, fp = fp).also {
            signerCredentials = it
        }
    }

    private fun signerConfigUrl(): String {
        var base = effectiveSignerUrl()
        for (suffix in listOf("/sign", "/sign/")) {
            if (base.endsWith(suffix)) {
                base = base.dropLast(suffix.length)
                break
            }
        }
        return base.trimEnd('/') + "/config"
    }

    private fun pcTrackV2Url(creds: SodaAppCredentials): String {
        val params = pcAppParams()
        params["device_id"] = creds.deviceId
        params["iid"] = creds.iid
        params["fp"] = creds.fp
        return "https://api.qishui.com/luna/pc/track_v2?${buildQuery(params)}"
    }

    private suspend fun fetchPcTrackV2Signed(cookie: String, trackId: String): SodaTrackV2Response {
        if (cookie.isBlank()) throw IllegalStateException("soda pc track_v2 requires cookie")
        val creds = ensureSignerCredentials()
        val body = buildString {
            append("{\"track_id\":")
            append(JsonPrimitive(trackId.trim()).toString())
            append(",\"media_type\":\"track\",\"queue_type\":\"favorite_track_playlist\",\"scene_name\":\"library\"}")
        }
        val requestUrl = pcTrackV2Url(creds)
        val headers = linkedMapOf(
            "User-Agent" to creds.userAgentOrDefault(),
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "application/json",
            "X-SS-STUB" to NativeCrypto.md5Hex(body).uppercase(Locale.ROOT),
            "x-luna-background-type" to "foreground",
            "x-luna-is-background-req" to "0",
            "x-luna-is-local-user" to "0",
            "Cookie" to cookie,
        )
        val signed = signRequest(requestUrl, body, headers)
        signed.headers.forEach { (key, value) -> headers[key] = value }
        val response = NativeHttp.postJson(
            signed.url,
            body,
            headers,
            cookie = cookie,
            userAgent = creds.userAgentOrDefault(),
        )
        if (response.body.isBlank()) {
            // 设备身份可能已变化（签名器重启），下次重新对齐 /config
            signerCredentials = null
            throw IllegalStateException(
                "soda pc track_v2 返回空响应：签名头未被接受，请检查签名器、设备指纹与 Cookie"
            )
        }
        return parseTrackV2Response(response.body)
    }

    // ---------------------------------------------------------------------
    // 加密流：下载后本地解密，播放器拿到的就是明文文件
    // ---------------------------------------------------------------------

    private suspend fun decryptToCache(trackId: String, info: DownloadInfo, workDir: File): String {
        val dir = File(workDir, SODA_CACHE_DIR).apply { mkdirs() }
        val format = info.format.trim().lowercase(Locale.ROOT)
        val extension = format.filter { it.isLetterOrDigit() }.ifEmpty { "m4a" }
        val marker = if (info.preview) "preview-" else ""
        val name = "soda-$trackId-$marker${info.size}-${info.bitrate}.$extension"
        val file = File(dir, name)
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return Uri.fromFile(file).toString()
        }

        val encoded = downloadBytes(info.url)
        if (encoded.isEmpty()) throw IllegalStateException("soda 音频下载为空")
        val plain = SodaDecrypt.decryptAudio(encoded, info.playAuth)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(plain)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("soda 缓存写入失败")
        }
        pruneCache(dir)
        return Uri.fromFile(file).toString()
    }

    private suspend fun downloadBytes(url: String): ByteArray = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).get().header("User-Agent", SODA_USER_AGENT).build()
        val call = SODA_STREAM_CLIENT.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException("soda audio download failed: HTTP ${it.code}")
                            )
                        }
                        return
                    }
                    val bytes = runCatching { it.body?.bytes() ?: ByteArray(0) }.getOrElse { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                        return
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                }
            }
        })
    }

    private fun pruneCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= SODA_CACHE_FILE_LIMIT) return
        files.sortedBy { it.lastModified() }
            .take(files.size - SODA_CACHE_FILE_LIMIT)
            .forEach { it.delete() }
    }

    private fun bestPlayerInfo(list: List<SodaPlayerInfo>): SodaPlayerInfo? {
        var best: SodaPlayerInfo? = null
        for (info in list) {
            if (info.mainPlayUrl.trim().isEmpty() && info.backupPlayUrl.trim().isEmpty()) continue
            val current = best
            if (current == null || betterStreamCandidate(
                    info.duration,
                    info.quality,
                    info.format,
                    info.bitrate,
                    info.size,
                    current.duration,
                    current.quality,
                    current.format,
                    current.bitrate,
                    current.size,
                )
            ) {
                best = info
            }
        }
        return best
    }

    private fun bestFromVideoModel(raw: JsonElement?): DownloadInfo? {
        if (raw == null || raw is JsonNull) return null
        var text = raw.toString().trim()
        if (text.isEmpty() || text == "null") return null
        var depth = 0
        while (depth < 3 && text.startsWith("\"")) {
            val nested = runCatching { SODA_JSON.parseToJsonElement(text) }.getOrNull()
            if (nested !is JsonPrimitive || !nested.isString) break
            text = nested.content.trim()
            depth++
        }
        val value = runCatching { SODA_JSON.parseToJsonElement(text) }.getOrNull() ?: return null
        val entries = mutableListOf<SodaVideoModelEntry>()
        collectVideoModelEntries(value, "", "", 0.0, entries)
        var best: SodaVideoModelEntry? = null
        for (entry in entries) {
            if (entry.mainPlayUrl.trim().isEmpty() && entry.backupPlayUrl.trim().isEmpty()) continue
            val current = best
            if (current == null || betterStreamCandidate(
                    entry.duration,
                    entry.quality,
                    entry.format,
                    entry.bitrate,
                    entry.size,
                    current.duration,
                    current.quality,
                    current.format,
                    current.bitrate,
                    current.size,
                )
            ) {
                best = entry
            }
        }
        val chosen = best ?: return null
        var downloadUrl = chosen.mainPlayUrl.trim()
        if (downloadUrl.isEmpty()) downloadUrl = chosen.backupPlayUrl.trim()
        if (downloadUrl.isEmpty()) return null
        return DownloadInfo(
            url = downloadUrl,
            playAuth = chosen.playAuth.trim(),
            format = chosen.format.trim(),
            size = chosen.size,
            duration = chosen.duration,
            bitrate = chosen.bitrate,
            quality = chosen.quality.trim(),
        )
    }

    private fun collectVideoModelEntries(
        value: JsonElement,
        keyHint: String,
        inheritedAuth: String,
        inheritedDuration: Double,
        entries: MutableList<SodaVideoModelEntry>,
    ) {
        when (value) {
            is JsonObject -> {
                var auth = inheritedAuth.trim()
                val ownAuth = videoModelPlayAuth(value)
                if (ownAuth.isNotEmpty()) auth = ownAuth
                var duration = inheritedDuration
                val ownDuration = jsonFloat(value, "video_duration", "duration", "Duration")
                if (ownDuration > 0) duration = normalizeSodaDuration(ownDuration)
                videoModelEntryFromMap(value, keyHint, auth, duration)?.let { entries += it }
                value.forEach { (key, child) ->
                    collectVideoModelEntries(child, key, auth, duration, entries)
                }
            }
            is JsonArray -> value.forEach { child ->
                collectVideoModelEntries(child, keyHint, inheritedAuth, inheritedDuration, entries)
            }
            else -> Unit
        }
    }

    private fun videoModelEntryFromMap(
        values: JsonObject,
        keyHint: String,
        inheritedAuth: String,
        inheritedDuration: Double,
    ): SodaVideoModelEntry? {
        var entry = SodaVideoModelEntry(
            mainPlayUrl = jsonString(
                values,
                "main_play_url", "MainPlayUrl", "main_url", "MainUrl", "url", "URL", "play_url", "PlayURL",
            ),
            backupPlayUrl = jsonString(
                values,
                "backup_play_url", "BackupPlayUrl", "backup_url", "BackupUrl",
                "backup_url_1", "backup_url_2", "backup_url_3",
            ),
            playAuth = jsonString(values, "play_auth", "PlayAuth"),
            size = jsonLong(values, "size", "Size", "file_size", "FileSize", "data_size", "DataSize"),
            format = jsonString(values, "format", "Format", "vtype", "VType", "file_format", "FileFormat"),
            bitrate = jsonInt(values, "bitrate", "Bitrate", "br", "BR", "bit_rate", "BitRate"),
            quality = jsonString(values, "quality", "Quality", "definition", "Definition", "quality_type", "QualityType"),
            duration = jsonFloat(values, "duration", "Duration"),
        )
        val meta = values["video_meta"] as? JsonObject
        if (meta != null) {
            if (entry.size == 0L) {
                entry = entry.copy(size = jsonLong(meta, "size", "Size", "file_size", "FileSize"))
            }
            if (entry.format.isEmpty()) {
                entry = entry.copy(
                    format = jsonString(meta, "format", "Format", "vtype", "VType", "codec_type", "CodecType")
                )
            }
            if (entry.bitrate == 0) {
                entry = entry.copy(
                    bitrate = jsonInt(meta, "bitrate", "Bitrate", "real_bitrate", "RealBitrate", "bit_rate", "BitRate")
                )
            }
            if (entry.quality.isEmpty()) {
                entry = entry.copy(
                    quality = jsonString(meta, "quality", "Quality", "definition", "Definition", "quality_type", "QualityType")
                )
            }
            if (entry.duration == 0.0) {
                entry = entry.copy(duration = jsonFloat(meta, "duration", "Duration"))
            }
        }
        if (entry.backupPlayUrl.isEmpty()) {
            entry = entry.copy(
                backupPlayUrl = firstJsonString(values, "backup_urls", "backupUrls", "url_list", "UrlList")
            )
        }
        if (entry.playAuth.isEmpty()) entry = entry.copy(playAuth = videoModelPlayAuth(values))
        if (entry.playAuth.isEmpty()) entry = entry.copy(playAuth = inheritedAuth.trim())
        if (entry.quality.isEmpty()) {
            entry = entry.copy(quality = videoModelQualityHint(jsonString(values, "gear_des_key", "GearDesKey")))
        }
        if (entry.quality.isEmpty()) entry = entry.copy(quality = videoModelQualityHint(keyHint))
        if (entry.duration == 0.0) entry = entry.copy(duration = inheritedDuration)
        return entry.takeIf { it.mainPlayUrl.trim().isNotEmpty() || it.backupPlayUrl.trim().isNotEmpty() }
    }

    private fun videoModelPlayAuth(values: JsonObject): String {
        for (key in listOf("encrypt_info", "EncryptInfo", "encryptInfo")) {
            val child = values[key] as? JsonObject ?: continue
            val auth = jsonString(child, "spade_a", "SpadeA", "spadeA", "play_auth", "PlayAuth")
            if (auth.isNotEmpty()) return auth
        }
        return ""
    }

    private fun videoModelQualityHint(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return ""
        val normalized = trimmed.lowercase().replace("-", "").replace("_", "").replace(" ", "")
        for (token in listOf("hires", "lossless", "sq", "flac", "highest", "higher", "standard", "normal")) {
            if (normalized.contains(token)) return token
        }
        return ""
    }

    // ---------------------------------------------------------------------
    // Playlist details
    // ---------------------------------------------------------------------

    private suspend fun fetchPlaylistDetail(cookie: String, id: String): Pair<Playlist, List<Song>> =
        fetchPlaylistDetailPaged(cookie, id)

    private suspend fun fetchPlaylistDetailPaged(cookie: String, id: String): Pair<Playlist, List<Song>> {
        val playlistId = id.trim()
        if (playlistId.isEmpty()) throw IllegalArgumentException("playlist id is empty")

        val pageSize = 100
        var cursor = ""
        val seenCursors = mutableSetOf<String>()
        val seenTracks = mutableSetOf<String>()
        var playlist: Playlist? = null
        val songs = mutableListOf<Song>()

        for (page in 0 until 20) {
            val resp = try {
                fetchPlaylistDetailPage(cookie, playlistId, cursor, pageSize)
            } catch (error: Exception) {
                if (page == 0) return fetchPlaylistDetailWeb(cookie, playlistId)
                throw error
            }
            if (playlist == null) {
                var built = buildPlaylistFromUserItem(resp.playlist, "", "")
                if (built.id.isEmpty()) {
                    built = built.copy(
                        id = playlistId,
                        source = SODA_SOURCE,
                        link = "https://www.qishui.com/playlist/$playlistId",
                    )
                }
                playlist = built
            }
            resp.mediaResources.forEach { item ->
                if (item.type != "track") return@forEach
                val track = item.entity.trackWrapper.track
                if (track.id.isEmpty() || !seenTracks.add(track.id)) return@forEach
                var song = buildSongFromTrack(track)
                if (song.cover.isEmpty()) {
                    val playlistCover = playlist.cover
                    if (playlistCover.isNotEmpty()) song = song.copy(cover = playlistCover)
                }
                songs += song
            }
            val nextCursor = resp.nextCursor.trim()
            if (nextCursor.isEmpty() || nextCursor == cursor || seenCursors.contains(nextCursor)) break
            if (!resp.hasMore && resp.mediaResources.size < pageSize) break
            seenCursors += nextCursor
            cursor = nextCursor
        }

        val result = playlist ?: throw IllegalStateException("playlist not found")
        if (result.id.isEmpty()) throw IllegalStateException("playlist not found")
        val finalPlaylist = if (result.trackCount == 0) result.copy(trackCount = songs.size) else result
        return finalPlaylist to songs
    }

    private suspend fun fetchPlaylistDetailPage(
        cookie: String,
        playlistId: String,
        cursor: String,
        count: Int,
    ): SodaPlaylistDetailResponse {
        val body = NativeHttp.get(
            pcPlaylistDetailUrl(playlistId, cursor, count),
            pcHeaders(),
            cookie,
            userAgent = PC_APP_USER_AGENT,
        )
        val resp = decodeSodaJson<SodaPlaylistDetailResponse>(body, "soda playlist detail json error")
        if (resp.statusCode != 0) {
            val message = resp.statusInfo.statusMsg.trim().ifEmpty { "unknown error" }
            throw IllegalStateException(
                "soda playlist detail api error: status_code=${resp.statusCode} status_msg=$message"
            )
        }
        return resp
    }

    private fun pcPlaylistDetailUrl(playlistId: String, cursor: String, count: Int): String {
        val safeCount = if (count <= 0) 100 else count
        val params = pcAppParams()
        params["playlist_id"] = playlistId.trim()
        params["cursor"] = cursor.trim()
        params["count"] = safeCount.toString()
        return "https://api.qishui.com/luna/pc/playlist/detail?${buildQuery(params)}"
    }

    private suspend fun fetchPlaylistDetailWeb(cookie: String, id: String): Pair<Playlist, List<Song>> {
        val params = mapOf(
            "playlist_id" to id,
            "cursor" to "0",
            "cnt" to "20",
            "aid" to "386088",
            "device_platform" to "web",
            "channel" to "pc_web",
        )
        val body = NativeHttp.get(
            "https://api.qishui.com/luna/pc/playlist/detail?${buildQuery(params)}",
            cookie = cookie,
            userAgent = SODA_USER_AGENT,
        )
        val resp = decodeSodaJson<SodaWebPlaylistResponse>(body, "soda playlist detail json error")

        var playlist = Playlist(
            source = SODA_SOURCE,
            id = id,
            name = resp.playlist.title,
            creator = resp.playlist.owner.nickname,
            description = resp.playlist.desc,
            trackCount = resp.playlist.countTracks,
            link = "https://www.qishui.com/playlist/$id",
        )
        var playlistCover: String? = null
        val coverUrls = resp.playlist.urlCover.urls
        if (coverUrls.isNotEmpty()) {
            var cover = coverUrls[0]
            val uri = resp.playlist.urlCover.uri
            if (uri.isNotEmpty() && !cover.contains(uri)) cover += uri
            if (!cover.contains("~")) cover += "~c5_300x300.jpg"
            playlistCover = cover
        }
        playlistCover?.let { cover -> playlist = playlist.copy(cover = cover) }

        val songs = mutableListOf<Song>()
        resp.mediaResources.forEach { item ->
            if (item.type != "track") return@forEach
            val track = item.entity.trackWrapper.track
            if (track.id.isEmpty()) return@forEach

            var displaySize = 0L
            track.bitRates.forEach { bitRate ->
                if (bitRate.size > displaySize) displaySize = bitRate.size
            }
            track.audioInfo.playInfoList.forEach { info ->
                if (info.size > displaySize) displaySize = info.size
            }

            val artist = track.artists.joinToString("、") { it.name }

            var cover = ""
            val albumCoverUrls = track.album.urlCover.urls
            if (albumCoverUrls.isNotEmpty()) {
                val domain = albumCoverUrls[0]
                val uri = track.album.urlCover.uri
                cover = when {
                    domain.isNotEmpty() && uri.isNotEmpty() && !domain.contains(uri) ->
                        "$domain$uri~c5_375x375.jpg"
                    domain.isNotEmpty() -> "$domain~c5_375x375.jpg"
                    else -> ""
                }
            }

            val seconds = track.duration / 1000
            var bitrate = 0
            if (seconds > 0 && displaySize > 0) {
                bitrate = (displaySize * 8 / 1000 / seconds).toInt()
            }

            var url = ""
            var ext = ""
            var size = displaySize
            val playInfoList = track.audioInfo.playInfoList
            if (playInfoList.isNotEmpty()) {
                var best = playInfoList[0]
                for (info in playInfoList) {
                    if (info.size > best.size) best = info
                }
                if (best.mainPlayUrl.isNotEmpty()) {
                    url = best.mainPlayUrl + "#auth=" + queryEscape(best.playAuth)
                    if (size == 0L) size = best.size
                    ext = best.format
                    bitrate = normalizeSodaBitrate(best.bitrate)
                }
            }

            songs += Song(
                source = SODA_SOURCE,
                id = track.id,
                name = track.name,
                artist = artist,
                album = track.album.name,
                duration = track.duration / 1000,
                size = size,
                bitrate = bitrate,
                url = url,
                ext = ext,
                cover = cover,
                link = "https://www.qishui.com/track/${track.id}",
                extra = mapOf("track_id" to track.id),
            )
        }
        return playlist to songs
    }

    private fun buildPlaylistFromUserItem(
        item: SodaUserPlaylistItem,
        currentUserId: String,
        currentNickname: String,
    ): Playlist {
        val playlistId = item.id.trim()
        if (playlistId.isEmpty()) return Playlist()

        val name = firstNonEmpty(item.title, item.publicTitle, playlistId)
        val creator = firstNonEmpty(item.owner.publicName, item.owner.nickname, currentNickname, currentUserId)
        var trackCount = item.countTracks
        if (trackCount == 0) trackCount = item.resourceCnt.trackCnt
        var playCount = item.playCount
        if (playCount == 0) playCount = item.stats.countPlayed

        val extra = mutableMapOf(
            "user_id" to currentUserId,
            "type" to item.type.toString(),
        )
        val ownerId = item.owner.id.trim()
        if (ownerId.isNotEmpty()) extra["owner_id"] = ownerId
        val publicTitle = item.publicTitle.trim()
        if (publicTitle.isNotEmpty()) extra["public_title"] = publicTitle
        val reviewStatus = item.reviewStatus.trim()
        if (reviewStatus.isNotEmpty()) extra["review_status"] = reviewStatus
        if (item.stats.countCollected > 0) extra["collect_count"] = item.stats.countCollected.toString()

        return Playlist(
            source = SODA_SOURCE,
            id = playlistId,
            name = name,
            cover = buildImageUrl(item.urlCover, "~c5_300x300.jpg"),
            trackCount = trackCount,
            playCount = playCount.toLong(),
            creator = creator,
            description = item.desc.trim(),
            link = "https://www.qishui.com/playlist/$playlistId",
            extra = extra,
        )
    }

    // ---------------------------------------------------------------------
    // Album details
    // ---------------------------------------------------------------------

    private suspend fun fetchAlbumDetail(cookie: String, id: String): Pair<Playlist, List<Song>> {
        val body = NativeHttp.get(sodaAlbumLink(id), cookie = cookie, userAgent = SODA_USER_AGENT)
        val page = parseSodaShareAlbumPage(body)
        val info = page.loaderData.albumPage.albumInfo
        if (info.id.isEmpty()) throw IllegalStateException("album not found")

        var description = info.pclines.joinToString(" ")
        description = description.trim()
        if (description.isEmpty()) description = info.company.trim()

        val extra = mutableMapOf("album_id" to info.id)
        if (info.releaseDate > 0) extra["release_date"] = info.releaseDate.toString()
        var album = Playlist(
            source = SODA_SOURCE,
            id = info.id,
            name = info.name,
            cover = buildImageUrl(info.urlCover, "~c5_300x300.jpg"),
            trackCount = info.countTracks,
            creator = joinArtists(info.artists),
            description = description,
            link = sodaAlbumLink(info.id),
            extra = extra,
        )
        if (album.trackCount == 0) {
            album = album.copy(trackCount = page.loaderData.albumPage.trackList.size)
        }

        val songs = mutableListOf<Song>()
        page.loaderData.albumPage.trackList.forEach { track ->
            if (track.id.isEmpty()) return@forEach

            var displaySize = maxBitRateSize(track.bitRates)
            val previewSize = maxBitRateSize(track.preview.bitRates)
            if (previewSize > displaySize) displaySize = previewSize

            var artist = joinArtists(track.artists)
            if (artist.isEmpty()) artist = album.creator

            var cover = buildImageUrl(track.album.urlCover, "~c5_375x375.jpg")
            if (cover.isEmpty()) cover = buildImageUrl(info.urlCover, "~c5_375x375.jpg")

            var albumId = track.album.id
            if (albumId.isEmpty()) albumId = info.id
            var albumName = track.album.name.trim()
            if (albumName.isEmpty()) albumName = info.name

            val duration = track.duration / 1000
            var bitrate = 0
            if (duration > 0 && displaySize > 0) {
                bitrate = (displaySize * 8 / 1000 / duration).toInt()
            }

            songs += Song(
                source = SODA_SOURCE,
                id = track.id,
                name = track.name,
                artist = artist,
                album = albumName,
                albumId = albumId,
                duration = duration,
                size = displaySize,
                bitrate = bitrate,
                cover = cover,
                link = "https://www.qishui.com/track/${track.id}",
                extra = trackExtra(track.id, track.labelInfo, mapOf("album_id" to albumId)),
                isVip = track.labelInfo.isVip(),
            )
        }
        if (songs.isEmpty()) throw IllegalStateException("album has no songs")
        return album to songs
    }

    private fun parseSodaShareAlbumPage(body: String): SodaShareAlbumPage {
        val routerJson = extractSodaJsonBlock(body, "_ROUTER_DATA = ")
        return decodeSodaJson(routerJson, "soda album page json error")
    }

    private fun extractSodaJsonBlock(page: String, marker: String): String {
        val markerStart = page.indexOf(marker)
        if (markerStart < 0) throw IllegalStateException("soda router data not found")
        val start = markerStart + marker.length

        var depth = 0
        var inString = false
        var escaped = false
        var started = false

        var index = start
        while (index < page.length) {
            val ch = page[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                    index++
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                    index++
                    continue
                }
                if (ch == '"') inString = false
                index++
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    depth++
                    started = true
                }
                '}' -> {
                    depth--
                    if (started && depth == 0) return page.substring(start, index + 1)
                }
            }
            index++
        }
        throw IllegalStateException("soda router data is incomplete")
    }

    // ---------------------------------------------------------------------
    // Search / playlist / album link extraction
    // ---------------------------------------------------------------------

    private suspend fun extractPlaylistId(cookie: String, link: String): String? {
        extractPlaylistIdFromText(link)?.let { return it }
        val (finalUrl, body) = fetchSharePage(cookie, link)
        extractPlaylistIdFromText(finalUrl)?.let { return it }
        extractPlaylistIdFromText(body)?.let { return it }
        return null
    }

    private suspend fun extractTrackId(cookie: String, link: String): String? {
        extractTrackIdFromText(link)?.let { return it }
        val (finalUrl, body) = fetchSharePage(cookie, link)
        extractTrackIdFromText(finalUrl)?.let { return it }
        extractTrackIdFromText(body)?.let { return it }
        return null
    }

    private suspend fun fetchSharePage(cookie: String, link: String): Pair<String, String> {
        val response = rawGet(link, mapOf("User-Agent" to SODA_USER_AGENT), cookie)
        return response.finalUrl to response.body
    }

    private fun extractPlaylistIdFromText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && isDigits(trimmed) && !trimmed.contains("/")) return trimmed

        val candidates = mutableListOf(trimmed)
        runCatching { URLDecoder.decode(trimmed, "UTF-8") }.getOrNull()?.let { decoded ->
            if (decoded != trimmed) candidates += decoded
        }

        val patterns = listOf(
            Regex("(?:^|[?&])playlist_id=(\\d+)"),
            Regex("(?:^|[?&])playlistId=(\\d+)"),
            Regex("\"playlist_id\"\\s*:\\s*\"?(\\d+)\"?"),
            Regex("\"playlistId\"\\s*:\\s*\"?(\\d+)\"?"),
            Regex("(?:/|%2f)playlist(?:/|%2f)(\\d+)", RegexOption.IGNORE_CASE),
            Regex("playlist/(\\d+)"),
        )
        for (candidate in candidates) {
            for (pattern in patterns) {
                val id = pattern.find(candidate)?.groupValues?.getOrNull(1).orEmpty()
                if (id.isNotEmpty()) return id
            }
        }
        return null
    }

    private fun extractTrackIdFromText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length > 10 && isDigits(trimmed) && !trimmed.contains("/")) return trimmed

        val candidates = mutableListOf(trimmed)
        runCatching { URLDecoder.decode(trimmed, "UTF-8") }.getOrNull()?.let { decoded ->
            if (decoded != trimmed) candidates += decoded
        }

        val patterns = listOf(
            Regex("(?:^|[?&])track_id=(\\d{10,})"),
            Regex("\"track_id\"\\s*:\\s*\"(\\d{10,})\""),
            Regex("(?:/|%2F)(?:track|song)(?:/|%2F)(\\d{10,})"),
            Regex("(?:track|song)/(\\d{10,})"),
        )
        for (candidate in candidates) {
            for (pattern in patterns) {
                val id = pattern.find(candidate)?.groupValues?.getOrNull(1).orEmpty()
                if (id.isNotEmpty()) return id
            }
        }
        return null
    }

    private fun extractAlbumId(link: String): String {
        val patterns = listOf(
            Regex("album_id=(\\d+)"),
            Regex("(?:^|[?&])id=(\\d+)"),
            Regex("album/(\\d+)"),
        )
        for (pattern in patterns) {
            val id = pattern.find(link)?.groupValues?.getOrNull(1).orEmpty()
            if (id.isNotEmpty()) return id
        }
        val trimmed = link.trim()
        if (trimmed.length > 10 && !trimmed.contains("/")) return trimmed
        return ""
    }

    // ---------------------------------------------------------------------
    // Categories / discovery
    // ---------------------------------------------------------------------

    private fun categoryChannel(categoryId: String): Int {
        val trimmed = categoryId.trim()
        if (trimmed.isEmpty()) return 0
        val channel = trimmed.toIntOrNull()
        if (channel == null || channel < 0) {
            throw IllegalArgumentException("invalid soda playlist category id: $trimmed")
        }
        return channel
    }

    private fun categoryPlaylistRounds(offset: Int, limit: Int): Int {
        val needed = offset + limit
        var rounds = (needed + CATEGORY_PLAYLIST_ITEMS_PER_ROUND - 1) / CATEGORY_PLAYLIST_ITEMS_PER_ROUND
        if (rounds < CATEGORY_PLAYLIST_MIN_ROUNDS) rounds = CATEGORY_PLAYLIST_MIN_ROUNDS
        if (rounds > CATEGORY_PLAYLIST_MAX_ROUNDS) rounds = CATEGORY_PLAYLIST_MAX_ROUNDS
        return rounds
    }

    private suspend fun fetchCategoryPlaylistItems(
        cookie: String,
        channel: Int,
        rounds: Int,
    ): List<SodaUserPlaylistItem> {
        val safeRounds = rounds.coerceAtLeast(1)
        val results = coroutineScope {
            (0 until safeRounds).map {
                async { runCatching { fetchRecommendChannel(cookie, channel) } }
            }.map { it.await() }
        }

        val seen = mutableSetOf<String>()
        val items = mutableListOf<SodaUserPlaylistItem>()
        val errors = mutableListOf<Exception>()
        results.forEach { result ->
            result.fold(
                onSuccess = { batch ->
                    batch.forEach { item ->
                        if (item.id.isEmpty() || !seen.add(item.id)) return@forEach
                        items += item
                    }
                },
                onFailure = { error ->
                    if (error is Exception) errors += error
                },
            )
        }
        if (items.isEmpty()) {
            errors.firstOrNull()?.let { throw it }
            throw IllegalStateException("soda category $channel has no playlists")
        }
        return items
    }

    private fun buildCategoryPlaylist(item: SodaUserPlaylistItem, channel: Int): Playlist {
        val playlist = buildPlaylistFromUserItem(item, "", "")
        if (playlist.id.isEmpty()) return playlist
        val extra = playlist.extra.toMutableMap()
        extra["sub_channel_id"] = channel.toString()
        return playlist.copy(
            creator = firstNonEmpty(item.owner.nickname, item.owner.publicName, playlist.creator),
            description = cleanTopListText(playlist.description, 200),
            extra = extra,
        )
    }

    private suspend fun fetchRecommendChannel(cookie: String, channel: Int): List<SodaUserPlaylistItem> {
        val payload = buildString {
            append("{")
            append("\"block_type\":\"discover_playlist_mix\",")
            append("\"feed_discover_extra\":{},")
            append("\"latest_douyin_liked_playlist_show_ts\":0,")
            append("\"sub_channel_id\":")
            append(channel)
            append("}")
        }
        val body = NativeHttp.postJson(
            "$RECOMMEND_PLAYLISTS_API?${buildQuery(pcAppParams())}",
            payload,
            headers = pcHeaders(),
            cookie = cookie,
            userAgent = PC_APP_USER_AGENT,
        ).body
        return parseDiscoverMixResponse(body)
    }

    private fun parseDiscoverMixResponse(body: String): List<SodaUserPlaylistItem> {
        val resp = decodeSodaJson<SodaDiscoverMixResponse>(body, "soda discover mix json parse error")
        val items = mutableListOf<SodaUserPlaylistItem>()
        resp.innerBlock.forEach { block ->
            block.resources.forEach { resource ->
                if (resource.type.isNotEmpty() && resource.type != "playlist") return@forEach
                val playlist = resource.entity.playlist
                if (playlist.id.isEmpty()) return@forEach
                items += playlist
            }
        }
        if (items.isEmpty()) throw IllegalStateException("soda discover mix has no playlists")
        return items
    }

    private suspend fun fetchRecommendTopLists(cookie: String): List<TopList> {
        val batches = coroutineScope {
            (0 until RECOMMEND_CHANNEL_COUNT).map { channel ->
                async { runCatching { fetchRecommendChannel(cookie, channel) }.getOrDefault(emptyList()) }
            }.map { it.await() }
        }
        val seen = mutableSetOf<String>()
        val topLists = mutableListOf<TopList>()
        for (items in batches) {
            for (item in items) {
                if (item.id.isEmpty() || !seen.add(item.id)) continue
                topLists += topListFromPlaylist(item)
                if (topLists.size >= RECOMMEND_TOP_LIST_LIMIT) return topLists
            }
        }
        return topLists
    }

    private fun topListFromPlaylist(playlist: SodaUserPlaylistItem): TopList {
        val name = firstNonEmpty(playlist.title, playlist.publicTitle)
        val creator = firstNonEmpty(playlist.owner.nickname, playlist.owner.publicName)
        var trackCount = playlist.countTracks
        if (trackCount == 0) trackCount = playlist.resourceCnt.trackCnt

        var description = cleanTopListText(playlist.desc, 200)
        if (description.isEmpty()) description = "汽水音乐推荐歌单"

        val extra = mutableMapOf<String, JsonElement>(
            "group" to JsonPrimitive(RECOMMEND_PLAYLIST_GROUP),
            "toplist_type" to JsonPrimitive("playlist"),
            "playlist_id" to JsonPrimitive(playlist.id),
        )
        if (creator.isNotEmpty()) extra["creator"] = JsonPrimitive(creator)

        return TopList(
            id = playlist.id,
            name = name,
            cover = buildImageUrl(playlist.urlCover, "~c5_300x300.jpg"),
            description = description,
            trackCount = trackCount,
            source = SODA_SOURCE,
            link = "https://www.qishui.com/playlist/${playlist.id}",
            extra = extra,
        )
    }

    private fun cleanTopListText(text: String, limit: Int): String {
        var cleaned = text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
        cleaned = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (limit <= 0) return cleaned
        if (cleaned.codePointCount(0, cleaned.length) > limit) {
            val end = cleaned.offsetByCodePoints(0, limit)
            return cleaned.substring(0, end).trim() + "…"
        }
        return cleaned
    }

    // ---------------------------------------------------------------------
    // Top lists / hot songs
    // ---------------------------------------------------------------------

    private fun builtinTopLists(): List<TopList> = SODA_TOP_LIST_DEFINITIONS.map { definition ->
        TopList(
            id = definition.id,
            name = definition.name,
            cover = definition.cover,
            description = definition.description,
            updateFrequency = TOP_LIST_FREQUENCY,
            trackCount = definition.trackCount,
            source = SODA_SOURCE,
            link = sodaTopListLink(definition.id),
            extra = mapOf(
                "chart_id" to JsonPrimitive(definition.id),
                "group" to JsonPrimitive(CHART_TOP_LIST_GROUP),
                "toplist_type" to JsonPrimitive("chart"),
            ),
        )
    }

    private suspend fun fetchTopListSongs(cookie: String, topListId: String, limit: Int): List<Song> {
        val id = topListId.trim()
        if (id.isEmpty()) throw IllegalArgumentException("soda toplist id is empty")
        if (!isDigits(id)) throw IllegalArgumentException("invalid soda toplist id: $id")

        val chartFirst = isBuiltinChartId(id)
        return try {
            if (chartFirst) fetchChartTopListSongs(cookie, id, limit)
            else fetchPlaylistTopListSongs(cookie, id, limit)
        } catch (firstError: Exception) {
            try {
                if (chartFirst) fetchPlaylistTopListSongs(cookie, id, limit)
                else fetchChartTopListSongs(cookie, id, limit)
            } catch (secondError: Exception) {
                throw IllegalStateException(
                    "soda toplist $id failed: ${firstError.message} (fallback: ${secondError.message})",
                    firstError,
                )
            }
        }
    }

    private suspend fun fetchChartTopListSongs(cookie: String, topListId: String, limit: Int): List<Song> {
        val chart = fetchSodaChart(cookie, topListId)
        val songs = chartSongs(chart, topListId, limit)
        if (songs.isEmpty()) throw IllegalStateException("soda toplist $topListId has no songs")
        return songs
    }

    private suspend fun fetchPlaylistTopListSongs(cookie: String, topListId: String, limit: Int): List<Song> {
        val songs = fetchPlaylistDetail(cookie, topListId).second
        val ranked = rankPlaylistSongs(songs, topListId, limit)
        if (ranked.isEmpty()) throw IllegalStateException("soda toplist $topListId has no songs")
        return ranked
    }

    private fun rankPlaylistSongs(songs: List<Song>, topListId: String, limit: Int): List<Song> {
        val ranked = mutableListOf<Song>()
        songs.forEachIndexed { index, song ->
            ranked += song.copy(
                extra = song.extra + mapOf(
                    "rank" to (index + 1).toString(),
                    "toplist_id" to topListId,
                )
            )
            if (limit > 0 && ranked.size >= limit) return ranked
        }
        return ranked
    }

    private suspend fun fetchSodaChart(cookie: String, topListId: String): SodaChart {
        return try {
            fetchSodaPcChart(cookie, topListId)
        } catch (pcError: Exception) {
            try {
                fetchSodaFallbackChart(cookie, topListId)
            } catch (fallbackError: Exception) {
                throw IllegalStateException(
                    "soda toplist $topListId failed: ${pcError.message} (fallback: ${fallbackError.message})",
                    pcError,
                )
            }
        }
    }

    private suspend fun fetchSodaPcChart(cookie: String, topListId: String): SodaChart {
        val body = NativeHttp.get(
            "$TOP_LIST_API/$topListId?${buildQuery(pcAppParams())}",
            pcHeaders(),
            cookie,
            userAgent = PC_APP_USER_AGENT,
        )
        return parseChartResponse(body).chart
    }

    private suspend fun fetchSodaFallbackChart(cookie: String, topListId: String): SodaChart {
        val body = NativeHttp.get(
            "$TOP_LIST_FALLBACK_API/$topListId?${buildQuery(mapOf("charge" to "0"))}",
            cookie = cookie,
            userAgent = SODA_USER_AGENT,
        )
        return parseChartResponse(body).chart
    }

    private fun parseChartResponse(body: String): SodaChartResponse {
        val resp = decodeSodaJson<SodaChartResponse>(body, "soda toplist json parse error")
        if (resp.statusCode != 0) {
            val message = resp.statusInfo.statusMsg.trim().ifEmpty { "unknown error" }
            throw IllegalStateException(
                "soda toplist api error: status_code=${resp.statusCode} status_msg=$message"
            )
        }
        if (resp.chart.trackRanks.isEmpty()) throw IllegalStateException("soda toplist has no tracks")
        return resp
    }

    private fun chartSongs(chart: SodaChart, topListId: String, limit: Int): List<Song> {
        val songs = mutableListOf<Song>()
        chart.trackRanks.forEachIndexed { index, item ->
            val track = item.track
            if (track.id.trim().isEmpty()) return@forEachIndexed

            var song = buildSongFromTrack(track)
            var rank = chartRankNumber(item.rank?.rank)
            if (rank <= 0) rank = index + 1

            val extra = song.extra.toMutableMap()
            extra["rank"] = rank.toString()
            extra["toplist_id"] = topListId
            scalarString(item.rank?.rankDelta).takeIf { it.isNotEmpty() }?.let { extra["rank_delta"] = it }
            item.rank?.rankType?.trim()?.takeIf { it.isNotEmpty() }?.let { extra["rank_type"] = it }
            item.rank?.trackId?.trim()?.takeIf { it.isNotEmpty() }?.let { extra["rank_track_id"] = it }
            track.vid.trim().takeIf { it.isNotEmpty() }?.let { extra["vid"] = it }

            song = song.copy(extra = extra)
            songs += song
            if (limit > 0 && songs.size >= limit) return songs
        }
        return songs
    }

    private fun chartRankNumber(value: JsonElement?): Int {
        if (value == null || value is JsonNull || value !is JsonPrimitive) return 0
        return if (value.isString) {
            value.content.trim().toIntOrNull() ?: 0
        } else {
            value.doubleOrNull?.let { (it + 0.5).toInt() } ?: 0
        }
    }

    private fun scalarString(value: JsonElement?): String {
        if (value == null || value is JsonNull) return ""
        if (value !is JsonPrimitive) return ""
        if (value.isString) return value.content.trim()
        val boolean = value.booleanOrNull
        if (boolean != null) return boolean.toString()
        val number = value.doubleOrNull ?: return ""
        if (number == number.toLong().toDouble()) return number.toLong().toString()
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()
    }

    private fun normalizeHotSongLimit(limit: Int): Int = when {
        limit <= 0 -> DEFAULT_HOT_SONG_LIMIT
        limit > MAX_HOT_SONG_LIMIT -> MAX_HOT_SONG_LIMIT
        else -> limit
    }

    // ---------------------------------------------------------------------
    // User playlists
    // ---------------------------------------------------------------------

    private suspend fun fetchPcMe(cookie: String): SodaPcMeResponse {
        val body = NativeHttp.get(
            "https://api.qishui.com/luna/pc/me?${buildQuery(pcAppParams())}",
            pcHeaders(),
            cookie,
            userAgent = PC_APP_USER_AGENT,
        )
        val resp = decodeSodaJson<SodaPcMeResponse>(body, "soda me json parse error")
        if (resp.statusCode != 0) {
            val message = resp.statusInfo.statusMsg.trim().ifEmpty { "unknown error" }
            throw IllegalStateException("soda me api error: status_code=${resp.statusCode} status_msg=$message")
        }
        return resp
    }

    private suspend fun fetchUserPlaylistPage(
        cookie: String,
        userId: String,
        cursor: String,
        count: Int,
    ): SodaUserPlaylistResponse {
        val safeCount = if (count <= 0) 50 else count
        val params = pcAppParams()
        params["user_id"] = userId.trim()
        params["cursor"] = cursor.trim()
        params["count"] = safeCount.toString()
        val body = NativeHttp.get(
            "https://api.qishui.com/luna/pc/user/playlist?${buildQuery(params)}",
            pcHeaders(),
            cookie,
            userAgent = PC_APP_USER_AGENT,
        )
        val resp = decodeSodaJson<SodaUserPlaylistResponse>(body, "soda user playlist json parse error")
        if (resp.statusCode != 0) {
            val message = resp.statusInfo.statusMsg.trim().ifEmpty { "unknown error" }
            throw IllegalStateException(
                "soda user playlist api error: status_code=${resp.statusCode} status_msg=$message"
            )
        }
        return resp
    }

    // ---------------------------------------------------------------------
    // Response mapping helpers
    // ---------------------------------------------------------------------

    private fun buildSongFromTrack(track: SodaTrack): Song {
        var displaySize = maxBitRateSize(track.bitRates)
        val previewSize = maxBitRateSize(track.preview.bitRates)
        if (previewSize > displaySize) displaySize = previewSize

        val duration = trackDurationSeconds(track)
        var bitrate = 0
        if (duration > 0 && displaySize > 0) {
            bitrate = (displaySize * 8 / 1000 / duration).toInt()
        }

        val albumId = track.album.id.trim()
        val artist = joinArtists(track.artists)

        var song = Song(
            source = SODA_SOURCE,
            id = track.id,
            name = track.name,
            artist = artist,
            album = track.album.name,
            albumId = albumId,
            duration = duration,
            size = displaySize,
            bitrate = bitrate,
            cover = buildImageUrl(track.album.urlCover, "~c5_375x375.jpg"),
            link = "https://www.qishui.com/track/${track.id}",
            extra = trackExtra(track.id, track.labelInfo, mapOf("album_id" to albumId)),
            isVip = track.labelInfo.isVip(),
        )

        val best = bestTrackPlayInfo(track.audioInfo.playInfoList)
        if (best != null) {
            var downloadUrl = best.mainPlayUrl.trim()
            if (downloadUrl.isEmpty()) downloadUrl = best.backupPlayUrl.trim()
            if (downloadUrl.isNotEmpty()) {
                var url = downloadUrl
                if (best.playAuth.trim().isNotEmpty()) {
                    url += "#auth=" + queryEscape(best.playAuth)
                }
                val extra = song.extra.toMutableMap()
                if (best.quality.trim().isNotEmpty()) extra["quality"] = best.quality.trim()
                song = song.copy(
                    url = url,
                    size = if (best.size > song.size) best.size else song.size,
                    ext = if (best.format.isNotEmpty()) best.format else song.ext,
                    bitrate = if (best.bitrate > 0) normalizeSodaBitrate(best.bitrate) else song.bitrate,
                    extra = extra,
                )
            }
        }
        return song
    }

    private fun bestTrackPlayInfo(list: List<SodaTrackPlayInfo>): SodaTrackPlayInfo? {
        var best: SodaTrackPlayInfo? = null
        for (info in list) {
            if (info.mainPlayUrl.trim().isEmpty() && info.backupPlayUrl.trim().isEmpty()) continue
            val current = best
            if (current == null || betterStreamCandidate(
                    info.duration.toDouble(),
                    info.quality,
                    info.format,
                    info.bitrate,
                    info.size,
                    current.duration.toDouble(),
                    current.quality,
                    current.format,
                    current.bitrate,
                    current.size,
                )
            ) {
                best = info
            }
        }
        return best
    }

    private fun betterStreamCandidate(
        aDuration: Double,
        aQuality: String,
        aFormat: String,
        aBitrate: Int,
        aSize: Long,
        bDuration: Double,
        bQuality: String,
        bFormat: String,
        bBitrate: Int,
        bSize: Long,
    ): Boolean {
        if (aDuration > 0 || bDuration > 0) {
            if (aDuration > bDuration + 1) return true
            if (bDuration > aDuration + 1) return false
        }
        val aRank = qualityRank(aQuality, aFormat, aBitrate)
        val bRank = qualityRank(bQuality, bFormat, bBitrate)
        if (aRank != bRank) return aRank > bRank

        val aBr = normalizeSodaBitrate(aBitrate)
        val bBr = normalizeSodaBitrate(bBitrate)
        if (aBr != bBr) return aBr > bBr
        if (aSize != bSize) return aSize > bSize
        return aQuality.trim() > bQuality.trim()
    }

    private fun qualityRank(quality: String, format: String, bitrate: Int): Int {
        val q = quality.trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")
        val f = format.trim().lowercase()
        val br = normalizeSodaBitrate(bitrate)
        val isLosslessFormat = f.contains("flac") || f.contains("alac") || f.contains("wav")
        val isLosslessLabel =
            q.contains("lossless") || q.contains("flac") || q.contains("sq") || q.contains("svip")
        val isHiResLabel = q.contains("hires") || q.contains("master")
        return when {
            isHiResLabel && (isLosslessFormat || br >= 900) -> 110
            isLosslessLabel || isLosslessFormat || br >= 900 -> 100
            isHiResLabel -> 90
            q.contains("atmos") || q.contains("dolby") || q.contains("spatial") -> 88
            q.contains("highest") || q.contains("excellent") || q.contains("superhigh") || q.contains("hq") -> 80
            q.contains("higher") || q == "high" || q.contains("320") -> 70
            q.contains("standard") || q.contains("medium") || q.contains("normal") ||
                q.contains("128") -> 50
            q.contains("low") || q.contains("preview") -> 10
            else -> when {
                br >= 900 -> 100
                br >= 320 -> 70
                br >= 256 -> 65
                br >= 192 -> 55
                br >= 128 -> 50
                br > 0 -> 20
                else -> 0
            }
        }
    }

    private fun normalizeSodaBitrate(bitrate: Int): Int = if (bitrate > 1000) bitrate / 1000 else bitrate

    private fun trackDurationSeconds(track: SodaTrack): Int =
        if (track.duration > 1000) track.duration / 1000 else track.duration

    private fun maxBitRateSize(bitRates: List<SodaBitRate>): Long {
        var size = 0L
        bitRates.forEach { if (it.size > size) size = it.size }
        return size
    }

    private fun joinArtists(artists: List<SodaArtist>): String =
        artists.mapNotNull { artist -> artist.name.trim().takeIf { it.isNotEmpty() } }
            .joinToString(" / ")

    private fun trackExtra(
        trackId: String,
        label: SodaLabelInfo,
        values: Map<String, String>,
    ): Map<String, String> {
        val extra = linkedMapOf(
            "track_id" to trackId,
            "is_vip" to label.isVip().toString(),
        )
        if (label.onlyVipDownload) extra["only_vip_download"] = "true"
        if (label.onlyVipPlayable) extra["only_vip_playable"] = "true"
        if (label.qualityOnlyVipCanDownload.isNotEmpty()) {
            extra["vip_download_qualities"] = label.qualityOnlyVipCanDownload.joinToString(",")
        }
        if (label.qualityOnlyVipCanPlay.isNotEmpty()) {
            extra["vip_play_qualities"] = label.qualityOnlyVipCanPlay.joinToString(",")
        }
        values.forEach { (key, value) ->
            if (value.trim().isNotEmpty()) extra[key] = value
        }
        return extra
    }

    private fun buildImageUrl(image: SodaImage, suffix: String): String {
        if (image.urls.isEmpty()) return ""
        var cover = image.urls[0].trim()
        val uri = image.uri.trim()
        val templatePrefix = image.templatePrefix.trim()
        if (uri.isNotEmpty() && templatePrefix.isNotEmpty()) {
            return DOUYIN_IMAGE_BASE_URL.trimEnd('/') + "/" + uri + "~" + templatePrefix + "-resize:960:960.png"
        }
        if (uri.isNotEmpty() && !cover.contains(uri)) cover += uri
        if (cover.isEmpty()) return ""
        if (suffix.isNotEmpty() && !cover.contains("~")) cover += suffix
        return cover
    }

    private fun applyDownloadInfo(song: Song, info: DownloadInfo): Song {
        var result = song
        val downloadUrl = downloadInfoUrl(info)
        if (downloadUrl.isNotEmpty()) result = result.copy(url = downloadUrl)
        if (info.size > 0) result = result.copy(size = info.size)
        if (info.format.isNotEmpty()) result = result.copy(ext = info.format)
        if (info.bitrate > 0) {
            result = result.copy(bitrate = normalizeSodaBitrate(info.bitrate))
        } else if (result.duration > 0 && info.size > 0) {
            result = result.copy(bitrate = (info.size * 8 / 1000 / result.duration).toInt())
        }
        if (info.duration > 0 && result.duration == 0) {
            result = result.copy(duration = (info.duration + 0.5).toInt())
        }
        val quality = info.quality.trim()
        if (quality.isNotEmpty()) {
            result = result.copy(
                extra = result.extra + mapOf("quality" to quality, "download_quality" to quality)
            )
        }
        return result
    }

    private fun songTrackId(song: Song): String {
        val extraTrackId = song.extra["track_id"].orEmpty().trim()
        if (extraTrackId.isNotEmpty()) return extraTrackId
        return song.id.trim()
    }

    private fun cachedDownloadInfo(song: Song): DownloadInfo? {
        if (!song.url.contains("#auth=")) return null
        val parts = song.url.split("#auth=")
        if (parts.size != 2) return null
        val auth = runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrDefault(parts[1])
        val quality = song.extra["quality"].orEmpty().trim()
        return DownloadInfo(
            url = parts[0],
            playAuth = auth,
            format = song.ext,
            size = song.size,
            duration = song.duration.toDouble(),
            bitrate = song.bitrate,
            quality = quality,
        )
    }

    private fun downloadInfoUrl(info: DownloadInfo): String =
        if (info.playAuth.trim().isEmpty()) info.url
        else info.url + "#auth=" + queryEscape(info.playAuth)

    private fun downloadInfoIsPreview(info: DownloadInfo?, fullDurationSeconds: Int): Boolean {
        if (info == null || info.duration <= 0 || fullDurationSeconds <= 0) return false
        return info.duration + 5 < fullDurationSeconds
    }

    internal fun parseSodaLyric(raw: String): String {
        val builder = StringBuilder()
        raw.split("\n").forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val match = SODA_LYRIC_LINE.find(line) ?: return@forEach
            val startTime = match.groupValues[1].toIntOrNull() ?: 0
            val content = match.groupValues[3]
            val minutes = startTime / 60000
            val seconds = (startTime % 60000) / 1000
            val millis = (startTime % 1000) / 10
            builder.append(
                "[%02d:%02d.%02d]%s\n".format(Locale.ROOT, minutes, seconds, millis, sodaVerbatimContent(content, startTime))
            )
        }
        return builder.toString()
    }

    /** 汽水逐字载荷 `<词起,词时[,0]>词` 转 `<mm:ss.SSS>` 增强 LRC；没有词标签时退回剥掉标签的纯文本。 */
    private fun sodaVerbatimContent(content: String, lineStartMs: Int): String {
        val matches = SODA_LYRIC_WORD_TAG.findAll(content).toList()
        if (matches.isEmpty()) return SODA_LYRIC_WORD.replace(content, "")
        val builder = StringBuilder()
        for (match in matches) {
            val wordStart = match.groupValues[1].toIntOrNull() ?: 0
            val wordText = match.groupValues[3]
            if (wordText.isEmpty()) continue
            // 与酷我一致：早于行首的时间按相对行首处理
            val start = if (wordStart >= lineStartMs) wordStart else lineStartMs + wordStart
            builder.append('<').append(formatSodaWordTime(start)).append('>').append(wordText)
        }
        return if (builder.isEmpty()) SODA_LYRIC_WORD.replace(content, "") else builder.toString()
    }

    private fun formatSodaWordTime(milliseconds: Int): String {
        val ms = milliseconds.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60000, (ms % 60000) / 1000, ms % 1000)
    }

    // ---------------------------------------------------------------------
    // Small generic helpers
    // ---------------------------------------------------------------------

    private fun webTrackV2Url(trackId: String): String = buildString {
        append("https://api.qishui.com/luna/pc/track_v2?")
        append(
            buildQuery(
                mapOf(
                    "track_id" to trackId,
                    "media_type" to "track",
                    "aid" to "386088",
                    "device_platform" to "web",
                    "channel" to "pc_web",
                )
            )
        )
    }

    private fun seoTrackUrl(trackId: String): String =
        "$SEO_TRACK_BASE?${buildQuery(mapOf("track_id" to trackId, "device_platform" to "web"))}"

    private fun sodaAlbumLink(id: String): String =
        "https://www.qishui.com/share/album?album_id=${id.trim()}"

    private fun sodaTopListLink(topListId: String): String =
        "https://music.douyin.com/qishui/share/playlist?playlist_id=${queryEscape(topListId)}"

    private fun isBuiltinChartId(topListId: String): Boolean =
        SODA_TOP_LIST_DEFINITIONS.any { it.id == topListId }

    private fun isDigits(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '0'..'9' }

    private fun firstNonEmpty(vararg values: String): String {
        for (value in values) {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }

    private fun buildQuery(params: Map<String, String>): String =
        params.entries.sortedBy { it.key }
            .joinToString("&") { (key, value) -> "${queryEscape(key)}=${queryEscape(value)}" }

    private fun queryEscape(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val builder = StringBuilder(bytes.size)
        for (byte in bytes) {
            val code = byte.toInt() and 0xff
            when {
                code in 'a'.code..'z'.code ||
                    code in 'A'.code..'Z'.code ||
                    code in '0'.code..'9'.code ||
                    code == '-'.code || code == '_'.code || code == '.'.code || code == '~'.code ->
                    builder.append(code.toChar())
                code == ' '.code -> builder.append('+')
                else -> builder.append('%')
                    .append(HEX_UPPER[(code ushr 4) and 0x0f])
                    .append(HEX_UPPER[code and 0x0f])
            }
        }
        return builder.toString()
    }

    private fun jsonString(values: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val value = values[key] ?: continue
            val text = jsonAnyString(value)
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun firstJsonString(values: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val list = values[key] as? JsonArray ?: continue
            for (item in list) {
                val text = jsonAnyString(item)
                if (text.isNotEmpty()) return text
            }
        }
        return ""
    }

    private fun jsonAnyString(value: JsonElement): String = when (value) {
        is JsonPrimitive -> if (value.isString) value.content.trim() else ""
        is JsonArray -> value.firstNotNullOfOrNull { item ->
            jsonAnyString(item).takeIf { it.isNotEmpty() }
        }.orEmpty()
        else -> ""
    }

    private fun jsonFloat(values: JsonObject, vararg keys: String): Double {
        for (key in keys) {
            val value = values[key] ?: continue
            if (value !is JsonPrimitive) continue
            if (value.isString) {
                value.content.trim().toDoubleOrNull()?.let { return it }
            } else {
                value.doubleOrNull?.let { return it }
            }
        }
        return 0.0
    }

    private fun jsonInt(values: JsonObject, vararg keys: String): Int =
        (jsonFloat(values, *keys) + 0.5).toInt()

    private fun jsonLong(values: JsonObject, vararg keys: String): Long =
        (jsonFloat(values, *keys) + 0.5).toLong()

    private fun normalizeSodaDuration(duration: Double): Double =
        if (duration > 1000) duration / 1000 else duration

    private fun SodaTrackV2Response.primaryTrack(): SodaTrack =
        if (track.id.isNotEmpty()) track else trackInfo

    private fun SodaLabelInfo.isVip(): Boolean {
        if (onlyVipDownload || onlyVipPlayable) return true
        if (qualityOnlyVipCanDownload.isNotEmpty() || qualityOnlyVipCanPlay.isNotEmpty()) return true
        for (policy in qualityMap.values) {
            if (policy.playDetail?.needVip == true) return true
            if (policy.downloadDetail?.needVip == true) return true
        }
        return false
    }

    private data class DownloadInfo(
        val url: String = "",
        val playAuth: String = "",
        val format: String = "",
        val size: Long = 0,
        val duration: Double = 0.0,
        val bitrate: Int = 0,
        val quality: String = "",
        val preview: Boolean = false,
    )

    private data class SodaVideoModelEntry(
        val mainPlayUrl: String = "",
        val backupPlayUrl: String = "",
        val playAuth: String = "",
        val size: Long = 0,
        val format: String = "",
        val bitrate: Int = 0,
        val quality: String = "",
        val duration: Double = 0.0,
    )

    private data class RawGetResponse(val finalUrl: String, val body: String)

    private data class SodaAppCredentials(
        val deviceId: String = "",
        val iid: String = "",
        val fp: String = "",
        val userAgent: String = "",
    ) {
        fun userAgentOrDefault(): String = userAgent.trim().ifEmpty { PC_APP_USER_AGENT }
    }

    private data class SignedRequest(val url: String, val headers: Map<String, String>)

    companion object {
        /**
         * 内置的 SodaM 公共签名服务：[SodaM](https://github.com/sodahub-org/sodam) 作者提供的默认实例，
         * 桌面版默认就连这一个。用户自己填了地址和 Token 就以填的为准。
         */
        const val DEFAULT_SIGNER_URL = "http://103.236.75.83:8899/sign"
        const val DEFAULT_SIGNER_TOKEN = "b356ad402bc31ee9e5a99cfdbf41eaa39961a562251c3359"

        /** 扫码登录会话的登记寿命：远长于二维码自身 TTL，只用来清理遗留条目。 */
        private const val LOGIN_SESSION_TTL_MS = 10 * 60_000L

        /** 签名器总开关；关掉后回到只走公开试听流的行为。 */
        @Volatile private var signerEnabled = true
        /** 用户自填的签名器地址，例如 http://192.168.1.10:8899/sign；空表示用内置公共服务。 */
        @Volatile private var signerUrl = ""
        @Volatile private var signerToken = ""
        @Volatile private var signerCredentials: SodaAppCredentials? = null

        fun configureSigner(url: String, token: String, enabled: Boolean = true) {
            val nextUrl = normalizeSignerUrl(url)
            val nextToken = token.trim()
            if (nextUrl == signerUrl && nextToken == signerToken && enabled == signerEnabled) return
            signerUrl = nextUrl
            signerToken = nextToken
            signerEnabled = enabled
            signerCredentials = null
        }

        private fun normalizeSignerUrl(raw: String): String {
            var trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            if (!trimmed.contains("://")) trimmed = "http://$trimmed"
            val parsed = trimmed.toHttpUrlOrNull() ?: return trimmed
            if (parsed.encodedPath == "/" || parsed.encodedPath.isEmpty()) {
                return parsed.newBuilder().encodedPath("/sign").build().toString()
            }
            return trimmed
        }

        /** 生效的签名服务地址：没自填就用内置的 SodaM 公共签名服务。 */
        fun effectiveSignerUrl(): String = signerUrl.ifBlank { DEFAULT_SIGNER_URL }

        /** 生效的 Token：公共服务的 Token 随 DEFAULT_SIGNER_URL 一起内置。 */
        fun effectiveSignerToken(): String = signerToken.ifBlank { DEFAULT_SIGNER_TOKEN }

        /** 是否在用内置的 SodaM 公共签名服务（用户没自填地址）。 */
        fun isUsingDefaultSigner(): Boolean = signerUrl.isBlank()

        fun isSignerConfigured(): Boolean = signerEnabled && effectiveSignerUrl().isNotBlank()

        private const val SODA_SOURCE = "soda"
        const val SODA_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val PC_APP_USER_AGENT = "LunaPC/3.3.0(359450208)"
        const val ANDROID_SEARCH_USER_AGENT =
            "com.luna.music/100198030 (Linux; U; Android 15; zh_CN_#Hans; ABR-AL80; Build/V417IR;tt-ok/3.12.13.19)"
        const val ANDROID_API_BASE = "https://api.qishui.com/luna"
        const val ANDROID_SEARCH_PAGE_SIZE = 20
        const val DOUYIN_IMAGE_BASE_URL = "https://p3-luna.douyinpic.com/img/"
        const val SEO_TRACK_BASE = "https://beta-luna.douyin.com/luna/h5/seo_track"
        const val TOP_LIST_API = "https://api.qishui.com/luna/pc/charts"
        const val TOP_LIST_FALLBACK_API = "https://api5-lf.qishui.com/luna/charts"
        const val RECOMMEND_PLAYLISTS_API = "https://api.qishui.com/luna/pc/discover/mix"
        const val FEED_MODE_API = "https://api.qishui.com/luna/pc/feed/mode"
        const val FEED_SONG_TAB_API = "https://api.qishui.com/luna/pc/feed/song-tab"
        const val SODA_FEED_BATCH = 10
        const val HOT_SONG_TOP_LIST_ID = "7036274230471712007"
        const val DEFAULT_HOT_SONG_LIMIT = 100
        const val MAX_HOT_SONG_LIMIT = 200
        const val CHART_TOP_LIST_GROUP = "汽水官方榜"
        const val RECOMMEND_PLAYLIST_GROUP = "汽水推荐歌单"
        const val TOP_LIST_FREQUENCY = "每周四更新"
        const val RECOMMEND_CHANNEL_COUNT = 10
        const val RECOMMEND_TOP_LIST_LIMIT = 100
        const val CATEGORY_PLAYLIST_DEFAULT_LIMIT = 30
        const val CATEGORY_PLAYLIST_ITEMS_PER_ROUND = 8
        const val CATEGORY_PLAYLIST_MIN_ROUNDS = 2
        const val CATEGORY_PLAYLIST_MAX_ROUNDS = 5
        const val VIP_SIGNER_REQUIRED_MESSAGE =
            "会员整曲需要汽水签名器：默认使用 SodaM 公共签名服务，若已关闭请在设置 › 平台账号 › 汽水播放签名器里重新启用"
        const val HEX_UPPER = "0123456789ABCDEF"
        const val SODA_CACHE_DIR = "soda"
        const val SODA_CACHE_FILE_LIMIT = 24
    }
}

// -------------------------------------------------------------------------
// Constants and static catalog data
// -------------------------------------------------------------------------

private val SODA_JSON = NativeHttp.json

private val SODA_RAW_CLIENT: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

/** 无损整曲可能几十 MB，下载/解密不能用 45 秒的接口超时。 */
private val SODA_STREAM_CLIENT: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

private val SODA_LYRIC_LINE = Regex("^\\[(\\d+),(\\d+)\\](.*)")
private val SODA_LYRIC_WORD = Regex("<[^>]+>")
private val SODA_LYRIC_WORD_TAG = Regex("""<(\d+),(\d+)(?:,\d+)?>([^<]*)""")

private data class SodaCategoryTag(val id: Int, val name: String, val group: String)

private val SODA_CATEGORY_TAGS = listOf(
    SodaCategoryTag(0, "每日推荐", "推荐"),
    SodaCategoryTag(14, "流行", "曲风"),
    SodaCategoryTag(8, "华语", "曲风"),
    SodaCategoryTag(9, "欧美", "曲风"),
    SodaCategoryTag(20, "国风", "曲风"),
    SodaCategoryTag(18, "民谣", "曲风"),
    SodaCategoryTag(15, "摇滚", "曲风"),
    SodaCategoryTag(38, "说唱", "曲风"),
    SodaCategoryTag(16, "电子", "曲风"),
    SodaCategoryTag(19, "R&B", "曲风"),
    SodaCategoryTag(69, "治愈", "场景"),
    SodaCategoryTag(45, "睡前", "场景"),
    SodaCategoryTag(40, "学习", "场景"),
)

private data class SodaTopListDefinition(
    val id: String,
    val name: String,
    val description: String,
    val cover: String,
    val trackCount: Int,
)

private val SODA_TOP_LIST_DEFINITIONS = listOf(
    SodaTopListDefinition(
        id = "7036274230471712007",
        name = "热歌榜",
        description = "汽水音乐内每周热度最高的50首歌，每周四更新",
        cover = "https://p3-luna.douyinpic.com/img/tos-cn-i-b829550vbb/d0d8d48461a62748e84689cdf049b19a.png~tplv-b829550vbb-resize:960:960.png",
        trackCount = 50,
    ),
    SodaTopListDefinition(
        id = "7060812597884869927",
        name = "新歌榜",
        description = "近期发行的热度最高的50首新歌，每周四更新",
        cover = "https://p3-luna.douyinpic.com/img/tos-cn-i-b829550vbb/f12f7eb5b54d0899c7c724df009668a8.png~tplv-b829550vbb-resize:960:960.png",
        trackCount = 50,
    ),
    SodaTopListDefinition(
        id = "7061475546400005410",
        name = "欧美榜",
        description = "汽水音乐内每周热度最高的50首外文歌曲，每周四更新",
        cover = "https://p3-luna.douyinpic.com/img/tos-cn-i-b829550vbb/33747550ed5499b58feda42a21748637.png~tplv-b829550vbb-resize:960:960.png",
        trackCount = 50,
    ),
    SodaTopListDefinition(
        id = "7415959718721494311",
        name = "音乐人歌曲榜",
        description = "抖音音乐人开放平台上传歌曲，综合每周站内热度进行排序展示",
        cover = "https://p3-luna.douyinpic.com/img/tos-cn-v-2774c002/o8FQKiQQBxHWa2hzsBNAgYOX6iEHEAibADAbfB~tplv-b829550vbb-resize:960:960.png",
        trackCount = 25,
    ),
)

private inline fun <reified T> decodeSodaJson(body: String, label: String): T =
    try {
        SODA_JSON.decodeFromString<T>(body)
    } catch (error: Exception) {
        throw IllegalStateException("$label: ${error.message}", error)
    }

// -------------------------------------------------------------------------
// Serialization DTOs
// -------------------------------------------------------------------------

@Serializable
private data class SodaStatusInfo(
    @SerialName("status_msg") val statusMsg: String = "",
)

@Serializable
private data class SodaArtist(val name: String = "")

@Serializable
private data class SodaImage(
    val urls: List<String> = emptyList(),
    val uri: String = "",
    @SerialName("template_prefix") val templatePrefix: String = "",
)

@Serializable
private data class SodaBitRate(
    val br: Int = 0,
    val quality: String = "",
    val size: Long = 0,
)

@Serializable
private data class SodaTrackPlayInfo(
    @SerialName("main_play_url") val mainPlayUrl: String = "",
    @SerialName("backup_play_url") val backupPlayUrl: String = "",
    @SerialName("play_auth") val playAuth: String = "",
    val size: Long = 0,
    val format: String = "",
    val bitrate: Int = 0,
    val quality: String = "",
    val duration: Int = 0,
)

@Serializable
private data class SodaTrackAudioInfo(
    @SerialName("play_info_list") val playInfoList: List<SodaTrackPlayInfo> = emptyList(),
)

@Serializable
private data class SodaAlbum(
    val id: String = "",
    val name: String = "",
    @SerialName("url_cover") val urlCover: SodaImage = SodaImage(),
)

@Serializable
private data class SodaPreview(
    val vid: String = "",
    val start: Int = 0,
    val duration: Int = 0,
    @SerialName("bit_rates") val bitRates: List<SodaBitRate> = emptyList(),
)

@Serializable
private data class SodaQualityBenefit(
    val condition: String = "",
    @SerialName("need_vip") val needVip: Boolean = false,
    @SerialName("need_purchase") val needPurchase: Boolean = false,
)

@Serializable
private data class SodaQualityPolicy(
    @SerialName("play_detail") val playDetail: SodaQualityBenefit? = null,
    @SerialName("download_detail") val downloadDetail: SodaQualityBenefit? = null,
)

@Serializable
private data class SodaLabelInfo(
    @SerialName("only_vip_download") val onlyVipDownload: Boolean = false,
    @SerialName("only_vip_playable") val onlyVipPlayable: Boolean = false,
    @SerialName("quality_only_vip_can_download") val qualityOnlyVipCanDownload: List<String> = emptyList(),
    @SerialName("quality_only_vip_can_play") val qualityOnlyVipCanPlay: List<String> = emptyList(),
    @SerialName("quality_map") val qualityMap: Map<String, SodaQualityPolicy> = emptyMap(),
)

@Serializable
private data class SodaTrack(
    val id: String = "",
    val name: String = "",
    val duration: Int = 0,
    val vid: String = "",
    val artists: List<SodaArtist> = emptyList(),
    val album: SodaAlbum = SodaAlbum(),
    @SerialName("bit_rates") val bitRates: List<SodaBitRate> = emptyList(),
    val preview: SodaPreview = SodaPreview(),
    @SerialName("label_info") val labelInfo: SodaLabelInfo = SodaLabelInfo(),
    @SerialName("audio_info") val audioInfo: SodaTrackAudioInfo = SodaTrackAudioInfo(),
)

@Serializable
private data class SodaPlaylistOwner(
    val id: String = "",
    val nickname: String = "",
    @SerialName("public_name") val publicName: String = "",
)

@Serializable
private data class SodaResourceCount(
    @SerialName("track_cnt") val trackCnt: Int = 0,
)

@Serializable
private data class SodaPlaylistStats(
    @SerialName("count_played") val countPlayed: Int = 0,
    @SerialName("count_collected") val countCollected: Int = 0,
)

@Serializable
private data class SodaUserPlaylistItem(
    val id: String = "",
    val title: String = "",
    @SerialName("public_title") val publicTitle: String = "",
    val desc: String = "",
    @SerialName("url_cover") val urlCover: SodaImage = SodaImage(),
    @SerialName("count_tracks") val countTracks: Int = 0,
    @SerialName("play_count") val playCount: Int = 0,
    val owner: SodaPlaylistOwner = SodaPlaylistOwner(),
    @SerialName("review_status") val reviewStatus: String = "",
    val type: Int = 0,
    @SerialName("resource_cnt") val resourceCnt: SodaResourceCount = SodaResourceCount(),
    val stats: SodaPlaylistStats = SodaPlaylistStats(),
)

@Serializable
private data class SodaFeedModeResponse(
    @SerialName("status_code") val statusCode: Int = 0,
    @SerialName("status_info") val statusInfo: SodaStatusInfo = SodaStatusInfo(),
    @SerialName("feed_mode_block") val feedModeBlock: List<SodaFeedModeBlock> = emptyList(),
    @SerialName("feed_mode_bar") val feedModeBar: SodaFeedModeBar = SodaFeedModeBar(),
)

@Serializable
private data class SodaFeedModeBar(
    @SerialName("feed_mode") val feedMode: List<SodaFeedModeEntry> = emptyList(),
)

@Serializable
private data class SodaFeedModeBlock(
    val title: String = "",
    @SerialName("type") val type: String = "",
    @SerialName("feed_mode") val feedMode: List<SodaFeedModeEntry> = emptyList(),
)

@Serializable
private data class SodaFeedModeEntry(
    @SerialName("type") val type: String = "",
    val text: String = "",
    val entity: SodaFeedModeEntity = SodaFeedModeEntity(),
    @SerialName("url_info") val urlInfo: SodaImage = SodaImage(),
)

@Serializable
private data class SodaFeedModeEntity(
    @SerialName("feed_scene_mode") val sceneMode: SodaFeedSceneMode = SodaFeedSceneMode(),
    @SerialName("feed_preference_mode") val preferenceMode: SodaPreferenceMode = SodaPreferenceMode(),
)

@Serializable
private data class SodaFeedSceneMode(
    @SerialName("scene_mode_id") val sceneModeId: Long = -1,
    @SerialName("sub_queue_type") val subQueueType: String = "",
)

@Serializable
private data class SodaPreferenceMode(val mode: String = "")

@Serializable
private data class SodaFeedTabResponse(
    val items: List<SodaFeedItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
private data class SodaFeedItem(
    val id: String = "",
    @SerialName("type") val type: String = "",
    val entity: SodaMediaEntity = SodaMediaEntity(),
)

@Serializable
private data class SodaTrackWrapper(val track: SodaTrack = SodaTrack())

@Serializable
private data class SodaMediaEntity(
    @SerialName("track_wrapper") val trackWrapper: SodaTrackWrapper = SodaTrackWrapper(),
)

@Serializable
private data class SodaMediaResource(
    val type: String = "",
    val entity: SodaMediaEntity = SodaMediaEntity(),
)

@Serializable
private data class SodaPlaylistDetailResponse(
    @SerialName("status_code") val statusCode: Int = 0,
    @SerialName("status_info") val statusInfo: SodaStatusInfo = SodaStatusInfo(),
    @SerialName("next_cursor") val nextCursor: String = "",
    @SerialName("has_more") val hasMore: Boolean = false,
    val playlist: SodaUserPlaylistItem = SodaUserPlaylistItem(),
    @SerialName("media_resources") val mediaResources: List<SodaMediaResource> = emptyList(),
)

@Serializable
private data class SodaWebPlaylist(
    val id: String = "",
    val title: String = "",
    val desc: String = "",
    val owner: SodaPlaylistOwner = SodaPlaylistOwner(),
    @SerialName("count_tracks") val countTracks: Int = 0,
    @SerialName("url_cover") val urlCover: SodaImage = SodaImage(),
)

@Serializable
private data class SodaWebPlaylistResponse(
    val playlist: SodaWebPlaylist = SodaWebPlaylist(),
    @SerialName("media_resources") val mediaResources: List<SodaMediaResource> = emptyList(),
)

@Serializable
private data class SodaTrackPlayer(
    @SerialName("media_id") val mediaId: String = "",
    @SerialName("url_player_info") val urlPlayerInfo: String = "",
    @SerialName("video_model") val videoModel: JsonElement? = null,
)

@Serializable
private data class SodaLyric(val content: String = "")

@Serializable
private data class SodaTrackV2Response(
    @SerialName("status_code") val statusCode: Int = 0,
    @SerialName("status_info") val statusInfo: SodaStatusInfo = SodaStatusInfo(),
    val track: SodaTrack = SodaTrack(),
    @SerialName("track_info") val trackInfo: SodaTrack = SodaTrack(),
    @SerialName("track_player") val trackPlayer: SodaTrackPlayer = SodaTrackPlayer(),
    val lyric: SodaLyric = SodaLyric(),
)

@Serializable
private data class SodaSeoTrack(
    val track: SodaTrack = SodaTrack(),
    val lyric: SodaLyric = SodaLyric(),
)

@Serializable
private data class SodaSeoTrackResponse(
    @SerialName("status_code") val statusCode: Int = 0,
    @SerialName("status_info") val statusInfo: SodaStatusInfo = SodaStatusInfo(),
    @SerialName("track_player") val trackPlayer: SodaTrackPlayer? = null,
    @SerialName("seo_track") val seoTrack: SodaSeoTrack? = null,
    val lyric: SodaLyric? = null,
)

@Serializable
private data class SodaSearchResponse(
    @SerialName("result_groups") val resultGroups: List<SodaSearchGroup> = emptyList(),
)

@Serializable
private data class SodaSearchGroup(val data: List<SodaSearchData> = emptyList())

@Serializable
private data class SodaSearchData(val entity: SodaSearchEntity = SodaSearchEntity())

@Serializable
private data class SodaSearchEntity(
    val track: SodaTrack = SodaTrack(),
    val playlist: SodaUserPlaylistItem = SodaUserPlaylistItem(),
    val album: SodaAlbumInfo = SodaAlbumInfo(),
    val artist: SodaSearchArtist = SodaSearchArtist(),
)

@Serializable
private data class SodaSearchArtist(
    val id: String = "",
    val name: String = "",
    @SerialName("count_tracks") val countTracks: Int = 0,
    @SerialName("url_avatar") val urlAvatar: SodaImage = SodaImage(),
)

@Serializable
private data class SodaAlbumInfo(
    val id: String = "",
    val name: String = "",
    val artists: List<SodaArtist> = emptyList(),
    val company: String = "",
    @SerialName("count_tracks") val countTracks: Int = 0,
    @SerialName("url_cover") val urlCover: SodaImage = SodaImage(),
    @SerialName("release_date") val releaseDate: Long = 0,
    val pclines: List<String> = emptyList(),
)

@Serializable
private data class SodaAlbumPage(
    @SerialName("albumInfo") val albumInfo: SodaAlbumInfo = SodaAlbumInfo(),
    @SerialName("trackList") val trackList: List<SodaTrack> = emptyList(),
)

@Serializable
private data class SodaAlbumLoaderData(
    @SerialName("album_page") val albumPage: SodaAlbumPage = SodaAlbumPage(),
)

@Serializable
private data class SodaShareAlbumPage(
    @SerialName("loaderData") val loaderData: SodaAlbumLoaderData = SodaAlbumLoaderData(),
)

@Serializable
private data class SodaChartResponse(
    @SerialName("status_code") val statusCode: Int = 0,
    @SerialName("status_info") val statusInfo: SodaStatusInfo = SodaStatusInfo(),
    val chart: SodaChart = SodaChart(),
)

@Serializable
private data class SodaChart(
    val id: String = "",
    val title: String = "",
    @SerialName("count_tracks") val countTracks: Int = 0,
    val period: String = "",
    @SerialName("release_time") val releaseTime: Long = 0,
    @SerialName("track_ranks") val trackRanks: List<SodaChartTrackRank> = emptyList(),
)

@Serializable
private data class SodaChartTrackRank(
    val rank: SodaChartRank? = null,
    val track: SodaTrack = SodaTrack(),
)

@Serializable
private data class SodaChartRank(
    val rank: JsonElement? = null,
    @SerialName("rank_delta") val rankDelta: JsonElement? = null,
    @SerialName("rank_type") val rankType: String = "",
    @SerialName("track_id") val trackId: String = "",
)

@Serializable
private data class SodaDiscoverMixResponse(
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("inner_block") val innerBlock: List<SodaDiscoverBlock> = emptyList(),
)

@Serializable
private data class SodaDiscoverBlock(
    val type: String = "",
    val title: String = "",
    @SerialName("inner_block_id") val innerBlockId: String = "",
    val resources: List<SodaDiscoverResource> = emptyList(),
)

@Serializable
private data class SodaDiscoverResource(
    val type: String = "",
    val entity: SodaDiscoverEntity = SodaDiscoverEntity(),
)

@Serializable
private data class SodaDiscoverEntity(
    val playlist: SodaUserPlaylistItem = SodaUserPlaylistItem(),
)

@Serializable
private data class SodaMyInfo(
    val id: String = "",
    val nickname: String = "",
    @SerialName("public_name") val publicName: String = "",
    @SerialName("larger_avatar_url") val avatar: SodaImage = SodaImage(),
)

@Serializable
private data class SodaPcMeResponse(
    @SerialName("status_code") val statusCode: Int = 0,
    @SerialName("status_info") val statusInfo: SodaStatusInfo = SodaStatusInfo(),
    @SerialName("my_info") val myInfo: SodaMyInfo = SodaMyInfo(),
)

@Serializable
private data class SodaUserPlaylistResponse(
    @SerialName("status_code") val statusCode: Int = 0,
    @SerialName("status_info") val statusInfo: SodaStatusInfo = SodaStatusInfo(),
    @SerialName("next_cursor") val nextCursor: String = "",
    @SerialName("has_more") val hasMore: Boolean = false,
    val playlists: List<SodaUserPlaylistItem> = emptyList(),
)

@Serializable
private data class SodaPlayerInfo(
    @SerialName("MainPlayUrl") val mainPlayUrl: String = "",
    @SerialName("BackupPlayUrl") val backupPlayUrl: String = "",
    @SerialName("PlayAuth") val playAuth: String = "",
    @SerialName("Size") val size: Long = 0,
    @SerialName("Bitrate") val bitrate: Int = 0,
    @SerialName("Format") val format: String = "",
    @SerialName("Duration") val duration: Double = 0.0,
    @SerialName("Quality") val quality: String = "",
)

@Serializable
private data class SodaPlayerError(
    @SerialName("Message") val message: String = "",
    @SerialName("Code") val code: String = "",
)

@Serializable
private data class SodaResponseMetadata(
    @SerialName("Error") val error: SodaPlayerError = SodaPlayerError(),
)

@Serializable
private data class SodaPlayerInfoData(
    @SerialName("PlayInfoList") val playInfoList: List<SodaPlayerInfo> = emptyList(),
)

@Serializable
private data class SodaPlayerInfoResult(
    @SerialName("Data") val data: SodaPlayerInfoData = SodaPlayerInfoData(),
)

@Serializable
private data class SodaPlayerInfoResponse(
    @SerialName("ResponseMetadata") val responseMetadata: SodaResponseMetadata = SodaResponseMetadata(),
    @SerialName("Result") val result: SodaPlayerInfoResult = SodaPlayerInfoResult(),
)
