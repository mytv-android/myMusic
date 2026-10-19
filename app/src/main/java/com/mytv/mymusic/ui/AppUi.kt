package com.mytv.mymusic.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mytv.mymusic.data.LibrarySection
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.SearchKind
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.launch

private enum class Destination(val label: String, val icon: ImageVector) { Home("首页", Icons.Default.Home), Search("搜索", Icons.Default.Search), Library("音乐库", Icons.AutoMirrored.Filled.QueueMusic), Settings("设置", Icons.Default.Settings) }

@Composable
fun MyMusicApp(state: MusicUiState, width: WindowWidthSizeClass, favorites: List<Song>, history: List<Song>, downloads: List<com.mytv.mymusic.data.DownloadRecord>, playlists: List<com.mytv.mymusic.data.LocalPlaylist>, actions: MusicActions) {
    var destination by remember { mutableStateOf(Destination.Home) }
    val snackbar = remember { SnackbarHostState() }
    val television = isTelevision()
    val rail = television || width != WindowWidthSizeClass.Compact
    LaunchedEffect(state.error) { if (state.error.isNotBlank()) snackbar.showSnackbar(state.error) }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = { if (!rail) BottomDestinationBar(destination) { destination = it }; PlayerBar(state, actions) }) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (event.key != Key.MediaPlayPause) return@onPreviewKeyEvent false
            actions.transport("play")
            true
        }) {
            if (rail) RailDestinationBar(destination, television) { destination = it }
            Box(Modifier.weight(1f).padding(if (television) 28.dp else 16.dp)) {
                when (destination) {
                    Destination.Home -> HomePage(state, television, actions)
                    Destination.Search -> SearchPage(state, television, actions)
                    Destination.Library -> LibraryPage(favorites, history, downloads, playlists, actions)
                    Destination.Settings -> SettingsPage(state, actions)
                }
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.TopEnd))
            }
        }
    }
    if (state.playerOpen && state.current != null) PlayerPage(state, television, actions)
}

class MusicActions(private val model: MusicViewModel) {
    fun provider(id: String) = model.selectProvider(id)
    fun theme(value: String) = model.selectTheme(value)
    fun query(value: String) = model.query(value)
    fun searchKind(value: SearchKind) = model.searchKind(value)
    fun search() = model.search()
    fun category(id: String) = model.loadCategory(id)
    fun playlist(item: Playlist, album: Boolean) = model.openPlaylist(item, album)
    fun chart(item: com.mytv.mymusic.data.TopList) = model.openChart(item)
    fun play(song: Song, queue: List<Song>) = model.play(song, queue)
    fun favorite(song: Song) = model.toggleFavorite(song)
    fun download(song: Song) = model.download(song)
    fun transport(action: String) = model.transport(action)
    fun seek(position: Long) = model.seek(position)
    fun closePlayer() = model.openPlayer(false)
    fun openPlayer() = model.openPlayer(true)
    fun login(type: String) = model.startLogin(type)
    fun cookie(value: String) = model.saveCookie(value)
    fun logout() = model.logout()
    fun createPlaylist(name: String) = model.createPlaylist(name)
    fun deleteDownload(record: com.mytv.mymusic.data.DownloadRecord) = model.deleteDownload(record)
    fun openLocal(playlist: com.mytv.mymusic.data.LocalPlaylist) = model.openLocalPlaylist(playlist)
}

@Composable private fun BottomDestinationBar(current: Destination, select: (Destination) -> Unit) { NavigationBar { Destination.entries.forEach { NavigationBarItem(current == it, { select(it) }, icon = { Icon(it.icon, it.label) }, label = { Text(it.label) }) } } }
@Composable private fun RailDestinationBar(current: Destination, television: Boolean, select: (Destination) -> Unit) { NavigationRail(Modifier.width(if (television) 112.dp else 88.dp)) { Destination.entries.forEach { NavigationRailItem(current == it, { select(it) }, icon = { Icon(it.icon, it.label) }, label = { Text(it.label) }) } } }

@Composable private fun HomePage(state: MusicUiState, television: Boolean, actions: MusicActions) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { ProviderPicker(state, actions) }
        item { Section("推荐歌单") { MediaRow(state.recommendations, television) { actions.playlist(it, false) } } }
        item { Section("排行榜") { MediaRow(state.charts.map { Playlist(it.id, it.name, it.cover, source = it.source) }, television) { selected -> state.charts.find { it.id == selected.id }?.let(actions::chart) } } }
        item { Section("分类") { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(state.categories) { FocusChip(it.name) { actions.category(it.id) } } } } }
        if (state.categoryPlaylists.isNotEmpty()) item { MediaGrid(state.categoryPlaylists, television) { actions.playlist(it, false) } }
        item { Section("热门单曲") { SongList(state.hotSongs, actions) } }
        if (state.collectionSongs.isNotEmpty()) item { Section(state.collectionTitle.ifBlank { "歌曲" }) { SongList(state.collectionSongs, actions) } }
    }
}

