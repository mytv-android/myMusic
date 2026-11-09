package com.mytv.mymusic.data.cloud

import java.io.IOException
import java.util.Locale

/**
 * 云端失败的种类。[retryable] 决定界面给不给“重试”：登录、风控、容量这几类重试也没用，
 * 得先让用户处理（规格 12、16.5）。
 */
enum class CloudErrorKind(val retryable: Boolean) {
    /** 没登录或 Cookie 失效（网易 301、QQ 1000）。上传队列遇到它就整体暂停。 */
    NotLoggedIn(false),

    /** 网易 -460 / -462：网络环境有风险。暂停整个队列，等用户点“继续”，不自动重试。 */
    RiskControl(false),

    /** 云盘空间不够；[CloudException.shortfall] 是还差的字节数。 */
    QuotaExceeded(false),

    /** 单个文件超过 500 MB（网易）或服务器限制了单次上传大小（WebDAV 413）。 */
    FileTooLarge(false),

    /** 空文件之类根本没法上传的。 */
    InvalidFile(false),

    /** 转码 10 分钟还没完成。 */
    TranscodeTimeout(true),

    /** 云盘处理文件时报了异常状态。 */
    TranscodeFailed(true),

    /** QQ 音乐云盘只读。 */
    UploadUnsupported(false),

    /** 这个存储不提供这项能力（比如 WebDAV 没有歌词）。 */
    Unsupported(false),

    /** 云端有这个文件但取不到播放地址（QQ 原文件、网易云盘地址接口都失败）。 */
    Unplayable(false),

    /** 平台和云盘都没有这首的歌词。 */
    NoLyrics(false),

    /** 云端文件已删除（404）；调用方把 remote.status 置成 missing，不删歌单条目。 */
    NotFound(false),

    /** WebDAV 401：密码不对或已失效。 */
    Unauthorized(false),

    /** WebDAV 403 等权限问题。 */
    Forbidden(false),

    /** WebDAV 同一位置已有大小不同的文件。 */
    Conflict(false),

    /** 云端暂时连不上（断网、超时）。不写进同步文件，网络恢复自动好。 */
    Network(true),

    /** 平台返回了错误码或 HTTP 错误。 */
    Server(true),

    /** 返回的内容看不懂。 */
    InvalidResponse(true),

    Cancelled(true),
}

/**
 * 云端操作的失败。继承 IOException：接到 Media3 / 下载时能沿着 IO 异常的路径走。
 * [message] 是给用户看的中文，不夹服务器原文里的内部信息。
 */
class CloudException(
    val kind: CloudErrorKind,
    message: String,
    val provider: CloudProvider? = null,
    /** 平台错误码或 HTTP 状态码；没有为 0。 */
    val code: Int = 0,
    /** [CloudErrorKind.QuotaExceeded] 时还差多少字节。 */
    val shortfall: Long = 0,
    cause: Throwable? = null,
) : IOException(message, cause) {
    val retryable: Boolean get() = kind.retryable

    override val message: String get() = super.message.orEmpty()

    /** 遇到这些就该暂停整个上传队列：后面的文件只会一样失败（规格 12：-460 / 401 不要循环重试）。 */
    val pausesQueue: Boolean
        get() = kind == CloudErrorKind.RiskControl || kind == CloudErrorKind.NotLoggedIn ||
            kind == CloudErrorKind.Unauthorized
}

/** 设计稿“出错时用户看到什么”那张表的文案，集中在这里。 */
object CloudMessages {
    const val RISK_CONTROL = "网易云说网络环境有风险，先暂停上传"

    /** 我的页那一行的说明（设计稿第 09 节）。 */
    const val RISK_CONTROL_PAUSED = "上传暂停：网易云要求重新验证"
    const val QQ_UPLOAD_UNSUPPORTED = "QQ 音乐云盘只能读取，暂不支持上传"
    const val QQ_ORIGINAL_UNPLAYABLE = "QQ 云盘里的原文件放不了"
    const val NETEASE_UNPLAYABLE = "网易云盘里的这首暂时取不到播放地址"
    const val TRANSCODE_TIMEOUT = "网易云盘转码超过 10 分钟还没完成，可以稍后重试"
    const val CLOUD_UNREACHABLE = "云端暂时连不上"
    const val CLOUD_DELETED = "云端文件已删除"
    const val WEBDAV_UNAUTHORIZED = "WebDAV 密码不对或已失效"
    const val WEBDAV_NO_RANGE = "这个服务器要整首下载后才能播"
    const val CANCELLED = "已取消"
    const val EMPTY_FILE = "文件是空的，没法上传"
    const val NO_LYRICS = "这首没有找到歌词"

    fun notLoggedIn(provider: CloudProvider): String = when (provider) {
        CloudProvider.NeteaseCloud -> "网易云音乐没有登录，或者登录已失效"
        CloudProvider.QqCloud -> "QQ 音乐没有登录，或者登录已失效"
        CloudProvider.WebDav -> "还没有配置 WebDAV"
    }

    fun quotaExceeded(shortfall: Long): String = "云盘空间不够，还差 ${formatSize(shortfall)}"

    fun fileTooLarge(limit: Long): String = "单个文件超过 ${formatSize(limit)}，云盘不收，已跳过"

    fun transcodeFailed(status: Int): String = "网易云盘处理这个文件失败（状态 $status），可以重试"

    fun server(provider: CloudProvider, code: Int): String = "${provider.label}返回错误（$code）"

    /** `1.2 GB` / `412 MB` / `36 KB`，按 1024 进位；GB 保留一位小数，整数时不写 `.0`。 */
    fun formatSize(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.ROOT, "%.1f", bytes / gb).removeSuffix(".0") + " GB"
            bytes >= mb -> String.format(Locale.ROOT, "%.0f", bytes / mb) + " MB"
            bytes >= kb -> String.format(Locale.ROOT, "%.0f", bytes / kb) + " KB"
            else -> "$bytes B"
        }
    }
}
