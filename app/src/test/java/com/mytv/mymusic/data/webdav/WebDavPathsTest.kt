package com.mytv.mymusic.data.webdav

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavPathsTest {

    @Test
    fun serverUrlGetsSchemeAndTrailingSlash() {
        assertEquals("https://dav.jianguoyun.com/dav/", WebDavPaths.parseServerUrl("dav.jianguoyun.com/dav")?.toString())
        assertEquals("http://192.168.1.5:5005/", WebDavPaths.parseServerUrl("  http://192.168.1.5:5005  ")?.toString())
        // 查询串和锚点不属于 WebDAV 地址，拼路径时会捣乱
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/alice/",
            WebDavPaths.parseServerUrl("https://cloud.example.com/remote.php/dav/files/alice?dir=/#top")?.toString(),
        )
        assertNull(WebDavPaths.parseServerUrl(""))
        // 预设标签只填了协议头时还不算地址
        assertNull(WebDavPaths.parseServerUrl("https://"))
        assertNull(WebDavPaths.parseServerUrl("ftp://nas.local/"))
    }

    @Test
    fun directoryNormalization() {
        assertEquals("myMusic/", WebDavPaths.normalizeDirectory(""))
        assertEquals("myMusic/", WebDavPaths.normalizeDirectory("   "))
        assertEquals("", WebDavPaths.normalizeDirectory("/"))
        assertEquals("myMusic/", WebDavPaths.normalizeDirectory("myMusic"))
        assertEquals("音乐/备份/", WebDavPaths.normalizeDirectory("/音乐/备份/"))
        assertEquals("a/b/", WebDavPaths.normalizeDirectory(" a \\ b "))
        // .. 不能把目录带出服务器地址
        assertEquals("a/b/", WebDavPaths.normalizeDirectory("a/../b/./"))
    }

    @Test
    fun joinKeepsTrailingSlashOfLastPart() {
        assertEquals("myMusic/mymusic-library.json", WebDavPaths.join("myMusic/", "mymusic-library.json"))
        assertEquals("mymusic-library.json", WebDavPaths.join("", "mymusic-library.json"))
        assertEquals("a/b/", WebDavPaths.join("a/", "b/"))
        assertEquals("a/", WebDavPaths.join("a/", ""))
        assertEquals("a", WebDavPaths.join("a", ""))
        assertTrue(WebDavPaths.isCollectionPath(""))
        assertTrue(WebDavPaths.isCollectionPath("a/"))
        assertFalse(WebDavPaths.isCollectionPath("a/b.json"))
    }

    @Test
    fun urlPercentEncodesEachSegment() {
        val base = "https://dav.jianguoyun.com/dav/".toHttpUrl()
        assertEquals(
            "https://dav.jianguoyun.com/dav/%E6%88%91%E7%9A%84%20%E6%AD%8C%E5%8D%95/a%20b.json",
            WebDavPaths.url(base, "我的 歌单/a b.json").toString(),
        )
        // % ? # 在文件名里是普通字符，不能被当成转义、查询串或锚点
        assertEquals(
            "https://dav.jianguoyun.com/dav/100%25/x%3F%23.json",
            WebDavPaths.url(base, "100%/x?#.json").toString(),
        )
        assertEquals("https://dav.jianguoyun.com/dav/myMusic/", WebDavPaths.url(base, "myMusic/").toString())
        assertEquals("https://dav.jianguoyun.com/dav/myMusic", WebDavPaths.url(base, "myMusic").toString())
        assertEquals(base, WebDavPaths.url(base, ""))
        assertEquals(base, WebDavPaths.url(base, "/"))
    }

    @Test
    fun nextcloudBaseWithEncodedAtSign() {
        val base = WebDavPaths.parseServerUrl("https://cloud.example.com/remote.php/dav/files/alice%40example.com")!!
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/alice%40example.com/myMusic/",
            WebDavPaths.url(base, "myMusic/").toString(),
        )
        // 服务器回的 href 把 @ 写没写成 %40 都要认得出是同一个目录
        assertEquals(
            "myMusic/a.json",
            WebDavPaths.relativePath(base, "https://cloud.example.com/remote.php/dav/files/alice@example.com/myMusic/a.json".toHttpUrl()),
        )
    }

    @Test
    fun relativePathRoundTripsAndRejectsOutsideBase() {
        val base = "http://10.0.2.2:8080/dav/".toHttpUrl()
        listOf("myMusic/", "myMusic/mymusic-library.json", "音乐/周杰伦 - 晴天.flac", "100%/x?.json", "").forEach { path ->
            assertEquals(path, WebDavPaths.relativePath(base, WebDavPaths.url(base, path)))
        }
        assertNull(WebDavPaths.relativePath(base, "http://10.0.2.2:8080/other/x".toHttpUrl()))
        assertNull(WebDavPaths.relativePath(base, "http://10.0.2.2:8080/".toHttpUrl()))
    }

    @Test
    fun hostLabelShowsNonDefaultPort() {
        assertEquals("dav.jianguoyun.com", WebDavPaths.hostLabel("https://dav.jianguoyun.com/dav/".toHttpUrl()))
        assertEquals("192.168.1.5:5005", WebDavPaths.hostLabel("http://192.168.1.5:5005/".toHttpUrl()))
        assertEquals("nas.local:443", WebDavPaths.hostLabel("http://nas.local:443/".toHttpUrl()))
        assertEquals("[fe80::1]:8080", WebDavPaths.hostLabel("http://[fe80::1]:8080/".toHttpUrl()))
    }

    @Test
    fun accountLocationIgnoresPasswordAndSpelling() {
        val a = WebDavAccount("dav.jianguoyun.com/dav", " alice@example.com ", "app-pass-1", "myMusic")
        val b = WebDavAccount("https://dav.jianguoyun.com/dav/", "alice@example.com", "app-pass-2", "/myMusic/")
        assertEquals(a.locationKey(), b.locationKey())
        assertTrue(a.locationKey() != b.copy(trustSelfSigned = true).locationKey())
        assertEquals("myMusic/mymusic-library.json", a.pathInDirectory("mymusic-library.json"))
        assertEquals("dav.jianguoyun.com", a.hostLabel)
        assertFalse(a.isCleartext)
        assertTrue(WebDavAccount("http://10.0.2.2:8080/", "u", "p").isCleartext)
    }

    @Test
    fun accountEncodeRoundTripsButToStringHidesPassword() {
        val account = WebDavAccount("https://nas.local:5006/", "admin", "s3cr\"et", "音乐/", trustSelfSigned = true)
        assertEquals(account, WebDavAccount.decode(account.encode()))
        assertFalse(account.toString().contains("s3cr"))
        assertNull(WebDavAccount.decode("not json"))
    }

    @Test
    fun weakOrBlankEtagMeansNoIfMatch() {
        assertEquals(WebDavCondition.None, WebDavCondition.ifMatch(null))
        assertEquals(WebDavCondition.None, WebDavCondition.ifMatch(" "))
        assertEquals(WebDavCondition.None, WebDavCondition.ifMatch("W/\"abc\""))
        assertEquals(WebDavCondition.IfMatch("\"abc\""), WebDavCondition.ifMatch(" \"abc\" "))
    }
}
