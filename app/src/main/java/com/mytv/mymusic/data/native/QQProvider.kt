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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import kotlin.random.Random

private val QQ_INFO = ProviderInfo(
    id = "qq",
    name = "QQ 音乐",
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

class QQProvider : BaseNativeProvider(QQ_INFO) {
    private val json = NativeHttp.json
    private val vipCache = ConcurrentHashMap<String, Boolean>()
    private val loginSessions = ConcurrentHashMap<String, QQLoginState>()

    override val supportsSearchPagination: Boolean = true

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> =
        searchSongsInternal(cookie, keyword, 1, 10)

    override suspend fun searchSongsPage(cookie: String, keyword: String, page: Int, limit: Int): SongSearchPage {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 50)
        val songs = searchSongsInternal(cookie, keyword, safePage, safeLimit)
        return SongSearchPage(songs, safePage, songs.size >= safeLimit)
    }

    private suspend fun searchSongsInternal(cookie: String, keyword: String, page: Int, limit: Int): List<Song> {
        val url = QQ_SEARCH_API + "?" + qqQuery(
            "w" to keyword,
            "format" to "json",
            "p" to page.toString(),
            "n" to limit.toString(),
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to QQ_SEARCH_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_USER_AGENT,
        )
        val response = json.decodeFromString<QQSearchResponse>(body)
        val isVip = isVipAccount(cookie)
        return response.data.song.list.mapNotNull { item ->
            if (!isVip && item.pay.payplay == 1) return@mapNotNull null
            songOfSearch(item)
        }
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        var songMid = Regex("songDetail/(\\w+)").find(link)?.groupValues?.getOrNull(1).orEmpty()
        if (songMid.isBlank()) {
            val uri = runCatching { URI(link) }.getOrNull()
            if (uri != null) {
                val songId = uriQueryValue(uri, "songid")
                if (songId.isNotEmpty()) {
                    val song = fetchSongDetailByID(cookie, songId)
                    val url = runCatching { downloadUrl(cookie, song) }.getOrNull().orEmpty()
                    return if (url.isNotBlank()) song.copy(url = url) else song
                }
                val mid = uriQueryValue(uri, "songmid")
                if (mid.isNotEmpty()) songMid = mid
            }
        }
        if (songMid.isBlank()) throw IllegalArgumentException("invalid qq music link")
        val song = fetchSongDetail(cookie, songMid)
        val url = runCatching { downloadUrl(cookie, song) }.getOrNull().orEmpty()
        return if (url.isNotBlank()) song.copy(url = url) else song
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.source != "qq") throw IllegalArgumentException("source mismatch")
        val songMid = song.extra["songmid"]?.takeIf { it.isNotBlank() } ?: song.id
        val isVip = isVipAccount(cookie)
        val prefixes: List<String>
        val extensions: List<String>
        if (isVip) {
            prefixes = listOf("AI00", "Q001", "Q000", "F000", "O801", "M800", "M500")
            extensions = listOf("flac", "flac", "flac", "flac", "ogg", "mp3", "mp3")
        } else {
            prefixes = listOf("M800", "M500")
            extensions = listOf("mp3", "mp3")
        }
        val filenames = prefixes.mapIndexed { index, prefix ->
            "$prefix$songMid$songMid.${extensions[index]}"
        }
        val body = buildJsonObject {
            putJsonObject("comm") {
                put("cv", 4747474)
                put("ct", 24)
                put("format", "json")
                put("inCharset", "utf-8")
                put("outCharset", "utf-8")
                put("notice", 0)
                put("platform", "yqq.json")
                put("needNewCode", 1)
                put("uin", 0)
            }
            putJsonObject("req_1") {
                put("module", "music.vkey.GetVkey")
                put("method", "UrlGetVkey")
                putJsonObject("param") {
                    put("guid", randomGuid())
                    putJsonArray("songmid") { filenames.forEach { add(JsonPrimitive(songMid)) } }
                    putJsonArray("songtype") { filenames.forEach { add(JsonPrimitive(0)) } }
                    put("uin", "0")
                    put("loginflag", 1)
                    put("platform", "20")
                    putJsonArray("filename") { filenames.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
        val response = NativeHttp.postJson(
            QQ_MUSICU_API,
            body.toString(),
            headers = mapOf("Referer" to QQ_DOWNLOAD_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_USER_AGENT,
        )
        val parsed = json.decodeFromString<QQVkeyResponse>(response.body)
        for (expected in filenames) {
            for (info in parsed.req1.data.midUrlInfo) {
                if (info.filename == expected && info.purl.isNotEmpty()) {
                    return "https://ws.stream.qqmusic.qq.com/" + info.purl
                }
            }
        }
        throw IllegalStateException("no valid download url found or vip required")
    }

    override suspend fun accountInfo(cookie: String): AccountInfo? {
        if (cookie.isBlank()) return null
        val uin = normalizeQqUin(cookie)
        if (uin.isBlank()) return null
        val musicKey = firstNonEmpty(cookieValue(cookie, "qm_keyst"), cookieValue(cookie, "qqmusic_key"))
        val loginType = firstNonEmpty(
            cookieValue(cookie, "tmeLoginType"),
            if (musicKey.startsWith("W_X")) "1" else "2",
        ).toIntOrNull() ?: 2
        val comm = buildJsonObject {
            put("ct", 11)
            put("cv", 14090008)
            put("v", 14090008)
            put("chid", "10003505")
            put("os_ver", "15")
            put("phonetype", "24122RKC7C")
            put("tmeAppID", "qqmusic")
            put("nettype", "NETWORK_WIFI")
            put("udid", "0")
            put("OpenUDID", "0")
            put("QIMEI36", "0")
            put("uin", uin)
            put("qq", uin)
            if (musicKey.isNotBlank()) put("authst", musicKey)
            put("tmeLoginType", loginType)
        }

        suspend fun request(module: String, method: String): JsonObject? {
            val body = buildJsonObject {
                put("comm", comm)
                putJsonObject("request") {
                    put("module", module)
                    put("method", method)
                    putJsonObject("param") {}
                }
            }
            val response = runCatching {
                NativeHttp.postJson(
                    QQ_MUSICU_API,
                    body.toString(),
                    headers = mapOf("Referer" to "https://y.qq.com"),
                    cookie = cookie,
                    randomIp = true,
                    userAgent = "QQMusic 14090008(android 15)",
                )
            }.getOrNull() ?: return null
            val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull() ?: return null
            if (root.mapInt("code") != 0) return null
            val request = root["request"] as? JsonObject ?: return null
            if (request.mapInt("code") != 0) return null
            return request["data"] as? JsonObject
        }

        val info = request("music.UserInfo.userInfoServer", "GetLoginUserInfo")
            ?.let { (it["info"] as? JsonObject) ?: it } ?: return null
        val nickname = firstNonEmpty(
            info.mapString("nick"),
            info.mapString("nickname"),
            info.mapString("name"),
        )
        if (nickname.isBlank()) return null
        val avatar = firstNonEmpty(
            info.mapString("logo"),
            info.mapString("headpic"),
            info.mapString("avatarUrl"),
        )
        val vip = request("VipLogin.VipLoginInter", "vip_login_base")
        return AccountInfo(
            nickname = nickname,
            avatar = normalizeCover(avatar),
            detail = qqMembershipLabel(vip).orEmpty(),
        )
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != "qq") throw IllegalArgumentException("source mismatch")
        val songMid = song.extra["songmid"]?.takeIf { it.isNotBlank() } ?: song.id
        var songId = song.id.toIntOrNull() ?: 0
        val extraId = song.extra["song_id"].orEmpty()
        if (extraId.isNotBlank()) songId = extraId.toIntOrNull() ?: 0
        var name = song.name
        var artist = song.artist
        var album = song.album
        var duration = song.duration
        if (songId == 0) {
            val parsed = runCatching { fetchSongDetail(cookie, songMid) }.getOrNull()
            if (parsed != null) {
                songId = parsed.extra["song_id"]?.toIntOrNull() ?: 0
                if (name.isBlank()) name = parsed.name
                if (artist.isBlank()) artist = parsed.artist
                if (album.isBlank()) album = parsed.album
                if (duration == 0) duration = parsed.duration
            }
        }
        if (songId == 0) throw IllegalStateException("qq song id not found")

        val encoder = Base64.getEncoder()
        val body = buildJsonObject {
            putJsonObject("comm") {
                put("ct", 11)
                put("cv", "1003006")
                put("v", "1003006")
                put("os_ver", "15")
                put("phonetype", "24122RKC7C")
                put("rom", "Redmi/miro/miro:15/AE3A.240806.005/OS2.0.105.0.VOMCNXM:user/release-keys")
                put("tmeAppID", "qqmusiclight")
                put("nettype", "NETWORK_WIFI")
                put("udid", "0")
                put("uid", "0")
                put("sid", "")
                put("loginUin", "0")
                put("platform", "yqq.json")
                put("needNewCode", 0)
            }
            putJsonObject("request") {
                put("method", "GetPlayLyricInfo")
                put("module", "music.musichallSong.PlayLyricInfo")
                putJsonObject("param") {
                    put("albumName", encoder.encodeToString(album.toByteArray(Charsets.UTF_8)))
                    put("crypt", 1)
                    put("ct", 19)
                    put("cv", 2111)
                    put("interval", duration)
                    put("lrc_t", 0)
                    put("qrc", 1)
                    put("qrc_t", 0)
                    put("roma", 1)
                    put("roma_t", 0)
                    put("singerName", encoder.encodeToString(artist.toByteArray(Charsets.UTF_8)))
                    put("songID", songId)
                    put("songName", encoder.encodeToString(name.toByteArray(Charsets.UTF_8)))
                    put("trans", 1)
                    put("trans_t", 0)
                    put("type", 0)
                }
            }
        }
        val response = NativeHttp.postJson(
            QQ_MUSICU_API,
            body.toString(),
            headers = mapOf("Referer" to QQ_LYRIC_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_USER_AGENT,
        )
        val parsed = json.decodeFromString<QQLyricResponse>(response.body)
        if (parsed.code != 0 || parsed.request.code != 0) {
            throw IllegalStateException("qq lyric api error code: ${parsed.code}/${parsed.request.code}")
        }
        if (parsed.request.data.lyric.isEmpty()) throw IllegalStateException("lyric is empty or not found")

        val tags = mutableMapOf("ti" to name, "ar" to artist, "al" to album)
        val data = mutableMapOf<String, List<QQrcLine>>()
        val sources = listOf(
            "orig" to parsed.request.data.lyric,
            "ts" to parsed.request.data.trans,
            "roma" to parsed.request.data.roma,
        )
        for ((key, raw) in sources) {
            if (raw.isBlank()) continue
            val decrypted = runCatching { decryptQrcHex(raw) }.getOrNull() ?: continue
            val (qrcTags, qrcData) = parseQrc(decrypted)
            if (key == "orig") {
                for ((tag, value) in qrcTags) {
                    if (tags[tag].isNullOrBlank()) tags[tag] = value
                }
            }
            data[key] = qrcData
        }
        if (data["orig"].isNullOrEmpty()) throw IllegalStateException("lyric is empty or qrc decrypt failed")
        return convertVerbatimLrc(tags, data, listOf("orig", "roma", "ts"))
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val url = QQ_PLAYLIST_SEARCH_API + "?" + qqQuery(
            "query" to keyword,
            "page_no" to "0",
            "num_per_page" to "20",
            "format" to "json",
            "remoteplace" to "txt.yqq.playlist",
            "flag_qc" to "0",
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to "https://y.qq.com/portal/search.html"),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_CHROME_USER_AGENT,
        )
        val response = json.decodeFromString<QQPlaylistSearchResponse>(stripJsonp(body))
        val playlists = response.data.list.map { item ->
            Playlist(
                id = item.dissid,
                name = item.dissname,
                cover = toHttps(item.imgurl),
                trackCount = item.songCount,
                playCount = item.listennum.toLong(),
                creator = item.creator.name,
                description = "",
                source = "qq",
                link = "https://y.qq.com/n/ryqq/playlist/${item.dissid}",
            )
        }
        if (playlists.isEmpty()) throw IllegalStateException("no playlists found")
        return playlists
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> {
        val playlistId = id.trim()
        if (playlistId == QQ_FAVORITE_PLAYLIST_ID) {
            val uin = normalizeQqUin(cookie)
            if (uin.isBlank()) throw IllegalStateException("qq favorite songs require uin cookie")
            return fetchProfileOrderSongs(cookie, uin, 1, 300).second
        }
        if (playlistId.startsWith(QQ_PROFILE_DIR_PREFIX)) {
            return fetchProfileDirPlaylistSongs(cookie, playlistId.removePrefix(QQ_PROFILE_DIR_PREFIX))
        }
        return fetchPlaylistDetail(cookie, playlistId).second
    }

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val playlistId = extractQQPlaylistId(link) ?: throw IllegalArgumentException("invalid qq playlist link")
        val (playlist, songs) = fetchPlaylistDetail(cookie, playlistId)
        return CollectionResult(playlist, songs)
    }

    override suspend fun recommendedPlaylists(cookie: String): List<Playlist> {
        val body = buildJsonObject {
            putJsonObject("comm") { put("ct", 24) }
            putJsonObject("recomPlaylist") {
                put("method", "get_hot_recommend")
                put("module", "playlist.HotRecommendServer")
                putJsonObject("param") {
                    put("async", 1)
                    put("cmd", 2)
                }
            }
        }
        val response = NativeHttp.postJson(
            QQ_MUSICU_API,
            body.toString(),
            headers = mapOf("Referer" to "https://y.qq.com/"),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_CHROME_USER_AGENT,
        )
        val parsed = json.decodeFromString<QQRecommendedResponse>(response.body)
        if (parsed.code != 0) throw IllegalStateException("qq api error code: ${parsed.code}")
        val playlists = parsed.recomPlaylist.data.vHot.map { item ->
            val playlistId = item.contentId.toString()
            val trackCount = if (item.songCnt != 0) item.songCnt else item.songCount
            Playlist(
                id = playlistId,
                name = item.title,
                cover = toHttps(item.cover),
                trackCount = trackCount,
                playCount = item.listenNum.toLong(),
                creator = item.username,
                description = "",
                source = "qq",
                link = "https://y.qq.com/n/ryqq/playlist/$playlistId",
            )
        }
        if (playlists.isEmpty()) throw IllegalStateException("no recommended playlists found")
        return playlists
    }

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val url = QQ_SEARCH_API + "?" + qqQuery(
            "format" to "json",
            "p" to "1",
            "n" to "10",
            "w" to keyword,
            "t" to "8",
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to "https://y.qq.com/portal/search.html"),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_WINDOWS_USER_AGENT,
        )
        val response = json.decodeFromString<QQAlbumSearchResponse>(body)
        val albums = response.data.album.list.mapNotNull { item ->
            if (item.albumMID.isBlank()) return@mapNotNull null
            Playlist(
                id = item.albumMID,
                name = item.albumName,
                cover = coverUrl(item.albumMID),
                trackCount = 0,
                playCount = 0,
                creator = item.singerName,
                description = "",
                source = "qq",
                link = "https://y.qq.com/n/ryqq/albumDetail/${item.albumMID}",
                extra = mapOf(
                    "type" to "album",
                    "album_id" to item.albumID.toString(),
                    "album_mid" to item.albumMID,
                    "publish_time" to item.publicTime,
                ),
            )
        }
        if (albums.isEmpty()) throw IllegalStateException("no albums found")
        return albums
    }

    override suspend fun albumSongs(cookie: String, id: String): List<Song> =
        fetchAlbumDetail(cookie, id).second

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult {
        val patterns = listOf(
            Regex("albumDetail/([A-Za-z0-9]+)"),
            Regex("album/([A-Za-z0-9]+)"),
            Regex("albummid=([A-Za-z0-9]+)"),
        )
        for (pattern in patterns) {
            val match = pattern.find(link) ?: continue
            val albumMid = match.groupValues.getOrNull(1).orEmpty()
            if (albumMid.isNotEmpty()) {
                val (playlist, songs) = fetchAlbumDetail(cookie, albumMid)
                return CollectionResult(playlist, songs)
            }
        }
        throw IllegalArgumentException("invalid qq album link")
    }

    override suspend fun categories(cookie: String): List<PlaylistCategory> {
        val url = QQ_CATEGORY_API + "?" + qqQuery(
            "format" to "json",
            "inCharset" to "utf8",
            "outCharset" to "utf-8",
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to "https://y.qq.com/"),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_WINDOWS_USER_AGENT,
        )
        val response = json.decodeFromString<QQCategoryResponse>(body)
        if (response.code != 0) {
            throw IllegalStateException("qq playlist category api error: ${response.message} (code ${response.code})")
        }
        val categories = mutableListOf(
            PlaylistCategory(id = "", name = "全部", group = "全部", source = "qq"),
        )
        for (group in response.data.categories) {
            val groupName = group.categoryGroupName.trim()
            for (item in group.items) {
                if (item.usable == 0 || item.categoryId == 0 || item.categoryId == 10000000) continue
                val name = item.categoryName.trim()
                if (name.isEmpty()) continue
                categories += PlaylistCategory(
                    id = item.categoryId.toString(),
                    name = name,
                    group = groupName,
                    source = "qq",
                )
            }
        }
        return categories
    }

    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        val categoryId = id.trim().ifBlank { "10000000" }
        val safePage = page.coerceAtLeast(1)
        val safeLimit = if (limit < 1) 20 else limit
        val offset = (safePage - 1) * safeLimit
        val url = QQ_CATEGORY_PLAYLIST_API + "?" + qqQuery(
            "picmid" to "1",
            "rnd" to "0.1",
            "g_tk" to "5381",
            "loginUin" to "0",
            "hostUin" to "0",
            "format" to "json",
            "inCharset" to "utf8",
            "outCharset" to "utf-8",
            "notice" to "0",
            "platform" to "yqq.json",
            "needNewCode" to "0",
            "categoryId" to categoryId,
            "sortId" to "5",
            "sin" to offset.toString(),
            "ein" to (offset + safeLimit - 1).toString(),
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to "https://y.qq.com/"),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_WINDOWS_USER_AGENT,
        )
        val response = json.decodeFromString<QQCategoryPlaylistResponse>(body)
        if (response.code != 0) {
            throw IllegalStateException("qq category playlist api error: ${response.message} (code ${response.code})")
        }
        val playlists = response.data.list.mapNotNull { item ->
            val playlistId = item.dissid.trim()
            val name = item.dissname.trim()
            if (playlistId.isEmpty() || name.isEmpty()) return@mapNotNull null
            val trackCount = if (item.songCount != 0) item.songCount else item.songNum
            Playlist(
                id = playlistId,
                name = name,
                cover = toHttps(item.imgurl),
                trackCount = trackCount,
                playCount = item.listennum.toLong(),
                creator = item.creator.name,
                description = item.introduction,
                source = "qq",
                link = "https://y.qq.com/n/ryqq/playlist/$playlistId",
                extra = mapOf("category_id" to categoryId),
            )
        }
        if (playlists.isEmpty()) throw IllegalStateException("no category playlists found")
        return playlists
    }

    override suspend fun userPlaylists(cookie: String, page: Int, limit: Int): List<Playlist> {
        if (cookie.isBlank()) throw IllegalStateException("qq user playlists require cookie")
        val safePage = page.coerceAtLeast(1)
        var safeLimit = if (limit <= 0) 30 else limit
        if (safeLimit > 100) safeLimit = 100
        val uin = normalizeQqUin(cookie)
        if (uin.isBlank()) throw IllegalStateException("qq user playlists require uin cookie")

        val playlists = mutableListOf<Playlist>()
        val seen = mutableSetOf<String>()
        fun addPlaylist(playlist: Playlist) {
            val playlistId = playlist.id.trim()
            val name = playlist.name.trim()
            if (playlistId.isEmpty() || name.isEmpty() || !seen.add(playlistId)) return
            playlists += playlist.copy(id = playlistId, name = name)
        }

        runCatching { fetchFavoriteSongsPlaylistSummary(cookie, uin) }.getOrNull()?.let { favorite ->
            if (favorite.trackCount > 0) addPlaylist(favorite)
        }

        val url = QQ_USER_CREATED_API + "?" + qqQuery(
            "hostuin" to uin,
            "sin" to ((safePage - 1) * safeLimit).toString(),
            "size" to safeLimit.toString(),
            "format" to "json",
            "inCharset" to "utf8",
            "outCharset" to "utf-8",
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to "https://y.qq.com/"),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_WINDOWS_USER_AGENT,
        )
        val response = json.decodeFromString<QQUserPlaylistResponse>(body)
        if (response.code != 0) {
            throw IllegalStateException("qq user playlist api error: ${response.message} (code ${response.code})")
        }

        for (item in response.data.disslist) {
            val playlistId = when {
                item.dissid > 0 -> item.dissid.toString()
                item.tid > 0 -> item.tid.toString()
                item.dirid > 0 -> QQ_PROFILE_DIR_PREFIX + item.dirid
                else -> ""
            }
            if (playlistId.isEmpty()) continue
            val name = firstNonEmpty(item.dissName, item.title)
            if (name.isEmpty()) continue
            var trackCount = item.songCount
            if (trackCount == 0) trackCount = item.songNum
            if (trackCount == 0) trackCount = item.songCnt
            val dirId = if (item.dirid > 0) item.dirid.toString() else ""
            val playCount = if (item.listenNum != 0) item.listenNum else item.visitnum
            addPlaylist(
                Playlist(
                    id = playlistId,
                    name = name,
                    cover = normalizeCover(firstNonEmpty(item.dissCover, item.cover)),
                    trackCount = trackCount,
                    playCount = playCount.toLong(),
                    creator = uin,
                    description = firstNonEmpty(item.dissDesc, item.desc),
                    source = "qq",
                    link = qqPlaylistLink(
                        playlistId,
                        firstNonEmpty(cookieValue(cookie, "euin"), uin),
                        dirId,
                    ),
                    extra = mapOf(
                        "uin" to uin,
                        "dirid" to dirId,
                        "commit_time" to item.commitTime,
                    ),
                )
            )
        }
        for (item in response.data.list) {
            val playlistId = item.dissid.trim()
            val name = item.dissname.trim()
            if (playlistId.isEmpty() || name.isEmpty()) continue
            val trackCount = if (item.songCount != 0) item.songCount else item.songNum
            addPlaylist(
                Playlist(
                    id = playlistId,
                    name = name,
                    cover = normalizeCover(item.imgurl),
                    trackCount = trackCount,
                    playCount = item.listennum.toLong(),
                    creator = uin,
                    description = item.introduction,
                    source = "qq",
                    link = "https://y.qq.com/n/ryqq/playlist/$playlistId",
                    extra = mapOf("uin" to uin),
                )
            )
        }
        runCatching { fetchProfileOrderPlaylists(cookie, uin, safePage, safeLimit) }
            .getOrNull()
            ?.forEach(::addPlaylist)
        return playlists
    }

    override suspend fun topLists(cookie: String): List<TopList> {
        val payload = buildJsonObject {
            putJsonObject("comm") {
                put("cv", 4747474)
                put("ct", 24)
                put("format", "json")
                put("inCharset", "utf-8")
                put("outCharset", "utf-8")
                put("notice", 0)
                put("platform", "yqq.json")
                put("needNewCode", 1)
                put("uin", "0")
                put("g_tk_new_20200303", 5381)
                put("g_tk", 5381)
            }
            putJsonObject("topList") {
                put("module", "musicToplist.ToplistInfoServer")
                put("method", "GetAll")
                putJsonObject("param") {}
            }
        }
        val url = QQ_TOP_LIST_API + "?format=json&data=" + goQueryEscape(payload.toString())
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to "https://y.qq.com/"),
            cookie = cookie,
            randomIp = true,
        )
        val response = json.decodeFromString<QQTopListResponse>(body)
        if (response.code != 0 || response.topList.code != 0) {
            throw IllegalStateException("qq toplist api error code: ${response.code}/${response.topList.code}")
        }
        return response.topList.data.group.flatMap { group ->
            group.toplist.mapNotNull { item ->
                val name = item.title.trim()
                if (item.topId <= 0 || name.isEmpty() || item.recType == 10010) return@mapNotNull null
                val id = item.topId.toString()
                val extra = buildMap {
                    put("group", JsonPrimitive(group.groupName))
                    put("group_id", JsonPrimitive(group.groupId.toString()))
                    put("grouptype", JsonPrimitive(item.groupType.toString()))
                    put("top_type", JsonPrimitive(item.topType.toString()))
                    val detail = item.titleDetail.trim()
                    if (detail.isNotEmpty()) put("title_detail", JsonPrimitive(detail))
                    if (item.period.isNotEmpty()) put("period", JsonPrimitive(item.period))
                    if (item.updateTime.isNotEmpty()) put("update_time", JsonPrimitive(item.updateTime))
                }
                TopList(
                    id = id,
                    name = name,
                    cover = firstCover(
                        item.frontPicUrl,
                        item.mbFrontPicUrl,
                        item.headPicUrl,
                        item.mbHeadPicUrl,
                        item.musichallPicUrl,
                    ),
                    description = cleanTopListIntro(item.intro),
                    updateFrequency = item.updateTips.trim(),
                    trackCount = item.totalNum,
                    playCount = item.listenNum,
                    source = "qq",
                    link = "https://y.qq.com/n/ryqq/toplist/$id",
                    extra = extra,
                )
            }
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
        return fetchTopListSongs(cookie, QQ_HOT_SONG_TOP_LIST_ID, safeLimit)
    }

    override suspend fun createLogin(type: String): QRCreateResult {
        val sessionId = NativeProviders.newSessionId(info.id)
        return if (normalizeLoginType(type) == "wx") createWxLogin(sessionId) else createQqLogin(sessionId)
    }

    override suspend fun checkLogin(sessionId: String, key: String): QRCheckResult {
        val params = parseQuery(key)
        val type = params["type"]?.trim().orEmpty()
            .ifBlank { loginSessions[sessionId]?.type ?: "qq" }
        val result = if (type == "wx") checkWxLogin(key) else checkQqLogin(key)
        if (result.status == "success" || result.status == "expired") loginSessions.remove(sessionId)
        return result
    }

    private suspend fun fetchAlbumDetail(cookie: String, id: String): Pair<Playlist, List<Song>> {
        var albumMid = id.trim()
        if (albumMid.isEmpty()) throw IllegalArgumentException("album id is empty")
        val headers = mapOf(
            "Referer" to "https://y.qq.com/",
            "Content-Type" to "application/json",
        )
        val detailBody = buildJsonObject {
            putJsonObject("comm") {
                put("ct", 24)
                put("cv", 0)
            }
            putJsonObject("album") {
                put("module", "music.musichallAlbum.AlbumInfoServer")
                put("method", "GetAlbumDetail")
                putJsonObject("param") { put("albumMid", albumMid) }
            }
        }
        val detailResponse = NativeHttp.postJson(
            QQ_MUSICU_API,
            detailBody.toString(),
            headers = headers,
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_WINDOWS_USER_AGENT,
        )
        val detail = json.decodeFromString<QQAlbumDetailResponse>(detailResponse.body)
        if (detail.album.code != 0) {
            throw IllegalStateException("qq album detail api error code: ${detail.album.code}")
        }
        val info = detail.album.data.basicInfo
        if (info.albumMid.isNotEmpty()) albumMid = info.albumMid
        if (info.albumName.isEmpty()) throw IllegalStateException("album not found")

        val artistNames = detail.album.data.singer.singerList.map { it.name }.filter { it.isNotEmpty() }
        val songs = mutableListOf<Song>()
        val batchSize = 100
        var totalNum = 0
        var begin = 0
        while (true) {
            val songBody = buildJsonObject {
                putJsonObject("comm") {
                    put("ct", 24)
                    put("cv", 0)
                }
                putJsonObject("album") {
                    put("module", "music.musichallAlbum.AlbumSongList")
                    put("method", "GetAlbumSongList")
                    putJsonObject("param") {
                        put("albumMid", albumMid)
                        put("begin", begin)
                        put("num", batchSize)
                        put("order", 2)
                    }
                }
            }
            val songResponse = NativeHttp.postJson(
                QQ_MUSICU_API,
                songBody.toString(),
                headers = headers,
                cookie = cookie,
                randomIp = true,
                userAgent = QQ_WINDOWS_USER_AGENT,
            )
            val page = json.decodeFromString<QQAlbumSongsResponse>(songResponse.body)
            if (page.album.code != 0) {
                throw IllegalStateException("qq album songs api error code: ${page.album.code}")
            }
            if (totalNum == 0) totalNum = page.album.data.totalNum
            val pageSongs = page.album.data.songList
            if (pageSongs.isEmpty()) break
            for (entry in pageSongs) {
                val songInfo = entry.songInfo
                if (songInfo.mid.isEmpty()) continue
                val pageArtists = songInfo.singer.map { it.name }.filter { it.isNotEmpty() }
                val (fileSize, bitrate) = pickFileSize(
                    songInfo.file.size128Mp3,
                    songInfo.file.size320Mp3,
                    songInfo.file.sizeFlac,
                    songInfo.interval,
                )
                val cover = if (songInfo.album.mid.isNotEmpty()) coverUrl(songInfo.album.mid) else ""
                songs += Song(
                    id = songInfo.mid,
                    name = songInfo.name,
                    artist = pageArtists.joinToString(", "),
                    album = songInfo.album.name,
                    albumId = songInfo.album.mid,
                    duration = songInfo.interval,
                    size = fileSize,
                    bitrate = bitrate,
                    source = "qq",
                    cover = cover,
                    link = "https://y.qq.com/n/ryqq/songDetail/${songInfo.mid}",
                    extra = mapOf(
                        "songmid" to songInfo.mid,
                        "album_mid" to songInfo.album.mid,
                        "album_id" to songInfo.album.id.toString(),
                    ),
                )
            }
            if (pageSongs.size < batchSize) break
            if (totalNum > 0 && begin + pageSongs.size >= totalNum) break
            begin += batchSize
        }

        val trackCount = if (totalNum == 0) songs.size else totalNum
        val playlist = Playlist(
            id = albumMid,
            name = info.albumName,
            cover = coverUrl(albumMid),
            trackCount = trackCount,
            playCount = 0,
            creator = artistNames.joinToString(", "),
            description = info.desc,
            source = "qq",
            link = "https://y.qq.com/n/ryqq/albumDetail/$albumMid",
            extra = mapOf(
                "type" to "album",
                "album_id" to info.albumID.toString(),
                "album_mid" to albumMid,
                "company" to detail.album.data.company.name,
                "publish_time" to info.publishDate,
            ),
        )
        return playlist to songs
    }

    private suspend fun fetchPlaylistDetail(cookie: String, id: String): Pair<Playlist, List<Song>> {
        val params = linkedMapOf(
            "type" to "1",
            "json" to "1",
            "utf8" to "1",
            "onlysong" to "0",
            "disstid" to id,
            "format" to "json",
            "g_tk" to "5381",
            "loginUin" to "0",
            "hostUin" to "0",
            "inCharset" to "utf8",
            "outCharset" to "utf-8",
            "notice" to "0",
            "platform" to "yqq",
            "needNewCode" to "0",
        )
        var lastError: Exception? = null
        var lastCdlist: List<QQCdlist>? = null
        for (endpoint in QQ_PLAYLIST_DETAIL_ENDPOINTS) {
            val fetched = try {
                NativeHttp.get(
                    endpoint + "?" + qqQuery(params),
                    headers = mapOf("Referer" to "https://y.qq.com/"),
                    cookie = cookie,
                    randomIp = true,
                    userAgent = QQ_WINDOWS_USER_AGENT,
                )
            } catch (error: Exception) {
                lastError = error
                continue
            }
            val parsed = try {
                json.decodeFromString<QQPlaylistDetailResponse>(stripJsonp(fetched))
            } catch (error: Exception) {
                lastError = IllegalStateException("qq playlist detail json error: ${error.message}", error)
                continue
            }
            lastCdlist = parsed.cdlist
            if (parsed.cdlist.isNotEmpty() && parsed.subcode == 0) {
                lastError = null
                break
            }
            lastError = IllegalStateException(
                "qq playlist detail api error: subcode=${parsed.subcode} msg=${parsed.msg}"
            )
        }
        val cdlist = lastCdlist
        if (cdlist.isNullOrEmpty()) {
            throw lastError ?: IllegalStateException("playlist not found (empty cdlist)")
        }
        val info = cdlist.first()
        val playlist = Playlist(
            id = id,
            name = info.dissname,
            cover = info.logo,
            trackCount = info.songnum,
            playCount = info.visitnum.toLong(),
            creator = info.nickname,
            description = info.desc,
            source = "qq",
            link = "https://y.qq.com/n/ryqq/playlist/$id",
        )
        val songs = info.songlist.map { item ->
            val artistNames = item.singer.map { it.name }
            val cover = if (item.albummid.isNotEmpty()) coverUrl(item.albummid) else ""
            val (fileSize, bitrate) = pickFileSize(item.size128, item.size320, item.sizeflac, item.interval)
            Song(
                id = item.songmid,
                name = item.songname,
                artist = artistNames.joinToString("、"),
                album = item.albumname,
                duration = item.interval,
                size = fileSize,
                bitrate = bitrate,
                source = "qq",
                cover = cover,
                link = "https://y.qq.com/n/ryqq/songDetail/${item.songmid}",
                extra = mapOf("songmid" to item.songmid),
            )
        }
        return playlist to songs
    }

    private suspend fun fetchSongDetail(cookie: String, songMid: String): Song {
        val url = QQ_SONG_DETAIL_API + "?" + qqQuery("songmid" to songMid, "format" to "json")
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to QQ_SEARCH_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_USER_AGENT,
        )
        return parseSongDetail(body)
    }

    private suspend fun fetchSongDetailByID(cookie: String, songId: String): Song {
        val url = QQ_SONG_DETAIL_API + "?" + qqQuery("songid" to songId, "format" to "json")
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to QQ_SEARCH_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_USER_AGENT,
        )
        return parseSongDetail(body)
    }

    private fun parseSongDetail(body: String): Song {
        val response = json.decodeFromString<QQDetailResponse>(body)
        val item = response.data.firstOrNull() ?: throw IllegalStateException("song detail not found")
        val artistNames = item.singer.map { it.name }
        val cover = if (item.album.mid.isNotEmpty()) coverUrl(item.album.mid) else ""
        return Song(
            id = item.mid,
            name = item.name,
            artist = artistNames.joinToString("、"),
            album = item.album.name,
            duration = item.interval,
            source = "qq",
            cover = cover,
            link = "https://y.qq.com/n/ryqq/songDetail/${item.mid}",
            extra = mapOf(
                "songmid" to item.mid,
                "song_id" to item.id.toString(),
            ),
        )
    }

    private suspend fun isVipAccount(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val cacheKey = NativeCrypto.md5Hex(cookie)
        vipCache[cacheKey]?.let { return it }
        return try {
            val songMid = "004YZbkL2MNHoY"
            val filename = "M500$songMid$songMid.mp3"
            val body = buildJsonObject {
                putJsonObject("comm") {
                    put("cv", 4747474)
                    put("ct", 24)
                    put("format", "json")
                    put("inCharset", "utf-8")
                    put("outCharset", "utf-8")
                    put("notice", 0)
                    put("platform", "yqq.json")
                    put("needNewCode", 1)
                    put("uin", 0)
                }
                putJsonObject("req_1") {
                    put("module", "music.vkey.GetVkey")
                    put("method", "UrlGetVkey")
                    putJsonObject("param") {
                        put("guid", randomGuid())
                        putJsonArray("songmid") { add(JsonPrimitive(songMid)) }
                        putJsonArray("songtype") { add(JsonPrimitive(0)) }
                        put("uin", "0")
                        put("loginflag", 1)
                        put("platform", "20")
                        putJsonArray("filename") { add(JsonPrimitive(filename)) }
                    }
                }
            }
            val response = NativeHttp.postJson(
                QQ_MUSICU_API,
                body.toString(),
                headers = mapOf("Referer" to QQ_DOWNLOAD_REFERER),
                cookie = cookie,
                randomIp = true,
                userAgent = QQ_USER_AGENT,
            )
            val parsed = json.decodeFromString<QQVkeyResponse>(response.body)
            val isVip = parsed.req1.data.midUrlInfo.firstOrNull()?.purl?.isNotEmpty() == true
            if (!isVip && parsed.req1.code != 0) return false
            vipCache[cacheKey] = isVip
            isVip
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchTopListSongs(cookie: String, topListId: String, limit: Int): List<Song> {
        val id = topListId.trim()
        if (id.isEmpty()) throw IllegalArgumentException("qq toplist id is empty")
        if (!id.all { it in '0'..'9' }) throw IllegalArgumentException("invalid qq toplist id: $id")
        val songs = mutableListOf<Song>()
        var begin = 0
        while (true) {
            val pageSongs = fetchTopListSongsPage(cookie, id, begin, QQ_TOP_LIST_PAGE_SIZE)
            if (pageSongs.isEmpty()) break
            songs += pageSongs
            if (limit > 0 && songs.size >= limit) {
                return songs.take(limit)
            }
            if (pageSongs.size < QQ_TOP_LIST_PAGE_SIZE) break
            begin += QQ_TOP_LIST_PAGE_SIZE
        }
        if (songs.isEmpty()) throw IllegalStateException("qq toplist $id has no songs")
        return songs
    }

    private suspend fun fetchTopListSongsPage(
        cookie: String,
        topListId: String,
        begin: Int,
        num: Int,
    ): List<Song> {
        val url = QQ_TOP_LIST_SONGS_API + "?" + qqQuery(
            "topid" to topListId,
            "song_begin" to begin.toString(),
            "song_num" to num.toString(),
            "format" to "json",
            "inCharset" to "utf8",
            "outCharset" to "utf-8",
            "notice" to "0",
            "platform" to "h5",
            "needNewCode" to "1",
            "tpl" to "3",
            "page" to "detail",
            "type" to "top",
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to "https://y.qq.com/"),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_USER_AGENT,
        )
        val parsed = json.decodeFromString<QQTopSongsResponse>(body)
        if (parsed.code != 0) {
            throw IllegalStateException("qq toplist songs api error code: ${parsed.code}")
        }
        val songs = mutableListOf<Song>()
        for ((index, entry) in parsed.songlist.withIndex()) {
            val info = entry.data
            if (info.songmid.isEmpty() || info.songname.isEmpty()) continue
            val artistNames = info.singer.map { it.name.trim() }.filter { it.isNotEmpty() }
            val (fileSize, bitrate) = pickFileSize(info.size128, info.size320, info.sizeflac, info.interval)
            val cover = if (info.albummid.isNotEmpty()) coverUrl(info.albummid) else ""
            val rank = entry.curCount.trim().ifEmpty { (begin + index + 1).toString() }
            val extra = buildMap {
                put("songmid", info.songmid)
                put("song_id", info.songid.toString())
                put("album_mid", info.albummid)
                if (info.strMediaMid.isNotEmpty()) put("media_mid", info.strMediaMid)
                put("toplist_id", topListId)
                put("rank", rank)
            }
            songs += Song(
                id = info.songmid,
                name = info.songname,
                artist = artistNames.joinToString(", "),
                album = info.albumname,
                albumId = info.albummid,
                duration = info.interval,
                size = fileSize,
                bitrate = bitrate,
                source = "qq",
                cover = cover,
                link = "https://y.qq.com/n/ryqq/songDetail/${info.songmid}",
                extra = extra,
            )
        }
        return songs
    }

    private suspend fun fetchFavoriteSongsPlaylistSummary(cookie: String, uin: String): Playlist {
        val (total, songs) = fetchProfileOrderSongs(cookie, uin, 1, 1)
        val cover = songs.firstOrNull()?.cover.orEmpty()
        return Playlist(
            id = QQ_FAVORITE_PLAYLIST_ID,
            name = "我喜欢的歌曲",
            cover = cover,
            trackCount = total,
            playCount = 0,
            creator = uin,
            description = "QQ音乐我喜欢的歌曲",
            source = "qq",
            link = "https://y.qq.com/n/ryqq/profile",
            extra = mapOf(
                "uin" to uin,
                "virtual" to "favorite_songs",
            ),
        )
    }

    private suspend fun fetchProfileOrderPlaylists(
        cookie: String,
        uin: String,
        page: Int,
        limit: Int,
    ): List<Playlist> {
        val url = QQ_PROFILE_ORDER_API + "?" + profileOrderAssetQuery(uin, "3", page, limit)
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to "https://y.qq.com/"),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_WINDOWS_USER_AGENT,
        )
        val parsed = json.decodeFromString<QQProfilePlaylistResponse>(body)
        if (parsed.code != 0) {
            throw IllegalStateException("qq profile playlist api error: ${parsed.msg} (code ${parsed.code})")
        }
        return parsed.data.cdlist.mapNotNull { item ->
            if (item.dissid <= 0 || item.dissname.trim().isEmpty()) return@mapNotNull null
            val playlistId = item.dissid.toString()
            var creator = item.nickname.trim()
            if (creator.isEmpty() && item.uin > 0) creator = item.uin.toString()
            Playlist(
                id = playlistId,
                name = item.dissname,
                cover = normalizeCover(item.logo),
                trackCount = item.songnum,
                playCount = item.listennum.toLong(),
                creator = creator,
                description = "",
                source = "qq",
                link = "https://y.qq.com/n/ryqq/playlist/$playlistId",
                extra = mapOf(
                    "uin" to uin,
                    "collected" to "true",
                ),
            )
        }
    }

    private suspend fun fetchProfileOrderSongs(
        cookie: String,
        uin: String,
        page: Int,
        limit: Int,
    ): Pair<Int, List<Song>> {
        val url = QQ_PROFILE_ORDER_API + "?" + profileOrderAssetQuery(uin, "1", page, limit)
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to "https://y.qq.com/"),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_WINDOWS_USER_AGENT,
        )
        val parsed = json.decodeFromString<QQProfileSongsResponse>(body)
        if (parsed.code != 0) {
            throw IllegalStateException("qq profile songs api error: ${parsed.msg} (code ${parsed.code})")
        }
        val songs = parsed.data.songlist.mapNotNull { entry ->
            val song = qqProfileSongToModel(entry.data)
            if (song.id.isEmpty() || song.name.isEmpty()) null else song
        }
        return parsed.data.totalsong to songs
    }

    private suspend fun fetchProfileDirPlaylistSongs(cookie: String, dirId: String): List<Song> {
        val dir = dirId.trim()
        if (dir.isEmpty()) throw IllegalArgumentException("qq profile dir playlist require dirid")
        val uin = firstNonEmpty(
            cookieValue(cookie, "euin"),
            cookieValue(cookie, "wxuin"),
            normalizeQqUin(cookie),
        )
        if (uin.isEmpty()) throw IllegalStateException("qq profile dir playlist require uin cookie")
        val url = QQ_PROFILE_DIR_API + "?" + qqQuery(
            "uin" to uin,
            "dirid" to dir,
            "new" to "0",
            "dirinfo" to "1",
            "miniportal" to "1",
            "fromDir2Diss" to "1",
            "mobile" to "1",
            "from" to "0",
            "to" to "500",
            "format" to "json",
            "g_tk" to "5381",
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to "https://y.qq.com/w/myalbum.html"),
            cookie = cookie,
            randomIp = true,
            userAgent = QQ_IPHONE_USER_AGENT,
        )
        val parsed = json.decodeFromString<QQProfileDirResponse>(stripJsonpBody(body))
        if (!qqJsonCodeOk(parsed.code)) {
            throw IllegalStateException("qq profile dir playlist api error: ${parsed.msg} (code ${jsonCodeText(parsed.code)})")
        }
        return parsed.songList.mapNotNull { item ->
            val song = qqProfileDirSongToModel(item)
            if (song.id.isEmpty() || song.name.isEmpty()) null else song
        }
    }

    private fun profileOrderAssetQuery(uin: String, reqType: String, page: Int, limit: Int): String {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = if (limit < 1) 50 else limit
        val offset = (safePage - 1) * safeLimit
        return qqQuery(
            "format" to "json",
            "inCharset" to "utf8",
            "outCharset" to "utf-8",
            "platform" to "yqq.json",
            "needNewCode" to "0",
            "loginUin" to uin,
            "hostUin" to "0",
            "notice" to "0",
            "g_tk" to "5381",
            "ct" to "20",
            "cid" to "205360956",
            "userid" to uin,
            "reqtype" to reqType,
            "sin" to offset.toString(),
            "ein" to (offset + safeLimit - 1).toString(),
        )
    }

    private fun qqProfileSongToModel(item: QQProfileSongData): Song {
        val artistNames = item.singer.map { it.name.trim() }.filter { it.isNotEmpty() }
        var songMid = item.songmid
        if (songMid.isEmpty() && item.songid > 0) songMid = item.songid.toString()
        val cover = if (item.albummid.isNotEmpty()) coverUrl(item.albummid) else ""
        val (fileSize, bitrate) = pickFileSize(item.size128, item.size320, item.sizeflac, item.interval)
        return Song(
            id = songMid,
            name = item.songname,
            artist = artistNames.joinToString("、"),
            album = item.albumname,
            duration = item.interval,
            size = fileSize,
            bitrate = bitrate,
            source = "qq",
            cover = cover,
            link = "https://y.qq.com/n/ryqq/songDetail/$songMid",
            extra = mapOf("songmid" to songMid),
        )
    }

    private fun qqProfileDirSongToModel(item: JsonObject): Song {
        val songType = item.mapInt("type")
        val data = item.mapString("data")
        if (data.isNotEmpty() && songType % 10 in 2..4) {
            val parts = data.split('|')
            fun value(index: Int): String = parts.getOrNull(index)?.trim().orEmpty()
            val songMid = value(0)
            val name = value(1)
            val albumMid = value(4)
            val albumName = value(5)
            val interval = value(7).toLongOrNull()?.toInt() ?: 0
            val size320 = value(11).toLongOrNull() ?: 0L
            val size128 = value(12).toLongOrNull() ?: 0L
            val sizeFlac = value(16).toLongOrNull() ?: 0L
            val cover = if (albumMid.isNotEmpty()) coverUrl(albumMid) else ""
            val (fileSize, bitrate) = pickFileSize(size128, size320, sizeFlac, interval)
            return Song(
                id = songMid,
                name = name,
                artist = value(3),
                album = albumName,
                duration = interval,
                size = fileSize,
                bitrate = bitrate,
                source = "qq",
                cover = cover,
                link = "https://y.qq.com/n/ryqq/songDetail/$songMid",
                extra = mapOf("songmid" to songMid),
            )
        }
        val songMid = firstNonEmpty(
            item.mapString("songmid"),
            item.mapString("mid"),
            item.mapString("id"),
        )
        val name = firstNonEmpty(item.mapString("songname"), item.mapString("name"))
        val albumMid = firstNonEmpty(item.mapString("albummid"), item.mapString("diskid"))
        val cover = if (albumMid.isNotEmpty()) coverUrl(albumMid) else ""
        return Song(
            id = songMid,
            name = name,
            artist = firstNonEmpty(item.mapString("singername"), item.mapString("singer")),
            album = firstNonEmpty(item.mapString("albumname"), item.mapString("diskname")),
            duration = item.mapInt("playtime"),
            source = "qq",
            cover = cover,
            link = "https://y.qq.com/n/ryqq/songDetail/$songMid",
            extra = mapOf("songmid" to songMid),
        )
    }

    private fun songOfSearch(item: QQSearchItem): Song {
        val artistNames = item.singer.map { it.name }
        val cover = if (item.albummid.isNotEmpty()) coverUrl(item.albummid) else ""
        val (fileSize, bitrate) = pickFileSize(item.size128, item.size320, item.sizeflac, item.interval)
        return Song(
            id = item.songmid,
            name = item.songname,
            artist = artistNames.joinToString("、"),
            album = item.albumname,
            duration = item.interval,
            size = fileSize,
            bitrate = bitrate,
            source = "qq",
            cover = cover,
            link = "https://y.qq.com/n/ryqq/songDetail/${item.songmid}",
            extra = mapOf(
                "songmid" to item.songmid,
                "song_id" to item.songid.toString(),
            ),
        )
    }

    private suspend fun createQqLogin(sessionId: String): QRCreateResult {
        val warmupQuery = qqQuery(
            "appid" to "716027609",
            "daid" to "383",
            "style" to "33",
            "login_text" to "登录",
            "hide_title_bar" to "1",
            "hide_border" to "1",
            "target" to "self",
            "s_url" to QQ_OAUTH_LOGIN_JUMP,
            "pt_3rd_aid" to QQ_OAUTH_CLIENT_ID,
            "pt_feedback_link" to "https://support.qq.com/products/77942?customInfo=.appid$QQ_OAUTH_CLIENT_ID",
            "theme" to "2",
            "verify_theme" to "",
        )
        val cookies = LinkedHashMap<String, String>()
        runCatching {
            NativeHttp.getResponse(
                QQ_QR_LOGIN_PAGE_API + "?" + warmupQuery,
                headers = mapOf("Accept" to "*/*"),
                userAgent = QQ_LOGIN_USER_AGENT,
            )
        }.getOrNull()?.cookies?.forEach { (key, value) ->
            if (value.isNotBlank()) cookies[key] = value
        }

        val showQuery = qqQuery(
            "appid" to "716027609",
            "e" to "2",
            "l" to "M",
            "s" to "3",
            "d" to "72",
            "v" to "4",
            "t" to String.format(Locale.ROOT, "%.6f", System.currentTimeMillis() / 1000.0),
            "daid" to "383",
            "pt_3rd_aid" to QQ_OAUTH_CLIENT_ID,
            "u1" to QQ_OAUTH_LOGIN_JUMP,
        )
        val showResponse = NativeHttp.getResponse(
            QQ_QR_SHOW_API + "?" + showQuery,
            headers = mapOf(
                "Referer" to "https://xui.ptlogin2.qq.com/",
                "Accept" to "*/*",
            ),
            cookie = joinCookies(cookies),
            userAgent = QQ_LOGIN_USER_AGENT,
            binary = true,
        )
        showResponse.cookies.forEach { (key, value) ->
            if (value.isNotBlank()) cookies[key] = value
        }
        val qrsig = cookies["qrsig"]?.trim().orEmpty()
        if (qrsig.isEmpty()) throw IllegalStateException("qq qr show missing qrsig")
        val loginSig = cookies["pt_login_sig"]?.trim().orEmpty()
        val keyParams = linkedMapOf("qrsig" to qrsig)
        if (loginSig.isNotEmpty()) keyParams["login_sig"] = loginSig
        val encodedState = encodeCookieState(cookies)
        if (encodedState.isNotEmpty()) keyParams["session"] = encodedState
        loginSessions[sessionId] = QQLoginState("qq", cookies)
        return QRCreateResult(
            sessionId = sessionId,
            session = QRSession(
                key = qqQuery(keyParams),
                url = "",
                // ptqrshow 的响应体就是二维码 PNG，界面用 data URI 直接显示
                imageUrl = showResponse.bytes.takeIf { it.isNotEmpty() }
                    ?.let { "data:image/png;base64," + android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                    .orEmpty(),
            ),
        )
    }

    private suspend fun checkQqLogin(key: String): QRCheckResult {
        val params = parseQuery(key)
        val qrsig = params["qrsig"]?.trim().orEmpty()
        if (qrsig.isEmpty()) throw IllegalArgumentException("qq qr login key missing qrsig")
        val loginSig = params["login_sig"]?.trim().orEmpty()
        val initialCookies = decodeCookieState(params["session"].orEmpty()) ?: mutableMapOf()
        initialCookies["qrsig"] = qrsig
        if (loginSig.isNotEmpty()) initialCookies["pt_login_sig"] = loginSig

        val query = qqQuery(
            "u1" to QQ_OAUTH_LOGIN_JUMP,
            "ptqrtoken" to hash33(qrsig).toString(),
            "ptredirect" to "0",
            "h" to "1",
            "t" to "1",
            "g" to "1",
            "from_ui" to "1",
            "ptlang" to "2052",
            "action" to "0-0-${System.currentTimeMillis()}",
            "js_ver" to "26071711",
            "js_type" to "1",
            "login_sig" to loginSig,
            "pt_uistyle" to "40",
            "aid" to "716027609",
            "daid" to "383",
            "pt_3rd_aid" to QQ_OAUTH_CLIENT_ID,
            "pt_js_version" to "c1987b96",
        )
        val response = NativeHttp.getResponse(
            QQ_QR_CHECK_API + "?" + query,
            headers = mapOf(
                "Referer" to "https://xui.ptlogin2.qq.com/",
                "Accept" to "*/*",
            ),
            cookie = joinCookies(initialCookies),
            userAgent = QQ_LOGIN_USER_AGENT,
        )
        val matches = Regex("'([^']*)'").findAll(response.body).map { it.groupValues[1] }.toList()
        val code: String
        val message: String
        val redirectUrl: String
        if (matches.size >= 5) {
            code = matches[0]
            message = matches[4]
            redirectUrl = matches[2]
        } else {
            code = ""
            message = response.body
            redirectUrl = ""
        }
        val status = when (code) {
            "0" -> "success"
            "65" -> "expired"
            "66" -> "waiting"
            "67" -> "scanned"
            else -> "failed"
        }
        if (status != "success") return QRCheckResult(status = status, message = message, cookie = "")
        response.cookies.forEach { (name, value) ->
            if (value.isNotBlank()) initialCookies[name] = value
        }
        if (redirectUrl.isEmpty()) {
            return QRCheckResult("failed", "qq login redirect url missing", "")
        }
        return finishQqLogin(initialCookies, redirectUrl, message)
    }

    private suspend fun finishQqLogin(
        cookies: MutableMap<String, String>,
        redirectUrl: String,
        message: String,
    ): QRCheckResult {
        return try {
            val landing = NativeHttp.getResponse(
                redirectUrl,
                headers = mapOf(
                    "Referer" to "https://xui.ptlogin2.qq.com/",
                    "Accept" to "*/*",
                ),
                cookie = joinCookies(cookies),
                userAgent = QQ_LOGIN_USER_AGENT,
            )
            landing.cookies.forEach { (name, value) ->
                if (value.isNotBlank()) cookies[name] = value
            }
            val early = normalizeQQMusicCookies(cookies)
            if (firstNonEmpty(
                    early["qm_keyst"].orEmpty(),
                    early["qqmusic_key"].orEmpty(),
                    early["musickey"].orEmpty(),
                ).isNotEmpty()
            ) {
                return QRCheckResult("success", message, joinCookies(early))
            }
            val pSkey = cookies["p_skey"]?.trim().orEmpty()
            if (pSkey.isEmpty()) return QRCheckResult("failed", "qq login missing p_skey", "")

            val form = linkedMapOf(
                "response_type" to "code",
                "client_id" to QQ_OAUTH_CLIENT_ID,
                "redirect_uri" to QQ_OAUTH_REDIRECT_URI,
                "scope" to QQ_OAUTH_SCOPE,
                "state" to "state",
                "switch" to "",
                "from_ptlogin" to "1",
                "src" to "1",
                "update_auth" to "1",
                "openapi" to "1010_1030",
                "g_tk" to gtk33(pSkey).toString(),
                "auth_time" to System.currentTimeMillis().toString(),
                "ui" to UUID.randomUUID().toString(),
            )
            val authorize = NativeHttp.postForm(
                QQ_OAUTH_AUTHORIZE,
                form,
                headers = mapOf(
                    "Referer" to QQ_OAUTH_LOGIN_JUMP,
                    "Origin" to "https://graph.qq.com",
                    "Accept" to "*/*",
                ),
                cookie = joinCookies(cookies),
                userAgent = QQ_LOGIN_USER_AGENT,
            )
            authorize.cookies.forEach { (name, value) ->
                if (value.isNotBlank()) cookies[name] = value
            }
            val code = Regex("[?&]code=([^&\"'\\s]+)").find(authorize.body)?.groupValues?.get(1).orEmpty()
            if (code.isEmpty()) {
                return QRCheckResult("failed", "qq oauth authorize missing code", "")
            }

            val payload = buildJsonObject {
                putJsonObject("comm") {
                    put("g_tk", 5381)
                    put("platform", "yqq")
                    put("ct", 24)
                    put("cv", 0)
                }
                putJsonObject("req") {
                    put("module", "QQConnectLogin.LoginServer")
                    put("method", "QQLogin")
                    putJsonObject("param") { put("code", code) }
                }
            }
            val login = NativeHttp.postJson(
                QQ_MUSICU_API,
                payload.toString(),
                headers = mapOf(
                    "Referer" to "https://y.qq.com/",
                    "Accept" to "*/*",
                ),
                cookie = joinCookies(cookies),
                userAgent = QQ_LOGIN_USER_AGENT,
            )
            login.cookies.forEach { (name, value) ->
                if (value.isNotBlank()) cookies[name] = value
            }
            val parsed = json.decodeFromString<QQWxLoginResponse>(login.body)
            if (parsed.code != 0 || parsed.req.code != 0) {
                val errorMessage = firstNonEmpty(parsed.req.message, parsed.req.msg, parsed.message, parsed.msg)
                return QRCheckResult(
                    "failed",
                    "qq music login api error: $errorMessage (code ${parsed.code}, req code ${parsed.req.code})",
                    "",
                )
            }
            for ((name, value) in qqWxLoginDataCookies(parsed.req.data)) {
                if (cookies[name].isNullOrEmpty()) cookies[name] = value
            }
            val normalized = normalizeQQMusicCookies(cookies)
            if (firstNonEmpty(
                    normalized["qm_keyst"].orEmpty(),
                    normalized["qqmusic_key"].orEmpty(),
                    normalized["musickey"].orEmpty(),
                ).isEmpty()
            ) {
                return QRCheckResult("failed", "qq music login missing qm_keyst", "")
            }
            QRCheckResult("success", message, joinCookies(normalized))
        } catch (error: Exception) {
            QRCheckResult("failed", error.message ?: "qq login failed", "")
        }
    }

    private suspend fun createWxLogin(sessionId: String): QRCreateResult {
        val state = "music-lib-${System.currentTimeMillis() * 1_000_000L + Random.nextLong(1_000_000L)}"
        val loginUrl = QQ_WX_QR_CONNECT_API + "?" + qqQuery(
            "appid" to QQ_WX_APP_ID,
            "redirect_uri" to QQ_WX_REDIRECT_URI,
            "response_type" to "code",
            "scope" to "snsapi_login",
            "state" to state,
            "href" to "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect",
        )
        val body = NativeHttp.get(
            loginUrl,
            headers = mapOf("Referer" to "https://y.qq.com/"),
            userAgent = QQ_WINDOWS_USER_AGENT,
        )
        val uuid = parseWxUuid(body)
        if (uuid.isEmpty()) throw IllegalStateException("qq wx qr connect missing uuid")
        val key = qqQuery(
            "type" to "wx",
            "uuid" to uuid,
            "state" to state,
        )
        loginSessions[sessionId] = QQLoginState("wx", emptyMap())
        return QRCreateResult(
            sessionId = sessionId,
            session = QRSession(
                key = key,
                url = loginUrl,
                imageUrl = "https://open.weixin.qq.com/connect/qrcode/$uuid",
            ),
        )
    }

    private suspend fun checkWxLogin(key: String): QRCheckResult {
        val params = parseQuery(key)
        val uuid = params["uuid"]?.trim().orEmpty()
        if (uuid.isEmpty()) throw IllegalArgumentException("qq wx qr login key missing uuid")
        var state = params["state"]?.trim().orEmpty()
        if (state.isEmpty()) state = "STATE"

        val url = QQ_WX_QR_CHECK_API + "?" + qqQuery(
            "uuid" to uuid,
            "_" to System.currentTimeMillis().toString(),
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to QQ_WX_QR_CONNECT_API),
            userAgent = QQ_WINDOWS_USER_AGENT,
        )
        val code = Regex("wx_errcode\\s*=\\s*'?([0-9]+)'?").find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val wxCode = Regex("""wx_code\s*=\s*["']([^"']*)["']""").find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val status = when (code) {
            "405" -> "success"
            "402" -> "expired"
            "404" -> "scanned"
            "408" -> "waiting"
            else -> "failed"
        }
        val message = when (code) {
            "405" -> "登录成功"
            "402" -> "二维码已过期"
            "404" -> "已扫码，请在微信中确认"
            "408" -> "等待扫码中"
            else -> body.trim()
        }
        if (status != "success") return QRCheckResult(status, message, "")
        if (wxCode.isEmpty()) return QRCheckResult("failed", "wechat auth code missing", "")

        return try {
            val cookies = fetchWxLoginCookies(wxCode)
            val normalized = normalizeQQMusicCookies(cookies)
            QRCheckResult("success", message, joinCookies(normalized))
        } catch (error: Exception) {
            QRCheckResult("failed", error.message ?: "qq wx login failed", "")
        }
    }

    private suspend fun fetchWxLoginCookies(wxCode: String): MutableMap<String, String> {
        val payload = buildJsonObject {
            putJsonObject("comm") {
                put("tmeAppID", "qqmusic")
                put("tmeLoginType", "1")
                put("g_tk", 5381)
                put("platform", "yqq")
                put("ct", 24)
                put("cv", 0)
            }
            putJsonObject("req") {
                put("module", "music.login.LoginServer")
                put("method", "Login")
                putJsonObject("param") {
                    put("strAppid", QQ_WX_APP_ID)
                    put("code", wxCode)
                }
            }
        }.toString()
        var lastError: Exception? = null
        for (endpoint in QQ_WX_LOGIN_ENDPOINTS) {
            try {
                val response = NativeHttp.postJson(
                    endpoint,
                    payload,
                    headers = mapOf(
                        "Referer" to QQ_WX_REDIRECT_URI,
                        "Origin" to "https://y.qq.com",
                        "Accept" to "*/*",
                    ),
                    cookie = "login_type=2",
                    userAgent = QQ_WINDOWS_USER_AGENT,
                )
                val parsed = json.decodeFromString<QQWxLoginResponse>(response.body)
                if (parsed.code != 0 || parsed.req.code != 0) {
                    val message = firstNonEmpty(parsed.req.message, parsed.req.msg, parsed.message, parsed.msg)
                    lastError = IllegalStateException(
                        "qq wx login api error: $message (code ${parsed.code}, req code ${parsed.req.code})"
                    )
                    continue
                }
                val cookies = LinkedHashMap<String, String>()
                response.cookies.forEach { (name, value) -> cookies[name] = value }
                for ((name, value) in qqWxLoginDataCookies(parsed.req.data)) {
                    if (cookies[name].isNullOrEmpty()) cookies[name] = value
                }
                return cookies
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("qq wx login failed")
    }

    private fun qqWxLoginDataCookies(data: Map<String, JsonElement>): Map<String, String> {
        fun value(keys: List<String>): String {
            for (key in keys) {
                val primitive = data[key] as? JsonPrimitive ?: continue
                if (primitive.isString) {
                    val text = primitive.content.trim()
                    if (text.isNotEmpty()) return text
                } else {
                    val number = primitive.content.toDoubleOrNull() ?: continue
                    if (number > 0) return number.toLong().toString()
                }
            }
            return ""
        }

        val result = LinkedHashMap<String, String>()
        val musicId = value(listOf("musicid", "musicId", "userid", "user_id", "uin"))
        if (musicId.isNotEmpty()) result["musicid"] = musicId
        val musicKey = value(listOf("musickey", "music_key", "qqmusic_key", "qm_keyst", "strMusicKey"))
        if (musicKey.isNotEmpty()) {
            result["musickey"] = musicKey
            result["qqmusic_key"] = musicKey
            result["qm_keyst"] = musicKey
        }
        val refreshKey = value(listOf("refresh_key", "refreshKey"))
        if (refreshKey.isNotEmpty()) result["refresh_key"] = refreshKey
        val refreshToken = value(listOf("refresh_token", "refreshToken"))
        if (refreshToken.isNotEmpty()) result["refresh_token"] = refreshToken
        val openId = value(listOf("openid", "openId", "wxopenid", "strOpenid"))
        if (openId.isNotEmpty()) {
            result["openid"] = openId
            result["wxopenid"] = openId
        }
        val unionId = value(listOf("unionid", "unionId", "wxunionid", "strUnionid"))
        if (unionId.isNotEmpty()) {
            result["unionid"] = unionId
            result["wxunionid"] = unionId
        }
        val accessToken = value(listOf("access_token", "accessToken", "wxaccess_token"))
        if (accessToken.isNotEmpty()) result["wxaccess_token"] = accessToken
        return result
    }

    private fun normalizeQQMusicCookies(cookies: Map<String, String>): MutableMap<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((key, value) in cookies) {
            if (key.isNotBlank() && value.isNotBlank()) result[key] = value
        }
        if (result["uin"].isNullOrEmpty()) {
            result["uin"] = firstNonEmpty(
                result["ptui_loginuin"].orEmpty(),
                result["luin"].orEmpty(),
                result["pt2gguin"].orEmpty(),
                result["superuin"].orEmpty(),
                result["p_uin"].orEmpty(),
                result["musicid"].orEmpty(),
                result["userid"].orEmpty(),
                result["wxuin"].orEmpty(),
            )
        }
        if (result["qqmusic_key"].isNullOrEmpty()) {
            result["qqmusic_key"] = firstNonEmpty(
                result["qm_keyst"].orEmpty(),
                result["musickey"].orEmpty(),
                result["music_key"].orEmpty(),
                result["p_skey"].orEmpty(),
                result["skey"].orEmpty(),
            )
        }
        if (result["qm_keyst"].isNullOrEmpty()) {
            result["qm_keyst"] = firstNonEmpty(
                result["qqmusic_key"].orEmpty(),
                result["musickey"].orEmpty(),
                result["music_key"].orEmpty(),
                result["p_skey"].orEmpty(),
                result["skey"].orEmpty(),
            )
        }
        return result
    }

    private fun parseWxUuid(raw: String): String {
        val patterns = listOf(
            Regex("""connect/l/qrconnect\?uuid=([A-Za-z0-9_-]+)"""),
            Regex("window\\.QRLogin\\.uuid\\s*=\\s*\"([^\"]+)\""),
            Regex("""/connect/qrcode/([A-Za-z0-9_-]+)"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(raw) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun normalizeLoginType(type: String): String = when (type.trim().lowercase()) {
        "wx", "wechat", "weixin" -> "wx"
        else -> "qq"
    }

    private fun extractQQPlaylistId(link: String): String? {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return null
        val parsed = runCatching { URI(trimmed) }.getOrNull()
        if (parsed != null) {
            val pathValue = parsed.path.orEmpty().lowercase()
            if (pathValue.contains("playlist")) {
                for (key in listOf("id", "disstid", "dissid")) {
                    val playlistId = normalizeQQPlaylistId(uriQueryValue(parsed, key))
                    if (playlistId.isNotEmpty()) return playlistId
                }
            }
            val parts = parsed.path.orEmpty().trim('/').split('/')
            for ((index, part) in parts.withIndex()) {
                if (part.equals("playlist", ignoreCase = true) && index + 1 < parts.size) {
                    val playlistId = normalizeQQPlaylistId(parts[index + 1])
                    if (playlistId.isNotEmpty()) return playlistId
                }
            }
        }
        return Regex("(?:^|/)playlist/(\\d+)(?:[/?#]|$)").find(trimmed)?.groupValues?.getOrNull(1)
    }

    private fun normalizeQQPlaylistId(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.all { it in '0'..'9' }) trimmed else ""
    }

    private fun uriQueryValue(uri: URI, name: String): String {
        val query = uri.rawQuery ?: return ""
        for (part in query.split('&')) {
            val index = part.indexOf('=')
            if (index <= 0) continue
            if (decodeUrlComponent(part.substring(0, index)) == name) {
                return decodeUrlComponent(part.substring(index + 1))
            }
        }
        return ""
    }

    private fun qqPlaylistLink(playlistId: String, uin: String, dirId: String): String {
        if (playlistId.startsWith(QQ_PROFILE_DIR_PREFIX) && dirId.isNotBlank()) {
            val params = mutableListOf("dirid" to dirId.trim())
            if (uin.isNotBlank()) {
                params += "bu" to NativeCrypto.bytesToHex(uin.trim().toByteArray(Charsets.UTF_8), upper = true)
            }
            return "https://y.qq.com/w/myalbum.html?" + qqQuery(params)
        }
        return "https://y.qq.com/n/ryqq/playlist/$playlistId"
    }

    private fun normalizeQqUin(cookie: String): String {
        var uin = firstNonEmpty(
            cookieValue(cookie, "wxuin"),
            cookieValue(cookie, "uin"),
            cookieValue(cookie, "ptui_loginuin"),
            cookieValue(cookie, "luin"),
            cookieValue(cookie, "pt2gguin"),
            cookieValue(cookie, "superuin"),
            cookieValue(cookie, "p_uin"),
            cookieValue(cookie, "musicid"),
            cookieValue(cookie, "userid"),
        )
        if (uin.startsWith("o")) uin = uin.substring(1)
        return uin.trimStart('0').trim()
    }

    private fun cookieValue(cookie: String, key: String): String {
        for (part in cookie.split(';')) {
            val trimmed = part.trim()
            val index = trimmed.indexOf('=')
            if (index <= 0) continue
            if (trimmed.substring(0, index).trim() == key) {
                return trimmed.substring(index + 1).trim()
            }
        }
        return ""
    }

    private fun joinCookies(cookies: Map<String, String>): String =
        cookies.keys.filter { it.isNotBlank() }.sorted()
            .joinToString("; ") { key -> "$key=${cookies[key].orEmpty()}" }

    private fun encodeCookieState(cookies: Map<String, String>): String {
        val raw = joinCookies(cookies)
        if (raw.isEmpty()) return ""
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    private fun decodeCookieState(encoded: String): MutableMap<String, String>? {
        val value = encoded.trim()
        if (value.isEmpty()) return null
        val raw = runCatching {
            String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val result = LinkedHashMap<String, String>()
        for (part in raw.split(';')) {
            val trimmed = part.trim()
            val index = trimmed.indexOf('=')
            if (index <= 0) continue
            val key = trimmed.substring(0, index).trim()
            val cookieValue = trimmed.substring(index + 1).trim()
            if (key.isNotEmpty() && cookieValue.isNotEmpty()) result[key] = cookieValue
        }
        return result
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val index = part.indexOf('=')
            val key = decodeUrlComponent(if (index >= 0) part.substring(0, index) else part)
            val value = if (index >= 0) decodeUrlComponent(part.substring(index + 1)) else ""
            if (key.isNotEmpty() && !result.containsKey(key)) result[key] = value
        }
        return result
    }

    private fun decodeUrlComponent(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun qqQuery(vararg params: Pair<String, String>): String =
        params.sortedBy { it.first }
            .joinToString("&") { (key, value) -> "${goQueryEscape(key)}=${goQueryEscape(value)}" }

    private fun qqQuery(params: Map<String, String>): String =
        params.entries.sortedBy { it.key }
            .joinToString("&") { (key, value) -> "${goQueryEscape(key)}=${goQueryEscape(value)}" }

    private fun qqQuery(params: List<Pair<String, String>>): String =
        params.sortedBy { it.first }
            .joinToString("&") { (key, value) -> "${goQueryEscape(key)}=${goQueryEscape(value)}" }

    private fun goQueryEscape(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val code = byte.toInt() and 0xff
            val char = code.toChar()
            when {
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                    char == '-' || char == '_' || char == '.' || char == '~' -> out.append(char)
                char == ' ' -> out.append('+')
                else -> out.append('%')
                    .append(HEX_UPPER[code ushr 4])
                    .append(HEX_UPPER[code and 0x0f])
            }
        }
        return out.toString()
    }

    private fun stripJsonp(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val start = trimmed.indexOf('(')
        if (start < 0) return body
        val end = trimmed.lastIndexOf(')')
        if (end <= start) return body
        return trimmed.substring(start + 1, end)
    }

    private fun stripJsonpBody(body: String): String {
        val trimmed = body.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end >= start) trimmed.substring(start, end + 1) else body
    }

    private fun qqJsonCodeOk(code: JsonElement?): Boolean {
        if (code == null || code is JsonNull) return true
        val primitive = code as? JsonPrimitive ?: return false
        if (primitive.isString) {
            val value = primitive.content.trim()
            return value.isEmpty() || value == "0"
        }
        return primitive.content.toDoubleOrNull()?.toInt() == 0
    }

    private fun jsonCodeText(code: JsonElement?): String {
        if (code == null || code is JsonNull) return "nil"
        return (code as? JsonPrimitive)?.content ?: code.toString()
    }

    private fun JsonObject.mapString(key: String): String {
        val element = this[key] ?: return ""
        val primitive = element as? JsonPrimitive ?: return ""
        if (primitive.isString) return primitive.content.trim()
        val number = primitive.content.toDoubleOrNull() ?: return ""
        return if (number == number.toLong().toDouble()) number.toLong().toString() else primitive.content.trim()
    }

    private fun JsonObject.mapInt(key: String): Int = mapString(key).toLongOrNull()?.toInt() ?: 0

    private fun firstNonEmpty(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun coverUrl(albumMid: String): String = "$QQ_COVER_PREFIX$albumMid.jpg"

    private fun pickFileSize(size128: Long, size320: Long, sizeFlac: Long, interval: Int): Pair<Long, Int> {
        if (sizeFlac > 0) {
            val bitrate = if (interval > 0) (sizeFlac * 8 / 1000 / interval).toInt() else 800
            return sizeFlac to bitrate
        }
        if (size320 > 0) return size320 to 320
        return size128 to 128
    }

    private fun randomGuid(): String = Random.nextLong(1_000_000_000L, 10_000_000_000L).toString()

    private fun toHttps(cover: String): String =
        if (cover.startsWith("http://")) "https://" + cover.removePrefix("http://") else cover

    private fun normalizeCover(cover: String): String {
        val trimmed = cover.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") -> "https://" + trimmed.removePrefix("http://")
            else -> trimmed
        }
    }

    private fun firstCover(vararg covers: String): String {
        for (raw in covers) {
            val cover = raw.trim()
            if (cover.isEmpty()) continue
            return if (cover.startsWith("http://")) "https://" + cover.removePrefix("http://") else cover
        }
        return ""
    }

    private fun cleanTopListIntro(intro: String): String =
        intro.replace("<br>", " ").replace("<br/>", " ").replace("<br />", " ").trim()

    private fun hash33(value: String): Int {
        var hash = 0
        for (char in value) {
            hash += (hash shl 5) + char.code
        }
        return hash and 0x7fffffff
    }

    private fun gtk33(value: String): Int {
        var hash = 5381
        for (char in value) {
            hash += (hash shl 5) + char.code
        }
        return hash and 0x7fffffff
    }

    private data class QQLoginState(val type: String, val cookies: Map<String, String>)

    private companion object {
        const val QQ_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1"
        const val QQ_WINDOWS_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val QQ_CHROME_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
        const val QQ_IPHONE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148"
        const val QQ_LOGIN_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
        const val QQ_SEARCH_REFERER = "http://m.y.qq.com"
        const val QQ_DOWNLOAD_REFERER = "http://y.qq.com"
        const val QQ_LYRIC_REFERER = "https://y.qq.com/portal/player.html"
        const val QQ_COVER_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R300x300M000"

        const val QQ_MUSICU_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val QQ_SEARCH_API = "http://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp"
        const val QQ_SONG_DETAIL_API = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
        const val QQ_PLAYLIST_SEARCH_API = "http://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist"
        const val QQ_CATEGORY_API = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_tag_conf.fcg"
        const val QQ_CATEGORY_PLAYLIST_API = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg"
        const val QQ_USER_CREATED_API = "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss"
        const val QQ_PROFILE_ORDER_API = "https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg"
        const val QQ_PROFILE_DIR_API = "http://s.plcloud.music.qq.com/fcgi-bin/fcg_musiclist_getinfo.fcg"
        const val QQ_TOP_LIST_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val QQ_TOP_LIST_SONGS_API = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg"
        const val QQ_HOT_SONG_TOP_LIST_ID = "26"
        const val QQ_TOP_LIST_PAGE_SIZE = 50

        const val QQ_FAVORITE_PLAYLIST_ID = "profile:favorites"
        const val QQ_PROFILE_DIR_PREFIX = "profile:dir:"

        const val QQ_QR_LOGIN_PAGE_API = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin"
        const val QQ_QR_SHOW_API = "https://xui.ptlogin2.qq.com/ssl/ptqrshow"
        const val QQ_QR_CHECK_API = "https://xui.ptlogin2.qq.com/ssl/ptqrlogin"
        const val QQ_OAUTH_LOGIN_JUMP = "https://graph.qq.com/oauth2.0/login_jump"
        const val QQ_OAUTH_AUTHORIZE = "https://graph.qq.com/oauth2.0/authorize"
        const val QQ_OAUTH_CLIENT_ID = "100497308"
        const val QQ_OAUTH_SCOPE = "get_user_info,get_app_friends"
        const val QQ_OAUTH_REDIRECT_URI =
            "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https%3A%2F%2Fy.qq.com%2F"
        const val QQ_WX_QR_CONNECT_API = "https://open.weixin.qq.com/connect/qrconnect"
        const val QQ_WX_QR_CHECK_API = "https://lp.open.weixin.qq.com/connect/l/qrconnect"
        const val QQ_WX_REDIRECT_URI =
            "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/"
        const val QQ_WX_APP_ID = "wx48db31d50e334801"

        val QQ_PLAYLIST_DETAIL_ENDPOINTS = listOf(
            "https://i.y.qq.com/qzone-music/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg",
            "http://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg",
        )
        val QQ_WX_LOGIN_ENDPOINTS = listOf(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            "https://szu.y.qq.com/cgi-bin/musicu.fcg",
            "https://shu.y.qq.com/cgi-bin/musicu.fcg",
        )
        val HEX_UPPER = "0123456789ABCDEF".toCharArray()
    }
}

/**
 * 会员产品与等级：svip/超级会员，vip/豪华绿钻；identity.level 或 userinfo.music_level 是会员等级。
 */
internal fun qqMembershipLabel(data: JsonObject?): String? {
    data ?: return null
    val identity = data["identity"] as? JsonObject
    fun number(source: JsonObject?, key: String): Int =
        source?.get(key)?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    val svip = listOf(
        number(data, "svip"),
        number(identity, "svip"),
        number(identity, "HugeVip"),
        number(identity, "huge_vip"),
    ).any { it > 0 }
    val vip = listOf(
        number(identity, "vip"),
        number(identity, "ExpVip"),
        number(identity, "exp_vip"),
        number(identity, "GroupVipFlag"),
        number(identity, "CPLoverFlag"),
        number(identity, "ChildVip"),
    ).any { it > 0 }
    if (!svip && !vip) return null
    val level = number(identity, "level").takeIf { it > 0 }
        ?: number(data["userinfo"] as? JsonObject, "music_level").takeIf { it > 0 }
        ?: 0
    val name = if (svip) "超级会员" else "豪华绿钻"
    return if (level > 0) "$name V$level" else name
}

@Serializable
private data class QQSearchResponse(val data: QQSearchData = QQSearchData())

@Serializable
private data class QQSearchData(val song: QQSearchSong = QQSearchSong())

@Serializable
private data class QQSearchSong(val list: List<QQSearchItem> = emptyList())

@Serializable
private data class QQSearchItem(
    val songid: Long = 0,
    val songname: String = "",
    val songmid: String = "",
    val albumname: String = "",
    val albummid: String = "",
    val interval: Int = 0,
    val size128: Long = 0,
    val size320: Long = 0,
    val sizeflac: Long = 0,
    val singer: List<QQSinger> = emptyList(),
    val pay: QQPay = QQPay(),
)

@Serializable
private data class QQSinger(val name: String = "", val mid: String = "")

@Serializable
private data class QQPay(
    val paydownload: Int = 0,
    val payplay: Int = 0,
    val paytrackprice: Int = 0,
)

@Serializable
private data class QQAlbumSearchResponse(val data: QQAlbumSearchData = QQAlbumSearchData())

@Serializable
private data class QQAlbumSearchData(val album: QQAlbumSearchAlbum = QQAlbumSearchAlbum())

@Serializable
private data class QQAlbumSearchAlbum(val list: List<QQAlbumSearchItem> = emptyList())

@Serializable
private data class QQAlbumSearchItem(
    @SerialName("albumID") val albumID: Long = 0,
    @SerialName("albumMID") val albumMID: String = "",
    @SerialName("albumName") val albumName: String = "",
    val publicTime: String = "",
    val singerName: String = "",
)

@Serializable
private data class QQAlbumDetailResponse(
    val code: Int = 0,
    val album: QQAlbumNode = QQAlbumNode(),
)

@Serializable
private data class QQAlbumNode(
    val code: Int = 0,
    val data: QQAlbumData = QQAlbumData(),
)

@Serializable
private data class QQAlbumData(
    val basicInfo: QQAlbumBasic = QQAlbumBasic(),
    val company: QQCompany = QQCompany(),
    val singer: QQAlbumSingers = QQAlbumSingers(),
)

@Serializable
private data class QQAlbumBasic(
    val albumID: Long = 0,
    val albumMid: String = "",
    val albumName: String = "",
    val publishDate: String = "",
    val desc: String = "",
)

@Serializable
private data class QQCompany(val name: String = "")

@Serializable
private data class QQAlbumSingers(val singerList: List<QQSinger> = emptyList())

@Serializable
private data class QQAlbumSongsResponse(
    val code: Int = 0,
    val album: QQAlbumSongsNode = QQAlbumSongsNode(),
)

@Serializable
private data class QQAlbumSongsNode(
    val code: Int = 0,
    val data: QQAlbumSongsData = QQAlbumSongsData(),
)

@Serializable
private data class QQAlbumSongsData(
    val totalNum: Int = 0,
    val songList: List<QQAlbumSongEntry> = emptyList(),
)

@Serializable
private data class QQAlbumSongEntry(val songInfo: QQAlbumSongInfo = QQAlbumSongInfo())

@Serializable
private data class QQAlbumSongInfo(
    val mid: String = "",
    val name: String = "",
    val interval: Int = 0,
    val singer: List<QQSinger> = emptyList(),
    val album: QQAlbumRef = QQAlbumRef(),
    val file: QQFileSize = QQFileSize(),
    val pay: QQPayPlay = QQPayPlay(),
)

@Serializable
private data class QQAlbumRef(
    val id: Long = 0,
    val mid: String = "",
    val name: String = "",
)

@Serializable
private data class QQFileSize(
    @SerialName("size_128mp3") val size128Mp3: Long = 0,
    @SerialName("size_320mp3") val size320Mp3: Long = 0,
    @SerialName("size_flac") val sizeFlac: Long = 0,
)

@Serializable
private data class QQPayPlay(@SerialName("pay_play") val payPlay: Int = 0)

@Serializable
private data class QQPayPlayLegacy(val payplay: Int = 0)

@Serializable
private data class QQPlaylistDetailResponse(
    val code: Int = 0,
    val subcode: Int = 0,
    val msg: String = "",
    val cdlist: List<QQCdlist> = emptyList(),
)

@Serializable
private data class QQCdlist(
    val dissname: String = "",
    val logo: String = "",
    val nickname: String = "",
    val desc: String = "",
    val visitnum: Int = 0,
    val songnum: Int = 0,
    val songlist: List<QQCdSong> = emptyList(),
)

@Serializable
private data class QQCdSong(
    val songid: Long = 0,
    val songname: String = "",
    val songmid: String = "",
    val albumname: String = "",
    val albummid: String = "",
    val interval: Int = 0,
    val size128: Long = 0,
    val size320: Long = 0,
    val sizeflac: Long = 0,
    val pay: QQPayPlayLegacy = QQPayPlayLegacy(),
    val singer: List<QQSinger> = emptyList(),
)

@Serializable
private data class QQPlaylistSearchResponse(
    val code: Int = 0,
    val data: QQPlaylistSearchData = QQPlaylistSearchData(),
    val message: String = "",
)

@Serializable
private data class QQPlaylistSearchData(val list: List<QQSearchPlaylistItem> = emptyList())

@Serializable
private data class QQSearchPlaylistItem(
    val dissid: String = "",
    val dissname: String = "",
    val imgurl: String = "",
    @SerialName("song_count") val songCount: Int = 0,
    val listennum: Int = 0,
    val creator: QQCreator = QQCreator(),
)

@Serializable
private data class QQCreator(val name: String = "")

@Serializable
private data class QQCategoryResponse(
    val code: Int = 0,
    val data: QQCategoryData = QQCategoryData(),
    val message: String = "",
)

@Serializable
private data class QQCategoryData(val categories: List<QQCategoryGroup> = emptyList())

@Serializable
private data class QQCategoryGroup(
    val categoryGroupName: String = "",
    val items: List<QQCategoryItem> = emptyList(),
)

@Serializable
private data class QQCategoryItem(
    val categoryId: Int = 0,
    val categoryName: String = "",
    val usable: Int = 0,
)

@Serializable
private data class QQCategoryPlaylistResponse(
    val code: Int = 0,
    val data: QQCategoryPlaylistData = QQCategoryPlaylistData(),
    val message: String = "",
)

@Serializable
private data class QQCategoryPlaylistData(val list: List<QQCategoryPlaylistItem> = emptyList())

@Serializable
private data class QQCategoryPlaylistItem(
    val dissid: String = "",
    val dissname: String = "",
    val imgurl: String = "",
    val introduction: String = "",
    val listennum: Int = 0,
    @SerialName("song_count") val songCount: Int = 0,
    @SerialName("song_num") val songNum: Int = 0,
    val creator: QQCreator = QQCreator(),
)

@Serializable
private data class QQRecommendedResponse(
    val code: Int = 0,
    val recomPlaylist: QQRecomNode = QQRecomNode(),
)

@Serializable
private data class QQRecomNode(val data: QQRecomData = QQRecomData())

@Serializable
private data class QQRecomData(@SerialName("v_hot") val vHot: List<QQHotPlaylist> = emptyList())

@Serializable
private data class QQHotPlaylist(
    @SerialName("content_id") val contentId: Long = 0,
    val title: String = "",
    val cover: String = "",
    @SerialName("listen_num") val listenNum: Int = 0,
    @SerialName("song_cnt") val songCnt: Int = 0,
    @SerialName("song_count") val songCount: Int = 0,
    val username: String = "",
)

@Serializable
private data class QQUserPlaylistResponse(
    val code: Int = 0,
    val data: QQUserPlaylistData = QQUserPlaylistData(),
    val message: String = "",
)

@Serializable
private data class QQUserPlaylistData(
    val disslist: List<QQUserDiss> = emptyList(),
    val list: List<QQUserSubPlaylist> = emptyList(),
)

@Serializable
private data class QQUserDiss(
    val dirid: Long = 0,
    val dissid: Long = 0,
    val tid: Long = 0,
    @SerialName("diss_name") val dissName: String = "",
    val title: String = "",
    @SerialName("diss_cover") val dissCover: String = "",
    val cover: String = "",
    @SerialName("song_cnt") val songCnt: Int = 0,
    @SerialName("song_num") val songNum: Int = 0,
    @SerialName("song_count") val songCount: Int = 0,
    @SerialName("listen_num") val listenNum: Int = 0,
    val visitnum: Int = 0,
    @SerialName("diss_desc") val dissDesc: String = "",
    val desc: String = "",
    @SerialName("commit_time") val commitTime: String = "",
)

@Serializable
private data class QQUserSubPlaylist(
    val dissid: String = "",
    val dissname: String = "",
    val imgurl: String = "",
    @SerialName("song_count") val songCount: Int = 0,
    @SerialName("song_num") val songNum: Int = 0,
    val listennum: Int = 0,
    val introduction: String = "",
)

@Serializable
private data class QQProfilePlaylistResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: QQProfilePlaylistData = QQProfilePlaylistData(),
)

@Serializable
private data class QQProfilePlaylistData(val cdlist: List<QQProfilePlaylistItem> = emptyList())

@Serializable
private data class QQProfilePlaylistItem(
    val dissid: Long = 0,
    val dissname: String = "",
    val songnum: Int = 0,
    val listennum: Int = 0,
    val logo: String = "",
    val nickname: String = "",
    val uin: Long = 0,
)

@Serializable
private data class QQProfileSongsResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: QQProfileSongsData = QQProfileSongsData(),
)

@Serializable
private data class QQProfileSongsData(
    val totalsong: Int = 0,
    val songlist: List<QQProfileSongEntry> = emptyList(),
)

@Serializable
private data class QQProfileSongEntry(val data: QQProfileSongData = QQProfileSongData())

@Serializable
private data class QQProfileSongData(
    val songid: Long = 0,
    val songname: String = "",
    val songmid: String = "",
    val albumname: String = "",
    val albummid: String = "",
    val interval: Int = 0,
    val size128: Long = 0,
    val size320: Long = 0,
    val sizeflac: Long = 0,
    val singer: List<QQSinger> = emptyList(),
)

@Serializable
private data class QQProfileDirResponse(
    val code: JsonElement? = null,
    val msg: String = "",
    @SerialName("SongList") val songList: List<JsonObject> = emptyList(),
)

@Serializable
private data class QQTopListResponse(
    val code: Int = 0,
    val topList: QQTopListNode = QQTopListNode(),
)

@Serializable
private data class QQTopListNode(
    val code: Int = 0,
    val data: QQTopListData = QQTopListData(),
)

@Serializable
private data class QQTopListData(val group: List<QQTopListGroup> = emptyList())

@Serializable
private data class QQTopListGroup(
    val groupId: Int = 0,
    val groupName: String = "",
    val toplist: List<QQTopListItem> = emptyList(),
)

@Serializable
private data class QQTopListItem(
    val topId: Int = 0,
    val title: String = "",
    val titleDetail: String = "",
    val intro: String = "",
    val period: String = "",
    val updateTime: String = "",
    val updateTips: String = "",
    val listenNum: Long = 0,
    val totalNum: Int = 0,
    val topType: Int = 0,
    val updateType: Int = 0,
    val groupType: Int = 0,
    val recType: Int = 0,
    val frontPicUrl: String = "",
    val mbFrontPicUrl: String = "",
    val headPicUrl: String = "",
    val mbHeadPicUrl: String = "",
    val musichallPicUrl: String = "",
)

@Serializable
private data class QQTopSongsResponse(
    val code: Int = 0,
    val songlist: List<QQTopSongEntry> = emptyList(),
)

@Serializable
private data class QQTopSongEntry(
    @SerialName("cur_count") val curCount: String = "",
    val data: QQTopSongData = QQTopSongData(),
)

@Serializable
private data class QQTopSongData(
    val songid: Long = 0,
    val songmid: String = "",
    val songname: String = "",
    val albumname: String = "",
    val albummid: String = "",
    val interval: Int = 0,
    val size128: Long = 0,
    val size320: Long = 0,
    val sizeflac: Long = 0,
    val strMediaMid: String = "",
    val pay: QQPayPlayLegacy = QQPayPlayLegacy(),
    val singer: List<QQSinger> = emptyList(),
)

@Serializable
private data class QQDetailResponse(val data: List<QQDetailSong> = emptyList())

@Serializable
private data class QQDetailSong(
    val id: Long = 0,
    val name: String = "",
    val mid: String = "",
    val album: QQDetailAlbum = QQDetailAlbum(),
    val singer: List<QQSinger> = emptyList(),
    val interval: Int = 0,
)

@Serializable
private data class QQDetailAlbum(val name: String = "", val mid: String = "")

@Serializable
private data class QQVkeyResponse(
    @SerialName("req_1") val req1: QQVkeyNode = QQVkeyNode(),
)

@Serializable
private data class QQVkeyNode(
    val code: Int = 0,
    val data: QQVkeyData = QQVkeyData(),
)

@Serializable
private data class QQVkeyData(
    @SerialName("midurlinfo") val midUrlInfo: List<QQMidUrl> = emptyList(),
)

@Serializable
private data class QQMidUrl(val filename: String = "", val purl: String = "")

@Serializable
private data class QQLyricResponse(
    val code: Int = 0,
    val request: QQLyricRequest = QQLyricRequest(),
)

@Serializable
private data class QQLyricRequest(
    val code: Int = 0,
    val data: QQLyricData = QQLyricData(),
)

@Serializable
private data class QQLyricData(
    val lyric: String = "",
    val trans: String = "",
    val roma: String = "",
)

@Serializable
private data class QQWxLoginResponse(
    val code: Int = 0,
    val message: String = "",
    val msg: String = "",
    val req: QQWxLoginReq = QQWxLoginReq(),
)

@Serializable
private data class QQWxLoginReq(
    val code: Int = 0,
    val message: String = "",
    val msg: String = "",
    val data: Map<String, JsonElement> = emptyMap(),
)

private data class QQrcTime(val ms: Int, val ok: Boolean)

private data class QQrcWord(val start: QQrcTime, val end: QQrcTime, val text: String)

private data class QQrcLine(val start: QQrcTime, val end: QQrcTime, val words: List<QQrcWord>)

private const val QRC_ENCRYPT = 1
private const val QRC_DECRYPT = 0

private val QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)

private val QRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)$""")
private val QRC_WORD_REGEX = Regex("""(?:\[\d+,\d+])?([^()]*)\((\d+),(\d+)\)""")
private val QRC_TAG_REGEX = Regex("""^\[([A-Za-z]+):([^\]]*)]$""")
private val QRC_CONTENT_REGEX = Regex("""(?s)<Lyric_1[^>]*LyricContent="(.*?)"\s*/>""")

private val QRC_SBOX = arrayOf(
    intArrayOf(
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
    ),
    intArrayOf(
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
    ),
    intArrayOf(
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
    ),
    intArrayOf(
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
    ),
    intArrayOf(
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
    ),
    intArrayOf(
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
    ),
    intArrayOf(
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
    ),
    intArrayOf(
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
    ),
)

private val QRC_KEY_SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
private val QRC_KEY_PERM_C = intArrayOf(
    56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
    58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
)
private val QRC_KEY_PERM_D = intArrayOf(
    62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5,
    60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
)
private val QRC_KEY_COMPRESSION = intArrayOf(
    13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
    25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
    50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
)

private fun decryptQrcHex(content: String): String {
    val encrypted = NativeCrypto.hexToBytes(content.trim())
    require(encrypted.size % 8 == 0) {
        "qrc encrypted length ${encrypted.size} is not multiple of 8"
    }
    val plain = ByteArray(encrypted.size)
    var offset = 0
    while (offset < encrypted.size) {
        val block = qrcTripleDesDecrypt(encrypted.copyOfRange(offset, offset + 8))
        block.copyInto(plain, offset)
        offset += 8
    }
    return zlibInflate(plain)
}

private fun qrcTripleDesDecrypt(block: ByteArray): ByteArray {
    val keys = arrayOf(
        qrcKeySchedule(QRC_KEY.copyOfRange(16, 24), QRC_DECRYPT),
        qrcKeySchedule(QRC_KEY.copyOfRange(8, 16), QRC_ENCRYPT),
        qrcKeySchedule(QRC_KEY.copyOfRange(0, 8), QRC_DECRYPT),
    )
    var data = block.copyOf()
    for (index in 0 until 3) {
        data = qrcDesCrypt(data, keys[index])
    }
    return data
}

private fun qrcDesCrypt(input: ByteArray, key: Array<ByteArray>): ByteArray {
    val permuted = qrcInitialPermutation(input)
    var s0 = permuted.first
    var s1 = permuted.second
    for (index in 0 until 15) {
        val previous = s1
        s1 = qrcF(s1, key[index]) xor s0
        s0 = previous
    }
    s0 = qrcF(s1, key[15]) xor s0
    return qrcInversePermutation(s0, s1)
}

private fun qrcKeySchedule(key: ByteArray, mode: Int): Array<ByteArray> {
    val schedule = Array(16) { ByteArray(6) }
    var c = 0
    var d = 0
    for (index in 0 until 28) {
        c += qrcBitNum(key, QRC_KEY_PERM_C[index], 31 - index)
        d += qrcBitNum(key, QRC_KEY_PERM_D[index], 31 - index)
    }
    for (index in 0 until 16) {
        val shift = QRC_KEY_SHIFTS[index]
        c = ((c shl shift) or (c ushr (28 - shift))) and 0xfffffff0.toInt()
        d = ((d shl shift) or (d ushr (28 - shift))) and 0xfffffff0.toInt()
        val target = if (mode == QRC_DECRYPT) 15 - index else index
        for (j in 0 until 24) {
            schedule[target][j / 8] = (
                schedule[target][j / 8].toInt() or
                    qrcBitNumIntr(c, QRC_KEY_COMPRESSION[j], 7 - (j % 8))
                ).toByte()
        }
        for (j in 24 until 48) {
            schedule[target][j / 8] = (
                schedule[target][j / 8].toInt() or
                    qrcBitNumIntr(d, QRC_KEY_COMPRESSION[j] - 27, 7 - (j % 8))
                ).toByte()
        }
    }
    return schedule
}

private fun qrcF(state: Int, key: ByteArray): Int {
    val t1 = qrcBitNumIntl(state, 31, 0) or
        ((state and 0xf0000000.toInt()) ushr 1) or
        qrcBitNumIntl(state, 4, 5) or
        qrcBitNumIntl(state, 3, 6) or
        ((state and 0x0f000000) ushr 3) or
        qrcBitNumIntl(state, 8, 11) or
        qrcBitNumIntl(state, 7, 12) or
        ((state and 0x00f00000) ushr 5) or
        qrcBitNumIntl(state, 12, 17) or
        qrcBitNumIntl(state, 11, 18) or
        ((state and 0x000f0000) ushr 7) or
        qrcBitNumIntl(state, 16, 23)
    val t2 = qrcBitNumIntl(state, 15, 0) or
        ((state and 0x0000f000) shl 15) or
        qrcBitNumIntl(state, 20, 5) or
        qrcBitNumIntl(state, 19, 6) or
        ((state and 0x00000f00) shl 13) or
        qrcBitNumIntl(state, 24, 11) or
        qrcBitNumIntl(state, 23, 12) or
        ((state and 0x000000f0) shl 11) or
        qrcBitNumIntl(state, 28, 17) or
        qrcBitNumIntl(state, 27, 18) or
        ((state and 0x0000000f) shl 9) or
        qrcBitNumIntl(state, 0, 23)
    val lrg = byteArrayOf(
        (t1 ushr 24).toByte(),
        (t1 ushr 16).toByte(),
        (t1 ushr 8).toByte(),
        (t2 ushr 24).toByte(),
        (t2 ushr 16).toByte(),
        (t2 ushr 8).toByte(),
    )
    for (index in lrg.indices) {
        lrg[index] = (lrg[index].toInt() xor key[index].toInt()).toByte()
    }
    val mixed = (QRC_SBOX[0][qrcSBoxBit((lrg[0].toInt() and 0xff) ushr 2)] shl 28) or
        (QRC_SBOX[1][qrcSBoxBit(
            ((lrg[0].toInt() and 0x03) shl 4) or ((lrg[1].toInt() and 0xff) ushr 4)
        )] shl 24) or
        (QRC_SBOX[2][qrcSBoxBit(
            ((lrg[1].toInt() and 0x0f) shl 2) or ((lrg[2].toInt() and 0xff) ushr 6)
        )] shl 20) or
        (QRC_SBOX[3][qrcSBoxBit(lrg[2].toInt() and 0x3f)] shl 16) or
        (QRC_SBOX[4][qrcSBoxBit((lrg[3].toInt() and 0xff) ushr 2)] shl 12) or
        (QRC_SBOX[5][qrcSBoxBit(
            ((lrg[3].toInt() and 0x03) shl 4) or ((lrg[4].toInt() and 0xff) ushr 4)
        )] shl 8) or
        (QRC_SBOX[6][qrcSBoxBit(
            ((lrg[4].toInt() and 0x0f) shl 2) or ((lrg[5].toInt() and 0xff) ushr 6)
        )] shl 4) or
        QRC_SBOX[7][qrcSBoxBit(lrg[5].toInt() and 0x3f)]
    return qrcBitNumIntl(mixed, 15, 0) or qrcBitNumIntl(mixed, 6, 1) or
        qrcBitNumIntl(mixed, 19, 2) or qrcBitNumIntl(mixed, 20, 3) or
        qrcBitNumIntl(mixed, 28, 4) or qrcBitNumIntl(mixed, 11, 5) or
        qrcBitNumIntl(mixed, 27, 6) or qrcBitNumIntl(mixed, 16, 7) or
        qrcBitNumIntl(mixed, 0, 8) or qrcBitNumIntl(mixed, 14, 9) or
        qrcBitNumIntl(mixed, 22, 10) or qrcBitNumIntl(mixed, 25, 11) or
        qrcBitNumIntl(mixed, 4, 12) or qrcBitNumIntl(mixed, 17, 13) or
        qrcBitNumIntl(mixed, 30, 14) or qrcBitNumIntl(mixed, 9, 15) or
        qrcBitNumIntl(mixed, 1, 16) or qrcBitNumIntl(mixed, 7, 17) or
        qrcBitNumIntl(mixed, 23, 18) or qrcBitNumIntl(mixed, 13, 19) or
        qrcBitNumIntl(mixed, 31, 20) or qrcBitNumIntl(mixed, 26, 21) or
        qrcBitNumIntl(mixed, 2, 22) or qrcBitNumIntl(mixed, 8, 23) or
        qrcBitNumIntl(mixed, 18, 24) or qrcBitNumIntl(mixed, 12, 25) or
        qrcBitNumIntl(mixed, 29, 26) or qrcBitNumIntl(mixed, 5, 27) or
        qrcBitNumIntl(mixed, 21, 28) or qrcBitNumIntl(mixed, 10, 29) or
        qrcBitNumIntl(mixed, 3, 30) or qrcBitNumIntl(mixed, 24, 31)
}

private fun qrcInitialPermutation(input: ByteArray): Pair<Int, Int> {
    val s0 = qrcBitNum(input, 57, 31) or qrcBitNum(input, 49, 30) or
        qrcBitNum(input, 41, 29) or qrcBitNum(input, 33, 28) or
        qrcBitNum(input, 25, 27) or qrcBitNum(input, 17, 26) or
        qrcBitNum(input, 9, 25) or qrcBitNum(input, 1, 24) or
        qrcBitNum(input, 59, 23) or qrcBitNum(input, 51, 22) or
        qrcBitNum(input, 43, 21) or qrcBitNum(input, 35, 20) or
        qrcBitNum(input, 27, 19) or qrcBitNum(input, 19, 18) or
        qrcBitNum(input, 11, 17) or qrcBitNum(input, 3, 16) or
        qrcBitNum(input, 61, 15) or qrcBitNum(input, 53, 14) or
        qrcBitNum(input, 45, 13) or qrcBitNum(input, 37, 12) or
        qrcBitNum(input, 29, 11) or qrcBitNum(input, 21, 10) or
        qrcBitNum(input, 13, 9) or qrcBitNum(input, 5, 8) or
        qrcBitNum(input, 63, 7) or qrcBitNum(input, 55, 6) or
        qrcBitNum(input, 47, 5) or qrcBitNum(input, 39, 4) or
        qrcBitNum(input, 31, 3) or qrcBitNum(input, 23, 2) or
        qrcBitNum(input, 15, 1) or qrcBitNum(input, 7, 0)
    val s1 = qrcBitNum(input, 56, 31) or qrcBitNum(input, 48, 30) or
        qrcBitNum(input, 40, 29) or qrcBitNum(input, 32, 28) or
        qrcBitNum(input, 24, 27) or qrcBitNum(input, 16, 26) or
        qrcBitNum(input, 8, 25) or qrcBitNum(input, 0, 24) or
        qrcBitNum(input, 58, 23) or qrcBitNum(input, 50, 22) or
        qrcBitNum(input, 42, 21) or qrcBitNum(input, 34, 20) or
        qrcBitNum(input, 26, 19) or qrcBitNum(input, 18, 18) or
        qrcBitNum(input, 10, 17) or qrcBitNum(input, 2, 16) or
        qrcBitNum(input, 60, 15) or qrcBitNum(input, 52, 14) or
        qrcBitNum(input, 44, 13) or qrcBitNum(input, 36, 12) or
        qrcBitNum(input, 28, 11) or qrcBitNum(input, 20, 10) or
        qrcBitNum(input, 12, 9) or qrcBitNum(input, 4, 8) or
        qrcBitNum(input, 62, 7) or qrcBitNum(input, 54, 6) or
        qrcBitNum(input, 46, 5) or qrcBitNum(input, 38, 4) or
        qrcBitNum(input, 30, 3) or qrcBitNum(input, 22, 2) or
        qrcBitNum(input, 14, 1) or qrcBitNum(input, 6, 0)
    return s0 to s1
}

private fun qrcInversePermutation(s0: Int, s1: Int): ByteArray {
    val data = ByteArray(8)
    data[3] = (
        qrcBitNumIntr(s1, 7, 7) or qrcBitNumIntr(s0, 7, 6) or
            qrcBitNumIntr(s1, 15, 5) or qrcBitNumIntr(s0, 15, 4) or
            qrcBitNumIntr(s1, 23, 3) or qrcBitNumIntr(s0, 23, 2) or
            qrcBitNumIntr(s1, 31, 1) or qrcBitNumIntr(s0, 31, 0)
        ).toByte()
    data[2] = (
        qrcBitNumIntr(s1, 6, 7) or qrcBitNumIntr(s0, 6, 6) or
            qrcBitNumIntr(s1, 14, 5) or qrcBitNumIntr(s0, 14, 4) or
            qrcBitNumIntr(s1, 22, 3) or qrcBitNumIntr(s0, 22, 2) or
            qrcBitNumIntr(s1, 30, 1) or qrcBitNumIntr(s0, 30, 0)
        ).toByte()
    data[1] = (
        qrcBitNumIntr(s1, 5, 7) or qrcBitNumIntr(s0, 5, 6) or
            qrcBitNumIntr(s1, 13, 5) or qrcBitNumIntr(s0, 13, 4) or
            qrcBitNumIntr(s1, 21, 3) or qrcBitNumIntr(s0, 21, 2) or
            qrcBitNumIntr(s1, 29, 1) or qrcBitNumIntr(s0, 29, 0)
        ).toByte()
    data[0] = (
        qrcBitNumIntr(s1, 4, 7) or qrcBitNumIntr(s0, 4, 6) or
            qrcBitNumIntr(s1, 12, 5) or qrcBitNumIntr(s0, 12, 4) or
            qrcBitNumIntr(s1, 20, 3) or qrcBitNumIntr(s0, 20, 2) or
            qrcBitNumIntr(s1, 28, 1) or qrcBitNumIntr(s0, 28, 0)
        ).toByte()
    data[7] = (
        qrcBitNumIntr(s1, 3, 7) or qrcBitNumIntr(s0, 3, 6) or
            qrcBitNumIntr(s1, 11, 5) or qrcBitNumIntr(s0, 11, 4) or
            qrcBitNumIntr(s1, 19, 3) or qrcBitNumIntr(s0, 19, 2) or
            qrcBitNumIntr(s1, 27, 1) or qrcBitNumIntr(s0, 27, 0)
        ).toByte()
    data[6] = (
        qrcBitNumIntr(s1, 2, 7) or qrcBitNumIntr(s0, 2, 6) or
            qrcBitNumIntr(s1, 10, 5) or qrcBitNumIntr(s0, 10, 4) or
            qrcBitNumIntr(s1, 18, 3) or qrcBitNumIntr(s0, 18, 2) or
            qrcBitNumIntr(s1, 26, 1) or qrcBitNumIntr(s0, 26, 0)
        ).toByte()
    data[5] = (
        qrcBitNumIntr(s1, 1, 7) or qrcBitNumIntr(s0, 1, 6) or
            qrcBitNumIntr(s1, 9, 5) or qrcBitNumIntr(s0, 9, 4) or
            qrcBitNumIntr(s1, 17, 3) or qrcBitNumIntr(s0, 17, 2) or
            qrcBitNumIntr(s1, 25, 1) or qrcBitNumIntr(s0, 25, 0)
        ).toByte()
    data[4] = (
        qrcBitNumIntr(s1, 0, 7) or qrcBitNumIntr(s0, 0, 6) or
            qrcBitNumIntr(s1, 8, 5) or qrcBitNumIntr(s0, 8, 4) or
            qrcBitNumIntr(s1, 16, 3) or qrcBitNumIntr(s0, 16, 2) or
            qrcBitNumIntr(s1, 24, 1) or qrcBitNumIntr(s0, 24, 0)
        ).toByte()
    return data
}

private fun qrcBitNum(data: ByteArray, bit: Int, target: Int): Int =
    (((data[(bit / 32) * 4 + 3 - (bit % 32) / 8].toInt() and 0xff) ushr (7 - bit % 8)) and 1) shl target

private fun qrcBitNumIntr(value: Int, bit: Int, target: Int): Int =
    ((value ushr (31 - bit)) and 1) shl target

private fun qrcBitNumIntl(value: Int, bit: Int, target: Int): Int =
    ((value shl bit) and 0x80000000.toInt()) ushr target

private fun qrcSBoxBit(value: Int): Int =
    (value and 0x20) or ((value and 0x1f) shr 1) or ((value and 1) shl 4)

private fun zlibInflate(data: ByteArray): String {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0) break
            out.write(buffer, 0, count)
        }
        return out.toString(Charsets.UTF_8.name())
    } finally {
        inflater.end()
    }
}

