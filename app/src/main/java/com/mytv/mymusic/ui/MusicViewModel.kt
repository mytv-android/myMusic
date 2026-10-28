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
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.data.LyricsBackgroundMode
import com.mytv.mymusic.data.RhythmIntensity
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.QRCreateResult
import com.mytv.mymusic.data.RepeatMode
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.SavedSongTombstone
import com.mytv.mymusic.data.SearchKind
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import com.mytv.mymusic.data.TrackOverrideEntity
import com.mytv.mymusic.data.native.SodaProvider
import com.mytv.mymusic.data.sync.SyncStrategy
import com.mytv.mymusic.playback.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import coil3.SingletonImageLoader
import com.mytv.mymusic.ui.theme.Appearance
import com.mytv.mymusic.ui.theme.BlurMode
import com.mytv.mymusic.ui.theme.ColorSource
import com.mytv.mymusic.ui.theme.CoverSeedRepository
import com.mytv.mymusic.ui.theme.FontScale
import com.mytv.mymusic.ui.theme.LayoutMode
import com.mytv.mymusic.ui.theme.SchemeStyle
import com.mytv.mymusic.ui.theme.isTelevision
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class MusicUiState(
    val providers: List<ProviderInfo> = emptyList(),
    val providerId: String = "netease",
    val chartsSource: String = "",
    val playlistsSource: String = "",
    val theme: String = "system",
    val appearance: Appearance = Appearance(),
    val lyricsBackground: LyricsBackgroundMode = LyricsBackgroundMode.Ambient,
    val rhythmIntensity: RhythmIntensity = RhythmIntensity.Standard,
    val coverShape: CoverShapeChoice = CoverShapeChoice.Random,
    val tvAmbientClock: Boolean = true,
    val coverSeed: Int = 0,
    val sleepFade: Boolean = true,
    val cacheLimitMb: Int = 512,
    val reduceMotion: Boolean = false,
    val showBottomNav: Boolean = true,
    val cookies: Map<String, String> = emptyMap(),
    val sodaSignerUrl: String = "",
    val sodaSignerToken: String = "",
    val aggregation: AggregationSettings = AggregationSettings(),
    val searchHistory: List<String> = emptyList(),
    val skippedUpdateCode: Long = 0L,
    val loading: Boolean = false,
    val error: String = "",
    val current: Song? = null,
    val currentTrack: CanonicalTrack? = null,
    val currentSource: TrackSource? = null,
    val playing: Boolean = false,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val queue: List<Song> = emptyList(),
    val canonicalQueue: List<CanonicalTrack> = emptyList(),
    val lyrics: String = "",
    val playerOpen: Boolean = false,
    val login: QRCreateResult? = null,
    val loginStatus: String = "",
    val loginProviderId: String = "",
    val accountProfiles: Map<String, AccountProfile> = emptyMap(),
    val sourcePickerTrack: CanonicalTrack? = null,
    val sourcePickerQueue: List<CanonicalTrack> = emptyList(),
    val sourcePickerBusy: String = "",
    val sourcePickerError: String = "",
    val sourcePickerInfo: String = "",
    val mergeTrack: CanonicalTrack? = null,
    val playbackNotice: String = "",
    val deepLink: String = "",
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MyMusicApp
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient()
    private val base = MutableStateFlow(MusicUiState())
    private val settingsState = app.settings.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var loginJob: Job? = null
    private var aggregateSearchJob: Job? = null
    private var aggregateSearchGeneration = 0
    private var collectionSearchJob: Job? = null
    private var collectionSearchGeneration = 0
    private var chartsGeneration = 0
    private var playlistsGeneration = 0
    private var categoryGeneration = 0
    private var collectionGeneration = 0
    private var failoverInProgress = false
    private var updateDownloadJob: Job? = null
    private var pendingUpdateInstall = false
    private var splitOverrideKeys: Set<String> = emptySet()
    private var mergeTargets: Map<String, String> = emptyMap()
    private var aggregateSongs: Map<String, List<Song>> = emptyMap()
    private val pinnedProviders = mutableMapOf<String, String>()
    private val aggregateDao = app.database.aggregate()
    private val libraryDao = app.database.library()

    private val _charts = MutableStateFlow(ChartsUiState())
    val chartsState: StateFlow<ChartsUiState> = _charts
    private val _playlists = MutableStateFlow(PlaylistsUiState())
    val playlistsState: StateFlow<PlaylistsUiState> = _playlists
    private val _search = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _search
    private val _collection = MutableStateFlow(CollectionUiState())
    val collectionState: StateFlow<CollectionUiState> = _collection
    private val _librarySearch = MutableStateFlow(LibrarySearchUiState())
    val librarySearch: StateFlow<LibrarySearchUiState> = _librarySearch
    private var librarySearchGeneration = 0
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 12)
    val events = _events.asSharedFlow()

    private val _accountProfiles = MutableStateFlow<Map<String, AccountProfile>>(emptyMap())
    val accountProfiles: StateFlow<Map<String, AccountProfile>> = _accountProfiles
    private val accountInfoJobs = ConcurrentHashMap<String, Job>()

    // 播放进度单独成流：每 500ms 更新一次，放在 MusicUiState 里会让所有页面跟着重组
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position

    val syncState = app.sync.state
    val syncEvents = app.sync.events
    val updateState = app.update.state
    // 下载进度独立读取，版本、按钮和设置摘要不随每个进度更新重组。
    val updateDetails = updateState.map { it.copy(progress = 0f) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), updateState.value.copy(progress = 0f))
    val equalizerState = app.equalizer.state
    val sleepTimer = app.sleepTimer.state

    private val chartCache = mutableMapOf<String, CachedCharts>()
    private val recommendationCache = mutableMapOf<String, CachedRecommendations>()
    private val categoryCache = mutableMapOf<String, CachedCategory>()

    val favorites: StateFlow<List<SavedSong>> = app.database.dao().songs("favorite")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history: StateFlow<List<SavedSong>> = app.database.dao().songs("history")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val downloads: StateFlow<List<DownloadRecord>> = app.database.dao().downloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val localPlaylists: StateFlow<List<LocalPlaylistSummary>> =
        combine(libraryDao.playlists(), libraryDao.entryCounts()) { playlists, counts ->
            val countMap = counts.associate { it.playlistId to it.total }
            playlists.map { playlist ->
                LocalPlaylistSummary(
                    id = playlist.id,
                    name = playlist.name,
                    trackCount = countMap[playlist.id] ?: 0,
                    updatedAt = playlist.updatedAt,
                    dirty = playlist.dirty,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 自建歌单的封面拼贴：每个歌单取前 4 首有封面的歌。 */
    val localPlaylistCovers: StateFlow<Map<String, List<String>>> = localPlaylists
        .map { list ->
            list.associate { summary ->
                summary.id to libraryDao.entriesOnce(summary.id).asSequence()
                    .mapNotNull { entry -> runCatching { json.decodeFromString<Song>(entry.payload).cover }.getOrNull() }
                    .filter { it.isNotBlank() }
                    .take(4)
                    .toList()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val state: StateFlow<MusicUiState> = combine(base, settingsState, _accountProfiles) { ui, settings, profiles ->
        ui.copy(
            providerId = settings.provider,
            chartsSource = settings.chartsSource,
            playlistsSource = settings.playlistsSource,
            theme = settings.theme,
            appearance = settings.toAppearance(),
            lyricsBackground = settings.lyricsBackground,
            rhythmIntensity = settings.rhythmIntensity,
            coverShape = settings.coverShape,
            tvAmbientClock = settings.tvAmbientClock,
            sleepFade = settings.sleepFade,
            cacheLimitMb = settings.cacheLimitMb.takeIf { it > 0 } ?: app.defaultCacheLimitMb(),
            reduceMotion = settings.reduceMotion,
            showBottomNav = settings.showBottomNav,
            cookies = settings.cookies,
            sodaSignerUrl = settings.sodaSignerUrl,
            sodaSignerToken = settings.sodaSignerToken,
            aggregation = settings.aggregation,
            searchHistory = settings.searchHistory,
            skippedUpdateCode = settings.skippedUpdateCode,
            accountProfiles = profiles,
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
        workDir = { app.cacheDir },
    )
    private val matchJob = MatchJob(
        repository = app.repository,
        cookies = { settingsState.value.cookies },
        dao = aggregateDao,
        settings = { settingsState.value.aggregation },
    )

    private val coverSeeds = CoverSeedRepository(application, SingletonImageLoader.get(application))

    init {
        viewModelScope.launch {
            settingsState.map { it.cookies }.distinctUntilChanged().collect { cookies ->
                cookies.forEach { (id, cookie) ->
                    if (cookie.isBlank()) {
                        accountInfoJobs.remove(id)?.cancel()
                        _accountProfiles.update { it - id }
                    } else {
                        refreshAccountProfile(id, cookie)
                    }
                }
            }
        }
        viewModelScope.launch {
            base.map { it.current?.cover.orEmpty() }.distinctUntilChanged().collectLatest { url ->
                if (url.isBlank()) return@collectLatest
                val seed = coverSeeds.seedFor(url) ?: return@collectLatest
                base.update { it.copy(coverSeed = seed) }
                app.settings.lastSeed(seed)
                // 空闲时给队列里接下来几首预取色，切歌时直接命中缓存
                delay(1500)
                val queue = base.value.queue
                val index = queue.indexOfFirst { it.cover == url }
                queue.drop(index + 1).take(3).forEach { coverSeeds.seedFor(it.cover) }
            }
        }
        val token = SessionToken(application, android.content.ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).buildAsync()
        future.addListener({
            controller = future.get().also { it.addListener(playerListener) }
            startProgress()
        }, MoreExecutors.directExecutor())
        viewModelScope.launch {
            runCatching { app.repository.providers() }.onSuccess { providers ->
                base.value = base.value.copy(providers = providers)
                val settings = app.settings.state.first()
                loadCharts(providerId = settings.chartsSource.ifBlank { null }, force = true)
                loadPlaylists(providerId = settings.playlistsSource.ifBlank { null }, force = true)
            }
            app.sync.syncIfStaleOnForeground()
        }
        // 每次启动都检查一次更新，发现新版本由顶层弹窗提示；不依赖平台信息是否加载成功
        viewModelScope.launch { app.update.check(manual = false) }
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

    // ---------- charts ----------

    fun selectChartSource(providerId: String) {
        if (_charts.value.providerId == providerId && _charts.value.loadedAt > 0) return
        viewModelScope.launch { app.settings.chartsSource(providerId) }
        loadCharts(providerId, force = false)
    }

    fun refreshCharts() {
        loadCharts(_charts.value.providerId.ifBlank { null }, force = true)
    }

    private fun loadCharts(providerId: String?, force: Boolean) {
        val providers = base.value.providers
        if (providers.isEmpty()) return
        val candidates = providers.filter { it.topLists || it.hotSongs }
        val preferred = providerId ?: settingsState.value.chartsSource.takeIf { it.isNotBlank() }
        val target = preferred?.let { id -> candidates.find { it.id == id } ?: providers.find { it.id == id } }
            ?: candidates.find { it.id == settingsState.value.provider }
            ?: candidates.firstOrNull()
            ?: providers.first()
        val generation = ++chartsGeneration
        val cached = chartCache[target.id]
        if (!force && cached != null && System.currentTimeMillis() - cached.at < BROWSE_CACHE_MS) {
            _charts.value = ChartsUiState(
                providerId = target.id,
                providerName = target.name,
                supported = target.topLists,
                charts = cached.charts,
                featuredSongs = cached.featuredSongs,
                hotSongs = cached.hotSongs,
                loading = false,
                error = cached.error,
                loadedAt = cached.at,
            )
            return
        }
        _charts.value = ChartsUiState(
            providerId = target.id,
            providerName = target.name,
            supported = target.topLists,
            charts = if (force) emptyList() else _charts.value.charts.takeIf { _charts.value.providerId == target.id }.orEmpty(),
            featuredSongs = if (force) emptyList() else _charts.value.featuredSongs,
            hotSongs = if (force) emptyList() else _charts.value.hotSongs.takeIf { _charts.value.providerId == target.id }.orEmpty(),
            loading = target.topLists || target.hotSongs,
            error = "",
        )
        viewModelScope.launch {
            val cookie = cookieFor(target.id)
            val chartsResult = if (target.topLists) runCatching { app.repository.topLists(target.id, cookie) }
            else Result.success(emptyList())
            val hotResult = if (target.hotSongs) runCatching { app.repository.hotSongs(target.id, cookie, 12) }
            else Result.success(emptyList())
            val charts = chartsResult.getOrDefault(emptyList())
            val featured = charts.firstOrNull()
            val featuredSongs = if (featured != null && target.topListSongs) {
                runCatching { app.repository.topListSongs(target.id, cookie, featured.id).take(3) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            if (generation != chartsGeneration) return@launch
            val error = listOfNotNull(chartsResult.exceptionOrNull(), hotResult.exceptionOrNull())
                .firstOrNull()?.message.orEmpty()
            val hot = hotResult.getOrDefault(emptyList())
            chartCache[target.id] = CachedCharts(
                at = System.currentTimeMillis(),
                charts = charts,
                featuredSongs = featuredSongs,
                hotSongs = hot,
                error = error,
            )
            _charts.value = ChartsUiState(
                providerId = target.id,
                providerName = target.name,
                supported = target.topLists,
                charts = charts,
                featuredSongs = featuredSongs,
                hotSongs = hot,
                loading = false,
                error = error,
                loadedAt = System.currentTimeMillis(),
            )
        }
    }

    // ---------- playlists ----------

    fun selectPlaylistSource(providerId: String) {
        if (_playlists.value.providerId == providerId) return
        viewModelScope.launch { app.settings.playlistsSource(providerId) }
        loadPlaylists(providerId, force = false)
    }

    fun refreshPlaylists() {
        loadPlaylists(_playlists.value.providerId.ifBlank { null }, force = true)
    }

    fun setPlaylistMode(mode: PlaylistMode) {
        val current = _playlists.value
        if (mode == PlaylistMode.Category && !current.canCategorize) return
        _playlists.value = current.copy(mode = mode)
        if (mode == PlaylistMode.Category && current.categoryPlaylists.isEmpty() && !current.categoryLoading) {
            loadCategory(current.selectedCategoryId, page = 1)
        }
    }

    fun setCategoryGroup(group: String) {
        _playlists.update { it.copy(categoryGroup = group) }
    }

    fun selectCategory(category: PlaylistCategory) {
        _playlists.update {
            it.copy(
                selectedCategoryId = category.id,
                selectedCategoryName = category.name,
                categoryGroup = category.group.ifBlank { it.categoryGroup },
                categoryPlaylists = if (category.id == it.selectedCategoryId) it.categoryPlaylists else emptyList(),
            )
        }
        loadCategory(category.id, page = 1)
    }

    fun clearCategory() {
        _playlists.update {
            it.copy(
                selectedCategoryId = "",
                selectedCategoryName = "",
                categoryPlaylists = emptyList(),
                categoryPage = 1,
                categoryOpen = false,
                categoryError = "",
            )
        }
        loadCategory("", page = 1)
    }

    fun categoryPage(page: Int) {
        val current = _playlists.value
        if (page < 1 || page == current.categoryPage) return
        loadCategory(current.selectedCategoryId, page)
    }

    private fun loadPlaylists(providerId: String?, force: Boolean) {
        val providers = base.value.providers
        if (providers.isEmpty()) return
        val candidates = providers.filter { it.recommendedPlaylists || it.playlistCategories }
        val preferred = providerId ?: settingsState.value.playlistsSource.takeIf { it.isNotBlank() }
        val target = preferred?.let { id -> candidates.find { it.id == id } ?: providers.find { it.id == id } }
            ?: candidates.find { it.id == settingsState.value.provider }
            ?: candidates.firstOrNull()
            ?: providers.first()
        val generation = ++playlistsGeneration
        _playlists.value = PlaylistsUiState(
            providerId = target.id,
            providerName = target.name,
            canRecommend = target.recommendedPlaylists,
            canCategorize = target.playlistCategories,
            mode = _playlists.value.mode.takeIf { _playlists.value.providerId == target.id } ?: PlaylistMode.Recommend,
            recommendationsLoading = target.recommendedPlaylists,
            categoriesLoading = target.playlistCategories,
        )
        viewModelScope.launch {
            val cookie = cookieFor(target.id)
            val recommendationCached = recommendationCache[target.id]
            val useRecommendationCache = !force && recommendationCached != null &&
                System.currentTimeMillis() - recommendationCached.at < BROWSE_CACHE_MS
            val recommendationsResult = if (target.recommendedPlaylists) {
                if (useRecommendationCache) Result.success(recommendationCached!!.items)
                else runCatching { app.repository.recommended(target.id, cookie) }
            } else {
                Result.success(emptyList())
            }
            if (generation != playlistsGeneration) return@launch
            if (target.recommendedPlaylists && !useRecommendationCache) {
                recommendationsResult.getOrNull()?.let {
                    recommendationCache[target.id] = CachedRecommendations(System.currentTimeMillis(), it)
                }
            }
            _playlists.update {
                it.copy(
                    recommendations = recommendationsResult.getOrDefault(emptyList()),
                    recommendationsLoading = false,
                    recommendationsError = recommendationsResult.exceptionOrNull()?.message.orEmpty(),
                )
            }
            val categoriesResult = if (target.playlistCategories) {
                runCatching { app.repository.categories(target.id, cookie) }
            } else {
                Result.success(emptyList())
            }
            if (generation != playlistsGeneration) return@launch
            _playlists.update {
                it.copy(
                    categories = categoriesResult.getOrDefault(emptyList()),
                    categoriesLoading = false,
                    categoriesError = categoriesResult.exceptionOrNull()?.message.orEmpty(),
                )
            }
            if (_playlists.value.mode == PlaylistMode.Category &&
                _playlists.value.categoryPlaylists.isEmpty() &&
                target.playlistCategories
            ) {
                loadCategory(_playlists.value.selectedCategoryId, page = 1)
            }
        }
    }

    private fun loadCategory(categoryId: String, page: Int) {
        val current = _playlists.value
        val providerId = current.providerId.ifBlank { settingsState.value.provider }
        val safePage = page.coerceAtLeast(1)
        val generation = ++categoryGeneration
        val key = "$providerId|$categoryId|$safePage"
        _playlists.update {
            it.copy(
                categoryOpen = true,
                selectedCategoryId = categoryId,
                categoryPage = safePage,
                categoryLoading = true,
                categoryError = "",
            )
        }
        viewModelScope.launch {
            val cached = categoryCache[key]
            val result = if (cached != null && System.currentTimeMillis() - cached.at < BROWSE_CACHE_MS) {
                Result.success(cached.items)
            } else {
                runCatching {
                    app.repository.categoryPlaylists(providerId, cookieFor(providerId), categoryId, safePage, 30)
                }
            }
            if (generation != categoryGeneration) return@launch
            if (result.isSuccess && categoryCache[key] == null) {
                categoryCache[key] = CachedCategory(System.currentTimeMillis(), result.getOrDefault(emptyList()))
            }
            result.onFailure { error ->
                if (generation == categoryGeneration) {
                    _playlists.update {
                        it.copy(
                            categoryLoading = false,
                            categoryError = error.message ?: "分类加载失败",
                        )
                    }
                }
            }.onSuccess { items ->
                _playlists.update {
                    it.copy(
                        categoryPlaylists = items,
                        categoryLoading = false,
                        categoryError = "",
                    )
                }
            }
        }
    }

    // ---------- collection detail ----------

    fun openChart(chart: TopList) {
        val source = chart.source.ifBlank { _charts.value.providerId.ifBlank { settingsState.value.provider } }
        val subtitle = listOf(
            chart.updateFrequency.ifBlank { chart.description },
            "${chart.trackCount} 首".takeIf { chart.trackCount > 0 },
        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" / ")
        _collection.value = CollectionUiState(
            kind = CollectionKind.Chart,
            id = chart.id,
            title = chart.name,
            subtitle = subtitle,
            cover = chart.cover,
            source = source,
            sourceName = providerName(source),
            loading = true,
        )
        _events.tryEmit(UiEvent.OpenCollection(chart.name))
        val generation = ++collectionGeneration
        viewModelScope.launch {
            val result = runCatching { app.repository.topListSongs(source, cookieFor(source), chart.id) }
            if (generation != collectionGeneration) return@launch
            _collection.update {
                it.copy(
                    songs = result.getOrDefault(emptyList()),
                    loading = false,
                    error = result.exceptionOrNull()?.message.orEmpty(),
                )
            }
        }
    }

    fun openPlaylist(playlist: Playlist, album: Boolean) {
        val source = playlist.source.ifBlank { settingsState.value.provider }
        val subtitle = listOf(
            playlist.creator.ifBlank { if (album) "专辑" else "歌单" },
            "${playlist.trackCount} 首".takeIf { playlist.trackCount > 0 },
        ).filterNotNull().joinToString(" / ")
        _collection.value = CollectionUiState(
            kind = if (album) CollectionKind.Album else CollectionKind.Playlist,
            id = playlist.id,
            title = playlist.name,
            subtitle = subtitle,
            cover = playlist.cover,
            source = source,
            sourceName = providerName(source),
            loading = true,
        )
        _events.tryEmit(UiEvent.OpenCollection(playlist.name))
        val generation = ++collectionGeneration
        viewModelScope.launch {
            val result = if (album) {
                runCatching { app.repository.albumSongs(source, cookieFor(source), playlist.id) }
            } else {
                runCatching { app.repository.playlistSongs(source, cookieFor(source), playlist.id) }
            }
            if (generation != collectionGeneration) return@launch
            _collection.update {
                it.copy(
                    songs = result.getOrDefault(emptyList()),
                    loading = false,
                    error = result.exceptionOrNull()?.message.orEmpty(),
                )
            }
        }
    }

    fun retryCollection() {
        val current = _collection.value
        if (current.title.isBlank()) return
        val generation = ++collectionGeneration
        _collection.update { it.copy(loading = true, error = "") }
        viewModelScope.launch {
            val result = runCatching {
                when (current.kind) {
                    CollectionKind.Chart -> app.repository.topListSongs(current.source, cookieFor(current.source), current.id)
                    CollectionKind.Album -> app.repository.albumSongs(current.source, cookieFor(current.source), current.id)
                    CollectionKind.Playlist -> app.repository.playlistSongs(current.source, cookieFor(current.source), current.id)
                }
            }
            if (generation != collectionGeneration) return@launch
            _collection.update {
                it.copy(
                    songs = result.getOrDefault(emptyList()),
                    loading = false,
                    error = result.exceptionOrNull()?.message.orEmpty(),
                )
            }
        }
    }

    fun playCollection() {
        val songs = _collection.value.songs
        if (songs.isNotEmpty()) play(songs.first(), songs)
    }

    fun saveCollectionToLibrary() {
        val collection = _collection.value
        if (collection.songs.isEmpty()) {
            message("当前歌单还没有加载完成")
            return
        }
        addSongsToLibrary(collection.title, collection.songs)
    }

    fun closeCollection() {
        _collection.value = CollectionUiState()
    }

    // ---------- discover: 追加分页、直接播放榜单 / 歌单、加入队列、封面取色 ----------

    private val _categoryEndKey = MutableStateFlow("")

    /** 当前分类已经没有下一页。键里带上列表长度，重新进入同一分类时自动失效。 */
    val categoryEnd: StateFlow<Boolean> = combine(_playlists, _categoryEndKey) { playlists, key ->
        key.isNotEmpty() && key == categoryEndKey(playlists, playlists.categoryPlaylists.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private fun categoryEndKey(playlists: PlaylistsUiState, size: Int) =
        "${playlists.providerId}|${playlists.selectedCategoryId}|$size"

    /** 手机和平板滚到底时把下一页接在后面；电视仍用 categoryPage() 整页翻。失败时页码不前进，再调一次就是重试。 */
    fun loadMoreCategory() {
        val current = _playlists.value
        if (current.categoryLoading) return
        if (current.categoryPlaylists.isEmpty()) {
            loadCategory(current.selectedCategoryId, page = 1)
            return
        }
        if (_categoryEndKey.value == categoryEndKey(current, current.categoryPlaylists.size)) return
        val providerId = current.providerId.ifBlank { settingsState.value.provider }
        val categoryId = current.selectedCategoryId
        val page = current.categoryPage + 1
        val generation = ++categoryGeneration
        _playlists.update { it.copy(categoryLoading = true, categoryError = "") }
        viewModelScope.launch {
            val key = "$providerId|$categoryId|$page"
            val cached = categoryCache[key]
            val result = if (cached != null && System.currentTimeMillis() - cached.at < BROWSE_CACHE_MS) {
                Result.success(cached.items)
            } else {
                runCatching { app.repository.categoryPlaylists(providerId, cookieFor(providerId), categoryId, page, 30) }
            }
            if (generation != categoryGeneration) return@launch
            val latest = _playlists.value
            // 换平台、刷新会整体替换状态而不经过 categoryGeneration，旧分类的下一页不能接到新列表后面
            if (latest.categoryPlaylists !== current.categoryPlaylists || latest.selectedCategoryId != categoryId) {
                _playlists.update { it.copy(categoryLoading = false) }
                return@launch
            }
            result.onFailure { error ->
                _playlists.value = latest.copy(categoryLoading = false, categoryError = error.message ?: "分类加载失败")
            }.onSuccess { items ->
                categoryCache[key] = CachedCategory(System.currentTimeMillis(), items)
                val merged = (latest.categoryPlaylists + items).distinctBy { "${it.source}:${it.id}" }
                if (merged.size == latest.categoryPlaylists.size) {
                    _categoryEndKey.value = categoryEndKey(latest, merged.size)
                }
                _playlists.value = latest.copy(
                    categoryPlaylists = merged,
                    categoryPage = if (merged.size > latest.categoryPlaylists.size) page else latest.categoryPage,
                    categoryLoading = false,
                    categoryError = "",
                )
            }
        }
    }

    /** 重新读取当前页（电视翻页失败后的重试；categoryPage() 遇到同一页会直接返回）。 */
    fun retryCategory() {
        val current = _playlists.value
        loadCategory(current.selectedCategoryId, current.categoryPage)
    }

    /** 直接播放的读取任务：连点时只保留最后一次，免得先点的晚返回反而把后点的顶掉。 */
    private var directPlayJob: Job? = null

    /** 不进详情页直接播放整个榜单（轮播、电视 Hero 上的播放键）。 */
    fun playChart(chart: TopList) {
        val source = chart.source.ifBlank { _charts.value.providerId.ifBlank { settingsState.value.provider } }
        directPlayJob?.cancel()
        directPlayJob = viewModelScope.launch {
            val songs = runCatching { app.repository.topListSongs(source, cookieFor(source), chart.id) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    message(it.message ?: "榜单读取失败", error = true)
                    return@launch
                }
            if (songs.isEmpty()) message("这个榜单暂时没有歌曲", error = true) else play(songs.first(), songs)
        }
    }

    /** 不进详情页直接播放歌单或专辑。 */
    fun playPlaylist(playlist: Playlist, album: Boolean = false) {
        val source = playlist.source.ifBlank { settingsState.value.provider }
        directPlayJob?.cancel()
        directPlayJob = viewModelScope.launch {
            val songs = runCatching {
                if (album) app.repository.albumSongs(source, cookieFor(source), playlist.id)
                else app.repository.playlistSongs(source, cookieFor(source), playlist.id)
            }.getOrElse {
                if (it is CancellationException) throw it
                message(it.message ?: "歌单读取失败", error = true)
                return@launch
            }
            if (songs.isEmpty()) message("这个歌单暂时没有歌曲", error = true) else play(songs.first(), songs)
        }
    }

    /** 把详情页的歌接到播放队列末尾；队列为空时直接播放。已在队列里的不重复添加。 */
    fun enqueueCollection() {
        val songs = _collection.value.songs
        if (songs.isEmpty()) {
            message("当前歌单还没有加载完成")
            return
        }
        val player = controller
        if (player == null || base.value.queue.isEmpty()) {
            play(songs.first(), songs)
            return
        }
        val known = base.value.canonicalQueue.map { it.id }.toSet()
        val tracks = songs.map { canonicalForSong(it) }.distinctBy { it.id }.filter { it.id !in known }
        if (tracks.isEmpty()) {
            message("这些歌已经在播放队列里")
            return
        }
        val queued = tracks.map { preferredSong(it) }
        player.addMediaItems(queued.map(::mediaItem))
        base.value = base.value.copy(
            queue = base.value.queue + queued,
            canonicalQueue = base.value.canonicalQueue + tracks,
        )
        message("已加入播放队列：${tracks.size} 首")
    }

    /** 封面种子色（详情页头部渐变、电视 Hero 换色），与播放页共用缓存。 */
    suspend fun coverSeed(url: String): Int? = coverSeeds.seedFor(url)

    // ---------- search ----------

    fun setSearchQuery(value: String) {
        _search.update { it.copy(query = value) }
    }

    fun submitSearch(value: String = _search.value.query) {
        val text = value.trim()
        if (text.isBlank()) return
        _search.update {
            it.copy(
                query = text,
                submittedQuery = text,
                page = 1,
                aggregateGroups = emptyList(),
                aggregateCollections = emptyList(),
                providerStates = emptyMap(),
                collectionProviderStates = emptyMap(),
                aggregateStatus = SearchStatus.Idle,
                collectionStatus = SearchStatus.Idle,
                error = "",
                hasMore = false,
            )
        }
        viewModelScope.launch { app.settings.addSearchHistory(text) }
        if (isLinkQuery(text)) {
            openLink(text)
            return
        }
        search(page = 1, force = true)
    }

    fun setSearchKind(kind: SearchKind) {
        if (kind == SearchKind.Link) return
        _search.update { it.copy(kind = kind, page = 1) }
        if (_search.value.submittedQuery.isNotBlank() && !isLinkQuery(_search.value.submittedQuery)) {
            search(page = 1, force = true)
        }
    }

    fun clearSearch() {
        aggregateSearchJob?.cancel()
        collectionSearchJob?.cancel()
        _search.value = SearchUiState()
    }

    fun removeSearchHistory(query: String) {
        viewModelScope.launch { app.settings.removeSearchHistory(query) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { app.settings.clearSearchHistory() }
    }

    fun searchMore() {
        val current = _search.value
        if (current.submittedQuery.isBlank() || !current.hasMore || current.loading) return
        search(page = current.page + 1, force = true)
    }

    fun isLinkQuery(query: String): Boolean = LINK_PATTERN.containsMatchIn(query.trim())

    /** 撤销清除 / 删除搜索记录：按原顺序写回；撤销前新搜的词排在最前。 */
    fun restoreSearchHistory(items: List<String>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val added = settingsState.value.searchHistory.filterNot { it in items }
            val merged = (added + items).distinct().take(20)
            app.settings.clearSearchHistory()
            merged.asReversed().forEach { app.settings.addSearchHistory(it) }
        }
    }

    /**
     * 只重试本次失败、超时或被跳过的平台。已返回的平台结果保留，
     * 分组和排序按全部启用平台重新计算（引擎只拿到失败平台时算不出正确的覆盖率和平台名）。
     */
    fun retryFailedSearch() {
        val current = _search.value
        val query = current.submittedQuery.trim()
        if (query.isBlank() || current.loading) return
        if (current.kind != SearchKind.Song) {
            search(page = 1, force = true)
            return
        }
        val failedIds = current.providerStates.values.filter {
            it.status == ProviderStatus.Timeout || it.status == ProviderStatus.Error || it.status == ProviderStatus.Skipped
        }.map { it.providerId }.toSet()
        val all = enabledProviders()
        val retry = all.filter { it.id in failedIds }
        if (retry.isEmpty()) {
            search(page = 1, force = true)
            return
        }
        aggregateSearchJob?.cancel()
        val generation = ++aggregateSearchGeneration
        _search.update { state ->
            state.copy(
                loading = true,
                error = "",
                aggregateStatus = SearchStatus.Partial,
                providerStates = state.providerStates +
                    retry.associate { it.id to ProviderSearchState(it.id, it.name, ProviderStatus.Loading) },
            )
        }
        aggregateSearchJob = viewModelScope.launch {
            val names = all.associate { it.id to it.name }
            val priority = settingsState.value.aggregation.priorityFor(all.map { it.id })
            val preferred = priority.firstOrNull() ?: all.first().id
            fun regroup(): List<CanonicalTrack> {
                val cookies = settingsState.value.cookies
                val groups = TrackMatcher.group(
                    aggregateSongs, names, cookies, settingsState.value.aggregation.threshold(), priority,
                    splitOverrideKeys, mergeTargets,
                )
                return TrackMatcher.rank(groups, all.size, priority, preferred, cookies)
            }
            try {
                searchEngine.search(query, retry, force = true, page = 1).collect { update ->
                    if (generation != aggregateSearchGeneration || update !is SearchUpdate.ProviderArrived) return@collect
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
                    val groups = regroup()
                    _search.update {
                        it.copy(providerStates = it.providerStates + (update.providerId to providerState), aggregateGroups = groups)
                    }
                }
                if (generation == aggregateSearchGeneration) {
                    val groups = regroup()
                    _search.update {
                        val allFailed = it.providerStates.values.isNotEmpty() && it.providerStates.values.all { state ->
                            state.status == ProviderStatus.Timeout || state.status == ProviderStatus.Error || state.status == ProviderStatus.Skipped
                        }
                        it.copy(
                            aggregateGroups = groups,
                            aggregateStatus = when {
                                groups.isNotEmpty() -> SearchStatus.Complete
                                allFailed -> SearchStatus.Failed
                                else -> SearchStatus.Empty
                            },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == aggregateSearchGeneration) {
                    _search.update { it.copy(error = error.message ?: "重试失败") }
                }
            } finally {
                if (generation == aggregateSearchGeneration) {
                    _search.update { it.copy(loading = false) }
                }
            }
        }
    }

    private fun search(page: Int, force: Boolean) {
        val current = _search.value
        val query = current.submittedQuery.trim()
        if (query.isBlank()) return
        when (current.kind) {
            SearchKind.Song -> searchAggregated(query, force, page)
            SearchKind.Album, SearchKind.Playlist -> searchCollections(current.kind, query)
            SearchKind.Link -> openLink(query)
        }
    }

    private fun searchAggregated(query: String, force: Boolean, page: Int) {
        aggregateSearchJob?.cancel()
        val generation = ++aggregateSearchGeneration
        if (page == 1) {
            aggregateSongs = emptyMap()
            _search.update {
                it.copy(
                    loading = true,
                    error = "",
                    aggregateStatus = SearchStatus.Loading,
                    aggregateGroups = emptyList(),
                    providerStates = emptyMap(),
                    hasMore = false,
                    page = 1,
                )
            }
        } else {
            _search.update { it.copy(loading = true, error = "", page = page) }
        }
        aggregateSearchJob = viewModelScope.launch {
            val providers = enabledProviders()
            if (providers.isEmpty()) {
                _search.update {
                    it.copy(loading = false, error = "没有启用可搜索的平台", aggregateStatus = SearchStatus.Failed)
                }
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
                            _search.update {
                                it.copy(
                                    providerStates = it.providerStates + (update.providerId to providerState),
                                    aggregateStatus = SearchStatus.Partial,
                                )
                            }
                        }
                        is SearchUpdate.Groups -> _search.update { it.copy(aggregateGroups = update.groups) }
                        is SearchUpdate.FromCache -> {
                            aggregateSongs = update.songs
                            _search.update {
                                it.copy(
                                    aggregateGroups = update.groups,
                                    providerStates = update.states,
                                    aggregateStatus = SearchStatus.Complete,
                                    hasMore = update.hasMore,
                                )
                            }
                        }
                        is SearchUpdate.Completed -> {
                            val states = _search.value.providerStates.values
                            val allFailed = states.isNotEmpty() && states.all {
                                it.status == ProviderStatus.Timeout ||
                                    it.status == ProviderStatus.Error ||
                                    it.status == ProviderStatus.Skipped
                            }
                            _search.update {
                                it.copy(
                                    aggregateGroups = update.groups,
                                    aggregateStatus = when {
                                        update.groups.isNotEmpty() -> SearchStatus.Complete
                                        allFailed -> SearchStatus.Failed
                                        else -> SearchStatus.Empty
                                    },
                                    hasMore = update.hasMore,
                                    loading = false,
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == aggregateSearchGeneration) {
                    _search.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "聚合搜索失败",
                            aggregateStatus = SearchStatus.Failed,
                        )
                    }
                }
            } finally {
                if (generation == aggregateSearchGeneration) {
                    _search.update { it.copy(loading = false) }
                }
            }
        }
    }

    private fun searchCollections(kind: SearchKind, query: String) {
        collectionSearchJob?.cancel()
        val generation = ++collectionSearchGeneration
        _search.update {
            it.copy(
                loading = true,
                error = "",
                aggregateCollections = emptyList(),
                collectionProviderStates = emptyMap(),
                collectionStatus = SearchStatus.Loading,
                page = 1,
            )
        }
        collectionSearchJob = viewModelScope.launch {
            val providers = enabledProviders().filter {
                if (kind == SearchKind.Album) it.searchAlbum else it.searchPlaylist
            }
            if (providers.isEmpty()) {
                _search.update {
                    it.copy(loading = false, error = "没有启用支持${kind.label}搜索的平台", collectionStatus = SearchStatus.Failed)
                }
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
                                _search.update {
                                    it.copy(
                                        aggregateCollections = CollectionMatcher.group(results, names, kind, priority),
                                        collectionProviderStates = it.collectionProviderStates + (provider.id to providerState),
                                        collectionStatus = SearchStatus.Partial,
                                    )
                                }
                            }
                        }
                    }
                }
                if (generation == collectionSearchGeneration) {
                    val grouped = CollectionMatcher.group(results, names, kind, priority)
                    val allFailed = states.isNotEmpty() && states.values.all {
                        it.status == ProviderStatus.Timeout ||
                            it.status == ProviderStatus.Error ||
                            it.status == ProviderStatus.Skipped
                    }
                    _search.update {
                        it.copy(
                            aggregateCollections = grouped,
                            collectionStatus = when {
                                grouped.isNotEmpty() -> SearchStatus.Complete
                                allFailed -> SearchStatus.Failed
                                else -> SearchStatus.Empty
                            },
                            loading = false,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == collectionSearchGeneration) {
                    _search.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "聚合搜索失败",
                            collectionStatus = SearchStatus.Failed,
                        )
                    }
                }
            } finally {
                if (generation == collectionSearchGeneration) {
                    _search.update { it.copy(loading = false) }
                }
            }
        }
    }

    private fun enabledProviders(): List<ProviderInfo> {
        val all = base.value.providers.filter { it.search }
        if (all.isEmpty()) return emptyList()
        val ids = settingsState.value.aggregation.enabledOrDefault(all.map { it.id })
        return all.filter { it.id in ids }
    }

    fun openLink(link: String) {
        val value = link.trim()
        if (value.isBlank()) return
        viewModelScope.launch {
            base.value = base.value.copy(loading = true, error = "")
            try {
                for (providerId in linkParseCandidates(value)) {
                    val cookie = cookieFor(providerId)
                    val playlist = runCatching { app.repository.parsePlaylist(providerId, cookie, value) }.getOrNull()
                    val album = if (playlist == null) {
                        runCatching { app.repository.parseAlbum(providerId, cookie, value) }.getOrNull()
                    } else {
                        null
                    }
                    val result = playlist ?: album
                    if (result != null) {
                        val kind = if (playlist != null) CollectionKind.Playlist else CollectionKind.Album
                        val source = result.item.source.ifBlank { providerId }
                        _collection.value = CollectionUiState(
                            kind = kind,
                            id = result.item.id,
                            title = result.item.name,
                            subtitle = listOf(result.item.creator, "${result.songs.size} 首").filter { it.isNotBlank() }.joinToString(" / "),
                            cover = result.item.cover,
                            source = source,
                            sourceName = providerName(source),
                            songs = result.songs,
                        )
                        _events.tryEmit(UiEvent.OpenCollection(result.item.name))
                        // 复制歌单链接导入 = 直接存为本地歌单；同名歌单只补缺失的歌
                        if (kind == CollectionKind.Playlist) importLinkPlaylist(result.item.name, result.songs)
                        return@launch
                    }
                    val song = runCatching { app.repository.parseSong(providerId, cookie, value) }.getOrNull()
                    if (song != null) {
                        play(song, listOf(song))
                        return@launch
                    }
                }
                base.value = base.value.copy(error = "没有识别到这个链接")
            } finally {
                base.value = base.value.copy(loading = false)
            }
        }
    }

    // 链接属于哪个平台由域名决定，不跟默认平台走；识别不出或解析失败时，按默认平台、其余可解析平台兜底
    private fun linkParseCandidates(link: String): List<String> {
        val candidates = mutableListOf<String>()
        linkProviderId(link).takeIf { it.isNotBlank() }?.let(candidates::add)
        settingsState.value.provider.takeIf { it.isNotBlank() && it !in candidates }?.let(candidates::add)
        base.value.providers
            .filter { it.parsePlaylist || it.parseAlbum || it.parseSong }
            .forEach { if (it.id !in candidates) candidates.add(it.id) }
        return candidates
    }

    // ---------- library ----------

    fun localPlaylistDetail(id: String): Flow<LocalPlaylistDetail?> =
        combine(libraryDao.playlist(id), libraryDao.entries(id)) { playlist, entries ->
            playlist?.let {
                val decoded = entries.mapNotNull { entry ->
                    runCatching { json.decodeFromString<Song>(entry.payload) }.getOrNull()
                        ?.let { song -> entry.position to song }
                }
                LocalPlaylistDetail(
                    playlist = it,
                    entries = entries,
                    songs = decoded.map { (_, song) -> song },
                    songByPosition = decoded.toMap(),
                )
            }
        }.flowOn(Dispatchers.Default)

    fun createPlaylist(name: String) {
        val value = name.trim()
        if (value.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            libraryDao.putPlaylist(
                PlaylistEntity(id = id, name = value, createdAt = now, updatedAt = now, dirty = true)
            )
            app.sync.onLocalChange()
            message("已创建歌单「$value」")
        }
    }

    fun renamePlaylist(id: String, name: String) {
        val value = name.trim()
        if (value.isBlank()) return
        viewModelScope.launch {
            libraryDao.renamePlaylist(id, value, System.currentTimeMillis())
            app.sync.onLocalChange()
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            libraryDao.softDelete(id, System.currentTimeMillis())
            app.sync.onLocalChange()
            message("已删除歌单，可从云端恢复前先在本机保留墓碑")
        }
    }

    // 歌单增删移的位置都是读出再写回，快速连点或撤销时要串行，否则会按过期的位置改错歌
    private val playlistMutex = Mutex()

    private fun launchPlaylistEdit(block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launch { playlistMutex.withLock { block() } }

    fun addSongToPlaylist(playlistId: String, song: Song) {
        launchPlaylistEdit {
            val playlist = libraryDao.playlistOnce(playlistId) ?: return@launchPlaylistEdit
            if (playlist.deletedAt != null) return@launchPlaylistEdit
            val entries = libraryDao.entriesOnce(playlistId)
            if (entries.any { it.songKey == song.key }) {
                message("这首歌已经在「${playlist.name}」里")
                return@launchPlaylistEdit
            }
            val now = System.currentTimeMillis()
            libraryDao.replaceEntries(
                playlistId,
                entries + PlaylistEntryEntity(
                    playlistId = playlistId,
                    position = entries.size,
                    songKey = song.key,
                    payload = json.encodeToString(song),
                ),
            )
            libraryDao.markDirty(playlistId, now)
            app.sync.onLocalChange()
            message("已加入「${playlist.name}」")
        }
    }

    /** 批量加入现有歌单：按 songKey 去重，提示新增与已存在的数量。 */
    fun addSongsToPlaylist(playlistId: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        launchPlaylistEdit {
            val playlist = libraryDao.playlistOnce(playlistId) ?: return@launchPlaylistEdit
            if (playlist.deletedAt != null) return@launchPlaylistEdit
            val entries = libraryDao.entriesOnce(playlistId)
            val candidates = songs.distinctBy { it.key }.filter { song -> entries.none { it.songKey == song.key } }
            if (candidates.isEmpty()) {
                message("所选歌曲都已经在「${playlist.name}」里")
                return@launchPlaylistEdit
            }
            libraryDao.replaceEntries(
                playlistId,
                entries + candidates.mapIndexed { index, song ->
                    PlaylistEntryEntity(playlistId, entries.size + index, song.key, json.encodeToString(song))
                },
            )
            libraryDao.markDirty(playlistId, System.currentTimeMillis())
            app.sync.onLocalChange()
            val skipped = songs.size - candidates.size
            message(if (skipped > 0) "已加入 ${candidates.size} 首，$skipped 首已存在" else "已把 ${candidates.size} 首加入「${playlist.name}」")
        }
    }

    /** 批量导入到新建歌单（歌单链接导入、多选“新建歌单”都走这里）。 */
    fun addSongsToNewPlaylist(name: String, songs: List<Song>) {
        val value = name.trim()
        if (songs.isEmpty() || value.isBlank()) return
        launchPlaylistEdit {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            libraryDao.putPlaylist(
                PlaylistEntity(id = id, name = value, createdAt = now, updatedAt = now, dirty = true)
            )
            libraryDao.replaceEntries(
                id,
                songs.distinctBy { it.key }.mapIndexed { index, song ->
                    PlaylistEntryEntity(id, index, song.key, json.encodeToString(song))
                },
            )
            app.sync.onLocalChange()
            message("已创建歌单「$value」，加入 ${songs.size} 首")
        }
    }

    /** 批量移除歌单条目：一次重排，避免逐条删除时位置错位。 */
    fun removePlaylistEntries(playlistId: String, positions: List<Int>) {
        if (positions.isEmpty()) return
        launchPlaylistEdit {
            val doomed = positions.toHashSet()
            val entries = libraryDao.entriesOnce(playlistId)
            libraryDao.replaceEntries(
                playlistId,
                entries.filterNot { it.position in doomed }
                    .withIndex()
                    .map { (index, entry) -> entry.copy(position = index) },
            )
            libraryDao.markDirty(playlistId, System.currentTimeMillis())
            app.sync.onLocalChange()
        }
    }

    fun removePlaylistEntry(playlistId: String, position: Int) {
        launchPlaylistEdit {
            val entries = libraryDao.entriesOnce(playlistId).filterNot { it.position == position }
            libraryDao.replaceEntries(playlistId, entries.withIndex().map { (index, entry) -> entry.copy(position = index) })
            libraryDao.markDirty(playlistId, System.currentTimeMillis())
            app.sync.onLocalChange()
        }
    }

    fun movePlaylistEntry(playlistId: String, position: Int, delta: Int) {
        launchPlaylistEdit {
            val entries = libraryDao.entriesOnce(playlistId).toMutableList()
            val index = entries.indexOfFirst { it.position == position }
            if (index < 0) return@launchPlaylistEdit
            val target = (index + delta).coerceIn(0, entries.lastIndex)
            if (target == index) return@launchPlaylistEdit
            val item = entries.removeAt(index)
            entries.add(target, item)
            libraryDao.replaceEntries(playlistId, entries.withIndex().map { (i, entry) -> entry.copy(position = i) })
            libraryDao.markDirty(playlistId, System.currentTimeMillis())
            app.sync.onLocalChange()
        }
    }

    fun searchLibrarySongs(query: String) {
        val value = query.trim()
        if (value.isBlank()) {
            _librarySearch.value = LibrarySearchUiState()
            return
        }
        val generation = ++librarySearchGeneration
        _librarySearch.value = LibrarySearchUiState(query = value, loading = true)
        viewModelScope.launch {
            val providerId = settingsState.value.provider
            val result = runCatching { app.repository.searchSongs(providerId, cookieFor(providerId), value) }
            if (generation != librarySearchGeneration) return@launch
            _librarySearch.value = LibrarySearchUiState(
                query = value,
                results = result.getOrDefault(emptyList()),
                loading = false,
                error = result.exceptionOrNull()?.message.orEmpty(),
            )
        }
    }

    fun clearLibrarySearch() {
        librarySearchGeneration++
        _librarySearch.value = LibrarySearchUiState()
    }

    /** 撤销“移出歌单”：把原条目插回原位置。 */
    fun insertPlaylistEntry(playlistId: String, position: Int, songKey: String, payload: String) {
        launchPlaylistEdit {
            val entries = libraryDao.entriesOnce(playlistId).toMutableList()
            if (entries.any { it.songKey == songKey }) return@launchPlaylistEdit
            val index = position.coerceIn(0, entries.size)
            entries.add(index, PlaylistEntryEntity(playlistId, index, songKey, payload))
            libraryDao.replaceEntries(playlistId, entries.withIndex().map { (i, entry) -> entry.copy(position = i) })
            libraryDao.markDirty(playlistId, System.currentTimeMillis())
            app.sync.onLocalChange()
        }
    }

    fun clearPlayHistory() = viewModelScope.launch {
        history.value.forEach { app.database.dao().remove("history", it.key) }
    }

    /** 撤销取消收藏 / 清除记录：按原时间写回。 */
    fun restoreSavedSongs(songs: List<SavedSong>) = viewModelScope.launch {
        val dao = app.database.dao()
        songs.forEach {
            dao.clearTombstone(it.bucket, it.key)
            dao.save(it)
        }
        if (songs.any { it.bucket == "favorite" }) app.sync.onLocalChange()
    }

    fun addSongsToLibrary(name: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            libraryDao.putPlaylist(
                PlaylistEntity(id = id, name = name.trim().ifBlank { "未命名歌单" }, createdAt = now, updatedAt = now, dirty = true)
            )
            libraryDao.replaceEntries(
                id,
                songs.mapIndexed { index, song ->
                    PlaylistEntryEntity(id, index, song.key, json.encodeToString(song))
                },
            )
            app.sync.onLocalChange()
            message("已保存为「${name.trim().ifBlank { "未命名歌单" }}」")
        }
    }

    /** 链接导入的歌单存为同名本地歌单；已有同名歌单时把缺失的歌补进去，不重复建单。 */
    private fun importLinkPlaylist(name: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        launchPlaylistEdit {
            val trimmed = name.trim().ifBlank { "未命名歌单" }
            val now = System.currentTimeMillis()
            val existing = libraryDao.playlistsOnce().firstOrNull { it.name == trimmed && it.deletedAt == null }
            if (existing == null) {
                val id = UUID.randomUUID().toString()
                libraryDao.putPlaylist(
                    PlaylistEntity(id = id, name = trimmed, createdAt = now, updatedAt = now, dirty = true)
                )
                libraryDao.replaceEntries(
                    id,
                    songs.distinctBy { it.key }.mapIndexed { index, song ->
                        PlaylistEntryEntity(id, index, song.key, json.encodeToString(song))
                    },
                )
                app.sync.onLocalChange()
                message("已存为本地歌单「$trimmed」")
            } else {
                val entries = libraryDao.entriesOnce(existing.id)
                val additions = songs.distinctBy { it.key }.filter { song -> entries.none { it.songKey == song.key } }
                if (additions.isEmpty()) {
                    message("本地歌单「$trimmed」已是最新")
                } else {
                    libraryDao.replaceEntries(
                        existing.id,
                        entries + additions.mapIndexed { index, song ->
                            PlaylistEntryEntity(existing.id, entries.size + index, song.key, json.encodeToString(song))
                        },
                    )
                    libraryDao.markDirty(existing.id, now)
                    app.sync.onLocalChange()
                    message("「$trimmed」新增 ${additions.size} 首")
                }
            }
        }
    }

    fun playSavedSongs(records: List<SavedSong>) = viewModelScope.launch {
        if (records.isEmpty()) return@launch
        val songs = withContext(Dispatchers.Default) { records.map { it.song(json) } }
        songs.firstOrNull()?.let { play(it, songs) }
    }

    fun toggleFavorite(song: Song) = viewModelScope.launch {
        val dao = app.database.dao()
        if (favorites.value.any { it.key == song.key }) {
            dao.remove("favorite", song.key)
            dao.saveTombstone(SavedSongTombstone(song.key, "favorite", System.currentTimeMillis()))
        } else {
            dao.clearTombstone("favorite", song.key)
            remember("favorite", song)
        }
        app.sync.onLocalChange()
    }

    fun download(song: Song) = viewModelScope.launch {
        val appContext = getApplication<Application>()
        var failure: Throwable? = null
        val stream = runCatching {
            app.repository.playableStream(song, cookieFor(song.source), appContext.cacheDir)
        }.onFailure {
            if (it is CancellationException) throw it
            failure = it
        }.getOrNull()
        val url = stream?.url.orEmpty()
        if (url.isBlank()) {
            message(failure?.message?.takeIf { it.isNotBlank() } ?: "没有可下载的地址", error = true)
            return@launch
        }
        val directory = appContext.getExternalFilesDir("music") ?: appContext.filesDir
        // 真实音质优先：无损流按 flac 存盘，不要再用搜索结果的 mp3 猜测
        val extension = listOf(
            stream?.ext.orEmpty(),
            song.ext,
            Uri.parse(url).path?.let { File(it).extension }.orEmpty(),
        ).firstOrNull { it.isNotBlank() }?.lowercase() ?: "mp3"
        val safeName = "${song.name}-${song.artist}[${song.source}].$extension"
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val file = File(directory, safeName)
        app.database.dao().saveDownload(
            DownloadRecord(song.key, json.encodeToString(song), file.absolutePath, "下载中", System.currentTimeMillis())
        )
        runCatching {
            withContext(Dispatchers.IO) {
                if (url.startsWith("file:")) {
                    val cached = File(Uri.parse(url).path.orEmpty())
                    if (!cached.isFile) error("本地缓存文件不存在")
                    cached.copyTo(file, overwrite = true)
                } else {
                    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) error(response.message)
                        file.outputStream().use { output -> response.body.byteStream().copyTo(output) }
                    }
                }
            }
            app.database.dao().saveDownload(
                DownloadRecord(song.key, json.encodeToString(song), file.absolutePath, "已完成", System.currentTimeMillis())
            )
            message("已下载「${song.title}」")
        }.onFailure {
            file.delete()
            app.database.dao().deleteDownload(song.key)
            message(it.message ?: "下载失败", error = true)
        }
    }

    fun deleteDownload(record: DownloadRecord) = viewModelScope.launch {
        File(record.path).delete()
        app.database.dao().deleteDownload(record.key)
    }

    fun playDownloaded(record: DownloadRecord) {
        val song = record.song(json)
        val file = File(record.path)
        if (!file.exists()) {
            message("本地文件已不存在", error = true)
            return
        }
        play(song.copy(url = Uri.fromFile(file).toString()), listOf(song))
    }

    // ---------- playback ----------

    fun play(song: Song, queue: List<Song> = listOf(song)) {
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
                playerOpen = autoOpenPlayer || base.value.playerOpen,
                playbackNotice = fallbackNotice,
                error = "",
                deepLink = "",
            )
            rememberCanonical(playableTrack)
            remember("history", historySong(resolvedSong))
            loadLyrics(resolvedSong)
        }
    }

    fun openSource(track: CanonicalTrack, queue: List<CanonicalTrack> = _search.value.aggregateGroups) {
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

    fun openSourceForSong(song: Song, queue: List<Song>) {
        val track = canonicalForSong(song)
        val tracks = queue.map { canonicalForSong(it) }.ifEmpty { listOf(track) }
        openSource(track, tracks)
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
            val groups = _search.value.aggregateGroups.flatMap { group ->
                when {
                    group.id != track.id -> listOf(group)
                    remaining.sources.isEmpty() -> listOf(separated)
                    else -> listOf(remaining, separated)
                }
            }.distinctBy { it.id }
            _search.update { it.copy(aggregateGroups = groups) }
            base.value = base.value.copy(
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
            val groups = _search.value.aggregateGroups
                .filterNot { it.id == source.id }
                .map { if (it.id == target.id) merged else it }
            _search.update { it.copy(aggregateGroups = groups) }
            base.value = base.value.copy(
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
            _search.update { state ->
                state.copy(aggregateGroups = state.aggregateGroups.map { if (it.id == track.id) updated else it })
            }
            base.value = base.value.copy(
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
        message("已恢复自动匹配")
    }

    /** ≥1200dp 平板右侧常驻播放窗格，开始播放时不必再弹出全屏播放页。 */
    private var autoOpenPlayer = true

    fun setAutoOpenPlayer(enabled: Boolean) {
        autoOpenPlayer = enabled
    }

    fun openPlayer(open: Boolean) {
        base.value = base.value.copy(playerOpen = open)
    }

    fun clearError() {
        base.value = base.value.copy(error = "")
    }

    fun clearNotice() {
        base.value = base.value.copy(playbackNotice = "", deepLink = "")
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

    fun seek(position: Long) {
        controller?.seekTo(position)
        _position.value = position.coerceAtLeast(0)
    }

    fun playQueueIndex(index: Int) {
        val player = controller ?: return
        if (index !in base.value.queue.indices) return
        player.seekTo(index, 0)
        player.play()
    }

    fun clearQueue() {
        controller?.clearMediaItems()
        controller?.stop()
        _position.value = 0
        base.value = base.value.copy(current = null, currentTrack = null, currentSource = null, duration = 0, queue = emptyList(), canonicalQueue = emptyList(), playerOpen = false)
    }

    fun openDeepLink() {
        val link = base.value.deepLink
        if (link.isBlank()) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        }
        base.value = base.value.copy(deepLink = "")
    }

    private var lyricsJob: Job? = null

    private fun loadLyrics(song: Song) {
        // 快速切歌时上一首的请求可能比新歌晚返回，只保留最新一次
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val lyrics = runCatching { app.repository.lyrics(song, cookieFor(song.source)) }.getOrDefault("")
            // runCatching 会吞掉取消异常，被取消的旧请求在这里停下，不再写回
            if (!isActive) return@launch
            // 请求完成后再读 base：先取 base.value 再挂起会把请求期间更新的当前歌曲、播放状态写回旧值
            base.value = base.value.copy(lyrics = lyrics)
        }
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
        val song = source?.song ?: track.sources.first().song
        // 汽水的 URL 可能只是试听或加密流，切歌时交回解析器走签名/解密
        return if (song.source == "soda") song.copy(url = "") else song
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
            reasons.any { it.contains("签名器") } -> reasons.first { it.contains("签名器") }
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

    private fun providerName(id: String): String =
        base.value.providers.find { it.id == id }?.name ?: when (id) {
            "netease" -> "网易云音乐"
            "qq" -> "QQ 音乐"
            "kugou" -> "酷狗音乐"
            "kuwo" -> "酷我音乐"
            "migu" -> "咪咕音乐"
            "apple" -> "Apple Music"
            "soda" -> "汽水音乐"
            "bilibili" -> "Bilibili"
            "local" -> "本地"
            else -> id
        }

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

    private fun cookieFor(providerId: String) =
        settingsState.value.cookies[providerId.ifBlank { settingsState.value.provider }].orEmpty()

    private suspend fun remember(bucket: String, song: Song) =
        app.database.dao().save(SavedSong(song.key, bucket, json.encodeToString(song), System.currentTimeMillis()))

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
        // 队列里还没解析的歌（空地址）的解析任务；同一首只解析一次
        private var resolving: Job? = null
        private var resolvingIndex = -1

        // 连续跳过的放不了的歌数，整个队列都试过一遍就停下；跳过时的提示要留到下一首解析完
        private var unplayableSkips = 0
        private var skipNotice: String? = null

        // 换上地址后又收到空地址留下的“文件不存在”时只重新准备一次，避免真坏的地址来回重试
        private var staleErrorRetried = -1

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val index = controller?.currentMediaItemIndex ?: return
            val song = base.value.queue.getOrNull(index) ?: return
            val track = base.value.canonicalQueue.getOrNull(index)
            val source = track?.sources?.find { it.song.key == song.key }
            base.value = base.value.copy(currentTrack = track, currentSource = source)
            staleErrorRetried = -1
            if (song.url.isNotBlank()) {
                skipNotice = null
                unplayableSkips = 0
                loadLyrics(song)
                return
            }
            if (track == null) return
            resolveQueueItem(index, track)
        }

        /**
         * 解析队列里还没有地址的这一首。播放器里它是空地址，切过来时多半已经因为打开 "" 报错停下，
         * 所以换上解析出的地址后要重新准备；解析不出来就提示原因并跳到下一首，不停在一首放不了的歌上。
         */
        private fun resolveQueueItem(index: Int, track: CanonicalTrack) {
            if (resolvingIndex == index && resolving?.isActive == true) return
            resolvingIndex = index
            resolving = viewModelScope.launch {
                val local = localFileFor(track)
                if (local != null) {
                    val resolved = local.first.song.copy(url = Uri.fromFile(local.second).toString())
                    controller?.replaceMediaItem(index, mediaItem(resolved))
                    prepareIfStopped()
                    unplayableSkips = 0
                    base.value = base.value.copy(
                        queue = base.value.queue.mapIndexed { i, queued -> if (i == index) resolved else queued },
                        current = resolved,
                        currentSource = local.first,
                        playbackNotice = skipNotice.orEmpty(),
                    )
                    skipNotice = null
                    remember("history", historySong(resolved))
                    loadLyrics(resolved)
                    return@launch
                }
                val pinned = pinnedProviders[track.id]
                val result = resolvePlayable(track, pinned)
                val resolution = result.resolution as? Resolution.Ready
                if (resolution == null) {
                    skipUnplayable(index, track.title, noSourceMessage(result.track, result.resolution))
                    return@launch
                }
                val resolved = resolution.source.song.copy(url = resolution.url)
                val currentIndex = controller?.currentMediaItemIndex ?: return@launch
                if (currentIndex != index) return@launch
                controller?.replaceMediaItem(index, mediaItem(resolved))
                prepareIfStopped()
                unplayableSkips = 0
                base.value = base.value.copy(
                    queue = base.value.queue.mapIndexed { i, queued -> if (i == index) resolved else queued },
                    canonicalQueue = base.value.canonicalQueue.mapIndexed { i, queued -> if (i == index) result.track else queued },
                    current = resolved,
                    currentTrack = result.track,
                    currentSource = resolution.source,
                    playbackNotice = when {
                        result.autoMatched -> "原音源不可用，已自动匹配到${resolution.source.providerName}"
                        resolution.isPreview -> "当前为试听片段"
                        else -> skipNotice.orEmpty()
                    },
                )
                skipNotice = null
                rememberCanonical(result.track)
                remember("history", historySong(resolved))
                loadLyrics(resolved)
            }
        }

        /** 播放器因为空地址报错停在 IDLE 时，换好地址后重新准备；playWhenReady 还在，准备好就接着放。 */
        private fun prepareIfStopped() {
            val player = controller ?: return
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }

        /**
         * 第 [index] 首解析不出来：还停在它上面就用播放提示说明原因并跳到下一首；没有下一首，或连着跳过了整个队列 /
         * [MAX_UNPLAYABLE_SKIPS] 首（多半是断网或账号问题，再跳也是白跑），就暂停只留提示。用户已经切走时只提示，不再动播放器。
         */
        private fun skipUnplayable(index: Int, title: String, message: String) {
            val player = controller
            if (player == null || player.currentMediaItemIndex != index) {
                base.value = base.value.copy(playbackNotice = message)
                return
            }
            unplayableSkips++
            if (!player.hasNextMediaItem() || unplayableSkips >= minOf(base.value.queue.size, MAX_UNPLAYABLE_SKIPS)) {
                unplayableSkips = 0
                skipNotice = null
                player.pause()
                base.value = base.value.copy(playbackNotice = message)
                return
            }
            val notice = "已跳过《$title》：$message"
            skipNotice = notice
            base.value = base.value.copy(playbackNotice = notice)
            player.seekToNextMediaItem()
            // 下一首已经有地址就直接重新准备；还是空地址的等它切歌解析完再准备，不让播放器再去打开 ""
            if (base.value.queue.getOrNull(player.currentMediaItemIndex)?.url?.isNotBlank() == true) prepareIfStopped()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (failoverInProgress) return
            val index = controller?.currentMediaItemIndex ?: return
            val track = base.value.canonicalQueue.getOrNull(index) ?: return
            // 还没解析的歌在播放器里是空地址，打开 "" 只会报“文件不存在”：这不是音源坏了，不能走换源、把它的音源排除掉。
            // 正在解析就等它（解析完会重新准备）；没在解析（比如停在这首上又按了播放）就重新解析。
            // 解析恰好赶在这个报错之前换好了地址（地址缓存命中）时，报错来自换掉的空地址，重新准备一次即可
            val queued = base.value.queue.getOrNull(index)
            if (queued != null && queued.url.isBlank()) {
                resolveQueueItem(index, track)
                return
            }
            if (queued != null && queued.url.startsWith("http") && staleErrorRetried != index &&
                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            ) {
                staleErrorRetried = index
                controller?.prepare()
                return
            }
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
                    _position.value = it.currentPosition.coerceAtLeast(0)
                    val duration = it.duration.coerceAtLeast(0)
                    if (base.value.duration != duration) {
                        base.value = base.value.copy(duration = duration)
                    }
                }
                delay(500)
            }
        }
    }

    // ---------- settings ----------

    fun selectProvider(id: String) = viewModelScope.launch {
        app.settings.provider(id)
    }

    fun selectTheme(value: String) = viewModelScope.launch { app.settings.theme(value) }
    fun setLayoutMode(value: LayoutMode) = viewModelScope.launch { app.settings.layoutMode(value.name) }
    fun setColorSource(value: ColorSource) = viewModelScope.launch { app.settings.colorSource(value.name) }
    fun setCustomSeed(value: Int) = viewModelScope.launch {
        app.settings.customSeed(value)
        app.settings.colorSource(ColorSource.Custom.name)
    }
    fun setBrowseStyle(value: SchemeStyle) = viewModelScope.launch { app.settings.browseStyle(value.name) }
    fun setPlayerStyle(value: SchemeStyle) = viewModelScope.launch { app.settings.playerStyle(value.name) }
    fun setContrast(value: Float) = viewModelScope.launch { app.settings.contrast(value) }
    fun setFontScale(value: FontScale) = viewModelScope.launch { app.settings.fontScale(value.scale) }
    fun setAmoled(value: Boolean) = viewModelScope.launch { app.settings.amoled(value) }
    fun setBlurMode(value: BlurMode) = viewModelScope.launch { app.settings.blurMode(value.name) }

    // ---------- sleep timer ----------

    fun startSleepTimer(minutes: Int) = app.sleepTimer.start(minutes, settingsState.value.sleepFade)
    fun sleepAfterCurrent() = app.sleepTimer.stopAfterCurrent()
    fun cancelSleepTimer() = app.sleepTimer.cancel()
    fun setSleepFade(value: Boolean) = viewModelScope.launch { app.settings.sleepFade(value) }
    fun setReduceMotion(value: Boolean) = viewModelScope.launch { app.settings.reduceMotion(value) }
    fun setLyricsBackground(value: LyricsBackgroundMode) = viewModelScope.launch { app.settings.lyricsBackground(value) }
    fun setRhythmIntensity(value: RhythmIntensity) = viewModelScope.launch { app.settings.rhythmIntensity(value) }
    fun setCoverShape(value: CoverShapeChoice) = viewModelScope.launch { app.settings.coverShape(value) }
    fun setTvAmbientClock(value: Boolean) = viewModelScope.launch { app.settings.tvAmbientClock(value) }
    fun setShowBottomNav(value: Boolean) = viewModelScope.launch { app.settings.showBottomNav(value) }

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

    fun clearMatchCache() {
        searchEngine.clearCache()
        resolver.clearCache()
        viewModelScope.launch(Dispatchers.IO) { runCatching { aggregateDao.clearMatches() } }
        message("已清除匹配缓存")
    }

    fun clearMediaCache() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { app.mediaCache.keys.toList().forEach { app.mediaCache.removeResource(it) } }
        runCatching { File(app.cacheDir, SodaProvider.SODA_CACHE_DIR).deleteRecursively() }
        message("已清除播放缓存")
    }

    suspend fun mediaCacheSize(): Long = withContext(Dispatchers.IO) {
        runCatching {
            val sodaCache = File(app.cacheDir, SodaProvider.SODA_CACHE_DIR)
                .listFiles()?.sumOf { it.length() } ?: 0L
            app.mediaCache.cacheSpace + sodaCache
        }.getOrDefault(0L)
    }

    fun setCacheLimit(mb: Int) = viewModelScope.launch { app.settings.cacheLimitMb(mb) }

    fun startLogin(providerId: String = settingsState.value.provider, type: String = "qq") {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            val created = app.repository.createLogin(providerId, type)
            base.value = base.value.copy(login = created, loginStatus = "等待扫码", loginProviderId = providerId)
            repeat(40) {
                delay(3000)
                val result = app.repository.checkLogin(created.sessionId, created.session.key)
                base.value = base.value.copy(loginStatus = result.message.ifBlank { result.status })
                if (result.status == "success" && result.cookie.isNotBlank()) {
                    app.settings.cookie(providerId, result.cookie)
                    app.sync.onLocalChange()
                    base.value = base.value.copy(login = null, loginProviderId = "")
                    return@launch
                }
                if (result.status == "expired" || result.status == "failed") return@launch
            }
        }
    }

    fun saveCookie(providerId: String = settingsState.value.provider, cookie: String) = viewModelScope.launch {
        resolver.clearCache()
        searchEngine.clearCache()
        app.settings.cookie(providerId, cookie)
        app.sync.onLocalChange()
        base.value = base.value.copy(login = null, loginProviderId = "")
        message("Cookie 已保存")
    }

    /** 拉取登录账号的资料给账号卡片；失败就保持空，界面只显示“已登录”。 */
    private fun refreshAccountProfile(providerId: String, cookie: String) {
        if (accountInfoJobs[providerId]?.isActive == true) return
        accountInfoJobs[providerId] = viewModelScope.launch {
            val info = runCatching { app.repository.accountInfo(providerId, cookie) }.getOrNull() ?: return@launch
            val nickname = info.nickname.ifBlank { providerName(providerId) }
            _accountProfiles.update { current ->
                current + (providerId to AccountProfile(providerId, nickname, info.avatar, info.detail))
            }
        }
    }

    fun saveSodaSigner(url: String, token: String) = viewModelScope.launch {
        resolver.clearCache()
        app.settings.sodaSigner(url, token)
        SodaProvider.configureSigner(url, token)
        message(if (url.isBlank()) "已关闭汽水签名器" else "汽水签名器已保存")
    }

    fun logout(providerId: String = settingsState.value.provider) = viewModelScope.launch {
        resolver.clearCache()
        searchEngine.clearCache()
        app.settings.cookie(providerId, "")
        app.sync.onLocalChange()
        message("已退出平台账号")
    }

    /**
     * 设置页的扫码登录：状态写成中文短句（界面按“失败 / 已过期”判断能否刷新），
     * 获取二维码或轮询出错时不让异常冒出协程。
     */
    fun startQrLogin(providerId: String, type: String) {
        loginJob?.cancel()
        base.value = base.value.copy(login = null, loginStatus = "", loginProviderId = providerId)
        loginJob = viewModelScope.launch {
            val created = try {
                app.repository.createLogin(providerId, type)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                base.value = base.value.copy(login = null, loginStatus = "二维码获取失败：${error.message ?: "网络错误"}")
                return@launch
            }
            base.value = base.value.copy(login = created, loginStatus = "等待扫码", loginProviderId = providerId)
            repeat(40) {
                delay(3000)
                val result = try {
                    app.repository.checkLogin(created.sessionId, created.session.key)
                } catch (error: CancellationException) {
                    throw error
                } catch (transient: Exception) {
                    // 单次轮询失败多半是网络抖动，继续等下一轮
                    return@repeat
                }
                when (result.status) {
                    "success" -> if (result.cookie.isNotBlank()) {
                        resolver.clearCache()
                        searchEngine.clearCache()
                        app.settings.cookie(providerId, result.cookie)
                        app.sync.onLocalChange()
                        base.value = base.value.copy(login = null, loginStatus = "", loginProviderId = "")
                        message("登录成功")
                        return@launch
                    }
                    "scanned" -> base.value = base.value.copy(loginStatus = "已扫码，请在手机上确认")
                    "expired" -> {
                        base.value = base.value.copy(loginStatus = "二维码已过期")
                        return@launch
                    }
                    "failed" -> {
                        base.value = base.value.copy(loginStatus = "登录失败：${result.message.ifBlank { "平台拒绝了这次登录" }}")
                        return@launch
                    }
                    else -> base.value = base.value.copy(loginStatus = "等待扫码")
                }
            }
            base.value = base.value.copy(loginStatus = "二维码已过期")
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        base.value = base.value.copy(login = null, loginStatus = "", loginProviderId = "")
    }

    // ---------- sync ----------

    fun syncNow() {
        app.sync.requestSync(manual = true)
    }

    fun connectSync(providerId: String, token: String, remote: String) {
        viewModelScope.launch { app.sync.connect(providerId, token.trim(), remote.trim()) }
    }

    fun resolveInitialSync(strategy: SyncStrategy) {
        viewModelScope.launch { app.sync.resolveInitial(strategy) }
    }

    fun cancelInitialSync() = app.sync.cancelInitial()

    fun resolveSyncConflict(playlistId: String, strategy: SyncStrategy) {
        viewModelScope.launch { app.sync.resolveConflict(playlistId, strategy) }
    }

    fun setSyncAutoSync(enabled: Boolean) {
        viewModelScope.launch { app.sync.setAutoSync(enabled) }
    }

    fun setSyncWifiOnly(enabled: Boolean) {
        viewModelScope.launch { app.sync.setWifiOnly(enabled) }
    }

    fun disconnectSync() {
        viewModelScope.launch { app.sync.disconnect() }
    }

    fun exportLibrary(uri: Uri) {
        viewModelScope.launch { app.sync.export(uri) }
    }

    fun importLibrary(uri: Uri, strategy: SyncStrategy = SyncStrategy.Merge) {
        viewModelScope.launch { app.sync.import(uri, strategy) }
    }

    fun syncProviderLabel(id: String): String = when (id) {
        "gist" -> "GitHub Gist"
        "gitee" -> "Gitee 代码片段"
        else -> id
    }

    // ---------- update ----------

    fun checkForUpdates() {
        viewModelScope.launch { app.update.check(manual = true) }
    }

    suspend fun checkForUpdatesNow(): com.mytv.mymusic.data.update.UpdateUiState {
        app.update.check(manual = true)
        return app.update.state.value
    }

    fun downloadUpdate() {
        updateDownloadJob?.cancel()
        updateDownloadJob = viewModelScope.launch {
            if (!app.update.download()) return@launch
            // 下载并校验完成后直接拉起安装器，用户不需要再点一次
            launchUpdateInstaller()
        }
    }

    fun cancelUpdateDownload() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        app.update.cancelDownload()
    }

    fun installUpdate() {
        viewModelScope.launch { launchUpdateInstaller() }
    }

    /** 拉起系统安装器。缺少“安装未知来源”权限时打开系统设置，授权返回后自动继续安装。 */
    private suspend fun launchUpdateInstaller() {
        val file = updateState.value.file ?: return
        when (app.update.install(file)) {
            com.mytv.mymusic.data.update.InstallResult.Launched -> pendingUpdateInstall = false
            com.mytv.mymusic.data.update.InstallResult.NeedsPermission -> {
                pendingUpdateInstall = true
                app.update.openInstallPermissionSettings()
            }
            com.mytv.mymusic.data.update.InstallResult.Failed -> pendingUpdateInstall = false
        }
    }

    fun openInstallPermission() = app.update.openInstallPermissionSettings()

    fun openReleasePage() = app.update.openReleasePage()

    fun skipUpdate() {
        val info = updateState.value.info ?: return
        viewModelScope.launch {
            app.settings.skippedUpdateCode(info.versionCode)
            app.update.skipCurrent()
            app.update.clearError()
        }
    }

    fun clearUpdateError() = app.update.clearError()

    // ---------- equalizer ----------

    fun setEqualizerEnabled(enabled: Boolean) = app.equalizer.setEnabled(enabled)
    fun selectEqualizerPreset(id: String) = app.equalizer.selectPreset(id)
    fun setEqualizerBand(index: Int, db: Float) = app.equalizer.setBandLevel(index, db)
    fun resetEqualizer() = app.equalizer.resetFlat()
    fun saveEqualizerCustom() = app.equalizer.saveAsCustom()

    // ---------- misc ----------

    fun retryCharts() = refreshCharts()
    fun retryPlaylists() = refreshPlaylists()

    fun onAppForeground() {
        app.sync.syncIfStaleOnForeground()
        // 从“安装未知来源”设置页返回：已授权就直接继续安装，不用再点按钮
        if (pendingUpdateInstall && canInstallPackages()) {
            pendingUpdateInstall = false
            viewModelScope.launch { launchUpdateInstaller() }
        }
    }

    private fun canInstallPackages(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 26) return true
        return getApplication<Application>().packageManager.canRequestPackageInstalls()
    }

    /** 进入同步、音乐库或平台账号页面时拉取一次，避免两台设备停在不同版本。 */
    fun syncIfStaleOnScreen() {
        app.sync.syncIfStaleOnScreen()
    }

    /** 电视上离开应用即暂停播放，避免回到桌面后还在响；手机和平板保留后台播放。 */
    fun onAppBackgrounded() {
        val tv = getApplication<Application>().isTelevision() || settingsState.value.layoutMode == LayoutMode.Tv.name
        if (tv) controller?.takeIf { it.playWhenReady }?.pause()
    }

    fun diagnostics(): String {
        val context = getApplication<Application>()
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val code = when {
            info == null -> ""
            android.os.Build.VERSION.SDK_INT >= 28 -> info.longVersionCode.toString()
            else -> info.versionCode.toString()
        }
        val enabled = enabledProviders().joinToString("、") { it.name }
        return buildString {
            appendLine("myMusic 诊断信息")
            appendLine("版本：${info?.versionName.orEmpty()} ($code)")
            appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android：${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("启用平台：$enabled")
            appendLine("同步：${syncState.value.message.ifBlank { syncState.value.error.ifBlank { syncState.value.status.name } }}")
            appendLine("更新：${updateState.value.error.ifBlank { updateState.value.message.ifBlank { updateState.value.phase.name } }}")
            append("本信息不包含 Cookie、令牌或播放地址。")
        }
    }

    private fun message(text: String, error: Boolean = false) {
        if (error) base.value = base.value.copy(error = text) else _events.tryEmit(UiEvent.Message(text))
    }

    override fun onCleared() {
        progressJob?.cancel()
        aggregateSearchJob?.cancel()
        collectionSearchJob?.cancel()
        controller?.release()
        super.onCleared()
    }

    private data class CachedCharts(
        val at: Long,
        val charts: List<TopList>,
        val featuredSongs: List<Song>,
        val hotSongs: List<Song>,
        val error: String,
    )

    private data class CachedRecommendations(val at: Long, val items: List<Playlist>)
    private data class CachedCategory(val at: Long, val items: List<Playlist>)

    private companion object {
        const val BROWSE_CACHE_MS = 10L * 60 * 1000
        val LINK_PATTERN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    }
}

private const val AUTO_MATCH_TIMEOUT_MS = 8_000L
private const val MAX_UNPLAYABLE_SKIPS = 10

private val SavedSongJson = Json { ignoreUnknownKeys = true }

fun SavedSong.song(json: Json = SavedSongJson) = json.decodeFromString<Song>(payload)
fun DownloadRecord.song(json: Json = SavedSongJson) = json.decodeFromString<Song>(payload)

private fun AppSettings.toAppearance() = Appearance(
    theme = theme,
    layout = LayoutMode.entries.find { it.name == layoutMode } ?: LayoutMode.Auto,
    colorSource = ColorSource.entries.find { it.name == colorSource } ?: ColorSource.Cover,
    customSeed = customSeed,
    browseStyle = SchemeStyle.entries.find { it.name == browseStyle } ?: SchemeStyle.TonalSpot,
    playerStyle = SchemeStyle.entries.find { it.name == playerStyle } ?: SchemeStyle.Vibrant,
    contrast = contrast,
    fontScale = FontScale.of(fontScale),
    amoled = amoled,
    blur = BlurMode.entries.find { it.name == blurMode } ?: BlurMode.Auto,
    reduceMotion = reduceMotion,
    lastSeed = lastSeed,
)

/** 从分享链接的域名猜所属平台，返回 provider id，猜不出返回空串。 */
internal fun linkProviderId(link: String): String {
    val lower = link.lowercase()
    return when {
        "163.com" in lower || "163cn.tv" in lower -> "netease"
        "5sing" in lower -> "fivesing"
        "qq.com" in lower -> "qq"
        "kugou" in lower -> "kugou"
        "kuwo" in lower -> "kuwo"
        "migu" in lower -> "migu"
        "bilibili" in lower || "b23.tv" in lower -> "bilibili"
        "apple.com" in lower -> "apple"
        "joox" in lower -> "joox"
        "91q.com" in lower -> "qianqian"
        "qishui.com" in lower -> "soda"
        else -> ""
    }
}
