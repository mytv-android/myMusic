package com.mytv.mymusic.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

enum class SyncFailureKind { Auth, Permission, NotFound, Network, Corrupt, Unknown }

class SyncException(val kind: SyncFailureKind, override val message: String) : Exception(message)

data class RemoteAccount(val id: String, val login: String)

data class RemoteFile(
    val id: String,
    val content: String,
    val revision: String,
    val updatedAt: Long,
    val htmlUrl: String,
)

interface PlaylistSyncProvider {
    val id: String
    val label: String
    val fileName: String

    suspend fun validate(token: String): RemoteAccount
    suspend fun read(token: String, remoteId: String): RemoteFile
    suspend fun create(token: String, content: String): RemoteFile
    suspend fun update(token: String, remoteId: String, content: String): RemoteFile
}

internal fun syncHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(40, TimeUnit.SECONDS)
    .writeTimeout(40, TimeUnit.SECONDS)
    .build()

private val jsonMedia = "application/json; charset=utf-8".toMediaType()
private val json = Json { ignoreUnknownKeys = true }

internal fun extractRemoteId(input: String): String {
    val value = input.trim().trimEnd('/')
    if (value.isBlank()) return ""
    val noQuery = value.substringBefore('?').substringBefore('#')
    return noQuery.substringAfterLast('/').ifBlank { noQuery }
}

