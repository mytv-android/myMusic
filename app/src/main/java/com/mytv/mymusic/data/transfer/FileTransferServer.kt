package com.mytv.mymusic.data.transfer

import com.mytv.mymusic.data.sync.LanPanelServer
import com.mytv.mymusic.data.sync.SYNC_SETUP_SESSION_MILLIS
import com.mytv.mymusic.data.sync.SyncSetupTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder

/** 扫码网页是上传（把文件传到电视）还是下载（从电视导出到手机）。 */
internal sealed interface TransferMode {
    data object Upload : TransferMode
    data class Download(val file: ExportFile) : TransferMode
}

/** 交给电视的结果：收下了 / 电视正忙（别的文件还在预览）/ 不是歌单文件。 */
internal enum class UploadResult { Accepted, Busy, Rejected }

/** 网页每 2 秒问一次的状态。 */
internal enum class TransferPhase(val wire: String) {
    Waiting("waiting"),
    Received("received"),
    Done("done"),
    Error("error"),
}

/**
 * 电视没有系统文件选择器时，手机扫码打开的局域网网页：上传歌单文件给电视，或者从电视下载导出的歌单文件。
 *
 * 安全：只收局域网来源；令牌 128 位只在路径里；10 分钟过期，面板开着、应用在前台时才监听（由界面控制）；
 * 上传要同源 + `x-mymusic-transfer: 1` + `application/octet-stream`，简单表单和跨站 fetch 都过不了；
 * 32MB 上限、按 Content-Length 读进内存，不落盘（不用 parseBody，免得 NanoHTTPD 写临时文件）；
 * 一个会话只收一个文件；下载同一会话最多 [MAX_DOWNLOADS] 次。文件名只用来显示。
 */
