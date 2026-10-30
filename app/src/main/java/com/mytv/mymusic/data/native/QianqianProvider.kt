package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private val QIANQIAN_INFO = ProviderInfo(
    id = "qianqian",
    name = "千千音乐",
    search = true,
    download = true,
    lyrics = true,
    parseSong = true,
    searchPlaylist = true,
    playlistSongs = true,
    parsePlaylist = true,
    searchAlbum = true,
    albumSongs = true,
    parseAlbum = true,
    playlistCategories = true,
    unstable = "playlist-search",
)

class QianqianProvider : BaseNativeProvider(QIANQIAN_INFO) {
    private val json = NativeHttp.json

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val body = getSigned(
            cookie,
            SEARCH_API,
            mapOf(
                "word" to keyword,
                "type" to "1",
                "pageNo" to "1",
                "pageSize" to "10",
                "appid" to APP_ID,
            ),
        )
        val root = json.decodeFromString<QianqianSearchResponse>(body)
        return root.data.typeTrack.mapNotNull { item ->
            if (item.isVip != 0) null else searchSongOf(item)
        }
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val match = SONG_LINK_PATTERN.find(link)
            ?: throw IllegalArgumentException("invalid qianqian link")
        val tsid = match.groupValues[1]
        val item = fetchSongInfo(cookie, tsid)
            ?: throw IllegalStateException("song info not found")
        val song = Song(
            id = tsid,
            name = item.title,
            artist = joinArtists(item.artist),
            album = item.albumTitle,
            duration = item.duration,
            source = SOURCE,
            cover = item.pic,
            link = songLink(tsid),
            extra = buildMap {
                put("tsid", tsid)
                putVolume(item.replayGain, item.meanVolume, item.maxVolume)
            },
        )
        val url = try {
            fetchDownloadUrl(cookie, tsid)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ""
        }
        return if (url.isNotEmpty()) song.copy(url = url) else song
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.source != SOURCE) throw IllegalArgumentException("source mismatch")
        if (song.url.isNotEmpty()) return song.url
        val tsid = song.extra["tsid"].orEmpty().ifEmpty { song.id }
        return fetchDownloadUrl(cookie, tsid)
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != SOURCE) throw IllegalArgumentException("source mismatch")
        val tsid = song.extra["tsid"].orEmpty().ifEmpty { song.id }
        val lyricUrl = fetchSongInfo(cookie, tsid)?.lyric.orEmpty()
        if (lyricUrl.isEmpty()) throw IllegalStateException("lyric url not found")
        // The song/info "lyric" field is a URL to the LRC file; return its text as-is.
        return NativeHttp.get(
            url = lyricUrl,
            cookie = cookie,
            userAgent = USER_AGENT,
        )
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val body = getSigned(
            cookie,
            SEARCH_API,
            mapOf(
                "word" to keyword,
                "type" to "6",
                "pageNo" to "1",
                "pageSize" to "10",
                "appid" to APP_ID,
                "timestamp" to unixTimestamp(),
            ),
        )
        val raw = json.decodeFromString<QianqianRawSearchResponse>(body)
        if (!raw.state) return emptyList()
        val data = raw.data
        if (data == null || data is JsonNull) return emptyList()
        val decoded = try {
            json.decodeFromString<QianqianPlaylistSearchData>(data.toString())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return emptyList()
        }
        return decoded.typeSonglist.mapNotNull { item ->
            val id = jsonId(item.id, trim = false) ?: return@mapNotNull null
            Playlist(
                id = id,
                name = item.title,
                cover = item.pic,
                trackCount = item.trackCount,
                description = item.tag,
                source = SOURCE,
                link = playlistLink(id),
                extra = mapOf("type" to "playlist", "playlist_id" to id),
            )
        }
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> =
        fetchPlaylistDetail(cookie, id).second

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        for (pattern in PLAYLIST_LINK_PATTERNS) {
            val match = pattern.find(link)
            if (match != null) {
                val (playlist, songs) = fetchPlaylistDetail(cookie, match.groupValues[1])
                return CollectionResult(playlist, songs)
            }
        }
        if (link.isNotEmpty() && !link.contains('/')) {
            val (playlist, songs) = fetchPlaylistDetail(cookie, link)
            return CollectionResult(playlist, songs)
        }
        throw IllegalArgumentException("invalid qianqian playlist link")
    }

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val items = searchAlbumItems(cookie, keyword)
        val albums = items.mapNotNull { item ->
            val albumId = normalizeAlbumAssetCode(item.albumAssetCode)
            if (albumId.isEmpty()) return@mapNotNull null
            Playlist(
                id = albumId,
                name = item.title,
                cover = item.pic,
                trackCount = item.trackList.size,
                creator = joinArtists(item.artist),
                description = item.introduce,
                source = SOURCE,
                link = albumLink(albumId),
                extra = mapOf(
                    "type" to "album",
                    "album_id" to albumId,
                    "release_date" to releaseDate(item.releaseDate),
                    "genre" to item.genre.trim(),
                    "lang" to item.lang.trim(),
                ),
            )
        }
        if (albums.isEmpty()) throw IllegalStateException("no albums found")
        return albums
    }

    override suspend fun albumSongs(cookie: String, id: String): List<Song> =
        fetchAlbumDetail(cookie, id).second

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult {
        for (pattern in ALBUM_LINK_PATTERNS) {
            val match = pattern.find(link)
            if (match != null) {
                val (playlist, songs) = fetchAlbumDetail(cookie, match.groupValues[1])
                return CollectionResult(playlist, songs)
            }
        }
        throw IllegalArgumentException("invalid qianqian album link")
    }

    override suspend fun categories(cookie: String): List<PlaylistCategory> {
        val body = getSigned(cookie, TRACKLIST_CATEGORY_API, mapOf("appid" to APP_ID))
        val root = json.decodeFromString<QianqianCategoryResponse>(body)
        if (!root.state && root.errno != 22000) {
            throw IllegalStateException("api error: ${root.errmsg} (code ${root.errno})")
        }
        val result = mutableListOf(
            PlaylistCategory(
                id = "",
                name = "全部",
                group = "全部",
                source = SOURCE,
            )
        )
        for (group in root.data) {
            val groupName = group.categoryName.trim()
            for (item in group.subCate) {
                val id = item.id.trim()
                val name = item.categoryName.trim()
                if (id.isEmpty() || name.isEmpty()) continue
                result += PlaylistCategory(
                    id = id,
                    name = name,
                    group = groupName,
                    source = SOURCE,
                    count = item.count,
                )
            }
        }
        return result
    }

    override suspend fun categoryPlaylists(
        cookie: String,
        id: String,
        page: Int,
        limit: Int,
    ): List<Playlist> {
        val categoryId = id.trim()
        val safePage = if (page < 1) 1 else page
        var safeLimit = if (limit <= 0) 30 else limit
        if (safeLimit > 100) safeLimit = 100

        val params = mutableMapOf(
            "appid" to APP_ID,
            "pageNo" to safePage.toString(),
            "pageSize" to safeLimit.toString(),
        )
        if (categoryId.isNotEmpty()) params["subCateId"] = categoryId

        val body = getSigned(cookie, TRACKLIST_LIST_API, params)
        val root = json.decodeFromString<QianqianCategoryPlaylistResponse>(body)
        if (!root.state && root.errno != 22000) {
            throw IllegalStateException("api error: ${root.errmsg} (code ${root.errno})")
        }
        return root.data.result.mapNotNull { item ->
            val playlistId = jsonId(item.id, trim = true)?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            var description = item.desc.trim()
            if (description.isEmpty() && item.tagList.isNotEmpty()) {
                description = item.tagList.joinToString("、")
            }
            Playlist(
                id = playlistId,
                name = item.title,
                cover = item.pic,
                trackCount = item.trackCount,
                description = description,
                source = SOURCE,
                link = playlistLink(playlistId),
                extra = mapOf(
                    "type" to "playlist",
                    "playlist_id" to playlistId,
                    "category_id" to categoryId,
                ),
            )
        }
    }

    private suspend fun getSigned(
        cookie: String,
        base: String,
        params: Map<String, String>,
    ): String = NativeHttp.get(
        url = signedUrl(base, params),
        headers = mapOf("Referer" to REFERER),
        cookie = cookie,
        userAgent = USER_AGENT,
    )

    private fun searchSongOf(item: QianqianSearchTrack): Song {
        val albumId = normalizeAlbumAssetCode(item.albumAssetCode)
        val (size, bitrate) = rateStats(item.rateFileInfo, item.duration)
        return Song(
            id = item.tsid,
            name = item.title,
            artist = joinArtists(item.artist),
            album = item.albumTitle,
            albumId = albumId,
            duration = item.duration,
            size = size,
            bitrate = bitrate,
            source = SOURCE,
            cover = item.pic,
            link = songLink(item.tsid),
            extra = buildMap {
                put("tsid", item.tsid)
                put("album_id", albumId)
                putVolume(item.replayGain, item.meanVolume, item.maxVolume)
            },
        )
    }

    private suspend fun fetchSongInfo(cookie: String, tsid: String): QianqianSongInfoItem? {
        val body = getSigned(cookie, SONG_INFO_API, mapOf("TSID" to tsid, "appid" to APP_ID))
        return json.decodeFromString<QianqianSongInfoResponse>(body).data.firstOrNull()
    }

    private suspend fun fetchDownloadUrl(cookie: String, tsid: String): String {
        for (rate in RATE_ORDER) {
            val downloadUrl = try {
                val body = getSigned(
                    cookie,
                    TRACK_LINK_API,
                    mapOf("TSID" to tsid, "appid" to APP_ID, "rate" to rate),
                )
                val data = json.decodeFromString<QianqianTrackLinkResponse>(body).data
                data.path.ifEmpty { data.trailAudioInfo.path }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            if (downloadUrl.isNotEmpty()) return downloadUrl
        }
        throw IllegalStateException("download url not found")
    }

    private suspend fun searchAlbumItems(
        cookie: String,
        keyword: String,
    ): List<QianqianAlbumSearchItem> {
        val keywords = mutableListOf(keyword)
        val sanitized = sanitizeAlbumKeyword(keyword)
        if (sanitized.isNotEmpty() && sanitized != keyword) keywords += sanitized

        var lastError: Throwable? = null
        for (currentKeyword in keywords) {
            try {
                return searchAlbumItemsOnce(cookie, currentKeyword)
            } catch (error: RetryWithSanitizedKeywordException) {
                lastError = error
                if (currentKeyword == keyword) continue
                break
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                break
            }
        }
        throw lastError ?: IllegalStateException("no albums found")
    }

    private suspend fun searchAlbumItemsOnce(
        cookie: String,
        keyword: String,
    ): List<QianqianAlbumSearchItem> {
        val body = getSigned(
            cookie,
            SEARCH_API,
            mapOf(
                "word" to keyword,
                "type" to "3",
                "pageNo" to "1",
                "pageSize" to "10",
                "appid" to APP_ID,
            ),
        )
        val raw = json.decodeFromString<QianqianRawSearchResponse>(body)
        if (!raw.state) {
            if (raw.errno == 23001) {
                throw RetryWithSanitizedKeywordException(
                    "api error: ${raw.errmsg} (code ${raw.errno})",
                )
            }
            if (raw.errno != 22000) {
                throw IllegalStateException("api error: ${raw.errmsg} (code ${raw.errno})")
            }
        }
        val data = raw.data
        if (data == null || data is JsonNull || (data is JsonArray && data.isEmpty())) {
            throw IllegalStateException("no albums found")
        }
        val decoded = json.decodeFromString<QianqianAlbumSearchData>(data.toString())
        if (decoded.typeAlbum.isEmpty()) throw IllegalStateException("no albums found")
        return decoded.typeAlbum
    }

    private suspend fun fetchPlaylistDetail(
        cookie: String,
        id: String,
    ): Pair<Playlist, List<Song>> {
        val playlistId = id.trim()
        if (playlistId.isEmpty()) throw IllegalArgumentException("playlist id is empty")

        val body = getSigned(
            cookie,
            TRACKLIST_INFO_API,
            mapOf("id" to playlistId, "appid" to APP_ID, "type" to "0"),
        )
        val root = json.decodeFromString<QianqianPlaylistDetailResponse>(body)
        if (root.errno != 0 && root.errno != 22000) {
            throw IllegalStateException("api error: ${root.errmsg} (code ${root.errno})")
        }

        val data = root.data
        val songs = data.trackList.map { item ->
            val cover = item.pic.trim().ifEmpty { data.pic.trim() }
            Song(
                id = item.tsid,
                name = item.title,
                artist = joinArtists(item.artist),
                album = item.albumTitle,
                duration = item.duration,
                source = SOURCE,
                cover = cover,
                link = songLink(item.tsid),
                extra = mapOf("tsid" to item.tsid),
            )
        }
        if (songs.isEmpty()) throw IllegalStateException("playlist is empty or invalid")

        val trackCount = if (data.trackCount == 0) songs.size else data.trackCount
        var description = firstNonEmpty(data.desc, data.description)
        if (description.isEmpty() && data.tagList.isNotEmpty()) {
            description = data.tagList.joinToString("、")
        }
        val playlist = Playlist(
            id = playlistId,
            name = firstNonEmpty(data.title, playlistId),
            cover = firstNonEmpty(data.pic, songs.first().cover),
            trackCount = trackCount,
            creator = firstNonEmpty(
                data.creator,
                data.author,
                data.userName,
                data.nickName,
                data.ownerName,
            ),
            description = description,
            source = SOURCE,
            link = playlistLink(playlistId),
            extra = mapOf("type" to "playlist", "playlist_id" to playlistId),
        )
        return playlist to songs
    }

    private suspend fun fetchAlbumDetail(
        cookie: String,
        id: String,
    ): Pair<Playlist, List<Song>> {
        val albumId = resolveAlbumAssetCode(cookie, id)
        val body = getSigned(
            cookie,
            ALBUM_INFO_API,
            mapOf("albumAssetCode" to albumId, "appid" to APP_ID),
        )
        val root = json.decodeFromString<QianqianAlbumDetailResponse>(body)
        if (!root.state && root.errno != 22000) {
            throw IllegalStateException("api error: ${root.errmsg} (code ${root.errno})")
        }
        if (normalizeAlbumAssetCode(root.data.albumAssetCode).isEmpty()) {
            throw IllegalStateException("album not found")
        }

        val data = root.data
        val creator = joinArtists(data.artist)
        var trackCount = data.trackList.size
        val songs = mutableListOf<Song>()
        for (item in data.trackList) {
            val songId = item.assetId.trim()
            if (songId.isEmpty()) continue
            val artist = joinArtists(item.artist).ifEmpty { creator }
            val (size, bitrate) = rateStats(item.rateFileInfo, item.duration)
            val extra = buildMap {
                put("tsid", songId)
                put("album_id", albumId)
                if (item.sort > 0) put("track", item.sort.toString())
            }
            songs += Song(
                id = songId,
                name = item.title,
                artist = artist,
                album = data.title,
                albumId = albumId,
                duration = item.duration,
                size = size,
                bitrate = bitrate,
                source = SOURCE,
                cover = data.pic,
                link = songLink(songId),
                extra = extra,
            )
        }
        if (songs.isEmpty()) throw IllegalStateException("album is empty or invalid")
        if (trackCount == 0) trackCount = songs.size

        val playlist = Playlist(
            id = albumId,
            name = data.title,
            cover = data.pic,
            trackCount = trackCount,
            creator = creator,
            description = data.introduce,
            source = SOURCE,
            link = albumLink(albumId),
            extra = mapOf(
                "type" to "album",
                "album_id" to albumId,
                "release_date" to releaseDate(data.releaseDate),
                "genre" to data.genre.trim(),
                "lang" to data.lang.trim(),
            ),
        )
        return playlist to songs
    }

    private suspend fun resolveAlbumAssetCode(cookie: String, id: String): String {
        val value = id.trim()
        if (value.isEmpty()) throw IllegalArgumentException("album id is empty")
        val normalized = normalizeAlbumAssetCode(value)
        if (normalized.isNotEmpty()) return normalized

        val body = getSigned(
            cookie,
            ALBUM_ID_API,
            mapOf("albumid" to value, "appid" to APP_ID),
        )
        val root = json.decodeFromString<QianqianAlbumPsidResponse>(body)
        if (!root.state && root.errno != 22000) {
            throw IllegalStateException("api error: ${root.errmsg} (code ${root.errno})")
        }
        for (item in root.data) {
            val psid = normalizeAlbumAssetCode(item.psid)
            if (psid.isNotEmpty()) return psid
        }
        throw IllegalStateException("album asset code not found")
    }

    private fun signedUrl(base: String, params: Map<String, String>): String {
        val values = params.toMutableMap()
        values["timestamp"] = unixTimestamp()
        val signSource = values.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) -> "$key=$value" } + SECRET
        values["sign"] = NativeCrypto.md5Hex(signSource)
        val query = values.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) -> "${queryEscape(key)}=${queryEscape(value)}" }
        return "$base?$query"
    }

    private fun unixTimestamp(): String = (System.currentTimeMillis() / 1000).toString()

    private fun queryEscape(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val builder = StringBuilder(bytes.size * 3)
        for (byte in bytes) {
            val code = byte.toInt() and 0xff
            val char = code.toChar()
            when {
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' -> builder.append(char)
                char == '-' || char == '_' || char == '.' || char == '~' -> builder.append(char)
                char == ' ' -> builder.append('+')
                else -> builder
                    .append('%')
                    .append(HEX_UPPER[code ushr 4])
                    .append(HEX_UPPER[code and 0x0f])
            }
        }
        return builder.toString()
    }

    private fun joinArtists(artists: List<QianqianArtistDto>): String {
        if (artists.isEmpty()) return ""

        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun addName(name: String) {
            val value = name.trim()
            if (value.isEmpty() || !seen.add(value)) return
            names += value
        }
        for (artist in artists) {
            if (artist.artistType == 38) addName(artist.name)
        }
        if (names.isEmpty()) {
            for (artist in artists) addName(artist.name)
        }
        return names.joinToString("、")
    }

    private fun rateStats(
        rateFileInfo: Map<String, QianqianRateFileInfoDto>,
        duration: Int,
    ): Pair<Long, Int> {
        for (rate in RATE_ORDER) {
            val info = rateFileInfo[rate] ?: continue
            if (info.size <= 0) continue
            if (duration > 0) {
                return info.size to (info.size * 8 / 1000 / duration).toInt()
            }
            if (rate == "3000") return info.size to 800
            return info.size to (rate.toIntOrNull() ?: 0)
        }
        return 0L to 0
    }

    private fun normalizeAlbumAssetCode(id: String): String {
        val value = id.trim()
        if (value.length < 2) return ""
        return if (value[0] == 'p' || value[0] == 'P') "P" + value.substring(1) else ""
    }

    private fun sanitizeAlbumKeyword(keyword: String): String {
        var value = keyword.trim()
        for (char in ALBUM_KEYWORD_SPECIALS) {
            value = value.replace(char, ' ')
        }
        return value.split(WHITESPACE_PATTERN)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun releaseDate(raw: String): String {
        val value = raw.trim()
        return if (value.length >= 10) value.substring(0, 10) else value
    }

    private fun firstNonEmpty(vararg values: String): String {
        for (value in values) {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }

    private fun jsonId(element: JsonElement?, trim: Boolean): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) {
            return if (trim) primitive.content.trim() else primitive.content
        }
        val number = primitive.content.toDoubleOrNull() ?: return null
        return number.toLong().toString()
    }

    private fun songLink(id: String): String = "https://music.91q.com/song/$id"

    private fun playlistLink(id: String): String = "https://music.91q.com/songlist/$id"

    private fun albumLink(id: String): String = "https://music.91q.com/album/$id"

    private companion object {
        const val SOURCE = "qianqian"
        const val APP_ID = "16073360"
        const val SECRET = "0b50b02fd0d73a9c4c8c3a781c30845f"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
        const val REFERER = "https://music.91q.com/player"
        const val SEARCH_API = "https://music.91q.com/v1/search"
        const val TRACKLIST_INFO_API = "https://music.91q.com/v1/tracklist/info"
        const val TRACKLIST_CATEGORY_API = "https://music.91q.com/v1/tracklist/category"
        const val TRACKLIST_LIST_API = "https://music.91q.com/v1/tracklist/list"
        const val ALBUM_INFO_API = "https://music.91q.com/v1/album/info"
        const val ALBUM_ID_API = "https://music.91q.com/v1/album/albumid2psid"
        const val SONG_INFO_API = "https://music.91q.com/v1/song/info"
        const val TRACK_LINK_API = "https://music.91q.com/v1/song/tracklink"
        const val HEX_UPPER = "0123456789ABCDEF"
        const val ALBUM_KEYWORD_SPECIALS = ":：\"'“”‘’()（）[]【】，,/\\-."
        val RATE_ORDER = listOf("3000", "320", "128", "64")
        val WHITESPACE_PATTERN =
            Regex("[\\s\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+")
        val SONG_LINK_PATTERN = Regex("""music\.91q\.com/song/(\w+)""")
        val PLAYLIST_LINK_PATTERNS = listOf(
            Regex("""music\.91q\.com/(?:songlist|tracklist|playlist)/([A-Za-z0-9]+)"""),
            Regex("""(?:songlistid|tracklistid|playlistid|id)=([A-Za-z0-9]+)"""),
        )
        val ALBUM_LINK_PATTERNS = listOf(
            Regex("""music\.91q\.com/album/([A-Za-z0-9]+)"""),
            Regex("""albumAssetCode=([A-Za-z0-9]+)"""),
            Regex("""albumid=(\d+)"""),
        )
    }
}

