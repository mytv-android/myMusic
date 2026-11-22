package com.mytv.mymusic.data.sync

import com.mytv.mymusic.data.lx.LxPlatform
import com.mytv.mymusic.data.lx.LxSourceRecord
import com.mytv.mymusic.data.webdav.WebDavAccount
import com.mytv.mymusic.data.webdav.WebDavPaths
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class TvInputTarget(
    val id: String,
    val label: String,
    val hasValue: Boolean = false,
    val available: Boolean = true,
    val supportsEnter: Boolean = true,
)

internal data class TvInputPanelSnapshot(
    val theme: SyncSetupTheme = SyncSetupTheme(),
    val deviceName: String = "客厅电视",
    val target: TvInputTarget? = null,
    val allowSearch: Boolean = true,
    val allowSettings: Boolean = true,
    val syncProvider: String = "gist",
    val sync: SyncUiState = SyncUiState(),
    val signerUrl: String = "",
    val signerEnabled: Boolean = true,
)

internal enum class TvInputWriteMode(val wire: String) { Replace("replace"), Append("append") }

internal data class TvInputWrite(
    val targetId: String,
    val text: String,
    val mode: TvInputWriteMode,
    val submit: Boolean,
)

internal data class TvSignerSubmission(val url: String, val token: String, val enabled: Boolean)

/** 服务端只接收动作，不直接依赖 Android / ViewModel；宿主协程完成操作后按 operationId 回写结果。 */
internal class TvInputPanelHandlers(
    val write: (TvInputWrite, Long) -> Unit = { _, _ -> },
    val search: (String, Long) -> Unit = { _, _ -> },
    val source: (String, Long) -> Unit = { _, _ -> },
    val sync: (SyncSetupSubmission) -> Unit = {},
    val testWebDav: (WebDavAccount, Long) -> Unit = { _, _ -> },
    val saveStorage: (WebDavAccount, Long) -> Unit = { _, _ -> },
    val signer: (TvSignerSubmission, Long) -> Unit = { _, _ -> },
)

internal enum class TvInputSubmitResult { Accepted, Busy, Invalid, Expired, Failed, Unavailable, Forbidden }

internal data class TvInputPanelStatus(
    val operation: Long = 0,
    val scene: String = "home",
    val phase: SyncSetupPhase = SyncSetupPhase.Idle,
    val title: String = "",
    val detail: String = "",
)

