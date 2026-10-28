package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.QRCheckResult
import com.mytv.mymusic.data.QRCreateResult
import com.mytv.mymusic.data.QRSession
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val NETEASE_INFO = ProviderInfo(
    id = "netease",
    name = "网易云音乐",
    search = true,
    download = true,
    lyrics = true,
    parseSong = true,
    searchPlaylist = true,
    recommendedPlaylists = true,
    playlistSongs = true,
    parsePlaylist = true,
    searchAlbum = true,
    albumSongs = true,
    parseAlbum = true,
    userPlaylists = true,
    qrLogin = true,
    playlistCategories = true,
    topLists = true,
    topListSongs = true,
    hotSongs = true,
)

/** 分享文本里的链接；取到后去掉紧跟在链接后的中文/英文标点。 */
private val NETEASE_URL_PATTERN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val NETEASE_URL_TRAILING = charArrayOf(
    ')', '）', ']', '】', '》', '」', '』', '"', '\'', ',', '，', '。', '、', ';', '；', '!', '！', '?', '？',
)

class NetEaseProvider : BaseNativeProvider(NETEASE_INFO) {
    private val json = NativeHttp.json
    private val urlCache = ConcurrentHashMap<String, CachedUrl>()
    private val shortLinkCache = ConcurrentHashMap<String, String>()

    override val supportsSearchPagination: Boolean = true

