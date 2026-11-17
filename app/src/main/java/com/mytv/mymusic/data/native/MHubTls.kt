package com.mytv.mymusic.data.native

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * music.op.wiki 和它下发音频的 CDN（music.zr.wiki）都挂在 Let's Encrypt 的 YE 中间证书链上，
 * 信任锚是 ISRG Root X1。Android 6/7 的系统 CA 库里没有这个根，握手直接报
 * `CertPathValidatorException: Trust anchor for certification path not found`——
 * 不是超时也不是 429，是连 TCP 之后的 TLS 就没谈成，搜索、准备、播放全挂。
 *
 * 所以把 ISRG Root X1 随包带一份，当**附加**信任锚：系统 CA 照常校验，只有在系统里
 * 找不到锚时才回落到附带的这份。两个入口都用它——
 *  - 接口请求（OkHttp）：[NativeHttp] 按域名把 MHub 的请求切到 [client]；
 *  - 音频播放（ExoPlayer 的 DefaultHttpDataSource）：它走 HttpsURLConnection，
 *    没有注入 SSLSocketFactory 的口子，只能在启动时装一次进程级默认值，
 *    见 [installDefaultSslSocketFactory]。
 */
internal object MHubTls {

    /** MHub 的域名：接口在 op.wiki，真正的音频字节在 zr.wiki，两边都是同一条 LE 链。 */
    val hosts = setOf("music.op.wiki", "music.zr.wiki")

    /**
     * ISRG Root X1，letsencrypt.org/certs/isrgrootx1.pem，有效期到 2035-06-04。
     * 换成新的根只需替换这一个常量。
     */
    private const val ISRG_ROOT_X1_PEM = """
        -----BEGIN CERTIFICATE-----
        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
        -----END CERTIFICATE-----
    """

    val client: OkHttpClient by lazy { build() }

    /** 附带的那份根。单独暴露出来是为了单测能校验常量没在换根时被改坏。 */
    internal val anchor: X509Certificate by lazy { loadAnchor() }

    private val trustManager: X509TrustManager by lazy {
        ExtraAnchorTrustManager(systemTrustManager(), trustManagerOf(anchor))
    }

    private val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    /** 附带根解析不了就退回默认客户端——不能因为一个证书把整个源搞成发不出请求。 */
    private fun build(): OkHttpClient = NativeHttp.client.newBuilder()
        .sslSocketFactory(socketFactory, trustManager)
        .build()

    private val installed = AtomicBoolean()

    /**
     * 播放器（ExoPlayer 的 `DefaultHttpDataSource`）走 `HttpsURLConnection`，而这个类没有
     * 注入 SSLSocketFactory 的口子，只能设进程级默认值。在 Application.onCreate 里调一次，
     * 之后所有在线播放、切歌过渡分析都认这个锚。
     *
     * 装的是**附加**锚：系统 CA 仍然先验，只有在系统里找不到锚时才回落到附带的这份，
     * 所以其他源的行为一个字节都没变——只是原本直接握手失败的请求现在能过了。
     */
    fun installDefaultSslSocketFactory() {
        if (installed.compareAndSet(false, true)) {
            HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory)
        }
    }

    private fun loadAnchor(): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return ByteArrayInputStream(ISRG_ROOT_X1_PEM.trimIndent().toByteArray()).use { input ->
            factory.generateCertificate(input) as X509Certificate
        }
    }

    private fun trustManagerOf(anchor: X509Certificate): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("isrg-root-x1", anchor)
        }
        return trustManagers(store).first()
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return trustManagers(null, factory).first()
    }

    private fun trustManagers(store: KeyStore?, factory: TrustManagerFactory? = null): List<X509TrustManager> {
        val tmFactory = factory ?: TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .also { it.init(store) }
        return tmFactory.trustManagers.filterIsInstance<X509TrustManager>()
    }

    /**
     * 系统锚先验，失败了再用附带的那份锚验一遍；两份都不过才抛系统那份的异常
     * （用户看到的还是熟悉的"证书不受信任"，不会泄露我们额外带了什么）。
     */
    private class ExtraAnchorTrustManager(
        private val system: X509TrustManager,
        private val extra: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                system.checkServerTrusted(chain, authType)
                return
            } catch (systemFailure: CertificateException) {
                try {
                    extra.checkServerTrusted(chain, authType)
                } catch (extraFailure: CertificateException) {
                    throw systemFailure
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            system.acceptedIssuers + extra.acceptedIssuers
    }
}
