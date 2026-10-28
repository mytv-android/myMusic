package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale
import java.util.zip.InflaterInputStream
import kotlin.math.roundToLong
import kotlin.random.Random

private val KUWO_INFO = ProviderInfo(
    id = "kuwo",
    name = "酷我音乐",
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
    playlistCategories = true,
    topLists = true,
    topListSongs = true,
    hotSongs = true,
)

class KuwoProvider : BaseNativeProvider(KUWO_INFO) {
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
        val url = "$SEARCH_MUSIC_API?" + query(
            "vipver" to "1",
            "client" to "kt",
            "ft" to "music",
            "cluster" to "0",
            "strategy" to "2012",
            "encoding" to "utf8",
            "rformat" to "json",
            "mobi" to "1",
            "issubtitle" to "1",
            "show_copyright_off" to "1",
            "pn" to (page - 1).toString(),
            "rn" to limit.toString(),
            "all" to keyword,
        )
        val body = NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = KUWO_USER_AGENT)
        val response = try {
            json.decodeFromString<KuwoSearchResponse>(body)
        } catch (e: Exception) {
            throw IllegalStateException("kuwo json parse error: ${e.message}", e)
        }
        return response.abslist.orEmpty().mapNotNull { item ->
            if (item.bitSwitch == 0) return@mapNotNull null
            val cleanId = item.musicRid.removePrefix("MUSIC_")
            Song(
                id = cleanId,
                name = item.songName,
                artist = item.artist,
                album = item.album,
                duration = item.duration.toIntOrNull() ?: 0,
                size = parseSizeFromMInfo(item.minfo),
                bitrate = parseBitrateFromMInfo(item.minfo),
                source = "kuwo",
                cover = item.htsMvPic,
                link = "http://www.kuwo.cn/play_detail/$cleanId",
                extra = mapOf("rid" to cleanId),
            )
        }
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val match = Regex("play_detail/(\\d+)").find(link)
            ?: throw IllegalArgumentException("invalid kuwo link, rid not found")
        return fetchFullSongInfo(cookie, match.groupValues[1])
    }

    override suspend fun downloadUrl(cookie: String, song: Song): String {
        if (song.source != "kuwo") throw IllegalArgumentException("source mismatch")
        if (song.url.isNotEmpty()) return song.url
        val rid = song.extra["rid"]?.takeIf { it.isNotEmpty() } ?: song.id
        return fetchAudioUrl(cookie, rid)
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != "kuwo") throw IllegalArgumentException("source mismatch")
        val rid = song.extra["rid"]?.takeIf { it.isNotEmpty() } ?: song.id
        val newLyrics = runCatching { fetchNewLyrics(cookie, rid) }.getOrNull()
        if (newLyrics != null && newLyrics.isNotBlank()) return newLyrics
        return fetchSongInfoLyrics(cookie, rid)
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val body = searchCollection(cookie, keyword, "playlist")
        val response = try {
            json.decodeFromString<KuwoPlaylistSearchResponse>(legacyJson(body))
        } catch (e: Exception) {
            throw IllegalStateException("kuwo playlist json parse error: ${e.message}", e)
        }
        return response.abslist.orEmpty().map { item ->
            var cover = item.pic
            if (cover.isNotEmpty()) {
                cover = cover.replaceFirst("_150.", "_700.")
                if (!cover.startsWith("http")) cover = "http://$cover"
            }
            Playlist(
                id = item.playlistid,
                name = item.name,
                cover = cover,
                trackCount = item.songnum.trim().toIntOrNull() ?: 0,
                creator = item.nickname,
                description = item.intro,
                source = "kuwo",
                link = "http://www.kuwo.cn/playlist_detail/${item.playlistid}",
            )
        }
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> =
        fetchPlaylistDetail(cookie, id).second

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val match = Regex("playlist_detail/(\\d+)").find(link)
            ?: throw IllegalArgumentException("invalid kuwo playlist link")
        val (playlist, songs) = fetchPlaylistDetail(cookie, match.groupValues[1])
        return CollectionResult(playlist, songs)
    }

    override suspend fun recommendedPlaylists(cookie: String): List<Playlist> {
        val url = "$RECOMMEND_API?" + query("pn" to "0", "rn" to "30", "order" to "hot")
        val body = NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = KUWO_USER_AGENT)
        val response = try {
            json.decodeFromString<KuwoRecommendedResponse>(body)
        } catch (e: Exception) {
            throw IllegalStateException("kuwo recommend json parse error: ${e.message}", e)
        }
        if (response.code != 200) throw IllegalStateException("kuwo api error code: ${response.code}")
        val playlists = response.data?.data.orEmpty().map { item ->
            var cover = item.img
            if (cover.isNotEmpty() && !cover.startsWith("http")) cover = "http://$cover"
            var trackCount = item.songnum.kuwoInt()
            if (trackCount == 0) trackCount = item.total.kuwoInt()
            if (trackCount == 0) trackCount = item.count.kuwoInt()
            if (trackCount == 0) trackCount = item.musicnum.kuwoInt()
            Playlist(
                id = item.id,
                name = item.name,
                cover = cover,
                trackCount = trackCount,
                playCount = item.listencnt.kuwoInt().toLong(),
                creator = item.uname,
                description = item.desc,
                source = "kuwo",
                link = "http://www.kuwo.cn/playlist_detail/${item.id}",
            )
        }
        if (playlists.isEmpty()) throw IllegalStateException("no recommended playlists found")
        return playlists
    }

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val body = searchCollection(cookie, keyword, "album")
        val response = try {
            json.decodeFromString<KuwoAlbumSearchResponse>(legacyJson(body))
        } catch (e: Exception) {
            throw IllegalStateException("kuwo album json parse error: ${e.message}", e)
        }
        val albums = response.albumlist.orEmpty().mapNotNull { item ->
            val albumId = firstNonEmpty(item.albumid, item.id)
            if (albumId.isEmpty()) return@mapNotNull null
            Playlist(
                id = albumId,
                name = normalizeKuwoText(item.name),
                cover = normalizeKuwoImageUrl(firstNonEmpty(item.htsImg, item.img)),
                trackCount = item.musiccnt.trim().toIntOrNull() ?: 0,
                playCount = (item.playcnt.trim().toIntOrNull() ?: 0).toLong(),
                creator = normalizeKuwoText(firstNonEmpty(item.aartist, item.artist)),
                description = normalizeKuwoText(item.info),
                source = "kuwo",
                link = "http://www.kuwo.cn/album_detail/$albumId",
                extra = mapOf(
                    "type" to "album",
                    "album_id" to albumId,
                    "company" to normalizeKuwoText(item.company),
                    "publish_time" to item.pub.trim(),
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
            Regex("album_detail/(\\d+)"),
            Regex("album/(\\d+)"),
            Regex("albumid=(\\d+)"),
        )
        for (pattern in patterns) {
            val match = pattern.find(link) ?: continue
            val (playlist, songs) = fetchAlbumDetail(cookie, match.groupValues[1])
            return CollectionResult(playlist, songs)
        }
        throw IllegalArgumentException("invalid kuwo album link")
    }

    override suspend fun categories(cookie: String): List<PlaylistCategory> {
        val url = "$CATEGORY_TAG_API?" + query(
            "cmd" to "rcm_keyword_playlist",
            "user" to "0",
            "prod" to "kwplayer_pc_9.1.1.2",
            "vipver" to "9.1.1.2",
            "source" to "kwplayer_pc_9.1.1.2",
            "loginUid" to "0",
            "loginSid" to "0",
            "appUid" to "38668888",
        )
        val body = NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = KUWO_USER_AGENT)
        val response = try {
            json.decodeFromString<KuwoCategoryResponse>(body)
        } catch (e: Exception) {
            throw IllegalStateException("kuwo playlist category json parse error: ${e.message}", e)
        }
        if (response.code != 200) {
            throw IllegalStateException("kuwo playlist category api error: ${response.msg} (code ${response.code})")
        }
        val result = mutableListOf(
            PlaylistCategory(id = "", name = "全部", group = "全部", source = "kuwo"),
        )
        for (group in response.data.orEmpty()) {
            val groupName = normalizeKuwoText(group.name)
            for (item in group.data.orEmpty()) {
                val id = item.id.trim()
                val name = normalizeKuwoText(item.name)
                val digest = item.digest.trim()
                if (id.isEmpty() || name.isEmpty() || (digest.isNotEmpty() && digest != "10000")) continue
                result += PlaylistCategory(
                    id = id,
                    name = name,
                    group = groupName,
                    source = "kuwo",
                    hot = item.extend.uppercase().contains("HOT"),
                )
            }
        }
        if (result.size == 1) throw IllegalStateException("no playlist categories found")
        return result
    }

    override suspend fun categoryPlaylists(cookie: String, id: String, page: Int, limit: Int): List<Playlist> {
        val categoryId = id.trim()
        val safePage = if (page < 1) 1 else page
        val safeLimit = if (limit < 1) 20 else limit
        val params = mutableListOf(
            "loginUid" to "0",
            "loginSid" to "0",
            "appUid" to "38668888",
            "pn" to safePage.toString(),
            "rn" to safeLimit.toString(),
        )
        val endpoint: String
        if (categoryId.isEmpty()) {
            endpoint = "getRcmPlayList"
            params += "order" to "hot"
        } else {
            endpoint = "getTagPlayList"
            params += "id" to categoryId
        }
        val url = "$CATEGORY_PLAYLIST_API/$endpoint?" + query(*params.toTypedArray())
        val body = NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = KUWO_USER_AGENT)
        val response = try {
            json.decodeFromString<KuwoCategoryPlaylistResponse>(body)
        } catch (e: Exception) {
            throw IllegalStateException("kuwo category playlist json parse error: ${e.message}", e)
        }
        if (response.code != 200) {
            throw IllegalStateException("kuwo category playlist api error: ${response.msg} (code ${response.code})")
        }
        val playlists = response.data?.data.orEmpty().mapNotNull { item ->
            val playlistId = item.id.kuwoString()
            val name = normalizeKuwoText(item.name)
            if (playlistId.isEmpty() || name.isEmpty()) return@mapNotNull null
            var trackCount = item.songnum.kuwoInt()
            if (trackCount == 0) trackCount = item.total.kuwoInt()
            if (trackCount == 0) trackCount = item.count.kuwoInt()
            if (trackCount == 0) trackCount = item.musicnum.kuwoInt()
            Playlist(
                id = playlistId,
                name = name,
                cover = normalizeKuwoImageUrl(item.img),
                trackCount = trackCount,
                playCount = item.listencnt.kuwoInt().toLong(),
                creator = normalizeKuwoText(item.uname),
                description = normalizeKuwoText(firstNonEmpty(item.desc, item.info)),
                source = "kuwo",
                link = "http://www.kuwo.cn/playlist_detail/$playlistId",
                extra = mapOf("category_id" to categoryId),
            )
        }
        if (playlists.isEmpty()) throw IllegalStateException("no category playlists found")
        return playlists
    }

    override suspend fun topLists(cookie: String): List<TopList> {
        val body = NativeHttp.get(
            TOP_LIST_PAGE,
            headers = mapOf("Referer" to TOP_LIST_PAGE),
            cookie = cookie,
            randomIp = true,
            userAgent = KUWO_USER_AGENT,
        )
        return parseKuwoTopLists(body)
    }

    override suspend fun topListSongs(cookie: String, id: String): List<Song> =
        fetchTopListSongs(cookie, id, 0)

    override suspend fun hotSongs(cookie: String, limit: Int): List<Song> {
        val safeLimit = when {
            limit <= 0 -> DEFAULT_HOT_SONG_LIMIT
            limit > MAX_HOT_SONG_LIMIT -> MAX_HOT_SONG_LIMIT
            else -> limit
        }
        return fetchTopListSongs(cookie, HOT_SONG_TOP_LIST_ID, safeLimit)
    }

    private suspend fun searchCollection(cookie: String, keyword: String, ft: String): String {
        val url = "$SEARCH_LEGACY_API?" + query(
            "all" to keyword,
            "ft" to ft,
            "itemset" to "web_2013",
            "client" to "kt",
            "pcmp4" to "1",
            "geo" to "c",
            "vipver" to "1",
            "pn" to "0",
            "rn" to "10",
            "rformat" to "json",
            "encoding" to "utf8",
        )
        return NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = KUWO_USER_AGENT)
    }

    private suspend fun fetchPlaylistDetail(cookie: String, id: String): Pair<Playlist, List<Song>> {
        var totalSongs = 0
        val seen = mutableSetOf<String>()
        var playlist: Playlist? = null
        val songs = mutableListOf<Song>()

        for (page in 0 until KUWO_PLAYLIST_MAX_PAGES) {
            val body = fetchPlaylistDetailPage(cookie, id, page)
            val response = try {
                json.decodeFromString<KuwoPlaylistDetailResponse>(body)
            } catch (e: Exception) {
                throw IllegalStateException("kuwo playlist detail json error: ${e.message}", e)
            }

            if (page == 0) {
                if (response.musiclist.orEmpty().isEmpty()) {
                    throw IllegalStateException("playlist is empty or id is invalid")
                }
                totalSongs = response.total.kuwoInt()
                if (totalSongs == 0) totalSongs = response.validtotal.kuwoInt()
                playlist = Playlist(
                    id = id,
                    name = normalizeKuwoText(response.title),
                    cover = normalizeKuwoImageUrl(response.pic),
                    description = normalizeKuwoText(response.info),
                    creator = normalizeKuwoText(response.uname),
                    playCount = response.playnum.kuwoInt().toLong(),
                    trackCount = totalSongs,
                    source = "kuwo",
                    link = "http://www.kuwo.cn/playlist_detail/$id",
                )
            }

            val musicList = response.musiclist.orEmpty()
            if (musicList.isEmpty()) break
            for (item in musicList) {
                val songId = item.id.trim()
                if (songId.isEmpty() || !seen.add(songId)) continue
                songs += playlistSongOf(item)
            }
            if (musicList.size < KUWO_PLAYLIST_PAGE_SIZE) break
            if (totalSongs > 0 && songs.size >= totalSongs) break
        }

        val info = playlist ?: throw IllegalStateException("playlist is empty or id is invalid")
        if (songs.isEmpty()) throw IllegalStateException("playlist is empty or id is invalid")
        return (if (info.trackCount == 0) info.copy(trackCount = songs.size) else info) to songs
    }

    private suspend fun fetchPlaylistDetailPage(cookie: String, id: String, page: Int): String {
        val url = "$PLAYLIST_DETAIL_API?" + query(
            "op" to "getlistinfo",
            "pid" to id,
            "pn" to page.toString(),
            "rn" to KUWO_PLAYLIST_PAGE_SIZE.toString(),
            "encode" to "utf8",
            "keyset" to "pl2012",
            "identity" to "kuwo",
            "pcmp4" to "1",
            "vipver" to "1",
            "newver" to "1",
        )
        return NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = KUWO_USER_AGENT)
    }

    private fun playlistSongOf(item: KuwoPlaylistMusic): Song {
        val songId = item.id.trim()
        val name = normalizeKuwoText(item.name).ifEmpty { normalizeKuwoText(item.songName) }
        val artist = normalizeKuwoText(item.artist).ifEmpty { normalizeKuwoText(item.artistName) }
        return Song(
            id = songId,
            name = name,
            artist = artist,
            album = normalizeKuwoText(item.album),
            duration = item.duration.kuwoInt(),
            cover = normalizeKuwoImageUrl(item.albumpic),
            source = "kuwo",
            link = "http://www.kuwo.cn/play_detail/$songId",
            extra = mapOf("rid" to songId),
        )
    }

    private suspend fun fetchAlbumDetail(cookie: String, id: String): Pair<Playlist, List<Song>> {
        val albumId = id.trim()
        if (albumId.isEmpty()) throw IllegalArgumentException("album id is empty")

        var legacyError: Throwable? = null
        var legacyResult: Pair<Playlist, List<Song>>? = null
        try {
            val result = fetchAlbumDetailFromLegacyApi(cookie, albumId)
            if (result.second.isNotEmpty()) return result
            legacyResult = result
        } catch (e: Exception) {
            legacyError = e
        }

        var pageError: Throwable? = null
        try {
            val result = fetchAlbumDetailFromPage(cookie, albumId)
            if (result.second.isNotEmpty()) return result
            return result
        } catch (e: Exception) {
            pageError = e
        }

        if (legacyError != null && pageError != null) {
            throw IllegalStateException(
                "kuwo album detail failed: legacy api: ${legacyError.message}; page parse: ${pageError.message}",
                pageError,
            )
        }
        legacyError?.let { throw it }
        pageError?.let { throw it }
        return legacyResult ?: throw IllegalStateException("album not found")
    }

    private suspend fun fetchAlbumDetailFromLegacyApi(
        cookie: String,
        id: String,
    ): Pair<Playlist, List<Song>> {
        var album: Playlist? = null
        var totalSongs = 0
        val seen = mutableSetOf<String>()
        val songs = mutableListOf<Song>()
        var page = 0

        while (true) {
            val url = "$SEARCH_LEGACY_API?" + query(
                "pn" to page.toString(),
                "rn" to ALBUM_DETAIL_PAGE_SIZE.toString(),
                "stype" to "albuminfo",
                "albumid" to id,
                "sortby" to "0",
                "alflac" to "1",
                "show_copyright_off" to "1",
                "pcmp4" to "1",
                "encoding" to "utf8",
            )
            val body = NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = KUWO_USER_AGENT)
            val response = try {
                json.parseToJsonElement(legacyJson(body)).jsonObject
            } catch (e: Exception) {
                throw IllegalStateException("kuwo album detail json error: ${e.message}", e)
            }

            if (album == null) {
                val albumId = firstNonEmpty(
                    response["albumid"].kuwoString(),
                    response["id"].kuwoString(),
                    id,
                )
                totalSongs = response["songnum"].kuwoInt()
                album = Playlist(
                    id = albumId,
                    name = normalizeKuwoText(response["name"].kuwoString()),
                    cover = normalizeKuwoImageUrl(
                        firstNonEmpty(response["hts_img"].kuwoString(), response["img"].kuwoString()),
                    ),
                    trackCount = totalSongs,
                    creator = normalizeKuwoText(
                        firstNonEmpty(response["aartist"].kuwoString(), response["artist"].kuwoString()),
                    ),
                    description = normalizeKuwoText(response["info"].kuwoString()),
                    source = "kuwo",
                    link = "http://www.kuwo.cn/album_detail/$albumId",
                    extra = mapOf(
                        "type" to "album",
                        "album_id" to albumId,
                        "company" to normalizeKuwoText(response["company"].kuwoString()),
                        "publish_time" to response["pub"].kuwoString().trim(),
                        "lang" to normalizeKuwoText(response["lang"].kuwoString()),
                    ),
                )
            }

            val musicList: List<JsonElement> = response["musiclist"]
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: emptyList()
            if (musicList.isEmpty()) {
                if (page == 0) throw IllegalStateException("album $id detail api returned empty musiclist")
                break
            }

            for (rawItem in musicList) {
                val item = rawItem as? JsonObject ?: continue
                val rid = firstNonEmpty(item["id"].kuwoString(), item["musicrid"].kuwoString())
                if (rid.isEmpty() || !seen.add(rid)) continue

                val currentAlbum = album
                var songCover = normalizeKuwoImageUrl(
                    firstNonEmpty(
                        item["pic120"].kuwoString(),
                        item["web_albumpic_short"].kuwoString(),
                    ),
                )
                if (songCover.isEmpty() && currentAlbum != null) songCover = currentAlbum.cover

                val extra = mutableMapOf("rid" to rid)
                if (currentAlbum != null) extra["album_id"] = currentAlbum.id
                val track = item["track"].kuwoString().trim()
                if (track.isNotEmpty()) extra["track"] = track
                val subtitle = normalizeKuwoText(item["subtitle"].kuwoString())
                if (subtitle.isNotEmpty()) extra["subtitle"] = subtitle
                val bitSwitch = item["bitSwitch"].kuwoInt()
                if (bitSwitch > 0) extra["bit_switch"] = bitSwitch.toString()

                songs += Song(
                    id = rid,
                    name = normalizeKuwoText(
                        firstNonEmpty(item["name"].kuwoString(), item["songname"].kuwoString()),
                    ),
                    artist = normalizeKuwoText(
                        firstNonEmpty(item["aartist"].kuwoString(), item["artist"].kuwoString()),
                    ),
                    album = normalizeKuwoText(
                        firstNonEmpty(item["album"].kuwoString(), currentAlbum?.name.orEmpty()),
                    ),
                    albumId = currentAlbum?.id.orEmpty(),
                    duration = item["duration"].kuwoInt(),
                    size = parseSizeFromMInfo(item["MINFO"].kuwoString()),
                    bitrate = parseBitrateFromMInfo(item["MINFO"].kuwoString()),
                    cover = songCover,
                    source = "kuwo",
                    link = "http://www.kuwo.cn/play_detail/$rid",
                    extra = extra,
                )
            }

            if (musicList.size < ALBUM_DETAIL_PAGE_SIZE) break
            if (totalSongs > 0 && songs.size >= totalSongs) break
            page++
        }

        val found = album ?: throw IllegalStateException("album not found")
        return (if (found.trackCount == 0) found.copy(trackCount = songs.size) else found) to songs
    }

    private suspend fun fetchAlbumDetailFromPage(cookie: String, id: String): Pair<Playlist, List<Song>> {
        val pageUrl = "$ALBUM_DETAIL_PAGE_API/$id"
        val html = NativeHttp.get(pageUrl, cookie = cookie, userAgent = KUWO_USER_AGENT)

        val (pageAlbumName, pageArtistName) = parseKuwoAlbumPageTitle(
            findKuwoSubmatch(html, TITLE_RE),
        )
        var albumName = pageAlbumName
        var artistName = pageArtistName
        if (albumName.isEmpty()) {
            albumName = normalizeKuwoText(stripKuwoHtmlTags(findKuwoSubmatch(html, ALBUM_SONG_NAME_RE)))
        }
        if (artistName.isEmpty()) {
            artistName = normalizeKuwoText(stripKuwoHtmlTags(findKuwoSubmatch(html, ALBUM_ARTIST_NAME_RE)))
        }

        val infoBlock = findKuwoSubmatch(html, ALBUM_SONG_INFO_RE)
        val infoTips = findKuwoSubmatches(infoBlock, ALBUM_TIP_RE)
        var lang = ""
        var publishTime = ""
        if (infoTips.isNotEmpty()) lang = normalizeKuwoText(stripKuwoHtmlTags(infoTips[0]))
        if (infoTips.size > 1) publishTime = normalizeKuwoText(stripKuwoHtmlTags(infoTips[1]))

        val description = normalizeKuwoText(
            stripKuwoHtmlTags(findKuwoSubmatch(html, ALBUM_INTRO_RE)),
        )
        var cover = normalizeKuwoImageUrl(decodeKuwoEscaped(findKuwoSubmatch(html, ALBUM_HTS_IMG_RE)))
        if (cover.isEmpty()) {
            cover = normalizeKuwoImageUrl(decodeKuwoEscaped(findKuwoSubmatch(html, ALBUM_IMG_RE)))
        }
        val company = normalizeKuwoText(decodeKuwoEscaped(findKuwoSubmatch(html, ALBUM_COMPANY_RE)))

        val songBlocks = ALBUM_SONG_BLOCK_RE.findAll(html).map { it.value }.toList()
        if (songBlocks.isEmpty()) throw IllegalStateException("album page returned no songs")

        val album = Playlist(
            id = id,
            name = albumName,
            cover = cover,
            trackCount = songBlocks.size,
            creator = artistName,
            description = description,
            source = "kuwo",
            link = pageUrl,
            extra = mapOf(
                "type" to "album",
                "album_id" to id,
                "company" to company,
                "publish_time" to publishTime,
                "lang" to lang,
            ),
        )

        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        for (block in songBlocks) {
            val rid = findKuwoSubmatch(block, ALBUM_SONG_RID_RE)
            if (rid.isEmpty() || !seen.add(rid)) continue

            val name = normalizeKuwoText(
                stripKuwoHtmlTags(
                    firstNonEmpty(
                        findKuwoSubmatch(block, ALBUM_SONG_TITLE_RE),
                        findKuwoSubmatch(block, ALBUM_SONG_NAME_LINK_RE),
                    ),
                ),
            )
            val artist = normalizeKuwoText(
                stripKuwoHtmlTags(
                    firstNonEmpty(
                        findKuwoSubmatch(block, ALBUM_SONG_ARTIST_TITLE_RE),
                        findKuwoSubmatch(block, ALBUM_SONG_ARTIST_RE),
                        artistName,
                    ),
                ),
            )
            val track = firstNonEmpty(
                findKuwoSubmatch(block, ALBUM_SONG_TRACK_STYLE_RE),
                findKuwoSubmatch(block, ALBUM_SONG_TRACK_RE),
            )

            val extra = mutableMapOf("rid" to rid, "album_id" to album.id)
            if (track.isNotEmpty()) extra["track"] = track
            songs += Song(
                id = rid,
                name = name,
                artist = artist,
                album = album.name,
                albumId = album.id,
                cover = album.cover,
                source = "kuwo",
                link = "http://www.kuwo.cn/play_detail/$rid",
                extra = extra,
            )
        }
        if (songs.isEmpty()) throw IllegalStateException("album page parsed zero songs")
        return album.copy(trackCount = songs.size) to songs
    }

    private suspend fun fetchFullSongInfo(cookie: String, rid: String): Song {
        val metaUrl = "$SONG_INFO_API?" + query("musicId" to rid, "httpsStatus" to "1")
        var name = ""
        var artist = ""
        var cover = ""
        val metaBody = runCatching {
            NativeHttp.get(metaUrl, cookie = cookie, randomIp = true, userAgent = KUWO_USER_AGENT)
        }.getOrNull()
        if (metaBody != null) {
            val info = runCatching {
                json.decodeFromString<KuwoSongInfoResponse>(metaBody).data?.songInfo
            }.getOrNull()
            if (info != null) {
                name = info.songName
                artist = info.artist
                cover = info.pic
            }
        }
        if (name.isEmpty()) name = "Kuwo_Song_$rid"

        val audioUrl = fetchAudioUrl(cookie, rid)
        return Song(
            id = rid,
            name = name,
            artist = artist,
            cover = cover,
            url = audioUrl,
            source = "kuwo",
            link = "http://www.kuwo.cn/play_detail/$rid",
            extra = mapOf("rid" to rid),
        )
    }

    private suspend fun fetchAudioUrl(cookie: String, rid: String): String {
        val randomId = "C_APK_guanwang_${System.currentTimeMillis() * 1_000_000L + Random.nextLong(1_000_000L)}"
        // 从最高档往下试；反过来会永远命中 128k，登录也拿不到无损
        for (br in listOf("2000kflac", "flac", "320kmp3", "128kmp3")) {
            val url = "$MOBI_API?" + query(
                "f" to "web",
                "source" to "kwplayercar_ar_6.0.0.9_B_jiakong_vh.apk",
                "from" to "PC",
                "type" to "convert_url_with_sign",
                "br" to br,
                "rid" to rid,
                "user" to randomId,
            )
            val body = runCatching {
                NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = KUWO_USER_AGENT)
            }.getOrNull() ?: continue
            val dataUrl = runCatching {
                json.decodeFromString<KuwoMobiResponse>(body).data?.url.orEmpty()
            }.getOrDefault("")
            if (dataUrl.isNotEmpty()) return dataUrl
        }

        var secretCookie = cookie
        if (!secretCookie.contains("kw_token")) secretCookie += "; kw_token=secret_token"
        val fallbackUrl = "$PLAY_URL_FALLBACK_API?mid=$rid&type=music&httpsStatus=1"
        val fallbackBody = runCatching {
            NativeHttp.get(
                fallbackUrl,
                headers = mapOf("Secret" to "kuwo_web_secret"),
                cookie = secretCookie,
                randomIp = true,
                userAgent = KUWO_USER_AGENT,
            )
        }.getOrNull()
        if (fallbackBody != null) {
            val url = runCatching {
                json.decodeFromString<KuwoPlayUrlResponse>(fallbackBody).data?.url.orEmpty()
            }.getOrNull()
            if (url != null && url.isNotEmpty()) return url
        }
        throw IllegalStateException("download url not found (copyright restricted)")
    }

    private suspend fun fetchNewLyrics(cookie: String, rid: String): String {
        val params = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_$rid&lrcx=1"
        val encoded = Base64.getEncoder().encodeToString(xorKuwoNewLyric(params.toByteArray(Charsets.UTF_8)))
        val url = "$NEW_LYRIC_API?$encoded"
        val body = NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = KUWO_USER_AGENT)
        val raw = decodeKuwoNewLyric(body, lyricx = true)
        val lrc = convertKuwoNewLyric(raw)
        if (lrc.isBlank() || !hasKuwoTimestampedLyric(lrc)) {
            throw IllegalStateException("kuwo newlyric content is empty")
        }
        return lrc
    }

    private suspend fun fetchSongInfoLyrics(cookie: String, rid: String): String {
        val url = "$SONG_INFO_API?" + query("musicId" to rid, "httpsStatus" to "1")
        val body = try {
            NativeHttp.get(url, cookie = cookie, randomIp = true, userAgent = KUWO_USER_AGENT)
        } catch (e: Exception) {
            throw IllegalStateException("failed to fetch kuwo lyric API: ${e.message}", e)
        }
        val response = try {
            json.decodeFromString<KuwoLyricResponse>(body)
        } catch (e: Exception) {
            throw IllegalStateException("failed to parse kuwo lyric JSON: ${e.message}", e)
        }
        val lines = response.data?.lrclist.orEmpty()
        if (lines.isEmpty()) return ""
        val builder = StringBuilder()
        for (line in lines) {
            val secs = line.time.toDoubleOrNull() ?: 0.0
            val whole = secs.toInt()
            val minutes = whole / 60
            val seconds = whole % 60
            val hundredths = ((secs - whole) * 100).toInt()
            builder.append(
                String.format(Locale.ROOT, "[%02d:%02d.%02d]%s\n", minutes, seconds, hundredths, line.lineLyric),
            )
        }
        return builder.toString()
    }

    private suspend fun fetchTopListSongs(cookie: String, topListId: String, limit: Int): List<Song> {
        val id = topListId.trim()
        if (id.isEmpty()) throw IllegalArgumentException("kuwo toplist id is empty")
        if (!id.all { it in '0'..'9' }) throw IllegalArgumentException("invalid kuwo toplist id: $id")

        var pageSize = KUWO_TOP_LIST_PAGE_SIZE
        if (limit in 1 until pageSize) pageSize = limit

        val songs = mutableListOf<Song>()
        var page = 0
        while (true) {
            val (pageSongs, total) = fetchTopListSongsPage(cookie, id, page, pageSize)
            if (pageSongs.isEmpty()) break
            songs += pageSongs
            if (limit > 0 && songs.size >= limit) {
                return songs.take(limit)
            }
            if (total > 0 && songs.size >= total) break
            if (pageSongs.size < pageSize) break
            page++
        }
        if (songs.isEmpty()) throw IllegalStateException("kuwo toplist $id has no songs")
        return songs
    }

    private suspend fun fetchTopListSongsPage(
        cookie: String,
        topListId: String,
        page: Int,
        pageSize: Int,
    ): Pair<List<Song>, Int> {
        val url = "$TOP_LIST_SONGS_API?" + query(
            "from" to "pc",
            "fmt" to "json",
            "pn" to page.toString(),
            "rn" to pageSize.toString(),
            "type" to "bang",
            "data" to "content",
            "id" to topListId,
            "show_copyright_off" to "1",
            "pcmp4" to "1",
        )
        val body = NativeHttp.get(
            url,
            headers = mapOf("Referer" to TOP_LIST_PAGE),
            cookie = cookie,
            randomIp = true,
            userAgent = KUWO_USER_AGENT,
        )
        val response = try {
            json.decodeFromString<KuwoTopListSongsResponse>(body)
        } catch (e: Exception) {
            throw IllegalStateException("kuwo toplist json parse error: ${e.message}", e)
        }

        val total = response.num.kuwoInt()
        val result = mutableListOf<Song>()
        response.musiclist.orEmpty().forEachIndexed { index, item ->
            val name = normalizeKuwoText(item.name)
            val rid = item.id.kuwoString()
            if (name.isEmpty() || rid.isEmpty()) return@forEachIndexed

            var duration = item.songDuration.kuwoInt()
            if (duration <= 0) duration = item.duration.kuwoInt()

            val extra = mutableMapOf(
                "rid" to rid,
                "toplist_id" to topListId,
                "rank" to (page * pageSize + index + 1).toString(),
            )
            val optional = mapOf(
                "artist_id" to item.artistId.kuwoString(),
                "formats" to item.formats.trim(),
                "trend" to item.trend.trim(),
                "rank_change" to item.rankChange.kuwoString(),
                "highest_rank" to item.highestRank.kuwoString(),
                "score" to item.score100.kuwoString(),
                "is_new" to item.isNew.kuwoString(),
                "is_red_song" to item.isRedSong.kuwoString(),
                "pay" to item.pay.kuwoString(),
                "copyright" to item.copyright.kuwoString(),
                "online" to item.online.kuwoString(),
            )
            for ((key, value) in optional) {
                if (value.isNotEmpty()) extra[key] = value
            }

            result += Song(
                id = rid,
                name = name,
                artist = normalizeKuwoText(item.artist),
                album = normalizeKuwoText(item.album),
                albumId = item.albumId.kuwoString(),
                duration = duration,
                source = "kuwo",
                link = "http://www.kuwo.cn/play_detail/$rid",
                extra = extra,
            )
        }
        return result to total
    }

    private fun parseKuwoTopLists(body: String): List<TopList> {
        val marker = body.indexOf("window.__NUXT__")
        if (marker < 0) throw IllegalStateException("kuwo toplist menu not found")

        var script = body.substring(marker)
        val scriptEnd = script.indexOf("</script>")
        if (scriptEnd >= 0) script = script.substring(0, scriptEnd)

        val args = parseKuwoNuxtArgs(script)
        val menuPos = script.indexOf("bangMenu:[")
        if (menuPos < 0) throw IllegalStateException("kuwo toplist menu not found")

        val topLists = parseKuwoBangMenu(script.substring(menuPos + "bangMenu:".length), args)
        if (topLists.isEmpty()) throw IllegalStateException("kuwo toplist menu is empty")
        return topLists
    }

    private fun parseKuwoNuxtArgs(src: String): Map<String, String> {
        val header = src.indexOf("(function(")
        if (header < 0) throw IllegalStateException("kuwo toplist page data is malformed")

        val namesStart = header + "(function(".length
        val namesEnd = src.indexOf(")", namesStart)
        if (namesEnd < 0) throw IllegalStateException("kuwo toplist page data is malformed")
        val names = src.substring(namesStart, namesEnd).split(",")

        val argsStart = src.lastIndexOf("}(")
        if (argsStart < 0) throw IllegalStateException("kuwo toplist page data is malformed")

        val values = mutableMapOf<String, String>()
        var pos = skipKuwoJsSpace(src, argsStart + 2)
        for (name in names) {
            val key = name.trim()
            if (key.isEmpty()) continue
            if (pos >= src.length || src[pos] == ')') break
            val scanned = scanKuwoJsValue(src, pos, null)
            values[key] = scanned.first
            pos = skipKuwoJsSpace(src, scanned.second)
            if (pos < src.length && src[pos] == ',') pos = skipKuwoJsSpace(src, pos + 1)
        }
        if (values.isEmpty()) throw IllegalStateException("kuwo toplist page data is malformed")
        return values
    }

    private fun parseKuwoBangMenu(src: String, args: Map<String, String>): List<TopList> {
        var pos = skipKuwoJsSpace(src, 0)
        if (pos >= src.length || src[pos] != '[') {
            throw IllegalStateException("kuwo toplist menu is malformed")
        }
        pos++

        val topLists = mutableListOf<TopList>()
        while (pos < src.length) {
            pos = skipKuwoJsSpace(src, pos)
            if (pos >= src.length || src[pos] == ']') break
            if (src[pos] == ',') {
                pos++
                continue
            }
            if (src[pos] != '{') {
                throw IllegalStateException("kuwo toplist menu is malformed near ${kuwoJsSnippet(src, pos)}")
            }

            var groupName = ""
            var itemsRaw = ""
            pos = parseKuwoJsObject(src, pos, args) { key, value ->
                when (key) {
                    "name" -> groupName = value
                    "list" -> itemsRaw = value
                }
            }

            if (itemsRaw.trim().isEmpty()) continue
            val group = groupName.trim().ifEmpty { "其他" }
            val items = parseKuwoBangItems(itemsRaw, args)
            for (item in items) {
                val extra = mutableMapOf<String, JsonElement>("group" to JsonPrimitive(group))
                if (item.pub.isNotEmpty()) extra["pub"] = JsonPrimitive(item.pub)
                topLists += TopList(
                    id = item.id,
                    name = item.name,
                    cover = normalizeKuwoImageUrl(item.cover),
                    description = normalizeKuwoText(item.description),
                    updateFrequency = firstNonEmpty(item.frequency, item.pub),
                    source = "kuwo",
                    link = TOP_LIST_PAGE,
                    extra = extra,
                )
            }
        }
        return topLists
    }

    private fun parseKuwoBangItems(src: String, args: Map<String, String>): List<KuwoBangMenuItem> {
        var pos = skipKuwoJsSpace(src, 0)
        if (pos >= src.length || src[pos] != '[') {
            throw IllegalStateException("kuwo toplist group items are malformed")
        }
        pos++

        val items = mutableListOf<KuwoBangMenuItem>()
        while (pos < src.length) {
            pos = skipKuwoJsSpace(src, pos)
            if (pos >= src.length || src[pos] == ']') break
            if (src[pos] == ',') {
                pos++
                continue
            }
            if (src[pos] != '{') {
                throw IllegalStateException("kuwo toplist group items are malformed near ${kuwoJsSnippet(src, pos)}")
            }

            var id = ""
            var name = ""
            var cover = ""
            var description = ""
            var frequency = ""
            var pub = ""
            pos = parseKuwoJsObject(src, pos, args) { key, value ->
                when (key) {
                    "sourceid" -> id = value
                    "name" -> name = value
                    "pic" -> cover = value
                    "intro" -> description = value
                    "pub" -> {
                        frequency = value
                        pub = value
                    }
                }
            }
            if (id.isNotEmpty() && name.isNotEmpty()) {
                items += KuwoBangMenuItem(id, name, cover, description, frequency, pub)
            }
        }
        return items
    }

    private fun parseKuwoJsObject(
        src: String,
        position: Int,
        args: Map<String, String>?,
        visit: (String, String) -> Unit,
    ): Int {
        var pos = position
        if (pos >= src.length || src[pos] != '{') {
            throw IllegalStateException("kuwo js object is malformed")
        }
        pos++

        while (pos < src.length) {
            pos = skipKuwoJsSpace(src, pos)
            if (pos >= src.length) break
            when (src[pos]) {
                '}' -> return pos + 1
                ',' -> {
                    pos++
                    continue
                }
            }

            val keyScan = scanKuwoJsKey(src, pos)
            pos = skipKuwoJsSpace(src, keyScan.second)
            if (pos >= src.length || src[pos] != ':') {
                throw IllegalStateException("kuwo js object key is malformed")
            }
            pos = skipKuwoJsSpace(src, pos + 1)

            val valueScan = scanKuwoJsValue(src, pos, args)
            pos = valueScan.second
            visit(keyScan.first, valueScan.first)
        }
        throw IllegalStateException("kuwo js object is unterminated")
    }

    private fun scanKuwoJsKey(src: String, position: Int): Pair<String, Int> {
        if (position >= src.length) throw IllegalStateException("kuwo js key is malformed")
        if (src[position] == '"' || src[position] == '\'') return scanKuwoJsString(src, position)

        var end = position
        while (end < src.length && isKuwoJsIdentifierByte(src[end])) end++
        if (end == position) throw IllegalStateException("kuwo js key is malformed")
        return src.substring(position, end) to end
    }

    private fun scanKuwoJsValue(src: String, position: Int, args: Map<String, String>?): Pair<String, Int> {
        var pos = skipKuwoJsSpace(src, position)
        if (pos >= src.length) throw IllegalStateException("kuwo js value is malformed")

        when (src[pos]) {
            '"', '\'' -> return scanKuwoJsString(src, pos)
            '[', '{' -> {
                val next = skipKuwoJsBlock(src, pos)
                return src.substring(pos, next).trim() to next
            }
        }

        var end = pos
        while (end < src.length && isKuwoJsIdentifierByte(src[end])) end++
        if (end == pos) throw IllegalStateException("kuwo js value is malformed")
        val token = src.substring(pos, end)
        val value = args?.get(token) ?: token
        return value to end
    }

    private fun scanKuwoJsString(src: String, position: Int): Pair<String, Int> {
        val quote = src[position]
        val raw = StringBuilder()
        var index = position + 1
        while (index < src.length) {
            when (src[index]) {
                '\\' -> {
                    if (index + 1 >= src.length) {
                        throw IllegalStateException("kuwo js string is unterminated")
                    }
                    raw.append(src[index]).append(src[index + 1])
                    index += 2
                }
                quote -> return unescapeKuwoJsString(raw.toString()) to (index + 1)
                else -> {
                    raw.append(src[index])
                    index++
                }
            }
        }
        throw IllegalStateException("kuwo js string is unterminated")
    }

    private fun skipKuwoJsBlock(src: String, position: Int): Int {
        var depth = 0
        var index = position
        while (index < src.length) {
            when (src[index]) {
                '"', '\'' -> {
                    index = scanKuwoJsString(src, index).second
                    continue
                }
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth <= 0) return index + 1
                }
            }
            index++
        }
        throw IllegalStateException("kuwo js block is unterminated")
    }

    private fun unescapeKuwoJsString(raw: String): String {
        val quoted = "\"" + raw.replace("\"", "\\\"") + "\""
        val parsed = runCatching { json.parseToJsonElement(quoted).jsonPrimitive.content }.getOrNull()
        if (parsed != null) return parsed

        val out = StringBuilder()
        var index = 0
        while (index < raw.length) {
            if (raw[index] != '\\' || index + 1 >= raw.length) {
                out.append(raw[index])
                index++
                continue
            }
            index++
            when (raw[index]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    if (index + 4 < raw.length) {
                        val value = raw.substring(index + 1, index + 5).toIntOrNull(16)
                        if (value != null) {
                            out.append(value.toChar())
                            index += 4
                        } else {
                            out.append('u')
                        }
                    } else {
                        out.append('u')
                    }
                }
                else -> out.append(raw[index])
            }
            index++
        }
        return out.toString()
    }

    private fun skipKuwoJsSpace(src: String, position: Int): Int {
        var pos = position
        while (pos < src.length) {
            when (src[pos]) {
                ' ', '\t', '\r', '\n' -> pos++
                else -> return pos
            }
        }
        return pos
    }

    private fun isKuwoJsIdentifierByte(char: Char): Boolean = when {
        char in 'a'..'z' -> true
        char in 'A'..'Z' -> true
        char in '0'..'9' -> true
        char == '_' || char == '$' || char == '.' -> true
        else -> false
    }

    private fun kuwoJsSnippet(src: String, position: Int): String {
        val start = position.coerceAtLeast(0)
        if (start > src.length) return ""
        val end = (start + 32).coerceAtMost(src.length)
        return src.substring(start, end)
    }

    private fun decodeKuwoNewLyric(body: String, lyricx: Boolean): String {
        val candidates = listOf(
            body.toByteArray(Charsets.ISO_8859_1),
            body.toByteArray(Charsets.UTF_8),
        )
        var error: Exception? = null
        for (candidate in candidates) {
            try {
                return decodeKuwoNewLyricBytes(candidate, lyricx)
            } catch (e: Exception) {
                error = e
            }
        }
        throw IllegalStateException("invalid kuwo newlyric payload", error)
    }

    private fun decodeKuwoNewLyricBytes(buf: ByteArray, lyricx: Boolean): String {
        if (!buf.startsWithBytes("tp=content".toByteArray(Charsets.ISO_8859_1))) {
            throw IllegalStateException("invalid kuwo newlyric response")
        }
        val separator = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val index = indexOfBytes(buf, separator)
        if (index < 0) throw IllegalStateException("invalid kuwo newlyric payload")

        var plain = inflateKuwoNewLyric(buf.copyOfRange(index + separator.size, buf.size))
        if (lyricx) {
            val encoded = compactBase64(String(plain, Charsets.ISO_8859_1))
            val decoded = runCatching { Base64.getDecoder().decode(encoded) }
                .getOrElse { throw IllegalStateException("invalid kuwo newlyric payload", it) }
            plain = xorKuwoNewLyric(decoded)
        }
        return runCatching { String(plain, Charset.forName("GB18030")) }
            .getOrElse { String(plain, Charsets.UTF_8) }
    }

    private fun inflateKuwoNewLyric(data: ByteArray): ByteArray =
        InflaterInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

    private fun xorKuwoNewLyric(data: ByteArray): ByteArray {
        val key = "yeelion".toByteArray(Charsets.ISO_8859_1)
        return ByteArray(data.size) { index ->
            (data[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
    }

    private fun compactBase64(value: String): String =
        value.filterNot { it == ' ' || it == '\n' || it == '\r' || it == '\t' }

    internal fun convertKuwoNewLyric(raw: String): String {
        val lines = raw.split(Regex("[\\r\\n]+")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.isEmpty()) {
                index++
                continue
            }
            val match = KUWO_NEW_LYRIC_LINE.find(line)
            if (match == null) {
                if (KUWO_NEW_LYRIC_TAG.matches(line)) out += line
                index++
                continue
            }

            val payload = match.groupValues[4]
            if (isKuwoChineseTranslationPayload(payload)) {
                index++
                continue
            }
            val text = kuwoNewLyricPayloadText(payload)
            if (text.isEmpty()) {
                index++
                continue
            }

            val timestamp = "[${match.groupValues[1]}:${match.groupValues[2]}.${match.groupValues[3]}]"
            out += timestamp + kuwoVerbatimPayload(payload, kuwoLineStartMs(match))

            var roma = ""
            var translation = ""
            while (index + 1 < lines.size) {
                val nextMatch = KUWO_NEW_LYRIC_LINE.find(lines[index + 1].trim()) ?: break
                if (!nextMatch.groupValues[4].startsWith("<0,0>")) break
                val nextText = kuwoNewLyricPayloadText(nextMatch.groupValues[4])
                index++
                if (nextText.isEmpty()) continue
                when {
                    isKuwoChineseTranslationPayload(nextMatch.groupValues[4]) && translation.isEmpty() ->
                        translation = nextText
                    isKuwoRomajiPayload(nextText) && roma.isEmpty() -> roma = nextText
                }
            }
            if (roma.isNotEmpty()) out += timestamp + roma
            if (translation.isNotEmpty()) out += timestamp + translation
            index++
        }
        return out.joinToString("\n").trimEnd('\n')
    }

    private fun kuwoLineStartMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val millis = match.groupValues[3].toLongOrNull() ?: 0L
        return minutes * 60_000 + seconds * 1_000 + millis
    }

    /** 酷我逐字载荷 `<词起,词时>词` 转 `<mm:ss.SSS>` 增强 LRC；没有词标签时退回纯文本。 */
    private fun kuwoVerbatimPayload(payload: String, lineStartMs: Long): String {
        val matches = KUWO_NEW_LYRIC_WORD.findAll(payload).toList()
        if (matches.isEmpty()) return kuwoNewLyricPayloadText(payload)
        val builder = StringBuilder()
        for (match in matches) {
            val wordStart = match.groupValues[1].toLongOrNull() ?: 0L
            val wordText = match.groupValues[3]
            if (wordText.isEmpty()) continue
            // 时间轴有绝对毫秒和相对行首两种写法：早于行首的按相对处理
            val start = if (wordStart >= lineStartMs) wordStart else lineStartMs + wordStart
            builder.append('<').append(formatKuwoWordTime(start)).append('>').append(wordText)
        }
        return if (builder.isEmpty()) kuwoNewLyricPayloadText(payload) else builder.toString()
    }

    private fun formatKuwoWordTime(milliseconds: Long): String {
        val ms = milliseconds.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, (ms % 60_000) / 1_000, ms % 1_000)
    }

    private fun kuwoNewLyricPayloadText(payload: String): String {
        val matches = KUWO_NEW_LYRIC_WORD.findAll(payload).toList()
        if (matches.isEmpty()) return payload.trim()
        return buildString {
            for (match in matches) append(match.groupValues[3])
        }.trim()
    }

    private fun isKuwoChineseTranslationPayload(payload: String): Boolean {
        if (!payload.startsWith("<0,0>")) return false
        val text = kuwoNewLyricPayloadText(payload)
        return containsHan(text) && !containsKana(text)
    }

    private fun isKuwoRomajiPayload(text: String): Boolean {
        var hasLatin = false
        for (char in text) {
            if (char in 'A'..'Z' || char in 'a'..'z') {
                hasLatin = true
                continue
            }
            if (containsHan(char) || containsKana(char)) return false
        }
        return hasLatin
    }

    private fun containsHan(char: Char): Boolean = char in '一'..'鿿'

    private fun containsHan(text: String): Boolean = text.any { containsHan(it) }

    private fun containsKana(char: Char): Boolean =
        char in '぀'..'ヿ' || char in 'ｦ'..'ﾟ'

    private fun containsKana(text: String): Boolean = text.any { containsKana(it) }

    private fun hasKuwoTimestampedLyric(lrc: String): Boolean =
        lrc.split('\n').any { KUWO_NEW_LYRIC_LINE.matches(it.trim()) }

    private fun legacyJson(body: String): String = body.replace('\'', '"')

    private fun normalizeKuwoText(value: String): String {
        if (value.isEmpty()) return ""
        var text = htmlUnescape(value)
        text = text.replace(" ", " ")
        text = text.replace("\r\n", "\n")
        text = text.replace("\\n;", "\n")
        text = text.replace("\\n", "\n")
        text = text.replace("\n;", "\n")
        return text.trim()
    }

    private fun normalizeKuwoImageUrl(value: String): String {
        var raw = normalizeKuwoText(value)
        if (raw.isEmpty()) return ""
        if (raw.startsWith("//")) {
            raw = "http:$raw"
        } else if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = if (raw.startsWith("img")) {
                "http://$raw"
            } else {
                "http://img1.kuwo.cn/star/albumcover/" + raw.removePrefix("/")
            }
        }
        raw = raw.replaceFirst("/120/", "/500/")
        raw = raw.replaceFirst("/150/", "/500/")
        raw = raw.replaceFirst("/240/", "/500/")
        raw = raw.replaceFirst("_100.", "_500.")
        raw = raw.replaceFirst("_120.", "_500.")
        raw = raw.replaceFirst("_150.", "_500.")
        raw = raw.replaceFirst("_240.", "_500.")
        return raw
    }

    private fun parseSizeFromMInfo(minfo: String): Long {
        if (minfo.isEmpty()) return 0
        val formats = parseKuwoFormats(minfo)
        formats.firstOrNull { it.format == "mp3" && it.bitrate == "128" }?.let { return it.size }
        formats.firstOrNull { it.format == "mp3" && it.bitrate == "320" }?.let { return it.size }
        formats.firstOrNull { it.format == "flac" }?.let { return it.size }
        formats.firstOrNull { it.format == "flac" && it.bitrate == "2000" }?.let { return it.size }
        return formats.maxOfOrNull { it.size } ?: 0L
    }

    private fun parseBitrateFromMInfo(minfo: String): Int {
        if (minfo.isEmpty()) return 128
        val formats = parseKuwoFormats(minfo)
        formats.firstOrNull { it.format == "mp3" && it.bitrate == "128" }?.let { return 128 }
        formats.firstOrNull { it.format == "mp3" && it.bitrate == "320" }?.let { return 320 }
        formats.firstOrNull { it.format == "flac" && it.bitrate == "2000" }?.let {
            return it.bitrate.toIntOrNull()?.takeIf { value -> value > 0 } ?: 2000
        }
        formats.firstOrNull { it.format == "flac" }?.let {
            return it.bitrate.toIntOrNull()?.takeIf { value -> value > 0 } ?: 800
        }
        return 128
    }

    private fun parseKuwoFormats(minfo: String): List<KuwoFormatInfo> {
        val formats = mutableListOf<KuwoFormatInfo>()
        for (part in minfo.split(";")) {
            val values = mutableMapOf<String, String>()
            for (attr in part.split(",")) {
                val pair = attr.split(":")
                if (pair.size == 2) values[pair[0]] = pair[1]
            }
            val sizeText = values["size"] ?: continue
            if (sizeText.isEmpty()) continue
            val sizeMb = sizeText.lowercase().removeSuffix("mb").toDoubleOrNull() ?: 0.0
            formats += KuwoFormatInfo(
                format = values["format"].orEmpty(),
                bitrate = values["bitrate"].orEmpty(),
                size = (sizeMb * 1024 * 1024).toLong(),
            )
        }
        return formats
    }

    private fun parseKuwoAlbumPageTitle(title: String): Pair<String, String> {
        val normalized = normalizeKuwoText(title)
        if (normalized.isEmpty()) return "" to ""
        val parts = normalized.split("_")
        if (parts.size < 2) return "" to ""
        val name = normalizeKuwoText(parts[0].removeSuffix("专辑"))
        val artist = normalizeKuwoText(parts[1])
        return name to artist
    }

    private fun stripKuwoHtmlTags(raw: String): String {
        if (raw.isEmpty()) return ""
        var value = raw
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
            .replace("</p>", "\n")
            .replace("</div>", "\n")
        value = value.replace(HTML_TAG_RE, "")
        return normalizeKuwoText(value)
    }

    private fun decodeKuwoEscaped(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        return goUnquote(value) ?: value.replace("\\/", "/")
    }

    private fun goUnquote(raw: String): String? {
        val escaped = raw.replace("\"", "\\\"")
        val out = StringBuilder(escaped.length)
        var index = 0
        while (index < escaped.length) {
            val char = escaped[index]
            if (char != '\\') {
                out.append(char)
                index++
                continue
            }
            if (index + 1 >= escaped.length) return null
            when (val next = escaped[index + 1]) {
                'a' -> out.append('\u0007')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'v' -> out.append('\u000B')
                '\\' -> out.append('\\')
                '"' -> out.append('"')
                '\'' -> out.append('\'')
                'x' -> {
                    if (index + 3 >= escaped.length) return null
                    val code = escaped.substring(index + 2, index + 4).toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    index += 2
                }
                'u' -> {
                    if (index + 5 >= escaped.length) return null
                    val code = escaped.substring(index + 2, index + 6).toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    index += 4
                }
                'U' -> {
                    if (index + 9 >= escaped.length) return null
                    val code = escaped.substring(index + 2, index + 10).toIntOrNull(16) ?: return null
                    if (code !in 0..0x10FFFF || code in 0xD800..0xDFFF) return null
                    out.append(String(Character.toChars(code)))
                    index += 8
                }
                in '0'..'7' -> {
                    if (index + 3 >= escaped.length) return null
                    val digits = escaped.substring(index + 1, index + 4)
                    if (!digits.all { it in '0'..'7' }) return null
                    val code = digits.toIntOrNull(8) ?: return null
                    if (code > 255) return null
                    out.append(code.toChar())
                    index += 2
                }
                else -> return null
            }
            index += 2
        }
        return out.toString()
    }

    private fun htmlUnescape(value: String): String {
        if ('&' !in value) return value
        return HTML_ENTITY_RE.replace(value) { match ->
            val token = match.groupValues[1]
            when {
                token.startsWith("#x", ignoreCase = true) -> {
                    val code = token.substring(2).toIntOrNull(16)
                    if (code != null) {
                        runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
                    } else {
                        match.value
                    }
                }
                token.startsWith("#") -> {
                    val code = token.substring(1).toIntOrNull()
                    if (code != null) {
                        runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
                    } else {
                        match.value
                    }
                }
                else -> NAMED_ENTITIES[token.lowercase()] ?: match.value
            }
        }
    }

    private fun findKuwoSubmatch(input: String, pattern: Regex): String =
        pattern.find(input)?.groupValues?.getOrNull(1).orEmpty()

    private fun findKuwoSubmatches(input: String, pattern: Regex): List<String> =
        pattern.findAll(input).mapNotNull { it.groupValues.getOrNull(1) }.toList()

    private fun firstNonEmpty(vararg values: String): String =
        values.firstOrNull { it.trim().isNotEmpty() }?.trim().orEmpty()

    private fun query(vararg params: Pair<String, String>): String =
        params.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        outer@ for (index in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) continue@outer
            }
            return index
        }
        return -1
    }

    private fun JsonElement?.kuwoString(): String {
        val primitive = this as? JsonPrimitive ?: return ""
        if (primitive is JsonNull) return ""
        if (primitive.isString) return primitive.content.trim()
        val content = primitive.content.trim()
        val number = content.toDoubleOrNull() ?: return content
        return number.roundToLong().toString()
    }

    private fun JsonElement?.kuwoInt(): Int {
        val primitive = this as? JsonPrimitive ?: return 0
        if (primitive is JsonNull) return 0
        val content = primitive.content.trim()
        return if (primitive.isString) {
            content.toIntOrNull() ?: 0
        } else {
            content.toDoubleOrNull()?.toInt() ?: content.toIntOrNull() ?: 0
        }
    }

    private data class KuwoFormatInfo(val format: String, val bitrate: String, val size: Long)

    private data class KuwoBangMenuItem(
        val id: String,
        val name: String,
        val cover: String,
        val description: String,
        val frequency: String,
        val pub: String,
    )

    private companion object {
        const val KUWO_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val SEARCH_MUSIC_API = "http://www.kuwo.cn/search/searchMusicBykeyWord"
        const val SEARCH_LEGACY_API = "http://search.kuwo.cn/r.s"
        const val PLAYLIST_DETAIL_API = "http://nplserver.kuwo.cn/pl.svc"
        const val SONG_INFO_API = "http://m.kuwo.cn/newh5/singles/songinfoandlrc"
        const val MOBI_API = "https://mobi.kuwo.cn/mobi.s"
        const val PLAY_URL_FALLBACK_API = "http://www.kuwo.cn/api/v1/www/music/playUrl"
        const val NEW_LYRIC_API = "http://newlyric.kuwo.cn/newlyric.lrc"
        const val RECOMMEND_API = "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList"
        const val CATEGORY_TAG_API = "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagList"
        const val CATEGORY_PLAYLIST_API = "http://wapi.kuwo.cn/api/pc/classify/playlist"
        const val ALBUM_DETAIL_PAGE_API = "https://www.kuwo.cn/album_detail"
        const val TOP_LIST_PAGE = "http://www.kuwo.cn/rankList"
        const val TOP_LIST_SONGS_API = "http://kbangserver.kuwo.cn/ksong.s"
        const val HOT_SONG_TOP_LIST_ID = "16"
        const val KUWO_PLAYLIST_PAGE_SIZE = 100
        const val KUWO_PLAYLIST_MAX_PAGES = 500
        const val KUWO_TOP_LIST_PAGE_SIZE = 300
        const val ALBUM_DETAIL_PAGE_SIZE = 100
        const val DEFAULT_HOT_SONG_LIMIT = 100
        const val MAX_HOT_SONG_LIMIT = 200

        val KUWO_NEW_LYRIC_LINE = Regex("^\\[(\\d{2}):(\\d{2})\\.(\\d{3})\\](.*)\$")
        val KUWO_NEW_LYRIC_TAG = Regex("^\\[[A-Za-z]+:[^\\]]*\\]\$")
        val KUWO_NEW_LYRIC_WORD = Regex("<(-?\\d+),(-?\\d+)>([^<]*)")

        val TITLE_RE = Regex("""(?is)<title>(.*?)</title>""")
        val ALBUM_SONG_NAME_RE = Regex("""(?is)<p class="song_name"[^>]*>(.*?)</p>""")
        val ALBUM_ARTIST_NAME_RE = Regex("""(?is)<p class="artist_name"[^>]*>(.*?)</p>""")
        val ALBUM_SONG_INFO_RE = Regex("""(?is)<p class="song_info"[^>]*>(.*?)</p>""")
        val ALBUM_TIP_RE = Regex("""(?is)<span class="tip"[^>]*>(.*?)</span>""")
        val ALBUM_INTRO_RE = Regex("""(?is)<p class="intr_txt"[^>]*>.*?<span[^>]*>(.*?)</span>""")
        val ALBUM_HTS_IMG_RE = Regex("hts_img:\"([^\"]+)\"")
        val ALBUM_IMG_RE = Regex("img:\"([^\"]*albumcover[^\"]+)\"")
        val ALBUM_COMPANY_RE = Regex("company:\"([^\"]+)\"")
        val ALBUM_SONG_BLOCK_RE = Regex("""(?is)<li class="song_item[^"]*"[^>]*>.*?</li>""")
        val ALBUM_SONG_RID_RE = Regex("href=\"/play_detail/(\\d+)\"")
        val ALBUM_SONG_TITLE_RE = Regex("title=\"([^\"]+)\"")
        val ALBUM_SONG_NAME_LINK_RE = Regex("""(?is)<a[^>]*class="name"[^>]*>(.*?)</a>""")
        val ALBUM_SONG_ARTIST_TITLE_RE =
            Regex("(?is)<div class=\"song_artist\"[^>]*>.*?<span[^>]*title=\"([^\"]+)\"")
        val ALBUM_SONG_ARTIST_RE =
            Regex("""(?is)<div class="song_artist"[^>]*>.*?<span[^>]*>(.*?)</span>""")
        val ALBUM_SONG_TRACK_STYLE_RE =
            Regex("""(?is)<div class="rank_num"[^>]*>.*?<span style="display:;?"[^>]*>\s*(\d+)\s*</span>""")
        val ALBUM_SONG_TRACK_RE =
            Regex("""(?is)<div class="rank_num"[^>]*>.*?<span[^>]*>\s*(\d+)\s*</span>""")

        val HTML_TAG_RE = Regex("""(?is)<[^>]+>""")
        val HTML_ENTITY_RE = Regex("&(#[xX]?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]+);")
        val NAMED_ENTITIES = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
        )
    }
}

