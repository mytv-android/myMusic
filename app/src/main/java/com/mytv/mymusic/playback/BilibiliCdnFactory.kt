package com.mytv.mymusic.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.mytv.mymusic.data.native.BilibiliCdn
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * B 站音频流的防盗链：把 `*.bilivideo.com` 的字节请求补上 `Referer` 和浏览器档 UA，
 * 别的源原样走 [fallback]。为什么要单独一份工厂见 [BilibiliCdn]——简单说就是
 * [BilibiliProvider] 拿得到地址，ExoPlayer 自己去取却被 403。
 *
 * 为什么不走 `DataSpec.httpRequestHeaders`：[DefaultHttpDataSource] 在套完请求头之后
 * 会拿自己构造时那把 UA 再 setRequestProperty 一次，`User-Agent` 会被覆盖掉，
 * 而 B 站 CDN 恰好 UA 不对也 403。所以只能整条请求换一份自带正确 UA 的数据源。
 *
 * 包在现有工厂外面：缓存、洛雪/mop.wiki 的证书绕过、WebDAV 路由都不受影响。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class BilibiliCdnFactory(
    private val fallback: DataSource.Factory,
) : DataSource.Factory {
    /** 流式读整首歌，连接会一直开着：不能带 callTimeout，读超时放宽到 60 秒，CDN 抖一下不至于报错。 */
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val routed: DataSource.Factory = OkHttpDataSource.Factory(streamClient)
        .setUserAgent(BilibiliCdn.USER_AGENT)
        .setDefaultRequestProperties(mapOf("Referer" to BilibiliCdn.REFERER))

    override fun createDataSource(): DataSource = Source()

    private inner class Source : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val factory = if (BilibiliCdn.covers(dataSpec.uri.host)) routed else fallback
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
