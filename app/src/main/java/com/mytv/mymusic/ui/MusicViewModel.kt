package com.mytv.mymusic.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.aggregate.AggregateSearchEngine
import com.mytv.mymusic.aggregate.AggregationSettings
import com.mytv.mymusic.aggregate.CanonicalCollection
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.CollectionMatcher
import com.mytv.mymusic.aggregate.MatchJob
import com.mytv.mymusic.aggregate.MatchMethod
import com.mytv.mymusic.aggregate.MatchProgress
import com.mytv.mymusic.aggregate.MatchStrictness
import com.mytv.mymusic.aggregate.PlaybackMode
import com.mytv.mymusic.aggregate.ProviderSearchState
import com.mytv.mymusic.aggregate.ProviderStatus
import com.mytv.mymusic.aggregate.Resolution
import com.mytv.mymusic.aggregate.SearchScope
import com.mytv.mymusic.aggregate.SearchStatus
import com.mytv.mymusic.aggregate.SearchUpdate
import com.mytv.mymusic.aggregate.SongMatchResult
import com.mytv.mymusic.aggregate.SourceResolver
import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.aggregate.availabilityOf
import com.mytv.mymusic.data.CanonicalSourceEntity
import com.mytv.mymusic.data.CanonicalTrackEntity
import com.mytv.mymusic.data.DownloadRecord
import com.mytv.mymusic.data.LocalPlaylist
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.QRCreateResult
import com.mytv.mymusic.data.RepeatMode
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.SearchKind
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import com.mytv.mymusic.data.TrackOverrideEntity
import com.mytv.mymusic.playback.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale

data class ProviderHome(
    val provider: ProviderInfo,
    val recommendations: List<Playlist> = emptyList(),
    val charts: List<TopList> = emptyList(),
    val hotSongs: List<Song> = emptyList(),
)

