package com.mytv.mymusic.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.filled.CallMerge
import com.mytv.mymusic.ui.components.tvFocusRing
import androidx.compose.material3.toShape
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.heading
import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mytv.mymusic.ui.adaptive.DialogAvoidsFold
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.PlaybackMode
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.aggregate.availabilityOf
import com.mytv.mymusic.data.CrossfadeAnimation
import com.mytv.mymusic.data.CrossfadeMode
import com.mytv.mymusic.data.native.AudioQuality
import com.mytv.mymusic.data.DownloadRecord
import com.mytv.mymusic.data.MiniPlayerTarget
import com.mytv.mymusic.data.SearchKind
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList
import com.mytv.mymusic.data.sync.SyncStrategy
import com.mytv.mymusic.data.update.InstallResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MusicActions(private val model: MusicViewModel) {
    fun selectChartSource(id: String) = model.selectChartSource(id)
    fun refreshCharts() = model.refreshCharts()
    fun retryCharts() = model.retryCharts()
    fun openChart(chart: TopList) = model.openChart(chart)

    fun selectPlaylistSource(id: String) = model.selectPlaylistSource(id)
    fun refreshPlaylists() = model.refreshPlaylists()
    fun setPlaylistMode(mode: PlaylistMode) = model.setPlaylistMode(mode)
    fun selectListenGroup(group: String) = model.selectListenGroup(group)
    fun refreshListeningModes() = model.refreshListeningModes()
    fun playListeningMode(mode: com.mytv.mymusic.data.ListeningMode) = model.playListeningMode(mode)
    fun quickListen() = model.quickListen()
    fun setCategoryGroup(group: String) = model.setCategoryGroup(group)
    fun selectCategory(category: com.mytv.mymusic.data.PlaylistCategory) = model.selectCategory(category)
    fun clearCategory() = model.clearCategory()
    fun categoryPage(page: Int) = model.categoryPage(page)
    fun openPlaylist(playlist: com.mytv.mymusic.data.Playlist, album: Boolean = false) = model.openPlaylist(playlist, album)

    fun openArtist(playlist: com.mytv.mymusic.data.Playlist) = model.openArtist(playlist)
    /** 歌手页的专辑卡片、专辑页的“更多专辑”。 */
    fun openAlbum(album: com.mytv.mymusic.data.AlbumSummary) = model.openAlbum(album)
    /** 歌曲上的歌手名 → 歌手页；[song] 决定先在哪个平台上找。 */
    fun openArtistByName(name: String, song: Song? = null) = model.openArtistByName(name, song)
    /** 歌曲 → 它所在的专辑页。 */
    fun openAlbumOf(song: Song) = model.openAlbumOf(song)
    fun retryArtist(key: String) = model.retryArtist(key)
    fun loadMoreArtistAlbums(key: String) = model.loadMoreArtistAlbums(key)
    fun playArtist(key: String, shuffle: Boolean = false) = model.playArtist(key, shuffle)
    fun queueArtist(key: String) = model.queueArtist(key)
    fun saveArtistSongs(key: String) = model.saveArtistSongs(key)
    fun playCollection(openPlayer: Boolean = true) = model.playCollection(openPlayer)
    fun saveCollection() = model.saveCollectionToLibrary()
    fun retryCollection() = model.retryCollection()
    fun closeCollection() = model.closeCollection()
    fun loadMoreCategory() = model.loadMoreCategory()
    fun retryCategory() = model.retryCategory()
    fun playChart(chart: TopList) = model.playChart(chart)
    fun playPlaylist(playlist: com.mytv.mymusic.data.Playlist, album: Boolean = false) = model.playPlaylist(playlist, album)
    fun queueCollection() = model.enqueueCollection()
    suspend fun coverSeed(url: String): Int? = model.coverSeed(url)

    fun setSearchQuery(value: String) = model.setSearchQuery(value)
    fun submitSearch(value: String) = model.submitSearch(value)
    fun setSearchKind(kind: SearchKind) = model.setSearchKind(kind)
    fun clearSearch() = model.clearSearch()
    fun removeHistory(query: String) = model.removeSearchHistory(query)
    fun clearHistory() = model.clearSearchHistory()
    fun searchMore() = model.searchMore()
    fun isLink(value: String) = model.isLinkQuery(value)
    fun openLink(value: String) = model.openLink(value)
    fun restoreHistory(items: List<String>) = model.restoreSearchHistory(items)
    fun retryFailedSearch() = model.retryFailedSearch()

    fun openSource(track: CanonicalTrack, queue: List<CanonicalTrack>) = model.openSource(track, queue)
    fun openSourceForSong(song: Song, queue: List<Song>) = model.openSourceForSong(song, queue)
    fun closeSource() = model.closeSource()
    fun chooseSource(providerId: String, remember: Boolean) = model.chooseSource(providerId, remember)
    fun playAlternative(track: CanonicalTrack, source: TrackSource) = model.playAlternative(track, source)
    fun splitSource(track: CanonicalTrack, source: TrackSource) = model.splitSource(track, source)
    fun openMerge(track: CanonicalTrack) = model.openMerge(track)
    fun closeMerge() = model.closeMerge()
    fun mergeTo(track: CanonicalTrack) = model.mergeTo(track)
    fun mergeAlternative(track: CanonicalTrack, source: TrackSource) = model.mergeAlternative(track, source)

    /** [openPlayer] 为 false 时不弹出播放页（详情页海报视图里的播放全部 / 随机 / 大卡上的“播放”）。 */
    fun play(song: Song, queue: List<Song>, openPlayer: Boolean = true) = model.play(song, queue, openPlayer)
    fun playLocalPlaylist(song: Song, queue: List<Song>, playlistId: String, title: String, openPlayer: Boolean = true) =
        model.playLocalPlaylist(song, queue, playlistId, title, openPlayer)
    fun playSavedSongs(records: List<com.mytv.mymusic.data.SavedSong>) = model.playSavedSongs(records)
    fun playTrack(track: CanonicalTrack, queue: List<CanonicalTrack>) = model.playTrack(track, queue)
    fun transport(action: String) = model.transport(action)
    fun startRecognition() = model.startRecognition()
    fun dismissRecognition() = model.dismissRecognition()
    fun seek(position: Long) = model.seek(position)
    fun openPlayer() = model.openPlayer(true)
    fun closePlayer() = model.openPlayer(false)
    fun openPosterWall() = model.openPosterWall()
    fun closePosterWall() = model.closePosterWall()
    /** 迷你播放器等“正在播放”入口的点按：按设置打开播放页或海报墙。写着“打开播放页 / 全屏播放”的按钮仍用 [openPlayer]。 */
    fun openNowPlaying() = model.openNowPlaying()
    fun openPlaybackOrigin() = model.openPlaybackOrigin()
    fun consumePlaybackLocate() = model.consumePlaybackLocate()
    fun playQueueIndex(index: Int) = model.playQueueIndex(index)
    fun moveQueueItemNext(index: Int) = model.moveQueueItemNext(index)
    /** 任意一首（不一定在队列里）的“下一首播放”，详情页海报视图的大卡用。 */
    fun playSongNext(song: Song) = model.playSongNext(song)
    /** 一首接到队列末尾（滑动的「加到播放队列」）。 */
    fun addToQueue(song: Song) = model.addToQueue(song)
    /** 播放队列移出一首；正在放的不能移，返回 null。返回值交给 [restoreQueueItem] 撤销。 */
    fun removeQueueIndex(index: Int, songKey: String? = null): QueueRemoval? = model.removeQueueIndex(index, songKey)
    fun restoreQueueItem(removal: QueueRemoval) = model.restoreQueueItem(removal)
    /** 播放进度（每 100ms），只在需要画进度的叶子里读，见 MusicViewModel.position。 */
    val position: kotlinx.coroutines.flow.StateFlow<Long> get() = model.position
    fun clearQueue() = model.clearQueue()
    fun favorite(song: Song) = model.toggleFavorite(song)
    fun download(song: Song) = model.download(song)
    /** 收藏到平台账号（汽水的「我喜欢的音乐」）；平台没实现或没登录时由界面提示。 */
    fun collectOnPlatform(song: Song) = model.collectOnPlatform(song)
    fun openDeepLink() = model.openDeepLink()
    fun supportsComments(song: Song): Boolean = model.supportsComments(song)
    suspend fun comments(song: Song, page: Int, limit: Int = 20) = model.songComments(song, page, limit)
    fun clearError() = model.clearError()
    fun clearNotice() = model.clearNotice()

    fun createPlaylist(name: String) = model.createPlaylist(name)
    fun renamePlaylist(id: String, name: String) = model.renamePlaylist(id, name)
    fun deletePlaylist(id: String) = model.deletePlaylist(id)
    fun addSongToPlaylist(id: String, song: Song) = model.addSongToPlaylist(id, song)
    fun addSongsToPlaylist(id: String, songs: List<Song>) = model.addSongsToPlaylist(id, songs)
    fun addSongsToNewPlaylist(name: String, songs: List<Song>) = model.addSongsToNewPlaylist(name, songs)

    /** 自建歌单列表（「加到歌单」面板自己取，播放页不用为它多传一层参数）。 */
    val localPlaylists: kotlinx.coroutines.flow.StateFlow<List<LocalPlaylistSummary>> get() = model.localPlaylists
    /** 这首（含同一首的其他平台版本）在哪些自建歌单里，按歌单 id。 */
    fun playlistsContaining(song: Song): kotlinx.coroutines.flow.Flow<Set<String>> = model.playlistsContaining(song)
    /** 「加到歌单」面板的加入 / 移出 / 新建并加入：提示交回面板显示（见 [PlaylistEditResult]）。 */
    fun addSongToPlaylistQuietly(id: String, song: Song) = model.addSongToPlaylistQuietly(id, song)
    fun removeSongFromPlaylist(id: String, song: Song) = model.removeSongFromPlaylistQuietly(id, song)
    fun createPlaylistWithSong(name: String, song: Song) = model.createPlaylistWithSongQuietly(name, song)
    fun removeEntry(id: String, position: Int) = model.removePlaylistEntry(id, position)
    fun removeEntries(id: String, positions: List<Int>) = model.removePlaylistEntries(id, positions)
    fun moveEntry(id: String, position: Int, delta: Int) = model.movePlaylistEntry(id, position, delta)
    /** [providerId] 非空时在该平台里搜索（绑定歌单的“添加”用），否则用默认浏览来源。 */
    fun searchLibrary(query: String, providerId: String? = null) = model.searchLibrarySongs(query, providerId)
    fun clearLibrarySearch() = model.clearLibrarySearch()

    /** 平台歌单绑定：同步该平台歌单 / 解除绑定。 */
    fun syncBoundPlaylist(id: String) = model.refreshBoundPlaylist(id, manual = true)
    fun refreshBoundPlaylist(id: String) = model.refreshBoundPlaylist(id)
    fun unbindPlaylist(id: String) = model.unbindPlaylist(id)
    fun checkBoundPlaylistAccess(id: String) = model.checkBoundPlaylistAccess(id)

    /** 绑定歌单的可写状态（按本地歌单 id），详情页据此显示只读 / 可修改。 */
    val boundAccess: kotlinx.coroutines.flow.StateFlow<Map<String, BoundPlaylistAccess>>
        get() = model.boundAccess
    fun playDownload(record: DownloadRecord) = model.playDownloaded(record)
    fun playDownloads(records: List<DownloadRecord>) = model.playDownloaded(records)
    fun deleteDownload(record: DownloadRecord) = model.deleteDownload(record)
    fun restoreEntry(entry: com.mytv.mymusic.data.PlaylistEntryEntity) =
        model.insertPlaylistEntry(entry.playlistId, entry.position, entry.songKey, entry.payload)
    fun clearPlayHistory() = model.clearPlayHistory()
    fun removePlayHistory(key: String) = model.removePlayHistory(key)
    fun restoreSaved(songs: List<com.mytv.mymusic.data.SavedSong>) = model.restoreSavedSongs(songs)
    fun importLink(link: String) = model.openLink(link)

    fun selectProvider(id: String) = model.selectProvider(id)
    fun setTheme(value: String) = model.selectTheme(value)
    fun setLayoutMode(value: com.mytv.mymusic.ui.theme.LayoutMode) = model.setLayoutMode(value)
    fun setColorSource(value: com.mytv.mymusic.ui.theme.ColorSource) = model.setColorSource(value)
    fun setCustomSeed(value: Int) = model.setCustomSeed(value)
    fun setBrowseStyle(value: com.mytv.mymusic.ui.theme.SchemeStyle) = model.setBrowseStyle(value)
    fun setPlayerStyle(value: com.mytv.mymusic.ui.theme.SchemeStyle) = model.setPlayerStyle(value)
    fun setContrast(value: Float) = model.setContrast(value)
    fun setFontScale(value: com.mytv.mymusic.ui.theme.FontScale) = model.setFontScale(value)
    fun setAmoled(value: Boolean) = model.setAmoled(value)
    fun setBlurMode(value: com.mytv.mymusic.ui.theme.BlurMode) = model.setBlurMode(value)
    fun setLyricsBackground(value: com.mytv.mymusic.data.LyricsBackgroundMode) = model.setLyricsBackground(value)
    fun setNotificationLyrics(value: Boolean) = model.setNotificationLyrics(value)
    fun setFloatingLyricsEnabled(value: Boolean) = model.setFloatingLyricsEnabled(value)
    fun setLyriconProviderEnabled(value: Boolean) = model.setLyriconProviderEnabled(value)
    fun setLyriconSubscriberEnabled(value: Boolean) = model.setLyriconSubscriberEnabled(value)
    fun setRhythmIntensity(value: com.mytv.mymusic.data.RhythmIntensity) = model.setRhythmIntensity(value)
    fun setCoverShape(value: com.mytv.mymusic.data.CoverShapeChoice) = model.setCoverShape(value)
    fun setTvAmbientClock(value: Boolean) = model.setTvAmbientClock(value)
    fun setTabletopTop(value: com.mytv.mymusic.data.TabletopTop) = model.setTabletopTop(value)
    fun setKeepScreenOn(value: Boolean) = model.setKeepScreenOn(value)
    fun setTvBackgroundPlay(value: Boolean) = model.setTvBackgroundPlay(value)
    fun setRhythmOffset(value: Int) = model.setRhythmOffset(value)
    fun setRhythmDebug(value: Boolean) = model.setRhythmDebug(value)
    fun setPosterPrefs(transform: (com.mytv.mymusic.data.PosterPrefs) -> com.mytv.mymusic.data.PosterPrefs) =
        model.setPosterPrefs(transform)
    fun setSwipePrefs(transform: (com.mytv.mymusic.data.SwipePrefs) -> com.mytv.mymusic.data.SwipePrefs) =
        model.setSwipePrefs(transform)
    fun setSharePrefs(transform: (com.mytv.mymusic.data.SharePrefs) -> com.mytv.mymusic.data.SharePrefs) =
        model.setSharePrefs(transform)
    fun setLyricStage(transform: (com.mytv.mymusic.data.LyricStagePrefs) -> com.mytv.mymusic.data.LyricStagePrefs) =
        model.setLyricStage(transform)
    fun setLyricText(transform: (com.mytv.mymusic.data.LyricTextPrefs) -> com.mytv.mymusic.data.LyricTextPrefs) =
        model.setLyricText(transform)
    suspend fun lyricMemory(songKey: String) = model.lyricMemory(songKey)
    fun rememberLyric(songKey: String, update: (com.mytv.mymusic.data.LyricMemoryEntry) -> com.mytv.mymusic.data.LyricMemoryEntry) =
        model.rememberLyric(songKey, update)
    suspend fun lyricMemoryModeCount() = model.lyricMemoryModeCount()
    suspend fun forgetLyricModes() = model.forgetLyricModes()
    suspend fun restoreLyricModes(modes: Map<String, String>) = model.restoreLyricModes(modes)
    fun setCollectionView(value: com.mytv.mymusic.data.CollectionView) = model.setCollectionView(value)
    fun setMiniPlayerTarget(value: MiniPlayerTarget) = model.setMiniPlayerTarget(value)
    fun startSleepTimer(minutes: Int) = model.startSleepTimer(minutes)
    fun sleepAfterCurrent() = model.sleepAfterCurrent()
    fun cancelSleepTimer() = model.cancelSleepTimer()
    fun setSleepFade(value: Boolean) = model.setSleepFade(value)
    fun setCrossfadeEnabled(value: Boolean) = model.setCrossfadeEnabled(value)
    fun setCrossfadeSeconds(value: Int) = model.setCrossfadeSeconds(value)
    fun setCrossfadeAnimation(value: CrossfadeAnimation) = model.setCrossfadeAnimation(value)
    fun setCrossfadeMode(value: CrossfadeMode) = model.setCrossfadeMode(value)
    fun setReduceMotion(value: Boolean) = model.setReduceMotion(value)
    fun setVolumeNormalization(value: Boolean) = model.setVolumeNormalization(value)
    fun setAudioQuality(value: AudioQuality) = model.setAudioQuality(value)
    fun toggleAggregateProvider(id: String) = model.toggleAggregateProvider(id)
    fun moveSource(id: String, delta: Int) = model.moveSource(id, delta)
    fun resetSources() = model.resetSources()
    fun setStartupTab(route: String) = model.setStartupTab(route)
    fun setPlaybackMode(mode: PlaybackMode) = model.setPlaybackMode(mode)
    fun setPinnedProvider(id: String?) = model.setPinnedProvider(id)
    fun setAllowPreview(value: Boolean) = model.setAllowPreview(value)
    fun setStrictMatch(value: Boolean) = model.setMatchStrictness(
        if (value) com.mytv.mymusic.aggregate.MatchStrictness.Strict
        else com.mytv.mymusic.aggregate.MatchStrictness.Balanced
    )
    fun clearMatchCache() = model.clearMatchCache()
    fun clearMediaCache() = model.clearMediaCache()
    fun setCacheLimit(mb: Int) = model.setCacheLimit(mb)
    fun startLogin(providerId: String, type: String = "qq") = model.startLogin(providerId, type)
    fun saveCookie(providerId: String, cookie: String) = model.saveCookie(providerId, cookie)
    fun loginWithPassword(providerId: String, username: String, password: String) =
        model.loginWithPassword(providerId, username, password)
    fun saveSodaSigner(url: String, token: String, enabled: Boolean = true) =
        model.saveSodaSigner(url, token, enabled)

    /** 重置汽水扫码登录的设备身份，见 MusicViewModel.resetSodaLoginDevice。 */
    fun resetSodaLoginDevice() = model.resetSodaLoginDevice()

    /** Apple Music 匿名歌词中转总开关，见 MusicViewModel.setThirdPartyLyrics。 */
    fun setThirdPartyLyrics(value: Boolean) = model.setThirdPartyLyrics(value)
    fun setMetadataFallback(value: Boolean) = model.setMetadataFallback(value)
    fun importLyricFont(slot: Int, uri: Uri) = model.importLyricFont(slot, uri)
    fun clearLyricFont(slot: Int) = model.clearLyricFont(slot)

    /** 播放上报总开关：是否把播放记录回传给平台（见 PlayReporter）。 */
    fun setPlayReportEnabled(value: Boolean) = model.setPlayReportEnabled(value)
    /** 更新线路（见 GithubMirror）。 */
    fun setGithubMirror(value: com.mytv.mymusic.data.GithubMirror) = model.setGithubMirror(value)
    fun logout(providerId: String) = model.logout(providerId)
    fun startQrLogin(providerId: String, type: String) = model.startQrLogin(providerId, type)
    fun cancelLogin() = model.cancelLogin()

    /** 登录后导入平台歌单（设计稿 docs/platform-import.html）。 */
    fun openPlatformImport(providerId: String) = model.openPlatformImport(providerId)
    fun dismissPlatformImport(forever: Boolean = false) = model.dismissPlatformImport(forever)
    fun applyPlatformImport(ids: List<String>) = model.applyPlatformImport(ids)
    fun resetPlatformImportPrompt(providerId: String) = model.resetPlatformImportPrompt(providerId)
    val platformImport: kotlinx.coroutines.flow.StateFlow<PlatformImportUi> get() = model.platformImport

    fun syncNow() = model.syncNow()
    fun syncIfStaleOnScreen() = model.syncIfStaleOnScreen()
    /** 打开平台账号页时重新拉一遍各平台的头像、昵称、会员等级。 */
    fun refreshAccountProfiles() = model.refreshAccountProfiles()
    fun syncBoundPlaylistsOnScreen() = model.refreshStaleBoundPlaylists()
    fun connectSync(providerId: String, token: String, remote: String) = model.connectSync(providerId, token, remote)
    fun connectWebDav(account: com.mytv.mymusic.data.webdav.WebDavAccount) = model.connectWebDav(account)
    fun testWebDav(account: com.mytv.mymusic.data.webdav.WebDavAccount?) = model.testWebDav(account)
    fun resolveInitial(strategy: SyncStrategy) = model.resolveInitialSync(strategy)
    fun cancelInitial() = model.cancelInitialSync()
    fun resolveConflict(id: String, strategy: SyncStrategy) = model.resolveSyncConflict(id, strategy)
    fun setSyncAutoSync(value: Boolean) = model.setSyncAutoSync(value)
    fun setSyncWifiOnly(value: Boolean) = model.setSyncWifiOnly(value)
    fun disconnectSync() = model.disconnectSync()
    fun exportLibrary(uri: Uri) = model.exportLibrary(uri)
    fun importLibrary(uri: Uri, strategy: SyncStrategy = SyncStrategy.Merge) = model.importLibrary(uri, strategy)

    /** 歌单文件导出（设计稿 06 节）。 */
    suspend fun exportPreview(ids: List<String>) = model.exportPreview(ids)
    suspend fun exportFile(preview: com.mytv.mymusic.data.transfer.ExportPreview) = model.exportFile(preview)
    fun shareExport(preview: com.mytv.mymusic.data.transfer.ExportPreview) = model.shareExport(preview)
    fun saveExport(preview: com.mytv.mymusic.data.transfer.ExportPreview, uri: Uri) = model.saveExport(preview, uri)
    val favorites: kotlinx.coroutines.flow.StateFlow<List<com.mytv.mymusic.data.SavedSong>> get() = model.favorites

    /** 从文件导入：先嗅探，歌单文件 / 洛雪出预览，整库备份照旧合并。 */
    fun openImport(uri: Uri) = model.openImport(uri)
    fun openImport(bytes: ByteArray, displayName: String, fromPhone: Boolean = false) = model.openImport(bytes, displayName, fromPhone)
    fun applyImport(choices: Map<String, com.mytv.mymusic.data.transfer.ImportChoice>) = model.applyImport(choices)
    fun recheckImport() = model.recheckImport()
    fun dismissImport() = model.dismissImport()
    val transfer: kotlinx.coroutines.flow.StateFlow<TransferUi> get() = model.transfer
    val importDone: kotlinx.coroutines.flow.StateFlow<Int> get() = model.importDone

    fun checkUpdates() = model.checkForUpdates()
    suspend fun checkUpdatesNow(): com.mytv.mymusic.data.update.UpdateUiState = model.checkForUpdatesNow()
    fun downloadUpdate() = model.downloadUpdate()
    fun installUpdate() = model.installUpdate()
    fun openInstallPermission() = model.openInstallPermission()
    fun openReleasePage() = model.openReleasePage()
    fun clearUpdateError() = model.clearUpdateError()
    fun dismissUpdate() = model.dismissUpdate()
    fun postponeUpdate() = model.postponeUpdate()
    fun reopenUpdatePrompt() = model.reopenUpdatePrompt()

    fun setEqEnabled(value: Boolean) = model.setEqualizerEnabled(value)
    fun selectEqPreset(id: String) = model.selectEqualizerPreset(id)
    fun setEqBand(index: Int, db: Float) = model.setEqualizerBand(index, db)
    fun resetEq() = model.resetEqualizer()
    fun saveEqCustom() = model.saveEqualizerCustom()

    fun diagnostics(): String = model.diagnostics()
    fun exportLog(): String = model.exportLog()
}

