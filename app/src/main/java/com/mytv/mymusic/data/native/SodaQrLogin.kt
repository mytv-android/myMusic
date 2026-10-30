package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.QRCheckResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom
import java.util.UUID

/**
 * 汽水扫码登录的会话与状态机，字段和分支逐条对齐 libresoda `qr_login.rs`
 * （其 `docs/QR-LOGIN.md` 有 2026-09 真机扫码的实测记录）。要点：
 *
 * - 二维码内容必须是服务端下发的 `qrcode_index_url` **原样**（改写成
 *   light/invoke/scan_login 只会上报已扫码、确认落不了地）；
 * - `error_code=7` 是临时限流：冷却 5 秒继续，绝不判失败（60 秒会把
 *   「扫码→确认」窗口整段跳过）；
 * - 确认只认服务端状态（status=3/confirmed/success、logged_in、session_cookie）；
 * - 登录态不在响应体里，在签名页 cookie jar：确认后跟一跳 redirect_url，
 *   再校验 sessionid/sessionid_ss/sid_guard/sid_tt。
 */
internal class SodaQrSession(
    var token: String = "",
    val deviceId: String = SodaQrLogin.randomDigits(16),
    val installId: String = SodaQrLogin.randomDigits(15),
    val msToken: String = SodaQrLogin.randomMsToken(),
    val verifyPortraitId: String = "${UUID.randomUUID()}.login",
    @Volatile var cookie: String = "",
    @Volatile var createdMs: Long = System.currentTimeMillis(),
    @Volatile var lastCheckMs: Long = 0,
    @Volatile var cooldownUntilMs: Long = 0,
    @Volatile var lastResult: QRCheckResult? = null,
    @Volatile var everScanned: Boolean = false,
)

/** 登录流程要用的签名页能力；生产实现是 [SodaQrSigner]，测试用假网关。 */
internal interface SodaQrGateway {
    suspend fun beginSession(msToken: String)

    suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): SodaPageResponse

    suspend fun harvestCookies(urls: List<String>): String

    /** 二次验证：开可见窗口跑官方验证组件，返回用户是否完成验证。 */
    suspend fun showSecondVerify(decisionJson: String, generalParamsJson: String): Boolean = false

    fun close()
}

internal class SodaQrCreateResult(val token: String, val scanUrl: String, val qrImage: String)

internal object SodaQrLogin {

    private const val API_BASE = "https://api.qishui.com"
    private const val AID = "386088"

    /** 二维码会话寿命：服务端 TTL 约 3 分钟，超时仍未确认就按过期收尾。 */
    private const val SESSION_TTL_MS = 240_000L

    /** 同一 token 的最小轮询间隔（对齐上游 2.5 秒）。 */
    private const val MIN_CHECK_INTERVAL_MS = 2_500L

    /** `error_code=7`（访问太频繁）后的冷却：必须只压 5 秒，长了会错过确认窗口。 */
    private const val RATE_LIMIT_COOLDOWN_MS = 5_000L

    private val random = SecureRandom()

