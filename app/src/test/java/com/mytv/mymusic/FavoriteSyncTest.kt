package com.mytv.mymusic

import com.mytv.mymusic.data.FavoriteSyncIssue
import com.mytv.mymusic.data.FavoriteSyncMode
import com.mytv.mymusic.data.FavoriteSyncPreference
import com.mytv.mymusic.data.FavoriteSyncWrite
import com.mytv.mymusic.data.FavoriteWriteQueue
import com.mytv.mymusic.data.Playlist
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.favoriteCredentialFingerprint
import com.mytv.mymusic.data.favoriteSyncPlaylists
import com.mytv.mymusic.data.native.BaseNativeProvider
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.library.libraryPlaylistGroups
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteSyncTest {
    private val song = Song(id = "song", source = "test-source")
    private val cookie = "auth=fake-test-account"
    private val chosen = FavoriteSyncPreference(
        FavoriteSyncMode.Playlist, "custom", "自定义歌单", favoriteCredentialFingerprint(cookie),
    )

    @Test
    fun sameSongUsesDefaultAndCustomTargetsInOrder() = runTest {
        val provider = FakeProvider()
        val queue = FavoriteWriteQueue(this)
        val defaultWrite = FavoriteSyncWrite.forSong(song, provider, cookie, FavoriteSyncPreference())!!
        val customWrite = FavoriteSyncWrite.forSong(song, provider, cookie, chosen)!!
        val firstWrite = CompletableDeferred<Unit>()
        queue.enqueue(song.key) {
            firstWrite.await()
            assertEquals("native", defaultWrite.apply(song, add = true) { true })
        }
        queue.enqueue(song.key) { assertEquals("custom", customWrite.apply(song, add = false) { true }) }
        queue.enqueue(song.key) { customWrite.apply(song, add = true) { true } }
        runCurrent()
        assertTrue(provider.writes.isEmpty())
        firstWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf("add:native:song", "remove:custom:song", "add:custom:song"), provider.writes)
    }

    @Test
    fun skipsLocalUnsupportedAndLiveTracks() {
        val provider = FakeProvider()
        val default = FavoriteSyncPreference()
        assertNull(FavoriteSyncWrite.forSong(song.copy(source = "local"), null, "", default))
        assertNull(FavoriteSyncWrite.forSong(song.copy(extra = mapOf("live" to "1")), provider, cookie, default))
        assertNull(FavoriteSyncWrite.forSong(song, FakeProvider(native = false), cookie, default))
        assertNull(FavoriteSyncWrite.forSong(song, provider, cookie, FavoriteSyncPreference(FavoriteSyncMode.LocalOnly)))
    }

    @Test
    fun refusesStaleCredentialsAndAnUnwritableTarget() = runTest {
        val provider = FakeProvider()
        val stale = FavoriteSyncWrite.forSong(song, provider, "auth=another-account", chosen)!!
        assertTrue(runCatching { stale.apply(song, add = false) { true } }.exceptionOrNull() is FavoriteSyncIssue)

        val write = FavoriteSyncWrite.forSong(song, provider, cookie, chosen)!!
        var accountChecks = 0
        assertTrue(runCatching { write.apply(song, add = true) { ++accountChecks == 1 } }.exceptionOrNull() is FavoriteSyncIssue)

        for (writable in listOf(false, null)) {
            provider.writable = writable
            assertTrue(runCatching { write.apply(song, add = false) { true } }.exceptionOrNull() is FavoriteSyncIssue)
        }
        assertTrue(provider.writes.isEmpty())
    }

    @Test
    fun pickerOffersEmptyWritablePlaylistsAndPaginates() = runTest {
        val provider = FakeProvider()
        provider.pages[1] = (1..100).map { number -> Playlist("list-$number", "歌单 $number", source = provider.info.id) }
        provider.pages[2] = listOf(
            Playlist("empty", "空歌单", source = provider.info.id),
            Playlist("readonly", "只读", source = provider.info.id),
        )
        val choices = provider.favoriteSyncPlaylists(cookie)
        assertEquals(101, choices.size)
        assertEquals("empty", choices.last().id)
        assertEquals(0, choices.last().trackCount)
    }

    @Test
    fun playlistGroupsUseLinksAndKeepEachGroupsOrder() {
        val qqFirst = playlist("qq-1", "qq", "remote-1")
        val qqSecond = playlist("qq-2", "qq", "remote-2")
        val local = playlist("local")
        val incompleteLink = playlist("incomplete", "qq")
        val server = playlist("server", "subsonic:home", "external")
        val groups = libraryPlaylistGroups(listOf(qqFirst, local, server, qqSecond, incompleteLink))
        assertEquals(listOf("", "qq", "subsonic:home"), groups.keys.toList())
        assertEquals(listOf(local, incompleteLink), groups[""])
        assertEquals(listOf(qqFirst, qqSecond), groups["qq"])
        assertEquals(listOf(server), groups["subsonic:home"])
        val unbound = qqFirst.copy(linkProvider = "", linkId = "")
        assertEquals(listOf(unbound), libraryPlaylistGroups(listOf(unbound, qqSecond))[""])
    }

    private fun playlist(id: String, provider: String = "", remoteId: String = "") =
        LocalPlaylistSummary(id, id, 0, 0, false, linkProvider = provider, linkId = remoteId)

    private class FakeProvider(private val native: Boolean = true) :
        BaseNativeProvider(ProviderInfo("test-source", "测试音源", userPlaylists = true, playlistWrite = true)) {
        val writes = mutableListOf<String>()
        val pages = mutableMapOf<Int, List<Playlist>>()
        var writable: Boolean? = true
        override fun supportsCollect() = native
        override suspend fun userPlaylists(cookie: String, page: Int, limit: Int) = pages[page].orEmpty()
        override fun playlistWritableFromAccountList(playlist: Playlist): Boolean = playlist.id != "readonly"
        override suspend fun playlistWritable(cookie: String, id: String): Boolean? = writable
        override suspend fun collectTrack(cookie: String, song: Song): String {
            writes += "add:native:${song.id}"
            return "native"
        }
        override suspend fun addPlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
            writes += "add:$playlistId:${songs.single().id}"
        }
        override suspend fun removePlaylistTracks(cookie: String, playlistId: String, songs: List<Song>) {
            writes += "remove:$playlistId:${songs.single().id}"
        }
    }
}