/** “正在播放”入口点按后去哪，读屏和 onClickLabel 用。 */
val MiniPlayerTarget.openLabel: String
    get() = when (this) {
        MiniPlayerTarget.Player -> "打开播放页"
        MiniPlayerTarget.PosterWall -> "打开海报墙"
    }

fun coverInitial(title: String): String = title.trim().firstOrNull()?.toString().orEmpty().ifBlank { "音" }

/**
 * 封面。加载前和没有封面时显示取色容器上的首字，不再用固定的大地色。
 * shape 默认按 corner 取圆角；需要 MaterialShapes 裁切时传入 shape。
 */
@Composable
fun Cover(
    url: String,
    key: String,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    fontSize: Float = size.value * 0.36f,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(corner),
) {
    CoverBox(url, key, modifier.size(size), shape, fontSize)
}

@Composable
fun CoverBox(
    url: String,
    key: String,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    fontSize: Float = 28f,
) {
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            coverInitial(key),
            // 占位首字只是装饰，读屏不念
            modifier = Modifier.clearAndSetSemantics {},
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            fontSize = fontSize.sp,
            lineHeight = fontSize.sp,
            fontWeight = FontWeight.Black,
        )
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
fun CoverBanner(url: String, key: String, height: Dp, modifier: Modifier = Modifier) {
    CoverBox(url, key, modifier.fillMaxWidth().height(height), RoundedCornerShape(28.dp), height.value * 0.32f)
}

