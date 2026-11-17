package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.CollectionResult
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** 探测账号音质用的稳定曲目（music-lib fivesing/quality.go 同款）。 */
private const val FIVESING_QUALITY_PROBE_SONG_ID = "3529943"
private const val FIVESING_QUALITY_PROBE_SONG_TYPE = "yc"

private data class FivesingQualityCandidate(val option: AudioQualityOption, val tier: String)

/**
 * 5sing 暴露的 MP3 档，从高到低。sq/hq/lq 是 getSongUrl 同一响应里的三个字段。
 * 标签照抄 music-lib 的 model.AudioQualityOption。
 */
private val FIVESING_QUALITY_CANDIDATES = listOf(
    FivesingQualityCandidate(AudioQualityOption(AudioQuality.High, "高品质", "mp3", 320), "sq"),
    FivesingQualityCandidate(AudioQualityOption(AudioQuality.Higher, "较高品质", "mp3", 192), "hq"),
    FivesingQualityCandidate(AudioQualityOption(AudioQuality.Standard, "标准音质", "mp3", 128), "lq"),
)

internal val FIVESING_QUALITY_LADDER: AudioQualityLadder =
    AudioQualityLadder.of(FIVESING_QUALITY_CANDIDATES.map { it.option })

private fun fivesingQualityCandidateFor(quality: AudioQuality): FivesingQualityCandidate? =
    FIVESING_QUALITY_CANDIDATES.firstOrNull { it.option.value == quality }

/** 三档地址，探测器按字段判断可用档。 */
private data class FivesingAudioLinks(val sq: String, val hq: String, val lq: String)

private val FIVESING_INFO = ProviderInfo(
    id = "fivesing",
    name = "5sing",
    search = true,
    download = true,
    lyrics = true,
    parseSong = true,
    searchPlaylist = true,
    recommendedPlaylists = false,
    playlistSongs = true,
    parsePlaylist = true,
    searchAlbum = false,
    albumSongs = false,
    parseAlbum = false,
    userPlaylists = false,
    qrLogin = false,
    playlistCategories = false,
    topLists = false,
    topListSongs = false,
    hotSongs = false,
)

