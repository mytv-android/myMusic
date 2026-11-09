package com.mytv.mymusic.data.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 队列里的一项。[localId] 就是本地身份，同一首不会重复入队。 */
data class CloudQueueItem(
    val localId: String,
    val source: CloudUploadSource,
    val meta: CloudUploadMeta,
    val progress: CloudUploadProgress = CloudUploadProgress(CloudUploadStage.QUEUED, 0, source.size),
    /** 第几次尝试（从 1 开始）；排队时为 0。 */
    val attempts: Int = 0,
) {
    val stage: CloudUploadStage get() = progress.stage

    /** 失败原因；暂停时也挂在排回队首的那一项上，界面写原因。 */
    val error: CloudException? get() = progress.error
}

data class CloudQueueState(
    val items: List<CloudQueueItem> = emptyList(),
    /** 正在传的那一项。 */
    val activeId: String? = null,
    /** 遇到 -460 / 未登录 / WebDAV 401 时整个队列停下，等用户点“继续”。 */
    val paused: Boolean = false,
    val pauseReason: CloudException? = null,
    /** 继续之后在这个时刻（毫秒）之前不发请求：风控后的退避。0 表示不等。 */
    val resumeAt: Long = 0,
) {
    val pending: Int get() = items.count { it.stage != CloudUploadStage.READY && it.stage != CloudUploadStage.FAILED }
}

/** 一项结束：成功带 [ref]，失败带 [error]。集成层据此回写 local_tracks.remote。 */
data class CloudQueueResult(val localId: String, val ref: CloudRef?, val error: CloudException?)

/**
 * 一个云端位置的上传队列：严格串行（规格 6.4 并发 1），状态用 [state] 给界面。
 *
 * - 碰到 [CloudException.pausesQueue]（-460、未登录、WebDAV 401）不把后面的文件一个个撞失败，
 *   当前项放回队首（错误挂在上面），整个队列暂停；用户点继续时调 [resume]。
 * - 风控连续出现时，继续后先等一段再发请求，每多一次翻倍（[riskBackoffMs] 起步，最多
 *   [riskBackoffMaxMs]）；传成功一首就清零。网易的风控是按频率算的，马上接着撞只会更久。
 * - 其他失败只记在那一项上，不影响后面的；能重试的由用户点 [retry]。
 * - 断点续传靠存储自己：网易在 [com.mytv.mymusic.data.cloud.netease.NeteaseCloudUploader] 里按
 *   localId 记断点，所以重试 / 继续时同一首会从断点接着走。
 */
