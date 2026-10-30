package com.mytv.mymusic.data.sync

import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

internal const val SYNC_SETUP_SESSION_MILLIS = 10 * 60 * 1000L
private const val MAX_CONNECT_BODY_BYTES = 16 * 1024L

/** TV 当前配色会作为 theme.css 发给面板；这里只包含颜色，不包含账号或令牌。 */
internal data class SyncSetupTheme(
    val background: String = "#0F1513",
    val surface: String = "#151B19",
    val surfaceContainer: String = "#1B211F",
    val surfaceContainerHigh: String = "#252C2A",
    val primary: String = "#82D5C7",
    val onPrimary: String = "#003730",
    val primaryContainer: String = "#005047",
    val onPrimaryContainer: String = "#9CF2E2",
    val secondaryContainer: String = "#334B45",
    val onSecondaryContainer: String = "#D0E8E1",
    val tertiaryContainer: String = "#3A4666",
    val onTertiaryContainer: String = "#DCE2FF",
    val onSurface: String = "#DEE4E0",
    val onSurfaceVariant: String = "#BBC8C2",
    val outline: String = "#86958E",
    val outlineVariant: String = "#3B4944",
    val error: String = "#FFB4AB",
    val onError: String = "#690005",
    val errorContainer: String = "#93000A",
    val onErrorContainer: String = "#FFDAD6",
)

internal data class SyncSetupSnapshot(
    val provider: String = "gist",
    val theme: SyncSetupTheme = SyncSetupTheme(),
    val sync: SyncUiState = SyncUiState(),
)

internal class SyncSetupSubmission(
    val provider: String,
    val token: String,
    val remote: String,
)

internal data class SyncSetupStarted(val url: String, val expiresAt: Long)

internal enum class SyncSetupPhase(val wireValue: String) {
    Idle("idle"),
    Working("working"),
    NeedsTv("needs_tv"),
    Success("success"),
    Error("error"),
}

internal data class SyncSetupStatus(
    val phase: SyncSetupPhase,
    val title: String,
    val detail: String,
    val provider: String,
)

internal enum class SyncSetupSubmitResult { Accepted, Busy, Invalid, Expired, Failed }

@Serializable
private class ConnectRequest(
    val provider: String = "",
    val token: String = "",
    val remote: String = "",
)

private data class AccountMarker(
    val provider: String?,
    val remoteId: String?,
    val updatedAt: Long,
)

/**
 * 网页会话只记录提交来源和同步进度，不保留提交的令牌。
 * 已连接账户只有在本次连接实际开始或账户发生变化后才算成功，避免换令牌时误报旧账户。
 */
