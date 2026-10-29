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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val KUGOU_INFO = ProviderInfo(
    id = "kugou",
    name = "酷狗音乐",
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
    topLists = true,
    topListSongs = true,
    hotSongs = true,
)

class KugouProvider : BaseNativeProvider(KUGOU_INFO) {
    private val json = NativeHttp.json

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
        val params = mapOf(
            "keyword" to keyword,
            "platform" to "WebFilter",
            "format" to "json",
            "page" to page.toString(),
            "pagesize" to limit.toString(),
            "userid" to "-1",
            "clientver" to "",
            "tag" to "em",
            "filter" to "2",
            "iscorrection" to "1",
            "privilege_filter" to "0",
            "_" to System.currentTimeMillis().toString(),
        )
        val url = "http://songsearch.kugou.com/song_search_v2?" + query(params)
        val firstBody = NativeHttp.get(
            url,
            cookie = cookie,
            randomIp = true,
            userAgent = SEARCH_USER_AGENT,
        )
        var resp = json.decodeFromString<KugouSearchResponse>(firstBody)
        if (resp.data.lists.isEmpty() && cookie.trim().isNotEmpty()) {
            val retryBody = runCatching {
                NativeHttp.get(url, randomIp = true, userAgent = SEARCH_USER_AGENT)
            }.getOrNull()
            val retry = retryBody?.let { runCatching { json.decodeFromString<KugouSearchResponse>(it) }.getOrNull() }
            if (retry != null && retry.data.lists.isNotEmpty()) resp = retry
        }

