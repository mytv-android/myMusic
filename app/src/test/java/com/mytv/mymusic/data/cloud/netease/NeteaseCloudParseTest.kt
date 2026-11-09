package com.mytv.mymusic.data.cloud.netease

import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 网易云盘响应解析：列表、转码状态、查重、错误码，喂 JSON 样本直接断言。 */
class NeteaseCloudParseTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/cloud/$name")) { "缺少样本 $name" }.bufferedReader().use { it.readText() }

    @Test
    fun listPageReadsItemsAndQuotaWithStringNumbers() {
        val page = NeteaseCloudParse.listPage(fixture("netease_cloud_get.json"))

        assertEquals(2, page.items.size)
        assertEquals(2, page.count)
        assertTrue(page.hasMore)
        // size / maxSize 是字符串
        assertEquals(1_073_741_824L, page.quota.used)
        assertEquals(64_424_509_440L, page.quota.total)
        assertEquals(64_424_509_440L - 1_073_741_824L, page.quota.free)

        val matched = page.items[0]
        assertTrue(matched.matched)
        assertEquals(1_987_654_321L, matched.songId)
        assertEquals("1987654321", matched.resolveId)
        assertEquals(31_457_280L, matched.fileSize)

        val unmatched = page.items[1]
        assertFalse(unmatched.matched)
        // songId / fileSize / addTime 都是字符串也要认
        assertEquals(3_000_000_456L, unmatched.songId)
        assertEquals(2_457_600L, unmatched.fileSize)
        assertEquals(1_762_650_100_000L, unmatched.addTime)
        // 解析 ID 用 simpleSong.id，songId 留给 byids / 转码状态
        assertEquals("3000000123", unmatched.resolveId)
    }

    @Test
    fun entriesCarryMatchOnlyWhenMatched() {
        val page = NeteaseCloudParse.listPage(fixture("netease_cloud_get.json"))
        val matched = page.items[0].toEntry()
        assertEquals("netease_cloud", matched.provider)
        assertEquals("雨季备忘", matched.title)
        assertEquals("林夕、陈奕迅", matched.artist)
        assertEquals("旧唱片", matched.album)
        assertEquals("flac", matched.ext)
        assertEquals(245, matched.durationSec)
        assertEquals(999, matched.bitrate)
        assertEquals("https://p1.music.126.net/cover.jpg", matched.cover)
        assertEquals("1987654321", matched.match?.songId)
        assertEquals("1987654321", matched.cloudSongId)

        val unmatched = page.items[1].toEntry()
        assertNull(unmatched.match)
        assertEquals("demo 录音", unmatched.title)
        // 未匹配时用上传时写的歌手 / 专辑
        assertEquals("我", unmatched.artist)
        assertEquals("未知专辑", unmatched.album)
        assertEquals(320, unmatched.bitrate)
        assertEquals("3000000123", unmatched.id)
        assertEquals("3000000456", unmatched.cloudSongId)

        val ref = unmatched.toRef()
        assertEquals("3000000123", ref.id)
        assertEquals("3000000456", ref.cloudSongId)
        assertEquals("2025-11-09T01:01:40Z", ref.uploadedAt)
    }

    @Test
    fun statusReadsObjectKeyedBySongIdAndArrayForm() {
        val body = fixture("netease_music_status.json")
        val processing = NeteaseCloudParse.status(body, "3000000456")
        assertNotNull(processing)
        assertTrue(processing!!.processing)
        assertEquals(4, processing.waitTimeSec)

        val ready = NeteaseCloudParse.status(body, "3000000789")
        assertTrue(ready!!.ready)

        val array = """{"code":200,"statuses":[{"songId":"5","status":9}]}"""
        assertTrue(NeteaseCloudParse.status(array, "5")!!.ready)

        // 查不到这一条（rep create failed 之后常见）：返回 null，交给上传器容忍
        assertNull(NeteaseCloudParse.status("""{"code":200,"statuses":{}}""", "5"))
        assertNull(NeteaseCloudParse.status("""{"code":200,"statuses":{"5":{}}}""", "5"))
    }

    @Test
    fun uploadCheckReadsSongIdAndNeedUpload() {
        val check = NeteaseCloudParse.uploadCheck(fixture("netease_upload_check.json"))
        assertEquals("3000000456", check.songId)
        assertFalse(check.needUpload)

        val fresh = NeteaseCloudParse.uploadCheck("""{"code":200,"songId":"0","needUpload":true}""")
        assertTrue(fresh.needUpload)
    }

    @Test
    fun riskControlAndLoginCodesThrowTypedErrors() {
        val risk = expectCloudError { NeteaseCloudParse.listPage("""{"code":-460,"message":"Cheating"}""") }
        assertEquals(CloudErrorKind.RiskControl, risk.kind)
        assertEquals(CloudMessages.RISK_CONTROL, risk.message)
        assertTrue(risk.pausesQueue)
        assertFalse(risk.retryable)

        val login = expectCloudError { NeteaseCloudParse.uploadCheck("""{"code":301}""") }
        assertEquals(CloudErrorKind.NotLoggedIn, login.kind)
        assertTrue(login.pausesQueue)

        // info/v2 不按 200 判死，但风控照样抛
        val submitRisk = expectCloudError { NeteaseCloudParse.submit("""{"code":-462}""") }
        assertEquals(CloudErrorKind.RiskControl, submitRisk.kind)
    }

    @Test
    fun submitRecognisesRepCreateFailed() {
        val result = NeteaseCloudParse.submit("""{"code":400,"message":"rep create failed"}""")
        assertFalse(result.ok)
        assertTrue(result.repCreateFailed)
        assertTrue(NeteaseCloudParse.submit("""{"code":200,"songId":"42"}""").ok)
        // 200 但没给 songId 不算成功
        assertFalse(NeteaseCloudParse.submit("""{"code":200}""").ok)
    }

    @Test
    fun tokenHostsAndChunkResponses() {
        val token = NeteaseCloudParse.token(
            """{"code":200,"result":{"resourceId":"r1","objectKey":"obj/a b/x.mp3","token":"t"}}""",
        )
        assertEquals("obj/a b/x.mp3", token.objectKey)

        val hosts = NeteaseCloudParse.uploadHosts(
            """{"lbs":"x","upload":["http://45.127.129.8/","https://nosup-hz1.127.net","ftp://x"]}""",
        )
        assertEquals(listOf("https://nosup-hz1.127.net", "http://45.127.129.8"), hosts)

        val chunk = NeteaseCloudParse.nosChunk("""{"requestID":"q","offset":8388608,"context":"ctx-1","callbackRetMsg":""}""")
        assertEquals(8_388_608L, chunk.offset)
        assertEquals("ctx-1", chunk.context)
        assertEquals(42L, NeteaseCloudParse.nosOffset("""{"offset":42}"""))
        assertNull(NeteaseCloudParse.nosOffset("""{"errMsg":"no context"}"""))
    }

    @Test
    fun publishAcceptsCreatedAndCloudUrlFallbacks() {
        assertEquals(201, NeteaseCloudParse.publish("""{"code":201}"""))
        val viaArray = NeteaseCloudParse.cloudUrl("""{"code":200,"data":[{"url":"https://m701.music.126.net/a.flac","type":"FLAC","br":999000,"size":9}]}""")
        assertEquals("flac", viaArray!!.ext)
        assertEquals(999, viaArray.bitrate)
        val viaObject = NeteaseCloudParse.cloudUrl("""{"code":200,"data":{"downloadUrl":"https://x/b.mp3"}}""")
        assertEquals("https://x/b.mp3", viaObject!!.url)
        assertNull(NeteaseCloudParse.cloudUrl("""{"code":200,"data":{"url":null}}"""))
        assertEquals("[00:01]hi", NeteaseCloudParse.cloudLyric("""{"code":200,"lrc":{"lyric":"[00:01]hi"}}"""))
        assertEquals("[00:02]yo", NeteaseCloudParse.cloudLyric("""{"code":200,"data":{"lyric":"[00:02]yo"}}"""))
    }

    private fun expectCloudError(block: () -> Unit): CloudException {
        try {
            block()
        } catch (error: CloudException) {
            return error
        }
        fail("应该抛 CloudException")
        throw AssertionError()
    }
}
