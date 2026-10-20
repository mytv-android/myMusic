package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.net.URLEncoder
import java.util.Locale

private val MIGU_INFO = ProviderInfo(
    id = "migu",
    name = "咪咕音乐",
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

class MiguProvider : BaseNativeProvider(MIGU_INFO) {
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
        val body = miguGet(
            SEARCH_URL,
            mapOf(
                "ua" to "Android_migu",
                "version" to "5.0.1",
                "text" to keyword,
                "pageNo" to page.toString(),
                "pageSize" to limit.toString(),
                "searchSwitch" to """{"song":1,"album":0,"singer":0,"tagSong":0,"mvSong":0,"songlist":0,"bestShow":1}""",
            ),
            cookie,
        )
        return json.decodeFromString<MiguSearchResponse>(body).songResultData.result.mapNotNull(::convertItem)
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val contentId = SONG_LINK.find(link)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("invalid migu link")
        val song = fetchSongDetail(cookie, contentId)
        val url = runCatching { downloadUrl(cookie, song) }.getOrNull().orEmpty()
        return if (url.isNotBlank()) song.copy(url = url) else song
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.source.isNotBlank() && song.source != "migu") throw IllegalArgumentException("source mismatch")
        val parts = song.id.split("|")
        val contentId = song.extra["content_id"]?.takeIf { it.isNotBlank() } ?: parts.getOrNull(0).orEmpty()
        if (contentId.isBlank()) throw IllegalStateException("invalid id structure and missing content id")
        val resourceType = song.extra["resource_type"]?.takeIf { it.isNotBlank() } ?: parts.getOrNull(1).orEmpty().ifBlank { "2" }
        val formatType = song.extra["format_type"]?.takeIf { it.isNotBlank() } ?: parts.getOrNull(2).orEmpty()
        val copyrightId = song.extra["copyright_id"].orEmpty()
        val songId = song.extra["song_id"].orEmpty()
        val albumId = song.extra["album_id"].orEmpty().ifBlank { song.albumId }
        val targetFormat = normalizeTone(formatType).ifBlank { normalizeTone(song.ext) }.ifBlank { "PQ" }
        val tones = listOf("ZQ32", targetFormat, "PQ").distinct()
        var lastMessage = ""

        for (tone in tones) {
            val params = buildMap {
                put("netType", "01")
                put("resourceType", resourceType.ifBlank { "2" })
                put("contentId", contentId)
                put("toneFlag", tone)
                if (copyrightId.isNotBlank()) put("copyrightId", copyrightId)
                if (songId.isNotBlank()) put("songId", songId)
                if (albumId.isNotBlank()) put("albumId", albumId)
            }
            val body = runCatching { miguGet(LISTEN_URL, params, cookie, ANDROID_UA, "https://music.migu.cn/") }.getOrNull()
                ?: continue
            val response = runCatching { json.decodeFromString<MiguListenResponse>(body) }.getOrNull() ?: continue
            if (response.code.isNotBlank() && response.code != "000000") {
                lastMessage = response.info.ifBlank { response.data.dialogInfo.text }
                continue
            }
            val url = normalizeDownloadUrl(response.data.url)
            if (url.isNotBlank() && !isEncryptedDirectUrl(url)) return url
            lastMessage = response.data.dialogInfo.text.ifBlank { response.info }.ifBlank { "empty download url" }
        }
        throw IllegalStateException(lastMessage.ifBlank { "migu returned no download quality" })
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != "migu") throw IllegalArgumentException("source mismatch")
        val contentId = song.extra["content_id"]?.takeIf { it.isNotBlank() }
            ?: song.id.split("|").firstOrNull().orEmpty()
        if (contentId.isBlank()) throw IllegalStateException("invalid migu song id")
        val body = miguGet(
            RESOURCE_INFO_URL,
            mapOf("resourceId" to contentId, "resourceType" to "2"),
            cookie,
        )
        val resource = json.decodeFromString<MiguLyricResponse>(body).resource.firstOrNull()
            ?: throw IllegalStateException("resource info not found")
        val lyricUrl = resource.lrcUrl.ifBlank { resource.lyricUrl }
        if (lyricUrl.isBlank()) throw IllegalStateException("lyric url not found")
        return NativeHttp.get(
            lyricUrl.replaceFirst("http://", "https://"),
            headers = mapOf("Referer" to "https://y.migu.cn/"),
            cookie = cookie,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        )
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> =
        searchMiguPlaylists(cookie, keyword, 1, 10)

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> {
        val playlistId = id.trim()
        if (playlistId.isBlank()) throw IllegalArgumentException("playlist id is empty")
        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        var page = 1
        while (page <= 20) {
            val body = miguGet(
                PLAYLIST_SONGS_URL,
                mapOf("pageNo" to page.toString(), "pageSize" to "50", "playlistId" to playlistId),
                cookie,
            )
            val response = json.decodeFromString<MiguSongsPageResponse>(body)
            if (response.code.isNotBlank() && response.code != "000000") {
                throw IllegalStateException("migu api error: ${response.info} (code ${response.code})")
            }
            if (response.data.songList.isEmpty()) break
            val before = songs.size
            response.data.songList.forEach { item ->
                val song = convertItem(item) ?: return@forEach
                val key = song.extra["content_id"].orEmpty().ifBlank { item.contentId }.ifBlank { song.id }.ifBlank { "${song.name}|${song.artist}" }
                if (seen.add(key)) songs += song
            }
            if (response.data.songList.size < 50 || songs.size == before) break
            if (response.data.totalCount > 0 && songs.size >= response.data.totalCount) break
            page += 1
        }
        if (songs.isEmpty()) throw IllegalStateException("playlist has no playable songs")
        return songs
    }

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val id = PLAYLIST_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(link)?.groupValues?.getOrNull(1)
        } ?: link.takeIf { it.isNotBlank() && !it.contains("/") }
        ?: throw IllegalArgumentException("invalid migu playlist link")
        return fetchPlaylistDetail(cookie, id)
    }

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val body = miguGet(
            SEARCH_URL,
            mapOf(
                "ua" to "Android_migu",
                "version" to "5.0.1",
                "text" to keyword,
                "pageNo" to "1",
                "pageSize" to "10",
                "searchSwitch" to """{"song":0,"album":1,"singer":0,"tagSong":0,"mvSong":0,"songlist":0,"bestShow":1}""",
            ),
            cookie,
        )
        return json.decodeFromString<MiguAlbumSearchResponse>(body).albumResultData.result.mapNotNull { item ->
            val id = item.id.trim()
            if (id.isBlank()) return@mapNotNull null
            Playlist(
                id = id,
                name = item.name.trim(),
                cover = pickImage(item.imgItems),
                creator = item.singer.trim(),
                description = item.desc.trim().ifBlank { item.publishDate.trim() },
                source = "migu",
                link = miguAlbumLink(id),
                extra = mapOf(
                    "type" to "album",
                    "album_id" to id,
                    "resource_type" to item.resourceType.trim().ifBlank { "2003" },
                    "publish_date" to item.publishDate.trim(),
                ),
            )
        }.ifEmpty { throw IllegalStateException("no albums found") }
    }

    override suspend fun albumSongs(cookie: String, id: String): List<Song> =
        fetchAlbumSongs(cookie, id).first

    override suspend fun parseAlbum(cookie: String, link: String): CollectionResult {
        val id = ALBUM_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(link)?.groupValues?.getOrNull(1)
        } ?: throw IllegalArgumentException("invalid migu album link")
        return fetchAlbumDetail(cookie, id)
    }

    override suspend fun categories(cookie: String): List<PlaylistCategory> {
        val result = mutableListOf(
            PlaylistCategory(id = "", name = "全部", group = "全部", source = "migu")
        )
        CATEGORY_KEYWORDS.forEach { item ->
            result += PlaylistCategory(
                id = item.first,
                name = item.first,
                group = item.second,
                source = "migu",
                hot = item.third,
            )
        }
        return result
    }

    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        val category = id.trim().ifBlank { "华语" }
        val playlists = searchMiguPlaylists(cookie, category, page.coerceAtLeast(1), limit.coerceAtLeast(1))
        if (playlists.isEmpty()) throw IllegalStateException("no category playlists found")
        return playlists.map { it.copy(extra = it.extra + ("category_id" to category)) }
    }

    override suspend fun topLists(cookie: String): List<TopList> {
        val errors = mutableListOf<String>()
        for (endpoint in TOP_LIST_INDEX_APIS) {
            val body = runCatching { miguGet(endpoint, emptyMap(), cookie) }.getOrElse {
                errors += it.message.orEmpty()
                continue
            }
            val response = runCatching { json.decodeFromString<MiguRankIndexResponse>(body) }.getOrElse {
                errors += it.message.orEmpty()
                continue
            }
            if (response.code.isNotBlank() && response.code != "000000") {
                errors += response.info.ifBlank { response.code }
                continue
            }
            val seen = mutableSetOf<String>()
            val lists = response.data.contents.flatMap { group ->
                val groupName = group.style.trim().ifBlank { "咪咕榜单" }
                group.contents.mapNotNull { item ->
                    val rankId = item.rankId.trim()
                    val name = item.rankName.trim()
                    if (rankId.isBlank() || name.isBlank() || !seen.add(rankId)) return@mapNotNull null
                    TopList(
                        id = rankId,
                        name = name,
                        cover = normalizeImage(item.imageUrl),
                        description = "咪咕音乐 · $groupName",
                        source = "migu",
                        link = "https://music.migu.cn/v5/#/rankDetail?id=$rankId&playlistType=charts",
                        extra = mapOf(
                            "rank_id" to kotlinx.serialization.json.JsonPrimitive(rankId),
                            "group" to kotlinx.serialization.json.JsonPrimitive(groupName),
                            "toplist_type" to kotlinx.serialization.json.JsonPrimitive("rank"),
                        ),
                    )
                }
            }
            if (lists.isNotEmpty()) return lists
            errors += "migu toplist index is empty"
        }
        throw IllegalStateException("migu toplist failed: ${errors.joinToString("; fallback: ")}")
    }

    override suspend fun topListSongs(cookie: String, id: String): List<Song> =
        fetchTopListSongs(cookie, id, 0)

    override suspend fun hotSongs(cookie: String, limit: Int): List<Song> {
        val safeLimit = when {
            limit <= 0 -> 50
            limit > 50 -> 50
            else -> limit
        }
        return fetchTopListSongs(cookie, HOT_SONG_TOP_LIST_ID, safeLimit)
    }

    private suspend fun searchMiguPlaylists(cookie: String, keyword: String, page: Int, limit: Int): List<Playlist> {
        val body = miguGet(
            SEARCH_URL,
            mapOf(
                "ua" to "Android_migu",
                "version" to "5.0.1",
                "text" to keyword,
                "pageNo" to page.coerceAtLeast(1).toString(),
                "pageSize" to limit.coerceAtLeast(1).toString(),
                "searchSwitch" to """{"song":0,"album":0,"singer":0,"tagSong":0,"mvSong":0,"songlist":1,"bestShow":1}""",
            ),
            cookie,
        )
        return json.decodeFromString<MiguPlaylistSearchResponse>(body).songListResultData.result.mapNotNull { item ->
            val id = item.id.trim()
            val name = item.name.trim()
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            Playlist(
                id = id,
                name = name,
                cover = item.musicListPicUrl.trim().ifBlank { pickImage(item.imgItems) },
                trackCount = item.musicNum.toIntOrNull() ?: 0,
                playCount = item.playNum.toLongOrNull() ?: 0,
                creator = item.userName.trim().ifBlank { item.ownerName.trim() },
                source = "migu",
                link = miguPlaylistLink(id),
                extra = mapOf(
                    "type" to "playlist",
                    "playlist_id" to id,
                    "resource_type" to item.resourceType.trim().ifBlank { "2021" },
                ),
            )
        }
    }

    private suspend fun fetchPlaylistDetail(cookie: String, id: String): CollectionResult {
        val playlistId = id.trim()
        if (playlistId.isBlank()) throw IllegalArgumentException("playlist id is empty")
        val songs = playlistSongs(cookie, playlistId)
        val playlist = runCatching { fetchPlaylistInfo(cookie, playlistId) }.getOrElse {
            Playlist(
                id = playlistId,
                name = playlistId,
                trackCount = songs.size,
                source = "migu",
                link = miguPlaylistLink(playlistId),
                extra = mapOf("type" to "playlist", "playlist_id" to playlistId),
            )
        }.let { info ->
            info.copy(
                trackCount = if (info.trackCount > 0) info.trackCount else songs.size,
                cover = info.cover.ifBlank { songs.firstOrNull()?.cover.orEmpty() },
            )
        }
        return CollectionResult(playlist, songs)
    }

    private suspend fun fetchPlaylistInfo(cookie: String, id: String): Playlist {
        val body = miguGet(
            RESOURCE_INFO_URL,
            mapOf("needSimple" to "00", "resourceType" to "2021", "resourceId" to id),
            cookie,
        )
        val response = json.decodeFromString<MiguPlaylistInfoResponse>(body)
        if (response.code.isNotBlank() && response.code != "000000") {
            throw IllegalStateException("migu api error: ${response.info} (code ${response.code})")
        }
        val info = response.resource.firstOrNull() ?: throw IllegalStateException("migu playlist info not found")
        val playlistId = info.musicListId.trim().ifBlank { id }
        return Playlist(
            id = playlistId,
            name = info.title.trim().ifBlank { playlistId },
            cover = info.originalImgUrl.trim()
                .ifBlank { info.imgItem.img }
                .ifBlank { info.imgItem.webpImg }
                .ifBlank { info.imgItem.imgOri },
            trackCount = info.musicNum,
            playCount = info.opNumItem.playNum.toLong(),
            creator = info.ownerName,
            description = info.summary,
            source = "migu",
            link = miguPlaylistLink(playlistId),
            extra = mapOf(
                "type" to "playlist",
                "playlist_id" to playlistId,
                "resource_type" to info.resourceType.ifBlank { "2021" },
            ),
        )
    }

    private suspend fun fetchAlbumSongs(cookie: String, id: String): Pair<List<Song>, Int> {
        val albumId = id.trim()
        if (albumId.isBlank()) throw IllegalArgumentException("album id is empty")
        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        var total = 0
        var page = 1
        while (page <= 20) {
            val body = miguGet(
                ALBUM_SONGS_URL,
                mapOf("albumId" to albumId, "pageNo" to page.toString(), "pageSize" to "50"),
                cookie,
            )
            val response = json.decodeFromString<MiguSongsPageResponse>(body)
            if (response.code.isNotBlank() && response.code != "000000") {
                throw IllegalStateException("migu api error: ${response.info} (code ${response.code})")
            }
            if (total == 0) total = response.data.totalCount
            if (response.data.songList.isEmpty()) break
            val before = songs.size
            response.data.songList.forEach { item ->
                val song = convertItem(item) ?: return@forEach
                val key = song.extra["content_id"].orEmpty().ifBlank { item.contentId }.ifBlank { song.id }.ifBlank { "${song.name}|${song.artist}" }
                if (seen.add(key)) songs += song
            }
            if (response.data.songList.size < 50 || songs.size == before) break
            if (total > 0 && songs.size >= total) break
            page += 1
        }
        if (songs.isEmpty()) throw IllegalStateException("album has no playable songs")
        return songs to total
    }

    private suspend fun fetchAlbumDetail(cookie: String, id: String): CollectionResult {
        val albumId = id.trim()
        if (albumId.isBlank()) throw IllegalArgumentException("album id is empty")
        val (songs, total) = fetchAlbumSongs(cookie, albumId)
        val body = miguGet(
            RESOURCE_INFO_URL,
            mapOf("needSimple" to "00", "resourceType" to "2003", "resourceId" to albumId),
            cookie,
        )
        val response = json.decodeFromString<MiguAlbumInfoResponse>(body)
        if (response.code.isNotBlank() && response.code != "000000") {
            throw IllegalStateException("migu api error: ${response.info} (code ${response.code})")
        }
        val info = response.resource.firstOrNull() ?: throw IllegalStateException("album not found")
        val resolvedId = info.albumId.trim().ifBlank { albumId }
        val trackCount = info.totalCount.trim().toIntOrNull()?.takeIf { it > 0 }
            ?: total.takeIf { it > 0 }
            ?: songs.size
        val album = Playlist(
            id = resolvedId,
            name = info.title.trim(),
            cover = pickImage(info.imgItems),
            trackCount = trackCount,
            creator = info.singer.trim(),
            description = info.summary.trim(),
            source = "migu",
            link = miguAlbumLink(resolvedId),
            extra = mapOf(
                "type" to "album",
                "album_id" to resolvedId,
                "resource_type" to info.resourceType.trim().ifBlank { "2003" },
                "publish_time" to info.publishTime.trim(),
                "publish_date" to info.publishDate.trim(),
                "publish_corp" to info.publishCorp.trim(),
                "publish_company" to info.publishCompany.trim(),
                "album_alias" to info.albumAliasName.trim(),
                "album_class" to info.albumClass.trim(),
                "language" to info.language.trim(),
                "translate_name" to info.translateName.trim(),
            ),
        )
        return CollectionResult(album, songs)
    }

    private suspend fun fetchTopListSongs(cookie: String, id: String, limit: Int): List<Song> {
        val rankId = id.trim()
        if (rankId.isBlank()) throw IllegalArgumentException("migu toplist id is empty")
        if (!rankId.all { it.isDigit() }) throw IllegalArgumentException("invalid migu toplist id: $rankId")
        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        var page = 1
        while (page <= 10) {
            val body = miguGet(TOP_LIST_SONGS_API, mapOf("pageNo" to page.toString(), "rankId" to rankId), cookie)
            val response = json.decodeFromString<MiguRankInfoResponse>(body)
            if (response.code.isNotBlank() && response.code != "000000") {
                throw IllegalStateException("migu toplist songs api error: ${response.info} (code ${response.code})")
            }
            if (response.data.contents.isEmpty()) break
            for (item in response.data.contents) {
                val songData = item.songData.trim()
                if (songData.isBlank()) continue
                val songItem = runCatching { json.decodeFromString<MiguSongItem>(songData) }.getOrNull() ?: continue
                val song = convertItem(songItem) ?: continue
                val key = song.extra["content_id"].orEmpty().ifBlank { song.id }.ifBlank { "${song.name}|${song.artist}" }
                if (!seen.add(key)) continue
                val ranked = song.copy(
                    extra = song.extra + buildMap {
                        put("rank", (songs.size + 1).toString())
                        put("toplist_id", rankId)
                        if (item.score.isNotBlank()) put("rank_score", item.score)
                    }
                )
                songs += ranked
                if (limit > 0 && songs.size >= limit) return songs
            }
            if (!response.data.hasNextPage) break
            page += 1
        }
        if (songs.isEmpty()) throw IllegalStateException("migu toplist $rankId has no songs")
        return songs
    }

    private suspend fun fetchSongDetail(cookie: String, contentId: String): Song {
        val body = miguGet(
            RESOURCE_INFO_URL,
            mapOf("resourceType" to "2", "resourceId" to contentId),
            cookie,
        )
        val response = json.decodeFromString<MiguSongDetailResponse>(body)
        val item = response.resource.firstOrNull() ?: throw IllegalStateException("song detail not found")
        return convertItem(item) ?: throw IllegalStateException("no valid format found for this song")
    }

    private fun convertItem(item: MiguSongItem): Song? {
        val formats = collectFormats(item)
        if (formats.isEmpty()) return null
        val artists = collectArtistNames(item)
        val songName = firstNonEmpty(item.name, item.songName)
        val albumName = item.albums.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: item.album
        var duration = item.duration.toLong()
        val candidates = mutableListOf<MiguCandidate>()
        formats.forEachIndexed { index, format ->
            val size = firstNonZero(
                format.androidSize,
                format.asize,
                format.size,
                format.isize,
            ).toLongOrNull() ?: 0L
            val ext = firstNonEmpty(format.androidFileType, format.fileType).ifBlank {
                formatExt(format.formatType, firstNonEmpty(format.aformat, format.iformat))
            }
            if (duration == 0L && size > 0) {
                val bitrate = when (format.formatType.uppercase(Locale.ROOT)) {
                    "PQ" -> 128_000L
                    "HQ" -> 320_000L
                    "LQ" -> 64_000L
                    else -> 0L
                }
                if (bitrate > 0) duration = size * 8 / bitrate
            }
            val price = format.price.toIntOrNull() ?: 0
            val tags = format.showTag.ifEmpty { format.showTags }
            val vipTag = tags.any { it.equals("vip", ignoreCase = true) } ||
                item.downloadTags.any { it.equals("vip", ignoreCase = true) }
            val hiddenPaid = item.chargeAuditions == "1" && price >= 200
            candidates += MiguCandidate(
                index = index,
                size = size,
                ext = ext,
                rank = formatRank(format.formatType),
                vip = vipTag || hiddenPaid,
            )
        }
        if (candidates.isEmpty()) return null
        val best = candidates.maxWith(compareBy<MiguCandidate> { it.rank }.thenBy { it.size })
        val bestFormat = formats[best.index]
        val bitrate = if (duration > 0 && best.size > 0) (best.size * 8 / 1000 / duration).toInt() else 0
        val cover = normalizeImage(
            pickImage(item.imgItems).ifBlank { pickImage(item.albumImgs) }
                .ifBlank { item.img1 }.ifBlank { item.img2 }.ifBlank { item.img3 }
        )
        val linkId = firstNonEmpty(item.contentId, item.copyrightId)
        val extra = buildMap {
            put("content_id", item.contentId)
            put("resource_type", bestFormat.resourceType)
            put("format_type", bestFormat.formatType)
            if (item.copyrightId.isNotBlank()) put("copyright_id", item.copyrightId)
            if (item.songId.isNotBlank()) put("song_id", item.songId)
            if (item.albumId.isNotBlank()) put("album_id", item.albumId)
        }
        return Song(
            id = "${item.contentId}|${bestFormat.resourceType}|${bestFormat.formatType}",
            name = songName,
            artist = artists.joinToString(" / "),
            album = albumName,
            size = best.size,
            duration = duration.toInt(),
            bitrate = bitrate,
            source = "migu",
            ext = best.ext,
            cover = cover,
            link = "https://music.migu.cn/v3/music/song/$linkId",
            extra = extra,
            isVip = item.chargeAuditions == "1" || best.vip,
        )
    }

    private fun collectFormats(item: MiguSongItem): List<MiguRateFormat> {
        val groups = mutableListOf<List<MiguRateFormat>>()
        groups += item.newRateFormats
        groups += item.rateFormats
        groups += item.audioFormats
        if (item.z3dCode.formatType.isNotBlank()) {
            groups += listOf(
                MiguRateFormat(
                    formatType = item.z3dCode.formatType,
                    resourceType = item.z3dCode.resourceType,
                    size = item.z3dCode.size,
                    androidSize = item.z3dCode.androidSize,
                    isize = item.z3dCode.isize,
                    asize = item.z3dCode.asize,
                    fileType = item.z3dCode.fileType,
                    androidFileType = item.z3dCode.androidFileType,
                    iformat = item.z3dCode.iformat,
                    aformat = item.z3dCode.aformat,
                    price = item.z3dCode.price,
                )
            )
        }
        val seen = mutableSetOf<String>()
        val result = mutableListOf<MiguRateFormat>()
        groups.forEach { group ->
            group.forEach { format ->
                val key = "${format.formatType.uppercase(Locale.ROOT)}|${format.resourceType.trim()}"
                if (key == "|" || !seen.add(key)) return@forEach
                result += format
            }
        }
        return result
    }

    private fun collectArtistNames(item: MiguSongItem): List<String> {
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun append(name: String) {
            val value = name.trim()
            if (value.isNotBlank() && seen.add(value)) names += value
        }
        item.singers.forEach { append(it.name) }
        item.singerList.forEach { append(it.name) }
        item.artists.forEach { append(it.name) }
        if (names.isEmpty()) item.singer.split('|').forEach(::append)
        return names
    }

    private fun pickImage(items: List<MiguImageItem>): String {
        for (preferred in listOf("02", "01", "03")) {
            items.firstOrNull { it.imgSizeType == preferred && it.img.isNotBlank() }?.let { return it.img }
        }
        return items.firstOrNull { it.img.isNotBlank() }?.img.orEmpty()
    }

    private fun normalizeImage(image: String): String {
        val value = image.trim()
        return when {
            value.isBlank() -> ""
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "https://d.musicapp.migu.cn$value"
            else -> value
        }
    }

    private fun normalizeDownloadUrl(raw: String): String {
        val value = raw.trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("ftp://218.200.160.122:21/") -> "https://freetyst.nf.migu.cn/" + value.removePrefix("ftp://218.200.160.122:21/")
            else -> value
        }
    }

    private fun isEncryptedDirectUrl(url: String): Boolean {
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
        return path.contains("wav_3d/") && !path.contains("wav_3d_60s/")
    }

    private fun normalizeTone(value: String): String {
        val tone = value.trim().removePrefix(".").uppercase(Locale.ROOT)
        return if (tone in TONE_EXTENSIONS) tone else ""
    }

    private fun formatExt(formatType: String, formatCode: String): String {
        return when (formatType.trim().uppercase(Locale.ROOT)) {
            "SQ", "ZQ", "ZQ24" -> "flac"
            "ZQ32", "Z3D", "3D60" -> "wav"
            "I3D" -> "m4a"
            "PQ", "HQ", "LQ" -> "mp3"
            else -> if (formatCode.startsWith("011")) "flac" else "mp3"
        }
    }

    private fun formatRank(formatType: String): Int = when (formatType.trim().uppercase(Locale.ROOT)) {
        "ZQ32" -> 80
        "ZQ", "ZQ24" -> 75
        "SQ" -> 70
        "HQ" -> 60
        "I3D", "Z3D" -> 50
        "3D60" -> 40
        "PQ" -> 30
        "LQ" -> 20
        else -> 10
    }

    private fun miguAlbumLink(id: String) = "https://music.migu.cn/v3/music/album/$id"

    private fun miguPlaylistLink(id: String) =
        "https://music.migu.cn/v5/#/playlist?playlistId=$id&playlistType=ordinary"

    private fun firstNonEmpty(vararg values: String): String = values.firstOrNull { it.trim().isNotBlank() }?.trim().orEmpty()

    private fun firstNonZero(vararg values: String): String =
        values.firstOrNull { it.trim().isNotBlank() && it.trim() != "0" }?.trim().orEmpty()

    private suspend fun miguGet(
        url: String,
        params: Map<String, String>,
        cookie: String,
        userAgent: String = USER_AGENT,
        referer: String = REFERER,
    ): String {
        val full = if (params.isEmpty()) url else "$url?${encodeParams(params)}"
        return NativeHttp.get(
            full,
            headers = buildMap {
                put("Referer", referer)
                if (userAgent == ANDROID_UA) {
                    put("channel", ANDROID_CHANNEL)
                    put("version", ANDROID_VERSION)
                }
            },
            cookie = cookie,
            userAgent = userAgent,
        )
    }

    private fun encodeParams(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    private data class MiguCandidate(
        val index: Int,
        val size: Long,
        val ext: String,
        val rank: Int,
        val vip: Boolean,
    )

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1"
        const val ANDROID_UA = "Android_migu/7.41.13 okhttp/3.12.13"
        const val ANDROID_CHANNEL = "0146832"
        const val ANDROID_VERSION = "7.41.13"
        const val REFERER = "http://music.migu.cn/"
        const val SEARCH_URL = "http://pd.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do"
        const val RESOURCE_INFO_URL = "http://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do"
        const val PLAYLIST_SONGS_URL = "https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0"
        const val ALBUM_SONGS_URL = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/queryAlbumSong"
        const val LISTEN_URL = "https://c.musicapp.migu.cn/MIGUM2.0/v2.1/content/listen-url"
        const val TOP_LIST_SONGS_API = "https://app.c.nf.migu.cn/bmw/rank/rank-info/v1.0"
        const val HOT_SONG_TOP_LIST_ID = "83048887"
        val TOP_LIST_INDEX_APIS = listOf(
            "https://app.u.nf.migu.cn/bmw/rank/rank-index/v2.0",
            "https://app.c.nf.migu.cn/pc/bmw/rank/rank-index/v1.0",
        )
        val TONE_EXTENSIONS = setOf("LQ", "PQ", "HQ", "SQ", "ZQ", "ZQ24", "ZQ32", "Z3D", "I3D", "3D60")
        val SONG_LINK = Regex("""music\.migu\.cn/v3/music/song/(\d+)""")
        val PLAYLIST_PATTERNS = listOf(
            Regex("""playlistId=(\d+)"""),
            Regex("""musicListId=(\d+)"""),
            Regex("""(?:playlist|songlist)/(\d+)"""),
        )
        val ALBUM_PATTERNS = listOf(
            Regex("""music\.migu\.cn/(?:v3|v5)/music/album/(\d+)"""),
            Regex("""albumId=(\d+)"""),
            Regex("""resourceId=(\d+)"""),
        )
        val CATEGORY_KEYWORDS = listOf(
            Triple("华语", "语种", true),
            Triple("欧美", "语种", true),
            Triple("日语", "语种", false),
            Triple("韩语", "语种", false),
            Triple("粤语", "语种", false),
            Triple("流行", "风格", true),
            Triple("摇滚", "风格", true),
            Triple("民谣", "风格", true),
            Triple("电子", "风格", false),
            Triple("说唱", "风格", false),
            Triple("古风", "风格", false),
            Triple("轻音乐", "风格", false),
            Triple("影视", "场景", true),
            Triple("ACG", "场景", false),
            Triple("治愈", "场景", false),
            Triple("运动", "场景", false),
            Triple("学习", "场景", false),
            Triple("睡前", "场景", false),
        )
    }
}

