package com.mytv.mymusic.data.playreport

import com.mytv.mymusic.data.native.SodaPlayReporter
import com.mytv.mymusic.data.native.SodaProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 各平台上报的触发条件：什么时候发、什么时候不该发。
 * 报文格式本身在上游协议文档里，这里只守住「别乱发」这条线。
 */
class PlatformPlayReportTest {

    /** 可拨的假时钟：状态机按帧间隔算播放时长，真实墙钟在测试里不可控。 */
    private class FakeClock(var now: Long = 1_700_000_000_000L)

    /**
     * 喂帧 + 等上报落地。[log] 必须就是被测 reporter 往里写的那個列表，
     * 这样断言看到的是同一份数据。
     */
    private class Driver<T>(
        private val reporter: PlayReporter,
        private val clock: FakeClock,
        private val log: MutableList<T>,
    ) {
        fun tick(
            trackId: String,
            playing: Boolean = true,
            positionMs: Long = 0,
            durationMs: Long = 1_000,
            advanceMs: Long = 0,
            finished: Boolean = false,
            extra: Map<String, String> = emptyMap(),
        ) {
            clock.now += advanceMs
            reporter.onFrame(
                PlayFrame(trackId, playing, positionMs, durationMs, finished, extra),
            )
            settle()
        }

        /** 上报在自己的 IO scope 里跑，等到不再有新事件再返回。 */
        private fun settle() {
            val deadline = System.currentTimeMillis() + 3_000
            var seen = -1
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                if (log.size == seen) return
                seen = log.size
            }
        }
    }

    // ---------------- 网易云 ----------------

    @Test
    fun `netease needs a logged in cookie`() {
        val clock = FakeClock()
        val actions = mutableListOf<String>()
        val reporter = object : NetEasePlayReporter({ "" }) {
            override fun now(): Long = clock.now
            override suspend fun send(action: String, fields: Map<String, Any>) {
                actions += action
            }
        }
        Driver(reporter, clock, actions).tick("123", positionMs = 0, durationMs = 1_000, advanceMs = 1_000)
        Driver(reporter, clock, actions).tick(
            "123", positionMs = 1_000, durationMs = 1_000, advanceMs = 1_000, finished = true,
        )
        assertTrue("未登录不该上报", actions.isEmpty())
    }

    @Test
    fun `netease reports start immediately but play only when substantial`() {
        val clock = FakeClock()
        val actions = mutableListOf<String>()
        val reporter = object : NetEasePlayReporter({ "MUSIC_U=abc; __csrf=xyz" }) {
            override fun now(): Long = clock.now
            override suspend fun send(action: String, fields: Map<String, Any>) {
                actions += action
            }
        }
        val driver = Driver(reporter, clock, actions)
        // 一首 1 秒的歌
        driver.tick("123", positionMs = 0, durationMs = 1_000)
        assertEquals(listOf("startplay"), actions)

        // 只听 20% 就切走：听歌排行不计
        driver.tick("123", positionMs = 200, durationMs = 1_000, advanceMs = 200)
        actions.clear()
        reporter.noteUserAction(PlayEndReason.Next)
        driver.tick("456", positionMs = 0, durationMs = 1_000)
        assertTrue("放了一点不该计听歌排行", actions.none { it == "play" })

        // 放够 80%：计入
        driver.tick("456", positionMs = 200, durationMs = 1_000, advanceMs = 200)
        driver.tick("456", positionMs = 400, durationMs = 1_000, advanceMs = 200)
        driver.tick("456", positionMs = 600, durationMs = 1_000, advanceMs = 200)
        driver.tick("456", positionMs = 800, durationMs = 1_000, advanceMs = 200)
        actions.clear()
        reporter.noteUserAction(PlayEndReason.Next)
        driver.tick("789", positionMs = 0, durationMs = 1_000)
        assertTrue("放到 80% 以上要计", actions.contains("play"))
    }

    @Test
    fun `netease sends startplay once per track`() {
        val clock = FakeClock()
        val actions = mutableListOf<String>()
        val reporter = object : NetEasePlayReporter({ "MUSIC_U=abc" }) {
            override fun now(): Long = clock.now
            override suspend fun send(action: String, fields: Map<String, Any>) {
                actions += action
            }
        }
        val driver = Driver(reporter, clock, actions)
        driver.tick("123", positionMs = 0, durationMs = 1_000)
        driver.tick("123", positionMs = 500, durationMs = 1_000, advanceMs = 500)
        driver.tick("123", positionMs = 1_000, durationMs = 1_000, advanceMs = 500, finished = true)
        assertEquals(1, actions.count { it == "startplay" })
    }

    // ---------------- QQ音乐 ----------------

    @Test
    fun `qq skips songs without a numeric id`() {
        val clock = FakeClock()
        val sent = mutableListOf<String>()
        val reporter = object : QQPlayReporter({ "" }) {
            override fun now(): Long = clock.now
            override suspend fun deliver(url: String, body: String) {
                sent += url
            }
        }
        val driver = Driver(reporter, clock, sent)
        // songmid 在 trackId，数字 id 不在 extra 里：跳过
        driver.tick("003OUlho2HcRUE", extra = emptyMap())
        reporter.noteUserAction(PlayEndReason.Next)
        driver.tick("other", extra = emptyMap())
        assertTrue("没有数字 song_id 就不该上报", sent.isEmpty())
    }

    @Test
    fun `qq reports the previous song on track change`() {
        val clock = FakeClock()
        val sent = mutableListOf<String>()
        val reporter = object : QQPlayReporter({ "uin=o0123456789" }) {
            override fun now(): Long = clock.now
            override suspend fun deliver(url: String, body: String) {
                sent += url
            }
        }
        val driver = Driver(reporter, clock, sent)
        driver.tick("003OUlho2HcRUE", extra = mapOf("song_id" to "108986"))
        assertTrue("开播要有一次埋点", sent.any { it.contains("sdk.fcg") })

        sent.clear()
        driver.tick(
            "003OUlho2HcRUE", positionMs = 300, durationMs = 1_000, advanceMs = 300,
            extra = mapOf("song_id" to "108986"),
        )
        reporter.noteUserAction(PlayEndReason.Next)
        driver.tick("004abc", extra = mapOf("song_id" to "108987"))
        val durationReport = sent.firstOrNull { it.contains("webreport") }
        assertTrue("切歌要报上一首的时长", durationReport != null)
        assertTrue(durationReport!!.contains("Fsong_id=108986"))
    }

    // ---------------- 千千 ----------------

    @Test
    fun `qianqian sends four points across a song`() {
        val clock = FakeClock()
        val types = mutableListOf<Pair<String, String>>()
        val reporter = object : QianqianPlayReporter({ "" }) {
            override fun now(): Long = clock.now
            override suspend fun send(
                type: String,
                trackId: String,
                extra: Map<String, String>,
                extraParams: Map<String, String>,
            ) {
                types += type to trackId
            }
        }
        val driver = Driver(reporter, clock, types)
        // 一首 90 秒的歌，跨过 59 秒那一点
        driver.tick("T123", positionMs = 0, durationMs = 90_000)
        driver.tick("T123", positionMs = 600, durationMs = 90_000, advanceMs = 600)
        driver.tick("T123", positionMs = 59_000, durationMs = 90_000, advanceMs = 59_000)
        reporter.noteUserAction(PlayEndReason.Next)
        driver.tick("T124", positionMs = 0, durationMs = 90_000)
        // 只看 T123 的四个点，T124 的 playstart 不算
        assertEquals(
            listOf("playstart", "playsong100ms", "60play", "playend"),
            types.filter { it.second == "T123" }.map { it.first },
        )
    }

    @Test
    fun `qianqian sixty second point fires once`() {
        val clock = FakeClock()
        val types = mutableListOf<Pair<String, String>>()
        val reporter = object : QianqianPlayReporter({ "" }) {
            override fun now(): Long = clock.now
            override suspend fun send(
                type: String,
                trackId: String,
                extra: Map<String, String>,
                extraParams: Map<String, String>,
            ) {
                types += type to trackId
            }
        }
        val driver = Driver(reporter, clock, types)
        driver.tick("T123", positionMs = 0, durationMs = 90_000)
        driver.tick("T123", positionMs = 59_000, durationMs = 90_000, advanceMs = 59_000)
        driver.tick("T123", positionMs = 70_000, durationMs = 90_000, advanceMs = 11_000)
        assertEquals(1, types.count { it.first == "60play" })
    }

    // ---------------- 酷我 ----------------

    @Test
    fun `kuwo only reports songs that were actually played`() {
        val clock = FakeClock()
        val bodies = mutableListOf<String>()
        val reporter = object : KuwoPlayReporter({ "" }) {
            override fun now(): Long = clock.now
            override suspend fun deliver(body: String) {
                bodies += body
            }
        }
        val driver = Driver(reporter, clock, bodies)
        // 一秒没放就切走：不上报
        driver.tick("1", playing = false, positionMs = 0, durationMs = 240_000)
        reporter.noteUserAction(PlayEndReason.Next)
        driver.tick("2", positionMs = 0, durationMs = 180_000)
        assertTrue("没真听过就不该上报", bodies.isEmpty())

        driver.tick("2", positionMs = 30_000, durationMs = 180_000, advanceMs = 30_000)
        reporter.noteUserAction(PlayEndReason.Next)
        driver.tick("3", positionMs = 0, durationMs = 180_000)
        assertEquals(1, bodies.size)
        assertTrue(bodies.single().startsWith("2<"))
        assertTrue(bodies.single().contains("ACT:PLAY_MUSIC"))
        assertTrue(bodies.single().contains("RID:2"))
    }

    @Test
    fun `every reporter declares a summary for the settings page`() {
        val reporters: List<PlayReporter> = listOf(
            SodaPlayReporter(SodaProvider(), { "" }, { "1" }),
            NetEasePlayReporter({ "" }),
            QQPlayReporter({ "" }),
            KuwoPlayReporter({ "" }),
            QianqianPlayReporter({ "" }),
        )
        reporters.forEach { reporter ->
            assertTrue("${reporter.providerId} 缺少上报说明", reporter.reportSummary().isNotBlank())
        }
    }
}