data class MusicUiState(
    val providers: List<ProviderInfo> = emptyList(),
    val providerId: String = "netease",
    val theme: String = "system",
    val cookies: Map<String, String> = emptyMap(),
    val homes: List<ProviderHome> = emptyList(),
    val recommendations: List<Playlist> = emptyList(),
    val categories: List<PlaylistCategory> = emptyList(),
    val categoryPlaylists: List<Playlist> = emptyList(),
    val charts: List<TopList> = emptyList(),
    val hotSongs: List<Song> = emptyList(),
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val albums: List<Playlist> = emptyList(),
    val collectionTitle: String = "",
    val collectionSongs: List<Song> = emptyList(),
    val query: String = "",
    val searchKind: SearchKind = SearchKind.Song,
    val searchScope: SearchScope = SearchScope.Aggregated,
    val aggregateGroups: List<CanonicalTrack> = emptyList(),
    val aggregateCollections: List<CanonicalCollection> = emptyList(),
    val providerStates: Map<String, ProviderSearchState> = emptyMap(),
    val collectionProviderStates: Map<String, ProviderSearchState> = emptyMap(),
    val aggregateStatus: SearchStatus = SearchStatus.Idle,
    val collectionSearchStatus: SearchStatus = SearchStatus.Idle,
    val aggregateHasMore: Boolean = false,
    val aggregation: AggregationSettings = AggregationSettings(),
    val loading: Boolean = false,
    val error: String = "",
    val current: Song? = null,
    val currentTrack: CanonicalTrack? = null,
    val currentSource: TrackSource? = null,
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val queue: List<Song> = emptyList(),
    val canonicalQueue: List<CanonicalTrack> = emptyList(),
    val lyrics: String = "",
    val playerOpen: Boolean = false,
    val login: QRCreateResult? = null,
    val loginStatus: String = "",
    val page: Int = 1,
    val sourcePickerTrack: CanonicalTrack? = null,
    val sourcePickerQueue: List<CanonicalTrack> = emptyList(),
    val sourcePickerBusy: String = "",
    val sourcePickerError: String = "",
    val sourcePickerInfo: String = "",
    val mergeTrack: CanonicalTrack? = null,
    val playbackNotice: String = "",
    val deepLink: String = "",
    val collectionSource: String = "",
    val collectionMatches: Map<String, SongMatchResult> = emptyMap(),
    val collectionMatchTarget: String = "",
    val collectionMatchProgress: MatchProgress? = null,
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MyMusicApp
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient()
    private val base = MutableStateFlow(MusicUiState())
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var loginJob: Job? = null
    private var aggregateSearchJob: Job? = null
    private var aggregateSearchGeneration = 0
    private var collectionSearchJob: Job? = null
    private var collectionSearchGeneration = 0
    private var collectionMatchJob: Job? = null
    private var failoverInProgress = false
    private var splitOverrideKeys: Set<String> = emptySet()
    private var mergeTargets: Map<String, String> = emptyMap()
    private var aggregateSongs: Map<String, List<Song>> = emptyMap()
    private val pinnedProviders = mutableMapOf<String, String>()
    private val aggregateDao = app.database.aggregate()

    val favorites = app.database.dao().songs("favorite").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = app.database.dao().songs("history").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val localPlaylists = app.database.dao().playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val downloads = app.database.dao().downloads().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val state = combine(base, app.settings.state) { ui, settings ->
        ui.copy(
            providerId = settings.provider,
            theme = settings.theme,
            cookies = settings.cookies,
            aggregation = settings.aggregation,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MusicUiState())

    private val searchEngine = AggregateSearchEngine(
        repository = app.repository,
        cookies = { state.value.cookies },
        settings = { state.value.aggregation },
        splitOverrides = { splitOverrideKeys },
        mergeOverrides = { mergeTargets },
    )
    private val resolver = SourceResolver(
        repository = app.repository,
        cookies = { state.value.cookies },
        settings = { state.value.aggregation },
    )
    private val matchJob = MatchJob(
        repository = app.repository,
        cookies = { state.value.cookies },
        dao = aggregateDao,
        settings = { state.value.aggregation },
    )

    init {
        val token = SessionToken(application, android.content.ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).buildAsync()
        future.addListener({
            controller = future.get().also { it.addListener(playerListener) }
            startProgress()
        }, MoreExecutors.directExecutor())
        viewModelScope.launch {
            runCatching { base.value = base.value.copy(providers = app.repository.providers()) }
            refreshHome()
        }
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            runCatching { aggregateDao.pruneMatches(cutoff) }
            runCatching { aggregateDao.pruneTracks(cutoff) }
            val overrides = runCatching { aggregateDao.overrides() }.getOrDefault(emptyList())
            splitOverrideKeys = overrides
                .filter { it.action == "split" }
                .map { "${it.sourceKey}|${it.canonicalId}" }
                .toSet()
            mergeTargets = overrides
                .filter { it.action == "merge" }
                .mapNotNull { item ->
                    val target = item.canonicalId.removePrefix("src:")
                    if (target.isBlank()) null else item.sourceKey to target
                }
                .toMap()
        }
    }

    fun selectProvider(id: String) = viewModelScope.launch {
        app.settings.provider(id)
        base.value = base.value.copy(page = 1, categories = emptyList(), categoryPlaylists = emptyList())
        loadProviderDetails()
    }

    fun selectTheme(value: String) = viewModelScope.launch { app.settings.theme(value) }
    fun query(value: String) { base.value = base.value.copy(query = value) }
    fun searchKind(value: SearchKind) {
        base.value = base.value.copy(searchKind = value, page = 1)
        search(force = false)
    }
    fun setSearchScope(value: SearchScope) {
        base.value = base.value.copy(searchScope = value)
        search(force = false)
    }
    fun openPlayer(open: Boolean) { base.value = base.value.copy(playerOpen = open) }
    fun clearError() { base.value = base.value.copy(error = "") }
    fun clearNotice() { base.value = base.value.copy(playbackNotice = "") }

    fun refreshHome() = launchAction {
        val homes = coroutineScope {
            state.value.providers.map { provider ->
                async {
                    val cookie = cookieFor(provider.id)
                    ProviderHome(
                        provider = provider,
                        recommendations = if (provider.recommendedPlaylists) runCatching { app.repository.recommended(provider.id, cookie) }.getOrDefault(emptyList()) else emptyList(),
                        charts = if (provider.topLists) runCatching { app.repository.topLists(provider.id, cookie) }.getOrDefault(emptyList()) else emptyList(),
                        hotSongs = if (provider.hotSongs) runCatching { app.repository.hotSongs(provider.id, cookie, 12) }.getOrDefault(emptyList()) else emptyList(),
                    )
                }
            }.awaitAll()
        }.filter { it.recommendations.isNotEmpty() || it.charts.isNotEmpty() || it.hotSongs.isNotEmpty() }
        base.value = base.value.copy(homes = homes)
        loadProviderDetails()
    }

    private suspend fun loadProviderDetails() {
        val id = state.value.providerId
        val provider = state.value.providers.find { it.id == id } ?: return
        val categories = if (provider.playlistCategories) runCatching { app.repository.categories(id, cookieFor(id)) }.getOrDefault(emptyList()) else emptyList()
        base.value = base.value.copy(categories = categories)
    }

    fun loadCategory(categoryId: String, page: Int = base.value.page) = launchAction {
        val lists = app.repository.categoryPlaylists(state.value.providerId, cookieFor(state.value.providerId), categoryId, page, 30)
        base.value = base.value.copy(categoryPlaylists = lists, page = page)
    }

    fun search(page: Int = 1, force: Boolean = true) {
        val value = base.value.query.trim()
        if (value.isBlank()) return
        val kind = base.value.searchKind
        if (base.value.searchScope == SearchScope.Aggregated) {
            when (kind) {
                SearchKind.Song -> {
                    searchAggregated(value, force, page)
                    return
                }
                SearchKind.Album, SearchKind.Playlist -> {
                    searchCollections(kind, value)
                    return
                }
                SearchKind.Link -> Unit
            }
        }
        launchAction {
            val ui = state.value
            when (kind) {
                SearchKind.Song -> base.value = base.value.copy(songs = app.repository.searchSongs(ui.providerId, cookieFor(ui.providerId), value))
                SearchKind.Playlist -> base.value = base.value.copy(playlists = app.repository.searchPlaylists(ui.providerId, cookieFor(ui.providerId), value))
                SearchKind.Album -> base.value = base.value.copy(albums = app.repository.searchAlbums(ui.providerId, cookieFor(ui.providerId), value))
                SearchKind.Link -> openLink(value)
            }
            base.value = base.value.copy(page = page)
        }
    }

    fun searchMore() {
        val query = base.value.query.trim()
        if (query.isBlank() || !base.value.aggregateHasMore || base.value.loading) return
        search(base.value.page + 1, force = true)
    }

    private fun searchAggregated(query: String, force: Boolean = true, page: Int = 1) {
        aggregateSearchJob?.cancel()
        val generation = ++aggregateSearchGeneration
        if (page == 1) {
            aggregateSongs = emptyMap()
            base.value = base.value.copy(
                loading = true,
                error = "",
                aggregateStatus = SearchStatus.Loading,
                aggregateGroups = emptyList(),
                providerStates = emptyMap(),
                aggregateHasMore = false,
                page = 1,
            )
        } else {
            base.value = base.value.copy(
                loading = true,
                error = "",
                page = page,
            )
        }
        aggregateSearchJob = viewModelScope.launch {
            val providers = enabledProviders()
            if (providers.isEmpty()) {
                fail("没有启用可搜索的平台")
                return@launch
            }
            try {
                searchEngine.search(query, providers, force, page, aggregateSongs).collect { update ->
                    if (generation != aggregateSearchGeneration) return@collect
                    when (update) {
                        is SearchUpdate.ProviderArrived -> {
                            val status = when {
                                update.timedOut -> ProviderStatus.Timeout
                                update.error != null -> ProviderStatus.Error
                                else -> ProviderStatus.Success
                            }
                            val merged = (aggregateSongs[update.providerId].orEmpty() + update.songs).distinctBy { it.key }
                            aggregateSongs = aggregateSongs + (update.providerId to merged)
                            val providerState = ProviderSearchState(
                                providerId = update.providerId,
                                providerName = update.providerName,
                                status = status,
                                count = merged.size,
                                elapsedMs = update.elapsedMs,
                                error = update.error.orEmpty(),
                            )
                            base.value = base.value.copy(
                                providerStates = base.value.providerStates + (update.providerId to providerState),
                                aggregateStatus = SearchStatus.Partial,
                            )
                        }
                        is SearchUpdate.Groups -> base.value = base.value.copy(aggregateGroups = update.groups)
                        is SearchUpdate.FromCache -> {
                            aggregateSongs = update.songs
                            base.value = base.value.copy(
                                aggregateGroups = update.groups,
                                providerStates = update.states,
                                aggregateStatus = SearchStatus.Complete,
                                aggregateHasMore = update.hasMore,
                            )
                        }
                        is SearchUpdate.Completed -> {
                            val states = base.value.providerStates.values
                            val allFailed = states.isNotEmpty() && states.all {
                                it.status == ProviderStatus.Timeout || it.status == ProviderStatus.Error || it.status == ProviderStatus.Skipped
                            }
                            base.value = base.value.copy(
                                aggregateGroups = update.groups,
                                aggregateStatus = when {
                                    update.groups.isNotEmpty() -> SearchStatus.Complete
                                    allFailed -> SearchStatus.Failed
                                    else -> SearchStatus.Empty
                                },
                                aggregateHasMore = update.hasMore,
                                loading = false,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == aggregateSearchGeneration) fail(error.message ?: "聚合搜索失败")
            } finally {
                if (generation == aggregateSearchGeneration) {
                    base.value = base.value.copy(loading = false)
                }
            }
        }
    }

    private fun searchCollections(kind: SearchKind, query: String) {
        collectionSearchJob?.cancel()
        val generation = ++collectionSearchGeneration
        base.value = base.value.copy(
            loading = true,
            error = "",
            aggregateCollections = emptyList(),
            collectionProviderStates = emptyMap(),
            collectionSearchStatus = SearchStatus.Loading,
            page = 1,
        )
        collectionSearchJob = viewModelScope.launch {
            val providers = enabledProviders().filter {
                if (kind == SearchKind.Album) it.searchAlbum else it.searchPlaylist
            }
            if (providers.isEmpty()) {
                fail("没有启用支持${kind.label}搜索的平台")
                return@launch
            }
            val names = providers.associate { it.id to it.name }
            val priority = state.value.aggregation.priorityFor(providers.map { it.id })
            val results = ConcurrentHashMap<String, List<Playlist>>()
            val states = ConcurrentHashMap<String, ProviderSearchState>()
            val semaphore = Semaphore(3)
            try {
                supervisorScope {
                    providers.forEach { provider ->
                        launch(Dispatchers.IO) {
                            val started = System.currentTimeMillis()
                            val outcome = semaphore.withPermit {
                                try {
                                    Result.success(
                                        withTimeoutOrNull(6_000) {
                                            if (kind == SearchKind.Album) {
                                                app.repository.searchAlbums(provider.id, cookieFor(provider.id), query)
                                            } else {
                                                app.repository.searchPlaylists(provider.id, cookieFor(provider.id), query)
                                            }
                                        }
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    Result.failure(error)
                                }
                            }
                            val elapsed = System.currentTimeMillis() - started
                            val lists = outcome.getOrNull().orEmpty()
                            val timedOut = outcome.isSuccess && outcome.getOrNull() == null
                            val error = outcome.exceptionOrNull()?.message ?: if (timedOut) "请求超时" else ""
                            val status = when {
                                timedOut -> ProviderStatus.Timeout
                                error.isNotBlank() -> ProviderStatus.Error
                                else -> ProviderStatus.Success
                            }
                            val providerState = ProviderSearchState(provider.id, provider.name, status, lists.size, elapsed, error)
                            states[provider.id] = providerState
                            results[provider.id] = lists
                            if (generation == collectionSearchGeneration) {
                                base.value = base.value.copy(
                                    aggregateCollections = CollectionMatcher.group(results, names, kind, priority),
                                    collectionProviderStates = base.value.collectionProviderStates + (provider.id to providerState),
                                    collectionSearchStatus = SearchStatus.Partial,
                                )
                            }
                        }
                    }
                }
                if (generation == collectionSearchGeneration) {
                    val grouped = CollectionMatcher.group(results, names, kind, priority)
                    val allFailed = states.isNotEmpty() && states.values.all {
                        it.status == ProviderStatus.Timeout || it.status == ProviderStatus.Error || it.status == ProviderStatus.Skipped
                    }
                    base.value = base.value.copy(
                        aggregateCollections = grouped,
                        collectionSearchStatus = when {
                            grouped.isNotEmpty() -> SearchStatus.Complete
                            allFailed -> SearchStatus.Failed
                            else -> SearchStatus.Empty
                        },
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == collectionSearchGeneration) fail(error.message ?: "聚合搜索失败")
            } finally {
                if (generation == collectionSearchGeneration) {
                    base.value = base.value.copy(loading = false)
                }
            }
        }
    }

    private fun enabledProviders(): List<ProviderInfo> {
        val all = state.value.providers.filter { it.search }
        if (all.isEmpty()) return emptyList()
        val ids = state.value.aggregation.enabledOrDefault(all.map { it.id })
        return all.filter { it.id in ids }.ifEmpty { all.take(5) }
    }

    fun openPlaylist(item: Playlist, album: Boolean) = launchAction {
        val source = item.source.ifBlank { state.value.providerId }
        val songs = if (album) app.repository.albumSongs(source, cookieFor(source), item.id)
        else app.repository.playlistSongs(source, cookieFor(source), item.id)
        base.value = base.value.copy(
            collectionTitle = item.name,
            collectionSongs = songs,
            collectionSource = source,
            collectionMatches = emptyMap(),
            collectionMatchProgress = null,
            collectionMatchTarget = defaultMatchTarget(source),
        )
    }

    fun openChart(item: TopList) = launchAction {
        val source = item.source.ifBlank { state.value.providerId }
        base.value = base.value.copy(
            collectionTitle = item.name,
            collectionSongs = app.repository.topListSongs(source, cookieFor(source), item.id),
            collectionSource = source,
            collectionMatches = emptyMap(),
            collectionMatchProgress = null,
            collectionMatchTarget = defaultMatchTarget(source),
        )
    }

    fun openLink(link: String) = launchAction {
        val id = state.value.providerId
        val result = runCatching { app.repository.parsePlaylist(id, cookieFor(id), link) }.getOrNull()
            ?: runCatching { app.repository.parseAlbum(id, cookieFor(id), link) }.getOrNull()
        if (result != null) {
            val source = result.item.source.ifBlank { id }
            base.value = base.value.copy(
                collectionTitle = result.item.name,
                collectionSongs = result.songs,
                collectionSource = source,
                collectionMatches = emptyMap(),
                collectionMatchProgress = null,
                collectionMatchTarget = defaultMatchTarget(source),
            )
        } else {
            val song = app.repository.parseSong(id, cookieFor(id), link)
            play(song, listOf(song))
        }
    }

    fun play(song: Song, queue: List<Song> = base.value.collectionSongs.ifEmpty { base.value.songs.ifEmpty { base.value.hotSongs } }) {
        val selected = canonicalForSong(song)
        val tracks = queue.map { canonicalForSong(it) }.ifEmpty { listOf(selected) }
        val ordered = listOf(selected) + tracks.filter { it.id != selected.id }
        playTrack(selected, ordered, song.source.takeIf { it.isNotBlank() })
    }

    fun playTrack(track: CanonicalTrack, queue: List<CanonicalTrack> = listOf(track), pinnedProvider: String? = null) {
        viewModelScope.launch {
            val player = controller
            if (player == null) {
                base.value = base.value.copy(error = "播放器尚未就绪")
                return@launch
            }
            val ordered = (listOf(track) + queue.filter { it.id != track.id }).distinctBy { it.id }
            val local = localFileFor(track)
            val resolution = if (local == null) {
                resolver.resolve(track, pinnedProvider, emptySet(), resolver.buildPolicy(track, pinnedProvider))
            } else {
                null
            }
            if (local == null && resolution !is Resolution.Ready) {
                val message = noSourceMessage(track, resolution!!)
                base.value = base.value.copy(
                    playbackNotice = message,
                    deepLink = (resolution as? Resolution.None)?.deepLink.orEmpty(),
                )
                return@launch
            }
            if (pinnedProvider != null) pinnedProviders[track.id] = pinnedProvider
            val source = local?.first ?: (resolution as Resolution.Ready).source
            val resolvedSong = if (local != null) {
                source.song.copy(url = Uri.fromFile(local.second).toString())
            } else {
                source.song.copy(url = (resolution as Resolution.Ready).url)
            }
            val queueSongs = ordered.map { item ->
                if (item.id == track.id) resolvedSong else preferredSong(item)
            }.toMutableList()
            val fallbackNotice = if (local != null) {
                ""
            } else {
                val ready = resolution as Resolution.Ready
                when {
                    pinnedProvider != null && ready.source.providerId != pinnedProvider ->
                        "所选平台没有返回可播放地址，已回退到${ready.source.providerName}"
                    ready.isPreview -> "当前为试听片段"
                    else -> ""
                }
            }
            player.setMediaItems(queueSongs.map(::mediaItem), 0, 0)
            player.prepare()
            player.play()
            base.value = base.value.copy(
                queue = queueSongs,
                canonicalQueue = ordered,
                current = resolvedSong,
                currentTrack = track,
                currentSource = source,
                playerOpen = true,
                playbackNotice = fallbackNotice,
                error = "",
                deepLink = "",
            )
            rememberCanonical(track)
            remember("history", historySong(resolvedSong))
            loadLyrics(resolvedSong)
        }
    }

    fun openSource(track: CanonicalTrack, queue: List<CanonicalTrack> = base.value.aggregateGroups) {
        val ordered = queue.ifEmpty { listOf(track) }.let { list ->
            if (list.any { it.id == track.id }) list else listOf(track) + list
        }
        base.value = base.value.copy(
            sourcePickerTrack = track,
            sourcePickerQueue = ordered,
            sourcePickerBusy = "",
            sourcePickerError = "",
            sourcePickerInfo = "",
        )
    }

    fun openSourceForSong(song: Song) {
        val track = canonicalForSong(song)
        val queue = base.value.collectionSongs.map { canonicalForSong(it) }.ifEmpty { listOf(track) }
        openSource(track, queue)
    }

    fun closeSource() {
        base.value = base.value.copy(
            sourcePickerTrack = null,
            sourcePickerQueue = emptyList(),
            sourcePickerBusy = "",
            sourcePickerError = "",
            sourcePickerInfo = "",
        )
    }

    fun chooseSource(providerId: String, remember: Boolean = false) {
        val track = base.value.sourcePickerTrack ?: return
        val queue = base.value.sourcePickerQueue.ifEmpty { listOf(track) }
        viewModelScope.launch {
            base.value = base.value.copy(sourcePickerBusy = providerId, sourcePickerError = "", sourcePickerInfo = "")
            val existing = track.sources.find { it.providerId == providerId }
            val source = if (existing != null) {
                existing
            } else {
                val originalSong = track.sources.firstOrNull()?.song
                if (originalSong == null) {
                    base.value = base.value.copy(sourcePickerBusy = "", sourcePickerError = "没有可匹配的原始曲目")
                    return@launch
                }
                val result = matchJob.matchOne(originalSong, providerId)
                if (!result.matched || result.song == null) {
                    base.value = base.value.copy(
                        sourcePickerBusy = "",
                        sourcePickerError = result.reason.ifBlank { "未找到同一版本" },
                    )
                    return@launch
                }
                TrackSource(
                    providerId = providerId,
                    providerName = providerName(providerId),
                    song = result.song,
                    matchScore = result.score,
                    matchMethod = result.method,
                )
            }

            val updated = if (existing != null) track else TrackMatcher.withSource(track, source)
            if (remember) {
                app.settings.saveAggregation(
                    state.value.aggregation.copy(
                        playbackMode = PlaybackMode.Pinned,
                        pinnedProvider = providerId,
                    )
                )
            }
            pinnedProviders[updated.id] = providerId
            val originalKey = track.sources.firstOrNull()?.song?.key
            val newMatches = if (existing == null && originalKey != null && base.value.collectionSongs.any { it.key == originalKey }) {
                base.value.collectionMatches + (originalKey to SongMatchResult(source.song, source.matchScore, source.matchMethod))
            } else {
                base.value.collectionMatches
            }
            base.value = base.value.copy(
                sourcePickerTrack = null,
                sourcePickerQueue = emptyList(),
                sourcePickerBusy = "",
                aggregateGroups = base.value.aggregateGroups.map { if (it.id == updated.id) updated else it },
                collectionMatches = newMatches,
            )
            playTrack(updated, queue.map { if (it.id == updated.id) updated else it }, providerId)
        }
    }

    fun playAlternative(track: CanonicalTrack, source: TrackSource) {
        val single = TrackMatcher.singleSource(
            source.song,
            source.providerName,
            source.providerId,
            state.value.cookies[source.providerId].orEmpty(),
        )
        pinnedProviders[single.id] = source.providerId
        base.value = base.value.copy(sourcePickerTrack = null, sourcePickerQueue = emptyList(), sourcePickerError = "")
        playTrack(single, listOf(single), source.providerId)
    }

    fun matchCollection(targetProvider: String) {
        val songs = base.value.collectionSongs
        if (songs.isEmpty() || targetProvider.isBlank()) return
        collectionMatchJob?.cancel()
        base.value = base.value.copy(
            collectionMatchTarget = targetProvider,
            collectionMatches = emptyMap(),
            collectionMatchProgress = MatchProgress(targetProvider, songs.size, 0, 0),
        )
        collectionMatchJob = viewModelScope.launch {
            try {
                matchJob.matchMany(songs, targetProvider).collect { progress ->
                    val songKey = progress.currentSong?.key
                    val result = progress.currentResult
                    val matches = if (songKey != null && result != null) {
                        base.value.collectionMatches + (songKey to result)
                    } else {
                        base.value.collectionMatches
                    }
                    base.value = base.value.copy(
                        collectionMatches = matches,
                        collectionMatchProgress = progress,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                base.value = base.value.copy(
                    collectionMatchProgress = null,
                    error = error.message ?: "换源匹配失败",
                )
            }
        }
    }

    fun setMatchTarget(providerId: String) {
        if (base.value.collectionMatchTarget == providerId) return
        base.value = base.value.copy(
            collectionMatchTarget = providerId,
            collectionMatches = emptyMap(),
            collectionMatchProgress = null,
        )
    }

    fun clearMatchCache() {
        searchEngine.clearCache()
        resolver.clearCache()
        base.value = base.value.copy(collectionMatches = emptyMap(), collectionMatchProgress = null)
        viewModelScope.launch(Dispatchers.IO) { runCatching { aggregateDao.clearMatches() } }
    }

    fun clearMediaCache() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            app.mediaCache.keys.toList().forEach { app.mediaCache.removeResource(it) }
        }
        base.value = base.value.copy(playbackNotice = "已清除播放缓存")
    }

    fun splitSource(track: CanonicalTrack, source: TrackSource) {
        viewModelScope.launch {
            val key = "${source.song.key}|${track.id}"
            splitOverrideKeys = splitOverrideKeys + key
            mergeTargets = mergeTargets - source.song.key
            searchEngine.clearCache()
            runCatching {
                aggregateDao.saveOverride(
                    TrackOverrideEntity(
                        sourceKey = source.song.key,
                        canonicalId = track.id,
                        action = "split",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            val remaining = track.copy(
                sources = track.sources.filterNot { it.song.key == source.song.key },
                updatedAt = System.currentTimeMillis(),
            )
            val separatedRaw = TrackMatcher.singleSource(
                source.song,
                source.providerName,
                source.providerId,
                state.value.cookies[source.providerId].orEmpty(),
            )
            val separated = if (remaining.sources.isNotEmpty() && separatedRaw.id == track.id) {
                separatedRaw.copy(id = "${separatedRaw.id}-split-${source.song.key.hashCode()}")
            } else {
                separatedRaw
            }
            val groups = base.value.aggregateGroups.flatMap { group ->
                when {
                    group.id != track.id -> listOf(group)
                    remaining.sources.isEmpty() -> listOf(separated)
                    else -> listOf(remaining, separated)
                }
            }.distinctBy { it.id }
            base.value = base.value.copy(
                aggregateGroups = groups,
                sourcePickerTrack = remaining.sources.takeIf { it.isNotEmpty() }?.let { remaining },
                sourcePickerInfo = if (remaining.sources.isEmpty()) "" else "已拆分，当前分组还剩 ${remaining.sources.size} 个来源",
                sourcePickerError = "",
                playbackNotice = "已将该来源拆分为独立版本",
            )
        }
    }

    fun openMerge(track: CanonicalTrack) {
        base.value = base.value.copy(mergeTrack = track)
    }

    fun closeMerge() {
        base.value = base.value.copy(mergeTrack = null)
    }

    fun mergeTo(target: CanonicalTrack) {
        val source = base.value.mergeTrack ?: return
        if (source.id == target.id) {
            closeMerge()
            return
        }
        val targetKey = target.sources.firstOrNull()?.song?.key ?: return
        viewModelScope.launch {
            source.sources.forEach { item ->
                mergeTargets = mergeTargets + (item.song.key to targetKey)
                splitOverrideKeys = splitOverrideKeys.filterNot { it.startsWith("${item.song.key}|") }.toSet()
                runCatching {
                    aggregateDao.saveOverride(
                        TrackOverrideEntity(
                            sourceKey = item.song.key,
                            canonicalId = "src:$targetKey",
                            action = "merge",
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
            searchEngine.clearCache()
            val merged = source.sources.fold(target) { acc, item -> TrackMatcher.withSource(acc, item) }
                .copy(alternatives = (target.alternatives + source.alternatives).distinctBy { it.song.key })
            val groups = base.value.aggregateGroups
                .filterNot { it.id == source.id }
                .map { if (it.id == target.id) merged else it }
            base.value = base.value.copy(
                aggregateGroups = groups,
                mergeTrack = null,
                sourcePickerTrack = when (base.value.sourcePickerTrack?.id) {
                    source.id, target.id -> merged
                    else -> base.value.sourcePickerTrack
                },
                playbackNotice = "已合并到「${target.title}」",
            )
        }
    }

    fun mergeAlternative(track: CanonicalTrack, source: TrackSource) {
        val targetKey = track.sources.firstOrNull()?.song?.key ?: return
        viewModelScope.launch {
            mergeTargets = mergeTargets + (source.song.key to targetKey)
            splitOverrideKeys = splitOverrideKeys.filterNot { it.startsWith("${source.song.key}|") }.toSet()
            searchEngine.clearCache()
            runCatching {
                aggregateDao.saveOverride(
                    TrackOverrideEntity(
                        sourceKey = source.song.key,
                        canonicalId = "src:$targetKey",
                        action = "merge",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            val updated = TrackMatcher.withSource(track, source).copy(
                alternatives = track.alternatives.filterNot { it.song.key == source.song.key },
            )
            base.value = base.value.copy(
                aggregateGroups = base.value.aggregateGroups.map { if (it.id == track.id) updated else it },
                sourcePickerTrack = if (base.value.sourcePickerTrack?.id == track.id) updated else base.value.sourcePickerTrack,
                sourcePickerInfo = "已合并到当前曲目",
                sourcePickerError = "",
            )
        }
    }

    fun clearOverrides() {
        splitOverrideKeys = emptySet()
        mergeTargets = emptyMap()
        searchEngine.clearCache()
        viewModelScope.launch(Dispatchers.IO) { runCatching { aggregateDao.clearOverrides() } }
    }

    fun toggleFavorite(song: Song) = viewModelScope.launch {
        if (favorites.value.any { it.key == song.key }) app.database.dao().remove("favorite", song.key)
        else remember("favorite", song)
    }

    fun transport(action: String) {
        val player = controller ?: return
        when (action) {
            "play" -> if (player.isPlaying) player.pause() else player.play()
            "next" -> player.seekToNextMediaItem()
            "previous" -> player.seekToPreviousMediaItem()
            "shuffle" -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            "repeat" -> player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun seek(position: Long) { controller?.seekTo(position) }

    fun openDeepLink() {
        val link = base.value.deepLink
        if (link.isBlank()) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        }
        base.value = base.value.copy(deepLink = "")
    }

    fun download(song: Song) = viewModelScope.launch {
        val url = song.url.ifBlank { runCatching { app.repository.downloadUrl(song, cookieFor(song.source)) }.getOrDefault("") }
        if (url.isBlank()) {
            fail("没有可下载的地址")
            return@launch
        }
        val appContext = getApplication<Application>()
        val directory = appContext.getExternalFilesDir("music") ?: appContext.filesDir
        val safeName = "${song.name}-${song.artist}[${song.source}].${song.ext.ifBlank { "mp3" }}"
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val file = File(directory, safeName)
        app.database.dao().saveDownload(DownloadRecord(song.key, json.encodeToString(song), file.absolutePath, "下载中", System.currentTimeMillis()))
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) error(response.message)
                file.outputStream().use { output -> response.body.byteStream().copyTo(output) }
            }
        }
        app.database.dao().saveDownload(DownloadRecord(song.key, json.encodeToString(song), file.absolutePath, "已完成", System.currentTimeMillis()))
    }

    fun deleteDownload(record: DownloadRecord) = viewModelScope.launch { File(record.path).delete(); app.database.dao().deleteDownload(record.key) }
    fun createPlaylist(name: String) = viewModelScope.launch { if (name.isNotBlank()) app.database.dao().insertPlaylist(LocalPlaylist(name = name.trim(), songs = "[]")) }
    fun addToPlaylist(id: Long, song: Song) = viewModelScope.launch {
        val playlist = localPlaylists.value.find { it.id == id } ?: return@launch
        val songs = json.decodeFromString<List<Song>>(playlist.songs).toMutableList()
        if (songs.none { it.key == song.key }) songs += song
        app.database.dao().updatePlaylist(id, json.encodeToString(songs))
    }
    fun deletePlaylist(id: Long) = viewModelScope.launch { app.database.dao().deletePlaylist(id) }
    fun openLocalPlaylist(playlist: LocalPlaylist) {
        base.value = base.value.copy(
            collectionTitle = playlist.name,
            collectionSongs = json.decodeFromString(playlist.songs),
            collectionSource = "local",
            collectionMatches = emptyMap(),
            collectionMatchProgress = null,
            collectionMatchTarget = defaultMatchTarget("local"),
        )
    }

    fun toggleAggregateProvider(id: String) = viewModelScope.launch {
        val current = state.value.aggregation
        val enabled = current.enabledProviders.toMutableList()
        if (id in enabled) enabled.remove(id) else enabled.add(id)
        searchEngine.clearCache()
        app.settings.saveAggregation(current.copy(enabledProviders = enabled))
    }

    fun setPlaybackMode(mode: PlaybackMode) = viewModelScope.launch {
        app.settings.saveAggregation(state.value.aggregation.copy(playbackMode = mode))
    }

    fun setPinnedProvider(id: String?) = viewModelScope.launch {
        app.settings.saveAggregation(
            state.value.aggregation.copy(
                pinnedProvider = id,
                playbackMode = if (id == null) PlaybackMode.Auto else PlaybackMode.Pinned,
            )
        )
    }

    fun setAllowPreview(value: Boolean) = viewModelScope.launch {
        searchEngine.clearCache()
        app.settings.saveAggregation(state.value.aggregation.copy(allowPreview = value))
    }

    fun setMatchStrictness(value: MatchStrictness) = viewModelScope.launch {
        searchEngine.clearCache()
        app.settings.saveAggregation(state.value.aggregation.copy(matchStrictness = value))
    }

    fun moveProviderPriority(id: String, delta: Int) = viewModelScope.launch {
        val available = state.value.providers.filter { it.search }.map { it.id }
        val current = state.value.aggregation
        val list = current.priorityFor(available).toMutableList()
        val index = list.indexOf(id)
        if (index < 0) return@launch
        val target = (index + delta).coerceIn(0, list.lastIndex)
        if (target == index) return@launch
        val item = list.removeAt(index)
        list.add(target, item)
        searchEngine.clearCache()
        app.settings.saveAggregation(current.copy(providerPriority = list))
    }

    fun startLogin(type: String = "qq") {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            val created = app.repository.createLogin(state.value.providerId, type)
            base.value = base.value.copy(login = created, loginStatus = "等待扫码")
            repeat(40) {
                delay(3000)
                val result = app.repository.checkLogin(created.sessionId, created.session.key)
                base.value = base.value.copy(loginStatus = result.message.ifBlank { result.status })
                if (result.status == "success" && result.cookie.isNotBlank()) {
                    app.settings.cookie(state.value.providerId, result.cookie)
                    base.value = base.value.copy(login = null)
                    return@launch
                }
                if (result.status == "expired" || result.status == "failed") return@launch
            }
        }
    }

    fun saveCookie(cookie: String) = viewModelScope.launch {
        resolver.clearCache()
        searchEngine.clearCache()
        app.settings.cookie(state.value.providerId, cookie)
        base.value = base.value.copy(login = null)
    }
    fun logout() = viewModelScope.launch {
        resolver.clearCache()
        searchEngine.clearCache()
        app.settings.cookie(state.value.providerId, "")
    }

    private fun loadLyrics(song: Song) = viewModelScope.launch {
        base.value = base.value.copy(lyrics = runCatching { app.repository.lyrics(song, cookieFor(song.source)) }.getOrDefault(""))
    }

    private fun canonicalForSong(song: Song): CanonicalTrack {
        val providerId = song.source.ifBlank { state.value.providerId }
        val track = TrackMatcher.singleSource(song, providerName(providerId), providerId, state.value.cookies[providerId].orEmpty())
        val match = state.value.collectionMatches[song.key]?.song ?: return track
        val targetId = match.source.ifBlank { state.value.collectionMatchTarget }
        if (targetId.isBlank() || targetId == providerId) return track
        val score = state.value.collectionMatches[song.key]?.score ?: 1f
        val method = state.value.collectionMatches[song.key]?.method ?: MatchMethod.Meta
        return TrackMatcher.withSource(
            track,
            TrackSource(targetId, providerName(targetId), match, score, method),
        )
    }

    private fun preferredSong(track: CanonicalTrack): Song {
        localFileFor(track)?.let { (source, file) ->
            return source.song.copy(url = Uri.fromFile(file).toString())
        }
        val policy = resolver.buildPolicy(track, null)
        val source = resolver.orderCandidates(track, null, emptySet(), policy).firstOrNull()
        return source?.song ?: track.sources.first().song
    }

    private fun localFileFor(track: CanonicalTrack): Pair<TrackSource, File>? {
        val records = downloads.value
        if (records.isEmpty()) return null
        for (source in track.sources) {
            val record = records.firstOrNull { it.key == source.song.key && it.state == "已完成" } ?: continue
            val file = File(record.path)
            if (file.exists() && file.length() > 0) return source to file
        }
        return null
    }

    private fun historySong(song: Song): Song =
        if (song.url.startsWith("file:")) song.copy(url = "") else song

    private fun noSourceMessage(track: CanonicalTrack, resolution: Resolution): String {
        val reasons = (resolution as? Resolution.None)?.attempts?.map { it.reason }.orEmpty()
        return when {
            reasons.any { it.contains("登录") || it.contains("会员") } -> "这首歌需要登录或会员后才能播放"
            reasons.any { it.contains("试听") } -> "只有试听片段可用，请在设置中允许试听"
            track.sources.any { it.song.isVip } -> "这首歌需要会员或登录后才能播放"
            track.sources.any { it.isPreview } -> "只有试听片段可用，请在设置中允许试听"
            else -> "暂无可用播放源，可在平台 App 中打开"
        }
    }

    private fun defaultMatchTarget(source: String): String {
        val enabled = enabledProviders().map { it.id }
        return enabled.firstOrNull { it != source } ?: enabled.firstOrNull().orEmpty()
    }

    private fun providerName(id: String): String = state.value.providers.find { it.id == id }?.name ?: id

    private fun rememberCanonical(track: CanonicalTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                aggregateDao.saveTrack(
                    CanonicalTrackEntity(
                        id = track.id,
                        title = track.title,
                        artists = track.artists.joinToString(" / "),
                        album = track.album,
                        durationSec = track.durationSec,
                        version = track.version.name,
                        cover = track.cover,
                        confidence = track.confidence,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                aggregateDao.saveSources(
                    track.sources.map { source ->
                        CanonicalSourceEntity(
                            sourceKey = source.song.key,
                            canonicalId = track.id,
                            providerId = source.providerId,
                            payload = json.encodeToString(source.song),
                            matchScore = source.matchScore,
                            matchMethod = source.matchMethod.name,
                            availability = availabilityOf(source, state.value.cookies[source.providerId].orEmpty()).name,
                            isPreview = source.isPreview,
                            checkedAt = System.currentTimeMillis(),
                        )
                    }
                )
            }
        }
    }

    private fun launchAction(block: suspend () -> Unit) = viewModelScope.launch {
        base.value = base.value.copy(loading = true, error = "")
        runCatching { block() }.onFailure { fail(it.message ?: "请求失败") }
        base.value = base.value.copy(loading = false)
    }

    private fun fail(message: String) { base.value = base.value.copy(error = message, loading = false) }
    private fun cookieFor(providerId: String) = state.value.cookies[providerId.ifBlank { state.value.providerId }].orEmpty()
    private suspend fun remember(bucket: String, song: Song) = app.database.dao().save(SavedSong(song.key, bucket, json.encodeToString(song), System.currentTimeMillis()))

    private fun mediaItem(song: Song) = MediaItem.Builder().setMediaId(song.key).setUri(song.url).setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.cover.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .build()
    ).build()

    private fun formatPosition(position: Long): String =
        String.format(Locale.ROOT, "%02d:%02d", position / 60_000, (position / 1000) % 60)

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val index = controller?.currentMediaItemIndex ?: return
            val song = base.value.queue.getOrNull(index) ?: return
            val track = base.value.canonicalQueue.getOrNull(index)
            val source = track?.sources?.find { it.song.key == song.key }
            base.value = base.value.copy(currentTrack = track, currentSource = source)
            if (song.url.isNotBlank()) {
                loadLyrics(song)
                return
            }
            if (track == null) return
            viewModelScope.launch {
                val local = localFileFor(track)
                if (local != null) {
                    val resolved = local.first.song.copy(url = Uri.fromFile(local.second).toString())
                    controller?.replaceMediaItem(index, mediaItem(resolved))
                    base.value = base.value.copy(
                        queue = base.value.queue.mapIndexed { i, queued -> if (i == index) resolved else queued },
                        current = resolved,
                        currentSource = local.first,
                        playbackNotice = "",
                    )
                    remember("history", historySong(resolved))
                    loadLyrics(resolved)
                    return@launch
                }
                val pinned = pinnedProviders[track.id]
                val resolution = resolver.resolve(track, pinned, emptySet(), resolver.buildPolicy(track, pinned))
                if (resolution !is Resolution.Ready) {
                    base.value = base.value.copy(playbackNotice = noSourceMessage(track, resolution))
                    return@launch
                }
                val resolved = resolution.source.song.copy(url = resolution.url)
                val currentIndex = controller?.currentMediaItemIndex ?: return@launch
                if (currentIndex != index) return@launch
                controller?.replaceMediaItem(index, mediaItem(resolved))
                base.value = base.value.copy(
                    queue = base.value.queue.mapIndexed { i, queued -> if (i == index) resolved else queued },
                    current = resolved,
                    currentSource = resolution.source,
                    playbackNotice = if (resolution.isPreview) "当前为试听片段" else "",
                )
                remember("history", historySong(resolved))
                loadLyrics(resolved)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (failoverInProgress) return
            val index = controller?.currentMediaItemIndex ?: return
            val track = base.value.canonicalQueue.getOrNull(index) ?: return
            val failedSource = base.value.currentSource ?: track.sources.find { it.song.key == base.value.current?.key }
            val position = controller?.currentPosition ?: 0L
            failoverInProgress = true
            viewModelScope.launch {
                val local = localFileFor(track)
                if (local != null && !base.value.current?.url.orEmpty().startsWith("file:")) {
                    val player = controller
                    if (player != null) {
                        val resolved = local.first.song.copy(url = Uri.fromFile(local.second).toString())
                        player.replaceMediaItem(index, mediaItem(resolved))
                        player.seekTo(position)
                        player.play()
                        base.value = base.value.copy(
                            queue = base.value.queue.mapIndexed { i, queued -> if (i == index) resolved else queued },
                            current = resolved,
                            currentSource = local.first,
                            playbackNotice = "",
                        )
                        loadLyrics(resolved)
                        failoverInProgress = false
                        return@launch
                    }
                }
                failedSource?.let { resolver.invalidate(it) }
                val exclude = setOfNotNull(failedSource?.providerId)
                val resolution = resolver.resolve(track, null, exclude, resolver.buildPolicy(track, null))
                if (resolution is Resolution.Ready) {
                    val player = controller
                    if (player == null) {
                        failoverInProgress = false
                        return@launch
                    }
                    val resolved = resolution.source.song.copy(url = resolution.url)
                    player.replaceMediaItem(index, mediaItem(resolved))
                    player.seekTo(position)
                    player.play()
                    if (pinnedProviders[track.id] == failedSource?.providerId) {
                        pinnedProviders[track.id] = resolution.source.providerId
                    }
                    base.value = base.value.copy(
                        queue = base.value.queue.mapIndexed { i, queued -> if (i == index) resolved else queued },
                        current = resolved,
                        currentSource = resolution.source,
                        playbackNotice = "当前音源中断，已切换到${resolution.source.providerName}，并从 ${formatPosition(position)} 继续播放",
                    )
                    loadLyrics(resolved)
                } else {
                    controller?.pause()
                    base.value = base.value.copy(playbackNotice = noSourceMessage(track, resolution))
                }
                failoverInProgress = false
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val song = base.value.queue.getOrNull(player.currentMediaItemIndex)
            base.value = base.value.copy(
                current = song ?: base.value.current,
                playing = player.isPlaying,
                duration = player.duration.coerceAtLeast(0),
                shuffle = player.shuffleModeEnabled,
                repeat = when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.One
                    Player.REPEAT_MODE_ALL -> RepeatMode.All
                    else -> RepeatMode.Off
                },
            )
        }
    }

    private fun startProgress() {
        progressJob = viewModelScope.launch {
            while (true) {
                controller?.let {
                    base.value = base.value.copy(
                        position = it.currentPosition.coerceAtLeast(0),
                        duration = it.duration.coerceAtLeast(0),
                    )
                }
                delay(500)
            }
        }
    }

    override fun onCleared() {
        progressJob?.cancel()
        aggregateSearchJob?.cancel()
        collectionSearchJob?.cancel()
        collectionMatchJob?.cancel()
        controller?.release()
        super.onCleared()
    }
}

fun SavedSong.song(json: Json = Json { ignoreUnknownKeys = true }) = json.decodeFromString<Song>(payload)
fun DownloadRecord.song(json: Json = Json { ignoreUnknownKeys = true }) = json.decodeFromString<Song>(payload)
