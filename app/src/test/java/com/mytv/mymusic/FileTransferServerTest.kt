package com.mytv.mymusic

import com.mytv.mymusic.data.sync.SyncSetupTheme
import com.mytv.mymusic.data.transfer.ExportCounts
import com.mytv.mymusic.data.transfer.ExportFile
import com.mytv.mymusic.data.transfer.FileTransferServer
import com.mytv.mymusic.data.transfer.TransferMode
import com.mytv.mymusic.data.transfer.UploadResult
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

class FileTransferServerTest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val servers = mutableListOf<FileTransferServer>()
    private val uploads = CopyOnWriteArrayList<Pair<ByteArray, String>>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        client.connectionPool.evictAll()
    }

    @Test
    fun wrongTokenIsNotFound() {
        val base = start()
        client.newCall(Request.Builder().url(base.resolve("/s/0123456789abcdef0123456789abcdef/")!!).get().build())
            .execute().use { assertEquals(404, it.code) }
        get(base, "../index.html").use { assertEquals(404, it.code) }
        get(base, "api/session").use { assertEquals(200, it.code) }
    }

    @Test
    fun expiredSessionIsGone() {
        val now = AtomicLong(1_000)
        val base = start(clock = now::get, sessionMillis = 60_000)
        get(base, "api/status").use { assertEquals(200, it.code) }
        now.set(61_000)
        get(base, "api/status").use { response ->
            assertEquals(410, response.code)
            assertTrue(response.body.string().contains("过期"))
        }
        upload(base, lxBytes()).use { assertEquals(410, it.code) }
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun uploadWithoutTransferHeaderIsRejected() {
        val base = start()
        upload(base, lxBytes(), transferHeader = false).use { assertEquals(400, it.code) }
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun crossSiteUploadIsForbidden() {
        val base = start()
        upload(base, lxBytes(), origin = "http://evil.example").use { assertEquals(403, it.code) }
        upload(base, lxBytes(), fetchSite = "cross-site").use { assertEquals(403, it.code) }
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun uploadWithWrongContentTypeIsRejected() {
        val base = start()
        upload(base, lxBytes(), contentType = "text/plain").use { assertEquals(400, it.code) }
        upload(base, lxBytes(), contentType = "multipart/form-data; boundary=x").use { assertEquals(400, it.code) }
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun oversizedUploadIsRejectedWithoutReadingTheBody() {
        val base = start()
        // 只发头、说有 32MB + 1 字节、不发正文：服务端不读正文，直接 400 关连接
        Socket(base.host, base.port).use { socket ->
            socket.soTimeout = 5_000
            val path = base.encodedPath + "api/upload"
            val head = "POST $path HTTP/1.1\r\n" +
                "Host: ${base.host}:${base.port}\r\n" +
                "X-MyMusic-Transfer: 1\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${FileTransferServer.MAX_UPLOAD_BYTES + 1}\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray()); flush() }
            val status = socket.getInputStream().bufferedReader().readLine()
            assertTrue(status, status.startsWith("HTTP/1.1 400"))
        }
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun uploadThatIsNotGzipOrJsonIsUnsupported() {
        val base = start()
        upload(base, "<html>hello</html>".toByteArray()).use { response ->
            assertEquals(415, response.code)
            assertTrue(response.body.string().contains("不是歌单文件"))
        }
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun goodUploadIsAcceptedOnceWithDecodedName() {
        val base = start()
        val bytes = lxBytes()
        upload(base, bytes, name = "跑步 lx_list+1.lxmc").use { response ->
            assertEquals(202, response.code)
            assertEquals("received", json(response)["phase"])
        }
        assertEquals(1, uploads.size)
        assertArrayEquals(bytes, uploads.single().first)
        assertEquals("跑步 lx_list+1.lxmc", uploads.single().second)
        get(base, "api/status").use { assertEquals("received", json(it)["phase"]) }

        // 一个会话只收一个文件
        upload(base, lxBytes()).use { response ->
            assertEquals(409, response.code)
            assertTrue(response.body.string().contains("已经收到"))
        }
        assertEquals(1, uploads.size)
    }

    @Test
    fun busyTvLetsThePhoneRetry() {
        val busy = AtomicBoolean(true)
        val base = start(onUpload = { bytes, name ->
            if (busy.get()) {
                UploadResult.Busy
            } else {
                uploads += bytes to name
                UploadResult.Accepted
            }
        })
        upload(base, "{\"type\":\"playListPart_v2\"}".toByteArray()).use { assertEquals(409, it.code) }
        busy.set(false)
        upload(base, "﻿{\"type\":\"playListPart_v2\"}".toByteArray()).use { assertEquals(202, it.code) }
        assertEquals(1, uploads.size)
    }

    @Test
    fun downloadServesBytesWithUtf8FileNameAndStopsAfterThreeTimes() {
        val file = ExportFile(
            fileName = "跑步 2026-11-19.mymusic.json",
            mime = "application/json",
            bytes = "{\"type\":\"mymusic.playlists\"}".toByteArray(),
            counts = ExportCounts(total = 0, kept = 0, byProvider = emptyMap(), local = 0),
        )
        val base = start(mode = TransferMode.Download(file))
        get(base, "api/session").use { response ->
            val session = Json.parseToJsonElement(response.body.string()).jsonObject
            assertEquals("download", session["mode"]?.jsonPrimitive?.content)
            assertEquals(file.fileName, session["download"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        }
        repeat(FileTransferServer.MAX_DOWNLOADS) {
            get(base, "api/download").use { response ->
                assertEquals(200, response.code)
                assertArrayEquals(file.bytes, response.body.bytes())
                val disposition = response.header("Content-Disposition").orEmpty()
                val encoded = URLEncoder.encode(file.fileName, "UTF-8").replace("+", "%20")
                assertTrue(disposition, disposition.startsWith("attachment; filename=\""))
                assertTrue(disposition, disposition.endsWith("filename*=UTF-8''$encoded"))
                assertTrue(disposition, disposition.all { it.code < 0x80 })
                assertEquals("nosniff", response.header("X-Content-Type-Options"))
                assertEquals("no-store", response.header("Cache-Control"))
            }
        }
        get(base, "api/download").use { assertEquals(410, it.code) }
        get(base, "api/status").use { assertEquals("done", json(it)["phase"]) }
    }

    @Test
    fun downloadModeHasNoUploadAndUploadModeHasNoDownload() {
        val file = ExportFile("a.mymusic.json", "application/json", "{}".toByteArray(), ExportCounts(total = 0, kept = 0, byProvider = emptyMap(), local = 0))
        val download = start(mode = TransferMode.Download(file))
        upload(download, lxBytes()).use { assertEquals(404, it.code) }
        assertTrue(uploads.isEmpty())

        val upload = start()
        get(upload, "api/download").use { assertEquals(404, it.code) }
    }

    @Test
    fun pagesCarrySecurityHeadersAndCsp() {
        val base = start(theme = SyncSetupTheme(primary = "#123456"))
        client.newCall(Request.Builder().url(base).get().build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("no-store", response.header("Cache-Control"))
            assertEquals("nosniff", response.header("X-Content-Type-Options"))
            assertEquals("no-referrer", response.header("Referrer-Policy"))
            assertEquals("same-origin", response.header("Cross-Origin-Resource-Policy"))
            val csp = response.header("Content-Security-Policy").orEmpty()
            assertTrue(csp, csp.contains("script-src 'self'"))
            assertTrue(csp, csp.contains("frame-ancestors 'none'"))
            assertTrue(response.body.string().contains("transfer.js"))
        }
        get(base, "theme.css").use { assertTrue(it.body.string().contains("--primary: #123456;")) }
        get(base, "transfer.js").use { response ->
            assertEquals(200, response.code)
            assertTrue(response.header("Content-Type").orEmpty().startsWith("application/javascript"))
        }
        get(base, "api/session").use { response ->
            val session = Json.parseToJsonElement(response.body.string()).jsonObject
            assertEquals("upload", session["mode"]?.jsonPrimitive?.content)
            assertEquals(FileTransferServer.MAX_UPLOAD_BYTES.toString(), session["maxBytes"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun fileNameHeaderIsDecodedAndCleaned() {
        assertEquals("跑步.lxmc", FileTransferServer.decodeFileName(URLEncoder.encode("跑步.lxmc", "UTF-8").replace("+", "%20")))
        assertEquals("ab.json", FileTransferServer.decodeFileName("a%2Fb.json"))
        assertEquals("手机传来的文件", FileTransferServer.decodeFileName(null))
        assertEquals("手机传来的文件", FileTransferServer.decodeFileName("%ZZ"))
        assertEquals(150, FileTransferServer.decodeFileName("a".repeat(400)).length)
    }

    // ---------------- helpers ----------------

    private fun start(
        mode: TransferMode = TransferMode.Upload,
        theme: SyncSetupTheme = SyncSetupTheme(),
        clock: () -> Long = System::currentTimeMillis,
        sessionMillis: Long = 60_000,
        onUpload: (ByteArray, String) -> UploadResult = { bytes, name ->
            uploads += bytes to name
            UploadResult.Accepted
        },
    ): HttpUrl {
        val assets = mapOf(
            "index.html" to "<html><link href=\"transfer.css\"><link href=\"theme.css\"><script src=\"transfer.js\"></script></html>",
            "transfer.css" to "body { color: white; }",
            "transfer.js" to "console.log('transfer');",
        )
        val server = FileTransferServer(
            assets = { name -> assets[name]?.toByteArray() },
            theme = { theme },
            mode = mode,
            onUpload = onUpload,
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

    private fun upload(
        base: HttpUrl,
        bytes: ByteArray,
        name: String = "lx_list.lxmc",
        transferHeader: Boolean = true,
        contentType: String = "application/octet-stream",
        origin: String? = null,
        fetchSite: String = "same-origin",
    ): Response = client.newCall(
        Request.Builder()
            .url(base.resolve("api/upload")!!)
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .apply { if (transferHeader) header("X-MyMusic-Transfer", "1") }
            .header("X-File-Name", URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
            .header("Sec-Fetch-Site", fetchSite)
            .apply { if (origin != null) header("Origin", origin) }
            .build(),
    ).execute()

    private fun json(response: Response): Map<String, String> =
        Json.parseToJsonElement(response.body.string()).jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun lxBytes(): ByteArray {
        val json = """{"type":"playListPart_v2","data":{"id":"userlist_1","name":"跑步","list":[]}}"""
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.toByteArray()) } }.toByteArray()
    }
}
