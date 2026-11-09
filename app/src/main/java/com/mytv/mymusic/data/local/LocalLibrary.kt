package com.mytv.mymusic.data.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.SettingsStore
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.AppLog
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudHub
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudRef
import com.mytv.mymusic.data.cloud.CloudRefStatus
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadSource
import com.mytv.mymusic.data.cloud.skipFully
import com.mytv.mymusic.data.cloud.cloudExtensionOf
import com.mytv.mymusic.data.sync.LocalSyncRefs
import com.mytv.mymusic.data.sync.SyncLocal
import com.mytv.mymusic.data.sync.SyncMatch
import com.mytv.mymusic.data.sync.SyncRefMerge
import com.mytv.mymusic.data.sync.SyncRemote
import com.mytv.mymusic.data.sync.SyncTime
import com.mytv.mymusic.data.sync.SyncTrack
import com.mytv.mymusic.data.sync.SyncTrackRefs
import com.mytv.mymusic.data.sync.isWebUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 本地音乐页和徽标用的一首：数据库记录、按设置算好的显示用 Song、匹配到的平台歌。 */
data class LocalItem(
    val row: LocalTrackEntity,
    val song: Song,
    val match: Song?,
    /** 在本地音乐列表里显示（来源开着、没被“跳过 30 秒以下”藏起来）。 */
    val visible: Boolean,
    /** 这台设备上现在能直接读到文件。 */
    val onDevice: Boolean,
    /** 读不到时的说明：文件不在了 / 没有读取音乐的权限 / 位置已移出本地音乐；读得到为空。 */
    val offReason: String = "",
) {
    val localId: String get() = row.localId
}

data class LocalIndex(
    /** 列表里显示的歌，按标题排好。 */
    val items: List<LocalItem> = emptyList(),
    /** 所有扫到过的歌（含藏起来的），按身份查。 */
    val byId: Map<String, LocalItem> = emptyMap(),
    val loaded: Boolean = false,
    val autoMatch: Boolean = true,
    /** 本地歌的线索（文件所在设备、同步来的匹配），按身份查；这台设备没有文件的歌也在。 */
    val refs: Map<String, LocalRefEntity> = emptyMap(),
    /** 云端副本，按身份查，每个云端一行。 */
    val remotes: Map<String, List<LocalRemoteEntity>> = emptyMap(),
    /** [refs] 里的匹配解成的 Song（同步来的只有 ID 和歌名时是个占位）。 */
    val refMatches: Map<String, Song> = emptyMap(),
    /** 这台设备上配好了的云端（网易登录了、WebDAV 配好了）。 */
    val clouds: Set<CloudProvider> = emptySet(),
    val platformFallback: Boolean = true,
) {
    val totalBytes: Long get() = items.sumOf { it.row.size }
    val matchedCount: Int get() = items.count { it.row.matched }
    val unmatchedCount: Int get() = items.count { isUnmatched(it) }
    val missingCount: Int get() = items.count { it.row.missing }

    /** 已经传到至少一个云端（状态 ready）的歌，卡片的“已上传”和我的页那行用。 */
    val uploadedCount: Int get() = items.count { LocalUploadRules.isUploaded(remotes[it.localId].orEmpty()) }

    /** 灰色“没匹配”标签：自动匹配试过没成、用户点了不匹配，或者自动匹配关着。还在排队的不算。 */
    fun isUnmatched(item: LocalItem): Boolean = !item.row.matched && (item.row.matchSettled || !autoMatch)

    fun availability(localId: String): LocalAvailability {
        val item = byId[localId] ?: return LocalAvailability.Elsewhere
        return if (item.onDevice) LocalAvailability.OnDevice else LocalAvailability.Missing
    }

    /** 匹配到的平台歌：这台设备上的匹配优先，没有再用同步来的；这台设备点过“不匹配”就不用同步来的。 */
    fun matchOf(localId: String): Song? {
        val item = byId[localId]
        item?.match?.let { return it }
        if (item?.row?.matchMethod == LocalMatchMethod.REJECTED) return null
        return refMatches[localId]
    }

    /** 文件所在设备（同步来的）；不知道为空。 */
    fun device(localId: String): String = refs[localId]?.device.orEmpty()

    /** 这首在这台设备上会放哪一份，徽标和点按行为按它来（设计稿第 ⑦⑨ 张）。 */
    fun copyState(localId: String, now: Long = System.currentTimeMillis()): LocalCopyState {
        val item = byId[localId]
        return LocalCopyRules.state(
            onDevice = item?.onDevice == true,
            missingReason = item?.offReason.orEmpty(),
            remotes = remotes[localId].orEmpty(),
            configured = clouds,
            match = matchOf(localId),
            platformFallback = platformFallback,
            device = device(localId),
            now = now,
        )
    }
}

enum class LocalScanPhase { Idle, Listing, Hashing, Tags }

data class LocalScanState(
    val phase: LocalScanPhase = LocalScanPhase.Idle,
    /** 已经登记好的歌（列表里已经能看到的）。 */
    val found: Int = 0,
    /** 这次列出来的文件数，算身份阶段的分母。 */
    val total: Int = 0,
    val done: Int = 0,
    val error: String = "",
) {
    val running: Boolean get() = phase != LocalScanPhase.Idle
}

data class LocalMatchProgress(val done: Int = 0, val total: Int = 0) {
    val running: Boolean get() = total > 0
}

/**
 * 本地音乐库：扫描（媒体库 + 授权文件夹，增量、低优先级、可取消）、标签和封面、自动匹配、
 * 播放解析和歌词。原文件只读，从不修改。
 *
 * 播放侧的接口：[resolve]（按解析链找能放的那份）、[availability]、[lyrics]；界面用 [index]、[scan]、[matching]。
 *
 * 跨设备（同步 v3）：歌单里的本地歌带着三组线索——文件（指纹、文件名、所在设备）、云端副本、匹配。
 * 线索存在 local_refs / local_remotes，[syncRefs] 是同步层取 / 写线索的接口；上传队列传完一首调
 * [recordRemote]，删云端副本调 [deleteRemote]。线索一变就 [onRefsChanged]（接到同步的防抖上传）。
 */
