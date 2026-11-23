package com.mytv.mymusic.data.subsonic

import com.mytv.mymusic.data.playreport.BasePlayReporter
import com.mytv.mymusic.data.playreport.PlayEndReason
import com.mytv.mymusic.data.playreport.PlayReportHub

internal class SubsonicPlayReporter(
    initialClient: SubsonicClient,
) : BasePlayReporter() {
    @Volatile
    private var client = initialClient
    override val providerId: String = initialClient.account.providerId

    fun updateClient(updated: SubsonicClient) {
        require(sameSubsonicLibrary(client.account, updated.account)) { "播放上报不能切到另一个曲库" }
        client = updated
    }

    override fun reportSummary(): String = "正在播放与播放计数由服务器账号记录"

    override fun onPlayStart(trackId: String, durationMs: Long, extra: Map<String, String>) {
        PlayReportHub.launch {
            if ("playbackReport" in client.account.extensions) {
                client.request(
                    "reportPlayback",
                    listOf(
                        "mediaId" to trackId,
                        "mediaType" to "song",
                        "positionMs" to "0",
                        "state" to "playing",
                        "ignoreScrobble" to "true",
                    ),
                )
            } else {
                client.request("scrobble", listOf("id" to trackId, "submission" to "false"))
            }
        }
    }

    override fun onPlayProgress(trackId: String, positionMs: Long, durationMs: Long, extra: Map<String, String>) = Unit

    override fun onPlayEnd(
        trackId: String,
        playedMs: Long,
        durationMs: Long,
        reason: PlayEndReason,
        extra: Map<String, String>,
    ) {
        PlayReportHub.launch {
            if ("playbackReport" in client.account.extensions) {
                client.request(
                    "reportPlayback",
                    listOf(
                        "mediaId" to trackId,
                        "mediaType" to "song",
                        "positionMs" to playedMs.coerceAtLeast(0).toString(),
                        "state" to "paused",
                        "ignoreScrobble" to "true",
                    ),
                )
            }
            val enough = playedMs >= 30_000 || (durationMs > 0 && playedMs * 2 >= durationMs)
            if (enough && client.account.scrobblingEnabled != false) {
                client.request("scrobble", listOf("id" to trackId, "submission" to "true"))
            }
        }
    }
}
