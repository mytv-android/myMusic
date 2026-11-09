package com.mytv.mymusic.data.webdav

import com.mytv.mymusic.data.sync.SyncException
import com.mytv.mymusic.data.sync.SyncFailureKind
import com.mytv.mymusic.data.sync.WebDavSyncProvider
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** WebDAV 同步位置的建文件 / 条件更新 / 412 流程，对着内存里的假 WebDAV 服务器跑真实 HTTP。 */
class WebDavSyncProviderTest {
    private val dav = FakeWebDavServer()
    private val server = MockWebServer()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val provider = WebDavSyncProvider { WebDavClient(it, http) }
    private lateinit var account: WebDavAccount
    private lateinit var token: String

    @Before
    fun setUp() {
        server.dispatcher = dav
        server.start()
        account = WebDavAccount(server.url("/dav").toString(), "alice", "secret", "myMusic")
        token = account.encode()
    }

    @After
    fun tearDown() {
        server.close()
        http.connectionPool.evictAll()
    }

    @Test
    fun firstConnectCreatesDirectoryThenFileWithIfNoneMatch() = runBlocking {
        val remoteAccount = provider.validate(token)
        assertTrue(dav.hasDir("/dav/myMusic/"))
        assertEquals(account.hostLabel, remoteAccount.login)
        assertEquals("", provider.locate(token))

        val created = provider.create(token, """{"v":1}""")

        assertEquals("myMusic/mymusic-library.json", created.id)
        assertEquals(dav.etag("/dav/myMusic/mymusic-library.json"), created.revision)
        val put = dav.requests.last { it.method == "PUT" }
        assertEquals("*", put.headers["If-None-Match"])
        assertNull(put.headers["If-Match"])
        assertTrue(put.headers["Content-Type"].orEmpty().startsWith("application/json"))
        assertEquals("""{"v":1}""", dav.file("/dav/myMusic/mymusic-library.json"))

        assertEquals("myMusic/mymusic-library.json", provider.locate(token))
        val read = provider.read(token, created.id)
        assertEquals("""{"v":1}""", read.content)
        assertEquals(created.revision, read.revision)
        assertTrue(read.updatedAt > 0)
    }

    @Test
    fun createWhenAnotherDeviceAlreadyCreatedIsPrecondition() = runBlocking {
        provider.create(token, """{"device":"a"}""")
        val error = expectSync { provider.create(token, """{"device":"b"}""") }
        assertEquals(SyncFailureKind.Precondition, error.kind)
        // 先建好的那份没被覆盖
        assertEquals("""{"device":"a"}""", dav.file("/dav/myMusic/mymusic-library.json"))
    }

    @Test
    fun staleEtagIsPreconditionAndRereadThenUpdateSucceeds() = runBlocking {
        val created = provider.create(token, """{"v":1}""")
        dav.putFromElsewhere("/dav/myMusic/mymusic-library.json", """{"v":"other"}""")

        val error = expectSync { provider.update(token, created.id, """{"v":2}""", created.revision) }
        assertEquals(SyncFailureKind.Precondition, error.kind)
        assertEquals(created.revision, dav.requests.last { it.method == "PUT" }.headers["If-Match"])
        assertEquals("""{"v":"other"}""", dav.file("/dav/myMusic/mymusic-library.json"))

        // SyncCoordinator 的做法：重新读、合并、带新 ETag 再写
        val latest = provider.read(token, created.id)
        assertEquals("""{"v":"other"}""", latest.content)
        val updated = provider.update(token, created.id, """{"v":3}""", latest.revision)

        assertEquals(latest.revision, dav.requests.last { it.method == "PUT" }.headers["If-Match"])
        assertEquals("""{"v":3}""", dav.file("/dav/myMusic/mymusic-library.json"))
        assertEquals(dav.etag("/dav/myMusic/mymusic-library.json"), updated.revision)
        assertTrue(updated.revision != latest.revision)
    }

    @Test
    fun weakEtagIsNotSentAsIfMatch() = runBlocking {
        dav.weakEtags = true
        val created = provider.create(token, """{"v":1}""")
        assertTrue(created.revision.startsWith("W/"))

        provider.update(token, created.id, """{"v":2}""", created.revision)

        assertNull(dav.requests.last { it.method == "PUT" }.headers["If-Match"])
        assertEquals("""{"v":2}""", dav.file("/dav/myMusic/mymusic-library.json"))
    }

    @Test
    fun putWithoutEtagFallsBackToHead() = runBlocking {
        dav.putReturnsEtag = false
        val created = provider.create(token, """{"v":1}""")
        assertEquals(dav.etag("/dav/myMusic/mymusic-library.json"), created.revision)
        assertTrue(dav.requests.any { it.method == "HEAD" })
    }

    @Test
    fun deletedSyncFileIsNotFoundWithClearMessage() = runBlocking {
        provider.validate(token)
        val error = expectSync { provider.read(token, "myMusic/mymusic-library.json") }
        assertEquals(SyncFailureKind.NotFound, error.kind)
        assertTrue(error.message.contains("mymusic-library.json"))
    }

    @Test
    fun wrongPasswordIsAuthAndIsNotRetried() = runBlocking {
        val wrong = account.copy(password = "nope").encode()
        val error = expectSync { provider.validate(wrong) }
        assertEquals(SyncFailureKind.Auth, error.kind)
        assertEquals("用户名或密码不对。", error.message)
        // Basic 被拒后不重试，免得 Nextcloud 之类把账号锁掉
        assertEquals(1, dav.requests.size)
    }