internal class SyncSetupSession(
    private val snapshot: () -> SyncSetupSnapshot,
    private val onSubmit: (SyncSetupSubmission) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    sessionMillis: Long = SYNC_SETUP_SESSION_MILLIS,
) {
    val accessToken: String = ByteArray(16).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val expiresAt: Long = clock() + sessionMillis

    private var submittedProvider: String? = null
    private var submissionError = ""
    private var baselineAccount = AccountMarker(null, null, 0)
    private var baselineError = ""
    private var submittedAt = 0L
    private var observedTransition = false

    @Synchronized
    fun submit(submission: SyncSetupSubmission): SyncSetupSubmitResult {
        if (isExpired()) return SyncSetupSubmitResult.Expired
        val token = submission.token.trim()
        if (submission.provider !in PROVIDERS || token.isBlank() ||
            token.length > MAX_TOKEN_CHARS || submission.remote.length > MAX_REMOTE_CHARS
        ) {
            return SyncSetupSubmitResult.Invalid
        }
        if (submittedProvider == null && snapshot().sync.status == SyncStatus.Syncing) {
            return SyncSetupSubmitResult.Busy
        }
        submittedProvider?.let {
            if (status().phase in setOf(SyncSetupPhase.Working, SyncSetupPhase.NeedsTv, SyncSetupPhase.Success)) {
                return SyncSetupSubmitResult.Busy
            }
        }
        val currentSync = snapshot().sync
        val account = currentSync.account
        baselineAccount = AccountMarker(account?.provider, account?.remoteId, account?.updatedAt ?: 0)
        baselineError = currentSync.error
        submittedAt = clock()
        submittedProvider = submission.provider
        submissionError = ""
        observedTransition = false
        return try {
            onSubmit(SyncSetupSubmission(submission.provider, token, submission.remote.trim()))
            SyncSetupSubmitResult.Accepted
        } catch (_: Exception) {
            submissionError = "电视没有接收这次配置，请返回电视重试。"
            SyncSetupSubmitResult.Failed
        }
    }

    @Synchronized
    fun status(): SyncSetupStatus {
        val current = snapshot()
        val provider = submittedProvider ?: return SyncSetupStatus(
            phase = SyncSetupPhase.Idle,
            title = "等待配置",
            detail = "填写访问令牌后，电视会验证并完成连接。",
            provider = current.provider.takeIf { it in PROVIDERS } ?: "gist",
        )
        val sync = current.sync
        val account = sync.account
        val accountMarker = AccountMarker(account?.provider, account?.remoteId, account?.updatedAt ?: 0)
        if (sync.status == SyncStatus.Syncing || sync.initialMerge != null ||
            accountMarker != baselineAccount || sync.error != baselineError
        ) {
            observedTransition = true
        }
        return when {
            submissionError.isNotBlank() -> SyncSetupStatus(
                SyncSetupPhase.Error, "连接未完成", submissionError, provider,
            )
            sync.initialMerge != null -> SyncSetupStatus(
                SyncSetupPhase.NeedsTv,
                "请在电视上继续",
                "本机和云端都有歌单。请在电视上选择首次合并方式，此页面会自动更新。",
                provider,
            )
            sync.status == SyncStatus.Syncing -> SyncSetupStatus(
                SyncSetupPhase.Working,
                "正在连接",
                sync.phase.ifBlank { "电视正在验证令牌并读取云端文件。" },
                provider,
            )
            sync.error.isNotBlank() && (observedTransition || clock() - submittedAt >= STALE_ERROR_GRACE_MILLIS) ->
                SyncSetupStatus(SyncSetupPhase.Error, "连接未完成", safeSetupError(sync.error), provider)
            observedTransition && account?.provider == provider && sync.error.isBlank() -> SyncSetupStatus(
                SyncSetupPhase.Success,
                "连接成功",
                "电视已连接 ${providerLabel(provider)}，可以关闭此页面。",
                provider,
            )
            else -> SyncSetupStatus(
                SyncSetupPhase.Working,
                "正在等待电视",
                "配置已发送，电视很快会显示处理进度。",
                provider,
            )
        }
    }

    fun isExpired(): Boolean = clock() >= expiresAt

    private fun safeSetupError(message: String): String = when {
        message.startsWith("令牌无效") || message.startsWith("令牌权限") || message.startsWith("令牌无法读取") -> message
        message.startsWith("找不到云端文件") || message.startsWith("同步文件") || message.startsWith("云端") -> message
        message.startsWith("网络不可达") -> "网络不可达，请检查电视网络连接后重试。"
        message.startsWith("同步服务返回错误") -> "同步服务暂时不可用，请稍后重试；详细错误已显示在电视上。"
        else -> "电视未能完成连接。请查看电视上的错误详情，修正后在此重试。"
    }

    private companion object {
        val PROVIDERS = setOf("gist", "gitee")
        const val MAX_TOKEN_CHARS = 1024
        const val MAX_REMOTE_CHARS = 2048
        const val STALE_ERROR_GRACE_MILLIS = 3000L
    }
}

internal data class SetupAddressCandidate(val interfaceName: String, val address: InetAddress)

/**
 * 挑选要写进二维码的局域网 IPv4。
 *
 * 一台设备常同时有多个 IPv4：开着 Wi-Fi Direct 时 p2p0 会拿到 192.168.49.1 这种和家庭局域网
 * 不同网段的地址，USB / 蓝牙共享网络（usb0、rndis0、bt-pan）、蜂窝数据（rmnet*）、
 * VPN / TUN（tun*、tap*）、Wi-Fi Aware（aware*）也各有地址。只有 Wi-Fi / 以太网那个是手机
 * 所在局域网能访问的，所以按网卡名排优先级，前面的网卡没有可用地址时才退到后面的——只靠
 * USB 共享手机网络上网时，usb0 / rndis0 就是唯一入口，不能把它排除掉。
 *
 * 只展示私网 IPv4：169.254.x.x 是链路本地，按它访问不到，公网地址写进二维码也没有意义。
 * 同一块网卡报了多个地址时保留系统枚举顺序，第一个通常是 DHCP 分下来的那个，不按字符串
 * 比大小——那样 192.168.49.1 会排在 192.168.50.64 前面。
 */