@Composable private fun SearchPage(state: MusicUiState, television: Boolean, actions: MusicActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(state.query, actions::query, Modifier.fillMaxWidth(), label = { Text("歌曲、歌单、专辑或链接") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions { actions.search() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SearchKind.entries.forEach { FilterChip(state.searchKind == it, { actions.searchKind(it) }, { Text(it.label) }) } }
        Button(actions::search) { Text("搜索") }
        LazyColumn { when (state.searchKind) { SearchKind.Song -> item { SongList(state.songs, actions) }; SearchKind.Playlist -> item { MediaGrid(state.playlists, television) { actions.playlist(it, false) } }; SearchKind.Album -> item { MediaGrid(state.albums, television) { actions.playlist(it, true) } }; SearchKind.Link -> item { if (state.collectionSongs.isNotEmpty()) SongList(state.collectionSongs, actions) } } }
    }
}

@Composable private fun LibraryPage(favorites: List<Song>, history: List<Song>, downloads: List<com.mytv.mymusic.data.DownloadRecord>, playlists: List<com.mytv.mymusic.data.LocalPlaylist>, actions: MusicActions) {
    var section by remember { mutableStateOf(LibrarySection.Favorites) }
    var playlistName by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { LibrarySection.entries.forEach { FilterChip(section == it, { section = it }, { Text(it.label) }) } }
        when (section) {
            LibrarySection.Favorites -> SongList(favorites, actions)
            LibrarySection.History -> SongList(history, actions)
            LibrarySection.Downloads -> LazyColumn { items(downloads) { record -> Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(record.song().title + " · " + record.state); TextButton({ actions.deleteDownload(record) }) { Text("删除") } } } }
            LibrarySection.Playlists -> Column { Row { OutlinedTextField(playlistName, { playlistName = it }, label = { Text("新歌单名称") }); Button({ actions.createPlaylist(playlistName); playlistName = "" }) { Text("创建") } }; playlists.forEach { TextButton({ actions.openLocal(it) }) { Text(it.name) } } }
        }
    }
}

@Composable private fun SettingsPage(state: MusicUiState, actions: MusicActions) {
    var cookie by remember(state.providerId) { mutableStateOf(state.cookies[state.providerId].orEmpty()) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("外观", style = MaterialTheme.typography.titleLarge) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (id, label) -> FilterChip(state.theme == id, { actions.theme(id) }, { Text(label) }) } } }
        item { Text("平台账号", style = MaterialTheme.typography.titleLarge) }
        item { ProviderPicker(state, actions) }
        item { state.providers.find { it.id == state.providerId }?.note?.takeIf { it.isNotBlank() }?.let { Text(it) } }
        item { OutlinedTextField(cookie, { cookie = it }, Modifier.fillMaxWidth(), label = { Text("Cookie") }) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ actions.cookie(cookie) }) { Text("保存 Cookie") }; if (state.providers.find { it.id == state.providerId }?.qrLogin == true) Button({ actions.login(if (state.providerId == "qq") "qq" else "") }) { Text("扫码登录") }; TextButton(actions::logout) { Text("退出") } } }
        state.login?.let { item { Text("扫码地址：${it.session.url}\n${state.loginStatus}") } }
        item { Text("myMusic 基于 AGPL-3.0 的 music-lib（${com.mytv.mymusic.musicbridge.Musicbridge.sourceRevision()}）。源码发布于 https://github.com/mytv-android/myMusic。") }
    }
}

@Composable private fun PlayerBar(state: MusicUiState, actions: MusicActions) {
    val song = state.current ?: return
    Surface(Modifier.fillMaxWidth().focusable().clickable(onClick = actions::openPlayer), tonalElevation = 3.dp) { Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Cover(song.cover, 48); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); LinearProgressIndicator(if (state.duration > 0) state.position / state.duration.toFloat() else 0f, Modifier.fillMaxWidth()) }; TransportButton(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停") { actions.transport("play") } } }
}

