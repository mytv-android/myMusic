package com.mytv.mymusic.data.native

import android.util.Base64
import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val SPOTIFY_INFO = ProviderInfo(
    id = "spotify",
    name = "Spotify",
    search = true,
    // 官方 API 只给 30 秒试听，整曲受 DRM 保护，下载能力按“仅预览”算
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
    qrLogin = false,
    playlistCategories = true,
    topLists = true,
    topListSongs = true,
    hotSongs = true,
    note = "整曲受 DRM 保护，仅能播放官方 30 秒试听；未配置凭据时用匿名 Web Player 检索、公开嵌入与榜单镜像兜底。",
)

/** Spotify 的 HTTP 错误：能不能回退到公开嵌入，全看状态码。 */
private class SpotifyApiException(val status: Int, message: String) : Exception(message)

/**
 * Spotify 平台。
 *
 * 两条链路并存：配了 Client ID/Secret 或 token 时走官方 Web API；没配（或官方接口 401/403/404/429）
 * 就退回公开嵌入页与匿名 Web Player 检索，所以搜索、歌单、专辑、榜单在零配置下都能用。
 * 整曲始终拿不到——DRM 保护的资源不存在明文地址，只能给官方 preview。
 *
 * 移植自 music-lib 的 spotify 包（AGPL-3.0）。
 */
class SpotifyProvider : BaseNativeProvider(SPOTIFY_INFO) {
    private val json = NativeHttp.json

    // ---- 凭据与 token ----

    private data class Config(
        val clientId: String = "",
        val clientSecret: String = "",
        val refreshToken: String = "",
        val accessToken: String = "",
        val market: String = DEFAULT_MARKET,
    )

    private class TokenState(
        var accessToken: String = "",
        var isUser: Boolean = false,
        var expiresAt: Long = 0L,
        var refreshToken: String = "",
    )

    private val tokenMutex = Mutex()
    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, TokenState>()

    private fun configOf(cookie: String): Config {
        val values = parseSpotifyConfig(cookie)
        val market = firstValue(values, "market", "country", "spotify_market", "spotifymarket").uppercase()
        return Config(
            clientId = firstValue(values, "client_id", "clientid", "spotify_client_id", "spotifyclientid"),
            clientSecret = firstValue(values, "client_secret", "clientsecret", "spotify_client_secret", "spotifyclientsecret"),
            refreshToken = firstValue(values, "refresh_token", "refreshtoken", "spotify_refresh_token", "spotifyrefreshtoken"),
            accessToken = firstValue(values, "access_token", "accesstoken", "bearer_token", "bearertoken", "token"),
            market = if (market.length == 2) market else DEFAULT_MARKET,
        )
    }

