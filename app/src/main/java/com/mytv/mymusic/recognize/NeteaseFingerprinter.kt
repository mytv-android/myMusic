package com.mytv.mymusic.recognize

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 网易云识曲指纹：ncm-afp 的 WASM 只接受 3 秒 8kHz 单声道 Float32 PCM。
 * WASM 放在本地 asset 的 WebView 里跑，不联网；一次识别创建一个 WebView，用完销毁。
 */
class NeteaseFingerprinter(context: Context) {
    private val appContext = context.applicationContext

    suspend fun fingerprint(samples: FloatArray): String {
        require(samples.isNotEmpty()) { "empty pcm" }
        val result = CompletableDeferred<String>()
        val web = withContext(Dispatchers.Main) { createWebView(samples, result) }
        return try {
            withTimeout(15_000) { result.await() }
        } finally {
            withContext(Dispatchers.Main) { runCatching { web.destroy() } }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(samples: FloatArray, result: CompletableDeferred<String>): WebView {
        val web = WebView(appContext)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowFileAccessFromFileURLs = true
        web.settings.blockNetworkLoads = true
        web.addJavascriptInterface(Bridge(result), "NativeBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val bytes = ByteBuffer.allocate(samples.size * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply { samples.forEach { putFloat(it) } }
                    .array()
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                view.evaluateJavascript("run('$encoded')", null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    result.completeExceptionally(
                        IllegalStateException(error.description?.toString() ?: "指纹页面加载失败")
                    )
                }
            }
        }
        web.loadUrl("file:///android_asset/songrec/recognizer.html")
        return web
    }

    private class Bridge(private val result: CompletableDeferred<String>) {
        @JavascriptInterface
        fun onResult(value: String) {
            if (value.isBlank()) result.completeExceptionally(IllegalStateException("指纹为空"))
            else result.complete(value)
        }

        @JavascriptInterface
        fun onError(message: String) {
            result.completeExceptionally(IllegalStateException(message))
        }
    }
}
