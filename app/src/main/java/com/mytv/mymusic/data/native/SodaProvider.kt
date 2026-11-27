package com.mytv.mymusic.data.native

import android.net.Uri
import com.mytv.mymusic.AppLog
import com.mytv.mymusic.data.AlbumDetail
import com.mytv.mymusic.data.ArtistAlbumPage
import com.mytv.mymusic.data.ArtistDetail
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
import com.mytv.mymusic.data.playreport.PlayReportHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.putJsonArray
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
import kotlin.math.abs

private val SODA_INFO = ProviderInfo(
    id = "soda",
    name = "汽水音乐",
    search = true,
    download = true,
    lyrics = true,
    parseSong = true,
    searchPlaylist = true,
    recommendedPlaylists = true,
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
    playlistWrite = true,
    playlistReorder = true,
)

class SodaProvider : BaseNativeProvider(SODA_INFO) {

    /** 音质探测结果按 cookie 缓存：探测要把整首曲目的所有流抓一遍，不能每次进设置页都重跑。 */
    private val availableQualityCache = java.util.concurrent.ConcurrentHashMap<String, List<AudioQualityOption>>()

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val body = fetchSearchBody(cookie, "track", keyword, 1, ANDROID_SEARCH_PAGE_SIZE)
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

    override suspend fun downloadUrl(cookie: String, song: Song, quality: AudioQuality): String {
        if (song.source.isNotEmpty() && song.source != SODA_SOURCE) {
            throw IllegalArgumentException("source mismatch")
        }
        val trackId = songTrackId(song)
        val cached = cachedDownloadInfo(song)
        if (trackId.isNotEmpty()) {
            val info = if (quality == AudioQuality.Auto) {
                try {
                    resolveDownloadInfo(cookie, trackId, null)
                } catch (error: Exception) {
                    if (cached != null && cached.url.isNotEmpty()) return downloadInfoUrl(cached)
                    throw error
                }
            } else {
                resolveDownloadInfoWithQuality(cookie, trackId, song, quality)
            }
            return downloadInfoUrl(info)
        }
        if (quality != AudioQuality.Auto) throw IllegalStateException("track id is empty")
        if (cached != null && cached.url.isNotEmpty()) return downloadInfoUrl(cached)
        throw IllegalStateException("track id is empty")
    }

    /**
     * 播放地址。加密流（带 play_auth）没法直接交给 ExoPlayer，先下载并按 CENC
     * 解密到缓存文件，返回本地 URI；明文流仍返回原始地址。
     */
    override suspend fun playableStream(cookie: String, song: Song, workDir: File, quality: AudioQuality): PlayableStream {
        if (song.source.isNotEmpty() && song.source != SODA_SOURCE) {
            throw IllegalArgumentException("source mismatch")
        }
        val trackId = songTrackId(song)
        if (trackId.isEmpty()) throw IllegalStateException("track id is empty")
        // 指定档位时不能用按 trackId 命中的缓存文件：它可能是别的档，等于静默降档
        if (quality == AudioQuality.Auto) findCachedFile(trackId, workDir)?.let {
            // 文件名末段是播放信息里的原始码率（bps）；旧版本存下的 Song.bitrate 可能也是 bps，不能照搬
            val cachedBitrate = it.nameWithoutExtension.substringAfterLast('-').toIntOrNull()?.takeIf { br -> br > 0 }
            AppLog.d(TAG, "取流命中本地缓存 track=$trackId 文件=${it.name}")
            return PlayableStream(
                Uri.fromFile(it).toString(),
                ext = it.extension.lowercase(),
                bitrate = cachedBitrate?.let(::normalizeSodaBitrate) ?: song.bitrate,
            )
        }
        val startedAt = System.currentTimeMillis()
        AppLog.i(
            TAG,
            "取流开始 track=$trackId vip=${song.isVip} 签名器=${signerSummary()} " +
                "cookie=${describeCookie(cookie)}",
        )
        val info = if (quality == AudioQuality.Auto) {
            resolveDownloadInfo(cookie, trackId, null)
        } else {
            resolveDownloadInfoWithQuality(cookie, trackId, song, quality)
        }
        if (info.url.trim().isEmpty()) throw IllegalStateException("player info url not found")
        val url = if (info.playAuth.trim().isEmpty()) {
            downloadInfoUrl(info)
        } else {
            try {
                decryptToCache(trackId, info, workDir)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.w(
                    TAG,
                    "整曲下载/解密失败 track=$trackId ${describeDownloadInfo(info)}：${error.message}",
                )
                throw error
            }
        }
        AppLog.i(
            TAG,
            "取流完成 track=$trackId preview=${info.preview} ${describeDownloadInfo(info)} " +
                "解密=${info.playAuth.isNotBlank()} 耗时=${System.currentTimeMillis() - startedAt}ms",
        )
        return PlayableStream(
            url = url,
            ext = info.format.ifBlank { extensionFromUrl(url) }.ifBlank { song.ext },
            // 播放信息里的码率是 bps，Song.bitrate 是 kbps
            bitrate = info.bitrate.takeIf { it > 0 }?.let(::normalizeSodaBitrate) ?: song.bitrate,
            level = info.quality,
            // 会员曲拿到的可能只是片段：地址能用但时长对不上整曲，标记出来让解析器换源
            preview = info.preview,
        )
    }

    override suspend fun accountInfo(cookie: String): AccountInfo? {
        if (cookie.isBlank()) {
            AppLog.d(TAG, "账号资料：没有汽水 Cookie")
            return null
        }
        val me = try {
            fetchPcMe(cookie)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.w(
                TAG,
                "账号资料取不到，Cookie 可能不是完整登录态 cookie=${describeCookie(cookie)}：${error.message}",
            )
            // 登录态失效要把原因抛出去：设置页据此显示「登录已过期，请重新扫码」，
            // 不然一行「已登录」挂在那里，用户不知道该去重新登录。
            // HTTP 401/403 那条路 NativeHttp 只给文案，这里补一个类型化异常。
            val detail = error.message.orEmpty()
            if (error is SodaLoginExpiredException) throw error
            if (sodaLoginExpiredHttp(detail)) {
                throw SodaLoginExpiredException("${SODA_LOGIN_EXPIRED_MARK}（$detail）")
            }
            return null
        }
        val nickname = firstNonEmpty(me.myInfo.nickname, me.myInfo.publicName)
        AppLog.i(
            TAG,
            "账号资料 uid=${me.myInfo.id} 昵称=${nickname.ifBlank { "空" }} " +
                "cookie=${describeCookie(cookie)}",
        )
        if (nickname.isBlank()) return null
        return AccountInfo(
            nickname = nickname,
            avatar = buildImageUrl(me.myInfo.avatar, ""),
            // 账号卡的会员等级槽：汽水只有 VIP / SVIP 两档，非会员留空
            detail = sodaVipLabel(me.myInfo.isVip, me.myInfo.vipStage),
        )
    }

    /** 会员等级文案：`vip_stage` 优先，只有 `is_vip` 时回落到「VIP」（对齐 libresoda account.rs）。 */
    private fun sodaVipLabel(isVip: Boolean, vipStage: String): String = when {
        vipStage.trim().equals("svip", ignoreCase = true) -> "SVIP"
        vipStage.trim().equals("vip", ignoreCase = true) -> "VIP"
        isVip -> "VIP"
        else -> ""
    }

    // ---------------------------------------------------------------------
    // 扫码登录：隐藏 WebView 签名页（SodaQrSigner）+ qr_login.rs 状态机
    // ---------------------------------------------------------------------

    private val loginSessions = java.util.concurrent.ConcurrentHashMap<String, SodaLoginSession>()

    private class SodaLoginSession(
        val session: SodaQrSession,
        /**
         * 这个二维码自己的登录通道。libresoda 8d35f59 起 passport 请求改成纯 HTTP 直连
         * （不再拉签名页要 a_bogus），所以默认用 [SodaQrHttpGateway]；一个二维码一份
         * cookie jar，互不串登录态。只有二次验证（2046）还走 [SodaQrSigner] 的可见窗口。
         */
        val gateway: SodaQrGateway = SodaQrHttpGateway(),
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var consecutiveErrors: Int = 0,
    )

    override suspend fun createLogin(type: String): QRCreateResult {
        pruneLoginSessions()
        // 新二维码 = 新会话：cookie 与通道全部重来，但**设备身份不换**（落盘的那一份），
        // 否则每个二维码都是服务眼里的新设备，扫码后容易 error_code=7、会话也短命。
        loginSessions.values.forEach { it.gateway.close() }
        val (deviceId, installId) = loginDeviceOrGenerate()
        val session = SodaQrSession(deviceId = deviceId, installId = installId)
        val entry = SodaLoginSession(session)
        val created = try {
            SodaQrLogin.create(entry.gateway, session)
        } catch (error: CancellationException) {
            entry.gateway.close()
            throw error
        } catch (error: Exception) {
            entry.gateway.close()
            throw IllegalStateException("汽水二维码获取失败：${error.message ?: "网络错误"}", error)
        }
        val sessionId = NativeProviders.newSessionId(info.id)
        loginSessions[sessionId] = entry
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
        val gateway = entry.gateway
        val result = try {
            SodaQrLogin.check(gateway, entry.session)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 单次失败多半可重试；连着失败说明登录通道出问题了，别空转到过期
            entry.consecutiveErrors += 1
            if (entry.consecutiveErrors >= 3) {
                gateway.close()
                loginSessions.remove(sessionId)
                return QRCheckResult(status = "failed", message = "汽水登录轮询失败：${error.message ?: "未知错误"}", cookie = "")
            }
            return QRCheckResult(status = "waiting", message = "汽水正在确认登录，请稍候…", cookie = "")
        }
        entry.consecutiveErrors = 0
        if (result.status == "success") {
            AppLog.i(TAG, "扫码登录成功 cookie=${describeCookie(result.cookie)}")
        }
        if (result.status == "success" || result.status == "expired" || result.status == "failed") {
            loginSessions.remove(sessionId)
            gateway.close()
        }
        return result
    }

