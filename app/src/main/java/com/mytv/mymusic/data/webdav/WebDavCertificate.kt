package com.mytv.mymusic.data.webdav

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * 服务器叶子证书的摘要。打开“信任自签名证书”时，本机第一次连上就记下它（trust on first use），
 * 之后只认这一张：系统信任的证书照常放行，系统不认的只有指纹对得上才放行。
 *
 * 指纹是整张证书 DER 编码的 SHA-256，和 `openssl x509 -noout -fingerprint -sha256` 打出来的一样，
 * 用户可以拿服务器上的证书核对。只存在本机（WebDAV 账号里），不进同步文件。
 */
@Serializable
data class WebDavCertificate(
    /** 64 位大写十六进制，不带冒号。 */
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notAfter: Long,
    val selfSigned: Boolean,
    /** 读证书那一刻系统就信任它（证书链和主机名都对）：这种不用拿指纹打扰用户。 */
    val systemTrusted: Boolean = false,
) {
    /** `AB:CD:…` 形式，给人核对用。 */
    val fingerprint: String get() = sha256.chunked(2).joinToString(":")

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String?): WebDavCertificate? =
            raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }

        fun of(certificate: X509Certificate, systemTrusted: Boolean): WebDavCertificate = WebDavCertificate(
            sha256 = sha256(certificate),
            subject = displayName(certificate.subjectX500Principal),
            issuer = displayName(certificate.issuerX500Principal),
            notAfter = certificate.notAfter.time,
            selfSigned = isSelfSigned(certificate),
            systemTrusted = systemTrusted,
        )

        fun sha256(certificate: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4]).append(HEX[value and 0x0f])
                }
            }
        }

        // 主题和签发者一样还不够：自建 CA 也可能给叶子起同一个名字，签名能用自己的公钥验过才算自签名
        private fun isSelfSigned(certificate: X509Certificate): Boolean =
            certificate.subjectX500Principal == certificate.issuerX500Principal &&
                runCatching { certificate.verify(certificate.publicKey) }.isSuccess

        /** 优先给 CN；只写了 O、OU 的自建证书没有 CN，就把整串名字给出来。 */
        private fun displayName(principal: X500Principal): String {
            val name = principal.getName(X500Principal.RFC2253)
            val commonName = COMMON_NAME.find(name)?.groupValues?.get(1)?.replace(ESCAPED, "$1")?.trim()
            return commonName?.takeIf { it.isNotEmpty() }
                ?: name.ifBlank { "未命名" }.let { if (it.length > 80) it.take(80) + "…" else it }
        }

        private const val HEX = "0123456789ABCDEF"
        private val COMMON_NAME = Regex("""(?:^|,)\s*CN=((?:\\.|[^,\\])*)""", RegexOption.IGNORE_CASE)
        private val ESCAPED = Regex("""\\(.)""")
    }
}

/**
 * 测试连接 / 连接之前先读一次证书，按本机记下的那张决定能不能往下走。只有用户点的这两个操作会
 * 走到这里；后台同步不读证书、也不改记下的指纹，对不上就失败。
 */
object WebDavTrust {
    sealed interface Decision {
        /** 可以继续：第一次见（记下它）、和记下的一致，或者系统本来就信任。 */
        data class Trust(val certificate: WebDavCertificate) : Decision

        /** 和记下的不一样、系统也不认：停在这里，不发密码，等用户看过新指纹自己决定。 */
        data class Changed(val observed: WebDavCertificate, val pinned: WebDavCertificate) : Decision
    }

    fun decide(pinned: WebDavCertificate?, observed: WebDavCertificate): Decision = when {
        observed.systemTrusted -> Decision.Trust(observed)
        pinned == null -> Decision.Trust(observed)
        pinned.sha256.equals(observed.sha256, ignoreCase = true) -> Decision.Trust(observed)
        else -> Decision.Changed(observed, pinned)
    }
}

