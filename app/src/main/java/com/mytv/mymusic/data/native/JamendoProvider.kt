package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.net.URLEncoder
import java.security.MessageDigest
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

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val body = searchByType(cookie, keyword, "track")
        val results = json.decodeFromString<List<JamendoTrackItem>>(body)
        return results.mapNotNull { buildSong(it, JamendoTrackMeta()) }
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val match = TRACK_LINK.find(link) ?: throw IllegalArgumentException("invalid jamendo link")
        return getTrackByID(cookie, match.groupValues[1], JamendoTrackMeta())
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.source != "jamendo") throw IllegalArgumentException("source mismatch")
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
