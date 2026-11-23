package com.mytv.mymusic.data.sync

import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom

internal data class LanPanelStarted(val url: String, val expiresAt: Long)

/**
 * 电视上「手机扫码打开的局域网网页」的公共底座：同步配置（[SyncSetupServer]）、传歌单文件和
 * 手机辅助输入（TvInputPanelServer）共用。
 *
 * - 只认 `/s/<令牌>/` 下的路径，令牌 128 位随机、只在二维码里；
 * - 结尾少一个斜线时 301 回规范地址：手机浏览器和扫码器常把 `/` 吃掉，而页面里的 CSS、JS 和
 *   接口全是相对路径，少了它整页就打不开（issue #33）；
 * - 只收局域网来源（回环、私网、链路本地），过期一律 410；
 * - 统一的安全头（no-store、nosniff、no-referrer、CORP、Permissions-Policy，HTML 带 CSP）；
 * - 写接口的共用检查（同源、非跨站、自定义头、Content-Type、Content-Length 上限），见 [checkWrite]。
 * 监听通配地址，二维码上写 [selectSyncSetupAddress] 挑出来的局域网 IPv4。
 */
internal abstract class LanPanelServer(
    private val bindHost: String?,
    private val assets: (String) -> ByteArray?,
    val accessToken: String = newPanelToken(),
) : NanoHTTPD(bindHost, 0) {
    protected val sessionPath = "/s/$accessToken/"

    abstract val expiresAt: Long

    protected abstract fun isExpired(): Boolean

    /** 过期时网页上看到的话。 */
    protected open val expiredMessage: String = "二维码已过期，请扫描电视上的新二维码"

    /** 会话路径之后的部分（`""`、`index.html`、`api/…`）交给子类。 */
    protected abstract fun route(http: IHTTPSession, route: String): Response

    fun startPanel(): LanPanelStarted {
        val advertisedHost = if (bindHost == null) {
            findSyncSetupAddress()?.hostAddress ?: throw IOException("未找到局域网地址，请连接 Wi-Fi 或有线网络")
        } else {
            bindHost
        }
        try {
            start(SOCKET_READ_TIMEOUT, true)
        } catch (error: Exception) {
            stop()
            throw error
        }
        return LanPanelStarted(
            url = "http://$advertisedHost:$listeningPort$sessionPath",
            expiresAt = expiresAt,
        )
    }

    final override fun serve(session: IHTTPSession): Response {
        if (!session.isLocalPeer()) {
            return errorResponse(Response.Status.FORBIDDEN, "仅允许局域网设备访问")
        }
        if (isExpired()) {
            return errorResponse(Response.Status.GONE, expiredMessage)
        }
        val uri = session.uri.orEmpty()
        // 结尾的斜线被浏览器或扫码器吃掉时不能整页 404：页面里的 input.css / input.js / theme.css
        // 和 api/* 全是相对路径，少一个斜线它们会掉到 /s/ 下面，所以先 301 回规范地址。
        if (uri == sessionPath.removeSuffix("/") || uri == "$sessionPath/") {
            return redirect(sessionPath)
        }
        if (!uri.startsWith(sessionPath)) {
            // 电视上显示的只有 ip:端口，照着敲会落到这里；说清该扫码，别回一段看不懂的 JSON。
            return errorResponse(Response.Status.NOT_FOUND, "请扫描电视上的二维码打开面板")
        }
        return route(session, uri.removePrefix(sessionPath))
    }

    /**
     * 只在会话路径内部重定向，令牌不会出现在会话外的响应里。带上几句正文和链接，
     * 万一扫码器的内置浏览器不跟随 301，用户看到的也不是一片空白。
     */
    protected fun redirect(target: String): Response = textResponse(
        Response.Status.REDIRECT,
        "text/html; charset=UTF-8",
        """<!doctype html><meta charset="utf-8"><title>正在打开面板</title>
<meta http-equiv="refresh" content="0;url=$target">
<body style="font:16px system-ui;padding:24px">正在打开面板…<br>
<a href="$target">如果没有自动跳转，点这里</a></body>""",
        html = true,
    ).apply { addHeader("Location", target) }

    protected fun asset(name: String, mime: String, html: Boolean = false): Response {
        val bytes = assets(name) ?: return errorResponse(Response.Status.NOT_FOUND, "面板资源未打包")
        return textResponse(Response.Status.OK, mime, String(bytes, Charsets.UTF_8), html)
    }

    protected fun errorResponse(status: Response.Status, message: String): Response = jsonResponse(
        status,
        buildJsonObject { put("error", message) },
    ).apply {
        // 早期拒绝可能尚未读取 POST 请求体，不能让它残留在持久连接里污染下一次请求。
        addHeader("Connection", "close")
    }

    protected fun jsonResponse(status: Response.Status, body: JsonObject): Response = textResponse(
        status,
        "application/json; charset=UTF-8",
        body.toString(),
    )

    protected fun textResponse(
        status: Response.Status,
        mime: String,
        body: String,
        html: Boolean = false,
    ): Response = newFixedLengthResponse(status, mime, body).apply { secure(html) }

    /** 统一的安全头；字节响应（下载文件）也要加。 */
    protected fun Response.secure(html: Boolean = false): Response = apply {
        addHeader("Cache-Control", "no-store")
        addHeader("Pragma", "no-cache")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Cross-Origin-Resource-Policy", "same-origin")
        addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        if (html) {
            addHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                    "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
            )
        }
    }

    protected fun IHTTPSession.header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    protected fun IHTTPSession.hasSameOrigin(): Boolean {
        val origin = header("origin") ?: return true
        val host = header("host") ?: return false
        return origin.trimEnd('/').equals("http://$host", ignoreCase = true)
    }

    /**
     * 写接口的共用检查：同源、不是跨站、带 [customHeader]: 1、Content-Type 是 [contentType]、Content-Length 在 1..[maxBytes]。
     * 简单表单和跨站 fetch 都过不了（自定义头会触发预检，而这里不回应预检）。不通过返回错误响应，通过返回 null。
     */
    protected fun IHTTPSession.checkWrite(
        customHeader: String,
        contentType: String,
        maxBytes: Long,
        crossSiteMessage: String,
        badRequestMessage: String,
        tooLargeMessage: String = "请求内容为空或过大",
    ): Response? {
        if (header("sec-fetch-site") == "cross-site" || !hasSameOrigin()) {
            return errorResponse(Response.Status.FORBIDDEN, crossSiteMessage)
        }
        if (header(customHeader) != "1" || header("content-type")?.substringBefore(';')?.trim() != contentType) {
            return errorResponse(Response.Status.BAD_REQUEST, badRequestMessage)
        }
        val contentLength = header("content-length")?.toLongOrNull()
        if (contentLength == null || contentLength <= 0 || contentLength > maxBytes) {
            return errorResponse(Response.Status.BAD_REQUEST, tooLargeMessage)
        }
        return null
    }

    private fun IHTTPSession.isLocalPeer(): Boolean {
        val address = runCatching { InetAddress.getByName(remoteIpAddress) }.getOrNull() ?: return false
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
    }

    companion object {
        private val COLOR = Regex("^#[0-9a-fA-F]{6}$")

        /** 电视当前配色写成 CSS 变量，网页的颜色全来自它；只有颜色，不带账号或令牌。 */
        fun themeCss(theme: SyncSetupTheme): String {
            val colors = linkedMapOf(
                "background" to theme.background,
                "surface" to theme.surface,
                "surface-container" to theme.surfaceContainer,
                "surface-container-high" to theme.surfaceContainerHigh,
                "primary" to theme.primary,
                "on-primary" to theme.onPrimary,
                "primary-container" to theme.primaryContainer,
                "on-primary-container" to theme.onPrimaryContainer,
                "secondary-container" to theme.secondaryContainer,
                "on-secondary-container" to theme.onSecondaryContainer,
                "tertiary-container" to theme.tertiaryContainer,
                "on-tertiary-container" to theme.onTertiaryContainer,
                "on-surface" to theme.onSurface,
                "on-surface-variant" to theme.onSurfaceVariant,
                "outline" to theme.outline,
                "outline-variant" to theme.outlineVariant,
                "error" to theme.error,
                "on-error" to theme.onError,
                "error-container" to theme.errorContainer,
                "on-error-container" to theme.onErrorContainer,
            )
            return colors.entries.joinToString("\n", ":root {\n", "\n}\n") { (name, value) ->
                val safe = value.takeIf { COLOR.matches(it) } ?: "#FF00FF"
                "  --$name: $safe;"
            }
        }
    }
}

/** 32 位十六进制（SecureRandom 16 字节）。 */
internal fun newPanelToken(): String = ByteArray(16).also(SecureRandom()::nextBytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
