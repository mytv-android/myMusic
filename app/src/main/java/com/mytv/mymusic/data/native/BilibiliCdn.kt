package com.mytv.mymusic.data.native

/**
 * B 站音频 CDN 的防盗链契约。`playurl` 返回的音频流在 `*.bilivideo.com` 上，取字节只认两样东西：
 *
 *  - `Referer: https://www.bilibili.com/`。不带就是 openresty 的 `403 Forbidden`（HTML 错误页，
 *    不是 JSON），播放侧只会看到「打开失败」，日志里看不出是防盗链。
 *  - 浏览器档 User-Agent。`ExoPlayerLib/...`（Media3 默认那把）和 Dalvik 默认 UA 一样被 403，
 *    连 Android 移动版 Chrome 的 UA 也过不去；桌面 Chrome/Edge 和 okhttp 都能过。
 *
 * 所以同一首歌，[BilibiliProvider] 取流（带 Referer + 桌面 UA）拿得到地址，ExoPlayer 自己去
 * 取字节时却 403。修法是把这两个头补在真正发请求的地方：播放走
 * [com.mytv.mymusic.playback.BilibiliCdnFactory]，下载走 MusicViewModel 的那次 newCall。
 *
 * 证书照常校验：这里只加请求头，不学 [MHubTls] 那套绕过 TLS 的做法。
 */
internal object BilibiliCdn {

    /** 请求头里带的来源页。带不带末尾斜杠 CDN 都认，统一用带斜杠的写法。 */
    const val REFERER = "https://www.bilibili.com/"

    /**
     * 桌面 Chrome/Edge 档的 UA。B 站接口和音频 CDN 都只认浏览器档：`ExoPlayerLib/...`
     * （Media3 默认那把）、Dalvik 默认 UA、连 Android 移动版 Chrome 的 UA 都被 403。
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0"

    /**
     * B 站自有 CDN。`akamaized.net` 这类公用 CDN 不进来——它不是 B 站专属，
     * 塞进来只会给别的源乱加 Referer。
     */
    private val hosts = setOf("bilivideo.com", "bilivideo.cn")

    /** 音频/视频流的主机是不是 B 站 CDN（`cn-sccd-fx-01-03.bilivideo.com`、`upos-sz-mirror...` 都算）。 */
    fun covers(host: String?): Boolean {
        val lower = host?.lowercase() ?: return false
        return hosts.any { lower == it || lower.endsWith(".$it") }
    }

    /** 直接给整条 URL 判，省得调用方再写一遍域名切分。 */
    fun coversUrl(url: String): Boolean = covers(url.hostFromUrl())

    /** 下载这类不走 ExoPlayer 的场景：给 OkHttp 请求加上的头。 */
    fun headers(url: String): Map<String, String> =
        if (covers(url.hostFromUrl())) {
            mapOf("Referer" to REFERER, "User-Agent" to USER_AGENT)
        } else {
            emptyMap()
        }

    /** 不引 android.net.Uri：这个对象在纯 JVM 单测里也要能用。 */
    private fun String.hostFromUrl(): String {
        val schemeEnd = indexOf("://")
        var start = if (schemeEnd >= 0) schemeEnd + 3 else 0
        // userinfo@host 的形式（基本不会出现在 CDN 地址上，顺手处理掉）
        val at = indexOf('@', start)
        if (at >= 0) start = at + 1
        val slash = indexOf('/', start)
        val colon = indexOf(':', start)
        var end = length
        if (slash in 0 until end) end = slash
        if (colon in 0 until end) end = colon
        return substring(start, end)
    }
}
