package com.mytv.mymusic.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.library.libraryPlaylistGroups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * 播放页「加到歌单」面板（设计稿 04 节）：沿用 [PlaylistPickerSheet] 的勾选模式。
 * 每行右边是勾选框，已经有这首（含同一首的其他平台版本）的勾上；点一下加入或移出，带撤销，面板不自动关，
 * 可以一次加到几个歌单。顶上「新建歌单」建好直接把这首放进去。
 * 绑定了平台歌单的行标出改了以后会怎样，规则同歌单详情页：平台能写的写回平台，平台没有写接口的改了就解除绑定，
 * 收藏来的（不是自己的）歌单不能改。
 * 提示条放在面板里：面板是单独的窗口，外壳的 Snackbar 被它盖着，看不见也点不到撤销。
 */
@Composable
fun AddToPlaylistSheet(
    song: Song,
    providers: List<ProviderInfo>,
    actions: MusicActions,
    onDismiss: () -> Unit,
    /** 默认选中的分组 id（空串是本地）；由调用方按「这首歌的音源」算好，见 [defaultPlaylistGroup]。 */
    defaultGroup: String? = null,
) {
    val playlists by actions.localPlaylists.collectAsState()
    val containing by remember(song.key) { actions.playlistsContaining(song) }.collectAsState(initial = null)
    val access by actions.boundAccess.collectAsState()
    val tv = LocalAppWindow.current.isTv
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(emptySet<String>()) }
    // 绑定歌单是不是收藏来的（只读）：打开时查一次，结果在进程里缓存 10 分钟
    LaunchedEffect(playlists.filter { it.bound }.map { it.id }) {
        playlists.filter { it.bound }.forEach { actions.checkBoundPlaylistAccess(it.id) }
    }
    // 顺序定在打开那一刻：加歌会改歌单的修改时间，按时间排的列表会在手底下跳；新建的排在最前
    val order = remember { mutableListOf<String>() }
    val ordered = remember(playlists) {
        val known = order.toHashSet()
        order.addAll(0, playlists.map { it.id }.filter { it !in known })
        val byId = playlists.associateBy { it.id }
        order.retainAll(byId.keys)
        order.mapNotNull { byId[it] }
    }
    // 第一次查出来之前不出面板：电视的焦点要落在“还没加入的第一个”上，勾也不能先空着再闪出来
    val inPlaylists = containing ?: return

    // 分组跟着歌单列表更新（新建 / 删掉歌单后 tab 要跟上）；打开时的默认选中项只按当前音源算一次
    val groups = remember(ordered) { libraryPlaylistGroups(ordered).keys.toList() }
    var chosenGroup by remember { mutableStateOf(resolvePlaylistGroup(defaultGroup, groups)) }
    // 选中的分组已经被删掉 / 解绑时回到「全部」，和面板里的 tab 显示保持一致
    val group = chosenGroup?.takeIf { it in groups }

    fun binding(playlist: LocalPlaylistSummary): PlaylistBinding {
        if (!playlist.bound) return PlaylistBinding.None
        val info = providers.find { it.id == playlist.linkProvider } ?: return PlaylistBinding.None
        val platform = providerName(providers, playlist.linkProvider)
        return when {
            !info.playlistWrite -> PlaylistBinding.Unbinds
            access[playlist.id]?.readOnly == true -> PlaylistBinding.ReadOnly
            else -> PlaylistBinding.WriteBack(platform)
        }
    }

    // 电视的焦点落点：当前分组里第一个还没加入、能改的歌单
    val focusTarget = ordered.firstOrNull {
        it.id !in inPlaylists &&
            binding(it) != PlaylistBinding.ReadOnly &&
            (group == null || playlistGroupOf(it) == group)
    }?.id

    fun show(text: String, undo: (() -> Unit)? = null) {
        if (text.isBlank()) return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = text,
                actionLabel = if (undo != null) "撤销" else null,
                // 电视上要走到“撤销”得按几下方向键，多留一会儿
                duration = if (tv) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }

    /** 跑一次加入 / 移出，期间这一行转圈、点了不算；面板关了写平台照样做完（在 viewModelScope 里），只是不再提示。 */
    fun run(id: String, job: () -> Deferred<PlaylistEditResult>, done: (PlaylistEditResult) -> Unit) {
        if (id in busy) return
        busy = busy + id
        scope.launch {
            val result = try {
                job().await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PlaylistEditResult(changed = false, text = "没改成：${error.message.orEmpty()}", error = true)
            } finally {
                busy = busy - id
            }
            if (result.changed) done(result) else show(result.text)
        }
    }

    fun toggle(playlist: LocalPlaylistSummary) {
        val adding = playlist.id !in inPlaylists
        val suffix = binding(playlist).suffix
        if (adding) {
            run(playlist.id, { actions.addSongToPlaylistQuietly(playlist.id, song) }) {
                show("已加到「${playlist.name}」$suffix") {
                    run(playlist.id, { actions.removeSongFromPlaylist(playlist.id, song) }) { }
                }
            }
        } else {
            run(playlist.id, { actions.removeSongFromPlaylist(playlist.id, song) }) { result ->
                show("已从「${playlist.name}」移出$suffix") {
                    // 按原位置插回；绑定歌单照写回规则反向写平台
                    result.removed.forEach(actions::restoreEntry)
                }
            }
        }
    }

    val added = ordered.count { it.id in inPlaylists }
    PlaylistPickerSheet(
        playlists = ordered,
        onPick = { id -> ordered.find { it.id == id }?.let(::toggle) },
        onCreate = { name ->
            scope.launch {
                val result = actions.createPlaylistWithSong(name, song).await()
                show(if (result.changed) "已新建「$name」并加入这首" else result.text)
            }
        },
        onDismiss = onDismiss,
        title = "加到歌单",
        subtitle = "${song.title} · ${song.artist} · 已在 $added 个歌单里",
        checks = { id ->
            val playlist = ordered.find { it.id == id }
            val binding = playlist?.let(::binding) ?: PlaylistBinding.None
            PlaylistCheck(
                checked = id in inPlaylists,
                busy = id in busy,
                note = binding.note,
                locked = binding == PlaylistBinding.ReadOnly,
            )
        },
        note = if (tv) "按确认键加入或移出，面板不自动关。" else "点一下加入或移出，面板不自动关；收藏用旁边的心形。",
        snackbar = snackbar,
        // 电视焦点落在当前分组里第一个还没加入、能改的歌单上（tab 上按确认才进列表）
        focusId = focusTarget,
        // 只有一类歌单时不显示 tab，保持原来的扁平列表
        grouping = if (groups.size > 1) {
            PlaylistGrouping(
                groups = groups,
                selected = group,
                providers = providers,
                onSelect = { chosenGroup = it },
            )
        } else {
            null
        },
    )
}

/** 绑定歌单在面板里的说明（[note]）和改完后提示的后半句（[suffix]）。 */
private sealed class PlaylistBinding(val note: String, val suffix: String) {
    data object None : PlaylistBinding("", "")

    /** 平台能写：加入 / 移出同时改平台上的歌单。 */
    class WriteBack(platform: String) : PlaylistBinding("写回$platform", "，并写回$platform")

    /** 平台没有写接口：改了就和平台歌单解绑，之后手动维护（同歌单详情页的“编辑即解绑”）。 */
    data object Unbinds : PlaylistBinding("改动后解除绑定", "，歌单已解除绑定")

    /** 收藏来的歌单（不是当前账号自己的）：不能改。 */
    data object ReadOnly : PlaylistBinding("收藏的歌单，不能修改", "")
}
