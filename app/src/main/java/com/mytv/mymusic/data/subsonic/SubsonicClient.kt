package com.mytv.mymusic.data.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class SubsonicException(
    val code: Int,
    val serverMessage: String,
    val helpUrl: String = "",
) : IOException(humanSubsonicError(code, serverMessage))

data class SubsonicConnection(
    val account: SubsonicAccount,
    val serverLabel: String,
)

class SubsonicClient(
    initialAccount: SubsonicAccount,
    internal val httpClient: OkHttpClient = defaultHttpClient(),
    private val onAccountChanged: (SubsonicAccount) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
) {
    internal val mediaHttpClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .addInterceptor(::mediaAuthentication)
        .build()

    @Volatile
    var account: SubsonicAccount = initialAccount.normalized()
        private set

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val authParameterNames = listOf("u", "p", "t", "s", "apiKey", "jwt")

    internal fun adopt(updated: SubsonicAccount) {
        require(sameSubsonicCredentials(account, updated)) { "不能复用到另一组服务器凭据" }
        account = updated.normalized()
    }

    suspend fun request(
        endpoint: String,
        params: List<Pair<String, String>> = emptyList(),
        authenticate: Boolean = true,
        post: Boolean = false,
    ): JsonObject = withContext(Dispatchers.IO) {
        val mode = effectiveAuthMode()
        try {
            execute(endpoint, params, authenticate, post, mode)
        } catch (error: SubsonicException) {
            if (!authenticate || mode != SubsonicAuthMode.Token || error.code !in setOf(40, 41) || account.usesApiKey) {
                throw error
            }
            val result = execute(endpoint, params, authenticate, post, SubsonicAuthMode.Password)
            updateAccount(account.copy(authMode = SubsonicAuthMode.Password))
            result
        }
    }

    suspend fun bytes(
        endpoint: String,
        params: List<Pair<String, String>> = emptyList(),
    ): ByteArray = withContext(Dispatchers.IO) {
        val mode = effectiveAuthMode()
        try {
            executeBytes(endpoint, params, mode)
        } catch (error: SubsonicException) {
            if (mode != SubsonicAuthMode.Token || error.code !in setOf(40, 41) || account.usesApiKey) throw error
            val result = executeBytes(endpoint, params, SubsonicAuthMode.Password)
            updateAccount(account.copy(authMode = SubsonicAuthMode.Password))
            result
        }
    }

    suspend fun testConnection(): SubsonicConnection {
        val ping = request("ping")
        val extensions = if (ping.bool("openSubsonic")) {
            runCatching {
                request("getOpenSubsonicExtensions", authenticate = false)
                    .array("openSubsonicExtensions")
                    .mapNotNull { (it as? JsonObject)?.string("name") }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val user = runCatching {
            request("getUser", listOf("username" to account.username)).obj("user")
        }.getOrNull()
        val updated = account.copy(
            authMode = effectiveAuthMode(),
            serverType = ping.string("type"),
            serverVersion = ping.string("serverVersion"),
            apiVersion = ping.string("version"),
            openSubsonic = ping.bool("openSubsonic"),
            extensions = extensions,
            streamRole = user?.bool("streamRole"),
            downloadRole = user?.bool("downloadRole"),
            playlistRole = user?.bool("playlistRole"),
            scrobblingEnabled = user?.bool("scrobblingEnabled"),
        )
        updateAccount(updated)
        val type = updated.serverType.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .ifBlank { "Subsonic" }
        return SubsonicConnection(
            account = updated,
            serverLabel = listOf(type, updated.serverVersion).filter { it.isNotBlank() }.joinToString(" · "),
        )
    }

    fun streamUrl(songId: String): String = endpointUrl(
        "stream",
        listOf("id" to songId, "format" to "raw", SUBSONIC_ROUTE_ACCOUNT to account.id),
    )

    fun downloadUrl(songId: String): String = authenticatedUrl(
        "download",
        listOf("id" to songId, SUBSONIC_ROUTE_ACCOUNT to account.id),
        includeJsonFormat = true,
    )

    fun authenticatedUrl(
        endpoint: String,
        params: List<Pair<String, String>> = emptyList(),
        includeJsonFormat: Boolean = true,
        mode: SubsonicAuthMode = effectiveAuthMode(),
    ): String {
        val builder = endpointBuilder(endpoint)
        commonParameters(includeJsonFormat).forEach { (key, value) -> builder.addQueryParameter(key, value) }
        authenticationParameters(mode).forEach { (key, value) -> builder.addQueryParameter(key, value) }
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    fun authorize(url: HttpUrl, mode: SubsonicAuthMode = effectiveAuthMode()): HttpUrl {
        val builder = url.newBuilder()
        authParameterNames.forEach(builder::removeAllQueryParameters)
        builder.removeAllQueryParameters(SUBSONIC_ROUTE_ACCOUNT)
        commonParameters(includeJsonFormat = true).forEach { (key, value) ->
            builder.removeAllQueryParameters(key)
            builder.addQueryParameter(key, value)
        }
        authenticationParameters(mode).forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    fun matches(url: HttpUrl): Boolean {
        val base = account.baseUrl ?: return false
        if (url.scheme != base.scheme || !url.host.equals(base.host, true) || url.port != base.port) return false
        val prefix = base.encodedPath.let { if (it.endsWith('/')) it else "$it/" }
        return url.encodedPath.startsWith(prefix)
    }

    fun endpointUrl(endpoint: String, params: List<Pair<String, String>> = emptyList()): String =
        endpointBuilder(endpoint).apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build().toString()

    suspend fun openMedia(url: HttpUrl): Response = withContext(Dispatchers.IO) {
        require(matches(url)) { "媒体地址不属于这个服务器" }
        val clean = url.newBuilder().removeAllQueryParameters(SUBSONIC_ROUTE_ACCOUNT).build()
        val response = mediaHttpClient.newCall(Request.Builder().url(clean).get().build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("服务器返回 HTTP ${response.code} ${response.message}")
        }
        val (isEnvelope, error) = response.subsonicEnvelope()
        if (isEnvelope) {
            response.close()
            throw error ?: IOException("服务器没有返回音频")
        }
        response
    }

    private fun executeBytes(
        endpoint: String,
        params: List<Pair<String, String>>,
        mode: SubsonicAuthMode,
    ): ByteArray {
        val url = authenticatedUrl(endpoint, params, includeJsonFormat = true, mode = mode)
        val response = httpClient.newCall(Request.Builder().url(url).get().build()).execute()
        return response.use {
            if (!it.isSuccessful) throw IOException("服务器返回 HTTP ${it.code} ${it.message}")
            val bytes = it.body.bytes()
            subsonicException(bytes)?.let { error -> throw error }
            bytes
        }
    }

    private fun mediaAuthentication(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val first = chain.proceed(request)
        val (_, error) = first.subsonicEnvelope()
        if (error != null && (request.url.queryParameter("t") == null || account.usesApiKey)) {
            first.close()
            throw error
        }
        if (request.url.queryParameter("t") == null || account.usesApiKey) return first
        if (error != null && error.code !in setOf(40, 41)) {
            first.close()
            throw error
        }
        if (error == null) return first
        first.close()
        val retry = request.newBuilder().url(authorize(request.url, SubsonicAuthMode.Password)).build()
        val second = chain.proceed(retry)
        val (secondEnvelope, secondError) = second.subsonicEnvelope()
        if (secondError != null) {
            second.close()
            throw secondError
        }
        if (second.isSuccessful && !secondEnvelope) {
            updateAccount(account.copy(authMode = SubsonicAuthMode.Password))
        }
        return second
    }

    private fun Response.subsonicEnvelope(): Pair<Boolean, SubsonicException?> {
        val type = header("Content-Type").orEmpty()
        if (type.startsWith("audio/") || type.startsWith("application/octet-stream")) return false to null
        val preview = peekBody(4096).string()
        val envelope = preview.trimStart().startsWith("{") && preview.contains("subsonic-response")
        return envelope to if (envelope) subsonicException(preview) else null
    }

    private fun execute(
        endpoint: String,
        params: List<Pair<String, String>>,
        authenticate: Boolean,
        post: Boolean,
        mode: SubsonicAuthMode,
    ): JsonObject {
        val common = commonParameters(includeJsonFormat = true).toMutableList()
        if (authenticate) common += authenticationParameters(mode)
        common += params
        val builder = endpointBuilder(endpoint)
        val request = if (post) {
            val body = FormBody.Builder().apply { common.forEach { (key, value) -> add(key, value) } }.build()
            Request.Builder().url(builder.build()).post(body).build()
        } else {
            common.forEach { (key, value) -> builder.addQueryParameter(key, value) }
            Request.Builder().url(builder.build()).get().build()
        }
        val response = httpClient.newCall(request).execute()
        val body = response.use { it.body.string() }
        if (!response.isSuccessful) throw IOException("服务器返回 HTTP ${response.code} ${response.message}")
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw IOException("服务器没有返回有效的 Subsonic 响应")
        }
        val envelope = (root["subsonic-response"] as? JsonObject) ?: root
        val error = envelope["error"] as? JsonObject
        if (envelope.string("status") == "failed" || error != null) {
            throw SubsonicException(
                code = error?.int("code") ?: 0,
                serverMessage = error?.string("message").orEmpty().ifBlank { "未知错误" },
                helpUrl = error?.string("helpUrl").orEmpty(),
            )
        }
        return envelope
    }

    private fun endpointBuilder(endpoint: String): HttpUrl.Builder {
        val base = account.baseUrl ?: throw IllegalArgumentException("服务器地址无效")
        return base.newBuilder().addPathSegments("rest/${endpoint.removeSuffix(".view")}.view")
    }

    private fun commonParameters(includeJsonFormat: Boolean): List<Pair<String, String>> = buildList {
        add("v" to "1.16.1")
        add("c" to "myMusic")
        if (includeJsonFormat) add("f" to "json")
    }

    private fun effectiveAuthMode(): SubsonicAuthMode = when {
        account.usesApiKey -> SubsonicAuthMode.ApiKey
        account.authMode == SubsonicAuthMode.ApiKey -> SubsonicAuthMode.Token
        else -> account.authMode
    }

    internal fun authenticationParameters(mode: SubsonicAuthMode = effectiveAuthMode()): List<Pair<String, String>> = when (mode) {
        SubsonicAuthMode.ApiKey -> listOf("apiKey" to account.password)
        SubsonicAuthMode.Password -> listOf(
            "u" to account.username,
            "p" to "enc:" + account.password.toByteArray(Charsets.UTF_8).toHex(),
        )
        SubsonicAuthMode.Token -> {
            val salt = ByteArray(12).also(random::nextBytes).toHex()
            listOf(
                "u" to account.username,
                "t" to md5(account.password + salt),
                "s" to salt,
            )
        }
    }

    private fun updateAccount(value: SubsonicAccount) {
        account = value.normalized()
        onAccountChanged(account)
    }
}

internal fun JsonObject.string(key: String): String = when (val value = this[key]) {
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    else -> ""
}

internal fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull
    ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

internal fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull
    ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull
    ?: (this[key] as? JsonPrimitive)?.contentOrNull?.equals("true", true) == true

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): List<kotlinx.serialization.json.JsonElement> = when (val value = this[key]) {
    is JsonArray -> value
    is JsonObject -> listOf(value)
    else -> emptyList()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).toHex()

private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .build()

fun subsonicException(body: String): SubsonicException? {
    val envelope = runCatching {
        accountJson.parseToJsonElement(body).jsonObject.let { root ->
            (root["subsonic-response"] as? JsonObject) ?: root
        }
    }.getOrNull() ?: return null
    if (envelope.string("status") != "failed") return null
    val error = envelope.obj("error")
    return SubsonicException(
        code = error?.int("code") ?: 0,
        serverMessage = error?.string("message").orEmpty().ifBlank { "未知错误" },
        helpUrl = error?.string("helpUrl").orEmpty(),
    )
}

private fun subsonicException(bytes: ByteArray): SubsonicException? {
    val prefix = bytes.take(512).toByteArray().toString(Charsets.UTF_8)
    if (!prefix.trimStart().startsWith("{") || !prefix.contains("subsonic-response")) return null
    return subsonicException(bytes.toString(Charsets.UTF_8))
}

private fun humanSubsonicError(code: Int, message: String): String {
    val prefix = when (code) {
        20 -> "服务器要求更新客户端"
        30 -> "服务器版本太旧"
        40 -> "用户名或密码不正确；也可能是登录尝试过多被临时限流，请等待 20 秒后再试"
        41 -> "服务器不接受 token 认证"
        42 -> "服务器已停用这种认证方式"
        43 -> "认证参数冲突"
        44 -> "API key 无效，请重新生成"
        50 -> "服务器没有给这个账号开放该功能"
        70 -> "服务器上已找不到这项内容"
        else -> "服务器拒绝了请求"
    }
    return if (message.isBlank() || message in prefix) prefix else "$prefix：$message"
}
