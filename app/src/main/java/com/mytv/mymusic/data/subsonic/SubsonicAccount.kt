package com.mytv.mymusic.data.subsonic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

const val SUBSONIC_PROVIDER_PREFIX = "subsonic:"
const val SUBSONIC_ROUTE_ACCOUNT = "mymusic_account"

@Serializable
enum class SubsonicAuthMode { Token, Password, ApiKey }

@Serializable
data class SubsonicAccount(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val authMode: SubsonicAuthMode = SubsonicAuthMode.Token,
    val serverType: String = "",
    val serverVersion: String = "",
    val apiVersion: String = "",
    val openSubsonic: Boolean = false,
    val extensions: List<String> = emptyList(),
    val streamRole: Boolean? = null,
    val downloadRole: Boolean? = null,
    val playlistRole: Boolean? = null,
    val scrobblingEnabled: Boolean? = null,
) {
    val providerId: String get() = SUBSONIC_PROVIDER_PREFIX + id
    val displayName: String get() = name.trim().ifBlank { baseUrl?.host ?: "Subsonic" }
    val baseUrl: HttpUrl? get() = normalizeServerUrl(url)
    val usesApiKey: Boolean get() = password.startsWith(API_KEY_PREFIX)

    fun normalized(): SubsonicAccount = copy(
        name = name.trim(),
        url = baseUrl?.toString()?.trimEnd('/') ?: url.trim().trimEnd('/'),
        username = username.trim(),
        password = password,
        authMode = when {
            password.startsWith(API_KEY_PREFIX) -> SubsonicAuthMode.ApiKey
            authMode == SubsonicAuthMode.ApiKey -> SubsonicAuthMode.Token
            else -> authMode
        },
    )

    fun encode(): String = accountJson.encodeToString(serializer(), normalized())

    companion object {
        const val API_KEY_PREFIX = "nds_"

        fun decode(raw: String?): SubsonicAccount? = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { value -> runCatching { accountJson.decodeFromString(serializer(), value).normalized() }.getOrNull() }
            ?.takeIf { it.id.isNotBlank() && it.baseUrl != null && it.password.isNotBlank() }

        fun fromCookies(cookies: Map<String, String>): List<SubsonicAccount> = cookies.mapNotNull { (key, raw) ->
            if (!key.startsWith(SUBSONIC_PROVIDER_PREFIX)) return@mapNotNull null
            decode(raw)?.let { account ->
                val keyId = key.removePrefix(SUBSONIC_PROVIDER_PREFIX)
                if (account.id == keyId) account else account.copy(id = keyId)
            }
        }.sortedBy { it.displayName.lowercase() }
    }
}

fun normalizeServerUrl(raw: String): HttpUrl? {
    var value = raw.trim()
    if (value.isBlank()) return null
    if (!value.contains("://")) value = "https://$value"
    val parsed = value.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http" && parsed.scheme != "https") return null
    if (parsed.host.isBlank() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    val path = parsed.encodedPath.trimEnd('/')
    val restPath = when {
        path.endsWith("/rest") -> path.removeSuffix("/rest")
        path.endsWith("/rest/ping.view") -> path.removeSuffix("/rest/ping.view")
        else -> path
    }
    return parsed.newBuilder()
        .encodedPath(if (restPath.isBlank()) "/" else "$restPath/")
        .query(null)
        .fragment(null)
        .build()
}

internal fun sameSubsonicLibrary(a: SubsonicAccount, b: SubsonicAccount): Boolean =
    a.id == b.id && a.normalized().url == b.normalized().url && a.username == b.username

internal fun sameSubsonicCredentials(a: SubsonicAccount, b: SubsonicAccount): Boolean =
    sameSubsonicLibrary(a, b) && a.password == b.password

internal fun mergeSubsonicProbe(current: SubsonicAccount, probed: SubsonicAccount): SubsonicAccount = current.copy(
    authMode = probed.authMode,
    serverType = probed.serverType,
    serverVersion = probed.serverVersion,
    apiVersion = probed.apiVersion,
    openSubsonic = probed.openSubsonic,
    extensions = probed.extensions,
    streamRole = probed.streamRole,
    downloadRole = probed.downloadRole,
    playlistRole = probed.playlistRole,
    scrobblingEnabled = probed.scrobblingEnabled,
)

internal val accountJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
