package com.mytv.mymusic.data.lx

import android.content.Context
import com.mytv.mymusic.AppLog
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.PlayableStream
import com.mytv.mymusic.data.sync.LxSyncStore
import com.mytv.mymusic.data.sync.SyncLxSource
import com.mytv.mymusic.data.sync.SyncTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object LxSources : LxPlayback {
    private data class EngineEntry(val hash: String, val engine: LxScriptEngine)

    /** 同步层接这里；音源脚本随同步文件走，换设备也能直接播。 */
    fun syncStore(): LxSyncStore = SyncBridge

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val engines = ConcurrentHashMap<String, EngineEntry>()
    private val engineLocks = ConcurrentHashMap<String, Mutex>()
    private val importMutex = Mutex()
    private val notices = MutableStateFlow<List<LxUpdateNotice>>(emptyList())
    private val configurationGeneration = AtomicLong(0)
    private val failedLock = Any()
    private val failedScripts = LinkedHashMap<String, MutableSet<String>>()

    @Volatile
    private var store: LxSourceStore? = null
    @Volatile
    private var appContext: Context? = null

    val updateNotices: StateFlow<List<LxUpdateNotice>> = notices.asStateFlow()
    val records: StateFlow<List<LxSourceRecord>> get() = requireStore().records
    override val generation: Long get() = configurationGeneration.get()

    fun attach(context: Context) {
        if (store != null) return
        synchronized(this) {
            if (store == null) {
                appContext = context.applicationContext
                store = LxSourceStore(context.applicationContext)
            }
        }
    }

    override fun supports(providerId: String): Boolean {
        val source = LxPlatform.sourceKey(providerId) ?: return false
        return store?.records?.value.orEmpty().any {
            it.enabled && it.capabilities[source]?.actions?.contains("musicUrl") == true
        }
    }

    override suspend fun resolve(song: Song, quality: String): PlayableStream? =
        resolve(listOf(song), quality)?.stream

    override suspend fun resolve(songs: List<Song>, quality: String): LxPlaybackResult? {
        val sourceStore = store ?: return null
        val failures = synchronized(failedLock) { failedScripts.mapValues { it.value.toSet() } }
        val attempts = orderedLxAttempts(sourceStore.records.value, songs) { songKey, recordId ->
            failures[songKey]?.contains(recordId) == true
        }
        var lastError: Throwable? = null
        for ((record, song) in attempts) {
            try {
                return LxPlaybackResult(song, resolveFrom(record, song, quality).stream)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // 当前脚本无法处理这首歌时，先让它尝试其余匹配歌曲，再进入下一个脚本。
                lastError = error
            }
        }
        if (attempts.isEmpty()) {
            lastFailure = "没有可尝试的脚本：已启用的脚本都不声明支持这个平台，或这首之前已失败过"
        } else {
            lastFailure = "${attempts.first().first.name}：${lastError?.readableMessage().orEmpty().ifBlank { "脚本没有返回播放地址" }}"
        }
        return null
    }

    /** 最近一次洛雪取流失败的原因，换源失败时给用户看的提示用。 */
    @Volatile
    var lastFailure: String = ""

    suspend fun importOnline(url: String): LxSourceRecord {
        val normalized = normalizeUrl(url)
        val script = download(normalized)
        return importScript(script, normalized)
    }

    suspend fun importLocal(script: String, replaceId: String? = null): LxSourceRecord =
        importScript(script, "", replaceId)

    suspend fun update(record: LxSourceRecord): LxSourceRecord {
        require(record.sourceUrl.isNotBlank()) { "本地导入的音源请用“替换”选择新脚本" }
        val script = download(record.sourceUrl)
        return importScript(script, record.sourceUrl, record.id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = importMutex.withLock {
        requireStore().setEnabled(id, enabled)
        if (!enabled) closeEngine(id)
        bumpGeneration()
    }

    suspend fun move(id: String, delta: Int) = importMutex.withLock {
        requireStore().move(id, delta)
        bumpGeneration()
    }

    suspend fun delete(id: String) = importMutex.withLock {
        closeEngine(id)
        engineLocks.remove(id)
        requireStore().delete(id)
        bumpGeneration()
    }

    fun dismissUpdateNotice(sourceId: String) {
        notices.update { current -> current.filterNot { it.sourceId == sourceId } }
    }

    fun reportPlaybackFailure(song: Song) {
        val id = song.extra["lx_source_id"] ?: return
        synchronized(failedLock) {
            val failures = failedScripts.getOrPut(song.key) { mutableSetOf() }
            failures += id
            while (failedScripts.size > 64) failedScripts.remove(failedScripts.keys.first())
        }
    }

    fun clearPlaybackFailures(songKey: String) {
        synchronized(failedLock) { failedScripts.remove(songKey) }
    }

    /** 同步层用：把同步文件里的音源写成 [SyncLxSource]，[position] 保持本机顺序。 */
    private object SyncBridge : LxSyncStore {
        override suspend fun snapshot(): List<SyncLxSource> {
            val sourceStore = store ?: return emptyList()
            val live = sourceStore.records.value.mapIndexedNotNull { position, record ->
                val script = try {
                    sourceStore.readScript(record.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return@mapIndexedNotNull null
                }
                SyncLxSource(
                    id = record.id,
                    name = record.name,
                    description = record.description,
                    version = record.version,
                    author = record.author,
                    homepage = record.homepage,
                    sourceUrl = record.sourceUrl,
                    scriptHash = record.scriptHash,
                    script = script,
                    enabled = record.enabled,
                    position = position,
                    updatedAt = SyncTime.toIso(record.updatedAt),
                )
            }
            val gone = sourceStore.tombstones().map { (id, deletedAt) ->
                SyncLxSource(id = id, deletedAt = SyncTime.toIso(deletedAt))
            }
            return live + gone
        }

        override suspend fun apply(sources: List<SyncLxSource>, removeAbsent: Boolean) {
            val sourceStore = store ?: return
            importMutex.withLock {
                // 云端没有的音源、云端带墓碑的音源：都是「别处已经删了」，用 forget 不产生新的删除记录，
                // 否则本机会把这份删除再传回去
                if (removeAbsent) {
                    val keep = sources.map { it.id }.toSet()
                    sourceStore.records.value.filterNot { it.id in keep }.forEach {
                        closeEngine(it.id)
                        engineLocks.remove(it.id)
                        sourceStore.forget(it.id)
                    }
                }
                sources.filter { it.deletedAt != null }.forEach { gone ->
                    closeEngine(gone.id)
                    engineLocks.remove(gone.id)
                    sourceStore.forget(gone.id)
                }
                sources.filter { it.deletedAt == null && it.id.isNotBlank() }.forEachIndexed { index, source ->
                    val existing = sourceStore.get(source.id)
                    if (existing != null && existing.scriptHash == source.scriptHash) {
                        if (existing.enabled != source.enabled) sourceStore.setEnabled(source.id, source.enabled)
                        return@forEachIndexed
                    }
                    if (source.script.isBlank()) {
                        // 只同步到元数据：本机也没有这份脚本时没法用，跳过
                        if (existing == null) return@forEachIndexed
                        if (existing.enabled != source.enabled) sourceStore.setEnabled(source.id, source.enabled)
                        return@forEachIndexed
                    }
                    require(source.script.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT_BYTES) {
                        "同步文件里的脚本超过 2 MB，已跳过 ${source.name}"
                    }
                    val record = LxSourceRecord(
                        id = source.id,
                        name = source.name.ifBlank { existing?.name.orEmpty() },
                        description = source.description,
                        version = source.version,
                        author = source.author,
                        homepage = source.homepage,
                        sourceUrl = source.sourceUrl,
                        scriptHash = source.scriptHash,
                        enabled = source.enabled,
                        importedAt = existing?.importedAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                    // 能力表不进同步文件：写回后现场跑一遍脚本，换源时才知道它能给哪些平台取流
                    val capabilities = runCatching { probeCapabilities(record, source.script) }
                        .onFailure { error ->
                            AppLog.w("LxSources", "同步来的脚本探测失败：${source.name}: ${error.readableMessage()}")
                        }
                        .getOrDefault(emptyMap())
                    sourceStore.save(record.copy(capabilities = capabilities), source.script)
                    closeEngine(record.id)
                }
                // 顺序按同步文件里的 position 重排
                val order = sources.filter { it.deletedAt == null }.sortedBy { it.position }.map { it.id }
                var current = sourceStore.records.value.map { it.id }
                if (current.filter { it in order } != order.filter { it in current }) {
                    for (id in order) {
                        val from = current.indexOf(id)
                        if (from < 0) continue
                        val to = order.indexOf(id)
                        if (from == to) continue
                        sourceStore.move(id, to - from)
                        current = sourceStore.records.value.map { it.id }
                    }
                }
                // 同步文件里认过的删除（墓碑）之后不必再传；本机还没传出去的（新删的）继续留着
                val acknowledged = sources.filter { it.deletedAt != null }.map { it.id }.toSet()
                if (acknowledged.isNotEmpty()) sourceStore.keepTombstones(acknowledged)
                bumpGeneration()
            }
        }
    }

    /** 设置页的“测试”会额外读取真实媒体，避免把 example.invalid 之类的字符串当成成功。 */
    suspend fun probeStream(stream: PlayableStream): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(stream.url)
            .header("Range", "bytes=0-2047")
            .header("Accept", "*/*")
            .build()
        val probeClient = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        probeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("媒体地址返回 HTTP ${response.code}")
            val type = response.header("Content-Type").orEmpty()
            if (type.contains("text/html", true) || type.contains("json", true)) {
                throw IllegalStateException("媒体地址返回的是 ${type.substringBefore(';')}，不是音频")
            }
            if (response.body.byteStream().read() < 0) throw IllegalStateException("媒体地址返回了空内容")
            "HTTP ${response.code}" + (type.substringBefore(';').takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
        }
    }

    suspend fun resolveFrom(record: LxSourceRecord, song: Song, audioQuality: String = "auto"): LxResolvedStream {
        val source = LxPlatform.sourceKey(song.source)
            ?: throw IllegalArgumentException("这个平台不能使用落雪音源")
        val capability = record.capabilities[source]
            ?: throw IllegalArgumentException("${record.name} 不支持 ${LxPlatform.labels[source] ?: song.source}")
        require("musicUrl" in capability.actions) { "${record.name} 没有声明取流能力" }
        val qualities = if (source == "local") listOf<String?>(null) else LxProtocol.preferredQualities(capability, audioQuality).map { it as String? }
        require(qualities.isNotEmpty()) { "${record.name} 不支持请求的音质" }
        val engine = engineFor(record)
        val errors = mutableListOf<String>()
        for (quality in qualities) {
            try {
                val url = engine.musicUrl(source, quality, song)
                return LxResolvedStream(record, quality.orEmpty(), LxProtocol.stream(url, quality.orEmpty(), record))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                errors += "${quality ?: "默认"}：${error.readableMessage()}"
                if (engine.isClosed) break
            }
        }
        throw IllegalStateException(errors.joinToString("；").take(800).ifBlank { "脚本没有返回播放地址" })
    }

    private suspend fun importScript(script: String, sourceUrl: String, replaceId: String? = null): LxSourceRecord {
        val metadata = LxScriptHeaders.parse(script)
        val hash = script.sha256()
        return importMutex.withLock {
            val sourceStore = requireStore()
            if (replaceId != null) require(sourceStore.get(replaceId) != null) { "音源已删除，停止更新" }
            val existing = if (replaceId != null) {
                sourceStore.get(replaceId)
            } else {
                sourceUrl.takeIf { it.isNotBlank() }?.let(sourceStore::findByUrl)
                    ?: sourceStore.records.value.firstOrNull { it.scriptHash == hash }
            }
            if (existing != null && existing.scriptHash == hash && existing.sourceUrl == sourceUrl && replaceId == null) {
                return@withLock existing
            }
            val now = System.currentTimeMillis()
            val candidate = LxSourceRecord(
                id = existing?.id ?: "lx_${UUID.randomUUID().toString().replace("-", "")}",
                name = metadata.name,
                description = metadata.description,
                version = metadata.version,
                author = metadata.author,
                homepage = metadata.homepage,
                sourceUrl = sourceUrl,
                scriptHash = hash,
                enabled = existing?.enabled ?: true,
                importedAt = existing?.importedAt ?: now,
                updatedAt = now,
            )
            val capabilities = probeCapabilities(candidate, script)
            val saved = candidate.copy(capabilities = capabilities)
            sourceStore.save(saved, script)
            closeEngine(saved.id)
            bumpGeneration()
            saved
        }
    }

    /** 跑一遍脚本拿到它声明支持的平台和音质；导入、替换和同步写回都要用。 */
    private suspend fun probeCapabilities(record: LxSourceRecord, script: String): Map<String, LxCapability> {
        val engine = newEngine(record, script)
        return try {
            engine.start()
        } finally {
            engine.close()
        }
    }

    private suspend fun engineFor(record: LxSourceRecord): LxScriptEngine {
        engines[record.id]?.takeIf { it.hash == record.scriptHash && !it.engine.isClosed }?.let { return it.engine }
        val mutex = engineLocks.getOrPut(record.id) { Mutex() }
        return mutex.withLock {
            engines[record.id]?.takeIf { it.hash == record.scriptHash && !it.engine.isClosed }?.let { return@withLock it.engine }
            val script = requireStore().readScript(record.id)
            val engine = newEngine(record, script)
            try {
                engine.start()
            } catch (error: Throwable) {
                engine.close()
                throw error
            }
            val current = requireStore().get(record.id)
            if (current == null || current.scriptHash != record.scriptHash || (!current.enabled && record.enabled)) {
                engine.close()
                throw IllegalStateException("音源已在初始化期间被更新或关闭")
            }
            engines.put(record.id, EngineEntry(record.scriptHash, engine))?.takeIf { it.engine != engine }?.engine?.close()
            engine
        }
    }

    private fun newEngine(record: LxSourceRecord, script: String): LxScriptEngine = LxScriptEngine(
        appContext = appContext ?: throw IllegalStateException("自定义音源尚未初始化"),
        sourceId = record.id,
        metadata = LxScriptMetadata(record.name, record.description, record.version, record.author, record.homepage),
        rawScript = script,
        client = client,
        onUpdateNotice = { notice ->
            notices.update { current -> current.filterNot { it.sourceId == notice.sourceId } + notice }
        },
    )

    private suspend fun closeEngine(id: String) {
        val lock = engineLocks[id]
        if (lock == null) {
            engines.remove(id)?.engine?.close()
        } else {
            lock.withLock { engines.remove(id)?.engine?.close() }
        }
    }

    private fun bumpGeneration() {
        configurationGeneration.incrementAndGet()
        synchronized(failedLock) { failedScripts.clear() }
    }

    private suspend fun download(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("Accept", "text/javascript,text/plain,*/*").build())
            .execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("下载失败：HTTP ${response.code}")
                val body = response.body
                if (body.contentLength() > MAX_SCRIPT_BYTES) throw IllegalStateException("脚本超过 2 MB，无法导入")
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_SCRIPT_BYTES) throw IllegalStateException("脚本超过 2 MB，无法导入")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                decodeUtf8(bytes)
            }
    }

    private fun normalizeUrl(value: String): String {
        var url = value.trim()
        if (url.isNotBlank() && !url.contains("://")) url = "https://$url"
        require(url.startsWith("https://") || url.startsWith("http://")) { "请输入 HTTP/HTTPS 脚本地址" }
        require(url.length <= 8192) { "脚本地址过长" }
        return url.toHttpUrl().toString()
    }

    private fun requireStore(): LxSourceStore = store ?: throw IllegalStateException("自定义音源尚未初始化")
}

internal fun decodeUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) {
    throw IllegalArgumentException("脚本必须使用 UTF-8 编码")
}

internal fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