private class RetryWithSanitizedKeywordException(message: String) : Exception(message)

@Serializable
private data class QianqianArtistDto(
    val name: String = "",
    val artistType: Int = 0,
)

@Serializable
private data class QianqianRateFileInfoDto(
    val size: Long = 0,
    val format: String = "",
)

@Serializable
private data class QianqianSearchResponse(
    val data: QianqianSearchData = QianqianSearchData(),
)

@Serializable
private data class QianqianSearchData(
    val typeTrack: List<QianqianSearchTrack> = emptyList(),
)

private fun MutableMap<String, String>.putVolume(
    replayGain: Float?,
    meanVolume: Float?,
    maxVolume: Float?,
) {
    replayGain?.let { put("qianqian_replay_gain", it.toString()) }
    meanVolume?.let { put("qianqian_mean_volume", it.toString()) }
    maxVolume?.let { put("qianqian_max_volume", it.toString()) }
}

@Serializable
private data class QianqianSearchTrack(
    @SerialName("TSID") val tsid: String = "",
    val title: String = "",
    val albumTitle: String = "",
    val albumAssetCode: String = "",
    val pic: String = "",
    val duration: Int = 0,
    val lyric: String = "",
    val artist: List<QianqianArtistDto> = emptyList(),
    val rateFileInfo: Map<String, QianqianRateFileInfoDto> = emptyMap(),
    val isVip: Int = 0,
    @SerialName("afReplayGain") val replayGain: Float? = null,
    val meanVolume: Float? = null,
    val maxVolume: Float? = null,
)