/** 筛选 chip：选中时变成 16dp 圆角的 secondaryContainer 并带对勾，未选中是 8dp 圆角描边。 */
@Composable
fun MusicChip(
    text: String,
    selected: Boolean = false,
    description: String? = null,
    onClick: () -> Unit,
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        // 同名选项分属不同组时（浏览页 / 播放页风格），用 description 说明是哪一组
        label = { Text(text, if (description != null) Modifier.semantics { contentDescription = description } else Modifier, maxLines = 1) },
        // 用到的地方都是单选（平台、分类、预设），FilterChip 默认读成“复选框”
        modifier = Modifier.tvFocusRing(RoundedCornerShape(16.dp)).semantics { role = androidx.compose.ui.semantics.Role.RadioButton },
        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
        shape = RoundedCornerShape(if (selected) 16.dp else 8.dp),
    )
}

@Composable
fun InfoNote(text: String, tone: String = "neutral") {
    val (background, color) = when (tone) {
        "green" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "amber" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "red" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(16.dp), color = background, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

/**
 * 空状态：图标放在 MaterialShapes 裁出的装饰形状里（形状只是装饰，不代表内容类型），
 * 标题 + 说明 + 明确的下一步。[secondaryLabel] 是并排的第二个按钮（电视海报视图的“切回列表视图”），
 * 电视焦点落在第一个按钮上（只有第二个时落在它上面）。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    focusAction: Boolean = com.mytv.mymusic.ui.components.LocalAppWindow.current.isTv,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val actionFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val hasPrimary = actionLabel != null && onAction != null
    if (focusAction && (hasPrimary || (secondaryLabel != null && onSecondary != null))) {
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { actionFocus.requestFocus() } }
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(148.dp)
                .clip(androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(
            title,
            Modifier.padding(top = 22.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            message,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if ((actionLabel != null && onAction != null) || (secondaryLabel != null && onSecondary != null)) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (actionLabel != null && onAction != null) {
                    Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).focusRequester(actionFocus).tvFocusRing(RoundedCornerShape(24.dp))) {
                        Text(actionLabel)
                    }
                }
                if (secondaryLabel != null && onSecondary != null) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = onSecondary,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .then(if (hasPrimary) Modifier else Modifier.focusRequester(actionFocus))
                            .tvFocusRing(RoundedCornerShape(24.dp)),
                    ) {
                        Text(secondaryLabel)
                    }
                }
            }
        }
    }
}

/** 失败：说明原因，给出重试。 */
@Composable
fun ErrorBlock(message: String, onRetry: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                message,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (onRetry != null) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = onRetry,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("重试") }
            }
        }
    }
}