class CloudUploadQueue(
    private val storage: CloudStorage,
    private val scope: CoroutineScope,
    private val riskBackoffMs: Long = 60_000,
    private val riskBackoffMaxMs: Long = 30 * 60_000,
    private val now: () -> Long = System::currentTimeMillis,
    /** 用户从队列里删掉一项时调用（网易：丢掉断点）。 */
    private val onRemoved: (String) -> Unit = {},
) {
    private val lock = Any()
    private val _state = MutableStateFlow(CloudQueueState())
    val state: StateFlow<CloudQueueState> = _state.asStateFlow()

    private val _finished = MutableSharedFlow<CloudQueueResult>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 每项结束（成功或失败）发一次；暂停放回队首的不算结束。 */
    val finished: SharedFlow<CloudQueueResult> = _finished.asSharedFlow()

    val provider: CloudProvider get() = storage.provider

    private var workerRunning = false
    private var activeJob: Job? = null
    private var riskStrikes = 0

    /** 入队。已经在队里且没失败的同一首忽略；失败过的重新排队。返回是否新入队。 */
    fun enqueue(source: CloudUploadSource, meta: CloudUploadMeta): Boolean {
        val added = synchronized(lock) {
            val existing = _state.value.items.firstOrNull { it.localId == meta.localId }
            when {
                existing == null -> {
                    _state.update { it.copy(items = it.items + CloudQueueItem(meta.localId, source, meta)) }
                    true
                }
                existing.stage == CloudUploadStage.FAILED -> {
                    replace(meta.localId) { CloudQueueItem(meta.localId, source, meta, attempts = it.attempts) }
                    true
                }
                else -> false
            }
        }
        if (added) ensureWorker()
        return added
    }

    fun enqueueAll(items: List<Pair<CloudUploadSource, CloudUploadMeta>>) {
        items.forEach { (source, meta) -> enqueue(source, meta) }
    }

    /** 取消一项：正在传就立刻断开（断点保留），记成失败（可重试）。 */
    fun cancel(localId: String) {
        synchronized(lock) {
            val item = _state.value.items.firstOrNull { it.localId == localId } ?: return
            if (item.stage.terminal) return
            if (_state.value.activeId == localId) {
                activeJob?.cancel(CancellationException(CloudMessages.CANCELLED))
            }
            markFailed(localId, CloudException(CloudErrorKind.Cancelled, CloudMessages.CANCELLED, storage.provider))
        }
    }

    /** 失败的那项重新排队。 */
    fun retry(localId: String) {
        val changed = synchronized(lock) {
            val item = _state.value.items.firstOrNull { it.localId == localId }
            if (item?.stage != CloudUploadStage.FAILED) return@synchronized false
            replace(localId) { it.copy(progress = CloudUploadProgress(CloudUploadStage.QUEUED, 0, it.source.size)) }
            true
        }
        if (changed) ensureWorker()
    }

    /** 所有失败且能重试的项重新排队。 */
    fun retryAll() {
        _state.value.items.filter { it.stage == CloudUploadStage.FAILED && it.error?.retryable != false }
            .forEach { retry(it.localId) }
    }

    /** 用户点“继续”：解除暂停。风控暂停的先退避一段再发请求。 */
    fun resume() {
        synchronized(lock) {
            val current = _state.value
            if (!current.paused) return
            val wait = if (current.pauseReason?.kind == CloudErrorKind.RiskControl && riskStrikes > 0) {
                (riskBackoffMs shl (riskStrikes - 1).coerceAtMost(16)).coerceAtMost(riskBackoffMaxMs)
            } else {
                0L
            }
            _state.update {
                it.copy(
                    paused = false,
                    pauseReason = null,
                    resumeAt = if (wait > 0) now() + wait else 0,
                    items = it.items.map { item ->
                        // 挂在排队项上的暂停原因清掉，界面不再显示
                        if (item.stage == CloudUploadStage.QUEUED && item.error != null) {
                            item.copy(progress = item.progress.copy(error = null))
                        } else {
                            item
                        }
                    },
                )
            }
        }
        ensureWorker()
    }

    /** 手动暂停（用户点“暂停”）。正在传的那一片断开，断点保留，项放回队首。 */
    fun pause() {
        synchronized(lock) {
            if (_state.value.paused) return
            _state.update { it.copy(paused = true, pauseReason = null) }
            activeJob?.cancel(CancellationException(PAUSED))
        }
    }

    /** 从队列删掉一项（不删云端文件）。 */
    fun remove(localId: String) {
        synchronized(lock) {
            if (_state.value.activeId == localId) activeJob?.cancel(CancellationException(CloudMessages.CANCELLED))
            _state.update { state -> state.copy(items = state.items.filterNot { it.localId == localId }) }
        }
        onRemoved(localId)
    }

    /** 清掉已完成的项（成功的和失败的），留下还在排队的。 */
    fun clearFinished() {
        synchronized(lock) {
            _state.update { state -> state.copy(items = state.items.filterNot { it.stage.terminal }) }
        }
    }

    /**
     * 同时只有一个 worker。退出的决定和 [workerRunning] 复位在同一把锁里做：
     * 否则 worker 刚决定退出、还没结束时入队的项会没人处理。
     */
    private fun ensureWorker() {
        synchronized(lock) {
            if (workerRunning || _state.value.paused) return
            workerRunning = true
            scope.launch { runWorker() }
        }
    }

    private suspend fun runWorker() {
        try {
            while (true) {
                // 风控退避：等的过程中 resume / pause 可能改了 resumeAt，醒来重新看一遍
                val wait = _state.value.resumeAt - now()
                if (wait > 0) {
                    delay(wait)
                    continue
                }
                val next = synchronized(lock) {
                    val current = _state.value
                    val item = if (current.paused) null else current.items.firstOrNull { it.stage == CloudUploadStage.QUEUED }
                    if (item == null) {
                        workerRunning = false
                        _state.update { it.copy(activeId = null) }
                        return
                    }
                    _state.update { it.copy(activeId = item.localId, resumeAt = 0) }
                    replace(item.localId) { it.copy(attempts = it.attempts + 1) }
                    // 先建好、记下再启动：cancel() / pause() 在同一把锁里一定能拿到这一项的 Job
                    scope.launch(start = CoroutineStart.LAZY) { runItem(item) }.also { activeJob = it }
                }
                val job = next
                job.start()
                job.join()
                synchronized(lock) {
                    if (activeJob === job) activeJob = null
                    _state.update { it.copy(activeId = null) }
                }
            }
        } catch (error: CancellationException) {
            synchronized(lock) { workerRunning = false }
            throw error
        }
    }

    private suspend fun runItem(item: CloudQueueItem) {
        val id = item.localId
        try {
            val ref = storage.upload(item.source, item.meta) { progress ->
                synchronized(lock) {
                    // 被取消 / 删除之后迟到的进度不要写回去
                    val current = _state.value.items.firstOrNull { it.localId == id }
                    if (current != null && !current.stage.terminal) replace(id) { it.copy(progress = progress) }
                }
            }
            synchronized(lock) {
                riskStrikes = 0
                replace(id) {
                    it.copy(progress = CloudUploadProgress(CloudUploadStage.READY, it.source.size, it.source.size, ref = ref))
                }
            }
            _finished.tryEmit(CloudQueueResult(id, ref, null))
        } catch (error: CancellationException) {
            // cancel() / remove() 在同一把锁里已经把这项改成失败或删掉了；还没结束的是手动暂停
            // （或整个 scope 被取消），放回队首，下次从断点接着传
            synchronized(lock) {
                val current = _state.value.items.firstOrNull { it.localId == id }
                if (current != null && !current.stage.terminal) requeue(id, null)
            }
            throw error
        } catch (error: CloudException) {
            if (error.pausesQueue) {
                synchronized(lock) {
                    if (error.kind == CloudErrorKind.RiskControl) riskStrikes++
                    requeue(id, error)
                    _state.update { it.copy(paused = true, pauseReason = error) }
                }
            } else {
                synchronized(lock) { markFailed(id, error) }
                _finished.tryEmit(CloudQueueResult(id, null, error))
            }
        } catch (error: Exception) {
            val wrapped = CloudException(
                CloudErrorKind.Network,
                CloudMessages.CLOUD_UNREACHABLE,
                storage.provider,
                cause = error,
            )
            synchronized(lock) { markFailed(id, wrapped) }
            _finished.tryEmit(CloudQueueResult(id, null, wrapped))
        }
    }

    /** 放回队首：暂停后继续时第一个接着传。 */
    private fun requeue(localId: String, error: CloudException?) {
        _state.update { state ->
            val item = state.items.firstOrNull { it.localId == localId } ?: return@update state
            val back = item.copy(
                progress = CloudUploadProgress(CloudUploadStage.QUEUED, item.progress.bytesDone, item.source.size, error = error),
            )
            state.copy(items = listOf(back) + state.items.filterNot { it.localId == localId })
        }
    }

    private fun markFailed(localId: String, error: CloudException) {
        replace(localId) {
            it.copy(progress = CloudUploadProgress(CloudUploadStage.FAILED, it.progress.bytesDone, it.source.size, error = error))
        }
    }

    private inline fun replace(localId: String, crossinline transform: (CloudQueueItem) -> CloudQueueItem) {
        _state.update { state ->
            state.copy(items = state.items.map { if (it.localId == localId) transform(it) else it })
        }
    }

    private companion object {
        const val PAUSED = "已暂停"
    }
}
