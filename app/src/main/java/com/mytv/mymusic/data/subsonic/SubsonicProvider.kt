package com.mytv.mymusic.data.subsonic

import com.mytv.mymusic.data.AlbumDetail
import com.mytv.mymusic.data.AlbumSummary
import com.mytv.mymusic.data.ArtistAlbumPage
import com.mytv.mymusic.data.ArtistDetail
import com.mytv.mymusic.data.ArtistProfile
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import com.mytv.mymusic.data.native.AccountInfo
import com.mytv.mymusic.data.native.AudioQuality
import com.mytv.mymusic.data.native.NativeProvider
import com.mytv.mymusic.data.native.PlayableStream
import com.mytv.mymusic.data.native.PlaylistWriteException
import com.mytv.mymusic.data.native.PlaylistWriteFailure
import com.mytv.mymusic.data.native.SongSearchPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.io.File

class SubsonicProvider(
    account: SubsonicAccount,
    http: OkHttpClient? = null,
    private val covers: SubsonicCoverCache? = null,
    private val trackCache: SubsonicTrackCache? = null,
    sharedClient: SubsonicClient? = null,
    onAccountChanged: (SubsonicAccount) -> Unit = {},
) : NativeProvider {
    internal val client = sharedClient ?: if (http == null) {
        SubsonicClient(account, onAccountChanged = onAccountChanged)
    } else {
        SubsonicClient(account, http, onAccountChanged)
    }

    override val info: ProviderInfo get() = providerInfo(client.account)
    override val supportsSearchPagination: Boolean get() = true
    private val albumGate = Semaphore(3)

    override suspend fun searchSongsPage(cookie: String, keyword: String, page: Int, limit: Int): SongSearchPage {
        if (keyword.isBlank()) return SongSearchPage(emptyList(), page, false)
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 100)
        val params = listOf(
            "query" to keyword.trim(),
            "songCount" to safeLimit.toString(),
            "songOffset" to ((safePage - 1) * safeLimit).toString(),
            "albumCount" to "0",
            "artistCount" to "0",
        )
        val songs = try {
            val result = try {
                client.request("search3", params).obj("searchResult3")
            } catch (error: SubsonicException) {
                if (error.code !in setOf(0, 70)) throw error
                client.request("search2", params).obj("searchResult2")
            }
            mappedSongs(result?.array("song").orEmpty())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val cached = trackCache?.search(keyword.trim(), safeLimit, (safePage - 1) * safeLimit).orEmpty()
            if (cached.isEmpty()) throw error
            cached
        }
        return SongSearchPage(songs, safePage, songs.size >= safeLimit)
    }

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> =
        searchSongsPage(cookie, keyword, 1, 30).songs

    override suspend fun playableStream(
        cookie: String,
        song: Song,
        workDir: File,
        quality: AudioQuality,
    ): PlayableStream {
        requireSource(song)
        if (song.url.startsWith("file:") || song.url.startsWith("content:")) {
            return PlayableStream(url = song.url, ext = song.ext, bitrate = song.bitrate)
        }
        if (quality != AudioQuality.Auto) throw IllegalArgumentException("Subsonic 原始流不支持选择音质档位")
        return PlayableStream(
            url = client.streamUrl(song.id),
            ext = song.ext,
            bitrate = song.bitrate,
        )
    }

    override suspend fun downloadUrl(cookie: String, song: Song, quality: AudioQuality): String {
        requireSource(song)
        if (quality != AudioQuality.Auto) throw IllegalArgumentException("Subsonic 原始流不支持选择音质档位")
        return client.downloadUrl(song.id)
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        requireSource(song)
        if (client.account.openSubsonic && (client.account.extensions.isEmpty() || "songLyrics" in client.account.extensions)) {
            try {
                val list = client.request("getLyricsBySongId", listOf("id" to song.id))
                    .obj("lyricsList")?.array("structuredLyrics").orEmpty()
                    .mapNotNull { it as? JsonObject }
                if (list.isEmpty()) return ""
                val selected = list.sortedWith(
                    compareByDescending<JsonObject> { it.bool("synced") }
                        .thenBy { languageRank(it.string("lang")) },
                ).firstOrNull()
                if (selected != null) return structuredLyrics(selected)
            } catch (error: SubsonicException) {
                if (error.code !in setOf(0, 70)) throw error
            }
        }
        val response = client.request(
            "getLyrics",
            listOf("artist" to song.artist, "title" to song.name),
        ).obj("lyrics") ?: return ""
        return listOf("value", "content", "text").firstNotNullOfOrNull { key ->
            response.string(key).takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val query = keyword.trim()
        if (query.isBlank()) return emptyList()
        return playlists().filter { playlist ->
            playlist.name.contains(query, true) || playlist.description.contains(query, true)
        }
    }

    override suspend fun recommendedPlaylists(cookie: String): List<Playlist> = playlists()

    override suspend fun userPlaylists(cookie: String, page: Int, limit: Int): List<Playlist> {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 100)
        return playlists().drop((safePage - 1) * safeLimit).take(safeLimit)
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> =
        mappedSongs(client.request("getPlaylist", listOf("id" to id)).obj("playlist")?.array("entry").orEmpty())

    override suspend fun searchAlbums(cookie: String, keyword: String): List<Playlist> {
        val result = searchAll(keyword, songCount = 0, albumCount = 30, artistCount = 0)
        return mappedCollections(result?.array("album").orEmpty(), CollectionKind.Album)
    }

    override suspend fun searchArtists(cookie: String, keyword: String): List<Playlist> {
        val result = searchAll(keyword, songCount = 0, albumCount = 0, artistCount = 30)
        return mappedCollections(result?.array("artist").orEmpty(), CollectionKind.Artist)
    }

    override suspend fun albumSongs(cookie: String, id: String): List<Song> {
        val album = client.request("getAlbum", listOf("id" to id)).obj("album")
        return mappedSongs(album?.array("song").orEmpty(), album)
    }

    override suspend fun albumDetail(cookie: String, id: String): AlbumDetail {
        val album = client.request("getAlbum", listOf("id" to id)).obj("album")
            ?: throw IllegalStateException("服务器没有返回专辑资料")
        val summary = albumSummary(album, 600)
        return AlbumDetail(summary, mappedSongs(album.array("song"), album))
    }

    override suspend fun artistDetail(cookie: String, id: String): ArtistDetail {
        val artist = client.request("getArtist", listOf("id" to id)).obj("artist")
            ?: throw IllegalStateException("服务器没有返回歌手资料")
        val coverId = artist.string("coverArt").ifBlank { artist.string("artistImageUrl") }
        val name = artist.string("name")
        val hot = try {
            val param = if ("topSongsByArtistId" in client.account.extensions) "id" to id else "artist" to name
            mappedSongs(client.request("getTopSongs", listOf(param, "count" to "50")).obj("topSongs")?.array("song").orEmpty())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        return ArtistDetail(
            ArtistProfile(
                id = id,
                name = name,
                source = info.id,
                avatar = if (coverId.startsWith("http")) coverId else covers?.uri(client.account.id, coverId, 600).orEmpty(),
                songCount = artist.int("songCount"),
                albumCount = artist.int("albumCount").takeIf { it > 0 } ?: artist.array("album").size,
            ),
            hot,
        )
    }

    override suspend fun artistAlbums(cookie: String, id: String, page: Int, limit: Int): ArtistAlbumPage {
        val artist = client.request("getArtist", listOf("id" to id)).obj("artist")
        val all = artist?.array("album").orEmpty()
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 100)
        val selected = all.drop((safePage - 1) * safeLimit).take(safeLimit).mapNotNull { it as? JsonObject }
        return ArtistAlbumPage(
            albums = selected.map { albumSummary(it) },
            total = all.size,
            hasMore = safePage * safeLimit < all.size,
        )
    }

    override suspend fun artistSongs(cookie: String, id: String): List<Song> {
        val artist = client.request("getArtist", listOf("id" to id)).obj("artist")
        val albumIds = artist?.array("album").orEmpty().mapNotNull { (it as? JsonObject)?.string("id")?.takeIf(String::isNotBlank) }
        val songs = supervisorScope {
            albumIds.map { albumId ->
                async {
                    albumGate.withPermit {
                        try {
                            val album = client.request("getAlbum", listOf("id" to albumId)).obj("album")
                            mappedSongs(album?.array("song").orEmpty(), album)
                        } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                    }
                }
            }.awaitAll().flatten()
        }
        return songs.distinctBy { it.id }
    }

    override suspend fun topLists(cookie: String): List<TopList> = listOf(
        TopList("starred", "我的收藏", description = "服务器账号收藏的歌曲", source = info.id),
        TopList("newest", "最新专辑", description = "最近加入服务器的专辑", source = info.id),
        TopList("recent", "最近播放", description = "这个账号最近播放的专辑", source = info.id),
        TopList("highest", "高分专辑", description = "服务器上评分最高的专辑", source = info.id),
        TopList("frequent", "常听专辑", description = "这个账号播放最多的专辑", source = info.id),
    )

    override suspend fun topListSongs(cookie: String, id: String): List<Song> = when (id) {
        "starred" -> {
            try {
                mappedSongs(client.request("getStarred2").obj("starred2")?.array("song").orEmpty())
            } catch (error: SubsonicException) {
                if (error.code !in setOf(0, 70)) throw error
                mappedSongs(client.request("getStarred").obj("starred")?.array("song").orEmpty())
            }
        }
        in albumCharts -> albumChartSongs(id)
        else -> throw IllegalArgumentException("未知的服务器榜单")
    }

    override suspend fun hotSongs(cookie: String, limit: Int): List<Song> = albumChartSongs("frequent", limit)

    override fun supportsCollect(): Boolean = true

    override suspend fun collectTrack(cookie: String, song: Song): String? {
        requireSource(song)
        client.request("star", listOf("id" to song.id))
        return null
    }

    override suspend fun uncollectTrack(cookie: String, song: Song): String? {
        requireSource(song)
        client.request("unstar", listOf("id" to song.id))
        return null
    }

    override suspend fun accountInfo(cookie: String): AccountInfo {
        val user = client.request("getUser", listOf("username" to client.account.username)).obj("user")
        return AccountInfo(
            nickname = user?.string("username").orEmpty().ifBlank { client.account.username },
            detail = listOf(
                client.account.serverType.ifBlank { "Subsonic" }.replaceFirstChar(Char::uppercase),
                client.account.serverVersion,
            ).filter { it.isNotBlank() }.joinToString(" · "),
        )
    }

    override fun playlistWritableFromAccountList(playlist: Playlist): Boolean =
        client.account.playlistRole != false && playlist.extra["readonly"] != "1"

    override suspend fun playlistWritable(cookie: String, id: String): Boolean? {
        val playlist = try {
            client.request("getPlaylist", listOf("id" to id)).obj("playlist") ?: return null
        } catch (error: SubsonicException) {
            if (error.code == 70) return false
            throw error
        }
        return !playlist.bool("readonly") && client.account.playlistRole != false
    }

    override suspend fun addPlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        ensureWritable(playlistId)
        songs.onEach(::requireSource).map { it.id }.chunked(100).forEach { chunk ->
            writeRequest("updatePlaylist", listOf("playlistId" to playlistId) + chunk.map { "songIdToAdd" to it })
        }
    }

    override suspend fun removePlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        ensureWritable(playlistId)
        val entries = client.request("getPlaylist", listOf("id" to playlistId)).obj("playlist")?.array("entry").orEmpty()
        val remaining = entries.map { (it as? JsonObject)?.string("id") }.toMutableList()
        val indices = mutableListOf<Int>()
        songs.onEach(::requireSource).forEach { song ->
            val index = remaining.indexOf(song.id)
            if (index >= 0) {
                indices += index
                remaining[index] = null
            }
        }
        if (indices.isEmpty()) throw PlaylistWriteException("歌单里找不到要删除的歌曲", PlaylistWriteFailure.SongNotFound)
        indices.sortedDescending().chunked(100).forEach { chunk ->
            writeRequest(
                "updatePlaylist",
                listOf("playlistId" to playlistId) + chunk.map { "songIndexToRemove" to it.toString() },
            )
        }
    }

    suspend fun scrobble(songId: String, submission: Boolean = true) {
        client.request("scrobble", listOf("id" to songId, "submission" to submission.toString()))
    }

    suspend fun reportPlayback(songId: String, positionMs: Long, playing: Boolean) {
        if ("playbackReport" !in client.account.extensions) return
        client.request(
            "reportPlayback",
            listOf(
                "mediaId" to songId,
                "mediaType" to "song",
                "positionMs" to positionMs.toString(),
                "state" to if (playing) "playing" else "paused",
                "ignoreScrobble" to "true",
            ),
        )
    }

    private suspend fun playlists(): List<Playlist> {
        val items = client.request("getPlaylists").obj("playlists")?.array("playlist").orEmpty()
        return mappedCollections(items, CollectionKind.Playlist)
    }

    private suspend fun searchAll(keyword: String, songCount: Int, albumCount: Int, artistCount: Int): JsonObject? {
        if (keyword.isBlank()) return null
        val params = listOf(
            "query" to keyword.trim(),
            "songCount" to songCount.toString(),
            "albumCount" to albumCount.toString(),
            "artistCount" to artistCount.toString(),
        )
        return try {
            client.request("search3", params).obj("searchResult3")
        } catch (error: SubsonicException) {
            if (error.code !in setOf(0, 70)) throw error
            client.request("search2", params).obj("searchResult2")
        }
    }

    private suspend fun albumChartSongs(type: String, limit: Int = 100): List<Song> {
        val albums = client.request(
            "getAlbumList2",
            listOf("type" to type, "size" to "20", "offset" to "0"),
        ).obj("albumList2")?.array("album").orEmpty().mapNotNull { (it as? JsonObject)?.string("id") }
        return supervisorScope {
            albums.map { albumId ->
                async {
                    albumGate.withPermit {
                        try {
                            val album = client.request("getAlbum", listOf("id" to albumId)).obj("album")
                            mappedSongs(album?.array("song").orEmpty(), album)
                        } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                    }
                }
            }.awaitAll().flatten().distinctBy { it.id }.take(limit)
        }
    }

    private suspend fun mappedSongs(items: List<kotlinx.serialization.json.JsonElement>, parent: JsonObject? = null): List<Song> {
        val songs = items.mapNotNull { item ->
            (item as? JsonObject)?.let { SubsonicMapper.song(it, info.id, parent) { id, size -> covers?.uri(client.account.id, id, size).orEmpty() } }
        }
        trackCache?.putAll(songs)
        return songs
    }

    private suspend fun mappedCollections(items: List<kotlinx.serialization.json.JsonElement>, kind: CollectionKind): List<Playlist> {
        val playlists = items.mapNotNull { item ->
            (item as? JsonObject)?.let { SubsonicMapper.playlist(it, info.id, kind) { id, size -> covers?.uri(client.account.id, id, size).orEmpty() } }
        }
        return playlists
    }

    private fun albumSummary(item: JsonObject, size: Int = 300): AlbumSummary {
        val playlist = SubsonicMapper.playlist(item, info.id, CollectionKind.Album) { id, requestedSize ->
            covers?.uri(client.account.id, id, requestedSize).orEmpty()
        } ?: throw IllegalStateException("专辑缺少 id")
        return AlbumSummary(
            id = playlist.id,
            name = playlist.name,
            source = info.id,
            cover = if (size == 300) playlist.cover else covers?.uri(client.account.id, item.string("coverArt"), size).orEmpty(),
            artist = playlist.creator,
            artistId = item.string("artistId"),
            publishTime = item.int("year").takeIf { it > 0 }?.toString().orEmpty(),
            trackCount = playlist.trackCount,
            description = playlist.description,
        )
    }

    private suspend fun ensureWritable(playlistId: String) {
        if (client.account.playlistRole == false || playlistWritable("", playlistId) == false) {
            throw PlaylistWriteException("这个歌单在服务器上是只读的", PlaylistWriteFailure.NotOwner)
        }
    }

    private suspend fun writeRequest(endpoint: String, params: List<Pair<String, String>>) {
        try {
            client.request(endpoint, params, post = "formPost" in client.account.extensions)
        } catch (error: SubsonicException) {
            val failure = when (error.code) {
                40, 41, 44 -> PlaylistWriteFailure.NotLoggedIn
                50 -> PlaylistWriteFailure.NotOwner
                70 -> PlaylistWriteFailure.SongNotFound
                else -> PlaylistWriteFailure.Other
            }
            throw PlaylistWriteException(error.message.orEmpty(), failure, error)
        }
    }

    private fun requireSource(song: Song) {
        if (song.source != info.id) throw IllegalArgumentException("歌曲不属于这个服务器")
    }

    private enum class CollectionKind { Playlist, Album, Artist }

    companion object {
        private val albumCharts = setOf("newest", "recent", "highest", "frequent")

        fun providerInfo(account: SubsonicAccount): ProviderInfo = ProviderInfo(
            id = account.providerId,
            name = account.displayName,
            search = account.streamRole != false,
            download = account.downloadRole != false,
            lyrics = true,
            searchPlaylist = true,
            recommendedPlaylists = true,
            playlistSongs = true,
            searchAlbum = true,
            searchArtist = true,
            albumSongs = true,
            userPlaylists = true,
            topLists = true,
            topListSongs = true,
            hotSongs = true,
            playlistWrite = account.playlistRole != false,
            playlistReorder = false,
            loginRequired = true,
            unstable = "server-dependent",
            note = "自建音乐服务器",
        )
    }
}

