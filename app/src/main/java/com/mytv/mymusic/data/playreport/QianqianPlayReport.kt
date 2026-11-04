package com.mytv.mymusic.data.playreport

import com.mytv.mymusic.data.native.NativeCrypto
import com.mytv.mymusic.data.native.NativeHttp

/**
 * 千千音乐（百度音乐）播放上报。
 *
 * 官方 Web 播放器的埋点像素：`GET https://click-qianqian.91q.com/v.gif`，
 * 无签名、无 Cookie、无登录，返回 0 字节 GIF。
 * 一首歌打四个点：`playstart`（开播）→ `playsong100ms`（起播）→
 * `60play`（放到 59 秒，千千「满 59 秒算一次有效播放」的规则）→
 * `playend`（暂停 / 切歌 / 播完）。
 *
 * ⚠️ 0 字节回包，**无法确认服务端是否计入**，只能算尽力而为；失败静默。
 */
internal open class QianqianPlayReporter(
    private val cookie: () -> String,
) : BasePlayReporter() {

    override val providerId: String = "qianqian"

    override fun reportSummary(): String =
        "官方播放器埋点像素，零凭证零签名，尽力而为"

    /** 官方 cuid：cookie 里没有就生成一个并写 3650 天，这里进程内稳定即可。 */
    private val cuid: String = NativeCrypto.randomString(32)

    /** 每首歌的随机串（官方用 Math.random，不是真时间戳）。 */
    private var stamp = ""

    /** 已经打过 60play 没有；一首只打一次。 */
    private var sixtySecondReported = false

    override fun onPlayStart(trackId: String, durationMs: Long, extra: Map<String, String>) {
        stamp = "13_${(0..9).shuffled().take(12).joinToString("")}"
        sixtySecondReported = false
        PlayReportHub.launch { send(type = "playstart", trackId = trackId, extra = extra) }
    }

    override fun onPlayProgress(trackId: String, positionMs: Long, durationMs: Long, extra: Map<String, String>) {
        PlayReportHub.launch { send(type = "playsong100ms", trackId = trackId, extra = extra) }
    }

    override fun onPlayTick(trackId: String, positionMs: Long, durationMs: Long, extra: Map<String, String>) {
        // 放到 59 秒：千千的有效播放规则
        if (sixtySecondReported || positionMs < SIXTY_SECOND_MS) return
        sixtySecondReported = true
        PlayReportHub.launch { send(type = "60play", trackId = trackId, extra = extra) }
    }

    override fun onPlayEnd(
        trackId: String,
        playedMs: Long,
        durationMs: Long,
        reason: PlayEndReason,
        extra: Map<String, String>,
    ) {
        if (playedMs <= 0) return
        PlayReportHub.launch {
            send(
                type = "playend",
                trackId = trackId,
                extra = extra,
                extraParams = mapOf(
                    // 官方 endflag=1 正常结束；加载失败 / 重试超限才用别的值
                    "endflag" to "1",
                    "pos" to (playedMs / 1000).toString(),
                    "duration" to (durationMs / 1000).toString(),
                ),
            )
        }
    }

    protected open suspend fun send(
        type: String,
        trackId: String,
        extra: Map<String, String>,
        extraParams: Map<String, String> = emptyMap(),
    ) {
        val jar = cookieJar()
        val tsid = trackId.trim()
        if (tsid.isEmpty()) return
        val params = buildMap {
            put("pid", PID)
            put("ref", "web")
            put("userid", jar["userid"].orEmpty())
            put("action", "play")
            put("timestamp", stamp)
            put("flag", "1")
            put("buffct", "0")
            put("s2l", "-1")
            put("from", "")
            put("sid", tsid)
            put("playrefer", "")
            put("playreferid", "")
            put("rate", "")
            put("isVip", "0")
            put("isPay", "0")
            put("cuid", jar["cuid"]?.takeIf { it.isNotBlank() } ?: cuid)
            put("refer", "")
            putAll(extraParams)
            if (type == "playstart") {
                put("songfrom", if (tsid.all(Char::isDigit)) "owner" else "thirdParty")
            }
            if (type == "playsong100ms") {
                put("l2p", "0")
                put("s2p", "0")
            }
            put("browser_ver", "")
            put("os", "android")
            put("os_v", ANDROID_RELEASE)
        }
        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        NativeHttp.get("$PIXEL_URL?$query", userAgent = PIXEL_USER_AGENT)
    }

    private fun cookieJar(): Map<String, String> =
        cookie().split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }
            .toMap()

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val PIXEL_URL = "https://click-qianqian.91q.com/v.gif"
        const val PID = "304"
        const val SIXTY_SECOND_MS = 59_000L
        val ANDROID_RELEASE: String = runCatching { android.os.Build.VERSION.RELEASE }.getOrNull().orEmpty()
        const val PIXEL_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