        val isVip = runCatching { isVipAccount(cookie, VipCache()) }.getOrDefault(false)
        return resp.data.lists.map { item ->
            var finalHash = item.fileHash
            if (item.privilege != 10 && isVip) finalHash = item.sqFileHash
            if (finalHash.trim().isEmpty()) {
                finalHash = firstNonEmpty(
                    item.sqFileHash,
                    item.hqFileHash,
                    item.resFileHash,
                    item.transParam.ogg320Hash,
                    item.fileHash,
                    item.transParam.ogg128Hash,
                )
            }

            var size = item.fileSize.fileSizeLong()
            when (finalHash) {
                item.sqFileHash -> if (item.sqFileSize > 0) size = item.sqFileSize
                item.hqFileHash -> if (item.hqFileSize > 0) size = item.hqFileSize
                item.resFileHash -> if (item.resFileSize > 0) size = item.resFileSize
                item.transParam.ogg320Hash -> if (item.transParam.ogg320FileSize > 0) size = item.transParam.ogg320FileSize
                item.transParam.ogg128Hash -> if (item.transParam.ogg128FileSize > 0) size = item.transParam.ogg128FileSize
            }

            val bitrate = if (item.duration > 0 && size > 0) (size * 8 / 1000 / item.duration).toInt() else 0
            Song(
                id = finalHash,
                name = cleanKugouText(item.songName),
                artist = cleanKugouText(item.singerName),
                album = cleanKugouText(item.albumName),
                albumId = item.albumId,
                duration = item.duration,
                size = size,
                bitrate = bitrate,
                source = "kugou",
                cover = item.image.replaceFirst("{size}", "240"),
                link = "https://www.kugou.com/song/#hash=$finalHash",
                extra = mapOf(
                    "hash" to finalHash,
                    "ogg_320_hash" to item.transParam.ogg320Hash,
                    "ogg_128_hash" to item.transParam.ogg128Hash,
                    "sq_hash" to item.sqFileHash,
                    "file_hash" to item.fileHash,
                    "res_hash" to item.resFileHash,
                    "mv_hash" to item.mvHash,
                    "hq_hash" to item.hqFileHash,
                    "audio_id" to item.audioId.numericString(),
                    "album_audio_id" to firstNonEmpty(item.mixSongId.numericString(), item.id.numericString()),
                    "album_id" to item.albumId,
                    "privilege" to item.privilege.toString(),
                ),
            )
        }
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val match = KUGOU_HASH_REGEX.find(link)
            ?: throw IllegalArgumentException("invalid kugou link or hash not found")
        return fetchSongInfo(cookie, match.groupValues[1])
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.source != "kugou") throw IllegalArgumentException("source mismatch")
        if (song.url.isNotEmpty()) return song.url

        val hash = song.extra["hash"].orEmpty().ifEmpty { song.id }
        val privilege = song.extra["privilege"]?.trim()?.toIntOrNull() ?: -1
        val vipCache = VipCache()

        if (shouldTryHighQuality(cookie, privilege)) {
            val info = runCatching { fetchVipSongInfo(cookie, song, vipCache) }.getOrNull()
            if (info != null && info.url.isNotEmpty()) return info.url
        }

        val isVip = runCatching { isVipAccount(cookie, vipCache) }.getOrDefault(false)
        if (isVip) {
            val info = runCatching { fetchTrackerSongInfo(cookie, hash) }.getOrNull()
            if (info != null && info.url.isNotEmpty()) return info.url
        }

        return fetchSongInfo(cookie, hash).url
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != "kugou") throw IllegalArgumentException("source mismatch")
        val hash = song.extra["hash"].orEmpty().ifEmpty { song.id }
        val searchUrl = "http://krcs.kugou.com/search?ver=1&client=mobi&duration=${song.duration * 1000}&hash=$hash&album_audio_id="
        val searchBody = NativeHttp.get(
            searchUrl,
            headers = mapOf("Referer" to MOBILE_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        val search = json.decodeFromString<KugouLyricSearchResponse>(searchBody)
        val candidate = search.candidates.firstOrNull() ?: throw IllegalStateException("lyrics not found")

        val downloadUrl =
            "http://lyrics.kugou.com/download?ver=1&client=pc&id=${candidate.id.numericString()}&accesskey=${candidate.accesskey}&fmt=krc&charset=utf8"
        val body = NativeHttp.get(
            downloadUrl,
            headers = mapOf("Referer" to MOBILE_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        val download = json.decodeFromString<KugouLyricDownloadResponse>(body)
        if (download.content.isEmpty()) throw IllegalStateException("lyrics content is empty")

        if (download.contentType == 2 || download.fmt == "lrc") {
            val decoded = Base64.getDecoder().decode(download.content).toString(Charsets.UTF_8).trim()
            if (decoded.isEmpty()) throw IllegalStateException("lyrics content is empty")
            return decoded
        }

        val krc = decodeKrc(download.content)
        return convertKrcToLrc(krc, song)
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val url = "http://mobilecdn.kugou.com/api/v3/search/special?" + query(
            mapOf(
                "keyword" to keyword,
                "platform" to "WebFilter",
                "format" to "json",
                "page" to "1",
                "pagesize" to "10",
                "filter" to "0",
            )
        )
        val body = NativeHttp.get(
            url,
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        val resp = json.decodeFromString<KugouPlaylistSearchResponse>(body)
        return resp.data.info.map { item ->
            Playlist(
                id = item.specialId.toString(),
                name = item.specialName,
                cover = item.imgUrl.replaceFirst("{size}", "240"),
                trackCount = item.songCount,
                playCount = item.playCount.toLong(),
                creator = item.nickName,
                description = item.intro,
                source = "kugou",
                link = "https://www.kugou.com/yy/special/single/${item.specialId}.html",
            )
        }
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> {
        val trimmed = id.trim()
        if (trimmed.startsWith("cloudlist:")) {
            val listId = trimmed.removePrefix("cloudlist:").trim()
            if (listId.isNotEmpty()) return fetchCloudlistDetail(cookie, listId).second
        }
        return fetchPlaylistDetail(cookie, id).second
    }

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        return when {
            link.contains("/yy/special/single/") -> {
                val match = KUGOU_SPECIAL_LINK_REGEX.find(link)
                    ?: throw IllegalArgumentException("invalid kugou playlist link")
                val (playlist, songs) = fetchPlaylistDetail(cookie, match.groupValues[1])
                CollectionResult(playlist, songs)
            }
            link.contains("/songlist/") -> {
                val match = KUGOU_SONGLIST_LINK_REGEX.find(link)
                    ?: throw IllegalArgumentException("invalid kugou songlist link")
                val (playlist, songs) = fetchPlaylistDetail(cookie, match.groupValues[1])
                CollectionResult(playlist, songs)
            }
            else -> throw IllegalArgumentException("invalid kugou playlist link")
        }
    }

    override suspend fun recommendedPlaylists(cookie: String): List<Playlist> {
        val body = NativeHttp.get(
            "http://m.kugou.com/plist/index&json=true",
            headers = mapOf("Referer" to MOBILE_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        if (body.isEmpty() || body[0] != '{') {
            throw IllegalStateException("kugou api returned invalid json: ${body.take(200)}")
        }
        val resp = json.decodeFromString<KugouRecommendedResponse>(body)
        val playlists = resp.plist.list.info.map { item ->
            Playlist(
                id = item.specialId.toString(),
                name = item.specialName,
                cover = item.imgUrl.replaceFirst("{size}", "240"),
                trackCount = item.songCount,
                playCount = item.playCount.toLong(),
                creator = item.username,
                description = item.intro,
                source = "kugou",
                link = "https://www.kugou.com/yy/special/single/${item.specialId}.html",
            )
        }
        if (playlists.isEmpty()) throw IllegalStateException("no recommended playlists found")
        return playlists
    }

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val url = "http://mobilecdn.kugou.com/api/v3/search/album?" + query(
            mapOf(
                "keyword" to keyword,
                "format" to "json",
                "page" to "1",
                "pagesize" to "10",
            )
        )
        val body = NativeHttp.get(
            url,
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        val resp = json.decodeFromString<KugouAlbumSearchResponse>(body)
        if (resp.errcode != 0 || resp.status != 1) {
            throw IllegalStateException("kugou album search api error: status=${resp.status} errcode=${resp.errcode} error=${resp.error}")
        }
        val albums = resp.data.info.mapNotNull { item ->
            if (item.albumId == 0) return@mapNotNull null
            Playlist(
                id = item.albumId.toString(),
                name = item.albumName,
                cover = item.imgUrl.replaceFirst("{size}", "240"),
                trackCount = item.songCount,
                creator = item.singerName,
                description = item.intro,
                source = "kugou",
                link = "https://www.kugou.com/album/${item.albumId}.html",
                extra = mapOf(
                    "type" to "album",
                    "album_id" to item.albumId.toString(),
                    "publish_time" to item.publishTime,
                ),
            )
        }
        if (albums.isEmpty()) throw IllegalStateException("no albums found")
        return albums
    }

    override suspend fun searchArtists(cookie: String, keyword: String): List<Playlist> {
        val url = "http://mobilecdn.kugou.com/api/v3/search/singer?" + query(
            mapOf(
                "keyword" to keyword,
                "format" to "json",
                "page" to "1",
            )
        )
        val body = NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = MOBILE_USER_AGENT)
        val resp = json.decodeFromString<KugouSingerSearchResponse>(body)
        if (resp.errcode != 0 || resp.status != 1) {
            throw IllegalStateException("kugou singer search api error: status=${resp.status} errcode=${resp.errcode} error=${resp.error}")
        }
        // 这个接口不认 pagesize，固定返回一批；详情里才有头像和歌曲数
        val artists = resp.data.orEmpty().take(10).mapNotNull { item ->
            if (item.singerId == 0) return@mapNotNull null
            val info = runCatching { fetchSingerInfo(cookie, item.singerId) }.getOrNull()
            Playlist(
                id = item.singerId.toString(),
                name = info?.singerName?.takeIf { it.isNotBlank() } ?: item.singerName,
                cover = info?.imgUrl.orEmpty().replaceFirst("{size}", "240"),
                trackCount = info?.songCount ?: 0,
                creator = "",
                description = "",
                source = "kugou",
                link = "https://www.kugou.com/singer/${item.singerId}.html",
                extra = mapOf(
                    "type" to "artist",
                    "album_count" to (info?.albumCount ?: 0).toString(),
                ),
            )
        }
        if (artists.isEmpty()) throw IllegalStateException("no artists found")
        return artists
    }

    override suspend fun artistSongs(cookie: String, id: String): List<Song> =
        fetchSingerSongs(cookie, id)

    override suspend fun albumSongs(cookie: String, id: String): List<Song> =
        fetchAlbumDetail(cookie, id).second

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult {
        for (pattern in KUGOU_ALBUM_LINK_REGEXES) {
            val match = pattern.find(link) ?: continue
            val (playlist, songs) = fetchAlbumDetail(cookie, match.groupValues[1])
            return CollectionResult(playlist, songs)
        }
        throw IllegalArgumentException("invalid kugou album link")
    }

    override suspend fun categories(cookie: String): List<PlaylistCategory> {
        val body = NativeHttp.get(
            "http://mobilecdnbj.kugou.com/api/v3/tag/list?pid=0&apiver=2&plat=0",
            headers = mapOf("Referer" to MOBILE_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        val resp = json.decodeFromString<KugouCategoryResponse>(body)
        if (resp.status != 1 || resp.errcode != 0) {
            throw IllegalStateException("kugou playlist category api error: ${resp.error} (status ${resp.status} errcode ${resp.errcode})")
        }
        val result = mutableListOf(
            PlaylistCategory(id = "", name = "全部", group = "全部", source = "kugou")
        )
        for (group in resp.data.info) {
            val groupName = group.name.trim()
            if (group.id > 0 && groupName.isNotEmpty()) {
                result += PlaylistCategory(
                    id = "${group.id}:0",
                    name = groupName,
                    group = groupName,
                    source = "kugou",
                )
            }
            for (child in group.children) {
                val name = child.name.trim()
                if (child.id == 0 || name.isEmpty()) continue
                result += PlaylistCategory(
                    id = "${child.id}:${child.specialTagId}",
                    name = name,
                    group = groupName,
                    source = "kugou",
                    hot = child.isHot == 1,
                )
            }
        }
        return result
    }

    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        var categoryId = id.trim()
        val safePage = page.coerceAtLeast(1)
        val safeLimit = if (limit < 1) 20 else limit
        if (categoryId.isEmpty()) {
            val categoryList = categories(cookie)
            categoryId = categoryList.firstOrNull { it.id.trim().isNotEmpty() }?.id.orEmpty()
            if (categoryId.isEmpty()) throw IllegalStateException("no playlist categories found")
        }
        val (category, tagId) = parseCategoryId(categoryId)
        if (category.isEmpty()) throw IllegalStateException("invalid kugou playlist category id")

        val url = "http://mobilecdnbj.kugou.com/api/v3/tag/specialList?" + query(
            mapOf(
                "plat" to "0",
                "page" to safePage.toString(),
                "tagid" to tagId,
                "pagesize" to safeLimit.toString(),
                "ugc" to "1",
                "id" to category,
                "sort" to "2",
            )
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to MOBILE_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        val resp = json.decodeFromString<KugouCategoryPlaylistResponse>(body)
        if (resp.status != 1 || resp.errcode != 0) {
            throw IllegalStateException("kugou category playlist api error: ${resp.error} (status ${resp.status} errcode ${resp.errcode})")
        }
        val playlists = resp.data.info.mapNotNull { item ->
            val playlistId = if (item.specialId > 0) item.specialId.toString() else item.globalSpecialId.trim()
            val name = item.specialName.trim()
            if (playlistId.isEmpty() || name.isEmpty()) return@mapNotNull null
            val creator = item.username.trim().ifEmpty { item.singerName.trim() }
            Playlist(
                id = playlistId,
                name = name,
                cover = item.imgUrl.replaceFirst("{size}", "240"),
                trackCount = item.songCount,
                playCount = item.playCount.toLong(),
                creator = creator,
                description = item.intro,
                source = "kugou",
                link = "https://www.kugou.com/yy/special/single/$playlistId.html",
                extra = mapOf(
                    "category_id" to categoryId,
                    "id" to category,
                    "tag_id" to tagId,
                    "global_specialid" to item.globalSpecialId,
                    "publish_time" to item.publishTime,
                ),
            )
        }
        if (playlists.isEmpty()) throw IllegalStateException("no category playlists found")
        return playlists
    }

    override suspend fun userPlaylists(cookie: String, page: Int, limit: Int): List<Playlist> {
        val cookieMap = parseKugouCookie(cookie)
        val userID = firstNonEmpty(cookieMap["userid"].orEmpty(), cookieMap["KugooID"].orEmpty())
        if (userID.isEmpty() || userID == "0") {
            throw IllegalStateException("kugou user playlists require userid cookie")
        }
        val safePage = page.coerceAtLeast(1)
        var safeLimit = limit
        if (safeLimit <= 0) safeLimit = 30
        if (safeLimit > 100) safeLimit = 100

        if (kugouHasAppCookie(cookieMap)) {
            return getUserPlaylistsGateway(cookie, cookieMap, userID, safePage, safeLimit)
        }

        val url = "http://m.kugou.com/plist/index/${URLEncoder.encode(userID, "UTF-8")}?" +
            query(mapOf("json" to "true", "page" to safePage.toString(), "pagesize" to safeLimit.toString()))
        val body = try {
            NativeHttp.get(
                url,
                headers = mapOf("Referer" to MOBILE_REFERER),
                cookie = cookie,
                randomIp = true,
                userAgent = MOBILE_USER_AGENT,
            )
        } catch (error: Exception) {
            if (cookieMap["token"].orEmpty().trim().isEmpty() || cookieMap["KUGOU_API_MID"].orEmpty().trim().isEmpty()) throw error
            return getUserPlaylistsGateway(cookie, cookieMap, userID, safePage, safeLimit)
        }
        return try {
            parseKugouUserPlaylists(body, userID)
        } catch (error: Exception) {
            if (cookieMap["token"].orEmpty().trim().isEmpty() || cookieMap["KUGOU_API_MID"].orEmpty().trim().isEmpty()) throw error
            getUserPlaylistsGateway(cookie, cookieMap, userID, safePage, safeLimit)
        }
    }

    override suspend fun topLists(cookie: String): List<TopList> {
        val url = "http://mobilecdn.kugou.com/api/v3/rank/list?" + query(
            mapOf(
                "version" to "9108",
                "plat" to "0",
                "showtype" to "2",
                "parentid" to "0",
                "apiver" to "2",
                "area_code" to "1",
                "withsong" to "1",
                "with_res_tag" to "0",
                "page" to "1",
                "pagesize" to "100",
            )
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to MOBILE_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        val resp = json.decodeFromString<KugouTopListResponse>(body)
        if (resp.status != 1 || resp.errcode != 0) {
            throw IllegalStateException("kugou toplist api error: ${resp.error} (status ${resp.status} errcode ${resp.errcode})")
        }
        return resp.data.info.mapNotNull { item ->
            val name = item.rankName.trim()
            if (item.rankId <= 0 || name.isEmpty()) return@mapNotNull null
            val id = item.rankId.toString()
            val extra = buildMap<String, JsonElement> {
                put("group", JsonPrimitive(kugouTopListGroupName(item.classify)))
                put("classify", JsonPrimitive(item.classify.toString()))
                put("rank_type", JsonPrimitive(item.rankType.toString()))
                put("freq_type", JsonPrimitive(item.frequencyType.toString()))
                put("rank_cid", JsonPrimitive(item.rankCid.toString()))
                if (item.publishDate.isNotEmpty()) put("publish_time", JsonPrimitive(item.publishDate))
                val issue = item.issue.numericString()
                if (issue.isNotEmpty()) put("issue", JsonPrimitive(issue))
            }
            TopList(
                id = id,
                name = name,
                cover = kugouTopListCover(item.imgUrl, item.img9, item.albumImg9, item.imgCover),
                description = cleanTopListIntro(item.intro),
                updateFrequency = item.frequency.trim(),
                trackCount = item.extra.resp.allTotal.toInt(),
                playCount = item.playTimes,
                source = "kugou",
                link = "https://www.kugou.com/yy/rank/home/1-$id.html",
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
        val cookies = initLoginDevice()
        val params = mapOf(
            "appid" to "1001",
            "type" to "1",
            "plat" to "4",
            "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=$KUGOU_LITE_APP_ID&",
            "srcappid" to "2919",
        )
        val body = loginWebGet("https://login-user.kugou.com/v2/qrcode", params, cookies)
        val resp = json.decodeFromString<KugouQrCreateResponse>(body)
        val key = resp.data.qrCode.trim()
        if (key.isEmpty()) {
            throw IllegalStateException("kugou qr key api error: status=${resp.status} error_code=${resp.errorCode} error=${resp.error}")
        }
        val loginUrl = "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=" +
            URLEncoder.encode(key, "UTF-8")
        return QRCreateResult(
            sessionId = NativeProviders.newSessionId(info.id),
            session = QRSession(key = key, url = loginUrl, imageUrl = ""),
        )
    }

    override suspend fun checkLogin(sessionId: String, key: String): QRCheckResult {
        val qrKey = key.trim()
        if (qrKey.isEmpty()) throw IllegalArgumentException("kugou qr login key is empty")

        val cookies = initLoginDevice()
        val params = mapOf(
            "plat" to "4",
            "appid" to KUGOU_LITE_APP_ID,
            "srcappid" to "2919",
            "qrcode" to qrKey,
        )
        val body = loginWebGet("https://login-user.kugou.com/v2/get_userinfo_qrcode", params, cookies)
        val resp = json.decodeFromString<KugouQrCheckResponse>(body)
        val status = mapQrStatus(resp.data.status)
        val message = firstNonEmpty(resp.error, "status=${resp.data.status}")
        if (status != "success") return QRCheckResult(status = status, message = message, cookie = "")

        val token = resp.data.token.trim()
        val userID = resp.data.userId.numericString()
        if (token.isEmpty() || userID.isEmpty() || userID == "0") {
            return QRCheckResult(
                status = "failed",
                message = "kugou qr login succeeded but token or userid is empty",
                cookie = "",
            )
        }
        cookies["token"] = token
        cookies["userid"] = userID
        runCatching { registerLoginDevice(cookies) }
        return QRCheckResult(status = "success", message = message, cookie = joinCookies(cookies))
    }

    override suspend fun accountInfo(cookie: String): AccountInfo? {
        if (cookie.isBlank()) return null
        val cookieMap = parseKugouCookie(cookie)
        val token = cookieMap["token"].orEmpty().trim()
        val userid = cookieMap["userid"].orEmpty().trim()
        val mid = firstNonEmpty(
            cookieMap["KUGOU_API_MID"].orEmpty(),
            cookieMap["kg_mid"].orEmpty(),
            cookieMap["mid"].orEmpty(),
        )
        if (token.isEmpty() || userid.isEmpty() || userid == "0" || mid.isEmpty()) return null
        val dfid = firstNonEmpty(
            cookieMap["dfid"].orEmpty(),
            cookieMap["KUGOU_API_DFID"].orEmpty(),
            cookieMap["kg_dfid"].orEmpty(),
            "-",
        )

        val clienttimeSeconds = System.currentTimeMillis() / 1000
        val p = rsaRawHex(
            buildJsonObject {
                put("token", token)
                put("clienttime", clienttimeSeconds)
            }.toString(),
        )
        val bodyData = buildJsonObject {
            put("visit_time", clienttimeSeconds)
            put("usertype", 1)
            put("p", p)
            put("userid", userid.toLongOrNull() ?: 0L)
        }.toString()
        val params = mutableMapOf(
            "dfid" to dfid,
            "mid" to mid,
            "uuid" to "-",
            "appid" to KUGOU_LITE_APP_ID,
            "clientver" to KUGOU_LITE_VER,
            "clienttime" to clienttimeSeconds.toString(),
            "token" to token,
            "userid" to userid,
            "plat" to "1",
        )
        val body = runCatching {
            NativeHttp.postJson(
                buildAndroidUrl("https://gateway.kugou.com/v3/get_my_info", params, bodyData),
                bodyData,
                headers = androidHeaders(dfid, mid, clienttimeSeconds.toString(), "usercenter.kugou.com"),
                cookie = cookie,
                randomIp = true,
                userAgent = KUGOU_ANDROID_UA,
            ).body
        }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val data = root["data"] as? JsonObject ?: root
        val nickname = firstNonEmpty(
            data.findString("nickname", "username", "user_name", "nick_name"),
            root.findString("nickname", "username", "user_name", "nick_name"),
        )
        if (nickname.isBlank()) return null
        val avatar = firstNonEmpty(
            data.findString("pic", "user_pic", "userpic", "avatar"),
            root.findString("pic", "user_pic", "userpic", "avatar"),
        ).replaceFirst("http://", "https://")

        val vipData = runCatching { fetchUnionVip(cookie, token, userid, mid, dfid) }.getOrNull()
        val vipType = maxOf(
            data.findInt("vip_type", "vipType", "is_vip"),
            vipData?.findInt("vip_type", "vipType", "is_vip") ?: 0,
        )
        val userType = maxOf(
            data.findInt("user_type", "userType"),
            vipData?.findInt("user_type", "userType") ?: 0,
        )
        val products = (vipData?.get("busi_vip") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .associate { product ->
                product.findString("product_type") to product.findInt("is_vip", "vip_type")
            }
        return AccountInfo(
            nickname = nickname,
            avatar = avatar,
            detail = kugouMembershipLabel(vipType, userType, products).orEmpty(),
        )
    }

    private suspend fun fetchUnionVip(
        cookie: String,
        token: String,
        userid: String,
        mid: String,
        dfid: String,
    ): JsonObject? {
        val clienttime = nowSeconds()
        val params = mutableMapOf(
            "dfid" to dfid,
            "mid" to mid,
            "uuid" to "-",
            "appid" to KUGOU_LITE_APP_ID,
            "clientver" to KUGOU_LITE_VER,
            "clienttime" to clienttime,
            "token" to token,
            "userid" to userid,
            "busi_type" to "concept",
        )
        val body = NativeHttp.get(
            buildAndroidUrl("https://kugouvip.kugou.com/v1/get_union_vip", params, ""),
            headers = androidHeaders(dfid, mid, clienttime),
            cookie = cookie,
            randomIp = true,
            userAgent = KUGOU_ANDROID_UA,
        )
        val root = json.parseToJsonElement(body).jsonObject
        return root["data"] as? JsonObject
    }

    // ---- song info / download ----

    private suspend fun fetchSongInfo(cookie: String, hash: String): Song {
        val url = "http://m.kugou.com/app/i/getSongInfo.php?" + query(
            mapOf("cmd" to "playInfo", "hash" to hash)
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to MOBILE_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        val resp = json.decodeFromString<KugouSongInfoResponse>(body)
        if (resp.errcode != 0 || resp.url.isEmpty()) {
            throw IllegalStateException("kugou song info unavailable, errcode=${resp.errcode}")
        }
        return Song(
            id = hash,
            name = resp.songName,
            artist = resp.authorName,
            duration = resp.timeLength,
            size = resp.fileSize,
            bitrate = resp.bitRate / 1000,
            source = "kugou",
            url = resp.url,
            ext = resp.extName,
            cover = resp.albumImg.replaceFirst("{size}", "240"),
            link = "https://www.kugou.com/song/#hash=$hash",
            extra = mapOf("hash" to hash),
        )
    }

    private suspend fun shouldTryHighQuality(cookie: String, privilege: Int): Boolean {
        if (privilege == 10 || privilege == 8) return true
        return kugouHasAppCookie(parseKugouCookie(cookie))
    }

    private suspend fun fetchVipSongInfo(cookie: String, song: Song, vipCache: VipCache): Song? {
        if (cookie.trim().isEmpty()) throw IllegalStateException("cookie required for kugou vip download")

        var fallback: Song? = null
        var lastError: Throwable? = null
        for (hash in collectCandidateHashes(song)) {
            try {
                val info = fetchUrlV5(cookie, song, hash)
                if (info.url.isNotEmpty()) {
                    if (looksLossless(info.ext, info.bitrate, info.size)) {
                        vipCache.value = true
                        return info
                    }
                    if (fallback == null) fallback = info
                }
            } catch (error: Throwable) {
                lastError = error
            }
            try {
                val info = fetchPrivUrlV6(cookie, song, hash)
                if (info.url.isNotEmpty()) {
                    if (looksLossless(info.ext, info.bitrate, info.size)) {
                        vipCache.value = true
                        return info
                    }
                    if (fallback == null) fallback = info
                }
            } catch (error: Throwable) {
                lastError = error
            }
            try {
                val info = fetchSonginfoV2(cookie, hash)
                if (info.url.isNotEmpty()) {
                    if (looksLossless(info.ext, info.bitrate, info.size)) {
                        vipCache.value = true
                        return info
                    }
                    if (fallback == null) fallback = info
                }
            } catch (error: Throwable) {
                lastError = error
            }
            try {
                val info = fetchTrackerSongInfo(cookie, hash)
                if (info.url.isNotEmpty()) {
                    if (looksLossless(info.ext, info.bitrate, info.size)) {
                        vipCache.value = true
                        return info
                    }
                    if (fallback == null) fallback = info
                }
            } catch (error: Throwable) {
                lastError = error
            }
        }

        fallback?.let { return it }
        vipCache.value = false
        throw lastError ?: IllegalStateException("kugou vip download url not found")
    }

    private suspend fun fetchUrlV5(cookie: String, song: Song, rawHash: String): Song {
        val hash = rawHash.trim().lowercase()
        if (!isValidHash(hash)) throw IllegalArgumentException("invalid kugou hash")

        val cookieMap = parseKugouCookie(cookie)
        val token = cookieMap["token"].orEmpty().trim()
        val userID = cookieMap["userid"].orEmpty().trim()
        val mid = cookieMap["KUGOU_API_MID"].orEmpty().trim()
        if (token.isEmpty() || userID.isEmpty() || userID == "0" || mid.isEmpty()) {
            throw IllegalStateException("kugou v5 url requires app token, userid and KUGOU_API_MID")
        }

        var albumAudioID = "0"
        var albumID = "0"
        albumID = firstNonEmpty(song.albumId, albumID)
        albumAudioID = firstNonEmpty(song.extra["album_audio_id"].orEmpty(), song.extra["audio_id"].orEmpty(), albumAudioID)
        albumID = firstNonEmpty(song.extra["album_id"].orEmpty(), albumID)

        val clienttime = nowSeconds()
        val dfid = firstNonEmpty(cookieMap["dfid"].orEmpty(), randomKugouDfid())
        val params = mutableMapOf(
            "dfid" to dfid,
            "mid" to mid,
            "uuid" to "-",
            "appid" to KUGOU_LITE_APP_ID,
            "clientver" to KUGOU_LITE_VER,
            "clienttime" to clienttime,
            "token" to token,
            "userid" to userID,
            "album_id" to albumID,
            "area_code" to "1",
            "hash" to hash,
            "ssa_flag" to "is_fromtrack",
            "version" to "11436",
            "page_id" to "967177915",
            "quality" to "flac",
            "album_audio_id" to albumAudioID,
            "behavior" to "play",
            "pid" to "411",
            "cmd" to "26",
            "pidversion" to "3001",
            "IsFreePart" to "0",
            "ppage_id" to "356753938,823673182,967485191",
            "cdnBackup" to "1",
            "kcard" to "0",
            "module" to "",
        )
        params["key"] = NativeCrypto.md5Hex(hash + KUGOU_LITE_KEY + KUGOU_LITE_APP_ID + mid + userID)

        val url = buildAndroidUrl("https://gateway.kugou.com/v5/url", params, "")
        val body = NativeHttp.get(
            url,
            headers = androidHeaders(dfid, mid, clienttime, "trackercdn.kugou.com"),
            cookie = cookie,
            randomIp = true,
            userAgent = KUGOU_ANDROID_UA,
        )
        val resp = json.parseToJsonElement(body).jsonObject
        val downloadUrl = pickKugouResponseUrl(resp)
        if (downloadUrl.trim().isEmpty()) {
            throw IllegalStateException("kugou v5 url unavailable, status=${resp.findInt("status")} error_code=${resp.findInt("error_code", "errcode")}")
        }

        var ext = normalizeKugouExt(resp.findString("fileType", "extName", "extname"), downloadUrl)
        if (ext.isEmpty()) ext = "flac"
        return Song(
            id = hash,
            name = firstNonEmpty(resp.findString("songName", "song_name", "audio_name"), song.name),
            artist = firstNonEmpty(resp.findString("authorName", "author_name", "singerName"), song.artist),
            album = song.album,
            albumId = albumID,
            size = resp.findLong("fileSize", "filesize"),
            bitrate = normalizeKugouBitrate(resp.findInt("bitRate", "bitrate")),
            source = "kugou",
            url = downloadUrl,
            ext = ext,
            link = "https://www.kugou.com/song/#hash=$hash",
            extra = mapOf(
                "hash" to hash,
                "album_audio_id" to albumAudioID,
                "album_id" to albumID,
            ),
        )
    }

    private suspend fun fetchPrivUrlV6(cookie: String, song: Song, rawHash: String): Song {
        val hash = rawHash.trim().lowercase()
        if (!isValidHash(hash)) throw IllegalArgumentException("invalid kugou hash")

        val cookieMap = parseKugouCookie(cookie)
        val token = cookieMap["token"].orEmpty().trim()
        val userID = cookieMap["userid"].orEmpty().trim()
        val mid = cookieMap["KUGOU_API_MID"].orEmpty().trim()
        if (token.isEmpty() || userID.isEmpty() || userID == "0" || mid.isEmpty()) {
            throw IllegalStateException("kugou priv_url v6 requires app token, userid and KUGOU_API_MID")
        }

        var albumAudioID = firstNonEmpty(song.extra["album_audio_id"].orEmpty(), song.extra["audio_id"].orEmpty())
        if (albumAudioID.isEmpty()) {
            val info = runCatching { fetchSonginfoV2(cookie, hash) }.getOrNull()
            if (info != null) {
                albumAudioID = firstNonEmpty(info.extra["album_audio_id"].orEmpty(), info.extra["audio_id"].orEmpty())
            }
        }
        if (albumAudioID.isEmpty()) albumAudioID = "0"

        val dfid = firstNonEmpty(cookieMap["dfid"].orEmpty(), randomKugouDfid())
        val vipToken = firstNonEmpty(cookieMap["vip_token"].orEmpty(), cookieMap["viptoken"].orEmpty())
        val vipType = firstNonEmpty(cookieMap["vip_type"].orEmpty(), cookieMap["vipType"].orEmpty(), "6")
        val clienttimeMs = System.currentTimeMillis()

        val bodyData = buildJsonObject {
            put("area_code", "1")
            put("behavior", "play")
            put("qualities", buildJsonArray {
                listOf("128", "320", "flac", "high", "multitrack", "viper_atmos", "viper_tape", "viper_clear", "super")
                    .forEach { add(JsonPrimitive(it)) }
            })
            put("resource", buildJsonObject {
                put("album_audio_id", albumAudioID)
                put("collect_list_id", "3")
                put("collect_time", clienttimeMs)
                put("hash", hash)
                put("id", 0)
                put("page_id", 1)
                put("type", "audio")
            })
            put("token", token)
            put("tracker_param", buildJsonObject {
                put("all_m", 1)
                put("auth", "")
                put("is_free_part", 0)
                put("key", NativeCrypto.md5Hex(hash + KUGOU_LITE_KEY + KUGOU_LITE_APP_ID + mid + userID))
                put("module_id", 0)
                put("need_climax", 1)
                put("need_xcdn", 1)
                put("open_time", "")
                put("pid", "411")
                put("pidversion", "3001")
                put("priv_vip_type", "6")
                put("viptoken", vipToken)
            })
            put("userid", userID)
            put("vip", vipType)
        }.toString()

        val clienttime = nowSeconds()
        val params = mapOf(
            "dfid" to dfid,
            "mid" to mid,
            "uuid" to "-",
            "appid" to KUGOU_LITE_APP_ID,
            "clientver" to KUGOU_LITE_VER,
            "clienttime" to clienttime,
            "token" to token,
            "userid" to userID,
        )
        val url = buildAndroidUrl("http://tracker.kugou.com/v6/priv_url", params, bodyData)
        val body = NativeHttp.postJson(
            url,
            bodyData,
            headers = androidHeaders(dfid, mid, clienttime),
            cookie = cookie,
            randomIp = true,
            userAgent = KUGOU_ANDROID_UA,
        ).body
        val resp = json.parseToJsonElement(body).jsonObject
        val downloadUrl = pickKugouResponseUrl(resp)
        if (downloadUrl.trim().isEmpty()) {
            throw IllegalStateException("kugou priv_url v6 unavailable, status=${resp.findInt("status")} error_code=${resp.findInt("error_code", "errcode")}")
        }

        return Song(
            id = hash,
            name = firstNonEmpty(resp.findString("songName", "song_name", "audio_name"), song.name),
            artist = firstNonEmpty(resp.findString("authorName", "author_name", "singerName"), song.artist),
            album = song.album,
            albumId = song.albumId,
            size = resp.findLong("fileSize", "filesize"),
            bitrate = normalizeKugouBitrate(resp.findInt("bitRate", "bitrate")),
            source = "kugou",
            url = downloadUrl,
            ext = normalizeKugouExt(resp.findString("fileType", "extName", "extname"), downloadUrl),
            link = "https://www.kugou.com/song/#hash=$hash",
            extra = mapOf(
                "hash" to hash,
                "album_audio_id" to albumAudioID,
            ),
        )
    }

    private suspend fun fetchSonginfoV2(cookie: String, hash: String): Song {
        val cookieMap = parseKugouCookie(cookie)
        val t = cookieMap["t"].orEmpty().trim()
        val kugooId = cookieMap["KugooID"].orEmpty().trim()
        if (t.isEmpty() || kugooId.isEmpty()) {
            throw IllegalStateException("kugou songinfo v2 requires cookie t and KugooID")
        }

        val baseParams = mapOf(
            "srcappid" to "2919",
            "clientver" to "20000",
            "clienttime" to System.currentTimeMillis().toString(),
            "mid" to firstNonEmpty(cookieMap["mid"].orEmpty(), cookieMap["kg_mid"].orEmpty()),
            "uuid" to firstNonEmpty(cookieMap["uuid"].orEmpty(), cookieMap["mid"].orEmpty(), cookieMap["kg_mid"].orEmpty()),
            "dfid" to firstNonEmpty(cookieMap["dfid"].orEmpty(), cookieMap["kg_dfid"].orEmpty()),
            "appid" to "1014",
            "platid" to "4",
            "token" to t,
            "userid" to kugooId,
        )

        val step1Body = getSonginfoV2(cookie, baseParams + mapOf("hash" to hash))
        val step1 = json.decodeFromString<KugouSonginfoV2Step1Response>(step1Body)
        val encodeAlbumAudioId = step1.data.encodeAlbumAudioId.trim()
        if (encodeAlbumAudioId.isEmpty()) {
            throw IllegalStateException("kugou songinfo v2 missing encode_album_audio_id")
        }

        val step2Body = getSonginfoV2(cookie, baseParams + mapOf("encode_album_audio_id" to encodeAlbumAudioId))
        val step2 = json.decodeFromString<KugouSonginfoV2Step2Response>(step2Body)
        var downloadUrl = step2.data.playUrl.trim()
        if (downloadUrl.isEmpty()) downloadUrl = step2.data.playBackupUrl.trim()
        if (downloadUrl.isEmpty()) throw IllegalStateException("kugou songinfo v2 missing play url")

        return Song(
            id = hash,
            name = firstNonEmpty(step2.data.songName, step1.data.songName),
            artist = firstNonEmpty(step2.data.authorName, step1.data.authorName),
            album = step1.data.albumName,
            duration = normalizeKugouDuration(step2.data.timeLength),
            size = step2.data.fileSize,
            bitrate = normalizeKugouBitrate(step2.data.bitrate),
            source = "kugou",
            url = downloadUrl,
            ext = normalizeKugouExt(step2.data.extName, downloadUrl),
            cover = firstNonEmpty(step2.data.img, step1.data.img).replaceFirst("{size}", "240"),
            link = "https://www.kugou.com/song/#hash=$hash",
            extra = mapOf(
                "hash" to hash,
                "encode_album_audio_id" to encodeAlbumAudioId,
                "album_audio_id" to step1.data.albumAudioId.numericString(),
            ),
        )
    }

    private suspend fun getSonginfoV2(cookie: String, params: Map<String, String>): String {
        val url = buildSonginfoUrl(params)
        return NativeHttp.get(
            url,
            headers = mapOf(
                "Referer" to "https://www.kugou.com/",
                "Accept" to "application/json, text/plain, */*",
            ),
            cookie = cookie,
            randomIp = true,
            userAgent = PC_USER_AGENT,
        )
    }

    private suspend fun fetchTrackerSongInfo(cookie: String, rawHash: String): Song {
        val hash = rawHash.trim().lowercase()
        if (!isValidHash(hash)) throw IllegalArgumentException("invalid kugou hash")

        val urls = listOf(
            "https://trackercdn.kugou.com/i/v2/?cdnBackup=1&behavior=download&pid=1&cmd=21&appid=1001&hash=$hash&key=${NativeCrypto.md5Hex(hash + "kgcloudv2")}",
            "http://trackercdnbj.kugou.com/i/v2/?cmd=23&pid=1&behavior=download&hash=$hash&key=${NativeCrypto.md5Hex(hash + "kgcloudv2")}",
            "http://trackercdn.kugou.com/i/?cmd=4&pid=1&forceDown=0&vip=1&hash=$hash&key=${NativeCrypto.md5Hex(hash + "kgcloud")}",
        )
        for (url in urls) {
            val body = runCatching {
                NativeHttp.get(
                    url,
                    headers = mapOf("Referer" to "https://www.kugou.com/"),
                    cookie = cookie,
                    randomIp = true,
                    userAgent = PC_USER_AGENT,
                )
            }.getOrNull() ?: continue
            val resp = runCatching { json.decodeFromString<KugouTrackerResponse>(body) }.getOrNull() ?: continue
            var downloadUrl = resp.url.pickUrlValue().orEmpty()
            if (downloadUrl.isEmpty()) downloadUrl = resp.backupUrl.pickUrlValue().orEmpty()
            if (downloadUrl.isEmpty() || resp.errcode != 0) continue
            return Song(
                id = hash,
                name = resp.songName,
                artist = resp.authorName,
                duration = normalizeKugouDuration(resp.timeLength),
                size = resp.fileSize,
                bitrate = normalizeKugouBitrate(resp.bitRate),
                source = "kugou",
                url = downloadUrl,
                ext = normalizeKugouExt(resp.extName, downloadUrl),
                cover = resp.albumImg.replaceFirst("{size}", "240"),
                link = "https://www.kugou.com/song/#hash=$hash",
                extra = mapOf("hash" to hash),
            )
        }
        throw IllegalStateException("tracker kugou download url not found")
    }

    private suspend fun fetchSingerInfo(cookie: String, singerId: Int): KugouSingerInfo {
        val url = "http://mobilecdn.kugou.com/api/v3/singer/info?" + query(
            mapOf("singerid" to singerId.toString(), "with_res_tag" to "1")
        )
        val body = NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = MOBILE_USER_AGENT)
        val resp = json.decodeFromString<KugouSingerInfoResponse>(kugouStripTag(body))
        if (resp.errcode != 0 || resp.status != 1) {
            throw IllegalStateException("kugou singer info api error: status=${resp.status} errcode=${resp.errcode}")
        }
        return resp.data
    }

    private suspend fun fetchSingerSongs(cookie: String, id: String): List<Song> {
        val singerId = id.trim()
        if (singerId.isEmpty()) throw IllegalArgumentException("singer id is empty")
        val songs = mutableListOf<Song>()
        val pageSize = 100
        var page = 1
        var total = 0
        while (songs.size < KUGOU_SINGER_SONG_LIMIT) {
            val url = "http://mobilecdn.kugou.com/api/v3/singer/song?" + query(
                mapOf(
                    "sorttype" to "2",
                    "version" to "9108",
                    "identity" to "3",
                    "plat" to "0",
                    "pagesize" to pageSize.toString(),
                    "singerid" to singerId,
                    "area_code" to "1",
                    "page" to page.toString(),
                )
            )
            val body = NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = MOBILE_USER_AGENT)
            val resp = json.decodeFromString<KugouAlbumSongsResponse>(body)
            if (resp.errcode != 0 || resp.status != 1) {
                throw IllegalStateException("kugou singer songs api error: status=${resp.status} errcode=${resp.errcode} error=${resp.error}")
            }
            if (total == 0) total = resp.data.total
            if (resp.data.info.isEmpty()) break
            for (item in resp.data.info) {
                val finalHash = firstNonEmpty(item.hash, item.sqHash, item.hqHash, item.resHash)
                if (!isValidHash(finalHash)) continue
                var name = item.songName.trim()
                var artist = item.singerName.trim()
                if (name.isEmpty() || artist.isEmpty()) {
                    val parts = item.filename.split(" - ")
                    if (parts.size >= 2) {
                        artist = parts[0].trim()
                        name = parts.drop(1).joinToString(" - ").trim()
                    } else if (name.isEmpty()) {
                        name = item.filename.trim()
                    }
                }
                var size = item.fileSize
                when (finalHash) {
                    item.sqHash -> if (item.sqFileSize > 0) size = item.sqFileSize
                    item.hqHash -> if (item.hqFileSize > 0) size = item.hqFileSize
                }
                val bitrate = if (item.duration > 0 && size > 0) (size * 8 / 1000 / item.duration).toInt() else 0
                songs += Song(
                    id = finalHash,
                    name = name,
                    artist = artist,
                    album = item.albumName.trim(),
                    albumId = item.albumId,
                    duration = item.duration,
                    size = size,
                    bitrate = bitrate,
                    source = "kugou",
                    cover = item.transParam.unionCover.replaceFirst("{size}", "240"),
                    link = "https://www.kugou.com/song/#hash=$finalHash",
                    extra = mapOf(
                        "hash" to finalHash,
                        "sq_hash" to item.sqHash,
                        "hq_hash" to item.hqHash,
                        "audio_id" to item.audioId.toString(),
                        "album_id" to item.albumId,
                        "privilege" to item.privilege.toString(),
                    ),
                )
            }
            if (resp.data.info.size < pageSize) break
            if (total > 0 && songs.size >= total) break
            page++
        }
        if (songs.isEmpty()) throw IllegalStateException("kugou singer songs not found")
        return songs
    }

    private suspend fun isVipAccount(cookie: String, cache: VipCache): Boolean {
        cache.value?.let { return it }
        if (cookie.trim().isEmpty()) {
            cache.value = false
            return false
        }
        val body = NativeHttp.get(
            VIP_INFO_API,
            headers = mapOf("Accept" to "*/*"),
            cookie = cookie,
            randomIp = true,
            userAgent = PC_USER_AGENT,
        )
        val resp = json.decodeFromString<KugouVipInfoResponse>(body)
        val isVip = resp.errno == 0 &&
            resp.errorCode == 0 &&
            resp.vipRemains > 0 &&
            resp.isExpiredMember == 0 &&
            resp.role != 0
        cache.value = isVip
        return isVip
    }

    // ---- album ----

    private suspend fun fetchAlbumDetail(cookie: String, rawId: String): Pair<Playlist, List<Song>> {
        val id = rawId.trim()
        if (id.isEmpty()) throw IllegalArgumentException("album id is empty")

        val infoBody = NativeHttp.get(
            "http://mobilecdn.kugou.com/api/v3/album/info?albumid=$id&version=9108&area_code=1",
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        val info = json.decodeFromString<KugouAlbumInfoResponse>(infoBody)
        if (info.errcode != 0 || info.status != 1) {
            throw IllegalStateException("kugou album info api error: status=${info.status} errcode=${info.errcode} error=${info.error}")
        }

        val pageSize = 300
        var total = 0
        val songs = mutableListOf<Song>()
        var page = 1
        while (true) {
            val songBody = NativeHttp.get(
                "http://mobilecdn.kugou.com/api/v3/album/song?albumid=$id&page=$page&pagesize=$pageSize&version=9108&area_code=1",
                cookie = cookie,
                randomIp = true,
                userAgent = MOBILE_USER_AGENT,
            )
            val resp = json.decodeFromString<KugouAlbumSongsResponse>(songBody)
            if (resp.errcode != 0 || resp.status != 1) {
                throw IllegalStateException("kugou album songs api error: status=${resp.status} errcode=${resp.errcode} error=${resp.error}")
            }
            if (total == 0) total = resp.data.total
            if (resp.data.info.isEmpty()) break

            for (item in resp.data.info) {
                val finalHash = firstNonEmpty(
                    item.hash,
                    item.sqHash,
                    item.hqHash,
                    item.resHash,
                    item.transParam.ogg320Hash,
                    item.originHash,
                    item.transParam.ogg128Hash,
                )
                if (!isValidHash(finalHash)) continue

                var name = item.songName.trim()
                var artist = item.singerName.trim()
                if (name.isEmpty() || artist.isEmpty()) {
                    val parts = item.filename.split(" - ")
                    if (parts.size >= 2) {
                        artist = parts[0].trim()
                        name = parts.drop(1).joinToString(" - ").trim()
                    } else if (name.isEmpty()) {
                        name = item.filename.trim()
                    }
                }

                var albumName = item.albumName.trim()
                if (albumName.isEmpty()) albumName = info.data.albumName.trim()
                if (albumName.isEmpty()) albumName = item.remark.trim()

                var size = item.fileSize
                when (finalHash) {
                    item.sqHash -> if (item.sqFileSize > 0) size = item.sqFileSize
                    item.hqHash -> if (item.hqFileSize > 0) size = item.hqFileSize
                    item.resHash -> if (item.sqFileSize > 0) size = item.sqFileSize
                    item.transParam.ogg320Hash -> if (item.transParam.ogg320FileSize > 0) size = item.transParam.ogg320FileSize
                    item.transParam.ogg128Hash -> if (item.transParam.ogg128FileSize > 0) size = item.transParam.ogg128FileSize
                }
                val bitrate = if (item.duration > 0 && size > 0) (size * 8 / 1000 / item.duration).toInt() else 0
                val cover = firstNonEmpty(item.transParam.unionCover, info.data.imgUrl).replaceFirst("{size}", "240")
                val albumId = firstNonEmpty(item.albumId, id)

                songs += Song(
                    id = finalHash,
                    name = name,
                    artist = artist,
                    album = albumName,
                    albumId = albumId,
                    duration = item.duration,
                    size = size,
                    bitrate = bitrate,
                    source = "kugou",
                    cover = cover,
                    link = "https://www.kugou.com/song/#hash=$finalHash",
                    extra = mapOf(
                        "hash" to finalHash,
                        "ogg_320_hash" to item.transParam.ogg320Hash,
                        "ogg_128_hash" to item.transParam.ogg128Hash,
                        "sq_hash" to item.sqHash,
                        "file_hash" to item.originHash,
                        "res_hash" to item.resHash,
                        "mv_hash" to item.mvHash,
                        "hq_hash" to item.hqHash,
                        "audio_id" to item.audioId.toString(),
                        "album_id" to albumId,
                        "privilege" to item.privilege.toString(),
                    ),
                )
            }

            if (resp.data.info.size < pageSize) break
            if (total > 0 && songs.size >= total) break
            page++
        }

        var trackCount = total
        if (trackCount == 0) trackCount = info.data.songCount
        if (trackCount == 0) trackCount = songs.size

        val playlist = Playlist(
            id = firstNonEmpty(info.data.albumId.toString(), id),
            name = info.data.albumName,
            cover = info.data.imgUrl.replaceFirst("{size}", "240"),
            trackCount = trackCount,
            playCount = info.data.playCount.toLong(),
            creator = info.data.singerName,
            description = info.data.intro,
            source = "kugou",
            link = "https://www.kugou.com/album/$id.html",
            extra = mapOf(
                "type" to "album",
                "album_id" to firstNonEmpty(info.data.albumId.toString(), id),
                "publish_time" to info.data.publishTime,
            ),
        )
        return playlist to songs
    }

    // ---- playlists ----

    private suspend fun fetchPlaylistDetail(cookie: String, id: String): Pair<Playlist, List<Song>> {
        if (id.trim().lowercase().startsWith("gcid_")) {
            return fetchSonglistDetail(cookie, id)
        }
        val body = NativeHttp.get(
            "http://mobilecdn.kugou.com/api/v3/special/song?specialid=$id&page=1&pagesize=300&version=9108&area_code=1",
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        val resp = json.decodeFromString<KugouSpecialSongResponse>(body)
        val songs = mutableListOf<Song>()
        for (item in resp.data.info) {
            val finalHash = firstNonEmpty(
                item.hash,
                item.sqFileHash,
                item.hqFileHash,
                item.resFileHash,
                item.transParam.ogg320Hash,
                item.fileHash,
                item.transParam.ogg128Hash,
            )
            if (!isValidHash(finalHash)) continue

            var name = item.songName
            var artist = item.singerName
            if (name.isEmpty() || artist.isEmpty()) {
                val parts = item.filename.split(" - ")
                if (parts.size >= 2) {
                    artist = parts[0].trim()
                    name = parts.drop(1).joinToString(" - ").trim()
                } else {
                    name = item.filename
                }
            }

            var cover = ""
            if (item.transParam.unionCover.isNotEmpty()) {
                cover = item.transParam.unionCover.replaceFirst("{size}", "240")
            }
            var albumName = item.albumName
            if (albumName.isEmpty()) albumName = item.remark

            var size = item.fileSize
            when (finalHash) {
                item.sqFileHash -> if (item.sqFileSize > 0) size = item.sqFileSize
                item.hqFileHash -> if (item.hqFileSize > 0) size = item.hqFileSize
                item.resFileHash -> if (item.resFileSize > 0) size = item.resFileSize
                item.transParam.ogg320Hash -> if (item.transParam.ogg320FileSize > 0) size = item.transParam.ogg320FileSize
                item.transParam.ogg128Hash -> if (item.transParam.ogg128FileSize > 0) size = item.transParam.ogg128FileSize
            }

            songs += Song(
                id = finalHash,
                name = name,
                artist = artist,
                album = albumName,
                albumId = item.albumId,
                duration = item.duration,
                size = size,
                source = "kugou",
                cover = cover,
                link = "https://www.kugou.com/song/#hash=$finalHash",
                extra = mapOf(
                    "hash" to finalHash,
                    "ogg_320_hash" to item.transParam.ogg320Hash,
                    "ogg_128_hash" to item.transParam.ogg128Hash,
                    "sq_hash" to item.sqFileHash,
                    "file_hash" to item.fileHash,
                    "res_hash" to item.resFileHash,
                    "mv_hash" to item.mvHash,
                    "hq_hash" to item.hqFileHash,
                    "audio_id" to item.audioId.numericString(),
                    "album_id" to item.albumId,
                    "privilege" to item.privilege.toString(),
                ),
            )
        }

        val playlist = Playlist(
            id = id,
            source = "kugou",
            link = "https://www.kugou.com/yy/special/single/$id.html",
            trackCount = songs.size,
        )
        return playlist to songs
    }

    private suspend fun fetchSonglistDetail(cookie: String, rawId: String): Pair<Playlist, List<Song>> {
        val id = rawId.trim()
        val body = NativeHttp.get(
            "https://www.kugou.com/songlist/$id/",
            headers = mapOf("Referer" to MOBILE_REFERER),
            cookie = cookie,
            randomIp = true,
            userAgent = MOBILE_USER_AGENT,
        )
        val match = KUGOU_SONGLIST_PAYLOAD_REGEX.find(body)
            ?: throw IllegalStateException("kugou songlist payload not found")
        val resp = json.decodeFromString<KugouSonglistPayload>(match.groupValues[1])

        val playlistId = resp.encodeGic.ifEmpty { id }
        val playlist = Playlist(
            id = playlistId,
            name = resp.info.listInfo.name,
            cover = resp.info.listInfo.pic.replaceFirst("{size}", "240"),
            trackCount = resp.info.listInfo.count,
            playCount = resp.info.listInfo.heat.toLong(),
            creator = resp.info.listInfo.creator,
            description = resp.info.listInfo.intro,
            source = "kugou",
            link = "https://www.kugou.com/songlist/$playlistId/",
        )

        val isVip = runCatching { isVipAccount(cookie, VipCache()) }.getOrDefault(false)
        val songs = mutableListOf<Song>()
        for (item in resp.info.songs) {
            if (!isVip && item.privilege == 10) continue

            val hashes = pickSonglistHashes(item.hash, item.size, item.bitrate, item.relateGoods)
            var fileHash = hashes.fileHash
            var hqHash = hashes.hqHash
            var sqHash = hashes.sqHash
            var size = hashes.size
            var bitrate = hashes.bitrate
            if (isValidHash(item.fileHash)) fileHash = item.fileHash
            if (isValidHash(item.hqFileHash)) hqHash = item.hqFileHash
            if (isValidHash(item.sqFileHash)) sqHash = item.sqFileHash

            val finalHash = firstNonEmpty(
                item.hash,
                sqHash,
                hqHash,
                item.resFileHash,
                item.transParam.ogg320Hash,
                fileHash,
                item.transParam.ogg128Hash,
            )
            if (!isValidHash(finalHash)) continue

            when (finalHash) {
                sqHash -> Unit
                hqHash -> Unit
                item.resFileHash -> if (item.size > 0) size = item.size
                item.transParam.ogg320Hash -> if (item.transParam.ogg320FileSize > 0) size = item.transParam.ogg320FileSize
                item.transParam.ogg128Hash -> if (item.transParam.ogg128FileSize > 0) size = item.transParam.ogg128FileSize
                fileHash, item.hash -> if (item.size > 0) size = item.size
            }

            val duration = normalizeKugouDuration(item.timeLength)
            if (duration > 0 && size > 0) bitrate = (size * 8 / 1000 / duration).toInt()

            var coverUrl = item.cover
            if (item.transParam.unionCover.isNotEmpty()) coverUrl = item.transParam.unionCover
            coverUrl = coverUrl.replaceFirst("{size}", "240")

            songs += Song(
                id = finalHash,
                name = pickSonglistSongName(item.name),
                artist = joinSonglistArtists(item.singerInfo),
                album = item.albumInfo.name,
                albumId = item.albumId,
                duration = duration,
                size = size,
                bitrate = bitrate,
                source = "kugou",
                cover = coverUrl,
                link = "https://www.kugou.com/song/#hash=$finalHash",
                extra = mapOf(
                    "hash" to finalHash,
                    "ogg_320_hash" to item.transParam.ogg320Hash,
                    "ogg_128_hash" to item.transParam.ogg128Hash,
                    "sq_hash" to sqHash,
                    "file_hash" to fileHash,
                    "res_hash" to item.resFileHash,
                    "mv_hash" to item.mvHash,
                    "hq_hash" to hqHash,
                    "audio_id" to item.audioId.numericString(),
                    "album_id" to item.albumId,
                    "privilege" to item.privilege.toString(),
                ),
            )
        }

        val result = if (playlist.trackCount == 0) playlist.copy(trackCount = songs.size) else playlist
        return result to songs
    }

    private suspend fun fetchCloudlistDetail(cookie: String, listId: String): Pair<Playlist, List<Song>> {
        val cookieMap = parseKugouCookie(cookie)
        val userID = cookieMap["userid"].orEmpty().trim()
        val token = cookieMap["token"].orEmpty().trim()
        val mid = cookieMap["KUGOU_API_MID"].orEmpty().trim()
        if (listId.isEmpty() || userID.isEmpty() || userID == "0" || token.isEmpty() || mid.isEmpty()) {
            throw IllegalStateException("kugou cloudlist detail requires listid, userid, token and KUGOU_API_MID")
        }

        val dfid = firstNonEmpty(cookieMap["dfid"].orEmpty(), "-")
        val clienttime = nowSeconds()
        val data = buildJsonObject {
            put("listid", listId)
            put("userid", userID)
            put("area_code", 1)
            put("show_relate_goods", 1)
            put("pagesize", 300)
            put("allplatform", 1)
            put("show_cover", 1)
            put("type", 0)
            put("token", token)
            put("page", 1)
        }.toString()
        val params = mapOf(
            "dfid" to dfid,
            "mid" to mid,
            "uuid" to "-",
            "appid" to KUGOU_LITE_APP_ID,
            "clientver" to KUGOU_LITE_VER,
            "clienttime" to clienttime,
            "token" to token,
            "userid" to userID,
        )
        val url = buildAndroidUrl("https://gateway.kugou.com/v4/get_list_all_file", params, data)
        val body = NativeHttp.postJson(
            url,
            data,
            headers = androidHeaders(dfid, mid, clienttime, "cloudlist.service.kugou.com"),
            cookie = cookie,
            randomIp = true,
            userAgent = KUGOU_ANDROID_UA,
        ).body
        val resp = json.decodeFromString<KugouCloudlistResponse>(body)
        if (resp.status != 1 || resp.errorCode != 0 || resp.errcode != 0) {
            throw IllegalStateException(
                "kugou cloudlist detail api error: status=${resp.status} error_code=${resp.errorCode} errcode=${resp.errcode} error=${resp.error}"
            )
        }

        val playlist = Playlist(
            id = kugouCloudlistId(listId),
            source = "kugou",
            trackCount = resp.data.count.numericString().toIntOrNull() ?: 0,
        )
        val songs = mutableListOf<Song>()
        for (item in resp.data.info) {
            var baseSize = item.size
            if (baseSize == 0L) baseSize = item.fileSize
            val hashes = pickSonglistHashes(item.hash, baseSize, item.bitrate, item.relateGoods)
            var fileHash = hashes.fileHash
            var hqHash = hashes.hqHash
            var sqHash = hashes.sqHash
            var size = hashes.size
            var bitrate = hashes.bitrate
            if (isValidHash(item.fileHash)) fileHash = item.fileHash
            if (isValidHash(item.hqFileHash)) hqHash = item.hqFileHash
            if (isValidHash(item.sqFileHash)) sqHash = item.sqFileHash

            val finalHash = firstNonEmpty(
                sqHash,
                hqHash,
                item.resFileHash,
                item.transParam.ogg320Hash,
                item.hash,
                fileHash,
                item.transParam.ogg128Hash,
            )
            if (!isValidHash(finalHash)) continue

            when (finalHash) {
                sqHash -> if (item.sqFileSize > 0) size = item.sqFileSize
                hqHash -> if (item.hqFileSize > 0) size = item.hqFileSize
                item.resFileHash -> if (item.resFileSize > 0) size = item.resFileSize
                item.transParam.ogg320Hash -> if (item.transParam.ogg320FileSize > 0) size = item.transParam.ogg320FileSize
                item.transParam.ogg128Hash -> if (item.transParam.ogg128FileSize > 0) size = item.transParam.ogg128FileSize
                item.hash, fileHash -> if (baseSize > 0) size = baseSize
            }

            val duration = normalizeKugouDuration(item.timeLength)
            if (duration > 0 && size > 0) bitrate = (size * 8 / 1000 / duration).toInt()

            val albumId = firstNonEmpty(
                item.albumId.numericString(),
                item.albumIdLegacy.numericString(),
                item.albumInfo.id.numericString(),
            )
            val audioId = firstNonEmpty(item.audioId.numericString(), item.audioIdLegacy.numericString())
            val albumAudioId = firstNonEmpty(
                item.albumAudioId.numericString(),
                item.mixSongId.numericString(),
                item.id.numericString(),
                audioId,
            )
            val cover = firstNonEmpty(item.cover, item.transParam.unionCover).replaceFirst("{size}", "240")

            songs += Song(
                id = finalHash,
                name = pickSonglistSongName(firstNonEmpty(item.name, item.filename)),
                artist = joinSonglistArtists(item.singerInfo),
                album = item.albumInfo.name,
                albumId = albumId,
                duration = duration,
                size = size,
                bitrate = bitrate,
                source = "kugou",
                cover = cover,
                link = "https://www.kugou.com/song/#hash=$finalHash",
                extra = mapOf(
                    "hash" to finalHash,
                    "ogg_320_hash" to item.transParam.ogg320Hash,
                    "ogg_128_hash" to item.transParam.ogg128Hash,
                    "sq_hash" to sqHash,
                    "file_hash" to fileHash,
                    "res_hash" to item.resFileHash,
                    "mv_hash" to item.mvHash,
                    "hq_hash" to hqHash,
                    "audio_id" to audioId,
                    "album_audio_id" to albumAudioId,
                    "album_id" to albumId,
                    "cloud_listid" to listId,
                    "privilege" to item.privilege.toString(),
                ),
            )
        }
        return playlist.copy(trackCount = songs.size) to songs
    }

    private suspend fun getUserPlaylistsGateway(
        cookie: String,
        cookieMap: Map<String, String>,
        userID: String,
        page: Int,
        limit: Int,
    ): List<Playlist> {
        val mid = firstNonEmpty(cookieMap["KUGOU_API_MID"].orEmpty(), "-")
        val dfid = firstNonEmpty(cookieMap["dfid"].orEmpty(), "-")
        val clienttime = nowSeconds()
        val data = buildJsonObject {
            put("userid", userID)
            put("token", cookieMap["token"].orEmpty())
            put("total_ver", 979)
            put("type", 2)
            put("page", page)
            put("pagesize", limit)
        }.toString()
        val params = mapOf(
            "dfid" to dfid,
            "mid" to mid,
            "uuid" to "-",
            "appid" to KUGOU_LITE_APP_ID,
            "clientver" to KUGOU_LITE_VER,
            "clienttime" to clienttime,
            "token" to cookieMap["token"].orEmpty(),
            "userid" to userID,
            "plat" to "1",
        )
        val url = buildAndroidUrl("https://gateway.kugou.com/v7/get_all_list", params, data)
        val body = NativeHttp.postJson(
            url,
            data,
            headers = androidHeaders(dfid, mid, clienttime, "cloudlist.service.kugou.com"),
            cookie = cookie,
            randomIp = true,
            userAgent = KUGOU_ANDROID_UA,
        ).body
        return parseKugouUserPlaylists(body, userID)
    }

    private fun parseKugouUserPlaylists(body: String, userID: String): List<Playlist> {
        val resp = json.decodeFromString<KugouUserPlaylistResponse>(body)
        if (resp.status != 0 && resp.status != 1) {
            throw IllegalStateException(
                "kugou user playlist api error: status=${resp.status} errcode=${resp.errcode} error=${resp.error}"
            )
        }
        val playlists = mutableListOf<Playlist>()

        for (item in resp.plist.list.info) {
            if (item.specialId == 0 || item.specialName.trim().isEmpty()) continue
            val playlistId = item.specialId.toString()
            playlists += Playlist(
                id = playlistId,
                name = item.specialName,
                cover = item.imgUrl.replaceFirst("{size}", "240"),
                trackCount = item.songCount,
                playCount = item.playCount.toLong(),
                creator = firstNonEmpty(item.nickName, userID),
                description = item.intro,
                source = "kugou",
                link = "https://www.kugou.com/yy/special/single/$playlistId.html",
                extra = mapOf(
                    "user_id" to userID,
                    "collect_count" to item.collectCount.toString(),
                    "publish_time" to item.publishTime,
                ),
            )
        }

        for (item in resp.data.info) {
            val listId = item.listId.numericString()
            if (listId.isNotEmpty() && listId != "0") {
                val name = firstNonEmpty(item.name, item.specialName)
                if (name.isEmpty()) continue
                var trackCount = item.count.numericString().toIntOrNull() ?: 0
                if (trackCount == 0) trackCount = item.songCount
                val playlistId = kugouCloudlistId(listId)
                val globalCollectionId = item.globalCollectionId.trim()
                val link = if (globalCollectionId.isNotEmpty()) {
                    "https://www.kugou.com/songlist/$globalCollectionId/"
                } else {
                    ""
                }
                playlists += Playlist(
                    id = playlistId,
                    name = name,
                    cover = firstNonEmpty(item.pic, item.imgUrl).replaceFirst("{size}", "240"),
                    trackCount = trackCount,
                    playCount = item.playCount.toLong(),
                    creator = firstNonEmpty(item.listCreateUsername, item.username, item.nickName, userID),
                    description = item.intro,
                    link = link,
                    source = "kugou",
                    extra = mapOf(
                        "user_id" to userID,
                        "cloud_listid" to listId,
                        "global_collection_id" to globalCollectionId,
                        "list_create_userid" to item.listCreateUserId.numericString(),
                        "list_create_listid" to item.listCreateListId.numericString(),
                        "list_create_gid" to item.listCreateGid,
                        "update_time" to item.updateTime.numericString(),
                    ),
                )
                continue
            }

            val playlistId = if (item.specialId > 0) item.specialId.toString() else item.globalSpecialId.trim()
            val name = firstNonEmpty(item.specialName, item.name)
            if (playlistId.isEmpty() || name.isEmpty()) continue
            playlists += Playlist(
                id = playlistId,
                name = name,
                cover = firstNonEmpty(item.imgUrl, item.pic).replaceFirst("{size}", "240"),
                trackCount = item.songCount,
                playCount = item.playCount.toLong(),
                creator = firstNonEmpty(item.username, item.nickName, userID),
                description = item.intro,
                source = "kugou",
                link = "https://www.kugou.com/yy/special/single/$playlistId.html",
                extra = mapOf(
                    "user_id" to userID,
                    "global_specialid" to item.globalSpecialId,
                    "collect_count" to item.collectCount.toString(),
                    "publish_time" to item.publishTime,
                ),
            )
        }

        for (item in resp.data.list) {
            if (item.specialId == 0 || item.specialName.trim().isEmpty()) continue
            val playlistId = item.specialId.toString()
            playlists += Playlist(
                id = playlistId,
                name = item.specialName,
                cover = item.imgUrl.replaceFirst("{size}", "240"),
                trackCount = item.songCount,
                playCount = item.playCount.toLong(),
                creator = firstNonEmpty(item.nickName, userID),
                description = item.intro,
                source = "kugou",
                link = "https://www.kugou.com/yy/special/single/$playlistId.html",
                extra = mapOf("user_id" to userID),
            )
        }
        return playlists
    }

    // ---- top lists ----

    private suspend fun fetchTopListSongs(cookie: String, rawId: String, limit: Int): List<Song> {
        val topListId = rawId.trim()
        if (topListId.isEmpty()) throw IllegalArgumentException("kugou toplist id is empty")
        if (!topListId.all { it in '0'..'9' }) throw IllegalArgumentException("invalid kugou toplist id: $topListId")

        var pageSize = 300
        if (limit > 0 && limit < pageSize) pageSize = limit

        val songs = mutableListOf<Song>()
        var page = 1
        while (true) {
            val url = "http://mobilecdn.kugou.com/api/v3/rank/song?" + query(
                mapOf(
                    "version" to "9108",
                    "plat" to "0",
                    "ranktype" to "0",
                    "area_code" to "1",
                    "rankid" to topListId,
                    "page" to page.toString(),
                    "pagesize" to pageSize.toString(),
                    "with_res_tag" to "0",
                )
            )
            val body = NativeHttp.get(
                url,
                headers = mapOf("Referer" to MOBILE_REFERER),
                cookie = cookie,
                randomIp = true,
                userAgent = MOBILE_USER_AGENT,
            )
            val (pageSongs, total) = parseTopListSongs(body, topListId, (page - 1) * pageSize)
            if (pageSongs.isEmpty()) break

            songs += pageSongs
            if (limit > 0 && songs.size >= limit) {
                while (songs.size > limit) songs.removeAt(songs.size - 1)
                break
            }
            if (total > 0 && songs.size >= total) break
            if (pageSongs.size < pageSize) break
            page++
        }

        if (songs.isEmpty()) throw IllegalStateException("kugou toplist $topListId has no songs")
        return songs
    }

    private fun parseTopListSongs(body: String, topListId: String, offset: Int): Pair<List<Song>, Int> {
        val resp = json.decodeFromString<KugouTopListSongsResponse>(body)
        if (resp.status != 1 || resp.errcode != 0) {
            throw IllegalStateException(
                "kugou toplist songs api error: ${resp.error} (status ${resp.status} errcode ${resp.errcode})"
            )
        }
        val songs = mutableListOf<Song>()
        resp.data.info.forEachIndexed { index, item ->
            val finalHash = firstNonEmpty(
                item.hash,
                item.sqHash,
                item.hqHash,
                item.hashHigh,
                item.transParam.ogg320Hash,
                item.transParam.ogg128Hash,
            )
            if (!isValidHash(finalHash)) return@forEachIndexed

            var name = item.songName.trim()
            var artist = kugouTopListArtist(item.authors)
            if (name.isEmpty() || artist.isEmpty()) {
                val parts = item.filename.split(" - ")
                if (parts.size >= 2) {
                    if (artist.isEmpty()) artist = parts[0].trim()
                    if (name.isEmpty()) name = parts.drop(1).joinToString(" - ").trim()
                } else if (name.isEmpty()) {
                    name = item.filename.trim()
                }
            }
            if (name.isEmpty()) return@forEachIndexed

            var size = item.fileSize
            when (finalHash) {
                item.sqHash -> if (item.sqFileSize > 0) size = item.sqFileSize
                item.hqHash -> if (item.hqFileSize > 0) size = item.hqFileSize
            }

            var bitrate = item.bitrate
            if (bitrate <= 0 && size > 0 && item.duration > 0) {
                bitrate = (size * 8 / 1000 / item.duration).toInt()
            }

            songs += Song(
                id = finalHash,
                name = cleanKugouText(name),
                artist = cleanKugouText(artist),
                album = cleanKugouText(item.remark),
                albumId = item.albumId,
                duration = item.duration,
                size = size,
                bitrate = bitrate,
                source = "kugou",
                cover = kugouTopListSongCover(item.cover, item.transParam.unionCover),
                link = "https://www.kugou.com/song/#hash=$finalHash",
                extra = mapOf(
                    "hash" to finalHash,
                    "file_hash" to item.hash,
                    "sq_hash" to item.sqHash,
                    "hq_hash" to item.hqHash,
                    "res_hash" to item.hashSuper,
                    "high_hash" to item.hashHigh,
                    "ogg_320_hash" to item.transParam.ogg320Hash,
                    "ogg_128_hash" to item.transParam.ogg128Hash,
                    "mv_hash" to item.mvHash,
                    "audio_id" to item.audioId.toString(),
                    "album_audio_id" to item.albumAudioId.toString(),
                    "album_id" to item.albumId,
                    "privilege" to item.privilege.toString(),
                    "toplist_id" to topListId,
                    "rank" to (offset + index + 1).toString(),
                ),
            )
        }
        return songs to resp.data.total
    }

    // ---- QR login ----

    private suspend fun loginWebGet(url: String, params: Map<String, String>, cookies: Map<String, String>): String {
        val clienttime = nowSeconds()
        val finalParams = mutableMapOf(
            "dfid" to firstNonEmpty(cookies["dfid"].orEmpty(), "-"),
            "mid" to firstNonEmpty(cookies["KUGOU_API_MID"].orEmpty(), "-"),
            "uuid" to "-",
            "appid" to KUGOU_LITE_APP_ID,
            "clientver" to KUGOU_LITE_VER,
            "clienttime" to clienttime,
        )
        finalParams.putAll(params)
        val signature = signSonginfoParams(finalParams)
        val fullParams = finalParams + ("signature" to signature)
        return NativeHttp.get(
            "$url?${query(fullParams)}",
            headers = androidHeaders(finalParams["dfid"].orEmpty(), finalParams["mid"].orEmpty(), clienttime),
            cookie = joinCookies(cookies),
            randomIp = true,
            userAgent = KUGOU_ANDROID_UA,
        )
    }

    private suspend fun registerLoginDevice(cookies: MutableMap<String, String>) {
        val aesSeed = randomKugouString(6).lowercase()
        val digest = NativeCrypto.md5Hex(aesSeed)
        val aesKey = digest.substring(0, 16).toByteArray(Charsets.UTF_8)
        val aesIv = digest.substring(16, 32).toByteArray(Charsets.UTF_8)
        val deviceJson = buildJsonObject {
            put("availableRamSize", 4983533568L)
            put("availableRomSize", 48114719)
            put("availableSDSize", 48114717)
            put("basebandVer", "")
            put("batteryLevel", 100)
            put("batteryStatus", 3)
            put("brand", "Redmi")
            put("buildSerial", "unknown")
            put("device", "marble")
            put("imei", cookies["KUGOU_API_GUID"].orEmpty())
            put("imsi", "")
            put("manufacturer", "Xiaomi")
            put("uuid", cookies["KUGOU_API_GUID"].orEmpty())
            put("accelerometer", false)
            put("accelerometerValue", "")
            put("gravity", false)
            put("gravityValue", "")
            put("gyroscope", false)
            put("gyroscopeValue", "")
            put("light", false)
            put("lightValue", "")
            put("magnetic", false)
            put("magneticValue", "")
            put("orientation", false)
            put("orientationValue", "")
            put("pressure", false)
            put("pressureValue", "")
            put("step_counter", false)
            put("step_counterValue", "")
            put("temperature", false)
            put("temperatureValue", "")
        }.toString()
        val encrypted = aesCbcEncrypt(deviceJson.toByteArray(Charsets.UTF_8), aesKey, aesIv)
        val p = rsaPkcs1Hex(
            buildJsonObject {
                put("aes", aesSeed)
                put("uid", cookies["userid"].orEmpty())
                put("token", cookies["token"].orEmpty())
            }.toString()
        )
        val data = Base64.getEncoder().encodeToString(encrypted)
        val clienttime = nowSeconds()
        val dfid = firstNonEmpty(cookies["dfid"].orEmpty(), "-")
        val mid = firstNonEmpty(cookies["KUGOU_API_MID"].orEmpty(), "-")
        val params = mapOf(
            "dfid" to dfid,
            "mid" to mid,
            "uuid" to "-",
            "appid" to KUGOU_LITE_APP_ID,
            "clientver" to KUGOU_LITE_VER,
            "clienttime" to clienttime,
            "token" to cookies["token"].orEmpty(),
            "userid" to cookies["userid"].orEmpty(),
            "part" to "1",
            "platid" to "1",
            "p" to p,
        )
        val url = buildAndroidUrl("https://userservice.kugou.com/risk/v2/r_register_dev", params, data)
        val body = NativeHttp.postJson(
            url,
            data,
            headers = androidHeaders(dfid, mid, clienttime),
            cookie = joinCookies(cookies),
            randomIp = true,
            userAgent = KUGOU_ANDROID_UA,
        ).body
        val resp = json.decodeFromString<KugouRegisterResponse>(decryptRegisterBody(body, aesKey, aesIv))
        if (resp.status != 1) {
            throw IllegalStateException(
                "kugou register device api error: status=${resp.status} error_code=${resp.errorCode} error=${resp.error}"
            )
        }
        if (resp.data.dfid.trim().isNotEmpty()) cookies["dfid"] = resp.data.dfid
    }

    private fun decryptRegisterBody(body: String, key: ByteArray, iv: ByteArray): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return trimmed
        val decrypted = runCatching {
            aesCbcDecrypt(trimmed.toByteArray(Charsets.ISO_8859_1), key, iv)
        }.getOrElse { throw IllegalStateException("kugou register device response is not json") }
        val text = decrypted.toString(Charsets.UTF_8).trim()
        if (!text.startsWith("{")) throw IllegalStateException("kugou register device response is not json")
        return text
    }

    // ---- shared helpers ----

    private fun aesCbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(NativeCrypto.pkcs7Pad(data, 16))
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return NativeCrypto.pkcs7Unpad(cipher.doFinal(data))
    }

    private fun rsaPkcs1Hex(payload: String): String {
        val der = Base64.getDecoder().decode(KUGOU_LITE_PUBLIC_KEY)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return NativeCrypto.bytesToHex(cipher.doFinal(payload.toByteArray(Charsets.UTF_8)), upper = true)
    }

    /** 酷狗用户信息接口的 p 参数：零填充后直接做 RSA 幂运算（不是 PKCS#1 填充）。 */
    private fun rsaRawHex(payload: String): String {
        val der = Base64.getDecoder().decode(KUGOU_LITE_PUBLIC_KEY)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        val value = BigInteger(1, payload.toByteArray(Charsets.UTF_8))
        return value.modPow(key.publicExponent, key.modulus).toString(16).padStart(256, '0').uppercase(Locale.ROOT)
    }

    private fun decodeKrc(content: String): String {
        val decoded = Base64.getDecoder().decode(content)
        if (decoded.size < 4) throw IllegalStateException("krc content too short")
        val encrypted = decoded.copyOfRange(4, decoded.size)
        val plain = ByteArray(encrypted.size) { index ->
            (encrypted[index].toInt() xor KRC_KEY[index % KRC_KEY.size].toInt()).toByte()
        }
        return inflateKrc(plain)
    }

    private fun inflateKrc(data: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buffer, 0, count)
                }
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun convertKrcToLrc(raw: String, song: Song): String {
        val tags = mutableMapOf<String, String>()
        val orig = mutableListOf<KrcLine>()
        for (rawLine in raw.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val tagMatch = KRC_TAG_REGEX.find(line)
            if (tagMatch != null) {
                tags[tagMatch.groupValues[1]] = tagMatch.groupValues[2]
                continue
            }
            val match = KRC_LINE_REGEX.find(line) ?: continue
            val start = match.groupValues[1].toIntOrNull() ?: 0
            val duration = match.groupValues[2].toIntOrNull() ?: 0
            val content = match.groupValues[3]
            val words = mutableListOf<KrcWord>()
            for (wordMatch in KRC_WORD_REGEX.findAll(content)) {
                val wordStart = start + (wordMatch.groupValues[1].toIntOrNull() ?: 0)
                val wordEnd = wordStart + (wordMatch.groupValues[2].toIntOrNull() ?: 0)
                val text = wordMatch.groupValues[3]
                if (text.isEmpty()) continue
                words += KrcWord(wordStart, wordEnd, text)
            }
            if (words.isEmpty() && content.isNotEmpty()) {
                words += KrcWord(start, start + duration, content)
            }
            orig += KrcLine(start, start + duration, words)
        }
        if (orig.isEmpty()) throw IllegalStateException("lyrics content is empty")

        val data = mutableMapOf<String, List<KrcLine>>("orig" to orig)
        val language = tags["language"].orEmpty().trim()
        if (language.isNotEmpty()) addKrcLanguage(data, orig, language)

        val builder = StringBuilder()
        var wroteTag = false
        for ((key, value) in listOf(
            "ti" to firstNonEmpty(tags["ti"].orEmpty(), song.name),
            "ar" to firstNonEmpty(tags["ar"].orEmpty(), song.artist),
            "al" to firstNonEmpty(tags["al"].orEmpty(), song.album),
            "by" to tags["by"].orEmpty(),
            "offset" to tags["offset"].orEmpty(),
        )) {
            if (value.trim().isEmpty()) continue
            builder.append('[').append(key).append(':').append(value.trim()).append("]\n")
            wroteTag = true
        }
        if (wroteTag) builder.append('\n')

        for ((index, origLine) in orig.withIndex()) {
            val lineStart = lineStart(origLine)
            for (lang in listOf("orig", "roma", "ts")) {
                val lines = data[lang].orEmpty()
                if (lines.isEmpty()) continue
                val mapped = mappedIndex(lines, index, origLine)
                if (mapped < 0 || mapped >= lines.size) continue
                if (!lineHasText(lines[mapped])) continue
                builder.append(krcLineToLrc(lines[mapped], lineStart)).append('\n')
            }
        }
        return builder.toString().trimEnd('\n')
    }

    private fun addKrcLanguage(data: MutableMap<String, List<KrcLine>>, orig: List<KrcLine>, encoded: String) {
        val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return
        val payload = runCatching {
            json.decodeFromString<KugouKrcLanguage>(decoded.toString(Charsets.UTF_8))
        }.getOrNull() ?: return

        for (language in payload.content) {
            when (language.type) {
                0 -> {
                    val roma = mutableListOf<KrcLine>()
                    var skipped = 0
                    orig.forEachIndexed { index, line ->
                        if (!lineHasText(line)) {
                            skipped++
                            return@forEachIndexed
                        }
                        val lineIndex = index - skipped
                        if (lineIndex < 0 || lineIndex >= language.lyricContent.size) return@forEachIndexed
                        val words = mutableListOf<KrcWord>()
                        line.words.forEachIndexed wordLoop@{ wordIndex, word ->
                            if (wordIndex >= language.lyricContent[lineIndex].size) return@wordLoop
                            words += KrcWord(word.startMs, word.endMs, language.lyricContent[lineIndex][wordIndex])
                        }
                        roma += KrcLine(line.startMs, line.endMs, words)
                    }
                    if (roma.isNotEmpty()) data["roma"] = roma
                }
                1 -> {
                    val ts = mutableListOf<KrcLine>()
                    orig.forEachIndexed { index, line ->
                        if (index >= language.lyricContent.size || language.lyricContent[index].isEmpty()) {
                            return@forEachIndexed
                        }
                        val text = language.lyricContent[index][0]
                        ts += KrcLine(
                            line.startMs,
                            line.endMs,
                            listOf(KrcWord(line.startMs, line.endMs, text)),
                        )
                    }
                    if (ts.isNotEmpty()) data["ts"] = ts
                }
            }
        }
    }

    private fun krcLineToLrc(line: KrcLine, forcedStart: Int): String {
        val start = if (forcedStart >= 0) forcedStart else lineStart(line)
        val builder = StringBuilder()
        builder.append('[').append(formatKugouTime(start)).append(']')
        for (word in line.words) {
            if (word.text.isEmpty()) continue
            builder.append('<').append(formatKugouTime(word.startMs)).append('>').append(word.text)
        }
        return builder.toString()
    }

    private fun mappedIndex(lines: List<KrcLine>, index: Int, orig: KrcLine): Int {
        for ((lineIndex, line) in lines.withIndex()) {
            if (line.startMs == orig.startMs && lineHasText(line)) return lineIndex
        }
        if (lines.isNotEmpty()) return -1
        return if (index < lines.size) index else -1
    }

    private fun lineStart(line: KrcLine): Int =
        line.words.firstOrNull()?.startMs ?: line.startMs

    private fun lineHasText(line: KrcLine): Boolean =
        line.words.any { it.text.trim().isNotEmpty() }

    private fun formatKugouTime(ms: Int): String {
        val value = ms.coerceAtLeast(0)
        val minute = value / 60000
        val second = (value % 60000) / 1000
        val centisecond = (value % 1000) / 10
        return String.format(Locale.ROOT, "%02d:%02d.%02d", minute, second, centisecond)
    }

    private fun pickSonglistHashes(
        defaultHash: String,
        defaultSize: Long,
        defaultBitrate: Int,
        goods: List<KugouRelateGood>,
    ): SonglistHashes {
        var fileHash = defaultHash.trim()
        var size = defaultSize
        var bitrate = defaultBitrate
        var hqHash = ""
        var sqHash = ""
        for (item in goods) {
            val hash = item.hash.trim()
            if (!isValidHash(hash)) continue
            when {
                item.bitrate >= 700 -> {
                    sqHash = hash
                    size = item.size
                    bitrate = item.bitrate
                }
                item.bitrate >= 320 -> {
                    if (sqHash.isEmpty()) {
                        size = item.size
                        bitrate = item.bitrate
                    }
                    hqHash = hash
                }
                else -> if (fileHash.isEmpty()) fileHash = hash
            }
        }
        if (fileHash.isEmpty()) {
            if (hqHash.isNotEmpty()) fileHash = hqHash else if (sqHash.isNotEmpty()) fileHash = sqHash
        }
        return SonglistHashes(fileHash, hqHash, sqHash, size, bitrate)
    }

    private fun joinSonglistArtists(artists: List<KugouSinger>): String =
        artists.mapNotNull { it.name.trim().takeIf { name -> name.isNotEmpty() } }.joinToString("/")

    private fun pickSonglistSongName(name: String): String {
        val index = name.indexOf(" - ")
        if (index >= 0) {
            val rest = name.substring(index + 3).trim()
            if (rest.isNotEmpty()) return rest
        }
        return name.trim()
    }

    private fun collectCandidateHashes(song: Song): List<String> {
        val hashes = mutableListOf(
            song.extra["hash"].orEmpty(),
            song.extra["sq_hash"].orEmpty(),
            song.extra["hq_hash"].orEmpty(),
            song.extra["res_hash"].orEmpty(),
            song.extra["ogg_320_hash"].orEmpty(),
            song.extra["file_hash"].orEmpty(),
            song.extra["ogg_128_hash"].orEmpty(),
            song.id,
        )
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        for (hash in hashes) {
            val normalized = hash.trim().lowercase()
            if (!isValidHash(normalized)) continue
            if (!seen.add(normalized)) continue
            result += normalized
        }
        return result
    }

    private fun parseKugouCookie(cookie: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in cookie.split(';')) {
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) continue
            val index = trimmed.indexOf('=')
            if (index <= 0) continue
            result[trimmed.substring(0, index).trim()] = trimmed.substring(index + 1).trim()
        }
        return result
    }

    private fun kugouHasAppCookie(cookie: Map<String, String>): Boolean =
        cookie["token"].orEmpty().trim().isNotEmpty() &&
            cookie["userid"].orEmpty().trim().isNotEmpty() &&
            cookie["userid"].orEmpty().trim() != "0" &&
            cookie["KUGOU_API_MID"].orEmpty().trim().isNotEmpty()

    private fun kugouCloudlistId(listId: String): String {
        val trimmed = listId.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("cloudlist:")) return trimmed
        return "cloudlist:$trimmed"
    }

    private fun initLoginDevice(): MutableMap<String, String> {
        val guid = randomKugouGuid()
        return mutableMapOf(
            "KUGOU_API_GUID" to guid,
            "KUGOU_API_MID" to calculateKugouMid(guid),
            "KUGOU_API_MAC" to randomKugouString(12),
            "KUGOU_API_DEV" to randomKugouString(16),
        )
    }

    private fun randomKugouString(length: Int): String {
        val chars = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return buildString(length) {
            for (byte in bytes) append(chars[(byte.toInt() and 0xff) % chars.length])
        }
    }

    private fun randomKugouGuid(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = NativeCrypto.bytesToHex(bytes)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun calculateKugouMid(seed: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest).toString()
    }

    private fun randomKugouDfid(): String =
        NativeCrypto.md5Hex(System.nanoTime().toString()).uppercase().take(24)

    private fun isValidHash(hash: String): Boolean =
        hash.isNotEmpty() && hash != "00000000000000000000000000000000"

    private fun normalizeKugouDuration(value: Int): Int = if (value > 1000) value / 1000 else value

    private fun normalizeKugouBitrate(value: Int): Int = if (value > 1000) value / 1000 else value

    private fun normalizeKugouExt(extName: String, downloadUrl: String): String {
        val ext = extName.trim().lowercase()
        if (ext.isNotEmpty()) return ext
        val path = runCatching { URI(downloadUrl).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.endsWith(".flac") -> "flac"
            path.endsWith(".ape") -> "ape"
            path.endsWith(".mp3") -> "mp3"
            else -> ""
        }
    }

    private fun looksLossless(ext: String, bitrate: Int, size: Long): Boolean {
        val normalized = ext.trim().lowercase()
        if (normalized == "flac" || normalized == "ape" || normalized == "wav") return true
        return bitrate >= 700 || size >= 20L * 1024 * 1024
    }

    private fun pickKugouResponseUrl(resp: JsonObject): String {
        resp["url"].pickUrlValue()?.let { return it.replace("\\/", "/") }
        resp["backup_url"].pickUrlValue()?.let { return it.replace("\\/", "/") }
        val data = resp["data"] as? JsonObject ?: return ""
        data["url"].pickUrlValue()?.let { return it.replace("\\/", "/") }
        data["backup_url"].pickUrlValue()?.let { return it.replace("\\/", "/") }
        for (key in listOf("flac", "high", "320", "128", "super")) {
            val item = data[key] as? JsonObject ?: continue
            item["url"].pickUrlValue()?.let { return it.replace("\\/", "/") }
            item["backup_url"].pickUrlValue()?.let { return it.replace("\\/", "/") }
        }
        return ""
    }

    private fun JsonElement?.pickUrlValue(): String? = when (this) {
        is JsonPrimitive -> content.takeIf { it.isNotEmpty() }
        is JsonArray -> firstNotNullOfOrNull { element ->
            (element as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        }
        else -> null
    }

    private fun JsonObject.findString(vararg keys: String): String {
        for (key in keys) {
            val value = this[key]?.numericString().orEmpty().trim()
            if (value.isNotEmpty()) return value
        }
        val data = this["data"] as? JsonObject ?: return ""
        return data.findString(*keys)
    }

    private fun JsonObject.findInt(vararg keys: String): Int {
        for (key in keys) {
            val primitive = this[key] as? JsonPrimitive ?: continue
            val content = primitive.content.trim()
            content.toIntOrNull()?.let { return it }
            content.toDoubleOrNull()?.let { return it.toInt() }
        }
        val data = this["data"] as? JsonObject ?: return 0
        return data.findInt(*keys)
    }

    private fun JsonObject.findLong(vararg keys: String): Long {
        for (key in keys) {
            val primitive = this[key] as? JsonPrimitive ?: continue
            val content = primitive.content.trim()
            content.toLongOrNull()?.let { return it }
            content.toDoubleOrNull()?.let { return it.toLong() }
        }
        val data = this["data"] as? JsonObject ?: return 0L
        return data.findLong(*keys)
    }

    private fun JsonElement?.numericString(): String {
        val primitive = this as? JsonPrimitive ?: return ""
        if (primitive.isString) return primitive.content.trim()
        val content = primitive.content.trim()
        if (content.isEmpty()) return ""
        if (!content.contains('.') && !content.contains('e', ignoreCase = true)) return content
        val value = content.toDoubleOrNull() ?: return content
        return String.format(Locale.ROOT, "%.0f", value)
    }

    private fun JsonElement?.fileSizeLong(): Long {
        val primitive = this as? JsonPrimitive ?: return 0L
        val content = primitive.content.trim()
        return content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong() ?: 0L
    }

    private fun firstNonEmpty(vararg values: String): String {
        for (value in values) {
            if (value.trim().isNotEmpty()) return value.trim()
        }
        return ""
    }

    private fun cleanKugouText(value: String): String =
        KUGOU_HTML_TAG_REGEX.replace(unescapeKugouHtml(value), "").trim()

    private fun unescapeKugouHtml(value: String): String =
        KUGOU_HTML_ENTITY_REGEX.replace(value) { match ->
            val entity = match.groupValues[1]
            when {
                entity.startsWith("#x", ignoreCase = true) ->
                    entity.substring(2).toIntOrNull(16)?.toChar()?.toString() ?: match.value
                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.toChar()?.toString() ?: match.value
                else -> when (entity.lowercase()) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> match.value
                }
            }
        }

    private fun kugouTopListGroupName(classify: Int): String = when (classify) {
        1 -> "热门榜"
        2 -> "地区榜"
        3 -> "特色榜"
        4 -> "全球榜"
        5 -> "曲风榜"
        else -> "其他"
    }

    private fun kugouTopListCover(vararg urls: String): String {
        for (raw in urls) {
            var cover = raw.trim()
            if (cover.isEmpty()) continue
            cover = cover.replaceFirst("{size}", "400")
            if (cover.startsWith("http://")) cover = "https://" + cover.removePrefix("http://")
            return cover
        }
        return ""
    }

    private fun kugouTopListSongCover(vararg urls: String): String {
        for (raw in urls) {
            var cover = raw.trim()
            if (cover.isEmpty()) continue
            cover = cover.replaceFirst("{size}", "240")
            if (cover.startsWith("http://")) cover = "https://" + cover.removePrefix("http://")
            return cover
        }
        return ""
    }

    private fun cleanTopListIntro(intro: String): String {
        val flattened = intro.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
        return flattened.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun kugouTopListArtist(authors: List<KugouAuthor>): String =
        authors.mapNotNull { it.authorName.trim().takeIf { name -> name.isNotEmpty() } }.joinToString(", ")

    private fun parseCategoryId(categoryId: String): Pair<String, String> {
        val index = categoryId.indexOf(':')
        val id = if (index >= 0) categoryId.substring(0, index).trim() else categoryId.trim()
        var tagId = if (index >= 0) categoryId.substring(index + 1).trim() else "0"
        if (tagId.isEmpty()) tagId = "0"
        return id to tagId
    }

    private fun mapQrStatus(status: Int): String = when (status) {
        4 -> "success"
        2, 3 -> "scanned"
        -1, 5, 6 -> "expired"
        0, 1 -> "waiting"
        else -> "failed"
    }

    private fun joinCookies(cookies: Map<String, String>): String =
        cookies.entries
            .filter { it.key.trim().isNotEmpty() }
            .sortedBy { it.key }
            .joinToString("; ") { "${it.key}=${it.value}" }

    private fun nowSeconds(): String = (System.currentTimeMillis() / 1000).toString()

    /** 歌手详情会在 JSON 前后夹 HTML 注释标记，解析前去掉。 */
    private fun kugouStripTag(body: String): String = TAG_PATTERN.replace(body, "").trim()

    private fun query(params: Map<String, String>): String =
        params.entries.sortedBy { it.key }.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }

    private fun signSonginfoParams(params: Map<String, String>): String =
        NativeCrypto.md5Hex(KUGOU_SIGN_KEY + signedPairs(params) + KUGOU_SIGN_KEY)

    private fun signAndroidParams(params: Map<String, String>, data: String): String =
        NativeCrypto.md5Hex(KUGOU_LITE_SIGN + signedPairs(params) + data + KUGOU_LITE_SIGN)

    private fun signedPairs(params: Map<String, String>): String =
        params.entries.map { "${it.key}=${it.value}" }.sorted().joinToString("")

    private fun buildSonginfoUrl(params: Map<String, String>): String =
        "https://wwwapi.kugou.com/play/songinfo?" + query(params + ("signature" to signSonginfoParams(params)))

    private fun buildAndroidUrl(base: String, params: Map<String, String>, data: String): String =
        base + "?" + query(params + ("signature" to signAndroidParams(params, data)))

    private fun androidHeaders(
        dfid: String,
        mid: String,
        clienttime: String,
        router: String? = null,
    ): Map<String, String> = buildMap {
        put("dfid", dfid)
        put("clienttime", clienttime)
        put("mid", mid)
        put("kg-rc", "1")
        put("kg-thash", "5d816a0")
        put("kg-rec", "1")
        put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
        if (router != null) put("x-router", router)
    }

    private data class VipCache(var value: Boolean? = null)

    private data class SonglistHashes(
        val fileHash: String,
        val hqHash: String,
        val sqHash: String,
        val size: Long,
        val bitrate: Int,
    )

    private data class KrcWord(val startMs: Int, val endMs: Int, val text: String)

    private data class KrcLine(val startMs: Int, val endMs: Int, val words: List<KrcWord>)

    private companion object {
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1"
        const val SEARCH_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.162 Mobile Safari/537.36"
        const val PC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val KUGOU_ANDROID_UA = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"
        const val MOBILE_REFERER = "http://m.kugou.com"
        const val VIP_INFO_API = "https://vip.kugou.com/recharge/roleinfo"
        const val KUGOU_SIGN_KEY = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
        const val KUGOU_LITE_SIGN = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        const val KUGOU_LITE_APP_ID = "3116"
        const val KUGOU_LITE_VER = "11440"
        const val KUGOU_LITE_KEY = "185672dd44712f60bb1736df5a377e82"
        const val HOT_SONG_TOP_LIST_ID = "8888"
        const val KUGOU_SINGER_SONG_LIMIT = 500

        private val TAG_PATTERN = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        const val KUGOU_LITE_PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"

        val KRC_KEY = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d,
            0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
        )
        val secureRandom = SecureRandom()
        val KUGOU_HASH_REGEX = Regex("""hash=([a-f0-9]{32})""", RegexOption.IGNORE_CASE)
        val KUGOU_SPECIAL_LINK_REGEX = Regex("""special/single/(\d+)\.html""")
        val KUGOU_SONGLIST_LINK_REGEX = Regex("""songlist/(gcid_[a-zA-Z0-9]+)""")
        val KUGOU_SONGLIST_PAYLOAD_REGEX =
            Regex("""window\.[${'$'}]output\s*=\s*(\{.*?\})\s*;\s*</script>""")
        val KUGOU_ALBUM_LINK_REGEXES = listOf(
            Regex("""album/single/(\d+)\.html"""),
            Regex("""yy/album/single/(\d+)\.html"""),
            Regex("""album/(\d+)\.html"""),
            Regex("""albumid=(\d+)"""),
        )
        val KRC_TAG_REGEX = Regex("""^\[([A-Za-z]+):([^\]]*)]${'$'}""")
        val KRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)${'$'}""")
        val KRC_WORD_REGEX = Regex("""(?:\[\d+,\d+])?<(\d+),(\d+),\d+>([^<]*)""")
        val KUGOU_HTML_TAG_REGEX = Regex("<[^>]*>")
        val KUGOU_HTML_ENTITY_REGEX = Regex("""&(#x?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]*);""")
        val WHITESPACE_REGEX = Regex("""\s+""")
    }
}

/**
 * 酷狗会员产品：user_type 的 16 位是超级VIP，vip_type=6 是豪华VIP，
 * busi_vip 里的 svip/tvip 分别是概念VIP/畅听VIP（签到等活动发的会员）。
 */
internal fun kugouMembershipLabel(vipType: Int, userType: Int, products: Map<String, Int>): String? {
    if (userType and 16 != 0) return "超级VIP"
    if (vipType == 6) return "豪华VIP"
    if ((products["svip"] ?: 0) > 0) return "概念VIP"
    if ((products["tvip"] ?: 0) > 0) return "畅听VIP"
    if (vipType > 0 || products.values.any { it > 0 }) return "VIP"
    return null
}

// ---- response DTOs ----

@Serializable
private data class KugouSearchResponse(val data: KugouSearchData = KugouSearchData())

@Serializable
private data class KugouSearchData(val lists: List<KugouSearchItem> = emptyList())

@Serializable
private data class KugouSearchItem(
    @SerialName("Scid") val scid: JsonElement? = null,
    @SerialName("ID") val id: JsonElement? = null,
    @SerialName("MixSongID") val mixSongId: JsonElement? = null,
    @SerialName("SongName") val songName: String = "",
    @SerialName("SingerName") val singerName: String = "",
    @SerialName("AlbumName") val albumName: String = "",
    @SerialName("AlbumID") val albumId: String = "",
    @SerialName("Audioid") val audioId: JsonElement? = null,
    @SerialName("Duration") val duration: Int = 0,
    @SerialName("FileHash") val fileHash: String = "",
    @SerialName("SQFileHash") val sqFileHash: String = "",
    @SerialName("HQFileHash") val hqFileHash: String = "",
    @SerialName("ResFileHash") val resFileHash: String = "",
    @SerialName("MvHash") val mvHash: String = "",
    @SerialName("SQFileSize") val sqFileSize: Long = 0,
    @SerialName("HQFileSize") val hqFileSize: Long = 0,
    @SerialName("ResFileSize") val resFileSize: Long = 0,
    @SerialName("FileSize") val fileSize: JsonElement? = null,
    @SerialName("Image") val image: String = "",
    @SerialName("PayType") val payType: Int = 0,
    @SerialName("Privilege") val privilege: Int = 0,
    @SerialName("trans_param") val transParam: KugouTransParam = KugouTransParam(),
)

@Serializable
private data class KugouTransParam(
    @SerialName("union_cover") val unionCover: String = "",
    @SerialName("ogg_320_hash") val ogg320Hash: String = "",
    @SerialName("ogg_128_hash") val ogg128Hash: String = "",
    @SerialName("ogg_320_filesize") val ogg320FileSize: Long = 0,
    @SerialName("ogg_128_filesize") val ogg128FileSize: Long = 0,
)

@Serializable
private data class KugouSpecialSongResponse(val data: KugouSpecialSongData = KugouSpecialSongData())

@Serializable
private data class KugouSpecialSongData(val info: List<KugouSpecialSongItem> = emptyList())

@Serializable
private data class KugouSpecialSongItem(
    val hash: String = "",
    @SerialName("FileHash") val fileHash: String = "",
    @SerialName("SQFileHash") val sqFileHash: String = "",
    @SerialName("HQFileHash") val hqFileHash: String = "",
    @SerialName("ResFileHash") val resFileHash: String = "",
    @SerialName("MvHash") val mvHash: String = "",
    val filename: String = "",
    val duration: Int = 0,
    @SerialName("filesize") val fileSize: Long = 0,
    @SerialName("SQFileSize") val sqFileSize: Long = 0,
    @SerialName("HQFileSize") val hqFileSize: Long = 0,
    @SerialName("ResFileSize") val resFileSize: Long = 0,
    @SerialName("album_name") val albumName: String = "",
    @SerialName("AlbumID") val albumId: String = "",
    val remark: String = "",
    @SerialName("singername") val singerName: String = "",
    @SerialName("songname") val songName: String = "",
    @SerialName("Audioid") val audioId: JsonElement? = null,
    @SerialName("Privilege") val privilege: Int = 0,
    @SerialName("trans_param") val transParam: KugouTransParam = KugouTransParam(),
)

@Serializable
private data class KugouSonglistPayload(
    @SerialName("encode_gic") val encodeGic: String = "",
    val info: KugouSonglistInfo = KugouSonglistInfo(),
)

@Serializable
private data class KugouSonglistInfo(
    @SerialName("listinfo") val listInfo: KugouSonglistListInfo = KugouSonglistListInfo(),
    val songs: List<KugouSonglistSong> = emptyList(),
)

@Serializable
private data class KugouSonglistListInfo(
    val name: String = "",
    val pic: String = "",
    val intro: String = "",
    @SerialName("list_create_username") val creator: String = "",
    val count: Int = 0,
    val heat: Int = 0,
)

@Serializable
private data class KugouSonglistSong(
    val hash: String = "",
    @SerialName("FileHash") val fileHash: String = "",
    @SerialName("SQFileHash") val sqFileHash: String = "",
    @SerialName("HQFileHash") val hqFileHash: String = "",
    @SerialName("ResFileHash") val resFileHash: String = "",
    @SerialName("MvHash") val mvHash: String = "",
    val name: String = "",
    val bitrate: Int = 0,
    val size: Long = 0,
    @SerialName("timelen") val timeLength: Int = 0,
    val cover: String = "",
    val privilege: Int = 0,
    @SerialName("AlbumID") val albumId: String = "",
    @SerialName("Audioid") val audioId: JsonElement? = null,
    @SerialName("relate_goods") val relateGoods: List<KugouRelateGood> = emptyList(),
    @SerialName("singerinfo") val singerInfo: List<KugouSinger> = emptyList(),
    @SerialName("albuminfo") val albumInfo: KugouAlbumInfo = KugouAlbumInfo(),
    @SerialName("trans_param") val transParam: KugouTransParam = KugouTransParam(),
)

@Serializable
private data class KugouRelateGood(
    val hash: String = "",
    val bitrate: Int = 0,
    val privilege: Int = 0,
    val size: Long = 0,
)

@Serializable
private data class KugouSinger(val name: String = "")

@Serializable
private data class KugouAlbumInfo(
    val id: JsonElement? = null,
    val name: String = "",
)

@Serializable
private data class KugouCloudlistResponse(
    val status: Int = 0,
    @SerialName("error_code") val errorCode: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val data: KugouCloudlistData = KugouCloudlistData(),
)

@Serializable
private data class KugouCloudlistData(
    val count: JsonElement? = null,
    val info: List<KugouCloudlistItem> = emptyList(),
)

@Serializable
private data class KugouCloudlistItem(
    @SerialName("ID") val id: JsonElement? = null,
    val hash: String = "",
    @SerialName("FileHash") val fileHash: String = "",
    @SerialName("SQFileHash") val sqFileHash: String = "",
    @SerialName("HQFileHash") val hqFileHash: String = "",
    @SerialName("ResFileHash") val resFileHash: String = "",
    val name: String = "",
    val filename: String = "",
    @SerialName("timelen") val timeLength: Int = 0,
    val size: Long = 0,
    @SerialName("filesize") val fileSize: Long = 0,
    @SerialName("SQFileSize") val sqFileSize: Long = 0,
    @SerialName("HQFileSize") val hqFileSize: Long = 0,
    @SerialName("ResFileSize") val resFileSize: Long = 0,
    val bitrate: Int = 0,
    @SerialName("album_id") val albumId: JsonElement? = null,
    @SerialName("AlbumID") val albumIdLegacy: JsonElement? = null,
    @SerialName("album_audio_id") val albumAudioId: JsonElement? = null,
    @SerialName("MixSongID") val mixSongId: JsonElement? = null,
    @SerialName("audio_id") val audioId: JsonElement? = null,
    @SerialName("Audioid") val audioIdLegacy: JsonElement? = null,
    val cover: String = "",
    @SerialName("mvhash") val mvHash: String = "",
    val privilege: Int = 0,
    @SerialName("relate_goods") val relateGoods: List<KugouRelateGood> = emptyList(),
    @SerialName("albuminfo") val albumInfo: KugouAlbumInfo = KugouAlbumInfo(),
    @SerialName("singerinfo") val singerInfo: List<KugouSinger> = emptyList(),
    @SerialName("trans_param") val transParam: KugouTransParam = KugouTransParam(),
)

@Serializable
private data class KugouAlbumInfoResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val data: KugouAlbumInfoData = KugouAlbumInfoData(),
)

@Serializable
private data class KugouAlbumInfoData(
    @SerialName("albumid") val albumId: Int = 0,
    @SerialName("albumname") val albumName: String = "",
    @SerialName("singername") val singerName: String = "",
    val intro: String = "",
    @SerialName("imgurl") val imgUrl: String = "",
    @SerialName("publishtime") val publishTime: String = "",
    @SerialName("play_count") val playCount: Int = 0,
    @SerialName("songcount") val songCount: Int = 0,
)

@Serializable
private data class KugouSingerSearchResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    // 没有匹配时接口返回 data:null，而不是空数组
    val data: List<KugouSingerSearchItem>? = null,
)

@Serializable
private data class KugouSingerSearchItem(
    @SerialName("singerid") val singerId: Int = 0,
    @SerialName("singername") val singerName: String = "",
)

@Serializable
private data class KugouSingerInfoResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val data: KugouSingerInfo = KugouSingerInfo(),
)

@Serializable
private data class KugouSingerInfo(
    @SerialName("singerid") val singerId: Int = 0,
    @SerialName("singername") val singerName: String = "",
    @SerialName("songcount") val songCount: Int = 0,
    @SerialName("albumcount") val albumCount: Int = 0,
    @SerialName("imgurl") val imgUrl: String = "",
)

private data class KugouAlbumSongsResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val data: KugouAlbumSongsData = KugouAlbumSongsData(),
)

