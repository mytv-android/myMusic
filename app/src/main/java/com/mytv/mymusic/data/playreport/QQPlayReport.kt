package com.mytv.mymusic.data.playreport

import com.mytv.mymusic.data.native.NativeCrypto
import com.mytv.mymusic.data.native.NativeHttp

/**
 * QQ音乐播放上报。
 *
 * 官方 Web 播放器用两种方式把播放行为回传，都**不需要签名、加密或 Cookie**：
 *  - 播放时长：一个 1×1 图片信标 `GET stat6.y.qq.com/pc/fcgi-bin/cgi_music_webreport.fcg`，
 *    切歌时把上一首听了多少秒一起报上去
 *  - 播放事件：`POST stat6.y.qq.com/sdk/fcgi-bin/sdk.fcg`，`cmd=25` 的播放计数埋点
 *
 * 上报要的是歌曲的**数字 id**（`Song.extra["song_id"]`），不是 songmid，所以拿不到时就跳过。
 */
internal open class QQPlayReporter(
    private val cookie: () -> String,
) : BasePlayReporter() {

    override val providerId: String = "qq"

    override fun reportSummary(): String =
        "播放时长与播放计数埋点，切歌时把上一首听了多少秒报上去"

    /** 本进程的 guid，对应官方 pgv_pvid / 客户端生成的设备标识。 */
    private val guid: String = NativeCrypto.randomString(32)

    /** 这首歌的开播时刻（unix 秒），官方 Fstart_time 用。 */
    private var startedAt = 0L

    override fun onPlayStart(trackId: String, durationMs: Long, extra: Map<String, String>) {
        startedAt = System.currentTimeMillis() / 1000
        val songId = songId(extra) ?: return
        PlayReportHub.launch {
            // 播放事件埋点：point=3 是播放计数
            deliver(
                SDK_URL,
                sdkBody(point = 3, songId = songId, value = 0),
            )
        }
    }

    override fun onPlayProgress(trackId: String, positionMs: Long, durationMs: Long, extra: Map<String, String>) {
        // 时长类信标在切歌时一次报完，没有按进度的打点
    }

    override fun onPlayEnd(
        trackId: String,
        playedMs: Long,
        durationMs: Long,
        reason: PlayEndReason,
        extra: Map<String, String>,
    ) {
        val songId = songId(extra) ?: return
        val seconds = (playedMs / 1000).coerceAtLeast(0)
        PlayReportHub.launch {
            deliver(webReportUrl(songId = songId, playSeconds = seconds), "")
        }
    }

    /** 两个通道都不需要签名和 Cookie，直接 GET / POST；测试里替身成记录。 */
    protected open suspend fun deliver(url: String, body: String) {
        if (body.isBlank()) {
            NativeHttp.get(url, userAgent = QQ_USER_AGENT)
        } else {
            NativeHttp.postJson(url, body, userAgent = QQ_USER_AGENT)
        }
    }

    /** 官方 `Ftype`：有 songmid 且 type 为 0 时取 3，否则 1。 */
    private fun webReportUrl(songId: String, playSeconds: Long): String = buildString {
        append(WEB_REPORT_URL)
        append("?Count=1")
        append("&Fqq=").append(uin())
        append("&Fguid=").append(guid)
        append("&Ffromtag1=10050")
        append("&Ffromtag2=").append(songId)
        append("&Fsong_id=").append(songId)
        append("&Fplay_time=").append(playSeconds)
        append("&Fstart_time=").append(startedAt)
        append("&Ftype=3")
        append("&Fversion=1")
        append("&Fid1=0")
    }

    /** `cmd=25` 的播放事件埋点体。 */
    private fun sdkBody(point: Int, songId: String, value: Long): String =
        """
        {"common":{"_appid":"qqmusic","_uid":"${uin()}","_platform":11,
        "_account_source":"0","_channelid":"","_os":"pc","_app":"pc",
        "_opertime":${System.currentTimeMillis() / 1000},"_network_type":""},
        "items":[{"cmd":"25","int1":$point,"str1":"","int2":$songId,"str2":"PC",
        "int3":$value,"str3":"other","int4":0,"str5":"","str6":"${traceId()}",
        "str7":"","str8":"","str9":"","str10":""}]}
        """.trimIndent()

    /** 官方 str6：毫秒时间戳 + 9 位随机。 */
    private fun traceId(): String =
        "${System.currentTimeMillis()}${(100_000_000..999_999_999).random()}"

    /** cookie 里的 uin；未登录给 0。 */
    private fun uin(): String {
        val jar = cookieJar()
        for (key in listOf("uin", "p_uin", "wxuin")) {
            val raw = jar[key].orEmpty().trim()
            if (raw.isNotEmpty()) {
                val digits = raw.trimStart('o').filter { it.isDigit() }
                if (digits.isNotEmpty() && digits.length < 14) return digits
            }
        }
        return "0"
    }

    private fun cookieJar(): Map<String, String> =
        cookie().split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }
            .toMap()

    /** 上报要数字 song_id；搜索 / 解析结果都会带在 extra 里。 */
    private fun songId(extra: Map<String, String>): String? =
        extra["song_id"]?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    private companion object {
        const val WEB_REPORT_URL = "https://stat6.y.qq.com/pc/fcgi-bin/cgi_music_webreport.fcg"
        const val SDK_URL = "https://stat6.y.qq.com/sdk/fcgi-bin/sdk.fcg"
        const val QQ_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
