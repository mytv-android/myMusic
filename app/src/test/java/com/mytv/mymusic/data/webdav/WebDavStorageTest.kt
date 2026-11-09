package com.mytv.mymusic.data.webdav

import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudRef
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadProgress
import com.mytv.mymusic.data.cloud.CloudUploadSource
import com.mytv.mymusic.data.cloud.CloudUploadStage
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/** WebDAV 当云端存歌：路径布局、HEAD 跳过、临时名 + MOVE、If-None-Match / 412、列表、容量、播放。 */
class WebDavStorageTest {
    private val dav = FakeWebDavServer()
    private val server = MockWebServer()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private lateinit var storage: WebDavStorage

    private val hex = "9f3a" + "0".repeat(56) + "c41e"
    private val localId = "sha256:$hex"
    private val content = "fLaC-pretend-audio-bytes".toByteArray()
    private val finalPath = "/dav/myMusic/music/9f/$hex.flac"

    @Before
    fun setUp() {
        server.dispatcher = dav
        server.start()
        storage = storageFor("secret")
    }

    @After
    fun tearDown() {
        server.close()
        http.connectionPool.evictAll()
    }

    private fun storageFor(password: String) =
        WebDavStorage(WebDavClient(WebDavAccount(server.url("/dav").toString(), "alice", password, "myMusic"), http))

    private fun source(bytes: ByteArray = content) = CloudUploadSource.of("雨季备忘.flac", bytes)

    private fun meta() = CloudUploadMeta(localId = localId, ext = "flac")

    @Test
    fun pathLayoutBucketsByLocalId() {
        assertEquals("myMusic/music/", storage.musicDirectory)
        assertEquals("myMusic/music/9f/$hex.flac", storage.pathFor(localId, "FLAC"))
        // 大写的 hex 也落到同一个位置
        assertEquals("myMusic/music/9f/$hex.mp3", storage.pathFor("sha256:" + hex.uppercase(), "mp3"))
        assertEquals("myMusic/music/9f/$hex", storage.pathFor(localId, ""))
        assertEquals("myMusic/music/9f/", storage.bucketPath(localId))
        assertEquals("ab", WebDavStorage.bucketOf("ab12"))
        assertEquals("a0", WebDavStorage.bucketOf("a"))
        val error = try {
            storage.pathFor("sha256:", "flac")
            null
        } catch (e: CloudException) {
            e
        }
        assertEquals(CloudErrorKind.InvalidFile, error!!.kind)
    }

