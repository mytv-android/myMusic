package com.mytv.mymusic.data.webdav

import android.content.Context
import com.mytv.mymusic.data.sync.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 一个 WebDAV 账号。同步位置和以后的“上传到 WebDAV”共用同一个账号，所以它是独立存的实体，
 * 不挂在同步绑定（sync_accounts）上；密码只在本机，永远不进同步文件。
 *
 * [serverUrl] 和 [directory] 按用户输入原样保存，用的时候再规范化：坚果云、Nextcloud 这类地址
 * 用户常常少写结尾斜杠或多写空格，规范化的规则集中在 [WebDavPaths]，测试也只测那一处。
 */
@Serializable
data class WebDavAccount(
    val serverUrl: String,
    val username: String,
    val password: String,
    val directory: String = WebDavPaths.DEFAULT_DIRECTORY,
    val trustSelfSigned: Boolean = false,
    /**
     * 打开 [trustSelfSigned] 后本机认的那张服务器证书（见 [WebDavCertificate]）。
     * 只在用户点“测试连接”或“连接并同步”时定下，后台同步只照着它校验，从不自己改。
     */
    val pinnedCertificate: WebDavCertificate? = null,
) {
    /** 规范化后的服务器地址（一定带结尾斜杠）；地址写错时为 null。 */
    val baseUrl: HttpUrl? get() = WebDavPaths.parseServerUrl(serverUrl)

    /** 相对服务器地址的目录，形如 `myMusic/`；空字符串表示服务器根目录。 */
    val normalizedDirectory: String get() = WebDavPaths.normalizeDirectory(directory)

    /** 目录里某个文件相对服务器地址的路径，例如 `myMusic/mymusic-library.json`。 */
    fun pathInDirectory(name: String): String = WebDavPaths.join(normalizedDirectory, name)

    /** 卡片和同步位置上显示的主机名；非默认端口一起写上，局域网里常靠端口区分服务。 */
    val hostLabel: String get() = baseUrl?.let(WebDavPaths::hostLabel) ?: serverUrl.trim()

    /** http:// 地址：可以连，但 Basic 认证的密码是明文。 */
    val isCleartext: Boolean get() = baseUrl?.isHttps == false

    /**
     * 存盘和传给同步层前统一一次，避免同一个账号因为写法不同被当成两个。
     * 关掉证书开关或换成 http 时，记下的证书一起丢掉：下次再打开要重新确认。
     */
    fun normalized(): WebDavAccount = copy(
        serverUrl = baseUrl?.toString() ?: serverUrl.trim(),
        username = username.trim(),
        directory = normalizedDirectory,
        pinnedCertificate = pinnedCertificate?.takeIf { trustSelfSigned && baseUrl?.isHttps == true },
    )

    /** 和 [other] 是不是同一台服务器（协议、主机、端口都一样）：证书是跟着服务器走的，和路径无关。 */
    fun sameEndpoint(other: WebDavAccount): Boolean {
        val mine = baseUrl ?: return false
        val theirs = other.baseUrl ?: return false
        return mine.scheme == theirs.scheme && mine.host == theirs.host && mine.port == theirs.port
    }

    /**
     * 比较“是不是同一个位置”时用：地址、用户、目录、证书开关都一样才算。密码和记下的证书不参与——
     * 换密码、确认新证书都还是同一个位置，沿用原来的同步文件和基线。
     */
    fun locationKey(): String = with(normalized()) { "$serverUrl|$username|$directory|$trustSelfSigned" }

    fun encode(): String = json.encodeToString(serializer(), this)

    // data class 默认的 toString 会把密码打进日志
    override fun toString(): String =
        "WebDavAccount(server=$serverUrl, user=$username, dir=$directory, trustSelfSigned=$trustSelfSigned, " +
            "pinned=${pinnedCertificate?.sha256?.take(16)})"

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String): WebDavAccount? =
            runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
    }
}

