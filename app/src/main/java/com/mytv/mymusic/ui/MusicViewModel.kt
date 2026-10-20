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
import com.mytv.mymusic.aggregate.MatchStrictness
import com.mytv.mymusic.aggregate.PlaybackMode
import com.mytv.mymusic.aggregate.ProviderSearchState
import com.mytv.mymusic.aggregate.ProviderStatus
import com.mytv.mymusic.aggregate.Resolution
import com.mytv.mymusic.aggregate.SearchScope
import com.mytv.mymusic.aggregate.SearchStatus
import com.mytv.mymusic.aggregate.SearchUpdate
import com.mytv.mymusic.aggregate.SourceResolver
import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.aggregate.availabilityOf
import com.mytv.mymusic.data.AppSettings
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    val loading: Boolean = false,
    val error: String = "",
)

data class MusicUiState(
    val providers: List<ProviderInfo> = emptyList(),
    val providerId: String = "netease",
    val theme: String = "system",
    val cookies: Map<String, String> = emptyMap(),
    val home: ProviderHome? = null,
    val recommendations: List<Playlist> = emptyList(),
    val categories: List<PlaylistCategory> = emptyList(),
    val categoriesLoading: Boolean = false,
    val categoriesError: String = "",
    val categoryId: String = "",
    val categoryName: String = "",
    val categoryOpen: Boolean = false,
    val categoryPlaylists: List<Playlist> = emptyList(),
    val charts: List<TopList> = emptyList(),
    val hotSongs: List<Song> = emptyList(),
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val albums: List<Playlist> = emptyList(),
    val collectionTitle: String = "",
    val collectionSongs: List<Song> = emptyList(),
    val collectionOpen: Boolean = false,
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
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MyMusicApp
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient()
    private val base = MutableStateFlow(MusicUiState())
    private val settingsState = app.settings.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(provider = "netease", theme = "system", cookies = emptyMap()),
    )
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var loginJob: Job? = null
    private var aggregateSearchJob: Job? = null
    private var aggregateSearchGeneration = 0
    private var collectionSearchJob: Job? = null
    private var collectionSearchGeneration = 0
    private var homeGeneration = 0
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
        cookies = { settingsState.value.cookies },
        settings = { settingsState.value.aggregation },
        splitOverrides = { splitOverrideKeys },
        mergeOverrides = { mergeTargets },
    )
    private val resolver = SourceResolver(
        repository = app.repository,
        cookies = { settingsState.value.cookies },
        settings = { settingsState.value.aggregation },
    )
    private val matchJob = MatchJob(
        repository = app.repository,
        cookies = { settingsState.value.cookies },
        dao = aggregateDao,
        settings = { settingsState.value.aggregation },
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
            val providerId = runCatching { app.settings.state.first().provider }.getOrDefault("netease")
            refreshHome(providerId)
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
        base.value = base.value.copy(
            page = 1,
            home = null,
            categories = emptyList(),
            categoriesLoading = false,
            categoriesError = "",
            categoryId = "",
            categoryName = "",
            categoryOpen = false,
            categoryPlaylists = emptyList(),
        )
        refreshHome(id)
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

    fun refreshHome(providerId: String? = null) = launchAction {
        val generation = ++homeGeneration
        val providers = base.value.providers
        val id = providerId ?: settingsState.value.provider
        val provider = providers.find { it.id == id } ?: providers.firstOrNull() ?: return@launchAction
        base.value = base.value.copy(
            page = 1,
            home = ProviderHome(provider = provider, loading = true),
            categories = emptyList(),
            categoriesLoading = provider.playlistCategories,
            categoriesError = "",
            categoryId = "",
            categoryName = "",
            categoryOpen = false,
            categoryPlaylists = emptyList(),
        )
        val cookie = cookieFor(provider.id)
        val (recommended, charts, hot) = coroutineScope {
            val recommendedJob = async {
                if (provider.recommendedPlaylists) {
                    runCatching { app.repository.recommended(provider.id, cookie) }
                } else {
                    Result.success(emptyList())
                }
            }
            val chartsJob = async {
                if (provider.topLists) {
                    runCatching { app.repository.topLists(provider.id, cookie) }
                } else {
                    Result.success(emptyList())
                }
            }
            val hotJob = async {
                if (provider.hotSongs) {
                    runCatching { app.repository.hotSongs(provider.id, cookie, 12) }
                } else {
                    Result.success(emptyList())
                }
            }
            Triple(recommendedJob.await(), chartsJob.await(), hotJob.await())
        }
        if (generation != homeGeneration) return@launchAction
        val error = listOfNotNull(recommended.exceptionOrNull(), charts.exceptionOrNull(), hot.exceptionOrNull())
            .firstOrNull()?.message.orEmpty()
        base.update {
            it.copy(
                home = ProviderHome(
                    provider = provider,
                    recommendations = recommended.getOrDefault(emptyList()),
                    charts = charts.getOrDefault(emptyList()),
                    hotSongs = hot.getOrDefault(emptyList()),
                    error = error,
                ),
            )
        }
        loadProviderDetails(provider.id, generation)
    }

    private suspend fun loadProviderDetails(providerId: String? = null, generation: Int = homeGeneration) {
        val id = providerId ?: settingsState.value.provider
        val provider = base.value.providers.find { it.id == id } ?: return
        if (!provider.playlistCategories) {
            if (generation == homeGeneration) {
                base.value = base.value.copy(categories = emptyList(), categoriesLoading = false, categoriesError = "")
            }
            return
        }
        val result = runCatching { app.repository.categories(id, cookieFor(id)) }
        if (generation != homeGeneration) return
        base.value = base.value.copy(
            categories = result.getOrDefault(emptyList()),
            categoriesLoading = false,
            categoriesError = result.exceptionOrNull()?.message.orEmpty(),
        )
    }

    fun loadCategory(categoryId: String, page: Int = 1) = launchAction {
        val generation = homeGeneration
        val providerId = base.value.home?.provider?.id ?: settingsState.value.provider
        val categoryName = base.value.categories.find { it.id == categoryId }?.name.orEmpty()
        val lists = app.repository.categoryPlaylists(providerId, cookieFor(providerId), categoryId, page.coerceAtLeast(1), 30)
        if (generation != homeGeneration) return@launchAction
        base.value = base.value.copy(
            categoryId = categoryId,
            categoryName = categoryName,
            categoryOpen = true,
            categoryPlaylists = lists,
            page = page.coerceAtLeast(1),
        )
    }

    fun clearCategory() {
        base.value = base.value.copy(categoryId = "", categoryName = "", categoryOpen = false, categoryPlaylists = emptyList(), page = 1)
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
            val providerId = settingsState.value.provider
            when (kind) {
                SearchKind.Song -> base.value = base.value.copy(songs = app.repository.searchSongs(providerId, cookieFor(providerId), value))
                SearchKind.Playlist -> base.value = base.value.copy(playlists = app.repository.searchPlaylists(providerId, cookieFor(providerId), value))
                SearchKind.Album -> base.value = base.value.copy(albums = app.repository.searchAlbums(providerId, cookieFor(providerId), value))
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
            val priority = settingsState.value.aggregation.priorityFor(providers.map { it.id })
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
        val all = base.value.providers.filter { it.search }
        if (all.isEmpty()) return emptyList()
        val ids = settingsState.value.aggregation.enabledOrDefault(all.map { it.id })
        return all.filter { it.id in ids }.ifEmpty { all.take(5) }
    }

    fun openPlaylist(item: Playlist, album: Boolean) = launchAction {
        val source = item.source.ifBlank { settingsState.value.provider }
        base.value = base.value.copy(
            collectionTitle = item.name,
            collectionSongs = emptyList(),
            collectionSource = source,
            collectionOpen = true,
        )
        val songs = if (album) app.repository.albumSongs(source, cookieFor(source), item.id)
        else app.repository.playlistSongs(source, cookieFor(source), item.id)
        base.value = base.value.copy(collectionSongs = songs)
    }

    fun openChart(item: TopList) = launchAction {
        val source = item.source.ifBlank { settingsState.value.provider }
        base.value = base.value.copy(
            collectionTitle = item.name,
            collectionSongs = emptyList(),
            collectionSource = source,
            collectionOpen = true,
        )
        base.value = base.value.copy(
            collectionSongs = app.repository.topListSongs(source, cookieFor(source), item.id),
        )
    }

    fun closeCollection() {
        base.value = base.value.copy(collectionOpen = false)
    }

    fun openLink(link: String) = launchAction {
        val id = settingsState.value.provider
        val result = runCatching { app.repository.parsePlaylist(id, cookieFor(id), link) }.getOrNull()
            ?: runCatching { app.repository.parseAlbum(id, cookieFor(id), link) }.getOrNull()
        if (result != null) {
            val source = result.item.source.ifBlank { id }
            base.value = base.value.copy(
                collectionTitle = result.item.name,
                collectionSongs = result.songs,
                collectionSource = source,
                collectionOpen = true,
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
        playTrack(selected, ordered)
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
            var playableTrack = track
            var ready: Resolution.Ready? = null
            var autoMatched = false
            if (local == null) {
                val result = resolvePlayable(track, pinnedProvider)
                playableTrack = result.track
                ready = result.resolution as? Resolution.Ready
                autoMatched = result.autoMatched
                if (ready == null) {
                    base.value = base.value.copy(
                        playbackNotice = noSourceMessage(playableTrack, result.resolution),
                        deepLink = (result.resolution as? Resolution.None)?.deepLink.orEmpty(),
                    )
                    return@launch
                }
            }
            if (pinnedProvider != null) pinnedProviders[playableTrack.id] = pinnedProvider
            val source = local?.first ?: ready!!.source
            val resolvedSong = if (local != null) {
                source.song.copy(url = Uri.fromFile(local.second).toString())
            } else {
                source.song.copy(url = ready!!.url)
            }
            val canonicalQueue = ordered.map { if (it.id == track.id) playableTrack else it }
            val queueSongs = canonicalQueue.map { item ->
                if (item.id == playableTrack.id) resolvedSong else preferredSong(item)
            }
            val fallbackNotice = when {
                local != null -> ""
                ready!!.isPreview -> "当前为试听片段"
                autoMatched -> "原音源不可用，已自动匹配到${ready.source.providerName}"
                pinnedProvider != null && ready.source.providerId != pinnedProvider ->
                    "所选平台没有返回可播放地址，已切换到${ready.source.providerName}"
                else -> ""
            }
            player.setMediaItems(queueSongs.map(::mediaItem), 0, 0)
            player.prepare()
            player.play()
            base.value = base.value.copy(
                queue = queueSongs,
                canonicalQueue = canonicalQueue,
                current = resolvedSong,
                currentTrack = playableTrack,
                currentSource = source,
                playerOpen = true,
                playbackNotice = fallbackNotice,
                error = "",
                deepLink = "",
            )
            rememberCanonical(playableTrack)
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
                    settingsState.value.aggregation.copy(
                        playbackMode = PlaybackMode.Pinned,
                        pinnedProvider = providerId,
                    )
                )
            }
            pinnedProviders[updated.id] = providerId
            base.value = base.value.copy(
                sourcePickerTrack = null,
                sourcePickerQueue = emptyList(),
                sourcePickerBusy = "",
                aggregateGroups = base.value.aggregateGroups.map { if (it.id == updated.id) updated else it },
            )
            playTrack(updated, queue.map { if (it.id == updated.id) updated else it }, providerId)
        }
    }

    fun playAlternative(track: CanonicalTrack, source: TrackSource) {
        val single = TrackMatcher.singleSource(
            source.song,
            source.providerName,
            source.providerId,
            cookieFor(source.providerId),
        )
        pinnedProviders[single.id] = source.providerId
        base.value = base.value.copy(sourcePickerTrack = null, sourcePickerQueue = emptyList(), sourcePickerError = "")
        playTrack(single, listOf(single), source.providerId)
    }

    fun clearMatchCache() {
        searchEngine.clearCache()
        resolver.clearCache()
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
                cookieFor(source.providerId),
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
            collectionOpen = true,
        )
    }

    fun toggleAggregateProvider(id: String) = viewModelScope.launch {
        val current = settingsState.value.aggregation
        val enabled = current.enabledProviders.toMutableList()
        if (id in enabled) enabled.remove(id) else enabled.add(id)
        searchEngine.clearCache()
        app.settings.saveAggregation(current.copy(enabledProviders = enabled))
    }

    fun setPlaybackMode(mode: PlaybackMode) = viewModelScope.launch {
        app.settings.saveAggregation(settingsState.value.aggregation.copy(playbackMode = mode))
    }

    fun setPinnedProvider(id: String?) = viewModelScope.launch {
        app.settings.saveAggregation(
            settingsState.value.aggregation.copy(
                pinnedProvider = id,
                playbackMode = if (id == null) PlaybackMode.Auto else PlaybackMode.Pinned,
            )
        )
    }

    fun setAllowPreview(value: Boolean) = viewModelScope.launch {
        searchEngine.clearCache()
        app.settings.saveAggregation(settingsState.value.aggregation.copy(allowPreview = value))
    }

    fun setMatchStrictness(value: MatchStrictness) = viewModelScope.launch {
        searchEngine.clearCache()
        app.settings.saveAggregation(settingsState.value.aggregation.copy(matchStrictness = value))
    }

    fun moveProviderPriority(id: String, delta: Int) = viewModelScope.launch {
        val available = base.value.providers.filter { it.search }.map { it.id }
        val current = settingsState.value.aggregation
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
            val created = app.repository.createLogin(settingsState.value.provider, type)
            base.value = base.value.copy(login = created, loginStatus = "等待扫码")
            repeat(40) {
                delay(3000)
                val result = app.repository.checkLogin(created.sessionId, created.session.key)
                base.value = base.value.copy(loginStatus = result.message.ifBlank { result.status })
                if (result.status == "success" && result.cookie.isNotBlank()) {
                    app.settings.cookie(settingsState.value.provider, result.cookie)
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
        app.settings.cookie(settingsState.value.provider, cookie)
        base.value = base.value.copy(login = null)
    }
    fun logout() = viewModelScope.launch {
        resolver.clearCache()
        searchEngine.clearCache()
        app.settings.cookie(settingsState.value.provider, "")
    }

    private fun loadLyrics(song: Song) = viewModelScope.launch {
        base.value = base.value.copy(lyrics = runCatching { app.repository.lyrics(song, cookieFor(song.source)) }.getOrDefault(""))
    }

    private fun canonicalForSong(song: Song): CanonicalTrack {
        val providerId = song.source.ifBlank { settingsState.value.provider }
        return TrackMatcher.singleSource(song, providerName(providerId), providerId, cookieFor(providerId))
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
        val attempts = (resolution as? Resolution.None)?.attempts.orEmpty()
        val reasons = attempts.map { it.reason }
        val tried = attempts.map { it.source.providerName }.distinct()
        return when {
            reasons.any { it.contains("登录") || it.contains("会员") } -> "这首歌需要登录或会员后才能播放"
            reasons.any { it.contains("试听") } -> "只有试听片段可用，请在设置中允许试听"
            track.sources.any { it.song.isVip } -> "这首歌需要会员或登录后才能播放"
            track.sources.any { it.isPreview } -> "只有试听片段可用，请在设置中允许试听"
            tried.isNotEmpty() -> "已尝试${tried.joinToString("、")}，都没有返回可播放地址"
            else -> "暂无可用播放源"
        }
    }

    private data class AutoResolution(
        val track: CanonicalTrack,
        val resolution: Resolution,
        val autoMatched: Boolean,
    )

    private suspend fun resolvePlayable(
        track: CanonicalTrack,
        pinnedProvider: String? = null,
        excludeProviders: Set<String> = emptySet(),
    ): AutoResolution {
        val initial = resolver.resolve(
            track,
            pinnedProvider,
            excludeProviders,
            resolver.buildPolicy(track, pinnedProvider),
        )
        if (initial is Resolution.Ready) return AutoResolution(track, initial, autoMatched = false)

        val original = track.sources.firstOrNull()?.song
            ?: return AutoResolution(track, initial, autoMatched = false)
        val attempted = (track.sources.map { it.providerId } + excludeProviders).toMutableSet()
        var current = track
        var last = initial
        for (providerId in autoMatchProviders(attempted)) {
            val result = withTimeoutOrNull(AUTO_MATCH_TIMEOUT_MS) {
                runCatching { matchJob.matchOne(original, providerId) }.getOrNull()
            } ?: continue
            val song = result.song ?: continue
            current = TrackMatcher.withSource(
                current,
                TrackSource(
                    providerId = providerId,
                    providerName = providerName(providerId),
                    song = song,
                    matchScore = result.score,
                    matchMethod = result.method,
                ),
            )
            val resolution = resolver.resolve(
                current,
                pinnedProvider,
                excludeProviders,
                resolver.buildPolicy(current, pinnedProvider),
            )
            if (resolution is Resolution.Ready) {
                return AutoResolution(current, resolution, autoMatched = true)
            }
            last = resolution
        }
        return AutoResolution(current, last, autoMatched = false)
    }

    private fun autoMatchProviders(exclude: Set<String>): List<String> {
        val searchable = base.value.providers.filter { it.search }.map { it.id }
        if (searchable.isEmpty()) return emptyList()
        val aggregation = settingsState.value.aggregation
        val enabled = aggregation.enabledOrDefault(searchable).filter { it in searchable }
        return aggregation.priorityFor(enabled).filterNot { it in exclude }
    }

    private fun providerName(id: String): String = base.value.providers.find { it.id == id }?.name ?: id

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
                            availability = availabilityOf(source, cookieFor(source.providerId)).name,
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
    private fun cookieFor(providerId: String) = settingsState.value.cookies[providerId.ifBlank { settingsState.value.provider }].orEmpty()
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
                val result = resolvePlayable(track, pinned)
                val resolution = result.resolution as? Resolution.Ready
                if (resolution == null) {
                    base.value = base.value.copy(playbackNotice = noSourceMessage(result.track, result.resolution))
                    return@launch
                }
                val resolved = resolution.source.song.copy(url = resolution.url)
                val currentIndex = controller?.currentMediaItemIndex ?: return@launch
                if (currentIndex != index) return@launch
                controller?.replaceMediaItem(index, mediaItem(resolved))
                base.value = base.value.copy(
                    queue = base.value.queue.mapIndexed { i, queued -> if (i == index) resolved else queued },
                    canonicalQueue = base.value.canonicalQueue.mapIndexed { i, queued -> if (i == index) result.track else queued },
                    current = resolved,
                    currentTrack = result.track,
                    currentSource = resolution.source,
                    playbackNotice = when {
                        result.autoMatched -> "原音源不可用，已自动匹配到${resolution.source.providerName}"
                        resolution.isPreview -> "当前为试听片段"
                        else -> ""
                    },
                )
                rememberCanonical(result.track)
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
                val result = resolvePlayable(track, null, exclude)
                val resolution = result.resolution as? Resolution.Ready
                if (resolution != null) {
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
                        canonicalQueue = base.value.canonicalQueue.mapIndexed { i, queued -> if (i == index) result.track else queued },
                        current = resolved,
                        currentTrack = result.track,
                        currentSource = resolution.source,
                        playbackNotice = if (result.autoMatched) {
                            "当前音源中断，已自动匹配到${resolution.source.providerName}，并从 ${formatPosition(position)} 继续播放"
                        } else {
                            "当前音源中断，已切换到${resolution.source.providerName}，并从 ${formatPosition(position)} 继续播放"
                        },
                    )
                    rememberCanonical(result.track)
                    loadLyrics(resolved)
                } else {
                    controller?.pause()
                    base.value = base.value.copy(playbackNotice = noSourceMessage(result.track, result.resolution))
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
        controller?.release()
        super.onCleared()
    }
}

private const val AUTO_MATCH_TIMEOUT_MS = 8_000L

fun SavedSong.song(json: Json = Json { ignoreUnknownKeys = true }) = json.decodeFromString<Song>(payload)
fun DownloadRecord.song(json: Json = Json { ignoreUnknownKeys = true }) = json.decodeFromString<Song>(payload)