class FivesingProvider : BaseNativeProvider(FIVESING_INFO) {
    private val json = NativeHttp.json
    private val availableQualityCache = ConcurrentHashMap<String, List<AudioQualityOption>>()

    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
        val url = "$SEARCH_API?" + encodeParams(
            mapOf(
                "keyword" to keyword,
                "sort" to "1",
                "page" to "1",
                "filter" to "0",
                "type" to "0",
            )
        )
        val body = apiGet(url, cookie)
        val response = json.decodeFromString<FivesingSearchResponse>(body)
        return response.list.map { item ->
            val duration = if (item.songSize > 0) (item.songSize * 8 / 320_000).toInt() else 0
            Song(
                id = "${item.songId}|${item.typeEname}",
                name = removeEmTags(unescapeHtml(item.songName)),
                artist = removeEmTags(unescapeHtml(item.singer)),
                duration = duration,
                size = item.songSize,
                source = "fivesing",
                link = "http://5sing.kugou.com/${item.typeEname}/${item.songId}.html",
                extra = mapOf(
                    "songid" to item.songId.toString(),
                    "songtype" to item.typeEname,
                ),
            )
        }
    }

    override suspend fun parseSong(cookie: String, link: String): Song {
        val match = SONG_LINK.find(link) ?: throw IllegalArgumentException("invalid 5sing link")
        return fetchSongInfo(cookie, match.groupValues[2], match.groupValues[1])
    }

    override suspend fun downloadUrl(cookie: String, song: Song, quality: AudioQuality): String {
        if (song.source != "fivesing") throw IllegalArgumentException("source mismatch")
        if (quality == AudioQuality.Auto) {
            if (song.url.isNotBlank()) return song.url
            val (songId, songType) = songParts(song)
            return fetchAudioLink(cookie, songId, songType)
        }
        // 指定档位：只拿这一档，接口没给就报错；不能复用 song.url（自动选的最高档）
        val candidate = fivesingQualityCandidateFor(quality)
            ?: throw IllegalArgumentException("unsupported 5sing audio quality: $quality")
        val (songId, songType) = songParts(song)
        val links = fetchAudioLinks(cookie, songId, songType)
        val streamUrl = when (candidate.tier) {
            "sq" -> links.sq
            "hq" -> links.hq
            else -> links.lq
        }
        if (streamUrl.isBlank()) throw IllegalStateException("5sing audio quality ${candidate.option.value} is unavailable")
        return streamUrl
    }

    /** 用一个稳定曲目探测哪些档真正可解析：5sing 的 sq/hq/lq 是同一响应的三个字段。 */
    override suspend fun availableQualityOptions(cookie: String): List<AudioQualityOption> {
        availableQualityCache[cookie]?.let { return it }
        val links = runCatching { fetchAudioLinks(cookie, FIVESING_QUALITY_PROBE_SONG_ID, FIVESING_QUALITY_PROBE_SONG_TYPE) }
            .getOrNull() ?: return emptyList()
        val options = FIVESING_QUALITY_CANDIDATES.filter { candidate ->
            when (candidate.tier) {
                "sq" -> links.sq.isNotBlank()
                "hq" -> links.hq.isNotBlank()
                else -> links.lq.isNotBlank()
            }
        }.map { it.option }
        availableQualityCache[cookie] = options
        return options
    }

    /** 指定档位时不能走基类的 song.url 短路（那是自动选的最高档），必须重新按档取流。 */
    override suspend fun playableStream(cookie: String, song: Song, workDir: File, quality: AudioQuality): PlayableStream {
        val url = if (quality == AudioQuality.Auto) {
            song.url.ifBlank { downloadUrl(cookie, song, AudioQuality.Auto) }
        } else {
            downloadUrl(cookie, song, quality)
        }
        val candidate = fivesingQualityCandidateFor(quality)
        return PlayableStream(
            url = url,
            ext = candidate?.option?.format ?: song.ext.ifBlank { extensionFromUrl(url) },
            bitrate = candidate?.option?.bitrate?.takeIf { it > 0 } ?: song.bitrate,
        )
    }

    override suspend fun lyrics(cookie: String, song: Song): String {
        if (song.source != "fivesing") throw IllegalArgumentException("source mismatch")
        val (songId, songType) = songParts(song)
        val body = apiGet(
            "$NEWGET_API?" + encodeParams(mapOf("songid" to songId, "songtype" to songType)),
            cookie,
        )
        val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonObject }.getOrNull()
        val words = data?.get("dynamicWords")?.jsonPrimitive?.content.orEmpty()
        if (words.isBlank()) throw IllegalStateException("lyrics not found")
        return words
    }

    override suspend fun searchPlaylists(cookie: String, keyword: String): List<Playlist> {
        val url = "$SEARCH_API?" + encodeParams(
            mapOf(
                "keyword" to keyword,
                "sort" to "1",
                "page" to "1",
                "filter" to "0",
                "type" to "1",
            )
        )
        val body = apiGet(url, cookie)
        val response = json.decodeFromString<FivesingPlaylistSearchResponse>(body)
        val playlists = response.list.map { item ->
            Playlist(
                id = item.songListId,
                name = removeEmTags(unescapeHtml(item.title)),
                cover = item.pictureUrl,
                trackCount = item.songCnt,
                playCount = item.playCount.toLong(),
                creator = item.userName.ifBlank { "ID: ${item.userId}" },
                description = removeEmTags(unescapeHtml(item.content)).takeUnless { it == "0" }.orEmpty(),
                source = "fivesing",
                link = "http://5sing.kugou.com/${item.userId}/dj/${item.songListId}.html",
                extra = mapOf("user_id" to item.userId),
            )
        }
        return coroutineScope {
            playlists.mapIndexed { index, playlist ->
                async {
                    if (playlist.creator.startsWith("ID: ") && playlist.id.isNotBlank()) {
                        val name = runCatching { fetchCreatorName(cookie, playlist.id) }.getOrNull().orEmpty()
                        if (name.isNotBlank()) playlist.copy(creator = name) else playlist
                    } else {
                        playlist
                    }
                }
            }.map { it.await() }
        }
    }

    override suspend fun playlistSongs(cookie: String, id: String): List<Song> =
        fetchPlaylistDetail(cookie, id).songs

    override suspend fun parsePlaylist(cookie: String, link: String): CollectionResult {
        val match = PLAYLIST_LINK.find(link) ?: throw IllegalArgumentException("invalid 5sing playlist link")
        return fetchPlaylistDetail(cookie, match.groupValues[2])
    }

    private suspend fun fetchSongInfo(cookie: String, songId: String, songType: String): Song {
        val audioUrl = fetchAudioLink(cookie, songId, songType)
        val body = runCatching {
            apiGet(
                "$NEWGET_API?" + encodeParams(mapOf("songid" to songId, "songtype" to songType)),
                cookie,
            )
        }.getOrNull()
        val data = body?.let { runCatching { json.parseToJsonElement(it).jsonObject["data"]?.jsonObject }.getOrNull() }
        val name = data?.get("SN")?.jsonPrimitive?.content.orEmpty().ifBlank { "5sing_${songType}_$songId" }
        val user = data?.get("user")?.jsonObject
        return Song(
            id = "$songId|$songType",
            name = name,
            artist = user?.get("NN")?.jsonPrimitive?.content.orEmpty(),
            source = "fivesing",
            cover = user?.get("I")?.jsonPrimitive?.content.orEmpty(),
            url = audioUrl,
            link = "http://5sing.kugou.com/$songType/$songId.html",
            extra = mapOf("songid" to songId, "songtype" to songType),
        )
    }

    private suspend fun fetchAudioLink(cookie: String, songId: String, songType: String): String {
        val links = fetchAudioLinks(cookie, songId, songType)
        return firstValid(links.sq, links.hq, links.lq).ifBlank {
            throw IllegalStateException("no valid download url found")
        }
    }

    /** 一次取回 sq/hq/lq 三档地址，供选档与探测共用。 */
    private suspend fun fetchAudioLinks(cookie: String, songId: String, songType: String): FivesingAudioLinks {
        val body = apiGet(
            "$SONG_URL_API?" + encodeParams(mapOf("songid" to songId, "songtype" to songType)),
            cookie,
        )
        val parsed = json.decodeFromString<FivesingUrlResponse>(body)
        if (parsed.code != 1000) throw IllegalStateException("5sing api returned error code ${parsed.code}")
        val links = FivesingAudioLinks(
            sq = firstValid(parsed.data.squrl, parsed.data.squrlBackup),
            hq = firstValid(parsed.data.hqurl, parsed.data.hqurlBackup),
            lq = firstValid(parsed.data.lqurl, parsed.data.lqurlBackup),
        )
        if (links.sq.isBlank() && links.hq.isBlank() && links.lq.isBlank()) {
            throw IllegalStateException("no valid download url found")
        }
        return links
    }

    private suspend fun fetchCreatorName(cookie: String, id: String): String {
        val body = apiGet(
            "$SONG_LIST_API?" + encodeParams(mapOf("id" to id, "songfields" to "user")),
            cookie,
        )
        val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonObject }.getOrNull() ?: return ""
        return data["user"]?.jsonObject?.get("NN")?.jsonPrimitive?.content.orEmpty()
    }

    private suspend fun fetchPlaylistDetail(cookie: String, id: String): CollectionResult {
        val infoBody = apiGet(
            "$SONG_LIST_API?" + encodeParams(mapOf("id" to id, "songfields" to "ID,user")),
            cookie,
        )
        val data = runCatching { json.parseToJsonElement(infoBody).jsonObject["data"]?.jsonObject }.getOrNull()
            ?: throw IllegalStateException("playlist info not found or invalid (api returned empty list)")
        val title = data["T"]?.jsonPrimitive?.content.orEmpty()
        val content = data["C"]?.jsonPrimitive?.content.orEmpty()
        val picture = data["P"]?.jsonPrimitive?.content.orEmpty()
        val click = data["H"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val songCount = data["E"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val user = data["user"]?.jsonObject
        val userId = user?.get("ID")?.jsonPrimitive?.content.orEmpty()
        if (userId.isBlank() || userId == "0") throw IllegalStateException("playlist user not found or invalid id")
        val playlist = Playlist(
            id = id,
            name = title,
            cover = picture,
            trackCount = songCount,
            playCount = click,
            creator = user?.get("NN")?.jsonPrimitive?.content.orEmpty(),
            description = content,
            source = "fivesing",
            link = "http://5sing.kugou.com/$userId/dj/$id.html",
            extra = mapOf("user_id" to userId),
        )
        val page = runCatching { apiGet(playlist.link, cookie) }.getOrNull()
            ?: return CollectionResult(playlist, emptyList())
        return CollectionResult(playlist, parseSongsFromHtml(page))
    }

    private fun parseSongsFromHtml(content: String): List<Song> {
        val blocks = Regex("""<li class="p_rel">([\s\S]*?)</li>""").findAll(content).toList()
        if (blocks.isEmpty()) return emptyList()
        val result = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        blocks.forEach { block ->
            val html = block.groupValues[1]
            val match = SONG_IN_HTML.find(html) ?: return@forEach
            val kind = match.groupValues[1]
            val songId = match.groupValues[2]
            val rawName = match.groupValues[3]
            val artist = ARTIST_IN_HTML.find(html)?.groupValues?.getOrNull(1).orEmpty().ifBlank { "Unknown" }
            val key = "$kind|$songId"
            if (!seen.add(key)) return@forEach
            result += Song(
                id = "$songId|$kind",
                name = removeEmTags(unescapeHtml(rawName)),
                artist = removeEmTags(unescapeHtml(artist)),
                source = "fivesing",
                link = "http://5sing.kugou.com/$kind/$songId.html",
                extra = mapOf("songid" to songId, "songtype" to kind),
            )
        }
        return result
    }

    private suspend fun apiGet(url: String, cookie: String): String =
        NativeHttp.get(url, headers = mapOf("Referer" to "http://5sing.kugou.com/"), cookie = cookie, userAgent = USER_AGENT)

    private fun songParts(song: Song): Pair<String, String> {
        val extraId = song.extra["songid"].orEmpty()
        val extraType = song.extra["songtype"].orEmpty()
        if (extraId.isNotBlank() && extraType.isNotBlank()) return extraId to extraType
        val parts = song.id.split('|')
        if (parts.size != 2) throw IllegalArgumentException("invalid id structure")
        return parts[0] to parts[1]
    }

    private fun removeEmTags(value: String): String =
        value.replace("<em class=\"keyword\">", "").replace("</em>", "").trim()

    private fun unescapeHtml(value: String): String {
        var result = value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        result = Regex("""&#(\d+);""").replace(result) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        result = Regex("""&#x([0-9a-fA-F]+);""").replace(result) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
        return result
    }

    private fun firstValid(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun encodeParams(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val SEARCH_API = "http://search.5sing.kugou.com/home/json"
        const val NEWGET_API = "http://mobileapi.5sing.kugou.com/song/newget"
        const val SONG_URL_API = "http://mobileapi.5sing.kugou.com/song/getSongUrl"
        const val SONG_LIST_API = "http://mobileapi.5sing.kugou.com/song/getsonglist"
        val SONG_LINK = Regex("""5sing\.kugou\.com/(\w+)/(\d+)\.html""")
        val PLAYLIST_LINK = Regex("""5sing\.kugou\.com/(?:(\d+)/)?dj/([a-zA-Z0-9]+)\.html""")
        val SONG_IN_HTML = Regex("""href="http://5sing\.kugou\.com/(yc|fc|bz)/(\d+)\.html"[^>]*>([^<]+)</a>""")
        val ARTIST_IN_HTML = Regex("""class="s_soner[^"]*".*?>([^<]+)</a>""")
    }
}

@Serializable
private data class FivesingSearchResponse(val list: List<FivesingSearchItem> = emptyList())

@Serializable
private data class FivesingSearchItem(
    val songId: Long = 0,
    val songName: String = "",
    val singer: String = "",
    val songSize: Long = 0,
    val typeEname: String = "",
)

@Serializable
private data class FivesingPlaylistSearchResponse(val list: List<FivesingPlaylistSearchItem> = emptyList())

@Serializable
private data class FivesingPlaylistSearchItem(
    val songListId: String = "",
    val title: String = "",
    val pictureUrl: String = "",
    val playCount: Int = 0,
    val userName: String = "",
    val songCnt: Int = 0,
    val content: String = "",
    val userId: String = "",
)

@Serializable
private data class FivesingUrlResponse(val code: Int = 0, val data: FivesingUrlData = FivesingUrlData())

@Serializable
private data class FivesingUrlData(
    val squrl: String = "",
    val squrl_backup: String = "",
    val hqurl: String = "",
    val hqurl_backup: String = "",
    val lqurl: String = "",
    val lqurl_backup: String = "",
) {
    val squrlBackup get() = squrl_backup
    val hqurlBackup get() = hqurl_backup
    val lqurlBackup get() = lqurl_backup
}
