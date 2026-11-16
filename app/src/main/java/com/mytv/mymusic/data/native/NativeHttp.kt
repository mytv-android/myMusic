package com.mytv.mymusic.data.native

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class NativeResponse(
    val body: String,
    val cookies: Map<String, String>,
    val code: Int,
    /** 只有 binary 请求才填，例如登录二维码图片。 */
    val bytes: ByteArray = ByteArray(0),
    /** 跟随跳转后的最终地址，用于短链还原；没有发生跳转时与请求地址相同。 */
    val finalUrl: String = "",
    /** 302 的 Location 原值；只有传了 followRedirects = false 的请求才可能有值。 */
    val location: String = "",
)

object NativeHttp {
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 不跟随跳转的客户端：OkHttp 自动跟随只把最终响应交出来，中间某一跳种下的 Set-Cookie 会丢掉
     * （QQ 扫码登录的 p_skey 就是这样丢的），需要自己顺着 Location 走时用这个。
     */
    private val noRedirectClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
    ): String = getResponse(url, headers, cookie, randomIp, userAgent).body

    suspend fun getResponse(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
        binary: Boolean = false,
        followRedirects: Boolean = true,
    ): NativeResponse {
        val request = Request.Builder()
            .url(url)
            .get()
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request, binary, followRedirects)
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
        followRedirects: Boolean = true,
    ): NativeResponse {
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request, followRedirects = followRedirects)
    }

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
    ): NativeResponse {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request)
    }

    suspend fun postBytes(
        url: String,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
        contentType: String = "application/octet-stream",
    ): NativeResponse {
        val request = Request.Builder()
            .url(url)
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request)
    }

    private fun Request.Builder.applyCommonHeaders(
        headers: Map<String, String>,
        cookie: String,
        randomIp: Boolean,
        userAgent: String,
    ): Request.Builder {
        header("User-Agent", userAgent)
        if (cookie.isNotBlank()) header("Cookie", cookie)
        headers.forEach { (key, value) ->
            if (!key.equals("Content-Type", ignoreCase = true) && !key.equals("User-Agent", ignoreCase = true)) {
                header(key, value)
            }
        }
        if (randomIp) {
            val ip = NativeCrypto.randomChinaIp()
            header("X-Forwarded-For", ip)
            header("X-Real-IP", ip)
        }
        return this
    }

    /** POST 二进制请求体，响应也按原始字节返回（汽水/酷狗这类加密响应）。 */
    suspend fun postBinary(
        url: String,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
        contentType: String = "application/json;charset=utf-8",
    ): NativeResponse {
        val request = Request.Builder()
            .url(url)
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request, binary = true)
    }

    private suspend fun execute(request: Request, binary: Boolean = false, followRedirects: Boolean = true): NativeResponse =
        suspendCancellableCoroutine { continuation ->
            val call = (if (followRedirects) client else noRedirectClient).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val bytes = if (binary) runCatching { it.body?.bytes() }.getOrNull() ?: ByteArray(0) else ByteArray(0)
                        val body = if (binary) "" else runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                        // 手动跟随跳转时 3xx 是正常回包：Location 才是要读的东西
                        val isRedirect = !followRedirects && it.code in 300..399
                        if (!it.isSuccessful && !isRedirect) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException("HTTP ${it.code} ${it.message}: ${body.take(300)}")
                                )
                            }
                            return
                        }
                        val cookies = buildMap {
                            it.headers.values("Set-Cookie").forEach { header ->
                                val pair = header.substringBefore(';').trim()
                                val index = pair.indexOf('=')
                                if (index > 0) {
                                    put(pair.substring(0, index).trim(), pair.substring(index + 1).trim())
                                }
                            }
                        }
                        if (continuation.isActive) {
                            continuation.resume(
                                NativeResponse(
                                    body = body,
                                    cookies = cookies,
                                    code = it.code,
                                    bytes = bytes,
                                    finalUrl = it.request.url.toString(),
                                    location = it.header("Location").orEmpty(),
                                )
                            )
                        }
                    }
                }
            })
        }
}