@Serializable
private data class KugouAlbumSongsData(
    val total: Int = 0,
    val info: List<KugouAlbumSong> = emptyList(),
)

@Serializable
private data class KugouAlbumSong(
    val hash: String = "",
    @SerialName("origin_hash") val originHash: String = "",
    @SerialName("sqhash") val sqHash: String = "",
    @SerialName("320hash") val hqHash: String = "",
    @SerialName("res_hash") val resHash: String = "",
    @SerialName("mvhash") val mvHash: String = "",
    val filename: String = "",
    @SerialName("songname") val songName: String = "",
    @SerialName("singername") val singerName: String = "",
    @SerialName("album_name") val albumName: String = "",
    @SerialName("album_id") val albumId: String = "",
    val duration: Int = 0,
    @SerialName("filesize") val fileSize: Long = 0,
    @SerialName("sqfilesize") val sqFileSize: Long = 0,
    @SerialName("320filesize") val hqFileSize: Long = 0,
    @SerialName("audio_id") val audioId: Long = 0,
    val privilege: Int = 0,
    val remark: String = "",
    @SerialName("trans_param") val transParam: KugouTransParam = KugouTransParam(),
)

@Serializable
private data class KugouAlbumSearchResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val data: KugouAlbumSearchData = KugouAlbumSearchData(),
)

