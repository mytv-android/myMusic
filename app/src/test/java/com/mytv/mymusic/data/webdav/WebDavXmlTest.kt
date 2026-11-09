package com.mytv.mymusic.data.webdav

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 各家 PROPFIND 207 的真实写法：前缀、命名空间、href 形式都不一样，解析结果要一致。 */
class WebDavXmlTest {

    @Test
    fun apacheUsesUpperDPrefixAndLp1ForLiveProperties() {
        val base = "https://nas.example.com/dav/".toHttpUrl()
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:ns0="DAV:">
<D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
<D:href>/dav/myMusic/</D:href>
<D:propstat>
<D:prop>
<lp1:resourcetype><D:collection/></lp1:resourcetype>
<lp1:getlastmodified>Mon, 09 Nov 2026 03:41:05 GMT</lp1:getlastmodified>
<lp1:getetag>"1000-64a2b"</lp1:getetag>
<D:getcontenttype>httpd/unix-directory</D:getcontenttype>
</D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
<D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
<D:href>/dav/myMusic/mymusic-library.json</D:href>
<D:propstat>
<D:prop>
<lp1:resourcetype/>
<lp1:getcontentlength>1234</lp1:getcontentlength>
<lp1:getetag>"4d2-64a2c"</lp1:getetag>
<lp2:executable>F</lp2:executable>
<D:getcontenttype>application/json</D:getcontenttype>
</D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""

        val items = WebDavXml.parseMultistatus(xml, base.resolve("myMusic/")!!, base)

        assertEquals(2, items.size)
        val dir = items[0]
        assertEquals("myMusic/", dir.path)
        assertTrue(dir.isCollection)
        assertEquals("\"1000-64a2b\"", dir.etag)
        assertEquals(1_794_195_665_000L, dir.lastModified)
        val file = items[1]
        assertEquals("myMusic/mymusic-library.json", file.path)
        assertEquals("mymusic-library.json", file.name)
        assertFalse(file.isCollection)
        assertEquals(1234L, file.contentLength)
        assertEquals("\"4d2-64a2c\"", file.etag)
        assertEquals("application/json", file.contentType)
    }

