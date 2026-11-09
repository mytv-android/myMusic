package com.mytv.mymusic.data.sync

import com.mytv.mymusic.data.webdav.WebDavAccount
import com.mytv.mymusic.data.webdav.WebDavCertificate
import com.mytv.mymusic.data.webdav.WebDavClient
import com.mytv.mymusic.data.webdav.WebDavCondition
import com.mytv.mymusic.data.webdav.WebDavErrorKind
import com.mytv.mymusic.data.webdav.WebDavException
import com.mytv.mymusic.data.webdav.WebDavMessages
import kotlinx.coroutines.CancellationException

/**
 * 同步位置的第三个实现：同步文件放在 WebDAV 目录里的 `mymusic-library.json`。
 *
 * 接口里的 token 对 WebDAV 来说是 [WebDavAccount.encode] 出来的整个账号（含密码，只在内存里传），
 * 落盘由 SyncCoordinator 交给 WebDavAccountStore，和 Gist / Gitee 的令牌分开存。
 * remoteId 是同步文件相对服务器地址的路径，例如 `myMusic/mymusic-library.json`。
 *
 * 防覆盖靠 ETag：第一次建文件用 `If-None-Match: *`，之后用 `If-Match: <读到的 ETag>`；
 * 412 抛 [SyncFailureKind.Precondition]，SyncCoordinator 重新读、重新合并（和 Gist 发现云端变了一样）。
 */
class WebDavSyncProvider(
    private val newClient: (WebDavAccount) -> WebDavClient = { WebDavClient(it) },
) : PlaylistSyncProvider {
    override val id = ID
    override val label = "WebDAV"
    override val fileName = FILE_NAME

    // 同一个账号复用同一个客户端：Digest 的 nonce 计数和连接池都挂在它上面
    private val clients = object : LinkedHashMap<String, WebDavClient>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebDavClient>?) = size > 3
    }

    fun client(account: WebDavAccount): WebDavClient = synchronized(clients) {
        clients.getOrPut(account.encode()) { newClient(account) }
    }

    /** 只读服务器证书（不发请求、不带密码）。读完就丢，不占 [clients] 的位置。 */
    suspend fun probeCertificate(account: WebDavAccount): WebDavCertificate = newClient(account).probeCertificate()

    override suspend fun validate(token: String): RemoteAccount = call(token) { client, account ->
        client.ensureDirectory(account.normalizedDirectory)
        RemoteAccount(id = client.url(account.normalizedDirectory).toString(), login = account.hostLabel)
    }

    override suspend fun locate(token: String): String = call(token) { client, account ->
        val path = account.pathInDirectory(fileName)
        val existing = client.stat(path)
        if (existing != null && !existing.isCollection) path else ""
    }

    override suspend fun read(token: String, remoteId: String): RemoteFile = call(token) { client, _ ->
        val content = try {
            client.read(remoteId, maxBytes = MAX_READ_BYTES)
        } catch (error: WebDavException) {
            // 目录还在、文件被删了：“检查服务器地址”会把人带偏
            if (error.kind != WebDavErrorKind.NotFound) throw error
            throw SyncException(
                SyncFailureKind.NotFound,
                "WebDAV 目录里找不到 $fileName，可能被删了。重新连接会按本机数据重建。",
            )
        }
        RemoteFile(
            id = remoteId,
            content = content.text(),
            revision = content.etag.orEmpty(),
            updatedAt = content.lastModified ?: 0L,
            htmlUrl = "",
        )
    }

    override suspend fun create(token: String, content: String): RemoteFile = call(token) { client, account ->
        client.ensureDirectory(account.normalizedDirectory)
        val path = account.pathInDirectory(fileName)
        write(client, path, content, WebDavCondition.IfNoneMatchAny)
    }

    override suspend fun update(
        token: String,
        remoteId: String,
        content: String,
        expectedRevision: String,
    ): RemoteFile = call(token) { client, _ ->
        write(client, remoteId, content, WebDavCondition.ifMatch(expectedRevision))
    }

    private suspend fun write(client: WebDavClient, path: String, content: String, condition: WebDavCondition): RemoteFile {
        val result = client.putBytes(path, content.toByteArray(Charsets.UTF_8), WebDavClient.JSON, condition)
        // 不少服务器 PUT 不回 ETag，补一次 HEAD；拿不到就留空，比较时退回按内容比
        val head = if (result.etag == null) runCatching { client.head(path) }.getOrNull() else null
        return RemoteFile(
            id = path,
            content = content,
            revision = result.etag ?: head?.etag.orEmpty(),
            updatedAt = head?.lastModified ?: System.currentTimeMillis(),
            htmlUrl = "",
        )
    }

    private suspend fun <T> call(token: String, block: suspend (WebDavClient, WebDavAccount) -> T): T {
        val account = WebDavAccount.decode(token)
            ?: throw SyncException(SyncFailureKind.Auth, "WebDAV 账号信息不完整，请重新连接。")
        val client = try {
            client(account)
        } catch (error: WebDavException) {
            throw SyncException(SyncFailureKind.Unknown, WebDavMessages.describe(error, account))
        }
        return try {
            block(client, account)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncException) {
            throw error
        } catch (error: WebDavException) {
            throw SyncException(failureKind(error.kind), WebDavMessages.describe(error, account))
        } catch (error: Exception) {
            throw SyncException(
                SyncFailureKind.Unknown,
                error.message?.takeIf { it.isNotBlank() } ?: "同步失败（${error.javaClass.simpleName}）",
            )
        }
    }

    companion object {
        const val ID = "webdav"
        const val FILE_NAME = "mymusic-library.json"

        /** 上传上限是 900 KB；读的时候放宽，别人手动改大了也能读出来再提示。 */
        private const val MAX_READ_BYTES = 4L * 1024 * 1024

        internal fun failureKind(kind: WebDavErrorKind): SyncFailureKind = when (kind) {
            WebDavErrorKind.Unauthorized -> SyncFailureKind.Auth
            WebDavErrorKind.Forbidden -> SyncFailureKind.Permission
            WebDavErrorKind.NotFound -> SyncFailureKind.NotFound
            WebDavErrorKind.PreconditionFailed -> SyncFailureKind.Precondition
            // 证书对不上也按网络错误让 WorkManager 退避重试：握手阶段就失败，密码发不出去，重试无害；
            // 酒店 Wi-Fi 这类劫持 TLS 的网络换掉以后，下一次重试就自己好了，不该一直卡住自动同步
            WebDavErrorKind.Network, WebDavErrorKind.Tls, WebDavErrorKind.UntrustedCertificate,
            WebDavErrorKind.CertificateChanged -> SyncFailureKind.Network
            WebDavErrorKind.InvalidResponse -> SyncFailureKind.Corrupt
            else -> SyncFailureKind.Unknown
        }
    }
}