@Serializable
private data class KugouAlbumSearchData(val info: List<KugouAlbumSearchItem> = emptyList())

@Serializable
private data class KugouAlbumSearchItem(
    @SerialName("albumid") val albumId: Int = 0,
    @SerialName("albumname") val albumName: String = "",
    @SerialName("singername") val singerName: String = "",
    @SerialName("publishtime") val publishTime: String = "",
    @SerialName("imgurl") val imgUrl: String = "",
    val intro: String = "",
    @SerialName("songcount") val songCount: Int = 0,
)

@Serializable
private data class KugouPlaylistSearchResponse(
    val data: KugouPlaylistSearchData = KugouPlaylistSearchData(),
)

@Serializable
private data class KugouPlaylistSearchData(val info: List<KugouPlaylistSearchItem> = emptyList())

@Serializable
private data class KugouPlaylistSearchItem(
    @SerialName("specialid") val specialId: Int = 0,
    @SerialName("specialname") val specialName: String = "",
    val intro: String = "",
    @SerialName("imgurl") val imgUrl: String = "",
    @SerialName("songcount") val songCount: Int = 0,
    @SerialName("playcount") val playCount: Int = 0,
    @SerialName("nickname") val nickName: String = "",
    @SerialName("publishtime") val publishTime: String = "",
)