internal class TvInputPanelSession(
    private val snapshot: () -> TvInputPanelSnapshot,
    private val handlers: TvInputPanelHandlers,
    private val clock: () -> Long = System::currentTimeMillis,
    sessionMillis: Long = SYNC_SETUP_SESSION_MILLIS,
    val accessToken: String = newPanelToken(),
) {
    val expiresAt: Long = clock() + sessionMillis

    private enum class Kind { Input, Search, Source, Sync, SyncTest, StorageTest, StorageSave, Signer }

    private data class Operation(
        val id: Long,
        val scene: String,
        val kind: Kind,
        val submittedAt: Long,
        val successTitle: String,
        val successDetail: String,
        val errorTitle: String,
        val testTarget: String = "",
        val baselineCheck: WebDavCheckUi = WebDavCheckUi(),
        var observedCheck: Boolean = false,
        var phase: SyncSetupPhase = SyncSetupPhase.Working,
        var title: String = "正在等待电视",
        var detail: String = "电视正在处理，草稿已保留。",
    ) {
        fun status() = TvInputPanelStatus(id, scene, phase, title, detail)
    }

    private var nextOperation = 0L
    private var active: Operation? = null
    private val syncSession = SyncSetupSession(
        snapshot = {
            val current = snapshot()
            SyncSetupSnapshot(provider = current.syncProvider, sync = current.sync)
        },
        onSubmit = handlers.sync,
        clock = clock,
        sessionMillis = sessionMillis,
        accessToken = accessToken,
    )

    @Synchronized
    fun status(): TvInputPanelStatus {
        val operation = active ?: return TvInputPanelStatus()
        if (operation.kind == Kind.Sync) {
            val status = syncSession.status()
            return TvInputPanelStatus(operation.id, "sync", status.phase, status.title, status.detail)
        }
        if (operation.kind == Kind.SyncTest || operation.kind == Kind.StorageTest) updateTest(operation)
        return operation.status()
    }

    @Synchronized
    fun submitText(request: TvTextRequest): TvInputSubmitResult {
        if (request.text.isBlank() || request.text.length > MAX_TEXT_CHARS) return TvInputSubmitResult.Invalid
        val mode = when (request.mode) {
            TvInputWriteMode.Replace.wire -> TvInputWriteMode.Replace
            TvInputWriteMode.Append.wire -> TvInputWriteMode.Append
            else -> return TvInputSubmitResult.Invalid
        }
        val current = snapshot()
        val target = current.target
        val kind: Kind
        val successTitle: String
        val successDetail: String
        if (request.target == "search") {
            if (!current.allowSearch) return TvInputSubmitResult.Forbidden
            kind = Kind.Search
            successTitle = "已在电视上搜索"
            successDetail = "电视已打开“${request.text.trim()}”的搜索。"
        } else {
            if (request.target != "field" || target == null) return TvInputSubmitResult.Invalid
            if (!target.available || target.id != request.targetId || (request.submit && !target.supportsEnter)) {
                return TvInputSubmitResult.Unavailable
            }
            kind = Kind.Input
            successTitle = if (request.submit) "已发送并确认" else "电视已收到"
            successDetail = if (mode == TvInputWriteMode.Append) "文字已追加到绑定输入框。" else "文字已写入绑定输入框。"
        }
        val operation = begin("home", kind, successTitle, successDetail, "发送失败") ?: return lastBeginError ?: TvInputSubmitResult.Failed
        return run(operation) {
            if (kind == Kind.Search) handlers.search(request.text.trim(), operation.id)
            else handlers.write(TvInputWrite(request.targetId, request.text, mode, request.submit), operation.id)
        }
    }

    @Synchronized
    fun submitSource(url: String): TvInputSubmitResult {
        if (!snapshot().allowSettings) return TvInputSubmitResult.Forbidden
        val trimmed = url.trim()
        if (trimmed.length > MAX_REMOTE_CHARS || !HTTP_URL.containsMatchIn(trimmed)) return TvInputSubmitResult.Invalid
        val operation = begin("source", Kind.Source, "音源已导入", "脚本已通过校验并启用。", "导入失败，未保存")
            ?: return lastBeginError ?: TvInputSubmitResult.Failed
        return run(operation) { handlers.source(trimmed, operation.id) }
    }

    @Synchronized
    fun submitSync(submission: SyncSetupSubmission): TvInputSubmitResult {
        if (!snapshot().allowSettings) return TvInputSubmitResult.Forbidden
        if (isExpired()) return TvInputSubmitResult.Expired
        if (busy()) return TvInputSubmitResult.Busy
        val result = syncSession.submit(submission)
        if (result == SyncSetupSubmitResult.Accepted) {
            nextOperation += 1
            active = Operation(
                nextOperation, "sync", Kind.Sync, clock(), "", "", "连接未完成",
            )
        }
        return when (result) {
            SyncSetupSubmitResult.Accepted -> TvInputSubmitResult.Accepted
            SyncSetupSubmitResult.Busy -> TvInputSubmitResult.Busy
            SyncSetupSubmitResult.Invalid -> TvInputSubmitResult.Invalid
            SyncSetupSubmitResult.Expired -> TvInputSubmitResult.Expired
            SyncSetupSubmitResult.Failed -> TvInputSubmitResult.Failed
        }
    }

    @Synchronized
    fun submitTest(scene: String, account: WebDavAccount): TvInputSubmitResult {
        if (!snapshot().allowSettings || (scene != "sync" && scene != "storage")) return TvInputSubmitResult.Forbidden
        if (!validWebDav(account)) return TvInputSubmitResult.Invalid
        val kind = if (scene == "sync") Kind.SyncTest else Kind.StorageTest
        val normalized = account.normalized()
        val operation = begin("sync".takeIf { scene == "sync" } ?: "storage", kind, "连接正常", "", "连接失败")
            ?: return lastBeginError ?: TvInputSubmitResult.Failed
        active = operation.copy(
            testTarget = normalized.locationKey(),
            baselineCheck = snapshot().sync.webDavCheck,
        )
        return run(active!!) { handlers.testWebDav(normalized, operation.id) }
    }

    @Synchronized
    fun submitStorage(account: WebDavAccount): TvInputSubmitResult {
        if (!snapshot().allowSettings) return TvInputSubmitResult.Forbidden
        if (!validWebDav(account)) return TvInputSubmitResult.Invalid
        val operation = begin("storage", Kind.StorageSave, "WebDAV 已保存", "上传位置已保存，同步位置未改变。", "保存失败")
            ?: return lastBeginError ?: TvInputSubmitResult.Failed
        return run(operation) { handlers.saveStorage(account.normalized(), operation.id) }
    }

    @Synchronized
    fun submitSigner(submission: TvSignerSubmission): TvInputSubmitResult {
        if (!snapshot().allowSettings) return TvInputSubmitResult.Forbidden
        if (submission.url.length > MAX_REMOTE_CHARS || submission.token.length > MAX_TOKEN_CHARS) {
            return TvInputSubmitResult.Invalid
        }
        val enabled = submission.enabled
        val custom = enabled && submission.url.isNotBlank()
        if (custom && WebDavPaths.parseServerUrl(submission.url) == null) return TvInputSubmitResult.Invalid
        val normalized = if (custom) submission else TvSignerSubmission("", "", enabled)
        val title = if (enabled) "签名服务已保存" else "签名器已关闭"
        val detail = when {
            !enabled -> "电视已保存关闭状态。"
            custom -> "电视已保存自定义签名服务。"
            else -> "电视已恢复 SodaM 公共签名服务。"
        }
        val operation = begin("signer", Kind.Signer, title, detail, "保存失败") ?: return lastBeginError ?: TvInputSubmitResult.Failed
        return run(operation) { handlers.signer(normalized, operation.id) }
    }

    @Synchronized
    fun finish(operationId: Long, error: String? = null, detail: String? = null) {
        val operation = active ?: return
        if (operation.id != operationId || operation.phase != SyncSetupPhase.Working) return
        operation.phase = if (error.isNullOrBlank()) SyncSetupPhase.Success else SyncSetupPhase.Error
        operation.title = if (error.isNullOrBlank()) operation.successTitle else operation.errorTitle
        operation.detail = when {
            !error.isNullOrBlank() -> error
            !detail.isNullOrBlank() -> detail
            else -> operation.successDetail
        }
    }

    @Synchronized
    fun finishSource(operationId: Long, record: LxSourceRecord) {
        val version = record.version.takeIf { it.isNotBlank() }?.let { "v$it" }.orEmpty()
        val platforms = LxPlatform.labels.mapNotNull { (source, label) ->
            val capability = record.capabilities[source]
            if (capability != null && "musicUrl" in capability.actions) label else null
        }.joinToString(" · ")
        val detail = listOf(record.name, version, platforms).filter { it.isNotBlank() }.joinToString(" · ")
        finish(operationId, detail = detail.ifBlank { "脚本已通过校验并启用。" })
    }

    fun isExpired(): Boolean = clock() >= expiresAt

    private fun begin(
        scene: String,
        kind: Kind,
        successTitle: String,
        successDetail: String,
        errorTitle: String,
    ): Operation? {
        lastBeginError = when {
            isExpired() -> TvInputSubmitResult.Expired
            busy() -> TvInputSubmitResult.Busy
            else -> null
        }
        if (lastBeginError != null) return null
        nextOperation += 1
        return Operation(
            nextOperation, scene, kind, clock(), successTitle, successDetail, errorTitle,
        ).also { active = it }
    }

    private var lastBeginError: TvInputSubmitResult? = null

    private fun run(operation: Operation, callback: () -> Unit): TvInputSubmitResult = try {
        callback()
        TvInputSubmitResult.Accepted
    } catch (_: Exception) {
        finish(operation.id, "电视没有接收这次操作，请重试。")
        TvInputSubmitResult.Failed
    }

    private fun busy(): Boolean = status().phase in setOf(SyncSetupPhase.Working, SyncSetupPhase.NeedsTv)

    private fun updateTest(operation: Operation) {
        if (operation.phase != SyncSetupPhase.Working) return
        val check = snapshot().sync.webDavCheck
        if (check.target != operation.testTarget) return
        if (check.running) {
            operation.observedCheck = true
            operation.detail = check.message.ifBlank { "电视正在测试连接。" }
            return
        }
        // 同一份地址刚测过时不能立刻吃旧结果；正常会经过 running，极快失败则给 3 秒转换窗口。
        if (check != operation.baselineCheck || operation.observedCheck ||
            clock() - operation.submittedAt >= STALE_CHECK_GRACE_MILLIS
        ) {
            operation.phase = if (check.ok) SyncSetupPhase.Success else SyncSetupPhase.Error
            operation.title = if (check.ok) "连接正常" else "连接失败"
            operation.detail = check.message.ifBlank { if (check.ok) "电视可以访问这个服务器。" else "请检查地址、账号和网络。" }
        }
    }

    private fun validWebDav(account: WebDavAccount): Boolean {
        val normalized = account.normalized()
        val parsed = normalized.baseUrl ?: return false
        val saved = snapshot().sync.webDav
        val reusePassword = normalized.password.isEmpty() && saved?.hasPassword == true &&
            WebDavPaths.parseServerUrl(saved.serverUrl) == parsed && saved.username == normalized.username
        return normalized.serverUrl.length <= MAX_REMOTE_CHARS && normalized.username.isNotBlank() &&
            normalized.username.length <= MAX_FIELD_CHARS && normalized.directory.length <= MAX_FIELD_CHARS &&
            (normalized.password.isNotEmpty() || reusePassword) && normalized.password.length <= MAX_TOKEN_CHARS
    }

    companion object {
        const val MAX_TEXT_CHARS = 4096
        const val MAX_TOKEN_CHARS = 1024
        const val MAX_FIELD_CHARS = 512
        const val MAX_REMOTE_CHARS = 2048
        const val STALE_CHECK_GRACE_MILLIS = 3000L
        val HTTP_URL = Regex("^https?://", RegexOption.IGNORE_CASE)
    }
}

