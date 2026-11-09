package com.mytv.mymusic.data.local

import android.content.Context
import android.net.Uri
import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.data.AppSettings
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.NativeProviders
import com.mytv.mymusic.data.native.NetEaseProvider
import com.mytv.mymusic.recognize.NeteaseFingerprinter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException

/** 一个匹配候选：平台歌、所在平台、TrackMatcher 打分（识曲给的是识曲置信度）。 */
data class LocalCandidate(val song: Song, val providerId: String, val score: Float, val method: String)

/** 网络失败（不是“没找到”）：这首先不标“没匹配”，下次再试。 */
class LocalMatchNetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 本地歌的封面 / 歌词匹配（规格 5.1）：
 * 1. 标题和歌手都像样 → 在已启用的平台里搜，TrackMatcher 打分 ≥ 0.85 直接用；
 * 2. 没有像样的标签或分数不够 → 取 3 秒 8kHz 单声道，本机算网易云指纹，交给网易云识曲；
 * 3. 还不行 → 没匹配，等用户在「换一个」里手动选。
 * 识曲只发指纹，不发音频；搜索只发歌名和歌手。
 */
class LocalMatcher(
    private val context: Context,
    private val repository: MusicRepository,
    private val settings: suspend () -> AppSettings,
) {
    private val fingerprinter by lazy { NeteaseFingerprinter(context) }
    // 指纹在 WebView 里跑，一次一个，免得自动匹配时同时开好几个 WebView
    private val fingerprintLock = Mutex()
    private val payloadJson = Json { ignoreUnknownKeys = true }

    /** 已启用、能搜索的平台，按换源优先级排。 */
    suspend fun searchProviders(): List<String> {
        val searchable = runCatching { repository.providers() }.getOrDefault(emptyList()).filter { it.search }.map { it.id }
        if (searchable.isEmpty()) return emptyList()
        val aggregation = settings().aggregation
        val enabled = aggregation.enabledOrDefault(searchable).filter { it in searchable }
        return aggregation.priorityFor(enabled)
    }

    /**
     * 拿来和候选打分的“这首歌”：标签像样用标签，否则用文件名猜的；
     * 文件名也是占位时，已经匹配上就拿匹配到的歌名歌手（多半是对的，换平台找同一首时分数才有意义），
     * 再不行用搜索词当歌名。
     */
    fun reference(row: LocalTrackEntity, query: String = ""): Song {
        val usable = LocalTags.hasUsableTags(row.title, row.artist, row.displayName)
        val (title, artist) = when {
            usable -> row.title to row.artist
            !LocalTags.isJunkTitle(row.title, row.displayName) -> row.title to ""
            else -> {
                val guess = LocalTags.guessFromFileName(row.displayName)
                val match = matchOf(row)
                when {
                    !LocalTags.isJunkTitle(guess.first, "") -> guess
                    match != null && match.name.isNotBlank() -> match.name to match.artist
                    query.isNotBlank() -> query to ""
                    else -> guess
                }
            }
        }
        return Song(
            id = row.localId,
            name = title,
            artist = artist,
            album = row.album,
            duration = row.durationSec,
            source = LOCAL_SOURCE,
        )
    }

    private fun matchOf(row: LocalTrackEntity): Song? =
        row.matchPayload.takeIf { row.matched && it.isNotBlank() }
            ?.let { payload -> runCatching { payloadJson.decodeFromString(Song.serializer(), payload) }.getOrNull() }

    /** 自动匹配第一步：按标签搜。前三个平台依次试，够分就停；全部请求都失败算网络问题。 */
    suspend fun matchByTags(row: LocalTrackEntity): LocalCandidate? {
        if (!LocalTags.hasUsableTags(row.title, row.artist, row.displayName)) return null
        val query = LocalTags.searchQuery(row.title, row.artist, row.displayName)
        val providers = searchProviders().take(3)
        if (providers.isEmpty()) return null
        var failures = 0
        var lastError: Throwable? = null
        for (providerId in providers) {
            val candidates = try {
                search(row, query, providerId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failures++
                lastError = error
                continue
            }
            val best = candidates.firstOrNull() ?: continue
            if (best.score >= LocalMatchRules.TAG_ACCEPT) return best.copy(method = LocalMatchMethod.TAG)
        }
        if (failures == providers.size) throw LocalMatchNetworkException("搜索失败", lastError)
        return null
    }

    /** 在一个平台里搜 [query]，按和这首本地歌的相似度从高到低排。分数只是参考，不过滤。 */
    suspend fun search(row: LocalTrackEntity, query: String, providerId: String): List<LocalCandidate> {
        val value = query.trim()
        if (value.isEmpty()) return emptyList()
        val cookie = settings().cookies[providerId].orEmpty()
        val results = withContext(Dispatchers.IO) { repository.searchSongs(providerId, cookie, value) }.take(20)
        val reference = reference(row, value)
        // 参考只剩搜索词时，逐个候选把它的歌手从搜索词里拆出来再比
        val fromQuery = reference.artist.isEmpty() && reference.name == value
        return results.map { song ->
            val against = if (fromQuery) {
                LocalTags.splitQuery(value, song.artist).let { (title, artist) -> reference.copy(name = title, artist = artist) }
            } else {
                reference
            }
            val score = TrackMatcher.score(against, LOCAL_SOURCE, song, providerId)
            LocalCandidate(song, providerId, if (score.rejected) score.total.coerceAtMost(0.5f) else score.total, LocalMatchMethod.MANUAL)
        }.sortedByDescending { it.score }
    }

    /**
     * 听歌识曲：从 [startMs] 取 3 秒。取样或算指纹失败（文件格式不支持、太短）返回空列表；
     * 识曲接口请求失败抛 [LocalMatchNetworkException]。
     */
    suspend fun listen(row: LocalTrackEntity, startMs: Long): List<LocalCandidate> {
        val netease = NativeProviders.get("netease") as? NetEaseProvider ?: return emptyList()
        val start = LocalMatchRules.sampleStartMs(row.durationSec * 1000L, startMs)
        val pcm = withContext(Dispatchers.Default) {
            runCatching { LocalAudioSampler.sample(context, Uri.parse(row.uri), start) }
                .recoverCatching { if (start > 0) LocalAudioSampler.sample(context, Uri.parse(row.uri), 0L) else throw it }
                .getOrNull()
        } ?: return emptyList()
        val fingerprint = fingerprintLock.withLock {
            try {
                fingerprinter.fingerprint(pcm)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()
        val songs = try {
            withContext(Dispatchers.IO) { netease.matchAudio(fingerprint, LocalMatchRules.SAMPLE_SECONDS) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            throw LocalMatchNetworkException("识曲请求失败", error)
        }
        val picked = LocalMatchRules.pickAudio(songs, row.durationSec)
        return songs.map { song ->
            val confidence = picked?.takeIf { it.first.key == song.key }?.second ?: LocalMatchRules.AUDIO_CONFIDENCE_LOOSE
            LocalCandidate(song, "netease", confidence, LocalMatchMethod.AUDIO)
        }.sortedByDescending { it.score }
    }

    /** 自动匹配第二步：识曲，挑时长对得上的那首。 */
    suspend fun matchByAudio(row: LocalTrackEntity, startMs: Long = LocalMatchRules.SAMPLE_START_MS): LocalCandidate? {
        val candidates = listen(row, startMs)
        val picked = LocalMatchRules.pickAudio(candidates.map { it.song }, row.durationSec) ?: return null
        return LocalCandidate(picked.first, "netease", picked.second, LocalMatchMethod.AUDIO)
    }
}