@Composable
fun InputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            DialogAvoidsFold()
            Text(title, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            OutlinedTextField(
                value,
                { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value); onDismiss() },
                enabled = value.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "确定",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            DialogAvoidsFold()
            Text(title, style = MaterialTheme.typography.titleLarge)
        },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 电视海报墙的整页操作（播放全部、切回列表视图、海报墙设置……），接在歌曲操作下面成一组。
 * [title] 是组的小标题“类型 · 标题”，读屏和眼睛都靠它分清哪些是这一首、哪些是整页的。
 */
@androidx.compose.runtime.Immutable
class WallMenu(val title: String, val items: List<WallMenuItem>)

/** 整页操作的一行；[summary] 是第二行（已收藏、已绑定这类状态），没有就空着。 */
@androidx.compose.runtime.Immutable
class WallMenuItem(val icon: ImageVector, val label: String, val onClick: () -> Unit, val summary: String = "")

/**
 * 歌曲操作面板里要不要接整页操作：[menu] 打开面板时才取（墙上的长按回调在页面组合之外设，
 * 直接存菜单会让页面每次重组都写一遍状态），[fromMenuKey] 是菜单键打开的——面板滚到这一组、焦点落在第一行。
 */
@androidx.compose.runtime.Immutable
class WallPageActions(val menu: () -> WallMenu?, val fromMenuKey: Boolean)