@Serializable
private data class QianqianRawSearchResponse(
    val state: Boolean = false,
    val errno: Int = 0,
    val errmsg: String = "",
    val data: JsonElement? = null,
)

@Serializable
private data class QianqianPlaylistSearchData(
    val typeSonglist: List<QianqianPlaylistSearchItem> = emptyList(),
)

@Serializable
private data class QianqianPlaylistSearchItem(
    val id: JsonElement? = null,
    val title: String = "",
    val pic: String = "",
    val trackCount: Int = 0,
    val tag: String = "",
)

@Serializable
private data class QianqianPlaylistDetailResponse(
    val data: QianqianPlaylistDetailData = QianqianPlaylistDetailData(),
    val errno: Int = 0,
    val errmsg: String = "",
)

@Serializable
private data class QianqianPlaylistDetailData(
    val title: String = "",
    val pic: String = "",
    val desc: String = "",
    val description: String = "",
    val trackCount: Int = 0,
    val tagList: List<String> = emptyList(),
    val creator: String = "",
    val author: String = "",
    val userName: String = "",
    val nickName: String = "",
    val ownerName: String = "",
    val trackList: List<QianqianPlaylistTrack> = emptyList(),
)

@Serializable
private data class QianqianPlaylistTrack(
    @SerialName("TSID") val tsid: String = "",
    val title: String = "",
    val albumTitle: String = "",
    val pic: String = "",
    val duration: Int = 0,
    val artist: List<QianqianArtistDto> = emptyList(),
    val isVip: Int = 0,
)

