package com.mytv.mymusic.data.webdav

import mockwebserver3.MockWebServer
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 播放用的 Range 读取核心（不依赖 android.net.Uri）：认证由 WebDAV 客户端带上、Range 头、
 * 服务器不认 Range 时从头跳、416 在文件末尾当空段；外加路由工厂的地址判断。
 */
class WebDavDataSourceTest {
    private val dav = FakeWebDavServer()
    private val server = MockWebServer()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val bytes = "0123456789abcdef".toByteArray()
    private val path = "/dav/myMusic/music/9f/song.flac"
    private lateinit var storage: WebDavStorage

    @Before
    fun setUp() {
        server.dispatcher = dav
        server.start()
        dav.mkdirs("/dav/myMusic/music/9f/")
        dav.putFromElsewhere(path, String(bytes))
        storage = storageFor("secret")
    }

    @After
    fun tearDown() {
        server.close()
        http.connectionPool.evictAll()
    }

    private fun storageFor(password: String) =
        WebDavStorage(WebDavClient(WebDavAccount(server.url("/dav").toString(), "alice", password, "myMusic"), http))

    private fun open(position: Long, length: Long): Pair<WebDavRangeReader.Opened, ByteArray> {
        val opened = WebDavRangeReader.open(storage.streamClient, server.url(path).toString(), position, length)
        val data = opened.response.use { opened.stream.readBytes() }
        val limited = if (opened.bytesRemaining >= 0) data.copyOf(opened.bytesRemaining.toInt()) else data
        return opened to limited
    }

    @Test
    fun rangedReadCarriesAuthAndRangeHeader() {
        val (opened, data) = open(position = 2, length = 3)

        assertArrayEquals("234".toByteArray(), data)
        assertEquals(3L, opened.bytesRemaining)
        assertFalse(opened.rangeIgnored)
        val get = dav.requests.last { it.method == "GET" }
        assertEquals("bytes=2-4", get.headers["Range"])
        // 认证来自 WebDAV 客户端的 OkHttpClient，DataSpec 里不放密码
        assertEquals(Credentials.basic("alice", "secret", Charsets.UTF_8), get.headers["Authorization"])
        assertEquals("identity", get.headers["Accept-Encoding"])
    }

    @Test
    fun openEndedRangeAndPlainRead() {
        val (tail, tailData) = open(position = 10, length = -1)
        assertArrayEquals("abcdef".toByteArray(), tailData)
        assertEquals(6L, tail.bytesRemaining)
        assertEquals("bytes=10-", dav.requests.last { it.method == "GET" }.headers["Range"])

        val (whole, wholeData) = open(position = 0, length = -1)
        assertArrayEquals(bytes, wholeData)
        assertEquals(bytes.size.toLong(), whole.bytesRemaining)
        assertNull(dav.requests.last { it.method == "GET" }.headers["Range"])
    }

    @Test
    fun serverIgnoringRangeIsSkippedLocallyAndReported() {
        dav.ignoreRange = true
        val (opened, data) = open(position = 5, length = 4)

        assertTrue(opened.rangeIgnored)
        assertEquals(4L, opened.bytesRemaining)
        assertArrayEquals("5678".toByteArray(), data)
    }

    @Test
    fun seekToEndIsEmptyNotError() {
        val (opened, data) = open(position = bytes.size.toLong(), length = -1)
        assertEquals(0L, opened.bytesRemaining)
        assertEquals(0, data.size)
    }

    @Test
    fun wrongPasswordSurfacesStatus() {
        val error = try {
            WebDavRangeReader.open(storageFor("wrong").streamClient, server.url(path).toString(), 0, -1)
            null
        } catch (e: WebDavStatusException) {
            e
        }
        assertEquals(401, error!!.code)
    }

    @Test
    fun missingFileIs404() {
        try {
            WebDavRangeReader.open(storage.streamClient, server.url("/dav/myMusic/music/00/none.flac").toString(), 0, -1)
            fail("应该抛 404")
        } catch (e: WebDavStatusException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun rangeHeaderAndContentRangeHelpers() {
        assertNull(WebDavRangeReader.rangeHeader(0, -1))
        assertEquals("bytes=0-99", WebDavRangeReader.rangeHeader(0, 100))
        assertEquals("bytes=7-", WebDavRangeReader.rangeHeader(7, -1))
        assertEquals(100L, WebDavRangeReader.startOf("bytes 100-199/1000"))
        assertEquals(1000L, WebDavRangeReader.totalOf("bytes */1000"))
        assertEquals(1000L, WebDavRangeReader.totalOf("bytes 0-99/1000"))
        assertNull(WebDavRangeReader.startOf(null))
    }

    @Test
    fun routingMatchesOnlyUrlsUnderServer() {
        val base = "https://dav.example.com/remote.php/dav/".toHttpUrl()
        assertTrue(WebDavRoutingDataSourceFactory.handles(base, "https://dav.example.com/remote.php/dav/myMusic/music/9f/a.flac"))
        assertTrue(WebDavRoutingDataSourceFactory.handles(base, "https://DAV.example.com/remote.php/dav/a.mp3"))
        assertFalse(WebDavRoutingDataSourceFactory.handles(base, "https://dav.example.com/remote.php/other/a.mp3"))
        assertFalse(WebDavRoutingDataSourceFactory.handles(base, "http://dav.example.com/remote.php/dav/a.mp3"))
        assertFalse(WebDavRoutingDataSourceFactory.handles(base, "https://dav.example.com:8443/remote.php/dav/a.mp3"))
        assertFalse(WebDavRoutingDataSourceFactory.handles(base, "https://m701.music.126.net/a.flac"))
        assertFalse(WebDavRoutingDataSourceFactory.handles(base, "content://media/external/audio/1"))
        // 服务器地址在根上：同主机的都算
        assertTrue(WebDavRoutingDataSourceFactory.handles("https://nas.local/".toHttpUrl(), "https://nas.local/x/a.mp3"))
    }
}
