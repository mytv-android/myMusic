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
    ): NativeResponse {
        val request = Request.Builder()
            .url(url)
            .get()
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request)
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        cookie: String = "",
        randomIp: Boolean = false,
        userAgent: String = DEFAULT_USER_AGENT,
    ): NativeResponse {
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .applyCommonHeaders(headers, cookie, randomIp, userAgent)
            .build()
        return execute(request)
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

    private suspend fun execute(request: Request): NativeResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                        if (!it.isSuccessful) {
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
                            continuation.resume(NativeResponse(body, cookies, it.code))
                        }
                    }
                }
            })
        }
}
