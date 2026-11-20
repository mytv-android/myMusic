package com.mytv.mymusic.data.native

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 汽水扫码登录的**纯 HTTP** 通道：不经隐藏 WebView、不要 a_bogus、不带 x-helios/x-medusa，
 * 直接用 OkHttp 发 passport 请求，登录态从 `Set-Cookie` 里收。
 *
 * 这是 libresoda 8d35f59（「纯 HTTP 扫码登录与二次验证桥接」）之后的形态：桌面版原来靠
 * CDP 拉起 Chromium 当签名页，上游已把整条 `cdp_signer` 删掉，改成本地 HTTP 直连。
 * `docs/QR-LOGIN.md` 记录了两个二维码共用同一浏览器上下文会被护照服务限流
 * （`error_code=7`），所以这里一个二维码一份独立的 cookie jar（对齐上游的 `session_key`）。
 *
 * 仍然走 WebView 的只有二次验证（`error_code=2046`）：官方验证组件要真实浏览器环境，
 * 由 [SodaQrSigner] 开可见窗口跑，见 [showSecondVerify]。
 */
internal class SodaQrHttpGateway : SodaQrGateway {

    /** 一个二维码一份 cookie jar：设备身份、登录态都隔离，避免互相污染。 */
    private val jar = object : CookieJar {
        private val store = LinkedHashMap<String, Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                // 过期的持久 cookie 直接丢掉，其余的按名字覆盖
                if (cookie.persistent && cookie.expiresAt < System.currentTimeMillis()) {
                    store.remove(cookie.name)
                } else {
                    store[cookie.name] = cookie
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.values.filter { it.matches(url) }.toList()

        /** 把这些地址能看到的 cookie 拼成请求头，交给上层合并进会话。 */
        fun headerFor(urls: List<String>): String {
            val names = LinkedHashMap<String, String>()
            urls.forEach { raw ->
                val httpUrl = raw.toHttpUrlOrNull() ?: return@forEach
                store.values.filter { it.matches(httpUrl) }.forEach { names[it.name] = it.value }
            }
            return names.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        }

        /** 当前 jar 里的全部键值，给二次验证窗口种 Cookie 用。 */
        fun entries(): List<Pair<String, String>> = store.values.map { it.name to it.value }

        fun clear() = store.clear()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        // 登录态靠自己收 Set-Cookie，不跟着 302 跑
        .followRedirects(false)
        .cookieJar(jar)
        .build()

    /** 种本会话的 msToken：官方 Web 端把它放在 xmst / xmsty 两个 cookie 里。 */
    override suspend fun beginSession(msToken: String) {
        jar.clear()
        val host = PASSPORT_HOST
        val url = HttpUrl.Builder().scheme("https").host(host).build()
        listOf("xmst", "xmsty").forEach { name ->
            jar.saveFromResponse(url, listOf(Cookie.Builder().name(name).value(msToken).domain(host).build()))
        }
    }

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): SodaPageResponse {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IllegalStateException("汽水登录地址无效")
        val builder = Request.Builder().url(httpUrl)
        headers.forEach { (key, value) ->
            // 浏览器 XHR 会自己带这两个，直连时也不该由调用方指定
            if (key.equals("Cookie", ignoreCase = true) || key.equals("User-Agent", ignoreCase = true)) return@forEach
            builder.header(key, value)
        }
        builder.header("User-Agent", PASSPORT_USER_AGENT)
        val verb = method.uppercase()
        when {
            body != null -> builder.method(verb, body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            verb == "POST" -> builder.method("POST", RequestBody.create(null, ByteArray(0)))
            else -> builder.method(verb, null)
        }
        val response = await(client.newCall(builder.build()))
        response.use { raw ->
            val text = runCatching { raw.body?.string().orEmpty() }.getOrElse { "" }
            return SodaPageResponse(
                status = raw.code,
                body = text,
                // jar 里已经是这份响应收完之后的状态，交给上层合并进会话
                cookieHeader = jar.headerFor(
                    listOf(url, "https://api.qishui.com/", "https://www.qishui.com/"),
                ),
            )
        }
    }

    /** 从 cookie jar 里引登录态：确认后跟一跳 redirect_url 时用。 */
    override suspend fun harvestCookies(urls: List<String>): String = jar.headerFor(urls)

    /**
     * 二次验证：官方组件要浏览器环境，转交 [SodaQrSigner] 开可见窗口跑。
     * 组件发出的请求跑在那个 WebView 里，所以先把本会话 jar 里的 cookie 种进
     * 全局 CookieManager——否则验证请求带的身份和轮询时不是同一份。
     * 返回 false（用户取消 / 超时）时上层按「已取消」收尾。
     */
    override suspend fun showSecondVerify(decisionJson: String, generalParamsJson: String): Boolean {
        seedWebViewCookies()
        return SodaQrSigner.showSecondVerify(decisionJson, generalParamsJson)
    }

    private suspend fun seedWebViewCookies() = withContext(Dispatchers.Main) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        jar.entries().forEach { (name, value) ->
            manager.setCookie("https://api.qishui.com/", "$name=$value")
        }
        manager.flush()
    }

    override fun close() {
        jar.clear()
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response)
            }
        })
    }

    private companion object {
        const val PASSPORT_HOST = "api.qishui.com"

        /** 与签名页同一份 UA：护照服务按它判断客户端形态。 */
        const val PASSPORT_USER_AGENT = "LunaPC/3.0.0(290101097)"
    }
}
