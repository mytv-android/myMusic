package com.mytv.mymusic.data.cloud.netease

import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudUploadCandidate
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadProgress
import com.mytv.mymusic.data.cloud.CloudUploadSource
import com.mytv.mymusic.data.cloud.CloudUploadStage
import com.mytv.mymusic.data.cloud.UploadGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 上传状态机：weapi / eapi 走记参数的假通道，lbs 和 NOS 分片直传走 MockWebServer 的真实 HTTP。
 * 只有内存里的假数据，不碰真实账号。
 */
class NeteaseCloudUploaderTest {
    // 先于 NosDispatcher 初始化：它按文件大小开缓冲区
    private val content = "0123456789".toByteArray()
    private val md5Hex = MessageDigest.getInstance("MD5").digest(content).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val transport = FakeNeteaseTransport()
    private val nos = MockWebServer()
    private val dispatcher = NosDispatcher()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        // 和正式的 NOS 客户端一样：不让 OkHttp 自己重发分片
        .retryOnConnectionFailure(false)
        .build()

    private val meta = CloudUploadMeta(localId = "sha256:abc", title = "雨季备忘", artist = "林夕", album = "旧唱片", ext = "mp3")

    @Before
    fun setUp() {
        nos.dispatcher = dispatcher
        nos.start()
    }

    @After
    fun tearDown() {
        nos.close()
        http.connectionPool.evictAll()
    }

    // ------------------------------------------------------------------ 秒传 / 分片

    @Test
    fun instantUploadStillAllocatesTokenAndSkipsNos() = runBlocking {
        happyPath(needUpload = false)
        val events = api().uploader.upload(source(), meta).toList()

        val last = events.last()
        assertEquals(CloudUploadStage.READY, last.stage)
        assertTrue(last.instant)
        assertEquals(
            listOf(
                CloudUploadStage.QUEUED,
                CloudUploadStage.HASHING,
                CloudUploadStage.CHECKING,
                CloudUploadStage.UPLOADING,
                CloudUploadStage.SUBMITTING,
                CloudUploadStage.TRANSCODING,
                CloudUploadStage.PUBLISHING,
                CloudUploadStage.READY,
            ),
            events.map { it.stage }.distinct(),
        )
        // 秒传也要令牌，而且在 info/v2 之前
        val paths = transport.calls.map { it.path }
        assertEquals(1, transport.count(TOKEN))
        assertTrue(paths.indexOf(TOKEN) < paths.indexOf(SUBMIT))
        assertEquals(0, nos.requestCount)

        val check = transport.callsTo(CHECK).single()
        assertEquals(md5Hex, check.str("md5"))
        assertEquals("10", check.str("length"))
        assertEquals("mp3", check.str("ext"))
        assertEquals("csrf1", check.str("csrf_token"))

        val token = transport.callsTo(TOKEN).single()
        assertEquals("song", token.str("filename"))
        assertEquals("bkt", token.str("bucket"))
        assertEquals("audio", token.str("type"))
        assertEquals(md5Hex, token.str("md5"))

        val submit = transport.callsTo(SUBMIT).single()
        assertEquals("res-1", submit.str("resourceId"))
        assertEquals("777", submit.str("songid"))
        assertEquals("song.mp3", submit.str("filename"))
        assertEquals("雨季备忘", submit.str("song"))
        assertEquals("林夕", submit.str("artist"))
        assertEquals("旧唱片", submit.str("album"))
        assertEquals("999000", submit.str("bitrate"))
        assertEquals("888", transport.callsTo(PUBLISH).single().str("songid"))

        val ref = checkNotNull(last.ref)
        assertEquals("netease_cloud", ref.provider)
        // 解析 ID 用 byids 查回来的 simpleSong.id，songId 另存
        assertEquals("999", ref.id)
        assertEquals("888", ref.cloudSongId)
        assertEquals(md5Hex, ref.md5)
        assertEquals(10L, ref.fileSize)
        assertEquals("sha256:abc", ref.localId)
        assertEquals("ready", ref.status)
        assertEquals("", ref.playableId)
    }

