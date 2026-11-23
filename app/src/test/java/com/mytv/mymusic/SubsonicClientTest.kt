package com.mytv.mymusic

import com.mytv.mymusic.data.subsonic.SubsonicAccount
import com.mytv.mymusic.data.subsonic.SubsonicAuthMode
import com.mytv.mymusic.data.subsonic.SubsonicClient
import com.mytv.mymusic.data.subsonic.SubsonicException
import com.mytv.mymusic.data.subsonic.md5
import com.mytv.mymusic.data.subsonic.normalizeServerUrl
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicClientTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun tokenAuthenticationUsesMd5PasswordPlusSalt() = runTest {
        server.enqueue(ok())
        val client = client()
        client.request("ping")
        val request = server.takeRequest()
        val url = request.url
        assertEquals("/music/rest/ping.view", url.encodedPath)
        assertEquals("demo", url.queryParameter("u"))
        assertEquals("1.16.1", url.queryParameter("v"))
        assertEquals("myMusic", url.queryParameter("c"))
        val salt = url.queryParameter("s")!!
        assertTrue(salt.length >= 6)
        assertEquals(md5("secret$salt"), url.queryParameter("t"))
        assertNull(url.queryParameter("p"))
    }

    @Test
    fun tokenFailureFallsBackToEncodedPasswordOnce() = runTest {
        server.enqueue(failed(41, "token not supported"))
        server.enqueue(ok())
        val client = client()
        client.request("ping")
        val first = server.takeRequest().url
        val second = server.takeRequest().url
        assertNotNull(first.queryParameter("t"))
        assertEquals("enc:" + "secret".toByteArray().joinToString("") { "%02x".format(it) }, second.queryParameter("p"))
        assertNull(second.queryParameter("t"))
        assertNull(second.queryParameter("s"))
        assertEquals(SubsonicAuthMode.Password, client.account.authMode)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun apiKeyDoesNotSendConflictingUsername() = runTest {
        server.enqueue(ok())
        val client = client(account().copy(username = "", password = "nds_test_key"))
        client.request("ping")
        val url = server.takeRequest().url
        assertEquals("nds_test_key", url.queryParameter("apiKey"))
        assertNull(url.queryParameter("u"))
        assertNull(url.queryParameter("p"))
        assertNull(url.queryParameter("t"))
    }

    @Test
    fun playbackUrlIsCleanButDownloadAndRoutingAuthenticate() = runTest {
        val client = client()
        val stream = client.streamUrl("song 1").toHttpUrl()
        assertEquals("/music/rest/stream.view", stream.encodedPath)
        assertNull(stream.queryParameter("t"))
        assertEquals("home", stream.queryParameter("mymusic_account"))
        val download = client.downloadUrl("song 1").toHttpUrl()
        assertEquals("demo", download.queryParameter("u"))
        assertNotNull(download.queryParameter("t"))
        val authorized = client.authorize(stream)
        assertNotNull(authorized.queryParameter("t"))
        assertNull(authorized.queryParameter("mymusic_account"))
        assertEquals("raw", authorized.queryParameter("format"))
        assertEquals("song 1", authorized.queryParameter("id"))
    }

    @Test
    fun multipleAccountsOnSameHostRouteByStableAccountId() {
        val home = client(account().copy(id = "home"))
        val work = client(account().copy(id = "work", username = "work"))
        val clients = listOf(home, work)
        assertSame(
            work,
            com.mytv.mymusic.data.subsonic.SubsonicRoutingDataSourceFactory.selectClient(
                clients,
                work.streamUrl("s1").toHttpUrl(),
            ),
        )
        val withoutMarker = work.streamUrl("s1").toHttpUrl().newBuilder()
            .removeAllQueryParameters("mymusic_account").build()
        assertNull(com.mytv.mymusic.data.subsonic.SubsonicRoutingDataSourceFactory.selectClient(clients, withoutMarker))
        assertSame(home, com.mytv.mymusic.data.subsonic.SubsonicRoutingDataSourceFactory.selectClient(listOf(home), withoutMarker))
    }

    @Test
    fun apiKeyAccountCanSwitchBackToPassword() = runTest {
        val api = account().copy(password = "nds_key", authMode = SubsonicAuthMode.ApiKey).normalized()
        val regular = api.copy(password = "secret").normalized()
        assertFalse(regular.usesApiKey)
        assertEquals(SubsonicAuthMode.Token, regular.authMode)
        server.enqueue(ok())
        client(regular).request("ping")
        val url = server.takeRequest().url
        assertEquals("demo", url.queryParameter("u"))
        assertNotNull(url.queryParameter("t"))
        assertNull(url.queryParameter("apiKey"))
    }

    @Test
    fun coverBytesFallBackToEncodedPassword() = runTest {
        server.enqueue(failed(41, "token disabled"))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body("image").build())
        val bytes = client().bytes("getCoverArt", listOf("id" to "cover"))
        assertEquals("image", bytes.toString(Charsets.UTF_8))
        assertNotNull(server.takeRequest().url.queryParameter("t"))
        assertEquals("enc:736563726574", server.takeRequest().url.queryParameter("p"))
    }

    @Test
    fun playbackHttpRequestFallsBackAndReturnsAudio() = runTest {
        server.enqueue(failed(40, "token disabled"))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/mpeg").body("audio").build())
        val client = client()
        val request = okhttp3.Request.Builder().url(client.authorize(client.streamUrl("s1").toHttpUrl())).build()
        val response = client.mediaHttpClient.newCall(request).execute()
        assertEquals("audio", response.use { it.body.string() })
        assertNotNull(server.takeRequest().url.queryParameter("t"))
        val retry = server.takeRequest().url
        assertEquals("enc:736563726574", retry.queryParameter("p"))
        assertNull(retry.queryParameter("mymusic_account"))
        assertEquals(SubsonicAuthMode.Password, client.account.authMode)
    }

    @Test
    fun downloadResponseUsesSameFallbackAndStreamsAudio() = runTest {
        server.enqueue(failed(41, "token disabled"))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/flac").body("FLAC").build())
        val client = client()
        val response = client.openMedia(client.downloadUrl("s1").toHttpUrl())
        assertEquals("FLAC", response.use { it.body.string() })
        server.takeRequest()
        assertEquals("enc:736563726574", server.takeRequest().url.queryParameter("p"))
        assertEquals(SubsonicAuthMode.Password, client.account.authMode)
    }

    @Test
    fun postUsesRepeatedFormParameters() = runTest {
        server.enqueue(ok())
        client().request(
            "updatePlaylist",
            listOf("playlistId" to "p1", "songIdToAdd" to "a", "songIdToAdd" to "b"),
            post = true,
        )
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/music/rest/updatePlaylist.view", request.url.encodedPath)
        val form = request.body!!.utf8()
        assertTrue(form.contains("playlistId=p1"))
        assertEquals(2, form.split('&').count { it == "songIdToAdd=a" || it == "songIdToAdd=b" })
    }

    @Test
    fun connectionProbeStoresCapabilitiesAndRoles() = runTest {
        server.enqueue(ok("""{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.64.2","openSubsonic":true}"""))
        server.enqueue(ok("""{"openSubsonicExtensions":[{"name":"songLyrics","versions":[1,2]},{"name":"formPost","versions":[1]}]}"""))
        server.enqueue(ok("""{"user":{"username":"demo","streamRole":true,"downloadRole":true,"playlistRole":false,"scrobblingEnabled":true}}"""))
        val result = client().testConnection()
        assertEquals("navidrome", result.account.serverType)
        assertEquals(listOf("songLyrics", "formPost"), result.account.extensions)
        assertEquals(false, result.account.playlistRole)
        assertEquals(true, result.account.downloadRole)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun errorEnvelopeIsHumanReadable() = runTest {
        server.enqueue(failed(40, "Wrong username or password"))
        val error = org.junit.Assert.assertThrows(SubsonicException::class.java) {
            kotlinx.coroutines.runBlocking { client(SubsonicAuthMode.Password).request("ping") }
        }
        assertEquals(40, error.code)
        assertTrue(error.message!!.contains("20 秒"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun accountCodecAndUrlNormalizationPreservePasswordSpaces() {
        val account = account().copy(url = "https://example.com", password = " two words ")
        val decoded = SubsonicAccount.decode(account.encode())!!
        assertEquals(" two words ", decoded.password)
        assertEquals("https://example.com/music/", normalizeServerUrl("example.com/music/rest/ping.view?u=demo").toString())
        assertEquals("https://example.com/", normalizeServerUrl("https://example.com/rest").toString())
        assertNull(normalizeServerUrl("file:///tmp/music"))
    }

    private fun client(mode: SubsonicAuthMode = SubsonicAuthMode.Token): SubsonicClient = client(account().copy(authMode = mode))

    private fun client(account: SubsonicAccount): SubsonicClient = SubsonicClient(
        account.copy(url = server.url("/music/").toString()),
        OkHttpClient(),
    )

    private fun account() = SubsonicAccount(id = "home", name = "Home", username = "demo", password = "secret")

    private fun ok(payload: String = ""): MockResponse {
        val fields = payload.trim().removePrefix("{").removeSuffix("}")
        val body = buildString {
            append("""{"subsonic-response":{"status":"ok","version":"1.16.1"""")
            if (fields.isNotBlank()) append(',').append(fields)
            append("}}")
        }
        return MockResponse.Builder().body(body).build()
    }

    private fun failed(code: Int, message: String) = MockResponse.Builder().body(
        """{"subsonic-response":{"status":"failed","error":{"code":$code,"message":"$message"}}}""",
    ).build()
}
