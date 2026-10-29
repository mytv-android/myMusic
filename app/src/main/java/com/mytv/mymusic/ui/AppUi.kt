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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.PlaybackMode
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.aggregate.availabilityOf
import com.mytv.mymusic.data.DownloadRecord
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
    fun setCategoryGroup(group: String) = model.setCategoryGroup(group)
    fun selectCategory(category: com.mytv.mymusic.data.PlaylistCategory) = model.selectCategory(category)
    fun clearCategory() = model.clearCategory()
    fun categoryPage(page: Int) = model.categoryPage(page)
    fun openPlaylist(playlist: com.mytv.mymusic.data.Playlist, album: Boolean = false) = model.openPlaylist(playlist, album)

    fun openArtist(playlist: com.mytv.mymusic.data.Playlist) = model.openArtist(playlist)
    fun playCollection() = model.playCollection()
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

    fun play(song: Song, queue: List<Song>) = model.play(song, queue)
    fun playSavedSongs(records: List<com.mytv.mymusic.data.SavedSong>) = model.playSavedSongs(records)
    fun playTrack(track: CanonicalTrack, queue: List<CanonicalTrack>) = model.playTrack(track, queue)
    fun transport(action: String) = model.transport(action)
    fun seek(position: Long) = model.seek(position)
    fun openPlayer() = model.openPlayer(true)
    fun closePlayer() = model.openPlayer(false)
    fun playQueueIndex(index: Int) = model.playQueueIndex(index)
    fun clearQueue() = model.clearQueue()
    fun favorite(song: Song) = model.toggleFavorite(song)
    fun download(song: Song) = model.download(song)
    fun openDeepLink() = model.openDeepLink()
    fun clearError() = model.clearError()
    fun clearNotice() = model.clearNotice()

    fun createPlaylist(name: String) = model.createPlaylist(name)
    fun renamePlaylist(id: String, name: String) = model.renamePlaylist(id, name)
    fun deletePlaylist(id: String) = model.deletePlaylist(id)
    fun addSongToPlaylist(id: String, song: Song) = model.addSongToPlaylist(id, song)
    fun addSongsToPlaylist(id: String, songs: List<Song>) = model.addSongsToPlaylist(id, songs)
    fun addSongsToNewPlaylist(name: String, songs: List<Song>) = model.addSongsToNewPlaylist(name, songs)
    fun removeEntry(id: String, position: Int) = model.removePlaylistEntry(id, position)
    fun removeEntries(id: String, positions: List<Int>) = model.removePlaylistEntries(id, positions)
    fun moveEntry(id: String, position: Int, delta: Int) = model.movePlaylistEntry(id, position, delta)
    fun searchLibrary(query: String) = model.searchLibrarySongs(query)
    fun clearLibrarySearch() = model.clearLibrarySearch()
    fun playDownload(record: DownloadRecord) = model.playDownloaded(record)
    fun deleteDownload(record: DownloadRecord) = model.deleteDownload(record)
    fun restoreEntry(entry: com.mytv.mymusic.data.PlaylistEntryEntity) =
        model.insertPlaylistEntry(entry.playlistId, entry.position, entry.songKey, entry.payload)
    fun clearPlayHistory() = model.clearPlayHistory()
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
    fun setRhythmIntensity(value: com.mytv.mymusic.data.RhythmIntensity) = model.setRhythmIntensity(value)
    fun setCoverShape(value: com.mytv.mymusic.data.CoverShapeChoice) = model.setCoverShape(value)
    fun setTvAmbientClock(value: Boolean) = model.setTvAmbientClock(value)
    fun startSleepTimer(minutes: Int) = model.startSleepTimer(minutes)
    fun sleepAfterCurrent() = model.sleepAfterCurrent()
    fun cancelSleepTimer() = model.cancelSleepTimer()
    fun setSleepFade(value: Boolean) = model.setSleepFade(value)
    fun setReduceMotion(value: Boolean) = model.setReduceMotion(value)
    fun toggleAggregateProvider(id: String) = model.toggleAggregateProvider(id)
    fun setPlaybackMode(mode: PlaybackMode) = model.setPlaybackMode(mode)
    fun setPinnedProvider(id: String?) = model.setPinnedProvider(id)
    fun setAllowPreview(value: Boolean) = model.setAllowPreview(value)
    fun setStrictMatch(value: Boolean) = model.setMatchStrictness(
        if (value) com.mytv.mymusic.aggregate.MatchStrictness.Strict
        else com.mytv.mymusic.aggregate.MatchStrictness.Balanced
    )
    fun movePriority(id: String, delta: Int) = model.moveProviderPriority(id, delta)
    fun clearMatchCache() = model.clearMatchCache()
    fun clearMediaCache() = model.clearMediaCache()
    fun setCacheLimit(mb: Int) = model.setCacheLimit(mb)
    fun startLogin(providerId: String, type: String = "qq") = model.startLogin(providerId, type)
    fun saveCookie(providerId: String, cookie: String) = model.saveCookie(providerId, cookie)
    fun saveSodaSigner(url: String, token: String, enabled: Boolean = true) =
        model.saveSodaSigner(url, token, enabled)
    fun logout(providerId: String) = model.logout(providerId)
    fun startQrLogin(providerId: String, type: String) = model.startQrLogin(providerId, type)
    fun cancelLogin() = model.cancelLogin()

    fun syncNow() = model.syncNow()
    fun syncIfStaleOnScreen() = model.syncIfStaleOnScreen()
    fun connectSync(providerId: String, token: String, remote: String) = model.connectSync(providerId, token, remote)
    fun resolveInitial(strategy: SyncStrategy) = model.resolveInitialSync(strategy)
    fun cancelInitial() = model.cancelInitialSync()
    fun resolveConflict(id: String, strategy: SyncStrategy) = model.resolveSyncConflict(id, strategy)
    fun setSyncAutoSync(value: Boolean) = model.setSyncAutoSync(value)
    fun setSyncWifiOnly(value: Boolean) = model.setSyncWifiOnly(value)
    fun disconnectSync() = model.disconnectSync()
    fun exportLibrary(uri: Uri) = model.exportLibrary(uri)
    fun importLibrary(uri: Uri, strategy: SyncStrategy = SyncStrategy.Merge) = model.importLibrary(uri, strategy)

    fun checkUpdates() = model.checkForUpdates()
    suspend fun checkUpdatesNow(): com.mytv.mymusic.data.update.UpdateUiState = model.checkForUpdatesNow()
    fun downloadUpdate() = model.downloadUpdate()
    fun cancelUpdateDownload() = model.cancelUpdateDownload()
    fun installUpdate() = model.installUpdate()
    fun openInstallPermission() = model.openInstallPermission()
    fun openReleasePage() = model.openReleasePage()
    fun skipUpdate() = model.skipUpdate()
    fun clearUpdateError() = model.clearUpdateError()

    fun setEqEnabled(value: Boolean) = model.setEqualizerEnabled(value)
    fun selectEqPreset(id: String) = model.selectEqualizerPreset(id)
    fun setEqBand(index: Int, db: Float) = model.setEqualizerBand(index, db)
    fun resetEq() = model.resetEqualizer()
    fun saveEqCustom() = model.saveEqualizerCustom()

    fun diagnostics(): String = model.diagnostics()
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
 * 标题 + 说明 + 明确的下一步。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    focusAction: Boolean = com.mytv.mymusic.ui.components.LocalAppWindow.current.isTv,
) {
    val actionFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (focusAction && actionLabel != null && onAction != null) {
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
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 18.dp).heightIn(min = 48.dp).focusRequester(actionFocus).tvFocusRing(RoundedCornerShape(24.dp))) {
                Text(actionLabel)
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
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
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
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
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
 * 歌曲操作：手机是底部面板，平板是侧边面板，电视是右侧面板。
 * 顶部三个快捷操作（收藏 / 下载 / 加歌单）用大按钮，其余是列表。
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
) {
    var showPlaylists by remember { mutableStateOf(false) }
    com.mytv.mymusic.ui.components.AdaptiveSheet(
        onDismiss = onDismiss,
        title = if (showPlaylists) "添加到歌单" else song.title,
        subtitle = if (showPlaylists) song.title else song.subtitle,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (!showPlaylists) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (favorite) "已收藏" else "收藏", favorite, Modifier.weight(1f)) {
                        onToggleFavorite()
                    }
                    QuickAction(Icons.Filled.Download, "下载", false, Modifier.weight(1f)) { onDownload(); onDismiss() }
                    QuickAction(Icons.Filled.LibraryAdd, "加到歌单", false, Modifier.weight(1f), enabled = playlists.isNotEmpty()) { showPlaylists = true }
                }
                Spacer(Modifier.height(12.dp))
                val rows = listOfNotNull(
                    onOpenSource?.let { SheetRow(Icons.Filled.SwapHoriz, "选择音源 / 查看其他版本", false, it) },
                    onMerge?.let { SheetRow(Icons.AutoMirrored.Filled.CallMerge, "合并到…", false, it) },
                    onRemoveFromPlaylist?.let { SheetRow(Icons.Filled.Delete, "从歌单移除", true, it) },
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rows.forEachIndexed { index, row ->
                        com.mytv.mymusic.ui.components.ListRow(
                            title = row.label,
                            index = index,
                            count = rows.size,
                            onClick = { row.action(); onDismiss() },
                            icon = row.icon,
                            iconTone = if (row.danger) com.mytv.mymusic.ui.components.IconTone.Error else com.mytv.mymusic.ui.components.IconTone.Secondary,
                            danger = row.danger,
                            showChevron = false,
                        )
                    }
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

private class SheetRow(val icon: ImageVector, val label: String, val danger: Boolean, val action: () -> Unit)

/**
 * 批量加入歌单：列出本地歌单，也可以新建一个并直接加入。
 * 常规单曲入口在 TrackActionsSheet 里，这里只服务多选。
 */
@Composable
fun PlaylistPickerSheet(
    playlists: List<LocalPlaylistSummary>,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    com.mytv.mymusic.ui.components.AdaptiveSheet(
        onDismiss = onDismiss,
        title = if (creating) "新建歌单" else "加入歌单",
        subtitle = if (creating) "" else "选中的歌曲会按原来的顺序加入",
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (creating) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(4.dp)),
                    singleLine = true,
                    placeholder = { Text("歌单名称") },
                    trailingIcon = {
                        TextButton(
                            onClick = { onCreate(name.trim()); onDismiss() },
                            enabled = name.isNotBlank(),
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                        ) { Text("创建并加入") }
                    },
                )
                TextButton(onClick = { creating = false }, modifier = Modifier.padding(top = 8.dp)) { Text("返回") }
            } else {
                val rows = playlists.size + 1
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item(key = "new", contentType = "pickerNew") {
                        com.mytv.mymusic.ui.components.ListRow(
                            title = "新建歌单",
                            index = 0,
                            count = rows,
                            onClick = { creating = true },
                            icon = Icons.Filled.Add,
                            showChevron = false,
                        )
                    }
                    itemsIndexed(playlists, key = { _, playlist -> playlist.id }, contentType = { _, _ -> "pickerPlaylist" }) { index, playlist ->
                        com.mytv.mymusic.ui.components.ListRow(
                            title = playlist.name,
                            index = index + 1,
                            count = rows,
                            onClick = { onPick(playlist.id); onDismiss() },
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            value = "${playlist.trackCount} 首",
                            showChevron = false,
                        )
                    }
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
        title = { Text("合并到…") },
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
        else -> id
    }
