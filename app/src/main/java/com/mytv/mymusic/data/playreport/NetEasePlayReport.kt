package com.mytv.mymusic.data.playreport

import com.mytv.mymusic.data.native.NativeCrypto
import com.mytv.mymusic.data.native.NativeHttp
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 网易云音乐播放上报（听歌打卡）。
 *
 * 官方客户端把「最近播放」和「听歌排行」都走 `POST /eapi/feedback/weblog`，
 * 一条 `startplay`（开播）+ 一条 `play`（结束，带播放秒数）两条日志。
 *
 * ⚠️ 只有 `clientlog.music.163.com` + `os=osx` 身份这一组组合实测能入账；
 * `music.163.com` / `interface.music.163.com` 上的同一条路径会返回 200 但被服务端静默丢弃，
 * 而且这个通道随服务端策略变化，随时可能失效。返回 200 不代表入账，
 * 只能由账号主人在官方 App 的「最近播放 / 听歌排行」里核对。
 *
 * 触发时机对齐官方：开播发 `startplay`；自然播完或播放量达到整曲 80% 才发 `play`
 * （听歌排行只计有效播放）。
 */
internal open class NetEasePlayReporter(
    private val cookie: () -> String,
    /** 落盘并随同步走的设备号；由启动时注入，别在这里摸 DataStore。 */
    private val deviceId: () -> String = { effectiveDeviceId() },
) : BasePlayReporter() {

    override val providerId: String = "netease"

    override fun reportSummary(): String =
        "听歌打卡：开播与播放量足够时各报一条，驱动最近播放和听歌排行"

    override fun onPlayStart(trackId: String, durationMs: Long, extra: Map<String, String>) {
        if (!loggedIn()) return
        PlayReportHub.launch {
            send(
                action = "startplay",
                fields = linkedMapOf(
                    "id" to trackId,
                    "type" to "song",
                    "mainsite" to "1",
                    "mainsiteWeb" to "1",
                    "content" to "id=",
                ),
            )
        }
    }

    override fun onPlayProgress(trackId: String, positionMs: Long, durationMs: Long, extra: Map<String, String>) {
        // 官方在播放器埋点里报进度，weblog 这条通道没有对应事件
    }

    override fun onPlayEnd(
        trackId: String,
        playedMs: Long,
        durationMs: Long,
        reason: PlayEndReason,
        extra: Map<String, String>,
    ) {
        // 听歌排行只计有效播放：播完，或者放过了整曲的 80%
        val substantial = reason == PlayEndReason.Finished ||
            (durationMs > 0 && playedMs >= durationMs * 4 / 5)
        if (!substantial) return
        if (!loggedIn()) return
        PlayReportHub.launch {
            send(
                action = "play",
                fields = linkedMapOf(
                    "download" to 0,
                    // 自然播完 playend，被切走 interrupt
                    "end" to if (reason == PlayEndReason.Finished) "playend" else "interrupt",
                    "id" to trackId,
                    "sourceId" to "",
                    "time" to (playedMs / 1000).coerceAtLeast(1),
                    "type" to "song",
                    "wifi" to 0,
                    "source" to "list",
                    "mainsite" to "1",
                    "mainsiteWeb" to "1",
                    "content" to "id=",
                ),
            )
        }
    }

    /** 未登录不入账：MUSIC_U 是这条通道的凭据。 */
    private fun loggedIn(): Boolean = cookieJar()["MUSIC_U"]?.isNotBlank() == true

    protected open suspend fun send(action: String, fields: Map<String, Any>) {
        val jar = cookieJar()
        val musicU = jar["MUSIC_U"].orEmpty()
        if (musicU.isBlank()) return
        val header = buildJsonObject {
            put("osver", OS_VER)
            put("deviceId", resolveDeviceId(jar))
            // os=osx 是这条通道能入账的决定性变量，换成 android / iphone / pc 都会被丢弃
            put("os", "osx")
            put("appver", APP_VER)
            put("versioncode", VERSION_CODE)
            put("mobilename", "")
            put("buildver", (System.currentTimeMillis() / 1000).toString())
            put("resolution", RESOLUTION)
            put("__csrf", jar["__csrf"].orEmpty())
            put("channel", "netease")
            put("requestId", "${System.currentTimeMillis()}${(1000..9999).random()}")
            put("MUSIC_U", musicU)
            jar["MUSIC_A"]?.takeIf { it.isNotBlank() }?.let { put("MUSIC_A", it) }
        }
        val payload = buildJsonObject {
            put("logs", "[${logJson(action, fields)}]")
            put("e_r", false)
            putJsonObject("header") { header.forEach { (key, value) -> putAny(key, value) } }
        }.toString()
        val params = NativeCrypto.encryptEApi(WEBLOG_PATH, payload)
        // 请求 Cookie 就是上面那份 header，官方客户端同样把它塞进 Cookie
        val headerCookie = header.entries.joinToString("; ") { (key, value) ->
            "${encode(key)}=${encode(value.toString())}"
        }
        NativeHttp.postForm(
            "$CLIENT_LOG_DOMAIN/eapi/feedback/weblog",
            mapOf("params" to params),
            headers = mapOf("Referer" to REFERER),
            cookie = headerCookie,
            userAgent = MAC_USER_AGENT,
        )
    }

    /** 一条日志：`{"action":..,"json":{..}}`。 */
    private fun logJson(action: String, fields: Map<String, Any>): String =
        buildJsonObject {
            put("action", action)
            putJsonObject("json") { fields.forEach { (key, value) -> putAny(key, value) } }
        }.toString()

    private fun JsonObjectBuilder.putAny(key: String, value: Any) {
        when (value) {
            is Number -> put(key, value)
            is Boolean -> put(key, value)
            else -> put(key, value.toString())
        }
    }

    private fun cookieJar(): Map<String, String> = parseCookieJar(cookie())

    private fun parseCookieJar(raw: String): Map<String, String> =
        raw.split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }
            .toMap()

    /**
     * 设备号优先取注入的那份（落盘 + 随同步走）：`MUSIC_U` 是跨设备同步的，设备号却是每进程
     * 一个随机串的话，服务端按它归并「最近播放 / 听歌排行」时同一账号每次重启都是一台新设备，
     * 历史永远攒不起来——和汽水修复前同形。
     *
     * 仍保留「读 cookie 里的 deviceId」优先：上游哪天真把这个字段回种进 Cookie，就认它的。
     */
    private fun resolveDeviceId(jar: Map<String, String>): String =
        jar["deviceId"].orEmpty().ifBlank { deviceId() }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /** 单测用来核对最终采用的设备号（注入值 / Cookie 回种值，走同一条判定）。 */
    internal fun deviceIdForTest(cookie: String): String = resolveDeviceId(parseCookieJar(cookie))

    companion object {
        /** 启动时注入的设备号：落盘并随同步走，进程内所有实例共用一份。 */
        @Volatile
        private var injectedDeviceId: String = ""

        fun configureDeviceId(value: String) {
            val next = value.trim()
            if (next == injectedDeviceId) return
            injectedDeviceId = next
        }

        /** 没注入过就现生成一份：总比每请求换一个好，也不会因为没注入就上报空值。 */
        fun effectiveDeviceId(): String =
            injectedDeviceId.ifBlank { NativeCrypto.randomString(32).also { injectedDeviceId = it } }

        const val CLIENT_LOG_DOMAIN = "https://clientlog.music.163.com"
        /** 签名用的路径要把 /eapi/ 换回 /api/，encryptEApi 内部已经处理。 */
        const val WEBLOG_PATH = "$CLIENT_LOG_DOMAIN/eapi/feedback/weblog"
        const val REFERER = "https://music.163.com/"
        const val APP_VER = "3.1.10.5100"
        const val VERSION_CODE = "140"
        const val RESOLUTION = "1920x1080"
        /**
         * 系统版本固定成常量，和 [MAC_USER_AGENT] 的 macOS 对得上：这个通道只有
         * `os=osx` 能入账，osver 却报真机 Android 版本是自相矛盾的；而设备号现在跨设备
         * 同步，两台设备对同一账号报不同系统版本比各报各的随机号更可疑。
         */
        const val OS_VER = "10.15.7"
        const val MAC_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