private fun parseQrc(raw: String): Pair<Map<String, String>, List<QQrcLine>> {
    var text = raw
    val contentMatch = QRC_CONTENT_REGEX.find(text)
    if (contentMatch != null) {
        text = htmlUnescape(contentMatch.groupValues[1])
    }
    val tags = mutableMapOf<String, String>()
    val lines = mutableListOf<QQrcLine>()
    for (rawLine in text.split('\n')) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val tagMatch = QRC_TAG_REGEX.matchEntire(line)
        if (tagMatch != null) {
            tags[tagMatch.groupValues[1]] = tagMatch.groupValues[2]
            continue
        }
        val match = QRC_LINE_REGEX.matchEntire(line) ?: continue
        val start = match.groupValues[1].toIntOrNull() ?: 0
        val end = start + (match.groupValues[2].toIntOrNull() ?: 0)
        val content = match.groupValues[3]
        val words = mutableListOf<QQrcWord>()
        for (wordMatch in QRC_WORD_REGEX.findAll(content)) {
            val wordStart = wordMatch.groupValues[2].toIntOrNull() ?: 0
            val wordEnd = wordStart + (wordMatch.groupValues[3].toIntOrNull() ?: 0)
            val wordText = wordMatch.groupValues[1]
            if (wordText.isEmpty() || wordText == "\r") continue
            words += QQrcWord(
                start = QQrcTime(wordStart, true),
                end = QQrcTime(wordEnd, true),
                text = wordText,
            )
        }
        if (words.isEmpty() && content.isNotEmpty()) {
            words += QQrcWord(QQrcTime(start, true), QQrcTime(end, true), content)
        }
        lines += QQrcLine(QQrcTime(start, true), QQrcTime(end, true), words)
    }
    return tags to lines
}