internal fun selectSyncSetupAddress(candidates: List<SetupAddressCandidate>): InetAddress? = candidates
    .filter { candidate ->
        val address = candidate.address
        address is Inet4Address && address.isSiteLocalAddress &&
            !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isAnyLocalAddress
    }
    .sortedWith(
        compareBy<SetupAddressCandidate> { it.interfaceName.lowercase().lanPriority() }
            .thenBy { it.interfaceName },
    )
    .firstOrNull()?.address

/** Wi-Fi / 以太网网卡：手机所在局域网的入口。其余（p2p、usb/rndis、rmnet、tun…）都排在后面。 */
private fun String.lanPriority(): Int {
    val name = lowercase()
    return when {
        name.startsWith("wlan") -> 0
        name.startsWith("eth") || name.startsWith("en") -> 1
        // USB / 蓝牙共享网络：没有 Wi-Fi 和有线时，手机往往就在这个共享网络里
        name.startsWith("usb") || name.startsWith("rndis") || name.startsWith("bt-pan") -> 2
        else -> 3
    }
}

/**
 * 枚举网卡取要展示给手机的地址。服务本身监听通配地址（bindHost 为空时 NanoHTTPD 绑
 * 0.0.0.0），所有网卡都进得来，所以这里只决定二维码和页面上写哪个地址。
 */
private fun findSyncSetupAddress(): InetAddress? = runCatching {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
    Collections.list(interfaces)
        .filter { it.isUp && !it.isLoopback }
        .flatMap { network ->
            Collections.list(network.inetAddresses).map { SetupAddressCandidate(network.name, it) }
        }
        .let(::selectSyncSetupAddress)
}.getOrNull()

/**
 * 仅在电视连接页运行的局域网 HTTP 面板。
 * 二维码持有随机会话路径；接口只接受同源 JSON POST，不开放令牌读取或任意文件访问。
 */