/**
 * 歌曲操作：手机是底部面板，平板是侧边面板，电视是右侧面板。
 * 顶部三个快捷操作（收藏 / 下载 / 加歌单）用大按钮，其余是列表。
 * [wallPage] 只在电视的海报墙里传：墙内焦点在墙自己手里，墙外不放能拿焦点的工具栏，
 * 整页操作接在歌曲操作下面成一组，从任意格长按 OK 或按菜单键都能进。
 */
@Composable
fun TrackActionsSheet(
    song: Song,
    favorite: Boolean,
    playlists: List<LocalPlaylistSummary>,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onOpenSource: (() -> Unit)?,
    onRemoveFromPlaylist: (() -> Unit)?,
    onDismiss: () -> Unit,
    onMerge: (() -> Unit)? = null,
    wallPage: WallPageActions? = null,
    /**
     * 本地歌的「换一个匹配」。这台设备没有文件、正在用匹配到的平台版本播放时，这是唯一能改匹配的入口
     * （歌曲信息只在本地音乐页里），选好的匹配记进线索表并同步回有文件的设备。非本地歌为 null。
     */
    onRematch: (() -> Unit)? = null,
    /** 在当前面板里切到评论，不关闭外层歌曲操作。 */
    onOpenComments: (() -> Unit)? = null,
    /** “查看歌手：某某”，每位歌手一行（最多 3 位）；参数是点的那位歌手名。 */
    onOpenArtist: ((String) -> Unit)? = null,
    /** “查看专辑：某某”。歌曲没有专辑名时不显示。 */
    onOpenAlbum: (() -> Unit)? = null,
    /** 收藏到平台账号（汽水的「我喜欢的音乐」）；平台没有这个能力时为 null，不显示这一行。 */
    onCollect: (() -> Unit)? = null,
    /** 已经在这位歌手的页面里：不再给他自己那一行。 */
    hideArtist: String = "",
    /** 已经在这张专辑的页面里：不给“查看专辑”。 */
    hideAlbum: String = "",
    /** 最近播放里的“从最近播放删除”（红色，排在最后，和“从歌单移除”同一种行）；别的列表为 null。 */
    onRemoveFromHistory: (() -> Unit)? = null,
) {
    var showPlaylists by remember { mutableStateOf(false) }
    // 打开面板那一刻取一次：面板开着时页面状态变了（比如收藏了），关掉再开才换
    val page = remember(wallPage) { wallPage?.menu?.invoke()?.takeIf { it.items.isNotEmpty() } }
    com.mytv.mymusic.ui.components.AdaptiveSheet(
        onDismiss = onDismiss,
        title = if (showPlaylists) "添加到歌单" else song.title,
        subtitle = if (showPlaylists) song.title else song.subtitle,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (!showPlaylists) {
                // 电视面板只有一屏高，接上整页操作就放不下了，要能滚；上下留一点给焦点框放大
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (favorite) "已收藏" else "收藏", favorite, Modifier.weight(1f)) {
                        onToggleFavorite()
                    }
                    QuickAction(Icons.Filled.Download, "下载", false, Modifier.weight(1f)) { onDownload(); onDismiss() }
                    QuickAction(Icons.Filled.LibraryAdd, "加到歌单", false, Modifier.weight(1f), enabled = playlists.isNotEmpty()) { showPlaylists = true }
                }
                Spacer(Modifier.height(12.dp))
                // 直播（电台）没有歌手 / 专辑可看
                val browsable = !song.isLive
                val hiddenArtist = com.mytv.mymusic.aggregate.TrackNormalizer.keyOf(hideArtist)
                val artistRows = if (onOpenArtist == null || !browsable) {
                    emptyList()
                } else {
                    com.mytv.mymusic.ui.artist.splitArtistNames(song.artist)
                        .filter { hiddenArtist.isEmpty() || com.mytv.mymusic.aggregate.TrackNormalizer.keyOf(it) != hiddenArtist }
                        .take(3)
                        .map { name -> SheetRow(Icons.Filled.Person, "查看歌手：$name", false, action = { onOpenArtist(name) }) }
                }
                val albumRow = onOpenAlbum?.takeIf {
                    browsable && song.album.isNotBlank() &&
                        com.mytv.mymusic.aggregate.TrackNormalizer.keyOf(song.album) != com.mytv.mymusic.aggregate.TrackNormalizer.keyOf(hideAlbum)
                }?.let { SheetRow(Icons.Filled.Album, "查看专辑：${song.album}", false, it) }
                val rows = artistRows + listOfNotNull(
                    albumRow,
                    onCollect?.let { SheetRow(Icons.Filled.FavoriteBorder, "收藏到账号", false, it) },
                    onRematch?.let { SheetRow(Icons.Filled.FindReplace, "换一个匹配", false, it) },
                    onOpenSource?.let { SheetRow(Icons.Filled.SwapHoriz, "选择音源 / 查看其他版本", false, it) },
                    onOpenComments?.let { SheetRow(Icons.AutoMirrored.Filled.Comment, "查看评论", false, keepOpen = true, action = it) },
                    onMerge?.let { SheetRow(Icons.AutoMirrored.Filled.CallMerge, "合并到…", false, it) },
                    onRemoveFromPlaylist?.let { SheetRow(Icons.Filled.Delete, "从歌单移除", true, it) },
                    onRemoveFromHistory?.let { SheetRow(Icons.Filled.Delete, "从最近播放删除", true, it) },
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rows.forEachIndexed { index, row ->
                        com.mytv.mymusic.ui.components.ListRow(
                            title = row.label,
                            index = index,
                            count = rows.size,
                            onClick = { row.action(); if (!row.keepOpen) onDismiss() },
                            icon = row.icon,
                            iconTone = if (row.danger) com.mytv.mymusic.ui.components.IconTone.Error else com.mytv.mymusic.ui.components.IconTone.Secondary,
                            danger = row.danger,
                            showChevron = false,
                        )
                    }
                }
                if (page != null) WallPageGroup(page, scroll, wallPage?.fromMenuKey == true, onDismiss)
                }
            } else {
                if (playlists.isEmpty()) {
                    Text("还没有自建歌单，请先在“我的”中新建。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(playlists, key = { _, playlist -> playlist.id }, contentType = { _, _ -> "playlistChoice" }) { index, playlist ->
                        com.mytv.mymusic.ui.components.ListRow(
                            title = playlist.name,
                            index = index,
                            count = playlists.size,
                            onClick = { onAddToPlaylist(playlist.id); onDismiss() },
                            icon = Icons.Filled.LibraryAdd,
                            value = "${playlist.trackCount} 首",
                            showChevron = false,
                        )
                    }
                }
                TextButton(onClick = { showPlaylists = false }, modifier = Modifier.padding(top = 8.dp)) { Text("返回") }
            }
        }
    }
}

