package com.mytv.mymusic.playback

import android.net.Uri
import androidx.media3.common.C
import com.mytv.mymusic.AppLog
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import com.mytv.mymusic.data.native.AppleTokenCache
import com.mytv.mymusic.data.native.NativeHttp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
    /** adam ID 和 PSSH 由调用方按 MediaItem 绑好传进来，原因见下面 [executeKeyRequest]。 */
    private val adamId: String,
    private val pssh: String,
    private val isLibrary: Boolean = false,
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
        // adamId / PSSH 是构造时按 MediaItem 绑好的，不能从 request 里取——
        // ExoMediaDrm.KeyRequest.getLicenseServerUrl() 拿到的其实是 CDM 从 PSSH 解出来的
        // 默认授权地址（FrameworkMediaDrm 传的是 MediaDrm.KeyRequest.getDefaultUrl()），
        // Apple 的 PSSH 里只有 key_id、没有授权地址，所以它是空的。DrmConfiguration.licenseUri
        // 只是搭车把参数送到播放侧的载体，到不了这个回调里。
        if (adamId.isBlank() || pssh.isBlank()) {
            throw failure(uuid, IllegalStateException("授权参数缺失: adamId=${adamId.length} pssh=${pssh.length}"))
        }
        val mediaUserToken = mediaUserToken()
        AppLog.d(
            TAG,
            "授权请求 adamId=$adamId isLibrary=$isLibrary challenge=${request.data.size}B " +
                "token=${mediaUserToken.take(6)}…${mediaUserToken.takeLast(4)} len=${mediaUserToken.length}",
        )

        val challenge = android.util.Base64.encodeToString(request.data, android.util.Base64.NO_WRAP)
        val payload = Json.encodeToString(
            AppleLicenseRequest.serializer(),
            AppleLicenseRequest(
                challenge = challenge,
                keySystem = KEY_SYSTEM,
                uri = pssh,
                adamId = adamId,
                isLibrary = isLibrary,
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
                AppLog.d(TAG, "授权响应 HTTP=${response.code} len=${text.length} body=${text.take(400)}")
                if (!response.isSuccessful) {
                    throw IllegalStateException("授权失败 HTTP ${response.code}: ${text.take(200)}")
                }
                text
            }
        }.getOrElse {
            AppLog.e(TAG, "授权请求异常", it)
            throw failure(uuid, it)
        }

        // 响应里 license 之外还有别的字段，严格 schema 一碰见未知键整个解析就失败——
        // 真机上 Apple 明明回了 200 + license，却被当成"没给 license"扔掉，重试四轮全废。
        // 按 gamdl 的方式从字典里取值，其余字段一概不管。
        val license = licenseField(body, "license")
        if (license.isBlank()) {
            // Apple 的 failureType 2002 是「会话过期，重新登录」，原样带在消息里好排查
            val detail = listOf(licenseField(body, "failureType"), licenseField(body, "customerMessage"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val message = "授权没给 license${if (detail.isBlank()) "" else "（$detail）"}: ${body.take(120)}…${body.takeLast(80)}"
            AppLog.e(TAG, message)
            throw failure(uuid, IllegalStateException(message))
        }
        val bytes = runCatching { android.util.Base64.decode(license, android.util.Base64.DEFAULT) }
            .getOrElse {
                AppLog.e(TAG, "license base64 解不开", it)
                throw failure(uuid, it)
            }
        AppLog.i(TAG, "授权成功，license ${bytes.size}B kid=${licenseKeyIds(bytes)}")
        return MediaDrmCallback.Response(bytes)
    }

    /**
     * 新系统的 Widevine 走远程 provisioning：CDM 会先要一份设备证书才肯发起授权。
     * Pixel 8 Pro / Android 17 实测授权回调一次都不触发——之前这里直接抛
     * UnsupportedOperationException（"出厂就 provisioning 好"的假设在新系统上不成立），
     * 症状恰好是：session manager 建了、授权请求永不发出、播放错误被吞。
     * 证书流程跟 Apple 无关（是 Google 自己的），转交给 Media3 的 Http 实现，
     * 它会把 signedRequest 发给 CDM 在请求里自带的地址。
     */
    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response {
        AppLog.w(TAG, "CDM 要求设备证书 provisioning: defaultUrl=${request.defaultUrl.orEmpty().take(80)} ${request.data?.size ?: 0}B")
        return provisionDelegate.executeProvisionRequest(uuid, request)
    }

    /** 只借用它的 provisioning 分发（Google 的证书端点）；授权请求仍走上面自己的 Apple 信封。 */
    private val provisionDelegate by lazy {
        HttpMediaDrmCallback(LICENSE_ENDPOINT, DefaultHttpDataSource.Factory())
    }

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

    companion object {
        private const val TAG = "AppleDrm"

        /**
         * 设备上有安全解码器就用它。
         *
         * L1（硬件 TEE）CDM 解出来的明文只肯交给安全解码器；如果 Media3 选了普通解码器，
         * 真机上的症状就是 `requested key has not been loaded: ERROR_DRM_NO_LICENSE`（6006）
         * 和 `Decoder failed`（4003）——密钥明明装上了，解码器却拿不到。模拟器是 L3、没有
         * 安全解码器，所以一直复现不了。
         */
        val SECURE_IF_AVAILABLE_PROVIDER: ExoMediaDrm.Provider = object : ExoMediaDrm.Provider {
            override fun acquireExoMediaDrm(uuid: UUID): ExoMediaDrm =
                SecurePreferringMediaDrm(FrameworkMediaDrm.newInstance(uuid))
        }

        /** 设备上有没有能解 [mimeType] 的安全解码器（fMP4 + Widevine L1 的音频要靠它）。 */
        internal fun hasSecureDecoder(mimeType: String): Boolean = runCatching {
            // 走 Media3 自己的查询：安全解码器支持看的是 CodecCapabilities 的 secure 特性
            androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                .getDecoderInfos(mimeType, /* secure = */ true, /* tunneling = */ false)
                .isNotEmpty()
        }.getOrDefault(false)

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
         * 开头那段（默认 key 保的，30s 左右）的清单地址。两把 key 不能放进同一份清单，
         * 否则 Media3 整轨只用一把 key，开头那段解成静音——播放侧拿这个地址拼前一段。
         */
        const val EXTRA_DRM_INTRO_URL = "drm_intro_url"

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

        /**
         * 从授权响应里取一个字符串字段。故意按字典取值而不是映射成 data class：
         * 响应里的额外字段（真机抓到的 925B 响应里 license 只是一部分）会让严格
         * schema 整个解析失败，license 在手里也被当成"没给"。字段缺失、类型不是
         * 字符串、响应根本不是 JSON，都返回空串，由调用方按失败处理。
         */
        /**
         * license 里每把内容密钥的 kid。Widevine license 是 protobuf，KeyContainer 的
         * 字段 1 就是 16 字节 key_id（外层 `0x0a 0x10`），扫一遍就够用——两个 CDM 会话
         * 各拿各的 key，日志里对上 kid 才知道 key 轮换到底有没有生效。
         */
        internal fun licenseKeyIds(license: ByteArray): String {
            val out = mutableListOf<String>()
            var i = 0
            while (i + 18 <= license.size) {
                if (license[i] == 0x0A.toByte() && license[i + 1] == 0x10.toByte()) {
                    out += license.copyOfRange(i + 2, i + 18).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    i += 18
                } else {
                    i++
                }
            }
            return out.distinct().joinToString(",")
        }

        internal fun licenseField(body: String, name: String): String =
            runCatching {
                (Json.parseToJsonElement(body).jsonObject[name] as? JsonPrimitive)?.contentOrNull
            }.getOrNull().orEmpty()
    }
}

/** 包住 `FrameworkMediaDrm`：设备上有安全解码器就要求用它，其余行为原样透传。 */
@UnstableApi
private class SecurePreferringMediaDrm(private val delegate: ExoMediaDrm) : ExoMediaDrm by delegate {
    private var logged = false

    override fun requiresSecureDecoder(initData: ByteArray, mimeType: String): Boolean {
        val cdmWants = runCatching { delegate.requiresSecureDecoder(initData, mimeType) }.getOrDefault(false)
        val secureAvailable = AppleWidevineDrmCallback.hasSecureDecoder(mimeType)
        val wanted = cdmWants || secureAvailable
        if (!logged) {
            logged = true
            val level = runCatching { delegate.getPropertyString("securityLevel") }.getOrNull()
            AppLog.i(
                "AppleDrm",
                "解码器选择 mime=$mimeType CDM要求安全=$cdmWants 设备有安全解码器=$secureAvailable " +
                    "安全等级=${level ?: "?"} → 用安全解码器=$wanted",
            )
        }
        return wanted
    }
}

/** 绕开 `android.net.Uri`，单元测试里不用 Robolectric。 */
internal object UriCompat {
    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun decode(value: String): String = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
