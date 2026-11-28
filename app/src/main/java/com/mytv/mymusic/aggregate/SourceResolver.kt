package com.mytv.mymusic.aggregate

import com.mytv.mymusic.AppLog
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.local.LOCAL_SOURCE
import com.mytv.mymusic.data.lx.LxPlatform
import com.mytv.mymusic.data.native.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    fun buildPolicy(
        track: CanonicalTrack,
        pinnedProvider: String? = null,
        manualMethod: PlaybackMethod? = null,
        usePlatformPlayback: Boolean = true,
    ): SourcePolicy {
        val current = settings()
        val available = buildList {
            track.sources.mapTo(this) { it.providerId }
            if (lxCandidates(track).isNotEmpty()) add(LxPlatform.PROVIDER_ID)
        }.distinct()
        val pinned = pinnedProvider
            ?: current.pinnedProvider.takeIf { current.playbackMode == PlaybackMode.Pinned }
        // 只保留真正能生效的偏好：自动模式等于没配置，脚本已被删掉的洛雪方式也不该留在策略里
        val preferences = if (pinnedProvider == null && usePlatformPlayback) {
            current.platformPlayback.filter { (providerId, preference) ->
                preference.mode != PlaybackMode.Auto && preference.method in playbackMethodsFor(providerId)
            }
        } else emptyMap()
        // 从某个平台的列表点进来的曲目才认它的入口平台，聚合结果没有入口平台
        val preferred = track.playbackPlatform?.takeIf { it in preferences }
        // 自动播放只允许已启用的音乐源；用户显式固定的平台、本次点进来的入口平台和本地源永远允许
        val allowed = (available.filter { it in current.enabledProviders } + listOfNotNull(pinned, preferred) + LOCAL_SOURCE).toSet()
        // 有按平台偏好时不再拿"曲目第一个来源"当排序起点：那会把偏好平台的歌排到别的平台前面
        val origin = if (track.playbackPlatform == null && current.playbackMode == PlaybackMode.Priority && preferences.isNotEmpty()) {
            null
        } else track.sources.firstOrNull()?.providerId
        return SourcePolicy(
            mode = current.playbackMode,
            priority = current.playbackOrder(available, origin, pinned),
            pinnedProvider = pinned,
            allowPreview = current.allowPreview,
            allowedProviders = allowed,
            // 只有「仅本次换源」这种一次性选择才独占，常驻设置里没有独占开关
            pinnedOnly = pinned != null && manualMethod != null,
            platformPlayback = preferences,
            preferredPlatform = preferred,
            manualMethod = manualMethod,
            deferPreview = preferences.isNotEmpty(),
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
        val allowed = policy.allowedProviders
        // 同一个平台按配置炸成多个候选：平台直连一个、洛雪再一个，各自独立探测和缓存
        val nativeSources = track.sources
            .filterNot { it.providerId in excludeProviders }
            .filter { allowed == null || it.providerId in allowed || it.providerId == pinned }
            .flatMap { source ->
                val preference = policy.platformPlayback[source.providerId]
                val methods = when {
                    source.providerId == pinned && policy.manualMethod != null -> listOf(policy.manualMethod)
                    preference?.mode == PlaybackMode.Priority ->
                        (listOf(preference.method) + playbackMethodsFor(source.providerId)).distinct()
                    preference?.mode == PlaybackMode.Pinned -> listOf(preference.method)
                    else -> listOf(PlaybackMethod.Direct)
                }
                methods.map { method -> source.withPlaybackMethod(method) }
            }
        // 洛雪伪源只有被允许（或用户显式固定）时才合成：有候选不等于能用
        val lxAllowed = allowed == null || LxPlatform.PROVIDER_ID in allowed || pinned == LxPlatform.PROVIDER_ID
        val sources = nativeSources + listOfNotNull(unifiedLxSource(track, excludeProviders).takeIf { lxAllowed })
        // 洛雪候选能不能播由脚本决定，不跟原生源的会员/试听状态走
        val statuses = sources.associateWith { playbackAvailability(it, cookieMap[it.providerId].orEmpty()) }
        // 手写策略没给顺序时沿用 sources 顺序；buildPolicy 生成的策略不这样回落，免得把未启用的平台带回来
        val priority = if (policy.priority.isEmpty() && allowed == null) {
            sources.map { it.providerId }.distinct()
        } else {
            policy.priority
        }
        fun priorityIndex(source: TrackSource): Int {
            val index = priority.indexOf(source.providerId)
            return if (index < 0) Int.MAX_VALUE else index
        }

        val pinnedSources = sources.filter { source ->
            source.providerId == pinned && statuses[source] != Availability.Unavailable
        }
        // 「按音质」和「首选平台」的备选排序：已启用的平台里挑码率最高的。没报码率的平台不参与比大小，
        // 按用户排的顺序跟在已知码率的候选后面——既不能把「未知」当成最差，也不能让它插到已知码率前面。
        // 「按顺序」模式相反：严格按用户排的顺序走，码率不参与跨平台比较。
        val byQuality = compareBy<TrackSource> { if (it.song.bitrate > 0) 0 else 1 }
            .thenByDescending { it.song.bitrate.coerceAtLeast(0) }
            .thenByDescending { it.matchScore }
            .thenBy { priorityIndex(it) }
        val byOrder = compareBy<TrackSource> { priorityIndex(it) }.thenByDescending { it.matchScore }
        val full = sources.filter { source ->
            val status = statuses.getValue(source)
            val custom = source.providerId == LxPlatform.PROVIDER_ID || source.playbackMethod == PlaybackMethod.Lx
            // 配了按平台方式的平台即使当前"不可用"也留着，交给脚本或下一次探测决定
            val configured = source.providerId in policy.platformPlayback
            source.providerId != pinned && (configured ||
                (status != Availability.Unavailable &&
                    !(status == Availability.VipOnly && cookieMap[source.providerId].isNullOrBlank() && !custom) &&
                    (status != Availability.PreviewOnly || custom)))
        }.sortedWith(if (policy.mode == PlaybackMode.Priority) byOrder else byQuality)
        val preview = sources.filter { source ->
            source.providerId != pinned &&
                statuses.getValue(source) == Availability.PreviewOnly &&
                source.providerId !in policy.platformPlayback &&
                source.providerId != LxPlatform.PROVIDER_ID &&
                policy.allowPreview
        }.sortedWith(if (policy.mode == PlaybackMode.Priority) byOrder else byQuality)
        val ordered = (pinnedSources + full + preview).distinctBy { it.playbackKey }
        // 入口平台排最前：同平台的直连和洛雪仍然按策略里的方式顺序走
        val preferred = policy.preferredPlatform ?: return ordered
        return ordered.filter { it.providerId == preferred } + ordered.filterNot { it.providerId == preferred }
    }

    /** 同一首歌换个取流方式就是另一个候选：缓存键要分开，直连的地址不能拿去当洛雪的结果。 */
    private fun TrackSource.withPlaybackMethod(method: PlaybackMethod): TrackSource =
        if (method == PlaybackMethod.Direct) copy(playbackMethod = method, resolutionKey = "") else copy(
            playbackMethod = method,
            availability = Availability.Playable,
            isPreview = false,
            resolutionKey = "${method.name}:${song.key}",
        )

    /** 洛雪候选能不能播由脚本决定，不跟原生源的会员/试听状态走。 */
    private fun playbackAvailability(source: TrackSource, cookie: String): Availability =
        if (source.playbackMethod != PlaybackMethod.Direct || source.providerId == LxPlatform.PROVIDER_ID) {
            Availability.Playable
        } else availabilityOf(source, cookie)

    /** 一个洛雪候选代表整个脚本集合；锚点只用于列表展示，实际歌曲由脚本解析结果决定。 */
    private fun unifiedLxSource(track: CanonicalTrack, excludeProviders: Set<String>): TrackSource? {
        if (LxPlatform.PROVIDER_ID in excludeProviders) return null
        val candidates = lxCandidates(track)
        val anchor = candidates.firstOrNull() ?: return null
        return anchor.copy(
            providerId = LxPlatform.PROVIDER_ID,
            providerName = LxPlatform.PROVIDER_NAME,
            playbackMethod = PlaybackMethod.Lx,
            availability = Availability.Playable,
            isPreview = false,
            // 候选指纹进缓存键：expand 补配出新平台变体后要能重新尝试洛雪，别被旧负缓存挡住
            resolutionKey = "${LxPlatform.PROVIDER_ID}:${track.id}:" +
                candidates.map { it.song.key }.sorted().joinToString(","),
        )
    }

    /** 能交给洛雪脚本处理的平台变体；脚本能不能播与原生源当前的可用性无关，不在这里过滤。 */
    private fun lxCandidates(track: CanonicalTrack): List<TrackSource> =
        track.sources.asSequence()
            .filter { repository.supportsCustomSource(it.providerId) }
            .sortedWith(compareByDescending<TrackSource> { it.matchScore }.thenByDescending { it.song.bitrate })
            .distinctBy { it.song.key }
            .toList()

    suspend fun resolve(
        track: CanonicalTrack,
        pinnedProvider: String? = null,
        excludeProviders: Set<String> = emptySet(),
        policy: SourcePolicy = buildPolicy(track, pinnedProvider),
        excludeMethods: Set<String> = emptySet(),
    ): Resolution {
        val ordered = orderCandidates(track, pinnedProvider, excludeProviders, policy)
        val attempts = mutableListOf<Attempt>()
        val cookieMap = cookies()
        val explicitProvider = pinnedProvider ?: policy.pinnedProvider
        val customGeneration = repository.customSourceGeneration()
        // Lazy：串行路径一个候选都不走洛雪时不该白算一遍
        val customSongs = lazy { lxCandidates(track).map { it.song } }

        // 从某个平台点进来的歌先用该平台自己配置的方式；它给不出整曲，再交给全局规则
        val preferred = policy.preferredPlatform
        if (preferred != null && policy.mode == PlaybackMode.Auto && !policy.pinnedOnly) {
            var previewHead: Resolution.Ready? = null
            for (source in ordered.filter { it.providerId == preferred }) {
                when (val probe = probeSource(track, source, policy, explicitProvider, cookieMap, customGeneration, customSongs)) {
                    is Probe.Ready -> {
                        val ready = Resolution.Ready(probe.source, probe.url, probe.preview, probe.resolvedAt, attempts.toList())
                        if (!ready.isPreview || !policy.deferPreview) return ready
                        if (previewHead == null) previewHead = ready
                        attempts += Attempt(source, "仅提供试听")
                    }
                    is Probe.Skipped -> attempts += Attempt(source, probe.reason)
                }
            }
            val rest = ordered.filterNot { it.providerId == preferred }
            val tail = resolveByQuality(
                track, rest, policy.copy(preferredPlatform = null),
                explicitProvider, cookieMap, customGeneration, customSongs,
            )
            val merged = attempts + ((tail as? Resolution.Ready)?.attempts ?: (tail as Resolution.None).attempts)
            return when (tail) {
                is Resolution.Ready -> tail.copy(attempts = merged)
                is Resolution.None -> previewHead?.copy(attempts = merged) ?: tail.copy(attempts = merged)
            }
        }
        // 「按音质」要的是所有平台里码率最高的那个：串行会把第一个能响的当结果，
        // 哪怕后面的平台能给更高音质，所以并发问遍所有候选再挑
        if (policy.mode == PlaybackMode.Auto && !policy.pinnedOnly) {
            return resolveByQuality(track, ordered, policy, explicitProvider, cookieMap, customGeneration, customSongs)
        }
        // 「按顺序」「首选平台」顺序敏感：第一个能响的就用，提前返回也最快
        var firstPreview: Resolution.Ready? = null
        for (source in ordered) {
            // 刚失败过的方式本次不再试，避免原地重试同一份脚本/直连
            if (source.playbackKey in excludeMethods) {
                attempts += Attempt(source, "播放失败")
                continue
            }
            // 「仅本次换源」指定了方式：只试这个平台，其余记一笔原因就跳过
            if (policy.pinnedOnly && explicitProvider != null &&
                source.providerId != explicitProvider && source.providerId != policy.preferredPlatform
            ) {
                attempts += Attempt(source, "固定音源独占，不尝试其他来源")
                continue
            }
            when (val probe = probeSource(track, source, policy, explicitProvider, cookieMap, customGeneration, customSongs)) {
                is Probe.Ready -> {
                    val ready = Resolution.Ready(probe.source, probe.url, probe.preview, probe.resolvedAt)
                    // 试听先收着继续找：同平台可能还有洛雪能拿到整曲
                    if (!ready.isPreview || !policy.deferPreview) return ready
                    if (firstPreview == null) firstPreview = ready
                    attempts += Attempt(source, "仅提供试听")
                }
                is Probe.Skipped -> {
                    attempts += Attempt(source, probe.reason)
                    // 「固定」方式失败就停在这首，把失败源带回去给播放页说明原因
                    val fixed = policy.platformPlayback[source.providerId]?.mode == PlaybackMode.Pinned
                    if (fixed) {
                        val deepLink = track.sources.firstOrNull { it.song.link.isNotBlank() }?.song?.link.orEmpty()
                        return Resolution.None(attempts, deepLink, source)
                    }
                }
            }
        }

        firstPreview?.let { return it }
        val deepLink = track.sources.firstOrNull { it.song.link.isNotBlank() }?.song?.link.orEmpty()
        return Resolution.None(attempts, deepLink)
    }

    /**
     * 「按音质」：并发问遍所有候选，拿实际码率挑最高的。串行会把第一个能响的当结果，
     * 哪怕后面的平台能给更高音质。缓存命中的不重复发请求，按已知码率参与竞争；
     * 显式指定的平台仍然优先，码率竞争只在不指定时才有意义。洛雪伪源同样参与——
     * 脚本能给更高码率时没理由不用它，并发也不会让总耗时超过最慢的那个。
     */
    private suspend fun resolveByQuality(
        track: CanonicalTrack,
        ordered: List<TrackSource>,
        policy: SourcePolicy,
        explicitProvider: String?,
        cookieMap: Map<String, String>,
        customGeneration: Long,
        customSongs: Lazy<List<Song>>,
    ): Resolution = coroutineScope {
        val attempts = mutableListOf<Attempt>()
        var ready = mutableListOf<Probe.Ready>()
        for ((source, probe) in ordered.map { source ->
            async { source to probeSource(track, source, policy, explicitProvider, cookieMap, customGeneration, customSongs) }
        }.awaitAll()) {
            when (probe) {
                is Probe.Skipped -> attempts += Attempt(source, probe.reason)
                is Probe.Ready -> ready += probe
            }
        }
        val deepLink = track.sources.firstOrNull { it.song.link.isNotBlank() }?.song?.link.orEmpty()
        // 码率相同看匹配度，还相同就沿用候选顺序——并发前的排列顺序就是排好的顺序
        // 配了按平台方式时整曲优先于试听：同平台还有洛雪没试，不能拿直连的片段交差
        val ranker = if (policy.deferPreview) {
            compareBy<Probe.Ready> { !it.preview }
                .thenBy { it.source.song.bitrate }
                .thenBy { it.source.matchScore }
        } else {
            compareBy<Probe.Ready> { it.source.song.bitrate }
                .thenBy { it.source.matchScore }
        }
        val best = ready.filter { it.source.providerId == explicitProvider }.maxWithOrNull(ranker)
            ?: ready.maxWithOrNull(ranker)
        best?.let { return@coroutineScope Resolution.Ready(it.source, it.url, it.preview, it.resolvedAt) }
        // 「固定」方式全失败：把失败源带回去，播放页据此停在当前歌曲
        val fixedFailure = ordered.firstOrNull { source ->
            policy.platformPlayback[source.providerId]?.mode == PlaybackMode.Pinned
        }
        Resolution.None(attempts, deepLink, fixedFailure)
    }

    /** 单个候选探一次的结果：拿到了可用地址，或者因为某个原因跳过。 */
    private sealed class Probe {
        data class Ready(
            val source: TrackSource,
            val url: String,
            val preview: Boolean,
            val resolvedAt: Long,
        ) : Probe()

        data class Skipped(val reason: String) : Probe()
    }

    /**
     * 对单个候选探一次：可用性、地址缓存、负缓存、取流、锁档回退、试听判定都在这里。
     * 串行路径命中即返回；并发路径收集全部结果后挑码率最高的。
     */
    private suspend fun probeSource(
        track: CanonicalTrack,
        source: TrackSource,
        policy: SourcePolicy,
        explicitProvider: String?,
        cookieMap: Map<String, String>,
        customGeneration: Long,
        customSongs: Lazy<List<Song>>,
    ): Probe {
        val cookie = cookieMap[source.providerId].orEmpty()
        // 洛雪方式（伪源或某个平台的洛雪变体）都按脚本处理，不走会员/试听判定
        val custom = source.providerId == LxPlatform.PROVIDER_ID || source.playbackMethod == PlaybackMethod.Lx
        val status = playbackAvailability(source, cookie)
        if (status == Availability.Unavailable) return Probe.Skipped("不可用")
        if (status == Availability.VipOnly && cookie.isBlank() && !custom) return Probe.Skipped("需要登录或会员")
        val explicit = source.providerId == explicitProvider
        if ((status == Availability.PreviewOnly || source.isPreview) && !policy.allowPreview && !explicit && !custom) {
            return Probe.Skipped("仅提供试听")
        }

        val now = System.currentTimeMillis()
        val cacheKey = source.playbackKey
        val cached = synchronized(lock) { urlCache[cacheKey] }
        // 换过音质偏好就不能复用旧地址：用户选了无损，缓存里那条 128k 必须作废
        val qualityId = quality().id
        if (cached != null && cached.quality == qualityId && cached.generation == customGeneration && now - cached.at < URL_TTL_MS) {
            // 汽水这类取流后才知道是试听的源，缓存里带着试听标记：同样受「允许试听」约束
            if (cached.preview && !policy.allowPreview && !explicit) return Probe.Skipped("仅提供试听")
            val cachedSong = if (custom) cached.song ?: source.song else source.song
            val cachedSource = source.copy(song = cachedSong).withQuality(cached.ext, cached.bitrate, cached.extra)
            return Probe.Ready(cachedSource, cached.url, cached.preview, cached.at)
        }
        if (cached != null) synchronized(lock) { urlCache.remove(cacheKey) }

        val cachedFailure = synchronized(lock) { negativeCache[cacheKey] }
        // 洛雪脚本失败多半是临时性风控/上游抖动：负缓存短一些，兜底能尽快重试
        val negativeTtl = if (custom) CUSTOM_NEGATIVE_TTL_MS else NEGATIVE_TTL_MS
        if (cachedFailure != null && cachedFailure.generation == customGeneration && now - cachedFailure.at < negativeTtl) {
            return Probe.Skipped("近期解析失败")
        }

        var failure: Throwable? = null
        var resolvedSong = source.song
        var stream = runCatching {
            if (custom) {
                // 按平台指定的洛雪只解析这个平台的那首歌；洛雪伪源才让脚本在整个候选集里挑
                // 洛雪伪源让脚本在整个候选集里挑；按平台指定的洛雪只解析这个平台的那首歌
                val songs = if (source.providerId != LxPlatform.PROVIDER_ID && source.playbackMethod == PlaybackMethod.Lx) {
                    listOf(source.song)
                } else customSongs.value
                val result = repository.playableLxStream(songs, quality())
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
                if (custom) {
                    // 洛雪伪源让脚本在整个候选集里挑；按平台指定的洛雪只解析这个平台的那首歌
                val songs = if (source.providerId != LxPlatform.PROVIDER_ID && source.playbackMethod == PlaybackMethod.Lx) {
                    listOf(source.song)
                } else customSongs.value
                    val result = repository.playableLxStream(songs, AudioQuality.Auto)
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
            if (custom) {
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
            return Probe.Skipped(reason)
        }
        val fromCustom = custom || stream?.extra?.containsKey("lx_source") == true
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
        if (preview && !policy.allowPreview && !explicit) return Probe.Skipped("仅提供试听")
        if (custom) {
            AppLog.d("SourceResolver", "洛雪取流成功《${track.title}》：${stream?.extra?.get("lx_source").orEmpty()}")
        }
        return Probe.Ready(resolvedSource, url, preview, System.currentTimeMillis())
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
        val cacheKey = source.playbackKey
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
        const val CUSTOM_NEGATIVE_TTL_MS = 60_000L
    }
}