    @Test
    fun chunksFollowServerOffsetAndContext() = runBlocking {
        happyPath(needUpload = true)
        val events = api().uploader.upload(source(), meta).toList()

        assertEquals(CloudUploadStage.READY, events.last().stage)
        assertFalse(events.last().instant)
        val posts = dispatcher.posts
        assertEquals(listOf(0L, 4L, 8L), posts.map { it.url.queryParameter("offset")!!.toLong() })
        assertEquals(listOf("false", "false", "true"), posts.map { it.url.queryParameter("complete") })
        assertEquals(listOf(null, "ctx-1", "ctx-2"), posts.map { it.url.queryParameter("context") })
        posts.forEach { post ->
            // objectKey 当成一个路径段：/ 变 %2F（规格 6.3）
            assertEquals("/bkt/obj%2Fa%20b%2Fx.mp3", post.url.encodedPath)
            assertEquals("tok-1", post.headers["x-nos-token"])
            assertEquals(md5Hex, post.headers["Content-MD5"])
            assertEquals("1.0", post.url.queryParameter("version"))
        }
        assertArrayEquals(content, dispatcher.received())
        assertEquals(1, dispatcher.lbsCalls)

        // 进度只增不减，最后到 10
        val uploading = events.filter { it.stage == CloudUploadStage.UPLOADING }.map { it.bytesDone }
        assertEquals(uploading.sorted(), uploading)
        assertEquals(10L, uploading.last())
    }

    @Test
    fun contentMd5FallsBackToBase64After400() = runBlocking {
        happyPath(needUpload = true)
        dispatcher.rejectFirstPostWith400 = true
        val events = api().uploader.upload(source(), meta).toList()

        assertEquals(CloudUploadStage.READY, events.last().stage)
        val md5s = dispatcher.posts.map { it.headers["Content-MD5"] }
        val base64 = MessageDigest.getInstance("MD5").digest(content).toByteString().base64()
        assertEquals(listOf(md5Hex, base64, base64, base64), md5s)
        assertEquals(listOf(0L, 0L, 4L, 8L), dispatcher.posts.map { it.url.queryParameter("offset")!!.toLong() })
        assertArrayEquals(content, dispatcher.received())
    }

    @Test
    fun droppedChunkAsksNosForOffsetInsteadOfResending() = runBlocking {
        happyPath(needUpload = true)
        // 第二片服务器收下了，但回应前断线：客户端不知道它到没到
        dispatcher.dropPost = 2
        val events = api().uploader.upload(source(), meta).toList()

        assertEquals(CloudUploadStage.READY, events.last().stage)
        // 先问 NOS 收到了多少（uploadContext），确认后从 8 接着传，不重发 4..7
        assertEquals(listOf(0L, 4L, 8L), dispatcher.posts.map { it.url.queryParameter("offset")!!.toLong() })
        assertEquals(1, dispatcher.offsetQueries.size)
        assertEquals("ctx-1", dispatcher.offsetQueries.single().url.queryParameter("context"))
        assertArrayEquals(content, dispatcher.received())
    }

