package com.mytv.mymusic.aggregate

import com.mytv.mymusic.data.Song
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MatchScore(
    val total: Float,
    val title: Float,
    val artist: Float,
    val duration: Float,
    val album: Float,
    val method: MatchMethod = MatchMethod.Meta,
    val reason: String = "",
    val rejected: Boolean = false,
)

object TrackMatcher {
    private const val REVIEW_THRESHOLD = 0.72f

    fun score(source: Song, candidate: Song): MatchScore =
        score(source, source.source.ifBlank { "source" }, candidate, candidate.source.ifBlank { "candidate" })

    fun score(source: Song, sourceProvider: String, candidate: Song, candidateProvider: String): MatchScore =
        scoreNormalized(
            source,
            TrackNormalizer.normalize(source, sourceProvider),
            candidate,
            TrackNormalizer.normalize(candidate, candidateProvider),
        )

    fun bestMatch(source: Song, targetProvider: String, candidates: List<Song>, threshold: Float): SongMatchResult {
        var best: SongMatchResult? = null
        for (candidate in candidates.distinctBy { it.key }) {
            val result = score(source, source.source.ifBlank { "source" }, candidate, targetProvider)
            if (result.rejected) continue
            if (best == null || result.total > best.score) {
                best = SongMatchResult(candidate, result.total, result.method, result.reason)
            }
        }
        val found = best
        return if (found != null && found.score >= threshold) {
            found
        } else {
            SongMatchResult(
                score = found?.score ?: 0f,
                reason = found?.let { "最高匹配分 ${formatScore(it.score)}，低于阈值" } ?: "没有候选结果",
            )
        }
    }

    fun group(
        results: Map<String, List<Song>>,
        providerNames: Map<String, String>,
        cookies: Map<String, String>,
        threshold: Float,
        priority: List<String>,
        splitKeys: Set<String> = emptySet(),
        mergeTargets: Map<String, String> = emptyMap(),
    ): List<CanonicalTrack> {
        val providerOrder = priority.filter { it in results } + results.keys.filter { it !in priority }
        val groups = mutableListOf<Group>()
        val seen = mutableSetOf<String>()
        for (providerId in providerOrder) {
            val songs = results[providerId].orEmpty()
            songs.forEachIndexed { index, song ->
                if (!seen.add(song.key)) return@forEachIndexed
                val normalized = TrackNormalizer.normalize(song, providerId)
                val source = sourceOf(providerId, providerNames, cookies, song, index + 1, 1f, MatchMethod.Native)
                val candidates = groups.filter { sameVersion(it.version, normalized.version) }
                var bestGroup: Group? = null
                var bestScore: MatchScore? = null
                for (group in candidates) {
                    val candidateScore = scoreNormalized(song, normalized, group.representativeSong, group.normalized)
                    if (bestScore == null || candidateScore.total > bestScore.total) {
                        bestScore = candidateScore
                        bestGroup = group
                    }
                }
                val blocked = bestGroup != null && "${song.key}|${canonicalId(bestGroup.normalized)}" in splitKeys
                when {
                    bestGroup != null && bestScore != null && bestScore.total >= threshold && !blocked -> {
                        val existing = bestGroup.sources.firstOrNull { it.providerId == providerId }
                        if (existing == null) {
                            bestGroup.sources += source
                        } else if (source.song.bitrate > existing.song.bitrate || source.song.size > existing.song.size) {
                            bestGroup.sources.remove(existing)
                            bestGroup.sources += source
                        }
                        bestGroup.bestScore = max(bestGroup.bestScore, bestScore.total)
                        bestGroup.maybeImproveRepresentative(song, normalized, source)
                    }
                    bestGroup != null && bestScore != null && bestScore.total >= REVIEW_THRESHOLD && !blocked -> {
                        if (bestGroup.alternatives.none { it.song.key == source.song.key }) {
                            bestGroup.alternatives += source
                            bestGroup.bestScore = max(bestGroup.bestScore, bestScore.total)
                        }
                    }
                    else -> {
                        groups += Group(normalized, source)
                    }
                }
            }
        }
        if (mergeTargets.isNotEmpty()) {
            val snapshot = groups.toList()
            for ((sourceKey, targetSourceKey) in mergeTargets) {
                val sourceGroup = snapshot.firstOrNull { group -> group.sources.any { it.song.key == sourceKey } } ?: continue
                val targetGroup = snapshot.firstOrNull { group -> group.sources.any { it.song.key == targetSourceKey } } ?: continue
                if (sourceGroup === targetGroup) continue
                val source = sourceGroup.sources.firstOrNull { it.song.key == sourceKey } ?: continue
                if (targetGroup.sources.any { it.song.key == source.song.key }) continue
                if (targetGroup.sources.any { it.providerId == source.providerId }) {
                    sourceGroup.sources.removeAll { it.song.key == sourceKey }
                    if (sourceGroup.sources.isEmpty()) groups.remove(sourceGroup)
                    continue
                }
                sourceGroup.sources.removeAll { it.song.key == sourceKey }
                targetGroup.sources += source
                targetGroup.bestScore = max(targetGroup.bestScore, source.matchScore)
                if (sourceGroup.sources.isEmpty()) groups.remove(sourceGroup)
            }
        }
        return groups.map { freeze(it, providerNames, cookies, priority) }
    }

