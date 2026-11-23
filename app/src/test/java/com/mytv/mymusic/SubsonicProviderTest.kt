package com.mytv.mymusic

import com.mytv.mymusic.data.native.AudioQuality
import com.mytv.mymusic.data.subsonic.SubsonicAccount
import com.mytv.mymusic.data.subsonic.SubsonicMapper
import com.mytv.mymusic.data.subsonic.SubsonicProvider
import com.mytv.mymusic.data.subsonic.structuredLyrics
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SubsonicProviderTest {
    private val server = MockWebServer()
    private val json = Json

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun childGoldenVectorMapsEveryPlaybackField() {
        val item = json.parseToJsonElement(
            """{
              "id":123,"title":"Song","artist":"Artist","displayArtist":"Display Artist",
              "album":"Album","albumId":456,"duration":231,"size":5365035,"bitRate":320,
              "suffix":"mp3","coverArt":"al-cover","track":3,"discNumber":1,"year":2024,
              "musicBrainzId":"mbid","isrc":["A","B"],"replayGain":{"trackGain":-3.2,"trackPeak":0.9}
            }""",
        ).jsonObject
        val song = SubsonicMapper.song(item, "subsonic:home") { id, size -> "file:///$id@$size" }!!
        assertEquals("123", song.id)
        assertEquals("subsonic:home:123", song.key)
        assertEquals("Display Artist", song.artist)
        assertEquals("456", song.albumId)
        assertEquals(231, song.duration)
        assertEquals(5365035L, song.size)
        assertEquals(320, song.bitrate)
        assertEquals("mp3", song.ext)
        assertEquals("file:///al-cover@300", song.cover)
        assertEquals("A", song.extra["isrc"])
        assertEquals("-3.2", song.extra["replay_gain"])
        assertEquals("1", song.extra["disc"])
    }

    @Test
    fun searchPaginatesAndPlayableUrlContainsNoCredentials() = runTest {
        server.enqueue(response("""{"searchResult3":{"song":[{"id":"s1","title":"One","duration":10}]}}"""))
        val provider = provider()
        val result = provider.searchSongsPage("", "one", 2, 20)
        assertEquals("s1", result.songs.single().id)
        assertEquals(2, result.page)
        assertFalse(result.hasMore)
        val query = server.takeRequest().url
        assertEquals("20", query.queryParameter("songOffset"))
        val stream = provider.playableStream("", result.songs.single(), File("."), AudioQuality.Auto)
        val streamUrl = stream.url.toHttpUrl()
        assertEquals("raw", streamUrl.queryParameter("format"))
        assertEquals(null, streamUrl.queryParameter("t"))
        assertEquals(null, streamUrl.queryParameter("p"))
        assertEquals(null, streamUrl.queryParameter("password"))
    }

    @Test
    fun structuredLyricsUsesMillisecondTimesAndSeparateOffset() {
        val lyrics = json.parseToJsonElement(
            """{"lang":"eng","synced":true,"offset":100,"line":[
              {"start":18800,"value":"one"},{"start":22801,"value":"two"}
            ]}""",
        ).jsonObject
        assertEquals("[offset:100]\n[00:18.800]one\n[00:22.801]two", structuredLyrics(lyrics))
    }

    @Test
    fun playlistRoleControlsCapabilities() {
        val readOnly = provider(SubsonicAccount(playlistRole = false))
        assertFalse(readOnly.info.playlistWrite)
        assertFalse(readOnly.info.playlistReorder)
        assertTrue(readOnly.info.search)
        assertTrue(readOnly.supportsCollect())
    }

    @Test
    fun addTracksUsesPostOnlyWhenServerAdvertisesFormPost() = runTest {
        server.enqueue(response("""{"playlist":{"id":"p","readonly":false}}"""))
        server.enqueue(response())
        val provider = provider(SubsonicAccount(extensions = listOf("formPost"), playlistRole = true))
        provider.addPlaylistTracks("", "p", listOf(song("a"), song("b")))
        server.takeRequest()
        val update = server.takeRequest()
        assertEquals("POST", update.method)
        assertTrue(update.body!!.utf8().contains("songIdToAdd=a"))
    }

    @Test
    fun structuredLyricsChoosesSyncedChineseBeforeUnknownLanguage() = runTest {
        server.enqueue(response("""{"lyricsList":{"structuredLyrics":[
          {"lang":"eng","synced":false,"line":[{"value":"plain"}]},
          {"lang":"none","synced":true,"line":[{"start":1000,"value":"unknown"}]},
          {"lang":"zho","synced":true,"line":[{"start":2000,"value":"中文"}]}
        ]}}"""))
        val text = provider(SubsonicAccount(openSubsonic = true, extensions = listOf("songLyrics")))
            .lyrics("", song("s1"))
        assertEquals("[00:02.000]中文", text)
    }

    @Test
    fun legacyLyricsReturnPlainTextAndEmptyStructuredListStaysEmpty() = runTest {
        server.enqueue(response("""{"lyrics":{"artist":"a","title":"s","value":"first\nsecond"}}"""))
        assertEquals("first\nsecond", provider().lyrics("", song("s1")))
        server.enqueue(response("""{"lyricsList":{}}"""))
        assertEquals("", provider(SubsonicAccount(openSubsonic = true, extensions = listOf("songLyrics"))).lyrics("", song("s1")))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun removeDuplicateTracksUsesOriginalIndicesInDescendingOrder() = runTest {
        server.enqueue(response("""{"playlist":{"id":"p","readonly":false}}"""))
        server.enqueue(response("""{"playlist":{"entry":[{"id":"a"},{"id":"b"},{"id":"a"}]}}"""))
        server.enqueue(response())
        provider().removePlaylistTracks("", "p", listOf(song("a"), song("a")))
        server.takeRequest()
        server.takeRequest()
        val update = server.takeRequest().url
        assertEquals(listOf("2", "0"), update.queryParameterValues("songIndexToRemove"))
    }

    @Test
    fun albumChildrenInheritParentMetadata() {
        val child = json.parseToJsonElement("""{"id":"s1","title":"Song"}""").jsonObject
        val parent = json.parseToJsonElement(
            """{"id":"a1","name":"Album","artist":"Artist","coverArt":"cover"}""",
        ).jsonObject
        val mapped = SubsonicMapper.song(child, "subsonic:home", parent) { id, _ -> id }!!
        assertEquals("Album", mapped.album)
        assertEquals("a1", mapped.albumId)
        assertEquals("Artist", mapped.artist)
        assertEquals("cover", mapped.extra["subsonic_cover"])
    }

    private fun provider(account: SubsonicAccount = SubsonicAccount()): SubsonicProvider = SubsonicProvider(
        account.copy(id = "home", username = "demo", password = "secret", url = server.url("/").toString()),
        OkHttpClient(),
    )

    private fun song(id: String) = com.mytv.mymusic.data.Song(id = id, name = id, source = "subsonic:home")

    private fun response(payload: String = ""): MockResponse {
        val fields = payload.trim().removePrefix("{").removeSuffix("}")
        val body = buildString {
            append("""{"subsonic-response":{"status":"ok"""")
            if (fields.isNotBlank()) append(',').append(fields)
            append("}}")
        }
        return MockResponse.Builder().body(body).build()
    }
}