internal object SubsonicMapper {
    fun song(item: JsonObject, source: String, parent: JsonObject? = null, cover: (String, Int) -> String): Song? {
        val id = item.string("id").takeIf { it.isNotBlank() } ?: return null
        val artists = item.array("artists").mapNotNull { (it as? JsonObject)?.string("name") }
        val coverId = item.string("coverArt").ifBlank { parent?.string("coverArt").orEmpty() }
        return Song(
            id = id,
            name = item.string("title"),
            artist = item.string("displayArtist").ifBlank { item.string("artist") }.ifBlank { artists.joinToString(" / ") }
                .ifBlank { parent?.string("displayArtist").orEmpty().ifBlank { parent?.string("artist").orEmpty() } },
            album = item.string("album").ifBlank { parent?.string("name").orEmpty() },
            albumId = item.string("albumId").ifBlank { parent?.string("id").orEmpty() },
            duration = item.int("duration"),
            size = item.long("size"),
            bitrate = item.int("bitRate"),
            source = source,
            ext = item.string("suffix"),
            cover = cover(coverId, 300),
            extra = buildMap {
                if (coverId.isNotBlank()) put("subsonic_cover", coverId)
                item.string("musicBrainzId").takeIf { it.isNotBlank() }?.let { put("mbid", it) }
                item.array("isrc").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                    .firstOrNull()?.takeIf { it.isNotBlank() }?.let { put("isrc", it) }
                item.int("track").takeIf { it > 0 }?.let { put("track", it.toString()) }
                item.int("discNumber").takeIf { it > 0 }?.let { put("disc", it.toString()) }
                item.int("year").takeIf { it > 0 }?.let { put("year", it.toString()) }
                if (item.string("starred").isNotBlank()) put("starred", "1")
                item.obj("replayGain")?.let { replay ->
                    replay.string("trackGain").takeIf { it.isNotBlank() }?.let { put("replay_gain", it) }
                    replay.string("trackPeak").takeIf { it.isNotBlank() }?.let { put("replay_peak", it) }
                }
            },
        )
    }