@Serializable
private data class KugouRecommendedResponse(
    val plist: KugouRecommendedPlist = KugouRecommendedPlist(),
)

@Serializable
private data class KugouRecommendedPlist(
    val list: KugouRecommendedList = KugouRecommendedList(),
)

@Serializable
private data class KugouRecommendedList(val info: List<KugouRecommendedItem> = emptyList())

@Serializable
private data class KugouRecommendedItem(
    @SerialName("specialid") val specialId: Int = 0,
    @SerialName("specialname") val specialName: String = "",
    @SerialName("imgurl") val imgUrl: String = "",
    @SerialName("playcount") val playCount: Int = 0,
    @SerialName("songcount") val songCount: Int = 0,
    val username: String = "",
    val intro: String = "",
)

@Serializable
private data class KugouCategoryResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val data: KugouCategoryData = KugouCategoryData(),
)

@Serializable
private data class KugouCategoryData(val info: List<KugouCategoryGroup> = emptyList())

@Serializable
private data class KugouCategoryGroup(
    val id: Int = 0,
    val name: String = "",
    val children: List<KugouCategoryChild> = emptyList(),
)

@Serializable
private data class KugouCategoryChild(
    val id: Int = 0,
    val name: String = "",
    @SerialName("special_tag_id") val specialTagId: Int = 0,
    @SerialName("is_hot") val isHot: Int = 0,
)