    @Test
    fun nextcloudLowerDPrefixWithPrivatePropsAnd404Propstat() {
        val base = "https://cloud.example.com/remote.php/dav/files/alice%40example.com/".toHttpUrl()
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
 <d:response>
  <d:href>/remote.php/dav/files/alice%40example.com/myMusic/</d:href>
  <d:propstat>
   <d:prop>
    <d:resourcetype><d:collection/></d:resourcetype>
    <d:getlastmodified>Mon, 09 Nov 2026 03:41:05 GMT</d:getlastmodified>
    <d:getetag>&quot;6914a6a1b2c3d&quot;</d:getetag>
    <oc:size>5243114</oc:size>
   </d:prop>
   <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
  <d:propstat>
   <d:prop>
    <d:getcontentlength/>
    <d:getcontenttype/>
   </d:prop>
   <d:status>HTTP/1.1 404 Not Found</d:status>
  </d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/alice%40example.com/myMusic/music/%E6%88%91%E7%9A%84%20%E6%AD%8C.flac</d:href>
  <d:propstat>
   <d:prop>
    <d:resourcetype/>
    <d:getcontentlength>5242880</d:getcontentlength>
    <d:getetag>&quot;0a1b2c&quot;</d:getetag>
    <oc:displayname>不是 DAV 命名空间的 displayname</oc:displayname>
    <d:getcontenttype>audio/flac</d:getcontenttype>
   </d:prop>
   <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
  <d:propstat>
   <d:prop>
    <nc:has-preview/>
    <d:displayname>404 里的值不能用</d:displayname>
   </d:prop>
   <d:status>HTTP/1.1 404 Not Found</d:status>
  </d:propstat>
 </d:response>
</d:multistatus>"""

        val items = WebDavXml.parseMultistatus(xml, base.resolve("myMusic/")!!, base)

        assertEquals(listOf("myMusic/", "myMusic/music/我的 歌.flac"), items.map { it.path })
        assertTrue(items[0].isCollection)
        // 404 propstat 里的空 getcontentlength 不能把目录的长度变成 0
        assertEquals(-1L, items[0].contentLength)
        assertEquals("\"6914a6a1b2c3d\"", items[0].etag)
        val song = items[1]
        assertFalse(song.isCollection)
        assertEquals("我的 歌.flac", song.name)
        assertEquals(5_242_880L, song.contentLength)
        assertEquals("audio/flac", song.contentType)
        // oc:displayname 不是 DAV:，404 里的 d:displayname 也不算
        assertNull(song.displayName)
    }

    @Test
    fun jianguoyunStyleWithDisplayNameAndEncodedChinese() {
        val base = "https://dav.jianguoyun.com/dav/".toHttpUrl()
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://ns.jianguoyun.com">
<d:response><d:href>/dav/%E9%9F%B3%E4%B9%90/</d:href><d:propstat><d:prop><d:getlastmodified>Mon, 09 Nov 2026 02:00:00 GMT</d:getlastmodified><d:getcontentlength>0</d:getcontentlength><d:owner>alice@example.com</d:owner><d:current-user-privilege-set><d:privilege><d:read/></d:privilege><d:privilege><d:write/></d:privilege></d:current-user-privilege-set><d:getcontenttype>httpd/unix-directory</d:getcontenttype><d:displayname>音乐</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
<d:response><d:href>/dav/%E9%9F%B3%E4%B9%90/mymusic-library.json</d:href><d:propstat><d:prop><d:getetag>"jgy-7f3a"</d:getetag><d:getlastmodified>Mon, 09 Nov 2026 02:05:00 GMT</d:getlastmodified><d:getcontentlength>2048</d:getcontentlength><d:getcontenttype>application/json</d:getcontenttype><d:displayname>mymusic-library.json</d:displayname><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""

        val items = WebDavXml.parseMultistatus(xml, base.resolve("%E9%9F%B3%E4%B9%90/")!!, base)

        assertEquals("音乐/", items[0].path)
        assertEquals("音乐", items[0].displayName)
        assertTrue(items[0].isCollection)
        assertEquals("音乐/mymusic-library.json", items[1].path)
        assertEquals("\"jgy-7f3a\"", items[1].etag)
        assertEquals(2048L, items[1].contentLength)
    }

    @Test
    fun defaultNamespaceWithFullUrlHrefsAndRawChinese() {
        val base = "http://192.168.1.5:8080/webdav/".toHttpUrl()
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<multistatus xmlns="DAV:">
  <response>
    <href>http://192.168.1.5:8080/webdav/myMusic/</href>
    <propstat><prop><resourcetype><collection/></resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat>
  </response>
  <response>
    <href>/webdav/myMusic/周杰伦 - 晴天.mp3</href>
    <propstat>
      <prop>
        <resourcetype/>
        <getcontentlength>4096</getcontentlength>
        <getetag>W/"weak-1"</getetag>
        <getlastmodified>2026-11-09T03:41:05Z</getlastmodified>
      </prop>
      <status>HTTP/1.1 200 OK</status>
    </propstat>
  </response>
</multistatus>"""

        val items = WebDavXml.parseMultistatus(xml, base.resolve("myMusic/")!!, base)

        assertEquals(listOf("myMusic/", "myMusic/周杰伦 - 晴天.mp3"), items.map { it.path })
        // 弱 ETag 原样保留，由 WebDavCondition.ifMatch 决定不拿它做 If-Match
        assertEquals("W/\"weak-1\"", items[1].etag)
        // 个别服务器给 ISO 8601
        assertEquals(1_794_195_665_000L, items[1].lastModified)
    }