internal class TvInputPanelServer private constructor(
    assets: (String) -> ByteArray?,
    private val snapshot: () -> TvInputPanelSnapshot,
    private val session: TvInputPanelSession,
    bindHost: String?,
) : LanPanelServer(bindHost, assets, session.accessToken) {
    constructor(
        assets: (String) -> ByteArray?,
        snapshot: () -> TvInputPanelSnapshot,
        handlers: TvInputPanelHandlers = TvInputPanelHandlers(),
        bindHost: String? = null,
        clock: () -> Long = System::currentTimeMillis,
        sessionMillis: Long = SYNC_SETUP_SESSION_MILLIS,
    ) : this(assets, snapshot, TvInputPanelSession(snapshot, handlers, clock, sessionMillis), bindHost)

    private val json = Json { ignoreUnknownKeys = true }
    override val expiresAt: Long get() = session.expiresAt
    override fun isExpired(): Boolean = session.isExpired()
    override val expiredMessage: String = "二维码已过期，请扫描电视上的新二维码"

    fun finish(operationId: Long, error: String? = null, detail: String? = null) =
        session.finish(operationId, error, detail)

    fun finishSource(operationId: Long, record: LxSourceRecord) = session.finishSource(operationId, record)

    fun status(): TvInputPanelStatus = session.status()

    override fun route(http: IHTTPSession, route: String): Response = when (http.method) {
        Method.GET -> get(route)
        Method.POST -> post(http, route)
        else -> errorResponse(Response.Status.METHOD_NOT_ALLOWED, "不支持此请求方式")
    }

    private fun get(route: String): Response = when (route) {
        "", "index.html" -> asset("index.html", "text/html; charset=UTF-8", html = true)
        "input.css" -> asset("input.css", "text/css; charset=UTF-8")
        "input.js" -> asset("input.js", "application/javascript; charset=UTF-8")
        "theme.css" -> textResponse(Response.Status.OK, "text/css; charset=UTF-8", themeCss(snapshot().theme))
        "api/session" -> jsonResponse(Response.Status.OK, sessionJson(snapshot()))
        "api/status" -> jsonResponse(Response.Status.OK, statusJson(session.status(), snapshot().target))
        else -> errorResponse(Response.Status.NOT_FOUND, "资源不存在")
    }

    private fun post(http: IHTTPSession, route: String): Response {
        if (route !in WRITE_ROUTES) return errorResponse(Response.Status.NOT_FOUND, "接口不存在")
        http.checkWrite(
            customHeader = "x-mymusic-input",
            contentType = "application/json",
            maxBytes = MAX_BODY_BYTES,
            crossSiteMessage = "请从电视提供的输入页面提交",
            badRequestMessage = "请使用输入页面中的发送按钮",
        )?.let { return it }
        val raw = readBody(http) ?: return errorResponse(Response.Status.BAD_REQUEST, "请求内容为空或过大")
        val result = try {
            when (route) {
                "api/input" -> session.submitText(json.decodeFromString<TvTextRequest>(raw))
                "api/source" -> session.submitSource(json.decodeFromString<SourceRequest>(raw).url)
                "api/sync" -> session.submitSync(json.decodeFromString<PanelConnectRequest>(raw).toSubmission())
                "api/sync/test" -> session.submitTest("sync", json.decodeFromString<DavRequest>(raw).account())
                "api/storage" -> session.submitStorage(json.decodeFromString<DavRequest>(raw).account())
                "api/storage/test" -> session.submitTest("storage", json.decodeFromString<DavRequest>(raw).account())
                "api/signer" -> session.submitSigner(json.decodeFromString<SignerRequest>(raw).submission())
                else -> TvInputSubmitResult.Invalid
            }
        } catch (_: Exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "内容格式不正确")
        }
        return when (result) {
            TvInputSubmitResult.Accepted -> jsonResponse(
                Response.Status.ACCEPTED, statusJson(session.status(), snapshot().target),
            )
            TvInputSubmitResult.Busy -> errorResponse(Response.Status.CONFLICT, "电视正在处理上一项操作，请稍候")
            TvInputSubmitResult.Invalid -> errorResponse(Response.Status.BAD_REQUEST, "请检查填写内容")
            TvInputSubmitResult.Expired -> errorResponse(Response.Status.GONE, "二维码已过期，请重新扫码")
            TvInputSubmitResult.Failed -> errorResponse(Response.Status.INTERNAL_ERROR, "电视没有接收这次操作，请重试")
            TvInputSubmitResult.Unavailable -> errorResponse(Response.Status.CONFLICT, "电视已离开这个输入框，请重新打开扫码输入")
            TvInputSubmitResult.Forbidden -> errorResponse(Response.Status.FORBIDDEN, "这个会话没有开放该功能")
        }
    }

    private fun readBody(http: IHTTPSession): String? {
        val body = mutableMapOf<String, String>()
        try {
            http.parseBody(body)
        } catch (_: Exception) {
            return null
        }
        val raw = body["postData"].orEmpty()
        return raw.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES }
    }

    private fun sessionJson(current: TvInputPanelSnapshot): JsonObject = buildJsonObject {
        put("deviceName", current.deviceName)
        put("expiresAt", expiresAt)
        put("allowSearch", current.allowSearch)
        put("allowSettings", current.allowSettings)
        put("initialScene", "home")
        put("provider", current.syncProvider.takeIf { it in PROVIDERS } ?: "gist")
        current.target?.let { target -> put("target", targetJson(target)) }
        put("signer", buildJsonObject {
            put("enabled", current.signerEnabled)
            put("custom", current.signerUrl.isNotBlank())
            put("url", current.signerUrl)
        })
        current.sync.webDav?.let { account ->
            put("webdav", buildJsonObject {
                put("url", account.serverUrl)
                put("username", account.username)
                put("directory", account.directory)
                put("trustSelfSigned", account.trustSelfSigned)
                put("hasPassword", account.hasPassword)
            })
        }
    }

    private fun statusJson(status: TvInputPanelStatus, target: TvInputTarget?): JsonObject = buildJsonObject {
        put("operation", status.operation)
        put("scene", status.scene)
        put("state", status.phase.wireValue)
        put("title", status.title)
        put("detail", status.detail)
        put("targetAvailable", target?.available == true)
        put("targetHasValue", target?.hasValue == true)
        target?.let { put("targetLabel", it.label) }
    }

    companion object {
        const val MAX_BODY_BYTES = 24 * 1024L
        val PROVIDERS = setOf("gist", "gitee", WebDavSyncProvider.ID)
        val WRITE_ROUTES = setOf(
            "api/input", "api/source", "api/sync", "api/sync/test",
            "api/storage", "api/storage/test", "api/signer",
        )
    }
}