@Serializable
private data class MiguSearchResponse(val songResultData: MiguSongResult = MiguSongResult())

@Serializable
private data class MiguSongResult(val result: List<MiguSongItem> = emptyList())

@Serializable
private data class MiguSongItem(
    val id: String = "",
    val name: String = "",
    val songName: String = "",
    val songId: String = "",
    val singers: List<MiguArtistItem> = emptyList(),
    val artists: List<MiguArtistItem> = emptyList(),
    val singerList: List<MiguArtistItem> = emptyList(),
    val albums: List<MiguAlbumItem> = emptyList(),
    val albumId: String = "",
    val album: String = "",
    val singer: String = "",
    val contentId: String = "",
    val copyrightId: String = "",
    val chargeAuditions: String = "",
    val downloadTags: List<String> = emptyList(),
    val imgItems: List<MiguImageItem> = emptyList(),
    val albumImgs: List<MiguImageItem> = emptyList(),
    val newRateFormats: List<MiguRateFormat> = emptyList(),
    val rateFormats: List<MiguRateFormat> = emptyList(),
    val audioFormats: List<MiguRateFormat> = emptyList(),
    val z3dCode: MiguZ3DCode = MiguZ3DCode(),
    val img1: String = "",
    val img2: String = "",
    val img3: String = "",
    val duration: Int = 0,
)

