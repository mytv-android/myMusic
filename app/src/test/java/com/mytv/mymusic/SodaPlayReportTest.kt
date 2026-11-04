package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.playreport.PlayEndReason
import com.mytv.mymusic.data.playreport.PlayFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val testJson = Json { ignoreUnknownKeys = true }

private fun parseBody(body: String): JsonObject = testJson.parseToJsonElement(body).jsonObject

private fun JsonObject.eventName(): String = this["event"]!!.jsonPrimitive.content

private fun JsonObject.params(): JsonObject =
    testJson.parseToJsonElement(this["params"]!!.jsonPrimitive.content).jsonObject

/** events 数组里的第 [index] 条。 */
private fun JsonObject.eventsAt(index: Int): JsonObject =
    this["events"]!!.jsonArray[index].jsonObject

private fun JsonObject.eventList(): List<JsonObject> =
    (0 until this["events"]!!.jsonArray.size).map { this.eventsAt(it) }

/**
 * 汽水播放上报状态机：时长累计、首次进度阈值、暂停下降沿、切歌三连发。
 * 对应上游 sodam `play_report.rs` 的几条单测。
 */
class SodaPlayReportTest {

    /** 记录发出去的上报，不发网络请求，时钟可拨。 */
    private class RecordingReporter : SodaPlayReporter(
        provider = SodaProvider(),
        cookie = { "sessionid=fake" },
        deviceId = { "7123456789012345" },
    ) {
        val events = mutableListOf<SodaReportEvent>()
        var clock = 1_700_000_000_000L

        override fun now(): Long = clock

        override suspend fun resolveUserId(cookie: String): String = ""

        override suspend fun deliver(event: SodaReportEvent) {
            events += event
        }

        fun applogBodies(): List<JsonObject> =
            events.filterIsInstance<SodaReportEvent.Applog>().map { parseBody(it.body) }

        /** 所有埋点请求里的事件名，按发送顺序拉平。 */
        fun applogEventNames(): List<String> =
            applogBodies().flatMap { body -> body.eventList().map { it.eventName() } }

        fun businessBodies(): List<JsonObject> =
            events.filter { it !is SodaReportEvent.Applog }.map { parseBody(it.body) }

        /** 唯一的「结束一批」埋点：events 里有 audio_over 的那个请求。 */
        fun endBatch(): List<JsonObject> =
            applogBodies().first { body -> body.eventList().any { it.eventName() == "audio_over" } }
                .eventList()

        /** 推进时钟并喂一帧；等这一帧触发的上报全部落地再返回。 */
        fun tick(
            trackId: String,
            playing: Boolean,
            positionMs: Long,
            durationMs: Long,
            advanceMs: Long = 0,
            finished: Boolean = false,
        ) {
            clock += advanceMs
            onFrame(PlayFrame(trackId, playing, positionMs, durationMs, finished))
            awaitQuiesce()
        }

        /** 上报在自己的 IO scope 里跑，等到不再有新事件才算稳。 */
        private fun awaitQuiesce() {
            val deadline = System.currentTimeMillis() + 5_000
            var seen = -1
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                if (events.size == seen) return
                seen = events.size
            }
            throw AssertionError("上报没有稳定下来，最后 ${events.size} 条")
        }
    }

    @Test
    fun `first track reports play plus play count`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)

        val applog = reporter.applogBodies()
        assertEquals(listOf("audio_play"), reporter.applogEventNames())
        assertEquals(1, applog.size)

        val business = reporter.businessBodies()
        // 最近播放 + 播放计数，各一条
        assertEquals(2, business.size)
        assertEquals(
            "111",
            business.first { it.containsKey("media") }["media"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "play",
            business.first { it.containsKey("actions") }["actions"]!!.jsonArray[0].jsonObject["action_type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `progress reported once after half second`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.events.clear()
        // 0.5s 阈值是「严格大于」，正好 500ms 不算
        reporter.tick("111", playing = true, positionMs = 500, durationMs = 240_000)
        assertTrue("进度刚到阈值不应上报", reporter.applogBodies().isEmpty())

        reporter.tick("111", playing = true, positionMs = 600, durationMs = 240_000)
        val bodies = reporter.applogBodies()
        assertEquals(listOf("player_metrics"), reporter.applogEventNames())
        val params = bodies.single().eventsAt(0).params()
        assertEquals("progressed", params["type"]!!.jsonPrimitive.content)
        // extra_data 是进度秒的字符串，duration 是总时长秒
        assertEquals("0.6", params["extra_data"]!!.jsonPrimitive.content)
        assertEquals("240.0", params["duration"]!!.jsonPrimitive.content)

        // 同一首只发一次
        reporter.events.clear()
        reporter.tick("111", playing = true, positionMs = 5_000, durationMs = 240_000)
        assertTrue(reporter.applogBodies().isEmpty())
    }

    @Test
    fun `paused time is not counted`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        // 真放了 1 秒
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 1_000)
        reporter.events.clear()
        // 暂停：只发 audio_over，不补 audio_finish / finish 指标
        reporter.tick("111", playing = false, positionMs = 1_000, durationMs = 240_000)
        val bodies = reporter.applogBodies()
        assertEquals(listOf("audio_over"), reporter.applogEventNames())
        val params = bodies.single().eventsAt(0).params()
        assertEquals("1000", params["duration"]!!.jsonPrimitive.content)
        assertEquals("pause", params["audio_over_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `paused frames do not accumulate`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 1_000)
        reporter.events.clear()
        reporter.tick("111", playing = false, positionMs = 1_000, durationMs = 240_000)
        // 一直暂停着，隔了 5 秒也不该再发
        reporter.tick("111", playing = false, positionMs = 1_000, durationMs = 240_000, advanceMs = 5_000)
        assertEquals(1, reporter.applogBodies().size)
        assertEquals(
            "1000",
            reporter.applogBodies().single().eventsAt(0).params()["duration"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `tick delta is clamped so sleep is not counted`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.events.clear()
        // 进程睡了 10 分钟：最多只算 1 秒
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 600_000)
        reporter.noteUserAction(PlayEndReason.Next)
        reporter.tick("222", playing = true, positionMs = 0, durationMs = 180_000)
        assertEquals(
            "1000",
            reporter.endBatch()[0].params()["duration"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `track change sends three events in one request`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 1_000)
        reporter.events.clear()
        reporter.tick("222", playing = true, positionMs = 0, durationMs = 180_000)

        // audio_over + audio_finish + player_metrics(finish) 三条在一个 events 数组里
        val events = reporter.endBatch()
        assertEquals(listOf("audio_over", "audio_finish", "player_metrics"), events.map { it.eventName() })
        assertEquals("finish", events[2].params()["type"]!!.jsonPrimitive.content)
        // 上一首不是自然播完，默认按 next 记
        assertEquals("next", events[0].params()["audio_over_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user skip picks the requested end reason`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 1_000)
        reporter.events.clear()
        reporter.noteUserAction(PlayEndReason.Previous)
        reporter.tick("222", playing = true, positionMs = 0, durationMs = 180_000)

        assertEquals("previous", reporter.endBatch()[0].params()["audio_over_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `auto transition marks previous as finished`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 1_000)
        reporter.events.clear()
        // 播放器自动接下一首 = 上一首自然播完
        reporter.noteUserAction(PlayEndReason.Finished)
        reporter.tick("222", playing = true, positionMs = 0, durationMs = 180_000)

        assertEquals("finished", reporter.endBatch()[0].params()["audio_over_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `queue end reports the last track once`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 1_000)
        reporter.events.clear()
        // 队列播完：STATE_ENDED，不自动接下一首
        reporter.tick("111", playing = false, positionMs = 240_000, durationMs = 240_000, finished = true)
        val events = reporter.endBatch()
        assertEquals("audio_over", events[0].eventName())
        assertEquals("finished", events[0].params()["audio_over_status"]!!.jsonPrimitive.content)

        // 之后无论再怎么喂帧，都不会为同一首重复发
        reporter.events.clear()
        reporter.tick("111", playing = false, positionMs = 240_000, durationMs = 240_000, finished = true)
        reporter.tick("111", playing = false, positionMs = 240_000, durationMs = 240_000)
        assertTrue(reporter.applogBodies().isEmpty())
    }

    @Test
    fun `duration_pct is a ratio not seconds`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 1_000)
        reporter.events.clear()
        reporter.noteUserAction(PlayEndReason.Next)
        reporter.tick("222", playing = true, positionMs = 0, durationMs = 180_000)

        val params = reporter.endBatch()[0].params()
        // 播了 1 秒、总长 240 秒 → 比例 0.00417，不是秒数
        assertEquals("1000", params["duration"]!!.jsonPrimitive.content)
        assertEquals(1_000.0 / 240_000.0, params["duration_pct"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun `finish metric duration is seconds while audio_over is millis`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 1_000)
        reporter.events.clear()
        reporter.noteUserAction(PlayEndReason.Next)
        reporter.tick("222", playing = true, positionMs = 0, durationMs = 180_000)

        val events = reporter.endBatch()
        assertEquals("1000", events[0].params()["duration"]!!.jsonPrimitive.content)
        // 同名字段，player_metrics(finish) 里是秒
        assertEquals("240.0", events[2].params()["duration"]!!.jsonPrimitive.content)
        // cost 才是毫秒
        assertEquals("1000", events[2].params()["cost"]!!.jsonPrimitive.content)
    }

    @Test
    fun `played_media carries current and history newest first`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 1_000)
        reporter.noteUserAction(PlayEndReason.Next)
        reporter.tick("222", playing = true, positionMs = 0, durationMs = 180_000)
        reporter.tick("222", playing = true, positionMs = 1_000, durationMs = 180_000, advanceMs = 1_000)

        val media = reporter.playedMedia(30)
        // 当前这首在最前，紧接上一首
        assertEquals(listOf("222", "111"), media.map { it.id })
        // played_mills 是毫秒，duration 是秒
        assertEquals(1_000L, media[0].playedMs)
        assertEquals(180L, media[0].durationSeconds)
        assertEquals(1_000L, media[1].playedMs)
        assertEquals(240L, media[1].durationSeconds)
    }

    @Test
    fun `unplayed track leaves no record`() {
        val reporter = RecordingReporter()
        // 切过去一秒没放就切走：不入历史，也不发结束上报
        reporter.tick("111", playing = false, positionMs = 0, durationMs = 240_000)
        reporter.noteUserAction(PlayEndReason.Next)
        reporter.tick("222", playing = true, positionMs = 0, durationMs = 180_000)
        reporter.tick("222", playing = true, positionMs = 1_000, durationMs = 180_000, advanceMs = 1_000)

        assertEquals(listOf("222"), reporter.playedMedia(30).map { it.id })
    }

    @Test
    fun `discard drops the in flight session`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 1_000)
        reporter.events.clear()
        reporter.discard()

        assertTrue(reporter.playedMedia(30).isEmpty())
        // 之后再喂同一首，等于重新开播，不会补报上一段的结束
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000)
        val names = reporter.applogEventNames()
        // 两条上报在不同协程里跑，到达顺序不保证
        assertTrue("重新开播要发 audio_play", names.contains("audio_play"))
        assertFalse("丢弃后不该再补报上一段的结束", names.contains("audio_over"))
    }

    @Test
    fun `applog body wraps caller user header and events`() = runBlocking {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)

        val body = reporter.applogBodies().single()
        assertEquals("luna.desktop.client", body["caller"]!!.jsonPrimitive.content)
        val user = body["user"]!!.jsonObject
        assertEquals("7123456789012345", user["device_id"]!!.jsonPrimitive.content)
        assertEquals("12", user["user_type"]!!.jsonPrimitive.content)
        // 没登录：登录态全 false
        assertFalse(user["user_is_login"]!!.jsonPrimitive.content.toBoolean())
        val header = body["header"]!!.jsonObject
        assertEquals("386088", header["app_id"]!!.jsonPrimitive.content)
        // custom 是 JSON 字符串，不是嵌套对象
        val custom = testJson.parseToJsonElement(header["custom"]!!.jsonPrimitive.content).jsonObject
        assertEquals("1", custom["is_playing"]!!.jsonPrimitive.content)
        // 事件时间是秒
        val event = body.eventsAt(0)
        assertTrue(event["time"]!!.jsonPrimitive.content.toLong() > 1_700_000_000L)
        assertTrue(event["params"]!!.jsonPrimitive.content.startsWith("{"))
    }

    @Test
    fun `progress extra data keeps decimals`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.events.clear()
        reporter.tick("111", playing = true, positionMs = 12_500, durationMs = 240_000)
        assertEquals(
            "12.5",
            reporter.applogBodies().single().eventsAt(0).params()["extra_data"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `event duration is a number not a string`() {
        val reporter = RecordingReporter()
        reporter.tick("111", playing = true, positionMs = 0, durationMs = 240_000)
        reporter.tick("111", playing = true, positionMs = 1_000, durationMs = 240_000, advanceMs = 1_000)
        reporter.events.clear()
        reporter.noteUserAction(PlayEndReason.Next)
        reporter.tick("222", playing = true, positionMs = 0, durationMs = 180_000)
        val params = reporter.endBatch()[0].params()
        assertTrue(params["duration"] is JsonPrimitive)
    }
}
