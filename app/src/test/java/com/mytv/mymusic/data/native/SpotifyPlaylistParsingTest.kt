package com.mytv.mymusic.data.native

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Spotify 匿名 Web Player 的歌单目录解析。
 *
 * 这里的 JSON 都是从 `api-partner.spotify.com/pathfinder/v2/query` 的真实响应里摘出来的，
 * 当作 golden vector 用——上游改字段名时这些断言会先炸。
 */
class SpotifyPlaylistParsingTest {

    private val provider = SpotifyProvider()
    private val json = NativeHttp.json

    // fetchPlaylistContents 响应里的一首曲目：albumOfTrack.coverArt 是每首歌自己的专辑封面
    private val contentsTrack = """
        {"__typename":"Track","albumOfTrack":{"artists":{"items":[{"profile":{"name":"Ashe"},
        "uri":"spotify:artist:6P5NO5hzJbuOqSdyPB7SJM"}]},"coverArt":{"sources":[
        {"height":300,"url":"https://i.scdn.co/image/ab67616d00001e02efe09b635b15763133198101","width":300},
        {"height":64,"url":"https://i.scdn.co/image/ab67616d00004851efe09b635b15763133198101","width":64},
        {"height":640,"url":"https://i.scdn.co/image/ab67616d0000b273efe09b635b15763133198101","width":640}]},
        "date":{"isoString":"2026-10-16T00:00:00Z","precision":"DAY"},"name":"The Girl Of Your Dreams",
        "uri":"spotify:album:03HD0ohJOvejRoesXWKZFl"},"artists":{"items":[{"profile":{"name":"Ashe"},
        "uri":"spotify:artist:6P5NO5hzJbuOqSdyPB7SJM"}]},"contentRating":{"label":"NONE"},"discNumber":1,
        "trackDuration":{"totalMilliseconds":199460},"mediaType":"AUDIO","name":"Stop The Wedding!",
        "playability":{"playable":true,"reason":"PLAYABLE"},"playcount":"241361404","trackNumber":1,
        "uri":"spotify:track:7MOO89Qv9AV7VZkt5mcqA8"}
    """.trimIndent()

    // fetchPlaylistMetadata 响应里的 playlistV2：images.items[].sources 才是歌单封面
    private val playlistMetadata = """
        {"__typename":"Playlist","content":{"__typename":"PlaylistContents","items":[],"pagingInfo":
        {"limit":50,"offset":0},"totalCount":50},"description":"Your daily update of the most played
        tracks right now - Global.","followers":16963952,"format":"chart","images":{"items":[{"sources":
        [{"height":null,"url":"https://charts-images.scdn.co/assets/locale_en/regional/daily/region_global_default.jpg",
        "width":null}]}]},"name":"Top 50 - Global","ownerV2":{"data":{"__typename":"User","name":"Spotify",
        "uri":"spotify:user:spotify","username":"spotify"}},"revisionId":"test","uri":
        "spotify:playlist:37i9dQZEVXbMDoHDwVN2tF"}
    """.trimIndent()

    @Test
    fun `playlist contents track uses its own album cover`() {
        val song = provider.songFromWebPlayerContentsTrack(json.parseToJsonElement(contentsTrack).jsonObject)
        assertNotNull(song)
        assertEquals("7MOO89Qv9AV7VZkt5mcqA8", song!!.id)
        assertEquals("Stop The Wedding!", song.name)
        assertEquals("Ashe", song.artist)
        assertEquals("The Girl Of Your Dreams", song.album)
        assertEquals("03HD0ohJOvejRoesXWKZFl", song.albumId)
        assertEquals(199, song.duration)
        // 目录不带试听地址，留给嵌入页补；这里必须留空而不是拿歌单封面顶替
        assertEquals("", song.url)
        assertEquals("https://i.scdn.co/image/ab67616d0000b273efe09b635b15763133198101", song.cover)
        assertEquals("spotify", song.source)
        assertEquals("true", song.extra["preview_only"])
        assertEquals("web_player", song.extra["metadata_source"])
        assertEquals("1", song.extra["track_number"])
    }

    @Test
    fun `playlist contents track without a name or uri is dropped`() {
        val noName = """{"uri":"spotify:track:7MOO89Qv9AV7VZkt5mcqA8","name":"  "}"""
        assertNull(provider.songFromWebPlayerContentsTrack(json.parseToJsonElement(noName).jsonObject))
        val noUri = """{"name":"Stop The Wedding!"}"""
        assertNull(provider.songFromWebPlayerContentsTrack(json.parseToJsonElement(noUri).jsonObject))
    }

    @Test
    fun `playlist metadata reads name cover owner and description`() {
        val playlist = provider.playlistFromWebPlayerMetadata(
            "37i9dQZEVXbMDoHDwVN2tF",
            json.parseToJsonElement(playlistMetadata).jsonObject,
        )
        assertNotNull(playlist)
        assertEquals("37i9dQZEVXbMDoHDwVN2tF", playlist!!.id)
        assertEquals("Top 50 - Global", playlist.name)
        assertEquals("Spotify", playlist.creator)
        assertEquals(
            "https://charts-images.scdn.co/assets/locale_en/regional/daily/region_global_default.jpg",
            playlist.cover,
        )
        assertTrue(playlist.description.startsWith("Your daily update"))
        assertEquals("spotify", playlist.source)
    }

