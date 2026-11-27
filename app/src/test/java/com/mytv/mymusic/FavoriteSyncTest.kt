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
import com.mytv.mymusic.data.native.NativeCrypto
import com.mytv.mymusic.data.native.NativeHttp
import com.mytv.mymusic.data.native.NetEaseProvider
import com.mytv.mymusic.data.native.PlaylistWriteException
import com.mytv.mymusic.data.native.PlaylistWriteFailure
import com.mytv.mymusic.data.native.QQProvider
import com.mytv.mymusic.ui.LocalPlaylistSummary
import com.mytv.mymusic.ui.library.libraryPlaylistGroups
import com.mytv.mymusic.ui.favoriteFailureReason
import com.mytv.mymusic.ui.favoriteSourceSong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

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

    @Test
    fun favoriteFailureReasonKeepsEachPlatformMessage() {
        assertEquals(
            "QQ 音乐歌单写入失败（code=1000，retCode=无）",
            favoriteFailureReason(PlaylistWriteException("QQ 音乐歌单写入失败（code=1000，retCode=无）")),
        )
        assertEquals(
            "汽水音乐收藏失败（1000004）",
            favoriteFailureReason(PlaylistWriteException("汽水音乐收藏失败（1000004）")),
        )
        assertEquals(
            "请重新登录",
            favoriteFailureReason(PlaylistWriteException("expired", PlaylistWriteFailure.NotLoggedIn)),
        )
        assertEquals(
            "请检查网络、登录和目标歌单",
            favoriteFailureReason(IllegalStateException("network down")),
        )
    }

    @Test
    fun favoriteKeepsOriginalProviderWhenPlaybackResolvedElsewhere() {
        val original = Song(id = "n1", source = "netease")
        val playing = Song(id = "q1", source = "qq")
        assertEquals("netease", favoriteSourceSong(playing, playing, original).source)
        // 列表里点另一首（不是当前播放）时不受当前播放源影响
        val other = Song(id = "n2", source = "netease")
        assertEquals("netease", favoriteSourceSong(other, playing, original).source)
        assertEquals("qq", favoriteSourceSong(playing, null, null).source)
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

class NeteaseDefaultFavoriteSyncTest {
    private val cookie = "MUSIC_U=fake-session; __csrf=fake-csrf"
    private val song = Song(id = "123", source = "netease", extra = mapOf("song_id" to "2150000001"))

    @Test
    fun defaultFavoriteUpdatesTheSongHeart() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    "/weapi/nuser/account/get" -> MockResponse(body = """{"code":200,"profile":{"userId":1234}}""")
                    "/eapi/song/like" -> MockResponse(body = """{"code":200,"playlistId":5678}""")
                    else -> MockResponse(code = 502)
                }
            }
            val write = favoriteWrite(server)

            for (liked in listOf(true, false)) {
                assertEquals("5678", write.apply(song, add = liked) { true })
                assertEquals("/weapi/nuser/account/get", server.takeRequest(2, TimeUnit.SECONDS)?.url?.encodedPath)
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals(server.url("/eapi/song/like").toString(), request.url.toString())
                assertEquals("/eapi/song/like", request.url.encodedPath)
                assertEquals(cookie, request.headers["Cookie"])
                assertTrue(request.headers["User-Agent"].orEmpty().startsWith("NeteaseMusic "))

                val encrypted = request.body?.utf8()?.removePrefix("params=")
                val apiPayload = NativeCrypto.aesEcbDecrypt(
                    NativeCrypto.hexToBytes(requireNotNull(encrypted)),
                    "e82ckenh8dichen8".toByteArray(),
                ).decodeToString()
                    .substringAfter("-36cd479b6b5-")
                    .substringBeforeLast("-36cd479b6b5-")
                val payload = NativeHttp.json.parseToJsonElement(apiPayload).jsonObject
                val header = payload.getValue("header").jsonObject
                assertEquals("2150000001", payload.getValue("trackId").jsonPrimitive.content)
                assertEquals(1234L, payload.getValue("userid").jsonPrimitive.longOrNull)
                assertEquals(liked.toString(), payload.getValue("like").jsonPrimitive.content)
                assertEquals("iphone", header.getValue("os").jsonPrimitive.content)
                assertEquals("fake-csrf", header.getValue("__csrf").jsonPrimitive.content)
                assertEquals("fake-session", header.getValue("MUSIC_U").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun expiredSessionDoesNotAttemptHeartWrite() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(body = """{"code":301,"profile":null}"""))
            val write = favoriteWrite(server)
            val error = runCatching { write.apply(song, add = true) { true } }.exceptionOrNull() as? PlaylistWriteException
            assertEquals(PlaylistWriteFailure.NotLoggedIn, error?.failure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun reportsThePlatformHttpStatusWhenTheLikeRequestFails() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(body = """{"code":200,"profile":{"userId":1234}}"""))
            server.enqueue(MockResponse.Builder().code(502).body("upstream failed").build())
            val error = runCatching { favoriteWrite(server).apply(song, add = true) { true } }
                .exceptionOrNull() as? PlaylistWriteException
            assertEquals(PlaylistWriteFailure.Other, error?.failure)
            assertTrue(error?.message.orEmpty().contains("HTTP 502"))
            assertTrue(error?.message.orEmpty().contains("返回码 无"))
        }
    }

    @Test
    fun missingPlaylistIdDoesNotFailASuccessfulHeartWrite() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(body = """{"code":200,"profile":null,"account":{"id":1234}}"""))
            server.enqueue(MockResponse(body = """{"code":200}"""))
            assertNull(favoriteWrite(server).apply(song, add = true) { true })
            assertEquals(2, server.requestCount)
        }
    }

    private fun favoriteWrite(server: MockWebServer): FavoriteSyncWrite {
        server.start()
        val provider = NetEaseProvider(
            server.url("/weapi/nuser/account/get").toString(),
            server.url("/eapi/song/like").toString(),
        )
        return requireNotNull(FavoriteSyncWrite.forSong(song, provider, cookie, FavoriteSyncPreference()))
    }
}

class QQFavoriteSyncTest {
    @Test
    fun signatureComesFromSessionSkeyNotMusicKey() {
        val (uin, gtk) = QQProvider().qqLogin("uin=o123456; skey=session-key; qm_keyst=music-key")
        assertEquals("123456", uin)
        assertEquals(gtk33("session-key"), gtk)
        assertTrue(gtk != gtk33("music-key"))
    }

    @Test
    fun wechatLoginUsesEuinAsAccountWithoutSkey() {
        val (uin, gtk) = QQProvider().qqLogin("euin=oFakeEuin123**; qm_keyst=W_X_test")
        assertEquals("oFakeEuin123**", uin)
        assertEquals(5381, gtk)
    }

    @Test
    fun numericUinWinsOverEuinForDirPlaylists() {
        val (uin, _) = QQProvider().qqLogin("uin=o1205039851; euin=oFakeEuin123**; qm_keyst=W_X_test")
        assertEquals("1205039851", uin)
    }

    private fun gtk33(value: String): Int {
        var hash = 5381
        for (char in value) hash += (hash shl 5) + char.code
        return hash and 0x7fffffff
    }
}
