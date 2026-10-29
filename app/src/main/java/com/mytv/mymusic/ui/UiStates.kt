package com.mytv.mymusic.ui

import com.mytv.mymusic.aggregate.CanonicalCollection
import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.ProviderSearchState
import com.mytv.mymusic.aggregate.SearchStatus
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

data class CollectionUiState(
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
)

data class LocalPlaylistSummary(
    val id: String,
    val name: String,
    val trackCount: Int,
    val updatedAt: Long,
    val dirty: Boolean,
)

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
    data class OpenCollection(val title: String) : UiEvent
    data class Message(val text: String) : UiEvent
}

data class LinkPreview(val link: Boolean, val text: String)
