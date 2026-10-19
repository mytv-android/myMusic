package com.mytv.mymusic.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.mytv.mymusic.MyMusicApp
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
import com.mytv.mymusic.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class MusicUiState(
    val providers: List<ProviderInfo> = emptyList(),
    val providerId: String = "netease",
    val theme: String = "system",
    val cookies: Map<String, String> = emptyMap(),
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
    val loading: Boolean = false,
    val error: String = "",
    val current: Song? = null,
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val queue: List<Song> = emptyList(),
    val lyrics: String = "",
    val playerOpen: Boolean = false,
    val login: QRCreateResult? = null,
    val loginStatus: String = "",
    val page: Int = 1,
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MyMusicApp
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()
    private val base = MutableStateFlow(MusicUiState())
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var loginJob: Job? = null
    val favorites = app.database.dao().songs("favorite").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = app.database.dao().songs("history").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val localPlaylists = app.database.dao().playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val downloads = app.database.dao().downloads().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val state = combine(base, app.settings.state) { ui, settings -> ui.copy(providerId = settings.provider, theme = settings.theme, cookies = settings.cookies) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MusicUiState())

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
    }

    fun selectProvider(id: String) { viewModelScope.launch { app.settings.provider(id); base.value = base.value.copy(page = 1); refreshHome() } }
    fun selectTheme(value: String) { viewModelScope.launch { app.settings.theme(value) } }
    fun query(value: String) { base.value = base.value.copy(query = value) }
    fun searchKind(value: SearchKind) { base.value = base.value.copy(searchKind = value, page = 1); search() }
    fun openPlayer(open: Boolean) { base.value = base.value.copy(playerOpen = open) }
    fun clearError() { base.value = base.value.copy(error = "") }

    fun refreshHome() = launchAction {
        val id = state.value.providerId
        val cookie = cookie()
        val provider = state.value.providers.find { it.id == id }
        val recommended = if (provider?.recommendedPlaylists == true) runCatching { app.repository.recommended(id, cookie) }.getOrDefault(emptyList()) else emptyList()
        val categories = if (provider?.playlistCategories == true) runCatching { app.repository.categories(id, cookie) }.getOrDefault(emptyList()) else emptyList()
        val charts = if (provider?.topLists == true) runCatching { app.repository.topLists(id, cookie) }.getOrDefault(emptyList()) else emptyList()
        val hot = if (provider?.hotSongs == true) runCatching { app.repository.hotSongs(id, cookie, 30) }.getOrDefault(emptyList()) else emptyList()
        base.value = base.value.copy(recommendations = recommended, categories = categories, charts = charts, hotSongs = hot)
    }

    fun loadCategory(categoryId: String, page: Int = state.value.page) = launchAction {
        val lists = app.repository.categoryPlaylists(state.value.providerId, cookie(), categoryId, page, 30)
        base.value = base.value.copy(categoryPlaylists = lists, page = page)
    }

    fun search(page: Int = 1) = launchAction {
        val ui = state.value
        val value = ui.query.trim()
        if (value.isBlank()) return@launchAction
        when (ui.searchKind) {
            SearchKind.Song -> base.value = base.value.copy(songs = app.repository.searchSongs(ui.providerId, cookie(), value))
            SearchKind.Playlist -> base.value = base.value.copy(playlists = app.repository.searchPlaylists(ui.providerId, cookie(), value))
            SearchKind.Album -> base.value = base.value.copy(albums = app.repository.searchAlbums(ui.providerId, cookie(), value))
            SearchKind.Link -> openLink(value)
        }
        base.value = base.value.copy(page = page)
    }

    fun openPlaylist(item: Playlist, album: Boolean) = launchAction {
        val songs = if (album) app.repository.albumSongs(item.source.ifBlank { state.value.providerId }, cookie(), item.id)
        else app.repository.playlistSongs(item.source.ifBlank { state.value.providerId }, cookie(), item.id)
        base.value = base.value.copy(collectionTitle = item.name, collectionSongs = songs)
    }

    fun openChart(item: TopList) = launchAction {
        base.value = base.value.copy(collectionTitle = item.name, collectionSongs = app.repository.topListSongs(item.source, cookie(), item.id))
    }

    fun openLink(link: String) = launchAction {
        val id = state.value.providerId
        val result = runCatching { app.repository.parsePlaylist(id, cookie(), link) }.getOrNull()
            ?: runCatching { app.repository.parseAlbum(id, cookie(), link) }.getOrNull()
        if (result != null) base.value = base.value.copy(collectionTitle = result.item.name, collectionSongs = result.songs)
        else play(app.repository.parseSong(id, cookie(), link), listOf(app.repository.parseSong(id, cookie(), link)))
    }

    fun play(song: Song, queue: List<Song> = base.value.collectionSongs.ifEmpty { base.value.songs.ifEmpty { base.value.hotSongs } }) {
        viewModelScope.launch {
            val selected = song.copy(url = song.url.ifBlank { runCatching { app.repository.downloadUrl(song, cookie()) }.getOrDefault("") })
            val resolved = listOf(selected) + queue.filter { it.key != song.key }
            val playable = resolved.find { it.key == song.key } ?: return@launch
            val player = controller ?: return@launch
            player.setMediaItems(resolved.map(::mediaItem), 0, 0)
            player.prepare(); player.play()
            base.value = base.value.copy(queue = resolved, current = playable, playerOpen = true)
            remember("history", playable)
            loadLyrics(playable)
        }
    }

    fun toggleFavorite(song: Song) = viewModelScope.launch {
        if (favorites.value.any { it.key == song.key }) app.database.dao().remove("favorite", song.key) else remember("favorite", song)
    }

    fun transport(action: String) {
        val player = controller ?: return
        when (action) {
            "play" -> if (player.isPlaying) player.pause() else player.play()
            "next" -> player.seekToNextMediaItem()
            "previous" -> player.seekToPreviousMediaItem()
            "shuffle" -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            "repeat" -> player.repeatMode = when (player.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        }
    }

    fun seek(position: Long) { controller?.seekTo(position) }

    fun download(song: Song) = viewModelScope.launch {
        val url = song.url.ifBlank { runCatching { app.repository.downloadUrl(song, cookie()) }.getOrDefault("") }
        if (url.isBlank()) { fail("没有可下载的地址"); return@launch }
        val file = File(getApplication<Application>().getExternalFilesDir("music"), "${song.name}-${song.artist}.${song.ext.ifBlank { "mp3" }}".replace(Regex("[\\\\/:*?\"<>|]"), "_"))
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
    fun openLocalPlaylist(playlist: LocalPlaylist) { base.value = base.value.copy(collectionTitle = playlist.name, collectionSongs = json.decodeFromString(playlist.songs)) }

    fun startLogin(type: String = "qq") {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            val created = app.repository.createLogin(state.value.providerId, type)
            base.value = base.value.copy(login = created, loginStatus = "等待扫码")
            repeat(40) {
                delay(3000)
                val result = app.repository.checkLogin(created.sessionId, created.session.key)
                base.value = base.value.copy(loginStatus = result.message.ifBlank { result.status })
                if (result.status == "success" && result.cookie.isNotBlank()) { app.settings.cookie(state.value.providerId, result.cookie); base.value = base.value.copy(login = null); return@launch }
                if (result.status == "expired" || result.status == "failed") return@launch
            }
        }
    }

    fun saveCookie(cookie: String) = viewModelScope.launch { app.settings.cookie(state.value.providerId, cookie); base.value = base.value.copy(login = null) }
    fun logout() = viewModelScope.launch { app.settings.cookie(state.value.providerId, "") }

    private fun loadLyrics(song: Song) = viewModelScope.launch {
        base.value = base.value.copy(lyrics = runCatching { app.repository.lyrics(song, cookie()) }.getOrDefault(""))
    }

    private fun launchAction(block: suspend () -> Unit) = viewModelScope.launch {
        base.value = base.value.copy(loading = true, error = "")
        runCatching { block() }.onFailure { fail(it.message ?: "请求失败") }
        base.value = base.value.copy(loading = false)
    }

    private fun fail(message: String) { base.value = base.value.copy(error = message, loading = false) }
    private fun cookie() = state.value.cookies[state.value.providerId].orEmpty()
    private suspend fun remember(bucket: String, song: Song) = app.database.dao().save(SavedSong(song.key, bucket, json.encodeToString(song), System.currentTimeMillis()))

    private fun mediaItem(song: Song) = MediaItem.Builder().setMediaId(song.key).setUri(song.url).setMediaMetadata(
        MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album).setArtworkUri(song.cover.takeIf { it.isNotBlank() }?.let(Uri::parse)).build()
    ).build()

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val song = base.value.queue.getOrNull(controller?.currentMediaItemIndex ?: return) ?: return
            if (song.url.isNotBlank()) return
            viewModelScope.launch {
                val url = runCatching { app.repository.downloadUrl(song, cookie()) }.getOrDefault("")
                if (url.isBlank()) return@launch
                val resolved = song.copy(url = url)
                val index = controller?.currentMediaItemIndex ?: return@launch
                controller?.replaceMediaItem(index, mediaItem(resolved))
                base.value = base.value.copy(queue = base.value.queue.mapIndexed { itemIndex, queued -> if (itemIndex == index) resolved else queued })
            }
        }
        override fun onEvents(player: Player, events: Player.Events) {
            val song = base.value.queue.getOrNull(player.currentMediaItemIndex)
            base.value = base.value.copy(current = song ?: base.value.current, playing = player.isPlaying, duration = player.duration.coerceAtLeast(0), shuffle = player.shuffleModeEnabled, repeat = when (player.repeatMode) { Player.REPEAT_MODE_ONE -> RepeatMode.One; Player.REPEAT_MODE_ALL -> RepeatMode.All; else -> RepeatMode.Off })
        }
    }

    private fun startProgress() { progressJob = viewModelScope.launch { while (true) { controller?.let { base.value = base.value.copy(position = it.currentPosition.coerceAtLeast(0), duration = it.duration.coerceAtLeast(0)) }; delay(500) } } }
    override fun onCleared() { progressJob?.cancel(); controller?.release(); super.onCleared() }
}

fun SavedSong.song(json: Json = Json { ignoreUnknownKeys = true }) = json.decodeFromString<Song>(payload)
fun DownloadRecord.song(json: Json = Json { ignoreUnknownKeys = true }) = json.decodeFromString<Song>(payload)