internal class FileTransferServer(
    assets: (String) -> ByteArray?,
    private val theme: () -> SyncSetupTheme,
    private val mode: TransferMode,
    private val onUpload: (bytes: ByteArray, name: String) -> UploadResult,
    bindHost: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    sessionMillis: Long = SYNC_SETUP_SESSION_MILLIS,
) : LanPanelServer(bindHost, assets) {
    override val expiresAt: Long = clock() + sessionMillis

    private var received = false
    private var downloads = 0

    @Volatile private var phase: TransferPhase = TransferPhase.Waiting

    @Volatile private var message: String = initialMessage()

    override fun isExpired(): Boolean = clock() >= expiresAt

    /** 电视那边的进度（预览中、导入完、取消）由界面写进来，网页轮询时看到。 */
    fun setStatus(phase: TransferPhase, message: String) {
        this.phase = phase
        this.message = message
    }

    override fun route(http: IHTTPSession, route: String): Response = when (http.method) {
        Method.GET -> when (route) {
            "", "index.html" -> asset("index.html", "text/html; charset=UTF-8", html = true)
            "transfer.css" -> asset("transfer.css", "text/css; charset=UTF-8")
            "transfer.js" -> asset("transfer.js", "application/javascript; charset=UTF-8")
            "theme.css" -> textResponse(Response.Status.OK, "text/css; charset=UTF-8", themeCss(theme()))
            "api/session" -> jsonResponse(Response.Status.OK, sessionJson())
            "api/status" -> jsonResponse(Response.Status.OK, statusJson())
            "api/download" -> if (mode is TransferMode.Download) download(mode.file) else notFound()
            else -> errorResponse(Response.Status.NOT_FOUND, "资源不存在")
        }
        Method.POST -> if (route == "api/upload" && mode == TransferMode.Upload) upload(http) else notFound()
        else -> errorResponse(Response.Status.METHOD_NOT_ALLOWED, "不支持此请求方式")
    }

    private fun notFound(): Response = errorResponse(Response.Status.NOT_FOUND, "接口不存在")

    private fun sessionJson(): JsonObject = buildJsonObject {
        put("mode", if (mode is TransferMode.Download) "download" else "upload")
        put("expiresAt", expiresAt)
        put("maxBytes", MAX_UPLOAD_BYTES)
        if (mode is TransferMode.Download) {
            putJsonObject("download") {
                put("name", mode.file.fileName)
                put("size", mode.file.bytes.size)
            }
        }
    }

    private fun statusJson(): JsonObject = buildJsonObject {
        put("phase", phase.wire)
        put("message", message)
    }

    private fun upload(http: IHTTPSession): Response {
        http.checkWrite(
            customHeader = UPLOAD_HEADER,
            contentType = "application/octet-stream",
            maxBytes = MAX_UPLOAD_BYTES,
            crossSiteMessage = "请从电视提供的网页上传",
            badRequestMessage = "请用电视提供的网页选择文件上传",
            tooLargeMessage = "文件为空或超过 32MB",
        )?.let { return it }
        synchronized(this) {
            if (received) return errorResponse(Response.Status.CONFLICT, "电视已经收到文件了")
        }
        val length = http.header("content-length")?.toLongOrNull() ?: 0L
        val bytes = try {
            readExactly(http.inputStream, length.toInt())
        } catch (_: IOException) {
            return errorResponse(Response.Status.BAD_REQUEST, "文件没有传完整，请重试")
        }
        if (!TransferReader.looksLikeTransfer(bytes)) {
            return errorResponse(Response.Status.UNSUPPORTED_MEDIA_TYPE, "不是歌单文件")
        }
        val name = decodeFileName(http.header("x-file-name"))
        synchronized(this) {
            if (received) return errorResponse(Response.Status.CONFLICT, "电视已经收到文件了")
            when (runCatching { onUpload(bytes, name) }.getOrDefault(UploadResult.Busy)) {
                UploadResult.Accepted -> {
                    received = true
                    setStatus(TransferPhase.Received, "电视已收到，请在电视上确认导入")
                }
                UploadResult.Busy -> return errorResponse(Response.Status.CONFLICT, "电视正在处理别的文件，请稍后再试")
                UploadResult.Rejected -> return errorResponse(Response.Status.UNSUPPORTED_MEDIA_TYPE, "不是歌单文件")
            }
        }
        return jsonResponse(Response.Status.ACCEPTED, statusJson())
    }

    private fun download(file: ExportFile): Response {
        synchronized(this) {
            // 手机浏览器会预取、重试，留几次余量；用完就失效
            if (downloads >= MAX_DOWNLOADS) return errorResponse(Response.Status.GONE, "下载次数用完了，请在电视上重新打开")
            downloads++
            setStatus(TransferPhase.Done, "已下载 ${file.fileName}")
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "${file.mime}; charset=UTF-8",
            ByteArrayInputStream(file.bytes),
            file.bytes.size.toLong(),
        ).secure().apply {
            addHeader("Content-Disposition", contentDisposition(file.fileName))
        }
    }

    private fun initialMessage(): String =
        if (mode is TransferMode.Download) "点按钮把文件存到手机" else "选一个歌单文件发送到电视"

    companion object {
        const val MAX_UPLOAD_BYTES = TransferReader.MAX_FILE_BYTES
        const val MAX_DOWNLOADS = 3
        const val UPLOAD_HEADER = "x-mymusic-transfer"

        /** 照 Content-Length 读满；不够就是连接断了。 */
        internal fun readExactly(input: InputStream, length: Int): ByteArray {
            val out = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(out, read, length - read)
                if (n < 0) throw IOException("连接提前断开")
                read += n
            }
            return out
        }

        /** 网页用 encodeURIComponent 放进头里；解码后去掉不能当文件名的字符，最长 150 字。 */
        internal fun decodeFileName(raw: String?): String {
            val decoded = runCatching { URLDecoder.decode(raw.orEmpty().replace("+", "%2B"), "UTF-8") }.getOrDefault("")
            return safeFileName(decoded).takeIf { decoded.isNotBlank() } ?: "手机传来的文件"
        }

        /** `attachment; filename="<ASCII 回退>"; filename*=UTF-8''<百分号编码>`。 */
        internal fun contentDisposition(name: String): String {
            val ascii = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
        }
    }
}