internal class SyncSetupServer(
    private val assets: (String) -> ByteArray?,
    snapshot: () -> SyncSetupSnapshot,
    onSubmit: (SyncSetupSubmission) -> Unit,
    private val bindHost: String? = null,
    clock: () -> Long = System::currentTimeMillis,
    sessionMillis: Long = SYNC_SETUP_SESSION_MILLIS,
) : NanoHTTPD(bindHost, 0) {
    private val snapshot = snapshot
    private val session = SyncSetupSession(snapshot, onSubmit, clock, sessionMillis)
    private val sessionPath = "/s/${session.accessToken}/"
    private val json = Json { ignoreUnknownKeys = true }

    fun startPanel(): SyncSetupStarted {
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
        return SyncSetupStarted(
            url = "http://$advertisedHost:$listeningPort$sessionPath",
            expiresAt = session.expiresAt,
        )
    }

    override fun serve(session: IHTTPSession): Response {
        if (!session.isLocalPeer()) {
            return errorResponse(Response.Status.FORBIDDEN, "仅允许局域网设备访问")
        }
        if (this.session.isExpired()) {
            return errorResponse(Response.Status.GONE, "二维码已过期，请扫描电视上的新二维码")
        }
        val uri = session.uri.orEmpty()
        if (!uri.startsWith(sessionPath)) {
            return errorResponse(Response.Status.NOT_FOUND, "页面不存在")
        }
        return when (session.method) {
            Method.GET -> get(uri.removePrefix(sessionPath))
            Method.POST -> if (uri.removePrefix(sessionPath) == "api/connect") {
                connect(session)
            } else {
                errorResponse(Response.Status.NOT_FOUND, "接口不存在")
            }
            else -> errorResponse(Response.Status.METHOD_NOT_ALLOWED, "不支持此请求方式")
        }
    }

    private fun get(route: String): Response = when (route) {
        "", "index.html" -> asset("index.html", "text/html; charset=UTF-8", html = true)
        "setup.css" -> asset("setup.css", "text/css; charset=UTF-8")
        "setup.js" -> asset("setup.js", "application/javascript; charset=UTF-8")
        "theme.css" -> textResponse(Response.Status.OK, "text/css; charset=UTF-8", themeCss())
        "api/session" -> jsonResponse(
            Response.Status.OK,
            buildJsonObject {
                put("provider", snapshot().provider.takeIf { it == "gist" || it == "gitee" } ?: "gist")
                put("expiresAt", session.expiresAt)
            },
        )
        "api/status" -> jsonResponse(Response.Status.OK, statusJson(session.status()))
        else -> errorResponse(Response.Status.NOT_FOUND, "资源不存在")
    }

    private fun connect(http: IHTTPSession): Response {
        if (http.header("sec-fetch-site") == "cross-site" || !http.hasSameOrigin()) {
            return errorResponse(Response.Status.FORBIDDEN, "请从电视提供的配置页面提交")
        }
        if (http.header("x-mymusic-setup") != "1" ||
            http.header("content-type")?.substringBefore(';')?.trim() != "application/json"
        ) {
            return errorResponse(Response.Status.BAD_REQUEST, "请使用配置页面中的连接按钮")
        }
        val contentLength = http.header("content-length")?.toLongOrNull()
        if (contentLength == null || contentLength <= 0 || contentLength > MAX_CONNECT_BODY_BYTES) {
            return errorResponse(Response.Status.BAD_REQUEST, "请求内容为空或过大")
        }
        val body = mutableMapOf<String, String>()
        try {
            http.parseBody(body)
        } catch (_: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "无法读取配置")
        }
        val raw = body["postData"].orEmpty()
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_CONNECT_BODY_BYTES) {
            return errorResponse(Response.Status.BAD_REQUEST, "请求内容过大")
        }
        val request = try {
            json.decodeFromString<ConnectRequest>(raw)
        } catch (_: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "配置格式不正确")
        }
        return when (session.submit(SyncSetupSubmission(request.provider, request.token, request.remote))) {
            SyncSetupSubmitResult.Accepted -> jsonResponse(
                Response.Status.ACCEPTED,
                statusJson(
                    SyncSetupStatus(
                        SyncSetupPhase.Working,
                        "配置已发送",
                        "电视已收到配置，正在开始验证。",
                        request.provider,
                    ),
                ),
            )
            SyncSetupSubmitResult.Busy -> errorResponse(Response.Status.CONFLICT, "电视正在处理配置，请稍候")
            SyncSetupSubmitResult.Invalid -> errorResponse(
                Response.Status.BAD_REQUEST,
                "请检查服务类型和令牌；已有文件 ID 或链接不能超过 2048 个字符",
            )
            SyncSetupSubmitResult.Expired -> errorResponse(Response.Status.GONE, "二维码已过期，请重新扫码")
            SyncSetupSubmitResult.Failed -> errorResponse(Response.Status.INTERNAL_ERROR, "电视没有接收配置，请重试")
        }
    }

    private fun statusJson(status: SyncSetupStatus): JsonObject = buildJsonObject {
        put("state", status.phase.wireValue)
        put("title", status.title)
        put("detail", status.detail)
        put("provider", status.provider)
    }

    private fun themeCss(): String {
        val theme = snapshot().theme
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

    private fun asset(name: String, mime: String, html: Boolean = false): Response {
        val bytes = assets(name) ?: return errorResponse(Response.Status.NOT_FOUND, "面板资源未打包")
        return textResponse(Response.Status.OK, mime, String(bytes, Charsets.UTF_8), html)
    }

    private fun errorResponse(status: Response.Status, message: String): Response = jsonResponse(
        status,
        buildJsonObject { put("error", message) },
    ).apply {
        // 早期拒绝可能尚未读取 POST 请求体，不能让它残留在持久连接里污染下一次请求。
        addHeader("Connection", "close")
    }

    private fun jsonResponse(status: Response.Status, body: JsonObject): Response = textResponse(
        status,
        "application/json; charset=UTF-8",
        body.toString(),
    )

    private fun textResponse(
        status: Response.Status,
        mime: String,
        body: String,
        html: Boolean = false,
    ): Response = newFixedLengthResponse(status, mime, body).apply {
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

    private fun IHTTPSession.header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun IHTTPSession.hasSameOrigin(): Boolean {
        val origin = header("origin") ?: return true
        val host = header("host") ?: return false
        return origin.trimEnd('/').equals("http://$host", ignoreCase = true)
    }

    private fun IHTTPSession.isLocalPeer(): Boolean {
        val address = runCatching { InetAddress.getByName(remoteIpAddress) }.getOrNull() ?: return false
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
    }

    private companion object {
        val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    }
}

internal fun providerLabel(provider: String): String = when (provider) {
    "gist" -> "GitHub Gist"
    "gitee" -> "Gitee 代码片段"
    else -> provider
}
