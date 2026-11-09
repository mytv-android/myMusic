package com.mytv.mymusic.data.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 上传队列：串行、-460 暂停整个队列 + 退避、普通失败只记在那一项、取消 / 暂停 / 重试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudUploadQueueTest {
    private class FakeStorage : CloudStorage {
        override val provider: CloudProvider = CloudProvider.NeteaseCloud
        val attempts = CopyOnWriteArrayList<String>()
        var running = 0
        var maxRunning = 0

        /** (localId, 这一首第几次尝试) → 结果；抛 CloudException 模拟失败。 */
        var behavior: suspend (String, Int, (CloudUploadProgress) -> Unit) -> Unit = { _, _, _ -> delay(100) }

        override suspend fun upload(
            source: CloudUploadSource,
            meta: CloudUploadMeta,
            progress: (CloudUploadProgress) -> Unit,
        ): CloudRef {
            attempts += meta.localId
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                behavior(meta.localId, attempts.count { it == meta.localId }, progress)
            } finally {
                running--
            }
            return CloudRef(provider.id, "cloud-${meta.localId}", localId = meta.localId)
        }

        override suspend fun list(cursor: String?): CloudPage = CloudPage(emptyList())
        override suspend fun quota(): CloudQuota? = null
        override suspend fun resolvePlayback(ref: CloudRef): CloudStream = throw UnsupportedOperationException()
        override suspend fun delete(ref: CloudRef) = Unit
    }

    private val storage = FakeStorage()
    private val removed = mutableListOf<String>()

    /**
     * 队列自己的 scope：同一个调度器上的前台任务，advanceUntilIdle 会把它跑完（backgroundScope 里的
     * 任务它不等）；又不是 runTest 的子协程，卡在 awaitCancellation 的上传不会让测试等到超时。
     */
    private var work: CoroutineScope? = null

    @After
    fun tearDown() {
        work?.cancel()
    }

    private fun TestScope.queue(gate: UploadGate = UploadGate.Always) = CloudUploadQueue(
        storage = storage,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { work = it },
        riskBackoffMs = 60_000,
        riskBackoffMaxMs = 30 * 60_000,
        now = { testScheduler.currentTime },
        onRemoved = { removed += it },
        gate = gate,
    )

    /** 集成层的门：后台任务开着才放行（LocalUploads 里是 allowed 这个 StateFlow）。 */
    private class Gate(open: Boolean) : UploadGate {
        val allowed = MutableStateFlow(open)
        override suspend fun awaitAllowed() {
            allowed.first { it }
        }
    }

    private fun CloudUploadQueue.add(vararg ids: String) =
        ids.forEach { enqueue(CloudUploadSource.of("$it.mp3", ByteArray(10)), CloudUploadMeta(localId = it)) }

    private fun CloudUploadQueue.item(id: String) = state.value.items.first { it.localId == id }

    private fun risk() = CloudException(CloudErrorKind.RiskControl, CloudMessages.RISK_CONTROL, CloudProvider.NeteaseCloud, -460)

    @Test
    fun uploadsOneAtATimeInOrder() = runTest {
        val queue = queue()
        val finished = mutableListOf<CloudQueueResult>()
        work!!.launch { queue.finished.collect { finished += it } }
        runCurrent()
        queue.add("a", "b", "c")
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), storage.attempts)
        assertEquals(1, storage.maxRunning)
        assertTrue(queue.state.value.items.all { it.stage == CloudUploadStage.READY })
        assertEquals(listOf("a", "b", "c"), finished.map { it.localId })
        assertEquals("cloud-b", finished[1].ref!!.id)
        assertNull(queue.state.value.activeId)
        assertEquals(0, queue.state.value.pending)
    }

    @Test
    fun riskControlPausesWholeQueueAndBacksOffOnResume() = runTest {
        storage.behavior = { id, attempt, _ ->
            delay(100)
            if (id == "b" && attempt == 1) throw risk()
        }
        val queue = queue()
        val finished = mutableListOf<CloudQueueResult>()
        work!!.launch { queue.finished.collect { finished += it } }
        runCurrent()
        queue.add("a", "b", "c")
        advanceUntilIdle()

        // b 撞到 -460：放回队首、整个队列停下，c 不去撞
        assertEquals(listOf("a", "b"), storage.attempts)
        val state = queue.state.value
        assertTrue(state.paused)
        assertEquals(CloudErrorKind.RiskControl, state.pauseReason!!.kind)
        assertEquals("b", state.items.first().localId)
        assertEquals(CloudUploadStage.QUEUED, queue.item("b").stage)
        assertEquals(CloudErrorKind.RiskControl, queue.item("b").error!!.kind)
        assertEquals(CloudUploadStage.QUEUED, queue.item("c").stage)
        assertEquals(listOf("a"), finished.map { it.localId })

        // 用户点继续：先退避一分钟再发请求
        queue.resume()
        assertFalse(queue.state.value.paused)
        assertEquals(testScheduler.currentTime + 60_000, queue.state.value.resumeAt)
        assertNull(queue.item("b").error)
        advanceTimeBy(59_000)
        assertEquals(listOf("a", "b"), storage.attempts)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "b", "c"), storage.attempts)
        assertTrue(queue.state.value.items.all { it.stage == CloudUploadStage.READY })
    }

    @Test
    fun repeatedRiskControlDoublesBackoff() = runTest {
        storage.behavior = { id, _, _ -> if (id == "a") throw risk() }
        val queue = queue()
        queue.add("a")
        advanceUntilIdle()
        assertTrue(queue.state.value.paused)

        queue.resume()
        assertEquals(60_000, queue.state.value.resumeAt - testScheduler.currentTime)
        advanceUntilIdle()
        assertTrue(queue.state.value.paused)

        queue.resume()
        assertEquals(120_000, queue.state.value.resumeAt - testScheduler.currentTime)
        advanceUntilIdle()
        assertEquals(3, storage.attempts.size)
    }

    @Test
    fun ordinaryFailureStaysOnItemAndQueueContinues() = runTest {
        storage.behavior = { id, attempt, _ ->
            if (id == "b" && attempt == 1) {
                throw CloudException(CloudErrorKind.TranscodeTimeout, CloudMessages.TRANSCODE_TIMEOUT, CloudProvider.NeteaseCloud)
            }
        }
        val queue = queue()
        val finished = mutableListOf<CloudQueueResult>()
        work!!.launch { queue.finished.collect { finished += it } }
        runCurrent()
        queue.add("a", "b", "c")
        advanceUntilIdle()

        assertFalse(queue.state.value.paused)
        assertEquals(CloudUploadStage.FAILED, queue.item("b").stage)
        assertEquals(CloudUploadStage.READY, queue.item("c").stage)
        assertEquals(CloudErrorKind.TranscodeTimeout, finished.first { it.localId == "b" }.error!!.kind)

        queue.retry("b")
        advanceUntilIdle()
        assertEquals(CloudUploadStage.READY, queue.item("b").stage)
        assertEquals(2, queue.item("b").attempts)
    }

    @Test
    fun cancelActiveItemMarksItRetryableAndMovesOn() = runTest {
        storage.behavior = { id, _, progress ->
            progress(CloudUploadProgress(CloudUploadStage.UPLOADING, 4, 10))
            if (id == "a") awaitCancellation()
        }
        val queue = queue()
        queue.add("a", "b")
        runCurrent()
        assertEquals("a", queue.state.value.activeId)
        assertEquals(4L, queue.item("a").progress.bytesDone)

        queue.cancel("a")
        advanceUntilIdle()

        val a = queue.item("a")
        assertEquals(CloudUploadStage.FAILED, a.stage)
        assertEquals(CloudErrorKind.Cancelled, a.error!!.kind)
        assertTrue(a.error!!.retryable)
        assertEquals(CloudUploadStage.READY, queue.item("b").stage)
    }

    @Test
    fun manualPauseRequeuesActiveItemAtHead() = runTest {
        storage.behavior = { id, attempt, _ -> if (id == "a" && attempt == 1) awaitCancellation() else delay(10) }
        val queue = queue()
        queue.add("a", "b")
        runCurrent()
        queue.pause()
        advanceUntilIdle()

        assertTrue(queue.state.value.paused)
        assertNull(queue.state.value.pauseReason)
        assertEquals(CloudUploadStage.QUEUED, queue.item("a").stage)
        assertEquals("a", queue.state.value.items.first().localId)
        assertEquals(listOf("a"), storage.attempts)

        // 手动暂停不退避
        queue.resume()
        assertEquals(0L, queue.state.value.resumeAt)
        advanceUntilIdle()
        assertEquals(listOf("a", "a", "b"), storage.attempts)
    }

    @Test
    fun duplicatesIgnoredAndRemoveNotifies() = runTest {
        storage.behavior = { _, _, _ -> awaitCancellation() }
        val queue = queue()
        queue.add("a")
        assertFalse(queue.enqueue(CloudUploadSource.of("a.mp3", ByteArray(1)), CloudUploadMeta("a")))
        runCurrent()
        queue.remove("a")
        advanceUntilIdle()
        assertTrue(queue.state.value.items.isEmpty())
        assertEquals(listOf("a"), removed)

        // 删掉之后入队的项照样有人处理（worker 已经退出时会重新起一个）
        storage.behavior = { _, _, _ -> }
        queue.add("b")
        advanceUntilIdle()
        assertEquals(CloudUploadStage.READY, queue.item("b").stage)
        queue.clearFinished()
        assertTrue(queue.state.value.items.isEmpty())
    }

    @Test
    fun closedGateKeepsItemsQueuedUntilOpened() = runTest {
        val gate = Gate(open = false)
        val queue = queue(gate)
        queue.add("a", "b")
        advanceUntilIdle()
        // 后台任务还没起来：都还是“排队”，没有一项显示成正在传
        assertTrue(storage.attempts.isEmpty())
        assertNull(queue.state.value.activeId)
        assertTrue(queue.state.value.items.all { it.stage == CloudUploadStage.QUEUED })

        gate.allowed.value = true
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), storage.attempts)
    }

    @Test
    fun interruptRequeuesAtHeadWithoutPausing() = runTest {
        storage.behavior = { id, attempt, progress ->
            progress(CloudUploadProgress(CloudUploadStage.UPLOADING, 4, 10))
            if (id == "b" && attempt == 1) awaitCancellation() else delay(10)
        }
        val gate = Gate(open = true)
        val queue = queue(gate)
        queue.add("a", "b", "c")
        advanceTimeBy(15)
        runCurrent()
        assertEquals("b", queue.state.value.activeId)

        // 后台任务被系统停了：先关门再打断，正在传的那首放回队首，不算失败也不算暂停
        gate.allowed.value = false
        queue.interrupt()
        advanceUntilIdle()
        val state = queue.state.value
        assertFalse(state.paused)
        assertNull(state.pauseReason)
        assertEquals("b", state.items.first().localId)
        assertEquals(CloudUploadStage.QUEUED, queue.item("b").stage)
        assertNull(queue.item("b").error)
        assertEquals(CloudUploadStage.QUEUED, queue.item("c").stage)
        assertEquals(listOf("a", "b"), storage.attempts)

        gate.allowed.value = true
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "b", "c"), storage.attempts)
        assertTrue(queue.state.value.items.all { it.stage == CloudUploadStage.READY })
    }

    @Test
    fun restoreWithPauseReasonWaitsForResume() = runTest {
        val queue = queue()
        val items = listOf("a", "b").map { id ->
            CloudQueueItem(
                localId = id,
                source = CloudUploadSource.of("$id.mp3", ByteArray(10)),
                meta = CloudUploadMeta(localId = id),
                progress = CloudUploadProgress(CloudUploadStage.QUEUED, 0, 10, error = if (id == "a") risk() else null),
            )
        }
        assertTrue(queue.restore(items, risk()))
        advanceUntilIdle()
        // 重启前撞了风控：重启后不能自己接着撞，等用户点继续
        assertTrue(queue.state.value.paused)
        assertTrue(storage.attempts.isEmpty())
        // 队列里已经有东西时不再覆盖
        assertFalse(queue.restore(items.take(1), null))

        queue.resume()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), storage.attempts)
    }

    @Test
    fun restoreWithoutPauseRunsAndClearCompletedKeepsFailures() = runTest {
        storage.behavior = { id, _, _ ->
            if (id == "b") throw CloudException(CloudErrorKind.TranscodeTimeout, CloudMessages.TRANSCODE_TIMEOUT, CloudProvider.NeteaseCloud)
        }
        val queue = queue()
        val items = listOf("a", "b").map { id ->
            CloudQueueItem(id, CloudUploadSource.of("$id.mp3", ByteArray(10)), CloudUploadMeta(localId = id))
        }
        assertTrue(queue.restore(items, null))
        advanceUntilIdle()
        assertEquals(CloudUploadStage.READY, queue.item("a").stage)
        assertEquals(CloudUploadStage.FAILED, queue.item("b").stage)

        // 一批传完：传好的清掉（计数从头数），失败的留着给用户看原因、点重试
        queue.clearCompleted()
        assertEquals(listOf("b"), queue.state.value.items.map { it.localId })
    }
}