private fun parseIso(value: String?): Long =
    runCatching { Instant.parse(value.orEmpty()).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

private fun Request.Builder.bearer(token: String) = apply {
    header("Authorization", "Bearer $token")
    header("Accept", "application/vnd.github+json")
    header("X-GitHub-Api-Version", "2022-11-28")
    header("User-Agent", "myMusic-Android")
}

class GistSyncProvider(private val http: OkHttpClient = syncHttpClient()) : PlaylistSyncProvider {
    override val id = "gist"
    override val label = "GitHub Gist"
    override val fileName = "mymusic-library.json"
    private val api = "https://api.github.com".toHttpUrl()

    override suspend fun validate(token: String): RemoteAccount = call {
        val request = Request.Builder().url(api.newBuilder().addPathSegment("user").build()).bearer(token).build()
        val body = execute(request)
        RemoteAccount(
            id = body["id"]?.jsonPrimitive?.content.orEmpty(),
            login = body["login"]?.jsonPrimitive?.content.orEmpty(),
        ).also {
            if (it.login.isBlank()) throw SyncException(SyncFailureKind.Auth, "令牌无法读取 GitHub 账号")
        }
    }

    override suspend fun read(token: String, remoteId: String): RemoteFile = call {
        val request = Request.Builder().url(gistUrl(remoteId)).bearer(token).build()
        parseFile(execute(request))
    }

    override suspend fun create(token: String, content: String): RemoteFile = call {
        val payload = buildJsonObject {
            put("description", "myMusic 自建歌单同步")
            put("public", false)
            put(
                "files",
                buildJsonObject { put(fileName, buildJsonObject { put("content", content) }) },
            )
        }
        val request = Request.Builder()
            .url(api.newBuilder().addPathSegment("gists").build())
            .bearer(token)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        parseFile(execute(request))
    }

    override suspend fun update(token: String, remoteId: String, content: String): RemoteFile = call {
        val payload = buildJsonObject {
            put("files", buildJsonObject { put(fileName, buildJsonObject { put("content", content) }) })
        }
        val request = Request.Builder()
            .url(gistUrl(remoteId))
            .bearer(token)
            .patch(payload.toString().toRequestBody(jsonMedia))
            .build()
        parseFile(execute(request))
    }

    private fun gistUrl(remoteId: String) = api.newBuilder().addPathSegment("gists").addPathSegment(remoteId).build()

    private fun parseFile(root: JsonObject): RemoteFile {
        val file = root["files"]?.jsonObject?.get(fileName)?.jsonObject
            ?: throw SyncException(SyncFailureKind.Corrupt, "云端 Gist 里没有 $fileName，无法同步。")
        val content = file["content"]?.jsonPrimitive?.content
            ?: throw SyncException(SyncFailureKind.Corrupt, "云端文件内容无法读取。")
        val revision = runCatching {
            root["history"]?.jsonArray?.firstOrNull()?.jsonObject?.get("version")?.jsonPrimitive?.content
        }.getOrNull().orEmpty()
            .ifBlank { root["updated_at"]?.jsonPrimitive?.content.orEmpty() }
        return RemoteFile(
            id = root["id"]?.jsonPrimitive?.content.orEmpty(),
            content = content,
            revision = revision,
            updatedAt = parseIso(root["updated_at"]?.jsonPrimitive?.content),
            htmlUrl = root["html_url"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                val raw = response.body.string()
                if (!response.isSuccessful) throw httpFailure(response.code, raw)
                runCatching { Json.parseToJsonElement(raw).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
            }
        } catch (error: SyncException) {
            throw error
        } catch (error: IOException) {
            throw SyncException(SyncFailureKind.Network, "网络不可达：${error.message ?: "请检查连接"}")
        }
    }
}

class GiteeSyncProvider(private val http: OkHttpClient = syncHttpClient()) : PlaylistSyncProvider {
    override val id = "gitee"
    override val label = "Gitee 代码片段"
    override val fileName = "mymusic-library.json"
    private val api = "https://gitee.com/api/v5".toHttpUrl()

    override suspend fun validate(token: String): RemoteAccount = call {
        val url = api.newBuilder().addPathSegment("user").addQueryParameter("access_token", token).build()
        val root = execute(Request.Builder().url(url).get().header("User-Agent", "myMusic-Android").build())
        RemoteAccount(
            id = root["id"]?.jsonPrimitive?.content.orEmpty(),
            login = root["login"]?.jsonPrimitive?.content.orEmpty(),
        ).also {
            if (it.login.isBlank()) throw SyncException(SyncFailureKind.Auth, "令牌无法读取 Gitee 账号")
        }
    }

    override suspend fun read(token: String, remoteId: String): RemoteFile = call {
        val url = api.newBuilder()
            .addPathSegment("gists").addPathSegment(remoteId)
            .addQueryParameter("access_token", token)
            .build()
        parseFile(execute(Request.Builder().url(url).get().header("User-Agent", "myMusic-Android").build()))
    }

    override suspend fun create(token: String, content: String): RemoteFile = call {
        val payload = buildJsonObject {
            put("access_token", token)
            put("description", "myMusic 自建歌单同步")
            put("public", false)
            put(
                "files",
                buildJsonObject {
                    put(fileName, buildJsonObject {
                        put("content", content)
                        put("type", "application/json")
                    })
                },
            )
        }
        val url = api.newBuilder().addPathSegment("gists").build()
        parseFile(
            execute(
                Request.Builder().url(url)
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .header("User-Agent", "myMusic-Android")
                    .build()
            )
        )
    }

    override suspend fun update(token: String, remoteId: String, content: String): RemoteFile = call {
        val payload = buildJsonObject {
            put("access_token", token)
            put("description", "myMusic 自建歌单同步")
            put(
                "files",
                buildJsonObject {
                    put(fileName, buildJsonObject {
                        put("content", content)
                        put("type", "application/json")
                    })
                },
            )
        }
        val url = api.newBuilder().addPathSegment("gists").addPathSegment(remoteId).build()
        parseFile(
            execute(
                Request.Builder().url(url)
                    .patch(payload.toString().toRequestBody(jsonMedia))
                    .header("User-Agent", "myMusic-Android")
                    .build()
            )
        )
    }

    private fun parseFile(root: JsonObject): RemoteFile {
        val file = root["files"]?.jsonObject?.get(fileName)?.jsonObject
            ?: throw SyncException(SyncFailureKind.Corrupt, "云端代码片段里没有 $fileName，无法同步。")
        val content = file["content"]?.jsonPrimitive?.content
            ?: throw SyncException(SyncFailureKind.Corrupt, "云端文件内容无法读取。")
        return RemoteFile(
            id = root["id"]?.jsonPrimitive?.content.orEmpty(),
            content = content,
            revision = root["updated_at"]?.jsonPrimitive?.content ?: "",
            updatedAt = parseIso(root["updated_at"]?.jsonPrimitive?.content),
            htmlUrl = root["html_url"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                val raw = response.body.string()
                if (!response.isSuccessful) throw httpFailure(response.code, raw)
                runCatching { Json.parseToJsonElement(raw).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
            }
        } catch (error: SyncException) {
            throw error
        } catch (error: IOException) {
            throw SyncException(SyncFailureKind.Network, "网络不可达：${error.message ?: "请检查连接"}")
        }
    }
}

private fun httpFailure(code: Int, raw: String): SyncException {
    val detail = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        root["message"]?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("")
    return when (code) {
        401 -> SyncException(SyncFailureKind.Auth, "令牌无效或已过期，请重新生成。")
        403 -> SyncException(SyncFailureKind.Permission, "令牌权限不足，请至少授予代码片段 / Gist 权限。")
        404 -> SyncException(SyncFailureKind.NotFound, "找不到云端文件，请确认链接或重新连接。")
        else -> SyncException(
            SyncFailureKind.Unknown,
            if (detail.isBlank()) "同步服务返回错误（HTTP $code）" else "同步服务返回错误：$detail",
        )
    }
}

private inline fun <T> call(block: () -> T): T = try {
    block()
} catch (error: SyncException) {
    throw error
} catch (error: Exception) {
    throw SyncException(
        SyncFailureKind.Unknown,
        error.message?.takeIf { it.isNotBlank() } ?: "同步失败（${error.javaClass.simpleName}）",
    )
}
