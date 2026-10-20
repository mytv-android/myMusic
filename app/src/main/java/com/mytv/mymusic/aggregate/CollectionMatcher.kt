package com.mytv.mymusic.aggregate

import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.SearchKind
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CollectionSource(
    val providerId: String,
    val providerName: String,
    val playlist: Playlist,
    val score: Float,
)

data class CanonicalCollection(
    val id: String,
    val name: String,
    val creator: String,
    val cover: String,
    val trackCount: Int,
    val kind: SearchKind,
    val sources: List<CollectionSource>,
    val confidence: Float,
    val sameNameOnly: Boolean = false,
)

object CollectionMatcher {
    fun group(
        results: Map<String, List<Playlist>>,
        providerNames: Map<String, String>,
        kind: SearchKind,
        priority: List<String>,
    ): List<CanonicalCollection> {
        val order = priority.filter { it in results } + results.keys.filter { it !in priority }
        val groups = mutableListOf<Group>()
        for (providerId in order) {
            results[providerId].orEmpty().forEach { playlist ->
                if (playlist.name.isBlank()) return@forEach
                val normalized = normalize(playlist)
                var bestGroup: Group? = null
                var bestScore = 0f
                for (group in groups) {
                    val score = score(normalized, group.normalized, kind)
                    if (score > bestScore) {
                        bestScore = score
                        bestGroup = group
                    }
                }
                val threshold = if (kind == SearchKind.Album) 0.88f else 0.98f
                if (bestGroup != null && bestScore >= threshold) {
                    bestGroup.sources += CollectionSource(providerId, providerNames[providerId] ?: providerId, playlist, bestScore)
                    bestGroup.confidence = max(bestGroup.confidence, bestScore)
                } else {
                    groups += Group(
                        normalized = normalized,
                        sources = mutableListOf(CollectionSource(providerId, providerNames[providerId] ?: providerId, playlist, 1f)),
                    )
                }
            }
        }
        return groups.map { freeze(it, kind, priority) }.sortedWith(
            compareByDescending<CanonicalCollection> { it.sources.size }
                .thenByDescending { it.confidence }
                .thenBy { it.name }
        )
    }

    private fun normalize(playlist: Playlist): NormalizedCollection = NormalizedCollection(
        nameKey = TrackNormalizer.keyOf(playlist.name),
        creatorKey = TrackNormalizer.keyOf(playlist.creator),
        trackCount = playlist.trackCount,
    )

    private fun score(a: NormalizedCollection, b: NormalizedCollection, kind: SearchKind): Float {
        if (a.nameKey.isBlank() || b.nameKey.isBlank()) return 0f
        if (kind == SearchKind.Playlist) {
            return if (a.nameKey == b.nameKey) 1f else 0f
        }
        val name = similarity(a.nameKey, b.nameKey)
        val creator = similarity(a.creatorKey, b.creatorKey)
        val countDiff = abs(a.trackCount - b.trackCount)
        val count = if (a.trackCount <= 0 || b.trackCount <= 0) 0.5f else (1f - min(1f, countDiff / 10f)).coerceAtLeast(0f)
        if (name < 0.70f || creator < 0.35f) return 0f
        var total = name * 0.55f + creator * 0.30f + count * 0.15f
        if (countDiff > 5) total = min(total, 0.65f)
        return total.coerceIn(0f, 1f)
    }

    private fun freeze(group: Group, kind: SearchKind, priority: List<String>): CanonicalCollection {
        val sources = group.sources
            .distinctBy { "${it.providerId}:${it.playlist.id}" }
            .sortedBy { priority.indexOf(it.providerId).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        val best = sources.maxByOrNull { source ->
            var score = 0f
            if (source.playlist.cover.isNotBlank()) score += 2f
            if (source.playlist.trackCount > 0) score += 1f
            val index = priority.indexOf(source.providerId)
            if (index >= 0) score += (10 - index.coerceAtMost(10)) * 0.1f
            score
        } ?: sources.first()
        val identity = buildString {
            append(kind.name).append('|').append(group.normalized.nameKey)
            if (kind == SearchKind.Album) append('|').append(group.normalized.creatorKey)
        }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        return CanonicalCollection(
            id = "col_$digest",
            name = best.playlist.name,
            creator = best.playlist.creator,
            cover = best.playlist.cover,
            trackCount = best.playlist.trackCount,
            kind = kind,
            sources = sources,
            confidence = group.confidence,
            sameNameOnly = kind == SearchKind.Playlist && sources.size > 1,
        )
    }

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isBlank() || b.isBlank()) return 0f
        val distance = levenshtein(a, b)
        return (1f - distance.toFloat() / max(a.length, b.length)).coerceIn(0f, 1f)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    private data class NormalizedCollection(
        val nameKey: String,
        val creatorKey: String,
        val trackCount: Int,
    )

    private class Group(
        val normalized: NormalizedCollection,
        val sources: MutableList<CollectionSource>,
        var confidence: Float = 1f,
    )
}
