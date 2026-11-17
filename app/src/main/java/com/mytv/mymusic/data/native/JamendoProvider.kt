package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.AlbumDetail
import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

private val JAMENDO_INFO = ProviderInfo(
    id = "jamendo",
    name = "Jamendo",
    search = true,
    download = true,
    lyrics = false,
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
    playlistCategories = false,
    topLists = false,
    topListSongs = false,
    hotSongs = false,
    unstable = "playlist-search",
)

class JamendoProvider : BaseNativeProvider(JAMENDO_INFO) {
    private val json = NativeHttp.json
    private val availableQualityCache = ConcurrentHashMap<String, List<AudioQualityOption>>()

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val body = searchByType(cookie, keyword, "track")
        val results = json.decodeFromString<List<JamendoTrackItem>>(body)
        return results.mapNotNull { buildSong(it, JamendoTrackMeta()) }
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val match = TRACK_LINK.find(link) ?: throw IllegalArgumentException("invalid jamendo link")
        return getTrackByID(cookie, match.groupValues[1], JamendoTrackMeta())
    }

    override suspend fun downloadUrl(cookie: String, song: Song, quality: AudioQuality): String {
        if (song.source != "jamendo") throw IllegalArgumentException("source mismatch")
        if (quality == AudioQuality.Auto) {
            if (song.url.isNotEmpty()) return song.url

            val trackId = song.extra["track_id"]?.takeIf { it.isNotEmpty() } ?: song.id
            if (trackId.isEmpty()) throw IllegalArgumentException("id missing")

            val info = getTrackByID(
                cookie,
                trackId,
                JamendoTrackMeta(
                    artistName = song.artist,
                    albumName = song.album,
                    albumId = song.albumId,
                ),
            )
            return info.url
        }

        // 指定档位：只取这一档地址并确认能访问，接口没给或 404 直接报错
        val candidate = jamendoQualityCandidateFor(quality)
            ?: throw IllegalArgumentException("unsupported jamendo audio quality: $quality")
        val trackId = (song.extra["track_id"]?.takeIf { it.isNotEmpty() } ?: song.id).trim()
        if (trackId.isEmpty()) throw IllegalArgumentException("jamendo track id is empty")
        val item = fetchTrackItem(cookie, trackId)
        val streamUrl = jamendoQualityUrl(item, candidate)
        if (streamUrl.isEmpty()) throw IllegalStateException("jamendo audio quality $quality is unavailable")
        jamendoProbeStream(streamUrl)
        return streamUrl
    }

    /**
     * 探测账号能拿到的档：Jamendo 会挂出 mp33 地址但存储后端对该曲 404，所以必须真请求一遍。
     * 抓一批曲目、每档逐个试，有一首能通就算这一档可用。
     */
    override suspend fun availableQualityOptions(cookie: String): List<AudioQualityOption> {
        availableQualityCache[cookie]?.let { return it }
        val items = runCatching { jamendoQualityProbeItems(cookie) }.getOrNull() ?: return emptyList()
        val options = buildList {
            for (candidate in JAMENDO_QUALITY_CANDIDATES) {
                for (item in items) {
                    val streamUrl = jamendoQualityUrl(item, candidate)
                    if (streamUrl.isEmpty()) continue
                    if (runCatching { jamendoProbeStream(streamUrl) }.isSuccess) {
                        add(candidate.option)
                        break
                    }
                }
            }
        }
        availableQualityCache[cookie] = options
        return options
    }

    private suspend fun jamendoQualityProbeItems(cookie: String): List<JamendoTrackItem> {
        val body = searchByType(cookie, JAMENDO_QUALITY_PROBE_KEYWORD, "track")
        return json.decodeFromString<List<JamendoTrackItem>>(body).take(8)
    }

    private suspend fun fetchTrackItem(cookie: String, id: String): JamendoTrackItem {
        val body = apiGet(cookie, "$TRACK_API?" + encodeParams(mapOf("id" to id)), TRACK_API_PATH)
        val results = json.decodeFromString<List<JamendoTrackItem>>(body)
        if (results.isEmpty()) throw IllegalStateException("track not found")
        return results[0]
    }

    /** 指定档位时不能走基类的 song.url 短路（那是自动选的最高档），必须重新按档取流。 */
    override suspend fun playableStream(cookie: String, song: Song, workDir: File, quality: AudioQuality): PlayableStream {
        val url = if (quality == AudioQuality.Auto) {
            song.url.ifBlank { downloadUrl(cookie, song, AudioQuality.Auto) }
        } else {
            downloadUrl(cookie, song, quality)
        }
        val option = jamendoQualityCandidateFor(quality)?.option
        return PlayableStream(
            url = url,
            ext = option?.format ?: song.ext.ifBlank { extensionFromUrl(url) },
            bitrate = option?.bitrate?.takeIf { it > 0 } ?: song.bitrate,
        )
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val body = searchByType(cookie, keyword, "playlist")
        val results = json.decodeFromString<List<JamendoPlaylistItem>>(body)
        return results.mapNotNull { item ->
            if (item.id == 0) return@mapNotNull null
            val playlistId = item.id.toString()
            Playlist(
                id = playlistId,
                name = item.name,
                cover = item.image,
                creator = item.userName,
                source = "jamendo",
                link = playlistLink(playlistId),
            )
        }
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> {
        val playlistItem = getPlaylistByID(cookie, id)
        return fetchPlaylistTracks(cookie, playlistItem)
    }

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val match = PLAYLIST_LINK.find(link)
        if (match != null) return fetchPlaylistDetail(cookie, match.groupValues[1])
        if (link.isNotEmpty() && !link.contains("/")) return fetchPlaylistDetail(cookie, link)
        throw IllegalArgumentException("invalid jamendo playlist link")
    }

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val body = searchByType(cookie, keyword, "album")
        val results = json.decodeFromString<List<JamendoAlbumSearchItem>>(body)
        val albums = results.mapNotNull { item ->
            if (item.id == 0) return@mapNotNull null
            val albumId = item.id.toString()
            val extra = buildMap {
                put("album_id", albumId)
                if (item.artist.id > 0) put("artist_id", item.artist.id.toString())
            }
            Playlist(
                id = albumId,
                name = item.name,
                cover = item.cover.big.size300,
                creator = item.artist.name,
                source = "jamendo",
                link = albumLink(albumId),
                extra = extra,
            )
        }
        if (albums.isEmpty()) throw IllegalStateException("no albums found")
        return albums
    }

    override suspend fun albumSongs(cookie: String, id: String): List<Song> =
        fetchAlbumDetail(cookie, id).songs

    override suspend fun albumDetail(cookie: String, id: String): AlbumDetail {
        val result = fetchAlbumDetail(cookie, id)
        return AlbumDetail(result.item.toLooseAlbumSummary(), result.songs)
    }

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult {
        val match = ALBUM_LINK.find(link) ?: throw IllegalArgumentException("invalid jamendo album link")
        return fetchAlbumDetail(cookie, match.groupValues[1])
    }

    private suspend fun fetchAlbumDetail(cookie: String, id: String): CollectionResult {
        val albumItem = getAlbumByID(cookie, id)
        val creator = try {
            getArtistNameByID(cookie, albumItem.artistId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ""
        }

        val extra = buildMap {
            put("album_id", albumItem.id.toString())
            if (albumItem.artistId > 0) put("artist_id", albumItem.artistId.toString())
        }
        val songs = fetchAlbumTracks(cookie, albumItem, creator)
        val album = Playlist(
            id = albumItem.id.toString(),
            name = albumItem.name,
            cover = albumItem.cover.big.size300,
            trackCount = albumItem.tracks.size,
            creator = if (creator.isEmpty() && songs.isNotEmpty()) songs[0].artist else creator,
            description = pickJamendoDescription(albumItem.description),
            source = "jamendo",
            link = albumLink(albumItem.id.toString()),
            extra = extra,
        )
        return CollectionResult(album, songs)
    }

    private suspend fun fetchPlaylistDetail(cookie: String, id: String): CollectionResult {
        val playlistId = id.trim()
        if (playlistId.isEmpty()) throw IllegalArgumentException("playlist id is empty")

        val playlistItem = getPlaylistByID(cookie, playlistId)
        // The Go provider fetches the playlist once more inside GetPlaylistSongs.
        val songs = fetchPlaylistTracks(cookie, getPlaylistByID(cookie, playlistId))

        val playlist = Playlist(
            id = playlistItem.id.toString(),
            name = playlistItem.name,
            cover = playlistItem.image,
            trackCount = songs.size,
            creator = playlistItem.userName,
            description = playlistItem.description.trim(),
            source = "jamendo",
            link = playlistLink(playlistItem.id.toString()),
            extra = mapOf(
                "type" to "playlist",
                "playlist_id" to playlistItem.id.toString(),
            ),
        )
        return CollectionResult(playlist, songs)
    }

    private suspend fun fetchAlbumTracks(
        cookie: String,
        albumItem: JamendoAlbumItem,
        creator: String,
    ): List<Song> {
        if (albumItem.tracks.isEmpty()) throw IllegalStateException("album is empty or invalid")
        val meta = JamendoTrackMeta(
            artistName = creator,
            albumName = albumItem.name,
            albumId = albumItem.id.toString(),
        )
        return fetchTracks(
            cookie = cookie,
            trackIds = albumItem.tracks.map { it.id },
            meta = meta,
            emptyMessage = "album has no playable tracks",
        )
    }

    private suspend fun fetchPlaylistTracks(
        cookie: String,
        playlistItem: JamendoPlaylistItem,
    ): List<Song> {
        if (playlistItem.tracks.isEmpty()) throw IllegalStateException("playlist is empty or invalid")
        return fetchTracks(
            cookie = cookie,
            trackIds = playlistItem.tracks.map { it.id },
            meta = JamendoTrackMeta(),
            emptyMessage = "playlist has no playable tracks",
        )
    }

    private suspend fun fetchTracks(
        cookie: String,
        trackIds: List<Int>,
        meta: JamendoTrackMeta,
        emptyMessage: String,
    ): List<Song> = coroutineScope {
        val semaphore = Semaphore(4)
        val outcomes = trackIds.map { trackId ->
            if (trackId == 0) {
                null
            } else {
                async {
                    semaphore.withPermit {
                        try {
                            TrackOutcome(song = getTrackByID(cookie, trackId.toString(), meta))
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            TrackOutcome(error = error)
                        }
                    }
                }
            }
        }.map { it?.await() }

        val songs = outcomes.mapNotNull { it?.song }
        if (songs.isEmpty()) {
            val firstError = outcomes.firstNotNullOfOrNull { it?.error }
            throw firstError ?: IllegalStateException(emptyMessage)
        }
        songs
    }

    private suspend fun getTrackByID(cookie: String, id: String, meta: JamendoTrackMeta): Song {
        val body = apiGet(
            cookie,
            "$TRACK_API?" + encodeParams(mapOf("id" to id)),
            TRACK_API_PATH,
        )
        val results = json.decodeFromString<List<JamendoTrackItem>>(body)
        if (results.isEmpty()) throw IllegalStateException("track not found")

        val item = results[0]
        val resolvedMeta = resolveTrackMeta(cookie, item, meta)
        return buildSong(item, resolvedMeta) ?: throw IllegalStateException("no valid stream found")
    }

    private suspend fun resolveTrackMeta(
        cookie: String,
        item: JamendoTrackItem,
        meta: JamendoTrackMeta,
    ): JamendoTrackMeta {
        var albumId = meta.albumId
        var albumName = meta.albumName
        var artistName = meta.artistName

        if (albumId.isEmpty() && item.albumId > 0) albumId = item.albumId.toString()
        if (albumName.isEmpty()) albumName = item.album.name.trim()
        if (artistName.isEmpty()) artistName = item.artist.name.trim()

        if (albumName.isEmpty() && item.albumId > 0) {
            val albumItem = getAlbumByID(cookie, item.albumId.toString())
            albumName = albumItem.name
            if (albumId.isEmpty()) albumId = albumItem.id.toString()
        }

        if (artistName.isEmpty() && item.artistId > 0) {
            artistName = getArtistNameByID(cookie, item.artistId)
        }

        return JamendoTrackMeta(
            artistName = artistName,
            albumName = albumName,
            albumId = albumId,
        )
    }

    private suspend fun getAlbumByID(cookie: String, id: String): JamendoAlbumItem {
        val body = apiGet(
            cookie,
            "$ALBUM_API?" + encodeParams(mapOf("id" to id)),
            ALBUM_API_PATH,
        )
        val results = json.decodeFromString<List<JamendoAlbumItem>>(body)
        if (results.isEmpty()) throw IllegalStateException("album not found")
        return results[0]
    }

    private suspend fun getPlaylistByID(cookie: String, id: String): JamendoPlaylistItem {
        val body = apiGet(
            cookie,
            "$PLAYLIST_API?" + encodeParams(mapOf("id" to id)),
            PLAYLIST_API_PATH,
        )
        val results = json.decodeFromString<List<JamendoPlaylistItem>>(body)
        if (results.isEmpty()) throw IllegalStateException("playlist not found")
        return results[0]
    }

    private suspend fun getArtistNameByID(cookie: String, id: Int): String {
        if (id == 0) return ""
        val body = apiGet(
            cookie,
            "$ARTIST_API?" + encodeParams(mapOf("id" to id.toString())),
            ARTIST_API_PATH,
        )
        val results = json.decodeFromString<List<JamendoArtist>>(body)
        if (results.isEmpty()) throw IllegalStateException("artist not found")
        return results[0].name
    }

    private suspend fun searchByType(cookie: String, keyword: String, type: String): String {
        val query = encodeParams(
            mapOf(
                "query" to keyword,
                "type" to type,
                "limit" to "20",
                "identities" to "www",
            )
        )
        return apiGet(cookie, "$SEARCH_API?$query", SEARCH_API_PATH)
    }

    private suspend fun apiGet(cookie: String, url: String, path: String): String =
        NativeHttp.get(
            url,
            headers = mapOf(
                "Referer" to REFERER,
                "x-jam-call" to makeXJamCall(path),
                "x-jam-version" to X_JAM_VERSION,
                "x-requested-with" to "XMLHttpRequest",
            ),
            cookie = cookie,
            userAgent = USER_AGENT,
        )

    private fun buildSong(item: JamendoTrackItem, meta: JamendoTrackMeta): Song? {
        val streams = if (item.download.isNotEmpty()) item.download else item.stream
        val (downloadUrl, ext) = pickBestQuality(streams)
        if (downloadUrl.isEmpty()) return null

        val trackId = item.id.toString()
        val albumId = meta.albumId.ifEmpty {
            if (item.albumId > 0) item.albumId.toString() else ""
        }
        return Song(
            id = trackId,
            name = item.name,
            artist = firstNonEmpty(item.artist.name.trim(), meta.artistName.trim()),
            album = firstNonEmpty(item.album.name.trim(), meta.albumName.trim()),
            albumId = albumId,
            duration = item.duration,
            source = "jamendo",
            url = downloadUrl,
            ext = ext,
            cover = item.cover.big.size300,
            link = trackLink(trackId),
            extra = buildMap {
                put("track_id", trackId)
                if (albumId.isNotEmpty()) put("album_id", albumId)
            },
        )
    }

    private fun pickBestQuality(streams: Map<String, String?>): Pair<String, String> {
        for (key in QUALITY_ORDER) {
            val url = streams[key].orEmpty()
            if (url.isEmpty()) continue
            return when (key) {
                "mp33", "mp32" -> url to "mp3"
                else -> url to key
            }
        }
        return "" to ""
    }

    /** Jamendo 对不存在的档也会给地址，真发一个 HEAD 才知道 404；不能 GET，整曲文件太大。 */
    private suspend fun jamendoProbeStream(streamUrl: String) {
        val request = Request.Builder()
            .url(streamUrl)
            .head()
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .build()
        suspendCancellableCoroutine<Unit> { continuation ->
            val call = JAMENDO_PROBE_CLIENT.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(IOException("HTTP ${it.code}"))
                            }
                            return
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            })
        }
    }

    private fun pickJamendoDescription(description: Map<String, String?>): String {
        for (key in DESCRIPTION_LANGUAGES) {
            val value = description[key]?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun firstNonEmpty(vararg values: String): String {
        for (value in values) {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }

    private fun encodeParams(params: Map<String, String>): String =
        params.entries.sortedBy { it.key }.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    private fun makeXJamCall(path: String): String {
        val randStr = Random.nextDouble().toString()
        val digest = NativeCrypto.bytesToHex(
            MessageDigest.getInstance("SHA-1").digest((path + randStr).toByteArray(Charsets.UTF_8))
        )
        return "\$$digest*$randStr~"
    }

    private data class TrackOutcome(val song: Song? = null, val error: Exception? = null)

    private companion object {
        // Declared by the upstream Go provider; no request in this provider actually uses it.
        const val CLIENT_ID = "9873ff31"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
        const val REFERER = "https://www.jamendo.com/search?q=musicdl"
        const val X_JAM_VERSION = "4gvfvv"
        const val SEARCH_API = "https://www.jamendo.com/api/search"
        const val SEARCH_API_PATH = "/api/search"
        const val TRACK_API = "https://www.jamendo.com/api/tracks"
        const val TRACK_API_PATH = "/api/tracks"
        const val ALBUM_API = "https://www.jamendo.com/api/albums"
        const val ALBUM_API_PATH = "/api/albums"
        const val ARTIST_API = "https://www.jamendo.com/api/artists"
        const val ARTIST_API_PATH = "/api/artists"
        const val PLAYLIST_API = "https://www.jamendo.com/api/playlists"
        const val PLAYLIST_API_PATH = "/api/playlists"
        val TRACK_LINK = Regex("""jamendo\.com/track/(\d+)""")
        val ALBUM_LINK = Regex("""jamendo\.com/album/(\d+)""")
        val PLAYLIST_LINK = Regex("""jamendo\.com/playlist/(\d+)""")
        val QUALITY_ORDER = listOf("flac", "mp33", "mp32", "mp3", "ogg")
        val DESCRIPTION_LANGUAGES = listOf("en", "fr", "de", "es", "it", "ru", "pt", "pl")
    }
}

private const val JAMENDO_QUALITY_PROBE_KEYWORD = "love"

/** 探测流是否存在的 HEAD 客户端：整曲文件太大，不能 GET。 */
private val JAMENDO_PROBE_CLIENT: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
}

private data class JamendoQualitySource(val field: String, val stream: Boolean = false)

private data class JamendoQualityCandidate(
    val option: AudioQualityOption,
    val sources: List<JamendoQualitySource>,
)

/**
 * Jamendo 的可下载档，从高到低。一首曲的 download / stream 两个 map 里字段名不同，
 * 每档按顺序找第一个有值的地址。标签照抄 music-lib 的 model.AudioQualityOption。
 */
private val JAMENDO_QUALITY_CANDIDATES = listOf(
    JamendoQualityCandidate(
        AudioQualityOption(AudioQuality.Lossless, "无损音质", "flac"),
        listOf(JamendoQualitySource("flac"), JamendoQualitySource("flac", stream = true)),
    ),
    JamendoQualityCandidate(
        AudioQualityOption(AudioQuality.High, "高品质", "mp3", 320),
        listOf(JamendoQualitySource("mp33"), JamendoQualitySource("mp33", stream = true)),
    ),
    JamendoQualityCandidate(
        AudioQualityOption(AudioQuality.Higher, "较高品质", "mp3", 192),
        listOf(
            JamendoQualitySource("mp32"),
            JamendoQualitySource("mp32", stream = true),
            JamendoQualitySource("mp3"),
        ),
    ),
    JamendoQualityCandidate(
        AudioQualityOption(AudioQuality.Standard, "标准音质", "ogg", 112),
        listOf(JamendoQualitySource("ogg"), JamendoQualitySource("ogg", stream = true)),
    ),
    JamendoQualityCandidate(
        AudioQualityOption(AudioQuality.Low, "低品质", "mp3", 96),
        listOf(JamendoQualitySource("mp3", stream = true)),
    ),
)

internal val JAMENDO_QUALITY_LADDER: AudioQualityLadder =
    AudioQualityLadder.of(JAMENDO_QUALITY_CANDIDATES.map { it.option })

private fun jamendoQualityCandidateFor(quality: AudioQuality): JamendoQualityCandidate? =
    JAMENDO_QUALITY_CANDIDATES.firstOrNull { it.option.value == quality }

private fun jamendoQualityUrl(item: JamendoTrackItem, candidate: JamendoQualityCandidate): String {
    for (source in candidate.sources) {
        val streams = if (source.stream) item.stream else item.download
        val url = streams[source.field]?.trim().orEmpty()
        if (url.isNotEmpty()) return url
    }
    return ""
}

private fun albumLink(id: String): String = "https://www.jamendo.com/album/$id"

private fun playlistLink(id: String): String = "https://www.jamendo.com/playlist/$id"

private fun trackLink(id: String): String = "https://www.jamendo.com/track/$id"

private data class JamendoTrackMeta(
    val artistName: String = "",
    val albumName: String = "",
    val albumId: String = "",
)

@Serializable
private data class JamendoArtist(
    val id: Int = 0,
    val name: String = "",
)

@Serializable
private data class JamendoAlbum(
    val id: Int = 0,
    val name: String = "",
)

@Serializable
private data class JamendoCoverBig(
    @SerialName("size300") val size300: String = "",
)

@Serializable
private data class JamendoCover(
    val big: JamendoCoverBig = JamendoCoverBig(),
)

@Serializable
private data class JamendoTrackRef(
    val position: Int = 0,
    val id: Int = 0,
)

@Serializable
private data class JamendoTrackItem(
    val id: Int = 0,
    val name: String = "",
    val duration: Int = 0,
    val artistId: Int = 0,
    val albumId: Int = 0,
    val artist: JamendoArtist = JamendoArtist(),
    val album: JamendoAlbum = JamendoAlbum(),
    val cover: JamendoCover = JamendoCover(),
    val download: Map<String, String?> = emptyMap(),
    val stream: Map<String, String?> = emptyMap(),
)

@Serializable
private data class JamendoAlbumSearchItem(
    val id: Int = 0,
    val name: String = "",
    val artist: JamendoArtist = JamendoArtist(),
    val cover: JamendoCover = JamendoCover(),
)

@Serializable
private data class JamendoAlbumItem(
    val id: Int = 0,
    val name: String = "",
    val artistId: Int = 0,
    val dateReleased: Long = 0,
    val description: Map<String, String?> = emptyMap(),
    val cover: JamendoCover = JamendoCover(),
    val tracks: List<JamendoTrackRef> = emptyList(),
)

@Serializable
private data class JamendoPlaylistItem(
    val id: Int = 0,
    val name: String = "",
    @SerialName("user_name") val userName: String = "",
    val image: String = "",
    val description: String = "",
    val tracks: List<JamendoTrackRef> = emptyList(),
)