@Serializable
private data class MiguArtistItem(val id: String = "", val name: String = "")

@Serializable
private data class MiguAlbumItem(val id: String = "", val name: String = "")

@Serializable
private data class MiguImageItem(val imgSizeType: String = "", val img: String = "")

@Serializable
private data class MiguRateFormat(
    val formatType: String = "",
    val resourceType: String = "",
    val size: String = "",
    val androidSize: String = "",
    val isize: String = "",
    val asize: String = "",
    val fileType: String = "",
    val androidFileType: String = "",
    val iformat: String = "",
    val aformat: String = "",
    val price: String = "",
    val showTag: List<String> = emptyList(),
    val showTags: List<String> = emptyList(),
)

@Serializable
private data class MiguZ3DCode(
    val formatType: String = "",
    val resourceType: String = "",
    val size: String = "",
    val androidSize: String = "",
    val isize: String = "",
    val asize: String = "",
    val fileType: String = "",
    val androidFileType: String = "",
    val iformat: String = "",
    val aformat: String = "",
    val price: String = "",
    val androidFileKey: String = "",
    val iosFileKey: String = "",
    val iosSize: String = "",
    val iosFileType: String = "",
    val url: String = "",
    val iosUrl: String = "",
    val androidUrl: String = "",
    val h5Url: String = "",
)