    override suspend fun searchSongsPage(cookie: String, keyword: String, page: Int, limit: Int): SongSearchPage {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 50)
        val response = cloudSearch(cookie, keyword, 1, safeLimit, (safePage - 1) * safeLimit)
        val root = json.decodeFromString<NeteaseSearchResponse>(response.body)
        val songs = root.result?.songs.orEmpty().map(::songOf)
        return SongSearchPage(songs, safePage, songs.size >= safeLimit)
    }

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val response = cloudSearch(cookie, keyword, 1, 10)
        val root = json.decodeFromString<NeteaseSearchResponse>(response.body)
        return root.result?.songs.orEmpty().map(::songOf)
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val parsed = parseNeteaseLink(resolveNeteaseLink(link)) ?: throw IllegalArgumentException("invalid netease link")
        if (parsed.first != NeteaseLinkKind.Song) throw IllegalArgumentException("invalid netease song link")
        val songs = fetchSongsBatch(cookie, listOf(parsed.second))
        val song = songs.firstOrNull() ?: throw IllegalStateException("netease song not found")
        val url = runCatching { downloadUrl(cookie, song) }.getOrNull().orEmpty()
        return if (url.isNotBlank()) song.copy(url = url) else song
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.source != "netease") throw IllegalArgumentException("source mismatch")
        val songId = song.extra["song_id"]?.takeIf { it.isNotBlank() } ?: song.id
        val levels = preferredLevels(song)
        val cacheKey = "$songId:${levels.joinToString(",")}:${NativeCrypto.md5Hex(cookie)}"
        urlCache[cacheKey]?.let { cached ->
            if (cached.expiresAt > System.currentTimeMillis()) return cached.url
            urlCache.remove(cacheKey)
        }

        // eapi v1 对账号没有权限的档位会自动回退到可用档：登录的免费歌能拿到 hires，VIP 直接到母带。
        // 未登录不试 eapi——匿名请求只会回落到 128k，比 weapi 的 320k 差。
        if (cookie.isNotBlank()) {
            for (level in levels) {
                val eapi = runCatching { getEapiDownloadUrl(cookie, songId, level) }.getOrNull()
                if (!eapi.isNullOrBlank()) {
                    urlCache[cacheKey] = CachedUrl(eapi, System.currentTimeMillis() + CACHE_TTL_MS)
                    return eapi
                }
            }
        }

        for (br in listOf(320000, 128000)) {
            val body = buildJsonObject {
                put("ids", buildJsonArray { add(JsonPrimitive(songId)) }.toString())
                put("br", br)
            }
            val response = weapiPost(DOWNLOAD_API, body, cookie)
            val url = runCatching { json.decodeFromString<NeteaseDownloadResponse>(response.body) }
                .getOrNull()?.data?.firstOrNull()?.url.orEmpty()
            if (url.isNotBlank()) {
                urlCache[cacheKey] = CachedUrl(url, System.currentTimeMillis() + CACHE_TTL_MS)
                return url
            }
        }
        throw IllegalStateException("download url not found (might be vip or copyright restricted)")
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != "netease") throw IllegalArgumentException("source mismatch")
        val songId = song.extra["song_id"]?.takeIf { it.isNotBlank() } ?: song.id
        val body = buildJsonObject {
            put("csrf_token", "")
            put("id", songId)
            put("lv", -1)
            put("tv", -1)
            put("rv", -1)
            put("yv", -1)
        }
        val response = weapiPost(LYRIC_API, body, cookie)
        val data = json.decodeFromString<NeteaseLyricResponse>(response.body)
        if (data.code != 0 && data.code != 200) throw IllegalStateException("netease lyric api error: ${data.code}")
        // 优先逐字 YRC，转换不出有效行再退回普通 LRC
        val yrc = convertYrcToLrc(data.yrc.lyric)
        if (yrc.isNotBlank()) return yrc
        val lrc = data.lrc.lyric.trim()
        if (lrc.isNotBlank()) return lrc
        val translated = data.tlyric.lyric.trim()
        if (translated.isNotBlank()) return translated
        throw IllegalStateException("lyric is empty or not found")
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val response = cloudSearch(cookie, keyword, 1000, 10)
        val root = json.decodeFromString<NeteaseSearchResponse>(response.body)
        return root.result?.playlists.orEmpty().map { item ->
            Playlist(
                id = item.id.toString(),
                name = item.name,
                cover = item.coverImgUrl,
                trackCount = item.trackCount,
                playCount = item.playCount.toLong(),
                creator = item.creator.nickname,
                description = item.description,
                source = "netease",
                link = "https://music.163.com/#/playlist?id=${item.id}",
            )
        }
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> =
        fetchPlaylistDetailLimited(cookie, id, 0).second

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val parsed = parseNeteaseLink(resolveNeteaseLink(link)) ?: throw IllegalArgumentException("invalid netease link")
        if (parsed.first != NeteaseLinkKind.Playlist) throw IllegalArgumentException("invalid netease playlist link")
        val (playlist, songs) = fetchPlaylistDetailLimited(cookie, parsed.second, 0)
        return CollectionResult(playlist, songs)
    }

    override suspend fun recommendedPlaylists(cookie: String): List<Playlist> {
        val body = buildJsonObject {
            put("limit", 30)
            put("total", true)
            put("n", 1000)
        }
        val response = weapiPost(RECOMMENDED_API, body, cookie, sendCookie = false)
        val data = json.decodeFromString<NeteaseRecommendedResponse>(response.body)
        if (data.code != 200) throw IllegalStateException("netease api error code: ${data.code}")
        return data.result.map { item ->
            Playlist(
                id = item.id.toString(),
                name = item.name,
                cover = item.picUrl,
                trackCount = item.trackCount,
                playCount = item.playCount.toLong(),
                creator = item.copywriter.ifBlank { "网易云推荐" },
                description = item.copywriter,
                source = "netease",
                link = "https://music.163.com/#/playlist?id=${item.id}",
                extra = if (item.alg.isBlank()) emptyMap() else mapOf("alg" to item.alg),
            )
        }
    }

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val response = cloudSearch(cookie, keyword, 10, 10)
        val root = json.decodeFromString<NeteaseSearchResponse>(response.body)
        return root.result?.albums.orEmpty().map { item ->
            val artist = item.artist.name.ifBlank { item.artists.joinToString(", ") { it.name } }
            Playlist(
                id = item.id.toString(),
                name = item.name,
                cover = item.picUrl,
                trackCount = item.size,
                creator = artist,
                description = item.description.ifBlank { item.briefDesc },
                source = "netease",
                link = "https://music.163.com/#/album?id=${item.id}",
                extra = mapOf(
                    "type" to "album",
                    "company" to item.company,
                    "publish_time" to item.publishTime.toString(),
                ),
            )
        }
    }

    override suspend fun albumSongs(cookie: String, id: String): List<Song> =
        fetchAlbumDetail(cookie, id).second

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult {
        val parsed = parseNeteaseLink(resolveNeteaseLink(link)) ?: throw IllegalArgumentException("invalid netease link")
        if (parsed.first != NeteaseLinkKind.Album) throw IllegalArgumentException("invalid netease album link")
        val (playlist, songs) = fetchAlbumDetail(cookie, parsed.second)
        return CollectionResult(playlist, songs)
    }

    override suspend fun categories(cookie: String): List<PlaylistCategory> {
        val body = buildJsonObject { put("csrf_token", "") }
        val response = weapiPost(CATEGORY_API, body, cookie, sendCookie = false)
        val data = json.decodeFromString<NeteaseCategoryResponse>(response.body)
        if (data.code != 200) throw IllegalStateException("netease api error code: ${data.code}")
        val result = mutableListOf(
            PlaylistCategory(
                id = "",
                name = "全部",
                group = "全部",
                source = "netease",
                count = data.all.resourceCount,
                hot = data.all.hot,
            )
        )
        data.sub.forEach { item ->
            if (item.name.isBlank()) return@forEach
            result += PlaylistCategory(
                id = item.name,
                name = item.name,
                group = data.categories[item.category.toString()].orEmpty(),
                source = "netease",
                count = item.resourceCount,
                hot = item.hot,
            )
        }
        return result
    }

    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        val category = id.ifBlank { "全部" }
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 100)
        val body = buildJsonObject {
            put("cat", category)
            put("order", "hot")
            put("limit", safeLimit)
            put("offset", (safePage - 1) * safeLimit)
            put("total", safePage == 1)
            put("csrf_token", "")
        }
        val response = weapiPost(CATEGORY_PLAYLIST_API, body, cookie, sendCookie = false)
        val data = json.decodeFromString<NeteasePlaylistListResponse>(response.body)
        if (data.code != 200) throw IllegalStateException("netease api error code: ${data.code}")
        return data.playlists.map { item ->
            Playlist(
                id = item.id.toString(),
                name = item.name,
                cover = item.coverImgUrl,
                trackCount = item.trackCount,
                playCount = item.playCount.toLong(),
                creator = item.creator.nickname,
                description = item.description,
                source = "netease",
                link = "https://music.163.com/#/playlist?id=${item.id}",
                extra = mapOf("category_id" to category),
            )
        }
    }

    override suspend fun userPlaylists(cookie: String, page: Int, limit: Int): List<Playlist> {
        if (cookie.isBlank()) throw IllegalStateException("netease user playlists require cookie")
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 100)
        val accountBody = buildJsonObject { put("csrf_token", "") }
        val accountResponse = weapiPost(USER_ACCOUNT_API, accountBody, cookie)
        val account = json.parseToJsonElement(accountResponse.body).jsonObject
        val accountCode = account.int("code")
        val profile = account["profile"]?.jsonObject
        val userId = profile?.long("userId") ?: 0L
        if (accountCode != 200 || userId == 0L) throw IllegalStateException("netease account api error code: $accountCode")

        val body = buildJsonObject {
            put("uid", userId)
            put("limit", safeLimit)
            put("offset", (safePage - 1) * safeLimit)
            put("includeVideo", true)
            put("csrf_token", "")
        }
        val response = weapiPost(USER_PLAYLIST_API, body, cookie)
        val data = json.decodeFromString<NeteaseUserPlaylistResponse>(response.body)
        if (data.code != 200) throw IllegalStateException("netease user playlist api error code: ${data.code}")
        return data.playlist.map { item ->
            Playlist(
                id = item.id.toString(),
                name = item.name,
                cover = item.coverImgUrl,
                trackCount = item.trackCount,
                playCount = item.playCount.toLong(),
                creator = item.creator.nickname.ifBlank { profile?.string("nickname").orEmpty() },
                description = item.description,
                source = "netease",
                link = "https://music.163.com/#/playlist?id=${item.id}",
                extra = mapOf(
                    "user_id" to userId.toString(),
                    "subscribed" to item.subscribed.toString(),
                ),
            )
        }
    }

    override suspend fun topLists(cookie: String): List<TopList> {
        val response = NativeHttp.postForm(
            TOP_LIST_API,
            emptyMap(),
            headers = mapOf("Referer" to REFERER),
            cookie = cookie,
            randomIp = true,
        )
        val data = json.decodeFromString<NeteaseTopListResponse>(response.body)
        if (data.code != 200) throw IllegalStateException("netease api error code: ${data.code}")
        return data.list.mapNotNull { item ->
            if (item.id <= 0 || item.name.isBlank()) return@mapNotNull null
            val id = item.id.toString()
            val extra = buildMap {
                if (item.toplistType.isNotBlank()) put("toplist_type", JsonPrimitive(item.toplistType))
                if (item.trackUpdateTime > 0) put("update_time", JsonPrimitive(item.trackUpdateTime.toString()))
            }
            TopList(
                id = id,
                name = item.name.trim(),
                cover = item.coverImgUrl,
                description = item.description,
                updateFrequency = item.updateFrequency,
                trackCount = item.trackCount,
                playCount = item.playCount,
                source = "netease",
                link = "https://music.163.com/#/discover/toplist?id=$id",
                extra = extra,
            )
        }
    }

    override suspend fun topListSongs(cookie: String, id: String): List<Song> =
        fetchTopListSongs(cookie, id, 0)

    override suspend fun hotSongs(cookie: String, limit: Int): List<Song> {
        val safeLimit = when {
            limit <= 0 -> 100
            limit > 200 -> 200
            else -> limit
        }
        return fetchTopListSongs(cookie, HOT_SONG_TOP_LIST_ID, safeLimit)
    }

    override suspend fun createLogin(type: String): QRCreateResult {
        val response = NativeHttp.postForm(
            QR_KEY_API,
            mapOf("type" to "3"),
            headers = mapOf("Referer" to REFERER),
            randomIp = true,
            userAgent = QR_USER_AGENT,
        )
        val data = json.parseToJsonElement(response.body).jsonObject
        val code = data.int("code")
        val unikey = data.string("unikey")
        if (code != 200 || unikey.isBlank()) throw IllegalStateException("netease qr key api error: code=$code")
        return QRCreateResult(
            sessionId = NativeProviders.newSessionId(info.id),
            session = QRSession(
                key = unikey,
                url = "https://music.163.com/login?codekey=${URLEncoder.encode(unikey, "UTF-8")}",
                imageUrl = "",
            ),
        )
    }

    override suspend fun checkLogin(sessionId: String, key: String): QRCheckResult {
        if (key.isBlank()) throw IllegalArgumentException("netease qr login key is empty")
        val response = NativeHttp.postForm(
            QR_CHECK_API,
            mapOf("key" to key, "type" to "3"),
            headers = mapOf("Referer" to REFERER),
            randomIp = true,
            userAgent = QR_USER_AGENT,
        )
        val data = json.parseToJsonElement(response.body).jsonObject
        val code = data.int("code")
        val status = when (code) {
            800 -> "expired"
            801 -> "waiting"
            802 -> "scanned"
            803 -> "success"
            else -> "failed"
        }
        val cookie = data.string("cookie").ifBlank { joinCookies(response.cookies) }
        return QRCheckResult(
            status = status,
            message = data.string("message"),
            cookie = if (status == "success") cookie else "",
        )
    }

    private suspend fun cloudSearch(
        cookie: String,
        keyword: String,
        type: Int,
        limit: Int,
        offset: Int = 0,
    ): NativeResponse {
        val eparams = buildJsonObject {
            put("method", "POST")
            put("url", "http://music.163.com/api/cloudsearch/pc")
            putJsonObject("params") {
                put("s", keyword)
                put("type", type)
                put("offset", offset)
                put("limit", limit)
            }
        }
        return NativeHttp.postForm(
            SEARCH_API,
            mapOf("eparams" to NativeCrypto.encryptLinux(eparams.toString())),
            headers = mapOf("Referer" to REFERER),
            cookie = cookie,
            randomIp = true,
        )
    }

    private suspend fun weapiPost(
        url: String,
        body: JsonObject,
        cookie: String,
        sendCookie: Boolean = true,
    ): NativeResponse {
        val (params, encSecKey) = NativeCrypto.encryptWeApi(body.toString())
        return NativeHttp.postForm(
            url,
            mapOf("params" to params, "encSecKey" to encSecKey),
            headers = mapOf("Referer" to REFERER),
            cookie = if (sendCookie) cookie else "",
            randomIp = true,
        )
    }

    override suspend fun accountInfo(cookie: String): AccountInfo? {
        if (cookie.isBlank()) return null
        val response = weapiPost(USER_ACCOUNT_API, buildJsonObject { put("csrf_token", "") }, cookie)
        val data = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull() ?: return null
        if (data.int("code") != 200) return null
        val profile = data["profile"]?.jsonObject ?: return null
        val nickname = profile.string("nickname")
        if (nickname.isBlank()) return null
        val detail = listOfNotNull(
            neteaseVipLabel(profile.int("vipType")),
            profile.int("level").takeIf { it > 0 }?.let { "Lv.$it" },
        ).joinToString(" · ")
        return AccountInfo(nickname = nickname, avatar = profile.string("avatarUrl"), detail = detail)
    }

    private suspend fun fetchPlaylistDetailLimited(
        cookie: String,
        playlistId: String,
        maxSongs: Int,
    ): Pair<Playlist, List<Song>> {
        val body = buildJsonObject {
            put("id", playlistId)
            put("n", 0)
            put("csrf_token", "")
        }
        val response = weapiPost(PLAYLIST_API, body, cookie)
        val data = json.decodeFromString<NeteasePlaylistResponse>(response.body)
        if (data.code != 200) throw IllegalStateException("netease api error code: ${data.code}")
        val detail = data.playlist ?: throw IllegalStateException("netease playlist not found")
        val playlist = Playlist(
            id = detail.id.toString(),
            name = detail.name,
            cover = detail.coverImgUrl,
            trackCount = detail.trackCount,
            playCount = detail.playCount,
            creator = detail.creator.nickname,
            description = detail.description,
            source = "netease",
            link = "https://music.163.com/#/playlist?id=${detail.id}",
        )
        var ids = detail.trackIds.map { it.id.toString() }
        if (maxSongs > 0 && ids.size > maxSongs) ids = ids.take(maxSongs)
        val songs = mutableListOf<Song>()
        ids.chunked(500).forEach { batch ->
            runCatching { fetchSongsBatch(cookie, batch) }.getOrDefault(emptyList()).let { songs += it }
        }
        return playlist to songs
    }

    private suspend fun fetchSongsBatch(cookie: String, songIds: List<String>): List<Song> {
        if (songIds.isEmpty()) return emptyList()
        val c = buildJsonArray {
            songIds.forEach { id -> add(buildJsonObject { put("id", id) }) }
        }.toString()
        val ids = buildJsonArray {
            songIds.forEach { id -> add(JsonPrimitive(id)) }
        }.toString()
        val body = buildJsonObject {
            put("c", c)
            put("ids", ids)
        }
        val response = weapiPost(DETAIL_API, body, cookie)
        val data = json.decodeFromString<NeteaseSongDetailResponse>(response.body)
        return data.songs.map { item ->
            Song(
                id = item.id.toString(),
                name = item.name,
                artist = item.ar.joinToString("、") { it.name },
                album = item.al.name,
                duration = item.dt / 1000,
                source = "netease",
                cover = item.al.picUrl,
                link = "https://music.163.com/#/song?id=${item.id}",
                extra = buildMap {
                    put("song_id", item.id.toString())
                    val level = item.privilege.maxbrLevel.ifBlank { item.privilege.plLevel }.trim().lowercase()
                    if (level.isNotBlank()) put("netease_level", level)
                },
            )
        }
    }

    private suspend fun fetchAlbumDetail(cookie: String, albumId: String): Pair<Playlist, List<Song>> {
        val body = buildJsonObject { put("csrf_token", "") }
        val response = weapiPost(ALBUM_API.format(albumId), body, cookie)
        val data = json.decodeFromString<NeteaseAlbumResponse>(response.body)
        if (data.code != 200) throw IllegalStateException("netease api error code: ${data.code}")
        val album = data.album ?: throw IllegalStateException("netease album not found")
        val artistName = album.artist.name.ifBlank { album.artists.joinToString(", ") { it.name } }
        val description = album.description.ifBlank { album.briefDesc }
        val playlist = Playlist(
            id = album.id.toString(),
            name = album.name,
            cover = album.picUrl,
            trackCount = album.size,
            creator = artistName,
            description = description,
            source = "netease",
            link = "https://music.163.com/#/album?id=${album.id}",
            extra = mapOf(
                "type" to "album",
                "company" to album.company,
                "publish_time" to album.publishTime.toString(),
            ),
        )
        val songs = data.songs.map { item ->
            val size = when {
                item.privilege.fl >= 320000 && item.h.size > 0 -> item.h.size
                item.privilege.fl >= 192000 && item.m.size > 0 -> item.m.size
                else -> item.l.size
            }
            val duration = item.dt / 1000
            val bitrate = if (duration > 0 && size > 0) (size * 8 / 1000 / duration).toInt() else 128
            Song(
                id = item.id.toString(),
                name = item.name,
                artist = item.ar.joinToString(", ") { it.name },
                album = item.al.name,
                albumId = item.al.id.toString(),
                duration = duration,
                size = size,
                bitrate = bitrate,
                source = "netease",
                cover = item.al.picUrl,
                link = "https://music.163.com/#/song?id=${item.id}",
                extra = mapOf(
                    "song_id" to item.id.toString(),
                    "album_id" to item.al.id.toString(),
                ),
            )
        }
        return playlist to songs
    }

    private suspend fun fetchTopListSongs(cookie: String, topListId: String, limit: Int): List<Song> {
        val id = topListId.trim()
        if (id.isBlank()) throw IllegalArgumentException("netease toplist id is empty")
        if (!id.all { it.isDigit() }) throw IllegalArgumentException("invalid netease toplist id: $id")
        val (_, songs) = fetchPlaylistDetailLimited(cookie, id, limit)
        return songs.mapIndexed { index, song ->
            song.copy(
                extra = song.extra + mapOf(
                    "toplist_id" to id,
                    "rank" to (index + 1).toString(),
                )
            )
        }
    }

    private suspend fun getEapiDownloadUrl(cookie: String, songId: String, quality: String): String {
        val id = songId.toIntOrNull() ?: throw IllegalArgumentException("invalid song id: $songId")
        val header = """{"os":"pc","appver":"","osver":"","deviceId":"pyncm!","requestId":"12345678"}"""
        val payload = buildJsonObject {
            put("ids", buildJsonArray { add(JsonPrimitive(id)) })
            put("level", quality)
            put("encodeType", "flac")
            put("header", header)
        }.toString()
        val params = NativeCrypto.encryptEApi(DOWNLOAD_EAPI, payload)
        val response = NativeHttp.postForm(
            DOWNLOAD_EAPI,
            mapOf("params" to params),
            headers = mapOf("Referer" to REFERER),
            cookie = cookie,
            randomIp = true,
        )
        val data = json.decodeFromString<NeteaseDownloadResponse>(response.body)
        val url = data.data.firstOrNull()?.url.orEmpty()
        if (url.isBlank()) throw IllegalStateException("eapi download url not found")
        return url
    }

    private fun songOf(item: NeteaseSearchSong): Song {
        val size = when {
            item.privilege.fl >= 320000 && item.h.size > 0 -> item.h.size
            item.privilege.fl >= 192000 && item.m.size > 0 -> item.m.size
            else -> item.l.size
        }
        val duration = item.dt / 1000
        val bitrate = if (duration > 0 && size > 0) (size * 8 / 1000 / duration).toInt() else 128
        return Song(
            id = item.id.toString(),
            name = item.name,
            artist = item.ar.joinToString("、") { it.name },
            album = item.al.name,
            duration = duration,
            size = size,
            bitrate = bitrate,
            source = "netease",
            cover = item.al.picUrl,
            link = "https://music.163.com/#/song?id=${item.id}",
            extra = buildMap {
                put("song_id", item.id.toString())
                val level = item.privilege.maxbrLevel.ifBlank { item.privilege.plLevel }.trim().lowercase()
                if (level.isNotBlank()) put("netease_level", level)
            },
            isVip = item.fee != 0 || item.privilege.fee != 0,
        )
    }

    private fun preferredLevels(song: Song): List<String> =
        neteaseLevelCandidates(song.extra["netease_level"] ?: song.extra["level"] ?: "")

    /** 分享文本里夹带链接时，取其中第一个链接；否则返回原链接。 */
    private fun linkUrl(link: String): String =
        NETEASE_URL_PATTERN.find(link)?.value?.trimEnd(*NETEASE_URL_TRAILING)?.takeIf { it.isNotBlank() }
            ?: link.trim()

    internal fun isNeteaseShortLink(link: String): Boolean {
        val host = runCatching { URI(linkUrl(link)).host }.getOrNull()?.lowercase(Locale.ROOT).orEmpty()
        return host == SHORT_LINK_HOST || host.endsWith(".$SHORT_LINK_HOST")
    }

    /**
     * 163cn.tv 分享短链要先跟随一次跳转才能拿到真实地址；结果缓存，一次打开链接只请求一次。
     * [fetchFinalUrl] 默认走网络，测试时注入假实现即可覆盖解析逻辑。
     */
    internal suspend fun resolveNeteaseLink(
        link: String,
        fetchFinalUrl: suspend (String) -> String = { NativeHttp.getResponse(it).finalUrl },
    ): String {
        val url = linkUrl(link)
        if (!isNeteaseShortLink(url)) return link
        shortLinkCache[url]?.let { return it }
        val finalUrl = runCatching { fetchFinalUrl(url) }.getOrNull().orEmpty()
        if (finalUrl.isBlank() || finalUrl == url) return link
        shortLinkCache[url] = finalUrl
        return finalUrl
    }

    internal fun parseNeteaseLink(link: String): Pair<NeteaseLinkKind, String>? {
        val candidates = mutableListOf<String>()
        // 分享文本（“分享单曲《…》: https://… （来自网易云音乐）”）里先取出其中的链接
        NETEASE_URL_PATTERN.find(link)?.value
            ?.trimEnd(*NETEASE_URL_TRAILING)
            ?.takeIf { it.isNotBlank() && it != link }
            ?.let(candidates::add)
        candidates += link
        runCatching {
            val uri = URI(link)
            val path = uri.path
            if (!path.isNullOrBlank() && path != "/") {
                candidates += path + (uri.rawQuery?.let { "?$it" } ?: "")
            }
            val fragment = uri.fragment?.trim()?.removePrefix("!")?.trim()
            if (!fragment.isNullOrBlank()) candidates += fragment
        }
        for (candidate in candidates) {
            val parsed = parseLinkCandidate(candidate)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseLinkCandidate(candidate: String): Pair<NeteaseLinkKind, String>? {
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val segments = uri.path.orEmpty().lowercase().split('/').filter { it.isNotBlank() }
        var kind = when {
            "song" in segments -> NeteaseLinkKind.Song
            "album" in segments -> NeteaseLinkKind.Album
            "playlist" in segments -> NeteaseLinkKind.Playlist
            else -> null
        }
        var id = queryParam(uri, "id")
        // 路径形式 /song/186016、/album/123、/playlist/456：末段就是 id，
        // 与 kind 是否已由路径关键字命中无关
        if (id.isBlank() && segments.size >= 2) {
            val last = segments.last()
            val byPath = when (segments[segments.size - 2]) {
                "song" -> NeteaseLinkKind.Song
                "album" -> NeteaseLinkKind.Album
                "playlist" -> NeteaseLinkKind.Playlist
                else -> null
            }
            if (byPath != null && last.all { it.isDigit() }) {
                kind = byPath
                id = last
            }
        }
        if (kind == null || id.isBlank() || !id.all { it.isDigit() }) return null
        return kind to id
    }

    private fun queryParam(uri: URI, name: String): String {
        val query = uri.rawQuery ?: return ""
        return query.split('&').firstNotNullOfOrNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@firstNotNullOfOrNull null
            val key = URLDecoder.decode(part.substring(0, index), "UTF-8")
            if (key != name) return@firstNotNullOfOrNull null
            URLDecoder.decode(part.substring(index + 1), "UTF-8")
        }.orEmpty()
    }

    private fun joinCookies(cookies: Map<String, String>): String =
        cookies.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}=${it.value}" }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.int(key: String): Int =
        this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

    private data class CachedUrl(val url: String, val expiresAt: Long)

    internal enum class NeteaseLinkKind { Song, Album, Playlist }

    private companion object {
        const val SHORT_LINK_HOST = "163cn.tv"
        const val REFERER = "http://music.163.com/"
        const val SEARCH_API = "http://music.163.com/api/linux/forward"
        const val DOWNLOAD_API = "http://music.163.com/weapi/song/enhance/player/url"
        const val DOWNLOAD_EAPI = "https://interface3.music.163.com/eapi/song/enhance/player/url/v1"
        const val DETAIL_API = "https://music.163.com/weapi/v3/song/detail"
        const val PLAYLIST_API = "https://music.163.com/weapi/v3/playlist/detail"
        const val CATEGORY_API = "https://music.163.com/weapi/playlist/catalogue"
        const val CATEGORY_PLAYLIST_API = "https://music.163.com/weapi/playlist/list"
        const val USER_PLAYLIST_API = "https://music.163.com/weapi/user/playlist"
        const val ALBUM_API = "https://music.163.com/weapi/v1/album/%s"
        const val USER_ACCOUNT_API = "https://music.163.com/weapi/nuser/account/get"
        const val RECOMMENDED_API = "https://music.163.com/weapi/personalized/playlist"
        const val LYRIC_API = "https://music.163.com/weapi/song/lyric"
        const val TOP_LIST_API = "https://music.163.com/api/toplist"
        const val HOT_SONG_TOP_LIST_ID = "3778678"
        const val QR_KEY_API = "https://interface.music.163.com/api/login/qrcode/unikey"
        const val QR_CHECK_API = "https://interface.music.163.com/api/login/qrcode/client/login"
        const val QR_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.0.18.203152"
        const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}

