package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import com.mytv.mymusic.playback.AppleWidevineDrmCallback
import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
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
            mapOf("term" to keyword, "types" to "songs", "limit" to "30", "extend" to "extendedAssetUrls"),
        )
        val storefront = storefront(cookie)
        return json.decodeFromString<AppleSearchResponse>(body).results.songs.data
            .map { songFromCatalog(it, storefront) }
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
        return songFromCatalog(resource, storefront(cookie))
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.url.isNotBlank()) return song.url
        val parsed = parseSong(cookie, song.id)
        if (parsed.url.isBlank()) {
            throw IllegalStateException("apple music: no preview URL available")
        }
        return parsed.url
    }

    /**
     * 整曲走 [AppleWidevineDrmCallback] 授权的 HLS；登录态或授权拿不到就退回试听片段，
     * 并按 `preview = true` 交出去，让聚合解析器去试别的平台。
     *
     * 返回的 [PlayableStream.extra] 里带 DRM 三件套（scheme / pssh / adamId），
     * `SourceResolver` 会合进 `Song.extra`，最终由 `MusicViewModel.mediaItem()`
     * 拼成 `MediaItem.DrmConfiguration`。
     */
    override suspend fun playableStream(cookie: String, song: Song, workDir: File): PlayableStream {
        val master = song.extra[EXTRA_APPLE_HLS]?.takeIf { it.isNotBlank() }
            ?: appleEnhancedHls(cookie, song)
        if (!master.isNullOrBlank()) {
            val variant = appleHlsVariant(master)
            if (variant != null) {
                return PlayableStream(
                    url = variant.playlistUrl,
                    ext = "m4a",
                    bitrate = variant.bitrate,
                    extra = buildMap {
                        put(AppleWidevineDrmCallback.EXTRA_DRM_SCHEME, AppleWidevineDrmCallback.DRM_SCHEME_WIDEVINE)
                        put(AppleWidevineDrmCallback.EXTRA_DRM_PSSH, variant.pssh)
                        put(AppleWidevineDrmCallback.EXTRA_DRM_ADAM_ID, song.id)
                    },
                )
            }
        }
        // 整曲这条路不通：退回片段，并明确标出来
        val preview = song.url.ifBlank { parseSong(cookie, song.id).url }
        if (preview.isBlank()) throw IllegalStateException("apple music: 没有可播放的地址")
        return PlayableStream(url = preview, ext = "m4a", bitrate = 0, preview = true)
    }

    /** 从歌曲资源上取整曲主清单；拿不到（没登录 / 该曲没有 enhancedHls）返回空。 */
    private suspend fun appleEnhancedHls(cookie: String, song: Song): String? {
        val storefront = song.extra["storefront"]?.takeIf { it.matches(Regex("^[a-z]{2}$")) } ?: storefront(cookie)
        val body = runCatching {
            ampGet(
                cookie,
                "/v1/catalog/$storefront/songs/${song.id}",
                mapOf("extend" to "extendedAssetUrls"),
            )
        }.getOrNull() ?: return null
        val resource = runCatching { json.decodeFromString<AppleResourceResponse>(body).data.firstOrNull() }.getOrNull()
        return resource?.attributes?.extendedAssetUrls?.enhancedHls?.takeIf { it.isNotBlank() }
    }

    /** 主清单 + 档位媒体清单各一次请求，选出可用档位并取出 Widevine PSSH。 */
    private suspend fun appleHlsVariant(masterUrl: String): AppleHlsVariant? {
        val master = runCatching { NativeHttp.get(masterUrl, userAgent = APPLE_WEB_UA) }.getOrNull() ?: return null
        val base = masterUrl.substringBeforeLast('/')
        val baseUri = runCatching { java.net.URI(base) }.getOrNull() ?: return null
        for (variant in AppleHlsManifest.variants(master).sortedBy { AppleHlsManifest.AUDIO_GROUPS.indexOf(it.group) }) {
            val playlistUrl = runCatching { baseUri.resolve(variant.uri)?.toString() }.getOrNull() ?: continue
            val media = runCatching { NativeHttp.get(playlistUrl, userAgent = APPLE_WEB_UA) }.getOrNull() ?: continue
            val pssh = AppleHlsManifest.widevinePssh(media) ?: continue
            return AppleHlsVariant(playlistUrl = playlistUrl, pssh = pssh, bitrate = variant.bitrateKbps)
        }
        return null
    }

    private data class AppleHlsVariant(
        val playlistUrl: String,
        val pssh: String,
        val bitrate: Int,
    )

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.id.isBlank()) throw IllegalArgumentException("song id is empty")
        // 登录用户：歌词在 /songs/{id}/lyrics 子资源上，要带 media-user-token 才能拿到
        val native = runCatching { appleNativeLyrics(cookie, song) }.getOrDefault("")
        if (native.isNotBlank()) return native
        if (!AppleLyricsConfig.thirdPartyLyrics) return ""
        for (source in APPLE_LYRIC_SOURCES) {
            val ttml = runCatching { source.fetch(song.id) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val lrc = convertTtmlToLrc(ttml)
            if (lrc.isNotBlank()) return lrc
        }
        return ""
    }

    /**
     * Apple Music 账号信息。Apple 不向网页端暴露 Apple ID 的昵称和头像，所以头像一栏
     * 只能是空的；能拿到的是订阅状态和账号所在商店。
     *
     * `/v1/me/account?meta=subscription` 同时给出 `meta.subscription.active`（是否会员）
     * 和 `meta.subscription.storefront`（账号真实商店），后者正好用来纠正默认的 us——
     * 中国区账号按美区目录检索和取歌词都会不对。
     */
    override suspend fun accountInfo(cookie: String): AccountInfo? {
        if (mediaUserToken(cookie).isBlank()) return null
        val body = runCatching {
            ampGet(cookie, "/v1/me/account", mapOf("meta" to "subscription"), mediaTokenHeader = true)
        }.getOrNull() ?: return null
        val response = runCatching { json.decodeFromString<AppleAccountResponse>(body) }.getOrNull() ?: return null
        val subscription = response.meta.subscription
        // 商店是账号属性，缓存下来给 storefront() 用；读不到就不动
        subscription.storefront.takeIf { it.matches(Regex("^[a-z]{2}$")) }?.let { AppleAccount.storefront = it }
        val detail = when (subscription.active) {
            true -> "Apple Music 会员"
            false -> "已登录 · 无有效订阅"
            null -> "已登录"
        }
        return AccountInfo(nickname = "", avatar = "", detail = detail)
    }

    /**
     * Apple 自己的歌词接口。注意不是歌曲资源上的 `include=lyrics`——那个关系匿名和被登录
     * 都已经不再返回；真正还有歌词的是 `/songs/{id}/lyrics` 和 `/syllable-lyrics` 两个子资源，
     * 且必须同时带上 `media-user-token` 请求头和 Cookie，只发 Cookie 拿不到。
     *
     * 商店必须和歌曲所属的目录一致（adam ID 是分商店的），所以优先用搜索时记在 [Song.extra]
     * 里的 storefront，其次是账号真实商店（[AppleAccount]），再才是 Cookie 里声明的，
     * 最后才退回默认的 us。
     */
    private suspend fun appleNativeLyrics(cookie: String, song: Song): String {
        val storefront = song.extra["storefront"]?.takeIf { it.matches(Regex("^[a-z]{2}$")) }
            ?: storefront(cookie)
        for (endpoint in APPLE_LYRIC_ENDPOINTS) {
            val body = runCatching {
                ampGet(
                    cookie,
                    "/v1/catalog/$storefront/songs/${song.id}/$endpoint",
                    mapOf("extend" to "ttmlLocalizations"),
                    mediaTokenHeader = true,
                )
            }.getOrNull() ?: continue
            val resource = runCatching {
                json.decodeFromString<AppleResourceResponse>(body).data.firstOrNull()
            }.getOrNull() ?: continue
            val ttml = resource.attributes.ttml.ifBlank { resource.attributes.ttmlLocalizations }
            if (ttml.isNotBlank()) return convertTtmlToLrc(ttml)
        }
        return ""
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val body = ampGet(
            cookie,
            "/v1/catalog/${storefront(cookie)}/search",
            // 实测 limit=50 能正常返回（"周杰伦" 拿到 37 个），100 会报错，所以取 50
            mapOf("term" to keyword, "types" to "playlists", "limit" to "50"),
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
            mapOf("extend" to "extendedAssetUrls", "extend[songs]" to "extendedAssetUrls"),
        )
        val resource = json.decodeFromString<AppleResourceResponse>(body).data.firstOrNull()
            ?: throw IllegalStateException("apple album not found: $albumId")
        val playlist = albumFromResource(resource)
        val storefront = storefront(cookie)
        val songs = resource.relationships.tracks.data.map { songFromCatalog(it, storefront) }
        return CollectionResult(playlist, songs)
    }

    override suspend fun categories(cookie: String): List<PlaylistCategory> =
        APPLE_CATEGORIES.map { PlaylistCategory(id = it.first, name = it.second, source = "apple") }

    /**
     * 分类下的歌单。`/apple-curators/{id}/playlists` 只给策划自己挂的那几个（cn 的「热门」
     * 实测只有 8 个，`limit` 上限 25），而官网的分类页要丰富得多。差别在于官网走的是
     * grouping 树：一次 `ids[apple-curators]=` + `include=grouping,playlists` +
     * `include[editorial-elements]=children,contents,room` 会把整棵 editorial 树内联回来，
     * cn「热门」实测能走出 **244 个独立歌单**。
     *
     * `platform=web` 不能省——少了它 Apple 返回 200 但 `meta.failures.response` 是
     * `partial-failure`、树是空的，属于静默失败，必须当错误处理。
     */
    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        val storefront = storefront(cookie)
        val body = ampGet(
            cookie,
            "/v1/catalog/$storefront",
            mapOf(
                "ids[apple-curators]" to id,
                "include" to "grouping,playlists",
                "extend[apple-curators]" to "playlistCount",
                "include[editorial-elements]" to "children,contents,room",
                "platform" to "web",
                "l" to "zh-Hans-CN",
            ),
        )
        val response = json.decodeFromString<AppleGroupingResponse>(body)
        // 静默失败：HTTP 200 但没有树，此时退回老的扁平接口，至少能拿到策划自己那批
        val tree = response.data.firstOrNull()?.relationships?.grouping?.data
        if (tree.isNullOrEmpty()) {
            return curatorPlaylistsFlat(cookie, id, page, limit)
        }
        val collected = mutableListOf<Playlist>()
        val seen = mutableSetOf<String>()
        tree.forEach { collectPlaylists(it, collected, seen, 0) }
        // 树里的顺序是官网的栏目顺序，已经比扁平接口好；分页仍按调用方的 page/limit 切
        val safeLimit = limit.coerceAtLeast(1)
        val from = ((page.coerceAtLeast(1) - 1) * safeLimit).coerceAtMost(collected.size)
        return collected.drop(from).take(safeLimit)
    }

    /** grouping 失败时的退路：`/apple-curators/{id}/playlists`，limit 上限 25。 */
    private suspend fun curatorPlaylistsFlat(
        cookie: String,
        id: String,
        page: Int,
        limit: Int,
    ): List<Playlist> {
        val safeLimit = limit.coerceAtLeast(1)
        var offset = (page.coerceAtLeast(1) - 1) * safeLimit
        val result = mutableListOf<Playlist>()
        var guard = 0
        while (result.size < safeLimit && guard < 20) {
            val batch = (safeLimit - result.size).coerceAtMost(25)
            val body = ampGet(
                cookie,
                "/v1/catalog/cn/apple-curators/$id/playlists",
                mapOf("limit" to batch.toString(), "offset" to offset.toString(), "l" to "zh-Hans-CN"),
            )
            val response = json.decodeFromString<AppleResourceListResponse>(body)
            result += response.data.map(::playlistFromResource)
            if (response.data.size < batch || response.next.isBlank()) break
            offset += response.data.size
            guard += 1
        }
        return result.take(safeLimit)
    }

    /**
     * 递归走 grouping 树收集歌单。树里混着 albums / songs / stations 等节点，
     * 只取 `playlists`；深度设上限防止 Apple 哪天给出环形结构。
     */
    private fun collectPlaylists(
        node: AppleResource,
        out: MutableList<Playlist>,
        seen: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > 6 || node.id in seen) return
        seen += node.id
        if (node.type == "playlists") out += playlistFromResource(node)
        val relationships = node.relationships
        relationships.tabs.data.forEach { collectPlaylists(it, out, seen, depth + 1) }
        relationships.children.data.forEach { collectPlaylists(it, out, seen, depth + 1) }
        relationships.contents.data.forEach { collectPlaylists(it, out, seen, depth + 1) }
        relationships.playlists.data.forEach { collectPlaylists(it, out, seen, depth + 1) }
    }

    /**
     * Apple 的榜单没有自己的封面图，RSS 里的 `icon` 只是 Apple favicon。
     * 用榜单第一名歌曲的封面顶替：并发拉各商店的 feed，失败就留空（和其他平台一样有兜底）。
     */
    override suspend fun topLists(cookie: String): List<TopList> = coroutineScope {
        APPLE_STOREFRONTS.map { (code, name) ->
            async {
                val id = "most-played:songs:$code"
                val cover = runCatching { chartCover(code) }.getOrDefault("")
                TopList(
                    id = id,
                    name = "$name Top 100",
                    cover = cover,
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
        }.awaitAll()
    }

    /** 榜单第一名的封面；拿不到就返回空串。 */
    private suspend fun chartCover(storefront: String): String {
        val feed = fetchChartFeed(AppleChartRef("most-played", "songs", storefront))
        val artwork = feed.feed.results.firstOrNull()?.artworkUrl100.orEmpty()
        return artworkUpgrade(artwork, 600)
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
        val storefront = storefront(cookie)
        val resource = runCatching {
            val body = ampGet(
                cookie,
                "/v1/catalog/$storefront/playlists/$playlistId",
                mapOf("limit[tracks]" to APPLE_TRACK_PAGE.toString(), "extend" to "extendedAssetUrls"),
            )
            json.decodeFromString<AppleResourceResponse>(body).data.firstOrNull()
        }.getOrNull() ?: throw IllegalStateException("apple playlist not found: $playlistId")
        val tracks = fetchAllTrackResources(cookie, storefront, playlistId, resource.relationships.tracks)
        val playlist = playlistFromResource(resource)
        val songs = tracks.map { songFromCatalog(it, storefront) }
        return playlist.copy(trackCount = if (playlist.trackCount == 0 || songs.size > playlist.trackCount) songs.size else playlist.trackCount) to songs
    }

    /**
     * 歌单曲目分页。两个坑都实测过：
     *  - `limit[tracks]` 配合 `offset` 打歌单资源时，**offset 被静默忽略**——返回 200 且数据
     *    看着正常，其实永远是第一页；
     *  - 响应里的 `next` **会把 limit 丢掉**，跟着走会退化成服务端默认的 100。
     * 所以第一页用 `limit[tracks]=300` 拿满，之后一律走
     * `/playlists/{id}/tracks?limit=300&offset=N` 自己翻。`limit` 上限 300（301 直接被
     * 参数校验拒掉）；offset 越界返回 404，就地收尾。
     */
    private suspend fun fetchAllTrackResources(
        cookie: String,
        storefront: String,
        playlistId: String,
        first: AppleTrackRelationship,
    ): List<AppleResource> {
        val all = first.data.toMutableList()
        // 第一页就没满一页，说明没有了
        if (first.data.size < APPLE_TRACK_PAGE) return all
        var offset = first.data.size
        var guard = 0
        while (guard < 30) {
            val page = runCatching {
                val body = ampGet(
                    cookie,
                    "/v1/catalog/$storefront/playlists/$playlistId/tracks",
                    mapOf(
                        "limit" to APPLE_TRACK_PAGE.toString(),
                        "offset" to offset.toString(),
                        "extend" to "extendedAssetUrls",
                    ),
                )
                json.decodeFromString<AppleResourceListResponse>(body).data
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: break
            all += page
            offset += page.size
            guard += 1
            if (page.size < APPLE_TRACK_PAGE) break
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
            // 用 delay 而不是 Thread.sleep：topLists 会为每个商店并发拉榜单封面，
            // Thread.sleep 会把承载协程的线程全占住，互相拖死
            if (attempt < 2) delay((attempt + 1) * 400L)
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

    private fun songFromCatalog(resource: AppleResource, storefront: String = ""): Song {
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
                // adam ID 是分商店的，取歌词子资源时要用同一个商店，否则 404
                if (storefront.matches(Regex("^[a-z]{2}$"))) put("storefront", storefront)
                // 整曲主清单；请求时带 extend=extendedAssetUrls 才有。encrypted，见 playableStream
                val hls = attr.extendedAssetUrls.enhancedHls
                if (hls.isNotBlank()) put(EXTRA_APPLE_HLS, hls)
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

    private suspend fun ampGet(
        cookie: String,
        uri: String,
        params: Map<String, String>,
        mediaTokenHeader: Boolean = false,
    ): String {
        val query = if (params.isEmpty()) "" else "?" + encodeParams(params)
        return ampGetRaw(
            cookie,
            if (uri.startsWith("/")) "$AMP_API$uri$query" else "$uri$query",
            mediaTokenHeader,
        )
    }

    private suspend fun ampGetRaw(
        cookie: String,
        uri: String,
        mediaTokenHeader: Boolean = false,
    ): String {
        val full = if (uri.startsWith("/")) "$AMP_API$uri" else uri
        val mediaToken = mediaUserToken(cookie)
        val cookieHeader = mediaToken.takeIf { it.isNotBlank() }?.let { "media-user-token=$it" }.orEmpty()
        val userToken = parseCookieParts(cookie)["token"]?.takeIf { it.isNotBlank() }
        val token = userToken ?: ensureToken()
        // 歌词子资源要求 media-user-token 同时出现在请求头和 Cookie 里，只发 Cookie 拿不到
        val headers = buildMap {
            put("Authorization", "Bearer $token")
            put("Origin", APPLE_HOME)
            put("Referer", "$APPLE_HOME/")
            if (mediaTokenHeader && mediaToken.isNotBlank()) put("media-user-token", mediaToken)
        }
        return runCatching { ampRequest(full, token, cookieHeader, headers) }
            .recoverCatching { error ->
                // developer token 过期或被 Apple 换掉时，清掉缓存重新抓一个再试一次，避免整个源失效
                if (userToken != null || !error.isUnauthorized()) throw error
                ampRequest(full, ensureToken(forceRefresh = true), cookieHeader, headers)
            }
            .getOrThrow()
    }

    private suspend fun ampRequest(
        url: String,
        token: String,
        cookieHeader: String,
        headers: Map<String, String>,
    ): String = NativeHttp.get(
        url,
        headers = headers,
        cookie = cookieHeader,
        userAgent = NativeHttp.DEFAULT_USER_AGENT,
    )

    /**
     * Apple Music 的 developer token 由 music.apple.com 的前端包分发，会随前端发版轮换：
     * 入口包名从 `index-legacy~<hash>.js` 换成了 `index~<hash>.js`，token 前缀也从 `eyJh...`
     * 变成 `eyJ0eXAi...`，而且同一个包里会带多个 JWT，只有一个对 amp-api 的 catalog 接口有效。
     * 所以这里不写死包名和前缀：把首页引用的前端包里所有 JWT 取出来逐个试，缓存第一个能用的。
     *
     * 代价控制：`music.apple.com/` 的 HTML 有 2MB，而 `beta.music.apple.com/` 只有 2.7KB 且引用
     * 同一个 JS 包，所以 HTML 走 beta；JS 包本身压缩后约 876KB，仍要下一次，因此把取到的 token
     * 连同它自己的过期时间落盘，冷启动直接复用，只在快过期时才重新抓。
     */
    private suspend fun ensureToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            cachedToken.takeIf { it.isNotBlank() }?.let { return it }
            AppleTokenCache.read()?.let { cached ->
                cachedToken = cached
                return cached
            }
        }
        val home = NativeHttp.get(APPLE_BETA_HOME, userAgent = APPLE_WEB_UA)
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
                AppleTokenCache.write(it)
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
        val declared = parseCookieParts(cookie)["storefront"].orEmpty().trim().lowercase(Locale.ROOT)
        declared.takeIf { it.matches(Regex("^[a-z]{2}$")) }?.let { return it }
        // 账号真实商店优先于写死的 us：中国区账号按美区目录检索和取歌词都不对
        AppleAccount.storefront?.takeIf { it.matches(Regex("^[a-z]{2}$")) }?.let { return it }
        return "us"
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
        /** 歌单曲目分页的每页大小；Apple 的参数上限就是 300，301 会被直接拒掉。 */
        const val APPLE_TRACK_PAGE = 300

        /** 只为拿入口 JS 包名用的轻量首页，2.7KB；[APPLE_HOME] 的 HTML 有 2MB。 */
        const val APPLE_BETA_HOME = "https://beta.music.apple.com/"
        const val AMP_API = "https://amp-api.music.apple.com"
        const val LOOKUP_API = "https://itunes.apple.com/lookup"
        const val APPLE_WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        val APPLE_UNAUTHORIZED = Regex("""HTTP 40[13]\b""")

        /**
         * 歌词子资源的两个端点。中文等语言 `syllable-lyrics` 给逐词歌词，
         * 其他语言 `lyrics` 更全；两个都试，谁先给非空 TTML 用谁。
         */
        val APPLE_LYRIC_ENDPOINTS = listOf("syllable-lyrics", "lyrics")
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

        /** [Song.extra] 里整曲主清单的键，只有 AppleProvider 自己用。 */
        const val EXTRA_APPLE_HLS = "apple_hls"
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

/**
 * 从 `/v1/me/account` 读到的账号属性。Apple 只暴露订阅状态和所在商店，
 * 昵称和头像拿不到（见 [AppleProvider.accountInfo]）。
 */
object AppleAccount {
    /** 账号真实商店，例如 `cn`；由 [AppleProvider.accountInfo] 写入。 */
    @Volatile
    var storefront: String? = null
}

/**
 * developer token 的落盘缓存。token 是 JWT，`exp` 声明里带过期时间（实测约 70 天），
 * 所以不必每次冷启动都重新下 876KB 的前端包；只在快过期时才重新抓。
 *
 * [dir] 由 Application 启动时注入；没注入（测试等）就退回只走内存缓存。
 */
object AppleTokenCache {
    /** 距过期还有这么久就当它还有效，留点余量免得边界上拿到刚过期的 token。 */
    private const val FRESH_MARGIN_MS = 24 * 60 * 60 * 1000L
    private const val FILE_NAME = "apple_developer_token"

    @Volatile
    var dir: File? = null

    fun attach(context: Context) {
        dir = context.cacheDir
    }

    fun read(): String? {
        val file = file() ?: return null
        val token = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return token.takeIf { !isExpired(it) }
    }

    fun write(token: String) {
        val file = file() ?: return
        runCatching { file.writeText(token) }
    }

    private fun file(): File? = dir?.let { File(it, FILE_NAME) }

    /** 读不出 `exp` 时按已过期处理——宁可多抓一次，也不要拿过期 token 去撞 401。 */
    internal fun isExpired(token: String): Boolean {
        val exp = jwtExpiry(token) ?: return true
        return exp * 1000L - FRESH_MARGIN_MS <= System.currentTimeMillis()
    }

    /** JWT 第二段是 base64url 的 JSON，取 `exp`（Unix 秒）。解析不了就返回 null。 */
    private fun jwtExpiry(token: String): Long? {
        val payload = token.split('.').getOrNull(1) ?: return null
        val json = runCatching {
            String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return Regex(""""exp"\s*:\s*(\d+)""").find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }
}

/**
 * Apple Music 匿名歌词中转总开关。Apple 官方接口已不再向未登录用户返回歌词，
 * 关掉后 [AppleProvider.lyrics] 只试 Apple 自己的接口，等于没有歌词；
 * 好处是请求不再发给第三方服务（见 [APPLE_LYRIC_SOURCES]）。
 */
object AppleLyricsConfig {
    @Volatile
    var thirdPartyLyrics: Boolean = true
        private set

    fun configure(enabled: Boolean) {
        thirdPartyLyrics = enabled
    }
}

/**
 * Apple Music 匿名歌词的第三方来源。Apple 自己的 amp-api 已经不再向未登录用户返回
 * `relationships.lyrics`，这些服务用已登录的 Apple Music 账号中转，返回的仍是 Apple 原生
 * 逐词 TTML（`itunes:timing="Word"`），所以能直接喂给 [convertTtmlToLrc]。
 *
 * 它们都是社区运营的公共服务，可能随时过载或下线，因此按顺序逐个尝试，全部失败就返回空；
 * 用户也可以在设置里整体关掉（见 [AppleLyricsConfig]）。
 */
internal val APPLE_LYRIC_SOURCES: List<AppleLyricSource> = listOf(
    // JSON：{"ttmlContent": "<tt ...>", "type": "Syllable", ...}
    AppleLyricSource(
        name = "zarz",
        url = "https://api.zarz.moe/apple-music/lyrics",
        query = { id -> "id=${encodeId(id)}&v=1" },
        extract = ::appleTtmlFromZarz,
    ),
    // 带 ttml=true 时直接回 TTML 文本
    AppleLyricSource(
        name = "paxsenix",
        url = "https://lyrics.paxsenix.org/apple-music/lyrics",
        query = { id -> "id=${encodeId(id)}&ttml=true" },
        extract = ::appleTtmlFromPaxsenix,
    ),
    // AMLL 社区逐词词库，JSON：{"data": {"lyrics": "<tt ...>"}}
    AppleLyricSource(
        name = "amll",
        url = "https://api.amll.dev/v1/lyrics/get",
        query = { id -> "appleMusicId=${encodeId(id)}" },
        extract = ::appleTtmlFromAmll,
    ),
)

/** zarz 回的是 JSON，TTML 在 `ttmlContent`；没歌词时是 404，根本走不到这里。 */
internal fun appleTtmlFromZarz(body: String): String = runCatching {
    NativeHttp.json.decodeFromString<ZarzLyricsResponse>(body).ttmlContent
}.getOrDefault("")

/** paxsenix 带 `ttml=true` 时直接回 TTML 文本；过载时回 HTML/JSON，按非 TTML 丢掉。 */
internal fun appleTtmlFromPaxsenix(body: String): String {
    val trimmed = body.trimStart()
    // 错误页也是 `<` 开头（<html>…），所以只认 TTML 根元素和 XML 声明
    return if (trimmed.startsWith("<tt") || trimmed.startsWith("<?xml")) trimmed else ""
}

/** AMLL 回的是 JSON，TTML 在 `data.lyrics`；词库没有这首歌时回错误对象，按空丢掉。 */
internal fun appleTtmlFromAmll(body: String): String = runCatching {
    NativeHttp.json.decodeFromString<AmllLyricsResponse>(body).data.lyrics
}.getOrDefault("")

/**
 * 一个第三方 Apple 歌词来源：[url] 加 Apple Music 歌曲 ID 取回 TTML。
 * [query] 由各源自己拼，因为它们要的参数名不一样。
 */
internal class AppleLyricSource(
    val name: String,
    val url: String,
    val query: (id: String) -> String,
    val extract: (body: String) -> String,
) {
    suspend fun fetch(id: String): String =
        extract(NativeHttp.get("$url?${query(id)}", userAgent = NativeHttp.DEFAULT_USER_AGENT))
}

private fun encodeId(id: String): String = URLEncoder.encode(id, "UTF-8")

@Serializable
private data class ZarzLyricsResponse(val ttmlContent: String = "")

/** `/v1/me/account?meta=subscription`：订阅状态和账号所在商店。 */
@Serializable
internal data class AppleAccountResponse(
    val meta: AppleAccountMeta = AppleAccountMeta(),
)

@Serializable
internal data class AppleAccountMeta(val subscription: AppleSubscription = AppleSubscription())

/** [active] 为 null 表示响应里没有这个字段，不要和 false 混为一谈。 */
@Serializable
internal data class AppleSubscription(
    val active: Boolean? = null,
    val storefront: String = "",
)

@Serializable
private data class AmllLyricsResponse(val data: AmllSongItem = AmllSongItem())

@Serializable
private data class AmllSongItem(val lyrics: String = "")

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

/** `ids[apple-curators]=` 批量查询的响应：`data` 里带整棵 grouping 树。 */
@Serializable
private data class AppleGroupingResponse(
    val data: List<AppleResource> = emptyList(),
    val meta: AppleGroupingMeta = AppleGroupingMeta(),
)

/**
 * 少了 `platform=web` 时 Apple 返回 200 但树是空的，并把原因放在这里——
 * `failures.response == "partial-failure"`。属于静默失败，调用方要当错误处理。
 */
@Serializable
private data class AppleGroupingMeta(val failures: AppleGroupingFailures = AppleGroupingFailures())

@Serializable
private data class AppleGroupingFailures(val response: String = "")

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
    /** `/songs/{id}/lyrics` 子资源返回的 TTML；`extend=ttmlLocalizations` 时另有本地化版本。 */
    val ttml: String = "",
    val ttmlLocalizations: String = "",
    /**
     * `extend=extendedAssetUrls` 才回的整曲地址。[enhancedHls] 是 HLS 主清单（含 ALAC /
     * Atmos / 256k 等全部档位），其余几个是 `.m4p` 直链。注意这些都是加密的，见
     * [AppleProvider.playableStream]。
     */
    val extendedAssetUrls: AppleAssetUrls = AppleAssetUrls(),
)

@Serializable
private data class AppleAssetUrls(
    val enhancedHls: String = "",
    val lightweight: String = "",
    val lightweightPlus: String = "",
    val plus: String = "",
    val superLightweight: String = "",
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
    /** grouping 树的下钻关系：tab → children → contents → playlists。 */
    val tabs: AppleNodeList = AppleNodeList(),
    val children: AppleNodeList = AppleNodeList(),
    val contents: AppleNodeList = AppleNodeList(),
    val playlists: AppleNodeList = AppleNodeList(),
    /** `ids[apple-curators]=` 批量查询时，树根挂在这里。 */
    val grouping: AppleNodeList = AppleNodeList(),
)

@Serializable
private data class AppleNodeList(val data: List<AppleResource> = emptyList())

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