@Serializable
private data class MiguPlaylistSearchResponse(
    val songListResultData: MiguPlaylistSearchResult = MiguPlaylistSearchResult(),
)

@Serializable
private data class MiguPlaylistSearchResult(val result: List<MiguPlaylistSearchItem> = emptyList())

@Serializable
private data class MiguPlaylistSearchItem(
    val id: String = "",
    val name: String = "",
    val musicNum: String = "",
    val userName: String = "",
    val ownerName: String = "",
    val musicListPicUrl: String = "",
    val playNum: String = "",
    val resourceType: String = "",
    val imgItems: List<MiguImageItem> = emptyList(),
)

@Serializable
private data class MiguAlbumSearchResponse(
    val albumResultData: MiguAlbumSearchResult = MiguAlbumSearchResult(),
)

@Serializable
private data class MiguAlbumSearchResult(val result: List<MiguAlbumSearchItem> = emptyList())

@Serializable
private data class MiguAlbumSearchItem(
    val id: String = "",
    val resourceType: String = "",
    val name: String = "",
    val singer: String = "",
    val publishDate: String = "",
    val desc: String = "",
    val imgItems: List<MiguImageItem> = emptyList(),
)

@Serializable
private data class MiguSongsPageResponse(
    val code: String = "",
    val info: String = "",
    val data: MiguSongsPage = MiguSongsPage(),
)

