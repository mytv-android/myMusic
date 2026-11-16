package com.mytv.mymusic.data.native

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mytv.mymusic.ForegroundActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * QQ 扫码登录的隐藏 WebView：把官方登录页跑起来，让浏览器自己去轮询 ptqrlogin。
 *
 * 为什么要绕这一圈：`ptqrlogin` 现在被 QQ 的网关按请求形状卡——只有浏览器发的请求过得去
 * （TLS 指纹 + `Sec-Fetch-Mode: no-cors` 的 `<script>` 标签），OkHttp / curl / requests
 * 一律 403 空 body（实测：同一会话里 script 标签 200、`fetch()` 403、requests 403）。
 * 官方页面自己就是用 script 标签轮询的，所以在 WebView 里它能过，本机 HTTP 客户端不能。
 *
 * 本层只做三件事：把二维码图片地址从页面里读出来、登录成功后把 CookieManager 里的
 * 登录态收走、页面刷新二维码时把新地址读出来。后面的授权换 key 仍走原生 HTTP。
 */
internal object QQLoginPage {

    private const val PAGE_LOAD_TIMEOUT_MS = 30_000L
    private const val QR_READY_TIMEOUT_MS = 25_000L
    private const val EVAL_TIMEOUT_MS = 10_000L

    /** 桌面 Chrome：官方登录页按 PC 版渲染，二维码才是大的那张。 */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    /** 官方页把二维码设在 #qrlogin_img 上，src 就是 ptqrshow 的地址。 */
    private const val QR_SRC_JS =
        "(function(){var i=document.getElementById('qrlogin_img');" +
            "if(!i)return '';var s=i.getAttribute('src')||'';return s?i.src:'';})()"

    /**
     * 把页面正在显示的那张二维码读成 data URL。
     *
     * 不能自己去请求 ptqrshow：它每调一次就换一张码、种一个新 qrsig，重新拉到的图和
     * 页面正在轮询的那张对不上，用户扫了也登录不了。图片与页面同源，canvas 不会被污染。
     */
    private const val QR_DATA_JS =
        "(function(){var i=document.getElementById('qrlogin_img');" +
            "if(!i||!i.naturalWidth)return '';" +
            "var c=document.createElement('canvas');c.width=i.naturalWidth;c.height=i.naturalHeight;" +
            "var x=c.getContext('2d');x.drawImage(i,0,0);" +
            "try{return c.toDataURL('image/png');}catch(e){return '';}})()"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var page: LoginPage? = null

    /**
     * 打开登录页并等到二维码出来；拿不到前台 Activity 或二维码迟迟不出现时返回 false，
     * 调用方退回原生取码那条路（ptqrlogin 可能被网关拦，但二维码至少能显示）。
     */
    suspend fun begin(loginPageUrl: String): Boolean {
        val existing = page
        if (existing != null && existing.isAlive) return true
        existing?.destroy()
        return withContext(Dispatchers.Main) {
            val activity = ForegroundActivity.get() ?: return@withContext false
            val created = LoginPage(activity)
            created.load(loginPageUrl)
            page = created
            val ready = withTimeoutOrNull(QR_READY_TIMEOUT_MS) {
                created.awaitReady()
                // 等到图能读出来为止：src 挂上时图片可能还没解码，那时候 canvas 读不到东西
                while (created.evaluateQuietly(QR_DATA_JS).isBlank()) delay(250)
                true
            } ?: false
            if (!ready) {
                page = null
                created.destroy()
            }
            ready
        }
    }

    /** 当前二维码图片地址；页面刷新过二维码就会变，调用方据此换图。 */
    suspend fun qrImageUrl(): String = withPage { it.evaluateQuietly(QR_SRC_JS) }.orEmpty()

    /** 页面还在吗：Activity 重建后旧页面就废了，调用方要知道，别干等。 */
    fun isAlive(): Boolean = page?.isAlive == true

    /** 当前二维码图片的 data URL；取不到（图还没画完、canvas 被污染）时返回空。 */
    suspend fun qrImageDataUrl(): String = withPage { it.evaluateQuietly(QR_DATA_JS) }.orEmpty()

    suspend fun cookieHeader(url: String): String = withPage { it.cookieHeader(url) }.orEmpty()

    /** 登录态是否已经落到 cookie 里（扫码成功后 ptlogin 会种下 p_skey）。 */
    suspend fun hasLoginCookie(): Boolean = withPage { it.hasLoginCookie() } ?: false

    /** 把一组地址下可见的 cookie 收成一条头，交给原生那步做授权换 key。 */
    suspend fun harvestCookies(urls: List<String>): String =
        withPage { it.harvestCookies(urls) }.orEmpty()

    fun close() {
        val current = page ?: return
        page = null
        current.destroy()
    }

    private suspend fun <T> withPage(block: suspend (LoginPage) -> T): T? {
        val current = page ?: return null
        if (!current.isAlive) return null
        return runCatching { block(current) }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private class LoginPage(activity: Activity) {
        private val activityRef = WeakReference(activity)
        private val webView = WebView(activity)
        private val loaded = CompletableDeferred<Unit>()
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

        fun load(url: String) {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = DESKTOP_USER_AGENT
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (loaded.isActive) loaded.complete(Unit)
                }
            }
            webView.loadUrl(url)
        }

        suspend fun awaitReady() = withTimeout(PAGE_LOAD_TIMEOUT_MS) { loaded.await() }

        /** evaluateJavascript 只能在主线程调；返回值是 JSON 编码过的字符串。 */
        suspend fun evaluate(script: String): String = withContext(Dispatchers.Main) {
            if (destroyed) throw IllegalStateException("qq 登录页已关闭")
            val result = CompletableDeferred<String>()
            webView.evaluateJavascript(script) { value -> result.complete(value ?: "null") }
            withTimeout(EVAL_TIMEOUT_MS) { result.await() }
        }

        /** 读页面状态的兜底版本：页面正在导航之类的异常只当没读到，不往上传。 */
        suspend fun evaluateQuietly(script: String): String =
            runCatching { evaluate(script) }.getOrNull()?.let(::unquoteJsString).orEmpty()

        fun cookieHeader(url: String): String =
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()

        fun hasLoginCookie(): Boolean =
            cookieHeader("https://xui.ptlogin2.qq.com/").contains("p_skey=") ||
                cookieHeader("https://graph.qq.com/").contains("p_skey=")

        fun harvestCookies(urls: List<String>): String =
            urls.mapNotNull { cookieHeader(it).takeIf { header -> header.isNotBlank() } }
                .joinToString("; ")

        fun destroy() {
            if (destroyed) return
            destroyed = true
            if (loaded.isActive) loaded.cancel()
            val detach: () -> Unit = {
                runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
                runCatching { webView.destroy() }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) detach() else mainHandler.post(detach)
        }
    }

    /** evaluateJavascript 回的是 JSON 字符串，剥掉引号和转义才是真值。 */
    private fun unquoteJsString(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("\"") || !trimmed.endsWith("\"")) return ""
        return trimmed.substring(1, trimmed.length - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
    }
}