    /**
     * 按上游的别名表取第一个命中的键。键名只做小写与 `-`/空格 转 `_`，
     * 不拆驼峰，所以 `refreshToken` 归一成 `refreshtoken`，得在别名里列全。
     */
    private fun firstValue(values: Map<String, String>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { values[normalizeSpotifyConfigKey(it)]?.takeIf(String::isNotBlank) }.orEmpty()

    private val Config.key: String get() = NativeCrypto.md5Hex("$clientId|$clientSecret|$refreshToken|$accessToken")

    private fun Config.hasCatalogAuthentication(): Boolean =
        accessToken.isNotBlank() || refreshToken.isNotBlank() || (clientId.isNotBlank() && clientSecret.isNotBlank())

    private fun Config.marketOrDefault(): String = if (market.length == 2) market.uppercase() else DEFAULT_MARKET

    private suspend fun Config.ensureAccessToken(userOnly: Boolean): String {
        val state = tokenCache.getOrPut(key) { TokenState() }
        tokenMutex.withLock {
            if (state.accessToken.isNotBlank() &&
                (!userOnly || state.isUser) &&
                (state.expiresAt == 0L || System.currentTimeMillis() + TOKEN_SAFETY_MS < state.expiresAt)
            ) {
                return state.accessToken
            }
            if (refreshToken.isNotBlank()) {
                val fetched = runCatching { fetchAccessToken(state, "refresh_token") }.getOrNull()
                if (fetched != null) return fetched
                if (userOnly) throw IllegalStateException("spotify 用户歌单需要带 playlist-read-private 的 token")
            }
            if (userOnly) {
                throw IllegalStateException("spotify 用户歌单需要 access_token 或 refresh_token")
            }
            if (clientId.isBlank() || clientSecret.isBlank()) throw SpotifyApiException(0, "未配置 Spotify 凭据")
            return fetchAccessToken(state, "client_credentials")
                ?: throw IllegalStateException("spotify 获取 access_token 失败")
        }
    }

    private suspend fun Config.fetchAccessToken(state: TokenState, grantType: String): String? {
        val form = buildMap {
            put("grant_type", grantType)
            when (grantType) {
                "refresh_token" -> {
                    put("refresh_token", refreshToken)
                    if (clientSecret.isBlank()) put("client_id", clientId)
                }
                "client_credentials" -> Unit
                else -> return null
            }
        }
        val headers = mutableMapOf("Content-Type" to "application/x-www-form-urlencoded")
        if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
            val basic = Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.NO_WRAP)
            headers["Authorization"] = "Basic $basic"
        }
        val response = NativeHttp.postForm(TOKEN_API, form, headers = headers)
        if (response.code !in 200..299) throw SpotifyApiException(response.code, "spotify token ${response.code}")
        val parsed = json.decodeFromString<SpotifyTokenResponse>(response.body)
        val token = parsed.accessToken.trim()
        if (token.isBlank()) throw IllegalStateException("spotify token 响应缺少 access_token")
        state.accessToken = token
        state.isUser = grantType == "refresh_token"
        val expiresIn = if (parsed.expiresIn > 0) parsed.expiresIn else 3600
        state.expiresAt = System.currentTimeMillis() + expiresIn * 1000L
        if (parsed.refreshToken.isNotBlank()) {
            state.refreshToken = parsed.refreshToken
        }
        return token
    }

    private fun Config.invalidateToken(token: String) {
        val state = tokenCache[key] ?: return
        if (token.isBlank() || state.accessToken == token) {
            state.accessToken = ""
            state.expiresAt = 0L
            state.isUser = false
        }
    }

    /** 官方 Web API 请求；401 换一次 token、429 按 Retry-After 退避后重试。 */
    private suspend fun Config.getJson(
        path: String,
        query: Map<String, String> = emptyMap(),
        userOnly: Boolean = false,
    ): JsonObject? {
        var authRetried = false
        var rateLimitRetried = false
        repeat(3) {
            val token = ensureAccessToken(userOnly)
            val url = buildString {
                append(API_BASE).append('/').append(path.trimStart('/'))
                if (query.isNotEmpty()) {
                    append('?')
                    append(query.entries.joinToString("&") { (k, v) ->
                        "${urlEncode(k)}=${urlEncode(v)}"
                    })
                }
            }
            val response = NativeHttp.getResponse(
                url,
                headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
                userAgent = USER_AGENT,
            )
            if (response.code == 401 && !authRetried) {
                authRetried = true
                invalidateToken(token)
                return@repeat
            }
            if (response.code == 429 && !rateLimitRetried) {
                rateLimitRetried = true
                return@repeat
            }
            if (response.code !in 200..299) throw SpotifyApiException(response.code, spotifyErrorMessage(response.body))
            if (response.body.isBlank()) return null
            return json.parseToJsonElement(response.body).jsonObject
        }
        throw IllegalStateException("spotify 请求在重新鉴权重试后仍然失败")
    }

    // ---- 匿名 Web Player ----

    private class AnonymousAuth(
        val accessToken: String,
        val clientToken: String,
        val clientVersion: String,
        val deviceId: String,
        val cookie: String,
        val expiresAt: Long,
    )

    @Volatile
    private var anonymousAuth: AnonymousAuth? = null

    private suspend fun anonymousAuthOrThrow(): AnonymousAuth {
        anonymousAuth?.takeIf { System.currentTimeMillis() + ANONYMOUS_AUTH_MARGIN_MS < it.expiresAt }?.let { return it }
        val fetched = fetchAnonymousAuth() ?: throw IllegalStateException(
            "spotify 匿名 Web Player 取 token 失败；协议可能已更换，需要更新 TOTP 版本",
        )
        anonymousAuth = fetched
        return fetched
    }

    private suspend fun fetchAnonymousAuth(): AnonymousAuth? {
        val bootstrap = fetchWebPlayerBootstrap() ?: return null
        var lastError: Throwable? = null
        for (secret in TOTP_SECRETS) {
            val totp = generateTotp(secret.values)
            val token = runCatching { fetchWebPlayerToken(bootstrap.cookie, secret.version, totp) }.getOrElse { error ->
                lastError = error
                null
            } ?: continue
            val clientToken = runCatching {
                fetchWebPlayerClientToken(bootstrap.cookie, bootstrap.clientVersion, token.clientId, bootstrap.deviceId)
            }.getOrElse { error ->
                lastError = error
                null
            } ?: continue
            return AnonymousAuth(
                accessToken = token.accessToken,
                clientToken = clientToken,
                clientVersion = bootstrap.clientVersion,
                deviceId = bootstrap.deviceId,
                cookie = bootstrap.cookie,
                expiresAt = System.currentTimeMillis() + ANONYMOUS_TOKEN_TTL_MS,
            )
        }
        lastError?.let { throw it }
        return null
    }

    private class Bootstrap(val deviceId: String, val cookie: String, val clientVersion: String)

    private suspend fun fetchWebPlayerBootstrap(): Bootstrap? {
        val response = NativeHttp.getResponse(
            "$OPEN_BASE/",
            headers = mapOf("Accept" to "text/html,application/xhtml+xml", "Accept-Language" to "en-US,en;q=0.9"),
            userAgent = WEB_PLAYER_USER_AGENT,
        )
        if (response.code !in 200..299) return null
        val deviceId = response.cookies["sp_t"]?.takeIf { it.isNotBlank() } ?: randomUuid()
        val clientVersion = appServerClientVersion(response.body) ?: return null
        return Bootstrap(deviceId, "sp_t=$deviceId", clientVersion)
    }

    @Serializable
    private data class WebPlayerToken(val accessToken: String = "", val clientId: String = "")

    private suspend fun fetchWebPlayerToken(cookie: String, totpVersion: Int, totp: String): WebPlayerToken {
        val url = "$OPEN_BASE/api/token?" + listOf(
            "reason" to "init",
            "productType" to "web-player",
            "totp" to totp,
            "totpVer" to totpVersion.toString(),
            "totpServer" to totp,
        ).joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
        val response = NativeHttp.getResponse(
            url,
            headers = mapOf(
                "Accept" to "application/json",
                "Accept-Language" to "en-US,en;q=0.9",
                "Origin" to OPEN_BASE,
                "Referer" to "$OPEN_BASE/",
                "Cookie" to cookie,
            ),
            userAgent = WEB_PLAYER_USER_AGENT,
        )
        if (response.code !in 200..299) throw IllegalStateException("spotify web player token ${response.code}")
        val parsed = json.decodeFromString<WebPlayerToken>(response.body)
        if (parsed.accessToken.isBlank() || parsed.clientId.isBlank()) {
            throw IllegalStateException("spotify web player token 响应不完整")
        }
        return parsed
    }

    @Serializable
    private data class ClientTokenResponse(val granted_token: GrantedToken? = null) {
        @Serializable
        data class GrantedToken(val token: String = "")
    }

    private suspend fun fetchWebPlayerClientToken(
        cookie: String,
        clientVersion: String,
        clientId: String,
        deviceId: String,
    ): String {
        val payload = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", clientVersion)
                put("client_id", clientId)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", "windows")
                    put("os_version", "NT 10.0")
                    put("device_id", deviceId)
                    put("device_type", "computer")
                }
            }
        }.toString()
        val response = NativeHttp.postJson(
            "$CLIENT_TOKEN_BASE/v1/clienttoken",
            payload,
            headers = mapOf(
                "Accept" to "application/json",
                "Origin" to OPEN_BASE,
                "Referer" to "$OPEN_BASE/",
                "Cookie" to cookie,
            ),
            userAgent = WEB_PLAYER_USER_AGENT,
        )
        if (response.code !in 200..299) throw IllegalStateException("spotify client token ${response.code}")
        val token = json.decodeFromString<ClientTokenResponse>(response.body).granted_token?.token.orEmpty().trim()
        if (token.isBlank()) throw IllegalStateException("spotify client token 响应为空")
        return token
    }

    /** 匿名 GraphQL 检索：歌曲 / 专辑 / 歌单一次请求全拿到，按 keyword 缓存一分钟。 */
    private suspend fun webPlayerSearch(keyword: String): JsonObject {
        webSearchCache[keyword]?.takeIf { System.currentTimeMillis() < it.second }?.let { return it.first }
        val payload = buildJsonObject {
            put("operationName", "searchDesktop")
            putJsonObject("variables") {
                put("searchTerm", keyword)
                put("offset", 0)
                put("limit", SEARCH_LIMIT)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", true)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", true)
                put("includeAuthors", false)
            }
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", SEARCH_QUERY_HASH)
                }
            }
        }.toString()

        var authRetried = false
        while (true) {
            val auth = anonymousAuthOrThrow()
            val response = NativeHttp.postJson(
                "$PARTNER_BASE/pathfinder/v2/query",
                payload,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Origin" to OPEN_BASE,
                    "Referer" to "$OPEN_BASE/",
                    "Authorization" to "Bearer ${auth.accessToken}",
                    "Client-Token" to auth.clientToken,
                    "Spotify-App-Version" to auth.clientVersion,
                    "Cookie" to auth.cookie,
                ),
                userAgent = WEB_PLAYER_USER_AGENT,
            )
            if ((response.code == 401 || response.code == 403) && !authRetried) {
                authRetried = true
                anonymousAuth = null
                continue
            }
            if (response.code !in 200..299) {
                throw IllegalStateException("spotify 匿名检索 ${response.code}: ${spotifyErrorMessage(response.body)}")
            }
            val root = json.parseToJsonElement(response.body).jsonObject
            if (root["errors"]?.jsonArray?.isNotEmpty() == true && webSearchResultCount(root) == 0) {
                val message = root["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.content
                throw IllegalStateException("spotify 匿名检索被拒：${message ?: "未知错误"}")
            }
            if (webSearchCache.size >= SEARCH_CACHE_SIZE) {
                webSearchCache.keys.take(webSearchCache.size - SEARCH_CACHE_SIZE + 1).forEach { webSearchCache.remove(it) }
            }
            webSearchCache[keyword] = root to System.currentTimeMillis() + SEARCH_CACHE_TTL_MS
            return root
        }
    }

    private val webSearchCache = java.util.concurrent.ConcurrentHashMap<String, Pair<JsonObject, Long>>()

    private fun webSearchItems(root: JsonObject, vararg keys: String): List<JsonObject> {
        val search = root["data"]?.jsonObject?.get("searchV2")?.jsonObject ?: return emptyList()
        for (key in keys) {
            val items = search[key]?.jsonObject?.get("items")?.jsonArray
            if (!items.isNullOrEmpty()) {
                return items.mapNotNull { item ->
                    val element = item.jsonObject
                    element["item"]?.jsonObject?.get("data")?.jsonObject
                        ?: element["itemV2"]?.jsonObject?.get("data")?.jsonObject
                        ?: element["data"]?.jsonObject
                }
            }
        }
        return emptyList()
    }

    private fun webSearchResultCount(root: JsonObject): Int = webSearchItems(root, "tracksV2", "tracks").size +
        webSearchItems(root, "albumsV2", "albums").size +
        webSearchItems(root, "playlistsV2", "playlists").size

    // ---- 公开嵌入页 ----

    @Serializable
    private data class EmbedEntity(
        val type: String = "",
        val uri: String = "",
        val id: String = "",
        val name: String = "",
        val title: String = "",
        val subtitle: String = "",
        val artists: List<EmbedArtist> = emptyList(),
        val duration: Int = 0,
        val releaseDate: JsonElement? = null,
        val audioPreview: EmbedPreview? = null,
        val coverArt: EmbedArt = EmbedArt(),
        val visualIdentity: EmbedVisualIdentity = EmbedVisualIdentity(),
        val trackList: List<EmbedTrack> = emptyList(),
    ) {
        /** 嵌入页的 releaseDate 有时是 `"2024-01-01"` 有时是 `{"isoString":..,"year":..}`。 */
        val releaseDateText: String
            get() {
                val element = releaseDate ?: return ""
                val primitive = element as? kotlinx.serialization.json.JsonPrimitive
                if (primitive != null && !primitive.content.isNullOrBlank()) return primitive.content.trim()
                val obj = element as? JsonObject ?: return ""
                return obj["isoString"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                    ?: obj["year"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }?.toString()
                    .orEmpty()
            }

        @Serializable
        data class EmbedArtist(val name: String = "")

        @Serializable
        data class EmbedPreview(val format: String = "", val url: String = "")

        @Serializable
        data class EmbedArt(val sources: List<EmbedImage> = emptyList())

        @Serializable
        data class EmbedVisualIdentity(val image: List<EmbedImage> = emptyList())

        @Serializable
        data class EmbedTrack(
            val uri: String = "",
            val title: String = "",
            val subtitle: String = "",
            val duration: Int = 0,
            val isPlayable: Boolean = true,
            val isExplicit: Boolean = false,
            val audioPreview: EmbedPreview? = null,
        )
    }

    @Serializable
    private data class EmbedImage(val url: String = "", val width: Int = 0, val height: Int = 0)

    @Serializable
    private data class EmbedPage(
        val props: Props = Props(),
    ) {
        @Serializable
        data class Props(val pageProps: PageProps = PageProps()) {
            @Serializable
            data class PageProps(
                val status: Int = 0,
                val state: State = State(),
            ) {
                @Serializable
                data class State(val data: Data = Data()) {
                    @Serializable
                    data class Data(val entity: EmbedEntity = EmbedEntity())
                }
            }
        }
    }

    private suspend fun fetchEmbedEntity(entityType: String, id: String): EmbedEntity {
        val endpoints = listOf(
            "$OPEN_BASE/embed/$entityType/${urlEncode(id)}",
            "$EMBED_BASE/?uri=${urlEncode("spotify:$entityType:$id")}",
        )
        val results = coroutineScope {
            endpoints.map { endpoint ->
                async {
                    runCatching { fetchEmbedEndpoint(endpoint, entityType, id) }
                }
            }.awaitAll()
        }
        results.firstOrNull { it.isSuccess }?.getOrNull()?.let { return it }
        val failure = results.firstOrNull { it.isFailure }?.exceptionOrNull()
        throw IllegalStateException("spotify $entityType 公开嵌入取不到：${failure?.message ?: "未知错误"}")
    }

    private suspend fun fetchEmbedEndpoint(endpoint: String, entityType: String, id: String): EmbedEntity {
        val response = NativeHttp.getResponse(
            endpoint,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml"),
            userAgent = USER_AGENT,
        )
        if (response.code !in 200..299) throw IllegalStateException("spotify 嵌入页 ${response.code}")
        val payload = nextDataPayload(response.body) ?: throw IllegalStateException("spotify 嵌入页没有 __NEXT_DATA__")
        val page = json.decodeFromString<EmbedPage>(payload)
        if (page.props.pageProps.status == 451) throw IllegalStateException("spotify $entityType 在当前地区不可用（451）")
        val entity = page.props.pageProps.state.data.entity
        if (!entity.type.equals(entityType, ignoreCase = true)) {
            throw IllegalStateException("spotify 嵌入页返回的是 ${entity.type}")
        }
        if (entity.id.ifBlank { id }.isBlank()) throw IllegalStateException("spotify 嵌入页没有 $entityType")
        return entity
    }

    // ---- 歌单 / 专辑 / 榜单 ----

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val config = configOf(cookie)
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) throw IllegalArgumentException("spotify 搜索关键词为空")
        if (!config.hasCatalogAuthentication()) return webPlayerSongs(trimmed)
        val query = mapOf("q" to trimmed, "type" to "track", "limit" to SEARCH_LIMIT.toString(), "market" to config.marketOrDefault())
        val response = runCatching { config.getJson("/search", query) }.getOrElse { error ->
            if (!allowsAnonymousFallback(error)) throw error
            return webPlayerSongs(trimmed)
        }
        return response?.get("tracks")?.jsonObject?.get("items")?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.filter { it["id"]?.jsonPrimitive?.content?.isNotBlank() == true }
            ?.map { songFromApiTrack(it) }
            .orEmpty()
    }

    private suspend fun webPlayerSongs(keyword: String): List<Song> {
        val root = webPlayerSearch(keyword)
        return webSearchItems(root, "tracksV2", "tracks").mapNotNull { songFromWebPlayerTrack(it) }
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val id = extractSpotifyId(link, "track")
        val config = configOf(cookie)
        if (!config.hasCatalogAuthentication()) return fetchPublicTrack(id)
        val track = runCatching { config.getJson("/tracks/${urlEncode(id)}", mapOf("market" to config.marketOrDefault())) }
            .getOrNull()
        return track?.let { songFromApiTrack(it) } ?: fetchPublicTrack(id)
    }

    private suspend fun fetchPublicTrack(id: String): Song {
        val entity = fetchEmbedEntity("track", id)
        val song = songFromEmbed(entity)
        if (song == null) throw IllegalStateException("spotify 公开嵌入没有返回曲目")
        return song
    }

    override suspend fun downloadUrl(cookie: String, song: Song, quality: AudioQuality): String {
        if (quality != AudioQuality.Auto && quality != AudioQuality.Preview) {
            throw IllegalStateException("spotify 整曲受 DRM 保护，只有试听片段")
        }
        if (song.source.isNotBlank() && !song.source.equals("spotify", ignoreCase = true)) {
            throw IllegalArgumentException("source mismatch")
        }
        if (song.url.isNotBlank()) return song.url
        val id = song.id.ifBlank { extractSpotifyId(song.link, "track") }
        if (id.isBlank()) throw IllegalStateException("spotify 曲目 id 为空")
        val config = configOf(cookie)
        val publicPreview = runCatching { fetchPublicTrack(id).url }.getOrNull().orEmpty()
        if (publicPreview.isNotBlank()) return publicPreview
        if (config.hasCatalogAuthentication()) {
            val track = runCatching { config.getJson("/tracks/${urlEncode(id)}", mapOf("market" to config.marketOrDefault())) }.getOrNull()
            val preview = track?.get("preview_url")?.jsonPrimitive?.content?.trim().orEmpty()
            if (preview.isNotBlank()) return preview
        }
        throw IllegalStateException("spotify 该曲目没有试听地址；整曲受 DRM 保护")
    }

    override suspend fun availableQualityOptions(cookie: String): List<AudioQualityOption> {
        val probe = Song(id = SPOTIFY_PROBE_SONG_ID, source = "spotify")
        downloadUrl(cookie, probe, AudioQuality.Preview)
        return listOf(SPOTIFY_PREVIEW_QUALITY)
    }

    /** 歌词走 LRCLIB：先精确匹配，不中再按歌名 + 歌手搜一遍。 */
    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != "spotify") throw IllegalArgumentException("source mismatch")
        var name = song.name.trim()
        var artist = song.artist.trim()
        var album = song.album.trim()
        var duration = song.duration
        if (name.isBlank() || artist.isBlank()) {
            val id = song.id.ifBlank { runCatching { extractSpotifyId(song.link, "track") }.getOrNull().orEmpty() }
            if (id.isBlank()) throw IllegalStateException("spotify 歌词需要曲目 id、歌名与歌手")
            val config = configOf(cookie)
            val track = runCatching { config.getJson("/tracks/${urlEncode(id)}", mapOf("market" to config.marketOrDefault())) }.getOrNull()
                ?: throw IllegalStateException("spotify 取曲目详情失败")
            val fetched = songFromApiTrack(track)
            name = fetched.name
            artist = fetched.artist
            album = fetched.album
            duration = fetched.duration
        }
        if (name.isBlank() || artist.isBlank()) throw IllegalStateException("spotify 歌词需要歌名与歌手")

        val exact = buildMap {
            put("track_name", name)
            put("artist_name", artist)
            if (album.isNotBlank()) put("album_name", album)
            if (duration > 0) put("duration", duration.toString())
        }
        val exactResponse = spotifyPublicGet("$LRCLIB_API/get", exact)
        if (exactResponse.code == 200) {
            val text = lrclibText(exactResponse.body)
            if (text.isNotBlank()) return text
        }
        if (exactResponse.code != 404 && exactResponse.code != 200) {
            throw IllegalStateException("spotify 歌词：LRCLIB 返回 ${exactResponse.code}")
        }
        val searchResponse = spotifyPublicGet(
            "$LRCLIB_API/search",
            mapOf("track_name" to name, "artist_name" to artist),
        )
        if (searchResponse.code != 200) throw IllegalStateException("spotify 歌词搜索：LRCLIB 返回 ${searchResponse.code}")
        val candidates = runCatching {
            json.decodeFromString<List<LrclibLyrics>>(searchResponse.body)
        }.getOrDefault(emptyList())
        return candidates.firstOrNull { lrclibText(it.syncedLyrics.ifBlank { it.plainLyrics }).isNotBlank() }
            ?.let { lrclibText(it.syncedLyrics.ifBlank { it.plainLyrics }) }
            .orEmpty()
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val config = configOf(cookie)
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) throw IllegalArgumentException("spotify 歌单搜索关键词为空")
        if (!config.hasCatalogAuthentication()) return webPlayerPlaylists(trimmed)
        val query = mapOf("q" to trimmed, "type" to "playlist", "limit" to SEARCH_LIMIT.toString(), "market" to config.marketOrDefault())
        val response = runCatching { config.getJson("/search", query) }.getOrElse { error ->
            if (!allowsAnonymousFallback(error)) throw error
            return webPlayerPlaylists(trimmed)
        }
        return response?.get("playlists")?.jsonObject?.get("items")?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.map { playlistFromApi(it) }
            .orEmpty()
    }

    private suspend fun webPlayerPlaylists(keyword: String): List<Playlist> =
        webSearchItems(webPlayerSearch(keyword), "playlistsV2", "playlists").mapNotNull { playlistFromWebPlayer(it) }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> {
        val playlistId = extractSpotifyId(id, "playlist")
        val config = configOf(cookie)
        if (!config.hasCatalogAuthentication()) return playlistTracksFromPublic(playlistId)
        val songs = runCatching { fetchPlaylistTracks(config, playlistId) }.getOrElse { error ->
            if (!allowsPublicFallback(error)) throw error
            return playlistTracksFromPublic(playlistId)
        }
        return songs
    }

    private suspend fun fetchPlaylistTracks(config: Config, playlistId: String): List<Song> {
        val songs = mutableListOf<Song>()
        var endpoint = "items"
        var offset = 0
        while (true) {
            val query = mapOf(
                "limit" to PLAYLIST_PAGE_SIZE.toString(),
                "offset" to offset.toString(),
                "market" to config.marketOrDefault(),
                "additional_types" to "track",
            )
            val page = runCatching { config.getJson("/playlists/${urlEncode(playlistId)}/$endpoint", query) }
                .getOrElse { error ->
                    // Spotify 把 /tracks 改名成了 /items；老形状的账号仍然只认 /tracks
                    if (offset == 0 && endpoint == "items" && error is SpotifyApiException && error.status == 404) {
                        endpoint = "tracks"
                        return@getOrElse null
                    }
                    throw error
                } ?: continue
            val items = page["items"]?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()
            songs += items.mapNotNull { item ->
                val track = item["track"]?.jsonObject ?: item["item"]?.jsonObject ?: return@mapNotNull null
                if (track["id"]?.jsonPrimitive?.content?.isBlank() != false) null else songFromApiTrack(track)
            }
            val total = page["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (items.isEmpty() || (total > 0 && songs.size >= total) || page["next"]?.jsonPrimitive?.content.isNullOrBlank()) break
            offset += items.size
        }
        return songs
    }

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val playlistId = extractSpotifyId(link, "playlist")
        val config = configOf(cookie)
        val songs = playlistSongs(cookie, playlistId)
        val playlist = runCatching { fetchPlaylist(config, playlistId) }.getOrNull()
            ?: fetchPlaylistFromEmbed(playlistId)
        return CollectionResult(
            item = playlist.copy(trackCount = if (playlist.trackCount > 0) playlist.trackCount else songs.size),
            songs = songs,
        )
    }

    private suspend fun fetchPlaylist(config: Config, playlistId: String): Playlist {
        if (!config.hasCatalogAuthentication()) return fetchPlaylistFromEmbed(playlistId)
        val response = runCatching { config.getJson("/playlists/${urlEncode(playlistId)}", mapOf("market" to config.marketOrDefault())) }
            .getOrElse { error ->
                if (!allowsPublicFallback(error)) throw error
                return fetchPlaylistFromEmbed(playlistId)
            }
        val id = response?.get("id")?.jsonPrimitive?.content.orEmpty()
        if (response == null || id.isBlank()) throw IllegalStateException("spotify 歌单 $playlistId 不存在")
        return playlistFromApi(response)
    }

    private suspend fun fetchPlaylistFromEmbed(playlistId: String): Playlist {
        val entity = fetchEmbedEntity("playlist", playlistId)
        return Playlist(
            id = entity.id.ifBlank { playlistId },
            name = entity.name.ifBlank { entity.title },
            cover = bestEmbedCover(entity.coverArt.sources).ifBlank { bestEmbedCover(entity.visualIdentity.image) },
            trackCount = entity.trackList.size,
            creator = cleanEmbedText(entity.subtitle).ifBlank { "Spotify" },
            description = "Spotify 公开歌单",
            source = "spotify",
            link = "$OPEN_BASE/playlist/$playlistId",
            extra = mapOf("type" to "playlist", "spotify_id" to playlistId),
        )
    }

    /** 没凭据时歌单曲目的两条公开兜底：嵌入页 + kworb 榜单镜像，谁先成功用谁。 */
    private suspend fun playlistTracksFromPublic(playlistId: String): List<Song> {
        val chartCountry = chartCountryOf(playlistId)
        val results = coroutineScope {
            buildList {
                add(async { runCatching { songsFromEmbed(fetchEmbedEntity("playlist", playlistId)) } })
                if (chartCountry != null) {
                    add(async { runCatching { chartTracks(playlistId, chartCountry) } })
                }
            }.awaitAll()
        }
        results.firstOrNull { it.isSuccess }?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val failure = results.firstOrNull { it.isFailure }?.exceptionOrNull()
        throw IllegalStateException("spotify 歌单 $playlistId 公开兜底失败：${failure?.message ?: "未知错误"}")
    }

    override suspend fun recommendedPlaylists(cookie: String): List<Playlist> {
        val config = configOf(cookie)
        val query = mapOf("country" to config.marketOrDefault(), "locale" to "zh_CN", "limit" to CATEGORY_LIMIT.toString())
        val response = runCatching { config.getJson("/browse/featured-playlists", query) }.getOrNull()
        val playlists = response?.get("playlists")?.jsonObject?.get("items")?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.map { playlistFromApi(it) }
            .orEmpty()
        return playlists.ifEmpty { topListPlaylists() }
    }

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val config = configOf(cookie)
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) throw IllegalArgumentException("spotify 专辑搜索关键词为空")
        if (!config.hasCatalogAuthentication()) return webPlayerAlbums(trimmed)
        val query = mapOf("q" to trimmed, "type" to "album", "limit" to SEARCH_LIMIT.toString(), "market" to config.marketOrDefault())
        val response = runCatching { config.getJson("/search", query) }.getOrElse { error ->
            if (!allowsAnonymousFallback(error)) throw error
            return webPlayerAlbums(trimmed)
        }
        return response?.get("albums")?.jsonObject?.get("items")?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.map { albumFromApi(it) }
            .orEmpty()
    }

    private suspend fun webPlayerAlbums(keyword: String): List<Playlist> =
        webSearchItems(webPlayerSearch(keyword), "albumsV2", "albums").mapNotNull { albumFromWebPlayer(it) }

    override suspend fun albumSongs(cookie: String, id: String): List<Song> {
        val albumId = extractSpotifyId(id, "album")
        val config = configOf(cookie)
        val fromApi = if (config.hasCatalogAuthentication()) {
            runCatching { fetchAlbumTracks(config, albumId) }.getOrNull()
        } else {
            null
        }
        if (!fromApi.isNullOrEmpty()) return fromApi
        val (_, songs) = fetchAlbumFromPublic(albumId)
        return songs
    }

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult {
        val albumId = extractSpotifyId(link, "album")
        val config = configOf(cookie)
        if (config.hasCatalogAuthentication()) {
            val album = runCatching { config.getJson("/albums/${urlEncode(albumId)}", mapOf("market" to config.marketOrDefault())) }.getOrNull()
            if (album != null && album["id"]?.jsonPrimitive?.content?.isNotBlank() == true) {
                val songs = fetchAlbumTracks(config, albumId)
                return CollectionResult(albumFromApi(album).copy(trackCount = songs.size), songs)
            }
        }
        val (playlist, songs) = fetchAlbumFromPublic(albumId)
        return CollectionResult(playlist, songs)
    }

    private suspend fun fetchAlbumTracks(config: Config, albumId: String): List<Song> {
        val songs = mutableListOf<Song>()
        var offset = 0
        while (true) {
            val query = mapOf("limit" to ALBUM_PAGE_SIZE.toString(), "offset" to offset.toString(), "market" to config.marketOrDefault())
            val page = config.getJson("/albums/${urlEncode(albumId)}/tracks", query)
                ?: throw IllegalStateException("spotify 专辑 $albumId 曲目为空")
            val items = page["items"]?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()
            songs += items.filter { it["id"]?.jsonPrimitive?.content?.isNotBlank() == true }.map { songFromApiTrack(it) }
            val total = page["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (items.isEmpty() || (total > 0 && songs.size >= total) || page["next"]?.jsonPrimitive?.content.isNullOrBlank()) break
            offset += items.size
        }
        return songs
    }

    private suspend fun fetchAlbumFromPublic(albumId: String): Pair<Playlist, List<Song>> {
        val entity = fetchEmbedEntity("album", albumId)
        val name = entity.name.ifBlank { entity.title }
        if (entity.id.ifBlank { albumId }.isBlank() || name.isBlank()) {
            throw IllegalStateException("spotify 公开专辑元数据缺少 id 或名称")
        }
        val cover = bestEmbedCover(entity.visualIdentity.image).ifBlank { bestEmbedCover(entity.coverArt.sources) }
        val releaseDate = entity.releaseDateText
        val playlist = Playlist(
            id = entity.id.ifBlank { albumId },
            name = name,
            cover = cover,
            trackCount = entity.trackList.size,
            creator = artistNames(entity.artists).ifBlank { cleanEmbedText(entity.subtitle) },
            description = releaseDate,
            source = "spotify",
            link = "$OPEN_BASE/album/$albumId",
            extra = buildMap {
                put("type", "album")
                put("spotify_id", entity.id.ifBlank { albumId })
                if (releaseDate.isNotBlank()) put("release_date", releaseDate)
            },
        )
        val songs = songsFromEmbed(entity).map { song ->
            song.copy(
                album = song.album.ifBlank { name },
                albumId = song.albumId.ifBlank { playlist.id },
                cover = song.cover.ifBlank { cover },
            )
        }
        return playlist to songs
    }

    override suspend fun categories(cookie: String): List<PlaylistCategory> {
        val config = configOf(cookie)
        val query = mapOf("limit" to CATEGORY_LIMIT.toString(), "country" to config.marketOrDefault(), "locale" to "zh_CN")
        val response = runCatching { config.getJson("/browse/categories", query) }.getOrNull()
        val items = response?.get("categories")?.jsonObject?.get("items")?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()
        val categories = items.mapNotNull { item ->
            val id = item["id"]?.jsonPrimitive?.content?.trim().orEmpty()
            val name = item["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (id.isBlank() || name.isBlank()) null else PlaylistCategory(id = id, name = name, group = "Spotify", source = "spotify")
        }
        return categories.ifEmpty { FALLBACK_CATEGORIES }
    }

    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        val config = configOf(cookie)
        val categoryId = id.ifBlank { "pop" }
        val (offset, pageSize) = normalizePage(page, limit, 20, 50)
        val query = mapOf("country" to config.marketOrDefault(), "limit" to pageSize.toString(), "offset" to offset.toString())
        val response = runCatching { config.getJson("/browse/categories/${urlEncode(categoryId)}/playlists", query) }.getOrNull()
        if (response != null) {
            val playlists = response["playlists"]?.jsonObject?.get("items")?.jsonArray?.mapNotNull { it.jsonObject }
                ?.map { playlistFromApi(it) }
                .orEmpty()
            if (playlists.isNotEmpty()) return playlists
        }
        // 分类目录在很多地区不可用，退回按分类名搜歌单
        val name = FALLBACK_CATEGORIES.firstOrNull { it.id == categoryId }?.name ?: categoryId
        return searchPlaylists(cookie, name).take(pageSize)
    }

    override suspend fun userPlaylists(cookie: String, page: Int, limit: Int): List<Playlist> {
        val config = configOf(cookie)
        val (offset, pageSize) = normalizePage(page, limit, 20, 50)
        val query = mapOf("limit" to pageSize.toString(), "offset" to offset.toString())
        val response = config.getJson("/me/playlists", query, userOnly = true)
            ?: throw IllegalStateException("spotify 个人歌单为空")
        return response["items"]?.jsonArray?.mapNotNull { it.jsonObject }
            ?.filter { it["id"]?.jsonPrimitive?.content?.isNotBlank() == true }
            ?.map { playlistFromApi(it) }
            .orEmpty()
    }

    override suspend fun topLists(cookie: String): List<TopList> = TOP_LISTS.map { definition ->
        TopList(
            id = definition.id,
            name = definition.name,
            description = definition.description,
            updateFrequency = "每日更新",
            trackCount = 50,
            source = "spotify",
            link = "$OPEN_BASE/playlist/${definition.playlistId}",
        )
    }

    override suspend fun topListSongs(cookie: String, id: String): List<Song> {
        val definition = resolveTopList(id)
        val songs = playlistSongs(cookie, definition.playlistId)
        return songs.mapIndexed { index, song ->
            song.copy(extra = song.extra + mapOf("rank" to (index + 1).toString(), "toplist_id" to definition.id))
        }
    }

    override suspend fun hotSongs(cookie: String, limit: Int): List<Song> {
        val safeLimit = if (limit <= 0) 50 else limit.coerceAtMost(50)
        val songs = playlistSongs(cookie, TOP_LISTS.first().playlistId)
        return songs.take(safeLimit).mapIndexed { index, song ->
            song.copy(extra = song.extra + mapOf("rank" to (index + 1).toString(), "toplist_id" to TOP_LISTS.first().id))
        }
    }

    // ---- 转换 ----

    private fun songFromApiTrack(track: JsonObject): Song {
        val album = track["album"]?.jsonObject
        val artists = track["artists"]?.jsonArray?.mapNotNull { it.jsonObject?.get("name")?.jsonPrimitive?.content?.trim() }
            ?.filter { it.isNotBlank() }?.distinct().orEmpty()
        val albumArtists = album?.get("artists")?.jsonArray?.mapNotNull { it.jsonObject?.get("name")?.jsonPrimitive?.content?.trim() }
            ?.filter { it.isNotBlank() }?.distinct().orEmpty()
        val extra = buildMap {
            put("type", "track")
            track["id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }?.let { put("spotify_id", it) }
            track["track_number"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }?.let { put("track_number", it.toString()) }
            track["disc_number"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }?.let { put("disc_number", it.toString()) }
            if (track["explicit"]?.jsonPrimitive?.content == "true") put("explicit", "true")
            track["external_ids"]?.jsonObject?.get("isrc")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }?.let { put("isrc", it) }
            put("preview_only", "true")
        }
        return Song(
            id = track["id"]?.jsonPrimitive?.content?.trim().orEmpty(),
            name = track["name"]?.jsonPrimitive?.content?.trim().orEmpty(),
            artist = artists.joinToString(", ").ifBlank { albumArtists.joinToString(", ") },
            album = album?.get("name")?.jsonPrimitive?.content?.trim().orEmpty(),
            albumId = album?.get("id")?.jsonPrimitive?.content?.trim().orEmpty(),
            duration = (track["duration_ms"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) / 1000,
            cover = bestCover(album?.get("images")?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()),
            url = track["preview_url"]?.jsonPrimitive?.content?.trim().orEmpty(),
            ext = "mp3",
            source = "spotify",
            link = track["external_urls"]?.jsonObject?.get("spotify")?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() } ?: "$OPEN_BASE/track/${track["id"]?.jsonPrimitive?.content?.trim().orEmpty()}",
            extra = extra,
        )
    }

    private fun songFromWebPlayerTrack(track: JsonObject): Song? {
        val id = idFromUri(track["uri"]?.jsonPrimitive?.content, "track") ?: return null
        val name = track["name"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (name.isBlank()) return null
        val album = track["albumOfTrack"]?.jsonObject
        val previews = track["previews"]?.jsonObject?.get("audioPreviews")?.jsonObject?.get("items")?.jsonArray
            ?.mapNotNull { it.jsonObject?.get("url")?.jsonPrimitive?.content?.trim() }
            ?.firstOrNull { it.isNotBlank() }
            .orEmpty()
        val extra = buildMap {
            put("type", "track")
            put("spotify_id", id)
            put("preview_only", "true")
            track["trackNumber"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }?.let { put("track_number", it.toString()) }
            track["discNumber"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }?.let { put("disc_number", it.toString()) }
            if (track["contentRating"]?.jsonObject?.get("label")?.jsonPrimitive?.content?.equals("EXPLICIT", true) == true) {
                put("explicit", "true")
            }
            if (track["playability"]?.jsonObject?.get("playable")?.jsonPrimitive?.content == "false") put("playable", "false")
        }
        return Song(
            id = id,
            name = name,
            artist = webPlayerArtists(track["artists"]?.jsonObject),
            album = album?.get("name")?.jsonPrimitive?.content?.trim().orEmpty(),
            albumId = idFromUri(album?.get("uri")?.jsonPrimitive?.content, "album").orEmpty(),
            duration = (track["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.content?.toIntOrNull() ?: 0) / 1000,
            cover = bestCover(album?.get("coverArt")?.jsonObject?.get("sources")?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()),
            url = previews,
            ext = "mp3",
            source = "spotify",
            link = "$OPEN_BASE/track/$id",
            extra = extra,
        )
    }

    private fun songsFromEmbed(entity: EmbedEntity): List<Song> {
        val cover = bestEmbedCover(entity.coverArt.sources).ifBlank { bestEmbedCover(entity.visualIdentity.image) }
        return entity.trackList.mapNotNull { item ->
            val uri = item.uri.trim()
            val id = uri.removePrefix("spotify:track:")
            val name = cleanEmbedText(item.title)
            if (id.isBlank() || id == uri || name.isBlank()) return@mapNotNull null
            Song(
                id = id,
                name = name,
                artist = cleanEmbedText(item.subtitle),
                duration = item.duration / 1000,
                cover = cover,
                url = item.audioPreview?.url?.trim().orEmpty(),
                ext = "mp3",
                source = "spotify",
                link = "$OPEN_BASE/track/$id",
                extra = buildMap {
                    put("type", "track")
                    put("spotify_id", id)
                    put("preview_only", "true")
                    if (item.isExplicit) put("explicit", "true")
                    if (!item.isPlayable) put("playable", "false")
                },
            )
        }
    }

    private fun songFromEmbed(entity: EmbedEntity): Song? {
        if (!entity.type.equals("track", ignoreCase = true)) return null
        val uri = entity.uri.trim()
        var id = uri.removePrefix("spotify:track:")
        if (entity.id.isNotBlank()) id = entity.id.trim()
        if (id.isBlank() || id == uri) return null
        val name = cleanEmbedText(entity.name).ifBlank { cleanEmbedText(entity.title) }
        if (name.isBlank()) return null
        val artist = artistNames(entity.artists).ifBlank { cleanEmbedText(entity.subtitle) }
        val cover = bestEmbedCover(entity.visualIdentity.image).ifBlank { bestEmbedCover(entity.coverArt.sources) }
        return Song(
            id = id,
            name = name,
            artist = artist,
            duration = entity.duration / 1000,
            cover = cover,
            url = entity.audioPreview?.url?.trim().orEmpty(),
            ext = "mp3",
            source = "spotify",
            link = "$OPEN_BASE/track/$id",
            extra = mapOf("type" to "track", "spotify_id" to id, "preview_only" to "true"),
        )
    }

    private fun playlistFromApi(playlist: JsonObject): Playlist {
        val tracks = playlist["tracks"]?.jsonObject
        val items = playlist["items"]?.jsonObject
        val count = tracks?.get("total")?.jsonPrimitive?.content?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: items?.get("total")?.jsonPrimitive?.content?.toIntOrNull()
            ?: 0
        val id = playlist["id"]?.jsonPrimitive?.content?.trim().orEmpty()
        return Playlist(
            id = id,
            name = playlist["name"]?.jsonPrimitive?.content?.trim().orEmpty(),
            cover = bestCover(playlist["images"]?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()),
            trackCount = count,
            creator = playlist["owner"]?.jsonObject?.get("display_name")?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: playlist["owner"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.trim().orEmpty(),
            description = playlist["description"]?.jsonPrimitive?.content?.trim().orEmpty(),
            source = "spotify",
            link = playlist["external_urls"]?.jsonObject?.get("spotify")?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() } ?: "$OPEN_BASE/playlist/$id",
            extra = buildMap {
                put("type", "playlist")
                put("spotify_id", id)
                playlist["owner"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }?.let { put("owner_id", it) }
                playlist["snapshot_id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }?.let { put("snapshot_id", it) }
            },
        )
    }

    private fun playlistFromWebPlayer(playlist: JsonObject): Playlist? {
        val id = idFromUri(playlist["uri"]?.jsonPrimitive?.content, "playlist") ?: return null
        val name = playlist["name"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (name.isBlank()) return null
        val images = playlist["images"]?.jsonObject?.get("items")?.jsonArray
            ?.mapNotNull { it.jsonObject?.get("sources")?.jsonArray?.mapNotNull { source -> source.jsonObject } }
            ?.firstOrNull()
            .orEmpty()
        val owner = playlist["ownerV2"]?.jsonObject ?: playlist["owner"]?.jsonObject
        val ownerName = owner?.get("data")?.jsonObject?.get("name")?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: owner?.get("name")?.jsonPrimitive?.content?.trim().orEmpty()
        val ownerUri = owner?.get("data")?.jsonObject?.get("uri")?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: owner?.get("uri")?.jsonPrimitive?.content?.trim().orEmpty()
        return Playlist(
            id = id,
            name = name,
            cover = bestCover(images),
            trackCount = playlist["content"]?.jsonObject?.get("totalCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            creator = ownerName,
            description = playlist["description"]?.jsonPrimitive?.content?.trim().orEmpty(),
            source = "spotify",
            link = "$OPEN_BASE/playlist/$id",
            extra = buildMap {
                put("type", "playlist")
                put("spotify_id", id)
                if (ownerUri.isNotBlank()) put("owner_id", ownerUri.removePrefix("spotify:user:"))
            },
        )
    }

    private fun albumFromApi(album: JsonObject): Playlist {
        val id = album["id"]?.jsonPrimitive?.content?.trim().orEmpty()
        val releaseDate = album["release_date"]?.jsonPrimitive?.content?.trim().orEmpty()
        return Playlist(
            id = id,
            name = album["name"]?.jsonPrimitive?.content?.trim().orEmpty(),
            cover = bestCover(album["images"]?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()),
            trackCount = album["total_tracks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            creator = album["artists"]?.jsonArray?.mapNotNull { it.jsonObject?.get("name")?.jsonPrimitive?.content?.trim() }
                ?.filter { it.isNotBlank() }?.distinct()?.joinToString(", ").orEmpty(),
            description = album["label"]?.jsonPrimitive?.content?.trim().orEmpty(),
            source = "spotify",
            link = album["external_urls"]?.jsonObject?.get("spotify")?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() } ?: "$OPEN_BASE/album/$id",
            extra = buildMap {
                put("type", "album")
                put("spotify_id", id)
                album["album_type"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }?.let { put("album_type", it) }
                if (releaseDate.isNotBlank()) put("release_date", releaseDate)
            },
        )
    }

    private fun albumFromWebPlayer(album: JsonObject): Playlist? {
        val id = idFromUri(album["uri"]?.jsonPrimitive?.content, "album") ?: return null
        val name = album["name"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (name.isBlank()) return null
        val date = album["date"]?.jsonObject
        val releaseDate = date?.get("isoString")?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: date?.get("year")?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }?.toString()
            .orEmpty()
        return Playlist(
            id = id,
            name = name,
            cover = bestCover(album["coverArt"]?.jsonObject?.get("sources")?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()),
            trackCount = album["tracks"]?.jsonObject?.get("totalCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            creator = webPlayerArtists(album["artists"]?.jsonObject),
            description = releaseDate,
            source = "spotify",
            link = "$OPEN_BASE/album/$id",
            extra = buildMap {
                put("type", "album")
                put("spotify_id", id)
                if (releaseDate.isNotBlank()) put("release_date", releaseDate)
            },
        )
    }

    private fun webPlayerArtists(artists: JsonObject?): String =
        artists?.get("items")?.jsonArray
            ?.mapNotNull { it.jsonObject?.get("profile")?.jsonObject?.get("name")?.jsonPrimitive?.content?.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.joinToString(", ")
            .orEmpty()

    private fun artistNames(artists: List<EmbedEntity.EmbedArtist>): String =
        artists.map { it.name.trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")

    private fun bestCover(images: List<JsonObject>): String {
        var best = ""
        var bestWidth = -1
        images.forEach { obj ->
            val url = obj["url"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (url.isBlank()) return@forEach
            val width = obj["width"]?.jsonPrimitive?.content?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: obj["height"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?.takeIf { it > 0 }
                ?: 0
            if (width > bestWidth) {
                best = url
                bestWidth = width
            }
        }
        return best
    }

    private fun bestEmbedCover(images: List<EmbedImage>): String {
        var best = ""
        var bestWidth = -1
        images.forEach { image ->
            val url = image.url.trim()
            if (url.isBlank()) return@forEach
            val width = if (image.width > 0) image.width else if (image.height > 0) image.height else 0
            if (width > bestWidth) {
                best = url
                bestWidth = width
            }
        }
        return best
    }

    private fun cleanEmbedText(value: String): String =
        value.replace('\u00a0', ' ').trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")

    // ---- kworb 榜单镜像：官方歌单接口不可用时按国家拿 Top 50 ----

    private suspend fun chartTracks(playlistId: String, country: String): List<Song> {
        val response = NativeHttp.getResponse(
            "$KWORB_BASE/spotify/country/${urlEncode(country)}_daily.html",
            headers = mapOf("Accept" to "text/html,application/xhtml+xml"),
            userAgent = USER_AGENT,
        )
        if (response.code != 200) throw IllegalStateException("spotify 榜单镜像 ${response.code}")
        val entries = parseKworbChart(response.body)
        val cover = runCatching {
            fetchOEmbed(playlistId)?.let { bestCover(listOf(buildJsonObject { put("url", it.thumbnail_url) })) }.orEmpty()
        }.getOrNull().orEmpty()
        return entries.take(CHART_TRACK_LIMIT).map { entry ->
            Song(
                id = entry.id,
                name = entry.title,
                artist = entry.artist,
                cover = cover,
                source = "spotify",
                link = "$OPEN_BASE/track/${entry.id}",
                extra = buildMap {
                    put("type", "track")
                    put("spotify_id", entry.id)
                    put("preview_only", "true")
                    put("rank", entry.rank.toString())
                    if (entry.date.isNotBlank()) put("chart_date", entry.date)
                },
            )
        }
    }

    private class KworbEntry(val rank: Int, val id: String, val title: String, val artist: String, val date: String)

    private fun parseKworbChart(document: String): List<KworbEntry> {
        val rowPattern = Regex("(?is)<tr>\\s*<td class=\"np\">(\\d+)</td>.*?</tr>")
        val trackPattern = Regex("(?is)<a[^>]+href=\"(?:\\.\\./)?track/([A-Za-z0-9_-]+)\\.html\"[^>]*>(.*?)</a>")
        val artistPattern = Regex("(?is)<a[^>]+href=\"(?:\\.\\./)?artist/[^\"]+\\.html\"[^>]*>(.*?)</a>")
        val datePattern = Regex("(?is)<span class=\"pagetitle\">[^<]*?(\\d{4}/\\d{2}/\\d{2})")
        val htmlTagPattern = Regex("(?is)<[^>]+>")
        val entries = mutableListOf<KworbEntry>()
        val chartDate = datePattern.find(document)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        for (row in rowPattern.findAll(document)) {
            val rank = row.groupValues[1].trim().toIntOrNull() ?: continue
            val track = trackPattern.find(row.value) ?: continue
            val title = cleanChartText(track.groupValues[2], htmlTagPattern)
            if (title.isBlank()) continue
            val artists = artistPattern.findAll(row.value)
                .map { cleanChartText(it.groupValues[1], htmlTagPattern) }
                .filter { it.isNotBlank() }
                .distinct()
            entries += KworbEntry(rank, track.groupValues[1].trim(), title, artists.joinToString(", "), chartDate)
        }
        if (entries.isEmpty()) throw IllegalStateException("spotify 榜单镜像没有解析出曲目")
        return entries
    }

    private fun cleanChartText(value: String, htmlTagPattern: Regex): String =
        htmlTagPattern.replace(value, " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")

    @Serializable
    private data class OEmbedResponse(val title: String = "", val thumbnail_url: String = "")

    private suspend fun fetchOEmbed(playlistId: String): OEmbedResponse? {
        val endpoints = listOf("$EMBED_BASE/oembed", "$OPEN_BASE/oembed")
        val results = coroutineScope {
            endpoints.map { endpoint ->
                async {
                    runCatching {
                        val url = "$endpoint?url=${urlEncode("spotify:playlist:$playlistId")}"
                        val response = NativeHttp.getResponse(url, headers = mapOf("Accept" to "application/json"), userAgent = USER_AGENT)
                        if (response.code != 200) return@runCatching null
                        json.decodeFromString<OEmbedResponse>(response.body).takeIf { it.title.isNotBlank() }
                    }.getOrNull()
                }
            }.awaitAll()
        }
        return results.firstOrNull { it != null }
    }

    // ---- 工具 ----

    private class TopListDefinition(
        val id: String,
        val playlistId: String,
        val name: String,
        val description: String,
        val chartCountry: String,
    )

    private fun resolveTopList(topListId: String): TopListDefinition {
        val raw = topListId.trim()
        if (raw.isBlank()) throw IllegalStateException("spotify 榜单 id 为空")
        TOP_LISTS.firstOrNull { raw == it.id || raw == it.playlistId || raw == "playlist:${it.playlistId}" }?.let { return it }
        if (raw.startsWith("playlist:")) {
            val playlistId = runCatching { extractSpotifyId(raw.removePrefix("playlist:"), "playlist") }.getOrNull()
            if (playlistId != null) {
                return TopListDefinition(raw, playlistId, "Spotify 歌单", "Spotify 编辑精选", "")
            }
        }
        if (raw.contains("open.spotify.com")) {
            val playlistId = runCatching { extractSpotifyId(raw, "playlist") }.getOrNull()
            if (playlistId != null) {
                return TopListDefinition("playlist:$playlistId", playlistId, "Spotify 歌单", "Spotify 编辑精选", "")
            }
        }
        throw IllegalStateException("不支持的 spotify 榜单 id: $topListId")
    }

    private fun chartCountryOf(playlistId: String): String? =
        TOP_LISTS.firstOrNull { it.playlistId == playlistId && it.chartCountry.isNotBlank() }?.chartCountry

    private fun topListPlaylists(): List<Playlist> = TOP_LISTS.map { definition ->
        Playlist(
            id = definition.playlistId,
            name = definition.name,
            creator = "Spotify",
            description = definition.description,
            source = "spotify",
            link = "$OPEN_BASE/playlist/${definition.playlistId}",
            extra = mapOf("type" to "playlist", "spotify_id" to definition.playlistId),
        )
    }

    private fun lrclibText(raw: String): String = raw.trim()

    private suspend fun spotifyPublicGet(endpoint: String, query: Map<String, String>): NativeResponse {
        val url = if (query.isEmpty()) endpoint else "$endpoint?" + query.entries.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
        return NativeHttp.getResponse(url, headers = mapOf("Accept" to "application/json"), userAgent = USER_AGENT)
    }

    private fun allowsAnonymousFallback(error: Throwable): Boolean {
        if (error is SpotifyApiException) {
            return error.status == 0 || error.status == 401 || error.status == 403 || error.status == 429
        }
        return false
    }

    private fun allowsPublicFallback(error: Throwable): Boolean {
        if (error is SpotifyApiException) {
            return error.status == 0 || error.status == 401 || error.status == 403 || error.status == 404 || error.status == 429
        }
        return false
    }

    private fun normalizePage(page: Int, limit: Int, defaultLimit: Int, maxLimit: Int): Pair<Int, Int> {
        val safePage = if (page <= 0) 1 else page
        val safeLimit = when {
            limit <= 0 -> defaultLimit
            limit > maxLimit -> maxLimit
            else -> limit
        }
        return (safePage - 1) * safeLimit to safeLimit
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun randomUuid(): String {
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        bytes[6] = (bytes[6].toInt() and 0x0f or 0x40).toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
        val hex = NativeCrypto.bytesToHex(bytes)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    /** TOTP：上游按版本轮换密钥，逐个试到能用为止。 */
    private class TotpSecret(val version: Int, val values: ByteArray)

    private fun generateTotp(secret: ByteArray): String {
        val transformed = ByteArray(secret.size) { index -> (secret[index].toInt() xor (index % 33 + 9)).toByte() }
        val key = transformed.joinToString("") { (it.toInt() and 0xFF).toString() }
        val counter = System.currentTimeMillis() / 1000L / 30L
        val message = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA1"))
        val digest = mac.doFinal(message)
        val offset = digest[digest.size - 1].toInt() and 0x0f
        val raw = ByteBuffer.wrap(digest, offset, 4).order(ByteOrder.BIG_ENDIAN).int
        val value = (raw.toLong() and 0xFFFFFFFFL) and 0x7FFFFFFFL
        return String.format("%06d", value % 1_000_000L)
    }

    private fun appServerClientVersion(document: String): String? {
        val marker = """<script id="appServerConfig" type="text/plain">"""
        val start = document.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = document.indexOf("</script>", from)
        if (end < 0) return null
        val encoded = document.substring(from, end).trim()
        val decoded = runCatching { Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val config = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null
        return config["clientVersion"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun nextDataPayload(document: String): String? {
        val marker = """id="__NEXT_DATA__"""
        val markerIndex = document.indexOf(marker)
        if (markerIndex < 0) return null
        val scriptStart = document.lastIndexOf("<script", markerIndex)
        if (scriptStart < 0) return null
        val tagEnd = document.indexOf('>', markerIndex)
        if (tagEnd < 0) return null
        val payloadStart = tagEnd + 1
        val payloadEnd = document.indexOf("</script>", payloadStart)
        if (payloadEnd < 0) return null
        return document.substring(payloadStart, payloadEnd).trim().takeIf { it.isNotBlank() }
    }

    private fun spotifyErrorMessage(body: String): String {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = parsed?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content?.trim()
            ?: parsed?.get("message")?.jsonPrimitive?.content?.trim()
        val text = message?.takeIf { it.isNotBlank() } ?: body.trim().take(500)
        return text.ifBlank { "未知错误" }
    }

    @Serializable
    private data class SpotifyTokenResponse(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String = "",
        @kotlinx.serialization.SerialName("token_type") val tokenType: String = "",
        @kotlinx.serialization.SerialName("expires_in") val expiresIn: Int = 0,
        @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String = "",
    )

    @Serializable
    private data class LrclibLyrics(val syncedLyrics: String = "", val plainLyrics: String = "")

    private companion object {
        const val API_BASE = "https://api.spotify.com/v1"
        const val ACCOUNTS_BASE = "https://accounts.spotify.com"
        const val OPEN_BASE = "https://open.spotify.com"
        const val EMBED_BASE = "https://embed.spotify.com"
        const val CLIENT_TOKEN_BASE = "https://clienttoken.spotify.com"
        const val PARTNER_BASE = "https://api-partner.spotify.com"
        const val TOKEN_API = "$ACCOUNTS_BASE/api/token"
        const val LRCLIB_API = "https://lrclib.net/api"
        const val KWORB_BASE = "https://kworb.net"
        const val DEFAULT_MARKET = "US"
        const val USER_AGENT = "go-music-dl-spotify/1.0"
        const val WEB_PLAYER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        const val SEARCH_QUERY_HASH = "fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c"
        const val SEARCH_LIMIT = 30
        const val PLAYLIST_PAGE_SIZE = 50
        const val ALBUM_PAGE_SIZE = 50
        const val CATEGORY_LIMIT = 50
        const val CHART_TRACK_LIMIT = 50
        const val SPOTIFY_PROBE_SONG_ID = "2sFepQNyl1WjpROruP8bJ0"
        const val TOKEN_SAFETY_MS = 30_000L
        const val ANONYMOUS_TOKEN_TTL_MS = 45 * 60 * 1000L
        const val ANONYMOUS_AUTH_MARGIN_MS = 2 * 60 * 1000L
        const val SEARCH_CACHE_TTL_MS = 60_000L
        const val SEARCH_CACHE_SIZE = 64

        val SPOTIFY_PREVIEW_QUALITY = AudioQualityOption(
            value = AudioQuality.Preview,
            label = "试听片段",
            format = "m4a",
        )

        val TOTP_SECRETS = listOf(
            TotpSecret(
                61,
                byteArrayOf(
                    44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97,
                    75, 76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78,
                ),
            ),
            TotpSecret(
                60,
                byteArrayOf(
                    79, 109, 69, 123, 90, 65, 46, 74, 94, 34, 58, 48, 70, 71,
                    92, 85, 122, 63, 91, 64, 87, 87,
                ),
            ),
            TotpSecret(
                59,
                byteArrayOf(
                    123, 105, 79, 70, 110, 59, 52, 125, 60, 49, 80, 70, 89, 75,
                    80, 86, 63, 53, 123, 37, 117, 49, 52, 93, 77, 62, 47, 86,
                    48, 104, 68, 72,
                ),
            ),
        )

        val TOP_LISTS = listOf(
            TopListDefinition("global-top-50", "37i9dQZEVXbMDoHDwVN2tF", "Global Top 50", "Spotify 全球热门歌曲 Top 50", "global"),
            TopListDefinition("us-top-50", "37i9dQZEVXbLRQDuF5jeBp", "United States Top 50", "Spotify 美国热门歌曲 Top 50", "us"),
            TopListDefinition("uk-top-50", "37i9dQZEVXbLnolsZ8PSNw", "United Kingdom Top 50", "Spotify 英国热门歌曲 Top 50", "gb"),
            TopListDefinition("jp-top-50", "37i9dQZEVXbKXQ4mDTEBXq", "Japan Top 50", "Spotify 日本热门歌曲 Top 50", "jp"),
            TopListDefinition("au-top-50", "37i9dQZEVXbJPcfkRz0wJ0", "Australia Top 50", "Spotify 澳大利亚热门歌曲 Top 50", "au"),
            TopListDefinition("ca-top-50", "37i9dQZEVXbKj23U1GF4IR", "Canada Top 50", "Spotify 加拿大热门歌曲 Top 50", "ca"),
        )

        val FALLBACK_CATEGORIES = listOf(
            PlaylistCategory(id = "pop", name = "流行", group = "曲风", source = "spotify", hot = true),
            PlaylistCategory(id = "hiphop", name = "嘻哈", group = "曲风", source = "spotify", hot = true),
            PlaylistCategory(id = "rock", name = "摇滚", group = "曲风", source = "spotify", hot = true),
            PlaylistCategory(id = "indie", name = "独立音乐", group = "曲风", source = "spotify"),
            PlaylistCategory(id = "dance", name = "舞曲", group = "曲风", source = "spotify", hot = true),
            PlaylistCategory(id = "rnb", name = "R&B", group = "曲风", source = "spotify"),
            PlaylistCategory(id = "chill", name = "轻松", group = "场景", source = "spotify"),
            PlaylistCategory(id = "workout", name = "运动", group = "场景", source = "spotify"),
            PlaylistCategory(id = "party", name = "派对", group = "场景", source = "spotify"),
            PlaylistCategory(id = "sleep", name = "助眠", group = "场景", source = "spotify"),
            PlaylistCategory(id = "country", name = "乡村", group = "曲风", source = "spotify"),
            PlaylistCategory(id = "latin", name = "拉丁", group = "曲风", source = "spotify"),
        )
    }
}

// ---- 包级工具：ID 解析与凭据解析，多个入口都要用 ----

/** 接受裸 ID、`spotify:track:xxx`、open/play.spotify.com 链接与 embed/intl- 前缀。 */
internal fun extractSpotifyId(raw: String, expectedType: String): String {
    val value = raw.trim()
    if (value.isBlank()) throw IllegalArgumentException("spotify $expectedType id 为空")
    if (value.startsWith("spotify:", ignoreCase = true)) {
        val parts = value.split(":")
        if (parts.size < 3) throw IllegalArgumentException("无效的 spotify $expectedType uri: $value")
        val idType = parts[1].trim().lowercase()
        if (idType != expectedType) throw IllegalArgumentException("期望 spotify $expectedType，实际是 $idType")
        val id = parts[2].trim()
        if (id.isBlank()) throw IllegalArgumentException("spotify $expectedType id 为空")
        return id
    }
    if (!value.contains("/") && !value.contains(":") && !value.contains("?")) return value
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: throw IllegalArgumentException("无效的 spotify $expectedType 链接: $value")
    val host = (uri.host ?: "").lowercase().removePrefix("www.")
    if (host != "open.spotify.com" && host != "play.spotify.com") {
        throw IllegalArgumentException("无效的 spotify $expectedType 链接: $value")
    }
    val segments = (uri.path ?: "").split("/").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
    while (segments.isNotEmpty() && (segments[0] == "embed" || segments[0].startsWith("intl-"))) {
        segments.removeAt(0)
    }
    if (segments.size < 2) throw IllegalArgumentException("无效的 spotify $expectedType 链接: $value")
    if (segments[0].lowercase() != expectedType) {
        throw IllegalArgumentException("期望 spotify $expectedType，实际是 ${segments[0].lowercase()}")
    }
    val id = segments[1].trim()
    if (id.isBlank()) throw IllegalArgumentException("spotify $expectedType id 为空")
    return id
}

internal fun idFromUri(uri: String?, entityType: String): String? =
    runCatching { extractSpotifyId(uri.orEmpty(), entityType) }.getOrNull()

/** 凭据既接受 `client_id=..;client_secret=..`，也接受 JSON。 */
internal fun parseSpotifyConfig(raw: String): Map<String, String> {
    val value = raw.trim()
    if (value.isBlank()) return emptyMap()
    if (value.startsWith("{")) {
        val parsed = runCatching { NativeHttp.json.parseToJsonElement(value).jsonObject }.getOrNull() ?: return emptyMap()
        return parsed.entries.mapNotNull { (key, element) ->
            val primitive = element as? JsonPrimitive ?: return@mapNotNull null
            normalizeSpotifyConfigKey(key) to primitive.content.trim()
        }.toMap()
    }
    val values = mutableMapOf<String, String>()
    value.replace("&", ";").replace("\n", ";").replace("\r", ";")
        .split(";")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { item ->
            val parts = item.split("=", limit = 2)
            if (parts.size != 2) return@forEach
            values[normalizeSpotifyConfigKey(parts[0])] = parts[1].trim().trim('"', '\'')
        }
    return values
}

internal fun normalizeSpotifyConfigKey(key: String): String =
    key.trim().lowercase().replace("-", "_").replace(" ", "_")