/** 取流档位从高到低：母带 > 沉浸环绕 > 高清环绕 > Hi-Res > 无损 > 极高。 */
internal val NETEASE_LEVEL_ORDER = listOf("jymaster", "sky", "jyeffect", "hires", "lossless", "exhigh")

/**
 * 取流尝试的档位序列：从歌曲标注的最高档开始往下最多两档，再以 exhigh 兜底。
 * v1 接口对没有权限的档位会自动回退到账号可用档；档位缺省时从母带试起，免费歌也能拿到 hires。
 */
internal fun neteaseLevelCandidates(songLevel: String): List<String> {
    val from = NETEASE_LEVEL_ORDER.indexOf(songLevel.trim().lowercase()).let { if (it >= 0) it else 0 }
    return (NETEASE_LEVEL_ORDER.subList(from, NETEASE_LEVEL_ORDER.size).take(2) + "exhigh").distinct()
}

internal fun neteaseVipLabel(vipType: Int): String? = when {
    vipType == 12 -> "黑胶SVIP"
    vipType == 11 -> "黑胶VIP"
    vipType > 0 -> "会员"
    else -> null
}

@Serializable
private data class NeteaseSearchResponse(val result: NeteaseSearchResult? = null)

@Serializable
private data class NeteaseSearchResult(
    val songs: List<NeteaseSearchSong> = emptyList(),
    val playlists: List<NeteaseSearchPlaylist> = emptyList(),
    val albums: List<NeteaseSearchAlbum> = emptyList(),
)

