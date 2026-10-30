package com.mytv.mymusic.data.native

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mytv.mymusic.ForegroundActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.lang.ref.WeakReference
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** 签名页单次请求的结果：响应体 + WebView cookie jar 里该 URL 当前可见的 Cookie。 */
internal class SodaPageResponse(val status: Int, val body: String, val cookieHeader: String)

/**
 * 汽水扫码登录的「签名页」：隐藏 WebView 里跑 libresoda 内置的官方安全组件
 * （assets/soda/security/：security_host.html + sdk-glue.js + bdms.js）。
 *
 * 桌面端 SodaM 靠 CDP 拉起 Chromium（`--disable-web-security`）当签名页；Android
 * 上用 `loadDataWithBaseURL("https://api.qishui.com/")` 让页面拿到汽水同源身份：
 * passport 请求由页面 XHR 同源发出，bdms 自动补 a_bogus（44 字符），登录态落在
 * WebView 的 CookieManager 里。passport 请求**不能**带 x-helios/x-medusa 签名
 * （那套设备指纹与二维码会话的 device_id 冲突），也不要传 Cookie/User-Agent 头
 * （XHR 的 forbidden header，浏览器自己带）。
 */
internal object SodaQrSigner : SodaQrGateway {

    private const val PAGE_ORIGIN = "https://api.qishui.com/"
    private const val ASSET_DIR = "soda/security"
    private const val PAGE_LOAD_TIMEOUT_MS = 30_000L
    private const val BDMS_READY_TIMEOUT_MS = 30_000L
    private const val REQUEST_TIMEOUT_MS = 45_000L

    /** 与 libresoda qr_login.rs / 官方 PC 客户端一致的护照 UA。 */
    const val PASSPORT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "SodaMusic/3.2.1 Chrome/136.0.7103.59 Electron/36.4.0 Safari/537.36"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var page: SignerPage? = null

    /**
     * 开一个二维码会话：确保签名页就绪，并做会话隔离——清空 qishui 的 Cookie 与
     * 页面 localStorage（对应桌面端「每个二维码一个独立 BrowserContext」，不隔离
     * 会被护照服务按设备身份限流，error_code=7 连环出现）。
     */
    override suspend fun beginSession(msToken: String) {
        ensurePage().beginSession(msToken)
    }