private class SheetRow(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean,
    val action: () -> Unit,
    val keepOpen: Boolean = false,
)

/**
 * 歌曲操作下面的整页操作组：小标题 + 一列行。[focusFirst]（菜单键打开）时面板滚到小标题、焦点落在第一行
 * （电视上焦点行按系统的跟焦规则停在面板约三成高处，小标题就在它上面）。
 * 面板在电视上是滑进来的，内容晚一帧才摆好，焦点请求要等摆好再发，没接住就下一帧再试。
 */
@Composable
private fun WallPageGroup(menu: WallMenu, scroll: androidx.compose.foundation.ScrollState, focusFirst: Boolean, onDismiss: () -> Unit) {
    val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var groupTop by remember { mutableStateOf(-1) }
    var firstFocused by remember { mutableStateOf(false) }
    if (focusFirst) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val top = androidx.compose.runtime.snapshotFlow { groupTop }.first { it >= 0 }
            scroll.scrollTo(top)
            repeat(5) {
                androidx.compose.runtime.withFrameNanos { }
                if (firstFocused) return@LaunchedEffect
                runCatching { firstFocus.requestFocus() }
            }
        }
    }
    Text(
        menu.title,
        Modifier
            .onPlaced { groupTop = it.positionInParent().y.toInt() }
            .padding(start = 4.dp, top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        menu.items.forEachIndexed { index, item ->
            com.mytv.mymusic.ui.components.ListRow(
                title = item.label,
                index = index,
                count = menu.items.size,
                onClick = { item.onClick(); onDismiss() },
                modifier = if (index == 0) {
                    Modifier.focusRequester(firstFocus).onFocusChanged { firstFocused = it.isFocused }
                } else {
                    Modifier
                },
                icon = item.icon,
                summary = item.summary,
                showChevron = false,
            )
        }
    }
}