/** 界面要的非机密信息：不带密码，可以放进 UI 状态和电视面板的快照里。 */
data class WebDavAccountInfo(
    val serverUrl: String,
    val username: String,
    val directory: String,
    val trustSelfSigned: Boolean,
    val hasPassword: Boolean,
    val usedForStorage: Boolean,
    /** 本机认的服务器证书；没开证书开关时为 null。 */
    val certificate: WebDavCertificate? = null,
) {
    val hostLabel: String get() = WebDavPaths.parseServerUrl(serverUrl)?.let(WebDavPaths::hostLabel) ?: serverUrl
}

/**
 * WebDAV 账号的本机存储：地址、用户名、目录、证书开关和记下的证书指纹放普通偏好（指纹是公开信息，
 * 只需要防改不需要保密，应用私有目录够了），密码交给 [SecretStore] 用 Keystore 加密。
 *
 * [usedForStorage] 是给后续“上传到 WebDAV”留的钩子：上传还在用这个账号时，断开同步只解除
 * 同步绑定，不删账号。现在还没有上传功能，所以它总是 false。
 */
class WebDavAccountStore(context: Context, private val secrets: SecretStore) {
    private val preferences = context.getSharedPreferences("mymusic_webdav", Context.MODE_PRIVATE)
    private val _info = MutableStateFlow(readInfo())
    val info: StateFlow<WebDavAccountInfo?> = _info.asStateFlow()

    /**
     * 每次保存、清除都加一。只改密码时 [info] 前后相等、不会发新值，用账号的地方
     * （云端副本的取流客户端）靠它知道要重建、之前记下的“密码不对”要作废。
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @get:Synchronized
    val usedForStorage: Boolean get() = preferences.getBoolean(KEY_STORAGE, false)

    @Synchronized
    fun load(): WebDavAccount? {
        val url = preferences.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = secrets.secret(SECRET_PASSWORD) ?: return null
        return WebDavAccount(
            serverUrl = url,
            username = preferences.getString(KEY_USER, "").orEmpty(),
            password = password,
            directory = preferences.getString(KEY_DIR, WebDavPaths.DEFAULT_DIRECTORY).orEmpty(),
            trustSelfSigned = preferences.getBoolean(KEY_TRUST, false),
            pinnedCertificate = WebDavCertificate.decode(preferences.getString(KEY_CERT, null)),
        ).normalized()
    }

    @Synchronized
    fun save(account: WebDavAccount) {
        val normalized = account.normalized()
        secrets.saveSecret(SECRET_PASSWORD, normalized.password)
        preferences.edit()
            .putString(KEY_URL, normalized.serverUrl)
            .putString(KEY_USER, normalized.username)
            .putString(KEY_DIR, normalized.directory)
            .putBoolean(KEY_TRUST, normalized.trustSelfSigned)
            .apply {
                val certificate = normalized.pinnedCertificate
                if (certificate != null) putString(KEY_CERT, certificate.encode()) else remove(KEY_CERT)
            }
            .apply()
        _info.value = readInfo()
        _revision.value += 1
    }

    @Synchronized
    fun setUsedForStorage(value: Boolean) {
        preferences.edit().putBoolean(KEY_STORAGE, value).apply()
        _info.value = readInfo()
    }

    @Synchronized
    fun clear() {
        secrets.removeSecret(SECRET_PASSWORD)
        preferences.edit().clear().apply()
        _info.value = null
        _revision.value += 1
    }

    private fun readInfo(): WebDavAccountInfo? {
        val url = preferences.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        return WebDavAccountInfo(
            serverUrl = url,
            username = preferences.getString(KEY_USER, "").orEmpty(),
            directory = preferences.getString(KEY_DIR, WebDavPaths.DEFAULT_DIRECTORY).orEmpty(),
            trustSelfSigned = preferences.getBoolean(KEY_TRUST, false),
            hasPassword = secrets.hasSecret(SECRET_PASSWORD),
            usedForStorage = preferences.getBoolean(KEY_STORAGE, false),
            certificate = WebDavCertificate.decode(preferences.getString(KEY_CERT, null))
                ?.takeIf { preferences.getBoolean(KEY_TRUST, false) },
        )
    }

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_USER = "username"
        const val KEY_DIR = "directory"
        const val KEY_TRUST = "trust_self_signed"
        const val KEY_CERT = "pinned_certificate"
        const val KEY_STORAGE = "used_for_storage"
        const val SECRET_PASSWORD = "webdav_password"
    }
}

/**
 * 路径规则。客户端所有方法收的都是“相对服务器地址”的路径（`myMusic/music/9f/9f3a….flac`），
 * 由这里逐段转义后拼到服务器地址上：
 * - 用户输入是给人看的文字，空格、中文、% 都按字面转义，不当成已经转义过的串；
 * - `.` / `..` 和空段直接丢掉，不让目录名把路径带到服务器地址外面去；
 * - 目录（collection）一律带结尾斜杠：Apache 之类对不带斜杠的目录回 301，而 OkHttp 跟随
 *   301 时会把 MKCOL / PUT 改成 GET。
 */
object WebDavPaths {
    const val DEFAULT_DIRECTORY = "myMusic/"