@Serializable
private data class NeteaseSearchSong(
    val id: Long = 0,
    val name: String = "",
    val ar: List<NeteaseArtist> = emptyList(),
    val al: NeteaseAlbum = NeteaseAlbum(),
    val dt: Int = 0,
    val fee: Int = 0,
    val privilege: NeteasePrivilege = NeteasePrivilege(),
    val h: NeteaseSize = NeteaseSize(),
    val m: NeteaseSize = NeteaseSize(),
    val l: NeteaseSize = NeteaseSize(),
)

@Serializable
private data class NeteaseArtist(val name: String = "")

@Serializable
private data class NeteaseAlbum(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String = "",
)

@Serializable
private data class NeteasePrivilege(
    val fl: Int = 0,
    val pl: Int = 0,
    val fee: Int = 0,
    val maxbrLevel: String = "",
    val plLevel: String = "",
)

@Serializable
private data class NeteaseSize(val size: Long = 0)

@Serializable
private data class NeteaseCreator(val nickname: String = "")

@Serializable
private data class NeteaseSearchPlaylist(
    val id: Long = 0,
    val name: String = "",
    val coverImgUrl: String = "",
    val creator: NeteaseCreator = NeteaseCreator(),
    val trackCount: Int = 0,
    val playCount: Double = 0.0,
    val description: String = "",
)

