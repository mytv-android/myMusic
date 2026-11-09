package com.mytv.mymusic.data.webdav

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内存里的最小 WebDAV 服务器，只实现同步和“测试连接”要用的方法，行为照 Apache / Nextcloud：
 * MKCOL 父目录不在回 409、已存在回 405；PUT 认 If-Match / If-None-Match: *；GET 认 Range。
 * 路径一律用客户端发来的转义形式做键，href 原样回传。
 */
internal class FakeWebDavServer(
    private val root: String = "/dav/",
    private val username: String = "alice",
    private val password: String = "secret",
) : Dispatcher() {
    private class Entry(val bytes: ByteArray, val etag: String, val modified: Long)

    private val files = ConcurrentHashMap<String, Entry>()
    private val dirs: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add(root) }
    private val version = AtomicInteger()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** 模仿 Apache 文件刚写完那一秒发的弱 ETag。 */
    @Volatile var weakEtags = false

    /** 不少服务器 PUT 成功也不回 ETag。 */
    @Volatile var putReturnsEtag = true

    /** 要求 Digest 认证（Basic 一律 401）。 */
    @Volatile var digestOnly = false
    private val nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093"

    fun file(path: String): String? = files[path]?.bytes?.toString(Charsets.UTF_8)
    fun etag(path: String): String? = files[path]?.etag
    fun hasDir(path: String) = path in dirs
    fun paths(): Set<String> = files.keys + dirs

    /** 另一台设备改了文件：换内容、换 ETag。 */
    fun putFromElsewhere(path: String, content: String) {
        files[path] = Entry(content.toByteArray(), nextEtag(), System.currentTimeMillis())
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        if (!authorized(request)) {
            val challenge = if (digestOnly) {
                "Digest realm=\"dav\", qop=\"auth\", algorithm=MD5, nonce=\"$nonce\", opaque=\"5ccc069c\""
            } else {
                "Basic realm=\"dav\""
            }
            return MockResponse.Builder().code(401).addHeader("WWW-Authenticate", challenge).build()
        }
        val path = request.url.encodedPath
        return when (request.method) {
            "PROPFIND" -> propfind(path, request.headers["Depth"] ?: "infinity")
            "MKCOL" -> mkcol(path)
            "PUT" -> put(path, request)
            "GET" -> get(path, request, withBody = true)
            "HEAD" -> get(path, request, withBody = false)
            "DELETE" -> delete(path)
            else -> status(405)
        }
    }

    private fun authorized(request: RecordedRequest): Boolean {
        val header = request.headers["Authorization"].orEmpty()
        if (!digestOnly) return header == Credentials.basic(username, password, Charsets.UTF_8)
        if (!header.startsWith("Digest ")) return false
        val params = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""").findAll(header.removePrefix("Digest "))
            .associate { it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        val ha1 = md5("$username:dav:$password")
        val ha2 = md5("${request.method}:${params["uri"]}")
        val expected = md5("$ha1:$nonce:${params["nc"]}:${params["cnonce"]}:${params["qop"]}:$ha2")
        return params["username"] == username && params["nonce"] == nonce &&
            params["uri"] == request.url.encodedPath && params["response"] == expected
    }

    private fun propfind(path: String, depth: String): MockResponse {
        val items = when {
            path in dirs -> listOf(path) + if (depth == "0") emptyList() else children(path)
            files.containsKey(path) -> listOf(path)
            // Apache 对不带斜杠的目录回 301；OkHttp 跟随时 PROPFIND 仍是 PROPFIND
            "$path/" in dirs -> return MockResponse.Builder().code(301).addHeader("Location", "$path/").build()
            else -> return status(404)
        }
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">""")
            items.forEach { item ->
                append("<d:response><d:href>").append(item).append("</d:href><d:propstat><d:prop>")
                val entry = files[item]
                if (entry == null) {
                    append("<d:resourcetype><d:collection/></d:resourcetype>")
                } else {
                    append("<d:resourcetype/>")
                    append("<d:getcontentlength>").append(entry.bytes.size).append("</d:getcontentlength>")
                    append("<d:getetag>").append(entry.etag.replace("\"", "&quot;")).append("</d:getetag>")
                }
                append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
            }
            append("</d:multistatus>")
        }
        return MockResponse.Builder()
            .code(207)
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .body(body)
            .build()
    }

    private fun children(dir: String): List<String> =
        (dirs + files.keys).filter { it != dir && it.startsWith(dir) && !it.removePrefix(dir).trimEnd('/').contains('/') }.sorted()

    private fun mkcol(path: String): MockResponse = when {
        path in dirs || files.containsKey(path.trimEnd('/')) -> status(405)
        parent(path) !in dirs -> status(409)
        else -> {
            dirs += path
            status(201)
        }
    }

    private fun put(path: String, request: RecordedRequest): MockResponse {
        if (parent(path) !in dirs) return status(409)
        val existing = files[path]
        val ifNoneMatch = request.headers["If-None-Match"]
        val ifMatch = request.headers["If-Match"]
        if (ifNoneMatch == "*" && existing != null) return status(412)
        if (ifMatch != null && (existing == null || existing.etag != ifMatch || existing.etag.startsWith("W/"))) {
            return status(412)
        }
        val entry = Entry(request.body?.toByteArray() ?: ByteArray(0), nextEtag(), System.currentTimeMillis())
        files[path] = entry
        return MockResponse.Builder()
            .code(if (existing == null) 201 else 204)
            .apply { if (putReturnsEtag) addHeader("ETag", entry.etag) }
            .build()
    }

    private fun get(path: String, request: RecordedRequest, withBody: Boolean): MockResponse {
        val entry = files[path] ?: return status(404)
        val builder = MockResponse.Builder()
            .addHeader("ETag", entry.etag)
            .addHeader("Last-Modified", "Mon, 09 Nov 2026 03:41:05 GMT")
            .addHeader("Accept-Ranges", "bytes")
        val range = request.headers["Range"]?.let { Regex("""bytes=(\d+)-(\d*)""").matchEntire(it) }
        if (range != null) {
            val first = range.groupValues[1].toInt()
            val last = range.groupValues[2].toIntOrNull()?.coerceAtMost(entry.bytes.size - 1) ?: (entry.bytes.size - 1)
            builder.code(206).addHeader("Content-Range", "bytes $first-$last/${entry.bytes.size}")
            if (withBody) builder.body(okio.Buffer().write(entry.bytes.copyOfRange(first, last + 1)))
        } else {
            builder.code(200)
            if (withBody) builder.body(okio.Buffer().write(entry.bytes))
        }
        return builder.build()
    }

    private fun delete(path: String): MockResponse = when {
        files.remove(path) != null -> status(204)
        dirs.remove(path) -> status(204)
        else -> status(404)
    }

    private fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/') + "/"

    private fun nextEtag(): String {
        val tag = "\"v${version.incrementAndGet()}\""
        return if (weakEtags) "W/$tag" else tag
    }

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