@Serializable
private data class KuwoSearchResponse(val abslist: List<KuwoSearchItem>? = null)

@Serializable
private data class KuwoSearchItem(
    @SerialName("MUSICRID") val musicRid: String = "",
    @SerialName("SONGNAME") val songName: String = "",
    @SerialName("ARTIST") val artist: String = "",
    @SerialName("ALBUM") val album: String = "",
    @SerialName("DURATION") val duration: String = "",
    @SerialName("hts_MVPIC") val htsMvPic: String = "",
    @SerialName("MINFO") val minfo: String = "",
    val bitSwitch: Int = 0,
)

@Serializable
private data class KuwoPlaylistSearchResponse(val abslist: List<KuwoPlaylistSearchItem>? = null)

@Serializable
private data class KuwoPlaylistSearchItem(
    val playlistid: String = "",
    val name: String = "",
    val pic: String = "",
    val songnum: String = "",
    val intro: String = "",
    val nickname: String = "",
)

@Serializable
private data class KuwoAlbumSearchResponse(val albumlist: List<KuwoAlbumSearchItem>? = null)

@Serializable
private data class KuwoAlbumSearchItem(
    val albumid: String = "",
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val aartist: String = "",
    @SerialName("hts_img") val htsImg: String = "",
    val img: String = "",
    val musiccnt: String = "",
    val info: String = "",
    val company: String = "",
    val pub: String = "",
    @SerialName("PLAYCNT") val playcnt: String = "",
)

