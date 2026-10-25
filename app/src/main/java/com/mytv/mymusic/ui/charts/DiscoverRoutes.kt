package com.mytv.mymusic.ui.charts

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.AppNav
import com.mytv.mymusic.ui.CollectionScreen
import com.mytv.mymusic.ui.playlists.PlaylistsScreen
import com.mytv.mymusic.ui.song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 发现：榜单、歌单，以及两者共用的榜单 / 歌单 / 专辑详情页。 */
fun NavGraphBuilder.discoverRoutes(app: AppNav) {
    composable("charts") {
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
    composable("collection") {
        val state by app.model.state.collectAsState()
        val collection by app.model.collectionState.collectAsState()
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
