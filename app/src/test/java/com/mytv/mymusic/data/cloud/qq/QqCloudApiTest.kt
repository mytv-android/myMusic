package com.mytv.mymusic.data.cloud.qq

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudRef
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadSource
import com.mytv.mymusic.data.native.PlayableStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/** QQ 音乐云盘（只读）：列表解析（base64 文件名、秒 / 毫秒时间戳、matched 取值）、请求体、播放与删除。 */
class QqCloudApiTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/cloud/$name")) { "缺少样本 $name" }.bufferedReader().use { it.readText() }

    private class FakeTransport(var handler: (JsonObject, Int) -> String) : QqCloudTransport {
        val bodies = CopyOnWriteArrayList<JsonObject>()

        override suspend fun post(body: String, cookie: String): String {
            val json = Json.parseToJsonElement(body).jsonObject
            bodies += json
            return handler(json, bodies.size)
        }
    }

    private class FakePlatform : QqPlatform {
        val streamed = CopyOnWriteArrayList<Song>()
        val lyricRequests = CopyOnWriteArrayList<Song>()

        override fun login(cookie: String): Pair<String, Int> =
            if (cookie.contains("uin=")) "123456789" to 98765 else "0" to 5381

        override suspend fun stream(cookie: String, song: Song): PlayableStream {
            streamed += song
            return PlayableStream("https://ws.stream.qqmusic.qq.com/F000${song.id}.flac", "flac", 900)
        }

        override suspend fun lyrics(cookie: String, song: Song): String {
            lyricRequests += song
            return "[00:01.00]晴天"
        }
    }

    private val platform = FakePlatform()

    private fun api(transport: QqCloudTransport, cookie: String = "uin=o0123456789; qm_keyst=k") =
        QqCloudApi({ cookie }, transport, platform, requestIntervalMs = 0, retryBackoffMs = 1)

    @Test
    fun parsesBase64NamesTimestampsAndMatchFlags() = runBlocking {
        val transport = FakeTransport { _, _ -> fixture("qq_get_match_audio.json") }
        val page = api(transport).list(null)

        assertEquals(3, page.entries.size)
        assertEquals(3, page.total)
        assertNull(page.nextCursor)

        val matched = page.entries[0]
        assertEquals("qq_cloud", matched.provider)
        assertEquals("wy_7f3e2a", matched.id)
        assertEquals("晴天 - 周杰伦.flac", matched.fileName)
        assertEquals("flac", matched.ext)
        // 秒 → 毫秒
        assertEquals(1_762_650_000_000L, matched.addedAt)
        assertEquals("晴天", matched.title)
        assertEquals("周杰伦", matched.artist)
        assertEquals("叶惠美", matched.album)
        assertEquals(269, matched.durationSec)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R300x300M000000MkMni19ClKG.jpg", matched.cover)
        assertTrue(matched.playable)
        assertEquals("0039MnYb0qxYhV", matched.playableId)
        assertEquals("0039MnYb0qxYhV", matched.match!!.mid)
        assertEquals("97773", matched.match!!.songId)
        assertEquals(31_457_280L, matched.fileSize)

        val original = page.entries[1]
        assertEquals("demo录音.mp3", original.fileName)
        // 已经是毫秒的不再乘
        assertEquals(1_762_650_100_123L, original.addedAt)
        assertEquals("2", original.extra["matched"])
        assertFalse(original.playable)
        assertEquals(CloudMessages.QQ_ORIGINAL_UNPLAYABLE, original.unplayableReason)
        assertNull(original.match)
        assertEquals("", original.playableId)
        assertEquals("demo录音", original.title)
        assertEquals(2_457_600L, original.fileSize)

        // 字段名的候选写法、不是 base64 的文件名、matched=true、pmid 封面
        val plain = page.entries[2]
        assertEquals("wy_plain", plain.id)
        assertEquals("plain name.mp3", plain.fileName)
        assertEquals(0L, plain.addedAt)
        assertTrue(plain.playable)
        assertEquals("某人", plain.artist)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R300x300M000002xyz.jpg", plain.cover)
        assertEquals(1024L, plain.fileSize)
    }

    @Test
    fun requestUsesWeiyunModuleAndCommFromSpec() = runBlocking {
        val transport = FakeTransport { _, _ -> fixture("qq_get_match_audio.json") }
        api(transport).list(null)

        val body = transport.bodies.single()
        val comm = body["comm"]!!.jsonObject
        assertEquals(24, comm["ct"]!!.jsonPrimitive.int)
        assertEquals(4747474, comm["cv"]!!.jsonPrimitive.int)
        assertEquals("yqq.json", comm["platform"]!!.jsonPrimitive.content)
        assertEquals("123456789", comm["uin"]!!.jsonPrimitive.content)
        assertEquals(98765, comm["g_tk"]!!.jsonPrimitive.int)
        assertEquals(98765, comm["g_tk_new_20200303"]!!.jsonPrimitive.int)
        val req = body["req_1"]!!.jsonObject
        assertEquals("music.WeiyunMusic.WeiyunMusicRead", req["module"]!!.jsonPrimitive.content)
        assertEquals("GetMatchAudio", req["method"]!!.jsonPrimitive.content)
        val param = req["param"]!!.jsonObject
        assertEquals(1, param["page_no"]!!.jsonPrimitive.int)
        assertEquals(2000, param["page_size"]!!.jsonPrimitive.int)
        assertEquals("", param["file_id"]!!.jsonPrimitive.content)
        assertFalse(param["uin"]!!.jsonPrimitive.isString)
    }

    @Test
    fun pagingFollowsHasMoreAndTotal() = runBlocking {
        val transport = FakeTransport { body, _ ->
            val page = body["req_1"]!!.jsonObject["param"]!!.jsonObject["page_no"]!!.jsonPrimitive.int
            """{"code":0,"req_1":{"code":0,"data":{"total":3,"info_list":[{"file_id":"f$page","filename":"YS5tcDM=","matched":2}]}}}"""
        }
        val api = QqCloudApi({ "uin=1" }, transport, platform, pageSize = 1, requestIntervalMs = 0)
        val first = api.list(null)
        assertEquals("2", first.nextCursor)
        assertEquals("a.mp3", first.entries.single().fileName)
        assertEquals("3", api.list(first.nextCursor).nextCursor)
        assertNull(api.list("3").nextCursor)
    }

    @Test
    fun loginErrorsAreTyped() = runBlocking {
        val notLoggedIn = expectCloudError { api(FakeTransport { _, _ -> "" }, cookie = "qm_keyst=x").list(null) }
        assertEquals(CloudErrorKind.NotLoggedIn, notLoggedIn.kind)

        val expired = FakeTransport { _, _ -> """{"code":0,"req_1":{"code":1000}}""" }
        val error = expectCloudError { api(expired).list(null) }
        assertEquals(CloudErrorKind.NotLoggedIn, error.kind)
        assertEquals(1000, error.code)
    }

    @Test
    fun matchedPlaysViaVkeyAndOriginalIsUnplayable() = runBlocking {
        val api = api(FakeTransport { _, _ -> fixture("qq_get_match_audio.json") })
        val stream = api.resolvePlayback(CloudRef("qq_cloud", "wy_7f3e2a", playableId = "0039MnYb0qxYhV", ext = "flac"))
        assertEquals(QqCloudApi.VIA_VKEY, stream.via)
        assertEquals("0039MnYb0qxYhV", platform.streamed.single().extra["songmid"])
        assertEquals("qq", platform.streamed.single().source)

        val error = expectCloudError { api.resolvePlayback(CloudRef("qq_cloud", "wy_91c0d4")) }
        assertEquals(CloudErrorKind.Unplayable, error.kind)
        assertEquals(CloudMessages.QQ_ORIGINAL_UNPLAYABLE, error.message)
    }

    @Test
    fun lyricsUseMatchedSong() = runBlocking {
        val api = api(FakeTransport { _, _ -> fixture("qq_get_match_audio.json") })
        val entry = api.list(null).entries[0]
        assertEquals("[00:01.00]晴天", api.lyrics(entry))
        val song = platform.lyricRequests.single()
        assertEquals("97773", song.extra["song_id"])
        assertEquals("0039MnYb0qxYhV", song.extra["songmid"])
        assertEquals(269, song.duration)
        assertEquals(CloudErrorKind.NoLyrics, expectCloudError { api.lyrics(CloudRef("qq_cloud", "x")) }.kind)
    }

    @Test
    fun uploadIsUnsupportedAndQuotaUnknown() = runBlocking {
        val api = api(FakeTransport { _, _ -> fail("不该发请求"); "" })
        val error = expectCloudError { api.upload(CloudUploadSource.of("a.mp3", ByteArray(3)), CloudUploadMeta("sha256:a")) }
        assertEquals(CloudErrorKind.UploadUnsupported, error.kind)
        assertEquals(CloudMessages.QQ_UPLOAD_UNSUPPORTED, error.message)
        assertNull(api.quota())
    }

    @Test
    fun deleteSendsFileIdAndConfirmsAfterTimeout() = runBlocking {
        val transport = FakeTransport { body, n ->
            val method = body["req_1"]!!.jsonObject["method"]!!.jsonPrimitive.content
            when {
                method == "DeleteAudio" && n == 1 -> throw SocketTimeoutException("timeout")
                // 超时后翻列表：已经不在了
                method == "GetMatchAudio" -> """{"code":0,"req_1":{"code":0,"data":{"total":0,"info_list":[]}}}"""
                else -> """{"code":0,"req_1":{"code":0,"data":{}}}"""
            }
        }
        api(transport).delete(CloudRef("qq_cloud", "wy_7f3e2a"))

        val deletes = transport.bodies.filter { it["req_1"]!!.jsonObject["method"]!!.jsonPrimitive.content == "DeleteAudio" }
        assertEquals(1, deletes.size)
        val req = deletes.single()["req_1"]!!.jsonObject
        assertEquals("music.WeiyunMusic.WeiyunMusicWrite", req["module"]!!.jsonPrimitive.content)
        assertEquals("wy_7f3e2a", req["param"]!!.jsonObject["file_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun deleteRetriesOnceWhenStillListedAfterTimeout() = runBlocking {
        val transport = FakeTransport { body, _ ->
            when (body["req_1"]!!.jsonObject["method"]!!.jsonPrimitive.content) {
                "DeleteAudio" -> throw SocketTimeoutException("timeout")
                else -> """{"code":0,"req_1":{"code":0,"data":{"info_list":[{"file_id":"wy_7f3e2a","matched":2}]}}}"""
            }
        }
        val error = expectCloudError { api(transport).delete(CloudRef("qq_cloud", "wy_7f3e2a")) }
        assertEquals(CloudErrorKind.Network, error.kind)
        assertEquals(2, transport.bodies.count { it["req_1"]!!.jsonObject["method"]!!.jsonPrimitive.content == "DeleteAudio" })
    }

    @Test
    fun timestampAndFileNameHelpers() {
        assertEquals(1_000L, QqCloudParse.timestampMillis(1))
        assertEquals(99_999_999_999_000L, QqCloudParse.timestampMillis(99_999_999_999))
        assertEquals(100_000_000_000L, QqCloudParse.timestampMillis(100_000_000_000))
        assertEquals("song.mp3", QqCloudParse.decodeFileName("c29uZy5tcDM="))
        // URL 安全的 base64 也认
        assertEquals("demo录音.mp3", QqCloudParse.decodeFileName("ZGVtb-W9lemfsy5tcDM="))
        // 解出来是乱码就用原文
        assertEquals("abcd", QqCloudParse.decodeFileName("abcd"))
    }

    private suspend fun expectCloudError(block: suspend () -> Unit): CloudException {
        try {
            block()
        } catch (error: CloudException) {
            return error
        }
        fail("应该抛 CloudException")
        throw AssertionError()
    }
}
