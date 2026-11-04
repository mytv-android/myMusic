package com.mytv.mymusic.data.playreport

import android.util.Base64
import com.mytv.mymusic.data.native.NativeCrypto
import com.mytv.mymusic.data.native.NativeHttp

/**
 * 酷我音乐播放上报。
 *
 * 酷我 H5 播放器的埋点通道：`POST https://log.kuwo.cn/music.yl`，
 * body 是 `Base64("2<K:V|K:V|...>")` 的键值文本。无签名、无 token、无 Cookie。
 *
 * ⚠️ 这是酷我的**内部统计埋点**，不是文档化 API，也没有任何开源实现；
 * 单向只上报、没有读取接口，不保证体现在用户可见的听歌记录里，随时可能改。
 * 失败一律静默，对用户不做任何承诺。
 */
internal open class KuwoPlayReporter(
    private val cookie: () -> String,
) : BasePlayReporter() {

    override val providerId: String = "kuwo"

    override fun reportSummary(): String =
        "酷我内部统计埋点，尽力而为，不保证体现在听歌记录里"

    override fun onPlayStart(trackId: String, durationMs: Long, extra: Map<String, String>) {
        // 酷我在切歌 / 播完时一次报完，没有独立的开播打点
    }

    override fun onPlayProgress(trackId: String, positionMs: Long, durationMs: Long, extra: Map<String, String>) {
        // 同上
    }

    override fun onPlayEnd(
        trackId: String,
        playedMs: Long,
        durationMs: Long,
        reason: PlayEndReason,
        extra: Map<String, String>,
    ) {
        if (playedMs <= 0) return
        val rid = extra["rid"]?.trim()?.ifEmpty { null } ?: trackId
        val jar = cookieJar()
        PlayReportHub.launch {
            val fields = linkedMapOf(
                "SRC" to "web_2.0",
                "ACT" to "PLAY_MUSIC",
                "T" to "0",
                "PROD" to "web",
                "PLAT" to "WIN32",
                "VER" to "2.0",
                "FROM" to "www",
                "UI" to jar["userid"].orEmpty(),
                "U" to jar["sid"].orEmpty(),
                "DUR" to (durationMs / 1000).toString(),
                "PT" to (playedMs / 1000).toString(),
                "FMT" to "mp3",
                "BR" to "",
                // 官方把歌名和 rid 拼在来源里
                "PSRC" to "web->歌曲播放->($rid)",
                "LSRC" to "",
                "OSV" to "",
                "NE" to "2",
                "PTYPE" to "1",
                "RID" to rid,
            )
            val text = fields.entries.joinToString("|") { (key, value) -> "$key:$value" }
            // PLAY_MUSIC 这条的前缀是 "2"，同文件里 KWH5_LOG 才用 "2%09"
            deliver("2<$text>")
        }
    }

    /**
     * 埋点只发不读。[text] 是未编码的 `2<K:V|K:V>` 文本，默认实现负责 Base64 后发出；
     * 测试里替身成记录，避免在没有 Android 框架的单测里碰 Base64。
     */
    protected open suspend fun deliver(text: String) {
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        NativeHttp.postBytes(
            LOG_URL,
            encoded.toByteArray(Charsets.UTF_8),
            contentType = "text/plain;charset=UTF-8",
            userAgent = KUWO_USER_AGENT,
        )
    }

    private fun cookieJar(): Map<String, String> =
        cookie().split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }
            .toMap()

    private companion object {
        const val LOG_URL = "https://log.kuwo.cn/music.yl"
        const val KUWO_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