    @Test
    fun `playlist metadata without a name is rejected`() {
        val blank = """{"uri":"spotify:playlist:37i9dQZEVXbMDoHDwVN2tF","name":""}"""
        assertNull(
            provider.playlistFromWebPlayerMetadata(
                "37i9dQZEVXbMDoHDwVN2tF",
                json.parseToJsonElement(blank).jsonObject,
            ),
        )
    }

    @Test
    fun `persisted query payload carries the playlist hash`() {
        val payload = provider.persistedQueryPayload(
            "fetchPlaylistContents",
            "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
            json.parseToJsonElement(
                """{"uri":"spotify:playlist:37i9dQZEVXbMDoHDwVN2tF","offset":0,"limit":50}""",
            ).jsonObject,
        )
        val root = json.parseToJsonElement(payload).jsonObject
        assertEquals("fetchPlaylistContents", root["operationName"]?.jsonPrimitive?.content)
        val variables = root["variables"]?.jsonObject
        assertEquals("spotify:playlist:37i9dQZEVXbMDoHDwVN2tF", variables?.get("uri")?.jsonPrimitive?.content)
        assertEquals(0, variables?.get("offset")?.jsonPrimitive?.content?.toIntOrNull())
        assertEquals(50, variables?.get("limit")?.jsonPrimitive?.content?.toIntOrNull())
        val persisted = root["extensions"]?.jsonObject?.get("persistedQuery")?.jsonObject
        assertEquals(1, persisted?.get("version")?.jsonPrimitive?.content?.toIntOrNull())
        assertEquals(
            "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
            persisted?.get("sha256Hash")?.jsonPrimitive?.content,
        )
    }

    // ---- LRCLIB：歌词正文在 JSON 里，不能把整个响应当歌词 ----

    @Test
    fun `lrclib get response is decoded to synced lyrics`() {
        val body = """
            {"id":37639522,"name":"Stop The Wedding!","trackName":"Stop The Wedding!","artistName":"Ashe",
            "albumName":"Ashe","duration":199.0,"instrumental":false,"hasWordSync":false,
            "plainLyrics":"plain text here","syncedLyrics":"[00:01.00] Stop The Wedding!"}
        """.trimIndent()
        assertEquals("[00:01.00] Stop The Wedding!", provider.lrclibLyricsText(body))
    }

    @Test
    fun `lrclib get response falls back to plain lyrics`() {
        val body = """{"id":1,"syncedLyrics":"","plainLyrics":"only plain text"}"""
        assertEquals("only plain text", provider.lrclibLyricsText(body))
    }

    @Test
    fun `lrclib get response never returns the raw json`() {
        val body = """{"id":1,"syncedLyrics":"","plainLyrics":""}"""
        val text = provider.lrclibLyricsText(body)
        assertTrue("歌词不该是整条 JSON：$text", text.isBlank())
    }

    @Test
    fun `lrclib garbage body degrades to empty`() {
        assertEquals("", provider.lrclibLyricsText("<html>502 Bad Gateway</html>"))
        assertEquals("", provider.lrclibLyricsText(""))
    }
}

/**
 * [NativeHttp] 的 `throwOnError` 开关。
 *
 * Spotify 全靠它才能看到真实的 401/429/400——默认抛异常时状态码根本传不出来，
 * 401 换 token、429 退避、官方接口挂了降级到公开嵌入这几条路全是死的。
 */
class NativeHttpThrowOnErrorTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `non 2xx throws by default`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(HttpURLConnection.HTTP_BAD_REQUEST).body("bad request").build())
        val error = runCatching {
            NativeHttp.getResponse(server.url("/playlists/1/items").toString())
        }.exceptionOrNull()
        assertTrue("默认行为应该抛 IOException，实际是 $error", error is IOException)
        assertTrue(error?.message?.contains("400") == true)
    }

    @Test
    fun `non 2xx is returned when throwOnError is false`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(HttpURLConnection.HTTP_BAD_REQUEST)
                .body("""{"error":{"message":"Invalid market code"}}""")
                .build(),
        )
        val response = NativeHttp.getResponse(
            server.url("/playlists/1/items").toString(),
            throwOnError = false,
        )
        assertEquals(400, response.code)
        assertTrue(response.body.contains("Invalid market code"))
    }

    @Test
    fun `401 and 429 are also returned when throwOnError is false`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("expired").build())
        assertEquals(401, NativeHttp.getResponse(server.url("/me").toString(), throwOnError = false).code)
        server.enqueue(MockResponse.Builder().code(429).body("slow down").build())
        val throttled = NativeHttp.getResponse(server.url("/me").toString(), throwOnError = false)
        assertEquals(429, throttled.code)
        assertEquals("slow down", throttled.body)
    }

    @Test
    fun `successful responses are unaffected`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
        val response = NativeHttp.getResponse(server.url("/ok").toString(), throwOnError = false)
        assertEquals(200, response.code)
        assertEquals("""{"ok":true}""", response.body)
    }
}