    fun parseServerUrl(input: String): HttpUrl? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        val builder = url.newBuilder().query(null).fragment(null)
        if (!url.encodedPath.endsWith("/")) builder.addPathSegment("")
        return builder.build()
    }

    /** 空白回落到默认目录（多半是误删）；单独一个 `/` 才表示服务器根目录。 */
    fun normalizeDirectory(input: String): String {
        if (input.isBlank()) return DEFAULT_DIRECTORY
        val parts = segments(input)
        return if (parts.isEmpty()) "" else parts.joinToString("/") + "/"
    }

    fun segments(path: String): List<String> = path
        .replace('\\', '/')
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }

    /** 把几段拼成一个相对路径；最后一段以 `/` 结尾时结果也是目录。 */
    fun join(vararg parts: String): String {
        val joined = parts.flatMap(::segments).joinToString("/")
        val collection = parts.lastOrNull { it.isNotBlank() }?.trimEnd()?.endsWith("/") == true
        return if (collection && joined.isNotEmpty()) "$joined/" else joined
    }

    fun isCollectionPath(path: String): Boolean = path.isEmpty() || path.endsWith("/")

    /** 相对路径拼成完整地址；空路径就是服务器地址本身。 */
    fun url(base: HttpUrl, path: String): HttpUrl {
        val parts = segments(path)
        if (parts.isEmpty()) return base
        val builder = base.newBuilder()
        parts.forEach { builder.addPathSegment(it) }
        if (path.trimEnd().endsWith("/") || path.trimEnd().endsWith("\\")) builder.addPathSegment("")
        return builder.build()
    }

    /**
     * 服务器回的 href 换回相对路径（逐段解码）。href 不在服务器地址下面时返回 null——
     * 有的服务器把根目录写成别的形式，调用方要能识别出来再决定怎么办。
     */
    fun relativePath(base: HttpUrl, url: HttpUrl): String? {
        val baseParts = base.pathSegments.dropLastWhile { it.isEmpty() }
        val parts = url.pathSegments.dropLastWhile { it.isEmpty() }
        if (parts.size < baseParts.size || parts.subList(0, baseParts.size) != baseParts) return null
        val rest = parts.drop(baseParts.size)
        if (rest.isEmpty()) return ""
        val joined = rest.joinToString("/")
        return if (url.encodedPath.endsWith("/")) "$joined/" else joined
    }

    fun hostLabel(url: HttpUrl): String {
        val defaultPort = HttpUrl.defaultPort(url.scheme)
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        return if (url.port == defaultPort) host else "$host:${url.port}"
    }
}