@Serializable
private data class KugouCategoryPlaylistResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val data: KugouCategoryPlaylistData = KugouCategoryPlaylistData(),
)

@Serializable
private data class KugouCategoryPlaylistData(
    val info: List<KugouCategoryPlaylistItem> = emptyList(),
)

@Serializable
private data class KugouCategoryPlaylistItem(
    @SerialName("specialid") val specialId: Int = 0,
    @SerialName("global_specialid") val globalSpecialId: String = "",
    @SerialName("specialname") val specialName: String = "",
    @SerialName("imgurl") val imgUrl: String = "",
    val intro: String = "",
    @SerialName("playcount") val playCount: Int = 0,
    @SerialName("songcount") val songCount: Int = 0,
    val username: String = "",
    @SerialName("singername") val singerName: String = "",
    @SerialName("publishtime") val publishTime: String = "",
)

@Serializable
private data class KugouUserPlaylistResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val data: KugouUserPlaylistData = KugouUserPlaylistData(),
    val plist: KugouUserPlist = KugouUserPlist(),
)

@Serializable
private data class KugouUserPlaylistData(
    val info: List<KugouUserPlaylistItem> = emptyList(),
    val list: List<KugouUserSpecialItem> = emptyList(),
)

@Serializable
private data class KugouUserPlaylistItem(
    @SerialName("listid") val listId: JsonElement? = null,
    @SerialName("specialid") val specialId: Int = 0,
    @SerialName("global_specialid") val globalSpecialId: String = "",
    @SerialName("global_collection_id") val globalCollectionId: String = "",
    @SerialName("specialname") val specialName: String = "",
    val name: String = "",
    @SerialName("imgurl") val imgUrl: String = "",
    val pic: String = "",
    val intro: String = "",
    @SerialName("playcount") val playCount: Int = 0,
    @SerialName("songcount") val songCount: Int = 0,
    val count: JsonElement? = null,
    @SerialName("collectcount") val collectCount: Int = 0,
    val username: String = "",
    @SerialName("nickname") val nickName: String = "",
    @SerialName("list_create_username") val listCreateUsername: String = "",
    @SerialName("list_create_userid") val listCreateUserId: JsonElement? = null,
    @SerialName("list_create_listid") val listCreateListId: JsonElement? = null,
    @SerialName("list_create_gid") val listCreateGid: String = "",
    @SerialName("publishtime") val publishTime: String = "",
    @SerialName("update_time") val updateTime: JsonElement? = null,
)