private fun convertVerbatimLrc(
    tags: Map<String, String>,
    data: Map<String, List<QQrcLine>>,
    order: List<String>,
): String {
    val builder = StringBuilder()
    for (key in listOf("ti", "ar", "al", "by", "offset")) {
        val value = tags[key]?.trim().orEmpty()
        if (value.isNotEmpty()) {
            builder.append('[').append(key).append(':').append(value).append("]\n")
        }
    }
    if (tags.isNotEmpty()) builder.append('\n')

    val original = data["orig"].orEmpty()
    for ((index, origLine) in original.withIndex()) {
        val start = qrcLineStart(origLine)
        for (language in order) {
            val lines = data[language].orEmpty()
            if (lines.isEmpty()) continue
            val lineIndex = mappedQrcIndex(lines, index, origLine)
            if (lineIndex < 0 || lineIndex >= lines.size) continue
            if (!qrcLineHasText(lines[lineIndex])) continue
            builder.append(lineToLrc(lines[lineIndex], start)).append('\n')
        }
    }
    return builder.toString().trimEnd('\n')
}

private fun lineToLrc(line: QQrcLine, forcedStart: QQrcTime): String {
    val start = if (forcedStart.ok) forcedStart else qrcLineStart(line)
    val builder = StringBuilder()
    if (start.ok) {
        builder.append('[').append(formatLrcTime(start.ms)).append(']')
    }
    for (word in line.words) {
        if (word.text.isEmpty()) continue
        if (word.start.ok) {
            builder.append('<').append(formatLrcTime(word.start.ms)).append('>')
        }
        builder.append(word.text)
    }
    return builder.toString()
}