    fun playlist(
        item: JsonObject,
        source: String,
        kind: Any,
        cover: (String, Int) -> String,
    ): Playlist? {
        val id = item.string("id").takeIf { it.isNotBlank() } ?: return null
        val coverId = item.string("coverArt")
        val kindName = kind.toString().substringAfterLast('.').lowercase()
        return Playlist(
            id = id,
            name = item.string("name").ifBlank { item.string("title") },
            cover = cover(coverId, 300),
            trackCount = item.int("songCount"),
            creator = item.string("artist").ifBlank { item.string("owner") },
            description = item.string("comment").ifBlank { item.string("genre") },
            source = source,
            extra = buildMap {
                put("type", kindName)
                if (coverId.isNotBlank()) put("subsonic_cover", coverId)
                if (item.bool("readonly")) put("readonly", "1")
                item.string("owner").takeIf { it.isNotBlank() }?.let { put("owner", it) }
                item.string("artistId").takeIf { it.isNotBlank() }?.let { put("artist_id", it) }
                item.int("year").takeIf { it > 0 }?.let { put("publish_time", it.toString()) }
                if (kindName == "artist") put("album_count", item.int("albumCount").toString())
            },
        )
    }
}

internal fun structuredLyrics(lyrics: JsonObject): String {
    val lines = lyrics.array("line").mapNotNull { it as? JsonObject }
    if (lines.isEmpty()) return ""
    if (!lyrics.bool("synced")) return lines.joinToString("\n") { it.string("value") }
    val offset = lyrics.long("offset")
    return buildString {
        if (offset != 0L) append("[offset:").append(offset).append("]\n")
        lines.forEach { line ->
            val start = line.long("start").coerceAtLeast(0)
            val minutes = start / 60_000
            val seconds = (start % 60_000) / 1000
            val millis = start % 1000
            append('[').append("%02d:%02d.%03d".format(minutes, seconds, millis)).append(']')
            append(line.string("value")).append('\n')
        }
    }.trimEnd()
}

private fun languageRank(language: String): Int = when (language.lowercase()) {
    "zho", "chi", "zh", "zh-cn" -> 0
    "eng", "en" -> 1
    "none", "xxx", "und", "" -> 3
    else -> 2
}