@Serializable
private data class KugouUserSpecialItem(
    @SerialName("specialid") val specialId: Int = 0,
    @SerialName("specialname") val specialName: String = "",
    @SerialName("imgurl") val imgUrl: String = "",
    val intro: String = "",
    @SerialName("playcount") val playCount: Int = 0,
    @SerialName("songcount") val songCount: Int = 0,
    @SerialName("collectcount") val collectCount: Int = 0,
    @SerialName("nickname") val nickName: String = "",
    @SerialName("publishtime") val publishTime: String = "",
)

@Serializable
private data class KugouUserPlist(val list: KugouUserPlistList = KugouUserPlistList())

@Serializable
private data class KugouUserPlistList(val info: List<KugouUserPlistItem> = emptyList())

@Serializable
private data class KugouUserPlistItem(
    @SerialName("specialid") val specialId: Int = 0,
    @SerialName("specialname") val specialName: String = "",
    @SerialName("imgurl") val imgUrl: String = "",
    val intro: String = "",
    @SerialName("playcount") val playCount: Int = 0,
    @SerialName("songcount") val songCount: Int = 0,
    @SerialName("collectcount") val collectCount: Int = 0,
    @SerialName("nickname") val nickName: String = "",
    @SerialName("publishtime") val publishTime: String = "",
)

