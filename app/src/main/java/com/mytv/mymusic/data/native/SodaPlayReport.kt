package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.playreport.BasePlayReporter
import com.mytv.mymusic.data.playreport.PlayEndReason
import com.mytv.mymusic.data.playreport.PlayReportHub
import com.mytv.mymusic.data.playreport.PlayedMedia
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/**
 * 一条待发的汽水上报。[body] 是已经序列化好的请求体。
 *
 * 埋点（[Applog]）不带 Cookie、不走签名器；业务写接口（[PlayCount] / [RecentlyPlayed]）走签名器。
 */
internal sealed interface SodaReportEvent {
    val trackId: String
    val body: String
    val cookie: String

    data class Applog(
        override val trackId: String,
        override val body: String,
        override val cookie: String = "",
    ) : SodaReportEvent

    data class PlayCount(
        override val trackId: String,
        override val body: String,
        override val cookie: String,
    ) : SodaReportEvent

    data class RecentlyPlayed(
        override val trackId: String,
        override val body: String,
        override val cookie: String,
    ) : SodaReportEvent
}

/**
 * 汽水播放上报。
 *
 * 三条通道，与上游 sodam / libresoda 的 `play_report.rs` + `report.rs` 一致（AGPL-3.0，
 * 见 THIRD_PARTY_NOTICES.md）：
 *  - 进度 / 时长 → applog 埋点 `POST https://mcs.zijieapi.com/v1/json`（非签名接口）
 *  - 播放计数 → 业务接口 `POST /luna/pc/action`（走签名器）
 *  - 开播时另写一条「最近播放」`POST /luna/pc/me/recently-played-media`
 *
 * 推荐流的口味则靠 [playedMedia] 回填进 `/luna/pc/feed/song-tab` 的 `played_media`。
 * 播放状态机（何时算开播 / 暂停 / 播完）在 [com.mytv.mymusic.data.playreport.BasePlayReporter]。
 */
