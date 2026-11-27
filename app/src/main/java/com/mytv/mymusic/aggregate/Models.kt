package com.mytv.mymusic.aggregate

import com.mytv.mymusic.data.Song
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class VersionKind { Studio, Live, Remix, Instrumental, Acoustic, Cover, Demo, Alternate, Unknown }

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
    /** 组合源的解析缓存键；原生源留空，继续按歌曲 key 缓存。 */
    val resolutionKey: String = "",
) {
    val qualityLabel: String get() = listOfNotNull(
        song.bitrate.takeIf { it > 0 }?.let { "$it kbps" },
        song.ext.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
    ).joinToString(" · ").ifBlank { "音质未知" }
    val actualProviderName: String get() = song.extra["lx_source"]?.takeIf { it.isNotBlank() }
        ?.let { "$providerName · $it" } ?: providerName
}

data class SourcePolicy(
    val mode: PlaybackMode = PlaybackMode.Auto,
    val priority: List<String> = emptyList(),
    val pinnedProvider: String? = null,
    val allowPreview: Boolean = false,
    /** 自动播放的候选白名单；null 表示不限制，兼容手写策略与现有单测。 */
    val allowedProviders: Set<String>? = null,
    /**
     * 固定音源是否独占：只在 [pinnedProvider] 取流，失败就停，不再回退别的平台。
     * 回退到没有播放权限的平台往往只能播试听，播一秒又断，比直接报错更难理解。
     */
    val pinnedOnly: Boolean = false,
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

@Serializable
data class AggregationSettings(
    val enabledProviders: List<String> = listOf("netease", "qq", "kugou", "kuwo", "migu", "lx"),
    val playbackMode: PlaybackMode = PlaybackMode.Auto,
    val providerPriority: List<String> = listOf("netease", "qq", "kugou", "kuwo", "migu", "lx", "apple"),
    val pinnedProvider: String? = null,
    /**
     * 「固定音源」是否独占：只在 [pinnedProvider] 取流，失败就停，不再回退别的平台。
     * 默认关，保持原有「先固定、再回退」的行为。
     */
    val pinnedOnly: Boolean = false,
    val allowPreview: Boolean = false,
    val matchStrictness: MatchStrictness = MatchStrictness.Strict,
    val showUnavailable: Boolean = true,
    /**
     * 用户自己把洛雪关掉了。导入脚本时自动启用要绕开这种情况；
     * 不能拿 [providerPriority] 里有没有 lx 判断：它是默认顺序表，老配置本来就带着 lx。
     */
    val lxDismissed: Boolean = false,
) {
    fun threshold(): Float = if (matchStrictness == MatchStrictness.Strict) 0.92f else 0.88f

    fun enabledOrDefault(available: List<String>): List<String> =
        enabledProviders.filter { it in available }

    /**
     * 平台顺序。用户在设置里排的 [enabledProviders] 就是顺序本身（启用的在前、未启用的垫底），
     * [providerPriority] 只给没在启用列表里出现过的 id（换源时碰到的冷门平台）兜底。
     */
    fun priorityFor(available: List<String>): List<String> {
        val ordered = (enabledProviders + providerPriority).distinct().filter { it in available }
        return ordered + available.filter { it !in ordered }
    }

    /**
     * 自动播放的候选顺序：pinned（用户显式固定）在最前，来源平台已启用时紧随其后，
     * 其余按 [enabledProviders] 的启用顺序。未启用的平台不会出现在结果里——
     * 这里不能再拿 [providerPriority] 或 available 的剩余 id 兜底，否则关掉的来源平台又会被排回来。
     */
    fun playbackOrder(available: List<String>, origin: String?, pinned: String?): List<String> {
        val enabled = enabledProviders.distinct().filter { it in available }
        return buildList {
            pinned?.takeIf { it.isNotBlank() }?.let { add(it) }
            origin?.takeIf { it in enabled }?.let { add(it) }
            addAll(enabled)
        }.distinct()
    }
}
