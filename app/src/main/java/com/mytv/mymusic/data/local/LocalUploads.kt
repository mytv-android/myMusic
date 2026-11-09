package com.mytv.mymusic.data.local

import com.mytv.mymusic.AppLog
import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudHub
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudPage
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudQueueItem
import com.mytv.mymusic.data.cloud.CloudQueueState
import com.mytv.mymusic.data.cloud.CloudQuota
import com.mytv.mymusic.data.cloud.CloudRef
import com.mytv.mymusic.data.cloud.CloudStorage
import com.mytv.mymusic.data.cloud.CloudStream
import com.mytv.mymusic.data.cloud.CloudUploadCandidate
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadProgress
import com.mytv.mymusic.data.cloud.CloudUploadQueue
import com.mytv.mymusic.data.cloud.CloudUploadSource
import com.mytv.mymusic.data.cloud.CloudUploadStage
import com.mytv.mymusic.data.cloud.UploadGate
import com.mytv.mymusic.data.cloud.UploadPrecheck
import com.mytv.mymusic.data.webdav.WebDavAccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 队列里的一项连同它要去的云端。 */
data class LocalUploadEntry(val provider: CloudProvider, val item: CloudQueueItem) {
    val localId: String get() = item.localId
}

/** 两个云端队列合起来给界面和通知看的样子（设计稿第 ② 张的“正在上传 · 2 / 5”）。 */
data class LocalUploadState(
    val queues: Map<CloudProvider, CloudQueueState> = emptyMap(),
    /** 后台任务正开着门（在传或在退避等待）；false 且有排队项时，是在等网络或等后台任务起来。 */
    val running: Boolean = false,
) {
    val entries: List<LocalUploadEntry> =
        queues.flatMap { (provider, queue) -> queue.items.map { LocalUploadEntry(provider, it) } }

    /** 正在传的那一首（同时只有一首：每个云端串行，后台任务也一次只开一个门，但两个云端可能各传一首）。 */
    val active: List<LocalUploadEntry> = queues.flatMap { (provider, queue) ->
        queue.items.filter { it.localId == queue.activeId && !it.stage.terminal }.map { LocalUploadEntry(provider, it) }
    }
    val waiting: List<LocalUploadEntry> = entries.filter { it.item.stage == CloudUploadStage.QUEUED && active.none { a -> a.localId == it.localId && a.provider == it.provider } }
    val failed: List<LocalUploadEntry> = entries.filter { it.item.stage == CloudUploadStage.FAILED }
    val doneCount: Int = entries.count { it.item.stage == CloudUploadStage.READY }
    val pendingCount: Int = active.size + waiting.size

    /** 这一批一共几首（传完的 + 还没传完的，失败的另算）。 */
    val total: Int get() = doneCount + pendingCount

    /** 正在传第几首：传完的加上正在传的。 */
    val current: Int get() = doneCount + active.size
    val busy: Boolean get() = pendingCount > 0

    /** 第一个停下来的云端和原因（风控、登录失效、WebDAV 密码不对）。 */
    val paused: Pair<CloudProvider, CloudException?>? = queues.entries
        .firstOrNull { (_, queue) -> queue.paused && queue.items.any { it.stage == CloudUploadStage.QUEUED } }
        ?.let { (provider, queue) -> provider to queue.pauseReason }

    /** 风控后点了继续、还在退避等待的云端和恢复时刻。 */
    val backoffUntil: Long = queues.values.maxOfOrNull { if (!it.paused) it.resumeAt else 0L } ?: 0L

    fun find(localId: String): List<LocalUploadEntry> = entries.filter { it.localId == localId }

    /** 这首在这个云端是不是正在排队 / 正在传。 */
    fun isPending(localId: String, provider: CloudProvider): Boolean =
        entries.any { it.localId == localId && it.provider == provider && !it.item.stage.terminal }
}

/** 一次“打开上传面板”的请求：从哪几首、是不是用户明确要重新上传、默认选哪个云端。 */
data class LocalUploadRequest(
    val ids: List<String>,
    /** 云端副本那行点的“重新上传”：已经有一份也照传。 */
    val force: Boolean = false,
    val provider: CloudProvider? = null,
)