    @Test
    fun oddPrefixesAndForeignNamespaceWithSameLocalName() {
        val base = "https://dav.example.com/".toHttpUrl()
        val xml = """<?xml version="1.0"?>
<ns0:multistatus xmlns:ns0="DAV:" xmlns:D="urn:not-dav">
  <ns0:response>
    <ns0:href>/a.txt</ns0:href>
    <ns0:propstat>
      <ns0:prop>
        <ns0:getcontentlength>10</ns0:getcontentlength>
        <D:getcontentlength>999</D:getcontentlength>
        <ns0:resourcetype/>
      </ns0:prop>
      <ns0:status>HTTP/1.1 200 OK</ns0:status>
    </ns0:propstat>
  </ns0:response>
  <D:response>
    <D:href>/not-a-dav-response.txt</D:href>
  </D:response>
</ns0:multistatus>"""

        val items = WebDavXml.parseMultistatus(xml, base, base)

        assertEquals(1, items.size)
        assertEquals("a.txt", items[0].path)
        assertEquals(10L, items[0].contentLength)
    }

    @Test
    fun noNamespaceAtAllStillParses() {
        val base = "https://dav.example.com/dav/".toHttpUrl()
        val xml = """<multistatus><response><href>/dav/x/</href><propstat><prop><resourcetype><collection/></resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"""

        val items = WebDavXml.parseMultistatus(xml, base, base)

        assertEquals(1, items.size)
        assertEquals("x/", items[0].path)
        assertTrue(items[0].isCollection)
    }

    @Test
    fun missingResourceTypeFallsBackToTrailingSlashAndBaseItselfIsEmptyPath() {
        val base = "https://dav.example.com/dav/".toHttpUrl()
        val xml = """<d:multistatus xmlns:d="DAV:">
<d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:getetag>"root"</d:getetag></d:prop></d:propstat></d:response>
<d:response><d:href>/dav/folder/</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
<d:response><d:href>/dav/file</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
<d:response><d:href>/dav/gone.txt</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>
</d:multistatus>"""

        val items = WebDavXml.parseMultistatus(xml, base, base)

        assertEquals(listOf("", "folder/", "file"), items.map { it.path })
        assertEquals(listOf(true, true, false), items.map { it.isCollection })
        // 没写 status 的 propstat 按成功处理
        assertEquals("\"root\"", items[0].etag)
    }

    @Test
    fun rejectsDoctypeNonMultistatusAndBrokenXml() {
        val base = "https://dav.example.com/".toHttpUrl()
        val bad = listOf(
            """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY a "aaaa">]><d:multistatus xmlns:d="DAV:"/>""",
            """<html><body>Login</body></html>""",
            """<d:multistatus xmlns:d="DAV:"><d:response>""",
            "",
        )
        bad.forEach { xml ->
            try {
                WebDavXml.parseMultistatus(xml, base, base)
                fail("应拒绝：$xml")
            } catch (error: WebDavException) {
                assertEquals(WebDavErrorKind.InvalidResponse, error.kind)
            }
        }
    }

    @Test
    fun statusLinesAndHttpDates() {
        assertEquals(200, WebDavXml.statusCode("HTTP/1.1 200 OK"))
        assertEquals(404, WebDavXml.statusCode(" HTTP/1.1 404 Not Found "))
        assertEquals(200, WebDavXml.statusCode(null))
        assertEquals(0, WebDavXml.statusCode("garbage"))

        assertEquals(1_794_195_665_000L, WebDavXml.parseHttpDate("Mon, 09 Nov 2026 03:41:05 GMT"))
        assertEquals(1_794_195_665_000L, WebDavXml.parseHttpDate("2026-11-09T03:41:05Z"))
        assertNull(WebDavXml.parseHttpDate("yesterday"))
        assertNull(WebDavXml.parseHttpDate(""))
        assertNull(WebDavXml.parseHttpDate(null))
    }
}