@Serializable
private data class KuwoPlaylistDetailResponse(
    val title: String = "",
    val pic: String = "",
    val info: String = "",
    val uname: String = "",
    val playnum: JsonElement? = null,
    val total: JsonElement? = null,
    val validtotal: JsonElement? = null,
    val musiclist: List<KuwoPlaylistMusic>? = null,
)

@Serializable
private data class KuwoPlaylistMusic(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val album: String = "",
    val albumpic: String = "",
    val duration: JsonElement? = null,
    @SerialName("song_name") val songName: String = "",
    @SerialName("artist_name") val artistName: String = "",
)

@Serializable
private data class KuwoRecommendedResponse(
    val code: Int = 0,
    val data: KuwoRecommendedData? = null,
)

@Serializable
private data class KuwoRecommendedData(val data: List<KuwoRecommendedItem>? = null)

@Serializable
private data class KuwoRecommendedItem(
    val id: String = "",
    val name: String = "",
    val img: String = "",
    val listencnt: JsonElement? = null,
    val songnum: JsonElement? = null,
    val total: JsonElement? = null,
    val count: JsonElement? = null,
    val musicnum: JsonElement? = null,
    val uname: String = "",
    val desc: String = "",
)

@Serializable
private data class KuwoCategoryResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: List<KuwoCategoryGroup>? = null,
)

