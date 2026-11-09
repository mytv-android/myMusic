package com.mytv.mymusic.data.webdav

import okhttp3.HttpUrl
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** PROPFIND 里的一项（文件或目录）。 */
data class WebDavResource(
    /** 相对服务器地址、已解码的路径；目录以 `/` 结尾，服务器地址本身是空串。 */
    val path: String,
    val isCollection: Boolean,
    /** 未知时为 -1（目录通常不给）。 */
    val contentLength: Long = -1,
    /** 原样保留引号和 `W/` 前缀，If-Match 要原样回传。 */
    val etag: String? = null,
    val contentType: String? = null,
    /** 毫秒时间戳；服务器没给或格式看不懂时为 null。 */
    val lastModified: Long? = null,
    val displayName: String? = null,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

/**
 * 解析 207 Multi-Status。各家写法差得很远，所以不按前缀认元素，只认“DAV: 命名空间 + 本地名”：
 * - Apache mod_dav 用 `D:`，活属性却挂在 `lp1:`（同样绑到 DAV:）上；
 * - Nextcloud 用 `d:`，再混进 `oc:` / `nc:` 的私有属性，找不到的属性放在 404 的 propstat 里；
 * - 也有服务器直接用默认命名空间 `xmlns="DAV:"`，甚至完全不声明命名空间。
 * href 可能是绝对路径，也可能是完整 URL，中文有的转义有的不转义，统一按请求地址解析再逐段解码。
 */
object WebDavXml {
    private const val DAV = "DAV:"

    fun parseMultistatus(xml: String, requestUrl: HttpUrl, baseUrl: HttpUrl): List<WebDavResource> {
        // multistatus 里不会有 DTD；带 DOCTYPE 的一律当成异常响应，顺带挡掉实体膨胀之类的问题
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
            throw WebDavException(WebDavErrorKind.InvalidResponse, "服务器返回了带 DOCTYPE 的 XML")
        }
        val root = try {
            newBuilderFactory().newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        } catch (error: Exception) {
            throw WebDavException(WebDavErrorKind.InvalidResponse, "服务器返回的 XML 无法解析", cause = error)
        } ?: throw WebDavException(WebDavErrorKind.InvalidResponse, "服务器返回了空的 XML")
        if (!root.isDav("multistatus")) {
            throw WebDavException(WebDavErrorKind.InvalidResponse, "服务器返回的不是 WebDAV 列表")
        }
        return root.davChildren("response").mapNotNull { parseResponse(it, requestUrl, baseUrl) }
    }

    private fun parseResponse(response: Element, requestUrl: HttpUrl, baseUrl: HttpUrl): WebDavResource? {
        val href = response.davChildren("href").firstOrNull()?.textContent?.trim().orEmpty()
        if (href.isEmpty()) return null
        val url = runCatching { requestUrl.resolve(href) }.getOrNull() ?: return null
        val propstats = response.davChildren("propstat")
        // 没有 propstat、只有整体 status 的 response（比如 404）不算一项
        if (propstats.isEmpty()) return null
        val props = propstats
            .filter { statusCode(it.davChildren("status").firstOrNull()?.textContent) in 200..299 }
            .flatMap { it.davChildren("prop") }
            .flatMap { it.childElements() }
            .filter { it.isDav() }
            .associateBy { it.localNameOrSuffix() }
        val resourceType = props["resourcetype"]
        val collection = resourceType?.davChildren("collection")?.isNotEmpty() == true ||
            (resourceType == null && url.encodedPath.endsWith("/"))
        val relative = WebDavPaths.relativePath(baseUrl, url)
            ?: url.pathSegments.dropLastWhile { it.isEmpty() }.joinToString("/")
        val path = when {
            relative.isEmpty() -> ""
            collection && !relative.endsWith("/") -> "$relative/"
            !collection -> relative.trimEnd('/')
            else -> relative
        }
        return WebDavResource(
            path = path,
            isCollection = collection,
            contentLength = props["getcontentlength"]?.textContent?.trim()?.toLongOrNull() ?: -1,
            etag = props["getetag"]?.textContent?.trim()?.takeIf { it.isNotEmpty() },
            contentType = props["getcontenttype"]?.textContent?.trim()?.takeIf { it.isNotEmpty() },
            lastModified = parseHttpDate(props["getlastmodified"]?.textContent),
            displayName = props["displayname"]?.textContent?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** `HTTP/1.1 200 OK` → 200；没有 status 时按成功处理（有些服务器省略它）。 */
    internal fun statusCode(line: String?): Int {
        val value = line?.trim().orEmpty()
        if (value.isEmpty()) return 200
        return value.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
    }

    /** RFC 1123（`Sun, 09 Nov 2026 03:41:05 GMT`）为主；个别服务器给 ISO 8601。 */
    fun parseHttpDate(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(text).toEpochMilli() }
            .getOrNull()
    }

    private fun newBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Android 的实现不认这些开关、会抛异常；JVM（单元测试）上的 Xerces 认，能关就关
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { isExpandEntityReferences = false }
    }

    private fun Element.localNameOrSuffix(): String = localName ?: nodeName.substringAfter(':')

    /** 命名空间是 DAV:；完全没声明命名空间的服务器也认，只看本地名。 */
    private fun Element.isDav(name: String? = null): Boolean {
        val namespace = namespaceURI
        if (namespace != null && namespace != DAV) return false
        return name == null || localNameOrSuffix() == name
    }

    private fun Element.childElements(): List<Element> {
        val result = mutableListOf<Element>()
        var node = firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE) result += node as Element
            node = node.nextSibling
        }
        return result
    }

    private fun Element.davChildren(name: String): List<Element> = childElements().filter { it.isDav(name) }
}