    @Test
    fun cancelledUploadResumesFromCheckpoint() = runBlocking {
        happyPath(needUpload = true)
        // 第 1 次是文件开始前，第 2 次是第一片前，第 3 次是第二片前：在这里卡住再取消
        val entered = CompletableDeferred<Unit>()
        var gateCalls = 0
        var blocking = true
        val gate = UploadGate {
            gateCalls++
            if (blocking && gateCalls == 3) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val api = api(gate = gate)
        val first = Collections.synchronizedList(mutableListOf<CloudUploadProgress>())
        val job = launch { api.uploader.upload(source(), meta).collect { first += it } }
        withTimeout(10_000) { entered.await() }
        job.cancel()
        job.join()
        assertTrue(api.uploader.hasCheckpoint(meta.localId))
        assertEquals(4L, first.filter { it.stage == CloudUploadStage.UPLOADING }.maxOf { it.bytesDone })
        assertTrue(first.none { it.stage.terminal })

        blocking = false
        val second = api.uploader.upload(source(), meta).toList()

        assertEquals(CloudUploadStage.READY, second.last().stage)
        // 断点续传：不重算 MD5、不重新查重 / 申请令牌，从 4 接着传
        assertTrue(second.none { it.stage == CloudUploadStage.HASHING })
        assertEquals(1, transport.count(CHECK))
        assertEquals(1, transport.count(TOKEN))
        assertEquals(listOf(0L, 4L, 8L), dispatcher.posts.map { it.url.queryParameter("offset")!!.toLong() })
        assertEquals("ctx-1", dispatcher.posts[1].url.queryParameter("context"))
        assertArrayEquals(content, dispatcher.received())
        assertFalse(api.uploader.hasCheckpoint(meta.localId))
    }

    // ------------------------------------------------------------------ 提交 / 转码 / 发布

    @Test
    fun repCreateFailedKeepsPollingWithCheckSongId() = runBlocking {
        happyPath(needUpload = false)
        transport.respond(SUBMIT, """{"code":400,"message":"rep create failed"}""")
        transport.on(STATUS) { _, n ->
            when (n) {
                // rep create failed 之后一开始查不到状态
                1 -> """{"code":200,"statuses":{}}"""
                2 -> """{"code":200,"statuses":{"777":{"status":1,"waitTime":0}}}"""
                else -> """{"code":200,"statuses":{"777":{"status":9}}}"""
            }
        }
        transport.respond(BYIDS, """{"code":200,"data":[{"songId":777,"simpleSong":{"id":1777}}]}""")
        val events = api().uploader.upload(source(), meta).toList()

        assertEquals(CloudUploadStage.READY, events.last().stage)
        assertEquals(1, transport.count(SUBMIT))
        assertEquals(3, transport.count(STATUS))
        transport.callsTo(STATUS).forEach { call ->
            assertEquals(JsonArray(listOf(JsonPrimitive(777L))), call.params["songIds"])
        }
        assertEquals("777", transport.callsTo(PUBLISH).single().str("songid"))
        assertEquals("1777", events.last().ref!!.id)
    }

    @Test
    fun transcodePollingTimesOut() = runTest {
        happyPath(needUpload = false)
        transport.respond(STATUS, """{"code":200,"statuses":{"888":{"status":1,"waitTime":0}}}""")
        val config = config().copy(statusPollMinMs = 3_000, statusPollMaxMs = 5_000, transcodeTimeoutMs = 60_000)
        val api = api(config = config, now = { testScheduler.currentTime })
        val events = api.uploader.upload(source(), meta).toList()

        val last = events.last()
        assertEquals(CloudUploadStage.FAILED, last.stage)
        assertEquals(CloudErrorKind.TranscodeTimeout, last.error!!.kind)
        assertEquals(CloudMessages.TRANSCODE_TIMEOUT, last.error!!.message)
        assertTrue(last.error!!.retryable)
        assertTrue(testScheduler.currentTime >= 60_000)
        // 间隔 3 秒：大约 21 次，不会每毫秒刷一次
        assertTrue(transport.count(STATUS) in 15..22)
        assertEquals(0, transport.count(PUBLISH))
        // 失败后断点还在：重试时不用再提交一次
        assertTrue(api.uploader.hasCheckpoint(meta.localId))
    }

    @Test
    fun abnormalTranscodeStatusFails() = runBlocking {
        happyPath(needUpload = false)
        transport.respond(STATUS, """{"code":200,"statuses":{"888":{"status":3}}}""")
        val last = api().uploader.upload(source(), meta).toList().last()
        assertEquals(CloudErrorKind.TranscodeFailed, last.error!!.kind)
        assertEquals(3, last.error!!.code)
    }

    @Test
    fun submitTimeoutConfirmsViaByIdsInsteadOfResubmitting() = runBlocking {
        happyPath(needUpload = false)
        transport.on(SUBMIT) { _, _ -> throw SocketTimeoutException("timeout") }
        // 其实已经入库了：byids 能查到查重给的 songId
        transport.respond(BYIDS, """{"code":200,"data":[{"songId":777,"simpleSong":{"id":1777},"md5":"$md5Hex"}]}""")
        transport.respond(STATUS, """{"code":200,"statuses":{"777":{"status":9}}}""")
        val last = api().uploader.upload(source(), meta).toList().last()

        assertEquals(CloudUploadStage.READY, last.stage)
        assertEquals(1, transport.count(SUBMIT))
        assertEquals("777", last.ref!!.cloudSongId)
        assertEquals("1777", last.ref!!.id)
    }

    @Test
    fun submitTimeoutResubmitsOnceWhenNotFound() = runBlocking {
        happyPath(needUpload = false)
        transport.on(SUBMIT) { _, n ->
            if (n == 1) throw SocketTimeoutException("timeout")
            """{"code":200,"songId":"888"}"""
        }
        transport.on(BYIDS) { params, _ ->
            // 确认时（查 777）查不到；收尾时（查 888）能查到
            if ((params["songIds"] as JsonArray).contains(JsonPrimitive(888L))) {
                """{"code":200,"data":[{"songId":888,"simpleSong":{"id":999}}]}"""
            } else {
                """{"code":200,"data":[]}"""
            }
        }
        val last = api().uploader.upload(source(), meta).toList().last()

        assertEquals(CloudUploadStage.READY, last.stage)
        assertEquals(2, transport.count(SUBMIT))
        // 中间翻过最近列表确认
        assertTrue(transport.callsTo(LIST).any { it.str("limit") == "100" })
    }

    @Test
    fun publishTimeoutConfirmsViaRecentList() = runBlocking {
        happyPath(needUpload = false)
        transport.on(PUBLISH) { _, _ -> throw SocketTimeoutException("timeout") }
        transport.on(LIST) { params, _ ->
            val data = if (params["limit"].toString() == "100") {
                """[{"songId":888,"simpleSong":{"id":999},"md5":"$md5Hex","fileName":"song.mp3","fileSize":10}]"""
            } else {
                "[]"
            }
            """{"code":200,"size":"0","maxSize":"100000","data":$data}"""
        }
        val last = api().uploader.upload(source(), meta).toList().last()

        assertEquals(CloudUploadStage.READY, last.stage)
        assertEquals(1, transport.count(PUBLISH))
    }

    // ------------------------------------------------------------------ 风控 / 容量 / 大小

    @Test
    fun riskControlFailsWithPauseFlagAndKeepsHash() = runBlocking {
        happyPath(needUpload = false)
        transport.respond(CHECK, """{"code":-460,"message":"Cheating"}""")
        val api = api()
        val last = api.uploader.upload(source(), meta).toList().last()

        assertEquals(CloudUploadStage.FAILED, last.stage)
        val error = last.error!!
        assertEquals(CloudErrorKind.RiskControl, error.kind)
        assertEquals(CloudMessages.RISK_CONTROL, error.message)
        assertTrue(error.pausesQueue)
        assertEquals(0, transport.count(TOKEN))

        // 用户处理完点继续：MD5 不重算
        transport.respond(CHECK, """{"code":200,"songId":"777","needUpload":false}""")
        val again = api.uploader.upload(source(), meta).toList()
        assertEquals(CloudUploadStage.READY, again.last().stage)
        assertTrue(again.none { it.stage == CloudUploadStage.HASHING })
    }

    @Test
    fun oversizedFileIsSkippedBeforeAnyRequest() = runBlocking {
        happyPath(needUpload = false)
        val last = api(config = config().copy(maxFileSize = 8)).uploader.upload(source(), meta).toList().last()

        assertEquals(CloudErrorKind.FileTooLarge, last.error!!.kind)
        assertFalse(last.error!!.retryable)
        assertTrue(last.error!!.message.startsWith("单个文件超过"))
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun quotaShortfallStopsBeforeCheck() = runBlocking {
        happyPath(needUpload = false)
        transport.respond(LIST, """{"code":200,"size":"95","maxSize":"100","data":[]}""")
        val last = api().uploader.upload(source(), meta).toList().last()

        val error = last.error!!
        assertEquals(CloudErrorKind.QuotaExceeded, error.kind)
        assertEquals(5L, error.shortfall)
        assertEquals("云盘空间不够，还差 5 B", error.message)
        assertEquals(0, transport.count(CHECK))
    }

    @Test
    fun precheckUsesMaxSizeMinusSize() = runBlocking {
        happyPath(needUpload = false)
        transport.respond(LIST, """{"code":200,"size":"85","maxSize":"100","data":[]}""")
        val plan = api(config = config().copy(maxFileSize = 20)).precheck(
            listOf(CloudUploadCandidate("a", 10), CloudUploadCandidate("b", 30), CloudUploadCandidate("c", 8)),
        )
        assertEquals(listOf("a", "c"), plan.accepted)
        assertEquals(listOf("b"), plan.tooLarge)
        assertEquals(18L, plan.totalBytes)
        assertEquals(3L, plan.shortfall)
        assertFalse(plan.canStart)
        assertEquals(15L, plan.quota!!.free)
    }

    @Test
    fun missingCookieIsNotLoggedIn() = runBlocking {
        val api = NeteaseCloudApi(cookie = { "__csrf=x" }, transport = transport, platform = NoPlatform, config = config(), nos = http)
        val last = api.uploader.upload(source(), meta).toList().last()
        assertEquals(CloudErrorKind.NotLoggedIn, last.error!!.kind)
        assertTrue(last.error!!.pausesQueue)
        assertTrue(transport.calls.isEmpty())
        assertNull(last.ref)
    }

    // ------------------------------------------------------------------ 工具

    private fun source() = CloudUploadSource.of("song.mp3", content)

    private fun config() = NeteaseCloudConfig(
        requestIntervalMs = 0,
        maxRetries = 2,
        retryBackoffMs = 1,
        statusPollMinMs = 1,
        statusPollMaxMs = 2,
        transcodeTimeoutMs = 5_000,
        missingStatusGraceMs = 2_000,
        submitRetryWindowMs = 50,
        submitRetryDelayMs = 1,
        chunkSize = 4,
        maxFileSize = 1_000,
        listPageSize = 200,
        confirmScanSize = 100,
        lbsUrl = nos.url("/lbs").toString(),
        bucket = "bkt",
    )

    private fun api(
        config: NeteaseCloudConfig = config(),
        gate: UploadGate = UploadGate.Always,
        now: () -> Long = System::currentTimeMillis,
    ) = NeteaseCloudApi(
        cookie = { "MUSIC_U=abc; __csrf=csrf1" },
        transport = transport,
        platform = NoPlatform,
        config = config,
        nos = http,
        gate = gate,
        now = now,
    )

    private fun happyPath(needUpload: Boolean) {
        transport.respond(LIST, """{"code":200,"size":"100","maxSize":"100000","hasMore":false,"data":[]}""")
        transport.respond(CHECK, """{"code":200,"songId":"777","needUpload":$needUpload}""")
        transport.respond(TOKEN, """{"code":200,"result":{"resourceId":"res-1","objectKey":"obj/a b/x.mp3","token":"tok-1"}}""")
        transport.respond(SUBMIT, """{"code":200,"songId":"888"}""")
        transport.respond(STATUS, """{"code":200,"statuses":{"888":{"status":9,"waitTime":0}}}""")
        transport.respond(PUBLISH, """{"code":200}""")
        transport.respond(BYIDS, """{"code":200,"data":[{"songId":888,"simpleSong":{"id":999},"fileName":"song.mp3","fileSize":10,"md5":"$md5Hex"}]}""")
    }

    /** 假的 lbs + NOS：按 offset 收字节，回 {offset, context}；可以模拟 400、回应前断线。 */
    private inner class NosDispatcher : Dispatcher() {
        val posts = CopyOnWriteArrayList<RecordedRequest>()
        val offsetQueries = CopyOnWriteArrayList<RecordedRequest>()
        @Volatile var lbsCalls = 0
        @Volatile var rejectFirstPostWith400 = false
        @Volatile var dropPost = -1
        private val buffer = ByteArray(content.size)
        @Volatile private var committed = 0L

        fun received(): ByteArray = buffer.copyOf(committed.toInt())

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.url
            return when {
                url.encodedPath == "/lbs" -> {
                    lbsCalls++
                    json("""{"lbs":"x","upload":["${nos.url("/").toString().trimEnd('/')}"]}""")
                }
                request.method == "GET" && "uploadContext" in url.queryParameterNames -> {
                    offsetQueries += request
                    json("""{"offset":$committed}""")
                }
                request.method == "POST" -> {
                    posts += request
                    val n = posts.size
                    if (rejectFirstPostWith400 && n == 1) return MockResponse.Builder().code(400).build()
                    val offset = url.queryParameter("offset")!!.toLong()
                    val bytes = request.body?.toByteArray() ?: ByteArray(0)
                    bytes.copyInto(buffer, offset.toInt())
                    committed = offset + bytes.size
                    if (n == dropPost) {
                        MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()
                    } else {
                        json("""{"requestID":"q$n","offset":$committed,"context":"ctx-$n"}""")
                    }
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }

        private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
    }

    private companion object {
        const val LIST = "/api/v1/cloud/get"
        const val BYIDS = "/api/v1/cloud/get/byids"
        const val CHECK = "/api/cloud/upload/check"
        const val TOKEN = "/api/nos/token/alloc"
        const val SUBMIT = "/api/upload/cloud/info/v2"
        const val STATUS = "/api/v1/cloud/music/status"
        const val PUBLISH = "/api/cloud/pub/v2"
    }
}