@Serializable
private data class QianqianAlbumSearchData(
    val typeAlbum: List<QianqianAlbumSearchItem> = emptyList(),
)

@Serializable
private data class QianqianAlbumSearchItem(
    val albumAssetCode: String = "",
    val title: String = "",
    val pic: String = "",
    val introduce: String = "",
    val releaseDate: String = "",
    val genre: String = "",
    val lang: String = "",
    val artist: List<QianqianArtistDto> = emptyList(),
    val trackList: List<JsonElement> = emptyList(),
)

@Serializable
private data class QianqianAlbumDetailResponse(
    val state: Boolean = false,
    val errno: Int = 0,
    val errmsg: String = "",
    val data: QianqianAlbumDetailData = QianqianAlbumDetailData(),
)

@Serializable
private data class QianqianAlbumDetailData(
    val albumAssetCode: String = "",
    val title: String = "",
    val pic: String = "",
    val introduce: String = "",
    val releaseDate: String = "",
    val genre: String = "",
    val lang: String = "",
    val artist: List<QianqianArtistDto> = emptyList(),
    val trackList: List<QianqianAlbumTrack> = emptyList(),
)

@Serializable
private data class QianqianAlbumTrack(
    val duration: Int = 0,
    val artist: List<QianqianArtistDto> = emptyList(),
    val assetId: String = "",
    val sort: Int = 0,
    val title: String = "",
    val isVip: Int = 0,
    val isPaid: Int = 0,
    val rateFileInfo: Map<String, QianqianRateFileInfoDto> = emptyMap(),
)

