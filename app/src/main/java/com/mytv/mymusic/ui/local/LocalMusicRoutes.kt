package com.mytv.mymusic.ui.local

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mytv.mymusic.ui.AppNav

/** 「我的 › 本地音乐」的路由。设置在 settings/local，页右上角的 ⚙ 直接进那里。 */
const val LOCAL_MUSIC_ROUTE = "localMusic"

fun NavGraphBuilder.localMusicRoutes(app: AppNav) {
    composable(LOCAL_MUSIC_ROUTE) {
        val state by app.model.state.collectAsState()
        val playlists by app.model.localPlaylists.collectAsState()
        LocalMusicScreen(
            state = state,
            playlists = playlists,
            actions = app.actions,
            onBack = app::back,
            onOpenSettings = { app.openSettings("settings/local") },
        )
    }
}
