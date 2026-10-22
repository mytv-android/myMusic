package com.mytv.mymusic.aggregate

import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AggregateSearchEngine(
    private val repository: MusicRepository,
    private val cookies: () -> Map<String, String>,
    private val settings: () -> AggregationSettings,
    private val splitOverrides: () -> Set<String> = { emptySet() },
    private val mergeOverrides: () -> Map<String, String> = { emptyMap() },
    private val limitPerProvider: Int = 20,
    private val timeoutMs: Long = 6_000,
) {
    private val health = ProviderHealth()
    private val cache = LinkedHashMap<String, CachedSearch>()
    private val cacheLock = Any()

    fun search(
        query: String,
        providers: List<ProviderInfo>,
        force: Boolean = false,
        page: Int = 1,
        existing: Map<String, List<Song>> = emptyMap(),
    ): Flow<SearchUpdate> = channelFlow {
        val enabled = providers.filter { it.search }.distinctBy { it.id }
        if (enabled.isEmpty()) {
            send(SearchUpdate.Completed(emptyList()))
            return@channelFlow
        }
        val availableIds = enabled.map { it.id }
        val priority = settings().priorityFor(availableIds)
        val preferred = priority.firstOrNull() ?: availableIds.first()
        val threshold = settings().threshold()
        val names = enabled.associate { it.id to it.name }
        val cacheKey = query.trim().lowercase(Locale.ROOT) + "|" + availableIds.sorted().joinToString(",")

        if (!force && page == 1) {
            val cached = synchronized(cacheLock) { cache[cacheKey] }
            if (cached != null && System.currentTimeMillis() - cached.at < CACHE_TTL_MS) {
                send(SearchUpdate.FromCache(cached.groups, cached.states, cached.songs, cached.hasMore))
                send(SearchUpdate.Completed(cached.groups, fromCache = true, hasMore = cached.hasMore))
                return@channelFlow
            }
        }

        val results = ConcurrentHashMap<String, List<Song>>()
        results.putAll(existing)
        val states = ConcurrentHashMap<String, ProviderSearchState>()
        val moreAvailable = ConcurrentHashMap<String, Boolean>()
        val semaphore = Semaphore(3)

        supervisorScope {
            enabled.forEach { provider ->
                launch(Dispatchers.IO) {
                    if (page > 1 && !repository.supportsSearchPagination(provider.id)) {
                        return@launch
                    }
                    if (!health.canRequest(provider.id)) {
                        val state = ProviderSearchState(provider.id, provider.name, ProviderStatus.Skipped, error = "暂时不可用")
                        states[provider.id] = state
                        send(SearchUpdate.ProviderArrived(provider.id, provider.name, emptyList(), 0, "暂时不可用", page = page))
                        return@launch
                    }

                    val started = System.currentTimeMillis()
                    val outcome = semaphore.withPermit {
                        try {
                            Result.success(
                                withTimeoutOrNull(timeoutMs) {
                                    repository.searchSongsPage(
                                        provider.id,
                                        cookies()[provider.id].orEmpty(),
                                        query,
                                        page,
                                        limitPerProvider,
                                    )
                                }
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                    }
                    val elapsed = System.currentTimeMillis() - started
                    val pageResult = outcome.getOrNull()
                    val songs = pageResult?.songs.orEmpty().take(limitPerProvider)
                    val timedOut = outcome.isSuccess && pageResult == null
                    val error = outcome.exceptionOrNull()?.message ?: if (timedOut) "请求超时" else ""
                    if (error.isBlank()) health.success(provider.id) else health.failure(provider.id)
                    if (pageResult?.hasMore == true) moreAvailable[provider.id] = true
                    val status = when {
                        timedOut -> ProviderStatus.Timeout
                        error.isNotBlank() -> ProviderStatus.Error
                        else -> ProviderStatus.Success
                    }
                    val mergedSongs = (results[provider.id].orEmpty() + songs).distinctBy { it.key }
                    results[provider.id] = mergedSongs
                    states[provider.id] = ProviderSearchState(provider.id, provider.name, status, mergedSongs.size, elapsed, error)
                    send(
                        SearchUpdate.ProviderArrived(
                            providerId = provider.id,
                            providerName = provider.name,
                            songs = songs,
                            elapsedMs = elapsed,
                            error = error.ifBlank { null },
                            timedOut = timedOut,
                            page = page,
                        )
                    )

                    val groups = TrackMatcher.group(
                        results, names, cookies(), threshold, priority,
                        splitOverrides(), mergeOverrides(),
                    )
                    send(SearchUpdate.Groups(TrackMatcher.rank(groups, enabled.size, priority, preferred, cookies())))
                }
            }
        }

        val groups = TrackMatcher.group(
            results, names, cookies(), threshold, priority,
            splitOverrides(), mergeOverrides(),
        )
        val ranked = TrackMatcher.rank(groups, enabled.size, priority, preferred, cookies())
        val hasMore = moreAvailable.isNotEmpty()
        if (page == 1) {
            synchronized(cacheLock) {
                cache[cacheKey] = CachedSearch(ranked, states.toMap(), results.toMap(), hasMore, System.currentTimeMillis())
                trimCache()
            }
        }
        send(SearchUpdate.Completed(ranked, hasMore = hasMore))
    }

    fun clearCache() {
        synchronized(cacheLock) { cache.clear() }
    }

    private fun trimCache() {
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        cache.entries.removeAll { it.value.at < cutoff }
        while (cache.size > 20) {
            val oldest = cache.entries.minByOrNull { it.value.at } ?: break
            cache.remove(oldest.key)
        }
    }

    private data class CachedSearch(
        val groups: List<CanonicalTrack>,
        val states: Map<String, ProviderSearchState>,
        val songs: Map<String, List<Song>>,
        val hasMore: Boolean,
        val at: Long,
    )

    private companion object {
        const val CACHE_TTL_MS = 5 * 60_000L
    }
}
