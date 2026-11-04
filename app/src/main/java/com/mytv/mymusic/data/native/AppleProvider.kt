package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

private val APPLE_INFO = ProviderInfo(
    id = "apple",
    name = "Apple Music",
    search = true,
    download = true,
    lyrics = true,
    parseSong = true,
    searchPlaylist = true,
    recommendedPlaylists = false,
    playlistSongs = true,
    parsePlaylist = true,
    searchAlbum = true,
    albumSongs = true,
    parseAlbum = true,
    userPlaylists = false,
    qrLogin = false,
    playlistCategories = true,
    topLists = true,
    topListSongs = true,
    hotSongs = true,
)

class AppleProvider : BaseNativeProvider(APPLE_INFO) {
    private val json = NativeHttp.json

    @Volatile
    private var cachedToken: String = ""

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val body = ampGet(
            cookie,
            "/v1/catalog/${storefront(cookie)}/search",
            mapOf("term" to keyword, "types" to "songs", "limit" to "30"),
        )
        return json.decodeFromString<AppleSearchResponse>(body).results.songs.data.map(::songFromCatalog)
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val songId = extractAppleId(link, "song") ?: throw IllegalArgumentException("invalid apple music song link: $link")
        val body = ampGet(
            cookie,
            "/v1/catalog/${storefront(cookie)}/songs/$songId",
            mapOf("extend" to "extendedAssetUrls", "include" to "lyrics,albums"),
        )
        val resource = json.decodeFromString<AppleResourceResponse>(body).data.firstOrNull()
            ?: throw IllegalStateException("apple song not found: $songId")
        return songFromCatalog(resource)
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.url.isNotBlank()) return song.url
        val parsed = parseSong(cookie, song.id)
        if (parsed.url.isBlank()) {
            throw IllegalStateException("apple music: no preview URL available (full download requires gamdl)")
        }
        return parsed.url
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.id.isBlank()) throw IllegalArgumentException("song id is empty")
        val body = ampGet(
            cookie,
            "/v1/catalog/${storefront(cookie)}/songs/${song.id}",
            mapOf("include" to "lyrics", "extend" to "extendedAssetUrls"),
        )
        val resource = json.decodeFromString<AppleResourceResponse>(body).data.firstOrNull() ?: return ""
        val ttml = resource.relationships.lyrics.data.firstOrNull()?.attributes?.ttml.orEmpty()
        return convertTtmlToLrc(ttml)
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val body = ampGet(
            cookie,
            "/v1/catalog/${storefront(cookie)}/search",
            mapOf("term" to keyword, "types" to "playlists", "limit" to "20"),
        )
        return json.decodeFromString<AppleSearchResponse>(body).results.playlists.data.map(::playlistFromResource)
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> =
        fetchPlaylistDetail(cookie, id).second

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val playlistId = extractAppleId(link, "playlist")
            ?: throw IllegalArgumentException("invalid apple music playlist link: $link")
        val (playlist, songs) = fetchPlaylistDetail(cookie, playlistId)
        return CollectionResult(playlist, songs)
    }

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val body = ampGet(
            cookie,
            "/v1/catalog/${storefront(cookie)}/search",
            mapOf("term" to keyword, "types" to "albums", "limit" to "20"),
        )
        return json.decodeFromString<AppleSearchResponse>(body).results.albums.data.map(::albumFromResource)
    }

    override suspend fun albumSongs(cookie: String, id: String): List<Song> =
        parseAlbum(cookie, id).songs

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult {
        val albumId = extractAppleId(link, "album") ?: throw IllegalArgumentException("invalid apple music album link: $link")
        val body = ampGet(
            cookie,
            "/v1/catalog/${storefront(cookie)}/albums/$albumId",
            mapOf("extend" to "extendedAssetUrls"),
        )
        val resource = json.decodeFromString<AppleResourceResponse>(body).data.firstOrNull()
            ?: throw IllegalStateException("apple album not found: $albumId")
        val playlist = albumFromResource(resource)
        val songs = resource.relationships.tracks.data.map(::songFromCatalog)
        return CollectionResult(playlist, songs)
    }

    override suspend fun categories(cookie: String): List<PlaylistCategory> =
        APPLE_CATEGORIES.map { PlaylistCategory(id = it.first, name = it.second, source = "apple") }

    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        val safeLimit = limit.coerceAtLeast(1)
        var offset = (page.coerceAtLeast(1) - 1) * safeLimit
        val result = mutableListOf<Playlist>()
        var guard = 0
        while (result.size < safeLimit && guard < 20) {
            val batch = (safeLimit - result.size).coerceAtMost(25)
            val body = ampGet(
                cookie,
                "/v1/catalog/cn/apple-curators/$id/playlists",
                mapOf(
                    "limit" to batch.toString(),
                    "offset" to offset.toString(),
                    "l" to "zh-Hans-CN",
                ),
            )
            val response = json.decodeFromString<AppleResourceListResponse>(body)
            result += response.data.map(::playlistFromResource)
            if (response.data.size < batch || response.next.isBlank()) break
            offset += response.data.size
            guard += 1
        }
        return result.take(safeLimit)
    }

    override suspend fun topLists(cookie: String): List<TopList> =
        APPLE_STOREFRONTS.map { (code, name) ->
            val id = "most-played:songs:$code"
            TopList(
                id = id,
                name = "$name Top 100",
                description = "Apple Music · $name 热门歌曲 Top 100",
                updateFrequency = "每日更新",
                trackCount = 100,
                source = "apple",
                link = "https://music.apple.com/$code/new/top-charts",
                extra = mapOf(
                    "storefront" to JsonPrimitive(code),
                    "chart" to JsonPrimitive("most-played"),
                    "media" to JsonPrimitive("songs"),
                    "toplist_type" to JsonPrimitive("chart"),
                ),
            )
        }

    override suspend fun topListSongs(cookie: String, id: String): List<Song> {
        val ref = parseChartId(id)
        return fetchChartSongs(ref, 100)
    }

    override suspend fun hotSongs(cookie: String, limit: Int): List<Song> {
        val ref = AppleChartRef("most-played", "songs", storefront(cookie))
        val safeLimit = when {
            limit <= 0 -> 100
            limit > 100 -> 100
            else -> limit
        }
        return fetchChartSongs(ref, safeLimit)
    }

    private suspend fun fetchPlaylistDetail(cookie: String, playlistId: String): Pair<Playlist, List<Song>> {
        val body = ampGet(
            cookie,
            "/v1/catalog/${storefront(cookie)}/playlists/$playlistId",
            mapOf("limit[tracks]" to "300", "extend" to "extendedAssetUrls"),
        )
        val resource = json.decodeFromString<AppleResourceResponse>(body).data.firstOrNull()
            ?: throw IllegalStateException("apple playlist not found: $playlistId")
        val tracks = fetchAllTrackResources(cookie, resource.relationships.tracks)
        val playlist = playlistFromResource(resource)
        val songs = tracks.map(::songFromCatalog)
        return playlist.copy(trackCount = if (playlist.trackCount == 0 || songs.size > playlist.trackCount) songs.size else playlist.trackCount) to songs
    }

    private suspend fun fetchAllTrackResources(
        cookie: String,
        relationship: AppleTrackRelationship,
    ): List<AppleResource> {
        val all = relationship.data.toMutableList()
        var next = relationship.next.trim()
        var guard = 0
        while (next.isNotBlank() && guard < 30) {
            val body = ampGetRaw(cookie, next)
            val page = json.decodeFromString<AppleResourceListResponse>(body)
            all += page.data
            next = page.next.trim()
            guard += 1
        }
        return all
    }

    private suspend fun fetchChartSongs(ref: AppleChartRef, limit: Int): List<Song> {
        val feed = fetchChartFeed(ref)
        val items = feed.feed.results.take(limit)
        if (items.isEmpty()) throw IllegalStateException("apple toplist ${ref.id} is empty")
        val details = fetchLookupDetails(ref.storefront, items)
        return items.mapIndexed { index, item ->
            val detail = details[item.id.trim()]
            chartSong(item, detail, ref).copy(
                extra = chartSong(item, detail, ref).extra + mapOf(
                    "rank" to (index + 1).toString(),
                    "toplist_id" to ref.id,
                )
            )
        }
    }

    private suspend fun fetchChartFeed(ref: AppleChartRef): AppleChartResponse {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            for (host in CHART_HOSTS) {
                val url = "${host.trimEnd('/')}/${ref.storefront}/music/${ref.feed}/100/${ref.media}.json"
                val result = runCatching {
                    val body = NativeHttp.get(
                        url,
                        headers = mapOf("Accept" to "application/json", "Referer" to "$APPLE_HOME/"),
                        userAgent = CHART_UA,
                    )
                    val response = json.decodeFromString<AppleChartResponse>(body)
                    if (response.feed.results.isEmpty()) error("apple chart feed is empty")
                    response
                }
                if (result.isSuccess) return result.getOrThrow()
                lastError = result.exceptionOrNull()
            }
            if (attempt < 2) Thread.sleep((attempt + 1) * 400L)
        }
        throw IllegalStateException("apple toplist ${ref.id} request failed: ${lastError?.message.orEmpty()}", lastError)
    }

    private suspend fun fetchLookupDetails(
        storefront: String,
        items: List<AppleChartItem>,
    ): Map<String, AppleLookupItem> {
        val ids = items.map { it.id.trim() }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, AppleLookupItem>()
        ids.chunked(100).forEach { chunk ->
            val query = chunk.joinToString(",")
            val url = "$LOOKUP_API?" + encodeParams(
                mapOf(
                    "id" to query,
                    "country" to storefront,
                    "entity" to "song",
                    "limit" to "200",
                )
            )
            runCatching {
                val body = NativeHttp.get(
                    url,
                    headers = mapOf("Accept" to "application/json", "Referer" to "$APPLE_HOME/"),
                    userAgent = CHART_UA,
                )
                json.decodeFromString<AppleLookupResponse>(body).results.forEach { item ->
                    if (item.trackId > 0) result[item.trackId.toString()] = item
                }
            }
        }
        return result
    }

    private fun songFromCatalog(resource: AppleResource): Song {
        val attr = resource.attributes
        val preview = attr.previews.firstOrNull()?.url.orEmpty()
        return Song(
            id = resource.id,
            name = attr.name,
            artist = attr.artistName,
            album = attr.albumName,
            duration = attr.durationInMillis / 1000,
            source = "apple",
            cover = artworkUrl(attr.artwork.url, 600),
            url = preview,
            ext = "m4a",
            link = attr.url,
            extra = buildMap {
                if (attr.isrc.isNotBlank()) put("isrc", attr.isrc)
            },
            isVip = false,
        )
    }

    private fun albumFromResource(resource: AppleResource): Playlist {
        val attr = resource.attributes
        return Playlist(
            id = resource.id,
            name = attr.name,
            cover = artworkUrl(attr.artwork.url, 300),
            trackCount = attr.trackCount,
            creator = attr.artistName,
            description = attr.editorialNotes.short.ifBlank { attr.editorialNotes.standard },
            source = "apple",
            link = attr.url,
            extra = buildMap {
                if (attr.releaseDate.isNotBlank()) put("release_date", attr.releaseDate)
                if (attr.recordLabel.isNotBlank()) put("record_label", attr.recordLabel)
            },
        )
    }

    private fun playlistFromResource(resource: AppleResource): Playlist {
        val attr = resource.attributes
        val description = attr.description.short
            .ifBlank { attr.description.standard }
            .ifBlank { attr.editorialNotes.short }
            .ifBlank { attr.editorialNotes.standard }
        return Playlist(
            id = resource.id,
            name = attr.name,
            cover = artworkUrl(attr.artwork.url, 300),
            trackCount = attr.trackCount,
            creator = attr.curatorName,
            description = description,
            source = "apple",
            link = attr.url,
        )
    }

    private fun chartSong(item: AppleChartItem, detail: AppleLookupItem?, ref: AppleChartRef): Song {
        val cover = artworkUpgrade(
            detail?.artworkUrl100?.takeIf { it.isNotBlank() } ?: item.artworkUrl100,
            600,
        )
        val extra = buildMap {
            put("storefront", ref.storefront)
            put("chart", ref.feed)
            if (item.releaseDate.isNotBlank()) put("release_date", item.releaseDate)
            if (!detail?.primaryGenreName.isNullOrBlank()) put("genre", detail.primaryGenreName)
        }
        return Song(
            id = item.id.trim(),
            name = item.name.ifBlank { detail?.trackName.orEmpty() },
            artist = item.artistName.ifBlank { detail?.artistName.orEmpty() },
            album = detail?.collectionName.orEmpty(),
            duration = (detail?.trackTimeMillis ?: 0) / 1000,
            source = "apple",
            cover = cover,
            url = detail?.previewUrl.orEmpty(),
            ext = "m4a",
            link = stripLookupParam(detail?.trackViewUrl.orEmpty()).ifBlank { item.url },
            extra = extra,
            isVip = false,
        )
    }

    private suspend fun ampGet(cookie: String, uri: String, params: Map<String, String>): String {
        val query = if (params.isEmpty()) "" else "?" + encodeParams(params)
        return ampGetRaw(cookie, if (uri.startsWith("/")) "$AMP_API$uri$query" else "$uri$query")
    }

    private suspend fun ampGetRaw(cookie: String, uri: String): String {
        val full = if (uri.startsWith("/")) "$AMP_API$uri" else uri
        val cookieHeader = mediaUserToken(cookie).takeIf { it.isNotBlank() }?.let { "media-user-token=$it" }.orEmpty()
        val userToken = parseCookieParts(cookie)["token"]?.takeIf { it.isNotBlank() }
        val token = userToken ?: ensureToken()
        return runCatching { ampRequest(full, token, cookieHeader) }
            .recoverCatching { error ->
                // developer token 过期或被 Apple 换掉时，清掉缓存重新抓一个再试一次，避免整个源失效
                if (userToken != null || !error.isUnauthorized()) throw error
                ampRequest(full, ensureToken(forceRefresh = true), cookieHeader)
            }
            .getOrThrow()
    }

    private suspend fun ampRequest(url: String, token: String, cookieHeader: String): String =
        NativeHttp.get(
            url,
            headers = mapOf("Authorization" to "Bearer $token", "Origin" to APPLE_HOME),
            cookie = cookieHeader,
            userAgent = NativeHttp.DEFAULT_USER_AGENT,
        )

    /**
     * Apple Music 的 developer token 由 music.apple.com 的前端包分发，会随前端发版轮换：
     * 入口包名从 `index-legacy~<hash>.js` 换成了 `index~<hash>.js`，token 前缀也从 `eyJh...`
     * 变成 `eyJ0eXAi...`，而且同一个包里会带多个 JWT，只有一个对 amp-api 的 catalog 接口有效。
     * 所以这里不写死包名和前缀：把首页引用的前端包里所有 JWT 取出来逐个试，缓存第一个能用的。
     */
    private suspend fun ensureToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh) cachedToken.takeIf { it.isNotBlank() }?.let { return it }
        val home = NativeHttp.get(APPLE_HOME, userAgent = APPLE_WEB_UA)
        val bundles = appleBundlePaths(home)
        if (bundles.isEmpty()) throw IllegalStateException("apple: index.js URI not found")
        var lastError: String = "apple: token not found in ${bundles.joinToString()}"
        for (bundle in bundles) {
            val js = NativeHttp.get("$APPLE_HOME/$bundle", userAgent = APPLE_WEB_UA)
            val tokens = appleTokens(js)
            if (tokens.isEmpty()) continue
            // 只有一个候选时不必试探，直接用；真出了问题由真实请求把错误抛出来
            val chosen = if (tokens.size == 1) tokens.first() else tokens.firstOrNull { tokenWorks(it) }
            chosen?.let {
                cachedToken = it
                return it
            }
            lastError = "apple: no usable token in $bundle (${tokens.size} candidates)"
        }
        throw IllegalStateException(lastError)
    }

    private suspend fun tokenWorks(token: String): Boolean = runCatching {
        NativeHttp.get(
            "$AMP_API/v1/catalog/us/search?term=apple&types=songs&limit=1",
            headers = mapOf("Authorization" to "Bearer $token", "Origin" to APPLE_HOME),
            userAgent = NativeHttp.DEFAULT_USER_AGENT,
        )
        true
    }.getOrDefault(false)

    /** OkHttp 的异常信息形如 `HTTP 401 Unauthorized: ...`，用来识别 token 失效。 */
    private fun Throwable.isUnauthorized(): Boolean = APPLE_UNAUTHORIZED.containsMatchIn(message.orEmpty())

    private fun storefront(cookie: String): String {
        val value = parseCookieParts(cookie)["storefront"].orEmpty().trim().lowercase(Locale.ROOT)
        return value.takeIf { it.matches(Regex("^[a-z]{2}$")) } ?: "us"
    }

    private fun mediaUserToken(cookie: String): String {
        val parts = parseCookieParts(cookie)
        val value = parts["media-user-token"].orEmpty().trim()
        if (value.isNotBlank()) return value
        return cookie.trim().takeIf { it.isNotBlank() && !it.contains('=') && !it.contains(';') }.orEmpty()
    }

    private fun parseCookieParts(cookie: String): Map<String, String> = buildMap {
        cookie.split(';').forEach { part ->
            val index = part.indexOf('=')
            if (index > 0) put(part.substring(0, index).trim(), part.substring(index + 1).trim())
        }
    }

    private fun extractAppleId(link: String, mediaType: String): String? {
        val value = link.trim()
        if (value.isBlank()) return null
        if (isRawAppleId(value, mediaType)) return value
        runCatching {
            val uri = URI(value)
            if (mediaType == "song") {
                val songId = queryParam(uri, "i")
                if (songId.isNotBlank()) return songId
            }
            val fromPath = extractAppleIdFromPath(uri.path.orEmpty(), mediaType)
            if (fromPath.isNotBlank()) return fromPath
        }
        val patterns = mutableListOf(
            Regex("""$mediaType/[^/]+/(\d+)"""),
            Regex("""${mediaType}s?/[^/]+/[^/]+/(\d+)"""),
            Regex("""/(\d+)$"""),
            Regex("""[?&]i=(\d+)"""),
        )
        if (mediaType == "playlist") {
            patterns += Regex("""playlists?/[^/?#]+/([a-zA-Z0-9._-]+)""")
            patterns += Regex("""playlists?/([a-zA-Z0-9._-]+)(?:[?#]|$)""")
        }
        patterns.forEach { pattern ->
            pattern.find(value)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun isRawAppleId(value: String, mediaType: String): Boolean {
        if (value.contains('/') || value.contains('?') || value.contains('#') || value.contains("://")) return false
        return if (mediaType == "playlist") {
            Regex("^[a-zA-Z0-9._-]+$").matches(value)
        } else {
            Regex("^\\d+$").matches(value)
        }
    }

    private fun extractAppleIdFromPath(path: String, mediaType: String): String {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        segments.forEachIndexed { index, segment ->
            if (segment != mediaType && segment != "${mediaType}s") return@forEachIndexed
            if (index + 2 < segments.size) return segments[index + 2]
            if (index + 1 < segments.size) return segments[index + 1]
        }
        return ""
    }

    private fun queryParam(uri: URI, name: String): String {
        val query = uri.rawQuery ?: return ""
        return query.split('&').firstNotNullOfOrNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@firstNotNullOfOrNull null
            if (part.substring(0, index) != name) return@firstNotNullOfOrNull null
            URLDecoder.decode(part.substring(index + 1), "UTF-8")
        }.orEmpty()
    }

    private fun parseChartId(value: String): AppleChartRef {
        val trimmed = value.trim().lowercase(Locale.ROOT)
        if (trimmed.isBlank()) throw IllegalArgumentException("apple toplist id is empty")
        val parts = trimmed.split(':')
        val ref = when (parts.size) {
            1 -> AppleChartRef("most-played", "songs", parts[0])
            2 -> AppleChartRef("most-played", parts[0], parts[1])
            3 -> AppleChartRef(parts[0], parts[1], parts[2])
            else -> throw IllegalArgumentException("invalid apple toplist id: $value")
        }
        if (ref.feed != "most-played" || ref.media != "songs" || !ref.storefront.matches(Regex("^[a-z]{2}$"))) {
            throw IllegalArgumentException("invalid apple toplist id: $value")
        }
        return ref
    }

    private fun artworkUrl(raw: String, size: Int): String {
        if (raw.isBlank()) return ""
        return raw.replace("{w}", size.toString()).replace("{h}", size.toString())
    }

    private fun artworkUpgrade(raw: String, size: Int): String {
        if (raw.isBlank()) return ""
        val value = if (size <= 0) 600 else size
        return raw.replace(Regex("""/(\d+)x(\d+)bb\.(jpg|png|webp)"""), "/${value}x${value}bb.$3")
    }

    private fun stripLookupParam(link: String): String {
        if (link.isBlank()) return ""
        return link.replace(Regex("""[?&]uo=\d+"""), "")
            .replace("?&", "?")
            .trimEnd('?', '&')
    }

    private fun encodeParams(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    private data class AppleChartRef(val feed: String, val media: String, val storefront: String) {
        val id: String get() = "$feed:$media:$storefront"
    }

    private companion object {
        const val APPLE_HOME = "https://music.apple.com"
        const val AMP_API = "https://amp-api.music.apple.com"
        const val LOOKUP_API = "https://itunes.apple.com/lookup"
        const val APPLE_WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        val APPLE_UNAUTHORIZED = Regex("""HTTP 40[13]\b""")
        const val CHART_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val CHART_HOSTS = listOf(
            "https://rss.marketingtools.apple.com/api/v2",
            "https://rss.applemarketingtools.com/api/v2",
        )
        val APPLE_STOREFRONTS = listOf(
            "cn" to "中国内地",
            "hk" to "中国香港",
            "tw" to "中国台湾",
            "us" to "美国",
            "jp" to "日本",
            "kr" to "韩国",
            "gb" to "英国",
            "sg" to "新加坡",
        )
        val APPLE_CATEGORIES = listOf(
            "1526756058" to "热门",
            "1479949880" to "C-Pop",
            "1019400042" to "国语流行",
            "1019398918" to "粤语流行",
            "1019399540" to "国际流行",
            "1019399551" to "K-Pop",
            "1019399547" to "J-Pop",
            "989061415" to "嘻哈/说唱",
            "1019400044" to "R&B",
            "1019400046" to "摇滚",
            "1019397973" to "另类音乐",
            "976439535" to "舞曲",
            "976439536" to "电子",
            "976439541" to "独立音乐",
            "1019399549" to "爵士乐",
            "1019398924" to "古典音乐",
            "976439528" to "蓝调",
            "976439534" to "乡村音乐",
            "1531542847" to "拉丁音乐",
            "988656348" to "非洲音乐",
            "976439543" to "金属乐",
            "976439550" to "朋克乐",
            "1019400049" to "不插电",
            "1231181168" to "影视原声",
            "1441811365" to "DJ 混音精选",
            "1532467784" to "瞩目之星",
            "1554938339" to "年代之声",
            "1564180390" to "空间音频",
            "989010186" to "亲子",
        )
    }
}

/** music.apple.com 首页里可能承载 developer token 的 JS 包路径，按优先级去重。 */
internal val APPLE_BUNDLE_REGEXES = listOf(
    // 入口包：现行 index~<hash>.js，历史命名 index-legacy~<hash>.js
    Regex("""/(assets/index[^/"]*\.js)"""),
    // 兜底：assets 下任意 js
    Regex("""/(assets/[A-Za-z0-9._~-]+\.js)"""),
)

/** JWT：header.payload.signature 三段；Apple 的 developer token 也长这样。 */
internal val APPLE_JWT_REGEX = Regex("""eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}""")

/** 首页引用的前端包路径，入口包优先；最多返回前两个，避免一次冷启动拉太多包。 */
internal fun appleBundlePaths(html: String): List<String> =
    APPLE_BUNDLE_REGEXES.flatMap { regex -> regex.findAll(html).map { it.groupValues[1] } }
        .filter { it.isNotBlank() }
        .distinct()
        .take(2)

/** 前端包里出现的所有 JWT，保持出现顺序去重；调用方负责挑出对 amp-api 可用的那个。 */
internal fun appleTokens(js: String): List<String> =
    APPLE_JWT_REGEX.findAll(js).map { it.value }.distinct().toList()

@Serializable
private data class AppleSearchResponse(val results: AppleSearchResults = AppleSearchResults())

@Serializable
private data class AppleSearchResults(
    val songs: AppleResourceList = AppleResourceList(),
    val albums: AppleResourceList = AppleResourceList(),
    val playlists: AppleResourceList = AppleResourceList(),
)

@Serializable
private data class AppleResourceResponse(val data: List<AppleResource> = emptyList())

@Serializable
private data class AppleResourceListResponse(
    val data: List<AppleResource> = emptyList(),
    val href: String = "",
    val next: String = "",
)

@Serializable
private data class AppleResourceList(val data: List<AppleResource> = emptyList())

@Serializable
private data class AppleResource(
    val id: String = "",
    val type: String = "",
    val attributes: AppleAttributes = AppleAttributes(),
    val relationships: AppleRelationships = AppleRelationships(),
)

@Serializable
private data class AppleAttributes(
    val name: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val durationInMillis: Int = 0,
    val artwork: AppleArtwork = AppleArtwork(),
    val url: String = "",
    val previews: List<ApplePreview> = emptyList(),
    val isrc: String = "",
    val trackCount: Int = 0,
    val curatorName: String = "",
    val description: AppleText = AppleText(),
    val editorialNotes: AppleText = AppleText(),
    val lastModifiedDate: String = "",
    val releaseDate: String = "",
    val recordLabel: String = "",
)

@Serializable
private data class AppleArtwork(val url: String = "", val width: Int = 0, val height: Int = 0)

@Serializable
private data class ApplePreview(val url: String = "")

@Serializable
private data class AppleText(val short: String = "", val standard: String = "")

@Serializable
private data class AppleRelationships(
    val tracks: AppleTrackRelationship = AppleTrackRelationship(),
    val lyrics: AppleLyricsRelationship = AppleLyricsRelationship(),
)

@Serializable
private data class AppleTrackRelationship(
    val data: List<AppleResource> = emptyList(),
    val href: String = "",
    val next: String = "",
)

@Serializable
private data class AppleLyricsRelationship(val data: List<AppleLyricData> = emptyList())

// Apple Music TTML 歌词：行 `<p begin="..">..</p>`，逐字 `<span begin="..">词</span>`；
// 时间为 HH:MM:SS.mmm / MM:SS.mmm / 秒.mmm（可带 s 后缀）。
private val TTML_LINE = Regex("""<p\b[^>]*\bbegin="([^"]+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
private val TTML_WORD = Regex("""<span\b([^>]*)>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
private val TTML_BEGIN = Regex("""\bbegin="([^"]*)"""")
private val TTML_END = Regex("""\bend="([^"]*)"""")
private val TTML_ENTITY = Regex("""&(amp|lt|gt|quot|apos|#39|#x27);""")

/**
 * TTML 转增强 LRC：带 span 的行输出 `<mm:ss.SSS>` 逐字标签，无 span 的行退回整行时间戳。
 * 找不到任何歌词行（空串或非 TTML）时原样返回，由上层按纯文本展示。
 */
internal fun convertTtmlToLrc(ttml: String): String {
    if (ttml.isBlank()) return ttml
    val builder = StringBuilder()
    for (lineMatch in TTML_LINE.findAll(ttml)) {
        val lineStart = parseTtmlTime(lineMatch.groupValues[1])
        if (lineStart < 0) continue
        val content = lineMatch.groupValues[2]
        val wordMatches = TTML_WORD.findAll(content).toList()
        if (wordMatches.isEmpty()) {
            val text = decodeTtmlEntities(TTML_WORD.replace(content, "").replace(Regex("""<[^>]+>"""), "")).trim()
            if (text.isEmpty()) continue
            builder.append('[').append(formatTtmlTime(lineStart)).append(']').append(text).append('\n')
            continue
        }
        builder.append('[').append(formatTtmlTime(lineStart)).append(']')
        var words = 0
        for (wordMatch in wordMatches) {
            val attrs = wordMatch.groupValues[1]
            val wordText = decodeTtmlEntities(wordMatch.groupValues[2]).trim()
            if (wordText.isEmpty()) continue
            var wordStart = parseTtmlTime(TTML_BEGIN.find(attrs)?.groupValues?.getOrNull(1).orEmpty())
            if (wordStart < 0) wordStart = lineStart
            builder.append('<').append(formatTtmlTime(wordStart)).append('>').append(wordText)
            val wordEnd = parseTtmlTime(TTML_END.find(attrs)?.groupValues?.getOrNull(1).orEmpty())
            if (wordEnd > wordStart) builder.append("</").append(formatTtmlTime(wordEnd)).append('>')
            words++
        }
        if (words == 0) continue
        builder.append('\n')
    }
    if (builder.isEmpty()) return ttml
    return builder.toString().trimEnd('\n')
}

private fun parseTtmlTime(value: String): Long {
    val cleaned = value.trim().removeSuffix("s")
    if (cleaned.isEmpty()) return -1
    var seconds = 0.0
    var multiplier = 1.0
    for (part in cleaned.split(':').asReversed()) {
        val parsed = part.toDoubleOrNull() ?: return -1
        seconds += parsed * multiplier
        multiplier *= 60.0
    }
    return (seconds * 1000).toLong()
}

private fun formatTtmlTime(milliseconds: Long): String {
    val ms = milliseconds.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, (ms % 60_000) / 1_000, ms % 1_000)
}

/** 单遍解码 TTML 常见实体，避免 `&amp;lt;` 这类嵌套写法被二次还原。 */
private fun decodeTtmlEntities(text: String): String = TTML_ENTITY.replace(text) { match ->
    when (match.groupValues[1]) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        else -> "'"
    }
}

@Serializable
private data class AppleLyricData(val attributes: AppleLyricAttributes = AppleLyricAttributes())

@Serializable
private data class AppleLyricAttributes(val ttml: String = "")

@Serializable
private data class AppleChartResponse(val feed: AppleChartFeed = AppleChartFeed())

@Serializable
private data class AppleChartFeed(val results: List<AppleChartItem> = emptyList())

@Serializable
private data class AppleChartItem(
    val id: String = "",
    val name: String = "",
    val artistName: String = "",
    val releaseDate: String = "",
    val artworkUrl100: String = "",
    val url: String = "",
)

@Serializable
private data class AppleLookupResponse(val results: List<AppleLookupItem> = emptyList())

@Serializable
private data class AppleLookupItem(
    val trackId: Long = 0,
    val trackName: String = "",
    val artistName: String = "",
    val collectionId: Long = 0,
    val collectionName: String = "",
    val trackTimeMillis: Int = 0,
    val previewUrl: String = "",
    val artworkUrl100: String = "",
    val trackViewUrl: String = "",
    val primaryGenreName: String = "",
    val releaseDate: String = "",
)