@Serializable
private data class KuwoCategoryGroup(
    val id: String = "",
    val name: String = "",
    val mdigest: String = "",
    val data: List<KuwoCategoryItem>? = null,
)

@Serializable
private data class KuwoCategoryItem(
    val id: String = "",
    val name: String = "",
    val digest: String = "",
    val extend: String = "",
    val isnew: String = "",
)

@Serializable
private data class KuwoCategoryPlaylistResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: KuwoCategoryPlaylistData? = null,
)

@Serializable
private data class KuwoCategoryPlaylistData(val data: List<KuwoCategoryPlaylistItem>? = null)

@Serializable
private data class KuwoCategoryPlaylistItem(
    val id: JsonElement? = null,
    val name: String = "",
    val img: String = "",
    val listencnt: JsonElement? = null,
    val songnum: JsonElement? = null,
    val total: JsonElement? = null,
    val count: JsonElement? = null,
    val musicnum: JsonElement? = null,
    val uname: String = "",
    val desc: String = "",
    val info: String = "",
)

@Serializable
private data class KuwoTopListSongsResponse(
    val num: JsonElement? = null,
    val musiclist: List<KuwoBangSong>? = null,
)

@Serializable
private data class KuwoBangSong(
    val id: JsonElement? = null,
    val name: String = "",
    val artist: String = "",
    val album: String = "",
    @SerialName("albumid") val albumId: JsonElement? = null,
    @SerialName("artistid") val artistId: JsonElement? = null,
    val duration: JsonElement? = null,
    @SerialName("song_duration") val songDuration: JsonElement? = null,
    val formats: String = "",
    val score100: JsonElement? = null,
    val trend: String = "",
    @SerialName("rank_change") val rankChange: JsonElement? = null,
    @SerialName("highest_rank") val highestRank: JsonElement? = null,
    @SerialName("isnew") val isNew: JsonElement? = null,
    @SerialName("isRedSong") val isRedSong: JsonElement? = null,
    val pay: JsonElement? = null,
    val copyright: JsonElement? = null,
    val online: JsonElement? = null,
)

@Serializable
private data class KuwoSongInfoResponse(val data: KuwoSongInfoData? = null)

@Serializable
private data class KuwoSongInfoData(@SerialName("songinfo") val songInfo: KuwoSongInfo? = null)

@Serializable
private data class KuwoSongInfo(
    val songName: String = "",
    val artist: String = "",
    val pic: String = "",
)

@Serializable
private data class KuwoMobiResponse(val data: KuwoMobiData? = null)

@Serializable
private data class KuwoMobiData(
    val url: String = "",
    val bitrate: Int = 0,
    val format: String = "",
)

@Serializable
private data class KuwoPlayUrlResponse(val data: KuwoPlayUrlData? = null)

@Serializable
private data class KuwoPlayUrlData(val url: String = "")

@Serializable
private data class KuwoLyricResponse(val data: KuwoLyricData? = null)

@Serializable
private data class KuwoLyricData(val lrclist: List<KuwoLyricLine>? = null)

@Serializable
private data class KuwoLyricLine(
    val time: String = "",
    val lineLyric: String = "",
)
