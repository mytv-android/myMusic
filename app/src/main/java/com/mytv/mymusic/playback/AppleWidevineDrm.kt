package com.mytv.mymusic.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import com.mytv.mymusic.data.native.AppleTokenCache
import com.mytv.mymusic.data.native.NativeHttp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Apple Music 整曲的 Widevine 授权。
 *
 * 背景：`enhancedHls` 整曲清单里每条 `#EXT-X-KEY` 都是 FairPlay / PlayReady / Widevine
 * 三重 SAMPLE-AES。安卓上只有 Widevine 走得通（FairPlay 的 SDK 不对外发放，PlayReady 手机
 * 基本不预装），而 Media3 恰好原生支持 Widevine——所以把 DRM 交给设备自己的 CDM，
 * 不需要任何 `.wvd` 之类的设备私钥。
 *
 * 授权流程（对齐 gamdl 的 `get_license_exchange`）：
 *  1. Media3 的 Widevine CDM 拿着清单里的 PSSH 自己生成 challenge；
 *  2. 这里把 challenge 套进 Apple 的 JSON 信封 POST 到固定端点；
 *  3. 响应里的 `license` 字段是 base64，解出来喂回 CDM。
 *
 * 所以 [MediaItem.DrmConfiguration][androidx.media3.common.MediaItem.DrmConfiguration] 的
 * licenseUri 不是真的请求地址，只是搭车把 `adamId` / `pssh` 带过来的载体——真正的端点是
 * [LICENSE_ENDPOINT]，请求体也不符合 `HttpMediaDrmCallback` 的裸字节约定（那边裸 POST
 * challenge、期望裸 license 回来），必须自己包一层 JSON。
 */
