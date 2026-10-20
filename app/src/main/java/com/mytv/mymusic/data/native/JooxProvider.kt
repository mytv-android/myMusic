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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

private val JOOX_INFO = ProviderInfo(
    id = "joox",
    name = "JOOX",
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
)

class JooxProvider : BaseNativeProvider(JOOX_INFO) {
    private val json = NativeHttp.json

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val url = SEARCH_API + "?" + encodeParams(
            mapOf("country" to COUNTRY, "lang" to LANG, "keyword" to keyword)
        )
        val body = jooxGet(url, cookie)
        val response = json.decodeFromString<JooxSongSearchResponse>(body)
        val songs = mutableListOf<Song>()
        for (section in response.sectionList) {
            for (item in section.itemList) {
                for (songItem in item.song) {
                    val info = songItem.songInfo
                    if (info.id.isEmpty()) continue
                    songs += songFromSongInfo(info, fallbackAlbumCover = false)
                }
            }
        }
        return songs
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val match = SONG_LINK.find(link)
        val songId = when {
            match != null -> normalizeJooxId(match.groupValues[1])
            link.length > 10 && !link.contains("/") -> link
            else -> throw IllegalArgumentException("invalid joox link")
        }
        return fetchSongInfo(cookie, songId)
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.source != "joox") throw IllegalArgumentException("source mismatch")
        // The interface returns only a URL, so a resolved URL/format cannot be written back to Song.
        if (song.url.isNotEmpty()) return song.url
        val songId = song.extra["songid"]?.takeIf { it.isNotEmpty() } ?: song.id
        return fetchSongInfo(cookie, songId).url
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != "joox") throw IllegalArgumentException("source mismatch")
        val songId = song.extra["songid"]?.takeIf { it.isNotEmpty() } ?: song.id
        val url = LYRIC_API + "?" + encodeParams(
            mapOf("musicid" to songId, "country" to COUNTRY, "lang" to LANG)
        )
        var body = jooxGet(url, cookie)
        val callbackIndex = body.indexOf("MusicJsonCallback(")
        if (callbackIndex >= 0) {
            body = body.substring(callbackIndex)
                .removePrefix("MusicJsonCallback(")
                .removeSuffix(")")
        }
        val response = json.decodeFromString<JooxLyricResponse>(body)
        if (response.lyric.isEmpty()) throw IllegalStateException("lyric not found or empty")
        // JOOX returns a base64-encoded LRC document; decode it verbatim.
        val decoded = try {
            Base64.getDecoder().decode(stripBase64LineBreaks(response.lyric))
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("base64 decode error: ${error.message}", error)
        }
        return String(decoded, Charsets.UTF_8)
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val url = SEARCH_API + "?" + encodeParams(
            mapOf("country" to COUNTRY, "lang" to LANG, "keyword" to keyword)
        )
        val body = jooxGet(url, cookie)
        val response = json.decodeFromString<JooxPlaylistSearchResponse>(body)
        val playlists = mutableListOf<Playlist>()
        for (section in response.sectionList) {
            for (item in section.itemList) {
                if (item.type != 1) continue
                val info = item.editorPlaylist
                val playlistId = normalizeJooxId(info.id)
                if (playlistId.isEmpty()) continue
                var cover = ""
                for (image in info.images) {
                    if (image.width == 300) {
                        cover = image.url
                        break
                    }
                }
                if (cover.isEmpty() && info.images.isNotEmpty()) cover = info.images[0].url
                playlists += Playlist(
                    id = playlistId,
                    name = info.name,
                    cover = cover,
                    source = "joox",
                    link = jooxPlaylistLink(playlistId),
                    extra = mapOf("playlist_id" to playlistId),
                )
            }
        }
        return playlists
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> =
        fetchPlaylistSongs(cookie, id)

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        for (pattern in PLAYLIST_PATTERNS) {
            val match = pattern.find(link) ?: continue
            val (playlist, songs) = fetchPlaylistDetail(cookie, match.groupValues[1])
            return CollectionResult(playlist, songs)
        }
        if (link.length > 8 && !link.contains("/")) {
            val (playlist, songs) = fetchPlaylistDetail(cookie, link)
            return CollectionResult(playlist, songs)
        }
        throw IllegalArgumentException("invalid joox playlist link")
    }

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val url = SEARCH_API + "?" + encodeParams(
            mapOf("country" to COUNTRY, "lang" to LANG, "keyword" to keyword)
        )
        val body = jooxGet(url, cookie)
        val response = json.decodeFromString<JooxAlbumSearchResponse>(body)
        val albums = mutableListOf<Playlist>()
        val seen = mutableSetOf<String>()
        for (section in response.sectionList) {
            if (section.sectionType != 1) continue
            for (item in section.itemList) {
                if (item.type != 2) continue
                val album = item.album
                val albumId = normalizeJooxId(album.id)
                if (albumId.isEmpty()) continue
                if (!seen.add(albumId)) continue
                albums += Playlist(
                    id = albumId,
                    name = album.name,
                    cover = pickJooxImage(album.images),
                    trackCount = 0,
                    creator = joinJooxArtists(album.artistList),
                    description = album.publishDate.trim(),
                    source = "joox",
                    link = jooxAlbumLink(albumId),
                    extra = mapOf(
                        "type" to "album",
                        "album_id" to albumId,
                        "publish_date" to album.publishDate.trim(),
                    ),
                )
            }
        }
        if (albums.isEmpty()) throw IllegalStateException("no albums found")
        return albums
    }

    override suspend fun albumSongs(cookie: String, id: String): List<Song> =
        fetchAlbumDetail(cookie, id).second

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult {
        for (pattern in ALBUM_PATTERNS) {
            val match = pattern.find(link) ?: continue
            val (album, songs) = fetchAlbumDetail(cookie, match.groupValues[1])
            return CollectionResult(album, songs)
        }
        if (link.length > 10 && !link.contains("/")) {
            val (album, songs) = fetchAlbumDetail(cookie, link)
            return CollectionResult(album, songs)
        }
        throw IllegalArgumentException("invalid joox album link")
    }

    override suspend fun categories(cookie: String): List<PlaylistCategory> {
        val data = fetchPlaylistCategoriesPage(cookie)
        val categories = mutableListOf(
            PlaylistCategory(id = "", name = "全部", group = "全部", source = "joox")
        )
        data.categories.forEachIndexed { index, category ->
            val name = decodeJooxBase64Text(category.title)
            if (name.isEmpty() || category.itemList.isEmpty()) return@forEachIndexed
            categories += PlaylistCategory(
                id = index.toString(),
                name = name,
                group = "JOOX",
                source = "joox",
                count = category.itemList.size,
            )
        }
        if (categories.size == 1) throw IllegalStateException("no playlist categories found")
        return categories
    }

    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        val categoryId = id.trim()
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit
        val data = fetchPlaylistCategoriesPage(cookie)

        val items: List<JooxCategoryPlaylistItem>
        var categoryName = "全部"
        if (categoryId.isEmpty()) {
            val seen = mutableSetOf<String>()
            val collected = mutableListOf<JooxCategoryPlaylistItem>()
            for (category in data.categories) {
                for (item in category.itemList) {
                    val playlistId = jooxCategoryPlaylistId(item.id)
                    if (playlistId.isEmpty()) continue
                    if (!seen.add(playlistId)) continue
                    collected += item
                }
            }
            items = collected
        } else {
            var matched: List<JooxCategoryPlaylistItem> = emptyList()
            for ((index, category) in data.categories.withIndex()) {
                val name = decodeJooxBase64Text(category.title)
                if (index.toString() == categoryId || name.equals(categoryId, ignoreCase = true)) {
                    matched = category.itemList
                    categoryName = name
                    break
                }
            }
            if (matched.isEmpty()) throw IllegalStateException("playlist category not found")
            items = matched
        }

        val startLong = (safePage - 1).toLong() * safeLimit
        if (startLong >= items.size) throw IllegalStateException("no category playlists found")
        val start = startLong.toInt()
        val end = minOf(start + safeLimit, items.size)
        val playlists = mutableListOf<Playlist>()
        for (item in items.subList(start, end)) {
            val playlistId = jooxCategoryPlaylistId(item.id)
            val name = decodeJooxBase64Text(item.title)
            if (playlistId.isEmpty() || name.isEmpty()) continue
            playlists += Playlist(
                id = playlistId,
                name = name,
                cover = item.picUrl.replace("%d", "300"),
                source = "joox",
                link = jooxPlaylistLink(playlistId),
                extra = mapOf(
                    "category_id" to categoryId,
                    "category_name" to categoryName,
                    "playlist_id" to playlistId,
                ),
            )
        }
        if (playlists.isEmpty()) throw IllegalStateException("no category playlists found")
        return playlists
    }

    private suspend fun fetchSongInfo(cookie: String, songId: String): Song {
        val url = SONG_INFO_API + "?" + encodeParams(
            mapOf("songid" to songId, "lang" to LANG, "country" to COUNTRY)
        )
        var body = jooxGet(url, cookie)
        if (body.startsWith("MusicInfoCallback(")) {
            body = body.removePrefix("MusicInfoCallback(").removeSuffix(")")
        }
        val response = json.decodeFromString<JooxSongInfoResponse>(body)

        val availableQualities = mutableMapOf<String, JsonElement>()
        val kbpsMap = response.kbpsMap
        if (kbpsMap is JsonPrimitive && kbpsMap.isString) {
            runCatching { json.parseToJsonElement(kbpsMap.content).jsonObject }
                .getOrNull()
                ?.let { availableQualities.putAll(it) }
        } else if (kbpsMap is JsonObject) {
            availableQualities.putAll(kbpsMap)
        }

        val candidates = listOf(
            "320" to response.r320Url,
            "192" to response.r192Url,
            "128" to response.mp3Url,
            "96" to response.m4aUrl,
        )
        var downloadUrl = ""
        for ((quality, candidateUrl) in candidates) {
            val value = availableQualities[quality] ?: continue
            if (qualityHasSize(value) && candidateUrl.isNotEmpty()) {
                downloadUrl = candidateUrl
                break
            }
        }
        if (downloadUrl.isEmpty()) {
            downloadUrl = firstNonEmpty(response.r320Url, response.r192Url, response.mp3Url, response.m4aUrl)
        }
        if (downloadUrl.isEmpty()) throw IllegalStateException("no valid download url found")

        return Song(
            id = songId,
            name = response.msong,
            artist = response.msinger,
            album = response.malbum,
            duration = response.minterval,
            source = "joox",
            url = downloadUrl,
            cover = response.img,
            link = jooxSingleLink(songId),
            extra = mapOf("songid" to songId),
        )
    }

    private suspend fun fetchPlaylistDetail(cookie: String, id: String): Pair<Playlist, List<Song>> {
        var pageError: Exception? = null
        try {
            return fetchPlaylistPageData(cookie, id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            pageError = error
        }

        val playlistId = normalizeJooxId(id)
        if (playlistId.isEmpty()) throw IllegalArgumentException("playlist id is empty")
        val songs = try {
            fetchPlaylistSongs(cookie, playlistId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Mirrors the Go provider: the page-fetch error wins when the fallback also fails.
            throw pageError ?: IllegalStateException("joox playlist page data not found")
        }

        val playlist = Playlist(
            id = playlistId,
            name = playlistId,
            cover = songs.firstOrNull()?.cover.orEmpty(),
            trackCount = songs.size,
            source = "joox",
            link = jooxPlaylistLink(playlistId),
            extra = mapOf(
                "type" to "playlist",
                "playlist_id" to playlistId,
            ),
        )
        return playlist to songs
    }

    private suspend fun fetchPlaylistSongsFromPage(cookie: String, id: String): List<Song> =
        fetchPlaylistPageData(cookie, id).second

    private suspend fun fetchPlaylistPageData(cookie: String, id: String): Pair<Playlist, List<Song>> {
        val playlistId = normalizeJooxId(id)
        if (playlistId.isEmpty()) throw IllegalArgumentException("playlist id is empty")

        var lastError: Exception? = null
        for (pageUrl in jooxPlaylistLinks(playlistId)) {
            try {
                return fetchPlaylistPageDataFromUrl(cookie, playlistId, pageUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("joox playlist page data not found")
    }

    private suspend fun fetchPlaylistPageDataFromUrl(
        cookie: String,
        playlistIdInput: String,
        pageUrl: String,
    ): Pair<Playlist, List<Song>> {
        val body = jooxGet(pageUrl, cookie)
        val nextData = extractNextData(body)
            ?: throw IllegalStateException("joox playlist page data not found")
        val response = json.decodeFromString<JooxPlaylistNextData>(nextData)
        val pageProps = response.props.pageProps

        var detail = pageProps.playlistDetail
        var tracks = pageProps.allPlaylistTracks.tracks.items
        var trackCount = firstNonZero(
            pageProps.allPlaylistTracks.tracks.totalCount,
            pageProps.allPlaylistTracks.tracks.totalCountCamel,
            pageProps.allPlaylistTracks.tracks.listCount,
        )
        if (tracks.isEmpty()) tracks = detail.trackList
        if (tracks.isEmpty()) {
            val contentPage = pageProps.content.page
            detail = contentPage.playlistDetail
            tracks = contentPage.allPlaylistTracks.tracks.items
            trackCount = firstNonZero(
                contentPage.allPlaylistTracks.tracks.totalCount,
                contentPage.allPlaylistTracks.tracks.totalCountCamel,
                contentPage.allPlaylistTracks.tracks.listCount,
            )
            if (tracks.isEmpty()) tracks = detail.trackList
        }
        if (tracks.isEmpty()) throw IllegalStateException("playlist has no songs")

        val playlistId = normalizeJooxId(
            firstNonEmpty(detail.id, detail.listId, detail.playlistId, playlistIdInput)
        )
        val name = firstNonEmpty(detail.name, detail.title, detail.playlistName, playlistId)
        val cover = firstNonEmpty(detail.imgSrc, detail.cover, detail.image, detail.pic)
        trackCount = firstNonZero(
            trackCount,
            detail.trackCount,
            detail.totalCount,
            detail.totalCountCamel,
            detail.listCount,
            tracks.size,
        )
        val creator = firstNonEmpty(
            detail.creator,
            detail.creatorName,
            detail.userName,
            detail.nickName,
            detail.ownerName,
            detail.author,
            detail.authorName,
            detail.user.name,
            detail.user.userName,
            detail.user.nickName,
            detail.user.nick,
            detail.owner.name,
            detail.owner.userName,
            detail.owner.nickName,
            detail.owner.nick,
            detail.creatorInfo.name,
            detail.creatorInfo.userName,
            detail.creatorInfo.nickName,
            detail.creatorInfo.nick,
        )
        val playlist = Playlist(
            id = playlistId,
            name = name,
            cover = cover,
            trackCount = trackCount,
            creator = creator,
            description = firstNonEmpty(detail.description, detail.intro, detail.desc),
            source = "joox",
            link = pageUrl,
            extra = mapOf(
                "type" to "playlist",
                "playlist_id" to playlistId,
            ),
        )

        val songs = tracks.mapNotNull { jooxSongFromTrack(it, name, cover, "") }
        if (songs.isEmpty()) throw IllegalStateException("playlist has no playable songs")
        return playlist to songs
    }

    private suspend fun fetchAlbumDetail(cookie: String, id: String): Pair<Playlist, List<Song>> {
        val albumData = fetchAlbumPageData(cookie, id)
        val albumId = normalizeJooxId(albumData.id)
        if (albumId.isEmpty()) throw IllegalStateException("album not found")

        var trackCount = albumData.trackList.totalCount
        if (trackCount == 0) {
            trackCount = if (albumData.trackList.listCount > 0) {
                albumData.trackList.listCount
            } else {
                albumData.trackList.items.size
            }
        }

        var cover = albumData.imgSrc.trim()
        if (cover.isEmpty() && albumData.trackList.items.isNotEmpty()) {
            cover = pickJooxImage(albumData.trackList.items[0].images)
        }

        val album = Playlist(
            id = albumId,
            name = albumData.title,
            cover = cover,
            trackCount = trackCount,
            creator = joinJooxArtists(albumData.artistList),
            description = albumData.description.trim(),
            source = "joox",
            link = jooxAlbumLink(albumId),
            extra = mapOf(
                "type" to "album",
                "album_id" to albumId,
                "publish_date" to albumData.publishDate.trim(),
            ),
        )

        val songs = mutableListOf<Song>()
        for (item in albumData.trackList.items) {
            val songId = normalizeJooxId(item.id)
            if (songId.isEmpty()) continue
            songs += Song(
                id = songId,
                name = item.name,
                artist = joinJooxArtists(item.artistList),
                album = firstNonEmpty(item.albumName, albumData.title),
                duration = item.playDuration,
                source = "joox",
                cover = firstNonEmpty(pickJooxImage(item.images), albumData.imgSrc.trim()),
                link = jooxSingleLink(songId),
                extra = mapOf(
                    "songid" to songId,
                    "album_id" to albumId,
                ),
            )
        }
        if (songs.isEmpty()) throw IllegalStateException("album has no songs")
        return album to songs
    }

    private suspend fun fetchAlbumPageData(cookie: String, id: String): JooxAlbumPageData {
        val albumId = normalizeJooxId(id)
        if (albumId.isEmpty()) throw IllegalArgumentException("album id is empty")

        val body = jooxGet(jooxAlbumLink(albumId), cookie)
        val nextData = extractNextData(body)
            ?: throw IllegalStateException("joox album page data not found")
        val response = json.decodeFromString<JooxAlbumNextData>(nextData)
        var albumData = response.props.pageProps.albumData
        if (normalizeJooxId(albumData.id).isEmpty()) {
            albumData = response.props.pageProps.content.page.albumData
        }
        if (normalizeJooxId(albumData.id).isEmpty()) {
            throw IllegalStateException("album data not found")
        }
        return albumData
    }

    private suspend fun fetchPlaylistSongs(cookie: String, id: String): List<Song> {
        val url = PLAYLIST_API + "?" + encodeParams(
            mapOf("id" to id, "country" to COUNTRY, "lang" to LANG)
        )
        val body = try {
            jooxGet(url, cookie)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return fallbackPlaylistSongsOrThrow(cookie, id, error)
        }

        val response = try {
            json.decodeFromString<JooxSongSearchResponse>(body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return fallbackPlaylistSongsOrThrow(
                cookie,
                id,
                IllegalStateException("joox playlist json error: ${error.message}", error),
            )
        }

        val songs = mutableListOf<Song>()
        var foundSongs = false
        for (section in response.sectionList) {
            for (item in section.itemList) {
                if (item.type != 5) continue
                for (songItem in item.song) {
                    val info = songItem.songInfo
                    if (info.id.isEmpty()) continue
                    songs += songFromSongInfo(info, fallbackAlbumCover = true)
                    foundSongs = true
                }
            }
        }

        if (!foundSongs) {
            val fallback = try {
                fetchPlaylistSongsFromPage(cookie, id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (!fallback.isNullOrEmpty()) return fallback
            throw IllegalStateException("no songs found in playlist or invalid playlist ID")
        }
        return songs
    }

    private suspend fun fallbackPlaylistSongsOrThrow(
        cookie: String,
        id: String,
        originalError: Exception,
    ): List<Song> {
        val fallback = try {
            fetchPlaylistSongsFromPage(cookie, id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (!fallback.isNullOrEmpty()) return fallback
        throw originalError
    }

    private suspend fun fetchPlaylistCategoriesPage(cookie: String): JooxPlaylistCategoryPage {
        val body = jooxGet(CATEGORY_PAGE, cookie)
        val nextData = extractNextData(body)
            ?: throw IllegalStateException("joox playlist category page data not found")
        val response = json.decodeFromString<JooxCategoryNextData>(nextData)
        val page = response.props.pageProps.mlList
        if (page.categories.isEmpty()) {
            throw IllegalStateException("joox playlist categories not found")
        }
        return page
    }

    private suspend fun jooxGet(url: String, cookie: String): String =
        NativeHttp.get(
            url,
            headers = mapOf("X-Forwarded-For" to X_FORWARDED_FOR),
            cookie = cookie.ifEmpty { DEFAULT_COOKIE },
            userAgent = USER_AGENT,
        )

    private fun songFromSongInfo(info: JooxSongInfo, fallbackAlbumCover: Boolean): Song {
        val artist = info.artistList.joinToString(" / ") { it.name }
        var cover = ""
        for (image in info.images) {
            if (image.width == 300) {
                cover = image.url
                break
            }
        }
        if (cover.isEmpty() && info.images.isNotEmpty()) cover = info.images[0].url
        if (fallbackAlbumCover && cover.isEmpty() && info.albumId.isNotEmpty()) {
            cover = jooxAlbumCoverFallback(info.albumId)
        }
        return Song(
            id = info.id,
            name = info.name,
            artist = artist,
            album = info.albumName,
            duration = info.playDuration,
            source = "joox",
            cover = cover,
            link = jooxSingleLink(info.id),
            extra = mapOf("songid" to info.id),
        )
    }

    private fun jooxSongFromTrack(
        item: JooxAlbumTrack,
        fallbackAlbum: String,
        fallbackCover: String,
        fallbackAlbumId: String,
    ): Song? {
        val songId = normalizeJooxId(item.id)
        if (songId.isEmpty()) return null

        val artists = if (item.artistList.isNotEmpty()) item.artistList else item.artistListCamel
        val albumId = normalizeJooxId(
            firstNonEmpty(item.albumId, item.albumIdCamel, fallbackAlbumId)
        )
        val extra = buildMap {
            put("songid", songId)
            if (albumId.isNotEmpty()) put("album_id", albumId)
        }
        return Song(
            id = songId,
            name = firstNonEmpty(item.name, item.title, "Unknown"),
            artist = firstNonEmpty(joinJooxArtists(artists), "Unknown"),
            album = firstNonEmpty(item.albumName, item.albumNameCamel, fallbackAlbum),
            albumId = albumId,
            duration = firstNonZero(item.playDuration, item.playDurationCamel, item.duration),
            source = "joox",
            cover = firstNonEmpty(
                pickJooxImage(item.images),
                item.imgSrc,
                item.cover,
                item.image,
                item.pic,
                fallbackCover,
            ),
            link = jooxSingleLink(songId),
            extra = extra,
        )
    }

    private fun extractNextData(body: String): String? =
        NEXT_DATA.find(body)?.groupValues?.getOrNull(1)

    private fun joinJooxArtists(artists: List<JooxArtist>): String =
        artists.mapNotNull { artist ->
            artist.name.trim().takeIf { it.isNotEmpty() }
        }.joinToString(" / ")

    private fun pickJooxImage(images: List<JooxImage>): String {
        for (preferred in intArrayOf(300, 1000, 100)) {
            for (image in images) {
                if (image.width == preferred && image.url.trim().isNotEmpty()) return image.url
            }
        }
        for (image in images) {
            if (image.url.trim().isNotEmpty()) return image.url
        }
        return ""
    }

    private fun normalizeJooxId(raw: String): String {
        var id = raw.trim()
        if (id.isEmpty()) return ""
        if (id.contains("%")) {
            pathUnescape(id)?.let { id = it }
            queryUnescape(id)?.let { id = it.replace(" ", "+") }
        }
        return id
    }

    private fun pathUnescape(value: String): String? = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun queryUnescape(value: String): String? = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun jooxSingleLink(id: String): String = "$HOME/hk/single/$id"

    private fun jooxAlbumLink(id: String): String = "$HOME/hk/album/${normalizeJooxId(id)}"

    private fun jooxPlaylistLink(id: String): String = jooxRegionalPlaylistLink("id", id)

    private fun jooxRegionalPlaylistLink(region: String, id: String): String =
        "$HOME/$region/playlist/${normalizeJooxId(id)}"

    private fun jooxPlaylistLinks(id: String): List<String> = listOf(
        jooxRegionalPlaylistLink("id", id),
        jooxRegionalPlaylistLink("sg", id),
        jooxRegionalPlaylistLink("hk", id),
    )

    private fun jooxAlbumCoverFallback(albumId: String): String {
        if (albumId.length < 2) return ""
        return "https://imgcache.joox.com/music/joox/photo/mid_album_300/" +
            "${albumId.takeLast(2)}/${albumId.takeLast(1)}/$albumId.jpg"
    }

    private fun decodeJooxBase64Text(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        var padded = trimmed
        val remainder = padded.length % 4
        if (remainder != 0) padded += "=".repeat(4 - remainder)
        val cleaned = stripBase64LineBreaks(padded)
        for (decoder in BASE64_DECODERS) {
            try {
                return String(decoder.decode(cleaned), Charsets.UTF_8).trim()
            } catch (_: IllegalArgumentException) {
                // Try the next encoding, matching the Go provider.
            }
        }
        return trimmed
    }

    private fun jooxCategoryPlaylistId(value: JsonElement?): String = when {
        value == null || value is JsonNull -> ""
        value is JsonPrimitive && value.isString -> normalizeJooxId(value.content)
        value is JsonPrimitive -> {
            val content = value.content.trim()
            content.toDoubleOrNull()?.toLong()?.toString() ?: content
        }
        else -> value.toString().trim()
    }

    private fun qualityHasSize(value: JsonElement): Boolean {
        val primitive = value as? JsonPrimitive ?: return false
        if (primitive is JsonNull) return false
        if (primitive.isString) {
            val text = primitive.content
            return text.isNotEmpty() && text != "0"
        }
        val number = primitive.content.toDoubleOrNull() ?: return false
        return number > 0
    }

    private fun firstNonEmpty(vararg values: String): String =
        values.firstOrNull { it.trim().isNotEmpty() }.orEmpty()

    private fun firstNonZero(vararg values: Int): Int =
        values.firstOrNull { it > 0 } ?: 0

    private fun stripBase64LineBreaks(value: String): String =
        value.replace("\r", "").replace("\n", "")

    private fun encodeParams(params: Map<String, String>): String =
        params.entries.sortedBy { it.key }.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
        const val DEFAULT_COOKIE =
            "wmid=142420656; user_type=1; country=id; session_key=2a5d97d05dc8fe238150184eaf3519ad;"
        const val X_FORWARDED_FOR = "36.73.34.109"
        const val COUNTRY = "sg"
        const val LANG = "zh_cn"
        const val HOME = "https://www.joox.com"
        const val SEARCH_API = "https://cache.api.joox.com/openjoox/v3/search"
        const val PLAYLIST_API = "https://cache.api.joox.com/openjoox/v3/playlist"
        const val SONG_INFO_API = "https://api.joox.com/web-fcgi-bin/web_get_songinfo"
        const val LYRIC_API = "https://api.joox.com/web-fcgi-bin/web_lyric"
        const val CATEGORY_PAGE = "https://www.joox.com/sg/playlist"

        val NEXT_DATA = Regex(
            """<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val SONG_LINK = Regex("""joox\.com/.*/single/([^/?#]+)""")
        val PLAYLIST_PATTERNS = listOf(
            Regex("""joox\.com/.*/playlist/([^/?#]+)"""),
            Regex("""(?:playlistid|playlist_id|id)=([^&]+)"""),
        )
        val ALBUM_PATTERNS = listOf(
            Regex("""joox\.com/.*/album/([^/?#]+)"""),
            Regex("""h_activity_id=([^&]+)"""),
            Regex("""albumid=([^&]+)"""),
        )
        val BASE64_DECODERS = listOf(Base64.getDecoder(), Base64.getUrlDecoder())
    }
}

@Serializable
private data class JooxArtist(
    val id: String = "",
    val name: String = "",
)

@Serializable
private data class JooxImage(
    val width: Int = 0,
    val url: String = "",
)

@Serializable
private data class JooxAlbumTrack(
    val id: String = "",
    val name: String = "",
    val title: String = "",
    @SerialName("album_id") val albumId: String = "",
    @SerialName("albumId") val albumIdCamel: String = "",
    @SerialName("album_name") val albumName: String = "",
    @SerialName("albumName") val albumNameCamel: String = "",
    @SerialName("artist_list") val artistList: List<JooxArtist> = emptyList(),
    @SerialName("artistList") val artistListCamel: List<JooxArtist> = emptyList(),
    @SerialName("play_duration") val playDuration: Int = 0,
    @SerialName("playDuration") val playDurationCamel: Int = 0,
    val duration: Int = 0,
    val images: List<JooxImage> = emptyList(),
    @SerialName("imgSrc") val imgSrc: String = "",
    val cover: String = "",
    val image: String = "",
    val pic: String = "",
)

@Serializable
private data class JooxAlbumTrackList(
    val items: List<JooxAlbumTrack> = emptyList(),
    @SerialName("list_count") val listCount: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
)

@Serializable
private data class JooxTrackPage(
    val items: List<JooxAlbumTrack> = emptyList(),
    @SerialName("list_count") val listCount: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("totalCount") val totalCountCamel: Int = 0,
)

@Serializable
private data class JooxAllPlaylistTracks(
    val tracks: JooxTrackPage = JooxTrackPage(),
)

@Serializable
private data class JooxAlbumPageData(
    val id: String = "",
    @SerialName("imgSrc") val imgSrc: String = "",
    val title: String = "",
    @SerialName("artistList") val artistList: List<JooxArtist> = emptyList(),
    @SerialName("publishDate") val publishDate: String = "",
    val description: String = "",
    @SerialName("trackList") val trackList: JooxAlbumTrackList = JooxAlbumTrackList(),
)

@Serializable
private data class JooxNamedUser(
    val name: String = "",
    @SerialName("userName") val userName: String = "",
    @SerialName("nickName") val nickName: String = "",
    val nick: String = "",
)

@Serializable
private data class JooxPlaylistPageData(
    val id: String = "",
    @SerialName("listId") val listId: String = "",
    @SerialName("playlistId") val playlistId: String = "",
    val name: String = "",
    val title: String = "",
    @SerialName("playlistName") val playlistName: String = "",
    @SerialName("imgSrc") val imgSrc: String = "",
    val cover: String = "",
    val image: String = "",
    val pic: String = "",
    val creator: String = "",
    @SerialName("creatorName") val creatorName: String = "",
    @SerialName("userName") val userName: String = "",
    @SerialName("nickName") val nickName: String = "",
    @SerialName("ownerName") val ownerName: String = "",
    val author: String = "",
    @SerialName("authorName") val authorName: String = "",
    val user: JooxNamedUser = JooxNamedUser(),
    val owner: JooxNamedUser = JooxNamedUser(),
    @SerialName("creatorInfo") val creatorInfo: JooxNamedUser = JooxNamedUser(),
    val description: String = "",
    val intro: String = "",
    val desc: String = "",
    @SerialName("trackCount") val trackCount: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("totalCount") val totalCountCamel: Int = 0,
    @SerialName("list_count") val listCount: Int = 0,
    @SerialName("trackList") val trackList: List<JooxAlbumTrack> = emptyList(),
)

@Serializable
private data class JooxPlaylistNextData(
    val props: JooxPlaylistProps = JooxPlaylistProps(),
)

@Serializable
private data class JooxPlaylistProps(
    @SerialName("pageProps") val pageProps: JooxPlaylistPageProps = JooxPlaylistPageProps(),
)

@Serializable
private data class JooxPlaylistPageProps(
    @SerialName("allPlaylistTracks") val allPlaylistTracks: JooxAllPlaylistTracks = JooxAllPlaylistTracks(),
    @SerialName("playlistDetailList") val playlistDetail: JooxPlaylistPageData = JooxPlaylistPageData(),
    val content: JooxPlaylistContent = JooxPlaylistContent(),
)

@Serializable
private data class JooxPlaylistContent(
    val page: JooxPlaylistContentPage = JooxPlaylistContentPage(),
)

@Serializable
private data class JooxPlaylistContentPage(
    @SerialName("allPlaylistTracks") val allPlaylistTracks: JooxAllPlaylistTracks = JooxAllPlaylistTracks(),
    @SerialName("playlistDetailList") val playlistDetail: JooxPlaylistPageData = JooxPlaylistPageData(),
)

@Serializable
private data class JooxAlbumNextData(
    val props: JooxAlbumProps = JooxAlbumProps(),
)

@Serializable
private data class JooxAlbumProps(
    @SerialName("pageProps") val pageProps: JooxAlbumPageProps = JooxAlbumPageProps(),
)

@Serializable
private data class JooxAlbumPageProps(
    @SerialName("albumData") val albumData: JooxAlbumPageData = JooxAlbumPageData(),
    val content: JooxAlbumContent = JooxAlbumContent(),
)

@Serializable
private data class JooxAlbumContent(
    val page: JooxAlbumContentPage = JooxAlbumContentPage(),
)

@Serializable
private data class JooxAlbumContentPage(
    @SerialName("albumData") val albumData: JooxAlbumPageData = JooxAlbumPageData(),
)

@Serializable
private data class JooxCategoryNextData(
    val props: JooxCategoryProps = JooxCategoryProps(),
)

@Serializable
private data class JooxCategoryProps(
    @SerialName("pageProps") val pageProps: JooxCategoryPageProps = JooxCategoryPageProps(),
)

@Serializable
private data class JooxCategoryPageProps(
    @SerialName("mlList") val mlList: JooxPlaylistCategoryPage = JooxPlaylistCategoryPage(),
)

@Serializable
private data class JooxPlaylistCategoryPage(
    @SerialName("category") val categories: List<JooxPlaylistCategory> = emptyList(),
)

@Serializable
private data class JooxPlaylistCategory(
    val type: Int = 0,
    val title: String = "",
    @SerialName("itemlist") val itemList: List<JooxCategoryPlaylistItem> = emptyList(),
)

@Serializable
private data class JooxCategoryPlaylistItem(
    val id: JsonElement? = null,
    val title: String = "",
    @SerialName("picurl") val picUrl: String = "",
)

@Serializable
private data class JooxSongSearchResponse(
    @SerialName("section_list") val sectionList: List<JooxSongSearchSection> = emptyList(),
)

@Serializable
private data class JooxSongSearchSection(
    @SerialName("section_title") val sectionTitle: String = "",
    @SerialName("section_type") val sectionType: Int = 0,
    @SerialName("item_list") val itemList: List<JooxSongSearchItem> = emptyList(),
)

@Serializable
private data class JooxSongSearchItem(
    val type: Int = 0,
    val song: List<JooxSongWrapper> = emptyList(),
)

@Serializable
private data class JooxSongWrapper(
    @SerialName("song_info") val songInfo: JooxSongInfo = JooxSongInfo(),
)

@Serializable
private data class JooxSongInfo(
    val id: String = "",
    val name: String = "",
    @SerialName("album_name") val albumName: String = "",
    @SerialName("album_id") val albumId: String = "",
    @SerialName("artist_list") val artistList: List<JooxArtist> = emptyList(),
    @SerialName("play_duration") val playDuration: Int = 0,
    val images: List<JooxImage> = emptyList(),
    @SerialName("vip_flag") val vipFlag: Int = 0,
)

@Serializable
private data class JooxPlaylistSearchResponse(
    @SerialName("section_list") val sectionList: List<JooxPlaylistSearchSection> = emptyList(),
)

@Serializable
private data class JooxPlaylistSearchSection(
    @SerialName("section_title") val sectionTitle: String = "",
    @SerialName("section_type") val sectionType: Int = 0,
    @SerialName("item_list") val itemList: List<JooxPlaylistSearchItem> = emptyList(),
)

@Serializable
private data class JooxPlaylistSearchItem(
    val type: Int = 0,
    @SerialName("editor_playlist") val editorPlaylist: JooxEditorPlaylist = JooxEditorPlaylist(),
)

@Serializable
private data class JooxEditorPlaylist(
    val id: String = "",
    val name: String = "",
    val images: List<JooxImage> = emptyList(),
)

@Serializable
private data class JooxAlbumSearchResponse(
    @SerialName("section_list") val sectionList: List<JooxAlbumSearchSection> = emptyList(),
)

@Serializable
private data class JooxAlbumSearchSection(
    @SerialName("section_type") val sectionType: Int = 0,
    @SerialName("item_list") val itemList: List<JooxAlbumSearchItem> = emptyList(),
)

@Serializable
private data class JooxAlbumSearchItem(
    val type: Int = 0,
    val album: JooxSearchAlbum = JooxSearchAlbum(),
)

@Serializable
private data class JooxSearchAlbum(
    val id: String = "",
    val name: String = "",
    val images: List<JooxImage> = emptyList(),
    @SerialName("publish_date") val publishDate: String = "",
    @SerialName("artist_list") val artistList: List<JooxArtist> = emptyList(),
)

@Serializable
private data class JooxSongInfoResponse(
    val msong: String = "",
    val msinger: String = "",
    val malbum: String = "",
    val img: String = "",
    val minterval: Int = 0,
    @SerialName("r320Url") val r320Url: String = "",
    @SerialName("r192Url") val r192Url: String = "",
    @SerialName("mp3Url") val mp3Url: String = "",
    @SerialName("m4aUrl") val m4aUrl: String = "",
    @SerialName("kbps_map") val kbpsMap: JsonElement? = null,
)

@Serializable
private data class JooxLyricResponse(
    val lyric: String = "",
)
