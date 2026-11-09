package com.mytv.mymusic.data.webdav

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.mytv.mymusic.data.cloud.skipFully
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/** WebDAV GET 回了 2xx 以外的状态。只在 [WebDavRangeReader] 和 DataSource 之间用。 */
internal class WebDavStatusException(
    val code: Int,
    val reason: String,
    val headers: Map<String, List<String>>,
) : IOException("GET 返回 HTTP $code")

/**
 * 按 Range 打开一段（不依赖 android.net.Uri，单元测试直接跑）：
 * - 206：照 Content-Range 校正起点；服务器给的起点比要的早就跳过多出来的部分；
 * - 200：服务器不认 Range（规格 8.4），从头读再跳到 position，[Opened.rangeIgnored] 为 true，
 *   界面可以提示“这个服务器要整首下载后才能播”；
 * - 416：起点刚好在文件末尾时当成空段（拖到最后），否则抛错；
 * - 其他：抛 [WebDavStatusException]。
 */
internal object WebDavRangeReader {
    class Opened(
        val response: Response,
        val stream: InputStream,
        /** 还能读多少字节；-1（[C.LENGTH_UNSET]）表示不知道，读到流结尾为止。 */
        val bytesRemaining: Long,
        val rangeIgnored: Boolean,
        val headers: Map<String, List<String>>,
    )

    fun open(client: OkHttpClient, url: String, position: Long, length: Long): Opened {
        val builder = Request.Builder().url(url).get()
            // 不要 gzip：OkHttp 透明解压后长度对不上，Range 也会被一些服务器忽略
            .header("Accept-Encoding", "identity")
        rangeHeader(position, length)?.let { builder.header("Range", it) }
        val response = client.newCall(builder.build()).execute()
        try {
            val headers = response.headers.toMultimap()
            return when (response.code) {
                206 -> partial(response, headers, position, length)
                200 -> full(response, headers, position, length)
                416 -> {
                    val total = totalOf(response.header("Content-Range"))
                    if (position > 0 && total == position) {
                        Opened(response, response.body.byteStream(), 0, false, headers)
                    } else {
                        throw WebDavStatusException(416, response.message, headers)
                    }
                }
                else -> throw WebDavStatusException(response.code, response.message, headers)
            }
        } catch (error: Throwable) {
            response.close()
            throw error
        }
    }

    /** position 0 且读到结尾时不带 Range，和普通播放器一样。[length] < 0 表示读到结尾。 */
    fun rangeHeader(position: Long, length: Long): String? = when {
        position <= 0 && length < 0 -> null
        length < 0 -> "bytes=$position-"
        else -> "bytes=$position-${position + length - 1}"
    }

    private fun partial(response: Response, headers: Map<String, List<String>>, position: Long, length: Long): Opened {
        val stream = response.body.byteStream()
        val start = startOf(response.header("Content-Range")) ?: position
        if (start > position) throw WebDavStatusException(416, "Content-Range 起点不对", headers)
        val skip = position - start
        if (skip > 0) stream.skipFully(skip)
        val bodyLength = response.body.contentLength().takeIf { it >= 0 }?.minus(skip)
        val remaining = when {
            length >= 0 && bodyLength != null -> minOf(length, bodyLength)
            length >= 0 -> length
            bodyLength != null -> bodyLength
            else -> C.LENGTH_UNSET.toLong()
        }
        return Opened(response, stream, remaining, false, headers)
    }

    private fun full(response: Response, headers: Map<String, List<String>>, position: Long, length: Long): Opened {
        val stream = response.body.byteStream()
        val askedRange = rangeHeader(position, length) != null
        if (position > 0) stream.skipFully(position)
        val bodyLength = response.body.contentLength().takeIf { it >= 0 }?.minus(position)
        val remaining = when {
            length >= 0 && bodyLength != null -> minOf(length, bodyLength)
            length >= 0 -> length
            bodyLength != null -> bodyLength
            else -> C.LENGTH_UNSET.toLong()
        }
        return Opened(response, stream, remaining, askedRange, headers)
    }