    /** 经签名页发一次 passport 请求；校验 bdms 补的 a_bogus，并带回 cookie jar 快照。 */
    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): SodaPageResponse =
        ensurePage().request(method, url, headers, body)

    /** 读取 cookie jar：把一组 URL 下可见的 Cookie 拼成一条头（去重交给上层 merge）。 */
    override suspend fun harvestCookies(urls: List<String>): String = page?.harvestCookies(urls) ?: ""

    /**
     * 二次验证（`error_code=2046`）：开一个可见窗口跑官方验证组件。
     * 页面与签名页同源同存储（CookieManager / localStorage 全应用共享），
     * 组件发出的白名单请求由页面内 `__qishuiRequest` 补 a_bogus 后同源发出。
     * 返回 true 表示用户完成了验证。
     */
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun showSecondVerify(decisionJson: String, generalParamsJson: String): Boolean {
        val activity = ForegroundActivity.get() ?: return false
        return withContext(Dispatchers.Main) {
            val dialog = android.app.Dialog(activity)
            val webView = WebView(activity)
            val done = CompletableDeferred<Boolean>()
            fun configure(view: WebView) {
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = PASSPORT_USER_AGENT
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                view.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun complete(status: Boolean, message: String) {
                        if (done.isActive) done.complete(status)
                    }
                }, "__qishuiAndroidVerify")
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                        interceptSecurityAsset(v, request.url.toString())

                    @Deprecated("Deprecated in Java")
                    override fun shouldInterceptRequest(v: WebView, url: String): WebResourceResponse? =
                        interceptSecurityAsset(v, url)
                }
            }
            configure(webView)
            dialog.setContentView(webView)
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            dialog.setOnCancelListener { if (done.isActive) done.complete(false) }
            dialog.setOnDismissListener {
                runCatching { webView.destroy() }
            }
            val html = activity.assets.open("$ASSET_DIR/soda_verify.html")
                .use { it.readBytes().toString(Charsets.UTF_8) }
            webView.loadDataWithBaseURL(PAGE_ORIGIN, html, "text/html", "utf-8", null)
            dialog.show()
            // 等页面与 bdms 就绪后启动验证组件（JS 字符串直接内联，避免拼接注入）
            val started = withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS + BDMS_READY_TIMEOUT_MS) {
                while (true) {
                    val ready = evaluateOn(webView, "(window.bdms && window.__runSecondVerify) ? '1' : '0'")
                    if (ready.contains("1")) break
                    delay(250)
                }
                evaluateOn(webView, "window.__runSecondVerify($decisionJson, {generalParams:$generalParamsJson})")
                true
            } ?: false
            val result = if (!started) false else withTimeoutOrNull(SECOND_VERIFY_WAIT_MS) { done.await() } ?: false
            runCatching { dialog.dismiss() }
            result
        }
    }

    private suspend fun evaluateOn(view: WebView, script: String): String {
        val result = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            view.evaluateJavascript(script) { value -> result.complete(value ?: "null") }
        }
        return withTimeout(PAGE_LOAD_TIMEOUT_MS) { result.await() }
    }

    override fun close() {
        val current = page ?: return
        page = null
        current.destroy()
    }

    private suspend fun ensurePage(): SignerPage {
        val existing = page
        if (existing != null && existing.isAlive) return existing
        existing?.destroy()
        return withContext(Dispatchers.Main) {
            val activity = ForegroundActivity.get()
                ?: throw IllegalStateException("请回到应用界面内再发起汽水登录")
            val created = SignerPage(activity)
            created.load()
            page = created
            created
        }
    }

    private class SignerPage(activity: Activity) {
        private val activityRef = WeakReference(activity)
        private val webView = WebView(activity)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
        private val requestId = AtomicInteger(0)
        private var loaded = CompletableDeferred<Unit>()
        private var destroyed = false

        /** 页面还活着且仍挂在当前前台 Activity 上；Activity 重建后要整页重来。 */
        val isAlive: Boolean get() = !destroyed && activityRef.get() === ForegroundActivity.get()

        init {
            // 挂到窗口上（0 尺寸、不可见）：未挂载的 WebView 定时器与渲染可能被系统节流
            runCatching {
                (activity.window.decorView as? ViewGroup)?.addView(
                    webView,
                    ViewGroup.LayoutParams(0, 0),
                )
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        fun load() {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = PASSPORT_USER_AGENT
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (loaded.isActive) loaded.complete(Unit)
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    interceptSecurityAsset(view, request.url.toString())

                @Deprecated("Deprecated in Java")
                override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                    interceptSecurityAsset(view, url)
            }
            webView.addJavascriptInterface(Bridge(), "__qishuiAndroid")
            val html = webView.context.assets.open("$ASSET_DIR/security_host.html")
                .use { it.readBytes().toString(Charsets.UTF_8) }
            webView.loadDataWithBaseURL(PAGE_ORIGIN, html, "text/html", "utf-8", null)
        }

        /**
         * security_host.html 以 api.qishui.com 为源加载相对脚本（react/sdk-glue），
         * sdk-glue 又会从字节的 CDN 拉 bdms.js：全部改由本地资产应答，确定性最高。
         */
        private fun interceptAsset(url: String): WebResourceResponse? =
            interceptSecurityAsset(webView, url)

        suspend fun beginSession(msToken: String) {
            withTimeout(PAGE_LOAD_TIMEOUT_MS) {
                loaded.await()
            }
            // 清 Cookie（异步，等回调落地再继续）+ 清 localStorage，再种本会话的 msToken
            removeAllCookiesBlocking()
            evaluate(
                buildString {
                    append("(function(){try{localStorage.clear();")
                    append("localStorage.setItem('xmst',").append(JsonPrimitive(msToken).toString()).append(");")
                    append("localStorage.setItem('xmsty',").append(JsonPrimitive(msToken).toString()).append(");")
                    append("return 'ok';}catch(e){return 'err:'+e;}})()")
                },
            )
            // 等 bdms 就绪：sdk-glue 动态加载 bdms.js 之后页面才具备签名能力
            withTimeout(BDMS_READY_TIMEOUT_MS) {
                while (!evaluate("(window.bdms ? '1' : '0')").contains("1")) delay(200)
            }
        }

        suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): SodaPageResponse {
            val id = requestId.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred
            val spec = buildJsonObject {
                put("method", method.uppercase())
                put("url", url)
                putJsonObject("headers") { headers.forEach { (k, v) -> put(k, v) } }
                if (body != null) put("body", body)
                put("timeout", 20_000)
            }.toString()
            val js = buildString {
                append("(function(){var id=").append(id).append(";var spec=").append(spec).append(";")
                append("var done=function(r){try{__qishuiAndroid.onResult(id,JSON.stringify(r));}catch(e){}};")
                append("try{Promise.resolve(window.__qishuiRequest(spec)).then(")
                append("function(r){done({ok:true,result:r});},")
                append("function(e){done({ok:false,error:String(e&&e.message||e)});});}")
                append("catch(e){done({ok:false,error:String(e&&e.message||e)});}})()")
            }
            try {
                evaluate(js)
                val raw = withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
                return parseResponse(raw, url)
            } finally {
                pending.remove(id)
            }
        }

        private fun parseResponse(raw: String, requestUrl: String): SodaPageResponse {
            val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse {
                throw IllegalStateException("汽水签名页返回异常数据")
            }
            if ((root["ok"] as? JsonPrimitive)?.booleanOrNull == false) {
                throw IllegalStateException("汽水签名页请求失败：${root["error"]?.jsonPrimitive?.content ?: "未知错误"}")
            }
            val result = root["result"]?.jsonObject ?: throw IllegalStateException("汽水签名页缺少响应")
            val status = (result["status"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val body = (result["body"] as? JsonPrimitive)?.content ?: ""
            val responseUrl = (result["responseURL"] as? JsonPrimitive)?.content ?: ""
            // 只拦「bdms 根本没签名」的 200（那种响应会一路撞 error_code=7）；
            // 长度不同 WebView/bdms 版本会给出 44～46 字符，不能用固定长度卡死
            val aBogus = responseUrl.substringAfter('?', "").split('&')
                .firstOrNull { it.startsWith("a_bogus=") }?.substringAfter('=') ?: ""
            if (aBogus.length < 40) {
                throw IllegalStateException("汽水安全签名生成失败：a_bogus 缺失（${aBogus.length} 字符）")
            }
            return SodaPageResponse(status, body, harvestCookieHeader(requestUrl))
        }

        suspend fun harvestCookies(urls: List<String>): String =
            urls.mapNotNull { harvestCookieHeader(it).takeIf { header -> header.isNotBlank() } }.joinToString("; ")

        private fun harvestCookieHeader(url: String): String =
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()

        /** evaluateJavascript 必须在主线程调用；返回值是 JSON 编码后的字符串。 */
        private suspend fun evaluate(script: String): String = withContext(Dispatchers.Main) {
            if (destroyed) throw IllegalStateException("汽水签名页已关闭")
            val result = CompletableDeferred<String>()
            webView.evaluateJavascript(script) { value -> result.complete(value ?: "null") }
            withTimeout(PAGE_LOAD_TIMEOUT_MS) { result.await() }
        }

        private suspend fun removeAllCookiesBlocking() {
            val manager = CookieManager.getInstance()
            suspendCancellableCoroutine { continuation ->
                var remaining = 2
                val finish: () -> Unit = {
                    remaining -= 1
                    if (remaining == 0 && continuation.isActive) continuation.resume(Unit)
                }
                runCatching { manager.removeAllCookies { finish() } }.onFailure { finish() }
                runCatching { manager.removeSessionCookies { finish() } }.onFailure { finish() }
            }
            runCatching { manager.flush() }
        }

        fun destroy() {
            if (destroyed) return
            destroyed = true
            pending.values.forEach {
                if (it.isActive) it.completeExceptionally(IllegalStateException("汽水签名页已关闭"))
            }
            pending.clear()
            if (loaded.isActive) loaded.cancel()
            val detach: () -> Unit = {
                runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
                runCatching { webView.destroy() }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) detach() else mainHandler.post(detach)
        }

        private inner class Bridge {
            @JavascriptInterface
            fun onResult(id: Int, json: String) {
                pending[id]?.complete(json)
            }
        }
    }

    private val securityAssets = setOf("react.js", "react-dom.js", "sdk-glue.js")

    private const val SECOND_VERIFY_WAIT_MS = 5 * 60 * 1000L

    /** 两个安全页面共用的本地资产应答：react/sdk-glue 走相对路径，bdms 走字节 CDN。 */
    private fun interceptSecurityAsset(view: WebView, url: String): WebResourceResponse? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val name = uri.path?.substringAfterLast('/') ?: return null
        val asset = when {
            name == "bdms.js" -> "bdms.js"
            uri.host == "api.qishui.com" && name in securityAssets -> name
            else -> return null
        }
        return runCatching {
            val bytes = view.context.assets.open("$ASSET_DIR/$asset").use { it.readBytes() }
            WebResourceResponse("text/javascript; charset=utf-8", null, bytes.inputStream())
        }.getOrNull()
    }
}
