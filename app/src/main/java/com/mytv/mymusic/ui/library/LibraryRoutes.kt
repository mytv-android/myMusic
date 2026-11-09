package com.mytv.mymusic.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.mytv.mymusic.ui.AppNav
import com.mytv.mymusic.ui.TvExitBackHandler
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import com.mytv.mymusic.ui.local.LOCAL_MUSIC_ROUTE
import com.mytv.mymusic.ui.local.localMusicRoutes
import kotlinx.coroutines.launch

/**
 * 我的：本机音乐库、喜欢 / 最近 / 下载、自建歌单和同步。
 * 手机上设置入口在“我的”右上角；平板和电视在导航栏底部。
 */
fun NavGraphBuilder.libraryRoutes(app: AppNav) {
    composable("library") {
        TvExitBackHandler()
        val state by app.model.state.collectAsState()
        val favorites by app.model.favorites.collectAsState()
        val history by app.model.history.collectAsState()
        val downloads by app.model.downloads.collectAsState()
        val localPlaylists by app.model.localPlaylists.collectAsState()
        val covers by app.model.localPlaylistCovers.collectAsState()
        val sync by app.model.syncState.collectAsState()
        val listen by app.model.listenTaskState.collectAsState()
        val soda by app.model.sodaWelfare.collectAsState()
        val nav = remember(app) {
            LibraryNav(
                openSettings = { app.openSettings("settings") },
                openSync = { app.nav.navigate("syncCenter") },
                openPlaylist = { id -> app.nav.navigate("localPlaylist/$id") },
                openFavorites = { app.nav.navigate("favorites") },
                openHistory = { app.nav.navigate("history") },
                openDownloads = { app.nav.navigate("downloads") },
                openCharts = { app.openTab("charts") },
                openWelfare = { app.nav.navigate("welfare") },
                openLocalMusic = { app.nav.navigate(LOCAL_MUSIC_ROUTE) },
            )
        }
        // 「我的」页的入口卡也要显示任务进度，进页面就把服务端状态读回来
        LaunchedEffect(Unit) { app.model.ensureSodaWelfareLoaded() }
        LibraryScreen(
            state = state,
            favorites = favorites,
            history = history,
            downloads = downloads,
            playlists = localPlaylists,
            covers = covers,
            sync = sync,
            listen = listen,
            soda = soda,
            actions = app.actions,
            nav = nav,
            // ≥840dp 平板：选中的歌单直接显示在右栏，不跳页
            detailPane = { id, onClosed -> LocalPlaylistHost(app, id, embedded = true, onBack = onClosed) },
        )
    }
    composable(
        "localPlaylist/{playlistId}",
        arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString("playlistId").orEmpty()
        LocalPlaylistHost(app, id, embedded = false, onBack = app::back)
    }
    composable("favorites") {
        val state by app.model.state.collectAsState()
        val favorites by app.model.favorites.collectAsState()
        val downloads by app.model.downloads.collectAsState()
        val localPlaylists by app.model.localPlaylists.collectAsState()
        FavoritesScreen(
            state = state,
            favorites = favorites,
            downloads = downloads,
            playlists = localPlaylists,
            actions = app.actions,
            onBack = app::back,
            onBrowse = { app.openTab("charts") },
        )
    }
    composable("history") {
        val state by app.model.state.collectAsState()
        val history by app.model.history.collectAsState()
        val favorites by app.model.favorites.collectAsState()
        val localPlaylists by app.model.localPlaylists.collectAsState()
        HistoryScreen(
            state = state,
            history = history,
            favorites = favorites,
            playlists = localPlaylists,
            actions = app.actions,
            onBack = app::back,
        )
    }
    composable("downloads") {
        val state by app.model.state.collectAsState()
        val downloads by app.model.downloads.collectAsState()
        // mediaCacheSize 会扫描缓存目录；用 produceState 放到后台，记录变化后再刷新
        val cacheBytes by produceState(initialValue = 0L, downloads) {
            value = app.model.mediaCacheSize()
        }
        DownloadsScreen(
            state = state,
            downloads = downloads,
            cacheBytes = cacheBytes,
            actions = app.actions,
            onBack = app::back,
        )
    }
    composable("welfare") {
        TvExitBackHandler()
        val soda by app.model.sodaWelfare.collectAsState()
        val listen by app.model.listenTaskState.collectAsState()
        val snackbar = LocalAppSnackbar.current
        val scope = rememberCoroutineScope()
        WelfareScreen(
            soda = soda,
            todayMinutes = listen.todayMinutes,
            onRefresh = app.model::refreshSodaWelfare,
            onApply = {
                app.model.applySodaFreeVip { result ->
                    scope.launch { snackbar.showSnackbar(result.message) }
                }
            },
            // 没登录汽水时去平台账号页扫码
            onLogin = { app.openSettings("settings/accounts") },
            onBack = app::back,
        )
    }
    localMusicRoutes(app)
    composable("syncCenter") {
        val sync by app.model.syncState.collectAsState()
        SyncCenterScreen(sync, app.actions, onBack = app::back) { provider ->
            app.nav.navigate("syncConnect/$provider")
        }
    }
    composable(
        "syncConnect/{provider}",
        arguments = listOf(navArgument("provider") { type = NavType.StringType }),
    ) { entry ->
        val sync by app.model.syncState.collectAsState()
        SyncConnectScreen(
            sync = sync,
            actions = app.actions,
            initialProvider = entry.arguments?.getString("provider"),
            onBack = app::back,
            onConnected = app::back,
        )
    }
}

@Composable
private fun LocalPlaylistHost(app: AppNav, id: String, embedded: Boolean, onBack: () -> Unit) {
    val detailFlow = remember(id) { app.model.localPlaylistDetail(id) }
    val state by app.model.state.collectAsState()
    val sync by app.model.syncState.collectAsState()
    val librarySearch by app.model.librarySearch.collectAsState()
    val favorites by app.model.favorites.collectAsState()
    val localPlaylists by app.model.localPlaylists.collectAsState()
    val boundAccess by app.actions.boundAccess.collectAsState()
    LocalPlaylistScreen(
        playlistId = id,
        detailFlow = detailFlow,
        state = state,
        sync = sync,
        librarySearch = librarySearch,
        favorites = favorites,
        playlists = localPlaylists,
        boundAccess = boundAccess[id],
        actions = app.actions,
        onBack = onBack,
        onOpenSync = { app.nav.navigate("syncCenter") },
        embedded = embedded,
    )
}