@Serializable
private data class NeteaseSearchAlbum(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String = "",
    val size: Int = 0,
    val company: String = "",
    val description: String = "",
    val briefDesc: String = "",
    val publishTime: Long = 0,
    val artist: NeteaseArtist = NeteaseArtist(),
    val artists: List<NeteaseArtist> = emptyList(),
)

@Serializable
private data class NeteaseSongDetailResponse(val songs: List<NeteaseDetailSong> = emptyList())

@Serializable
private data class NeteaseDetailSong(
    val id: Long = 0,
    val name: String = "",
    val ar: List<NeteaseArtist> = emptyList(),
    val al: NeteaseAlbum = NeteaseAlbum(),
    val dt: Int = 0,
    val privilege: NeteasePrivilege = NeteasePrivilege(),
)

@Serializable
private data class NeteasePlaylistResponse(val code: Int = 0, val playlist: NeteasePlaylistDetail? = null)

@Serializable
private data class NeteasePlaylistDetail(
    val id: Long = 0,
    val name: String = "",
    val coverImgUrl: String = "",
    val description: String = "",
    val playCount: Long = 0,
    val trackCount: Int = 0,
    val creator: NeteaseCreator = NeteaseCreator(),
    val trackIds: List<NeteaseTrackId> = emptyList(),
)

