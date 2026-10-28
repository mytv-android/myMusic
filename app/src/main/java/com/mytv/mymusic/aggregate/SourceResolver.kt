package com.mytv.mymusic.aggregate

import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.LinkedHashMap

class SourceResolver(
    private val repository: MusicRepository,
    private val cookies: () -> Map<String, String>,
    private val settings: () -> AggregationSettings,
    private val workDir: () -> File,
) {
    private data class CachedUrl(
        val url: String,
        val preview: Boolean,
        val at: Long,
        val ext: String = "",
        val bitrate: Int = 0,
    )

    private val urlCache = object : LinkedHashMap<String, CachedUrl>(32, 0.75f, true) {}
    private val negativeCache = object : LinkedHashMap<String, Long>(32, 0.75f, true) {}
    private val lock = Any()

    fun buildPolicy(track: CanonicalTrack, pinnedProvider: String? = null): SourcePolicy {
        val current = settings()
        val available = track.sources.map { it.providerId }.distinct()
        val configuredPinned = if (current.playbackMode == PlaybackMode.Pinned) current.pinnedProvider else null
        return SourcePolicy(
            mode = current.playbackMode,
            priority = current.priorityFor(available),
            pinnedProvider = pinnedProvider ?: configuredPinned,
            allowPreview = current.allowPreview,
        )
    }

    fun orderCandidates(
        track: CanonicalTrack,
        pinnedProvider: String?,
        excludeProviders: Set<String>,
        policy: SourcePolicy,
    ): List<TrackSource> {
        val cookieMap = cookies()
        val pinned = pinnedProvider ?: policy.pinnedProvider
        val sources = track.sources.filterNot { it.providerId in excludeProviders }
        val statuses = sources.associateWith { availabilityOf(it, cookieMap[it.providerId].orEmpty()) }
        val priority = if (policy.priority.isEmpty()) sources.map { it.providerId }.distinct() else policy.priority
        fun priorityIndex(source: TrackSource): Int {
            val index = priority.indexOf(source.providerId)
            return if (index < 0) Int.MAX_VALUE else index
        }

        val pinnedSources = sources.filter { source ->
            source.providerId == pinned && statuses[source] != Availability.Unavailable
        }
        val full = sources.filter { source ->
            val status = statuses.getValue(source)
            source.providerId != pinned &&
                status != Availability.Unavailable &&
                !(status == Availability.VipOnly && cookieMap[source.providerId].isNullOrBlank()) &&
                status != Availability.PreviewOnly
        }.sortedWith(
            if (policy.mode == PlaybackMode.Priority) {
                compareBy<TrackSource> { priorityIndex(it) }.thenByDescending { it.matchScore }
            } else {
                compareBy<TrackSource> { priorityIndex(it) }
                    .thenByDescending { it.song.bitrate }
                    .thenByDescending { it.matchScore }
            }
        )
        val preview = sources.filter { source ->
            source.providerId != pinned &&
                statuses.getValue(source) == Availability.PreviewOnly &&
                policy.allowPreview
        }.sortedWith(
            compareBy<TrackSource> { priorityIndex(it) }
                .thenByDescending { it.song.bitrate }
        )
        return (pinnedSources + full + preview).distinctBy { it.song.key }
    }

    suspend fun resolve(
        track: CanonicalTrack,
        pinnedProvider: String? = null,
        excludeProviders: Set<String> = emptySet(),
        policy: SourcePolicy = buildPolicy(track, pinnedProvider),
    ): Resolution {
        val ordered = orderCandidates(track, pinnedProvider, excludeProviders, policy)
        val attempts = mutableListOf<Attempt>()
        val cookieMap = cookies()
        val explicitProvider = pinnedProvider ?: policy.pinnedProvider

        for (source in ordered) {
            val cookie = cookieMap[source.providerId].orEmpty()
            val status = availabilityOf(source, cookie)
            if (status == Availability.Unavailable) {
                attempts += Attempt(source, "不可用")
                continue
            }
            if (status == Availability.VipOnly && cookie.isBlank()) {
                attempts += Attempt(source, "需要登录或会员")
                continue
            }
            val explicit = source.providerId == explicitProvider
            if ((status == Availability.PreviewOnly || source.isPreview) && !policy.allowPreview && !explicit) {
                attempts += Attempt(source, "仅提供试听")
                continue
            }

            val now = System.currentTimeMillis()
            val cached = synchronized(lock) { urlCache[source.song.key] }
            if (cached != null && now - cached.at < URL_TTL_MS) {
                val cachedSource = source.withQuality(cached.ext, cached.bitrate)
                return Resolution.Ready(cachedSource, cached.url, cached.preview, cached.at)
            }
            if (cached != null) synchronized(lock) { urlCache.remove(source.song.key) }

            val negativeAt = synchronized(lock) { negativeCache[source.song.key] }
            if (negativeAt != null && now - negativeAt < NEGATIVE_TTL_MS) {
                attempts += Attempt(source, "近期解析失败")
                continue
            }

            var failure: Throwable? = null
            val stream = runCatching {
                repository.playableStream(source.song, cookie, workDir())
            }.onFailure {
                if (it is CancellationException) throw it
                failure = it
            }.getOrNull()
            val url = stream?.url.orEmpty()
            if (url.isBlank()) {
                synchronized(lock) { negativeCache[source.song.key] = System.currentTimeMillis() }
                val reason = failure?.message
                    ?.takeIf { source.providerId == "soda" && it.contains("签名器") }
                    ?.substringBefore("\n")
                    ?: "没有返回播放地址"
                attempts += Attempt(source, reason)
                continue
            }
            val preview = source.isPreview || isPreviewSource(source.providerId, source.song)
            val resolvedSource = source.withQuality(stream?.ext.orEmpty(), stream?.bitrate ?: 0)
            synchronized(lock) {
                urlCache[source.song.key] = CachedUrl(
                    url = url,
                    preview = preview,
                    at = System.currentTimeMillis(),
                    ext = resolvedSource.song.ext,
                    bitrate = resolvedSource.song.bitrate,
                )
            }
            return Resolution.Ready(resolvedSource, url, preview)
        }

        val deepLink = track.sources.firstOrNull { it.song.link.isNotBlank() }?.song?.link.orEmpty()
        return Resolution.None(attempts, deepLink)
    }

    private fun TrackSource.withQuality(ext: String, bitrate: Int): TrackSource =
        copy(song = song.withQuality(ext, bitrate))

    private fun Song.withQuality(ext: String, bitrate: Int): Song {
        val resolvedExt = ext.ifBlank { this.ext }
        val resolvedBitrate = bitrate.takeIf { it > 0 } ?: this.bitrate
        return if (resolvedExt == this.ext && resolvedBitrate == this.bitrate) this
        else copy(ext = resolvedExt, bitrate = resolvedBitrate)
    }

    fun invalidate(source: TrackSource) {
        synchronized(lock) {
            urlCache.remove(source.song.key)
            negativeCache.remove(source.song.key)
        }
    }

    fun clearCache() {
        synchronized(lock) {
            urlCache.clear()
            negativeCache.clear()
        }
    }

    private companion object {
        const val URL_TTL_MS = 3 * 60_000L
        const val NEGATIVE_TTL_MS = 10 * 60_000L
    }
}
