package com.mytv.mymusic.aggregate

import com.mytv.mymusic.data.Song
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class VersionKind { Studio, Live, Remix, Instrumental, Acoustic, Cover, Demo, Unknown }

@Serializable
enum class Availability { Playable, VipOnly, PreviewOnly, Unavailable, Unknown }

@Serializable
enum class MatchMethod { Isrc, Meta, Manual, Native }

@Serializable
enum class PlaybackMode { Auto, Priority, Pinned }

@Serializable
enum class MatchStrictness { Strict, Balanced }

enum class SearchScope { Aggregated, SingleProvider }

enum class SearchStatus { Idle, Loading, Partial, Complete, Empty, Failed }

enum class ProviderStatus { Loading, Success, Timeout, Error, Unsupported, Skipped }

data class CanonicalTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationSec: Int,
    val version: VersionKind,
    val cover: String,
    val sources: List<TrackSource>,
    val alternatives: List<TrackSource> = emptyList(),
    val confidence: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val artistLabel: String get() = artists.filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "未知歌手" }
    val subtitle: String get() = listOf(artistLabel, album).filter { it.isNotBlank() }.joinToString(" · ")
    val durationLabel: String get() = if (durationSec <= 0) "" else String.format(Locale.ROOT, "%d:%02d", durationSec / 60, durationSec % 60)
    val primarySource: TrackSource? get() = sources.firstOrNull()
}

data class TrackSource(
    val providerId: String,
    val providerName: String,
    val song: Song,
    val matchScore: Float = 1f,
    val matchMethod: MatchMethod = MatchMethod.Meta,
    val availability: Availability = Availability.Unknown,
    val isPreview: Boolean = false,
    val originalRank: Int = 0,
    val checkedAt: Long = 0L,
) {
    val qualityLabel: String get() = listOfNotNull(
        song.bitrate.takeIf { it > 0 }?.let { "$it kbps" },
        song.ext.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
    ).joinToString(" · ").ifBlank { "音质未知" }
}

data class SourcePolicy(
    val mode: PlaybackMode = PlaybackMode.Auto,
    val priority: List<String> = emptyList(),
    val pinnedProvider: String? = null,
    val allowPreview: Boolean = false,
)

data class Attempt(val source: TrackSource, val reason: String)

sealed interface Resolution {
    data class Ready(
        val source: TrackSource,
        val url: String,
        val isPreview: Boolean,
        val resolvedAt: Long = System.currentTimeMillis(),
    ) : Resolution

    data class None(
        val attempts: List<Attempt>,
        val deepLink: String = "",
    ) : Resolution
}

data class ProviderSearchState(
    val providerId: String,
    val providerName: String,
    val status: ProviderStatus,
    val count: Int = 0,
    val elapsedMs: Long = 0,
    val error: String = "",
)

sealed interface SearchUpdate {
    data class ProviderArrived(
        val providerId: String,
        val providerName: String,
        val songs: List<Song>,
        val elapsedMs: Long,
        val error: String? = null,
        val timedOut: Boolean = false,
        val page: Int = 1,
    ) : SearchUpdate

    data class Groups(val groups: List<CanonicalTrack>) : SearchUpdate

    data class FromCache(
        val groups: List<CanonicalTrack>,
        val states: Map<String, ProviderSearchState>,
        val songs: Map<String, List<Song>> = emptyMap(),
        val hasMore: Boolean = false,
    ) : SearchUpdate

    data class Completed(
        val groups: List<CanonicalTrack>,
        val fromCache: Boolean = false,
        val hasMore: Boolean = false,
    ) : SearchUpdate
}

data class SongMatchResult(
    val song: Song? = null,
    val score: Float = 0f,
    val method: MatchMethod = MatchMethod.Meta,
    val reason: String = "",
    val fromCache: Boolean = false,
) {
    val matched: Boolean get() = song != null
}

data class MatchProgress(
    val targetProvider: String,
    val total: Int,
    val completed: Int,
    val matched: Int,
    val currentSong: Song? = null,
    val currentResult: SongMatchResult? = null,
    val done: Boolean = false,
    val error: String = "",
)

@Serializable
data class AggregationSettings(
    val enabledProviders: List<String> = listOf("netease", "qq", "kugou", "kuwo", "migu"),
    val playbackMode: PlaybackMode = PlaybackMode.Auto,
    val providerPriority: List<String> = listOf("netease", "qq", "kugou", "kuwo", "migu", "apple"),
    val pinnedProvider: String? = null,
    val allowPreview: Boolean = false,
    val matchStrictness: MatchStrictness = MatchStrictness.Strict,
    val showUnavailable: Boolean = true,
) {
    fun threshold(): Float = if (matchStrictness == MatchStrictness.Strict) 0.92f else 0.88f

    fun enabledOrDefault(available: List<String>): List<String> {
        val filtered = enabledProviders.filter { it in available }
        return if (filtered.isNotEmpty()) filtered else available.take(5)
    }

    fun priorityFor(available: List<String>): List<String> {
        val ordered = providerPriority.filter { it in available }.distinct()
        return ordered + available.filter { it !in ordered }
    }
}
