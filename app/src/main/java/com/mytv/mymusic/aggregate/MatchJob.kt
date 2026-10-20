package com.mytv.mymusic.aggregate

import com.mytv.mymusic.data.AggregateDao
import com.mytv.mymusic.data.MatchCacheEntity
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MatchJob(
    private val repository: MusicRepository,
    private val cookies: () -> Map<String, String>,
    private val dao: AggregateDao,
    private val settings: () -> AggregationSettings,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun matchOne(source: Song, targetProvider: String): SongMatchResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = runCatching { dao.match(source.key, targetProvider) }.getOrNull()
        if (cached != null && now - cached.checkedAt < CACHE_TTL_MS) {
            if (cached.payload.isBlank()) {
                return@withContext SongMatchResult(
                    score = cached.score,
                    reason = "缓存：未找到同一版本",
                    fromCache = true,
                )
            }
            val song = runCatching { json.decodeFromString<Song>(cached.payload) }.getOrNull()
            if (song != null) {
                return@withContext SongMatchResult(
                    song = song,
                    score = cached.score,
                    method = MatchMethod.Meta,
                    reason = "使用匹配缓存",
                    fromCache = true,
                )
            }
        }

        val cookie = cookies()[targetProvider].orEmpty()
        val query = searchQuery(source)
        val candidates = try {
            repository.searchSongs(targetProvider, cookie, query).take(10)
        } catch (error: Exception) {
            return@withContext SongMatchResult(reason = "检索失败：${error.message ?: "网络错误"}")
        }
        var result = TrackMatcher.bestMatch(source, targetProvider, candidates, settings().threshold())
        if (result.song == null && candidates.isEmpty()) {
            val fallback = runCatching {
                repository.searchSongs(targetProvider, cookie, source.name).take(10)
            }.getOrDefault(emptyList())
            if (fallback.isNotEmpty()) {
                result = TrackMatcher.bestMatch(source, targetProvider, fallback, settings().threshold())
            }
        }

        val matchedSong = result.song
        runCatching {
            dao.saveMatch(
                MatchCacheEntity(
                    sourceKey = source.key,
                    targetProvider = targetProvider,
                    targetKey = matchedSong?.key.orEmpty(),
                    score = result.score,
                    payload = matchedSong?.let { json.encodeToString(it) }.orEmpty(),
                    checkedAt = System.currentTimeMillis(),
                )
            )
        }
        result
    }

    private fun searchQuery(song: Song): String {
        val artist = TrackNormalizer.splitArtists(song.artist).firstOrNull().orEmpty()
        return listOf(song.name.trim(), artist).filter { it.isNotBlank() }.joinToString(" ")
    }

    private companion object {
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
