package com.mytv.mymusic.ui.local

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mytv.mymusic.data.sync.WebDavSyncProvider
import com.mytv.mymusic.ui.AppNav

/** 「我的 › 本地音乐」的路由。设置在 settings/local，页右上角的 ⚙ 直接进那里。 */
const val LOCAL_MUSIC_ROUTE = "localMusic"

/** 云端视图里 WebDAV / QQ 云盘那一行点进来的列表。 */
const val LOCAL_CLOUD_ROUTE = "localMusic/cloud/{provider}"

fun localCloudRoute(providerId: String) = "localMusic/cloud/$providerId"

/**
 * “连接 WebDAV”的去处：同步已经在用 WebDAV 时去同步那边（账号只有一份，在那里改）；
 * 否则去只给上传用的那张表单。
 */
fun localWebDavRoute(app: AppNav): String =
    if (app.model.syncState.value.account?.provider == WebDavSyncProvider.ID) "syncConnect/${WebDavSyncProvider.ID}" else LOCAL_WEBDAV_ROUTE

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
            onOpenAccounts = { app.openSettings("settings/accounts") },
            onConnectWebDav = { app.nav.navigate(localWebDavRoute(app)) { launchSingleTop = true } },
            onOpenCloudList = { provider -> app.nav.navigate(localCloudRoute(provider.id)) { launchSingleTop = true } },
        )
    }
    composable(LOCAL_CLOUD_ROUTE) { entry ->
        val state by app.model.state.collectAsState()
        LocalCloudListScreen(
            providerId = entry.arguments?.getString("provider").orEmpty(),
            actions = app.actions,
            playingKey = state.current?.key,
            onBack = app::back,
            onOpenAccounts = { app.openSettings("settings/accounts") },
            onConnectWebDav = { app.nav.navigate(localWebDavRoute(app)) { launchSingleTop = true } },
        )
    }
    composable(LOCAL_WEBDAV_ROUTE) {
        val sync by app.model.syncState.collectAsState()
        LocalWebDavScreen(
            sync = sync,
            actions = app.actions,
            onBack = app::back,
            onOpenSyncWebDav = { app.nav.navigate("syncConnect/${WebDavSyncProvider.ID}") { launchSingleTop = true } },
        )
    }
}
