package com.mytv.mymusic.data.native

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * music.op.wiki 的 TLS：接口在 `music.op.wiki`，音频字节在 `music.zr.wiki`，两个域名都挂在
 * Let's Encrypt 的证书链上（YE 中间证书 → ISRG Root X1）。按用户要求这里**不校验证书**，
 * 省得 Let's Encrypt 换一次根就要跟着换一份随包的根证书。
 *
 * 代价说清楚：这两个域名的连接不再有中间人保护。攻击者和用户在同一条网络路径上
 * （公共 WiFi、恶意代理、被控路由器）时，可以冒充这两个站点——读到匿名的搜索/取流请求，
 * 或者拿到已登录账号的 `mhub_session` Cookie。所以"不验证"严格限定在 MHub 的这两个域名：
 *  - 接口请求：[NativeHttp] 按域名把 MHub 的请求交给 [client]；
 *  - 音频播放：播放器的数据源工厂按域名把 MHub 的地址交给 OkHttp，见 [RoutingFactory]。
 * 其他源仍旧走系统默认那套，该验的照验——没有为了让这一个源通就把全应用的 TLS 都关掉。
 */
internal object MHubTls {

    val hosts = setOf("music.op.wiki", "music.zr.wiki")

    /** 任何证书链都当通过。 */
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val socketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }.socketFactory
    }

    /** 接口请求：沿用 [NativeHttp] 的超时和重定向配置，只换掉 SSL。 */
    val client: OkHttpClient by lazy {
        NativeHttp.client.newBuilder()
            .sslSocketFactory(socketFactory, trustAll)
            .build()
    }

    /**
     * 播放：流式读整首歌，连接会一直开着，所以不能带 callTimeout（否则几十秒后被掐断），
     * 读超时也放宽到 60 秒，CDN 抖一下不至于直接报播放错误。
     */
    val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .sslSocketFactory(socketFactory, trustAll)
            .build()
    }

    /**
     * 播放器只有一个 DataSource.Factory：MHub 域名的地址交给 OkHttp（不验证证书），
     * 其余原样走 [fallback]。包在现有工厂外面即可，不用为 MHub 单独建播放器。
     */
    @OptIn(UnstableApi::class)
    class RoutingFactory(
        private val fallback: DataSource.Factory,
    ) : DataSource.Factory {
        private val routed: DataSource.Factory = OkHttpDataSource.Factory(streamClient)
            .setUserAgent(NativeHttp.DEFAULT_USER_AGENT)

        override fun createDataSource(): DataSource = Source()

        private inner class Source : DataSource {
            private val listeners = mutableListOf<TransferListener>()
            private var delegate: DataSource? = null

            override fun addTransferListener(transferListener: TransferListener) {
                listeners += transferListener
            }

            override fun open(dataSpec: DataSpec): Long {
                val factory = if (dataSpec.uri.isMhub()) routed else fallback
                val source = factory.createDataSource()
                listeners.forEach(source::addTransferListener)
                delegate = source
                return source.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                checkNotNull(delegate) { "open() 之前调用了 read()" }.read(buffer, offset, length)

            override fun getUri(): Uri? = delegate?.uri

            override fun getResponseHeaders(): Map<String, List<String>> =
                delegate?.responseHeaders ?: emptyMap()

            override fun close() {
                try {
                    delegate?.close()
                } finally {
                    delegate = null
                }
            }
        }
    }

    /** 只有这两个主机的 https 请求绕过证书校验；其余（含明文和本机文件）照旧。 */
    internal fun bypassesCertificateValidation(scheme: String?, host: String?): Boolean =
        scheme == "https" && host?.lowercase() in hosts

    internal fun Uri.isMhub(): Boolean = bypassesCertificateValidation(scheme, host)
}