internal open class SodaPlayReporter(
    private val provider: SodaProvider,
    private val cookie: () -> String,
    /** 埋点的设备标识必须和业务接口同一份指纹，服务端才能把两边归到同一台设备。 */
    private val deviceId: suspend () -> String,
) : BasePlayReporter() {

    override val providerId: String = SodaProvider.SODA_PROVIDER_ID

    override fun reportSummary(): String =
        "进度 / 时长埋点 + 播放计数 + 最近播放，推荐请求里还带最近听过的曲目"

    /** 登录用户 id；账号未就绪时为空，埋点按未登录处理。 */
    @Volatile
    private var userId: String = ""

    override fun onPlayStart(trackId: String, durationMs: Long, extra: Map<String, String>) {
        PlayReportHub.launch {
            val currentCookie = cookie()
            if (currentCookie.isBlank()) {
                // 退出登录了：账号身份作废，之后按匿名设备上报
                userId = ""
                return@launch
            }
            if (userId.isEmpty()) userId = resolveUserId(currentCookie)
            deliver(
                SodaReportEvent.RecentlyPlayed(trackId, recentlyPlayedBody(trackId), currentCookie),
            )
            sendApplog(isPlaying = true, listOf(playEvent(trackId)))
            deliver(SodaReportEvent.PlayCount(trackId, actionBody(trackId), currentCookie))
        }
    }

    override fun onPlayProgress(
        trackId: String,
        positionMs: Long,
        durationMs: Long,
        extra: Map<String, String>,
    ) {
        PlayReportHub.launch {
            sendApplog(
                isPlaying = true,
                listOf(progressEvent(trackId, positionMs / 1000.0, durationMs / 1000.0)),
            )
        }
    }

    /**
     * 时长上报。暂停只发 `audio_over`；切歌 / 播完 / 换队列在一个请求里三连发
     * `audio_over` + `audio_finish` + `player_metrics(finish)`。
     */
    override fun onPlayEnd(
        trackId: String,
        playedMs: Long,
        durationMs: Long,
        reason: PlayEndReason,
        extra: Map<String, String>,
    ) {
        if (playedMs <= 0) return
        PlayReportHub.launch {
            val seconds = durationMs / 1000.0
            val events = buildList {
                add(durationEvent(trackId, playedMs, seconds, reason, playedMs, 0, finished = false))
                if (reason != PlayEndReason.Pause) {
                    add(durationEvent(trackId, playedMs, seconds, reason, playedMs, 0, finished = true))
                    add(finishMetricEvent(trackId, playedMs, seconds))
                }
            }
            sendApplog(isPlaying = false, events)
        }
    }

    override fun setUserId(value: String) {
        userId = value.trim()
    }

    /** 埋点直发 MCS：不带 Cookie、不走签名器，只需要 Content-Type 和 body 的 MD5。 */
    private suspend fun sendApplog(isPlaying: Boolean, events: List<JsonObject>) {
        val body = applogBody(isPlaying, events)
        deliver(SodaReportEvent.Applog("", body, ""))
    }

    /** 登录用户 id；测试里不联网。 */
    protected open suspend fun resolveUserId(cookie: String): String =
        runCatching { provider.userId(cookie) }.getOrNull().orEmpty()

    /** 业务写接口：走签名器，和设备指纹同一套。测试里替身成记录用。 */
    protected open suspend fun deliver(event: SodaReportEvent) {
        val currentCookie = event.cookie.ifBlank { cookie() }
        if (event is SodaReportEvent.Applog) {
            // 埋点失败不能连带后面的播放计数一起不发
            runCatching {
                NativeHttp.postJson(
                    MCS_URL,
                    event.body,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "X-SS-STUB" to NativeCrypto.md5Hex(event.body).uppercase(Locale.ROOT),
                    ),
                    userAgent = NativeHttp.DEFAULT_USER_AGENT,
                )
            }
            return
        }
        val path = when (event) {
            is SodaReportEvent.PlayCount -> ACTION_PATH
            is SodaReportEvent.RecentlyPlayed -> RECENTLY_PLAYED_PATH
        }
        runCatching { provider.businessPost(path, event.body, currentCookie) }
    }

    // ---------------------------------------------------------------------
    // 报文构造
    // ---------------------------------------------------------------------

    private suspend fun applogBody(isPlaying: Boolean, events: List<JsonObject>): String {
        val device = deviceId().trim().ifEmpty { FALLBACK_DEVICE_ID }
        val userId = userId
        val loggedIn = userId.isNotBlank()
        // header.custom 和每个 event 的 params 都是「JSON 字符串」，不是嵌套对象
        val custom = buildJsonObject {
            put("pc_channel", CHANNEL)
            put("is_playing", if (isPlaying) 1 else 0)
            put("background_type", "foreground")
            put("user_login_status", if (loggedIn) 1 else 0)
        }.toString()
        return buildJsonObject {
            put("caller", CALLER)
            putJsonObject("user") {
                put("user_unique_id", device)
                put("user_type", 12)
                put("user_id", userId)
                put("user_is_auth", loggedIn)
                put("user_is_login", loggedIn)
                put("device_id", device)
            }
            putJsonObject("header") {
                put("app_id", APP_ID)
                put("app_channel", CHANNEL)
                put("channel", CHANNEL)
                put("pc_channel", CHANNEL)
                put("app_version", APPLOG_APP_VERSION)
                put("os_name", OS_NAME)
                put("os_version", osVersion())
                put("ab_version", "")
                put("resolution", "")
                put("custom", custom)
            }
            putJsonArray("events") { events.forEach { add(it) } }
        }.toString()
    }

    private fun applogEvent(event: String, params: JsonObject) = buildJsonObject {
        put("event", event)
        put("params", params.toString())
        // 埋点时间戳是秒
        put("time", System.currentTimeMillis() / 1000)
    }

    private fun mediaParams(trackId: String) = buildJsonObject {
        put("group_type", "track")
        put("group_id", trackId)
        put("media_type", "track")
    }

    private fun playEvent(trackId: String) =
        applogEvent("audio_play", mediaParams(trackId))

    /** 进度事件：extra_data 是进度**秒**的字符串，duration 是总时长**秒**。 */
    private fun progressEvent(trackId: String, progressSeconds: Double, durationSeconds: Double) =
        applogEvent(
            "player_metrics",
            buildJsonObject {
                put("group_type", "track")
                put("group_id", trackId)
                put("media_type", "track")
                put("type", "progressed")
                put("extra_data", formatSeconds(progressSeconds))
                put("duration", durationSeconds.coerceAtLeast(0.0))
            },
        )

    /**
     * 时长事件。duration 是实际播放**毫秒**，duration_pct 是播放比例（不是秒）；
     * finished 时用 `*_finish_status` 并带前台 / 后台时长。
     */
    private fun durationEvent(
        trackId: String,
        playedMs: Long,
        durationSeconds: Double,
        reason: PlayEndReason,
        foregroundMs: Long,
        backgroundMs: Long,
        finished: Boolean,
    ): JsonObject {
        val totalMs = (durationSeconds.coerceAtLeast(0.0) * 1000.0).coerceAtLeast(1.0)
        val pct = (playedMs.coerceAtLeast(0L).toDouble() / totalMs).coerceIn(0.0, 1.0)
        return applogEvent(
            if (finished) "audio_finish" else "audio_over",
            buildJsonObject {
                put("group_type", "track")
                put("group_id", trackId)
                put("media_type", "track")
                put("duration", playedMs.coerceAtLeast(0L))
                put("duration_pct", pct)
                if (finished) {
                    put("audio_finish_status", reason.status())
                    put("foreground_dur", foregroundMs.coerceAtLeast(0L))
                    put("background_dur", backgroundMs.coerceAtLeast(0L))
                    put("is_lyrics_move", 0)
                    put("is_progress_bar_move", 0)
                } else {
                    put("audio_over_status", reason.status())
                }
            },
        )
    }

    /** 切歌 / 换队列时补发的播放器指标。注意它的 duration 是**秒**，和 audio_over 的毫秒不同。 */
    private fun finishMetricEvent(trackId: String, playedMs: Long, durationSeconds: Double) =
        applogEvent(
            "player_metrics",
            buildJsonObject {
                put("group_type", "track")
                put("group_id", trackId)
                put("media_type", "track")
                put("type", "finish")
                put("engine_version", "v4")
                put("cost", playedMs.coerceAtLeast(0L))
                put("duration", durationSeconds.coerceAtLeast(0.0))
                put("extra_data", "")
            },
        )

    private fun recentlyPlayedBody(trackId: String) = buildJsonObject {
        putJsonArray("media") {
            add(
                buildJsonObject {
                    put("id", trackId)
                    put("type", "track")
                },
            )
        }
    }.toString()

    private fun actionBody(trackId: String) = buildJsonObject {
        putJsonArray("actions") {
            add(
                buildJsonObject {
                    put("action_type", "play")
                    put("item_type", "track")
                    put("item_id", trackId)
                    put("scene_name", "")
                    put("queue_type", "")
                    put("sub_queue_type", "")
                    put("is_collected", false)
                    put("need_report_feed", false)
                    put("feed_request_id", "")
                    put("is_listen_video", false)
                },
            )
        }
    }.toString()

    private fun PlayEndReason.status(): String = when (this) {
        PlayEndReason.Finished -> "finished"
        PlayEndReason.Pause -> "pause"
        PlayEndReason.Next -> "next"
        PlayEndReason.Previous -> "previous"
        PlayEndReason.ChangeQueue -> "change_queue"
    }

    /** 官方 extra_data 用 Rust f64 Display 的写法：整数不带小数点。 */
    private fun formatSeconds(value: Double): String {
        if (!value.isFinite() || value <= 0.0) return "0"
        val rounded = Math.round(value * 1000.0) / 1000.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }

    /** 系统版本；单元测试里没有 Android 框架，取不到就留空。 */
    private fun osVersion(): String = runCatching { android.os.Build.VERSION.RELEASE }.getOrNull().orEmpty()

    private companion object {
        const val MCS_URL = "https://mcs.zijieapi.com/v1/json"
        const val ACTION_PATH = "/luna/pc/action"
        const val RECENTLY_PLAYED_PATH = "/luna/pc/me/recently-played-media"
        const val APP_ID = "386088"
        const val CALLER = "luna.desktop.client"
        const val CHANNEL = "official"
        const val APPLOG_APP_VERSION = "3.8.0"
        const val OS_NAME = "android"

        /** 设备标识还没落盘时的进程内兜底，形状同官方 getFallbackDid。 */
        val FALLBACK_DEVICE_ID: String by lazy {
            val seed = (System.currentTimeMillis() % 10_000_000_000_000L).toString()
            "100000$seed".take(19)
        }
    }
}