/** 系统不认、指纹也和记下的不一样。[observed] 是这次服务器给的证书，界面拿去显示新指纹。 */
internal class CertificatePinMismatch(
    val observed: WebDavCertificate,
    cause: Throwable,
) : CertificateException("服务器证书和本机信任的指纹不一致", cause)

/**
 * “信任自签名证书”打开时这个账号的客户端用的校验，只挂在这一个 OkHttpClient 上：
 * - 证书链系统认、主机名也对得上，照常放行（换成正规证书、证书续期都不用重新确认）；
 * - 系统不认时，叶子证书的 SHA-256 必须等于 [pin]，否则握手失败，抛 [CertificatePinMismatch]；
 *   [pin] 为空（还没确认过任何证书）就按系统的结论拒绝；
 * - 主机名只认服务器地址里那一个，跳转到别的主机直接失败，指纹放行不会被带到别处；
 * - 这里从不改 [pin]：换指纹只有一条路，用户在“测试连接”里看过新指纹后点“信任新证书”。
 *
 * “系统”取自 [base]（默认就是系统证书库），单元测试借它换成测试里生成的根证书。
 */
internal fun OkHttpClient.Builder.pinTrust(base: OkHttpClient, host: String, pin: String) {
    val manager = PinnedTrustManager(base.x509TrustManager ?: platformTrustManager(), pin)
    val systemVerifier = base.hostnameVerifier
    sslSocketFactory(sslContext(manager).socketFactory, manager)
    hostnameVerifier { name, session ->
        // 指纹对上了就以指纹为准：自签名证书的名字常常和地址对不上（只写了 IP 或者随便起的名字）
        name.equals(host, ignoreCase = true) && (manager.matchesPin(session) || systemVerifier.verify(name, session))
    }
}

/**
 * 只读证书、不发请求的客户端：叶子证书一到手就在证书校验里中止握手，HTTP 请求一个字节都不发，
 * 密码也就不会发出去。系统认的证书链还要过主机名校验才算 [WebDavCertificate.systemTrusted]，
 * 所以那种情况让握手做完、在主机名校验这一步停下——它同样在发请求之前。
 */
internal class CertificateProbe(base: OkHttpClient) {
    private val system = base.x509TrustManager ?: platformTrustManager()
    private val systemVerifier = base.hostnameVerifier

    @Volatile private var leaf: X509Certificate? = null
    @Volatile private var chainTrusted = false
    @Volatile private var hostnameTrusted = false

    private val recorder = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            leaf = chain.firstOrNull() ?: throw CertificateException("服务器没有给出证书")
            chainTrusted = runCatching { system.checkServerTrusted(chain, authType) }.isSuccess
            if (!chainTrusted) throw CertificateException("已读到证书，不再继续握手")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    val client: OkHttpClient = base.newBuilder()
        .sslSocketFactory(sslContext(recorder).socketFactory, recorder)
        .hostnameVerifier { name, session ->
            hostnameTrusted = systemVerifier.verify(name, session)
            false
        }
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()

    fun result(): WebDavCertificate? = leaf?.let { WebDavCertificate.of(it, chainTrusted && hostnameTrusted) }
}

/**
 * 只实现两参数的 checkServerTrusted：X509ExtendedTrustManager 要 API 24（minSdk 23），而
 * network_security_config 里没有按域名的配置，系统的两参数校验在各个版本上都能用。
 */
private class PinnedTrustManager(private val system: X509TrustManager, private val pin: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("服务器没有给出证书")
        try {
            system.checkServerTrusted(chain, authType)
        } catch (error: CertificateException) {
            if (pin.isEmpty()) throw error
            if (WebDavCertificate.sha256(leaf) == pin) return
            throw CertificatePinMismatch(WebDavCertificate.of(leaf, systemTrusted = false), error)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    fun matchesPin(session: SSLSession): Boolean {
        if (pin.isEmpty()) return false
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return false
        return WebDavCertificate.sha256(leaf) == pin
    }
}

private fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}

private fun sslContext(manager: X509TrustManager): SSLContext =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), SecureRandom()) }
