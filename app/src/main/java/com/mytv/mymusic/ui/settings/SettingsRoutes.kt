package com.mytv.mymusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mytv.mymusic.ui.AppNav
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.library.SyncCenterScreen
import kotlinx.coroutines.launch
import java.io.File

/**
 * 设置的路由。手机和 <840dp 平板：“settings” 是分组列表，每个分区是独立子页；
 * ≥840dp 平板和电视：所有设置路由都打开同一个列表 + 详情双栏，路由只决定默认选中哪一项，
 * 所以 openSettings("settings/playback") 这样的深链接在大屏上会直接选中对应分区。
 */
fun NavGraphBuilder.settingsRoutes(app: AppNav) {
    composable("settings") { SettingsRoute(app, null) }
    SettingsSection.entries.forEach { section ->
        composable(section.route) { SettingsRoute(app, section) }
    }
}

@Composable
private fun SettingsRoute(app: AppNav, section: SettingsSection?) {
    val window = LocalAppWindow.current
    // 清除播放缓存后让列表里的“存储”占用跟着刷新
    var cacheTick by remember { mutableIntStateOf(0) }
    when {
        window.twoPane -> SettingsTwoPane(
            overview = rememberSettingsOverview(app, cacheTick),
            initial = section ?: SettingsSection.Appearance,
        ) { selected, open ->
            SectionContent(
                app = app,
                section = selected,
                embedded = true,
                onBack = { selected.parent?.let(open) },
                onOpen = open,
                onCacheCleared = { cacheTick++ },
            )
        }
        section == null -> SettingsHomeScreen(
            overview = rememberSettingsOverview(app, cacheTick),
            onOpen = { app.nav.navigate(it.route) { launchSingleTop = true } },
            // 手机从“我的”右上角进来，要能返回；平板的设置在导航栏上，是顶层目的地
            onBack = if (window.isPhone) app::back else null,
        )
        else -> SectionContent(
            app = app,
            section = section,
            embedded = false,
            onBack = app::back,
            onOpen = { app.nav.navigate(it.route) { launchSingleTop = true } },
            onCacheCleared = { cacheTick++ },
        )
    }
}

@Composable
private fun SectionContent(
    app: AppNav,
    section: SettingsSection,
    embedded: Boolean,
    onBack: () -> Unit,
    onOpen: (SettingsSection) -> Unit,
    onCacheCleared: () -> Unit,
) {
    val actions = app.actions
    when (section) {
        SettingsSection.Appearance -> {
            val state by app.model.state.collectAsState()
            AppearanceSettingsScreen(state, actions, onBack, embedded)
        }
        SettingsSection.Playback -> {
            val state by app.model.state.collectAsState()
            PlaybackSettingsScreen(state, actions, onBack, embedded)
        }
        SettingsSection.Equalizer -> {
            val state by app.model.state.collectAsState()
            val equalizer by app.model.equalizerState.collectAsState()
            EqualizerScreen(state, equalizer, actions, onBack, embedded)
        }
        SettingsSection.Search -> {
            val state by app.model.state.collectAsState()
            SearchBrowseSettingsScreen(state, actions, onBack, embedded)
        }
        SettingsSection.Accounts -> {
            val state by app.model.state.collectAsState()
            AccountsSettingsScreen(state, actions, onBack, embedded)
        }
        SettingsSection.Sync -> {
            val sync by app.model.syncState.collectAsState()
            SyncCenterScreen(sync, actions, onBack = onBack, embedded = embedded) { provider ->
                app.nav.navigate("syncConnect/$provider")
            }
        }
        SettingsSection.Storage -> StorageContent(app, onBack, embedded, onCacheCleared)
        SettingsSection.About -> {
            val state by app.model.state.collectAsState()
            val update by app.model.updateState.collectAsState()
            AboutScreen(
                state = state,
                update = update,
                actions = actions,
                onBack = onBack,
                onLicenses = { onOpen(SettingsSection.Licenses) },
                onPrivacy = { onOpen(SettingsSection.Privacy) },
                embedded = embedded,
            )
        }
        SettingsSection.Licenses -> LicensesScreen(onBack, embedded)
        SettingsSection.Privacy -> PrivacyScreen(onBack, embedded)
    }
}

@Composable
private fun StorageContent(app: AppNav, onBack: () -> Unit, embedded: Boolean, onCleared: () -> Unit) {
    val downloads by app.model.downloads.collectAsState()
    val state by app.model.state.collectAsState()
    val downloadsBytes = remember(downloads) { downloads.sumOf { downloadSize(it.path) } }
    var cacheBytes by remember { mutableLongStateOf(app.model.mediaCacheSize()) }
    val scope = rememberCoroutineScope()
    StorageSettingsScreen(
        cacheBytes = cacheBytes,
        downloadsBytes = downloadsBytes,
        downloadsCount = downloads.size,
        onClearCache = {
            scope.launch {
                // 清除在 IO 线程进行，完成后再读一次占用
                app.actions.clearMediaCache().join()
                cacheBytes = app.model.mediaCacheSize()
                onCleared()
            }
        },
        onClearMatch = app.actions::clearMatchCache,
        cacheLimitMb = state.cacheLimitMb,
        onCacheLimit = {
            app.actions.setCacheLimit(it)
            // 调低上限会立即淘汰，稍后刷新占用
            scope.launch {
                kotlinx.coroutines.delay(600)
                cacheBytes = app.model.mediaCacheSize()
            }
        },
        onOpenDownloads = { app.nav.navigate("downloads") { launchSingleTop = true } },
        onBack = onBack,
        embedded = embedded,
    )
}

@Composable
private fun rememberSettingsOverview(app: AppNav, cacheTick: Int): SettingsOverview {
    val state by app.model.state.collectAsState()
    val sync by app.model.syncState.collectAsState()
    val update by app.model.updateState.collectAsState()
    val equalizer by app.model.equalizerState.collectAsState()
    val downloads by app.model.downloads.collectAsState()
    val downloadsBytes = remember(downloads) { downloads.sumOf { downloadSize(it.path) } }
    val cacheBytes = remember(state.current?.key, cacheTick) { app.model.mediaCacheSize() }
    return settingsOverview(state, sync, update, equalizer, cacheBytes, downloadsBytes)
}

private fun downloadSize(path: String): Long = runCatching { File(path).length() }.getOrDefault(0L)