@Serializable
private data class QianqianAlbumPsidResponse(
    val state: Boolean = false,
    val errno: Int = 0,
    val errmsg: String = "",
    val data: List<QianqianAlbumPsid> = emptyList(),
)

@Serializable
private data class QianqianAlbumPsid(
    val psid: String = "",
)

@Serializable
private data class QianqianSongInfoResponse(
    val data: List<QianqianSongInfoItem> = emptyList(),
)

@Serializable
private data class QianqianSongInfoItem(
    val title: String = "",
    val albumTitle: String = "",
    val pic: String = "",
    val duration: Int = 0,
    val artist: List<QianqianArtistDto> = emptyList(),
    val lyric: String = "",
    @SerialName("afReplayGain") val replayGain: Float? = null,
    val meanVolume: Float? = null,
    val maxVolume: Float? = null,
)

@Serializable
private data class QianqianTrackLinkResponse(
    val data: QianqianTrackLinkData = QianqianTrackLinkData(),
)

@Serializable
private data class QianqianTrackLinkData(
    val path: String = "",
    val format: String = "",
    val size: Long = 0,
    val duration: Int = 0,
    @SerialName("trail_audio_info") val trailAudioInfo: QianqianTrailAudioInfo =
        QianqianTrailAudioInfo(),
)

@Serializable
private data class QianqianTrailAudioInfo(
    val path: String = "",
)

@Serializable
private data class QianqianCategoryResponse(
    val state: Boolean = false,
    val errno: Int = 0,
    val errmsg: String = "",
    val data: List<QianqianCategoryGroup> = emptyList(),
)

@Serializable
private data class QianqianCategoryGroup(
    val categoryName: String = "",
    val subCate: List<QianqianSubCategory> = emptyList(),
)

@Serializable
private data class QianqianSubCategory(
    val id: String = "",
    val categoryName: String = "",
    val count: Int = 0,
)

@Serializable
private data class QianqianCategoryPlaylistResponse(
    val state: Boolean = false,
    val errno: Int = 0,
    val errmsg: String = "",
    val data: QianqianCategoryPlaylistData = QianqianCategoryPlaylistData(),
)

@Serializable
private data class QianqianCategoryPlaylistData(
    val result: List<QianqianCategoryPlaylistItem> = emptyList(),
)

@Serializable
private data class QianqianCategoryPlaylistItem(
    val id: JsonElement? = null,
    val title: String = "",
    val pic: String = "",
    val trackCount: Int = 0,
    val desc: String = "",
    val tagList: List<String> = emptyList(),
)
