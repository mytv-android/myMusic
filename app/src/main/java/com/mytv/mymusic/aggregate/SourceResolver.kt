package com.mytv.mymusic.aggregate

import com.mytv.mymusic.AppLog
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.lx.LxPlatform
import com.mytv.mymusic.data.native.AudioQuality
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.LinkedHashMap

class SourceResolver(
    private val repository: MusicRepository,
    private val cookies: () -> Map<String, String>,
    private val settings: () -> AggregationSettings,
    private val workDir: () -> File,
    /** 播放音质偏好；换源解析和播放取流都要按它取，选了无损就不能偷偷给 128k。 */
    private val quality: () -> AudioQuality = { AudioQuality.Auto },
) {
    private data class CachedUrl(
        val url: String,
        val preview: Boolean,
        val at: Long,
        val ext: String = "",
        val bitrate: Int = 0,
        val extra: Map<String, String> = emptyMap(),
        /** 取这条地址时的音质偏好；偏好一变整条缓存作废。 */
        val quality: String = AudioQuality.Auto.id,
        val generation: Long = 0,
        /** 洛雪实际命中的歌曲可能与列表展示用的锚点歌曲不同。 */
        val song: Song? = null,
    )

    private data class CachedFailure(val at: Long, val generation: Long)

    private val urlCache = object : LinkedHashMap<String, CachedUrl>(32, 0.75f, true) {}
    private val negativeCache = object : LinkedHashMap<String, CachedFailure>(32, 0.75f, true) {}
    private val lock = Any()

    fun buildPolicy(track: CanonicalTrack, pinnedProvider: String? = null): SourcePolicy {
        val current = settings()
        val cookieMap = cookies()
        val available = buildList {
            track.sources.mapTo(this) { it.providerId }
            if (lxCandidates(track, cookieMap).isNotEmpty()) add(LxPlatform.PROVIDER_ID)
        }.distinct()
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
        val nativeSources = track.sources.filterNot { it.providerId in excludeProviders }
        val sources = nativeSources + listOfNotNull(unifiedLxSource(track, excludeProviders, cookieMap))
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
            val custom = source.providerId == LxPlatform.PROVIDER_ID
            source.providerId != pinned &&
                status != Availability.Unavailable &&
                !(status == Availability.VipOnly && cookieMap[source.providerId].isNullOrBlank() && !custom) &&
                (status != Availability.PreviewOnly || custom)
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
                source.providerId != LxPlatform.PROVIDER_ID &&
                policy.allowPreview
        }.sortedWith(
            compareBy<TrackSource> { priorityIndex(it) }
                .thenByDescending { it.song.bitrate }
        )
        return (pinnedSources + full + preview).distinctBy { it.resolutionKey.ifBlank { it.song.key } }
    }

    /** 一个洛雪候选代表整个脚本集合；锚点只用于列表展示，实际歌曲由脚本解析结果决定。 */
    private fun unifiedLxSource(
        track: CanonicalTrack,
        excludeProviders: Set<String>,
        cookieMap: Map<String, String>,
    ): TrackSource? {
        if (LxPlatform.PROVIDER_ID in excludeProviders) return null
        val anchor = lxCandidates(track, cookieMap).firstOrNull() ?: return null
        return anchor.copy(
            providerId = LxPlatform.PROVIDER_ID,
            providerName = LxPlatform.PROVIDER_NAME,
            availability = Availability.Playable,
            isPreview = false,
            resolutionKey = "${LxPlatform.PROVIDER_ID}:${track.id}",
        )
    }

    private fun lxCandidates(track: CanonicalTrack, cookieMap: Map<String, String>): List<TrackSource> =
        track.sources.asSequence()
            .filter { repository.supportsCustomSource(it.providerId) }
            .filter { availabilityOf(it, cookieMap[it.providerId].orEmpty()) != Availability.Unavailable }
            .sortedWith(compareByDescending<TrackSource> { it.matchScore }.thenByDescending { it.song.bitrate })
            .distinctBy { it.song.key }
            .toList()

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
        val customGeneration = repository.customSourceGeneration()
        val customSongs by lazy { lxCandidates(track, cookieMap).map { it.song } }

        for (source in ordered) {
            val cookie = cookieMap[source.providerId].orEmpty()
            val status = availabilityOf(source, cookie)
            val custom = source.providerId == LxPlatform.PROVIDER_ID
            if (status == Availability.Unavailable) {
                attempts += Attempt(source, "不可用")
                continue
            }
            if (status == Availability.VipOnly && cookie.isBlank() && !custom) {
                attempts += Attempt(source, "需要登录或会员")
                continue
            }
            val explicit = source.providerId == explicitProvider
            if ((status == Availability.PreviewOnly || source.isPreview) && !policy.allowPreview && !explicit && !custom) {
                attempts += Attempt(source, "仅提供试听")
                continue
            }

            val now = System.currentTimeMillis()
            val cacheKey = source.resolutionKey.ifBlank { source.song.key }
            val cached = synchronized(lock) { urlCache[cacheKey] }
            // 换过音质偏好就不能复用旧地址：用户选了无损，缓存里那条 128k 必须作废
            val qualityId = quality().id
            if (cached != null && cached.quality == qualityId && cached.generation == customGeneration && now - cached.at < URL_TTL_MS) {
                // 汽水这类取流后才知道是试听的源，缓存里带着试听标记：同样受「允许试听」约束
                if (cached.preview && !policy.allowPreview && !explicit) {
                    attempts += Attempt(source, "仅提供试听")
                    continue
                }
                val cachedSong = if (source.providerId == LxPlatform.PROVIDER_ID) cached.song ?: source.song else source.song
                val cachedSource = source.copy(song = cachedSong).withQuality(cached.ext, cached.bitrate, cached.extra)
                return Resolution.Ready(cachedSource, cached.url, cached.preview, cached.at)
            }
            if (cached != null) synchronized(lock) { urlCache.remove(cacheKey) }

            val cachedFailure = synchronized(lock) { negativeCache[cacheKey] }
            if (cachedFailure != null && cachedFailure.generation == customGeneration && now - cachedFailure.at < NEGATIVE_TTL_MS) {
                attempts += Attempt(source, "近期解析失败")
                continue
            }

            var failure: Throwable? = null
            var resolvedSong = source.song
            var stream = runCatching {
                if (source.providerId == LxPlatform.PROVIDER_ID) {
                    val result = repository.playableLxStream(customSongs, quality())
                    resolvedSong = result?.song ?: source.song
                    result?.stream
                } else {
                    repository.playableStream(source.song, cookie, workDir(), quality())
                }
            }.onFailure {
                if (it is CancellationException) throw it
                failure = it
            }.getOrNull()
            if (stream?.url.isNullOrBlank() && quality() != AudioQuality.Auto) {
                // 用户锁了音质而这个平台给不了（没上架无损、账号没会员）：退回自动档再取一次，
                // 宁可降档也要让这首歌响，不能因为一个音质偏好就整首播不出来。
                stream = runCatching {
                    if (source.providerId == LxPlatform.PROVIDER_ID) {
                        val result = repository.playableLxStream(customSongs, AudioQuality.Auto)
                        resolvedSong = result?.song ?: source.song
                        result?.stream
                    } else {
                        repository.playableStream(source.song, cookie, workDir(), AudioQuality.Auto)
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                }.getOrNull()
            }
            val url = stream?.url.orEmpty()
            if (url.isBlank()) {
                // 洛雪是「换源点了却回退」的主要出处，失败原因要进应用内日志，用户导日志时能看见
                if (source.providerId == LxPlatform.PROVIDER_ID) {
                    val reason = repository.customSourceFailure().ifBlank { failure?.message.orEmpty() }
                    AppLog.w("SourceResolver", "洛雪取流失败《${track.title}》：${reason.take(300)}")
                }
                synchronized(lock) {
                    negativeCache[cacheKey] = CachedFailure(System.currentTimeMillis(), customGeneration)
                }
                val reason = failure?.message
                    ?.takeIf { source.providerId == "soda" && it.contains("签名器") }
                    ?.substringBefore("\n")
                    ?: "没有返回播放地址"
                attempts += Attempt(source, reason)
                continue
            }
            val fromCustom = source.providerId == LxPlatform.PROVIDER_ID || stream?.extra?.containsKey("lx_source") == true
            val preview = if (fromCustom) stream?.preview == true else {
                source.isPreview || stream?.preview == true || isPreviewSource(source.providerId, source.song)
            }
            val resolvedSource = source.copy(song = resolvedSong).withQuality(
                stream?.ext.orEmpty(),
                stream?.bitrate ?: 0,
                stream?.extra.orEmpty(),
            )
            synchronized(lock) {
                urlCache[cacheKey] = CachedUrl(
                    url = url,
                    preview = preview,
                    at = System.currentTimeMillis(),
                    ext = resolvedSource.song.ext,
                    bitrate = resolvedSource.song.bitrate,
                    extra = stream?.extra.orEmpty(),
                    quality = qualityId,
                    generation = customGeneration,
                    song = resolvedSong,
                )
            }
            // 地址拿到了但只是片段，而设置里不允许试听：当没有可用地址，继续试下一个平台；
            // 缓存里留着这份试听地址，用户打开「允许试听」后可以直接复用
            if (preview && !policy.allowPreview && !explicit) {
                attempts += Attempt(source, "仅提供试听")
                continue
            }
            if (source.providerId == LxPlatform.PROVIDER_ID) {
                AppLog.d("SourceResolver", "洛雪取流成功《${track.title}》：${stream?.extra?.get("lx_source").orEmpty()}")
            }
            return Resolution.Ready(resolvedSource, url, preview)
        }

        val deepLink = track.sources.firstOrNull { it.song.link.isNotBlank() }?.song?.link.orEmpty()
        return Resolution.None(attempts, deepLink)
    }

    private fun TrackSource.withQuality(
        ext: String,
        bitrate: Int,
        extra: Map<String, String> = emptyMap(),
    ): TrackSource = copy(song = song.withQuality(ext, bitrate, extra))

    private fun Song.withQuality(ext: String, bitrate: Int, extra: Map<String, String> = emptyMap()): Song {
        val resolvedExt = ext.ifBlank { this.ext }
        val resolvedBitrate = bitrate.takeIf { it > 0 } ?: this.bitrate
        val resolvedExtra = this.extra.filterKeys { !it.startsWith("lx_") } + extra
        return if (resolvedExt == this.ext && resolvedBitrate == this.bitrate && resolvedExtra == this.extra) this
        else copy(ext = resolvedExt, bitrate = resolvedBitrate, extra = resolvedExtra)
    }

    fun invalidate(source: TrackSource) {
        val cacheKey = source.resolutionKey.ifBlank { source.song.key }
        synchronized(lock) {
            urlCache.remove(cacheKey)
            negativeCache.remove(cacheKey)
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
