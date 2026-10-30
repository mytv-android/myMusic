package com.mytv.mymusic.ui.search

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mytv.mymusic.ui.AppNav
import com.mytv.mymusic.ui.TvExitBackHandler

fun NavGraphBuilder.searchRoutes(app: AppNav) {
    composable("search") {
        TvExitBackHandler()
        val state by app.model.state.collectAsState()
        val search by app.model.searchState.collectAsState()
        val favorites by app.model.favorites.collectAsState()
        val playlists by app.model.localPlaylists.collectAsState()
        val recognition by app.model.recognitionState.collectAsState()
        SearchScreen(
            state = state,
            search = search,
            favorites = favorites,
            playlists = playlists,
            actions = app.actions,
            recognition = recognition,
            onOpenSearchSettings = { app.openSettings("settings/search") },
        )
    }
}