/**
 * 单曲勾选模式里一个歌单行的样子：[checked] 已经有这首；[busy] 正在加入 / 移出（转圈，点了不算）；
 * [note] 绑定歌单改动后会怎样（写回网易云音乐 / 改动后解除绑定 / 收藏的歌单不能改）；[locked] 不能改。
 */
@androidx.compose.runtime.Immutable
class PlaylistCheck(val checked: Boolean, val busy: Boolean = false, val note: String = "", val locked: Boolean = false)

/**
 * 加入歌单：列出本地歌单，也可以新建一个并直接加入。
 * 默认是多选的批量加入：点一行就加入并关面板。常规单曲入口在 TrackActionsSheet 里。
 *
 * 传了 [checks] 是单曲勾选模式（播放页「加到歌单」，见 [AddToPlaylistSheet]）：每行右边是勾选框，已经有这首的勾上，
 * 点一下由 [onPick] 加入或移出；新建歌单后回到列表；面板不自动关，可以一次加到几个歌单。
 * [snackbar] 是面板自己的提示条（撤销）：面板是单独的窗口，外壳的 Snackbar 被它盖着，看不见也点不到。
 * [focusId] 是电视上打开时焦点落的那一行（第一个还没加入的歌单），没有就落在「新建歌单」。
 */
