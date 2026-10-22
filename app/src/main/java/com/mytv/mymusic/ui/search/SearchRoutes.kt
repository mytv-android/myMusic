package com.mytv.mymusic.ui.search

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mytv.mymusic.ui.AppNav

fun NavGraphBuilder.searchRoutes(app: AppNav) {
    composable("search") {
        val state by app.model.state.collectAsState()
        val search by app.model.searchState.collectAsState()
        val favorites by app.model.favorites.collectAsState()
        val playlists by app.model.localPlaylists.collectAsState()
        SearchScreen(
            state = state,
            search = search,
            favorites = favorites,
            playlists = playlists,
            actions = app.actions,
            onOpenSearchSettings = { app.openSettings("settings/search") },
        )
    }
}
