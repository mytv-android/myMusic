package com.mytv.mymusic.data.metadata

import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.aggregate.TrackNormalizer
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.NativeHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.roundToInt

/** 公共元数据兜底：Rush 同源的 LRCLIB 歌词 + 无需私人令牌的 Apple 搜索封面。 */
object CommunityMetadata {
    internal const val ACCEPT_SCORE = 0.72f
    private const val USER_AGENT = "myMusic/1.1 (https://github.com/mytv-android/myMusic)"

    suspend fun fetch(song: Song, needLyrics: Boolean, needCover: Boolean): CommunityMetadataResult = coroutineScope {
        val lyrics = if (needLyrics) async { safely { fetchLyrics(song) } } else null
        val cover = if (needCover) async { safely { fetchCover(song) } } else null
        CommunityMetadataResult(lyrics?.await().orEmpty(), cover?.await().orEmpty())
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    /** 查询顺序与 Rush 7.2.1 一致；但这里用 TrackMatcher 打分，不直接拿第一条。 */
    private suspend fun fetchLyrics(song: Song): String {
        val title = cleanTitle(song.name).ifBlank { song.name.trim() }
        val artist = TrackNormalizer.splitArtists(song.artist).firstOrNull().orEmpty().ifBlank { song.artist.trim() }
        if (title.isBlank()) return ""
        val queries = buildList {
            add(
                mapOf(
                    "track_name" to title,
                    "artist_name" to artist,
                    "album_name" to song.album.trim(),
                ).filterValues { it.isNotBlank() },
            )
            add(mapOf("q" to listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")))
            if (title != song.name.trim()) {
                add(mapOf("track_name" to song.name.trim(), "artist_name" to song.artist.trim()))
            }
        }
        for (query in queries) {
            val results = try {
                val url = url("https://lrclib.net/api/search", query)
                parseLrcLib(NativeHttp.get(url, userAgent = USER_AGENT))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptyList()
            }
            val selected = selectLrcLib(song, results)
            if (selected.isNotBlank()) return selected
        }
        return ""
    }

    private suspend fun fetchCover(song: Song): String {
        if (song.name.isBlank()) return ""
        val query = listOf(song.name.trim(), TrackNormalizer.splitArtists(song.artist).firstOrNull().orEmpty())
            .filter { it.isNotBlank() }.joinToString(" ")
        val url = url(
            "https://itunes.apple.com/search",
            mapOf("term" to query, "media" to "music", "entity" to "song", "limit" to "10"),
        )
        return selectAppleCover(song, parseAppleSearch(NativeHttp.get(url, userAgent = USER_AGENT)))
    }

    private fun cleanTitle(value: String): String = value.trim()
        .replace(Regex("""[\(\[（【][^\)\]）】]*[\)\]）】]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

data class CommunityMetadataResult(val lyrics: String = "", val cover: String = "")

@Serializable
internal data class LrcLibEntry(
    val id: Long = 0,
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

internal fun parseLrcLib(body: String): List<LrcLibEntry> =
    NativeHttp.json.decodeFromString(body)

internal fun selectLrcLib(source: Song, entries: List<LrcLibEntry>): String {
    val usable = entries.filter { !it.instrumental && (!it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank()) }
    val candidates = usable.mapIndexed { index, entry ->
        Song(
            id = index.toString(),
            name = entry.trackName,
            artist = entry.artistName,
            album = entry.albumName.takeUnless { it.equals("null", true) }.orEmpty(),
            duration = entry.duration.roundToInt(),
            source = "lrclib",
        )
    }
    val match = TrackMatcher.bestMatch(source, "lrclib", candidates, CommunityMetadata.ACCEPT_SCORE).song ?: return ""
    val index = match.id.toIntOrNull() ?: return ""
    val entry = usable.getOrNull(index) ?: return ""
    return entry.syncedLyrics?.takeIf { it.isNotBlank() } ?: entry.plainLyrics?.trim().orEmpty()
}

@Serializable
internal data class AppleSearchEntry(
    val trackId: Long = 0,
    val trackName: String = "",
    val artistName: String = "",
    val collectionName: String = "",
    val trackTimeMillis: Long = 0,
    val artworkUrl100: String = "",
)

@Serializable
private data class AppleSearchResponse(val results: List<AppleSearchEntry> = emptyList())

internal fun parseAppleSearch(body: String): List<AppleSearchEntry> =
    NativeHttp.json.decodeFromString<AppleSearchResponse>(body).results

internal fun selectAppleCover(source: Song, entries: List<AppleSearchEntry>): String {
    val usable = entries.filter { it.artworkUrl100.isNotBlank() }
    val candidates = usable.mapIndexed { index, entry ->
        Song(
            id = index.toString(),
            name = entry.trackName,
            artist = entry.artistName,
            album = entry.collectionName,
            duration = (entry.trackTimeMillis / 1000).toInt(),
            source = "apple",
        )
    }
    val match = TrackMatcher.bestMatch(source, "apple", candidates, CommunityMetadata.ACCEPT_SCORE).song ?: return ""
    val index = match.id.toIntOrNull() ?: return ""
    return usable.getOrNull(index)?.artworkUrl100
        ?.replace(Regex("""/100x100([^/]*\.(?:jpg|png))$"""), "/1200x1200$1")
        .orEmpty()
}

private fun url(base: String, params: Map<String, String>): String = base.toHttpUrl().newBuilder().apply {
    params.forEach { (key, value) -> addQueryParameter(key, value) }
}.build().toString()