@Serializable
private data class NeteaseTrackId(val id: Long = 0)

@Serializable
private data class NeteasePlaylistListResponse(
    val code: Int = 0,
    val playlists: List<NeteaseSearchPlaylist> = emptyList(),
)

@Serializable
private data class NeteaseRecommendedResponse(
    val code: Int = 0,
    val result: List<NeteaseRecommended> = emptyList(),
)

@Serializable
private data class NeteaseRecommended(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String = "",
    val playCount: Double = 0.0,
    val trackCount: Int = 0,
    val copywriter: String = "",
    val alg: String = "",
)

@Serializable
private data class NeteaseCategoryResponse(
    val code: Int = 0,
    val categories: Map<String, String> = emptyMap(),
    val all: NeteaseCategoryAll = NeteaseCategoryAll(),
    val sub: List<NeteaseCategorySub> = emptyList(),
)

@Serializable
private data class NeteaseCategoryAll(
    val name: String = "",
    val hot: Boolean = false,
    val resourceCount: Int = 0,
)

@Serializable
private data class NeteaseCategorySub(
    val name: String = "",
    val category: Int = 0,
    val hot: Boolean = false,
    val resourceCount: Int = 0,
)

@Serializable
private data class NeteaseUserPlaylistResponse(
    val code: Int = 0,
    val playlist: List<NeteaseUserPlaylistItem> = emptyList(),
)