@Composable
fun PlaylistPickerSheet(
    playlists: List<LocalPlaylistSummary>,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "加入歌单",
    subtitle: String = "选中的歌曲会按原来的顺序加入",
    checks: ((String) -> PlaylistCheck)? = null,
    note: String = "",
    snackbar: androidx.compose.material3.SnackbarHostState? = null,
    focusId: String? = null,
) {
    var creating by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val window = com.mytv.mymusic.ui.components.LocalAppWindow.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val focusRow = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusField = remember { androidx.compose.ui.focus.FocusRequester() }
    var rowFocused by remember { mutableStateOf(false) }
    fun submit() {
        if (name.isBlank()) return
        onCreate(name.trim())
        if (checks == null) {
            onDismiss()
        } else {
            // 勾选模式建好就回到列表，新歌单排在最前、已经勾上
            creating = false
            name = ""
        }
    }
    // 电视：打开时焦点落在第一个还没加入的歌单上（没有就落在「新建歌单」），确认键直接勾。
    // 面板是滑进来的，内容晚几帧才摆好，没接住就下一帧再试；只在打开时落一次，之后焦点跟着用户走
    val target by androidx.compose.runtime.rememberUpdatedState(
        if (focusId == null) 0 else playlists.indexOfFirst { it.id == focusId } + 1,
    )
    val focusIndex = target
    com.mytv.mymusic.ui.components.AdaptiveSheet(
        onDismiss = onDismiss,
        title = if (creating) "新建歌单" else title,
        subtitle = if (creating) "" else subtitle,
    ) {
        // 新建完回到列表时再落一次：输入框没了，焦点不能丢
        if (checks != null && window.isTv && !creating) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                rowFocused = false
                repeat(10) {
                    androidx.compose.runtime.withFrameNanos { }
                    if (rowFocused) return@LaunchedEffect
                    runCatching { listState.scrollToItem(target) }
                    runCatching { focusRow.requestFocus() }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (creating) {
                // 点掉的「新建歌单」行已经不在了，焦点直接放进输入框（电视上不然会丢）。
                // 电视遥控器的方向键在输入框里只挪光标，走不到旁边的「创建并加入」，所以键盘的完成键也能建
                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focusField.requestFocus() } }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    Modifier.fillMaxWidth().focusRequester(focusField).tvFocusRing(RoundedCornerShape(4.dp)),
                    singleLine = true,
                    placeholder = { Text("歌单名称") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        TextButton(
                            onClick = ::submit,
                            enabled = name.isNotBlank(),
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                        ) { Text("创建并加入") }
                    },
                )
                TextButton(onClick = { creating = false }, modifier = Modifier.padding(top = 8.dp)) { Text("返回") }
            } else {
                val rows = playlists.size + 1
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "new", contentType = "pickerNew") {
                        com.mytv.mymusic.ui.components.ListRow(
                            title = "新建歌单",
                            index = 0,
                            count = rows,
                            onClick = { creating = true },
                            modifier = if (checks != null && focusIndex == 0) {
                                Modifier.focusRequester(focusRow).onFocusChanged { rowFocused = it.isFocused }
                            } else {
                                Modifier
                            },
                            icon = Icons.Filled.Add,
                            summary = if (checks != null) "建好后直接把这首加进去" else "",
                            showChevron = false,
                        )
                    }
                    itemsIndexed(playlists, key = { _, playlist -> playlist.id }, contentType = { _, _ -> "pickerPlaylist" }) { index, playlist ->
                        if (checks == null) {
                            com.mytv.mymusic.ui.components.ListRow(
                                title = playlist.name,
                                index = index + 1,
                                count = rows,
                                onClick = { onPick(playlist.id); onDismiss() },
                                icon = Icons.AutoMirrored.Filled.QueueMusic,
                                value = "${playlist.trackCount} 首",
                                showChevron = false,
                            )
                        } else {
                            val check = checks(playlist.id)
                            com.mytv.mymusic.ui.components.ListRow(
                                title = playlist.name,
                                index = index + 1,
                                count = rows,
                                onClick = if (check.locked) null else ({ if (!check.busy) onPick(playlist.id) }),
                                // 行内勾选框不带语义，勾没勾由整行提供（同 SwitchRow）
                                modifier = Modifier
                                    .then(
                                        if (focusIndex == index + 1) {
                                            Modifier.focusRequester(focusRow).onFocusChanged { rowFocused = it.isFocused }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .semantics {
                                        role = androidx.compose.ui.semantics.Role.Checkbox
                                        toggleableState = androidx.compose.ui.state.ToggleableState(check.checked)
                                    },
                                icon = Icons.AutoMirrored.Filled.QueueMusic,
                                summary = listOf("${playlist.trackCount} 首", check.note).filter { it.isNotBlank() }.joinToString(" · "),
                                showChevron = false,
                                trailing = {
                                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                        if (check.busy) {
                                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                        } else {
                                            androidx.compose.material3.Checkbox(
                                                checked = check.checked,
                                                onCheckedChange = null,
                                                enabled = !check.locked,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                // 提示条压在说明那一块上，那块按提示条的高度留够：压在列表上会挡住刚点的那一行的勾
                Box(Modifier.fillMaxWidth().heightIn(min = if (snackbar != null) 72.dp else 0.dp)) {
                    if (note.isNotBlank()) {
                        Text(
                            note,
                            Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    snackbar?.let { androidx.compose.material3.SnackbarHost(it, Modifier.align(Alignment.BottomCenter)) }
                }
            }
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    on: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(72.dp).tvFocusRing(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.38f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * 合并到…：单选列表 + 取消 / 合并。手机和平板都是居中对话框（平板 440dp），电视默认焦点在“合并”。
 */
@Composable
fun MergeDialog(track: CanonicalTrack, candidates: List<CanonicalTrack>, onMerge: (CanonicalTrack) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable(track.id) { mutableStateOf(candidates.firstOrNull()?.id) }
    val confirmFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val tv = com.mytv.mymusic.ui.components.LocalAppWindow.current.isTv
    androidx.compose.runtime.LaunchedEffect(tv) { if (tv) runCatching { confirmFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 440.dp),
        title = {
            DialogAvoidsFold()
            Text("合并到…")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "把「${track.title} · ${track.artistLabel}」并入另一条结果。只影响本机，可以在换源面板里拆分回来。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (candidates.isEmpty()) {
                    Text("没有其他可合并的曲目", style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(
                    Modifier.width(360.dp).heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(candidates, key = { _, target -> target.id }, contentType = { _, _ -> "mergeChoice" }) { index, target ->
                        val on = selected == target.id
                        com.mytv.mymusic.ui.components.ListRow(
                            title = target.title,
                            index = index,
                            count = candidates.size,
                            onClick = { selected = target.id },
                            // 行内 RadioButton 不带语义，单选状态由整行提供
                            modifier = Modifier.semantics {
                                role = androidx.compose.ui.semantics.Role.RadioButton
                                this.selected = on
                            },
                            summary = "${target.subtitle} · ${target.sources.size} 个平台",
                            selected = on,
                            showChevron = false,
                            leading = { androidx.compose.material3.RadioButton(selected = on, onClick = null) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { candidates.find { it.id == selected }?.let(onMerge) },
                enabled = selected != null,
                modifier = Modifier.focusRequester(confirmFocus).tvFocusRing(RoundedCornerShape(20.dp)),
            ) { Text("合并") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("取消") }
        },
    )
}

fun availabilityLabel(status: Availability): String = when (status) {
    Availability.Playable -> "完整"
    Availability.PreviewOnly -> "试听"
    Availability.VipOnly -> "VIP"
    Availability.Unavailable -> "无源"
    Availability.Unknown -> "待检测"
}

fun formatTime(ms: Long): String =
    String.format(Locale.ROOT, "%02d:%02d", (ms / 60000).coerceAtLeast(0), ((ms / 1000) % 60).coerceAtLeast(0))

fun formatDuration(seconds: Int): String =
    if (seconds <= 0) "" else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)

private val MonthDayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

fun relativeTime(millis: Long): String {
    if (millis <= 0) return ""
    val delta = System.currentTimeMillis() - millis
    return when {
        delta < 60_000 -> "刚刚"
        delta < 3_600_000 -> "${delta / 60_000} 分钟前"
        delta < 86_400_000 -> "${delta / 3_600_000} 小时前"
        delta < 7L * 86_400_000 -> "${delta / 86_400_000} 天前"
        else -> MonthDayFormat.format(Date(millis))
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun providerMark(name: String): String = when {
    name.contains("网易") -> "网易"
    name.contains("QQ") -> "QQ"
    name.contains("酷狗") -> "酷狗"
    name.contains("酷我") -> "酷我"
    name.contains("咪咕") -> "咪咕"
    name.contains("汽水") -> "汽水"
    name.contains("Apple") -> "Apple"
    name.startsWith("Bili", ignoreCase = true) -> "B站"
    name.contains("千千") -> "千千"
    name.contains("5sing", ignoreCase = true) -> "5sing"
    name.contains("Jamendo", ignoreCase = true) -> "Jam"
    name.contains("JOOX", ignoreCase = true) -> "JOOX"
    else -> name.take(2)
}

fun providerName(providers: List<com.mytv.mymusic.data.ProviderInfo>, id: String): String =
    providers.find { it.id == id }?.name ?: when (id) {
        "netease" -> "网易云音乐"
        "qq" -> "QQ 音乐"
        "kugou" -> "酷狗音乐"
        "kuwo" -> "酷我音乐"
        "migu" -> "咪咕音乐"
        "apple" -> "Apple Music"
        "soda" -> "汽水音乐"
        "bilibili" -> "Bilibili"
        "joox" -> "JOOX"
        "fivesing" -> "5sing"
        "qianqian" -> "千千音乐"
        "local" -> "本地"
        "lx" -> "洛雪"
        else -> id
    }