    fun rank(
        groups: List<CanonicalTrack>,
        providerCount: Int,
        priority: List<String>,
        preferredProvider: String,
        cookies: Map<String, String>,
    ): List<CanonicalTrack> {
        if (groups.isEmpty()) return groups
        val rrf = groups.map { group ->
            group.sources.sumOf { source -> 1.0 / (60.0 + (source.originalRank.takeIf { it > 0 } ?: 50)) }
        }
        val maxRrf = rrf.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        return groups.mapIndexed { index, group ->
            val coverage = (group.sources.map { it.providerId }.distinct().size.toDouble() / providerCount.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            val statuses = group.sources.map { availabilityOf(it, cookies[it.providerId].orEmpty()) }
            val preferredPlayable = group.sources
                .filter { it.providerId == preferredProvider }
                .any { availabilityOf(it, cookies[it.providerId].orEmpty()) != Availability.Unavailable }
            val playability = when {
                Availability.Playable in statuses -> 1.0
                preferredPlayable -> 0.8
                Availability.Unknown in statuses -> 0.65
                Availability.VipOnly in statuses -> 0.35
                Availability.PreviewOnly in statuses -> 0.25
                else -> 0.0
            }
            val metadata = listOf(group.cover.isNotBlank(), group.album.isNotBlank(), group.durationSec > 0).count { it } / 3.0
            val score = 0.60 * (rrf[index] / maxRrf) + 0.20 * coverage + 0.15 * playability + 0.05 * metadata
            Ranked(group, score)
        }.sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.group.title }).map { it.group }
    }

    fun singleSource(
        song: Song,
        providerName: String,
        providerId: String = song.source.ifBlank { "unknown" },
        cookie: String = "",
    ): CanonicalTrack {
        val normalized = TrackNormalizer.normalize(song, providerId)
        val source = sourceOf(providerId, mapOf(providerId to providerName), mapOf(providerId to cookie), song, 1, 1f, MatchMethod.Native)
        return CanonicalTrack(
            id = canonicalId(normalized),
            title = song.name.ifBlank { normalized.displayTitle },
            artists = TrackNormalizer.splitArtists(song.artist).ifEmpty { normalized.artists },
            album = song.album,
            durationSec = song.duration.coerceAtLeast(0),
            version = normalized.version,
            cover = song.cover,
            sources = listOf(source),
            alternatives = emptyList(),
            confidence = 1f,
        )
    }

    fun withSource(track: CanonicalTrack, source: TrackSource): CanonicalTrack {
        val sources = (track.sources.filterNot { it.providerId == source.providerId } + source)
            .distinctBy { it.song.key }
        val alternatives = track.alternatives.filterNot { it.song.key == source.song.key }
        return track.copy(
            sources = sources,
            alternatives = alternatives,
            confidence = max(track.confidence, source.matchScore),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun sourceOf(
        providerId: String,
        providerNames: Map<String, String>,
        cookies: Map<String, String>,
        song: Song,
        rank: Int,
        matchScore: Float,
        method: MatchMethod,
    ): TrackSource {
        val preview = isPreviewSource(providerId, song)
        val source = TrackSource(
            providerId = providerId,
            providerName = providerNames[providerId] ?: providerId,
            song = song,
            matchScore = matchScore,
            matchMethod = method,
            availability = Availability.Unknown,
            isPreview = preview,
            originalRank = rank,
            checkedAt = System.currentTimeMillis(),
        )
        return source.copy(availability = availabilityOf(source, cookies[providerId].orEmpty()))
    }

    private fun freeze(
        group: Group,
        providerNames: Map<String, String>,
        cookies: Map<String, String>,
        priority: List<String>,
    ): CanonicalTrack {
        val sources = group.sources.distinctBy { it.song.key }
            .sortedBy { priority.indexOf(it.providerId).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        val best = sources.maxByOrNull { metadataScore(it, priority) } ?: sources.first()
        val durationSource = sources.firstOrNull {
            TrackNormalizer.normalize(it.song, it.providerId).durationReliable
        } ?: best
        val cover = sources.firstOrNull { it.song.cover.isNotBlank() }?.song?.cover.orEmpty()
        val version = TrackNormalizer.normalize(best.song, best.providerId).version
        val alternatives = group.alternatives
            .filterNot { alternative -> sources.any { it.song.key == alternative.song.key } }
            .distinctBy { it.song.key }
        return CanonicalTrack(
            id = canonicalId(group.normalized),
            title = best.song.name.ifBlank { group.normalized.displayTitle },
            artists = TrackNormalizer.splitArtists(best.song.artist).ifEmpty { group.normalized.artists },
            album = best.song.album,
            durationSec = durationSource.song.duration.coerceAtLeast(0),
            version = version,
            cover = cover,
            sources = sources,
            alternatives = alternatives,
            confidence = group.bestScore,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun metadataScore(source: TrackSource, priority: List<String>): Float {
        val normalized = TrackNormalizer.normalize(source.song, source.providerId)
        var score = 0f
        if (source.song.cover.isNotBlank()) score += 2f
        if (source.song.album.isNotBlank()) score += 1f
        if (normalized.durationReliable) score += 1f
        val index = priority.indexOf(source.providerId)
        if (index >= 0) score += (10 - index.coerceAtMost(10)) * 0.1f
        if (source.isPreview) score -= 1f
        return score
    }

    private fun scoreNormalized(
        source: Song,
        a: NormalizedTrack,
        candidate: Song,
        b: NormalizedTrack,
    ): MatchScore {
        val isrcA = isrc(source)
        val isrcB = isrc(candidate)
        if (isrcA != null && isrcB != null && isrcA == isrcB) {
            return MatchScore(1f, 1f, 1f, 1f, 1f, MatchMethod.Isrc, "ISRC 一致")
        }

        val title = similarity(a.titleKey, b.titleKey)
        val artist = artistSimilarity(a, b)
        val bothDurationsReliable = a.durationReliable && b.durationReliable
        val durationDiff = abs(a.durationSec - b.durationSec)
        val duration = when {
            !bothDurationsReliable -> 0.7f
            durationDiff <= 2 -> 1f
            else -> (1f - min(1f, durationDiff / 15f)).coerceAtLeast(0f)
        }
        val album = if (a.albumKey.isBlank() || b.albumKey.isBlank()) 0.5f else similarity(a.albumKey, b.albumKey)
        var total = title * 0.40f + artist * 0.30f + duration * 0.20f + album * 0.10f
        var reason = ""

        if (!sameVersion(a.version, b.version)) {
            total = min(total, 0.55f)
            reason = "版本不同"
        }
        if (title < 0.65f) {
            return MatchScore(0f, title, artist, duration, album, reason = "标题差异过大", rejected = true)
        }
        if (bothDurationsReliable && durationDiff > 10) {
            total = min(total, 0.60f)
            reason = "时长差异过大"
        }
        if (artist < 0.45f && !(title >= 0.98f && (!bothDurationsReliable || durationDiff <= 2))) {
            return MatchScore(total, title, artist, duration, album, reason = "歌手不匹配", rejected = true)
        }
        return MatchScore(total, title, artist, duration, album, reason = reason)
    }

    private fun artistSimilarity(a: NormalizedTrack, b: NormalizedTrack): Float {
        if (a.artistKeys.isEmpty() || b.artistKeys.isEmpty()) return 0.5f
        val unknown = setOf("未知歌手", "unknown", "群星", "variousartists")
        if (a.artistKeys.all { it in unknown } || b.artistKeys.all { it in unknown }) return 0.5f
        val setA = a.artistKeys.toSet()
        val setB = b.artistKeys.toSet()
        val intersection = setA.intersect(setB).size.toFloat()
        val union = setA.union(setB).size.toFloat()
        val jaccard = if (union == 0f) 0.5f else intersection / union
        val primary = if (a.primaryArtistKey.isNotBlank() && a.primaryArtistKey == b.primaryArtistKey) {
            1f
        } else {
            similarity(a.primaryArtistKey, b.primaryArtistKey)
        }
        val full = similarity(a.rawArtistKey, b.rawArtistKey)
        return maxOf(jaccard, primary, full).coerceIn(0f, 1f)
    }

    private fun sameVersion(a: VersionKind, b: VersionKind): Boolean =
        a == b || a == VersionKind.Unknown || b == VersionKind.Unknown

    private fun similarity(a: String, b: String): Float {
        if (a == b) return if (a.isEmpty()) 0f else 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val distance = damerau(a, b)
        val ratio = 1f - distance.toFloat() / max(a.length, b.length)
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        val containment = if (shorter.length >= 2 && longer.contains(shorter)) {
            0.85f + 0.15f * shorter.length.toFloat() / longer.length
        } else {
            0f
        }
        return max(ratio, containment).coerceIn(0f, 1f)
    }

    private fun damerau(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    dp[i][j] = minOf(dp[i][j], dp[i - 2][j - 2] + 1)
                }
            }
        }
        return dp[a.length][b.length]
    }

    private fun isrc(song: Song): String? = listOf("isrc", "ISRC", "isrc_code", "isrcCode")
        .firstNotNullOfOrNull { key -> song.extra[key]?.trim()?.takeIf { it.isNotBlank() } }
        ?.uppercase(Locale.ROOT)

    private fun canonicalId(normalized: NormalizedTrack): String {
        val bucket = if (normalized.durationReliable) (normalized.durationSec + 2) / 5 * 5 else -1
        val raw = listOf(
            normalized.titleKey,
            normalized.artistKeys.sorted().joinToString("|"),
            normalized.version.name,
            bucket.toString(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return "ct_" + digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun formatScore(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

    private class Group(
        var normalized: NormalizedTrack,
        var representativeSong: Song,
        var representativeProvider: String,
        var version: VersionKind,
        val sources: MutableList<TrackSource> = mutableListOf(),
        val alternatives: MutableList<TrackSource> = mutableListOf(),
        var bestScore: Float = 1f,
    ) {
        constructor(normalized: NormalizedTrack, first: TrackSource) : this(
            normalized = normalized,
            representativeSong = first.song,
            representativeProvider = first.providerId,
            version = normalized.version,
            sources = mutableListOf(first),
            bestScore = 1f,
        )

        fun maybeImproveRepresentative(song: Song, candidate: NormalizedTrack, source: TrackSource) {
            val currentPreview = sources.any { it.song.key == representativeSong.key && it.isPreview }
            if ((!normalized.durationReliable || currentPreview) && candidate.durationReliable && !source.isPreview) {
                normalized = candidate
                representativeSong = song
                representativeProvider = source.providerId
                version = candidate.version
            }
        }
    }

    private data class Ranked(val group: CanonicalTrack, val score: Double)
}