    /** 放弃这次扫码登录：释放通道（纯 HTTP 的 cookie jar / 签名页的隐藏 WebView）。 */
    override fun cancelLogin(sessionId: String) {
        val entry = loginSessions.remove(sessionId) ?: return
        entry.gateway.close()
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
        return parseSodaLyric(resp.lyric.content, sodaChineseTranslation(resp.lyric))
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val body = fetchSearchBody(cookie, "playlist", keyword, 1, ANDROID_SEARCH_PAGE_SIZE)
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
        val body = fetchSearchBody(cookie, "album", keyword, 1, ANDROID_SEARCH_PAGE_SIZE)
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
        val body = fetchSearchBody(cookie, "artist", keyword, 1, ANDROID_SEARCH_PAGE_SIZE)
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

    override suspend fun albumDetail(cookie: String, id: String): AlbumDetail {
        val (album, songs) = fetchAlbumDetail(cookie, id)
        return AlbumDetail(album.toLooseAlbumSummary(), songs)
    }

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
            // played_media 是服务端判断口味的真正通道：把最近真放过的曲目连播放毫秒一起回传，
            // 空数组就等于「这个人什么都没听过」，推荐流只能给通用的热门内容
            append("\"played_media\":")
            append(PlayReportHub.playedMediaJson(info.id, PlayReportHub.PLAYED_MEDIA_LIMIT))
            append(",")
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

    /** 当前生效的设备标识（优先签名器 /config 给出的那份）；埋点要和它保持一致。 */
    internal suspend fun effectiveDeviceId(): String = ensureSignerCredentials().deviceId

    /** 登录用户的平台内部 id；没登录或取不到时为空，上报按匿名设备处理。 */
    internal suspend fun userId(cookie: String): String {
        if (cookie.isBlank()) return ""
        return runCatching { fetchPcMe(cookie).myInfo.id.trim() }.getOrNull().orEmpty()
    }

    /**
     * 搜索回包。2026-11 起 Android `/luna/search/all` 对未签名请求只回
     * `{"extra":{"empty_search":1}}`（或空 body），PC 端 `/luna/pc/search/all`
     * 带应用签名才返回完整 result_groups。所以配了签名器就走 PC 综合搜索，
     * 没配时保留原来的 Android 分类接口——不能把未签名的综合请求当成「没有结果」。
     * 对齐 libresoda `search.rs` 的 `fetch_search_body`。
     */
    private suspend fun fetchSearchBody(
        cookie: String,
        searchType: String,
        keyword: String,
        page: Int,
        pageSize: Int,
    ): String = if (isSignerConfigured()) {
        fetchPcSearchAll(cookie, keyword, page, pageSize, searchType)
    } else {
        fetchAndroidSearch(cookie, searchType, keyword, page, pageSize)
    }

    /**
     * PC 综合搜索（`GET /luna/pc/search/all`），逐请求应用级签名。
     * 上游 music-lib 没有这条（它只有 Android 搜索），是照官方 PC 客户端新增的实现。
     * 签名头没被接受（200 + 空 body / `empty_search`）时退回 Android 分类接口：
     * 它至少还能给出已收录的结果，比「搜不到」更接近真相。
     */
    private suspend fun fetchPcSearchAll(
        cookie: String,
        keyword: String,
        page: Int,
        pageSize: Int,
        fallbackType: String,
    ): String {
        val safePage = if (page < 1) 1 else page
        val safePageSize = if (pageSize <= 0) ANDROID_SEARCH_PAGE_SIZE else pageSize
        val creds = ensureSignerCredentials()
        val params = pcAppParams()
        params["device_id"] = creds.deviceId
        params["iid"] = creds.iid
        params["fp"] = creds.fp
        // PC 综合搜索按 3.7.0 的客户端指纹校验，和其余 PC 接口的 3.3.0 不是同一套
        params["version_name"] = PC_SEARCH_VERSION_NAME
        params["version_code"] = PC_SEARCH_VERSION_CODE
        params["q"] = keyword
        params["cursor"] = ((safePage - 1) * safePageSize).toString()
        params["search_id"] = java.util.UUID.randomUUID().toString()
        params["search_method"] = "input"
        params["debug_params"] = ""
        params["from_search_id"] = ""
        params["search_scene"] = ""
        params["_rticket"] = System.currentTimeMillis().toString()
        val requestUrl = "https://api.qishui.com/luna/pc/search/all?${buildQuery(params)}"
        val headers = linkedMapOf(
            "User-Agent" to PC_SEARCH_USER_AGENT,
            "Accept" to "application/json",
            "Accept-Encoding" to "identity",
            "x-luna-background-type" to "foreground",
            "x-luna-is-background-req" to "0",
            "x-luna-is-local-user" to "0",
            "Cookie" to cookie,
        )
        val signed = signRequest(requestUrl, "", headers, "GET")
        signed.headers.forEach { (key, value) -> headers[key] = value }
        val response = NativeHttp.getResponse(
            signed.url,
            headers,
            cookie = cookie,
            userAgent = PC_SEARCH_USER_AGENT,
        )
        val body = response.body
        if (body.isBlank() || body.contains("empty_search")) {
            AppLog.w(
                TAG,
                "pc search/all 无结果 HTTP=${response.code} body=${body.length}B，退回 Android 搜索接口",
            )
            return fetchAndroidSearch(cookie, fallbackType, keyword, page, pageSize)
        }
        return body
    }

    override val supportsSearchPagination: Boolean = true

    /** 分页搜索：[limit] 是每页条数，页码从 1 开始。 */
    override suspend fun searchSongsPage(cookie: String, keyword: String, page: Int, limit: Int): SongSearchPage {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 50)
        val body = fetchSearchBody(cookie, "track", keyword, safePage, safeLimit)
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
        return SongSearchPage(songs, safePage, songs.size >= safeLimit)
    }

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
        // 设备身份必须跨请求、跨启动稳定：签名器配了用它回传的那份，否则用落盘的上报设备号。
        // 曾经这里每次调用都取当前时间戳，等于**每个请求都是服务端眼里的一台新设备**
        // （对齐 sodam config.rs 的口径：服务端按 device_id 做风控，身份一直换就攒不起信任，
        // 会话也短命）。带签名的读写链路（pcGet / businessPost）下面还会用同一份覆盖一遍。
        val deviceId = signerCredentials?.deviceId
            ?: fallbackDeviceId.ifEmpty { System.currentTimeMillis().toString() }
        return mutableMapOf(
            "aid" to "386088",
            "app_name" to "luna_pc",
            "region" to "cn",
            "geo_region" to "cn",
            "os_region" to "cn",
            "sim_region" to "",
            "device_id" to deviceId,
            "cdid" to "",
            "iid" to deviceId,
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
            AppLog.w(TAG, "web track_v2 请求失败，试 SEO 兜底: ${error.message}")
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
            AppLog.w(TAG, "web track_v2 解析失败，试 SEO 兜底: ${parseError.message}")
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
            AppLog.w(TAG, "track_v2 返回错误 status_code=${resp.statusCode} status_msg=$message")
            throw sodaApiError("track_v2", resp.statusCode.toLong(), message)
        }
        return resp
    }

    private suspend fun fetchSeoTrackData(cookie: String, trackId: String): SodaTrackV2Response {
        val body = NativeHttp.get(seoTrackUrl(trackId), cookie = cookie, userAgent = SODA_USER_AGENT)
        val seo = decodeSodaJson<SodaSeoTrackResponse>(body, "soda seo_track json parse error")
        if (seo.statusCode != 0) {
            val message = seo.statusInfo.statusMsg.trim().ifEmpty { "unknown error" }
            throw sodaApiError("seo_track", seo.statusCode.toLong(), message)
        }
        val seoTrack = seo.seoTrack ?: SodaSeoTrack()
        val track = seoTrack.track
        val seoLyric = seoTrack.lyric
        val topLyric = seo.lyric
        val resp = SodaTrackV2Response(
            statusCode = 0,
            track = track,
            trackInfo = track,
            trackPlayer = seo.trackPlayer ?: SodaTrackPlayer(),
            lyric = SodaLyric(
                content = if (seoLyric.content.trim().isNotEmpty()) seoLyric.content else topLyric?.content.orEmpty(),
                // 翻译挂在顶层 lyric 上，seo_track.lyric 里一般是空的
                translations = topLyric?.translations
                    ?.takeIf { it is JsonObject && it.isNotEmpty() }
                    ?: seoLyric.translations,
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
        AppLog.d(
            TAG,
            "track_v2 track=$id vip=$isVipTrack 时长=${fullDuration}s label=${describeLabel(track.labelInfo)}",
        )

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
                AppLog.w(TAG, "web 播放信息失败，保留 videoModel 结果: ${error.message}")
                // Keep an already-obtained preview stream so member tracks remain playable.
                webInfo = previous
            }
        }
        val webIsPreview = downloadInfoIsPreview(webInfo, fullDuration)
        val webQuality = webInfo?.let { qualityRank(it.quality, it.format, it.bitrate) } ?: 0
        AppLog.d(TAG, "web 流 quality=$webQuality preview=$webIsPreview ${describeDownloadInfo(webInfo)}")

        // 整曲流走 App 端点（POST /luna/pc/track_v2），逐请求签名头 x-helios/x-medusa
        // 由外部签名器生成；默认连 SodaM 的公共签名服务，关闭签名器时保持只走公开 web/SEO 端点。
        if (isSignerConfigured() && cookie.isNotBlank()) {
            val skipPc = webInfo != null && !isVipTrack && !webIsPreview && webQuality >= 60
            val shouldTryPc = !skipPc && (isVipTrack || webIsPreview || webQuality < 100)
            AppLog.d(TAG, "签名整曲路径 skip=$skipPc shouldTry=$shouldTryPc webQuality=$webQuality")
            if (shouldTryPc) {
                try {
                    val pcResp = fetchPcTrackV2Signed(cookie, id)
                    val pcTrack = pcResp.primaryTrack()
                    if (fullDuration == 0) fullDuration = trackDurationSeconds(pcTrack)
                    if (pcTrack.labelInfo.isVip()) isVipTrack = true
                    AppLog.d(
                        TAG,
                        "pc track_v2 vip=$isVipTrack 时长=${fullDuration}s label=${describeLabel(pcTrack.labelInfo)}",
                    )

                    val pcVideo = bestFromVideoModel(pcResp.trackPlayer.videoModel)
                    if (pcVideo != null && !downloadInfoIsPreview(pcVideo, fullDuration)) {
                        AppLog.i(TAG, "pc videoModel 拿到整曲 ${describeDownloadInfo(pcVideo)}")
                        return pcVideo.copy(preview = false)
                    }
                    if (pcResp.trackPlayer.urlPlayerInfo.isNotEmpty()) {
                        val pcInfo = fetchPlayerInfo(cookie, pcResp.trackPlayer.urlPlayerInfo)
                        if (!downloadInfoIsPreview(pcInfo, fullDuration)) {
                            AppLog.i(TAG, "pc 播放信息拿到整曲 ${describeDownloadInfo(pcInfo)}")
                            return pcInfo.copy(preview = false)
                        }
                        AppLog.w(TAG, "pc 只返回了试听 ${describeDownloadInfo(pcInfo)}")
                        lastError = IllegalStateException("soda pc track_v2 returned preview stream")
                    } else {
                        AppLog.w(TAG, "pc track_v2 没有 player info url")
                        lastError = IllegalStateException("soda pc track_v2 missing player info url")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLog.w(TAG, "pc track_v2 失败: ${error.message}")
                    lastError = error
                }
            }
        } else {
            AppLog.d(
                TAG,
                "跳过签名整曲：签名器=${isSignerConfigured()} cookie=${describeCookie(cookie)}",
            )
        }

        if (webInfo != null && webInfo.url.isNotEmpty()) {
            if (webIsPreview) {
                AppLog.w(
                    TAG,
                    "最终退回公开试听流 track=$id vip=$isVipTrack ${describeDownloadInfo(webInfo)} " +
                        "pc原因=${lastError?.message ?: "未尝试"}",
                )
            }
            return webInfo.copy(preview = webIsPreview)
        }

        if (isVipTrack) {
            val reason = lastError?.message ?: "未获取到公开播放地址"
            AppLog.w(TAG, "会员曲无可用流 track=$id：$reason")
            throw IllegalStateException("$VIP_SIGNER_REQUIRED_MESSAGE：$reason")
        }
        if (lastError != null) throw lastError
        throw IllegalStateException("player info url not found")
    }

    /**
     * 指定档位取流：把该曲目所有可选流抓回来，按档位挑一条。挑不到就报错，
     * 绝不退回更低档（会员曲拿不到无损时接口会悄悄给试听/64k，必须拦住）。
     */
    private suspend fun resolveDownloadInfoWithQuality(
        cookie: String,
        trackId: String,
        song: Song,
        quality: AudioQuality,
    ): DownloadInfo {
        if (!SODA_QUALITY_LADDER.supports(quality)) {
            throw IllegalArgumentException("unsupported soda audio quality: $quality")
        }
        val streams = try {
            collectAvailableStreams(cookie, trackId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 抓流失败时，若歌曲里缓存的地址正好是请求的档位就直接用
            val cached = cachedDownloadInfo(song)
            if (cached != null && sodaDownloadInfoQuality(cached) == quality) return cached
            throw error
        }
        return selectSodaQualityStream(streams, quality)
            ?: throw IllegalStateException("soda audio quality $quality is unavailable for this song or account")
    }

    /** 探测当前账号能拿到哪些档：用一首会员曲把所有流抓一遍，再按推断出的档位归类。 */
    override suspend fun availableQualityOptions(cookie: String): List<AudioQualityOption> {
        availableQualityCache[cookie]?.let { return it }
        val streams = runCatching { collectAvailableStreams(cookie, SODA_QUALITY_PROBE_TRACK_ID) }
            .getOrNull() ?: return emptyList()
        val options = SODA_QUALITY_CANDIDATES.filter { candidate ->
            streams.any { sodaDownloadInfoQuality(it) == candidate.value }
        }
        availableQualityCache[cookie] = options
        return options
    }

    /** 两段式第一段：把 web track_v2 与签名 pc track_v2 里的全部流汇总、去重。 */
    private suspend fun collectAvailableStreams(cookie: String, trackId: String): List<DownloadInfo> {
        val id = trackId.trim()
        if (id.isEmpty()) throw IllegalArgumentException("track id is empty")
        val webResp = fetchWebTrackV2(cookie, id)
        var fullDuration = trackDurationSeconds(webResp.primaryTrack())
        val streams = mutableListOf<DownloadInfo>()
        streams += downloadInfosFromVideoModel(webResp.trackPlayer.videoModel)
        val webPlayerUrl = webResp.trackPlayer.urlPlayerInfo
        if (webPlayerUrl.isNotEmpty()) {
            val playerResult = runCatching { fetchPlayerInfoList(cookie, webPlayerUrl) }
            playerResult.getOrNull()?.let { streams += it }
            // videoModel 也没有流时才让播放信息这一步的错误上抛
            if (playerResult.isFailure && streams.isEmpty()) throw playerResult.exceptionOrNull()!!
        }

        if (isSignerConfigured() && cookie.isNotBlank() && webResp.primaryTrack().labelInfo.isVip()) {
            val pcResp = try {
                fetchPcTrackV2Signed(cookie, id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (pcResp != null) {
                val pcDuration = trackDurationSeconds(pcResp.primaryTrack())
                if (pcDuration > 0) fullDuration = pcDuration
                streams += downloadInfosFromVideoModel(pcResp.trackPlayer.videoModel)
                if (pcResp.trackPlayer.urlPlayerInfo.isNotEmpty()) {
                    runCatching { fetchPlayerInfoList(cookie, pcResp.trackPlayer.urlPlayerInfo) }
                        .getOrNull()
                        ?.let { streams += it }
                }
            }
        }

        val seen = mutableSetOf<String>()
        val filtered = streams.filter { info ->
            if (info.url.trim().isEmpty()) return@filter false
            seen.add(info.url + "\u0000" + info.playAuth)
        }.map { info ->
            // 是否试听要按整曲时长判定，videoModel 的流抓回来时还没带上
            if (!info.preview && downloadInfoIsPreview(info, fullDuration)) info.copy(preview = true) else info
        }
        if (filtered.isEmpty()) throw IllegalStateException("soda player info not found")
        return filtered
    }

    /** videoModel 不是只挑最好的一条，而是把每个条目都摊平成流，供按档位挑选。 */
    private fun downloadInfosFromVideoModel(raw: JsonElement?): List<DownloadInfo> {
        if (raw == null || raw is JsonNull) return emptyList()
        var text = raw.toString().trim()
        if (text.isEmpty() || text == "null") return emptyList()
        var depth = 0
        while (depth < 3 && text.startsWith("\"")) {
            val nested = runCatching { SODA_JSON.parseToJsonElement(text) }.getOrNull()
            if (nested !is JsonPrimitive || !nested.isString) break
            text = nested.content.trim()
            depth++
        }
        val value = runCatching { SODA_JSON.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val entries = mutableListOf<SodaVideoModelEntry>()
        collectVideoModelEntries(value, "", "", 0.0, entries)
        return entries.mapNotNull { entry ->
            var url = entry.mainPlayUrl.trim()
            if (url.isEmpty()) url = entry.backupPlayUrl.trim()
            if (url.isEmpty()) return@mapNotNull null
            DownloadInfo(
                url = url,
                playAuth = entry.playAuth.trim(),
                format = entry.format.trim(),
                size = entry.size,
                duration = entry.duration,
                bitrate = entry.bitrate,
                quality = entry.quality.trim(),
            )
        }
    }

    private suspend fun fetchPlayerInfoList(cookie: String, playerInfoUrl: String): List<DownloadInfo> {
        val body = NativeHttp.get(playerInfoUrl, cookie = cookie, userAgent = SODA_USER_AGENT)
        val resp = decodeSodaJson<SodaPlayerInfoResponse>(body, "parse play info response error")
        val list = resp.result.data.playInfoList
        if (list.isEmpty()) {
            val message = resp.responseMetadata.error.message
            throw IllegalStateException(message.ifEmpty { "no audio stream found" })
        }
        return list.mapNotNull { stream ->
            var url = stream.mainPlayUrl.trim()
            if (url.isEmpty()) url = stream.backupPlayUrl.trim()
            if (url.isEmpty()) return@mapNotNull null
            DownloadInfo(
                url = url,
                playAuth = stream.playAuth.trim(),
                format = stream.format.trim(),
                size = stream.size,
                duration = stream.duration,
                bitrate = stream.bitrate,
                quality = stream.quality.trim(),
            )
        }.ifEmpty { throw IllegalStateException("no audio stream found") }
    }

    /** 按 ext + 码率推断实际档位，用于「不静默降档」判断与探测归类。 */
    private fun sodaDownloadInfoQuality(info: DownloadInfo?): AudioQuality {
        info ?: return AudioQuality.Low
        if (info.preview) return AudioQuality.Preview
        val q = info.quality.trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")
        val format = info.format.trim().lowercase()
        val rank = qualityRank(info.quality, info.format, info.bitrate)
        return when {
            q.contains("spatial") || q.contains("atmos") || q.contains("dolby") -> AudioQuality.Spatial
            rank >= 110 -> AudioQuality.HiRes
            rank >= 100 -> AudioQuality.Lossless
            format.contains("flac") || format.contains("alac") || format.contains("wav") -> AudioQuality.Lossless
            rank >= 80 || normalizeSodaBitrate(info.bitrate) >= 280 -> AudioQuality.High
            rank >= 70 || normalizeSodaBitrate(info.bitrate) >= 192 -> AudioQuality.Higher
            rank >= 50 || normalizeSodaBitrate(info.bitrate) >= 96 -> AudioQuality.Standard
            else -> AudioQuality.Low
        }
    }

    /** 从候选里挑请求档位的那一条；同一档取非试听、时长/码率更高的那条。 */
    private fun selectSodaQualityStream(streams: List<DownloadInfo>, quality: AudioQuality): DownloadInfo? {
        var best: DownloadInfo? = null
        for (info in streams) {
            if (sodaDownloadInfoQuality(info) != quality) continue
            val current = best
            if (current == null) {
                best = info
                continue
            }
            if (current.preview && !info.preview) {
                best = info
                continue
            }
            if (betterStreamCandidate(
                    info.duration, info.quality, info.format, info.bitrate, info.size,
                    current.duration, current.quality, current.format, current.bitrate, current.size,
                )
            ) {
                best = info
            }
        }
        return best
    }

    private suspend fun fetchPlayerInfo(cookie: String, playerInfoUrl: String): DownloadInfo {
        val body = NativeHttp.get(playerInfoUrl, cookie = cookie, userAgent = SODA_USER_AGENT)
        val resp = decodeSodaJson<SodaPlayerInfoResponse>(body, "parse play info response error")
        val list = resp.result.data.playInfoList
        if (list.isEmpty()) {
            val message = resp.responseMetadata.error.message
            if (message.isNotEmpty()) {
                AppLog.w(TAG, "player_info 无音频流: $message")
                throw IllegalStateException(message)
            }
            AppLog.w(TAG, "player_info 无音频流，返回体 ${body.length}B")
            throw IllegalStateException("no audio stream found")
        }
        val best = bestPlayerInfo(list) ?: throw IllegalStateException("invalid download url")
        AppLog.d(
            TAG,
            "player_info 候选 ${list.size} 档: " +
                list.joinToString(" | ") { "${it.quality}/${it.format}/${it.bitrate}/${it.duration.toInt()}s" },
        )
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
     * 所以传给签名器的必须是即将原样发出的那一份。[method] 只有 GET / POST 两种用法：
     * 读接口是 GET（body 空），写接口是 POST。
     */
    private suspend fun signRequest(
        requestUrl: String,
        body: String,
        headers: Map<String, String>,
        method: String = "POST",
    ): SignedRequest {
        val endpoint = effectiveSignerUrl()
        if (endpoint.isEmpty()) throw IllegalStateException(VIP_SIGNER_REQUIRED_MESSAGE)
        val payload = buildJsonObject {
            put("url", requestUrl)
            put("method", method)
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
            postSignerWithRetry(endpoint, payload, signerHeaders)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.w(TAG, "签名器请求失败 endpoint=${describeEndpoint(endpoint)}：${error.message}")
            val hint = signerErrorHint(error.message.orEmpty())
            throw IllegalStateException("汽水签名器请求失败：${hint ?: error.message}", error)
        }
        val signed = try {
            parseSignerResponse(response.body, requestUrl)
        } catch (error: Exception) {
            AppLog.w(TAG, "签名器返回不可用 endpoint=${describeEndpoint(endpoint)}：${error.message}")
            throw error
        }
        AppLog.d(
            TAG,
            "签名成功 HTTP=${response.code} headers=${signed.headers.keys.joinToString(",")} " +
                "query=${if (signed.url != requestUrl) "已附加" else "无"}",
        )
        return signed
    }

    /**
     * libmssdk 的 429 `RATE_LIMITED` / 503 `BUSY` 都是可重试的（签名没有副作用），
     * 重试一次、等 1 秒；其余状态码直接失败。
     */
    private suspend fun postSignerWithRetry(
        endpoint: String,
        payload: String,
        headers: Map<String, String>,
    ): NativeResponse {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return NativeHttp.postJson(endpoint, payload, headers, userAgent = SODA_USER_AGENT)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                val message = error.message.orEmpty()
                val retryable = attempt == 0 && (message.contains("HTTP 429") || message.contains("HTTP 503"))
                if (!retryable) return@repeat
                AppLog.d(TAG, "签名服务忙/限流，1 秒后重试：${signerErrorHint(message) ?: message.take(120)}")
                delay(1_000)
            }
        }
        throw lastError ?: IllegalStateException("汽水签名器请求失败")
    }

    private fun parseSignerResponse(raw: String, requestUrl: String): SignedRequest {
        val root = runCatching { SODA_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw IllegalStateException("汽水签名器返回的不是 JSON")
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull
        if (ok == false) {
            var message = jsonString(root, "error").ifEmpty { "unknown error" }
            val code = jsonString(root, "code")
            if (code.isNotEmpty()) message += "（$code）"
            if (message.contains("unauthorized", ignoreCase = true) || code.equals("UNAUTHORIZED", true)) {
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
        // libmssdk 新版在回包里带它实际签名用的 device_id（URL 里没点名设备号时就是服务自己那份）
        jsonString(root, "device_id").takeIf { it.isNotEmpty() }?.let(::adoptSignerDeviceId)
        return SignedRequest(appendSignerQuery(requestUrl, msToken, aBogus), headers)
    }

    /** 把签名器实际使用的设备号写回缓存，后续 URL 里的 device_id / fp 与签名保持一致。 */
    private fun adoptSignerDeviceId(deviceId: String) {
        val current = signerCredentials
        if (current?.deviceId == deviceId) return
        signerCredentials = SodaAppCredentials(
            deviceId = deviceId,
            iid = current?.iid ?: deviceId,
            fp = deviceId,
        )
        AppLog.d(TAG, "采用签名器设备号 deviceId=${maskId(deviceId)}（后续 URL 与之对齐）")
    }

    /** libmssdk 的失败原因码按「下一步做什么」分类，这里把它翻译成用户能看懂的提示。 */
    private fun signerErrorHint(raw: String): String? = when {
        raw.contains("RATE_LIMITED") -> "签名服务被限流，请稍后重试"
        raw.contains("\"code\":\"BUSY\"") || raw.contains("\"code\":\"busy\"") -> "签名服务忙，请稍后重试"
        raw.contains("UNAUTHORIZED", ignoreCase = true) || raw.contains("unauthorized", ignoreCase = true) ->
            "签名服务要求鉴权：在设置里填写签名器 Token"
        else -> null
    }

    private fun appendSignerQuery(url: String, msToken: String, aBogus: String): String {
        if (msToken.isEmpty() && aBogus.isEmpty()) return url
        val builder = url.toHttpUrlOrNull()?.newBuilder() ?: return url
        if (msToken.isNotEmpty()) builder.setQueryParameter("msToken", msToken)
        if (aBogus.isNotEmpty()) builder.setQueryParameter("a_bogus", aBogus)
        return builder.build().toString()
    }

    /**
     * 设备身份。libmssdk 新版已删除 `GET /config`（它会明文返回完整设备号与安装号），
     * 这里先用落盘的设备号发第一份签名；签名器实际使用的那份会随 `/sign` 回包的
     * `device_id` 回来，[adoptSignerDeviceId] 对齐后后续 URL 与签名就是同一份。
     */
    private fun ensureSignerCredentials(): SodaAppCredentials {
        signerCredentials?.let { return it }
        // 用落盘的那份：每次启动重新生成的话，服务端（埋点、播放计数、推荐口味的
        // played_media）永远攒不起同一个人的历史；安装号跟设备号同一条口径
        // （和 localAppCredentials 一致），不再每次重建都换一个新的
        val deviceId = fallbackDeviceId.ifEmpty { System.currentTimeMillis().toString() }
        val credentials = SodaAppCredentials(
            deviceId = deviceId,
            iid = deviceId,
            fp = deviceId,
        )
        signerCredentials = credentials
        AppLog.d(
            TAG,
            "设备指纹 来源=本地 deviceId=${maskId(deviceId)} iid=${maskId(credentials.iid)} " +
                "（/config 已被签名服务删除，/sign 回包会带实际使用的那份）",
        )
        return credentials
    }

    /** 设备号只留首尾各 4 位，够对照又不会把完整指纹写进可分享的日志。 */
    private fun maskId(id: String): String =
        if (id.length <= 8) id else "${id.take(4)}…${id.takeLast(4)}(${id.length})"

    /**
     * 业务写接口的带签名 POST：播放上报（`/luna/pc/action`、`/luna/pc/me/recently-played-media`）
     * 和取流共用同一套签名器与设备指纹，请求头也必须和送进签名器的那份一致。
     * 签名器没配或签名失败时按原样发出——播放计数不带签名服务端也收。
     */
    internal suspend fun businessPost(
        path: String,
        body: String,
        cookie: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): NativeResponse {
        // 签名器关掉时不上网取指纹：播放上报不带签名服务端也收
        val signing = isSignerConfigured()
        val creds = if (signing) ensureSignerCredentials() else localAppCredentials()
        val params = pcAppParams()
        params["device_id"] = creds.deviceId
        params["iid"] = creds.iid
        params["fp"] = creds.fp
        val requestUrl = "https://api.qishui.com$path?${buildQuery(params)}"
        val headers = linkedMapOf(
            "User-Agent" to creds.userAgentOrDefault(),
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "application/json",
            "X-SS-STUB" to NativeCrypto.md5Hex(body).uppercase(Locale.ROOT),
            "x-luna-background-type" to "foreground",
            "x-luna-is-background-req" to "0",
            "x-luna-is-local-user" to "1",
            "Cookie" to cookie,
        )
        headers.putAll(extraHeaders)
        val signed = if (signing) runCatching { signRequest(requestUrl, body, headers) }.getOrNull() else null
        signed?.headers?.forEach { (key, value) -> headers[key] = value }
        return NativeHttp.postJson(
            signed?.url ?: requestUrl,
            body,
            headers,
            cookie = cookie,
            userAgent = creds.userAgentOrDefault(),
        )
    }

    /** 签名器不可用时本地生成的一份设备身份，仅供播放上报这类不强制签名的接口用。 */
    private fun localAppCredentials(): SodaAppCredentials {
        val deviceId = fallbackDeviceId.ifEmpty { System.currentTimeMillis().toString() }
        return SodaAppCredentials(deviceId = deviceId, iid = deviceId, fp = deviceId)
    }

    // ---------------------------------------------------------------------
    // 与 libresoda 对齐的新增能力：歌单写操作、收藏、歌手页、最近播放、联想词。
    // 这些接口都要求逐请求的应用级签名（x-helios / x-medusa），签名覆盖
    // 「URL + 全部请求头 + body」，所以参数和头必须在签名之前全部就位。
    // ---------------------------------------------------------------------

    /**
     * 带应用级签名的 GET。[params] 是业务查询参数（会并进 PC 公共参数一起签名），
     * [rawSuffix] 给需要重复 key 的参数用（官方客户端把数组编成 `item_types=a&item_types=b`）。
     *
     * [requireSigner] 为真时签名器没配就直接报错——写接口（收藏、歌单增删改）
     * 不带签名服务端不认，静默降级成未签名请求只会得到空响应。
     */
    internal suspend fun pcGet(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        cookie: String,
        rawSuffix: String = "",
        requireSigner: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val signing = isSignerConfigured()
        if (requireSigner && !signing) {
            throw IllegalStateException("这个操作需要汽水签名器：$VIP_SIGNER_REQUIRED_MESSAGE")
        }
        val creds = if (signing) ensureSignerCredentials() else localAppCredentials()
        val query = pcAppParams()
        query["device_id"] = creds.deviceId
        query["iid"] = creds.iid
        query["fp"] = creds.fp
        params.forEach { (key, value) -> query[key] = value }
        var requestUrl = "https://api.qishui.com$path?${buildQuery(query)}"
        if (rawSuffix.isNotEmpty()) requestUrl += "&$rawSuffix"
        val headers = linkedMapOf(
            "User-Agent" to creds.userAgentOrDefault(),
            "Accept" to "application/json",
            "x-luna-background-type" to "foreground",
            "x-luna-is-background-req" to "0",
            "x-luna-is-local-user" to "1",
            "Cookie" to cookie,
        )
        headers.putAll(extraHeaders)
        val signed = if (signing) runCatching { signRequest(requestUrl, "", headers, "GET") }.getOrNull() else null
        signed?.headers?.forEach { (key, value) -> headers[key] = value }
        val response = NativeHttp.getResponse(
            signed?.url ?: requestUrl,
            headers,
            cookie = cookie,
            userAgent = creds.userAgentOrDefault(),
        )
        if (response.body.isBlank()) {
            // 和 pc track_v2 同一个信号：签名头没被接受，或设备指纹和签名器不一致
            signerCredentials = null
            AppLog.w(TAG, "$path 返回空响应 HTTP=${response.code} deviceId=${maskId(creds.deviceId)}")
            throw IllegalStateException(
                "汽水 $path 返回空响应：签名头未被接受，请检查签名器、设备指纹与 Cookie",
            )
        }
        return response.body
    }

    /** 写操作的统一出口：发了 POST、校验回包、把服务端文案翻成用户能看懂的话。 */
    private suspend fun playlistWrite(
        cookie: String,
        path: String,
        body: String,
        action: String,
    ) {
        if (cookie.isBlank()) {
            throw PlaylistWriteException("请先登录汽水音乐", PlaylistWriteFailure.NotLoggedIn)
        }
        val raw = businessPost(path, body, cookie).body
        val root = SodaJson.parse(raw)
        if (!SodaPlaylistEdit.responseIsOk(root)) {
            val code = jsonInt(root, "status_code") ?: 0
            val message = SodaPlaylistEdit.responseErrorMessage(root)
            AppLog.w(TAG, "歌单写操作失败 path=$path code=$code msg=${message.ifBlank { "空" }}")
            throw PlaylistWriteException(
                when {
                    code == 1000003 -> "请先登录汽水音乐"
                    message.isNotBlank() -> message
                    else -> "汽水音乐${action}失败（$code）"
                },
                if (code == 1000003) PlaylistWriteFailure.NotLoggedIn else PlaylistWriteFailure.Other,
            )
        }
    }

    // ---------------- 歌单写操作（对齐 libresoda playlist_edit.rs） ----------------

    /**
     * 「我创建的歌单」：`GET /luna/pc/me/playlist`，官方客户端侧边栏用的就是它。
     * 只有出现在这里的歌单才能改——收藏来的别人的歌单不在列表里。
     * 隐私歌单在 `extra["is_private"]` 里标记（服务端只有为真时才给字段）。
     *
     * 返回「本页歌单 + 下一页游标」：空游标表示没有下一页。
     */
    internal suspend fun myPlaylistPage(cookie: String, cursor: String = "", count: Int = 50): Pair<List<Playlist>, String> {
        if (cookie.isBlank()) return emptyList<Playlist>() to ""
        val safeCount = if (count <= 0) 50 else count.coerceAtMost(100)
        val raw = pcGet(
            SODA_MY_PLAYLISTS_PATH,
            listOf("cursor" to cursor.trim(), "count" to safeCount.toString()),
            cookie,
        )
        val root = SodaJson.parse(raw) ?: return emptyList<Playlist>() to ""
        val playlists = SodaArtistApi.firstArray(root, "playlists").mapNotNull { entry ->
            val id = jsonText(entry, "id")
            if (id.isEmpty()) return@mapNotNull null
            val extra = mutableMapOf<String, String>()
            // 服务端只有隐私歌单才给这个字段，缺席就是不隐私
            if (jsonBool(entry, "is_private") == true) extra["is_private"] = "true"
            val ownerId = jsonText(entry, "owner", "id")
            if (ownerId.isNotEmpty()) extra["owner_id"] = ownerId
            Playlist(
                source = SODA_SOURCE,
                id = id,
                name = listOf(
                    jsonText(entry, "public_title"),
                    jsonText(entry, "title"),
                    jsonText(entry, "name"),
                ).firstOrNull { it.isNotBlank() }.orEmpty(),
                cover = SodaArtistApi.imageUrl(
                    jsonObj(entry, "url_cover") ?: JsonObject(emptyMap()),
                    "~c5_300x300.jpg",
                ),
                trackCount = jsonInt(entry, "count_tracks") ?: 0,
                creator = jsonText(entry, "owner", "nickname"),
                description = jsonText(entry, "desc"),
                link = "https://www.qishui.com/playlist/$id",
                extra = extra,
            )
        }
        return playlists to SodaArtistApi.nextCursor(root)
    }

    /** 我创建的歌单第一页；给账号卡片这类只展示几个的地方用。 */
    internal suspend fun myPlaylists(cookie: String, count: Int = 50): List<Playlist> =
        myPlaylistPage(cookie, "", count).first

    /**
     * 这个歌单能不能用当前账号改：出现在「我创建的歌单」里就能改。
     * null 表示没法判断（没登录 / 网络失败），调用方按「可以试」处理。
     */
    override suspend fun playlistWritable(cookie: String, id: String): Boolean? {
        if (cookie.isBlank()) return null
        val playlistId = id.trim()
        if (playlistId.isEmpty()) return null
        var cursor = ""
        val seen = mutableSetOf<String>()
        while (seen.size < 300) {
            val (mine, next) = try {
                myPlaylistPage(cookie, cursor, 100)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }
            if (mine.any { it.id == playlistId }) return true
            if (mine.isEmpty() || next.isEmpty() || next == cursor || !seen.add(next)) return false
            cursor = next
        }
        return false
    }

    override suspend fun addPlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
        val ids = songs.map { songTrackId(it) }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return
        playlistWrite(
            cookie,
            SodaPlaylistEdit.APPEND_MEDIA_PATH,
            SodaPlaylistEdit.mediaBody(playlistId, ids),
            "添加歌曲",
        )
    }

    override suspend fun removePlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
        val ids = songs.map { songTrackId(it) }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return
        playlistWrite(
            cookie,
            SodaPlaylistEdit.DELETE_MEDIA_PATH,
            SodaPlaylistEdit.mediaBody(playlistId, ids),
            "移除歌曲",
        )
    }

    override suspend fun reorderPlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
        val ids = songs.map { songTrackId(it) }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return
        // 排序只有非 PC 路径（PC 路径 404），客户端 IDL 里也只登记了这一条
        playlistWrite(
            cookie,
            SodaPlaylistEdit.SORT_MEDIA_PATH,
            SodaPlaylistEdit.mediaBody(playlistId, ids),
            "调整顺序",
        )
    }

    /** 在汽水上建歌单；带 [trackIds] 时建完就把这批歌放进去。返回新歌单 id。 */
    suspend fun createPlaylist(cookie: String, name: String, isPrivate: Boolean = false, trackIds: List<String> = emptyList()): String {
        SodaPlaylistEdit.checkName(name)?.let { throw PlaylistWriteException(it, PlaylistWriteFailure.Other) }
        val raw = businessPost(
            SodaPlaylistEdit.CREATE_PATH,
            SodaPlaylistEdit.createBody(name, isPrivate, trackIds),
            cookie,
        ).body
        val root = SodaJson.parse(raw)
        if (!SodaPlaylistEdit.responseIsOk(root)) {
            val code = jsonInt(root, "status_code") ?: 0
            val message = SodaPlaylistEdit.responseErrorMessage(root)
            throw PlaylistWriteException(
                when {
                    // 服务端对超长名字只回这个码，本地已经拦过一次，这里兜住服务端硬线
                    code == 1000004 -> SodaPlaylistEdit.checkName(name) ?: "汽水音乐不接受这个歌单名"
                    message.isNotBlank() -> message
                    else -> "汽水音乐创建歌单失败（$code）"
                },
                PlaylistWriteFailure.Other,
            )
        }
        return SodaPlaylistEdit.extractPlaylistId(root)
    }

    /** 改名 / 改描述 / 改可见性；只传要改的字段。 */
    suspend fun updatePlaylistInfo(
        cookie: String,
        playlistId: String,
        name: String? = null,
        description: String? = null,
        isPrivate: Boolean? = null,
    ) {
        name?.let { SodaPlaylistEdit.checkName(it)?.let { message -> throw PlaylistWriteException(message, PlaylistWriteFailure.Other) } }
        playlistWrite(
            cookie,
            SodaPlaylistEdit.UPDATE_PATH,
            SodaPlaylistEdit.updateBody(playlistId, name, description, isPrivate),
            "修改歌单",
        )
    }

    /** 批量删除歌单。 */
    suspend fun deletePlaylists(cookie: String, playlistIds: List<String>) {
        val ids = SodaCollection.cleanIds(playlistIds)
        if (ids.isEmpty()) return
        playlistWrite(cookie, SodaPlaylistEdit.DELETE_PATH, SodaPlaylistEdit.deleteBody(ids), "删除歌单")
    }

    /** 歌单名敏感词检查：true = 命中。以写入回包为准，这个只做前端提示。 */
    suspend fun playlistNameHasSensitiveWord(cookie: String, name: String): Boolean? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || cookie.isBlank()) return null
        val raw = pcGet(
            SodaPlaylistEdit.WORD_CHECK_PATH,
            listOf("content" to trimmed, "type" to SodaPlaylistEdit.WORD_CHECK_NAME),
            cookie,
        )
        return SodaPlaylistEdit.sensitiveWordHit(SodaJson.parse(raw))
    }

    /** 导入外部歌单链接；返回新歌单 id。导入是异步的，进度靠 [playlistImportTasks] 查。 */
    suspend fun importPlaylist(cookie: String, url: String): String {
        val raw = businessPost(SodaPlaylistEdit.IMPORT_PATH, SodaPlaylistEdit.importBody(url), cookie).body
        val root = SodaJson.parse(raw)
        if (!SodaPlaylistEdit.responseIsOk(root)) {
            val code = jsonInt(root, "status_code") ?: 0
            throw PlaylistWriteException(
                SodaPlaylistEdit.responseErrorMessage(root).ifEmpty { "汽水音乐导入歌单失败（$code）" },
                PlaylistWriteFailure.Other,
            )
        }
        return SodaPlaylistEdit.extractPlaylistId(root)
    }

    /** 查导入任务进度。 */
    suspend fun playlistImportTasks(cookie: String, taskIds: List<String>): String =
        businessPost(SodaPlaylistEdit.IMPORT_TASKS_PATH, SodaPlaylistEdit.importTasksBody(taskIds), cookie).body

    // ---------------- 收藏（对齐 libresoda collection.rs） ----------------

    /** 汽水的「我喜欢的音乐」是平台侧收藏，换设备也在；没登录时不给这个入口。 */
    override fun supportsCollect(): Boolean = true

    /** 喜欢 / 取消喜欢单曲（「我喜欢的音乐」）。 */
    override suspend fun collectTrack(cookie: String, song: Song) {
        val trackId = songTrackId(song)
        if (trackId.isEmpty()) throw IllegalArgumentException("track id is empty")
        val raw = businessPost(
            SodaCollection.COLLECT_MEDIA_PATH,
            SodaCollection.collectMediaBody(listOf(trackId)),
            cookie,
        ).body
        sodaWriteFailed(raw, "收藏")
    }

    override suspend fun uncollectTrack(cookie: String, song: Song) {
        val trackId = songTrackId(song)
        if (trackId.isEmpty()) throw IllegalArgumentException("track id is empty")
        val raw = businessPost(
            SodaCollection.UNCOLLECT_MEDIA_PATH,
            SodaCollection.uncollectMediaBody(listOf(trackId)),
            cookie,
        ).body
        sodaWriteFailed(raw, "取消收藏")
    }

    /** 批量版：收藏多首（换源面板「把这批歌收藏到汽水」用）。 */
    suspend fun collectTracks(cookie: String, songs: List<Song>) {
        val ids = songs.map { songTrackId(it) }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return
        val raw = businessPost(SodaCollection.COLLECT_MEDIA_PATH, SodaCollection.collectMediaBody(ids), cookie).body
        sodaWriteFailed(raw, "收藏")
    }

    suspend fun uncollectTracks(cookie: String, songs: List<Song>) {
        val ids = songs.map { songTrackId(it) }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return
        val raw = businessPost(SodaCollection.UNCOLLECT_MEDIA_PATH, SodaCollection.uncollectMediaBody(ids), cookie).body
        sodaWriteFailed(raw, "取消收藏")
    }

    suspend fun collectPlaylists(cookie: String, playlistIds: List<String>) {
        val ids = SodaCollection.cleanIds(playlistIds)
        if (ids.isEmpty() || cookie.isBlank()) return
        val raw = businessPost(SodaCollection.COLLECT_PLAYLIST_PATH, SodaCollection.idsBody("playlist_ids", ids), cookie).body
        sodaWriteFailed(raw, "收藏歌单")
    }

    suspend fun uncollectPlaylists(cookie: String, playlistIds: List<String>) {
        val ids = SodaCollection.cleanIds(playlistIds)
        if (ids.isEmpty() || cookie.isBlank()) return
        val raw = businessPost(SodaCollection.UNCOLLECT_PLAYLIST_PATH, SodaCollection.idsBody("playlist_ids", ids), cookie).body
        sodaWriteFailed(raw, "取消收藏歌单")
    }

    suspend fun collectAlbums(cookie: String, albumIds: List<String>) {
        val ids = SodaCollection.cleanIds(albumIds)
        if (ids.isEmpty() || cookie.isBlank()) return
        val raw = businessPost(SodaCollection.COLLECT_ALBUM_PATH, SodaCollection.idsBody("album_ids", ids), cookie).body
        sodaWriteFailed(raw, "收藏专辑")
    }

    suspend fun uncollectAlbums(cookie: String, albumIds: List<String>) {
        val ids = SodaCollection.cleanIds(albumIds)
        if (ids.isEmpty() || cookie.isBlank()) return
        val raw = businessPost(SodaCollection.UNCOLLECT_ALBUM_PATH, SodaCollection.idsBody("album_ids", ids), cookie).body
        sodaWriteFailed(raw, "取消收藏专辑")
    }

    suspend fun collectArtists(cookie: String, artistIds: List<String>) {
        val ids = SodaCollection.cleanIds(artistIds)
        if (ids.isEmpty() || cookie.isBlank()) return
        val raw = businessPost(SodaCollection.COLLECT_ARTIST_PATH, SodaCollection.idsBody("artist_ids", ids), cookie).body
        sodaWriteFailed(raw, "收藏艺人")
    }

    suspend fun uncollectArtists(cookie: String, artistIds: List<String>) {
        val ids = SodaCollection.cleanIds(artistIds)
        if (ids.isEmpty() || cookie.isBlank()) return
        val raw = businessPost(SodaCollection.UNCOLLECT_ARTIST_PATH, SodaCollection.idsBody("artist_ids", ids), cookie).body
        sodaWriteFailed(raw, "取消收藏艺人")
    }

    /**
     * 我收藏的内容（单曲 / 歌单 / 专辑 / 艺人）。空收藏时服务端**不返回**
     * `mixed_collections` 字段，只回 `status_info`——按空列表算，不当失败。
     */
    internal suspend fun collectedItems(
        cookie: String,
        cursor: String = "",
        count: Int = SodaCollection.DEFAULT_COUNT,
        itemTypes: List<String> = listOf(SodaCollection.ITEM_ALBUM, SodaCollection.ITEM_PLAYLIST),
    ): List<SodaCollectedItem> {
        if (cookie.isBlank()) return emptyList()
        val suffix = itemTypes.map { "item_types=${queryEscape(it.trim())}" }
            .filter { it.isNotBlank() }
            .joinToString("&")
        val raw = pcGet(
            SodaCollection.COLLECTED_MIXED_PATH,
            listOf("cursor" to cursor.trim(), "count" to SodaCollection.clampCount(count).toString()),
            cookie,
            rawSuffix = suffix,
        )
        return SodaCollectedParser.parse(SodaJson.parse(raw))
    }

    /** 我收藏的艺人（独立接口，每项 `is_collected=true`）。 */
    internal suspend fun collectedArtists(cookie: String, cursor: String = "", count: Int = 20): List<SodaCollectedItem> {
        if (cookie.isBlank()) return emptyList()
        val raw = pcGet(
            SodaCollection.ARTIST_COLLECTION_PATH,
            listOf("cursor" to cursor.trim(), "count" to SodaCollection.clampCount(count).toString()),
            cookie,
        )
        return SodaCollectedParser.parseArtists(SodaJson.parse(raw))
    }

    /** 写操作回包校验：非 0 的 status_code 一律当失败，把服务端文案带出去。 */
    private fun sodaWriteFailed(raw: String, action: String) {        val root = SodaJson.parse(raw)
        if (SodaPlaylistEdit.responseIsOk(root)) return
        val code = jsonInt(root, "status_code") ?: 0
        val message = SodaPlaylistEdit.responseErrorMessage(root)
        AppLog.w(TAG, "收藏写操作失败 action=$action code=$code msg=${message.ifBlank { "空" }}")
        throw IllegalStateException(
            when {
                code == 1000003 -> "请先登录汽水音乐"
                message.isNotBlank() -> message
                else -> "汽水音乐${action}失败（$code）"
            },
        )
    }

    // ---------------- 歌手页（对齐 libresoda artist.rs） ----------------

    override suspend fun artistDetail(cookie: String, id: String): ArtistDetail {
        val artistId = id.trim()
        if (artistId.isEmpty()) throw IllegalArgumentException("artist id is empty")
        val raw = pcGet("${SodaArtistApi.ARTIST_PATH}/$artistId", cookie = cookie)
        return sodaArtistDetail(SodaJson.parse(raw), artistId, info.id)
    }

    override suspend fun artistSongs(cookie: String, id: String): List<Song> {
        val artistId = id.trim()
        if (artistId.isEmpty()) return emptyList()
        val raw = pcGet(
            "${SodaArtistApi.ARTIST_PATH}/$artistId/tracks",
            listOf("cursor" to "", "count" to SodaArtistApi.clampCount(0).toString()),
            cookie,
        )
        return SodaArtistApi.tracks(SodaJson.parse(raw)).map { buildSongFromTrack(it) }
    }

    override suspend fun artistAlbums(cookie: String, id: String, page: Int, limit: Int): ArtistAlbumPage {
        val artistId = id.trim()
        if (artistId.isEmpty()) return ArtistAlbumPage(emptyList())
        val safeLimit = SodaArtistApi.clampCount(limit)
        val cursor = ((page.coerceAtLeast(1) - 1) * safeLimit).toString()
        val raw = pcGet(
            "${SodaArtistApi.ARTIST_PATH}/$artistId/albums",
            listOf("cursor" to cursor, "count" to safeLimit.toString()),
            cookie,
        )
        val root = SodaJson.parse(raw)
        return sodaArtistAlbumPage(root, SodaArtistApi.albums(root))
    }

    // ---------------- 最近播放 / 播放统计（对齐 libresoda playback.rs） ----------------

    /** 读「最近播放」；服务端字段随版本变动，这里按曲目 id + 标题解。 */
    suspend fun recentlyPlayed(cookie: String, cursor: String = "", count: Int = 20): List<Song> {
        if (cookie.isBlank()) return emptyList()
        val raw = pcGet(
            SODA_RECENTLY_PLAYED_PATH,
            listOf(
                "cursor" to cursor.trim(),
                "count" to SodaArtistApi.clampCount(count).toString(),
                "media_type" to "track",
                "scene" to "",
            ),
            cookie,
        )
        val root = SodaJson.parse(raw) ?: return emptyList()
        val entries = SodaArtistApi.firstArray(root, "media_list", "recently_played_media", "items")
        return entries.mapNotNull { entry ->
            val track = jsonObj(entry, "track") ?: entry
            decodeSodaJson<SodaTrack>(track.toString(), "soda recently played json parse error")
                .takeIf { it.id.isNotEmpty() }
                ?.let { buildSongFromTrack(it) }
        }
    }

    /** 从「最近播放」里删一条。 */
    suspend fun deleteRecentlyPlayed(cookie: String, trackIds: List<String>) {
        val ids = SodaCollection.cleanIds(trackIds)
        if (ids.isEmpty() || cookie.isBlank()) return
        businessPost("$SODA_RECENTLY_PLAYED_PATH/delete", SodaCollection.mediaOnlyBody(ids), cookie)
    }

    /** 播放统计：带 `media_id` 才回 stats，只带 status 的是空查询。 */
    suspend fun mediaStats(cookie: String, trackId: String): String {
        if (cookie.isBlank() || trackId.isBlank()) return ""
        return pcGet(SODA_MEDIA_STATS_PATH, listOf("media_id" to trackId, "media_type" to "track"), cookie)
    }

    /**
     * 播放前的业务校验（对齐 libresoda `report.rs::check_media`）：回包带版权状态
     * （`media_status[].status`）和风控结果（`risk_result`）。拿不到就返回空串。
     */
    suspend fun checkMedia(
        cookie: String,
        trackId: String,
        durationSeconds: Int = 0,
        sceneName: String = "player",
        queueType: String = "feed",
    ): String {
        if (cookie.isBlank() || trackId.isBlank()) return ""
        val body = buildJsonObject {
            putJsonArray("media_list") {
                add(buildJsonObject {
                    put("type", "track")
                    put("id", trackId.trim())
                    putJsonObject("attr") { put("duration", durationSeconds.coerceAtLeast(0)) }
                })
            }
            putJsonObject("limited_free_param_map") {}
            put("scene", 1)
            put("queue_type", queueType.trim())
            put("scene_name", sceneName.trim())
        }.toString()
        return businessPost(SODA_MCHECK_PATH, body, cookie).body
    }

    /** 已购 / 收藏的数字专辑（对齐 libresoda `collection.rs::digital_albums`）。 */
    suspend fun digitalAlbums(cookie: String, cursor: String = "", count: Int = 20): String {
        if (cookie.isBlank()) return ""
        return pcGet(
            SodaCollection.DIGITAL_ALBUMS_PATH,
            listOf("cursor" to cursor.trim(), "count" to SodaCollection.clampCount(count).toString()),
            cookie,
        )
    }

    /** 指定用户的收藏混合列表；传自己的 user_id 就是「我收藏的」。 */
    internal suspend fun userMixedCollections(
        cookie: String,
        userId: String,
        cursor: String = "",
        count: Int = 20,
        itemTypes: List<String> = listOf(SodaCollection.ITEM_ALBUM, SodaCollection.ITEM_PLAYLIST),
    ): List<SodaCollectedItem> {
        val uid = userId.trim()
        if (cookie.isBlank() || uid.isEmpty()) return emptyList()
        val suffix = itemTypes.map { "item_types=${queryEscape(it.trim())}" }
            .filter { it.isNotBlank() }
            .joinToString("&")
        val raw = pcGet(
            SodaCollection.USER_MIXED_PATH,
            listOf("user_id" to uid, "cursor" to cursor.trim(), "count" to SodaCollection.clampCount(count).toString()),
            cookie,
            rawSuffix = suffix,
        )
        return SodaCollectedParser.parse(SodaJson.parse(raw))
    }

    /**
     * 专辑详情优先走 `GET /luna/pc/albums/{id}`：JSON 直出，还带艺人 id。
     * 分享页 HTML（抓 `_ROUTER_DATA`）只作兜底——那条链路服务端一改版就整块失效。
     * PC 接口解不出东西（字段变动、空曲目）时静默回落到分享页，行为和以前一致。
     */
    private suspend fun fetchAlbumDetail(cookie: String, id: String): Pair<Playlist, List<Song>> {
        fetchPcAlbumDetail(cookie, id)?.let { return it }
        return fetchAlbumDetailFromSharePage(cookie, id)
    }

    private suspend fun fetchPcAlbumDetail(cookie: String, id: String): Pair<Playlist, List<Song>>? {
        val albumId = id.trim()
        if (albumId.isEmpty()) return null
        val raw = runCatching { pcGet("$SODA_PC_ALBUM_PATH/$albumId", cookie = cookie) }.getOrNull() ?: return null
        val root = SodaJson.parse(raw) ?: return null
        val info = SodaArtistApi.detailNode(root) ?: return null
        val resolvedId = jsonText(info, "id").ifEmpty { albumId }
        val name = jsonText(info, "name")
        if (name.isEmpty()) return null
        val songs = SodaArtistApi.tracks(root).map { buildSongFromTrack(it) }
        // 一首都没解出来就交给分享页：宁可慢一点，也不能给用户一个空专辑
        if (songs.isEmpty()) return null

        val artists = jsonObjList(info, "artists")
        val description = listOf(
            jsonText(info, "description"),
            jsonText(info, "desc"),
            info.pclines(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val extra = mutableMapOf("album_id" to resolvedId)
        val artistId = artists.firstOrNull()?.let { jsonText(it, "id") }.orEmpty()
        if (artistId.isNotEmpty()) extra["artist_id"] = artistId
        val releaseDate = jsonLong(info, "release_date") ?: 0L
        if (releaseDate > 0) extra["release_date"] = releaseDate.toString()
        val company = jsonText(info, "company")
        if (company.isNotEmpty()) extra["company"] = company

        val album = Playlist(
            source = SODA_SOURCE,
            id = resolvedId,
            name = name,
            cover = SodaArtistApi.imageUrl(jsonObj(info, "url_cover") ?: JsonObject(emptyMap()), "~c5_300x300.jpg"),
            trackCount = jsonInt(info, "count_tracks") ?: songs.size,
            creator = artists.firstOrNull()?.let { jsonText(it, "name") }.orEmpty(),
            description = description,
            link = sodaAlbumLink(resolvedId),
            extra = extra,
        )
        return album to songs
    }

    /** `pclines` 是分享页里的多行简介，PC 接口有时也带这个字段。 */
    private fun JsonObject.pclines(): String =
        SodaJson.stringList(this, "pclines").joinToString(" ").trim()

    private suspend fun fetchAlbumDetailFromSharePage(cookie: String, id: String): Pair<Playlist, List<Song>> {
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

    /** 「音乐墙」：总听歌时长 / 本周听歌时长。 */
    suspend fun musicWall(cookie: String): Pair<Long, Long> {
        if (cookie.isBlank()) return 0L to 0L
        val raw = businessPost(SODA_MUSIC_WALL_PATH, "{\"ab_param\":\"\",\"recent_rankings_version\":\"\",\"schema_extra\":\"\"}", cookie).body
        val root = SodaJson.parse(raw) ?: return 0L to 0L
        val node = jsonObj(root, "listen_duration") ?: root
        return jsonLong(node, "total_duration").orZero() to jsonLong(node, "week_duration").orZero()
    }

    // ---------------- 搜索联想 / 热搜词（对齐 libresoda search.rs） ----------------

    /** 联想词：`GET /luna/pc/sug`，客户端每次请求带一个新的 v4 UUID。 */
    suspend fun searchSuggestions(cookie: String, keyword: String): List<String> {
        val query = keyword.trim()
        if (query.isEmpty()) return emptyList()
        val raw = pcGet(
            SODA_SUG_PATH,
            listOf("q" to query, "sug_scene" to "main", "sug_search_id" to java.util.UUID.randomUUID().toString()),
            cookie,
        )
        val root = SodaJson.parse(raw) ?: return emptyList()
        return SodaArtistApi.firstArray(root, "sugs", "items").mapNotNull { entry ->
            val text = listOf(
                jsonText(entry, "content"),
                jsonText(entry, "word"),
                jsonText(entry, "title"),
                jsonText(entry, "q"),
            ).firstOrNull { it.isNotBlank() }
            text?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    /** 热搜词：`GET /luna/suggest-words/{type}`，`default` 是客户端默认那一档。 */
    suspend fun hotSearchWords(cookie: String, type: String = "default"): List<String> {
        val kind = type.trim().ifEmpty { "default" }
        val raw = pcGet("${SODA_SUGGEST_WORDS_PATH}/$kind", cookie = cookie)
        val root = SodaJson.parse(raw) ?: return emptyList()
        return SodaArtistApi.firstArray(root, "words", "suggest_words", "items").mapNotNull { entry ->
            val text = listOf(
                jsonText(entry, "word"),
                jsonText(entry, "content"),
                jsonText(entry, "title"),
            ).firstOrNull { it.isNotBlank() }
            text?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    /** 推荐歌单：`GET /luna/me/playlist/recommend`。 */
    override suspend fun recommendedPlaylists(cookie: String): List<Playlist> {
        val raw = pcGet(SODA_RECOMMEND_PLAYLIST_PATH, cookie = cookie)
        val root = SodaJson.parse(raw) ?: return emptyList()
        return SodaArtistApi.firstArray(root, "playlists", "items").mapNotNull { entry ->
            val id = jsonText(entry, "id")
            if (id.isEmpty()) return@mapNotNull null
            Playlist(
                source = SODA_SOURCE,
                id = id,
                name = listOf(jsonText(entry, "public_title"), jsonText(entry, "title"), jsonText(entry, "name"))
                    .firstOrNull { it.isNotBlank() }.orEmpty(),
                cover = SodaArtistApi.imageUrl(jsonObj(entry, "url_cover") ?: JsonObject(emptyMap()), "~c5_300x300.jpg"),
                trackCount = jsonInt(entry, "count_tracks") ?: 0,
                creator = jsonText(entry, "owner", "nickname"),
                description = jsonText(entry, "desc"),
                link = "https://www.qishui.com/playlist/$id",
            )
        }
    }

    private fun Long?.orZero(): Long = this ?: 0L

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
            // 签名器可能换了设备号或重启：清掉缓存，下次从 /sign 回包里重新对齐
            signerCredentials = null
            AppLog.w(
                TAG,
                "pc track_v2 返回空响应 HTTP=${response.code} deviceId=${maskId(creds.deviceId)} " +
                    "cookie=${describeCookie(cookie)}",
            )
            throw IllegalStateException(
                "soda pc track_v2 返回空响应：签名头未被接受，请检查签名器、设备指纹与 Cookie"
            )
        }
        return try {
            parseTrackV2Response(response.body)
        } catch (error: Exception) {
            AppLog.w(
                TAG,
                "pc track_v2 解析失败 HTTP=${response.code} deviceId=${maskId(creds.deviceId)}：${error.message}",
            )
            throw error
        }
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

        // 无损整曲几十 MB，180 秒的总超时一次下不完：加密字节先落 .part，断了接着要
        val downloaded = SodaResumableDownloader(SODA_STREAM_CLIENT, SODA_USER_AGENT).download(
            url = info.url,
            target = file,
            // 密钥指纹：换签名 / 换档后旧字节解不开，必须重下
            keyFingerprint = NativeCrypto.md5Hex(info.playAuth),
        )
        if (downloaded.bytes.isEmpty()) throw IllegalStateException("soda 音频下载为空")
        if (downloaded.report.resumedFrom > 0) {
            AppLog.i(TAG, "续传生效 trackId=$trackId 接上=${downloaded.report.resumedFrom}B " +
                "本次=${downloaded.report.transferred}B 共=${downloaded.report.onDisk}B")
        }
        // 解密直接写文件：明文不再整份驻留内存（密文 + 副本 + 明文三份同时在堆里会 OOM）
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.outputStream().buffered(DECRYPT_BUFFER_BYTES).use { out ->
                SodaDecrypt.decryptAudioTo(downloaded.bytes, info.playAuth, out)
            }
        } catch (error: Exception) {
            tmp.delete()
            throw error
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("soda 缓存写入失败")
        }
        pruneCache(dir)
        return Uri.fromFile(file).toString()
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
            throw sodaApiError("playlist detail", resp.statusCode.toLong(), message)
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
            if (chartFirst) fetchChartTopListSongs(id, limit)
            else fetchPlaylistTopListSongs(cookie, id, limit)
        } catch (firstError: Exception) {
            try {
                if (chartFirst) fetchPlaylistTopListSongs(cookie, id, limit)
                else fetchChartTopListSongs(id, limit)
            } catch (secondError: Exception) {
                throw IllegalStateException(
                    "soda toplist $id failed: ${firstError.message} (fallback: ${secondError.message})",
                    firstError,
                )
            }
        }
    }

    private suspend fun fetchChartTopListSongs(topListId: String, limit: Int): List<Song> {
        val chart = fetchSodaChart(topListId)
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

    // 榜单是公开接口：带登录 Cookie 会被服务端当成 PC 私有读请求，回 1000006
    // ERR_REQUEST_FORBIDDEN（登录后榜单整页失败），所以这里一律匿名请求。
    private suspend fun fetchSodaChart(topListId: String): SodaChart {
        return try {
            fetchSodaPcChart(topListId)
        } catch (pcError: Exception) {
            try {
                fetchSodaFallbackChart(topListId)
            } catch (fallbackError: Exception) {
                throw IllegalStateException(
                    "soda toplist $topListId failed: ${pcError.message} (fallback: ${fallbackError.message})",
                    pcError,
                )
            }
        }
    }

    private suspend fun fetchSodaPcChart(topListId: String): SodaChart {
        val body = NativeHttp.get(
            "$TOP_LIST_API/$topListId?${buildQuery(pcAppParams())}",
            pcHeaders(),
            userAgent = PC_APP_USER_AGENT,
        )
        return parseChartResponse(body).chart
    }

    private suspend fun fetchSodaFallbackChart(topListId: String): SodaChart {
        val body = NativeHttp.get(
            "$TOP_LIST_FALLBACK_API/$topListId?${buildQuery(mapOf("charge" to "0"))}",
            userAgent = SODA_USER_AGENT,
        )
        return parseChartResponse(body).chart
    }

    private fun parseChartResponse(body: String): SodaChartResponse {
        val resp = decodeSodaJson<SodaChartResponse>(body, "soda toplist json parse error")
        if (resp.statusCode != 0) {
            val message = resp.statusInfo.statusMsg.trim().ifEmpty { "unknown error" }
            throw sodaApiError("toplist", resp.statusCode.toLong(), message)
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
            throw sodaApiError("me", resp.statusCode.toLong(), message)
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
            throw sodaApiError("user playlist", resp.statusCode.toLong(), message)
        }
        return resp
    }

    // ---------------------------------------------------------------------
    // Response mapping helpers
    // ---------------------------------------------------------------------

    internal fun buildSongFromTrack(track: SodaTrack): Song {
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

    internal fun bestTrackPlayInfo(list: List<SodaTrackPlayInfo>): SodaTrackPlayInfo? {
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

    internal fun normalizeSodaBitrate(bitrate: Int): Int = if (bitrate > 1000) bitrate / 1000 else bitrate

    internal fun trackDurationSeconds(track: SodaTrack): Int =
        if (track.duration > 1000) track.duration / 1000 else track.duration

    internal fun maxBitRateSize(bitRates: List<SodaBitRate>): Long {
        var size = 0L
        bitRates.forEach { if (it.size > size) size = it.size }
        return size
    }

    internal fun joinArtists(artists: List<SodaArtist>): String =
        artists.mapNotNull { artist -> artist.name.trim().takeIf { it.isNotEmpty() } }
            .joinToString(" / ")

    internal fun trackExtra(
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

    internal fun buildImageUrl(image: SodaImage, suffix: String): String {
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

    internal fun songTrackId(song: Song): String {
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

    // ---------------------------------------------------------------------
    // 诊断日志：VIP 整曲取流是账号状态 × 签名器 × 服务端策略三方共同决定的，
    // 只报「只能试听」没法定位。这里把每段判断都记进 AppLog（不记 Cookie/Token 值）。
    // ---------------------------------------------------------------------

    private fun describeDownloadInfo(info: DownloadInfo?): String {
        if (info == null) return "流=none"
        return "流 quality=${info.quality} format=${info.format} bitrate=${info.bitrate} " +
            "时长=${info.duration}s 大小=${info.size} auth=${info.playAuth.length}B"
    }

    private fun describeLabel(label: SodaLabelInfo): String = buildString {
        append("onlyVipPlay=").append(label.onlyVipPlayable)
        append(" onlyVipDl=").append(label.onlyVipDownload)
        if (label.qualityOnlyVipCanPlay.isNotEmpty()) {
            append(" vipPlayQualities=").append(label.qualityOnlyVipCanPlay.joinToString(","))
        }
        if (label.qualityOnlyVipCanDownload.isNotEmpty()) {
            append(" vipDlQualities=").append(label.qualityOnlyVipCanDownload.joinToString(","))
        }
        if (label.qualityMap.isNotEmpty()) append(" qualities=").append(label.qualityMap.keys.joinToString(","))
    }

    /** 只列 Cookie 的字段名，值一律不落日志。 */
    private fun describeCookie(cookie: String): String = cookieFieldNames(cookie)

    private fun signerSummary(): String = when {
        !isSignerConfigured() -> "未启用"
        isUsingDefaultSigner() -> "SodaM公共(${describeEndpoint(effectiveSignerUrl())})"
        else -> "自定义(${describeEndpoint(effectiveSignerUrl())})"
    }

    private fun describeEndpoint(url: String): String =
        url.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: url

    internal fun parseSodaLyric(raw: String, translation: String = ""): String {
        val originals = mutableListOf<Pair<Int, String>>()
        raw.split("\n").forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val match = SODA_LYRIC_LINE.find(line) ?: return@forEach
            val startTime = match.groupValues[1].toIntOrNull() ?: 0
            originals += startTime to sodaVerbatimContent(match.groupValues[3], startTime)
        }
        if (originals.isEmpty()) return ""
        val translations = sodaTranslationLines(translation, originals)
        val builder = StringBuilder()
        originals.forEachIndexed { index, entry ->
            builder.appendSodaLyricLine(entry.first, entry.second)
            translations[index]?.forEach { text ->
                // 中文歌偶尔会把原文也塞进 translations，和原文一样的行不再重复一遍
                if (text != SODA_LYRIC_WORD.replace(entry.second, "")) {
                    builder.appendSodaLyricLine(entry.first, text)
                }
            }
        }
        return builder.toString()
    }

    private fun StringBuilder.appendSodaLyricLine(startTime: Int, content: String) {
        append(
            "[%02d:%02d.%02d]%s\n".format(
                Locale.ROOT,
                startTime / 60000,
                (startTime % 60000) / 1000,
                (startTime % 1000) / 10,
                content,
            )
        )
    }

    /**
     * 汽水的 `translations` 是 `{"cn":"[00:17.66]译文…"}` 这样的标准 LRC。
     * 每行译文贴到对应的原文后面（原文索引 → 译文），歌词解析端才会把同一时间戳的两行
     * 并成「原文 + 翻译」；只取中文，其他语言（罗马音之类）保持原样不显示。
     */
    private fun sodaTranslationLines(translation: String, originals: List<Pair<Int, String>>): Map<Int, List<String>> {
        if (translation.isBlank() || originals.isEmpty()) return emptyMap()
        val parsed = mutableListOf<Pair<Int, String>>()
        translation.split("\n").forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val match = SODA_LYRIC_TRANSLATION_LINE.find(line) ?: return@forEach
            val text = match.groupValues[4].trim()
            if (text.isEmpty()) return@forEach
            val time = sodaLrcTimeMs(match) ?: return@forEach
            parsed += time to text
        }
        if (parsed.isEmpty()) return emptyMap()
        // 译文和原文行数一致时按行号对齐（有些逐字歌词的时间戳和译文差好几秒，按时间配会配错）；
        // 行数不一致（间奏、纯音乐段落之类）才退回按时间找最近的一句
        val out = mutableMapOf<Int, MutableList<String>>()
        parsed.forEachIndexed { lineIndex, (time, text) ->
            val index = if (parsed.size == originals.size) {
                lineIndex
            } else {
                originals.indices.minByOrNull { abs(originals[it].first - time) }
            } ?: return@forEachIndexed
            out.getOrPut(index) { mutableListOf() } += text
        }
        return out
    }

    private fun sodaLrcTimeMs(match: MatchResult): Int? {
        val minutes = match.groupValues[1].toIntOrNull() ?: return null
        val seconds = match.groupValues[2].toIntOrNull() ?: return null
        val millis = when (match.groupValues[3].length) {
            1 -> match.groupValues[3].toIntOrNull()?.times(100) ?: 0
            2 -> match.groupValues[3].toIntOrNull()?.times(10) ?: 0
            3 -> match.groupValues[3].toIntOrNull() ?: 0
            else -> 0
        }
        return (minutes * 60_000 + seconds * 1_000 + millis).coerceAtLeast(0)
    }

    /** 取汽水自带的中文翻译；键是语言码，中文歌没有这个字段。 */
    private fun sodaChineseTranslation(lyric: SodaLyric): String =
        (lyric.translations as? JsonObject)?.entries
            ?.firstOrNull {
                it.key.equals("cn", ignoreCase = true) || it.key.startsWith("zh", ignoreCase = true)
            }
            ?.value?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()

    /** 汽水逐字载荷 `<词起,词时[,0]>词` 转 `<mm:ss.SSS>` 增强 LRC；没有词标签时退回剥掉标签的纯文本。 */
    private fun sodaVerbatimContent(content: String, lineStartMs: Int): String {
        val matches = SODA_LYRIC_WORD_TAG.findAll(content).toList()
        if (matches.isEmpty()) return SODA_LYRIC_WORD.replace(content, "")
        val builder = StringBuilder()
        for (match in matches) {
            val wordStart = match.groupValues[1].toIntOrNull() ?: 0
            val duration = match.groupValues[2].toIntOrNull() ?: 0
            val wordText = match.groupValues[3]
            if (wordText.isEmpty()) continue
            // 与酷我一致：早于行首的时间按相对行首处理
            val start = if (wordStart >= lineStartMs) wordStart else lineStartMs + wordStart
            builder.append('<').append(formatSodaWordTime(start)).append('>').append(wordText)
            if (duration > 0) builder.append("</").append(formatSodaWordTime(start + duration)).append('>')
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

    internal fun queryEscape(value: String): String {
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
        /** 诊断日志在 AppLog 里的 tag；导出日志时按它筛汽水相关条目。 */
        const val TAG = "Soda"

        /** Cookie 字段名列表（不含值）：登录态是否完整，看有没有 sessionid/sid_guard 就够了。 */
        fun cookieFieldNames(cookie: String): String {
            if (cookie.isBlank()) return "无"
            val names = cookie.split(';')
                .mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
                .distinct()
            return "有(${names.joinToString(",")})"
        }

        /**
         * 内置的 SodaM 公共签名服务：[SodaM](https://github.com/sodahub-org/sodam) 作者提供的默认实例，
         * 桌面版默认就连这一个。用户自己填了地址和 Token 就以填的为准。
         */
        const val DEFAULT_SIGNER_URL = "http://103.236.75.83:8899/sign"
        const val DEFAULT_SIGNER_TOKEN = "b356ad402bc31ee9e5a99cfdbf41eaa39961a562251c3359"

        /**
         * 「天天送会员」接口用的 UA：服务端按它判断客户端形态，和桌面版同款即可，
         * 这两个接口（commerce_info / apply_free_vip）不要求应用级签名。
         */
        const val WELFARE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"

        /** 天天送会员接口的公共查询参数；device_id / iid / fp 每次现生成，接口不校验唯一性。 */
        fun welfareAppParams(): String {
            val now = System.currentTimeMillis()
            val deviceId = now.toString()
            val iid = (now + 1).toString()
            AppLog.d(TAG, "福利接口参数 device_id=$deviceId iid=$iid region=cn tz=Asia/Shanghai")
            return welfareQuery(
                mapOf(
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
                ),
            )
        }

        private fun welfareQuery(params: Map<String, String>): String =
            params.entries.sortedBy { it.key }
                .joinToString("&") { (key, value) -> "${welfareEscape(key)}=${welfareEscape(value)}" }

        private fun welfareEscape(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8")

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
            AppLog.i(
                TAG,
                "签名器配置更新 enabled=$enabled url=${nextUrl.ifBlank { DEFAULT_SIGNER_URL }} " +
                    "token=${if ((nextToken.ifBlank { DEFAULT_SIGNER_TOKEN }).isNotBlank()) "已设置" else "空"}",
            )
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

        /**
         * 落盘的设备标识：签名器不给 /config 时用它兜底，跨启动保持同一个值，
         * 播放上报（埋点 / 播放计数 / 推荐口味的 played_media）才攒得起历史。
         */
        @Volatile
        private var fallbackDeviceId = ""

        fun configureFallbackDeviceId(deviceId: String) {
            val next = deviceId.trim()
            if (next == fallbackDeviceId) return
            fallbackDeviceId = next
            // 指纹变了，签名器缓存的那份作废
            signerCredentials = null
        }

        /**
         * 扫码登录的护照设备身份（`device_id` / `install_id`），由设置里落盘的那一份注入。
         * 所有二维码共用它：汽水按 `device_id` 做风控，持续使用同一个身份才会补发
         * `odin_tt`，每次换新设备会被当成新设备（`error_code=7`，会话也短命）。
         * 见 libresoda `docs/QR-LOGIN.md` 与 sodam config 的 `login_device_id`。
         */
        @Volatile private var loginDeviceId = ""
        @Volatile private var loginInstallId = ""

        fun configureLoginDevice(deviceId: String, installId: String) {
            val nextId = deviceId.trim()
            val nextInstall = installId.trim()
            if (nextId == loginDeviceId && nextInstall == loginInstallId) return
            loginDeviceId = nextId
            loginInstallId = nextInstall
            AppLog.i(TAG, "登录设备身份更新 device_id=$nextId install_id=$nextInstall")
        }

        /** 生效的登录设备身份；设置还没落盘时当场生成一份合法的（首位非 0）。 */
        private fun loginDeviceOrGenerate(): Pair<String, String> {
            val id = loginDeviceId
            val install = loginInstallId
            if (SodaQrLogin.deviceIdentityValid(id, install)) return id to install
            val generated = SodaQrLogin.newLoginDevice()
            loginDeviceId = generated.first
            loginInstallId = generated.second
            return generated
        }

        private const val SODA_SOURCE = "soda"
        /** 平台 id，和 [SODA_INFO] 的 id 一致；播放上报按它路由。 */
        const val SODA_PROVIDER_ID = "soda"

        /**
         * 登录态失效的文案标记：接口错误带上它，上层（设置页账号卡、播放失败原因）
         * 就能提示重新扫码，而不是笼统一句「没有返回播放地址」。
         */
        const val SODA_LOGIN_EXPIRED_MARK = "汽水登录已过期，请重新扫码登录"
        const val SODA_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val PC_APP_USER_AGENT = "LunaPC/3.3.0(359450208)"

        /**
         * PC 综合搜索单独用 3.7.0 的客户端指纹：服务端按它校验这条新链路，
         * 和其余 PC 接口的 3.3.0 不是同一套（对齐 libresoda `fetch_pc_search_all`）。
         */
        const val PC_SEARCH_USER_AGENT = "LunaPC/3.7.0(452316191)"
        const val PC_SEARCH_VERSION_NAME = "3.7.0"
        const val PC_SEARCH_VERSION_CODE = "30070000"
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

        /** 最近播放的读 / 删（libresoda `playback.rs`）。 */
        const val SODA_RECENTLY_PLAYED_PATH = "/luna/pc/me/recently-played-media"
        /** 播放统计 / 上报，参数透传。 */
        const val SODA_MEDIA_STATS_PATH = "/luna/pc/media-stats"
        /** 「音乐墙」：总听歌时长 / 本周听歌时长。 */
        const val SODA_MUSIC_WALL_PATH = "/luna/me/music_wall"
        /** 搜索联想词。 */
        const val SODA_SUG_PATH = "/luna/pc/sug"
        /** 热搜词，路径后面还要拼类型（`default` 是客户端默认档）。 */
        const val SODA_SUGGEST_WORDS_PATH = "/luna/suggest-words"
        /** 推荐歌单。 */
        const val SODA_RECOMMEND_PLAYLIST_PATH = "/luna/me/playlist/recommend"
        /** 「我创建的歌单」：官方客户端侧边栏用的就是它，也是判断歌单能否修改的依据。 */
        const val SODA_MY_PLAYLISTS_PATH = "/luna/pc/me/playlist"
        /** PC 专辑详情：JSON 直出，比抓分享页 HTML 稳。 */
        const val SODA_PC_ALBUM_PATH = "/luna/pc/albums"
        /** 播放前的业务校验：版权状态 + 风控结果。 */
        const val SODA_MCHECK_PATH = "/luna/pc/mcheck"
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

        /** 解密写盘的缓冲：256KB，逐样本明文不整份驻留内存。 */
        private const val DECRYPT_BUFFER_BYTES = 256 * 1024
    }
}

// -------------------------------------------------------------------------
// Constants and static catalog data
// -------------------------------------------------------------------------

private val SODA_JSON = NativeHttp.json

/**
 * 汽水登录态失效（业务码 7/401/403，或 HTTP 401/403）。扫码会话几天后就会过期，
 * 之后接口就是这么回的。设置页与播放失败原因按**类型**识别它，提示重新扫码；
 * 对齐 sodam `failure.rs` 的 `RecoveryAction::Relogin` 判定。
 */
internal class SodaLoginExpiredException(message: String) : IllegalStateException(message)

/**
 * 汽水业务码里的「登录态失效」：`7` / `401` / `403`（对齐 sodam `failure.rs` 的
 * `RecoveryAction::Relogin` 判定——业务码语义上游同样没实测过，但扫码登录的会话
 * 过期后接口就是这么回的）。命中时错误文案带「登录已过期」，上层据此提示重新扫码，
 * 不然用户只看到一句「没有返回播放地址」，不知道该去重新登录。
 */
private fun sodaLoginExpiredCode(statusCode: Long): Boolean =
    statusCode == 7L || statusCode == 401L || statusCode == 403L

/** HTTP 401 同样是登录态失效（NativeHttp 的非 2xx 是 `IOException("HTTP 401 ...")`）。 */
private fun sodaLoginExpiredHttp(message: String): Boolean =
    message.contains("HTTP 401") || message.contains("HTTP 403")

/**
 * 接口报错时统一过一遍登录态判定：失效就把人话提到前面，技术原文留给日志。
 * 返回的异常直接抛；失效时是 [SodaLoginExpiredException]，上层可以按类型判断，
 * 不用在每个调用点匹配异常文案。
 */
private fun sodaApiError(tag: String, statusCode: Long, statusMsg: String): IllegalStateException {
    val detail = "soda $tag api error: status_code=$statusCode status_msg=$statusMsg"
    return if (sodaLoginExpiredCode(statusCode)) {
        SodaLoginExpiredException("${SodaProvider.SODA_LOGIN_EXPIRED_MARK}（$detail）")
    } else {
        IllegalStateException(detail)
    }
}

/** 探测账号音质用的会员曲（music-lib soda/quality.go 同款）。 */
private const val SODA_QUALITY_PROBE_TRACK_ID = "7304719759323564095"

/**
 * 汽水的可选音质档，从高到低。标签/格式/码率/VIP 标记照抄 music-lib 的 model.AudioQualityOption。
 * 汽水不一定严格按档位返回，实际档位由 [sodaDownloadInfoQuality] 按 ext+码率推断。
 */
internal val SODA_QUALITY_CANDIDATES: List<AudioQualityOption> = listOf(
    AudioQualityOption(AudioQuality.HiRes, "Hi-Res 无损", "flac", vip = true),
    AudioQualityOption(AudioQuality.Lossless, "无损音质", "flac", vip = true),
    AudioQualityOption(AudioQuality.Spatial, "空间音频", "m4a", vip = true),
    AudioQualityOption(AudioQuality.High, "高品质 320k", "m4a", 320),
    AudioQualityOption(AudioQuality.Higher, "较高品质 128k", "m4a", 128),
    AudioQualityOption(AudioQuality.Standard, "标准音质 64k", "m4a", 64),
    AudioQualityOption(AudioQuality.Preview, "试听片段", "m4a"),
)

internal val SODA_QUALITY_LADDER: AudioQualityLadder = AudioQualityLadder.of(SODA_QUALITY_CANDIDATES)

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
/** 翻译是标准 LRC：`[mm:ss.cc]译文`，时间可省略、百分位一到三位。 */
private val SODA_LYRIC_TRANSLATION_LINE = Regex("""^\[(\d+):(\d+)(?:[.:](\d{1,3}))?](.*)$""")

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
internal data class SodaArtist(
    val id: String = "",
    val name: String = "",
)

@Serializable
internal data class SodaImage(
    val urls: List<String> = emptyList(),
    val uri: String = "",
    @SerialName("template_prefix") val templatePrefix: String = "",
)

@Serializable
internal data class SodaBitRate(
    val br: Int = 0,
    val quality: String = "",
    val size: Long = 0,
)

@Serializable
internal data class SodaTrackPlayInfo(
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
internal data class SodaTrackAudioInfo(
    @SerialName("play_info_list") val playInfoList: List<SodaTrackPlayInfo> = emptyList(),
)

@Serializable
internal data class SodaAlbum(
    val id: String = "",
    val name: String = "",
    @SerialName("url_cover") val urlCover: SodaImage = SodaImage(),
)

@Serializable
internal data class SodaPreview(
    val vid: String = "",
    val start: Int = 0,
    val duration: Int = 0,
    @SerialName("bit_rates") val bitRates: List<SodaBitRate> = emptyList(),
)

@Serializable
internal data class SodaQualityBenefit(
    val condition: String = "",
    @SerialName("need_vip") val needVip: Boolean = false,
    @SerialName("need_purchase") val needPurchase: Boolean = false,
)

@Serializable
internal data class SodaQualityPolicy(
    @SerialName("play_detail") val playDetail: SodaQualityBenefit? = null,
    @SerialName("download_detail") val downloadDetail: SodaQualityBenefit? = null,
)

@Serializable
internal data class SodaLabelInfo(
    @SerialName("only_vip_download") val onlyVipDownload: Boolean = false,
    @SerialName("only_vip_playable") val onlyVipPlayable: Boolean = false,
    @SerialName("quality_only_vip_can_download") val qualityOnlyVipCanDownload: List<String> = emptyList(),
    @SerialName("quality_only_vip_can_play") val qualityOnlyVipCanPlay: List<String> = emptyList(),
    @SerialName("quality_map") val qualityMap: Map<String, SodaQualityPolicy> = emptyMap(),
)

@Serializable
internal data class SodaTrack(
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
private data class SodaLyric(
    val content: String = "",
    /**
     * 外国歌的中文翻译：`{"cn":"[00:17.66]译文…"}`，中文歌整个字段都不返回。
     * 用 JsonElement 收着而不是 Map<String, String>：万一哪天某个语言的值换了形状，
     * 反序列化失败会把整首歌的歌词一起带没，宁可这里降级成没有翻译。
     */
    @SerialName("translations") val translations: JsonElement = JsonNull,
)

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
    /**
     * 账号自身的会员标记。优先看这两个字段而不是「探测曲目能不能拿整曲」：
     * 后者会把 SVIP 误判成非会员（对齐 libresoda `account.rs::is_vip_account`）。
     */
    @SerialName("is_vip") val isVip: Boolean = false,
    @SerialName("vip_stage") val vipStage: String = "",
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
