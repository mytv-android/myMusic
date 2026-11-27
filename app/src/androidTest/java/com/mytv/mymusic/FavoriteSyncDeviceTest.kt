package com.mytv.mymusic

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytv.mymusic.data.FavoriteSyncMode
import com.mytv.mymusic.data.FavoriteSyncPreference
import com.mytv.mymusic.data.ListenTaskState
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.BaseNativeProvider
import com.mytv.mymusic.data.native.NativeProviders
import com.mytv.mymusic.data.sync.SyncUiState
import com.mytv.mymusic.ui.KugouWelfareUi
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.MusicActions
import com.mytv.mymusic.ui.MusicUiState
import com.mytv.mymusic.ui.SodaWelfareUi
import com.mytv.mymusic.ui.components.AppWindow
import com.mytv.mymusic.ui.components.DeviceKind
import com.mytv.mymusic.ui.components.LocalAppWindow
import com.mytv.mymusic.ui.library.LibraryNav
import com.mytv.mymusic.ui.library.LibraryScreen
import com.mytv.mymusic.ui.settings.FavoriteSyncSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FavoriteSyncDeviceTest {
    @Test
    fun oldFavoriteRemovesFromCurrentTargetAndUndoRestoresOriginalTarget() = runBlocking {
        val provider = FixtureProvider()
        val app = ApplicationProvider.getApplicationContext<MyMusicApp>()
        val music = app.database.dao()
        val library = app.database.library()
        val song = Song(id = "only-track", name = "Test favorite", source = provider.info.id)
        val localPlaylistId = UUID.randomUUID().toString()
        val payload = Json.encodeToString(song)
        val previous = SavedSong(song.key, "favorite", payload, updatedAt = 1234)
        val startTarget = Playlist("first", "First", source = provider.info.id)
        val nextTarget = Playlist("next", "Next", source = provider.info.id)
        val cookie = "auth=fake"
        NativeProviders.register(provider)
        try {
            app.settings.cookie(provider.info.id, cookie)
            music.save(previous)
            library.putPlaylist(PlaylistEntity(localPlaylistId, "Test playlist", 0, 0, linkProvider = provider.info.id, linkId = startTarget.id))
            library.replaceEntries(localPlaylistId, listOf(PlaylistEntryEntity(localPlaylistId, 0, song.key, payload)))

            withContext(Dispatchers.Main) {
                val model = app.musicViewModel
                model.saveFavoriteSync(provider.info.id, FavoriteSyncMode.Playlist, startTarget, cookie)
                val undo = model.removeFavorite(song)
                undo.await()
                assertNull(music.savedSong("favorite", song.key))
                assertEquals("remove:first", withTimeout(5_000) { provider.writes.receive() })
                withTimeout(5_000) { library.entries(localPlaylistId).first { it.isEmpty() } }

                model.saveFavoriteSync(provider.info.id, FavoriteSyncMode.Playlist, nextTarget, cookie)
                model.undoFavorite(undo).join()
                assertEquals(previous, music.savedSong("favorite", song.key))
                assertEquals("add:first", withTimeout(5_000) { provider.writes.receive() })
                withTimeout(5_000) { library.entries(localPlaylistId).first { it.singleOrNull()?.songKey == song.key } }

                provider.failWrites = true
                model.removeFavorite(song).await()
                assertEquals("remove:next", withTimeout(5_000) { provider.writes.receive() })
                assertNull(music.savedSong("favorite", song.key))
            }
        } finally {
            NativeProviders.unregister(provider.info.id)
            app.settings.saveFavoriteSync(provider.info.id, FavoriteSyncPreference())
            app.settings.cookie(provider.info.id, "")
            music.remove("favorite", song.key)
            music.clearTombstone("favorite", song.key)
            library.deletePlaylist(localPlaylistId)
        }
    }

    private class FixtureProvider :
        BaseNativeProvider(ProviderInfo("test-${UUID.randomUUID()}", "测试收藏", userPlaylists = true, playlistWrite = true)) {
        val writes = Channel<String>(Channel.UNLIMITED)
        var failWrites = false
        private val contents = mutableMapOf<String, List<Song>>()

        override suspend fun playlistWritable(cookie: String, id: String): Boolean = true
        override suspend fun playlistSongs(cookie: String, id: String): List<Song> = contents[id].orEmpty()

        override suspend fun addPlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
            contents[playlistId] = songs
            writes.send("add:$playlistId")
        }

        override suspend fun removePlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
            writes.send("remove:$playlistId")
            if (failWrites) throw IOException("test network failure")
            contents.remove(playlistId)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class FavoriteSyncUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun phoneSwitchesPlaylistSources() = checkSourceTabs(AppWindow(DeviceKind.Phone, 400, 800))

    @Test
    fun tabletSwitchesPlaylistSources() = checkSourceTabs(AppWindow(DeviceKind.Tablet, 880, 800))

    @Test
    fun televisionSwitchesPlaylistSources() = checkSourceTabs(AppWindow(DeviceKind.Tv, 1000, 600))

    @Test
    fun settingsShowAllFavoriteTargetOptions() {
        val provider = requireNotNull(NativeProviders.get("qq")).info
        val app = ApplicationProvider.getApplicationContext<MyMusicApp>()
        compose.setContent {
            MaterialTheme {
                FavoriteSyncSettingsScreen(MusicUiState(providers = listOf(provider)), MusicActions(app.musicViewModel), {}, false)
            }
        }
        compose.onNodeWithText(provider.name).performClick()
        compose.onNodeWithText("平台默认").assertIsDisplayed()
        compose.onNodeWithText("指定歌单").assertIsDisplayed()
        compose.onNodeWithText("仅 App 收藏").assertIsDisplayed()
    }

    private fun checkSourceTabs(window: AppWindow) {
        val app = ApplicationProvider.getApplicationContext<MyMusicApp>()
        val provider = ProviderInfo("fixture", "测试音源")
        val bound = LocalPlaylistSummary("remote", "平台歌单", 0, 0, false, linkProvider = provider.id, linkId = "bound")
        val local = List(20) { index -> LocalPlaylistSummary("local-$index", "本地歌单 $index", 0, 0, false) }
        compose.setContent {
            CompositionLocalProvider(LocalAppWindow provides window) {
                MaterialTheme {
                    LibraryScreen(
                        state = MusicUiState(providers = listOf(provider)),
                        favorites = emptyList(),
                        history = emptyList(),
                        downloads = emptyList(),
                        playlists = listOf(bound) + local,
                        covers = emptyMap(),
                        sync = SyncUiState(),
                        listen = ListenTaskState(),
                        soda = SodaWelfareUi(),
                        kugou = KugouWelfareUi(),
                        actions = MusicActions(app.musicViewModel),
                        nav = LibraryNav({}, {}, {}, {}, {}, {}, {}, {}),
                        detailPane = { playlistId, _ -> Text("详情 $playlistId") },
                    )
                }
            }
        }
        val list = compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        val heading = compose.onNode(hasText("我的") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        val all = compose.onNodeWithText("全部").assertIsDisplayed().assertIsSelected()
        assertTrue(all.fetchSemanticsNode().boundsInRoot.top >= heading.fetchSemanticsNode().boundsInRoot.bottom)
        compose.onNodeWithText(provider.name).assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithText(bound.name).assertIsDisplayed()
        compose.onNodeWithText(local.first().name).assertDoesNotExist()
        if (window.isTablet) compose.onNodeWithText("详情 ${bound.id}").assertIsDisplayed()

        compose.onNodeWithText("本地").performClick().assertIsSelected()
        compose.onNodeWithText(bound.name).assertDoesNotExist()
        compose.onNodeWithText(local.first().name).assertIsDisplayed()

        if (!window.isTv) {
            list.performScrollToKey("pl:local-10")
            compose.onNodeWithText(provider.name).assertIsDisplayed().performClick()
            compose.onNodeWithText(bound.name).assertIsDisplayed()
        }
        compose.onNodeWithText("全部").performClick().assertIsSelected()
        list.performScrollToKey(if (window.isTv) "playlists:${provider.id}" else "pl:${bound.id}")
        compose.onNodeWithText(bound.name).assertIsDisplayed()
    }
}