private fun mappedQrcIndex(lines: List<QQrcLine>, index: Int, original: QQrcLine): Int {
    if (original.start.ok) {
        for ((lineIndex, line) in lines.withIndex()) {
            if (line.start.ok && line.start.ms == original.start.ms && qrcLineHasText(line)) {
                return lineIndex
            }
        }
        if (lines.any { it.start.ok }) return -1
    }
    return if (index < lines.size) index else -1
}

private fun qrcLineStart(line: QQrcLine): QQrcTime =
    line.words.firstOrNull()?.start?.takeIf { it.ok } ?: line.start

private fun qrcLineHasText(line: QQrcLine): Boolean =
    line.words.any { it.text.trim().isNotEmpty() }

private fun formatLrcTime(milliseconds: Int): String {
    val ms = milliseconds.coerceAtLeast(0)
    return String.format(
        Locale.ROOT,
        "%02d:%02d.%02d",
        ms / 60000,
        (ms % 60000) / 1000,
        (ms % 1000) / 10,
    )
}

private fun htmlUnescape(value: String): String {
    if ('&' !in value) return value
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '&') {
            val semicolon = value.indexOf(';', index + 1)
            if (semicolon in (index + 2)..(index + 12)) {
                val entity = value.substring(index + 1, semicolon)
                val decoded = when {
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.substring(2).toIntOrNull(16)?.let(::codePointString)
                    entity.startsWith("#") ->
                        entity.substring(1).toIntOrNull()?.let(::codePointString)
                    else -> null
                }
                if (decoded != null) {
                    out.append(decoded)
                    index = semicolon + 1
                    continue
                }
            }
        }
        out.append(char)
        index++
    }
    return out.toString()
}

private fun codePointString(codePoint: Int): String? =
    runCatching { String(Character.toChars(codePoint)) }.getOrNull()
