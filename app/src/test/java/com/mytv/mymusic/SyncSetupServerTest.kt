package com.mytv.mymusic

import com.mytv.mymusic.data.SyncAccountEntity
import com.mytv.mymusic.data.sync.InitialMergePreview
import com.mytv.mymusic.data.sync.SetupAddressCandidate
import com.mytv.mymusic.data.sync.SyncSetupPhase
import com.mytv.mymusic.data.sync.SyncSetupServer
import com.mytv.mymusic.data.sync.SyncSetupSession
import com.mytv.mymusic.data.sync.SyncSetupSnapshot
import com.mytv.mymusic.data.sync.SyncSetupSubmission
import com.mytv.mymusic.data.sync.SyncSetupSubmitResult
import com.mytv.mymusic.data.sync.SyncSetupTheme
import com.mytv.mymusic.data.sync.SyncStatus
import com.mytv.mymusic.data.sync.SyncUiState
import com.mytv.mymusic.data.sync.selectSyncSetupAddress
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SyncSetupServerTest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val servers = mutableListOf<SyncSetupServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        client.connectionPool.evictAll()
    }

    @Test
    fun servesOnlySessionAssetsAndCurrentTvTheme() {
        val snapshot = AtomicReference(
            SyncSetupSnapshot(
                provider = "gitee",
                theme = SyncSetupTheme(primary = "#123456"),
                sync = SyncUiState(
                    account = SyncAccountEntity(provider = "gitee", accountLabel = "private-account"),
                    status = SyncStatus.Idle,
                ),
            ),
        )
        val base = start(snapshot)

        client.newCall(Request.Builder().url(base).get().build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("no-store", response.header("Cache-Control"))
            assertTrue(response.header("Content-Security-Policy").orEmpty().contains("default-src 'self'"))
            val body = response.body.string()
            assertTrue(body.contains("setup.css"))
            assertTrue(body.contains("theme.css"))
            assertFalse(body.contains("private-account"))
        }
        get(base, "theme.css").use { response ->
            assertEquals(200, response.code)
            assertTrue(response.body.string().contains("--primary: #123456;"))
        }
        get(base, "api/session").use { response ->
            val body = response.body.string()
            assertEquals("gitee", kotlinx.serialization.json.Json.parseToJsonElement(body)
                .jsonObject["provider"]?.jsonPrimitive?.content)
            assertFalse(body.contains("private-account"))
        }
        client.newCall(Request.Builder().url(base.resolve("/s/not-this-session/")!!).get().build())
            .execute().use { response -> assertEquals(404, response.code) }
        get(base, "../SyncModels.kt").use { response -> assertEquals(404, response.code) }
    }

    @Test
    fun acceptsJsonAndTracksConnectionWithoutEchoingSecrets() {
        val snapshot = AtomicReference(SyncSetupSnapshot())
        val submissions = CopyOnWriteArrayList<SyncSetupSubmission>()
        val base = start(snapshot, submissions::add)
        val token = "secret-sync-token"

        post(base, """{"provider":"gist","token":"$token","remote":" https://gist.github.com/user/abc "}""")
            .use { response ->
                assertEquals(202, response.code)
                assertFalse(response.body.string().contains(token))
            }
        assertEquals(1, submissions.size)
        assertEquals(token, submissions.single().token)
        assertEquals("https://gist.github.com/user/abc", submissions.single().remote)
        get(base, "api/status").use { response ->
            assertEquals("working", response.state())
        }

        snapshot.set(SyncSetupSnapshot(sync = SyncUiState(status = SyncStatus.Syncing, phase = "正在验证令牌")))
        get(base, "api/status").use { response ->
            assertEquals("正在验证令牌", kotlinx.serialization.json.Json.parseToJsonElement(response.body.string())
                .jsonObject["detail"]?.jsonPrimitive?.content)
        }
        snapshot.set(
            SyncSetupSnapshot(
                sync = SyncUiState(
                    account = SyncAccountEntity(
                        provider = "gist",
                        remoteId = "abc",
                        accountLabel = "private-account",
                        updatedAt = 20,
                    ),
                    providerLabel = "GitHub Gist",
                    status = SyncStatus.Idle,
                ),
            ),
        )
        get(base, "api/status").use { response ->
            val body = response.body.string()
            assertTrue(body.contains("success"))
            assertFalse(body.contains(token))
            assertFalse(body.contains("private-account"))
        }
        post(base, """{"provider":"gist","token":"another-token","remote":""}""").use { response ->
            assertEquals(409, response.code)
        }
        assertEquals(1, submissions.size)
    }

    @Test
    fun rejectsInvalidCrossSiteAndOversizedRequests() {
        val submissions = CopyOnWriteArrayList<SyncSetupSubmission>()
        val base = start(AtomicReference(SyncSetupSnapshot()), submissions::add)

        post(base, """{"provider":"gist","token":"","remote":""}""").use { assertEquals(400, it.code) }
        post(base, """{"provider":"dropbox","token":"abc","remote":""}""").use { assertEquals(400, it.code) }
        post(base, "not-json").use { assertEquals(400, it.code) }
        post(base, """{"provider":"gist","token":"abc"}""", withSetupHeader = false)
            .use { assertEquals(400, it.code) }
        post(
            base,
            """{"provider":"gist","token":"abc"}""",
            origin = "https://evil.example",
            fetchSite = "cross-site",
        ).use { response ->
            assertEquals(response.body.string(), 403, response.code)
        }
        post(base, """{"provider":"gist","token":"${"x".repeat(17 * 1024)}"}""")
            .use { assertEquals(400, it.code) }

        assertTrue(submissions.isEmpty())
    }

    @Test
    fun retryDoesNotReusePreviousErrorOrSkipPolling() {
        var now = 1_000L
        val message = "令牌无效或已过期，请重新生成。"
        val snapshot = AtomicReference(
            SyncSetupSnapshot(sync = SyncUiState(status = SyncStatus.Error, error = message)),
        )
        val base = start(snapshot, clock = { now })
        val body = """{"provider":"gist","token":"test-token","remote":""}"""

        post(base, body).use { response ->
            assertEquals(202, response.code)
            assertEquals("working", response.state())
        }
        get(base, "api/status").use { response -> assertEquals("working", response.state()) }

        // 即使轮询错过了 Syncing，同一种错误再次发生时也不会永久卡在等待中。
        now += 3000
        get(base, "api/status").use { response -> assertEquals("error", response.state()) }

        post(base, body).use { response ->
            assertEquals(202, response.code)
            assertEquals("working", response.state())
        }
        get(base, "api/status").use { response -> assertEquals("working", response.state()) }
        snapshot.set(SyncSetupSnapshot(sync = SyncUiState(status = SyncStatus.Syncing, phase = "正在验证令牌")))
        get(base, "api/status").use { response -> assertEquals("working", response.state()) }
        snapshot.set(SyncSetupSnapshot(sync = SyncUiState(status = SyncStatus.Error, error = message)))
        get(base, "api/status").use { response -> assertEquals("error", response.state()) }
    }

    @Test
    fun oldAccountIsNotSuccessUntilThisConnectionTransitions() {
        val oldAccount = SyncAccountEntity(provider = "gist", remoteId = "old", updatedAt = 7)
        val snapshot = AtomicReference(
            SyncSetupSnapshot(sync = SyncUiState(account = oldAccount, status = SyncStatus.Idle)),
        )
        val session = SyncSetupSession(snapshot = snapshot::get, onSubmit = {})

        assertEquals(
            SyncSetupSubmitResult.Accepted,
            session.submit(SyncSetupSubmission("gist", "new-token", "new-remote")),
        )
        assertEquals(SyncSetupPhase.Working, session.status().phase)

        snapshot.set(
            SyncSetupSnapshot(
                sync = SyncUiState(account = oldAccount.copy(remoteId = "new", updatedAt = 8), status = SyncStatus.Idle),
            ),
        )
        assertEquals(SyncSetupPhase.Success, session.status().phase)
    }

    @Test
    fun initialMergeRequiresTvAndErrorsCannotLeakRequestDetails() {
        val snapshot = AtomicReference(SyncSetupSnapshot())
        val session = SyncSetupSession(snapshot = snapshot::get, onSubmit = {})
        session.submit(SyncSetupSubmission("gitee", "private-token", ""))
        snapshot.set(
            SyncSetupSnapshot(
                sync = SyncUiState(
                    status = SyncStatus.Idle,
                    initialMerge = InitialMergePreview(
                        provider = "gitee",
                        providerLabel = "Gitee 代码片段",
                        localPlaylists = 1,
                        localTracks = 2,
                        remotePlaylists = 1,
                        remoteTracks = 3,
                    ),
                ),
            ),
        )
        assertEquals(SyncSetupPhase.NeedsTv, session.status().phase)
        assertEquals(
            SyncSetupSubmitResult.Busy,
            session.submit(SyncSetupSubmission("gitee", "another-token", "")),
        )

        snapshot.set(
            SyncSetupSnapshot(
                sync = SyncUiState(status = SyncStatus.Error, error = "https://gitee.test/?access_token=private-token"),
            ),
        )
        val error = session.status()
        assertEquals(SyncSetupPhase.Error, error.phase)
        assertFalse(error.detail.contains("private-token"))
        assertTrue(error.detail.contains("电视上的错误详情"))
    }

    @Test
    fun expiredSessionCannotBeReadOrSubmitted() {
        var now = 10_000L
        val submissions = CopyOnWriteArrayList<SyncSetupSubmission>()
        val base = start(
            AtomicReference(SyncSetupSnapshot()),
            submissions::add,
            clock = { now },
            sessionMillis = 250,
        )
        now += 250

        get(base, "api/status").use { assertEquals(410, it.code) }
        post(base, """{"provider":"gist","token":"abc","remote":""}""").use { assertEquals(410, it.code) }
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun choosesPrivateWifiAddressForQrCode() {
        val selected = selectSyncSetupAddress(
            listOf(
                SetupAddressCandidate("lo", InetAddress.getByName("127.0.0.1")),
                SetupAddressCandidate("eth0", InetAddress.getByName("192.168.1.20")),
                SetupAddressCandidate("wlan0", InetAddress.getByName("192.168.1.10")),
                SetupAddressCandidate("wan0", InetAddress.getByName("8.8.8.8")),
                SetupAddressCandidate("wlan1", InetAddress.getByName("169.254.1.2")),
            ),
        )
        assertNotNull(selected)
        assertEquals("192.168.1.10", selected?.hostAddress)
        assertNull(selectSyncSetupAddress(listOf(SetupAddressCandidate("wan0", InetAddress.getByName("8.8.8.8")))))
    }

    @Test
    fun prefersWifiOverWifiDirectGroupAddress() {
        // Wi-Fi Direct 的 p2p0 常在枚举里排在 wlan0 前面，不能因为它先出现就写进二维码
        val selected = selectSyncSetupAddress(
            listOf(
                SetupAddressCandidate("p2p0", InetAddress.getByName("192.168.49.1")),
                SetupAddressCandidate("wlan0", InetAddress.getByName("192.168.50.64")),
            ),
        )
        assertEquals("192.168.50.64", selected?.hostAddress)
    }

    @Test
    fun keepsFirstAddressReportedForTheWifiInterface() {
        // 同一块网卡报了两个地址时不按字符串比大小：否则 192.168.49.1 会赢过 192.168.50.64
        val selected = selectSyncSetupAddress(
            listOf(
                SetupAddressCandidate("wlan0", InetAddress.getByName("192.168.50.64")),
                SetupAddressCandidate("wlan0", InetAddress.getByName("192.168.49.1")),
            ),
        )
        assertEquals("192.168.50.64", selected?.hostAddress)
    }

    @Test
    fun fallsBackToTetheringWhenThereIsNoWifiOrEthernet() {
        // 只靠 USB 共享手机网络上网时 usb0 / rndis0 是唯一入口，降级使用而不是放弃
        val selected = selectSyncSetupAddress(
            listOf(
                SetupAddressCandidate("rmnet0", InetAddress.getByName("10.116.4.9")),
                SetupAddressCandidate("p2p0", InetAddress.getByName("192.168.49.1")),
                SetupAddressCandidate("tun0", InetAddress.getByName("10.8.0.2")),
                SetupAddressCandidate("rndis0", InetAddress.getByName("192.168.42.129")),
            ),
        )
        assertEquals("192.168.42.129", selected?.hostAddress)
    }

    private fun start(
        snapshot: AtomicReference<SyncSetupSnapshot>,
        onSubmit: (SyncSetupSubmission) -> Unit = {},
        clock: () -> Long = System::currentTimeMillis,
        sessionMillis: Long = 60_000,
    ): HttpUrl {
        val assets = mapOf(
            "index.html" to "<html><link href=\"setup.css\"><link href=\"theme.css\"><script src=\"setup.js\"></script></html>",
            "setup.css" to "body { color: white; }",
            "setup.js" to "console.log('setup');",
        )
        val server = SyncSetupServer(
            assets = { name -> assets[name]?.toByteArray() },
            snapshot = snapshot::get,
            onSubmit = onSubmit,
            bindHost = "127.0.0.1",
            clock = clock,
            sessionMillis = sessionMillis,
        )
        servers += server
        return server.startPanel().url.toHttpUrl()
    }

    private fun get(base: HttpUrl, path: String): Response = client.newCall(
        Request.Builder().url(base.resolve(path)!!).get().build(),
    ).execute()

    private fun post(
        base: HttpUrl,
        body: String,
        withSetupHeader: Boolean = true,
        origin: String? = null,
        fetchSite: String = "same-origin",
    ): Response {
        val sameOrigin = "http://${base.host}:${base.port}"
        return client.newCall(
            Request.Builder()
                .url(base.resolve("api/connect")!!)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { if (withSetupHeader) header("X-MyMusic-Setup", "1") }
                .header("Origin", origin ?: sameOrigin)
                .header("Sec-Fetch-Site", fetchSite)
                .build(),
        ).execute()
    }

    private fun Response.state(): String = use { response ->
        kotlinx.serialization.json.Json.parseToJsonElement(response.body.string())
            .jsonObject["state"]?.jsonPrimitive?.content.orEmpty()
    }
}
