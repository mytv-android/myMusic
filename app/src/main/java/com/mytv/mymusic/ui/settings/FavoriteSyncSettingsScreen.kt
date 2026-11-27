package com.mytv.mymusic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.data.FavoriteSyncIssue
import com.mytv.mymusic.data.FavoriteSyncMode
import com.mytv.mymusic.data.FavoriteSyncPreference
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.components.AdaptiveSheet
import com.mytv.mymusic.ui.components.AppLoading
import com.mytv.mymusic.ui.components.ListRow
import com.mytv.mymusic.ui.components.LocalSheetClose
import com.mytv.mymusic.ui.components.SegmentGap
import com.mytv.mymusic.ui.library.TvDefaultFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FavoriteSyncSettingsScreen(
    state: MusicUiState,
    actions: MusicActions,
    onBack: () -> Unit,
    embedded: Boolean,
) {
    val providers = state.sources { (it.userPlaylists && it.playlistWrite) || actions.supportsNativeFavorite(it.id) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsPage(SettingsSection.FavoriteSync, embedded, onBack) {
        item(key = "intro") {
            SettingsNote("收藏和取消收藏时，也会增删对应平台歌单里的歌曲。")
        }
        itemsIndexed(providers, key = { _, provider -> provider.id }) { index, provider ->
            val preference = state.favoriteSync[provider.id] ?: FavoriteSyncPreference()
            val cookie = state.cookies[provider.id].orEmpty()
            val matches = remember(preference, cookie) { preference.mode == FavoriteSyncMode.Playlist && preference.matches(cookie) }
            val default = actions.supportsNativeFavorite(provider.id)
            ListRow(
                title = provider.name,
                summary = when (preference.mode) {
                    FavoriteSyncMode.LocalOnly -> "仅 App 收藏"
                    FavoriteSyncMode.Default -> when {
                        cookie.isBlank() -> "待登录"
                        default -> "平台默认"
                        else -> "请选择歌单"
                    }
                    FavoriteSyncMode.Playlist -> if (matches) preference.playlistName else "请重新选择歌单"
                },
                index = index,
                count = providers.size,
                onClick = { editingId = provider.id },
                leading = { ProviderMark(provider.name, id = provider.id, active = cookie.isNotBlank()) },
            )
        }
    }
    providers.firstOrNull { it.id == editingId }?.let { provider ->
        val cookie = state.cookies[provider.id].orEmpty()
        key(provider.id, cookie) {
            FavoriteSyncPicker(
                provider,
                cookie,
                state.favoriteSync[provider.id] ?: FavoriteSyncPreference(),
                actions,
                onDismiss = { editingId = null },
            )
        }
    }
}

@Composable
private fun FavoriteSyncPicker(
    provider: ProviderInfo,
    cookie: String,
    preference: FavoriteSyncPreference,
    actions: MusicActions,
    onDismiss: () -> Unit,
) {
    var showPlaylists by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val choices by produceState<Result<List<Playlist>>?>(null, showPlaylists, retry) {
        value = null
        if (showPlaylists) {
            value = try {
                Result.success(actions.favoritePlaylistChoices(provider.id, cookie))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val native = actions.supportsNativeFavorite(provider.id)
    val canChoose = provider.userPlaylists && provider.playlistWrite
    val selectedPlaylist = remember(preference, cookie) {
        preference.playlistId.takeIf { preference.mode == FavoriteSyncMode.Playlist && preference.matches(cookie) }
    }
    AdaptiveSheet(onDismiss = onDismiss, title = provider.name, subtitle = "收藏同步目标") {
        val close = LocalSheetClose.current
        val first = remember { FocusRequester() }
        TvDefaultFocus(first)
        val select: (FavoriteSyncMode, Playlist?) -> Unit = { mode, playlist ->
            if (!saving) {
                saving = true
                saveError = ""
                scope.launch {
                    try {
                        actions.saveFavoriteSync(provider.id, mode, playlist, cookie)
                        close()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        saveError = favoritePickerError(error)
                    } finally {
                        saving = false
                    }
                }
            }
        }
        LazyColumn(
            Modifier.padding(horizontal = 16.dp).heightIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            val modeCount = if (canChoose) 3 else 2
            item(key = "default") {
                ListRow(
                    title = "平台默认",
                    summary = if (native) "同步到平台自带的收藏" else "此音源需要手动指定歌单",
                    index = 0,
                    count = modeCount,
                    onClick = { select(FavoriteSyncMode.Default, null) },
                    selected = preference.mode == FavoriteSyncMode.Default,
                    modifier = Modifier.focusRequester(first),
                    showChevron = false,
                )
            }
            if (canChoose) {
                item(key = "custom") {
                    ListRow(
                        title = "指定歌单",
                        summary = when {
                            cookie.isBlank() -> "登录后可选"
                            selectedPlaylist != null -> preference.playlistName
                            preference.mode == FavoriteSyncMode.Playlist -> "请重新选择歌单"
                            else -> "选择当前账号可修改的歌单"
                        },
                        index = 1,
                        count = modeCount,
                        onClick = if (cookie.isBlank()) null else ({ showPlaylists = !showPlaylists }),
                        selected = selectedPlaylist != null,
                    )
                }
            }
            item(key = "local") {
                ListRow(
                    title = "仅 App 收藏",
                    index = modeCount - 1,
                    count = modeCount,
                    onClick = { select(FavoriteSyncMode.LocalOnly, null) },
                    selected = preference.mode == FavoriteSyncMode.LocalOnly,
                    showChevron = false,
                )
            }
            if (saving) item(key = "saving") { AppLoading(text = "正在保存…") }
            if (saveError.isNotBlank()) item(key = "saveError") {
                Text(saveError, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
            }
            if (showPlaylists) {
                val result = choices
                val playlists = result?.getOrNull()
                when {
                    result == null -> item(key = "loading") { AppLoading(text = "正在读取歌单…") }
                    playlists == null -> {
                        item(key = "error") { SettingsNote(favoritePickerError(result.exceptionOrNull())) }
                        item(key = "retry") { TextButton(onClick = { retry++ }) { Text("重试") } }
                    }
                    playlists.isEmpty() -> item(key = "empty") {
                        SettingsNote(
                            if (selectedPlaylist == null) "这个账号没有可修改的歌单。"
                            else "原目标已不可修改。创建歌单后重新选择，或切换到其他同步目标。",
                        )
                    }
                    else -> {
                        if (selectedPlaylist != null && playlists.none { it.id == selectedPlaylist }) {
                            item(key = "invalid") { SettingsNote("原目标已不可修改，请重新选择歌单。") }
                        }
                        itemsIndexed(playlists, key = { _, playlist -> "playlist:${playlist.id}" }) { index, playlist ->
                            ListRow(
                                title = playlist.name,
                                summary = "${playlist.trackCount} 首",
                                index = index,
                                count = playlists.size,
                                onClick = { select(FavoriteSyncMode.Playlist, playlist) },
                                selected = playlist.id == selectedPlaylist,
                                showChevron = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun favoritePickerError(error: Throwable?): String =
    (error as? FavoriteSyncIssue)?.message ?: "操作失败，请检查网络和登录后重试"