    @Test
    fun jianguoyunUnauthorizedMentionsAppPassword() {
        val jianguoyun = WebDavAccount("https://dav.jianguoyun.com/dav/", "alice@example.com", "login-password")
        val message = WebDavMessages.describe(WebDavException(WebDavErrorKind.Unauthorized, "401", 401), jianguoyun)
        assertTrue(message.startsWith("用户名或密码不对"))
        assertTrue(message.contains("应用密码"))
        assertFalse(WebDavMessages.describe(WebDavException(WebDavErrorKind.Unauthorized, "401", 401), account).contains("应用密码"))
    }

    @Test
    fun digestChallengeIsAnsweredOnceThenSentUpFront() = runBlocking {
        dav.digestOnly = true
        val client = provider.client(account)

        assertTrue(client.ensureDirectory("myMusic/"))
        val afterFirst = dav.requests.size
        client.putBytes("myMusic/a.txt", "hi".toByteArray(), WebDavClient.TEXT)

        // 只有第一个请求先挨了一次 401；之后直接带 Digest
        assertEquals(1, dav.requests.count { it.headers["Authorization"].orEmpty().startsWith("Basic") })
        assertEquals(afterFirst + 1, dav.requests.size)
        assertEquals("hi", dav.file("/dav/myMusic/a.txt"))
        assertTrue(client.stream("myMusic/a.txt").needsClient)
    }

    @Test
    fun digestWithWrongPasswordStopsAfterOneAttempt() = runBlocking {
        dav.digestOnly = true
        val error = expectSync { provider.validate(account.copy(password = "nope").encode()) }
        assertEquals(SyncFailureKind.Auth, error.kind)
        // Basic 一次 + Digest 一次，同一个 nonce 再被拒就停
        assertEquals(2, dav.requests.size)
    }

    @Test
    fun ensureDirectoryCreatesNestedChineseLevelsTopDown() = runBlocking {
        val client = provider.client(account.copy(directory = "音乐/备份 1/"))

        assertTrue(client.ensureDirectory("音乐/备份 1/"))

        val mkcols = dav.requests.filter { it.method == "MKCOL" }.map { it.url.encodedPath }
        assertEquals(listOf("/dav/%E9%9F%B3%E4%B9%90/", "/dav/%E9%9F%B3%E4%B9%90/%E5%A4%87%E4%BB%BD%201/"), mkcols)
        assertFalse(client.ensureDirectory("音乐/备份 1/"))
    }

    @Test
    fun ensureDirectoryOnWrongServerPathIsNotFound() = runBlocking {
        val wrongBase = account.copy(serverUrl = server.url("/nothing-here/").toString())
        val error = expectSync { provider.validate(wrongBase.encode()) }
        assertEquals(SyncFailureKind.NotFound, error.kind)
        assertTrue(dav.requests.none { it.method == "MKCOL" })
    }

    @Test
    fun fileInPlaceOfDirectoryIsReported() = runBlocking {
        dav.putFromElsewhere("/dav/myMusic", "not a folder")
        val error = expectSync { provider.validate(token) }
        assertTrue(error.message, error.message.contains("不是目录"))
    }

    @Test
    fun checkCreatesDirectoryWritesReadsRangeAndCleansUp() = runBlocking {
        val client = provider.client(account)

        val fresh = client.check(lookFor = WebDavSyncProvider.FILE_NAME)

        assertTrue(fresh.ok)
        assertTrue(fresh.createdDirectory)
        assertTrue(fresh.supportsRange)
        assertFalse(fresh.foundFile)
        assertEquals(setOf("/dav/", "/dav/myMusic/"), dav.paths())
        val probe = dav.requests.first { it.method == "PUT" }
        assertEquals("*", probe.headers["If-None-Match"])
        assertEquals("bytes=0-0", dav.requests.first { it.headers["Range"] != null }.headers["Range"])
        assertTrue(WebDavMessages.checkSummary(fresh, account).contains("已新建目录 myMusic/"))

        provider.create(token, "{}")
        val existing = client.check(lookFor = WebDavSyncProvider.FILE_NAME)
        assertTrue(existing.ok)
        assertFalse(existing.createdDirectory)
        assertTrue(existing.foundFile)
    }

    @Test
    fun checkReportsFailedStep() = runBlocking {
        val result = provider.client(account.copy(password = "nope")).check()
        assertFalse(result.ok)
        assertEquals(WebDavCheckStep.ReadDirectory, result.failedStep)
        assertNotNull(result.error)
        assertEquals("读取目录失败：用户名或密码不对。", WebDavMessages.checkSummary(result, account))
    }

    @Test
    fun rangedReadReturnsPartialContent() = runBlocking {
        val client = provider.client(account)
        client.ensureDirectory("myMusic/")
        client.putBytes("myMusic/song.flac", "0123456789".toByteArray())

        val part = client.read("myMusic/song.flac", 2L..4L)
        assertTrue(part.partial)
        assertEquals("234", part.text())
        assertEquals("bytes 2-4/10", part.contentRange)
        assertEquals("bytes=5-", WebDavClient.rangeHeader(5L..Long.MAX_VALUE))
        val tooBig = runCatching { client.read("myMusic/song.flac", maxBytes = 4) }.exceptionOrNull()
        assertEquals(WebDavErrorKind.InvalidResponse, (tooBig as WebDavException).kind)
        assertTrue(client.delete("myMusic/song.flac"))
        assertFalse(client.delete("myMusic/song.flac"))
    }

    private suspend fun expectSync(block: suspend () -> Unit): SyncException {
        try {
            block()
        } catch (error: SyncException) {
            return error
        }
        fail("应抛出 SyncException")
        throw AssertionError()
    }
}
