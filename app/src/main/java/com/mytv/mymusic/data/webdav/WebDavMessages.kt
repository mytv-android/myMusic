package com.mytv.mymusic.data.webdav

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 给用户看的 WebDAV 错误文案。同步位置和以后的上传共用，所以集中在这里。
 * 文案只写原因和下一步，不带服务器返回的正文（可能很长，也可能夹着内部路径）。
 */
object WebDavMessages {
    fun isJianguoyun(account: WebDavAccount?): Boolean =
        account?.baseUrl?.host?.endsWith("jianguoyun.com", ignoreCase = true) == true

    fun describe(error: WebDavException, account: WebDavAccount?): String = when (error.kind) {
        WebDavErrorKind.Unauthorized -> if (isJianguoyun(account)) {
            "用户名或密码不对。坚果云要用“安全选项 › 第三方应用管理”里生成的应用密码，不是登录密码。"
        } else {
            "用户名或密码不对。"
        }
        WebDavErrorKind.Forbidden ->
            "服务器拒绝访问这个目录（403），请检查账号权限，或换一个目录。"
        WebDavErrorKind.NotFound ->
            "服务器上找不到这个地址（404），请检查服务器地址。"
        WebDavErrorKind.MethodNotAllowed ->
            "服务器不支持这个操作（405），请确认填的是 WebDAV 地址。"
        WebDavErrorKind.Conflict ->
            error.message?.takeIf { error.message?.contains("不是目录") == true }?.let { "$it，请换一个目录。" }
                ?: "上级目录不存在（409），请检查目录。"
        WebDavErrorKind.PreconditionFailed ->
            "云端文件刚被其他设备修改，请再试一次。"
        WebDavErrorKind.Locked ->
            "文件被服务器锁定（423），请稍后再试。"
        WebDavErrorKind.PayloadTooLarge ->
            "服务器限制了单次上传的大小（413）。"
        WebDavErrorKind.InsufficientStorage ->
            "WebDAV 空间不够了（507）。"
        WebDavErrorKind.Http ->
            "WebDAV 服务器返回错误（HTTP ${error.status}）。"
        WebDavErrorKind.UntrustedCertificate -> if (account?.trustSelfSigned == true) {
            // 开关开着还失败：系统不认，本机也没确认过这张（或者证书上的名字和地址对不上）
            "服务器证书不受信任，也不是本机确认过的那张，已停止连接。$RECHECK"
        } else {
            "服务器证书不受信任。如果是自己架的服务器，可以打开“信任自签名证书”后再试。"
        }
        WebDavErrorKind.CertificateChanged ->
            "服务器证书变了，和上次信任的不一样，已停止连接。$RECHECK"
        WebDavErrorKind.Tls ->
            "和服务器建立加密连接失败：${error.message}"
        WebDavErrorKind.Network ->
            "连不上 WebDAV 服务器：${error.message}"
        WebDavErrorKind.InvalidResponse ->
            "服务器的回应看不懂（${error.message}），请确认填的是 WebDAV 地址。"
        WebDavErrorKind.InvalidConfig ->
            error.message ?: "WebDAV 设置不完整。"
    }

    /**
     * 测试连接的结果，写在按钮下方。[certificate] 是这次读到的服务器证书，系统不认时另起一行给出指纹；
     * [pinnedBefore] 表示本机之前就认这张。
     */
    fun checkSummary(
        result: WebDavCheckResult,
        account: WebDavAccount?,
        certificate: WebDavCertificate? = null,
        pinnedBefore: Boolean = false,
    ): String {
        val error = result.error
        val summary = if (!result.ok && error != null) {
            "${result.failedStep?.label ?: "测试"}失败：${describe(error, account)}"
        } else {
            buildString {
                append("能读写")
                append(if (result.supportsRange) " · 服务器支持断点播放" else " · 服务器不支持断点播放，上传的歌要整首下载后才能播")
                if (result.createdDirectory) append(" · 已新建目录 ${account?.normalizedDirectory.orEmpty().ifBlank { "/" }}")
                if (result.foundFile) append(" · 目录里已有同步文件，连接时会让你选合并方式")
            }
        }
        if (certificate == null || certificate.systemTrusted) return summary
        val note = if (pinnedBefore) "和本机信任的一致。" else "请和服务器上的证书核对，连接后本机只认这一张。"
        return "$summary\n${certificate(certificate)}，$note"
    }

    /** 测试连接时发现证书和本机记下的不一样：这次没发密码，给出新指纹，让用户自己决定信不信。 */
    fun certificateChanged(observed: WebDavCertificate): String =
        "服务器证书变了，和上次信任的不一样。这次测试在发送密码前就停下了。\n" +
            "新的${certificate(observed)}\n" +
            "确认是你自己换的证书，再点“信任新证书”；不确定就先别连，可能有人在中间拦截。"

    /** `证书指纹 AB:CD:…（颁发给 nas.local，自签名，2027-03 到期）` */
    fun certificate(certificate: WebDavCertificate, now: Long = System.currentTimeMillis()): String {
        val month = SimpleDateFormat("yyyy-MM", Locale.ROOT).format(Date(certificate.notAfter))
        val validity = if (certificate.notAfter < now) "已于 $month 过期" else "$month 到期"
        val issuer = if (certificate.selfSigned) "自签名" else "由 ${certificate.issuer} 签发"
        return "证书指纹 ${certificate.fingerprint}（颁发给 ${certificate.subject}，$issuer，$validity）"
    }

    private const val RECHECK = "如果是你自己换了证书，到 WebDAV 设置里点“测试连接”核对新指纹，确认后再连接。"
}