@Composable private fun PlayerPage(state: MusicUiState, television: Boolean, actions: MusicActions) {
    val song = state.current ?: return
    Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(if (television) 48.dp else 20.dp), Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text(song.source); TextButton(actions::closePlayer) { Text("关闭") } }
        if (television) Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) { Cover(song.cover, 320); PlayerControls(state, song, actions, Modifier.weight(1f)) } else { Cover(song.cover, 240); PlayerControls(state, song, actions, Modifier.fillMaxWidth()) }
    } }
}

@Composable private fun PlayerControls(state: MusicUiState, song: Song, actions: MusicActions, modifier: Modifier) { Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(song.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2); Text(song.subtitle); Slider((if (state.duration > 0) state.position / state.duration.toFloat() else 0f), { actions.seek((it * state.duration).toLong()) }); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TransportButton(Icons.Default.SkipPrevious, "上一首") { actions.transport("previous") }; TransportButton(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停") { actions.transport("play") }; TransportButton(Icons.Default.SkipNext, "下一首") { actions.transport("next") }; TextButton({ actions.transport("shuffle") }) { Text(if (state.shuffle) "随机开" else "随机关") }; TextButton({ actions.transport("repeat") }) { Text(state.repeat.name) }; TransportButton(Icons.Default.FavoriteBorder, "收藏") { actions.favorite(song) }; TransportButton(Icons.Default.Download, "下载") { actions.download(song) } }; Text(state.lyrics.ifBlank { "这个平台没有返回歌词" }, maxLines = 12) } }

@Composable private fun ProviderPicker(state: MusicUiState, actions: MusicActions) { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(state.providers) { FocusChip(it.name, state.providerId == it.id) { actions.provider(it.id) } } } }
@Composable private fun Section(title: String, content: @Composable () -> Unit) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); content() } }
@Composable private fun MediaRow(items: List<Playlist>, television: Boolean, select: (Playlist) -> Unit) { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(items) { MediaCard(it, television, select) } } }
@Composable private fun MediaGrid(items: List<Playlist>, television: Boolean, select: (Playlist) -> Unit) { LazyVerticalGrid(GridCells.Adaptive(if (television) 180.dp else 132.dp), contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(520.dp)) { items(items) { MediaCard(it, television, select) } } }
@Composable private fun MediaCard(item: Playlist, television: Boolean, select: (Playlist) -> Unit) { val size = if (television) 168.dp else 124.dp; FocusableColumn({ select(item) }, Modifier.width(size)) { Cover(item.cover, size.value.toInt()); Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(item.creator, maxLines = 1, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun SongList(songs: List<Song>, actions: MusicActions) { Column { songs.forEach { song -> FocusableRow({ actions.play(song, songs) }) { Cover(song.cover, 52); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }; Text(song.source); Icon(Icons.Default.FavoriteBorder, "收藏", Modifier.clickable { actions.favorite(song) }.padding(8.dp)); Icon(Icons.Default.Download, "下载", Modifier.clickable { actions.download(song) }.padding(8.dp)) } } } }
@Composable private fun Cover(url: String, size: Int) { AsyncImage(url, null, Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop) }
@Composable private fun FocusChip(text: String, selected: Boolean = false, onClick: () -> Unit) { var focused by remember { mutableStateOf(false) }; Surface(onClick, shape = RoundedCornerShape(50), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, modifier = Modifier.onFocusChanged { focused = it.isFocused }.border(if (focused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))) { Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) } }
@Composable private fun FocusableColumn(onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) { var focused by remember { mutableStateOf(false) }; Column(modifier.clickable(onClick = onClick).onFocusChanged { focused = it.isFocused }.border(if (focused) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } }
@Composable private fun FocusableRow(onClick: () -> Unit, content: @Composable () -> Unit) { var focused by remember { mutableStateOf(false) }; Row(Modifier.fillMaxWidth().clickable(onClick = onClick).onFocusChanged { focused = it.isFocused }.background(if (focused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface).padding(6.dp), verticalAlignment = Alignment.CenterVertically) { content() } }
@Composable private fun TransportButton(icon: ImageVector, label: String, onClick: () -> Unit) { var focused by remember { mutableStateOf(false) }; Surface(onClick, Modifier.size(48.dp).onFocusChanged { focused = it.isFocused }.border(if (focused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)), shape = RoundedCornerShape(24.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, label) } } }
@Composable private fun isTelevision(): Boolean { val configuration = LocalConfiguration.current; val uiMode = LocalContext.current.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK; return uiMode == Configuration.UI_MODE_TYPE_TELEVISION || (configuration.smallestScreenWidthDp >= 600 && configuration.screenWidthDp >= 960) }
