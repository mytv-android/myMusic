package com.mytv.mymusic.ui

import com.mytv.mymusic.aggregate.CanonicalCollection
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.ProviderSearchState
import com.mytv.mymusic.aggregate.SearchStatus
import com.mytv.mymusic.data.AlbumSummary
import com.mytv.mymusic.data.ListeningMode
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.SearchKind
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.TopList

enum class PlaylistMode { Recommend, Category, Listening }

data class ChartsUiState(
    val providerId: String = "",
    val providerName: String = "",
    val supported: Boolean = true,
    val charts: List<TopList> = emptyList(),
    val featuredSongs: List<Song> = emptyList(),
    val hotSongs: List<Song> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
    val loadedAt: Long = 0L,
)

data class PlaylistsUiState(
    val providerId: String = "",
    val providerName: String = "",
    val mode: PlaylistMode = PlaylistMode.Recommend,
    val canRecommend: Boolean = true,
    val canCategorize: Boolean = true,
    val recommendations: List<Playlist> = emptyList(),
    val recommendationsLoading: Boolean = false,
    val recommendationsError: String = "",
    val categories: List<PlaylistCategory> = emptyList(),
    val categoriesLoading: Boolean = false,
    val categoriesError: String = "",
    val categoryGroup: String = "全部",
    val selectedCategoryId: String = "",
    val selectedCategoryName: String = "",
    val categoryPlaylists: List<Playlist> = emptyList(),
    val categoryPage: Int = 1,
    val categoryLoading: Boolean = false,
    val categoryError: String = "",
    val categoryOpen: Boolean = false,
    val canListen: Boolean = false,
    val listenModes: List<ListeningMode> = emptyList(),
    val listenLoading: Boolean = false,
    val listenError: String = "",
    val listenGroup: String = "全部",
    /** 正在播放的听歌模式 id，卡片上给一个“正在播放”标记。 */
    val listenActive: String = "",
    /** 正在起播的模式 id，卡片上显示转圈。 */
    val listenStarting: String = "",
    val quickListenStarting: Boolean = false,
) {
    val listenGroups: List<String> get() = listenModes.map { it.group }.filter { it.isNotBlank() }.distinct()
    val visibleListenModes: List<ListeningMode>
        get() = if (listenGroup.isBlank() || listenGroup == "全部") listenModes
        else listenModes.filter { it.group == listenGroup }
}

data class SearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val kind: SearchKind = SearchKind.Song,
    val aggregateGroups: List<CanonicalTrack> = emptyList(),
    val aggregateCollections: List<CanonicalCollection> = emptyList(),
    val providerStates: Map<String, ProviderSearchState> = emptyMap(),
    val collectionProviderStates: Map<String, ProviderSearchState> = emptyMap(),
    val aggregateStatus: SearchStatus = SearchStatus.Idle,
    val collectionStatus: SearchStatus = SearchStatus.Idle,
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val page: Int = 1,
    val error: String = "",
) {
    val hasQuery: Boolean get() = submittedQuery.isNotBlank()
    val landing: Boolean get() = submittedQuery.isBlank()
}

/** 听歌模式队列里每首歌 extra 上的标记，值是模式 id；换队列后靠它判断还要不要继续续流。 */
const val LISTENING_FEED_MARK = "listening_mode"

/**
 * 正在续流的听歌模式，海报墙据此把队列排成带子。[modeId] 等于队列里歌曲 extra 的 [LISTENING_FEED_MARK]；
 * [appending] 为 true 时正在队尾续取下一批。换队列（play）后变回 null，App 重启后也是 null。
 */
data class ListeningFeedUi(
    val modeId: String,
    val name: String,
    val providerId: String,
    val providerName: String,
    val appending: Boolean,
) {
    /** 这首歌是不是这条推荐流里的：正在放的歌属于它时，海报墙才按带子排。 */
    fun owns(song: Song?): Boolean = song?.extra?.get(LISTENING_FEED_MARK) == modeId
}

enum class CollectionKind { Chart, Playlist, Album, Artist }

/** 登录平台的账号资料；资料接口拿不到时 detail 为空，界面只显示“已登录”。 */
data class AccountProfile(
    val providerId: String,
    val nickname: String = "",
    val avatar: String = "",
    val detail: String = "",
)

