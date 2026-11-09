package com.mytv.mymusic.data.cloud

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.mytv.mymusic.AppLog
import com.mytv.mymusic.data.SettingsStore
import com.mytv.mymusic.data.cloud.netease.NeteaseCloudApi
import com.mytv.mymusic.data.cloud.netease.cookieValue
import com.mytv.mymusic.data.cloud.qq.QqCloudApi
import com.mytv.mymusic.data.webdav.WebDavAccountStore
import com.mytv.mymusic.data.webdav.WebDavClient
import com.mytv.mymusic.data.webdav.WebDavPaths
import com.mytv.mymusic.data.webdav.WebDavRoutingDataSourceFactory
import com.mytv.mymusic.data.webdav.WebDavStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 应用里唯一一份三种云端的实例（本地歌的云端副本：取流、删除、歌词；以后上传也用它）。
 *
 * - 网易云盘 / QQ 云盘：Cookie 每次调用现取，换账号不用重建；网易的上传断点存在实例里，所以只建一个。
 * - WebDAV：用同步里配好的那个账号（密码在 Keystore），账号一改（[WebDavAccountStore.revision]）就重建，
 *   免得拿旧密码、旧证书指纹去连。
 * - [routing]：播放器和过渡分析共用的 DataSource 外壳，WebDAV 服务器下面的地址带上认证和证书钉住，
 *   其余地址原样交给原来的工厂；云端副本因此可以像在线歌一样进播放缓存，但从不进“下载”。
 */
class CloudHub(
    private val settings: SettingsStore,
    private val webDavAccounts: WebDavAccountStore,
    scope: CoroutineScope,
) {
    val netease: NeteaseCloudApi by lazy {
        NeteaseCloudApi(cookie = { settings.state.first().cookies[NETEASE_COOKIE].orEmpty() })
    }

    val qq: QqCloudApi by lazy {
        QqCloudApi(cookie = { settings.state.first().cookies[QQ_COOKIE].orEmpty() })
    }

    private val webDavLock = Any()
    @Volatile private var webDavCache: Pair<Int, WebDavStorage?>? = null

    /**
     * 每个云端的“账号指纹”：Cookie 里的登录字段、WebDAV 账号的版本号。变了说明换了账号或重新登录，
     * 这台设备之前记下的“云端文件已删除 / 要重新登录”都要作废（见 LocalLibrary）。
     */
    val accounts: Flow<Map<CloudProvider, String>> = combine(
        settings.state.map { it.cookies[NETEASE_COOKIE].orEmpty() to it.cookies[QQ_COOKIE].orEmpty() }.distinctUntilChanged(),
        webDavAccounts.info,
        webDavAccounts.revision,
    ) { (netease, qq), info, revision ->
        mapOf(
            CloudProvider.NeteaseCloud to cookieValue(netease, "MUSIC_U"),
            CloudProvider.QqCloud to qq.trim(),
            CloudProvider.WebDav to if (info != null && info.hasPassword) "$revision" else "",
        )
    }.distinctUntilChanged()

    /** 这台设备上能用的云端：网易登录了、QQ 有 Cookie、WebDAV 配好了。徽标按它判断云端副本能不能放。 */
    val configured: StateFlow<Set<CloudProvider>> = accounts
        .map { keys -> keys.filterValues { it.isNotBlank() }.keys }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** 没配置的返回 null（WebDAV 没账号、账号读不出来）。 */
    fun storage(provider: CloudProvider): CloudStorage? = when (provider) {
        CloudProvider.NeteaseCloud -> netease
        CloudProvider.QqCloud -> qq
        CloudProvider.WebDav -> webDav()
    }

    /** WebDAV 存储；读 Keystore 解密密码，不要在主线程第一次调用。 */
    fun webDav(): WebDavStorage? {
        val revision = webDavAccounts.revision.value
        webDavCache?.let { (cached, storage) -> if (cached == revision) return storage }
        synchronized(webDavLock) {
            webDavCache?.let { (cached, storage) -> if (cached == revision) return storage }
            val account = runCatching { webDavAccounts.load() }.getOrNull()
            val storage = account?.let {
                runCatching { WebDavStorage(WebDavClient(it), onRangeIgnored = ::rangeIgnored) }
                    .onFailure { error -> AppLog.w(TAG, "WebDAV 账号用不了：${error.message}") }
                    .getOrNull()
            }
            webDavCache = revision to storage
            return storage
        }
    }

    /**
     * 包一层 WebDAV 路由：地址在 WebDAV 服务器下面时用 [WebDavStorage.dataSourceFactory]
     * （带认证、证书钉住、Range），其余交给 [fallback]。服务器地址直接从账号信息里取，不用解密密码，
     * 所以普通在线歌、本机文件打开时不会碰 Keystore。
     */
    @OptIn(UnstableApi::class)
    fun routing(fallback: DataSource.Factory): DataSource.Factory = WebDavRoutingDataSourceFactory(
        baseUrl = { webDavAccounts.info.value?.serverUrl?.let(WebDavPaths::parseServerUrl) },
        webdav = { webDav()?.dataSourceFactory },
        fallback = fallback,
    )

    private fun rangeIgnored(url: String) {
        // 服务器不认 Range：能放，只是要从头读，拖动进度会慢；记一条日志方便排查
        AppLog.w(TAG, "WebDAV 服务器不支持分段读取，整首从头读：${url.substringAfterLast('/')}")
    }

    companion object {
        private const val TAG = "CloudHub"
        const val NETEASE_COOKIE = "netease"
        const val QQ_COOKIE = "qq"
    }
}
