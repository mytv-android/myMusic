package com.mytv.mymusic.ui.charts

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.AppNav
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mytv.mymusic.ui.COLLECTION_ROUTE
import com.mytv.mymusic.ui.CollectionScreen
import com.mytv.mymusic.ui.CollectionUiState
import com.mytv.mymusic.ui.TvExitBackHandler
import com.mytv.mymusic.ui.playlists.PlaylistsScreen
import com.mytv.mymusic.ui.song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 发现：榜单、歌单，以及两者共用的榜单 / 歌单 / 专辑详情页。 */
fun NavGraphBuilder.discoverRoutes(app: AppNav) {
    composable("charts") {
        TvExitBackHandler()
        val state by app.model.state.collectAsState()
        val charts by app.model.chartsState.collectAsState()
        val favorites by app.model.favorites.collectAsState()
        val localPlaylists by app.model.localPlaylists.collectAsState()
        val playlists by app.model.playlistsState.collectAsState()
        val history by app.model.history.collectAsState()
        // 电视的“最近播放”行只取前 20 条；payload 解码放后台线程
        val recent by produceState(initialValue = emptyList<Song>(), history) {
            value = withContext(Dispatchers.IO) {
                history.take(20).mapNotNull { runCatching { it.song() }.getOrNull() }
            }
        }
        ChartsScreen(
            state = state,
            charts = charts,
            actions = app.actions,
            favorites = favorites,
            playlists = localPlaylists,
            recommended = playlists.recommendations,
            history = recent,
        )
    }
    composable("playlists") {
        TvExitBackHandler()
        val state by app.model.state.collectAsState()
        val playlists by app.model.playlistsState.collectAsState()
        val categoryEnd by app.model.categoryEnd.collectAsState()
        PlaylistsScreen(
            state = state,
            playlists = playlists,
            actions = app.actions,
            onOpenSearch = { app.openTab("search") },
            categoryEnd = categoryEnd,
        )
    }
    composable("$COLLECTION_ROUTE/{key}") { entry ->
        val key = entry.arguments?.getString("key").orEmpty()
        // 每页按 key 取自己的内容；回到前台（进入或从上一层返回）时设为当前页，播放全部、收藏等都对它操作
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { app.model.activateCollection(key) }
        val state by app.model.state.collectAsState()
        val pages by app.model.collectionPages.collectAsState()
        val collection = pages[key] ?: CollectionUiState(key = key, loading = true)
        val favorites by app.model.favorites.collectAsState()
        val localPlaylists by app.model.localPlaylists.collectAsState()
        CollectionScreen(
            state = state,
            collection = collection,
            favorites = favorites,
            playlists = localPlaylists,
            actions = app.actions,
            onBack = app::back,
            onOpenPlaybackSettings = { app.openSettings("settings/playback") },
        )
    }
}