@UnstableApi
class AppleWidevineDrmCallback(
    /** 取当前 Apple 登录 Cookie，里面要有 `media-user-token`；为空就是没登录。 */
    private val cookieProvider: () -> String,
) : MediaDrmCallback {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
        if (uuid != C.WIDEVINE_UUID) {
            throw failure(uuid, IllegalStateException("只接了 Widevine，收到 $uuid"))
        }
        val params = parseQuery(request.licenseServerUrl.orEmpty())
        val adamId = params["adamId"].orEmpty()
        val pssh = params["pssh"].orEmpty()
        if (adamId.isBlank() || pssh.isBlank()) {
            throw failure(uuid, IllegalStateException("授权参数缺失: ${request.licenseServerUrl}"))
        }
        val mediaUserToken = mediaUserToken()

        val challenge = android.util.Base64.encodeToString(request.data, android.util.Base64.NO_WRAP)
        val payload = Json.encodeToString(
            AppleLicenseRequest.serializer(),
            AppleLicenseRequest(
                challenge = challenge,
                keySystem = KEY_SYSTEM,
                uri = pssh,
                adamId = adamId,
                isLibrary = params["library"] == "1",
                userInitiated = true,
            ),
        )
        val httpRequest = Request.Builder()
            .url(LICENSE_ENDPOINT)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Origin", APPLE_WEB_ORIGIN)
            .header("Referer", "$APPLE_WEB_ORIGIN/")
            .header("User-Agent", NativeHttp.DEFAULT_USER_AGENT)
            .apply {
                // gamdl 用的是同一个 httpx client，所以授权请求和 amp-api 一样带着
                // developer token + Cookie 里的 media-user-token
                AppleTokenCache.read()?.let { header("Authorization", "Bearer $it") }
                if (mediaUserToken.isNotBlank()) {
                    header("media-user-token", mediaUserToken)
                    header("Cookie", "media-user-token=$mediaUserToken")
                }
            }
            .build()
        val body = runCatching {
            client.newCall(httpRequest).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("授权失败 HTTP ${response.code}: ${text.take(200)}")
                }
                text
            }
        }.getOrElse { throw failure(uuid, it) }

        val parsed = runCatching { Json.decodeFromString(AppleLicenseResponse.serializer(), body) }.getOrNull()
        // Apple 的 failureType 2002 是「会话过期，重新登录」，原样带在消息里好排查
        val license = parsed?.license.orEmpty()
        if (license.isBlank()) {
            val detail = "${parsed?.failureType.orEmpty()} ${parsed?.customerMessage.orEmpty()}".trim()
            throw failure(uuid, IllegalStateException("授权没给 license${if (detail.isBlank()) "" else "（$detail）"}: ${body.take(200)}"))
        }
        val bytes = runCatching { android.util.Base64.decode(license, android.util.Base64.DEFAULT) }
            .getOrElse { throw failure(uuid, it) }
        return MediaDrmCallback.Response(bytes)
    }

    /**
     * Widevine 在安卓上出厂就是已 provisioning 的，走不到这儿；真走到了说明设备异常，
     * 直接报错比静默重试好。
     */
    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response =
        throw failure(uuid, UnsupportedOperationException("Apple Music 的 Widevine 不需要 provisioning"))

    private fun failure(uuid: UUID, cause: Throwable): MediaDrmCallbackException {
        val dataSpec = DataSpec.Builder().setUri(LICENSE_ENDPOINT).build()
        return MediaDrmCallbackException(dataSpec, Uri.EMPTY, emptyMap(), 0L, cause)
    }

    /** 从 Cookie 串里挑 `media-user-token`；用户只贴了 token 本身也认。 */
    internal fun mediaUserToken(): String {
        val cookie = cookieProvider()
        val named = cookie.split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }
            .firstOrNull { it.first == "media-user-token" }
            ?.second
            .orEmpty()
        return named.ifBlank {
            cookie.trim().takeIf { it.isNotBlank() && !it.contains('=') && !it.contains(';') }.orEmpty()
        }
    }

    @Serializable
    private data class AppleLicenseRequest(
        val challenge: String,
        @SerialName("key-system") val keySystem: String,
        /** gamdl 传的就是清单里那个 base64 PSSH，跟着来。 */
        val uri: String,
        val adamId: String,
        val isLibrary: Boolean,
        @SerialName("user-initiated") val userInitiated: Boolean,
    )

    @Serializable
    private data class AppleLicenseResponse(
        val license: String = "",
        val status: Int = 0,
        val failureType: String = "",
        val customerMessage: String = "",
    )

    companion object {
        const val LICENSE_ENDPOINT = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense"
        const val KEY_SYSTEM = "com.widevine.alpha"
        private const val APPLE_WEB_ORIGIN = "https://music.apple.com"

        /**
         * `Song.extra` 里 DRM 三件套的键。`AppleProvider.playableStream` 取流后写，
         * `MusicViewModel.mediaItem` 读出来拼 `MediaItem.DrmConfiguration`——
         * 两边就靠这几个字符串对齐。
         */
        const val EXTRA_DRM_SCHEME = "drm_scheme"
        const val EXTRA_DRM_PSSH = "drm_pssh"
        const val EXTRA_DRM_ADAM_ID = "drm_adam_id"
        const val DRM_SCHEME_WIDEVINE = "widevine"

        /**
         * 拼授权载体地址。adamId 和 PSSH 都塞进 query，回调里再从
         * [ExoMediaDrm.KeyRequest.getLicenseServerUrl] 取出来。
         */
        fun licenseUri(adamId: String, pssh: String, isLibrary: Boolean = false): String =
            "$LICENSE_ENDPOINT?adamId=${UriCompat.encode(adamId)}&pssh=${UriCompat.encode(pssh)}" +
                "&library=${if (isLibrary) "1" else "0"}"

        /** 暴露给测试和调用方：解析 query，不需要 Android 框架。 */
        internal fun parseQuery(uri: String): Map<String, String> {
            val query = uri.substringAfter('?', "")
            if (query.isBlank()) return emptyMap()
            return query.split('&').mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val index = part.indexOf('=')
                if (index < 0) null else part.substring(0, index) to UriCompat.decode(part.substring(index + 1))
            }.toMap()
        }
    }
}

/** 绕开 `android.net.Uri`，单元测试里不用 Robolectric。 */
internal object UriCompat {
    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun decode(value: String): String = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