/** 起后台上传任务（WorkManager 前台服务）的那一头，见 LocalUploadWorker。 */
fun interface LocalUploadRunner {
    /** [replace] 为 true 时换掉已有的任务（只在 Wi-Fi 下上传的开关变了，约束要跟着变）。 */
    fun schedule(wifiOnly: Boolean, replace: Boolean)
}

/**
 * 本地歌上传的应用级入口：每个能上传的云端一条 [CloudUploadQueue]（网易云盘、WebDAV；QQ 云盘只读，从不上传）。
 *
 * - 真正开始传交给后台任务：任务在跑时 [gate] 打开，系统停掉任务（换到移动网络、前台服务超时）时关门并把
 *   正在传的排回队首，下次接着传。没有后台任务可用（解锁前的进程）时直接在进程里开门。
 * - 队列落盘到 cloud_uploads（只记身份、位置、状态、失败原因，不放音频），进程被杀后放回来接着传。
 * - 传完一首写进 local_remotes（[LocalLibrary.recordRemote]），随同步告诉其他设备。
 * - 这个云端已经有能播的一份就不再传，除非用户明确点了“重新上传”。
 */
class LocalUploads(
    private val library: LocalLibrary,
    private val cloud: CloudHub,
    private val dao: LocalUploadDao,
    private val webDavAccounts: WebDavAccountStore,
) {
    // 上传读文件、算 MD5、走网络，不能放在应用那个主线程作用域里
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val allowed = MutableStateFlow(false)
    private val gate = UploadGate { allowed.first { it } }
    private val restored = CompletableDeferred<Unit>()
    private val lock = Any()
    private var workerActive = false

    /** 起后台任务的那一头；为空时（设备保护存储里的进程）在进程里直接传。 */
    @Volatile var runner: LocalUploadRunner? = null

    private val queues: Map<CloudProvider, CloudUploadQueue> = linkedMapOf(
        CloudProvider.NeteaseCloud to CloudUploadQueue(
            storage = NeteaseTarget(),
            scope = scope,
            onRemoved = { id -> runCatching { cloud.netease.uploader.discard(id) } },
            gate = gate,
        ),
        CloudProvider.WebDav to CloudUploadQueue(storage = WebDavTarget(), scope = scope, gate = gate),
    )

    val state: StateFlow<LocalUploadState> = combine(
        queues.getValue(CloudProvider.NeteaseCloud).state,
        queues.getValue(CloudProvider.WebDav).state,
        allowed,
    ) { netease, webdav, open ->
        LocalUploadState(mapOf(CloudProvider.NeteaseCloud to netease, CloudProvider.WebDav to webdav), open)
    }.stateIn(scope, SharingStarted.Eagerly, LocalUploadState())

    private val _request = MutableStateFlow<LocalUploadRequest?>(null)

    /** 全局的上传面板（AppNavigation 里挂着）看它：本地音乐页、歌曲信息、加进歌单后的提示都从这里打开。 */
    val request: StateFlow<LocalUploadRequest?> = _request.asStateFlow()

    fun openSheet(ids: List<String>, force: Boolean = false, provider: CloudProvider? = null) {
        if (ids.isNotEmpty()) _request.value = LocalUploadRequest(ids.distinct(), force, provider)
    }

    fun closeSheet() {
        _request.value = null
    }

    /** 应用启动时调一次：放回落盘的队列，开始落盘和回写，有排队的就起后台任务。 */
    fun start() {
        scope.launch {
            try {
                restore()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.w(TAG, "上传队列放不回来：${error.message}")
            } finally {
                restored.complete(Unit)
            }
            queues.forEach { (provider, queue) ->
                launch {
                    queue.state.map { LocalUploadRows.toRows(provider, it) }.distinctUntilChanged().collect { rows ->
                        runCatching { dao.replace(provider.id, rows) }
                            .onFailure { AppLog.w(TAG, "上传队列存不下来：${it.message}") }
                    }
                }
                launch {
                    queue.finished.collect { result ->
                        val ref = result.ref ?: return@collect
                        runCatching { library.recordRemote(result.localId, ref) }
                            .onFailure { AppLog.w(TAG, "记不下云端位置：${it.message}") }
                    }
                }
                // 一批传完（没有排队和正在传的）就清掉传完的，下一批从头数；失败的留着给用户重试
                launch {
                    queue.state.collect { current ->
                        if (current.activeId == null && current.pending == 0 && current.items.any { it.stage == CloudUploadStage.READY }) {
                            queue.clearCompleted()
                        }
                    }
                }
            }
            // 只在 Wi-Fi 下上传的开关一变，后台任务的网络约束跟着换
            launch {
                library.prefs.map { it.uploadWifiOnly }.distinctUntilChanged().drop(1).collect { kick(replace = true) }
            }
            kick()
        }
    }

    suspend fun awaitRestored() = restored.await()

    private suspend fun restore() {
        val rows = dao.all()
        if (rows.isEmpty()) return
        val sources = HashMap<String, Pair<CloudUploadSource, CloudUploadMeta>?>()
        val missing = HashMap<String, Pair<CloudUploadSource, CloudUploadMeta>>()
        rows.map { it.localId }.distinct().forEach { id ->
            val found = library.uploadSource(id)
            sources[id] = found
            if (found == null) missing[id] = library.missingUploadSource(id)
        }
        queues.forEach { (provider, queue) ->
            val (items, pausedBy) = LocalUploadRows.restore(provider, rows, { sources[it] }, { missing.getValue(it) })
            if (items.isNotEmpty()) queue.restore(items, pausedBy)
        }
    }

    fun queueOf(provider: CloudProvider): CloudUploadQueue? = queues[provider]

    /** 有没有该跑的：没暂停的队列里有排队项或正在传的。 */
    fun hasRunnable(): Boolean = queues.values.any { runnable(it.state.value) }

    private fun runnable(state: CloudQueueState): Boolean =
        !state.paused && (state.activeId != null || state.items.any { it.stage == CloudUploadStage.QUEUED })

    /**
     * 后台任务调它：开门，等到没有能跑的再返回。被取消（系统停掉任务）时也走 finally：关门，
     * 正在传的那首排回队首（不算失败、不算暂停），下次任务起来接着传。
     */
    suspend fun runInWorker(onProgress: suspend (LocalUploadState) -> Unit) {
        synchronized(lock) { workerActive = true }
        allowed.value = true
        try {
            coroutineScope {
                val reporter = launch { state.collect { onProgress(it) } }
                combine(queues.values.map { it.state }) { states -> states.any(::runnable) }.first { !it }
                reporter.cancel()
            }
        } finally {
            allowed.value = false
            queues.values.forEach { it.interrupt() }
            synchronized(lock) { workerActive = false }
            // 退出的同时有人入队：那次 kick 看到任务还在、什么都没做，这里补一次
            if (hasRunnable()) kick()
        }
    }

    /** 有能跑的就确保后台任务会起来；任务已经在跑时它自己会接着取下一首。 */
    fun kick(replace: Boolean = false) {
        if (!hasRunnable()) return
        val runner = runner
        if (runner == null) {
            allowed.value = true
            return
        }
        synchronized(lock) { if (workerActive && !replace) return }
        runner.schedule(library.prefs.value.uploadWifiOnly, replace)
    }

    /** 上传面板里的分拣：已经在这个云端的、这台设备上没有文件的、已经排着的都不进队列。 */
    fun plan(ids: List<String>, provider: CloudProvider, force: Boolean = false): UploadPlan {
        val index = library.index.value
        val current = state.value
        return LocalUploadRules.plan(
            ids = ids,
            provider = provider,
            remotes = index.remotes,
            onDevice = { index.byId[it]?.onDevice == true },
            queued = ids.filter { current.isPending(it, provider) }.toSet(),
            force = force,
        )
    }

    /**
     * 开始前的预检：网易云盘按已用 / 总容量和 500 MB 单文件上限；WebDAV 服务器告诉容量时按容量，否则不拦。
     * 失败（没登录、连不上）抛 [CloudException]，面板里直接写原因。
     */
    suspend fun precheck(provider: CloudProvider, ids: List<String>): UploadPrecheck = withContext(Dispatchers.IO) {
        val index = library.index.value
        val candidates = ids.map { CloudUploadCandidate(it, index.byId[it]?.row?.size ?: 0L) }
        when (provider) {
            CloudProvider.NeteaseCloud -> cloud.netease.precheck(candidates)
            CloudProvider.WebDav -> {
                val storage = cloud.webDav() ?: throw notConfigured(provider)
                // 默认的预检把查容量的错误全吞了（不知道容量就不挡）；但密码不对要在面板里就说出来，
                // 不然排进队列马上就因为 401 暂停。其它错误（服务器不报容量、暂时连不上）照旧不挡
                val quota = try {
                    storage.quota()
                } catch (error: CloudException) {
                    if (error.pausesQueue) throw error
                    null
                }
                UploadPrecheck.plan(candidates, quota, null)
            }
            CloudProvider.QqCloud -> throw CloudException(CloudErrorKind.UploadUnsupported, CloudMessages.QQ_UPLOAD_UNSUPPORTED, provider)
        }
    }

    /** 进队列，返回真正新排上的首数。WebDAV 账号从此也算“上传在用”，断开同步时不删。 */
    suspend fun enqueue(provider: CloudProvider, ids: List<String>): Int {
        val queue = queues[provider] ?: return 0
        var added = 0
        for (id in ids) {
            val (source, meta) = library.uploadSource(id) ?: continue
            if (queue.enqueue(source, meta)) added++
        }
        if (added > 0 && provider == CloudProvider.WebDav && !webDavAccounts.usedForStorage) webDavAccounts.setUsedForStorage(true)
        kick()
        return added
    }

    fun cancel(provider: CloudProvider, localId: String) {
        queues[provider]?.cancel(localId)
    }

    fun retry(provider: CloudProvider, localId: String) {
        queues[provider]?.retry(localId)
        kick()
    }

    fun retryAll() {
        queues.values.forEach { it.retryAll() }
        kick()
    }

    /** 用户点“继续”：解除暂停（风控的先退避一段，见 CloudUploadQueue.resume）。 */
    fun resume(provider: CloudProvider) {
        queues[provider]?.resume()
        kick()
    }

    /** 从队列里拿掉（不删云端文件）。 */
    fun remove(provider: CloudProvider, localId: String) {
        queues[provider]?.remove(localId)
    }

    /** 等着的都不传了：排队的和失败的一起拿掉，正在传的也断开。 */
    fun removeAll(provider: CloudProvider) {
        val queue = queues[provider] ?: return
        queue.state.value.items.filter { it.stage != CloudUploadStage.READY }.forEach { queue.remove(it.localId) }
    }

    private fun notConfigured(provider: CloudProvider) =
        CloudException(CloudErrorKind.NotLoggedIn, CloudMessages.notLoggedIn(provider), provider)

    /** 网易云盘：实例只有一个（断点存在里面），直接转过去。 */
    private inner class NeteaseTarget : CloudStorage {
        override val provider: CloudProvider = CloudProvider.NeteaseCloud
        override suspend fun list(cursor: String?): CloudPage = cloud.netease.list(cursor)
        override suspend fun quota(): CloudQuota? = cloud.netease.quota()
        override suspend fun resolvePlayback(ref: CloudRef): CloudStream = cloud.netease.resolvePlayback(ref)
        override suspend fun delete(ref: CloudRef) = cloud.netease.delete(ref)
        override suspend fun upload(
            source: CloudUploadSource,
            meta: CloudUploadMeta,
            progress: (CloudUploadProgress) -> Unit,
        ): CloudRef = cloud.netease.upload(source, meta, progress)
    }

    /**
     * WebDAV：账号随时可能改（换密码、换服务器、删掉），每次用时现取。没配时按“没登录”处理，
     * 队列暂停、写明原因，配好后用户点继续。
     */
    private inner class WebDavTarget : CloudStorage {
        override val provider: CloudProvider = CloudProvider.WebDav
        private fun current(): CloudStorage = cloud.webDav() ?: throw notConfigured(provider)
        override suspend fun list(cursor: String?): CloudPage = current().list(cursor)
        override suspend fun quota(): CloudQuota? = current().quota()
        override suspend fun resolvePlayback(ref: CloudRef): CloudStream = current().resolvePlayback(ref)
        override suspend fun delete(ref: CloudRef) = current().delete(ref)
        override suspend fun upload(
            source: CloudUploadSource,
            meta: CloudUploadMeta,
            progress: (CloudUploadProgress) -> Unit,
        ): CloudRef = current().upload(source, meta, progress)
    }

    private companion object {
        const val TAG = "LocalUploads"
    }
}