private fun targetJson(target: TvInputTarget) = buildJsonObject {
    put("id", target.id)
    put("label", target.label)
    put("hasValue", target.hasValue)
    put("available", target.available)
    put("supportsEnter", target.supportsEnter)
}

@Serializable
internal data class TvTextRequest(
    val target: String = "",
    val targetId: String = "",
    val text: String = "",
    val mode: String = "replace",
    val submit: Boolean = false,
)

@Serializable
private data class SourceRequest(val url: String = "")

@Serializable
private data class PanelConnectRequest(
    val provider: String = "",
    val token: String = "",
    val remote: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val directory: String = "",
    val trustSelfSigned: Boolean = false,
) {
    fun toSubmission(): SyncSetupSubmission {
        if (provider != WebDavSyncProvider.ID) return SyncSetupSubmission(provider, token.trim(), remote.trim())
        val account = DavRequest(url, username, password, directory, trustSelfSigned).account()
        return SyncSetupSubmission(provider, account.encode(), "", account)
    }
}

@Serializable
private data class DavRequest(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val directory: String = "",
    val trustSelfSigned: Boolean = false,
) {
    fun account() = WebDavAccount(url, username, password, directory, trustSelfSigned)
}

@Serializable
private data class SignerRequest(
    val enabled: Boolean = true,
    val custom: Boolean = true,
    val url: String = "",
    val token: String = "",
) {
    fun submission() = TvSignerSubmission(
        url = if (enabled && custom) url.trim() else "",
        token = if (enabled && custom) token else "",
        enabled = enabled,
    )
}