/**
 * 把平台返回的资料变成账号卡片要显示的那份。
 *
 * 昵称为空是合法情况：Apple Music 只暴露订阅状态，不向网页端给昵称和头像
 * （见 `AppleProvider.accountInfo`）。这时候昵称退回平台名，会员等级照样要显示，
 * 所以判成功看的是「接口通了」，不是「拿到了昵称」。
 */
fun accountProfileOf(providerId: String, platformName: String, info: com.mytv.mymusic.data.native.AccountInfo): AccountProfile =
    AccountProfile(
        providerId = providerId,
        nickname = info.nickname.trim().ifBlank { platformName },
        avatar = info.avatar,
        detail = info.detail,
    )

data class CollectionUiState(
    /** 详情页 key（`类型|平台|id`），路由参数和 MusicViewModel 的分页缓存都按它找这一页。 */
    val key: String = "",
    val kind: CollectionKind = CollectionKind.Playlist,
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val cover: String = "",
    val source: String = "",
    val sourceName: String = "",
    val songs: List<Song> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
    /** 专辑页头部：发行时间、厂牌、类型、简介、主歌手；平台没给时为 null。 */
    val album: AlbumSummary? = null,
    /** 专辑页底部“更多专辑”：同一歌手的其他专辑（不含这张）。 */
    val moreAlbums: List<AlbumSummary> = emptyList(),
)

data class LocalPlaylistSummary(
    val id: String,
    val name: String,
    val trackCount: Int,
    val updatedAt: Long,
    val dirty: Boolean,
    /** 绑定的平台歌单（provider + 平台 id）；非空表示跟随平台更新。 */
    val linkProvider: String = "",
    val linkId: String = "",
    val linkCover: String = "",
    val linkSyncedAt: Long = 0L,
) {
    val bound get() = linkProvider.isNotBlank() && linkId.isNotBlank()
}

/**
 * 「加到歌单」面板里点一下（加入、移出、新建并加入）的结果。面板是单独的窗口，外壳的 Snackbar 被它盖着，
 * 提示由面板自己显示：[changed] 为 false 时 [text] 是原因，[error] 为 true 是失败（登录、收藏的歌单、平台出错）；
 * [removed] 是移出的原条目，撤销时按原位置插回。
 */
data class PlaylistEditResult(
    val changed: Boolean,
    val text: String = "",
    val error: Boolean = false,
    val removed: List<PlaylistEntryEntity> = emptyList(),
)

/** 播放队列移出的一首：撤销时按原位置（队列变短了放末尾）插回。[track] 是聚合曲目，旧数据对不上时为 null。 */
data class QueueRemoval(val index: Int, val song: Song, val track: CanonicalTrack?)

data class LocalPlaylistDetail(
    val playlist: PlaylistEntity,
    val entries: List<PlaylistEntryEntity>,
    val songs: List<Song>,
    val songByPosition: Map<Int, Song> = emptyMap(),
)

data class LibrarySearchUiState(
    val query: String = "",
    val results: List<Song> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
)

sealed interface UiEvent {
    data class OpenCollection(val title: String, val key: String) : UiEvent

    /** 打开歌手页；[key] 见 ArtistPages。 */
    data class OpenArtist(val key: String) : UiEvent
    data class Message(val text: String) : UiEvent

    /**
     * 只在本机的本地歌加进了已同步的歌单 / 收藏：提示一句，带“上传”按钮（设计稿第 ② 张底部）。
     * [ids] 是要提示上传的那几首的本地身份，点了打开上传面板。只提示，从不自动上传。
     */
    data class UploadPrompt(val text: String, val ids: List<String>) : UiEvent
}

data class LinkPreview(val link: Boolean, val text: String)

/** 从文件导入的进度（设计稿 06 节 ⑬）：读文件 → 预览 → 写库。外壳按它挂导入预览面板，从哪一页导入都盖在上面。 */
sealed interface TransferUi {
    data object Idle : TransferUi

    /** 读文件、本地匹配、咪咕换取；[step] 是面板上的进度文字。 */
    data class Reading(val fileName: String, val step: String, val fromPhone: Boolean = false) : TransferUi

    data class Preview(val plan: com.mytv.mymusic.data.transfer.ImportPlan) : TransferUi

    data class Applying(val plan: com.mytv.mymusic.data.transfer.ImportPlan) : TransferUi
}