    /** `bytes 100-199/1000` → 100。 */
    fun startOf(contentRange: String?): Long? =
        Regex("""bytes\s+(\d+)-\d+/(?:\d+|\*)""").find(contentRange.orEmpty())?.groupValues?.get(1)?.toLongOrNull()

    // `bytes */1000`（416 时的写法）或 `bytes 0-99/1000` → 1000。用行注释：星号斜杠会提前结束块注释
    fun totalOf(contentRange: String?): Long? =
        Regex("""/(\d+)\s*$""").find(contentRange.orEmpty().trim())?.groupValues?.get(1)?.toLongOrNull()
}

/**
 * 给 Media3 播 WebDAV 上的歌：所有请求都走 [client]（[WebDavClient.http] 派生出来的），
 * Basic / Digest 认证和“信任自签名证书”的指纹校验都在那个客户端里，DataSpec 里不放密码。
 * 拖动时按 Range 只取需要的那段；服务器不支持 Range 时退化成从头读再跳过，并回调 [onRangeIgnored]。
 */
@OptIn(UnstableApi::class)
class WebDavDataSource(
    private val client: OkHttpClient,
    private val onRangeIgnored: (String) -> Unit = {},
) : BaseDataSource(/* isNetwork = */ true) {
    private var dataSpec: DataSpec? = null
    private var opened: WebDavRangeReader.Opened? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var transferring = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString()
        val result = try {
            WebDavRangeReader.open(client, url, dataSpec.position, dataSpec.length)
        } catch (error: WebDavStatusException) {
            if (error.code == 416) {
                throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            throw HttpDataSource.InvalidResponseCodeException(
                error.code,
                error.reason,
                null,
                error.headers,
                dataSpec,
                ByteArray(0),
            )
        } catch (error: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                error,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        if (result.rangeIgnored) onRangeIgnored(url)
        opened = result
        bytesRemaining = result.bytesRemaining
        transferring = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val source = checkNotNull(opened) { "open() 之前调用了 read()" }
        val toRead = if (bytesRemaining < 0) length else minOf(length.toLong(), bytesRemaining).toInt()
        val read = try {
            source.stream.read(buffer, offset, toRead)
        } catch (error: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                error,
                checkNotNull(dataSpec),
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
        if (read < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining > 0) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = dataSpec?.uri ?: opened?.response?.request?.url?.toString()?.let(Uri::parse)

    override fun getResponseHeaders(): Map<String, List<String>> = opened?.headers ?: emptyMap()

    override fun close() {
        opened?.response?.close()
        opened = null
        if (transferring) {
            transferring = false
            transferEnded()
        }
    }

    class Factory(
        private val client: OkHttpClient,
        private val onRangeIgnored: (String) -> Unit = {},
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = WebDavDataSource(client, onRangeIgnored)
    }
}

/**
 * 播放器只有一个 DataSource.Factory：WebDAV 服务器下面的地址交给 [webdav]，其余照旧走 [fallback]。
 * 集成时包在现有工厂外面即可，不用为 WebDAV 单独建播放器。
 */
@OptIn(UnstableApi::class)
class WebDavRoutingDataSourceFactory(
    private val baseUrl: () -> HttpUrl?,
    private val webdav: () -> DataSource.Factory?,
    private val fallback: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = Source()

    private inner class Source : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val base = baseUrl()
            val routed = if (base != null && handles(base, dataSpec.uri.toString())) webdav() else null
            val source = (routed ?: fallback).createDataSource()
            listeners.forEach(source::addTransferListener)
            delegate = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(delegate) { "open() 之前调用了 read()" }.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                delegate?.close()
            } finally {
                delegate = null
            }
        }
    }

    companion object {
        /** [url] 在 WebDAV 服务器地址下面：协议、主机、端口相同，路径以服务器地址的路径开头。 */
        fun handles(base: HttpUrl, url: String): Boolean {
            val target = url.toHttpUrlOrNull() ?: return false
            if (target.scheme != base.scheme || !target.host.equals(base.host, ignoreCase = true) || target.port != base.port) {
                return false
            }
            val prefix = base.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
            return target.encodedPath.startsWith(prefix)
        }
    }
}
