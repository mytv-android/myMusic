package com.mytv.mymusic.data.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * 可取消的请求：协程取消时 [Call.cancel]，正在写的上传体立刻断开。
 * 不用 runInterruptible + execute：阻塞的套接字写不响应线程中断，大文件上传会取消不掉。
 * 返回的 Response 由调用方关闭。
 */
internal suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // 回应到了但协程刚好被取消：关掉，免得连接泄漏
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/**
 * 把 [source] 的 [offset, offset + length) 流式写进请求体，不整块读进内存（FLAC 一首几十 MB）。
 * [onWritten] 报这一次已经写出的字节数（不是增量）：OkHttp 换连接重发时会再调一次 writeTo，
 * 增量会重复累计。[cancelled] 每写一块问一次，取消时立刻抛断。
 */
internal class SourceChunkBody(
    private val source: CloudUploadSource,
    private val offset: Long,
    private val length: Long,
    private val mediaType: MediaType?,
    private val cancelled: () -> Boolean = { false },
    private val onWritten: (Long) -> Unit = {},
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        source.open(offset).use { input ->
            val buffer = ByteArray(BUFFER)
            var written = 0L
            var reported = 0L
            while (written < length) {
                if (cancelled()) throw IOException(CloudMessages.CANCELLED)
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - written).toInt())
                if (read < 0) throw IOException("文件比登记的短")
                sink.write(buffer, 0, read)
                written += read
                if (written - reported >= REPORT_STEP || written == length) {
                    reported = written
                    onWritten(written)
                }
            }
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val REPORT_STEP = 256L * 1024
    }
}

/** runCatching 会把取消也吞掉；这里只吞普通异常，取消照常往上抛。 */
internal inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