class LocalLibrary(
    context: Context,
    private val dao: LocalTrackDao,
    private val refDao: LocalRefDao,
    private val settings: SettingsStore,
    private val repository: MusicRepository,
    /** 三种云端；为空时（测试、没接云端）云端副本这一步直接跳过。 */
    private val cloud: CloudHub?,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // 扫描单独一个后台优先级线程：算指纹要读文件，不和界面、播放抢 CPU 和 IO
    private val scanDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "local-scan")
    }.asCoroutineDispatcher()

    // 扫描、读标签、匹配都会改同一行；写之前在锁里重读，谁也不覆盖谁刚写的字段
    private val writeLock = Mutex()
    private val permissionTick = MutableStateFlow(0)
    private val matchCache = ConcurrentHashMap<String, Song>()

    val matcher = LocalMatcher(this.context, repository) { settings.state.first() }

    val prefs: StateFlow<LocalMusicSettings> = settings.state.map { it.localMusic }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, LocalMusicSettings())

    /** 线索变了（匹配、云端位置）：MyMusicApp 接到同步的 onLocalChange，防抖后上传。 */
    @Volatile var onRefsChanged: (() -> Unit)? = null

    private val refState = combine(refDao.observeRefs(), refDao.observeRemotes()) { refs, remotes -> refs to remotes }

    val index: StateFlow<LocalIndex> = combine(
        dao.observeAll(),
        refState,
        prefs,
        permissionTick,
        cloud?.configured ?: flowOf(emptySet()),
    ) { rows, (refs, remotes), prefs, _, clouds ->
        buildIndex(rows, refs, remotes, prefs, clouds)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, LocalIndex())

    private val _scan = MutableStateFlow(LocalScanState())
    val scan: StateFlow<LocalScanState> = _scan.asStateFlow()

    private val _matching = MutableStateFlow(LocalMatchProgress())
    val matching: StateFlow<LocalMatchProgress> = _matching.asStateFlow()

    private var scanJob: Job? = null
    private var matchJob: Job? = null
    // 这次进程里自动匹配已经试过（网络失败留着没标）的，不在同一次里反复重试
    private val attempted = ConcurrentHashMap.newKeySet<String>()

    private val fileStep = LocalResolveStep { request ->
        val row = request.row ?: return@LocalResolveStep null
        if (request.item?.onDevice != true) return@LocalResolveStep null
        val readable = withContext(Dispatchers.IO) { canOpen(row.uri) }
        if (!readable) {
            // 文件被删或搬走了：先标出来，下次扫描找到新位置会自动恢复
            markMissing(listOf(row.localId))
            return@LocalResolveStep null
        }
        if (request.skipFile) null else LocalPlayable.File(row.uri, request.item.song)
    }

    private val cloudStep = LocalCloudStep(
        storage = { provider -> cloud?.storage(provider) },
        record = { localId, provider, state -> refDao.setCheck(localId, provider, state, System.currentTimeMillis()) },
    )

    private val platformStep: LocalResolveStep = LocalPlatformStep

    private val resolveSteps = mutableListOf(fileStep, cloudStep, platformStep)

    /** 再插一步解析，排在本机文件、云端副本之后，平台版本之前。 */
    fun addResolveStep(step: LocalResolveStep) {
        resolveSteps.add(resolveSteps.indexOf(platformStep).coerceAtLeast(1), step)
    }

    init {
        // 换了账号、重新登录、改了 WebDAV 密码：这台设备之前记下的“云端文件已删除 / 要重新登录”作废，
        // 不然换回正确的账号后还要等一小时才会再试
        cloud?.let { hub ->
            scope.launch(Dispatchers.IO) {
                var previous: Map<CloudProvider, String>? = null
                hub.accounts.collect { current ->
                    val before = previous
                    previous = current
                    if (before == null) return@collect
                    current.filter { (provider, key) -> before[provider] != key }.keys.forEach { provider ->
                        runCatching { refDao.clearChecks(provider.id) }
                    }
                }
            }
        }
    }

    // ---- 权限和来源 ----

    fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

    /** 权限结果回来后调用：媒体库来源的歌能不能读跟着变，徽标要重算。 */
    fun onPermissionChanged(granted: Boolean) {
        permissionTick.update { it + 1 }
        scope.launch {
            settings.localMusic { it.copy(permissionDenied = !granted) }
            if (granted) scan()
        }
    }

    private fun hasFolderGrant(uri: String): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission }
    }.getOrDefault(false)

    /**
     * 文件夹选择器选中的目录：只接受 tree uri，只要读权限并持久化，下次启动不用再选。
     * 返回 false 表示这不是一个能用的文件夹。
     */
    suspend fun addFolder(treeUri: Uri): Boolean {
        if (!DocumentsContract.isTreeUri(treeUri)) return false
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrElse { error ->
            AppLog.w("LocalMusic", "文件夹授权失败：${error.message}")
            false
        }
        if (!granted) return false
        val name = withContext(Dispatchers.IO) { LocalScanner.folderName(context, treeUri) }
        val value = treeUri.toString()
        settings.localMusic { prefs ->
            if (prefs.folders.any { it.uri == value }) prefs else prefs.copy(folders = prefs.folders + LocalFolder(value, name))
        }
        scan()
        return true
    }

    /** 移出扫描范围：记录留着（匹配结果、歌单都不丢），只是不再显示、也不当作本机有文件。 */
    suspend fun removeFolder(uri: String) {
        settings.localMusic { prefs -> prefs.copy(folders = prefs.folders.filterNot { it.uri == uri }) }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun setMediaStore(enabled: Boolean) {
        settings.localMusic { it.copy(mediaStore = enabled) }
        if (enabled) scan()
    }

    suspend fun setAutoMatch(enabled: Boolean) {
        settings.localMusic { it.copy(autoMatch = enabled) }
        if (enabled) {
            attempted.clear()
            startMatching()
        }
    }

    suspend fun updatePrefs(transform: (LocalMusicSettings) -> LocalMusicSettings) = settings.localMusic(transform)

    /** 界面上的开关、文件夹增删放在应用级作用域里做：离开页面也要把设置写完、把扫描排上。 */
    fun launch(block: suspend LocalLibrary.() -> Unit): Job = scope.launch { block() }

    // ---- 扫描 ----

    /** 有可扫的来源才扫：没给权限、也没加文件夹时什么都不做。 */
    fun canScan(prefs: LocalMusicSettings = this.prefs.value): Boolean =
        (prefs.mediaStore && hasAudioPermission()) || prefs.folders.isNotEmpty()

    /** 启动后稍等再扫一遍（增量的，没变的文件不会再打开），然后补标签、补匹配。 */
    fun startupScan() {
        scope.launch {
            delay(STARTUP_DELAY_MS)
            val prefs = settings.state.first().localMusic
            if (canScan(prefs)) scan() else if (prefs.autoMatch) startMatching()
        }
    }

    /** 打开本地音乐页时：上次扫描超过 10 分钟就再扫一遍。 */
    fun scanIfStale() {
        val last = prefs.value.lastScanAt
        if (System.currentTimeMillis() - last > STALE_MS) scan()
    }

    fun scan() {
        if (scanJob?.isActive == true) return
        if (!canScan()) return
        scanJob = scope.launch(scanDispatcher) {
            try {
                runScan()
                readTags()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                AppLog.w("LocalMusic", "扫描失败：${error.javaClass.simpleName}: ${error.message}")
                _scan.update { it.copy(error = error.message.orEmpty()) }
            } finally {
                _scan.update { LocalScanState(error = it.error) }
            }
            if (prefs.value.autoMatch) startMatching()
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    private suspend fun runScan() = coroutineScope {
        val prefs = settings.state.first().localMusic
        _scan.value = LocalScanState(phase = LocalScanPhase.Listing)
        val files = ArrayList<ScannedFile>()
        val origins = HashSet<String>()
        if (prefs.mediaStore && hasAudioPermission()) {
            runCatching { LocalScanner.queryMediaStore(context, prefs.folders.filter { hasFolderGrant(it.uri) }) }
                .onSuccess {
                    files += it
                    origins += LOCAL_ORIGIN_MEDIA
                }
                .onFailure { AppLog.w("LocalMusic", "媒体库查询失败：${it.message}") }
        }
        for (folder in prefs.folders) {
            ensureActive()
            if (!hasFolderGrant(folder.uri)) {
                AppLog.w("LocalMusic", "文件夹授权已失效：${folder.name}")
                continue
            }
            runCatching { LocalScanner.walkTree(context, folder) { isActive } }
                .onSuccess {
                    files += it
                    origins += folder.uri
                }
                .onFailure { AppLog.w("LocalMusic", "文件夹扫描失败：${folder.name}: ${it.message}") }
        }
        ensureActive()
        val now = System.currentTimeMillis()
        val existing = dao.all()
        val plan = LocalScanDiff.plan(existing, files, now)
        val original = existing.associateBy { it.localId }
        val refreshed = plan.unchanged.filter { original[it.localId] != it }
        if (refreshed.isNotEmpty()) {
            writeLock.withLock {
                // 只回写位置相关的字段，匹配、标签以库里最新的为准
                val current = dao.getMany(refreshed.map { it.localId }).associateBy { it.localId }
                dao.upsert(refreshed.mapNotNull { row ->
                    current[row.localId]?.copy(
                        missing = false,
                        origin = row.origin,
                        lrcUri = row.lrcUri,
                        relativePath = row.relativePath,
                        displayName = row.displayName,
                        dataPath = row.dataPath,
                        updatedAt = now,
                    )
                })
            }
        }
        val seen = HashSet<String>(plan.unchanged.size + plan.toHash.size)
        plan.unchanged.forEach { seen += it.localId }
        _scan.value = LocalScanState(LocalScanPhase.Hashing, found = seen.size, total = files.size, done = plan.unchanged.size)
        val batch = ArrayList<Pair<String, ScannedFile>>()
        suspend fun flush() {
            if (batch.isEmpty()) return
            val stamp = System.currentTimeMillis()
            writeLock.withLock {
                val current = dao.getMany(batch.map { it.first }).associateBy { it.localId }
                dao.upsert(batch.map { (id, file) -> LocalScanDiff.merge(current[id], file, id, stamp) })
            }
            batch.clear()
        }
        for (file in plan.toHash) {
            ensureActive()
            val id = try {
                LocalIdentity.hash(context, Uri.parse(file.uri), file.size)
            } catch (error: Exception) {
                AppLog.w("LocalMusic", "读不了 ${file.displayName}：${error.message}")
                null
            }
            _scan.update { it.copy(done = it.done + 1) }
            // 同一份内容出现在两个位置：留第一个，第二个不登记
            if (id == null || !seen.add(id)) continue
            batch += id to file
            _scan.update { it.copy(found = seen.size) }
            if (batch.size >= WRITE_BATCH) flush()
        }
        flush()
        val gone = LocalScanDiff.vanished(dao.all(), seen, origins)
        if (gone.isNotEmpty()) markMissing(gone)
        settings.localMusic { it.copy(lastScanAt = System.currentTimeMillis()) }
    }

    /** 第二遍：读标签、内嵌封面、码率采样率。一首一首来，可以随时停；读不出来的也记成读过，不反复试。 */
    private suspend fun readTags() = coroutineScope {
        val pending = dao.all().filter { !it.tagsRead && !it.missing }
        if (pending.isEmpty()) return@coroutineScope
        _scan.update { it.copy(phase = LocalScanPhase.Tags, total = pending.size, done = 0) }
        val results = ArrayList<Pair<String, LocalFileTags?>>()
        suspend fun flush() {
            if (results.isEmpty()) return
            val covers = results.associate { (id, tags) ->
                id to (tags?.picture?.let { bytes -> runCatching { LocalMetadata.saveCover(context, id, bytes) }.getOrDefault("") } ?: "")
            }
            writeLock.withLock {
                val current = dao.getMany(results.map { it.first }).associateBy { it.localId }
                val now = System.currentTimeMillis()
                dao.upsert(results.mapNotNull { (id, tags) ->
                    val row = current[id] ?: return@mapNotNull null
                    if (tags == null) return@mapNotNull row.copy(tagsRead = true, updatedAt = now)
                    row.copy(
                        // 标签以文件为准：媒体库没标签时拿文件名顶上的标题在这里被换回空的
                        title = tags.title,
                        artist = tags.artist,
                        album = tags.album,
                        durationSec = if (tags.durationMs > 0) (tags.durationMs / 1000).toInt() else row.durationSec,
                        bitrate = tags.bitrate,
                        sampleRate = tags.sampleRate,
                        bitsPerSample = tags.bitsPerSample,
                        mimeType = row.mimeType.ifBlank { tags.mimeType },
                        coverPath = covers[id].orEmpty(),
                        tagsRead = true,
                        updatedAt = now,
                    )
                })
            }
            results.clear()
        }
        for (row in pending) {
            ensureActive()
            val tags = runCatching { LocalMetadata.read(context, Uri.parse(row.uri)) }
                .onFailure { AppLog.w("LocalMusic", "读不了标签 ${row.displayName}：${it.message}") }
                .getOrNull()
            results += row.localId to tags
            _scan.update { it.copy(done = it.done + 1) }
            if (results.size >= TAG_BATCH) flush()
        }
        flush()
    }

    private suspend fun markMissing(ids: List<String>) {
        if (ids.isEmpty()) return
        writeLock.withLock { ids.chunked(200).forEach { dao.setMissing(it, true, System.currentTimeMillis()) } }
    }

    /** 清理“文件不在了”的记录。歌单里的条目不受影响。 */
    suspend fun removeMissing(): Int = writeLock.withLock { dao.deleteMissing() }

    suspend fun forget(localId: String) = writeLock.withLock { dao.delete(localId) }

    // ---- 匹配 ----

    fun startMatching() {
        if (matchJob?.isActive == true) return
        matchJob = scope.launch(Dispatchers.IO) {
            val semaphore = Semaphore(MATCH_CONCURRENCY)
            try {
                var changed = false
                while (isActive && prefs.value.autoMatch) {
                    val enabled = index.value.byId
                    val pending = dao.all().filter {
                        !it.missing && it.tagsRead && !it.matchSettled && it.localId !in attempted && enabled[it.localId]?.onDevice != false
                    }
                    if (pending.isEmpty()) break
                    _matching.value = LocalMatchProgress(0, pending.size)
                    coroutineScope {
                        pending.forEach { row ->
                            launch {
                                semaphore.withPermit {
                                    if (!prefs.value.autoMatch) return@withPermit
                                    attempted += row.localId
                                    if (autoMatch(row)) changed = true
                                    _matching.update { it.copy(done = it.done + 1) }
                                }
                            }
                        }
                    }
                }
                // 匹配进了歌单里的本地歌时要同步出去；同步按指纹比对，没变的不会上传
                if (changed) notifyRefsChanged()
            } finally {
                _matching.value = LocalMatchProgress()
            }
        }
    }

    /** 返回 true 表示写进了一个匹配。 */
    private suspend fun autoMatch(row: LocalTrackEntity): Boolean {
        val synced = refDao.ref(row.localId)
        // 别的设备上点过「不匹配」：那是用户的决定，这台设备也不再自动匹配，免得自动结果又被同步回去
        if (synced?.rejected == true) {
            update(row.localId) { current ->
                if (current.matchSettled) current else current.copy(
                    matchProvider = "",
                    matchId = "",
                    matchConfidence = 0f,
                    matchMethod = LocalMatchMethod.REJECTED,
                    matchedAt = synced.matchedAt,
                    matchPayload = "",
                    updatedAt = System.currentTimeMillis(),
                )
            }
            return false
        }
        // 别的设备已经匹配过（同步来的）就直接用：省一次搜索 / 识曲，几台设备上也是同一首
        synced?.takeIf { it.matched && it.matchPayload.isNotBlank() }?.let { ref ->
            var adopted = false
            update(row.localId) { current ->
                if (current.matchSettled) current else current.withRefMatch(ref).also { adopted = true }
            }
            if (adopted) return true
        }
        val outcome = try {
            matcher.matchByTags(row) ?: matcher.matchByAudio(row)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: LocalMatchNetworkException) {
            // 断网：不标“没匹配”，下次启动再试
            return false
        } catch (error: Exception) {
            AppLog.w("LocalMusic", "匹配出错 ${row.displayName}：${error.message}")
            null
        }
        var written = false
        update(row.localId) { current ->
            when {
                // 用户在这期间手动处理过，以用户的为准
                current.matchSettled -> current
                outcome == null -> current.copy(matchMethod = LocalMatchMethod.NONE, matchedAt = System.currentTimeMillis())
                else -> current.withMatch(outcome).also { written = true }
            }
        }
        return written
    }

    /**
     * 「换一个匹配」里点了「使用这个」。这台设备有过这个文件就改 local_tracks；
     * 从没有过（别的设备加进歌单的）就记进线索表，随同步回到有文件的那台设备（设计稿第 ⑧ 张）。
     * 用户自己选的记满分：合并时按分数取，手选的总能盖过各设备上的自动匹配。
     */
    suspend fun applyMatch(localId: String, candidate: LocalCandidate) {
        val method = if (candidate.method == LocalMatchMethod.AUDIO) LocalMatchMethod.AUDIO else LocalMatchMethod.MANUAL
        val chosen = candidate.copy(method = method, score = USER_CONFIDENCE)
        if (dao.get(localId) != null) {
            update(localId) { it.withMatch(chosen) }
        } else {
            val now = System.currentTimeMillis()
            writeRef(localId) { ref ->
                ref.copy(
                    matchProvider = chosen.providerId,
                    matchId = chosen.song.id,
                    matchConfidence = chosen.score,
                    matchMethod = chosen.method,
                    matchedAt = now,
                    matchPayload = json.encodeToString(chosen.song.copy(url = "")),
                    updatedAt = now,
                )
            }
        }
        notifyRefsChanged()
    }

    /**
     * 「不匹配」：清掉匹配，回到文件自己的标签和内嵌封面，自动匹配也不再碰它。
     * 这也是用户的决定，要同步出去（同步文件里记成 method=none、满分）：不然别的设备快照里的旧匹配
     * 合并时又会被并回来。这台设备没有文件时记进线索表，效果是各台设备都不再用那个平台版本。
     */
    suspend fun rejectMatch(localId: String) {
        val now = System.currentTimeMillis()
        if (dao.get(localId) != null) {
            update(localId) {
                it.copy(
                    matchProvider = "",
                    matchId = "",
                    matchConfidence = 0f,
                    matchMethod = LocalMatchMethod.REJECTED,
                    matchedAt = now,
                    matchPayload = "",
                    updatedAt = now,
                )
            }
        } else {
            writeRef(localId) { ref ->
                ref.copy(
                    matchProvider = "",
                    matchId = "",
                    matchConfidence = USER_CONFIDENCE,
                    matchMethod = SyncMatch.METHOD_REJECTED,
                    matchedAt = now,
                    matchPayload = "",
                    updatedAt = now,
                )
            }
        }
        notifyRefsChanged()
    }

    /** [song] 是歌单里的那首：这台设备没有文件时拿它的歌名歌手当打分参考。 */
    suspend fun search(localId: String, query: String, providerId: String, song: Song? = null): List<LocalCandidate> {
        val row = dao.get(localId) ?: referenceRow(localId, song) ?: return emptyList()
        return matcher.search(row, query, providerId)
    }

    /** 没有文件的设备上搜平台版本时的打分参考：歌单里的歌名歌手时长 + 同步来的文件名。 */
    private suspend fun referenceRow(localId: String, song: Song?): LocalTrackEntity? {
        val ref = refDao.ref(localId)
        if (song == null && ref == null) return null
        return LocalTrackEntity(
            localId = localId,
            uri = "",
            origin = "",
            displayName = ref?.fileName.orEmpty().ifBlank { song?.extra?.get(EXTRA_FILE_NAME).orEmpty() },
            relativePath = "",
            dataPath = "",
            title = song?.name.orEmpty(),
            artist = song?.artist.orEmpty(),
            album = song?.album.orEmpty(),
            durationSec = song?.duration ?: 0,
            size = ref?.fileSize ?: 0,
            mimeType = "",
            modifiedAt = 0,
            addedAt = 0,
            bitrate = 0,
            sampleRate = 0,
            bitsPerSample = 0,
            tagsRead = true,
            coverPath = "",
            lrcUri = "",
            missing = true,
            matchProvider = ref?.matchProvider.orEmpty(),
            matchId = ref?.matchId.orEmpty(),
            matchConfidence = ref?.matchConfidence ?: 0f,
            matchMethod = ref?.matchMethod.orEmpty(),
            matchedAt = ref?.matchedAt ?: 0,
            matchPayload = ref?.matchPayload.orEmpty(),
            remoteProvider = "",
            remoteId = "",
            remotePlayableId = "",
            remoteStatus = "",
            remoteUpdatedAt = 0,
            updatedAt = 0,
        )
    }

    /** 「再听一次歌识曲」：换一段（第 60 秒起）重新识别。 */
    suspend fun listenAgain(localId: String): List<LocalCandidate> {
        val row = dao.get(localId) ?: return emptyList()
        return matcher.listen(row, LocalMatchRules.SAMPLE_RETRY_START_MS)
    }

    private fun LocalTrackEntity.withMatch(candidate: LocalCandidate): LocalTrackEntity {
        val now = System.currentTimeMillis()
        return copy(
            matchProvider = candidate.providerId,
            matchId = candidate.song.id,
            matchConfidence = candidate.score,
            matchMethod = candidate.method,
            matchedAt = now,
            // 地址有时效，不存；歌词、封面、平台版本兜底只要 id 和元数据
            matchPayload = json.encodeToString(candidate.song.copy(url = "")),
            updatedAt = now,
        )
    }

    /** 同步来的匹配写进这台设备的 local_tracks：匹配时间沿用原来的，往返同步时才不会被当成新匹配。 */
    private fun LocalTrackEntity.withRefMatch(ref: LocalRefEntity): LocalTrackEntity = copy(
        matchProvider = ref.matchProvider,
        matchId = ref.matchId,
        matchConfidence = ref.matchConfidence,
        matchMethod = ref.matchMethod.ifBlank { LocalMatchMethod.MANUAL },
        matchedAt = ref.matchedAt,
        matchPayload = ref.matchPayload,
        updatedAt = System.currentTimeMillis(),
    )

    private suspend fun update(localId: String, transform: (LocalTrackEntity) -> LocalTrackEntity) {
        writeLock.withLock {
            val row = dao.get(localId) ?: return@withLock
            val next = transform(row)
            if (next != row) dao.upsert(listOf(next))
        }
    }

    // ---- 播放和歌词 ----

    fun availability(localId: String): LocalAvailability = index.value.availability(localId)

    /** 这台设备上能直接放的那首（带显示信息），文件不在或从没扫到过返回 null。只查内存，不碰文件。 */
    fun playableItem(localId: String): LocalItem? = index.value.byId[localId]?.takeIf { it.onDevice }

    /**
     * 按解析链找这首本地歌能放的那份：本机文件 → 云端副本（网易云盘 → WebDAV → QQ 云盘）→ 匹配到的平台版本。
     * 都不行返回 [LocalPlayable.Unavailable]，原因直接给用户看（云端那步的原因优先，其次“只在 X 上”）；
     * 歌单条目永远不删，云端的 404 也只记在本机。
     *
     * [skipFile] / [skipCloud]：刚放失败的是本机文件或云端副本时跳过——两者是同一份内容，格式放不了就都放不了。
     */
    suspend fun resolve(song: Song, skipFile: Boolean = false, skipCloud: Boolean = false): LocalPlayable = withContext(Dispatchers.IO) {
        val row = dao.get(song.id)
        val item = index.value.byId[song.id]
        val ref = refDao.ref(song.id)
        val remotes = refDao.remotes(song.id)
        val refMatch = ref?.takeUnless { row?.matchMethod == LocalMatchMethod.REJECTED || it.rejected }?.let(::decodeRefMatch)
        val prefs = prefs.value
        val request = LocalResolveRequest(song, row, item, prefs, skipFile, ref, remotes, refMatch, skipCloud)
        runResolveChain(resolveSteps.toList(), request)?.let { outcome ->
            val note = request.notes.firstOrNull().orEmpty()
            return@withContext if (outcome is LocalPlayable.Platform && note.isNotEmpty()) outcome.copy(note = note) else outcome
        }
        val latest = dao.get(song.id)
        val fallback = when {
            latest == null -> LocalCopyRules.elsewhere(ref?.device.orEmpty())
            latest.origin == LOCAL_ORIGIN_MEDIA && !hasAudioPermission() -> "没有读取音乐的权限"
            item?.onDevice == false && !latest.missing -> "文件所在位置已移出本地音乐"
            skipFile && !latest.missing -> "这台设备放不了这个文件"
            else -> "文件不在了"
        }
        val reason = request.notes.firstOrNull() ?: fallback
        val searchable = LocalTags.hasUsableTags(song.name, song.artist, song.extra[EXTRA_FILE_NAME].orEmpty())
        LocalPlayable.Unavailable(
            reason,
            searchable = searchable && item?.match == null && refMatch == null,
            fallbackAllowed = prefs.platformFallback,
        )
    }

    private fun canOpen(uri: String): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    /**
     * 歌词：「同名 .lrc 优先」开着且找得到就用 .lrc；否则用匹配到的平台的歌词；平台也没有时再退回 .lrc。
     * 这台设备上没扫到过的歌（放的是云端副本或平台版本）用同步来的匹配取平台歌词；
     * 还没有时，网易云盘里的副本用云盘自己匹配到的那首的歌词。WebDAV 没有歌词，只能靠匹配。
     */
    suspend fun lyrics(song: Song, cookies: Map<String, String>): String = withContext(Dispatchers.IO) {
        val row = dao.get(song.id)
        val preferLrc = prefs.value.preferLrc
        if (row != null && preferLrc) readSidecar(row)?.let { return@withContext it }
        val match = row?.let(::decodeMatch)
            ?: refDao.ref(song.id)?.takeUnless { row?.matchMethod == LocalMatchMethod.REJECTED || it.rejected }?.let(::decodeRefMatch)
        if (match != null) {
            val text = try {
                repository.lyrics(match, cookies[match.source].orEmpty())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                ""
            }
            if (text.isNotBlank()) return@withContext text
        }
        if (row != null && !preferLrc) readSidecar(row)?.let { return@withContext it }
        cloudLyrics(song.id)
    }

    /** 网易云盘里的副本：云盘会把上传的文件匹配到曲库里的一首，歌词按那首取。取不到就算了。 */
    private suspend fun cloudLyrics(localId: String): String {
        val hub = cloud ?: return ""
        if (CloudProvider.NeteaseCloud !in hub.configured.value) return ""
        val slot = refDao.remote(localId, CloudProvider.NeteaseCloud.id)?.takeIf { it.ready } ?: return ""
        return try {
            hub.netease.lyrics(slot.toCloudRef())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            ""
        }
    }

    /** 同名 .lrc：文件夹扫描记下的文档 uri；媒体库的歌只在 Android 10 及以下能按路径找到旁边的文件。 */
    fun readSidecar(row: LocalTrackEntity): String? {
        val bytes = runCatching {
            if (row.lrcUri.isNotBlank()) {
                context.contentResolver.openInputStream(Uri.parse(row.lrcUri))?.use { it.readBytesLimited() }
            } else if (row.dataPath.isNotBlank()) {
                val audio = File(row.dataPath)
                val siblings = audio.parentFile?.list()?.toList().orEmpty()
                LocalLrc.findSidecar(audio.name, siblings)?.let { File(audio.parentFile, it).inputStream().use { input -> input.readBytesLimited() } }
            } else {
                null
            }
        }.getOrNull() ?: return null
        val text = LocalLrc.decode(bytes)
        return text.takeIf { LocalLrc.looksLikeLrc(it) }
    }

    /** 歌曲信息里“歌词”那一行要说清用的是哪份。 */
    fun hasSidecar(row: LocalTrackEntity): Boolean = row.lrcUri.isNotBlank() ||
        (row.dataPath.isNotBlank() && runCatching {
            val audio = File(row.dataPath)
            LocalLrc.findSidecar(audio.name, audio.parentFile?.list()?.toList().orEmpty()) != null
        }.getOrDefault(false))

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < LocalLrc.MAX_BYTES) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    // ---- 显示 ----

    fun decodeMatch(row: LocalTrackEntity): Song? {
        if (row.matchPayload.isBlank() || !row.matched) return null
        matchCache[row.matchPayload]?.let { return it }
        val song = runCatching { json.decodeFromString<Song>(row.matchPayload) }.getOrNull() ?: return null
        if (matchCache.size > 4096) matchCache.clear()
        matchCache[row.matchPayload] = song
        return song
    }

    fun decodeRefMatch(ref: LocalRefEntity): Song? {
        if (ref.matchPayload.isBlank() || !ref.matched) return null
        matchCache[ref.matchPayload]?.let { return it }
        val song = runCatching { json.decodeFromString<Song>(ref.matchPayload) }.getOrNull() ?: return null
        if (matchCache.size > 4096) matchCache.clear()
        matchCache[ref.matchPayload] = song
        return song
    }

    private fun buildIndex(
        rows: List<LocalTrackEntity>,
        refs: List<LocalRefEntity>,
        remotes: List<LocalRemoteEntity>,
        prefs: LocalMusicSettings,
        clouds: Set<CloudProvider>,
    ): LocalIndex {
        val mediaReadable = prefs.mediaStore && hasAudioPermission()
        val folders = prefs.folders.map { it.uri }.toHashSet()
        val items = rows.map { row ->
            val match = decodeMatch(row)
            val originOn = if (row.origin == LOCAL_ORIGIN_MEDIA) prefs.mediaStore else row.origin in folders
            val readable = if (row.origin == LOCAL_ORIGIN_MEDIA) mediaReadable else originOn
            val short = prefs.skipShort && row.durationSec in 1 until SHORT_SECONDS
            val onDevice = readable && !row.missing
            LocalItem(
                row = row,
                song = songOf(row, match, prefs.titlePolicy),
                match = match,
                visible = originOn && !short,
                onDevice = onDevice,
                offReason = when {
                    onDevice -> ""
                    row.missing -> "文件不在了"
                    row.origin == LOCAL_ORIGIN_MEDIA && prefs.mediaStore -> "没有读取音乐的权限"
                    else -> "文件所在位置已移出本地音乐"
                },
            )
        }
        val collator = Collator.getInstance(Locale.CHINA)
        val visible = items.filter { it.visible }.sortedWith(compareBy(collator) { it.song.name })
        val refMap = refs.associateBy { it.localId }
        return LocalIndex(
            items = visible,
            byId = items.associateBy { it.localId },
            loaded = true,
            autoMatch = prefs.autoMatch,
            refs = refMap,
            remotes = remotes.groupBy { it.localId },
            refMatches = refs.mapNotNull { ref -> decodeRefMatch(ref)?.let { ref.localId to it } }.toMap(),
            clouds = clouds,
            platformFallback = prefs.platformFallback,
        )
    }

    // ---- 跨设备线索（同步 v3） ----

    /**
     * 这台设备的名字，写进同步文件的 local.device，别的设备上显示“只在 Pixel 8 上”。
     * 用户在系统设置里改过的设备名优先（Android 7.1 起有），没有就用型号。
     */
    val deviceName: String by lazy {
        val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        } else {
            null
        }
        named?.trim()?.takeIf { it.isNotBlank() } ?: modelName()
    }

    private fun modelName(): String {
        val model = Build.MODEL.orEmpty().trim()
        val maker = Build.MANUFACTURER.orEmpty().trim()
        return when {
            model.isBlank() -> maker.ifBlank { "另一台设备" }
            maker.isBlank() || model.startsWith(maker, ignoreCase = true) -> model
            else -> "${maker.replaceFirstChar { it.uppercase() }} $model"
        }
    }

    private fun notifyRefsChanged() {
        onRefsChanged?.invoke()
    }

    // ---- 上传 ----

    /**
     * 上传用的文件和歌曲信息：这台设备上扫到过、现在打得开的才有，否则 null（传不了）。
     * content:// 每次现开流；版本写“大小:修改时间”，文件被替换后网易的断点不会接到旧文件上。
     * 歌名、歌手按界面上显示的（可能是匹配结果），云盘里看到的和这里一致。
     */
    suspend fun uploadSource(localId: String): Pair<CloudUploadSource, CloudUploadMeta>? = withContext(Dispatchers.IO) {
        val row = dao.get(localId) ?: return@withContext null
        if (row.missing || !canOpen(row.uri)) return@withContext null
        val shown = index.value.byId[localId]?.song
        val source = ContentUploadSource(context, row.uri, row.displayName, row.size, "${row.size}:${row.modifiedAt}")
        source to CloudUploadMeta(
            localId = localId,
            title = shown?.name.orEmpty().ifBlank { row.title },
            artist = shown?.artist.orEmpty().ifBlank { row.artist },
            album = shown?.album.orEmpty().ifBlank { row.album },
            ext = row.extension,
            // 已经匹配到网易云的那首：上传完顺手在云盘里关联上，云盘里也有封面和歌词
            neteaseMatchId = if (row.matched && row.matchProvider == "netease") row.matchId else "",
        )
    }

    /**
     * 队列里放回来、但这台设备上已经找不到文件的那项要个占位：大小按线索表里记的，打开就报错。
     * 它会直接记成失败，从不真的被读。
     */
    suspend fun missingUploadSource(localId: String): Pair<CloudUploadSource, CloudUploadMeta> = withContext(Dispatchers.IO) {
        val row = dao.get(localId)
        val ref = refDao.ref(localId)
        val name = row?.displayName ?: ref?.fileName.orEmpty().ifBlank { localId.substringAfter(':').take(12) }
        val size = row?.size ?: ref?.fileSize ?: 0L
        val source = object : CloudUploadSource {
            override val fileName: String = name
            override val size: Long = size
            override fun open(offset: Long): java.io.InputStream = throw java.io.IOException(LocalUploadRows.FILE_GONE)
        }
        source to CloudUploadMeta(localId = localId, title = row?.title.orEmpty(), artist = row?.artist.orEmpty())
    }

    /** 本地文件当上传源：content:// 和 file:// 都走 ContentResolver（SAF 文件夹里的歌也是 content://）。 */
    private class ContentUploadSource(
        private val context: Context,
        private val uri: String,
        override val fileName: String,
        override val size: Long,
        override val version: String,
    ) : CloudUploadSource {
        override fun open(offset: Long): java.io.InputStream {
            val stream = context.contentResolver.openInputStream(Uri.parse(uri))
                ?: throw java.io.IOException("打不开文件")
            return stream.also { it.skipFully(offset) }
        }
    }

    private val refLock = Mutex()

    /** 改一行线索（没有就新建一行），在锁里读改写，免得同步写回和用户操作互相覆盖。 */
    private suspend fun writeRef(localId: String, transform: (LocalRefEntity) -> LocalRefEntity) {
        refLock.withLock {
            val current = refDao.ref(localId) ?: LocalRefEntity(localId = localId)
            val next = transform(current)
            if (next != current) refDao.upsertRefs(listOf(next))
        }
    }

    /**
     * 上传队列传完一首后调用（以后的上传面板 ⑥ 用）：记下云端位置，标 ready，然后触发同步。
     * 同一个云端再传一次就覆盖那一行；[ref] 里没写上传时间的按现在算。
     * 文件名、大小、设备名一并补进线索，别的设备上“只在 X 上”和歌曲信息里要用。
     */
    suspend fun recordRemote(localId: String, ref: CloudRef) {
        val now = System.currentTimeMillis()
        val stamped = ref.copy(
            localId = localId,
            uploadedAt = ref.uploadedAt.ifBlank { SyncTime.toIso(now) },
        )
        refLock.withLock { refDao.upsertRemotes(listOf(LocalRemoteEntity.of(localId, stamped, now))) }
        val row = dao.get(localId)
        if (row != null) {
            writeRef(localId) { current ->
                current.copy(
                    fileName = current.fileName.ifBlank { row.displayName },
                    fileSize = if (current.fileSize > 0) current.fileSize else row.size,
                    device = current.device.ifBlank { deviceName },
                    updatedAt = now,
                )
            }
        }
        notifyRefsChanged()
    }

    /**
     * 云端副本删掉了（这台设备上删的，或在云盘里删了、用户确认）：状态改成 deleted，上传时间不变——
     * 合并时同一次上传里 deleted 胜出，别的设备也就不再去取；重新上传会带新的时间，自然盖过删除。
     * 不碰本机文件，不动歌单。
     */
    suspend fun markRemoteDeleted(localId: String, provider: CloudProvider) {
        val changed = refLock.withLock {
            val slot = refDao.remote(localId, provider.id) ?: return@withLock false
            if (slot.status == CloudRefStatus.DELETED) return@withLock false
            refDao.upsertRemotes(
                listOf(slot.copy(status = CloudRefStatus.DELETED, checkState = LocalCloudCheck.OK, checkedAt = 0, updatedAt = System.currentTimeMillis())),
            )
            true
        }
        if (changed) notifyRefsChanged()
    }

    /**
     * 「从网易云盘删除 / 从 WebDAV 删除」：先删云端文件（本来就不在也算成功），再把位置标成 deleted。
     * 失败抛 [CloudException]，message 直接给用户看。
     */
    suspend fun deleteRemote(localId: String, provider: CloudProvider) = withContext(Dispatchers.IO) {
        val slot = refDao.remote(localId, provider.id) ?: return@withContext
        val storage = cloud?.storage(provider)
            ?: throw CloudException(com.mytv.mymusic.data.cloud.CloudErrorKind.NotLoggedIn, CloudMessages.notLoggedIn(provider), provider)
        storage.delete(slot.toCloudRef())
        markRemoteDeleted(localId, provider)
    }

    /** 同步层的接口：建快照时取线索、合并完写回。 */
    val syncRefs: LocalSyncRefs = object : LocalSyncRefs {
        override suspend fun snapshotRefs(keys: Set<String>): Map<String, SyncTrackRefs> = buildSyncRefs(keys)
        override suspend fun applyRefs(tracks: Collection<SyncTrack>) = applySyncedRefs(tracks)
    }

    /**
     * 建快照：每首本地歌的三组线索，都是确定的（同样的库得出同样的结果），同步后再建一次和合并结果一致，
     * 不会每次同步都当成有变化：
     * - local：线索表里有的字段优先（多半是同步来的、全局一致的那份），没有再用这台设备自己的文件；
     * - match：这台设备的匹配和同步来的匹配按合并规则挑一个；
     * - remote：各个云端里上传时间最新的那份（同步文件里一首只记一个云端位置）。
     * 不放路径、地址、账号；封面只放匹配到的平台封面网址。
     */
    private suspend fun buildSyncRefs(keys: Set<String>): Map<String, SyncTrackRefs> = withContext(Dispatchers.IO) {
        val prefix = "$LOCAL_SOURCE:"
        val ids = keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.distinct()
        if (ids.isEmpty()) return@withContext emptyMap()
        val rows = HashMap<String, LocalTrackEntity>()
        val refs = HashMap<String, LocalRefEntity>()
        val remotes = HashMap<String, MutableList<LocalRemoteEntity>>()
        ids.chunked(QUERY_CHUNK).forEach { chunk ->
            dao.getMany(chunk).forEach { rows[it.localId] = it }
            refDao.refs(chunk).forEach { refs[it.localId] = it }
            refDao.remotesOf(chunk).forEach { remotes.getOrPut(it.localId) { mutableListOf() } += it }
        }
        val own = deviceName
        val out = LinkedHashMap<String, SyncTrackRefs>()
        for (id in ids) {
            val row = rows[id]
            val ref = refs[id]
            val slots = remotes[id].orEmpty()
            if (row == null && ref == null && slots.isEmpty()) continue
            val local = SyncLocal(
                hash = id,
                fileName = ref?.fileName.orEmpty().ifBlank { row?.displayName.orEmpty() },
                fileSize = ref?.fileSize?.takeIf { it > 0 } ?: row?.size ?: 0,
                // 只有这台设备真有过这个文件才写自己的名字
                device = ref?.device.orEmpty().ifBlank { if (row != null && !row.missing) own else "" },
            )
            val rowMatch = row?.let(::rowSyncMatch)
            val match = SyncRefMerge.match(rowMatch, ref?.syncMatch())
            val remote = slots.sortedBy { it.provider }.mapNotNull { it.toSync() }
                .fold(null as SyncRemote?) { best, next -> SyncRefMerge.remote(best, next) }
            val matchSong = when {
                match == null || match.rejected -> null
                rowMatch == match -> row?.let(::decodeMatch)
                else -> ref?.let(::decodeRefMatch)
            }
            out["$prefix$id"] = SyncTrackRefs(
                local = local,
                remote = remote,
                match = match,
                cover = matchSong?.cover?.takeIf(::isWebUrl).orEmpty(),
            )
        }
        out
    }

    private fun syncMatchOf(row: LocalTrackEntity): SyncMatch = SyncMatch(
        provider = row.matchProvider,
        id = row.matchId,
        confidence = row.matchConfidence,
        method = row.matchMethod,
        matchedAt = isoOrEmpty(row.matchedAt),
    )

    /** 这台设备上的匹配写进同步文件的样子：匹配上的照写；点过「不匹配」的写成拒绝；自动没匹配上的不写。 */
    private fun rowSyncMatch(row: LocalTrackEntity): SyncMatch? = when {
        row.matchMethod == LocalMatchMethod.REJECTED -> SyncMatch.rejection(isoOrEmpty(row.matchedAt))
        row.matched -> syncMatchOf(row)
        else -> null
    }

    /**
     * 合并后的线索写回：线索表按同样的合并规则并进来（同步来的一份当作“另一边”），云端位置写到对应云端那一行；
     * 这台设备有文件、而同步来的匹配更好时，也写进 local_tracks（封面、歌词跟着换）——
     * 用户在这台设备上点过“不匹配”、且比同步来的匹配更晚，就不动。
     */
    private suspend fun applySyncedRefs(tracks: Collection<SyncTrack>) = withContext(Dispatchers.IO) {
        val prefix = "$LOCAL_SOURCE:"
        val incoming = tracks.filter { it.key.startsWith(prefix) && it.hasRefs }.associateBy { it.key.removePrefix(prefix) }
        if (incoming.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val adopted = ArrayList<String>()
        val stubbed = LinkedHashSet<String>()
        incoming.keys.chunked(QUERY_CHUNK).forEach { chunk ->
            refLock.withLock {
                val refs = refDao.refs(chunk).associateBy { it.localId }
                val slots = refDao.remotesOf(chunk).groupBy { it.localId }
                val refRows = ArrayList<LocalRefEntity>()
                val remoteRows = ArrayList<LocalRemoteEntity>()
                for (id in chunk) {
                    val track = incoming.getValue(id)
                    val current = refs[id] ?: LocalRefEntity(localId = id)
                    val next = mergeRef(current, track, now)
                    if (next != current) {
                        refRows += next
                        if (next.matchPayload != current.matchPayload && isSyncedStub(next.matchPayload)) stubbed += id
                    }
                    val remote = track.remote ?: continue
                    if (CloudProvider.of(remote.provider) == null) continue
                    val slot = slots[id].orEmpty().firstOrNull { it.provider == remote.provider }
                    mergeRemote(id, slot, remote, track, now)?.let { remoteRows += it }
                }
                if (refRows.isNotEmpty()) refDao.upsertRefs(refRows)
                if (remoteRows.isNotEmpty()) refDao.upsertRemotes(remoteRows)
            }
            writeLock.withLock {
                val rows = dao.getMany(chunk)
                val changed = rows.mapNotNull { row ->
                    val match = incoming.getValue(row.localId).match ?: return@mapNotNull null
                    adoptMatch(row, match, incoming.getValue(row.localId), now)
                }
                if (changed.isNotEmpty()) {
                    dao.upsert(changed)
                    adopted += changed.map { it.localId }
                    changed.filter { isSyncedStub(it.matchPayload) }.forEach { stubbed += it.localId }
                }
            }
        }
        if (adopted.isNotEmpty()) AppLog.i("LocalMusic", "采用同步来的匹配 ${adopted.size} 首")
        refreshSyncedMatches(stubbed)
    }

    private fun isSyncedStub(payload: String): Boolean = payload.contains(EXTRA_SYNCED_MATCH)

    /**
     * 同步来的匹配只是占位（只有平台和 ID）。后台按这首的标签 / 文件名在那个平台搜一次，搜到同一个 ID
     * 就换成完整信息（平台上的歌名、歌手、专辑、封面）：歌曲信息里写的、别的设备上放平台版本时显示的
     * 才是那首歌。只是只读搜索；搜不到或断网就留着占位，不影响按 ID 播放和取歌词。
     */
    private fun refreshSyncedMatches(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val pending = ids.toList()
        scope.launch(Dispatchers.IO) {
            for (id in pending) {
                try {
                    refreshSyncedMatch(id)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    AppLog.w("LocalMusic", "补全同步来的匹配失败：${error.message}")
                }
            }
        }
    }

    private suspend fun refreshSyncedMatch(localId: String) {
        val row = dao.get(localId)
        val rowStub = row?.let(::decodeMatch)?.takeIf { isSyncedStub(row.matchPayload) }
        val ref = refDao.ref(localId)
        val refStub = ref?.let(::decodeRefMatch)?.takeIf { isSyncedStub(ref.matchPayload) }
        val stub = rowStub ?: refStub ?: return
        val reference = row ?: referenceRow(localId, stub) ?: return
        val query = LocalTags.searchQuery(reference.title, reference.artist, reference.displayName)
        val found = matcher.search(reference, query, stub.source).firstOrNull { it.song.id == stub.id }?.song ?: return
        val full = json.encodeToString(found.copy(url = ""))
        // 搜索期间用户可能又换了匹配：只替换还是同一首、还是占位的那份
        if (rowStub != null) {
            update(localId) { current ->
                if (current.matchProvider == stub.source && current.matchId == stub.id && isSyncedStub(current.matchPayload)) {
                    current.copy(matchPayload = full)
                } else {
                    current
                }
            }
        }
        if (refStub != null) {
            writeRef(localId) { current ->
                if (current.matchProvider == stub.source && current.matchId == stub.id && isSyncedStub(current.matchPayload)) {
                    current.copy(matchPayload = full)
                } else {
                    current
                }
            }
        }
    }

    private fun mergeRef(current: LocalRefEntity, track: SyncTrack, now: Long): LocalRefEntity {
        val local = SyncRefMerge.local(current.syncLocal().takeUnless { it.isEmpty || it == SyncLocal(hash = current.localId) }, track.local)
        val mine = current.syncMatch()
        val match = SyncRefMerge.match(mine, track.match)
        var next = current.copy(
            fileName = local?.fileName.orEmpty().ifBlank { current.fileName },
            fileSize = local?.fileSize?.takeIf { it > 0 } ?: current.fileSize,
            device = local?.device.orEmpty().ifBlank { current.device },
        )
        if (match != null && match != mine && match.rejected) {
            next = next.copy(
                matchProvider = "",
                matchId = "",
                matchConfidence = match.confidence,
                matchMethod = SyncMatch.METHOD_REJECTED,
                matchedAt = SyncTime.toMillis(match.matchedAt),
                matchPayload = "",
            )
        } else if (match != null && match != mine) {
            val sameSong = match.provider == current.matchProvider && match.id == current.matchId
            next = next.copy(
                matchProvider = match.provider,
                matchId = match.id,
                matchConfidence = match.confidence,
                matchMethod = match.method,
                matchedAt = SyncTime.toMillis(match.matchedAt),
                // 同一首只是分数 / 时间变了就留着已有的完整信息；换了一首先放个占位（ID + 歌名 + 封面网址）
                matchPayload = if (sameSong && current.matchPayload.isNotBlank()) current.matchPayload else stubPayload(match, track),
            )
        }
        return if (next == current) current else next.copy(updatedAt = now)
    }

    /**
     * 同步来的匹配只有平台和 ID：拿这首的歌名歌手时长、匹配封面拼一首占位，平台版本按 ID 现取地址。
     * 歌名其实是歌单里的那个（常常就是文件名），不是平台上那首的名字，所以打上 [EXTRA_SYNCED_MATCH]，
     * 歌曲信息里就不把它当平台歌名写出来。
     */
    private fun stubPayload(match: SyncMatch, track: SyncTrack): String = json.encodeToString(
        Song(
            id = match.id,
            name = track.title,
            artist = track.artist,
            album = track.album,
            duration = track.durationSec,
            source = match.provider,
            cover = track.cover.takeIf(::isWebUrl).orEmpty(),
            extra = mapOf(EXTRA_SYNCED_MATCH to "1"),
        ),
    )

    private fun mergeRemote(localId: String, slot: LocalRemoteEntity?, remote: SyncRemote, track: SyncTrack, now: Long): LocalRemoteEntity? {
        val mine = slot?.toSync()
        val winner = SyncRefMerge.remote(mine, remote)
        if (winner == mine || winner == null) return null
        val sameFile = slot != null && slot.remoteId == winner.id
        val fileName = track.local?.fileName.orEmpty()
        return LocalRemoteEntity(
            localId = localId,
            provider = winner.provider,
            remoteId = winner.id,
            playableId = winner.playableId,
            fileName = if (sameFile) slot!!.fileName.ifBlank { fileName } else fileName,
            fileSize = if (sameFile && slot!!.fileSize > 0) slot.fileSize else track.local?.fileSize ?: 0,
            status = winner.status,
            uploadedAt = winner.uploadedAt,
            ext = if (sameFile) slot!!.ext else cloudExtensionOf(winner.id).ifBlank { cloudExtensionOf(fileName) },
            md5 = if (sameFile) slot!!.md5 else "",
            cloudSongId = if (sameFile) slot!!.cloudSongId else "",
            // 换了位置或状态，这台设备之前的取流结果就不作数了
            checkState = if (sameFile && slot!!.status == winner.status) slot.checkState else LocalCloudCheck.OK,
            checkedAt = if (sameFile && slot!!.status == winner.status) slot.checkedAt else 0,
            updatedAt = now,
        )
    }

    private fun adoptMatch(row: LocalTrackEntity, match: SyncMatch, track: SyncTrack, now: Long): LocalTrackEntity? {
        // 这台设备点过「不匹配」也是一个决定，按同样的规则和同步来的比：更新的手选能盖过它，自动匹配不能
        val mine = rowSyncMatch(row)
        if (SyncRefMerge.match(mine, match) != match || mine == match) return null
        val at = SyncTime.toMillis(match.matchedAt)
        if (match.rejected) {
            return row.copy(
                matchProvider = "",
                matchId = "",
                matchConfidence = 0f,
                matchMethod = LocalMatchMethod.REJECTED,
                matchedAt = at,
                matchPayload = "",
                updatedAt = now,
            )
        }
        val sameSong = match.provider == row.matchProvider && match.id == row.matchId
        return row.copy(
            matchProvider = match.provider,
            matchId = match.id,
            matchConfidence = match.confidence,
            matchMethod = match.method.ifBlank { LocalMatchMethod.MANUAL },
            matchedAt = at,
            matchPayload = if (sameSong && row.matchPayload.isNotBlank()) row.matchPayload else stubPayload(match, track),
            updatedAt = now,
        )
    }

    companion object {
        const val EXTRA_FILE_NAME = "local_file"
        /** 匹配是别的设备同步来的占位（只知道平台和 ID，不知道平台上的歌名）。 */
        const val EXTRA_SYNCED_MATCH = "synced_match"
        const val REASON_ELSEWHERE = "只在原来的设备上，这台设备没有这个文件"
        /** 用户在「换一个匹配」里选的那首记满分，合并时总能盖过自动匹配。 */
        const val USER_CONFIDENCE = 1f
        private const val QUERY_CHUNK = 500
        private const val SHORT_SECONDS = 30
        private const val WRITE_BATCH = 25
        private const val TAG_BATCH = 10
        private const val MATCH_CONCURRENCY = 2
        private const val STARTUP_DELAY_MS = 5_000L
        private const val STALE_MS = 10 * 60_000L

        /** 列表、播放器、歌单里用的那首：source=local、key=local:<身份>，地址播放时才补。 */
        fun songOf(row: LocalTrackEntity, match: Song?, policy: LocalTitlePolicy): Song {
            val display = LocalTags.display(row.title, row.artist, row.album, row.displayName, match, policy)
            return Song(
                id = row.localId,
                name = display.title,
                artist = display.artist,
                album = display.album,
                duration = row.durationSec,
                size = row.size,
                bitrate = row.bitrate / 1000,
                source = LOCAL_SOURCE,
                ext = row.extension,
                // 内嵌封面优先，没有再用匹配到的平台封面
                cover = row.coverPath.ifBlank { match?.cover.orEmpty() },
                extra = mapOf(EXTRA_FILE_NAME to row.displayName),
            )
        }
    }
}

/**
 * 存进歌单、收藏、最近播放前去掉只在这台设备上有效的东西：本地地址、本机封面缓存路径。
 * 本地歌的地址一律不存：放的可能是云端副本的临时地址（网易云盘签名地址、WebDAV 地址），
 * 下次播放按解析链重新找，不能让它跟着歌单进同步文件。
 */
fun Song.forStorage(): Song {
    val stripUrl = url.isNotEmpty() && (source == LOCAL_SOURCE || url.startsWith("file:") || url.startsWith("content:"))
    val stripCover = source == LOCAL_SOURCE && cover.isNotBlank() && !cover.startsWith("http")
    if (!stripUrl && !stripCover) return this
    return copy(url = if (stripUrl) "" else url, cover = if (stripCover) "" else cover)
}
