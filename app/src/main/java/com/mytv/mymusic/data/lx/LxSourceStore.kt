package com.mytv.mymusic.data.lx

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

internal const val MAX_LX_SOURCES = 12

/** 元数据与脚本分开落盘，避免把几百 KB 的 JavaScript 塞进首屏设置快照。 */
class LxSourceStore(context: Context) {
    private val directory = File(context.filesDir, "lx-sources")
    private val metadataFile = File(directory, "sources.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val mutex = Mutex()
    private val mutableRecords = MutableStateFlow(readRecords())
    val records: StateFlow<List<LxSourceRecord>> = mutableRecords.asStateFlow()

    fun get(id: String): LxSourceRecord? = mutableRecords.value.firstOrNull { it.id == id }
    fun findByUrl(url: String): LxSourceRecord? = mutableRecords.value.firstOrNull {
        it.sourceUrl.isNotBlank() && it.sourceUrl == url
    }

    suspend fun readScript(id: String): String = withContext(Dispatchers.IO) {
        val record = get(id) ?: throw IllegalArgumentException("音源不存在或已删除")
        val file = scriptFile(id, record.scriptHash)
        val bytes = try {
            AtomicFile(file).openRead().use { it.readBytes() }
        } catch (_: java.io.IOException) {
            throw IllegalArgumentException("音源脚本文件不存在，请重新导入")
        }
        require(bytes.size <= MAX_SCRIPT_BYTES) { "音源脚本文件超过大小限制" }
        decodeUtf8(bytes)
    }

    suspend fun save(record: LxSourceRecord, script: String): LxSourceRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = mutableRecords.value
            require(record.id.isNotBlank() && record.name.isNotBlank()) { "音源信息不完整" }
            require(record.id in current.map { it.id } || current.size < MAX_LX_SOURCES) {
                "最多保存 $MAX_LX_SOURCES 个自定义音源"
            }
            require(script.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT_BYTES) { "脚本超过 2 MB，无法导入" }
            val target = scriptFile(record.id, record.scriptHash)
            atomicWrite(target, script.toByteArray(Charsets.UTF_8))
            val updated = current.indexOfFirst { it.id == record.id }.let { index ->
                if (index >= 0) current.toMutableList().apply { this[index] = record } else current + record
            }
            writeRecords(updated)
            directory.listFiles()?.filter { it.name.startsWith("${record.id}-") && it.name != target.name }
                ?.forEach { it.delete() }
            record
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = update(id) { it.copy(enabled = enabled) }

    suspend fun move(id: String, delta: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = mutableRecords.value.toMutableList()
            val from = current.indexOfFirst { it.id == id }
            if (from < 0) return@withLock
            val to = (from + delta).coerceIn(0, current.lastIndex)
            if (from == to) return@withLock
            val item = current.removeAt(from)
            current.add(to, item)
            writeRecords(current)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = mutableRecords.value
            if (current.none { it.id == id }) return@withLock
            writeRecords(current.filterNot { it.id == id })
            directory.listFiles()?.filter { it.name.startsWith("$id-") }?.forEach { it.delete() }
        }
    }

    private suspend fun update(id: String, transform: (LxSourceRecord) -> LxSourceRecord) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = mutableRecords.value
                val index = current.indexOfFirst { it.id == id }
                if (index < 0) return@withLock
                val updated = current.toMutableList()
                updated[index] = transform(updated[index])
                writeRecords(updated)
            }
        }
    }

    private fun readRecords(): List<LxSourceRecord> = runCatching {
        val text = AtomicFile(metadataFile).openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        json.decodeFromString<List<LxSourceRecord>>(text)
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .take(MAX_LX_SOURCES)
    }.getOrDefault(emptyList())

    private fun writeRecords(records: List<LxSourceRecord>) {
        atomicWrite(metadataFile, json.encodeToString(records).toByteArray(Charsets.UTF_8))
        mutableRecords.value = records
    }

    private fun scriptFile(id: String, hash: String): File {
        require(id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) { "非法音源 ID" }
        require(hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }) { "非法脚本哈希" }
        return File(directory, "$id-$hash.js")
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        directory.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.flush()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }
}