@Serializable
private data class MiguSongsPage(
    val songList: List<MiguSongItem> = emptyList(),
    val totalCount: Int = 0,
)

@Serializable
private data class MiguPlaylistInfoResponse(
    val code: String = "",
    val info: String = "",
    val resource: List<MiguPlaylistInfo> = emptyList(),
)

@Serializable
private data class MiguPlaylistInfo(
    val musicListId: String = "",
    val title: String = "",
    val summary: String = "",
    val musicNum: Int = 0,
    val originalImgUrl: String = "",
    val ownerName: String = "",
    val resourceType: String = "",
    val imgItem: MiguPlaylistImage = MiguPlaylistImage(),
    val opNumItem: MiguPlaylistOpNum = MiguPlaylistOpNum(),
)

@Serializable
private data class MiguPlaylistImage(
    val img: String = "",
    val imgOri: String = "",
    val webpImg: String = "",
)

@Serializable
private data class MiguPlaylistOpNum(val playNum: Int = 0)

@Serializable
private data class MiguAlbumInfoResponse(
    val code: String = "",
    val info: String = "",
    val resource: List<MiguAlbumInfo> = emptyList(),
)

@Serializable
private data class MiguAlbumInfo(
    val resourceType: String = "",
    val albumId: String = "",
    val imgItems: List<MiguImageItem> = emptyList(),
    val title: String = "",
    val singer: String = "",
    val summary: String = "",
    val totalCount: String = "",
    val publishTime: String = "",
    val publishCorp: String = "",
    val albumAliasName: String = "",
    val albumClass: String = "",
    val language: String = "",
    val publishCompany: String = "",
    val publishDate: String = "",
    val translateName: String = "",
)