@Serializable
private data class NeteaseUserPlaylistItem(
    val id: Long = 0,
    val name: String = "",
    val coverImgUrl: String = "",
    val trackCount: Int = 0,
    val playCount: Double = 0.0,
    val description: String = "",
    val subscribed: Boolean = false,
    val creator: NeteaseCreator = NeteaseCreator(),
)

@Serializable
private data class NeteaseAlbumResponse(
    val code: Int = 0,
    val album: NeteaseAlbumDetail? = null,
    val songs: List<NeteaseAlbumSong> = emptyList(),
)

@Serializable
private data class NeteaseAlbumDetail(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String = "",
    val size: Int = 0,
    val company: String = "",
    val description: String = "",
    val briefDesc: String = "",
    val publishTime: Long = 0,
    val artist: NeteaseArtist = NeteaseArtist(),
    val artists: List<NeteaseArtist> = emptyList(),
)

@Serializable
private data class NeteaseAlbumSong(
    val id: Long = 0,
    val name: String = "",
    val ar: List<NeteaseArtist> = emptyList(),
    val al: NeteaseAlbum = NeteaseAlbum(),
    val dt: Int = 0,
    val fee: Int = 0,
    val privilege: NeteasePrivilege = NeteasePrivilege(),
    val h: NeteaseSize = NeteaseSize(),
    val m: NeteaseSize = NeteaseSize(),
    val l: NeteaseSize = NeteaseSize(),
)

