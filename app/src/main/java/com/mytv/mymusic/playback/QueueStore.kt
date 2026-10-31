package com.mytv.mymusic.playback

import android.content.Context
import com.mytv.mymusic.data.CoverShapeChoice
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// 停在离结尾不到这么多的位置就算放完了
private const val END_SLACK_MS = 1_000L

/** 落盘的播放队列。在线地址会过期，存盘前清掉，续播时再解析；本地文件（file:）原样保留。 */
@Serializable
data class SavedQueue(
    val songs: List<Song> = emptyList(),
    val savedAt: Long = 0L,
)

/** 落盘的播放位置：第几首、听到哪儿、那首的封面形状。和队列分开存，播放中每隔一阵写一次也只有几十个字节。 */
@Serializable
data class SavedCursor(
    val key: String = "",
    val index: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shape: String = "",
    val savedAt: Long = 0L,
) {
    val shapeChoice: CoverShapeChoice?
        get() = CoverShapeChoice.entries.find { it.name == shape }?.takeIf { it != CoverShapeChoice.Random }
}

/** 一次能续播的快照：队列和位置对得上才算数。 */
data class SavedPlayback(val songs: List<Song>, val cursor: SavedCursor) {
    val index: Int get() = cursor.index.coerceIn(0, songs.lastIndex)
    val current: Song get() = songs[index]
}

/**
 * 最后一次的播放队列和位置，存在 files/playback 下。进程被杀后桌面小组件的“继续播放”、
 * 系统媒体控件的继续播放（[androidx.media3.session.MediaSession.Callback.onPlaybackResumption]）都从这里接上。
 */
class QueueStore(context: Context) {
    private val dir = File(context.filesDir, "playback")
    private val queueFile = File(dir, "queue.json")
    private val cursorFile = File(dir, "cursor.json")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // 位置每 10 秒写一次、切歌和暂停也写：两次写同一个临时文件不能交错
    private val lock = Mutex()

    /** 主线程也能调：只看文件在不在，给媒体按键接收器判断要不要拉起前台服务。 */
    fun hasSaved(): Boolean = queueFile.isFile && cursorFile.isFile

    suspend fun load(): SavedPlayback? = withContext(Dispatchers.IO) {
        val queue = read<SavedQueue>(queueFile) ?: return@withContext null
        val cursor = read<SavedCursor>(cursorFile) ?: SavedCursor()
        if (queue.songs.isEmpty()) return@withContext null
        // 队列和位置分两次写，中间被杀可能对不上：按歌找回序号，找不到就从头放
        val index = when {
            queue.songs.getOrNull(cursor.index)?.key == cursor.key -> cursor.index
            else -> queue.songs.indexOfFirst { it.key == cursor.key }
        }
        val fixed = if (index >= 0) cursor.copy(index = index) else SavedCursor(key = queue.songs.first().key)
        // 上次整个队列放完停在最后一首的末尾：从这首开头接上，和播放器放完后再按播放一样
        val ended = fixed.durationMs > 0 && fixed.positionMs >= fixed.durationMs - END_SLACK_MS
        SavedPlayback(queue.songs, if (ended) fixed.copy(positionMs = 0L) else fixed)
    }

    suspend fun saveQueue(songs: List<Song>) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) {
            clear()
            return@withContext
        }
        val text = json.encodeToString(SavedQueue(songs.map(::forDisk), System.currentTimeMillis()))
        lock.withLock { write(queueFile, text) }
    }

    suspend fun saveCursor(cursor: SavedCursor) = withContext(Dispatchers.IO) {
        val text = json.encodeToString(cursor.copy(savedAt = System.currentTimeMillis()))
        lock.withLock { write(cursorFile, text) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            queueFile.delete()
            cursorFile.delete()
        }
    }

    private inline fun <reified T> read(file: File): T? =
        runCatching { if (file.isFile) json.decodeFromString<T>(file.readText()) else null }.getOrNull()

    /** 先写临时文件再改名，写到一半被杀也不会留下半截 JSON。 */
    private fun write(file: File, text: String) {
        runCatching {
            dir.mkdirs()
            val temp = File(dir, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private fun forDisk(song: Song): Song = if (song.url.startsWith("file:")) song else song.copy(url = "")
}