@Serializable
private data class MiguSongDetailResponse(val resource: List<MiguSongItem> = emptyList())

@Serializable
private data class MiguLyricResponse(val resource: List<MiguLyricResource> = emptyList())

@Serializable
private data class MiguLyricResource(val lrcUrl: String = "", val lyricUrl: String = "")

@Serializable
private data class MiguListenResponse(
    val code: String = "",
    val info: String = "",
    val data: MiguListenData = MiguListenData(),
)

@Serializable
private data class MiguListenData(
    val url: String = "",
    val formatType: String = "",
    val dialogInfo: MiguDialogInfo = MiguDialogInfo(),
    val songItem: MiguSongItem = MiguSongItem(),
)

@Serializable
private data class MiguDialogInfo(val text: String = "")

@Serializable
private data class MiguRankIndexResponse(
    val code: String = "",
    val info: String = "",
    val data: MiguRankIndexData = MiguRankIndexData(),
)

@Serializable
private data class MiguRankIndexData(val contents: List<MiguRankGroup> = emptyList())

@Serializable
private data class MiguRankGroup(
    val view: String = "",
    val style: String = "",
    val contents: List<MiguRankBrief> = emptyList(),
)

@Serializable
private data class MiguRankBrief(
    val view: String = "",
    val rankId: String = "",
    val rankName: String = "",
    val imageUrl: String = "",
)

@Serializable
private data class MiguRankInfoResponse(
    val code: String = "",
    val info: String = "",
    val data: MiguRankInfoData = MiguRankInfoData(),
)

@Serializable
private data class MiguRankInfoData(
    val title: String = "",
    val desc: String = "",
    val updateTime: String = "",
    val totalCount: Int = 0,
    val hasNextPage: Boolean = false,
    val contents: List<MiguRankInfoItem> = emptyList(),
)

@Serializable
private data class MiguRankInfoItem(
    val resType: String = "",
    val resId: String = "",
    val txt: String = "",
    val txt2: String = "",
    val txt3: String = "",
    val score: String = "",
    val songData: String = "",
)