@Serializable
private data class NeteaseTopListResponse(
    val code: Int = 0,
    val list: List<NeteaseTopList> = emptyList(),
)

@Serializable
private data class NeteaseTopList(
    val id: Long = 0,
    val name: String = "",
    val coverImgUrl: String = "",
    val description: String = "",
    val updateFrequency: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0,
    @SerialName("ToplistType") val toplistType: String = "",
    val trackUpdateTime: Long = 0,
)

@Serializable
private data class NeteaseDownloadResponse(val data: List<NeteaseDownloadData> = emptyList())

@Serializable
private data class NeteaseDownloadData(
    val url: String = "",
    val code: Int = 0,
    val br: Int = 0,
    val type: String = "",
)

// 网易逐字歌词（YRC）：行 `[行起毫秒,行时长毫秒]`，词 `(词起毫秒,词时长毫秒,0)词`，
// 时间轴均为绝对毫秒。行/词格式与 QQ QRC 同源，移植自 third_party/music-lib lyrics.ParseYRC。
private val NETEASE_YRC_LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
private val NETEASE_YRC_WORD = Regex("""(?:\[\d+,\d+])?\((\d+),(\d+)(?:,\d+)?\)([^(\[]*)""")

/** YRC 转成带 `<mm:ss.SSS>` 逐字标签的增强 LRC；没有可识别的行时返回空串，由调用方回退普通 LRC。 */
internal fun convertYrcToLrc(raw: String): String {
    if (raw.isBlank()) return ""
    val builder = StringBuilder()
    raw.split('\n').forEach { rawLine ->
        val line = rawLine.trim()
        val match = NETEASE_YRC_LINE.find(line) ?: return@forEach
        val start = match.groupValues[1].toIntOrNull() ?: 0
        val content = match.groupValues[3]
        builder.append('[').append(formatYrcTime(start)).append(']')
        var words = 0
        for (wordMatch in NETEASE_YRC_WORD.findAll(content)) {
            val wordStart = wordMatch.groupValues[1].toIntOrNull() ?: 0
            val text = wordMatch.groupValues[3]
            if (text.isEmpty()) continue
            builder.append('<').append(formatYrcTime(wordStart)).append('>').append(text)
            words++
        }
        if (words == 0 && content.isNotBlank()) builder.append(content.trim())
        builder.append('\n')
    }
    return builder.toString().trimEnd('\n')
}

private fun formatYrcTime(milliseconds: Int): String {
    val ms = milliseconds.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60000, (ms % 60000) / 1000, ms % 1000)
}

@Serializable
private data class NeteaseLyricResponse(
    val code: Int = 0,
    val lrc: NeteaseLyric = NeteaseLyric(),
    val yrc: NeteaseLyric = NeteaseLyric(),
    val tlyric: NeteaseLyric = NeteaseLyric(),
    val romalrc: NeteaseLyric = NeteaseLyric(),
)

@Serializable
private data class NeteaseLyric(val lyric: String = "")