@Serializable
private data class KugouTopListResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val data: KugouTopListData = KugouTopListData(),
)

@Serializable
private data class KugouTopListData(
    val total: Int = 0,
    val info: List<KugouTopListItem> = emptyList(),
)

@Serializable
private data class KugouTopListItem(
    @SerialName("rankid") val rankId: Int = 0,
    @SerialName("rankname") val rankName: String = "",
    @SerialName("ranktype") val rankType: Int = 0,
    val classify: Int = 0,
    @SerialName("update_frequency") val frequency: String = "",
    @SerialName("update_frequency_type") val frequencyType: Int = 0,
    @SerialName("play_times") val playTimes: Long = 0,
    val intro: String = "",
    @SerialName("imgurl") val imgUrl: String = "",
    @SerialName("img_9") val img9: String = "",
    @SerialName("img_cover") val imgCover: String = "",
    @SerialName("album_img_9") val albumImg9: String = "",
    @SerialName("rank_id_publish_date") val publishDate: String = "",
    val issue: JsonElement? = null,
    @SerialName("rank_cid") val rankCid: Int = 0,
    val extra: KugouTopListExtra = KugouTopListExtra(),
)

@Serializable
private data class KugouTopListExtra(val resp: KugouTopListResp = KugouTopListResp())

@Serializable
private data class KugouTopListResp(@SerialName("all_total") val allTotal: Long = 0)

@Serializable
private data class KugouTopListSongsResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val data: KugouTopListSongsData = KugouTopListSongsData(),
)

@Serializable
private data class KugouTopListSongsData(
    val total: Int = 0,
    val info: List<KugouTopListSong> = emptyList(),
)

@Serializable
private data class KugouTopListSong(
    val hash: String = "",
    @SerialName("hash_high") val hashHigh: String = "",
    @SerialName("hash_super") val hashSuper: String = "",
    @SerialName("320hash") val hqHash: String = "",
    @SerialName("sqhash") val sqHash: String = "",
    @SerialName("filesize") val fileSize: Long = 0,
    @SerialName("320filesize") val hqFileSize: Long = 0,
    @SerialName("sqfilesize") val sqFileSize: Long = 0,
    val duration: Int = 0,
    val bitrate: Int = 0,
    @SerialName("songname") val songName: String = "",
    val filename: String = "",
    val remark: String = "",
    @SerialName("album_id") val albumId: String = "",
    @SerialName("audio_id") val audioId: Long = 0,
    @SerialName("album_audio_id") val albumAudioId: Long = 0,
    @SerialName("mvhash") val mvHash: String = "",
    val privilege: Int = 0,
    @SerialName("album_sizable_cover") val cover: String = "",
    val authors: List<KugouAuthor> = emptyList(),
    @SerialName("trans_param") val transParam: KugouTransParam = KugouTransParam(),
)

@Serializable
private data class KugouAuthor(@SerialName("author_name") val authorName: String = "")

@Serializable
private data class KugouSongInfoResponse(
    val url: String = "",
    val bitRate: Int = 0,
    val extName: String = "",
    @SerialName("album_img") val albumImg: String = "",
    val songName: String = "",
    @SerialName("author_name") val authorName: String = "",
    val timeLength: Int = 0,
    val fileSize: Long = 0,
    val errcode: Int = 0,
)

@Serializable
private data class KugouTrackerResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val url: JsonElement? = null,
    @SerialName("backup_url") val backupUrl: JsonElement? = null,
    val bitRate: Int = 0,
    val extName: String = "",
    @SerialName("album_img") val albumImg: String = "",
    val songName: String = "",
    @SerialName("author_name") val authorName: String = "",
    val fileSize: Long = 0,
    val timeLength: Int = 0,
)

@Serializable
private data class KugouSonginfoV2Step1Response(
    val data: KugouSonginfoV2Step1 = KugouSonginfoV2Step1(),
    val status: Int = 0,
)

@Serializable
private data class KugouSonginfoV2Step1(
    val hash: String = "",
    @SerialName("song_name") val songName: String = "",
    @SerialName("author_name") val authorName: String = "",
    val img: String = "",
    @SerialName("album_name") val albumName: String = "",
    @SerialName("encode_album_audio_id") val encodeAlbumAudioId: String = "",
    @SerialName("album_audio_id") val albumAudioId: JsonElement? = null,
)

@Serializable
private data class KugouSonginfoV2Step2Response(
    val data: KugouSonginfoV2Step2 = KugouSonginfoV2Step2(),
    val status: Int = 0,
)

@Serializable
private data class KugouSonginfoV2Step2(
    @SerialName("play_url") val playUrl: String = "",
    @SerialName("play_backup_url") val playBackupUrl: String = "",
    @SerialName("filesize") val fileSize: Long = 0,
    val bitrate: Int = 0,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("song_name") val songName: String = "",
    @SerialName("author_name") val authorName: String = "",
    val img: String = "",
    @SerialName("timelength") val timeLength: Int = 0,
    @SerialName("extname") val extName: String = "",
)

@Serializable
private data class KugouVipInfoResponse(
    val errno: Int = 0,
    @SerialName("error_code") val errorCode: Int = 0,
    val role: Int = 0,
    @SerialName("user_type") val userType: Int = 0,
    val vipRemains: Int = 0,
    @SerialName("isExpiredMember") val isExpiredMember: Int = 0,
)

@Serializable
private data class KugouQrCreateResponse(
    val status: Int = 0,
    val data: KugouQrCreateData = KugouQrCreateData(),
    @SerialName("error_code") val errorCode: Int = 0,
    val error: String = "",
)

@Serializable
private data class KugouQrCreateData(@SerialName("qrcode") val qrCode: String = "")

@Serializable
private data class KugouQrCheckResponse(
    val status: Int = 0,
    val data: KugouQrCheckData = KugouQrCheckData(),
    @SerialName("error_code") val errorCode: Int = 0,
    val error: String = "",
)

@Serializable
private data class KugouQrCheckData(
    val status: Int = 0,
    val token: String = "",
    @SerialName("userid") val userId: JsonElement? = null,
)

@Serializable
private data class KugouRegisterResponse(
    val status: Int = 0,
    val data: KugouRegisterData = KugouRegisterData(),
    @SerialName("error_code") val errorCode: Int = 0,
    val error: String = "",
)

@Serializable
private data class KugouRegisterData(val dfid: String = "")

@Serializable
private data class KugouLyricSearchResponse(
    val status: Int = 0,
    val candidates: List<KugouLyricCandidate> = emptyList(),
)

@Serializable
private data class KugouLyricCandidate(
    val id: JsonElement? = null,
    val accesskey: String = "",
    val song: String = "",
)

@Serializable
private data class KugouLyricDownloadResponse(
    val status: Int = 0,
    val content: String = "",
    val fmt: String = "",
    @SerialName("contenttype") val contentType: Int = 0,
)

@Serializable
private data class KugouKrcLanguage(
    val content: List<KugouKrcLanguageItem> = emptyList(),
)

@Serializable
private data class KugouKrcLanguageItem(
    val type: Int = 0,
    val lyricContent: List<List<String>> = emptyList(),
)