    /** 创建二维码：`GET /passport/web/get_qrcode/`，返回原样 `qrcode_index_url`；token 写回 [session]。 */
    suspend fun create(gateway: SodaQrGateway, session: SodaQrSession): SodaQrCreateResult {
        gateway.beginSession(session.msToken)
        val url = buildUrl(
            "/passport/web/get_qrcode/",
            session,
            listOf(
                "next" to API_BASE,
                "need_logo" to "false",
                "need_short_url" to "false",
                "is_new_login" to "1",
            ),
        )
        val payload = passportRequest(gateway, session, "GET", url, body = null)
        val data = payload["data"] as? JsonObject ?: JsonObject(emptyMap())
        val token = (data["token"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val errorCode = dataLong(data, "error_code")
        val message = (payload["message"] as? JsonPrimitive)?.content.orEmpty()
        val description = (data["description"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (message != "success" || errorCode != 0L || token.isEmpty()) {
            throw IllegalStateException(description.ifBlank { "汽水二维码生成失败" })
        }
        val indexUrl = (data["qrcode_index_url"] as? JsonPrimitive)?.content.orEmpty()
        session.token = token
        val scanUrl = officialScanUrl(indexUrl)
        return SodaQrCreateResult(
            token = token,
            scanUrl = scanUrl,
            qrImage = (data["qrcode"] as? JsonPrimitive)?.content.orEmpty(),
        )
    }

    /** 轮询扫码状态：`POST /passport/web/check_qrconnect/`。 */
    suspend fun check(gateway: SodaQrGateway, session: SodaQrSession): QRCheckResult {
        val now = System.currentTimeMillis()
        if (session.token.isBlank()) return failed("登录会话无效，请重新生成二维码")
        if (now - session.createdMs > SESSION_TTL_MS) {
            return QRCheckResult(status = "expired", message = "汽水二维码已过期，请重新生成", cookie = "")
        }
        // 限流冷却 / 最小轮询间隔内：直接回上一次结果，不再打服务端
        session.lastResult?.let { cached ->
            if (now < session.cooldownUntilMs || now - session.lastCheckMs < MIN_CHECK_INTERVAL_MS) return cached
        }

        val body = formEncode(
            listOf(
                "need_logo" to "false",
                "need_short_url" to "false",
                "is_frontier" to "true",
                "token" to session.token,
                "is_new_login" to "1",
                "next" to API_BASE,
            ),
        )
        val url = buildUrl("/passport/web/check_qrconnect/", session, extraParams = emptyList())
        val payload = passportRequest(gateway, session, "POST", url, body)
        val data = payload["data"] as? JsonObject ?: JsonObject(emptyMap())
        val errorCode = dataLong(data, "error_code")
        val rawStatus = statusText(data)
        val description = (data["description"] as? JsonPrimitive)?.content?.trim().orEmpty()

        // 响应体里若直接给了 sessionid，先并进会话
        (data["sessionid"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let {
            session.cookie = mergeCookies(session.cookie, listOf("sessionid=$it"))
        }

        // 只认服务端状态，不能拿本地 cookie jar 里残留的 sessionid 判成功
        val confirmed = isConfirmed(data, rawStatus)

        // 二次验证：开着可见窗口跑官方组件，完成后带 biz_params 重发确认
        if (errorCode == 2046L && !confirmed) {
            session.createdMs = now // 验证期间放宽会话寿命
            val generalParams = JsonObject(
                commonParams(session, randomHex(8)).mapValues { JsonPrimitive(it.value) },
            )
            val done = gateway.showSecondVerify(data.toString(), generalParams.toString())
            if (!done) {
                return QRCheckResult(status = "waiting", message = "已取消二次验证，请重新扫码", cookie = "")
            }
            return completeSecondVerify(gateway, session, data)
        }

        if (errorCode == 2L || rawStatus in setOf("expired", "expire", "timeout")) {
            return QRCheckResult(status = "expired", message = "汽水二维码已过期，请重新生成", cookie = "")
        }

        // 7 号：临时限流，冷却 5 秒后继续，绝不判失败
        if (errorCode == 7L && !confirmed) {
            session.cooldownUntilMs = now + RATE_LIMIT_COOLDOWN_MS
            session.lastCheckMs = now
            if (session.lastResult == null) {
                session.lastResult = if (session.everScanned) {
                    QRCheckResult(status = "scanned", message = "已扫码，请在手机上确认", cookie = "")
                } else {
                    QRCheckResult(status = "waiting", message = "汽水正在确认登录，请稍候…", cookie = "")
                }
            }
            return session.lastResult ?: failed("汽水登录确认中")
        }

        if (errorCode != 0L && !confirmed && "访问太频繁" !in description && "操作频繁" !in description) {
            return failed(description.ifBlank { "汽水扫码失败（$errorCode）" })
        }

        if (confirmed) return finishConfirmed(gateway, session, data, payload)

        val scanned = rawStatus == "scanned" || rawStatus == "2"
        if (scanned) session.everScanned = true
        val result = if (scanned) {
            QRCheckResult(status = "scanned", message = "已扫码，请在手机上确认", cookie = "")
        } else {
            QRCheckResult(status = "waiting", message = "等待扫码", cookie = "")
        }
        session.lastResult = result
        session.lastCheckMs = now
        return result
    }

    /** 二次验证完成后，带原决策的 `biz_params` 重发确认（对齐 libresoda complete_second_verify）。 */
    private suspend fun completeSecondVerify(
        gateway: SodaQrGateway,
        session: SodaQrSession,
        decision: JsonObject,
    ): QRCheckResult {
        val body = formEncode(
            buildList {
                add("need_logo" to "false")
                add("need_short_url" to "false")
                add("is_frontier" to "true")
                add("token" to session.token)
                add("is_new_login" to "1")
                add("next" to API_BASE)
                addAll(normalizeBizParams(decision["biz_params"]))
            },
        )
        val url = buildUrl("/passport/web/check_qrconnect/", session, listOf("isResend" to "true"))
        val payload = passportRequest(gateway, session, "POST", url, body)
        val data = payload["data"] as? JsonObject ?: JsonObject(emptyMap())
        val errorCode = dataLong(data, "error_code")
        val rawStatus = statusText(data)
        if (isConfirmed(data, rawStatus)) return finishConfirmed(gateway, session, data, payload)
        if (errorCode == 2046L) {
            // 服务端仍要求验证：下一次轮询会重新走 2046 分支并再次弹出验证窗口
            return QRCheckResult(status = "waiting", message = "服务端仍要求二次验证，请重试", cookie = "")
        }
        if (errorCode == 7L) {
            session.cooldownUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
            return QRCheckResult(status = "waiting", message = "汽水正在确认登录，请稍候…", cookie = "")
        }
        return QRCheckResult(status = "waiting", message = "验证已提交，等待汽水确认…", cookie = "")
    }

    /** 归一化决策里的 `biz_params`：JSON 字符串 / 查询串 / 对象都展开成键值对。 */
    internal fun normalizeBizParams(value: kotlinx.serialization.json.JsonElement?): List<Pair<String, String>> {
        return when (value) {
            null -> emptyList()
            is kotlinx.serialization.json.JsonNull -> emptyList()
            is JsonPrimitive -> {
                val text = value.content.trim()
                if (text.isEmpty()) return emptyList()
                val parsed = runCatching { SodaQrLoginJson.parseToJsonElement(text) }.getOrNull()
                if (parsed != null && parsed !is JsonPrimitive) {
                    normalizeBizParams(parsed)
                } else {
                    text.split('&').mapNotNull { pair ->
                        val key = pair.substringBefore('=', "").trim()
                        if (key.isEmpty()) null else key to pair.substringAfter('=', "").trim()
                    }
                }
            }
            is JsonObject -> value.entries
                .filter { it.value !is kotlinx.serialization.json.JsonNull }
                .map { (key, item) ->
                    key to if (item is JsonPrimitive && item.isString) item.content else item.toString()
                }
            else -> emptyList()
        }
    }

    /** 确认后的收尾：从响应体多处收集登录态，不够就跟一跳 redirect_url 从 cookie jar 里引。 */
    private suspend fun finishConfirmed(
        gateway: SodaQrGateway,
        session: SodaQrSession,
        data: JsonObject,
        payload: JsonObject,
    ): QRCheckResult {
        val parts = mutableListOf<String>()
        for (container in listOf(data, payload)) {
            for (key in listOf("session_cookie", "cookie")) {
                (container[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { parts += it }
            }
        }
        for (key in listOf("sessionid", "session_id")) {
            (data[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { parts += "sessionid=$it" }
        }
        (data["auth"] as? JsonObject)?.let { auth ->
            for (key in listOf("sessionid", "session_id")) {
                (auth[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { parts += "sessionid=$it" }
            }
        }
        session.cookie = mergeCookies(session.cookie, parts)

        if (!hasLoginState(session.cookie)) {
            val redirect = (data["redirect_url"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (redirect.isNotEmpty()) {
                runCatching {
                    gateway.request(
                        "GET",
                        redirect,
                        mapOf("Accept" to "application/json, text/javascript"),
                        null,
                    )
                }
                session.cookie = mergeCookies(
                    session.cookie,
                    listOf(
                        gateway.harvestCookies(
                            listOf(redirect, "https://api.qishui.com/", "https://www.qishui.com/"),
                        ),
                    ),
                )
            }
        }
        if (!hasLoginState(session.cookie)) {
            return failed("汽水扫码成功但没拿到登录态，请重新扫码")
        }
        return QRCheckResult(status = "success", message = "登录成功", cookie = session.cookie)
    }

    /** 经签名页发一次 passport 请求（不带 x-helios/x-medusa：与二维码会话的 device_id 冲突）。 */
    private suspend fun passportRequest(
        gateway: SodaQrGateway,
        session: SodaQrSession,
        method: String,
        url: String,
        body: String?,
    ): JsonObject {
        val traceId = randomHex(8)
        val headers = linkedMapOf(
            "Accept" to "application/json, text/javascript",
            "x-tt-passport-verify-portrait" to session.verifyPortraitId,
            "x-tt-passport-trace-id" to traceId,
        )
        if (body != null) {
            headers["Content-Type"] = "application/x-www-form-urlencoded"
            headers["x-ss-stub"] = NativeCrypto.md5Hex(body).uppercase()
        }
        val response = gateway.request(method, url, headers, body)
        session.cookie = mergeCookies(session.cookie, listOf(response.cookieHeader))
        return parsePayload(response.body, response.status)
    }

    private fun parsePayload(body: String, status: Int): JsonObject {
        val parsed = runCatching {
            SodaQrLoginJson.parseToJsonElement(body) as? JsonObject
        }.getOrNull()
        if (parsed == null) {
            throw IllegalStateException("汽水登录接口返回无效数据（HTTP $status）")
        }
        return parsed
    }

    /**
     * 二维码内容 = 服务端下发的 `qrcode_index_url` 原样返回；改写成
     * light/invoke/scan_login 的二维码实测只能停在「已扫码」。
     */
    fun officialScanUrl(indexUrl: String): String {
        val url = indexUrl.trim()
        if (url.isEmpty()) throw IllegalStateException("汽水二维码缺少扫码地址")
        val query = url.substringAfter('?', "")
        val hasToken = query.split('&').any { it.startsWith("token=") && it.length > "token=".length }
        if (!hasToken) throw IllegalStateException("汽水二维码缺少登录 token")
        return url
    }

    /** JS-SDK 公共参数（键值与 libresoda common_params 一致，buildUrl 里按 key 排序编码）。 */
    private fun commonParams(session: SodaQrSession, bizTraceId: String): Map<String, String> = linkedMapOf(
        "passport_jssdk_version" to "2.4.13",
        "passport_jssdk_type" to "normal",
        "is_from_ttaccountsdk" to "1",
        "aid" to AID,
        "language" to "zh",
        "account_sdk_source" to "web",
        "p_js_v" to "2.4.13",
        "p_js_t" to "pro",
        "p_zt" to "3.3.5",
        "p_ver" to "1.0.29",
        "request_host" to "app%3A%2F%2Fresources",
        "p_bd" to "1.0.0.41",
        "is_new_login" to "1",
        "is_from_iesaccountsaas" to "1",
        "device_platform" to "PC",
        "version_code" to "3.5.2",
        "account_sdk_source_info" to "00",
        "biz_trace_id" to bizTraceId,
        "device_id" to session.deviceId,
        "install_id" to session.installId,
        "did" to session.deviceId,
        "iid" to session.installId,
        "msToken" to session.msToken,
    )

    private fun buildUrl(path: String, session: SodaQrSession, extraParams: List<Pair<String, String>>): String {
        val params = commonParams(session, randomHex(8)).toMutableMap()
        extraParams.forEach { (key, value) -> params[key] = value }
        val query = params.entries.sortedBy { it.key }
            .joinToString("&") { (key, value) -> "${queryEscape(key)}=${queryEscape(value)}" }
        return "$API_BASE$path?$query"
    }

    private fun statusText(data: JsonObject): String =
        when (val status = data["status"]) {
            is JsonPrimitive -> if (status.isString) status.content.trim().lowercase() else status.content
            else -> ""
        }

    private fun isConfirmed(data: JsonObject, rawStatus: String): Boolean {
        if (rawStatus in setOf("3", "confirmed", "success")) return true
        if ((data["logged_in"] as? JsonPrimitive)?.content == "true") return true
        return data.containsKey("session_cookie")
    }

    private fun dataLong(values: JsonObject, key: String): Long =
        when (val value = values[key]) {
            is JsonPrimitive -> value.content.trim().toLongOrNull() ?: 0L
            else -> 0L
        }

    private fun failed(message: String) = QRCheckResult(status = "failed", message = message, cookie = "")

    /** Cookie 合并（对齐 libresoda merge_cookies）：新值覆盖旧值，丢弃 Set-Cookie 属性段。 */
    fun mergeCookies(current: String, incoming: List<String>): String {
        val pairs = mutableListOf<Pair<String, String>>()
        fun push(name: String, value: String) {
            if (name.isEmpty() || value.isEmpty()) return
            val slot = pairs.indexOfFirst { it.first == name }
            if (slot >= 0) pairs[slot] = name to value else pairs += name to value
        }
        val sources = mutableListOf(current)
        sources += incoming
        for (raw in sources) {
            for (part in raw.split(';', ',')) {
                val name = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim().substringBefore(';').trim()
                val lower = name.lowercase()
                if (lower in setOf("path", "domain", "expires", "max-age", "samesite", "secure", "httponly")) continue
                push(name, value)
            }
        }
        return pairs.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    /** 会话 cookie 是否已具备登录态（四件套任一即可）。 */
    fun hasLoginState(cookie: String): Boolean {
        val names = setOf("sessionid", "sessionid_ss", "sid_guard", "sid_tt")
        return cookie.split(';').any { pair ->
            val name = pair.trim().substringBefore('=').trim()
            val value = pair.trim().substringAfter('=', "").trim()
            name in names && value.isNotEmpty()
        }
    }

    internal fun randomDigits(count: Int): String = buildString {
        repeat(count) { append(('0' + random.nextInt(10))) }
    }

    /** msToken：88 字节 base64url（无填充）再补 "=="，对齐上游形态。纯 Kotlin 编码，单测可跑。 */
    internal fun randomMsToken(): String {
        val bytes = ByteArray(88)
        random.nextBytes(bytes)
        return "${base64UrlNoPad(bytes)}=="
    }

    private fun base64UrlNoPad(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val builder = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index + 2 < bytes.size) {
            val packed = ((bytes[index].toInt() and 0xff) shl 16) or
                ((bytes[index + 1].toInt() and 0xff) shl 8) or
                (bytes[index + 2].toInt() and 0xff)
            builder.append(alphabet[(packed ushr 18) and 0x3f])
            builder.append(alphabet[(packed ushr 12) and 0x3f])
            builder.append(alphabet[(packed ushr 6) and 0x3f])
            builder.append(alphabet[packed and 0x3f])
            index += 3
        }
        val remain = bytes.size - index
        if (remain == 1) {
            val packed = (bytes[index].toInt() and 0xff) shl 16
            builder.append(alphabet[(packed ushr 18) and 0x3f])
            builder.append(alphabet[(packed ushr 12) and 0x3f])
        } else if (remain == 2) {
            val packed = ((bytes[index].toInt() and 0xff) shl 16) or ((bytes[index + 1].toInt() and 0xff) shl 8)
            builder.append(alphabet[(packed ushr 18) and 0x3f])
            builder.append(alphabet[(packed ushr 12) and 0x3f])
            builder.append(alphabet[(packed ushr 6) and 0x3f])
        }
        return builder.toString()
    }

    internal fun randomHex(count: Int): String = buildString {
        repeat(count) { append("0123456789abcdef"[random.nextInt(16)]) }
    }

    /** 表单编码：与查询串同一套转义，按 key 排序（对齐 Params.encode）。 */
    internal fun formEncode(values: List<Pair<String, String>>): String =
        values.sortedBy { it.first }
            .joinToString("&") { (key, value) -> "${queryEscape(key)}=${queryEscape(value)}" }

    private fun queryEscape(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val builder = StringBuilder(bytes.size)
        for (byte in bytes) {
            val code = byte.toInt() and 0xff
            when {
                code in 'a'.code..'z'.code ||
                    code in 'A'.code..'Z'.code ||
                    code in '0'.code..'9'.code ||
                    code == '-'.code || code == '_'.code || code == '.'.code || code == '~'.code ->
                    builder.append(code.toChar())
                code == ' '.code -> builder.append('+')
                else -> builder.append("%").append(String.format("%02X", code))
            }
        }
        return builder.toString()
    }
}

private val SodaQrLoginJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
}