    @Test
    fun uploadPutsTempWithIfNoneMatchThenMovesIntoPlace() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<CloudUploadProgress>())
        val ref = storage.upload(source(), meta()) { events += it }

        assertArrayEquals(content, dav.bytes(finalPath))
        // 父目录逐级建好，没有留下临时文件
        assertTrue(dav.hasDir("/dav/myMusic/music/9f/"))
        assertEquals(setOf(finalPath), dav.fileNames())

        val put = dav.requests.single { it.method == "PUT" }
        assertEquals("*", put.headers["If-None-Match"])
        val tempName = put.url.encodedPath.substringAfterLast('/')
        assertTrue(tempName.startsWith(".$hex.flac.") && tempName.endsWith(".tmp"))
        val move = dav.requests.single { it.method == "MOVE" }
        assertEquals("F", move.headers["Overwrite"])
        assertTrue(move.headers["Destination"]!!.endsWith(finalPath))
        // 先 HEAD 看有没有
        assertTrue(dav.requests.indexOfFirst { it.method == "HEAD" } < dav.requests.indexOf(put))

        assertEquals("webdav", ref.provider)
        assertEquals("myMusic/music/9f/$hex.flac", ref.id)
        assertEquals("雨季备忘.flac", ref.fileName)
        assertEquals(content.size.toLong(), ref.fileSize)
        assertEquals(localId, ref.localId)
        assertEquals("flac", ref.ext)
        assertEquals("", ref.playableId)

        val stages = events.map { it.stage }.distinct()
        assertEquals(
            listOf(
                CloudUploadStage.QUEUED,
                CloudUploadStage.CHECKING,
                CloudUploadStage.UPLOADING,
                CloudUploadStage.SUBMITTING,
                CloudUploadStage.READY,
            ),
            stages,
        )
        assertEquals(content.size.toLong(), events.filter { it.stage == CloudUploadStage.UPLOADING }.maxOf { it.bytesDone })
        assertFalse(events.last().skipped)
    }

    @Test
    fun existingSameSizeFileIsSkippedByHead() = runBlocking {
        dav.mkdirs("/dav/myMusic/music/9f/")
        dav.putFromElsewhere(finalPath, String(content))
        val events = mutableListOf<CloudUploadProgress>()
        val ref = storage.upload(source(), meta()) { events += it }

        assertTrue(events.last().skipped)
        assertEquals(CloudUploadStage.READY, events.last().stage)
        assertEquals("myMusic/music/9f/$hex.flac", ref.id)
        assertTrue(dav.requests.none { it.method == "PUT" || it.method == "MOVE" })
    }

    @Test
    fun existingDifferentSizeIsConflictNotOverwrite() = runBlocking {
        dav.mkdirs("/dav/myMusic/music/9f/")
        dav.putFromElsewhere(finalPath, "short")
        val error = expectCloudError { storage.upload(source(), meta()) }
        assertEquals(CloudErrorKind.Conflict, error.kind)
        assertEquals("short", dav.file(finalPath))
        assertTrue(dav.requests.none { it.method == "PUT" })
    }

    @Test
    fun moveRacedBySameFileElsewhereCountsAsDoneAndCleansTemp() = runBlocking {
        // MOVE 之前另一台设备刚好传完同一首：Overwrite F → 412，比大小一致就算成功
        dav.beforeMove = { destination -> dav.putFromElsewhere(destination, String(content)) }
        val ref = storage.upload(source(), meta())

        assertEquals("myMusic/music/9f/$hex.flac", ref.id)
        assertEquals(setOf(finalPath), dav.fileNames())
        assertTrue(dav.requests.any { it.method == "DELETE" && it.url.encodedPath.endsWith(".tmp") })
    }

    @Test
    fun serverWithoutMoveFallsBackToDirectPutWithIfNoneMatch() = runBlocking {
        dav.moveSupported = false
        storage.upload(source(), meta())

        assertArrayEquals(content, dav.bytes(finalPath))
        assertEquals(setOf(finalPath), dav.fileNames())
        val puts = dav.requests.filter { it.method == "PUT" }
        assertEquals(2, puts.size)
        assertTrue(puts.all { it.headers["If-None-Match"] == "*" })
        assertEquals(finalPath, puts.last().url.encodedPath)

        // 记住不支持 MOVE：下一首直接 PUT 正式名
        val otherHex = "ab" + "1".repeat(62)
        dav.requests.clear()
        storage.upload(source(), CloudUploadMeta(localId = "sha256:$otherHex", ext = "flac"))
        assertTrue(dav.requests.none { it.method == "MOVE" })
        assertEquals(1, dav.requests.count { it.method == "PUT" })
    }

    @Test
    fun directPutHitting412ComparesSize() = runBlocking {
        dav.moveSupported = false
        // 先传一首别的，让存储记住这台服务器不支持 MOVE，之后直接 PUT 正式名
        storage.upload(source(), CloudUploadMeta(localId = "sha256:" + "9f" + "2".repeat(62), ext = "flac"))

        // HEAD 之后、PUT 之前，另一台设备写进了同一首：If-None-Match: * 回 412，比大小一致就算成功
        dav.beforePut = { path -> if (path == finalPath) dav.putFromElsewhere(finalPath, String(content)) }
        val ref = storage.upload(source(), meta())
        assertEquals("myMusic/music/9f/$hex.flac", ref.id)
        assertEquals("*", dav.requests.last { it.method == "PUT" }.headers["If-None-Match"])

        // 同样的竞争但大小不同：Conflict，不覆盖
        val otherHex = "9f" + "3".repeat(62)
        val otherPath = "/dav/myMusic/music/9f/$otherHex.flac"
        dav.beforePut = { path -> if (path == otherPath) dav.putFromElsewhere(otherPath, "different") }
        val error = expectCloudError { storage.upload(source(), CloudUploadMeta(localId = "sha256:$otherHex", ext = "flac")) }
        assertEquals(CloudErrorKind.Conflict, error.kind)
        assertEquals("different", dav.file(otherPath))
    }

    @Test
    fun wrongPasswordIsUnauthorizedAndPausesQueue() = runBlocking {
        val error = expectCloudError { storageFor("wrong").upload(source(), meta()) }
        assertEquals(CloudErrorKind.Unauthorized, error.kind)
        assertEquals(CloudMessages.WEBDAV_UNAUTHORIZED, error.message)
        assertTrue(error.pausesQueue)
        assertEquals(401, error.code)
    }

    @Test
    fun emptyFileIsRejected() = runBlocking {
        val error = expectCloudError { storage.upload(source(ByteArray(0)), meta()) }
        assertEquals(CloudErrorKind.InvalidFile, error.kind)
        assertTrue(dav.requests.isEmpty())
    }

    @Test
    fun listWalksBucketsSkipsTempFilesAndPages() = runBlocking {
        val second = "ab" + "1".repeat(62)
        dav.mkdirs("/dav/myMusic/music/9f/")
        dav.mkdirs("/dav/myMusic/music/ab/")
        dav.putFromElsewhere(finalPath, "x")
        dav.putFromElsewhere("/dav/myMusic/music/9f/.$hex.flac.0011.tmp", "partial")
        dav.putFromElsewhere("/dav/myMusic/music/ab/$second.mp3", "yy")

        val all = storage.list(null)
        assertNull(all.nextCursor)
        // 桶按名字排：9f 在 ab 前面
        assertEquals(listOf("myMusic/music/9f/$hex.flac", "myMusic/music/ab/$second.mp3"), all.entries.map { it.id })
        val entry = all.entries.first()
        assertEquals(localId, entry.localId)
        assertEquals("flac", entry.ext)
        assertEquals(1L, entry.fileSize)
        assertNotNull(entry.extra["etag"])
        assertEquals("webdav", entry.provider)

        val paged = WebDavStorage(storage.client, pageSize = 1)
        val first = paged.list(null)
        assertEquals("ab", first.nextCursor)
        assertEquals("myMusic/music/9f/$hex.flac", first.entries.single().id)
        val rest = paged.list(first.nextCursor)
        assertEquals("myMusic/music/ab/$second.mp3", rest.entries.single().id)
        assertEquals("sha256:$second", rest.entries.single().localId)
        assertNull(rest.nextCursor)
    }

    @Test
    fun listWithoutMusicDirectoryIsEmpty() = runBlocking {
        assertTrue(storage.list(null).entries.isEmpty())
    }

    @Test
    fun quotaReadsRfc4331Properties() = runBlocking {
        dav.mkdirs("/dav/myMusic/")
        assertNull(storage.quota())
        dav.quota = 1_000L to 250L
        val quota = storage.quota()!!
        assertEquals(250L, quota.used)
        assertEquals(1_250L, quota.total)
        assertEquals(1_000L, quota.free)
        val body = dav.requests.last { it.method == "PROPFIND" }.body!!.utf8()
        assertTrue(body.contains("quota-available-bytes"))
        assertEquals("0", dav.requests.last { it.method == "PROPFIND" }.headers["Depth"])
    }

    @Test
    fun quotaParsesNegativeAvailableAsUnknown() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href>
            <d:propstat><d:prop><d:quota-available-bytes>-3</d:quota-available-bytes><d:quota-used-bytes>42</d:quota-used-bytes></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val quota = WebDavXml.parseQuota(xml)!!
        assertEquals(-1L, quota.availableBytes)
        assertEquals(42L, quota.usedBytes)
    }

    @Test
    fun deleteIsIdempotent() = runBlocking {
        dav.mkdirs("/dav/myMusic/music/9f/")
        dav.putFromElsewhere(finalPath, "x")
        val ref = CloudRef("webdav", "myMusic/music/9f/$hex.flac")
        storage.delete(ref)
        assertNull(dav.file(finalPath))
        // 已经没了也算成功
        storage.delete(ref)
    }

    @Test
    fun resolvePlaybackReturnsAuthenticatedDataSourceOrNotFound() = runBlocking {
        dav.mkdirs("/dav/myMusic/music/9f/")
        dav.putFromElsewhere(finalPath, String(content))
        val stream = storage.resolvePlayback(CloudRef("webdav", "myMusic/music/9f/$hex.flac", ext = "flac"))
        assertEquals(server.url(finalPath).toString(), stream.url)
        assertNotNull(stream.dataSourceFactory)
        assertEquals(content.size.toLong(), stream.contentLength)
        assertEquals("webdav", stream.via)
        // 密码不放进地址或请求头表
        assertFalse(stream.url.contains("secret"))
        assertTrue(stream.headers.isEmpty())

        // ref.id 不对（另一台设备用了旧目录）时按 localId 推路径
        val byLocalId = storage.resolvePlayback(CloudRef("webdav", "old/place.flac", localId = localId, ext = "flac"))
        assertEquals(stream.url, byLocalId.url)

        val missing = expectCloudError { storage.resolvePlayback(CloudRef("webdav", "myMusic/music/00/nothing.flac")) }
        assertEquals(CloudErrorKind.NotFound, missing.kind)
        assertEquals(CloudMessages.CLOUD_DELETED, missing.message)
    }

    private suspend fun expectCloudError(block: suspend () -> Unit): CloudException {
        try {
            block()
        } catch (error: CloudException) {
            return error
        }
        fail("应该抛 CloudException")
        throw AssertionError()
    }
}
