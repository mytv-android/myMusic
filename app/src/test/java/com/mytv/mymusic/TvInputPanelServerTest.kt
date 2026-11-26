package com.mytv.mymusic

import com.mytv.mymusic.data.SyncAccountEntity
import com.mytv.mymusic.data.lx.LxCapability
import com.mytv.mymusic.data.lx.LxSourceRecord
import com.mytv.mymusic.data.lx.MAX_SCRIPT_BYTES
import com.mytv.mymusic.data.sync.SyncSetupPhase
import com.mytv.mymusic.data.sync.SyncSetupSubmission
import com.mytv.mymusic.data.sync.SyncStatus
import com.mytv.mymusic.data.sync.SyncUiState
import com.mytv.mymusic.data.sync.TvInputPanelHandlers
import com.mytv.mymusic.data.sync.TvInputPanelServer
import com.mytv.mymusic.data.sync.TvInputPanelSession
import com.mytv.mymusic.data.sync.TvInputPanelSnapshot
import com.mytv.mymusic.data.sync.TvInputSubmitResult
import com.mytv.mymusic.data.sync.TvInputTarget
import com.mytv.mymusic.data.sync.TvInputWrite
import com.mytv.mymusic.data.sync.TvInputWriteMode
import com.mytv.mymusic.data.sync.TvSignerSubmission
import com.mytv.mymusic.data.sync.TvTextRequest
import com.mytv.mymusic.data.sync.WebDavCheckUi
import com.mytv.mymusic.data.webdav.WebDavAccount
import com.mytv.mymusic.data.webdav.WebDavAccountInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TvInputPanelServerTest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val servers = mutableListOf<TvInputPanelServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        client.connectionPool.evictAll()
    }

    @Test
    fun servesThemeAndCapabilitiesWithoutSecrets() {
        val snapshot = TvInputPanelSnapshot(
            target = TvInputTarget("lx-url", "自定义音源 · 脚本地址", hasValue = true),
            allowSearch = false,
            allowSettings = false,
            sync = SyncUiState(
                webDav = WebDavAccountInfo(
                    "https://nas.local/", "admin", "myMusic/", false, hasPassword = true,
                    usedForStorage = true,
                ),
            ),
        )
        val (base, _) = start(snapshot)

        get(base, "").use { response ->
            assertEquals(200, response.code)
            assertTrue(response.header("Content-Security-Policy").orEmpty().contains("default-src 'self'"))
            assertTrue(response.body.string().contains("input.js"))
        }
        get(base, "api/session").use { response ->
            val body = response.body.string()
            assertTrue(body.contains("lx-url"))
            assertTrue(body.contains("hasPassword\":true"))
            assertFalse(body.contains("allowSearch\":true"))
            assertFalse(body.contains("password\""))
        }
        client.newCall(Request.Builder().url(base.resolve("/s/not-this-session/")!!).get().build())
            .execute().use { assertEquals(404, it.code) }
    }

    @Test
    fun writesOnlyTheBoundFieldAndIgnoresLateCompletion() {
        val writes = CopyOnWriteArrayList<TvInputWrite>()
        var operation = 0L
        val session = TvInputPanelSession(
            snapshot = { TvInputPanelSnapshot(target = TvInputTarget("lx-url", "脚本地址")) },
            handlers = TvInputPanelHandlers(write = { write, id -> writes += write; operation = id }),
        )

        assertEquals(
            TvInputSubmitResult.Unavailable,
            session.submitText(TvTextRequest(target = "field", targetId = "other", text = "bad")),
        )
        assertEquals(
            TvInputSubmitResult.Accepted,
            session.submitText(TvTextRequest(target = "field", targetId = "lx-url", text = " 新地址 ", mode = "append")),
        )
        assertEquals(TvInputWrite("lx-url", " 新地址 ", TvInputWriteMode.Append, false), writes.single())
        session.finish(operation)
        assertEquals(SyncSetupPhase.Success, session.status().phase)
        assertEquals("电视已收到", session.status().title)

        val previousOperation = operation
        session.submitText(TvTextRequest(target = "field", targetId = "lx-url", text = "next"))
        val next = session.status()
        assertEquals(SyncSetupPhase.Working, next.phase)
        session.finish(previousOperation, "late error")
        assertEquals(next, session.status())
    }

    @Test
    fun globalSearchAndFieldCapabilitiesAreSeparated() {
        val searches = CopyOnWriteArrayList<String>()
        val fieldOnly = TvInputPanelSession(
            snapshot = {
                TvInputPanelSnapshot(
                    target = TvInputTarget("url", "地址"), allowSearch = false, allowSettings = false,
                )
            },
            handlers = TvInputPanelHandlers(search = { text, _ -> searches += text }),
        )
        assertEquals(TvInputSubmitResult.Forbidden, fieldOnly.submitSource("https://example.com/a.js"))
        assertEquals(
            TvInputSubmitResult.Forbidden,
            fieldOnly.submitText(TvTextRequest(target = "search", text = "方大同")),
        )
        assertTrue(searches.isEmpty())

        val global = TvInputPanelSession(
            snapshot = { TvInputPanelSnapshot() },
            handlers = TvInputPanelHandlers(search = { text, _ -> searches += text }),
        )
        assertEquals(
            TvInputSubmitResult.Accepted,
            global.submitText(TvTextRequest(target = "search", text = " 方大同 ")),
        )
        assertEquals("方大同", searches.single())
    }

    @Test
    fun sourceImportReturnsMetadata() {
        var importedUrl = ""
        var operation = 0L
        val session = TvInputPanelSession(
            snapshot = { TvInputPanelSnapshot() },
            handlers = TvInputPanelHandlers(source = { url, id -> importedUrl = url; operation = id }),
        )
        assertEquals(TvInputSubmitResult.Invalid, session.submitSource("file:///tmp/a.js"))
        assertEquals(TvInputSubmitResult.Accepted, session.submitSource(" https://example.com/latest.js "))
        assertEquals("https://example.com/latest.js", importedUrl)
        session.finishSource(
            operation,
            LxSourceRecord(
                name = "示例音源", version = "1.4",
                capabilities = mapOf("tx" to LxCapability(actions = listOf("musicUrl"))),
            ),
        )
        val status = session.status()
        assertEquals(SyncSetupPhase.Success, status.phase)
        assertTrue(status.detail.contains("示例音源"))
        assertTrue(status.detail.contains("v1.4"))
        assertTrue(status.detail.contains("QQ"))
    }

    /**
     * 服务端按 Content-Type 里的 charset 解 body，缺 charset 会退成 US-ASCII 把中文变成替换字符；
     * 面板的 fetch 原样发送自己设的 Content-Type，所以必须显式带 charset=utf-8。
     */
    @Test
    fun uploadedChineseTextSurvivesTheRoundTrip() {
        val searches = CopyOnWriteArrayList<String>()
        val (base, _) = start(TvInputPanelSnapshot(), TvInputPanelHandlers(search = { text, _ -> searches += text }))
        post(base, "api/input", """{"target":"search","text":"方大同 · 月亮粑粑"}""").use { response ->
            assertEquals(202, response.code)
        }
        assertEquals("方大同 · 月亮粑粑", searches.single())
    }

    /**
     * 电视上没有文件管理器，本地文件改成手机在面板里挑好后整段上传；
     * 上传的正文和电视本地选文件走同一条导入链路。
     */
    @Test
    fun sourceFileUploadGoesThroughTheSameImportPath() {
        val scripts = CopyOnWriteArrayList<String>()
        var operation = 0L
        val session = TvInputPanelSession(
            snapshot = { TvInputPanelSnapshot() },
            handlers = TvInputPanelHandlers(sourceScript = { script, id -> scripts += script; operation = id }),
        )
        val script = "// @name 手机上传的音源\n// @version 2.0\nlx.PLATFORM = 'wy';\n"
        assertEquals(TvInputSubmitResult.Invalid, session.submitSourceScript("   \n  "))
        assertEquals(
            TvInputSubmitResult.Invalid,
            session.submitSourceScript("x".repeat(MAX_SCRIPT_BYTES + 1)),
        )
        assertTrue(scripts.isEmpty())

        assertEquals(TvInputSubmitResult.Accepted, session.submitSourceScript(script))
        assertEquals(script, scripts.single())
        session.finishSource(
            operation,
            LxSourceRecord(
                name = "手机上传的音源", version = "2.0",
                capabilities = mapOf("wy" to LxCapability(actions = listOf("musicUrl"))),
            ),
        )
        val status = session.status()
        assertEquals(SyncSetupPhase.Success, status.phase)
        assertTrue(status.detail.contains("手机上传的音源"))
        assertTrue(status.detail.contains("网易云"))

        // 没开放设置的会话（字段级输入框）不允许导入音源
        val fieldOnly = TvInputPanelSession(
            snapshot = { TvInputPanelSnapshot(allowSettings = false) },
            handlers = TvInputPanelHandlers(sourceScript = { uploaded, _ -> scripts += uploaded }),
        )
        assertEquals(TvInputSubmitResult.Forbidden, fieldOnly.submitSourceScript(script))
        assertEquals(1, scripts.size)
    }

    @Test
    fun uploadedScriptBodyPassesTheRaisedBodyLimit() {
        val scripts = CopyOnWriteArrayList<String>()
        val (base, _) = start(
            TvInputPanelSnapshot(),
            TvInputPanelHandlers(sourceScript = { script, _ -> scripts += script }),
        )
        // 文字输入只有 24 KB，脚本要放到 2 MB 才不误伤正常音源
        val big = "// @name 大脚本\n" + "a".repeat(200 * 1024)
        post(base, "api/source/file", Json.encodeToString(ScriptPayload(big))).use { response ->
            assertEquals(202, response.code)
        }
        assertEquals(big, scripts.single())

        post(base, "api/source/file", Json.encodeToString(ScriptPayload("x".repeat(MAX_SCRIPT_BYTES + 1))))
            .use { response -> assertEquals(400, response.code) }
        assertEquals(1, scripts.size)
    }

    @Test
    fun savedWebDavPasswordCanBeReusedButNeverRead() {        val saved = WebDavAccountInfo(
            "https://nas.local/", "admin", "myMusic/", false, hasPassword = true,
            usedForStorage = true,
        )
        val accounts = CopyOnWriteArrayList<WebDavAccount>()
        val session = TvInputPanelSession(
            snapshot = { TvInputPanelSnapshot(sync = SyncUiState(webDav = saved)) },
            handlers = TvInputPanelHandlers(saveStorage = { account, _ -> accounts += account }),
        )
        val account = WebDavAccount("https://nas.local", "admin", "", "myMusic/")
        assertEquals(TvInputSubmitResult.Accepted, session.submitStorage(account))
        assertEquals("", accounts.single().password)
        assertEquals(
            TvInputSubmitResult.Invalid,
            session.submitStorage(account.copy(username = "other")),
        )
    }

    @Test
    fun webDavTestWaitsForFreshResult() {
        var now = 1000L
        val account = WebDavAccount("https://nas.local", "admin", "secret", "myMusic/")
        val target = account.normalized().locationKey()
        val baseline = WebDavCheckUi(ok = true, message = "旧结果", target = target)
        val snapshot = AtomicReference(TvInputPanelSnapshot(sync = SyncUiState(webDavCheck = baseline)))
        val session = TvInputPanelSession(
            snapshot = snapshot::get,
            handlers = TvInputPanelHandlers(testWebDav = { _, _ -> }),
            clock = { now },
        )
        assertEquals(TvInputSubmitResult.Accepted, session.submitTest("storage", account))
        assertEquals(SyncSetupPhase.Working, session.status().phase)
        snapshot.set(TvInputPanelSnapshot(sync = SyncUiState(webDavCheck = baseline.copy(running = true))))
        assertEquals(SyncSetupPhase.Working, session.status().phase)
        snapshot.set(TvInputPanelSnapshot(sync = SyncUiState(webDavCheck = baseline.copy(message = "新结果"))))
        assertEquals(SyncSetupPhase.Success, session.status().phase)
        assertEquals("新结果", session.status().detail)
    }

    @Test
    fun syncAndSignerReuseExistingStateAndSubmissionShapes() {
        val syncSnapshot = AtomicReference(TvInputPanelSnapshot())
        val submissions = CopyOnWriteArrayList<SyncSetupSubmission>()
        val syncSession = TvInputPanelSession(
            snapshot = syncSnapshot::get,
            handlers = TvInputPanelHandlers(sync = submissions::add),
        )
        syncSession.submitSync(SyncSetupSubmission("gist", "private-token", " remote "))
        syncSnapshot.set(
            TvInputPanelSnapshot(
                sync = SyncUiState(
                    account = SyncAccountEntity(provider = "gist", remoteId = "abc", updatedAt = 20),
                    status = SyncStatus.Idle,
                ),
            ),
        )
        assertEquals(SyncSetupPhase.Success, syncSession.status().phase)
        assertFalse(syncSession.status().detail.contains("private-token"))
        assertEquals("remote", submissions.single().remote)

        val signers = CopyOnWriteArrayList<TvSignerSubmission>()
        val signerSession = TvInputPanelSession(
            snapshot = { TvInputPanelSnapshot() },
            handlers = TvInputPanelHandlers(signer = { submission, _ -> signers += submission }),
        )
        signerSession.submitSigner(TvSignerSubmission("http://192.168.1.10:8899/sign", "token", true))
        assertEquals("token", signers.single().token)
        signerSession.finish(signerSession.status().operation)
        signerSession.submitSigner(TvSignerSubmission("https://old", "old-token", true))
        // 上一项已成功，允许新的保存；公共模式会清掉旧自定义地址和 Token。
        signerSession.finish(signerSession.status().operation)
        signerSession.submitSigner(TvSignerSubmission("", "", true))
        assertEquals(TvSignerSubmission("", "", true), signers.last())
    }

    /**
     * issue #33：手机扫码后网页打不开，只看到 {"error":"页面不存在"}。
     * 手机浏览器和扫码器常把结尾的 `/` 吃掉，而页面里的 CSS/JS/接口全是相对路径，
     * 所以少一个斜线也必须能打开面板，而不是 404。
     */
    @Test
    fun missingTrailingSlashStillOpensThePanel() {
        val (base, _) = start(TvInputPanelSnapshot())
        val withoutSlash = base.newBuilder().query(null).build().toString().trimEnd('/')
        client.newCall(Request.Builder().url(withoutSlash).get().build()).execute().use { response ->
            assertEquals(200, response.code)
            assertTrue(response.body.string().contains("input.js"))
        }
        // 相对路径的资源跟着重定向后也要拿得到
        get(base, "input.css").use { response ->
            assertEquals(200, response.code)
            assertEquals("body{color:white}", response.body.string())
        }
    }

    @Test
    fun bareHostAddressTellsTheUserToScanInsteadOfRawJson() {
        val (base, _) = start(TvInputPanelSnapshot())
        client.newCall(Request.Builder().url(base.newBuilder().encodedPath("/").build()).get().build())
            .execute().use { response ->
                assertEquals(404, response.code)
                assertTrue(response.body.string().contains("二维码"))
            }
    }

    @Test
    fun rejectsCrossSiteOversizedAndExpiredRequests() {        var now = 1000L
        val (base, _) = start(TvInputPanelSnapshot(), clock = { now }, sessionMillis = 250)
        val body = """{"target":"search","text":"方大同"}"""
        post(base, "api/input", body, withHeader = false).use { assertEquals(400, it.code) }
        post(base, "api/input", body, origin = "https://evil.example", fetchSite = "cross-site")
            .use { assertEquals(403, it.code) }
        post(base, "api/input", """{"target":"search","text":"${"x".repeat(25 * 1024)}"}""")
            .use { assertEquals(400, it.code) }
        now += 250
        get(base, "api/status").use { assertEquals(410, it.code) }
        post(base, "api/input", body).use { assertEquals(410, it.code) }
    }

    private fun start(
        snapshot: TvInputPanelSnapshot,
        handlers: TvInputPanelHandlers = TvInputPanelHandlers(),
        clock: () -> Long = System::currentTimeMillis,
        sessionMillis: Long = 60_000,
    ): Pair<HttpUrl, TvInputPanelServer> {
        val assets = mapOf(
            "index.html" to "<html><link href=\"input.css\"><script src=\"input.js\"></script></html>",
            "input.css" to "body{color:white}",
            "input.js" to "console.log('input')",
        )
        val server = TvInputPanelServer(
            assets = { name -> assets[name]?.toByteArray() },
            snapshot = { snapshot },
            handlers = handlers,
            bindHost = "127.0.0.1",
            clock = clock,
            sessionMillis = sessionMillis,
        )
        servers += server
        return server.startPanel().url.toHttpUrl() to server
    }

    private fun get(base: HttpUrl, path: String): Response = client.newCall(
        Request.Builder().url(base.resolve(path)!!).get().build(),
    ).execute()

    private fun post(
        base: HttpUrl,
        path: String,
        body: String,
        withHeader: Boolean = true,
        origin: String? = null,
        fetchSite: String = "same-origin",
        contentType: String = "application/json",
    ): Response = client.newCall(
        Request.Builder()
            .url(base.resolve(path)!!)
            .post(body.toRequestBody(contentType.toMediaType()))
            .apply { if (withHeader) header("X-MyMusic-Input", "1") }
            .header("Origin", origin ?: "http://${base.host}:${base.port}")
            .header("Sec-Fetch-Site", fetchSite)
            .build(),
    ).execute()
}

@Serializable
private data class ScriptPayload(val script: String = "")
