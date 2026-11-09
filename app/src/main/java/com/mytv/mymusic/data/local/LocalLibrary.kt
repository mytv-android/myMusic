package com.mytv.mymusic.data.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.SettingsStore
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.AppLog
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

/** 本地音乐页和徽标用的一首：数据库记录、按设置算好的显示用 Song、匹配到的平台歌。 */
data class LocalItem(
    val row: LocalTrackEntity,
    val song: Song,
    val match: Song?,
    /** 在本地音乐列表里显示（来源开着、没被“跳过 30 秒以下”藏起来）。 */
    val visible: Boolean,
    /** 这台设备上现在能直接读到文件。 */
    val onDevice: Boolean,
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
) {
    val totalBytes: Long get() = items.sumOf { it.row.size }
    val matchedCount: Int get() = items.count { it.row.matched }
    val unmatchedCount: Int get() = items.count { isUnmatched(it) }
    val missingCount: Int get() = items.count { it.row.missing }

    /** 灰色“没匹配”标签：自动匹配试过没成、用户点了不匹配，或者自动匹配关着。还在排队的不算。 */
    fun isUnmatched(item: LocalItem): Boolean = !item.row.matched && (item.row.matchSettled || !autoMatch)

    fun availability(localId: String): LocalAvailability {
        val item = byId[localId] ?: return LocalAvailability.Elsewhere
        return if (item.onDevice) LocalAvailability.OnDevice else LocalAvailability.Missing
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
 */
class LocalLibrary(
    context: Context,
    private val dao: LocalTrackDao,
    private val settings: SettingsStore,
    private val repository: MusicRepository,
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

    val index: StateFlow<LocalIndex> = combine(dao.observeAll(), prefs, permissionTick) { rows, prefs, _ ->
        buildIndex(rows, prefs)
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

    private val platformStep = LocalResolveStep { request ->
        if (!request.prefs.platformFallback) return@LocalResolveStep null
        val match = request.item?.match ?: return@LocalResolveStep null
        LocalPlayable.Platform(match, request.row?.matchConfidence ?: 0f)
    }

    private val resolveSteps = mutableListOf(fileStep, platformStep)

    /** 插一步解析（云端副本），排在本机文件之后、平台版本之前。 */
    fun addResolveStep(step: LocalResolveStep) {
        resolveSteps.add(resolveSteps.indexOf(platformStep).coerceAtLeast(1), step)
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
                                    autoMatch(row)
                                    _matching.update { it.copy(done = it.done + 1) }
                                }
                            }
                        }
                    }
                }
            } finally {
                _matching.value = LocalMatchProgress()
            }
        }
    }

    private suspend fun autoMatch(row: LocalTrackEntity) {
        val outcome = try {
            matcher.matchByTags(row) ?: matcher.matchByAudio(row)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: LocalMatchNetworkException) {
            // 断网：不标“没匹配”，下次启动再试
            return
        } catch (error: Exception) {
            AppLog.w("LocalMusic", "匹配出错 ${row.displayName}：${error.message}")
            null
        }
        update(row.localId) { current ->
            when {
                // 用户在这期间手动处理过，以用户的为准
                current.matchSettled -> current
                outcome == null -> current.copy(matchMethod = LocalMatchMethod.NONE, matchedAt = System.currentTimeMillis())
                else -> current.withMatch(outcome)
            }
        }
    }

    /** 「换一个匹配」里点了「使用这个」。 */
    suspend fun applyMatch(localId: String, candidate: LocalCandidate) {
        update(localId) { it.withMatch(candidate.copy(method = if (candidate.method == LocalMatchMethod.AUDIO) LocalMatchMethod.AUDIO else LocalMatchMethod.MANUAL)) }
    }

    /** 「不匹配」：清掉匹配，回到文件自己的标签和内嵌封面，自动匹配也不再碰它。 */
    suspend fun rejectMatch(localId: String) {
        update(localId) {
            it.copy(
                matchProvider = "",
                matchId = "",
                matchConfidence = 0f,
                matchMethod = LocalMatchMethod.REJECTED,
                matchedAt = System.currentTimeMillis(),
                matchPayload = "",
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    suspend fun search(localId: String, query: String, providerId: String): List<LocalCandidate> {
        val row = dao.get(localId) ?: return emptyList()
        return matcher.search(row, query, providerId)
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
     * 按解析链找这首本地歌能放的那份：本机文件 →（以后：云端副本）→ 匹配到的平台版本。
     * 都不行返回 [LocalPlayable.Unavailable]，原因直接给用户看；歌单条目永远不删。
     */
    suspend fun resolve(song: Song, skipFile: Boolean = false): LocalPlayable {
        val row = dao.get(song.id)
        val item = index.value.byId[song.id]
        val prefs = prefs.value
        val request = LocalResolveRequest(song, row, item, prefs, skipFile)
        for (step in resolveSteps.toList()) {
            step.resolve(request)?.let { return it }
        }
        val latest = dao.get(song.id)
        val reason = when {
            latest == null -> REASON_ELSEWHERE
            latest.origin == LOCAL_ORIGIN_MEDIA && !hasAudioPermission() -> "没有读取音乐的权限"
            item?.onDevice == false && !latest.missing -> "文件所在位置已移出本地音乐"
            skipFile && !latest.missing -> "这台设备放不了这个文件"
            else -> "文件不在了"
        }
        val searchable = LocalTags.hasUsableTags(song.name, song.artist, song.extra[EXTRA_FILE_NAME].orEmpty())
        return LocalPlayable.Unavailable(reason, searchable = searchable && item?.match == null, fallbackAllowed = prefs.platformFallback)
    }

    private fun canOpen(uri: String): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    /**
     * 歌词：「同名 .lrc 优先」开着且找得到就用 .lrc；否则用匹配到的平台的歌词；平台也没有时再退回 .lrc。
     * 这台设备上没扫到过的歌只能按传进来的 Song 里带的匹配信息取平台歌词（现在没有，返回空）。
     */
    suspend fun lyrics(song: Song, cookies: Map<String, String>): String = withContext(Dispatchers.IO) {
        val row = dao.get(song.id) ?: return@withContext ""
        val preferLrc = prefs.value.preferLrc
        if (preferLrc) readSidecar(row)?.let { return@withContext it }
        val match = decodeMatch(row)
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
        if (!preferLrc) readSidecar(row)?.let { return@withContext it }
        ""
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

    private fun buildIndex(rows: List<LocalTrackEntity>, prefs: LocalMusicSettings): LocalIndex {
        val mediaReadable = prefs.mediaStore && hasAudioPermission()
        val folders = prefs.folders.map { it.uri }.toHashSet()
        val items = rows.map { row ->
            val match = decodeMatch(row)
            val originOn = if (row.origin == LOCAL_ORIGIN_MEDIA) prefs.mediaStore else row.origin in folders
            val readable = if (row.origin == LOCAL_ORIGIN_MEDIA) mediaReadable else originOn
            val short = prefs.skipShort && row.durationSec in 1 until SHORT_SECONDS
            LocalItem(
                row = row,
                song = songOf(row, match, prefs.titlePolicy),
                match = match,
                visible = originOn && !short,
                onDevice = readable && !row.missing,
            )
        }
        val collator = Collator.getInstance(Locale.CHINA)
        val visible = items.filter { it.visible }.sortedWith(compareBy(collator) { it.song.name })
        return LocalIndex(visible, items.associateBy { it.localId }, loaded = true, autoMatch = prefs.autoMatch)
    }

    companion object {
        const val EXTRA_FILE_NAME = "local_file"
        const val REASON_ELSEWHERE = "只在原来的设备上，这台设备没有这个文件"
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

/** 存进歌单、收藏、最近播放前去掉只在这台设备上有效的东西：本地地址、本机封面缓存路径。 */
fun Song.forStorage(): Song {
    val stripUrl = url.startsWith("file:") || url.startsWith("content:")
    val stripCover = source == LOCAL_SOURCE && cover.isNotBlank() && !cover.startsWith("http")
    if (!stripUrl && !stripCover) return this
    return copy(url = if (stripUrl) "" else url, cover = if (stripCover) "" else cover)
}
